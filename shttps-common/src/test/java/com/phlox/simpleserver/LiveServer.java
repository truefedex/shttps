package com.phlox.simpleserver;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A real {@link SHTTPSApp} on a local port over a temporary root folder, and a raw socket client
 * that can also hang up half way through a request. Tests that need what the handlers do with
 * an actual request body, not just what a helper does in isolation.
 */
final class LiveServer implements AutoCloseable {
    final Path root;
    final int port;
    final TestDBEnvironment.Config config;

    private LiveServer(Path root, int port, TestDBEnvironment.Config config) {
        this.root = root;
        this.port = port;
        this.config = config;
    }

    static LiveServer start(Path temp, Consumer<SHTTPSConfig> configure) throws Exception {
        Path root = Files.createDirectories(temp.resolve("root"));
        TestDBEnvironment.Config config = new TestDBEnvironment.Config() {
            @Override public DocumentFile getRootDir() { return DocumentFile.fromFile(root.toFile()); }
            @Override public void setRootDir(String value) { }
        };
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        config.setPort(port);
        config.setAllowEditing(true);
        config.setWebDavSupport(true);
        configure.accept(config);
        SHTTPSApp app = SHTTPSApp.init(config, new Platform(root), fabric());
        app.startServer();
        return new LiveServer(root, port, config);
    }

    @Override
    public void close() {
        SHTTPSApp.destroy();
    }

    /** Sends the request and returns the status line, e.g. {@code HTTP/1.1 204 No Content}. */
    String exchange(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(concat(head.getBytes(StandardCharsets.UTF_8), body));
            socket.getOutputStream().flush();
            return statusLine(socket.getInputStream());
        }
    }

    /** Sends the request and returns the status line and headers, up to the blank line. */
    String exchangeHead(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(concat(head.getBytes(StandardCharsets.UTF_8), body));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            StringBuilder result = new StringBuilder();
            String line;
            while (!(line = statusLine(in)).isEmpty()) {
                result.append(line).append('\n');
            }
            return result.toString();
        }
    }

    /** The value of {@code name} in a head returned by {@link #exchangeHead}, or null. */
    static String header(String head, String name) {
        for (String line : head.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Sends the request, closes the sending half, and still reads the answer. */
    String exchangeHalfClosed(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(concat(head.getBytes(StandardCharsets.UTF_8), body));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            return statusLine(socket.getInputStream());
        }
    }

    /** Sends part of a request and goes away without reading anything. */
    void sendAndHangUp(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(concat(head.getBytes(StandardCharsets.UTF_8), body));
            socket.getOutputStream().flush();
        }
    }

    static String basicAuth(String user, String password) {
        return "Authorization: Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)) + "\r\n";
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String statusLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            line.write(b);
        }
        return line.toString(StandardCharsets.US_ASCII).trim();
    }

    boolean onlyFiles(String... names) {
        String[] actual = root.toFile().list();
        if (actual == null) return false;
        Arrays.sort(actual);
        String[] expected = names.clone();
        Arrays.sort(expected);
        return Arrays.equals(expected, actual);
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    /** The server finishes an abandoned request on its own thread after the socket is gone. */
    static boolean eventually(Condition condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.holds() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        return condition.holds();
    }

    private static SHTTPSDatabaseFabric fabric() {
        return new SHTTPSDatabaseFabric() {
            @Override public Database createDatabase(String path) { throw new UnsupportedOperationException(); }
            @Override public Database openDatabase(String path) { throw new UnsupportedOperationException(); }
            @Override public Database importDatabase(InputStream inputStream) { throw new UnsupportedOperationException(); }
            @Override public boolean deleteDatabase(String path) { throw new UnsupportedOperationException(); }
        };
    }

    /** A desktop-like platform: file URIs are opened as files. */
    private static final class Platform implements SHTTPSPlatformUtils {
        private final Path root;

        Platform(Path root) {
            this.root = root;
        }

        @Override public String getMimeType(String uri) { return null; }

        @Override
        public InputStream openInputStream(String uri) throws IOException {
            return Files.newInputStream(new File(URI.create(uri)).toPath());
        }

        @Override
        public OutputStream openOutputStream(String fileUri) throws IOException {
            return new FileOutputStream(new File(URI.create(fileUri)));
        }

        @Override
        public ImageData getImageThumbnail(String uri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public InputStream openAssetStream(String fileName) throws IOException {
            Path asset = findAsset(fileName);
            if (asset == null) {
                throw new IOException("Asset not found: " + fileName);
            }
            return Files.newInputStream(asset);
        }

        private static Path findAsset(String fileName) {
            Path[] candidates = new Path[] {
                    Path.of("shared-assets", fileName),
                    Path.of("shttps-common", "shared-assets", fileName),
                    Path.of("..", "shttps-common", "shared-assets", fileName)
            };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        /** A real size, so the asset handler serves the bundled web UI as it does in an app. */
        @Override public long getAssetSize(String fileName) {
            Path asset = findAsset(fileName);
            try {
                return asset == null ? -1 : Files.size(asset);
            } catch (IOException e) {
                return -1;
            }
        }

        /** -1, like the desktop build of old: the case the asset handler must still validate. */
        @Override public long getAssetLastModified(String fileName) { return -1; }
        @Override public boolean isThumbnailsSupported() { return false; }
        @Override public Set<NetworkInterface> findInterfaces(String[] allowedInterfaces) { return null; }
        @Override public DocumentFile getDefaultRootDir() { return DocumentFile.fromFile(root.toFile()); }
        @Override public ImageData generateCaptchaImage(String code, int width, int height) { return null; }
        @Override public XmlSerializer newXMLSerializer() { return null; }
        @Override public XmlPullParser newXMLPullParser() { return null; }
    }
}
