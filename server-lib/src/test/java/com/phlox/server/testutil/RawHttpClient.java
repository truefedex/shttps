package com.phlox.server.testutil;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An HTTP/1.1 client that writes exactly the bytes it is given and reads responses one by one
 * off a single connection - which is what testing the server's framing, keep-alive and
 * malformed-input handling needs, and what every real HTTP client library hides.
 */
public class RawHttpClient implements Closeable {
    private final Socket socket;
    private final OutputStream output;
    private final InputStream input;

    public RawHttpClient(int port) throws IOException {
        socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(10000);
        output = socket.getOutputStream();
        input = socket.getInputStream();
    }

    public RawHttpClient send(String raw) throws IOException {
        return send(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    public RawHttpClient send(byte[] raw) throws IOException {
        output.write(raw);
        output.flush();
        return this;
    }

    /** Sends a minimal {@code GET} with only a Host header. */
    public Response get(String target) throws IOException {
        send("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
        return readResponse();
    }

    public Response readResponse() throws IOException {
        return readResponse(false);
    }

    /**
     * @param noBody true for a response to HEAD, which carries headers of a body it never sends
     * @return the next response, or null if the server closed the connection before sending one
     */
    public Response readResponse(boolean noBody) throws IOException {
        String statusLine = readLine();
        if (statusLine == null) {
            return null;
        }
        Response response = new Response(statusLine);
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            response.headerLines.add(line);
        }
        int code = response.code;
        if (noBody || code / 100 == 1 || code == 204 || code == 304) {
            return response;
        }
        String contentLength = response.header("content-length");
        if (contentLength != null) {
            response.body = readExactly(Integer.parseInt(contentLength));
        } else {
            response.body = readToEnd();
        }
        return response;
    }

    /** Whether the server has closed the connection (EOF or reset), waiting up to the read timeout. */
    public boolean isClosedByServer() throws IOException {
        try {
            return input.read() == -1;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (SocketException e) {
            return true;
        }
    }

    public void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** One line without its line break; null at EOF before anything was read. */
    public String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        try {
            while ((b = input.read()) != -1) {
                any = true;
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    line.write(b);
                }
            }
        } catch (SocketException e) {
            //reset by the server counts as the end of the stream
        }
        return any ? line.toString("ISO-8859-1") : null;
    }

    private byte[] readExactly(int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count == -1) {
                throw new IOException("Connection closed after " + offset + " of " + length + " body bytes");
            }
            offset += count;
        }
        return body;
    }

    private byte[] readToEnd() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = input.read(buffer)) != -1) {
                body.write(buffer, 0, count);
            }
        } catch (SocketException e) {
            //reset after the body: the content is what counts
        }
        return body.toByteArray();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static class Response {
        public final String statusLine;
        public final int code;
        /** Header lines exactly as received, in order - injected lines show up here verbatim. */
        public final List<String> headerLines = new ArrayList<>();
        public byte[] body = new byte[0];

        Response(String statusLine) {
            this.statusLine = statusLine;
            String[] parts = statusLine.split(" ", 3);
            int parsed;
            try {
                parsed = Integer.parseInt(parts[1]);
            } catch (RuntimeException e) {
                parsed = -1;
            }
            this.code = parsed;
        }

        /** First value of a header, matched case-insensitively. */
        public String header(String name) {
            List<String> values = headers(name);
            return values.isEmpty() ? null : values.get(0);
        }

        public List<String> headers(String name) {
            List<String> values = new ArrayList<>();
            for (String line : headerLines) {
                int separator = line.indexOf(':');
                if (separator > 0 && line.substring(0, separator).trim().equalsIgnoreCase(name)) {
                    values.add(line.substring(separator + 1).trim());
                }
            }
            return Collections.unmodifiableList(values);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
