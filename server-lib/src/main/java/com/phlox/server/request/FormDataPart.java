package com.phlox.server.request;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class FormDataPart {
    public String name;
    public String fileName;
    public String contentType;
    ByteArrayOutputStream data;

    public FormDataPart(String name, String fileName, String contentType, ByteArrayOutputStream dataHolder) {
        this.name = name;
        this.fileName = fileName;
        this.contentType = contentType;
        this.data = dataHolder;
    }

    public byte[] getDataAsBytes() {
        return data.toByteArray();
    }

    public String getDataAsString() {
        return new String(data.toByteArray(), StandardCharsets.UTF_8);
    }
}
