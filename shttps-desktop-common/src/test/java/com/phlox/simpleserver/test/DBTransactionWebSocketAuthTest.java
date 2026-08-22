package com.phlox.simpleserver.test;

import com.phlox.server.SHTTPSConfigImpl;
import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The socket's commands are adapters over the same operations the HTTP endpoints use, so they must
 * enforce the same rights. This is the test that says so: the checks are not repeated in the
 * adapters, and a change that quietly bypassed them would look fine everywhere else.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DBTransactionWebSocketAuthTest {
    private static final String TEST_TABLE = "auth_tx_table";
    private static final String ADMIN = "admin";
    private static final String READER = "reader";
    /** May read, but holds no USE_TRANSACTION, so may not open a session at all. */
    private static final String NO_TRANSACTIONS = "no_transactions";
    private static final String PASSWORD = "password123";

    private SHTTPSApp app;
    private SHTTPSConfigImpl config;
    private String baseUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        config = new SHTTPSConfigImpl(tempDir.resolve("config.json").toFile());
        config.setPort(8080);
        config.setRootDir(tempDir.toAbsolutePath().toString());
        config.setDatabaseEnabled(true);
        config.setDatabasePath(tempDir.resolve("database.sqlite").toAbsolutePath().toString());
        config.setAllowDatabaseTableDataEditingApi(true);
        config.setAllowDatabaseCustomSqlRemoteApi(true);
        //basic auth, so the handshake can carry credentials in a header
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        config.setUsers(users());
        baseUrl = "http://127.0.0.1:8080";

        app = SHTTPSApp.init(config, new PlatformUtilsImpl(), new DatabaseFabricImpl());
        app.initIO();
        app.startServer();
        //not what this class is testing, and it opens a session per test
        app.dbTransactionRateLimitingMiddleware.setMaxRequests(Integer.MAX_VALUE);
        //created directly, so the test does not depend on the very rights it is checking
        app.getDatabase().execute("CREATE TABLE IF NOT EXISTS " + TEST_TABLE +
                " (id INTEGER PRIMARY KEY, name TEXT)");
        app.getDatabase().execute("INSERT INTO " + TEST_TABLE + " (name) VALUES ('existing')");
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            if (app.isServerRunning()) {
                app.stopServer();
            }
            SHTTPSApp.destroy();
        }
    }

    private static List<User> users() {
        List<User> users = new ArrayList<>();
        users.add(new User(ADMIN, Utils.sha256(Utils.hashFNV1a32(PASSWORD)), null,
                EnumSet.allOf(User.FileSystemRights.class),
                EnumSet.allOf(User.DBRights.class), null,
                System.currentTimeMillis(), null, null,
                EnumSet.of(User.SystemRights.READ_STATUS), 0));
        //may open a session and read within it, but nothing more - no CREATE, no EXEC_SQL
        users.add(new User(READER, Utils.sha256(Utils.hashFNV1a32(PASSWORD)), null,
                EnumSet.of(User.FileSystemRights.READ),
                EnumSet.of(User.DBRights.READ, User.DBRights.USE_TRANSACTION), null,
                System.currentTimeMillis(), null, null,
                EnumSet.of(User.SystemRights.READ_STATUS), 0));
        //the same, minus the right to open a transaction at all
        users.add(new User(NO_TRANSACTIONS, Utils.sha256(Utils.hashFNV1a32(PASSWORD)), null,
                EnumSet.of(User.FileSystemRights.READ),
                EnumSet.of(User.DBRights.READ, User.DBRights.CREATE), null,
                System.currentTimeMillis(), null, null,
                EnumSet.of(User.SystemRights.READ_STATUS), 0));
        return users;
    }

    private AuthTxClient connectAs(String username) throws Exception {
        return new AuthTxClient(baseUrl, username, PASSWORD);
    }

    /** Same shape as the other socket test's client, plus credentials on the handshake. */
    private static class AuthTxClient implements AutoCloseable, WebSocket.Listener {
        final BlockingQueue<JSONObject> frames = new LinkedBlockingQueue<>();
        final AtomicReference<int[]> closed = new AtomicReference<>(null);
        private final StringBuilder partial = new StringBuilder();
        private final WebSocket webSocket;
        private final AtomicInteger nextId = new AtomicInteger(1);

        AuthTxClient(String baseUrl, String username, String password) throws Exception {
            String credentials = Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .header("Authorization", "Basic " + credentials)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(baseUrl.replaceFirst("^http", "ws") + "/api/db/transaction"), this)
                    .get(10, TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(new JSONObject(partial.toString()));
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed.set(new int[]{statusCode});
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.compareAndSet(null, new int[]{-1});
        }

        void send(JSONObject frame) {
            frame.put("id", nextId.getAndIncrement());
            webSocket.sendText(frame.toString(), true).join();
        }

        JSONObject nextFrame() throws InterruptedException {
            JSONObject frame = frames.poll(15, TimeUnit.SECONDS);
            assertNotNull(frame, "no frame arrived");
            return frame;
        }

        int awaitClose() throws InterruptedException {
            for (int i = 0; i < 300; i++) {
                int[] code = closed.get();
                if (code != null) return code[0];
                Thread.sleep(50);
            }
            throw new AssertionError("the server never closed the connection");
        }

        @Override
        public void close() {
            try {
                webSocket.abort();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * The point of the session-level right: a user without it is turned away at the handshake,
     * before anything has been claimed.
     */
    @Test
    void aUserWithoutTheTransactionRightIsRefusedAtTheHandshake() throws Exception {
        try (AuthTxClient client = connectAs(NO_TRANSACTIONS)) {
            assertEquals(4403, client.awaitClose());
            //no frame at all - the session never got as far as accepting a command
            assertNull(client.frames.poll(1, TimeUnit.SECONDS));
        }

        //and this is what the whole change is for: the refusal happened before the transaction
        //started, so the single database writer was never taken. If the check had been made one
        //step later, this insert would queue behind an open transaction instead of succeeding.
        long start = System.currentTimeMillis();
        app.getDatabase().insert(TEST_TABLE, new JSONObject().put("name", "right after the refusal"));
        assertTrue(System.currentTimeMillis() - start < 1000,
                "the writer was still held after a refused handshake");
    }

    /** A user who does hold it can still be refused an individual command. */
    @Test
    void theSessionRightDoesNotReplaceThePerCommandChecks() throws Exception {
        try (AuthTxClient client = connectAs(READER)) {
            client.send(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            assertTrue(client.nextFrame().getBoolean("ok"), "reader may select");

            client.send(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "not allowed")));
            JSONObject refused = client.nextFrame();
            assertFalse(refused.getBoolean("ok"), refused.toString());
            assertEquals("FORBIDDEN", refused.getJSONObject("error").getString("kind"));
        }
    }

    @Test
    void aUserWithEveryRightCanInsertAndCommit() throws Exception {
        try (AuthTxClient client = connectAs(ADMIN)) {
            client.send(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "by admin")));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            client.send(new JSONObject().put("command", "commit"));
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
    }

    /** READ but no CREATE: the read is served and the write is refused. */
    @Test
    void aReaderMaySelectButMayNotInsert() throws Exception {
        try (AuthTxClient client = connectAs(READER)) {
            client.send(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            JSONObject read = client.nextFrame();
            assertTrue(read.getBoolean("ok"), read.toString());
            assertEquals(1, read.getJSONObject("result").getJSONArray("data").length());

            client.send(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "not allowed")));
            JSONObject refused = client.nextFrame();
            assertFalse(refused.getBoolean("ok"), refused.toString());
            assertEquals("FORBIDDEN", refused.getJSONObject("error").getString("kind"));
            //and the close code agrees with the frame, so a client watching only the socket
            //can still tell a refusal from a database failure
            assertEquals(4403, client.awaitClose());
        }
    }

    /** No EXEC_SQL either, so arbitrary SQL is not a way around the missing rights. */
    @Test
    void aReaderMayNotRunArbitrarySql() throws Exception {
        try (AuthTxClient client = connectAs(READER)) {
            client.send(new JSONObject().put("command", "query")
                    .put("sql", "INSERT INTO " + TEST_TABLE + " (name) VALUES ('smuggled')"));
            JSONObject refused = client.nextFrame();
            assertFalse(refused.getBoolean("ok"), refused.toString());
            assertEquals("FORBIDDEN", refused.getJSONObject("error").getString("kind"));
            //and the close code agrees with the frame, so a client watching only the socket
            //can still tell a refusal from a database failure
            assertEquals(4403, client.awaitClose());
        }
    }

    @Test
    void aReaderMayNotUpdateOrDelete() throws Exception {
        try (AuthTxClient client = connectAs(READER)) {
            client.send(new JSONObject().put("command", "delete").put("table", TEST_TABLE));
            JSONObject refused = client.nextFrame();
            assertFalse(refused.getBoolean("ok"), refused.toString());
            assertEquals("FORBIDDEN", refused.getJSONObject("error").getString("kind"));
            //and the close code agrees with the frame, so a client watching only the socket
            //can still tell a refusal from a database failure
            assertEquals(4403, client.awaitClose());
        }
    }
}
