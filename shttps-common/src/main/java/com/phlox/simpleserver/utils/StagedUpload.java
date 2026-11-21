package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An upload written into a temporary sibling and put in place only by {@link #commit()}, which
 * the handler calls once the whole request body has been read. Opening the destination itself
 * truncates it first, so a failed upload would destroy the previous contents before the new
 * ones exist.
 * <p>
 * Used only when {@code SHTTPSConfig.isAtomicUploadsEnabled()} is on, which it is not by default:
 * for the length of the upload the old and the new copy are both on disk, and users expect a
 * 1 GB file to fit into 1 GB of free space.
 * <p>
 * Closing {@link #stream()} only closes the temporary file. The body reader closes it in a
 * try-with-resources, that is also when the client went away half way, so closing is not proof
 * of a finished upload. A stream that was never committed must be {@link #discard()}ed.
 */
public final class StagedUpload {
    public interface StreamOpener {
        OutputStream open(DocumentFile file) throws Exception;
    }

    /** The body ended before the size the client announced. */
    public static final class IncompleteUploadException extends IOException {
        IncompleteUploadException(String message) {
            super(message);
        }
    }

    private final DocumentFile temp;
    private final String fileName;
    private final @Nullable DocumentFile existing;
    private final StorageQuota quota;
    private final long previousSize;
    private final long expectedSize;
    private final ProgressOutputStream progress;
    private final OutputStream stream;
    private boolean finished;

    private StagedUpload(DocumentFile temp, String fileName, @Nullable DocumentFile existing,
                         StorageQuota quota, long previousSize, long expectedSize,
                         ProgressOutputStream progress) {
        this.temp = temp;
        this.fileName = fileName;
        this.existing = existing;
        this.quota = quota;
        this.previousSize = previousSize;
        this.expectedSize = expectedSize;
        this.progress = progress;
        this.stream = new BufferedOutputStream(progress);
    }

    /**
     * @param expectedSize the size the client announced, or 0 or less when it is unknown; a
     *                     known size is checked again in {@link #commit()}
     */
    public static StagedUpload open(@NotNull DocumentFile parent, String mime, @NotNull String fileName,
                                    @Nullable DocumentFile existing, @NotNull StorageQuota quota,
                                    long expectedSize,
                                    @NotNull StorageQuota.QuotaExceededExceptionFactory errors,
                                    @NotNull StreamOpener opener) throws Exception {
        long previousSize = existing != null && existing.exists() ? existing.length() : 0L;
        DocumentFile temp = parent.createFile(mime, stagingName(fileName));
        if (temp == null) {
            throw new IOException("Cannot create upload file");
        }
        OutputStream raw;
        try {
            raw = opener.open(temp);
        } catch (Exception e) {
            temp.delete();
            throw e;
        }
        //previousSize 0: the old file is still on disk until the commit, so it frees nothing yet
        ProgressOutputStream progress = new ProgressOutputStream(raw, 1024 * 1024,
                quota.newWriteListener(parent, temp, expectedSize, 0L, errors));
        return new StagedUpload(temp, fileName, existing, quota, previousSize, expectedSize, progress);
    }

    public static String stagingName(String fileName) {
        String suffix = ".shttps-" + Long.toUnsignedString(System.nanoTime(), 36);
        String name = fileName + suffix;
        if (!DocumentFile.isValidChildName(name) || name.length() > 240) {
            name = "shttps-" + Long.toUnsignedString(System.nanoTime(), 36);
        }
        return name;
    }

    /** Where the body goes. Closing it does not publish anything. */
    public OutputStream stream() {
        return stream;
    }

    /**
     * Puts the staged bytes in place of {@code fileName}. Call only after the request body has
     * been read to its end and {@link #stream()} closed. On any failure the staging file is
     * removed and the previous file, if there was one, is left as it was.
     */
    public void commit() throws IOException {
        if (finished) return;
        finished = true;
        long written = progress.getTotal();
        if (expectedSize > 0 && written != expectedSize) {
            removeTemp();
            throw new IncompleteUploadException("Upload ended after " + written + " of " +
                    expectedSize + " bytes");
        }
        if (!temp.exists()) {
            //the quota listener already removed it after a failed write
            throw new IOException("Upload of " + fileName + " failed");
        }
        if (!replace()) {
            removeTemp();
            throw new IOException("Cannot replace " + fileName);
        }
        if (previousSize > 0) {
            quota.addUsedQuietly(-previousSize);
        }
    }

    /** Throws the staged bytes away. Safe to call after a failed or a finished commit. */
    public void discard() {
        if (finished) return;
        finished = true;
        try {
            stream.close();
        } catch (Exception ignored) {
            //already closed by the body reader, or the write failed; the bytes go either way
        }
        removeTemp();
    }

    private void removeTemp() {
        //the quota listener deletes the file itself when a write or the close failed, and has
        //then already given its bytes back
        if (temp.exists() && temp.delete()) {
            quota.addUsedQuietly(-progress.getTotal());
        }
    }

    private boolean replace() {
        if (existing != null && existing.exists()) {
            String backup = stagingName(fileName);
            if (!existing.renameTo(backup)) {
                return false;
            }
            if (!temp.renameTo(fileName)) {
                existing.renameTo(fileName);
                return false;
            }
            existing.delete();
            return true;
        }
        return temp.renameTo(fileName);
    }
}
