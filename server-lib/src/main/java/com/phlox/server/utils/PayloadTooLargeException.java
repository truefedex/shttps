package com.phlox.server.utils;

/**
 * A request body - or a part of it - is larger than the server is willing to hold. Answered with
 * 413 Payload Too Large. Extends IllegalStateException, which is what size limits used to throw,
 * so existing catch blocks keep working.
 */
public class PayloadTooLargeException extends IllegalStateException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
