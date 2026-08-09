package com.phlox.server.responses;

/**
 * A response that switches the connection to another protocol (typically "101 Switching
 * Protocols"). The server writes the headers of this response as usual - without adding
 * Content-Length or keep-alive headers, since HTTP framing ends here - and then hands the
 * connection over to {@link #getTakeoverHandler()}.
 */
public class UpgradeResponse extends Response {
    public static final int CODE_SWITCHING_PROTOCOLS = 101;
    public static final String PHRASE_SWITCHING_PROTOCOLS = "Switching Protocols";
    public static final String HEADER_UPGRADE = "Upgrade";
    public static final String CONNECTION_UPGRADE = "Upgrade";

    private final ConnectionTakeoverHandler takeoverHandler;

    public UpgradeResponse(int code, String phrase, ConnectionTakeoverHandler takeoverHandler) {
        //deliberately uses the stream constructor: an upgrade response must not carry a
        //Content-Length header, the bytes after the headers belong to the new protocol
        super(code, phrase, null);
        if (takeoverHandler == null) {
            throw new IllegalArgumentException("takeoverHandler can not be null");
        }
        this.takeoverHandler = takeoverHandler;
    }

    /**
     * Builds a "101 Switching Protocols" response with the {@code Upgrade} and
     * {@code Connection: Upgrade} headers already set.
     *
     * @param protocol value of the Upgrade header, e.g. "websocket"
     */
    public static UpgradeResponse switchingProtocols(String protocol, ConnectionTakeoverHandler takeoverHandler) {
        UpgradeResponse response = new UpgradeResponse(CODE_SWITCHING_PROTOCOLS,
                PHRASE_SWITCHING_PROTOCOLS, takeoverHandler);
        response.headers.add(HEADER_UPGRADE, protocol);
        response.headers.add(HEADER_CONNECTION, CONNECTION_UPGRADE);
        return response;
    }

    public ConnectionTakeoverHandler getTakeoverHandler() {
        return takeoverHandler;
    }
}
