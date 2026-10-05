package com.phlox.server.testutil;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.RequestHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * A {@link SimpleHttpServer} on a free loopback port, for tests that talk to it over a real
 * socket. Use it in try-with-resources or stop it from an {@code @AfterEach}.
 */
public class TestServer implements AutoCloseable {
    public final SimpleHttpServer server;
    private int port;

    public TestServer(RequestHandler handler) {
        this(handler, null);
    }

    public TestServer(RequestHandler handler, SimpleHttpServer.Callback callback) {
        server = new SimpleHttpServer(handler, callback);
    }

    /** Starts listening; configure {@link #server} before calling this. */
    public TestServer start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = serverSocket.getLocalPort();
        server.startListen(serverSocket);
        return this;
    }

    public int port() {
        return port;
    }

    public RawHttpClient connect() throws IOException {
        return new RawHttpClient(port);
    }

    @Override
    public void close() {
        server.stopListen();
    }
}
