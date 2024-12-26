package com.phlox.server.request;

import com.phlox.server.utils.SizeLimitedByteArrayOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class DefaultBinaryDataConsumer implements BinaryDataConsumer {
    //TODO: make this configurable
    public static final int MAX_MULTIPART_DATA_SIZE = 1024 * 1024 * 10; // 10 MB

    @Override
    public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws IOException {
        FormDataPart part = new FormDataPart(name, fileName, contentType, new SizeLimitedByteArrayOutputStream(MAX_MULTIPART_DATA_SIZE));
        request.multipartData.add(part);
        return part.data;
    }

    @Override
    public OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws IOException {
        RequestBodyImpl rBody = new RequestBodyImpl(new SizeLimitedByteArrayOutputStream(MAX_MULTIPART_DATA_SIZE));
        request.body = rBody;
        return rBody.baos;
    }

    public static class RequestBodyImpl implements RequestBody {
        public ByteArrayOutputStream baos;

        public RequestBodyImpl(ByteArrayOutputStream dataHolder) {
            this.baos = dataHolder;
        }

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(baos.toByteArray());
        }

        @Override
        public byte[] asBytes() {
            return baos.toByteArray();
        }

        @Override
        public String toString() {
            return baos.toString();
        }
    }
}
