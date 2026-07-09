package com.phlox.server.request;

import java.io.IOException;

/**
 * Wrapper for the body of a request that carries "Expect: 100-continue"
 * (RFC 7231 section 5.1.1): the client sends the body only after receiving
 * an interim "100 Continue" response. The interim response is sent lazily,
 * right before the first actual read of the body - so handlers that reject
 * a request early (auth, locks, quota) never make the client upload the body.
 */
public class ExpectContinueBodyStream extends BodyInputStream {

    /** Sends the interim "HTTP/1.1 100 Continue" response to the client. */
    public interface ContinueTrigger {
        void sendContinue() throws IOException;
    }

    private final BodyInputStream delegate;
    private final ContinueTrigger trigger;
    private volatile boolean continueSent = false;

    public ExpectContinueBodyStream(BodyInputStream delegate, ContinueTrigger trigger) {
        this.delegate = delegate;
        this.trigger = trigger;
    }

    public boolean isContinueSent() {
        return continueSent;
    }

    private void ensureContinueSent() throws IOException {
        if (!continueSent) {
            continueSent = true;
            trigger.sendContinue();
        }
    }

    @Override
    public int read() throws IOException {
        ensureContinueSent();
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureContinueSent();
        return delegate.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        //until 100 Continue is sent the client is not sending anything
        return continueSent ? delegate.available() : 0;
    }

    @Override
    public boolean isFullyConsumed() {
        return delegate.isFullyConsumed();
    }

    @Override
    public boolean drainRemaining(long maxBytes) throws IOException {
        if (!continueSent) {
            //the client is still waiting for "100 Continue" and will not send the body;
            //don't provoke the upload just to discard it - report "not consumed" so
            //the server closes the connection instead
            return false;
        }
        return delegate.drainRemaining(maxBytes);
    }

    @Override
    public void markDetached() throws IOException {
        //the detaching thread (e.g. a CGI stdin pump) may read the body concurrently
        //with response writing; send the interim response now, while the current
        //thread is still the only writer on the connection
        ensureContinueSent();
        delegate.markDetached();
        super.markDetached();
    }

    @Override
    public void close() {
        //intentionally does not close the underlying connection stream
    }
}
