package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-request helper unifying user storage accounting: free space calculation
 * (user's storage limit if set, otherwise the volume free space), usedStorage
 * bookkeeping and quota-aware progress tracking of uploads.
 * Accounting is allowed to be slightly imprecise in the moment (checks happen
 * once per written megabyte, concurrent operations may briefly overshoot), but
 * it must not drift permanently: usedStorage is recalculated from disk only
 * when a user is (re)created, so every byte written, copied or deleted has to
 * be reflected here.
 */
public class StorageQuota {
    public static final String ERROR_MSG_NO_SPACE_LEFT = "No file space left";

    public interface QuotaExceededExceptionFactory {
        Exception create(String message);
    }

    private final @Nullable User user;
    private final @NotNull UserStore userStore;
    private final @Nullable Long storageLimit;

    public StorageQuota(@Nullable User user, @NotNull UserStore userStore) {
        this.user = user;
        this.userStore = userStore;
        this.storageLimit = user != null ? userStore.provideUserRightsEvaluator().getStorageLimit(user) : null;
    }

    /** Space available for writing: the user's quota remainder if a limit is set,
     *  otherwise free space of the volume holding the given file/dir. */
    public long freeSpace(@NotNull DocumentFile fileOrDir) {
        if (storageLimit != null) {
            return storageLimit - user.usedStorage;
        }
        return fileOrDir.getStorageFreeSpace();
    }

    /** Pre-write check: can size bytes be written into dir, replacing previousSize
     *  bytes of an existing file (0 if creating a new one)? */
    public boolean hasSpaceFor(@NotNull DocumentFile dir, long size, long previousSize) {
        return freeSpace(dir) + previousSize >= size;
    }

    /** Record delta bytes as used (negative to free). No-op for anonymous access. */
    public void addUsed(long delta) throws Exception {
        if (user == null || delta == 0) return;
        userStore.updateUserAtomically(user.identity, u -> {
            u.usedStorage = Math.max(0, u.usedStorage + delta);
            user.usedStorage = u.usedStorage;
            return u;
        });
    }

    /** Same as addUsed but swallows store errors; for cleanup/rollback paths. */
    public void addUsedQuietly(long delta) {
        try {
            addUsed(delta);
        } catch (Exception ignored) {}
    }

    /** Size of a file, or total size of all files in a directory tree. */
    public static long sizeOf(@NotNull DocumentFile file) {
        return file.isDirectory() ? file.calculateDirectorySize() : file.length();
    }

    /**
     * Progress listener that counts written bytes against the quota, aborts the
     * upload when space runs out and rolls the accounting back if the upload fails.
     *
     * @param dir          directory used for volume free space checks
     * @param file         the file being written; deleted if the upload fails
     * @param expectedSize declared upload size, 0 if unknown
     * @param previousSize size of the file being overwritten, 0 if it is new
     */
    public ProgressOutputStream.WriteProgressListener newWriteListener(
            @NotNull DocumentFile dir, @NotNull DocumentFile file,
            long expectedSize, long previousSize,
            @NotNull QuotaExceededExceptionFactory errorFactory) {
        return new ProgressOutputStream.WriteProgressListener() {
            @Override
            public void onChunkWritten(long totalBytes, long chunkSize) throws Exception {
                addUsed(chunkSize);
                long free = freeSpace(dir);
                if (storageLimit != null) {
                    // the overwritten content still counts in usedStorage until the
                    // upload completes, though truncation already released its bytes
                    free += previousSize;
                }
                long leftToWrite = expectedSize > 0 ? (expectedSize - totalBytes) : 0;
                if (free < leftToWrite) {
                    throw errorFactory.create(ERROR_MSG_NO_SPACE_LEFT);
                }
            }

            @Override
            public void onStreamClosed(long totalBytes, Exception withException) {
                if (withException != null) {
                    if (file.exists()) {
                        file.delete();
                    }
                    // both the bytes written so far and the overwritten content are gone
                    addUsedQuietly(-(totalBytes + previousSize));
                } else {
                    // the new content replaced the old one, stop counting the old size
                    addUsedQuietly(-previousSize);
                }
            }
        };
    }
}
