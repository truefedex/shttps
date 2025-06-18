package com.phlox.server.responses;

import com.phlox.server.utils.HTMLTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class HTMLTemplateResponse extends Response {
    private final byte[] templateOutput;

    public HTMLTemplateResponse(String template, Object data) {
        super();
        this.templateOutput = new HTMLTemplate(template).process(data).getBytes(StandardCharsets.UTF_8);
        setContentType("text/html; charset=utf-8");
        setContentLength(templateOutput.length);
    }

    @Override
    public ByteArrayInputStream getStream() {
        return new ByteArrayInputStream(templateOutput);
    }
}
