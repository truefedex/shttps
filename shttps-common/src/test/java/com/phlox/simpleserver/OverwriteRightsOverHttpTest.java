package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.simpleserver.auth.User;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Copying or moving onto an existing file replaces it, so it takes the rights a write over that
 * file takes. CREATE on the destination folder used to be enough, which let an upload-only
 * account overwrite or destroy any file it could name.
 */
@Timeout(30)
public class OverwriteRightsOverHttpTest {
    private static final String PASSWORD = "secret";
    private LiveServer server;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        server = LiveServer.start(temp, c -> {
            c.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
            c.setUsers(List.of(
                    user("dropbox", EnumSet.of(User.FileSystemRights.CREATE, User.FileSystemRights.READ,
                            User.FileSystemRights.LIST_CONTENTS)),
                    user("editor", EnumSet.allOf(User.FileSystemRights.class))));
        });
        Files.createDirectories(server.root.resolve("src"));
        Files.createDirectories(server.root.resolve("dst"));
        Files.writeString(server.root.resolve("src/a.txt"), "from-src");
        Files.writeString(server.root.resolve("dst/a.txt"), "keep");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    @Test
    public void createAloneDoesNotCopyOverAnExistingFile() throws Exception {
        String status = apiMove("dropbox", "copy", "/dst", "/src/a.txt");
        assertTrue(status.contains(" 403 "), status);
        assertEquals("keep", Files.readString(server.root.resolve("dst/a.txt")));
    }

    @Test
    public void createAloneStillCopiesToAFreeName() throws Exception {
        Files.delete(server.root.resolve("dst/a.txt"));
        String status = apiMove("dropbox", "copy", "/dst", "/src/a.txt");
        assertTrue(status.contains(" 204 "), status);
        assertEquals("from-src", Files.readString(server.root.resolve("dst/a.txt")));
    }

    @Test
    public void updateAndDeleteAllowTheOverwrite() throws Exception {
        String status = apiMove("editor", "copy", "/dst", "/src/a.txt");
        assertTrue(status.contains(" 204 "), status);
        assertEquals("from-src", Files.readString(server.root.resolve("dst/a.txt")));
    }

    @Test
    public void webDavCopyWithOverwriteNeedsTheSameRights() throws Exception {
        String refused = webDavCopy("dropbox");
        assertTrue(refused.contains(" 403 "), refused);
        assertEquals("keep", Files.readString(server.root.resolve("dst/a.txt")));

        String allowed = webDavCopy("editor");
        assertTrue(allowed.contains(" 204 "), allowed);
        assertEquals("from-src", Files.readString(server.root.resolve("dst/a.txt")));
    }

    private String apiMove(String user, String action, String destPath, String file) throws Exception {
        byte[] body = ("{\"path\":\"" + destPath + "\",\"action\":\"" + action + "\",\"files\":[\"" + file + "\"]}")
                .getBytes(StandardCharsets.UTF_8);
        return server.exchange("POST /api/file/move HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                LiveServer.basicAuth(user, PASSWORD) +
                "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n", body);
    }

    private String webDavCopy(String user) throws Exception {
        return server.exchange("COPY /src/a.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" +
                LiveServer.basicAuth(user, PASSWORD) +
                "Destination: http://127.0.0.1:" + server.port + "/dst/a.txt\r\nOverwrite: T\r\n" +
                "Content-Length: 0\r\n\r\n", new byte[0]);
    }

    private static User user(String name, EnumSet<User.FileSystemRights> rights) {
        return new User(name,
                Objects.requireNonNull(com.phlox.simpleserver.utils.Utils.sha256(
                        com.phlox.simpleserver.utils.Utils.hashFNV1a32(PASSWORD))),
                null, rights, EnumSet.noneOf(User.DBRights.class), null,
                System.currentTimeMillis(), null, null,
                EnumSet.noneOf(User.SystemRights.class), 0);
    }
}
