package com.phlox.server.request;

import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that represents exactly the payload octets of a single request body,
 * independent of how the body is framed on the wire (Content-Length or chunked
 * transfer encoding). Consumers simply read it until EOF and never have to know
 * about framing or the underlying connection stream.
 */
public abstract class BodyInputStream extends InputStream {
    private volatile boolean detached = false;

    /**
     * @return true when all body octets have been consumed from the underlying
     * connection stream (or when the request has no body at all)
     */
    public abstract boolean isFullyConsumed();

    /**
     * Reads and discards the remainder of the body, up to maxBytes.
     *
     * @return true if the body is fully consumed afterwards
     */
    public abstract boolean drainRemaining(long maxBytes) throws IOException;

    /**
     * Marks this body as handed off to another thread (e.g. a CGI stdin pump).
     * After this the server must not read or drain this stream itself.
     * May perform connection I/O (e.g. sending "100 Continue" while the current
     * thread is still the only writer), hence the IOException.
     */
    public void markDetached() throws IOException {
        detached = true;
    }

    public boolean isDetached() {
        return detached;
    }
}
