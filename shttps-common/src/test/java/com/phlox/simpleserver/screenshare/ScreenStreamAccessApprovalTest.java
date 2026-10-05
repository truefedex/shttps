package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.websocket.WebSocketOptions;
import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.remotecontrol.RemoteInputCommand;
import com.phlox.simpleserver.remotecontrol.RemoteInputTarget;
import com.phlox.simpleserver.utils.Holder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The person at the device letting a screen viewer in: that nothing of the screen reaches a client
 * before they have, what each answer does to the connection, that a question nobody can answer
 * any more goes away, and which reconnects are let in again without asking.
 * <p>
 * Answers are carried out inline here, and the clock is a fake one, so everything but the pump's
 * own sending thread is deterministic.
 */
public class ScreenStreamAccessApprovalTest {

    /** Records what it was asked, and lets the test answer. */
    private static final class RecordingApprover implements ScreenAccessApprover {
        final List<ScreenAccessRequest> requested = new CopyOnWriteArrayList<>();
        final List<ScreenAccessRequest> finished = new CopyOnWriteArrayList<>();

        @Override
        public void onAccessRequested(@NotNull ScreenAccessRequest request) {
            requested.add(request);
        }

        @Override
        public void onAccessRequestFinished(@NotNull ScreenAccessRequest request) {
            finished.add(request);
        }

        ScreenAccessRequest last() {
            assertFalse(requested.isEmpty(), "nobody was asked");
            return requested.get(requested.size() - 1);
        }
    }

    /** Active, and counts its viewers. */
    private static final class FakeSource implements ScreenCaptureSource {
        final AtomicInteger subscriptions = new AtomicInteger();

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean addStreamListener(ScreenStreamListener listener) {
            subscriptions.incrementAndGet();
            listener.onInitSegment(new byte[] { 1, 2, 3 }, "avc1.42c01f", 1280, 720);
            return true;
        }

        @Override
        public void removeStreamListener(ScreenStreamListener listener) {
            subscriptions.decrementAndGet();
        }

        @Override
        public void requestKeyFrame() {}
    }

    /** Accepts everything and remembers what reached it. */
    private static final class FakeTarget implements RemoteInputTarget {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public EnumSet<InputFamily> inputFamilies() {
            return EnumSet.of(InputFamily.POINTER, InputFamily.KEYBOARD);
        }

        @Override
        public InputResult keyEvent(Object client, boolean down, String code) {
            calls.add("key " + code);
            return InputResult.OK;
        }

        @Override
        public boolean touchDown(Object client, float x, float y) {
            return true;
        }

        @Override
        public void touchMove(Object client, float x, float y) {}

        @Override
        public void touchUp(Object client, float x, float y, boolean cancel) {}

        @Override
        public void performGlobalAction(RemoteInputCommand.GlobalAction action) {}

        @Override
        public TextResult inputText(String text) {
            return TextResult.OK;
        }

        @Override
        public TextResult backspace() {
            return TextResult.OK;
        }

        @Override
        public TextResult enter() {
            return TextResult.OK;
        }

        @Override
        public void releaseClient(Object client) {}
    }

    /** Hands out whichever user the test says is logged in right now. */
    private static final class FakeAuthManager implements AuthManager {
        final UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(null));
        User current;

        @Override
        public @Nullable User getAuthenticatedUser(@NotNull RequestContext context) {
            return current;
        }

        @Override
        public @Nullable User authenticate(RequestContext context, Request request) {
            return current;
        }

        @Override
        public void logout(@NotNull RequestContext context, @NotNull Request request) {}

