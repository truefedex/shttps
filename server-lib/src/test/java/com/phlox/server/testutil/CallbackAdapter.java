package com.phlox.server.testutil;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;

import java.net.Socket;

/** All {@link SimpleHttpServer.Callback} methods as no-ops, to override just the ones a test watches. */
public class CallbackAdapter implements SimpleHttpServer.Callback {
    @Override public void onServerStarted() {}
    @Override public void onServerStopped() {}
    @Override public void onNewConnection(Socket socket, long connectionId) {}
    @Override public void onConnectionTracked(Socket socket, long connectionId) {}
    @Override public void onConnectionClosed(Socket socket, String reason, long connectionId) {}
    @Override public void onConnectionError(Socket socket, Exception e, long connectionId) {}
    @Override public void onConnectionRequest(RequestContext context, Request request) {}
    @Override public void onConnectionResponse(RequestContext context, Request request, Response response) {}
    @Override public void onConnectionRejected(Socket socket, int reason, long connectionId) {}
}
