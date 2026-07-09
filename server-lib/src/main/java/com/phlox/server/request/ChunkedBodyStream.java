package com.phlox.server.request;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Body framed by "Transfer-Encoding: chunked" (RFC 7230 section 4.1):
 * a sequence of chunks, each prefixed by its size in hex on its own line,
 * terminated by a zero-sized chunk and optional trailer headers (discarded).
 * Reports EOF after the terminal chunk, never reads past it and never closes
 * the connection stream.
 * <p>
 * A malformed chunk header is unrecoverable (the stream position can not be
 * trusted anymore) and raises an IOException; the server closes the connection.
 * If the client closes the connection mid-body, the stream reports EOF and
 * counts as fully consumed - the connection is dead at that point anyway.
 */
public class ChunkedBodyStream extends BodyInputStream {
    //limit for a single chunk-size/extension/trailer line, to bound memory on abuse
    private static final int MAX_LINE_LENGTH = 1024;

    private final InputStream base;
    private long remainingInChunk = 0;
    private boolean firstChunk = true;
    private volatile boolean finished = false;

    public ChunkedBodyStream(InputStream base) {
        this.base = base;
    }

    @Override
    public int read() throws IOException {
        if (!ensureChunkData()) {
            return -1;
        }
        int b = base.read();
        if (b == -1) {
            finished = true;
        } else {
            remainingInChunk--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!ensureChunkData()) {
            return -1;
        }
        int count = base.read(b, off, (int) Math.min(len, remainingInChunk));
        if (count == -1) {
            finished = true;
        } else {
            remainingInChunk -= count;
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(base.available(), remainingInChunk);
    }

    @Override
    public boolean isFullyConsumed() {
        return finished;
    }

    @Override
    public boolean drainRemaining(long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long drained = 0;
        while (!finished && drained < maxBytes) {
            int count = read(buffer, 0, (int) Math.min(buffer.length, maxBytes - drained));
            if (count == -1) {
                break;
            }
            drained += count;
        }
        return finished;
    }

    @Override
    public void close() {
        //intentionally does not close the underlying connection stream
    }

    /**
     * Positions the stream on chunk payload data, decoding chunk framing as needed.
     *
     * @return false when the terminal chunk has been reached (body EOF)
     */
    private boolean ensureChunkData() throws IOException {
        if (finished) {
            return false;
        }
        while (remainingInChunk == 0) {
            if (!firstChunk) {
                //consume the CRLF that terminates the previous chunk's data
                String crlf = readLine();
                if (!crlf.isEmpty()) {
                    throw new IOException("Malformed chunked body: expected CRLF after chunk data");
                }
            }
            long size = parseChunkSize(readLine());
            firstChunk = false;
            if (size == 0) {
                //terminal chunk: discard optional trailer headers up to the empty line
                while (!readLine().isEmpty()) {
                    //ignore trailers
                }
                finished = true;
                return false;
            }
            remainingInChunk = size;
        }
        return true;
    }

    private long parseChunkSize(String line) throws IOException {
        //chunk extensions (";name=value") are allowed and ignored
        int extensionStart = line.indexOf(';');
        String hex = (extensionStart >= 0 ? line.substring(0, extensionStart) : line).trim();
        long size;
        try {
            size = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed chunked body: invalid chunk size \"" + hex + "\"");
        }
        if (size < 0) {
            throw new IOException("Malformed chunked body: negative chunk size");
        }
        return size;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = base.read();
            if (b == -1) {
                throw new IOException("Malformed chunked body: unexpected end of stream");
            }
            if (b == '\n') {
                break;
            }
            if (line.size() >= MAX_LINE_LENGTH) {
                throw new IOException("Malformed chunked body: line too long");
            }
            line.write(b);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
