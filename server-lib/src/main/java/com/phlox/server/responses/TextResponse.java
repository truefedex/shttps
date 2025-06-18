package com.phlox.server.responses;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class TextResponse extends Response {
    public static final String MIME_TYPE_TEXT = "text/plain";

    public TextResponse(byte[] textBytes, String mimeType, int code, String phrase) {
        super(new ByteArrayInputStream(textBytes));
        setContentType(mimeType);
        setContentLength(textBytes.length);
        this.code = code;
        this.phrase = phrase;
    }

    public TextResponse(byte[] textBytes, String mimeType) {
        this(textBytes, mimeType, 200, "OK");
    }

    public TextResponse(String text, String mimeType) {
        this(text.getBytes(StandardCharsets.UTF_8), mimeType);
    }

    public TextResponse(String text) {
        this(text, MIME_TYPE_TEXT);
    }

    public TextResponse(int code, String phrase, String text) {
        this(code, phrase, text, MIME_TYPE_TEXT);
    }

    public TextResponse(int code, String phrase, String text, String mimeType) {
        this(text.getBytes(StandardCharsets.UTF_8), mimeType, code, phrase);
    }
}
