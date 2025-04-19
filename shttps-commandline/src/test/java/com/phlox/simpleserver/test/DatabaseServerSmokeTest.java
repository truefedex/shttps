package com.phlox.simpleserver.test;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.server.SHTTPSConfigImpl;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
            String createTableSql = "CREATE TABLE " + TEST_TABLE + " (" + TEST_COLUMN + " TEXT)";
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

        // Test custom SQL endpoint
        String sqlData = "SELECT * FROM " + TEST_TABLE;
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/query"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(sqlData))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }
} 