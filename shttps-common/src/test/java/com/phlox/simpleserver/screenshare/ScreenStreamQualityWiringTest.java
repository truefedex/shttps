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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * How a viewer's wishes travel from the page to the capture source: read out of the handshake so
 * the stream starts right, changed later by a message, announced back, and given up when the
 * viewer leaves. What the wishes add up to is {@link ScreenStreamQualityControllerTest}'s business.
 */
public class ScreenStreamQualityWiringTest {

    /** Offers a choice of quality and remembers every profile it was switched to. */
    private static final class ChoosySource implements ScreenCaptureSource {
        final List<ScreenStreamProfile> profiles = new CopyOnWriteArrayList<>();
        final List<ScreenStreamListener> listeners = new CopyOnWriteArrayList<>();
        final boolean supported;
        /** Whether it can leave the mouse pointer out of the picture and report it instead. */
        boolean cursorOverlay;

        ChoosySource(boolean supported) {
            this.supported = supported;
        }

        @Override
        public boolean supportsCursorOverlay() {
            return cursorOverlay;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean addStreamListener(ScreenStreamListener listener) {
            listeners.add(listener);
            listener.onInitSegment(new byte[] { 1 }, "avc1.42c01f", 1280, 720);
            return true;
        }

        @Override
        public void removeStreamListener(ScreenStreamListener listener) {}

        @Override
        public void requestKeyFrame() {}

        @Override
        public boolean supportsStreamQuality() {
            return supported;
        }

        @Override
        public void setStreamProfile(ScreenStreamProfile profile) {
            profiles.add(profile);
        }

        ScreenStreamProfile last() {
            return profiles.isEmpty() ? null : profiles.get(profiles.size() - 1);
        }
    }

    private static final class RecordingSession extends WebSocketSession {
        final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();

        RecordingSession(Socket socket, Request handshake) {
            super(socket, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(),
                    handshake, null, new WebSocketOptions());
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
        public void close(int code, String reason) {}
    }

    private final List<Socket> sockets = new ArrayList<>();
    private final SHTTPSConfig config = new TestDBEnvironment.Config();

    @AfterEach
    public void closeSockets() throws Exception {
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private ScreenStreamWebSocketHandler handler(ChoosySource source) {
        //authentication off, so every viewer may watch without a database behind the rights
        return new ScreenStreamWebSocketHandler(config, null, () -> source, () -> null);
    }

    /** @param query name/value pairs of the handshake's query string */
    private RecordingSession open(ScreenStreamWebSocketHandler handler, String address,
                                  String... query) throws Exception {
        Request request = new Request();
        request.hostAddress = address;
        for (int i = 0; i + 1 < query.length; i += 2) {
            request.queryParams.put(query[i], query[i + 1]);
        }
        Socket socket = new Socket();
        sockets.add(socket);
        RecordingSession session = new RecordingSession(socket, request);
        handler.onSessionCreated(null, session);
        handler.onOpen(session);
        return session;
    }

    /** @return the next frame of that type, skipping the others the connection produces */
    private static String nextOfType(RecordingSession session, String type) throws InterruptedException {
        for (int i = 0; i < 8; i++) {
            String text = session.texts.poll(5, TimeUnit.SECONDS);
            if (text == null) {
                return null;
            }
            if (text.contains("\"type\":\"" + type + "\"")) {
                return text;
            }
        }
        return null;
    }

    private static void awaitProfile(ChoosySource source, ScreenStreamQuality quality) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ScreenStreamProfile last = source.last();
            if (last != null && last.quality == quality) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(quality, source.last() == null ? null : source.last().quality);
    }

    /** A page from before there was a choice, still cached in someone's browser. */
    @Test
    public void aClientThatNamesNoQualityGetsWhatItAlwaysGot() throws Exception {
        ChoosySource source = new ChoosySource(true);
        RecordingSession session = open(handler(source), "192.168.1.5");

        assertEquals(ScreenStreamProfile.DEFAULT, source.last());
        String stream = nextOfType(session, "stream");
        assertNotNull(stream, "the choice was not announced");
        assertTrue(stream.contains("\"requested\":\"low\""), stream);
    }

