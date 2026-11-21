package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

public class StagedUploadTest {

    @Test
    public void aFailedWriteLeavesThePreviousFile(@TempDir Path temp) throws Exception {
        DocumentFile dir = DocumentFile.fromFile(temp.toFile());
        Files.writeString(temp.resolve("note.txt"), "old");
        User user = new User("u", "h");
        user.storageLimit = 0L;

        StagedUpload upload = open(dir, "note.txt", new StorageQuota(user, store(user)), 100);
        assertThrows(Exception.class, () -> {
            try (OutputStream out = upload.stream()) {
                out.write("new".getBytes());
            }
        });
        assertThrows(Exception.class, upload::commit);

        assertEquals("old", Files.readString(temp.resolve("note.txt")));
        assertEquals(1, temp.toFile().list().length);
    }

    @Test
    public void aCommittedUploadReplacesThePreviousFile(@TempDir Path temp) throws Exception {
        DocumentFile dir = DocumentFile.fromFile(temp.toFile());
        Files.writeString(temp.resolve("note.txt"), "old");

        StagedUpload upload = open(dir, "note.txt", new StorageQuota(null, store(null)), 3);
        try (OutputStream out = upload.stream()) {
            out.write("new".getBytes());
        }
        //closing alone publishes nothing
        assertEquals("old", Files.readString(temp.resolve("note.txt")));
        upload.commit();

        assertArrayEquals("new".getBytes(), Files.readAllBytes(temp.resolve("note.txt")));
        assertEquals(1, temp.toFile().list().length);
    }

    @Test
    public void aClosedButUncommittedUploadIsDiscarded(@TempDir Path temp) throws Exception {
        //what the body reader does when the client goes away: close the stream, then throw
        DocumentFile dir = DocumentFile.fromFile(temp.toFile());
        Files.writeString(temp.resolve("note.txt"), "old");

        StagedUpload upload = open(dir, "note.txt", new StorageQuota(null, store(null)), 0);
        try (OutputStream out = upload.stream()) {
            out.write("ne".getBytes());
        }
        upload.discard();

        assertEquals("old", Files.readString(temp.resolve("note.txt")));
        assertEquals(1, temp.toFile().list().length);
    }

    @Test
    public void aBodyShorterThanAnnouncedIsNotPublished(@TempDir Path temp) throws Exception {
        DocumentFile dir = DocumentFile.fromFile(temp.toFile());
        Files.writeString(temp.resolve("note.txt"), "old");

        StagedUpload upload = open(dir, "note.txt", new StorageQuota(null, store(null)), 10);
        try (OutputStream out = upload.stream()) {
            out.write("new".getBytes());
        }
        assertThrows(StagedUpload.IncompleteUploadException.class, upload::commit);

        assertEquals("old", Files.readString(temp.resolve("note.txt")));
        assertEquals(1, temp.toFile().list().length);
    }

    @Test
    public void aNewFileAppearsOnlyOnCommit(@TempDir Path temp) throws Exception {
        DocumentFile dir = DocumentFile.fromFile(temp.toFile());

        StagedUpload upload = open(dir, "fresh.txt", new StorageQuota(null, store(null)), 3);
        try (OutputStream out = upload.stream()) {
            out.write("new".getBytes());
        }
        assertFalse(Files.exists(temp.resolve("fresh.txt")));
        upload.commit();
        assertEquals("new", Files.readString(temp.resolve("fresh.txt")));
        assertEquals(1, temp.toFile().list().length);
    }

    private static StagedUpload open(DocumentFile dir, String name, StorageQuota quota, long expected) throws Exception {
        return StagedUpload.open(dir, "text/plain", name, dir.findFile(name), quota, expected,
                message -> new IllegalStateException(message),
                DocumentFile::openOutputStream);
    }

    private static UserStore store(User user) {
        return (UserStore) Proxy.newProxyInstance(UserStore.class.getClassLoader(),
                new Class<?>[]{UserStore.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "store";
                        if ("hashCode".equals(method.getName())) return 1;
                        return proxy == args[0];
                    }
                    if ("updateUserAtomically".equals(method.getName()) && user != null) {
                        ((UserStore.Updater<User>) args[1]).process(user);
                        return null;
                    }
                    if ("provideUserRightsEvaluator".equals(method.getName())) {
                        return new UserRightsEvaluator(new Holder<>(null));
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
    }
}
