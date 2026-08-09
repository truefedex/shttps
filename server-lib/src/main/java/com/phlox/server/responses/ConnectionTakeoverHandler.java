package com.phlox.server.responses;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Takes ownership of a connection after an {@link UpgradeResponse} was sent, so that the
 * conversation can continue with a protocol that is not HTTP anymore (WebSocket, raw tunnel, ...).
 * <p>
 * It is called by the server on the connection's own thread, right after the response headers
 * were written and flushed, and the connection is never returned to the HTTP keep-alive loop:
 * whatever the implementation reads from or writes to the given streams is pure protocol data.
 * <p>
 * Contract notes for implementations:
 * <ul>
 *     <li>the method runs for as long as the connection lives - returning from it means
 *     "connection is done", after which the server closes the socket and reports it as closed;</li>
 *     <li>{@code input} is the very same stream the request headers were read from, so any
 *     bytes the client sent immediately after the handshake are already buffered in it -
 *     never create a new stream from the socket;</li>
 *     <li>the socket is handed over as-is, still carrying the HTTP keep-alive read timeout.
 *     An implementation that needs a different idle policy must set its own
 *     {@link Socket#setSoTimeout(int)};</li>
 *     <li>the streams must not be closed by the implementation, the server does that.</li>
 * </ul>
 */
@FunctionalInterface
public interface ConnectionTakeoverHandler {
    void onConnectionTakenOver(Socket socket, InputStream input, OutputStream output) throws Exception;
}