    @Test
    public void whatThePageWantsIsKnownBeforeTheStreamStarts() throws Exception {
        ChoosySource source = new ChoosySource(true);
        RecordingSession session = open(handler(source), "192.168.1.5",
                "quality", "auto", "vw", "1300", "vh", "700");

        assertEquals(1, source.profiles.size(), "settled once, before subscribing");
        assertEquals(ScreenStreamQuality.HIGH, source.last().quality, "a local address starts high");
        assertEquals(1600, source.last().maxDimension, "but no larger than the window is worth");

        //the init frame comes first whatever else was queued: nothing may overtake the picture
        String first = session.texts.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertTrue(first.contains("\"type\":\"init\""), first);
        String stream = nextOfType(session, "stream");
        assertNotNull(stream);
        assertTrue(stream.contains("\"requested\":\"auto\""), stream);
        assertTrue(stream.contains("\"quality\":\"high\""), stream);
        assertTrue(stream.contains("\"dimension\":1600"), stream);
    }

    @Test
    public void autoIsCautiousAboutAnAddressFromElsewhere() throws Exception {
        ChoosySource source = new ChoosySource(true);
        open(handler(source), "203.0.113.9", "quality", "auto");
        assertEquals(ScreenStreamQuality.LOW, source.last().quality);
    }

    @Test
    public void theServersLimitIsObeyedAndTold() throws Exception {
        config.setScreenShareMaxQuality(ScreenStreamQuality.MEDIUM);
        ChoosySource source = new ChoosySource(true);
        RecordingSession session = open(handler(source), "192.168.1.5", "quality", "high");

        assertEquals(ScreenStreamQuality.MEDIUM, source.last().quality);
        String stream = nextOfType(session, "stream");
        assertNotNull(stream);
        assertTrue(stream.contains("\"max\":\"medium\""), stream);
        //what was asked for is still what the menu shows; what is served is said next to it
        assertTrue(stream.contains("\"requested\":\"high\""), stream);
        assertTrue(stream.contains("\"quality\":\"medium\""), stream);
    }

    @Test
    public void theViewerCanChangeItsMindWhileWatching() throws Exception {
        ChoosySource source = new ChoosySource(true);
        ScreenStreamWebSocketHandler handler = handler(source);
        RecordingSession session = open(handler, "192.168.1.5", "quality", "low");
        assertNotNull(nextOfType(session, "stream"));

        handler.onTextMessage(session,
                "{\"type\":\"stream\",\"quality\":\"medium\",\"viewport\":[1000,700]}");

        assertEquals(ScreenStreamQuality.MEDIUM, source.last().quality);
        assertEquals(1280, source.last().maxDimension);
        String stream = nextOfType(session, "stream");
        assertNotNull(stream, "the change was not announced");
        assertTrue(stream.contains("\"quality\":\"medium\""), stream);
    }

    @Test
    public void aViewerLeavingNoLongerHoldsTheOthersBack() throws Exception {
        ChoosySource source = new ChoosySource(true);
        ScreenStreamWebSocketHandler handler = handler(source);
        RecordingSession keen = open(handler, "192.168.1.5", "quality", "high");
        RecordingSession modest = open(handler, "192.168.1.6", "quality", "low");
        assertEquals(ScreenStreamQuality.LOW, source.last().quality, "one stream, the lowest wish");

        handler.onClose(modest, 1000, "");

        awaitProfile(source, ScreenStreamQuality.HIGH);
        String stream = null;
        for (int i = 0; i < 4; i++) {
            String next = nextOfType(keen, "stream");
            if (next == null) {
                break;
            }
            stream = next;
            if (next.contains("\"quality\":\"high\"")) {
                break;
            }
        }
        assertNotNull(stream);
        assertTrue(stream.contains("\"quality\":\"high\""), "the one still watching was not told: " + stream);
    }

