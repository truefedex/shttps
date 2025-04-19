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
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public class DatabasePerformanceTest {
    private static final int NUM_THREADS = 10;
    private static final int OPERATIONS_PER_THREAD = 100;
    private static final String TEST_DATABASE = "test.db";
    private static final String TEST_TABLE = "perf_test_table";
    private static final String TEST_COLUMN = "test_column";

    private ExecutorService executor;
    private FileServerTestConfig testConfig;
    private SHTTPSApp app;
    private HttpClient httpClient;
    private Path testDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        executor = Executors.newFixedThreadPool(NUM_THREADS);
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
        executor.shutdown();
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
    void testParallelReads() throws Exception {
        // First insert some test data
        insertTestData(OPERATIONS_PER_THREAD * NUM_THREADS);
        
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        
        Instant start = Instant.now();
        
        // Launch parallel read operations
        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String value = "test_value_" + (threadId * OPERATIONS_PER_THREAD + j);
                        if (readTestData(value)) {
                            successCount.incrementAndGet();
                        } else {
                            System.out.println("Failed to read value: " + value);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        
        // Wait for all operations to complete
        for (Future<?> future : futures) {
            future.get();
        }
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        
        System.out.printf("Parallel Reads Performance Test:\n" +
                "Total operations: %d\n" +
                "Successful reads: %d\n" +
                "Total time: %d ms\n" +
                "Operations per second: %.2f\n",
                NUM_THREADS * OPERATIONS_PER_THREAD,
                successCount.get(),
                duration.toMillis(),
                (NUM_THREADS * OPERATIONS_PER_THREAD) / (duration.toMillis() / 1000.0));
        
        assertEquals(NUM_THREADS * OPERATIONS_PER_THREAD, successCount.get());
    }

    @Test
    void testParallelWrites() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        
        Instant start = Instant.now();
        
        // Launch parallel write operations
        for (int i = 0; i < NUM_THREADS; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String value = "test_value_" + (threadId * OPERATIONS_PER_THREAD + j);
                        if (insertTestData(value)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        
        // Wait for all operations to complete
        for (Future<?> future : futures) {
            future.get();
        }
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        
        System.out.printf("Parallel Writes Performance Test:\n" +
                "Total operations: %d\n" +
                "Successful writes: %d\n" +
                "Total time: %d ms\n" +
                "Operations per second: %.2f\n",
                NUM_THREADS * OPERATIONS_PER_THREAD,
                successCount.get(),
                duration.toMillis(),
                (NUM_THREADS * OPERATIONS_PER_THREAD) / (duration.toMillis() / 1000.0));
        
        assertEquals(NUM_THREADS * OPERATIONS_PER_THREAD, successCount.get());
    }

    @Test
    void testParallelReadsAndWrites() throws Exception {
        // First insert some initial test data
        insertTestData(OPERATIONS_PER_THREAD * (NUM_THREADS / 2));
        
        AtomicInteger readSuccessCount = new AtomicInteger(0);
        AtomicInteger writeSuccessCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        
        Instant start = Instant.now();
        
        // Launch parallel read operations
        for (int i = 0; i < NUM_THREADS/2; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String value = "test_value_" + (threadId * OPERATIONS_PER_THREAD + j);
                        if (readTestData(value)) {
                            readSuccessCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        
        // Launch parallel write operations
        for (int i = NUM_THREADS/2; i < NUM_THREADS; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
                        String value = "test_value_" + (threadId * OPERATIONS_PER_THREAD + j);
                        if (insertTestData(value)) {
                            writeSuccessCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
        }
        
        // Wait for all operations to complete
        for (Future<?> future : futures) {
            future.get();
        }
        
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        
        System.out.printf("Parallel Reads and Writes Performance Test:\n" +
                "Total operations: %d\n" +
                "Successful reads: %d\n" +
                "Successful writes: %d\n" +
                "Total time: %d ms\n" +
                "Operations per second: %.2f\n",
                NUM_THREADS * OPERATIONS_PER_THREAD,
                readSuccessCount.get(),
                writeSuccessCount.get(),
                duration.toMillis(),
                (NUM_THREADS * OPERATIONS_PER_THREAD) / (duration.toMillis() / 1000.0));
        
        assertEquals(NUM_THREADS/2 * OPERATIONS_PER_THREAD, readSuccessCount.get());
        assertEquals(NUM_THREADS/2 * OPERATIONS_PER_THREAD, writeSuccessCount.get());
    }

    private void insertTestData(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            insertTestData("test_value_" + i);
        }
    }

    private boolean insertTestData(String value) throws Exception {
        String insertData = "table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                "&values=" + URLEncoder.encode("{\"" + TEST_COLUMN + "\":\"" + value + "\"}", StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/insert"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(insertData))
                .build();
        //System.err.println("Sending request with value: " + value);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains("generated_id");
    }

    private boolean readTestData(String value) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .uri(URI.create(testConfig.getServerUrl() + "/api/db/table?table=" + URLEncoder.encode(TEST_TABLE, StandardCharsets.UTF_8) +
                        "&filters=" + URLEncoder.encode("{\"clauses\":[\"" + TEST_COLUMN + "=\"],\"args\":[\"" + value + "\"]}", StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains(value);
    }
} 