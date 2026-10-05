package com.phlox.server.request;

import java.io.IOException;

/**
 * A request that can not be processed because of how it was sent - malformed, too big - rather
 * than because of what it asks for. The server answers it with {@link #code} and closes the
 * connection, since the position in the stream can no longer be trusted.
 */
public class BadRequestException extends IOException {
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_HEADER_FIELDS_TOO_LARGE = 431;
    public static final int CODE_URI_TOO_LONG = 414;

    public final int code;
    public final String phrase;

    public BadRequestException(int code, String phrase, String message) {
        super(message);
        this.code = code;
        this.phrase = phrase;
    }

    public BadRequestException(String message) {
        this(CODE_BAD_REQUEST, "Bad Request", message);
    }

    public BadRequestException(String message, Throwable cause) {
        this(message);
        initCause(cause);
    }

    public static BadRequestException headersTooLarge(String message) {
        return new BadRequestException(CODE_HEADER_FIELDS_TOO_LARGE, "Request Header Fields Too Large", message);
    }

    public static BadRequestException uriTooLong(String message) {
        return new BadRequestException(CODE_URI_TOO_LONG, "URI Too Long", message);
    }
}
