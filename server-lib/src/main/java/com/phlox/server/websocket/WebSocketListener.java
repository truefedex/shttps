package com.phlox.server.websocket;

/**
 * Receives the events of a single WebSocket connection. All methods are called on the
 * connection's own thread, one at a time, so an implementation only has to care about
 * synchronization if it touches state shared between connections.
 * <p>
 * Every method has a no-op default, implement only what is needed. An exception thrown by any
 * of the message callbacks is reported to {@link #onError} and closes the connection with
 * {@link WebSocketCloseCodes#INTERNAL_SERVER_ERROR}.
 */
public interface WebSocketListener {
    /**
     * The handshake is done and the connection is ready to send and receive.
     */
    default void onOpen(WebSocketSession session) throws Exception {}

    default void onTextMessage(WebSocketSession session, String message) throws Exception {}

    default void onBinaryMessage(WebSocketSession session, byte[] message) throws Exception {}

    /**
     * A ping arrived. The pong answer has already been sent by the session.
     */
    default void onPing(WebSocketSession session, byte[] payload) throws Exception {}

    default void onPong(WebSocketSession session, byte[] payload) throws Exception {}

    /**
     * The connection is gone. Called exactly once for a session that was opened, after which
     * nothing can be sent anymore.
     *
     * @param code   status code from the close handshake, or
     *               {@link WebSocketCloseCodes#ABNORMAL_CLOSURE} when there was none
     * @param reason human readable reason, may be empty
     */
    default void onClose(WebSocketSession session, int code, String reason) {}

    /**
     * Something went wrong: an I/O failure, a protocol violation by the peer or an exception
     * thrown by one of the other callbacks. Always followed by {@link #onClose}.
     */
    default void onError(WebSocketSession session, Throwable error) {}
}
