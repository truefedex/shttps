package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.Request;
import com.phlox.server.websocket.WebSocketOptions;
import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.remotecontrol.RemoteInputCommand;
import com.phlox.simpleserver.remotecontrol.RemoteInputTarget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the endpoint does with the desktop half of the input protocol: which target method each
 * command reaches, what the client is told when one of them will not run, and - the one that is
 * worth a test on its own - that a connection going away always lets go of whatever it was
 * holding, even when remote control was switched off in the meantime.
 * <p>
 * Driven through the real handler with a real {@link WebSocketSession} over a pair of byte arrays,
 * so the dispatch, the ownership token and the status frames are all the production ones.
 */
public class ScreenStreamControlDispatchTest {

    /** Records what the handler asked of the machine, and can be told what to answer. */
    private static final class RecordingTarget implements RemoteInputTarget {
        final List<String> calls = new ArrayList<>();
        final AtomicReference<Object> releasedClient = new AtomicReference<>();
        InputResult answer = InputResult.OK;
        EnumSet<InputFamily> families =
                EnumSet.of(InputFamily.POINTER, InputFamily.WHEEL, InputFamily.KEYBOARD);

        @Override
        public EnumSet<InputFamily> inputFamilies() {
            return families;
        }

        @Override
        public InputResult pointerMove(Object client, float x, float y) {
            calls.add("move " + x + " " + y);
            return answer;
        }

        @Override
        public InputResult pointerDown(Object client, RemoteInputCommand.PointerButton button,
                                       float x, float y) {
            calls.add("down " + button.name().toLowerCase() + " " + x + " " + y);
            return answer;
        }

        @Override
        public InputResult pointerUp(Object client, RemoteInputCommand.PointerButton button,
                                     boolean hasPosition, float x, float y, boolean cancel) {
            calls.add("up " + button.name().toLowerCase() + " hasPosition=" + hasPosition
                    + " cancel=" + cancel);
            return answer;
        }

        @Override
        public InputResult scroll(Object client, float notchesX, float notchesY,
                                  boolean hasPosition, float x, float y) {
            calls.add("scroll " + notchesX + " " + notchesY + " hasPosition=" + hasPosition);
            return answer;
        }

        @Override
        public InputResult keyEvent(Object client, boolean down, String code) {
            calls.add("key " + code + " " + (down ? "down" : "up"));
            return answer;
        }

        @Override
        public InputResult releaseHeldKeys(Object client) {
            calls.add("releaseHeldKeys");
            return answer;
        }

        @Override
        public boolean touchDown(Object client, float x, float y) {
            calls.add("touchDown");
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
            calls.add("text " + text);
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
        public void releaseClient(Object client) {
            calls.add("releaseClient");
            releasedClient.set(client);
        }
    }

    /** A source that is active and hands over an init segment the moment it is subscribed to. */
    private static final class FakeSource implements ScreenCaptureSource {
        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean addStreamListener(ScreenStreamListener listener) {
            //the contract: the init segment has to have been delivered before this returns, so
            //that the control frame the handler queues next lands behind it
            listener.onInitSegment(new byte[] { 1, 2, 3 }, "avc1.42c01f", 1280, 720);
            return true;
        }

        @Override
        public void removeStreamListener(ScreenStreamListener listener) {}

        @Override
        public void requestKeyFrame() {}

        @Override
        public String getPlatform() {
            return "desktop";
        }
    }

    /** Collects the text frames instead of writing them to a socket. */
    private static final class RecordingSession extends WebSocketSession {
        final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();

        RecordingSession(Socket socket) {
            super(socket, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    handshakeRequest(), null, new WebSocketOptions());
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
            //the real one writes a close frame and shortens the socket timeout, neither of which
            //means anything here
        }

