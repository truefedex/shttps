package com.phlox.server.websocket;

/**
 * Close status codes defined by RFC 6455 section 7.4.
 */
public final class WebSocketCloseCodes {
    public static final int NORMAL_CLOSURE = 1000;
    public static final int GOING_AWAY = 1001;
    public static final int PROTOCOL_ERROR = 1002;
    public static final int UNSUPPORTED_DATA = 1003;
    /** Not sent over the wire: "no status code was present in the close frame". */
    public static final int NO_STATUS_RECEIVED = 1005;
    /** Not sent over the wire: connection was lost without a close handshake. */
    public static final int ABNORMAL_CLOSURE = 1006;
    public static final int INVALID_FRAME_PAYLOAD_DATA = 1007;
    public static final int POLICY_VIOLATION = 1008;
    public static final int MESSAGE_TOO_BIG = 1009;
    public static final int MANDATORY_EXTENSION = 1010;
    public static final int INTERNAL_SERVER_ERROR = 1011;
    /** Not sent over the wire: TLS handshake failure. */
    public static final int TLS_HANDSHAKE_FAILURE = 1015;

    private WebSocketCloseCodes() {}

    /**
     * Whether a status code is allowed to appear in a close frame payload. 1005, 1006 and 1015
     * are reserved for local use only, and codes outside the defined ranges are protocol errors.
     */
    public static boolean isAllowedOnWire(int code) {
        if (code >= 3000 && code <= 4999) {
            //3000-3999 registered with IANA, 4000-4999 private use
            return true;
        }
        if (code < 1000 || code > 1011) {
            return false;
        }
        return code != NO_STATUS_RECEIVED && code != ABNORMAL_CLOSURE;
    }
}
