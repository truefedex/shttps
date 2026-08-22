package com.phlox.simpleserver.test;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.server.SHTTPSConfigImpl;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class DatabaseServerSmokeTest {
    private FileServerTestConfig testConfig;
    private SHTTPSApp app;
    private HttpClient httpClient;
    private Path testDir;
    private static final String TEST_DATABASE = "test.db";
    private static final String TEST_TABLE = "test_table";
    private static final String TEST_COLUMN = "test_column";
    private static final String TEST_VALUE = "test_value";

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        testDir = tempDir;
        testConfig = createTestConfig();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.of(5, ChronoUnit.SECONDS))
                .build();

        if (testConfig.isUseLocalServer()) {
            app = SHTTPSApp.init(
                    testConfig.getConfig(),
                    testConfig.getPlatformUtils(),
                    testConfig.getDatabaseFabric()
            );
            app.initIO();
            app.startServer();
        }
        
        // Create test table before running tests
        createTestTable();
    }

    @AfterEach
    void tearDown() {
        try {
            // Remove test table after tests
            removeTestTable();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (app != null) {
            if (app.isServerRunning()) {
                app.stopServer();
            }
            SHTTPSApp.destroy();
        }
    }

    private FileServerTestConfig createTestConfig() throws IOException {
        // For local server testing
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(testDir.resolve("config.json").toFile());
        config.setPort(8080);
        config.setRootDir(testDir.toAbsolutePath().toString());
        //copy database from resources to testDir
        InputStream is = getClass().getClassLoader().getResourceAsStream(TEST_DATABASE);
        assert is != null;
        Path localDatabaseFile = testDir.resolve(TEST_DATABASE);
        Files.copy(is, localDatabaseFile);
        config.setDatabasePath(localDatabaseFile.toAbsolutePath().toString());
        config.setDatabaseEnabled(true);
        config.setAllowDatabaseTableDataEditingApi(true);
        config.setAllowDatabaseCustomSqlRemoteApi(true);
        
        return FileServerTestConfig.forLocalServer(
                config,
                new PlatformUtilsImpl(),
                new DatabaseFabricImpl()
        );
    }

/*    private FileServerTestConfig createTestConfig() throws IOException {
        // For remote server testing
        return FileServerTestConfig.forRemoteServer("http://192.168.1.100:8080");
    }*/

    /**
     * Creates the test table using custom SQL
     */
    private void createTestTable() throws Exception {
        // First check if table exists
        String checkTableSql = "SELECT name FROM sqlite_master WHERE type='table' AND name='" + TEST_TABLE + "'";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(checkTableSql))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        // If table doesn't exist, create it
        if (response.statusCode() == 200 && !response.body().contains(TEST_TABLE)) {
            String createTableSql = "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (" + TEST_COLUMN + " TEXT)";
            request = HttpRequest.newBuilder()
                    .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(createTableSql))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "Failed to create test table");
        }
    }
    
    /**
     * Removes the test table using custom SQL
     */
    private void removeTestTable() throws Exception {
        String dropTableSql = "DROP TABLE IF EXISTS " + TEST_TABLE;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(dropTableSql))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Failed to remove test table");
    }

    @Test
    void testSchemaAndTableOperations() throws Exception {
        // Test schema endpoint
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/schema"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Test table data endpoint
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/table?table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        // Test insert operation
        String insertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("{\"" + TEST_COLUMN + "\":\"" + TEST_VALUE + "\"}", StandardCharsets.UTF_8);
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(insertData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("generated_id"));

        // Test update operation
        String updateData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("{\"" + TEST_COLUMN + "\":\"updated_value\"}", StandardCharsets.UTF_8) +
                "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"" + TEST_VALUE + "\"]}", StandardCharsets.UTF_8);
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/update"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString(updateData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("updated_rows"));

        // Test single cell data endpoint
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/cell-data?table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&column=" + URLEncoder.encode(TEST_COLUMN, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"updated_value\"]}", StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("updated_value", response.body());

        // Test delete operation
        String deleteData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"updated_value\"]}", StandardCharsets.UTF_8);
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/delete"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(deleteData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("deleted_rows"));

        // Test custom SQL endpoint. It must return the actual cell values: the result is read
        // from the database after the enclosing transaction is over, which used to yield nulls
        insertRows("{\"" + TEST_COLUMN + "\":\"custom_sql_value\"}");
        String sqlData = "SELECT " + TEST_COLUMN + " FROM " + TEST_TABLE;
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(sqlData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("{\"offset\":0,\"limit\":100,\"data\":[[\"custom_sql_value\"]]}", response.body());
    }

    /**
     * A result bigger than the pipe buffer of the response streamer can not be generated before
     * the request handler returns, so the rows are read well after the enclosing transaction
     * completed. Reading them must still work and must not truncate the result.
     */
    @Test
    void testCustomSqlLargeResultIsNotTruncated() throws Exception {
        int rowsCount = 500;
        String padding = "x".repeat(200);
        StringBuilder rowsJson = new StringBuilder("[");
        for (int i = 0; i < rowsCount; i++) {
            if (i > 0) rowsJson.append(",");
            rowsJson.append("{\"").append(TEST_COLUMN).append("\":\"row_").append(i).append("_").append(padding).append("\"}");
        }
        rowsJson.append("]");
        insertRows(rowsJson.toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query?limit=" + (rowsCount * 2)))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("SELECT " + TEST_COLUMN + " FROM " + TEST_TABLE))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        int returnedRows = 0;
        for (int i = body.indexOf("row_"); i >= 0; i = body.indexOf("row_", i + 1)) {
            returnedRows++;
        }
        assertEquals(rowsCount, returnedRows, "Result is truncated, got " + body.length() + " bytes");
        assertFalse(body.contains("null"), "Result contains null cells");
        assertTrue(body.contains("row_0_" + padding));
        assertTrue(body.contains("row_" + (rowsCount - 1) + "_" + padding));
        //guard: the result has to be bigger than the pipe buffer of the response streamer,
        //otherwise it could be generated before the request handler returns and this test
        //would no longer cover reading rows after the transaction is over
        assertTrue(body.length() > 64 * 1024, "Result is too small to outlive the transaction: " + body.length());
    }

    /**
     * @param valuesJson single row object or array of row objects, as accepted by /api/db/insert
     */
    private void insertRows(String valuesJson) throws Exception {
        String insertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode(valuesJson, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(insertData))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Failed to insert test rows: " + response.body());
    }

    @Test
    void testMultiRowInsert() throws Exception {
        // "values" as a JSON array inserts several rows within a single call
        String insertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("[{\"" + TEST_COLUMN + "\":\"batch_value_1\"},{\"" +
                        TEST_COLUMN + "\":\"batch_value_2\"}]", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(insertData))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("generated_ids"), "Unexpected response: " + response.body());

        // both rows must be readable back
        response = selectAllTestTableRows();
        assertTrue(response.body().contains("batch_value_1"), "Unexpected response: " + response.body());
        assertTrue(response.body().contains("batch_value_2"), "Unexpected response: " + response.body());

        // a failing row rolls back the whole batch
        String failingInsertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("[{\"" + TEST_COLUMN + "\":\"rolled_back_value\"},{\"no_such_column\":\"x\"}]",
                        StandardCharsets.UTF_8);
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(failingInsertData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode());
        response = selectAllTestTableRows();
        assertFalse(response.body().contains("rolled_back_value"), "Unexpected response: " + response.body());

        // an empty array is rejected
        String emptyInsertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("[]", StandardCharsets.UTF_8);
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(emptyInsertData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
    }

    /**
     * The cell endpoint answers with the value itself, streamed straight out of the database. The
     * stream has to stay open until the body has been sent - it used to be closed by the handler
     * before the response was written, which only went unnoticed because a short value is read
     * into memory anyway.
     */
    @Test
    void testCellDataStreamsALargeValueInFull() throws Exception {
        //past the 8 KB chunk size a cell stream reads in, and past the response pipe buffer
        String largeValue = "abcdefghij".repeat(20 * 1024);//200 KB
        insertRows("{\"" + TEST_COLUMN + "\":\"" + largeValue + "\"}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/cell-data?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&column=" + URLEncoder.encode(TEST_COLUMN, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"" +
                                largeValue + "\"]}", StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(largeValue.length(), response.body().length(),
                "The cell value came back truncated");
        assertEquals(largeValue, response.body());
    }

    /**
     * The cell endpoint answers with the value as UTF-8 bytes, so its Content-Length has to be a
     * byte count. It used to come from sqlite's length(), which counts characters for text, and any
     * value outside ASCII was therefore announced shorter than it was and arrived truncated.
     */
    @Test
    void testCellDataOfNonAsciiTextIsNotTruncated() throws Exception {
        //enough multi-byte content that a character count and a byte count differ a lot
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("héllo wörld ").append(i).append(" καλημέρα δρόμος 😀\n");
        }
        String value = sb.toString();
        int expectedBytes = value.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(expectedBytes > value.length(), "the test value must be one where the two differ");
        insertRows(new JSONObject().put(TEST_COLUMN, value).toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/cell-data?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&column=" + URLEncoder.encode(TEST_COLUMN, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "?\"],\"args\":[\"h%\"]}",
                                StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals(expectedBytes, response.body().length, "The value came back truncated");
        assertEquals(value, new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(String.valueOf(expectedBytes),
                response.headers().firstValue("Content-Length").orElse(null));
    }

    /**
     * A row that exists but holds nothing is "no content", not "not found" - the two platforms used
     * to disagree about this.
     */
    @Test
    void testCellDataOfANullCellIsNoContent() throws Exception {
        insertRows("{\"" + TEST_COLUMN + "\":null}");

        //no filters: the table holds exactly the row just inserted, and the query takes the first.
        //A filter can not single out a null cell anyway - every comparison with NULL is NULL
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/cell-data?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&column=" + URLEncoder.encode(TEST_COLUMN, StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), "Unexpected response: " + response.body());
    }

    /** No row at all is a different answer from a row whose cell is empty. */
    @Test
    void testCellDataOfAMissingRowIsNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/cell-data?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&column=" + URLEncoder.encode(TEST_COLUMN, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"nothing matches this\"]}",
                                StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), "Unexpected response: " + response.body());
    }

    /**
     * Every statement of a batch shares one transaction, so a failure in a later one has to undo
     * the earlier ones. The handler used to catch the failure inside the transaction scope, which
     * let it commit the work that had already been done.
     */
    @Test
    void testFailingStatementRollsBackTheRestOfTheBatch() throws Exception {
        String sql = "INSERT INTO " + TEST_TABLE + " (" + TEST_COLUMN + ") VALUES ('batch_first');" +
                "INSERT INTO " + TEST_TABLE + " (no_such_column) VALUES ('boom')";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(420, response.statusCode(), "Unexpected response: " + response.body());

        response = selectAllTestTableRows();
        assertFalse(response.body().contains("batch_first"),
                "The first statement of the batch was committed: " + response.body());
    }

    /**
     * A malformed "filters" parameter is the client's mistake. It used to escape the handler as a
     * JSON parsing error and come back as a 500.
     */
    @Test
    void testMalformedFiltersAreRejectedAsABadRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/table?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[]}", StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), "Unexpected response: " + response.body());
    }

    /**
     * The total is the count of everything matching the filters, not of the page. It used to be
     * taken with the page window already appended to the query, and since count(*) returns a single
     * row, any offset skipped it and the answer was zero on every page but the first.
     */
    @Test
    void testIncludeTotalIsTheUnpagedCount() throws Exception {
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < 7; i++) {
            if (i > 0) rows.append(",");
            rows.append("{\"").append(TEST_COLUMN).append("\":\"total_").append(i).append("\"}");
        }
        insertRows(rows.append("]").toString());

        for (int offset = 0; offset < 3; offset++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testConfig.getServerUrl() + "/api/db/table?table=" +
                            URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                            "&includeTotal=true&limit=2&offset=" + offset))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            JSONObject body = new JSONObject(response.body());
            assertEquals(7, body.getLong("total"), "wrong total at offset " + offset);
            //7 rows and a limit of 2, so every one of these offsets is a full page
            assertEquals(2, body.getJSONArray("data").length(), "wrong page size at offset " + offset);
        }
    }

    /** Asking about a table that is not there is "not found", whatever else is in the database. */
    @Test
    void testSchemaOfAnUnknownTableIsNotFound() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/schema?table=no_such_table"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), "Unexpected response: " + response.body());

        //while the named table that does exist comes back on its own
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/schema?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(TEST_COLUMN), "Unexpected response: " + response.body());
    }

    private HttpResponse<String> selectAllTestTableRows() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/table?table=" +
                        URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response;
    }
} 