        private static Request handshakeRequest() {
            Request request = new Request();
            request.hostAddress = "test";
            return request;
        }
    }

    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    public void closeSockets() throws Exception {
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private RecordingSession newSession() throws Exception {
        Socket socket = new Socket();
        sockets.add(socket);
        return new RecordingSession(socket);
    }

    private ScreenStreamWebSocketHandler handler(Supplier target) {
        //authentication off, which is what makes rights resolution allow everything without a
        //database behind it - the rights themselves are covered elsewhere
        SHTTPSConfig config = new TestDBEnvironment.Config();
        return new ScreenStreamWebSocketHandler(config, null, FakeSource::new, target::get);
    }

    /** A one-value holder, so a test can make the target vanish mid-connection. */
    private static final class Supplier {
        RemoteInputTarget value;

        Supplier(RemoteInputTarget value) {
            this.value = value;
        }

        RemoteInputTarget get() {
            return value;
        }
    }

    private String nextText(RecordingSession session) throws InterruptedException {
        //the frames are written by the pump's own thread
        return session.texts.poll(5, TimeUnit.SECONDS);
    }

    /** @return the control frame, skipping the init one the subscription produced. */
    private String openAndReadControlFrame(ScreenStreamWebSocketHandler handler,
                                           RecordingSession session) throws Exception {
        handler.onSessionCreated(null, session);
        handler.onOpen(session);
        String first = nextText(session);
        assertNotNull(first, "no init frame arrived");
        assertTrue(first.contains("\"type\":\"init\""), "expected an init frame, got " + first);
        return nextText(session);
    }

    @Test
    public void tellsTheClientWhichKindsOfInputTheMachineTakes() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        String control = openAndReadControlFrame(handler(new Supplier(target)), session);

        assertNotNull(control, "no control frame arrived");
        assertTrue(control.contains("\"enabled\":true"), control);
        assertTrue(control.contains("\"mouse\""), control);
        assertTrue(control.contains("\"wheel\""), control);
        assertTrue(control.contains("\"keyboard\""), control);
        //a phone's families are not a desktop's, and the page keys its whole toolbar on this
        assertTrue(!control.contains("\"touch\""), control);
    }

    @Test
    public void aBuildWithNoInputTargetStillSaysSoInTheSameShape() throws Exception {
        //the empty list is what lets the page tell "not right now" from "this server is too old to
        //know about input at all", which is a different thing and worth no periodic asking
        RecordingSession session = newSession();
        String control = openAndReadControlFrame(handler(new Supplier(null)), session);

        assertNotNull(control);
        assertTrue(control.contains("\"enabled\":false"), control);
        assertTrue(control.contains("\"input\":[]"), control);
    }

    @Test
    public void mouseCommandsReachTheMatchingMethods() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        handler.onTextMessage(session,
                "{\"type\":\"mouse\",\"action\":\"move\",\"x\":0.5,\"y\":0.25}");
        handler.onTextMessage(session,
                "{\"type\":\"mouse\",\"action\":\"down\",\"button\":\"right\",\"x\":0.5,\"y\":0.25}");
        handler.onTextMessage(session, "{\"type\":\"mouse\",\"action\":\"up\"}");
        handler.onTextMessage(session, "{\"type\":\"wheel\",\"dy\":-3,\"x\":0.5,\"y\":0.25}");
        handler.onTextMessage(session,
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"ControlLeft\"}");
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"reset\"}");

