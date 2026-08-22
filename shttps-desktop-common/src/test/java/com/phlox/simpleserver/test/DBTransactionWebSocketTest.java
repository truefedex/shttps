package com.phlox.simpleserver.test;

import com.phlox.server.SHTTPSConfigImpl;
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URLEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction endpoint over a real socket against a real database.
 * <p>
 * Everything has a timeout, because the failure these guard against is a transaction that never lets
 * go of the database's single write thread — a regression should fail the build rather than hang it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DBTransactionWebSocketTest {
    private static final String TEST_DATABASE = "test.db";
    private static final String TEST_TABLE = "ws_tx_table";
    /** Short, so the timeout tests do not cost seconds each. */
    private static final int MAX_LIFETIME_MS = 2000;
    private static final int INACTIVITY_MS = 600;

    private FileServerTestConfig testConfig;
    private SHTTPSApp app;
    private HttpClient httpClient;
    private Path testDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        testDir = tempDir;
        testConfig = createTestConfig();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        app = SHTTPSApp.init(testConfig.getConfig(), testConfig.getPlatformUtils(),
                testConfig.getDatabaseFabric());
        app.initIO();
        app.startServer();
        //this class opens far more than ten sessions from one address; without raising the limit
        //the suite would exhaust it partway through and fail in ways that look nothing like a rate
        //limit. The limit itself is covered by its own test, which lowers it again.
        app.dbTransactionRateLimitingMiddleware.setMaxRequests(Integer.MAX_VALUE);
        sql("CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INTEGER PRIMARY KEY, name TEXT)");
    }

    @AfterEach
    void tearDown() {
        try {
            sql("DROP TABLE IF EXISTS " + TEST_TABLE);
        } catch (Exception ignored) {
            //the server may already be gone
        }
        if (app != null) {
            if (app.isServerRunning()) {
                app.stopServer();
            }
            SHTTPSApp.destroy();
        }
    }

    private FileServerTestConfig createTestConfig() throws IOException {
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(testDir.resolve("config.json").toFile());
        config.setPort(8080);
        config.setRootDir(testDir.toAbsolutePath().toString());
        InputStream is = getClass().getClassLoader().getResourceAsStream(TEST_DATABASE);
        assertNotNull(is);
        Path localDatabaseFile = testDir.resolve(TEST_DATABASE);
        Files.copy(is, localDatabaseFile);
        config.setDatabasePath(localDatabaseFile.toAbsolutePath().toString());
        config.setDatabaseEnabled(true);
        config.setAllowDatabaseTableDataEditingApi(true);
        config.setAllowDatabaseCustomSqlRemoteApi(true);
        config.setDBTransactionMaxLifetimeMillis(MAX_LIFETIME_MS);
        config.setDBTransactionInactivityTimeoutMillis(INACTIVITY_MS);
        return FileServerTestConfig.forLocalServer(config, new PlatformUtilsImpl(), new DatabaseFabricImpl());
    }

    // ---- helpers ----

    /** Runs SQL through the ordinary HTTP endpoint, which is a separate transaction. */
    private String sql(String statement) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(statement))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private long rowCount() throws Exception {
        String body = sql("SELECT COUNT(*) FROM " + TEST_TABLE);
        return new JSONObject(body).getJSONArray("data").getJSONArray(0).getLong(0);
    }

    private TxClient connect() throws Exception {
        return new TxClient(testConfig.getServerUrl());
    }

    /**
     * A client for the endpoint. `onText` delivers fragments, so they are accumulated until the last
     * one before a frame is handed over.
     */
    private static class TxClient implements AutoCloseable, WebSocket.Listener {
        final BlockingQueue<JSONObject> frames = new LinkedBlockingQueue<>();
        final AtomicReference<int[]> closed = new AtomicReference<>(null);
        final AtomicReference<String> closeReason = new AtomicReference<>("");
        private final StringBuilder partial = new StringBuilder();
        private final WebSocket webSocket;
        private final AtomicInteger nextId = new AtomicInteger(1);

        TxClient(String baseUrl) throws Exception {
            String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/api/db/transaction";
            webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), this)
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
            closeReason.set(reason);
            closed.set(new int[]{statusCode});
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed.compareAndSet(null, new int[]{-1});
        }

        int send(String command, String sqlText, Integer limit, Boolean includeNames) {
            int id = nextId.getAndIncrement();
            JSONObject frame = new JSONObject().put("id", id).put("command", command);
            if (sqlText != null) frame.put("sql", sqlText);
            if (limit != null) frame.put("limit", limit.intValue());
            if (includeNames != null) frame.put("includeNames", includeNames.booleanValue());
            webSocket.sendText(frame.toString(), true).join();
            return id;
        }

        /** Sends an arbitrary command frame, filling in the id. */
        int sendFrame(JSONObject frame) {
            int id = nextId.getAndIncrement();
            frame.put("id", id);
            webSocket.sendText(frame.toString(), true).join();
            return id;
        }

        int query(String sqlText) {
            return send("query", sqlText, null, null);
        }

        int commit() {
            return send("commit", null, null, null);
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

    // ---- tests ----

    @Test
    void committedWorkIsVisibleAfterwards() throws Exception {
        try (TxClient client = connect()) {
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('a')");
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            //an INSERT produces no rows
            assertTrue(reply.isNull("result"), reply.toString());

            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('b')");
            assertTrue(client.nextFrame().getBoolean("ok"));

            client.commit();
            JSONObject committed = client.nextFrame();
            assertTrue(committed.getBoolean("ok"), committed.toString());
            assertTrue(committed.getBoolean("committed"));
            assertEquals(1000, client.awaitClose());
        }
        //the whole point: several statements, one transaction, all of them landed
        assertEquals(2, rowCount());
    }

    @Test
    void withoutCommitNothingIsKept() throws Exception {
        try (TxClient client = connect()) {
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('rolled back')");
            assertTrue(client.nextFrame().getBoolean("ok"));
        }
        //the client went away without committing
        Thread.sleep(500);
        assertEquals(0, rowCount());
    }

    @Test
    void aQueryReturnsItsRows() throws Exception {
        sql("INSERT INTO " + TEST_TABLE + " (name) VALUES ('x')");
        try (TxClient client = connect()) {
            client.send("query", "SELECT name FROM " + TEST_TABLE, null, Boolean.TRUE);
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            JSONObject result = reply.getJSONObject("result");
            assertEquals("[\"name\"]", result.getJSONArray("columns").toString());
            assertEquals("[[\"x\"]]", result.getJSONArray("data").toString());
        }
    }

    // ---- the four data commands ----

    @Test
    void insertReportsTheGeneratedId() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "one")));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            assertTrue(reply.getJSONObject("result").getLong("generated_id") > 0);
            client.commit();
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
        assertEquals(1, rowCount());
    }

    @Test
    void aBatchInsertReportsEveryGeneratedId() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new org.json.JSONArray()
                            .put(new JSONObject().put("name", "a"))
                            .put(new JSONObject().put("name", "b"))));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            //an array is a batch even of one, and answers with the plural field
            assertEquals(2, reply.getJSONObject("result").getJSONArray("generated_ids").length());
            client.commit();
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
        assertEquals(2, rowCount());
    }

    @Test
    void updateReportsTheNumberOfChangedRows() throws Exception {
        sql("INSERT INTO " + TEST_TABLE + " (name) VALUES ('before'),('before'),('other')");
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "update").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "after"))
                    .put("filters", new JSONObject()
                            .put("clauses", new org.json.JSONArray().put("name="))
                            .put("args", new org.json.JSONArray().put("before"))));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            assertEquals(2, reply.getJSONObject("result").getInt("updated_rows"));
            client.commit();
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
        assertEquals(2, new JSONObject(sql("SELECT COUNT(*) FROM " + TEST_TABLE + " WHERE name='after'"))
                .getJSONArray("data").getJSONArray(0).getLong(0));
    }

    @Test
    void deleteReportsTheNumberOfRemovedRows() throws Exception {
        sql("INSERT INTO " + TEST_TABLE + " (name) VALUES ('gone'),('gone'),('stays')");
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "delete").put("table", TEST_TABLE)
                    .put("filters", new JSONObject()
                            .put("clauses", new org.json.JSONArray().put("name="))
                            .put("args", new org.json.JSONArray().put("gone"))));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            assertEquals(2, reply.getJSONObject("result").getInt("deleted_rows"));
            client.commit();
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
        assertEquals(1, rowCount());
    }

    /** Also pins the unpaged total, which used to come back as zero on any page but the first. */
    @Test
    void selectReturnsAPageAndTheUnpagedTotal() throws Exception {
        StringBuilder rows = new StringBuilder("INSERT INTO " + TEST_TABLE + " (name) VALUES ");
        for (int i = 0; i < 7; i++) {
            if (i > 0) rows.append(",");
            rows.append("('row").append(i).append("')");
        }
        sql(rows.toString());

        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE)
                    .put("limit", 2).put("offset", 3).put("sort", "id").put("includeTotal", true));
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            JSONObject result = reply.getJSONObject("result");
            assertEquals(2, result.getJSONArray("data").length());
            assertEquals(7, result.getLong("total"), "the total must ignore the page window");
            //the page really is the fourth and fifth rows
            assertEquals("row3", result.getJSONArray("data").getJSONArray(0).getString(1));
        }
    }

    @Test
    void selectCanReturnRowsAsObjects() throws Exception {
        sql("INSERT INTO " + TEST_TABLE + " (name) VALUES ('obj')");
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE)
                    .put("rowsAsObjects", true));
            JSONObject reply = client.nextFrame();
            assertEquals("obj", reply.getJSONObject("result").getJSONArray("data")
                    .getJSONObject(0).getString("name"));
        }
    }

    // ---- what the commands are for ----

    /** Different commands, one transaction: they land together or not at all. */
    @Test
    void severalDifferentCommandsCommitTogether() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "first")));
            assertTrue(client.nextFrame().getBoolean("ok"));
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('second')");
            assertTrue(client.nextFrame().getBoolean("ok"));
            client.sendFrame(new JSONObject().put("command", "update").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "renamed"))
                    .put("filters", new JSONObject()
                            .put("clauses", new org.json.JSONArray().put("name="))
                            .put("args", new org.json.JSONArray().put("first"))));
            assertEquals(1, client.nextFrame().getJSONObject("result").getInt("updated_rows"));
            client.commit();
            assertTrue(client.nextFrame().getBoolean("committed"));
        }
        assertEquals(2, rowCount());
    }

    @Test
    void severalDifferentCommandsRollBackTogether() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "first")));
            assertTrue(client.nextFrame().getBoolean("ok"));
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('second')");
            assertTrue(client.nextFrame().getBoolean("ok"));
        }
        Thread.sleep(500);
        assertEquals(0, rowCount());
    }

    /**
     * The read-after-write consistency the HTTP endpoints can not offer: a select sees what this
     * session wrote, before anyone else can.
     */
    @Test
    void aSelectSeesAWriteMadeEarlierInTheSameSession() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "uncommitted")));
            assertTrue(client.nextFrame().getBoolean("ok"));

            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            JSONObject reply = client.nextFrame();
            assertEquals(1, reply.getJSONObject("result").getJSONArray("data").length(),
                    "the transaction must see its own write");
        }
        //the negative half, and it has to wait until the socket is gone: an HTTP read runs in a
        //transaction of its own and would queue behind this session rather than answer from before it
        Thread.sleep(500);
        assertEquals(0, rowCount(), "an uncommitted write must not survive the session");
    }

    // ---- parameters ----

    @Test
    void malformedFiltersAreABadRequest() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "delete").put("table", TEST_TABLE)
                    .put("filters", new JSONObject().put("clauses", new org.json.JSONArray())));
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("BAD_REQUEST", error.getJSONObject("error").getString("kind"));
            //the close code says the same thing the error frame did
            assertEquals(4400, client.awaitClose());
        }
    }

    @Test
    void aMissingTableIsABadRequest() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "select"));
            JSONObject error = client.nextFrame();
            assertEquals("BAD_REQUEST", error.getJSONObject("error").getString("kind"));
            //the close code says the same thing the error frame did
            assertEquals(4400, client.awaitClose());
        }
    }

    @Test
    void anUnknownCommandIsABadRequest() throws Exception {
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "drop_everything"));
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("BAD_REQUEST", error.getJSONObject("error").getString("kind"));
            assertTrue(error.getJSONObject("error").getString("message").contains("drop_everything"));
            client.awaitClose();
        }
    }

    @Test
    void selectDefaultsAndCapsItsRowLimit() throws Exception {
        StringBuilder rows = new StringBuilder("INSERT INTO " + TEST_TABLE + " (name) VALUES ");
        for (int i = 0; i < 1500; i++) {
            if (i > 0) rows.append(",");
            rows.append("('r").append(i).append("')");
        }
        sql(rows.toString());

        try (TxClient client = connect()) {
            //no limit: unlike GET /api/db/table, which would return all 1500, this is bounded
            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            assertEquals(100, client.nextFrame().getJSONObject("result").getJSONArray("data").length());

            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE)
                    .put("limit", 100000));
            assertEquals(1000, client.nextFrame().getJSONObject("result").getJSONArray("data").length());
        }
    }

    // ---- per-command config gates ----

    /** The gates differ per command, so one being off must not take the others down with it. */
    @Test
    void withTableEditingDisabledInsertIsRefusedButSelectStillWorks() throws Exception {
        testConfig.getConfig().setAllowDatabaseTableDataEditingApi(false);
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            assertTrue(client.nextFrame().getBoolean("ok"), "select has no config gate at all");

            client.sendFrame(new JSONObject().put("command", "insert").put("table", TEST_TABLE)
                    .put("values", new JSONObject().put("name", "nope")));
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("DISABLED", error.getJSONObject("error").getString("kind"));
            assertEquals(4403, client.awaitClose());
        }
    }

    @Test
    void aFailingStatementEndsTheSessionAndRollsBack() throws Exception {
        try (TxClient client = connect()) {
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('gone')");
            assertTrue(client.nextFrame().getBoolean("ok"));

            client.query("SELECT no_such_column FROM " + TEST_TABLE);
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("FAILED", error.getJSONObject("error").getString("kind"));
            //a failure ends the transaction rather than letting it carry on half-done
            assertEquals(4500, client.awaitClose());
        }
        assertEquals(0, rowCount());
    }

    @Test
    void aMalformedFrameIsReportedAsABadRequest() throws Exception {
        try (TxClient client = connect()) {
            client.webSocket.sendText("not json at all", true).join();
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("BAD_REQUEST", error.getJSONObject("error").getString("kind"));
            //the close code says the same thing the error frame did
            assertEquals(4400, client.awaitClose());
        }
    }

    @Test
    void anIdleSessionIsClosedByTheInactivityTimeout() throws Exception {
        try (TxClient client = connect()) {
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('idle')");
            assertTrue(client.nextFrame().getBoolean("ok"));
            //then say nothing at all
            assertEquals(4408, client.awaitClose());
        }
        assertEquals(0, rowCount());
    }

    /**
     * The lifetime cap is what keeps a busy client from holding the write thread indefinitely, so it
     * must not be pushed back by activity.
     */
    @Test
    void aBusySessionIsStillClosedByTheLifetimeCap() throws Exception {
        try (TxClient client = connect()) {
            long start = System.currentTimeMillis();
            int[] code = new int[]{0};
            while (System.currentTimeMillis() - start < MAX_LIFETIME_MS * 3) {
                if (client.closed.get() != null) {
                    code[0] = client.closed.get()[0];
                    break;
                }
                try {
                    client.query("SELECT 1");
                } catch (Exception e) {
                    break;//the socket went away mid-send, which is the close we are waiting for
                }
                Thread.sleep(INACTIVITY_MS / 3);
                client.frames.poll(200, TimeUnit.MILLISECONDS);
            }
            if (code[0] == 0) code[0] = client.awaitClose();
            assertEquals(4410, code[0], "expected the lifetime cap, not " + code[0]);
        }
    }

    /**
     * The scope loop only bounds waiting. A single command carrying a long batch of statements has
     * to be bounded too, or it holds the write thread for as long as it likes.
     */
    @Test
    void aLongBatchInOneCommandIsBoundedByTheLifetimeCap() throws Exception {
        //the deadline is read when the transaction starts, so shortening it here applies to the
        //session opened below. A batch this long can not finish inside it, which is the point:
        //without a deadline check between statements it would run to the end regardless
        testConfig.getConfig().setDBTransactionMaxLifetimeMillis(100);
        //statements slow enough that the batch takes seconds, so that "cut short" and "ran to the
        //end" are not a matter of milliseconds
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            //COUNT(*) over the whole series, not LIMIT 1 - a limit would let sqlite stop the
            //recursion after its first row and the statement would cost nothing
            batch.append("INSERT INTO ").append(TEST_TABLE)
                    .append(" (name) SELECT COUNT(*) FROM (WITH RECURSIVE c(i) AS (SELECT 1 UNION ALL ")
                    .append("SELECT i+1 FROM c WHERE i<20000) SELECT i FROM c);");
        }
        //a last statement that produces rows, so a batch that got to the end is unmistakable: the
        //client would receive a reply. Cut short, it receives nothing but the close
        batch.append("SELECT 1;");

        try (TxClient client = connect()) {
            client.query(batch.toString());
            assertNull(client.frames.poll(2, TimeUnit.SECONDS),
                    "the batch ran to completion instead of being cut short at its deadline");
            assertEquals(4410, client.awaitClose());
        }
        assertEquals(0, rowCount());
    }

    @Test
    void aSecondConcurrentSessionIsRefused() throws Exception {
        try (TxClient first = connect()) {
            first.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('first')");
            assertTrue(first.nextFrame().getBoolean("ok"));

            try (TxClient second = connect()) {
                assertEquals(4409, second.awaitClose());
            }
            //the first is undisturbed and can still commit
            first.commit();
            assertTrue(first.nextFrame().getBoolean("committed"));
            first.awaitClose();
        }
        assertEquals(1, rowCount());
    }

    /** The slot has to be released when a session ends, or the endpoint works exactly once. */
    @Test
    void aSessionCanBeStartedAgainAfterThePreviousOneEnded() throws Exception {
        try (TxClient first = connect()) {
            first.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('one')");
            assertTrue(first.nextFrame().getBoolean("ok"));
            first.commit();
            assertTrue(first.nextFrame().getBoolean("committed"));
            first.awaitClose();
        }
        try (TxClient second = connect()) {
            second.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('two')");
            assertTrue(second.nextFrame().getBoolean("ok"), "the slot was never released");
            second.commit();
            assertTrue(second.nextFrame().getBoolean("committed"));
            second.awaitClose();
        }
        assertEquals(2, rowCount());
    }

    /** The write thread must come back, or every later database request is stuck behind a ghost. */
    @Test
    void ordinaryEndpointsStillWorkAfterASessionTimesOut() throws Exception {
        try (TxClient client = connect()) {
            client.query("INSERT INTO " + TEST_TABLE + " (name) VALUES ('abandoned')");
            assertTrue(client.nextFrame().getBoolean("ok"));
            assertEquals(4408, client.awaitClose());
        }
        String insertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("{\"name\":\"after\"}", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(insertData))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(1, rowCount());
    }

    @Test
    void aResultIsCappedHoweverManyRowsTheClientAsksFor() throws Exception {
        StringBuilder rows = new StringBuilder("INSERT INTO " + TEST_TABLE + " (name) VALUES ");
        for (int i = 0; i < 1500; i++) {
            if (i > 0) rows.append(",");
            rows.append("('r").append(i).append("')");
        }
        sql(rows.toString());

        try (TxClient client = connect()) {
            client.send("query", "SELECT name FROM " + TEST_TABLE, 100000, null);
            JSONObject reply = client.nextFrame();
            assertTrue(reply.getBoolean("ok"), reply.toString());
            int returned = reply.getJSONObject("result").getJSONArray("data").length();
            //the client does not get to choose how much of the database is buffered
            assertTrue(returned <= 1000, "expected the result to be capped, got " + returned);
        }
    }

    /**
     * The session itself is not gated on any one command's config flag - that would refuse clients
     * with every right to run. The gate lives on the command instead.
     */
    @Test
    void withCustomSqlDisabledTheSessionOpensAndOnlyQueryIsRefused() throws Exception {
        testConfig.getConfig().setAllowDatabaseCustomSqlRemoteApi(false);
        try (TxClient client = connect()) {
            client.sendFrame(new JSONObject().put("command", "select").put("table", TEST_TABLE));
            assertTrue(client.nextFrame().getBoolean("ok"), "select does not need the custom SQL API");

            client.query("SELECT 1");
            JSONObject error = client.nextFrame();
            assertFalse(error.getBoolean("ok"), error.toString());
            assertEquals("DISABLED", error.getJSONObject("error").getString("kind"));
            assertEquals(4403, client.awaitClose());
        }
    }

    /**
     * A session holds the single database writer, so the rate at which one client may start them is
     * capped — otherwise a reconnect loop denies the database to everyone else.
     */
    @Test
    void openingSessionsTooFastIsRefused() throws Exception {
        app.dbTransactionRateLimitingMiddleware.setMaxRequests(3);

        //within the budget: these connect, and each is closed before the next
        for (int i = 0; i < 3; i++) {
            try (TxClient client = connect()) {
                assertNotNull(client);
            }
            Thread.sleep(100);
        }

        //over it: the handshake never completes
        boolean refused = false;
        try (TxClient client = connect()) {
            assertNotNull(client);
        } catch (Exception expected) {
            refused = true;
        }
        assertTrue(refused, "the fourth handshake should have been rate limited");

        //and the limit is on this route alone - ordinary database requests keep working
        assertEquals(0, rowCount());
    }

    /** A page on another origin must not be able to drive a transaction with the user's cookies. */
    @Test
    void aHandshakeFromAForeignOriginIsRejectedBeforeTheUpgrade() throws Exception {
        //written on a raw socket: the JDK's HttpClient refuses to set Upgrade and Connection, and
        //its WebSocket client will not send a foreign Origin
        assertEquals("HTTP/1.1 403 Forbidden", handshakeStatusLine("http://evil.example"));
        //while the same handshake from this very host is upgraded
        assertTrue(handshakeStatusLine(null).startsWith("HTTP/1.1 101"));
    }

    /**
     * The browser enforces nothing about CORS on a handshake, so the configured rules are worth
     * something here only if the server applies them itself.
     */
    @Test
    void anOriginCoveredByTheCORSRulesMayOpenASession() throws Exception {
        setCORSRulesForOrigins("http://trusted.example");

        assertTrue(handshakeStatusLine("http://trusted.example").startsWith("HTTP/1.1 101"),
                "an origin the CORS configuration names should be able to connect");
        //the rules are read per handshake, so this took effect without restarting the server
        assertEquals("HTTP/1.1 403 Forbidden", handshakeStatusLine("http://evil.example"),
                "an origin no rule covers is still refused");
    }

    /** A "*" rule is honoured exactly as it is for ordinary requests. */
    @Test
    void aWildcardCORSRuleAdmitsAnyOrigin() throws Exception {
        setCORSRulesForOrigins("*");

        assertTrue(handshakeStatusLine("http://anywhere.example").startsWith("HTTP/1.1 101"));
    }

    /** Removing the rules closes the door again, without a restart. */
    @Test
    void clearingTheCORSRulesRefusesTheOriginAgain() throws Exception {
        setCORSRulesForOrigins("http://trusted.example");
        assertTrue(handshakeStatusLine("http://trusted.example").startsWith("HTTP/1.1 101"));

        testConfig.getConfig().setCORSRules(null);

        assertEquals("HTTP/1.1 403 Forbidden", handshakeStatusLine("http://trusted.example"));
    }

    private void setCORSRulesForOrigins(String... origins) {
        List<CORSMiddleware.CORSRule> rules = new ArrayList<>();
        for (String origin : origins) {
            CORSMiddleware.CORSRule rule = new CORSMiddleware.CORSRule();
            rule.origin = origin;
            rules.add(rule);
        }
        testConfig.getConfig().setCORSRules(rules);
    }

    /** Performs a WebSocket handshake by hand and returns the status line of the answer. */
    private String handshakeStatusLine(String origin) throws Exception {
        URI base = URI.create(testConfig.getServerUrl());
        try (java.net.Socket socket = new java.net.Socket(base.getHost(), base.getPort())) {
            socket.setSoTimeout(10000);
            StringBuilder request = new StringBuilder()
                    .append("GET /api/db/transaction HTTP/1.1\r\n")
                    .append("Host: ").append(base.getHost()).append(":").append(base.getPort()).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();

            StringBuilder line = new StringBuilder();
            int b;
            while ((b = socket.getInputStream().read()) != -1) {
                if (b == '\r') continue;
                if (b == '\n') break;
                line.append((char) b);
            }
            return line.toString();
        }
    }

    @Test
    void pipeliningTooManyCommandsEndsTheSessionCleanly() throws Exception {
        try (TxClient client = connect()) {
            for (int i = 0; i < 200; i++) {
                try {
                    client.query("SELECT COUNT(*) FROM " + TEST_TABLE);
                } catch (Exception e) {
                    //the server ended the session part way through, which is the whole point -
                    //it must not buffer an unbounded backlog against a transaction that is running
                    break;
                }
            }
            client.awaitClose();
        }
        //the server is still healthy
        assertEquals(0, rowCount());
    }
}
