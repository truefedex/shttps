package com.phlox.server.websocket;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.platform.Base64;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.UpgradeResponse;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for a WebSocket endpoint: validates the handshake, answers it with
 * "101 Switching Protocols" and then runs the connection, reporting everything that happens on
 * it through the {@link WebSocketListener} methods, which subclasses override.
 * <p>
 * It is a plain {@link RequestHandler}, so an endpoint is registered like any other route and
 * gets the same treatment from the middlewares in front of it - the handshake is an ordinary
 * GET request, which means authentication, rate limiting and the rest all still apply:
 * <pre>
 * router.addRoute("/api/events", Set.of(Request.METHOD_GET), new WebSocketRequestHandler() {
 *     public void onTextMessage(WebSocketSession session, String message) throws IOException {
 *         session.sendText("you said: " + message);
 *     }
 * }, authMiddlewares);
 * </pre>
 * Every connection occupies the thread it was accepted on for as long as it lives, so this
 * suits the "a handful of clients" scale this server is built for, not thousands of them.
 * <p>
 * Note on security: by default a handshake is accepted regardless of the {@code Origin} header,
 * which is what non-browser clients need. An endpoint that relies on cookie based
 * authentication should override {@link #isOriginAllowed} - unlike XHR, a WebSocket handshake
 * from another origin is not blocked by the browser, so a foreign page can otherwise open an
 * authenticated connection with the user's cookies.
 */
public abstract class WebSocketRequestHandler implements RequestHandler, WebSocketListener {
    private static final Logger logger = SHTTPSLoggerProxy.getLogger(WebSocketRequestHandler.class);

    public static final int SUPPORTED_VERSION = 13;
    public static final String UPGRADE_TOKEN_WEBSOCKET = "websocket";
    /** Magic value from RFC 6455 used to derive the handshake answer. */
    public static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    //request headers are lower-cased by the request parser
    public static final String HEADER_UPGRADE = "upgrade";
    public static final String HEADER_SEC_WEBSOCKET_KEY = "sec-websocket-key";
    public static final String HEADER_SEC_WEBSOCKET_VERSION = "sec-websocket-version";
    public static final String HEADER_SEC_WEBSOCKET_PROTOCOL = "sec-websocket-protocol";

    public static final String RESPONSE_HEADER_SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept";
    public static final String RESPONSE_HEADER_SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    public static final String RESPONSE_HEADER_SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version";

    public final WebSocketOptions options = new WebSocketOptions();

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!Request.METHOD_GET.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        if (!isWebSocketUpgradeRequest(request)) {
            return upgradeRequired("This endpoint only accepts WebSocket connections");
        }

        String versionHeader = request.headers.get(HEADER_SEC_WEBSOCKET_VERSION);
        if (versionHeader == null || !versionHeader.trim().equals(Integer.toString(SUPPORTED_VERSION))) {
            //RFC 6455 4.2.2: tell the client which version we do speak
            return upgradeRequired("Unsupported WebSocket version: " + versionHeader);
        }

        String key = request.headers.get(HEADER_SEC_WEBSOCKET_KEY);
        if (key == null || !isValidHandshakeKey(key.trim())) {
            return StandardResponses.BAD_REQUEST("Missing or malformed Sec-WebSocket-Key header");
        }

        if (!isOriginAllowed(request, request.headers.get(Request.HEADER_ORIGIN))) {
            return StandardResponses.FORBIDDEN("WebSocket connections from this origin are not allowed");
        }

        List<String> offeredSubProtocols = parseOfferedSubProtocols(request);
        final String subProtocol = selectSubProtocol(request, offeredSubProtocols);
        if (subProtocol != null && !offeredSubProtocols.contains(subProtocol)) {
            logger.w("Selected WebSocket sub-protocol '" + subProtocol + "' was not offered by the client");
        }

        UpgradeResponse response = UpgradeResponse.switchingProtocols(UPGRADE_TOKEN_WEBSOCKET,
                (socket, input, output) -> {
                    WebSocketSession session = new WebSocketSession(socket, input, output,
                            request, subProtocol, options);
                    //the request context does not survive into the session, so this is the last
                    //chance to take anything the middlewares left in it along
                    onSessionCreated(context, session);
                    runSession(session);
                });
        response.headers.add(RESPONSE_HEADER_SEC_WEBSOCKET_ACCEPT, computeAcceptKey(key.trim()));
        if (subProtocol != null) {
            response.headers.add(RESPONSE_HEADER_SEC_WEBSOCKET_PROTOCOL, subProtocol);
        }
        //no Sec-WebSocket-Extensions in the answer: not offering any extension is always valid
        onHandshakeAccepted(context, request, response);
        return response;
    }

    /**
     * Runs a connection from the first frame to the last, making sure the listener callbacks
     * are balanced: {@link #onClose} happens exactly once for every {@link #onOpen} that
     * returned normally.
     */
    protected void runSession(WebSocketSession session) {
        boolean opened = false;
        try {
            onOpen(session);
            opened = true;
            session.runReadLoop(this);
        } catch (Throwable t) {
            if (t instanceof WebSocketProtocolException) {
                logger.w("WebSocket protocol violation by " + session.getRemoteAddress() + ": " + t.getMessage());
            } else if (t instanceof IOException) {
                logger.d("WebSocket connection with " + session.getRemoteAddress() + " failed: " + t);
            } else {
                logger.stackTrace(t);
            }
            try {
                onError(session, t);
            } catch (Throwable ignored) {
            }
        } finally {
            if (opened) {
                try {
                    onClose(session, session.getCloseCode(), session.getCloseReason());
                } catch (Throwable t) {
                    logger.stackTrace(t);
                }
            }
        }
    }

    /**
     * A request is a handshake if it asks to upgrade to "websocket" and says so in the
     * Connection header, which is a comma separated token list.
     */
    public static boolean isWebSocketUpgradeRequest(Request request) {
        String upgrade = request.headers.get(HEADER_UPGRADE);
        if (upgrade == null || !UPGRADE_TOKEN_WEBSOCKET.equalsIgnoreCase(upgrade.trim())) {
            return false;
        }
        return containsToken(request.headers.get(Request.HEADER_CONNECTION), "upgrade");
    }

    /**
     * The answer to the client's challenge: base64(sha1(key + magic guid)), see RFC 6455 4.2.2.
     */
    public static String computeAcceptKey(String secWebSocketKey) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((secWebSocketKey + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.encodeToString(digest);
    }

    /**
     * Called after the handshake was accepted and before it is sent, so that subclasses can add
     * their own headers to the response (cookies, for example). Does nothing by default.
     */
    protected void onHandshakeAccepted(RequestContext context, Request request, UpgradeResponse response) throws Exception {
    }

    /**
     * Called on the connection thread once the session exists and before {@link #onOpen} sees it,
     * with the context of the request that carried the handshake. This is where whatever the
     * middlewares put into {@link RequestContext#data} - the authenticated user, above all - can be
     * copied into the session's attributes: the context belongs to the request and is gone by the
     * time the first frame arrives. Does nothing by default.
     */
    protected void onSessionCreated(RequestContext context, WebSocketSession session) {
    }

    /**
     * Whether a handshake carrying this {@code Origin} header may be accepted. Accepts
     * everything by default, including requests without an Origin header at all (non-browser
     * clients do not send one).
     *
     * @param origin value of the Origin header, or null if the client did not send it
     */
    protected boolean isOriginAllowed(@NotNull Request request, @Nullable String origin) {
        return true;
    }

    /**
     * Picks one of the sub-protocols the client offers, or returns null to negotiate none
     * (the default). Returning a value that was not offered is a protocol violation, the
     * client is expected to drop such a connection.
     *
     * @param offeredSubProtocols what the client listed in Sec-WebSocket-Protocol, may be empty
     */
    @Nullable
    protected String selectSubProtocol(@NotNull Request request, @NotNull List<String> offeredSubProtocols) {
        return null;
    }

    /**
     * Sends a message to a group of sessions, dropping the ones that turn out to be dead.
     * Handy for the typical "broadcast to everyone who is listening" case.
     */
    public static void broadcastText(Iterable<WebSocketSession> sessions, String message) {
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendText(message);
            } catch (IOException e) {
                logger.d("Broadcast to " + session.getRemoteAddress() + " failed: " + e.getMessage());
                session.close(WebSocketCloseCodes.ABNORMAL_CLOSURE, "");
            }
        }
    }

    private static List<String> parseOfferedSubProtocols(Request request) {
        List<String> values = request.headers.getAll(HEADER_SEC_WEBSOCKET_PROTOCOL);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static boolean containsToken(String headerValue, String token) {
        if (headerValue == null) {
            return false;
        }
        for (String candidate : headerValue.split(",")) {
            if (token.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The key is a freshly generated 16 byte nonce in base64. Checking it costs nothing and
     * makes it harder to trick the server into upgrading a request that was not a handshake.
     */
    private static boolean isValidHandshakeKey(String key) {
        try {
            return Base64.decode(key).length == 16;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Response upgradeRequired(String message) {
        Response response = StandardResponses.BAD_REQUEST(message);
        response.code = 426;
        response.phrase = "Upgrade Required";
        response.headers.add(UpgradeResponse.HEADER_UPGRADE, UPGRADE_TOKEN_WEBSOCKET);
        response.headers.add(Response.HEADER_CONNECTION, UpgradeResponse.CONNECTION_UPGRADE);
        response.headers.add(RESPONSE_HEADER_SEC_WEBSOCKET_VERSION, Integer.toString(SUPPORTED_VERSION));
        return response;
    }
}