        @Override
        public @NotNull UserRightsEvaluator getUserRightsEvaluator() {
            return evaluator;
        }
    }

    /** Collects the text frames and the close code instead of writing to a socket. */
    private static final class RecordingSession extends WebSocketSession {
        final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final AtomicInteger closeCode = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        RecordingSession(Socket socket, String address) {
            super(socket, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    handshakeRequest(address), null, new WebSocketOptions());
        }

        @Override
        public void sendText(String message) {
            texts.add(message);
        }

        @Override
        public void sendBinary(byte[] message) {}

        @Override
        public void sendBinary(byte[] message, int offset, int length) {}

        @Override
        public void close(int code, String reason) {
            if (closeCode.compareAndSet(0, code)) {
                closed.countDown();
            }
        }

        String nextText() throws InterruptedException {
            //everything after the pending notice is written by the pump's own thread
            return texts.poll(5, TimeUnit.SECONDS);
        }

        private static Request handshakeRequest(String address) {
            Request request = new Request();
            request.hostAddress = address;
            return request;
        }
    }

    private final List<Socket> sockets = new ArrayList<>();
    private final SHTTPSConfig config = new TestDBEnvironment.Config();
    private final RecordingApprover approver = new RecordingApprover();
    private final FakeSource source = new FakeSource();
    private final FakeAuthManager authManager = new FakeAuthManager();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private RemoteInputTarget target = new FakeTarget();

    @AfterEach
    public void closeSockets() throws Exception {
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private ScreenStreamWebSocketHandler handler() {
        ScreenStreamWebSocketHandler handler = new ScreenStreamWebSocketHandler(config, authManager,
                () -> source, () -> target, approver);
        handler.decisionExecutor = Runnable::run;
        handler.clock = now::get;
        return handler;
    }

    private RecordingSession open(ScreenStreamWebSocketHandler handler, String address) throws Exception {
        Socket socket = new Socket();
        sockets.add(socket);
        RecordingSession session = new RecordingSession(socket, address);
        handler.onSessionCreated(null, session);
        handler.onOpen(session);
        return session;
    }

    private static void assertPending(RecordingSession session) throws Exception {
        String first = session.nextText();
        assertNotNull(first, "the client was told nothing");
        assertTrue(first.contains("\"type\":\"approval\"") && first.contains("\"state\":\"pending\""),
                "expected the pending notice first, got " + first);
    }

    /** @return the control frame that follows the init one. */
    private static String assertStreaming(RecordingSession session) throws Exception {
        String init = session.nextText();
        assertNotNull(init, "no stream started");
        assertTrue(init.contains("\"type\":\"init\""), "expected an init frame, got " + init);
        String control = session.nextText();
        assertNotNull(control, "no control frame followed the init one");
        return control;
    }

    private static User userWithScreenRights(String name) {
        User user = new User(name, "");
        user.systemRights = EnumSet.of(User.SystemRights.VIEW_SCREEN, User.SystemRights.CONTROL_SCREEN);
        return user;
    }

    @Test
    public void confirmationIsOnUnlessSwitchedOff() {
        assertTrue(config.isScreenShareConfirmationEnabled());
    }

    @Test
    public void withConfirmationOffTheStreamStartsAtOnce() throws Exception {
        config.setScreenShareConfirmationEnabled(false);
        RecordingSession session = open(handler(), "10.0.0.2");

        String control = assertStreaming(session);
        assertTrue(control.contains("\"enabled\":true"), control);
        assertTrue(approver.requested.isEmpty());
    }

    @Test
    public void aHostThatCanNotAskLetsViewersInAsBefore() throws Exception {
        ScreenStreamWebSocketHandler handler = new ScreenStreamWebSocketHandler(config, authManager,
                () -> source, () -> target);
        Socket socket = new Socket();
        sockets.add(socket);
        RecordingSession session = new RecordingSession(socket, "10.0.0.2");
        handler.onSessionCreated(null, session);
        handler.onOpen(session);

        assertStreaming(session);
    }

    @Test
    public void nothingOfTheScreenGoesOutBeforeTheAnswer() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession session = open(handler, "10.0.0.2");

        assertPending(session);
        assertEquals(0, source.subscriptions.get(), "subscribed before anyone said yes");
        ScreenAccessRequest request = approver.last();
        assertEquals("10.0.0.2", request.remoteAddress);
        assertNull(request.userName, "with authentication off there is no user to name");
        assertTrue(request.includesControl, "control is on offer and should be announced");

        //an input command while waiting reaches nothing
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA\"}");
        assertTrue(((FakeTarget) target).calls.isEmpty());

        assertTrue(request.answer(ScreenAccessRequest.Decision.ALLOW));
        String control = assertStreaming(session);
        assertTrue(control.contains("\"enabled\":true"), control);
        assertEquals(1, source.subscriptions.get());
        assertEquals(List.of(request), approver.finished);
    }

    @Test
    public void theRequestNamesTheAccountAndLeavesControlOutWhenItIsNotOnOffer() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        User viewer = new User("alice", "");
        viewer.systemRights = EnumSet.of(User.SystemRights.VIEW_SCREEN);
        authManager.current = viewer;

        open(handler(), "10.0.0.2");

        ScreenAccessRequest request = approver.last();
        assertEquals("alice", request.userName);
        assertFalse(request.includesControl, "this account may only watch");
    }

    @Test
    public void viewOnlyWithholdsControlAndSaysWhy() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession session = open(handler, "10.0.0.2");
        assertPending(session);

        approver.last().answer(ScreenAccessRequest.Decision.VIEW_ONLY);

        String control = assertStreaming(session);
        assertTrue(control.contains("\"enabled\":false"), control);
        assertTrue(control.contains("\"reason\":\"view-only\""), control);
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA\"}");
        assertTrue(((FakeTarget) target).calls.isEmpty(), "a watch-only viewer reached the keyboard");
    }

    @Test
    public void aRefusalClosesTheConnection() throws Exception {
        RecordingSession session = open(handler(), "10.0.0.2");
        assertPending(session);

        approver.last().answer(ScreenAccessRequest.Decision.DENY);

        assertEquals(ScreenStreamWebSocketHandler.CLOSE_CODE_ACCESS_DENIED, session.closeCode.get());
        assertEquals(0, source.subscriptions.get());
        assertEquals(1, approver.finished.size());
    }

    @Test
    public void noAnswerInTimeClosesTheConnectionAndTheQuestion() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        handler.accessRequestTimeoutMillis = 50;
        RecordingSession session = open(handler, "10.0.0.2");
        assertPending(session);
        ScreenAccessRequest request = approver.last();

        assertTrue(session.closed.await(5, TimeUnit.SECONDS), "the request never timed out");
        assertEquals(ScreenStreamWebSocketHandler.CLOSE_CODE_ACCESS_TIMEOUT, session.closeCode.get());
        assertEquals(List.of(request), approver.finished);
        assertFalse(request.answer(ScreenAccessRequest.Decision.ALLOW),
                "a late answer must not let the client in");
        assertEquals(0, source.subscriptions.get());
    }

    @Test
    public void aClientThatLeavesTakesTheQuestionAlong() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession session = open(handler, "10.0.0.2");
        ScreenAccessRequest request = approver.last();

        handler.onClose(session, 1001, "");

        assertEquals(List.of(request), approver.finished);
        assertFalse(request.answer(ScreenAccessRequest.Decision.ALLOW));
        assertEquals(0, source.subscriptions.get(), "a client that is gone was subscribed");
    }

    @Test
    public void aReconnectSoonAfterIsLetInWithoutAsking() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession first = open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.ALLOW);
        assertPending(first);
        assertStreaming(first);
        handler.onClose(first, 1006, "");

        now.addAndGet(ScreenStreamWebSocketHandler.ACCESS_GRACE_MILLIS - 1);
        RecordingSession second = open(handler, "10.0.0.2");

        assertEquals(1, approver.requested.size(), "asked again about a client just let in");
        String control = assertStreaming(second);
        assertTrue(control.contains("\"enabled\":true"), control);
    }

    @Test
    public void anApprovalRunsOutAfterTheGracePeriod() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession first = open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.ALLOW);
        handler.onClose(first, 1000, "");

        now.addAndGet(ScreenStreamWebSocketHandler.ACCESS_GRACE_MILLIS);
        RecordingSession second = open(handler, "10.0.0.2");

        assertEquals(2, approver.requested.size());
        assertPending(second);
    }

    @Test
    public void anApprovalDoesNotRunOutWhileItsConnectionIsOpen() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.ALLOW);

        //a second tab, an hour into watching in the first
        now.addAndGet(60 * 60 * 1000);
        open(handler, "10.0.0.2");

        assertEquals(1, approver.requested.size());
    }

    @Test
    public void anApprovalIsForOneAddressAndOneAccount() throws Exception {
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        ScreenStreamWebSocketHandler handler = handler();
        authManager.current = userWithScreenRights("alice");
        open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.ALLOW);

        authManager.current = userWithScreenRights("bob");
        open(handler, "10.0.0.2");
        assertEquals(2, approver.requested.size(), "bob got in on alice's approval");
        assertEquals("bob", approver.last().userName);

        authManager.current = userWithScreenRights("alice");
        open(handler, "10.0.0.3");
        assertEquals(3, approver.requested.size(), "another machine got in on alice's approval");
    }

    @Test
    public void anApprovalToWatchIsNotStretchedIntoOneToControl() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        target = null;
        RecordingSession first = open(handler, "10.0.0.2");
        assertFalse(approver.last().includesControl);
        approver.last().answer(ScreenAccessRequest.Decision.ALLOW);
        handler.onClose(first, 1000, "");

        //remote control switched on in the meantime
        target = new FakeTarget();
        RecordingSession second = open(handler, "10.0.0.2");

        assertEquals(2, approver.requested.size());
        assertTrue(approver.last().includesControl);
        assertPending(second);
    }

    @Test
    public void watchOnlyIsRememberedAsWatchOnly() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession first = open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.VIEW_ONLY);
        handler.onClose(first, 1006, "");

        RecordingSession second = open(handler, "10.0.0.2");

        assertEquals(1, approver.requested.size());
        String control = assertStreaming(second);
        assertTrue(control.contains("\"reason\":\"view-only\""), control);
    }

    @Test
    public void aRefusalIsNotRemembered() throws Exception {
        ScreenStreamWebSocketHandler handler = handler();
        RecordingSession first = open(handler, "10.0.0.2");
        approver.last().answer(ScreenAccessRequest.Decision.DENY);
        handler.onClose(first, ScreenStreamWebSocketHandler.CLOSE_CODE_ACCESS_DENIED, "");

        open(handler, "10.0.0.2");

        assertEquals(2, approver.requested.size(), "the user may have changed their mind");
    }
}
