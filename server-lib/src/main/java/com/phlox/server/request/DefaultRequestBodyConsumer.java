package com.phlox.server.request;

import com.phlox.server.utils.PayloadTooLargeException;
import com.phlox.server.utils.SizeLimitedByteArrayOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class DefaultRequestBodyConsumer implements RequestBodyConsumer {
    //TODO: make this configurable
    public static final int MAX_MULTIPART_DATA_SIZE = 1024 * 1024 * 10; // 10 MB
    /** All parts of one multipart body together: the per-part limit alone multiplies with the part count. */
    public static final long MAX_MULTIPART_TOTAL_SIZE = 1024L * 1024 * 32;

    private long multipartBudget = MAX_MULTIPART_TOTAL_SIZE;

    @Override
    public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws Exception {
        FormDataPart part = new FormDataPart(name, fileName, contentType, new BudgetedPartStream());
        request.multipartData.add(part);
        return part.data;
    }

    @Override
    public OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws Exception {
        RequestBodyImpl rBody = new RequestBodyImpl(new SizeLimitedByteArrayOutputStream(MAX_MULTIPART_DATA_SIZE));
        request.body = rBody;
        return rBody.baos;
    }

    /** A part buffer that also draws from the budget shared by all parts of the body. */
    private class BudgetedPartStream extends SizeLimitedByteArrayOutputStream {
        BudgetedPartStream() {
            super(MAX_MULTIPART_DATA_SIZE);
        }

        @Override
        public synchronized void write(int b) {
            reserve(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            reserve(len);
            super.write(b, off, len);
        }

        private void reserve(int len) {
            if (len > multipartBudget) {
                throw new PayloadTooLargeException("Multipart body exceeds the limit of " + MAX_MULTIPART_TOTAL_SIZE + " bytes");
            }
            multipartBudget -= len;
        }
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
        public long size() {
            return baos.size();
        }

        @Override
        public String toString() {
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
