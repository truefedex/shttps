package com.phlox.server.responses;

import com.phlox.server.utils.HTMLTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class HTMLTemplateResponse extends Response {
    private final byte[] templateOutput;

    public HTMLTemplateResponse(String template, Object data) {
        super();
        this.templateOutput = new HTMLTemplate(template).process(data).getBytes();
        setContentType("text/html");
        setContentLength(templateOutput.length);
    }

    @Override
    public ByteArrayInputStream getStream() {
        return new ByteArrayInputStream(templateOutput);
    }
}
