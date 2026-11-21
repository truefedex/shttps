package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.database.TestDBEnvironment;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A start that never binds, a stop whose callback starts the server again, and a database swapped
 * under a live server. The callback used to deadlock: stop joined the accept thread while holding
 * the app monitor, and the accept thread is the one that runs the callback.
 */
@Timeout(20)
public class ServerLifecycleTest {
    private SHTTPSApp app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.callback = null;
            SHTTPSApp.destroy();
            app = null;
        }
    }

    @Test
    public void aFailedBindReleasesWhatStartCreated(@TempDir Path temp) throws Exception {
        RootConfig config = new RootConfig();
        app = SHTTPSApp.init(config, new TestPlatform(temp), fabric());
        try (ServerSocket busy = new ServerSocket(0)) {
            config.setPort(busy.getLocalPort());
            assertThrows(IOException.class, app::startServer);
        }
        assertFalse(app.isServerRunning());
        assertNull(app.getAuthManager());

        config.setPort(0);
        app.startServer();
        assertEventuallyRunning();
        assertTrue(app.getAuthManager() != null);
    }

    @Test
    public void aSecondStartLeavesTheRunningServerAlone(@TempDir Path temp) throws Exception {
        //two restarts racing each other both end in startServer. The second must neither bind a
        //second socket nor release what the first one built
        RootConfig config = new RootConfig();
        config.setPort(0);
        app = SHTTPSApp.init(config, new TestPlatform(temp), fabric());
        app.startServer();
        //no waiting: a start that has returned has to be visible as running at once
        assertTrue(app.isServerRunning());
        Object authManager = app.getAuthManager();

        app.startServer();

        assertTrue(app.isServerRunning());
        assertSame(authManager, app.getAuthManager());
    }

    @Test
    public void stoppingFromTheAcceptThreadCanStartAgain(@TempDir Path temp) throws Exception {
        RootConfig config = new RootConfig();
        config.setPort(0);
        app = SHTTPSApp.init(config, new TestPlatform(temp), fabric());
        CountDownLatch restarted = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        app.callback = new SHTTPSApp.Callback() {
            @Override
            public void onServerStopped() {
                try {
                    app.startServer();
                } catch (Throwable t) {
                    error.set(t);
                }
                restarted.countDown();
            }
        };
        app.startServer();
        assertEventuallyRunning();
        app.stopServer();
        assertTrue(restarted.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertTrue(app.isServerRunning());
    }

    @Test
    public void replacingTheDatabaseStopsTheServerBeforeClosingTheOldOne(@TempDir Path temp) throws Exception {
        RootConfig config = new RootConfig();
        config.setPort(0);
        app = SHTTPSApp.init(config, new TestPlatform(temp), fabric());
        AtomicBoolean closed = new AtomicBoolean();
        Database first = database(closed);
        app.setDatabase(first);
        app.startServer();
        assertEventuallyRunning();

        Database second = database(new AtomicBoolean());
        app.setDatabase(second);

        assertTrue(closed.get());
        assertSame(second, app.getDatabase());
        assertEventuallyRunning();
    }

    /** The accept thread sets the running flag after {@code startServer} has returned. */
    private void assertEventuallyRunning() throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!app.isServerRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue(app.isServerRunning());
    }

    private static Database database(AtomicBoolean closed) {
        return (Database) Proxy.newProxyInstance(Database.class.getClassLoader(),
                new Class<?>[]{Database.class}, (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }

    private static SHTTPSDatabaseFabric fabric() {
        return new SHTTPSDatabaseFabric() {
            @Override public Database createDatabase(String path) { throw new UnsupportedOperationException(); }
            @Override public Database openDatabase(String path) { throw new UnsupportedOperationException(); }
            @Override public Database importDatabase(InputStream inputStream) { throw new UnsupportedOperationException(); }
            @Override public boolean deleteDatabase(String path) { throw new UnsupportedOperationException(); }
        };
    }

    /** Remembers the root the app constructor writes back. */
    private static final class RootConfig extends TestDBEnvironment.Config {
        private String root;

        @Override
        public DocumentFile getRootDir() {
            return root == null ? null : DocumentFile.fromFile(new File(root));
        }

        @Override
        public void setRootDir(String value) {
            this.root = value;
        }
    }

    /** Just enough of a platform for {@link SHTTPSApp#startServer()} to build the file browser. */
    private static final class TestPlatform implements SHTTPSPlatformUtils {
        private final Path root;

        TestPlatform(Path root) {
            this.root = root;
        }

        @Override public String getMimeType(String uri) { return null; }

        @Override
        public InputStream openInputStream(String uri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public OutputStream openOutputStream(String fileUri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public ImageData getImageThumbnail(String uri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public InputStream openAssetStream(String fileName) throws IOException {
            Path[] candidates = new Path[] {
                    Path.of("shared-assets", fileName),
                    Path.of("shttps-common", "shared-assets", fileName),
                    Path.of("..", "shttps-common", "shared-assets", fileName)
            };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return Files.newInputStream(candidate);
                }
            }
            throw new IOException("Asset not found: " + fileName + " from " + Path.of("").toAbsolutePath());
        }

        @Override public long getAssetSize(String fileName) { return -1; }
        @Override public long getAssetLastModified(String fileName) { return -1; }
        @Override public boolean isThumbnailsSupported() { return false; }
        @Override public Set<NetworkInterface> findInterfaces(String[] allowedInterfaces) { return null; }
        @Override public DocumentFile getDefaultRootDir() { return DocumentFile.fromFile(root.toFile()); }
        @Override public ImageData generateCaptchaImage(String code, int width, int height) { return null; }
        @Override public XmlSerializer newXMLSerializer() { return null; }
        @Override public XmlPullParser newXMLPullParser() { return null; }
    }
}