    /**
     * The pointer leaves the picture only when the source can report it separately <i>and</i>
     * every viewer can draw it. One page from before that existed is enough to keep it in.
     */
    @Test
    public void thePointerLeavesThePictureOnlyWhenEveryViewerCanDrawIt() throws Exception {
        ChoosySource source = new ChoosySource(true);
        source.cursorOverlay = true;
        ScreenStreamWebSocketHandler handler = handler(source);

        RecordingSession modern = open(handler, "192.168.1.5", "quality", "low", "cursor", "1");
        assertTrue(!source.last().cursorInPicture, "everyone watching draws the pointer themselves");
        String stream = nextOfType(modern, "stream");
        assertNotNull(stream);
        assertTrue(stream.contains("\"cursor\":\"overlay\""), stream);

        RecordingSession old = open(handler, "192.168.1.6", "quality", "low");
        assertTrue(source.last().cursorInPicture, "a viewer that cannot draw it would see no pointer");
        stream = nextOfType(modern, "stream");
        assertNotNull(stream, "the first viewer has to stop drawing a second pointer");
        assertTrue(stream.contains("\"cursor\":\"picture\""), stream);

        handler.onClose(old, 1000, "");
        long deadline = System.currentTimeMillis() + 5000;
        while (source.last().cursorInPicture && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!source.last().cursorInPicture, "with that viewer gone the pointer can leave again");
    }

    @Test
    public void aSourceThatAlwaysDrawsThePointerIsNeverSaidToLeaveItOut() throws Exception {
        ChoosySource source = new ChoosySource(true);
        RecordingSession session = open(handler(source), "192.168.1.5", "quality", "low", "cursor", "1");

        assertTrue(source.last().cursorInPicture);
        String stream = nextOfType(session, "stream");
        assertNotNull(stream);
        assertTrue(stream.contains("\"cursor\":\"picture\""),
                "the page would hide its own handling of a pointer nobody then draws: " + stream);
    }

    @Test
    public void pointerReportsReachTheViewerAndOnlyAsPlainKeywords() throws Exception {
        ChoosySource source = new ChoosySource(true);
        source.cursorOverlay = true;
        RecordingSession session = open(handler(source), "192.168.1.5", "quality", "low", "cursor", "1");
        ScreenStreamListener pump = source.listeners.get(0);

        pump.onCursor(0.25f, 0.5f, "text", true);
        String cursor = nextOfType(session, "cursor");
        assertNotNull(cursor, "the report did not reach the viewer");
        assertTrue(cursor.contains("\"x\":0.25"), cursor);
        assertTrue(cursor.contains("\"y\":0.5"), cursor);
        assertTrue(cursor.contains("\"shape\":\"text\""), cursor);
        assertTrue(cursor.contains("\"visible\":true"), cursor);

        //the shape ends up in a page's style, so it is not passed on as given
        pump.onCursor(0.3f, 0.5f, "url(x)\";}", false);
        cursor = nextOfType(session, "cursor");
        assertNotNull(cursor);
        assertTrue(cursor.contains("\"shape\":\"default\""), cursor);
        assertTrue(cursor.contains("\"visible\":false"), cursor);

        //the source repeats itself once a second for late joiners; that is not news to this one
        pump.onCursor(0.3f, 0.5f, "url(x)\";}", false);
        assertNull(session.texts.poll(300, TimeUnit.MILLISECONDS), "the same report was sent twice");
    }

    /** Android today, and any source written before there was a choice. */
    @Test
    public void aSourceWithOneFixedPictureIsNeverAskedAndNothingIsOffered() throws Exception {
        ChoosySource source = new ChoosySource(false);
        ScreenStreamWebSocketHandler handler = handler(source);
        RecordingSession session = open(handler, "192.168.1.5", "quality", "high");
        handler.onTextMessage(session, "{\"type\":\"stream\",\"quality\":\"high\"}");

        assertTrue(source.profiles.isEmpty());
        assertNotNull(nextOfType(session, "control"));
        assertNull(session.texts.poll(300, TimeUnit.MILLISECONDS), "no stream frame may follow");
    }
}