        assertEquals(
                List.of(
                        "move 0.5 0.25",
                        "down right 0.5 0.25",
                        "up left hasPosition=false cancel=false",
                        "scroll 0.0 -3.0 hasPosition=true",
                        "key ControlLeft down",
                        "releaseHeldKeys"),
                target.calls);
    }

    @Test
    public void aCancelledMousePressIsReportedAsOne() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        handler.onTextMessage(session,
                "{\"type\":\"mouse\",\"action\":\"cancel\",\"button\":\"middle\"}");

        assertEquals(List.of("up middle hasPosition=false cancel=true"), target.calls);
    }

    @Test
    public void whyAnEventDidNotHappenIsPassedOnToTheClient() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        target.answer = RemoteInputTarget.InputResult.BUSY;
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA\"}");
        assertTrue(nextText(session).contains("\"reason\":\"busy\""));

        target.answer = RemoteInputTarget.InputResult.UNSUPPORTED;
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"F13\"}");
        assertTrue(nextText(session).contains("\"reason\":\"unsupported\""));

        //what a window running elevated looks like from here
        target.answer = RemoteInputTarget.InputResult.FAILED;
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyB\"}");
        assertTrue(nextText(session).contains("\"reason\":\"input-failed\""));

        //and what a macOS machine that was never granted the Accessibility permission looks like.
        //It has to be its own reason: the page sends the user to a different place for each.
        target.answer = RemoteInputTarget.InputResult.NO_PERMISSION;
        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyC\"}");
        assertTrue(nextText(session).contains("\"reason\":\"input-permission\""));
    }

    @Test
    public void anUnchangedReasonIsNotRepeated() throws Exception {
        //a client dragging against a busy machine would otherwise get one frame per event
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        target.answer = RemoteInputTarget.InputResult.BUSY;
        for (int i = 0; i < 5; i++) {
            handler.onTextMessage(session,
                    "{\"type\":\"mouse\",\"action\":\"down\",\"x\":0.5,\"y\":0.5}");
        }
        assertTrue(nextText(session).contains("\"reason\":\"busy\""));
        assertNull(session.texts.poll(200, TimeUnit.MILLISECONDS), "the busy frame was repeated");
    }

    @Test
    public void switchingControlOnMidConnectionIsAnsweredToTheClientsProbe() throws Exception {
        //the page is told "disabled" on connect and then asks every ten seconds, which is the only
        //way it can find out that the user ticked the box - the desktop registers the route once,
        //at startup, and switching control on deliberately does not restart the server
        Supplier supplier = new Supplier(null);
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(supplier);

        String first = openAndReadControlFrame(handler, session);
        assertTrue(first.contains("\"enabled\":false"), first);
        assertTrue(first.contains("\"input\":[]"), first);

        supplier.value = new RecordingTarget();
        handler.onTextMessage(session, "{\"type\":\"status\"}");

        String answer = nextText(session);
        assertNotNull(answer, "the probe went unanswered");
        assertTrue(answer.contains("\"enabled\":true"), answer);
        assertTrue(answer.contains("\"mouse\""), answer);
    }

    @Test
    public void aDroppedMoveDoesNotMakeTheBusyFrameRepeatItself() throws Exception {
        //a target answers OK to a move it dropped because someone else holds the machine, so a
        //bystander hovering over the picture must not keep clearing the repeat suppression and
        //re-announcing what it was already told
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        target.answer = RemoteInputTarget.InputResult.BUSY;
        handler.onTextMessage(session, "{\"type\":\"mouse\",\"action\":\"down\",\"x\":0.5,\"y\":0.5}");
        assertTrue(nextText(session).contains("\"reason\":\"busy\""));

        //moves are dropped and answered OK by a target that is ignoring this client
        target.answer = RemoteInputTarget.InputResult.OK;
        for (int i = 0; i < 10; i++) {
            handler.onTextMessage(session,
                    "{\"type\":\"mouse\",\"action\":\"move\",\"x\":0.5,\"y\":0.5}");
        }
        target.answer = RemoteInputTarget.InputResult.BUSY;
        handler.onTextMessage(session, "{\"type\":\"mouse\",\"action\":\"down\",\"x\":0.5,\"y\":0.5}");

        assertNull(session.texts.poll(200, TimeUnit.MILLISECONDS),
                "the busy frame was repeated after moves that changed nothing");
    }

    @Test
    public void aClosingConnectionLetsGoEvenAfterControlWasSwitchedOff() throws Exception {
        //the case that leaves a Ctrl key held on someone's desktop: the supplier answers null by
        //the time the socket closes, so asking it alone would release nothing at all
        RecordingTarget target = new RecordingTarget();
        Supplier supplier = new Supplier(target);
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(supplier);
        openAndReadControlFrame(handler, session);

        handler.onTextMessage(session,
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"ControlLeft\"}");
        supplier.value = null;
        target.calls.clear();

        handler.onClose(session, 1006, "gone");

        assertEquals(List.of("releaseClient"), target.calls);
        assertTrue(target.releasedClient.get() == session,
                "the session itself is the ownership token the target was given");
    }

    @Test
    public void theTargetIsOnlyReleasedOnceWhenItIsStillTheCurrentOne() throws Exception {
        RecordingTarget target = new RecordingTarget();
        RecordingSession session = newSession();
        ScreenStreamWebSocketHandler handler = handler(new Supplier(target));
        openAndReadControlFrame(handler, session);

        handler.onTextMessage(session, "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA\"}");
        target.calls.clear();

        handler.onClose(session, 1000, "bye");

        assertEquals(List.of("releaseClient"), target.calls);
    }
}
