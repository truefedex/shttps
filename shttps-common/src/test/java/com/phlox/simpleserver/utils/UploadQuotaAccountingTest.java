package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.auth.UserStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A direct (not staged) upload through {@link StorageQuota#newWriteListener}: whatever way the
 * upload ends, the user's used storage has to match what is left on disk afterwards. The interval
 * is 4 bytes so that a few bytes already cross a reporting point.
 */
public class UploadQuotaAccountingTest {
    private static final int INTERVAL = 4;

    @Test
    public void aCompletedOverwriteCountsTheNewSizeOnly(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp, 100, "0123456789");
        try (OutputStream out = f.open(6)) {
            out.write("abcdef".getBytes());
        }
        assertEquals(6, Files.size(f.path));
        assertEquals(6, f.user.usedStorage);
    }

    @Test
    public void anOverwriteCutOffByTheClientCountsWhatWasWritten(@TempDir Path temp) throws Exception {
        //the body reader closes the stream normally when the client goes away; the old contents
        //were truncated when the file was opened, so only the bytes that arrived remain
        Fixture f = new Fixture(temp, 100, "0123456789");
        try (OutputStream out = f.open(50)) {
            out.write("abcdef".getBytes());
        }
        assertEquals(6, Files.size(f.path));
        assertEquals(6, f.user.usedStorage);
    }

    @Test
    public void aNewFileRejectedByTheQuotaIsRemovedAndNotCounted(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp, 10, null);
        OutputStream out = f.open(20);
        //the first 4 bytes cross a reporting point, are counted, and leave 6 of 20 needed
        assertThrows(RuntimeException.class, () -> {
            try (OutputStream o = out) {
                o.write("abcdef".getBytes());
            }
        });
        assertFalse(Files.exists(f.path));
        assertEquals(0, f.user.usedStorage);
    }

    @Test
    public void anOverwriteRejectedByTheQuotaGivesBackBothSizes(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp, 12, "0123456789");
        OutputStream out = f.open(30);
        assertThrows(RuntimeException.class, () -> {
            try (OutputStream o = out) {
                o.write("abcdefgh".getBytes());
            }
        });
        assertFalse(Files.exists(f.path));
        assertEquals(0, f.user.usedStorage);
    }

    @Test
    public void closingTwiceSettlesTheQuotaOnce(@TempDir Path temp) throws Exception {
        Fixture f = new Fixture(temp, 100, "0123456789");
        OutputStream out = f.open(3);
        out.write("abc".getBytes());
        out.close();
        out.close();
        assertEquals(3, f.user.usedStorage);
    }

    @Test
    public void anUnknownFreeSpaceDoesNotOverflowIntoNoSpaceLeft() {
        //SAF reports Long.MAX_VALUE; replacing an existing file added its size on top
        assertEquals(Long.MAX_VALUE, StorageQuota.plusSaturated(Long.MAX_VALUE, 10));
        assertEquals(Long.MIN_VALUE, StorageQuota.plusSaturated(Long.MIN_VALUE, -10));
        assertEquals(15, StorageQuota.plusSaturated(5, 10));
        assertTrue(new StorageQuota(null, store(null)).hasSpaceFor(new UnlimitedDir(), 100, 50));
    }

    private static final class Fixture {
        final User user = new User("u", "h");
        final DocumentFile dir;
        final Path path;
        final StorageQuota quota;
        final long previousSize;

        Fixture(Path temp, long limit, String existing) throws Exception {
            dir = DocumentFile.fromFile(temp.toFile());
            path = temp.resolve("f.bin");
            if (existing != null) Files.writeString(path, existing);
            previousSize = existing != null ? existing.length() : 0;
            user.storageLimit = limit;
            user.usedStorage = previousSize;
            quota = new StorageQuota(user, store(user));
        }

        OutputStream open(long expected) throws Exception {
            DocumentFile file = dir.findFile("f.bin");
            if (file == null) file = dir.createFile("application/octet-stream", "f.bin");
            return new ProgressOutputStream(file.openOutputStream(), INTERVAL,
                    quota.newWriteListener(dir, file, expected, previousSize,
                            message -> new IllegalStateException(message)));
        }
    }

    /** A directory whose volume cannot say how much space it has, like SAF's TreeDocumentFile. */
    private static final class UnlimitedDir extends DocumentFile {
        UnlimitedDir() { super(null); }
        @Override public long getStorageFreeSpace() { return Long.MAX_VALUE; }
        @Override public long getStorageSize() { return Long.MAX_VALUE; }
        @Override public DocumentFile createFile(String mimeType, String displayName) { return null; }
        @Override public DocumentFile createDirectory(String displayName) { return null; }
        @Override public String getUri() { return "x:/"; }
        @Override public String getName() { return ""; }
        @Override public String getType() { return null; }
        @Override public boolean isDirectory() { return true; }
        @Override public boolean isFile() { return false; }
        @Override public boolean isVirtual() { return false; }
        @Override public long lastModified() { return 0; }
        @Override public long created() { return 0; }
        @Override public long length() { return 0; }
        @Override public boolean canRead() { return true; }
        @Override public boolean canWrite() { return true; }
        @Override public boolean delete() { return false; }
        @Override public boolean exists() { return true; }
        @Override public DocumentFile[] listFiles() { return new DocumentFile[0]; }
        @Override public boolean renameTo(String displayName) { return false; }
        @Override public boolean copyTo(DocumentFile destDir) { return false; }
        @Override public boolean moveTo(DocumentFile destDir) { return false; }
        @Override public boolean copyTo(DocumentFile destDir, String destName) { return false; }
        @Override public boolean moveTo(DocumentFile destDir, String destName) { return false; }
        @Override public java.io.InputStream openInputStream() { return null; }
        @Override public OutputStream openOutputStream() { return null; }
        @Override public String getRelativePath(DocumentFile child) { return null; }
    }

    @SuppressWarnings("unchecked")
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
