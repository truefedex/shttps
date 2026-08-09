package com.phlox.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.request.DefaultRequestBodyReader;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.UpgradeResponse;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

public class WebSocketHandshakeTest {
    //example key and its expected answer from RFC 6455 section 1.3
    private static final String SAMPLE_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String SAMPLE_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    @Test
    public void acceptsValidHandshake() throws Exception {
        Response response = handle(new WebSocketRequestHandler() {}, handshakeRequest());

        assertInstanceOf(UpgradeResponse.class, response);
        assertEquals(101, response.code);
        assertEquals("websocket", response.headers.get(UpgradeResponse.HEADER_UPGRADE));
        assertEquals("Upgrade", response.headers.get(Response.HEADER_CONNECTION));
        assertEquals(SAMPLE_ACCEPT, response.headers.get(
                WebSocketRequestHandler.RESPONSE_HEADER_SEC_WEBSOCKET_ACCEPT));
        //HTTP framing ends with the handshake, a body length would be meaningless
        assertFalse(response.headers.containsKey(Response.HEADER_CONTENT_LENGTH));
        assertNull(response.headers.get(WebSocketRequestHandler.RESPONSE_HEADER_SEC_WEBSOCKET_PROTOCOL));
    }

    @Test
    public void computesAcceptKeyFromTheSpecExample() throws Exception {
        assertEquals(SAMPLE_ACCEPT, WebSocketRequestHandler.computeAcceptKey(SAMPLE_KEY));
    }

    @Test
    public void rejectsNonGetRequest() throws Exception {
        Request request = handshakeRequest();
        request.method = Request.METHOD_POST;

        Response response = handle(new WebSocketRequestHandler() {}, request);
        assertEquals(405, response.code);
    }

    @Test
    public void rejectsRequestWithoutUpgradeHeaders() throws Exception {
        Request plainGet = new Request();
        plainGet.method = Request.METHOD_GET;

        Response response = handle(new WebSocketRequestHandler() {}, plainGet);
        assertEquals(426, response.code);
        assertEquals("13", response.headers.get(
                WebSocketRequestHandler.RESPONSE_HEADER_SEC_WEBSOCKET_VERSION));
    }

    @Test
    public void rejectsUnsupportedVersion() throws Exception {
        Request request = handshakeRequest();
        request.headers.put(WebSocketRequestHandler.HEADER_SEC_WEBSOCKET_VERSION, "8");

        Response response = handle(new WebSocketRequestHandler() {}, request);
        assertEquals(426, response.code);
    }

    @Test
    public void rejectsMalformedKey() throws Exception {
        Request request = handshakeRequest();
        //valid base64 but not the required 16 byte nonce
        request.headers.put(WebSocketRequestHandler.HEADER_SEC_WEBSOCKET_KEY, "c2hvcnQ=");

        Response response = handle(new WebSocketRequestHandler() {}, request);
        assertEquals(400, response.code);
    }

    @Test
    public void acceptsUpgradeTokenInAMultiValueConnectionHeader() throws Exception {
        Request request = handshakeRequest();
        request.headers.put(Request.HEADER_CONNECTION, "keep-alive, Upgrade");

        assertInstanceOf(UpgradeResponse.class, handle(new WebSocketRequestHandler() {}, request));
    }

    @Test
    public void honoursOriginCheck() throws Exception {
        WebSocketRequestHandler handler = new WebSocketRequestHandler() {
            @Override
            protected boolean isOriginAllowed(@NotNull Request request, @Nullable String origin) {
                return "http://allowed.example".equals(origin);
            }
        };

        Request request = handshakeRequest();
        request.headers.put(Request.HEADER_ORIGIN, "http://evil.example");
        assertEquals(403, handle(handler, request).code);

        request.headers.put(Request.HEADER_ORIGIN, "http://allowed.example");
        assertInstanceOf(UpgradeResponse.class, handle(handler, request));
    }

    @Test
    public void negotiatesSubProtocol() throws Exception {
        WebSocketRequestHandler handler = new WebSocketRequestHandler() {
            @Override
            protected String selectSubProtocol(@NotNull Request request, @NotNull List<String> offered) {
                return offered.contains("chat") ? "chat" : null;
            }
        };

        Request request = handshakeRequest();
        request.headers.put(WebSocketRequestHandler.HEADER_SEC_WEBSOCKET_PROTOCOL, "superchat, chat");

        Response response = handle(handler, request);
        assertEquals("chat", response.headers.get(
                WebSocketRequestHandler.RESPONSE_HEADER_SEC_WEBSOCKET_PROTOCOL));
    }

    @Test
    public void handshakeHookCanAddHeaders() throws Exception {
        WebSocketRequestHandler handler = new WebSocketRequestHandler() {
            @Override
            protected void onHandshakeAccepted(RequestContext context, Request request, UpgradeResponse response) {
                response.headers.add("X-Session", "42");
            }
        };

        assertEquals("42", handle(handler, handshakeRequest()).headers.get("X-Session"));
    }

    @Test
    public void detectsHandshakeRequests() {
        assertTrue(WebSocketRequestHandler.isWebSocketUpgradeRequest(handshakeRequest()));

        Request noUpgrade = handshakeRequest();
        noUpgrade.headers.put(Request.HEADER_CONNECTION, "keep-alive");
        assertFalse(WebSocketRequestHandler.isWebSocketUpgradeRequest(noUpgrade));
    }

    private static Response handle(WebSocketRequestHandler handler, Request request) throws Exception {
        return handler.handleRequest(new RequestContext(new DefaultRequestBodyReader()), request);
    }

    static Request handshakeRequest() {
        Request request = new Request();
        request.method = Request.METHOD_GET;
        request.path = "/ws";
        request.headers.put(Request.HEADER_HOST, "localhost");
        request.headers.put(WebSocketRequestHandler.HEADER_UPGRADE, "websocket");
        request.headers.put(Request.HEADER_CONNECTION, "Upgrade");
        request.headers.put(WebSocketRequestHandler.HEADER_SEC_WEBSOCKET_KEY, SAMPLE_KEY);
        request.headers.put(WebSocketRequestHandler.HEADER_SEC_WEBSOCKET_VERSION, "13");
        return request;
    }
}
