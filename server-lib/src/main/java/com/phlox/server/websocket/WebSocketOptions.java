package com.phlox.server.websocket;

/**
 * Tunables of a WebSocket endpoint. Defaults are chosen to be safe for a small embedded
 * server: every connection holds one thread and buffers one message in memory.
 */
public class WebSocketOptions {
    /**
     * Biggest payload accepted in a single frame. A bigger frame closes the connection
     * with {@link WebSocketCloseCodes#MESSAGE_TOO_BIG}.
     */
    public volatile long maxFramePayloadLength = 1024 * 1024;

    /**
     * Biggest complete (possibly fragmented) message accepted. Since a message is buffered in
     * memory before it is delivered, this is the memory an idle-but-talking peer can make the
     * server allocate per connection.
     */
    public volatile long maxMessagePayloadLength = 1024 * 1024;

    /**
     * Outgoing messages longer than this are split into fragments. Zero or less sends every
     * message as a single frame.
     */
    public volatile int outgoingFrameLength = 64 * 1024;

    /**
     * How long a connection may stay silent before the server sends a ping to check that the
     * peer is still there. Zero or less disables both the pings and the idle detection, which
     * means a dead connection keeps its thread until the OS notices - only do that if
     * something else terminates idle connections.
     */
    public volatile int idlePingIntervalMillis = 30000;

    /**
     * How many pings in a row may stay unanswered before the connection is considered dead.
     */
    public volatile int maxUnansweredPings = 2;

    /**
     * How long to wait for the peer's answer to a close frame we sent before dropping
     * the connection.
     */
    public volatile int closeHandshakeTimeoutMillis = 5000;
}
