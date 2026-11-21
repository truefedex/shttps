package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads that the client abandons half way, over a real socket, with {@code atomic_uploads} on
 * and off. The staged upload is only as good as the handler that decides when to commit it: the
 * body reader closes the part's stream on a client EOF too, so a commit on close published the
 * truncated bytes. With staging off the file is written in place and is truncated, but a short
 * WebDAV body must no longer be answered with a success.
 */
@Timeout(30)
public class InterruptedUploadOverHttpTest {
    private static final String BOUNDARY = "----shttpsTestBoundary";
    private LiveServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    @Test
    public void stagedMultipartCutOffKeepsThePreviousFile(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> c.setAtomicUploadsEnabled(true));
        Files.writeString(server.root.resolve("note.txt"), "old");

        server.sendAndHangUp(multipartHead(500), multipartPart("note.txt", "truncated-new-content"));

        assertTrue(LiveServer.eventually(() -> server.onlyFiles("note.txt")));
        assertEquals("old", Files.readString(server.root.resolve("note.txt")));
    }

    @Test
    public void stagedMultipartCompletedReplacesTheFile(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> c.setAtomicUploadsEnabled(true));
        Files.writeString(server.root.resolve("note.txt"), "old");

        byte[] body = LiveServer.concat(multipartPart("note.txt", "new"),
                ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        String status = server.exchange(multipartHead(body.length), body);

        assertTrue(status.contains(" 204 "), status);
        assertEquals("new", Files.readString(server.root.resolve("note.txt")));
        assertTrue(server.onlyFiles("note.txt"));
    }

    @Test
    public void stagedWebDavPutCutOffKeepsThePreviousFile(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> c.setAtomicUploadsEnabled(true));
        Files.writeString(server.root.resolve("put.bin"), "old");

        server.sendAndHangUp(putHead("put.bin", 100), "0123456789".getBytes(StandardCharsets.US_ASCII));

        assertTrue(LiveServer.eventually(() -> server.onlyFiles("put.bin")));
        assertEquals("old", Files.readString(server.root.resolve("put.bin")));
    }

    @Test
    public void inPlaceWebDavPutShorterThanAnnouncedIsNotASuccess(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> c.setAtomicUploadsEnabled(false));
        Files.writeString(server.root.resolve("put.bin"), "old");

        //the client half-closes after 10 of 100 bytes and waits for the answer
        String status = server.exchangeHalfClosed(putHead("put.bin", 100),
                "0123456789".getBytes(StandardCharsets.US_ASCII));

        assertTrue(status.contains(" 400 "), status);
    }

    @Test
    public void inPlaceWebDavPutWithEmptyBodyTruncatesTheFile(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> c.setAtomicUploadsEnabled(false));
        Files.writeString(server.root.resolve("put.bin"), "old");

        String status = server.exchange(putHead("put.bin", 0), new byte[0]);

        assertTrue(status.contains(" 204 "), status);
        assertEquals(0, Files.size(server.root.resolve("put.bin")));
    }

    private static String multipartHead(int contentLength) {
        return "PUT /api/file/upload?path=/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n" +
                "Content-Length: " + contentLength + "\r\n\r\n";
    }

    private static byte[] multipartPart(String name, String content) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files[]\"; filename=\"" + name +
                "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content).getBytes(StandardCharsets.UTF_8);
    }

    private static String putHead(String name, int contentLength) {
        return "PUT /" + name + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                "Content-Length: " + contentLength + "\r\n\r\n";
    }
}
