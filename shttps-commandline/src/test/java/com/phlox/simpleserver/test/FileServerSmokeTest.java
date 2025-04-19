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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class FileServerSmokeTest {
    private FileServerTestConfig testConfig;
    private SHTTPSApp app;
    private HttpClient httpClient;
    private Path testDir;
    private static final String TEST_FILE_NAME = "test.txt";
    private static final String TEST_FOLDER_NAME = "test_folder";
    private static final String TEST_FILE_CONTENT = "Hello, World!";

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

        // Create test file
        Files.writeString(testDir.resolve(TEST_FILE_NAME), TEST_FILE_CONTENT);
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

    private FileServerTestConfig createTestConfig() throws IOException {
        // For local server testing
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(testDir.resolve("config.json").toFile());
        config.setPort(8080);
        config.setRootDir(testDir.toAbsolutePath().toString());
        config.setAllowEditing(true);
        
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

    private HttpResponse<String> uploadFile(String fileName, byte[] content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/upload?path=" + URLEncoder.encode("", StandardCharsets.UTF_8)))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .PUT(buildMultipartData("files[]", fileName, content))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.BodyPublisher buildMultipartData(String fieldName, String fileName, byte[] content) {
        String boundary = "boundary";
        byte[] separator = ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] fileHeader = ("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        // Calculate total length
        // this is needed to let httpClient know how much data to send and prevent it from going to
        // chunked mode unsupported by our server
        int totalLength = separator.length + fileHeader.length + content.length + end.length;
        
        // Create a single byte array with all parts
        byte[] result = new byte[totalLength];
        int offset = 0;
        System.arraycopy(separator, 0, result, offset, separator.length);
        offset += separator.length;
        System.arraycopy(fileHeader, 0, result, offset, fileHeader.length);
        offset += fileHeader.length;
        System.arraycopy(content, 0, result, offset, content.length);
        offset += content.length;
        System.arraycopy(end, 0, result, offset, end.length);

        return HttpRequest.BodyPublishers.ofByteArray(result);
    }

    @Test
    void testFileUploadAndDownload() throws Exception {
        // Upload the test file first
        byte[] content = TEST_FILE_CONTENT.getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploadResponse = uploadFile(TEST_FILE_NAME, content);
        assertEquals(204, uploadResponse.statusCode());

        // Verify file is listed
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/list?path=" + URLEncoder.encode("", StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<String> listResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());
        assertTrue(listResponse.body().contains(TEST_FILE_NAME));

        // Download and verify content
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/download?path=" + URLEncoder.encode(TEST_FILE_NAME, StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<byte[]> downloadResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloadResponse.statusCode());
        assertArrayEquals(content, downloadResponse.body());
    }

    @Test
    void testCreateAndListFolder() throws Exception {
        String folderName = "test_folder";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/new-folder"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("path=" + URLEncoder.encode("", StandardCharsets.UTF_8) + "&name=" + URLEncoder.encode(folderName, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());

        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/list?path=" + URLEncoder.encode("", StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(folderName));
    }

    @Test
    void testRenameAndDeleteFile() throws Exception {
        String fileName = "test_rename.txt";
        String newFileName = "test_renamed.txt";
        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> uploadResponse = uploadFile(fileName, content);
        assertEquals(204, uploadResponse.statusCode());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/rename"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("path=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8) + "&name=" + URLEncoder.encode(newFileName, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());

        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/delete"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"path\":\"\",\"files\":[\"/"+newFileName+"\"]}"))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());

        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/list?path=" + URLEncoder.encode("", StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(newFileName));
    }

    @Test
    void testMoveFile() throws Exception {
        String fileName = "test_move.txt";
        String folderName = "test_move_folder";
        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        // Create folder
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/new-folder"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("path=" + URLEncoder.encode("", StandardCharsets.UTF_8) + "&name=" + URLEncoder.encode(folderName, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());

        // Upload file
        HttpResponse<String> uploadResponse = uploadFile(fileName, content);
        assertEquals(204, uploadResponse.statusCode());

        // Move file
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/move"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"/" + folderName + "\",\"action\":\"move\",\"files\":[\"/"+fileName+"\"]}"))
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());

        // Verify file is in new location
        request = HttpRequest.newBuilder()
                .uri(URI.create(testConfig.getServerUrl() + "/api/file/list?path=" + URLEncoder.encode(folderName, StandardCharsets.UTF_8)))
                .GET()
                .build();
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains(fileName));
    }
} 