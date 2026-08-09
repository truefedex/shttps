package com.phlox.server.websocket;

import java.io.IOException;

/**
 * The peer violated the WebSocket protocol. Carries the status code the connection should be
 * closed with (see {@link WebSocketCloseCodes}).
 */
public class WebSocketProtocolException extends IOException {
    public final int closeCode;

    public WebSocketProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }
}
