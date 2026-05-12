package com.phlox.simpleserver.backup;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores a server backup produced by {@link DataExporter}.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>The archive is processed in a single streaming pass - no staging directory and no
 *       temp files are created.</li>
 *   <li>The {@code root_dir}, {@code database_path} and {@code tls_cert} keys from the
 *       archive's config are intentionally <b>not</b> applied: the local paths must remain
 *       the same because they may carry filesystem permissions / SAF grants. Only the
 *       <b>contents</b> at those locations are replaced.</li>
 *   <li>The database file is replaced via
 *       {@link com.phlox.simpleserver.database.SHTTPSDatabaseFabric#deleteDatabase(String)} +
 *       {@link com.phlox.simpleserver.database.SHTTPSDatabaseFabric#importDatabase(InputStream)};
 *       the resulting fresh path becomes the new {@code database_path}.</li>
 *   <li>The TLS certificate is replaced via {@link SHTTPSConfig#installTLSCertBytes(byte[])}.</li>
 *   <li>Root directory contents are written through the {@link DocumentFile} API into the
 *       <i>existing</i> {@link SHTTPSConfig#getRootDir()}.</li>
 * </ul>
 *
 * <p><b>Caller is responsible for ensuring the server is stopped</b> before invoking
 * {@link #importData(InputStream, boolean)} - the method will refuse otherwise.
 */
public class DataImporter {
    private static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DataImporter.class);

    private final SHTTPSApp app;
    private final SHTTPSConfig config;

    /**
     * Set to {@code true} by {@link #importData(InputStream, boolean)} when the processed
     * archive contained one or more entries under the {@code cgi/} folder. Those entries are
     * deliberately NOT extracted - the caller is expected to notify the user that the archive
     * carries a CGI folder which must be restored manually (the CGI folder path is a
     * filesystem-only setting and reliably picking the target location is up to the user).
     */
    public boolean archiveContainedCgi = false;

    public DataImporter(SHTTPSApp app) {
        this.app = app;
        this.config = app.config;
    }

    /** Thrown when {@code shouldOverride == false} but local data already exists. */
    public static class ExistingDataException extends IOException {
        public final boolean databaseExists;
        public final boolean tlsCertExists;
        public final boolean rootDirHasContent;

        public ExistingDataException(boolean db, boolean cert, boolean root) {
            super(buildMessage(db, cert, root));
            this.databaseExists = db;
            this.tlsCertExists = cert;
            this.rootDirHasContent = root;
        }

        private static String buildMessage(boolean db, boolean cert, boolean root) {
            StringBuilder sb = new StringBuilder("Refusing to import: existing data found - ");
            boolean first = true;
            if (db)   { sb.append("database");    first = false; }
            if (cert) { if (!first) sb.append(", "); sb.append("TLS certificate"); first = false; }
            if (root) { if (!first) sb.append(", "); sb.append("root directory contents"); }
            sb.append(". Set shouldOverride=true to replace it.");
            return sb.toString();
        }
    }

    /** Thrown when the archive is missing required entries or is otherwise malformed. */
    public static class InvalidArchiveException extends IOException {
        public InvalidArchiveException(String msg) { super(msg); }
        public InvalidArchiveException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * Performs a full restore from {@code in}. The stream is fully consumed but not closed.
     *
     * @param in              ZIP archive produced by {@link DataExporter}.
     * @param shouldOverride  when true, existing local data (database file, TLS certificate,
     *                        root directory contents) is replaced. When false, any pre-existing
     *                        data triggers {@link ExistingDataException} and the import is
     *                        aborted before any change is made.
     */
    public void importData(InputStream in, boolean shouldOverride) throws IOException {
        if (in == null) throw new IllegalArgumentException("in must not be null");

        // Reset stateful import-result flags so the caller sees only data from this invocation.
        archiveContainedCgi = false;

        if (app.isServerRunning()) {
            throw new IllegalStateException("Cannot import while server is running");
        }

        // 1. Probe existing state (against the *current* config, before any modifications).
        boolean existingDb = currentDatabaseFileExists();
        boolean existingCert = config.getTLSCertBytes() != null;
        boolean existingRoot = currentRootDirHasContent();
        if (!shouldOverride && (existingDb || existingCert || existingRoot)) {
            throw new ExistingDataException(existingDb, existingCert, existingRoot);
        }

        // 2. If overriding, clean root and remove the existing database BEFORE applying anything.
        //    Note: paths from the imported config are stripped later, so currentDatabasePath()
        //    still refers to the local DB on disk.
        if (shouldOverride) {
            cleanCurrentRootDir();
            if (existingDb) {
                if (app.getDatabase() != null) {
                    // Close the open handle so the underlying file can be removed cleanly.
                    app.setDatabase(null);
                }
                String dbPath = currentDatabasePath();
                if (dbPath != null) {
                    try {
                        app.databaseFabric.deleteDatabase(dbPath);
                    } catch (Exception e) {
                        logger.w("Failed to delete existing database at " + dbPath, e);
                    }
                }
            }
        }

        // 3. Stream-process ZIP entries in archive order
        //    (DataExporter writes config -> tls_cert -> database -> root/...).
        ZipInputStream zip = new ZipInputStream(in);
        boolean configApplied = false;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String rawName = entry.getName();
            if (rawName == null || rawName.isEmpty()) continue;
            String name = sanitizeEntryName(rawName);

            if (entry.isDirectory()) {
                // Directories are materialised on demand by writeEntryToRoot(); nothing to do here.
                continue;
            }

            if (name.equals(DataExporter.FILE_CONFIG)) {
                String json = readEntryAsString(zip);
                JSONObject configJson;
                try {
                    configJson = new JSONObject(json);
                } catch (JSONException e) {
                    throw new InvalidArchiveException("Malformed " + DataExporter.FILE_CONFIG, e);
                }
                // Strip path-related keys: local paths are bound to the current device's
                // permissions / sandbox and must not be replaced by paths from another device.
                // KEY_CGI_FOLDER is included here too - we'll clear it explicitly below.
                configJson.remove(SHTTPSConfig.KEY_ROOT_DIR);
                configJson.remove(SHTTPSConfig.KEY_DATABASE_PATH);
                configJson.remove(SHTTPSConfig.KEY_TLS_CERT);
                configJson.remove(SHTTPSConfig.KEY_CGI_FOLDER);
                try {
                    config.applyAll(configJson);
                } catch (Exception e) {
                    throw new InvalidArchiveException("Failed to apply imported config", e);
                }
                // Always reset the CGI folder on import. The imported path almost certainly
                // does not exist on the target machine, and the previously configured local
                // path most likely pointed inside the *old* root contents (which we have just
                // wiped during the override flow). The safest default is to clear it - the
                // server then falls back to the main root folder as the CGI folder (see
                // CgiMiddleware), and the user can pick a new dedicated location later.
                config.setCGIFolder(null);
                configApplied = true;
            } else if (name.equals(DataExporter.FILE_TLS_CERT)) {
                byte[] certBytes = readEntryAsBytes(zip);
                if (certBytes.length > 0) {
                    config.installTLSCertBytes(certBytes);
                }
            } else if (name.equals(DataExporter.FILE_DATABASE)) {
                Database newDb;
                try {
                    // CloseShield prevents the fabric's try-with-resources from closing our
                    // outer ZipInputStream when it consumes the entry.
                    newDb = app.databaseFabric.importDatabase(new CloseShieldInputStream(zip));
                } catch (Exception e) {
                    throw new InvalidArchiveException("Failed to import database", e);
                }
                if (newDb != null) {
                    // The fabric picks a fresh local path - record it and adopt it as the active DB.
                    config.setDatabasePath(newDb.getPath());
                    app.setDatabase(newDb);
                }
            } else if (name.startsWith(DataExporter.DIR_ROOT)) {
                String rel = name.substring(DataExporter.DIR_ROOT.length());
                if (rel.isEmpty()) {
                    drain(zip);
                } else {
                    writeEntryToRoot(zip, rel);
                }
            } else if (name.startsWith(DataExporter.DIR_CGI)) {
                // Deliberately NOT extracted - see archiveContainedCgi javadoc. We still
                // drain the entry so the ZipInputStream advances correctly.
                String rel = name.substring(DataExporter.DIR_CGI.length());
                if (!rel.isEmpty()) {
                    archiveContainedCgi = true;
                }
                drain(zip);
            } else {
                logger.w("Skipping unknown archive entry: " + name);
                drain(zip);
            }
        }

        if (!configApplied) {
            throw new InvalidArchiveException("Archive does not contain " + DataExporter.FILE_CONFIG);
        }
    }

    // ---------------------------------------------------------------------
    // Existing-data probes (operate on the *current* config, before applyAll)
    // ---------------------------------------------------------------------

    private String currentDatabasePath() {
        Database db = app.getDatabase();
        if (db != null) {
            try {
                String p = db.getPath();
                if (p != null && !p.isEmpty()) return p;
            } catch (Exception ignored) {}
        }
        String path = config.getDatabasePath();
        return (path == null || path.isEmpty()) ? null : path;
    }

    private boolean currentDatabaseFileExists() {
        String path = currentDatabasePath();
        if (path == null) return false;
        File f = new File(path);
        return f.exists() && f.isFile() && f.length() > 0;
    }

    private boolean currentRootDirHasContent() {
        DocumentFile root = config.getRootDir();
        if (root == null || !root.exists() || !root.isDirectory()) return false;
        DocumentFile[] children = root.listFiles();
        return children != null && children.length > 0;
    }

    // ---------------------------------------------------------------------
    // Apply helpers
    // ---------------------------------------------------------------------

    private void cleanCurrentRootDir() {
        DocumentFile root = config.getRootDir();
        if (root == null || !root.exists() || !root.isDirectory()) return;
        DocumentFile[] children = root.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            if (!child.delete()) {
                logger.w("Failed to delete during clean: " + child.getUri());
            }
        }
    }

    /**
     * Writes a single archive entry into the configured root directory at the given
     * relative path, creating any missing intermediate directories. Operates strictly
     * through the {@link DocumentFile} API so it works for both raw filesystem paths
     * and Android SAF / MediaStore-backed roots.
     */
    private void writeEntryToRoot(ZipInputStream zip, String relPath) throws IOException {
        DocumentFile root = config.getRootDir();
        if (root == null) {
            throw new IOException("Root directory not configured");
        }
        if (!root.exists() || !root.isDirectory()) {
            throw new IOException("Root path does not exist or is not a directory: " + root.getUri());
        }

        String[] parts = relPath.split("/");
        DocumentFile current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            DocumentFile sub = current.findFile(part);
            if (sub == null) {
                sub = current.createDirectory(part);
                if (sub == null) {
                    throw new IOException("Failed to create directory: " + part + " under " + current.getUri());
                }
            } else if (!sub.isDirectory()) {
                if (!sub.delete()) {
                    throw new IOException("Failed to replace file with directory: " + sub.getUri());
                }
                sub = current.createDirectory(part);
                if (sub == null) {
                    throw new IOException("Failed to create directory: " + part + " under " + current.getUri());
                }
            }
            current = sub;
        }

        String fileName = parts[parts.length - 1];
        if (fileName.isEmpty()) {
            // Trailing slash - effectively only created directories.
            return;
        }
        DocumentFile existing = current.findFile(fileName);
        if (existing != null && !existing.delete()) {
            throw new IOException("Failed to replace existing file: " + existing.getUri());
        }
        DocumentFile target = current.createFile("application/octet-stream", fileName);
        if (target == null) {
            throw new IOException("Failed to create file: " + fileName + " under " + current.getUri());
        }
        try (OutputStream os = target.openOutputStream()) {
            Utils.copyStream(zip, os);
        }
    }

    // ---------------------------------------------------------------------
    // Low-level helpers
    // ---------------------------------------------------------------------

    /**
     * Validates a ZIP entry name and converts backslashes to forward slashes.
     * Rejects path traversal and absolute path indicators that could be used to
     * escape the destination root.
     */
    private static String sanitizeEntryName(String name) throws InvalidArchiveException {
        String normalized = name.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new InvalidArchiveException("Refusing zip entry with parent traversal: " + name);
            }
        }
        if (normalized.startsWith("/")) {
            throw new InvalidArchiveException("Refusing zip entry with absolute path: " + name);
        }
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            throw new InvalidArchiveException("Refusing zip entry with drive letter: " + name);
        }
        return normalized;
    }

    private static String readEntryAsString(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Utils.copyStream(zip, buf);
        // ByteArrayOutputStream.toString(Charset) requires API 33; use the name-based overload
        // which is available on every supported Android level.
        return buf.toString(Charset.forName("UTF-8").name());
    }

    private static byte[] readEntryAsBytes(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Utils.copyStream(zip, buf);
        return buf.toByteArray();
    }

    private static void drain(ZipInputStream zip) throws IOException {
        byte[] buf = new byte[8192];
        while (zip.read(buf) != -1) {
            // discard
        }
    }

    /**
     * {@link FilterInputStream} that ignores {@link #close()}. Used when handing the
     * (still-active) {@link ZipInputStream} to a callee that uses try-with-resources -
     * we want the callee to consume the current entry but not terminate the outer ZIP.
     */
    private static class CloseShieldInputStream extends FilterInputStream {
        CloseShieldInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // No-op: prevent closing the underlying stream.
        }
    }
}
