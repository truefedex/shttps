package com.phlox.simpleserver.backup;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;

import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports the server data into a single ZIP archive that can later be restored.
 *
 * <p>The produced archive layout:
 * <pre>
 * config.json        // serialized {@link SHTTPSConfig#serializeAll()}
 * tls_cert.bin       // raw TLS keystore bytes (PKCS12/BKS) - present iff TLS is enabled and a cert is set
 * database.db        // SQLite database file - present iff database is enabled and the file exists
 * root/...           // (optional) full contents of the configured server root directory
 * cgi/...            // contents of the CGI root folder - only when it is not located inside root/
 * </pre>
 *
 * <p>The class is lightweight and synchronous - the caller is responsible for
 * running it on a background thread / piping into a network stream as needed.
 */
public class DataExporter {
    public static final String FILE_CONFIG = "config.json";
    public static final String FILE_TLS_CERT = "tls_cert.bin";
    public static final String FILE_DATABASE = "database.db";
    public static final String DIR_ROOT = "root/";
    public static final String DIR_CGI = "cgi/";

    private static final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(DataExporter.class);

    private final SHTTPSConfig config;
    @Nullable private final SHTTPSApp app;

    public DataExporter(SHTTPSApp app) {
        if (app == null) throw new IllegalArgumentException("app must not be null");
        this.app = app;
        this.config = app.config;
    }

    public DataExporter(SHTTPSConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.app = null;
        this.config = config;
    }

    /**
     * Writes the export archive to the given {@link OutputStream}. The underlying
     * stream is NOT closed by this method - the ZIP central directory is finalized
     * via {@link ZipOutputStream#finish()} so the caller may continue to use the
     * stream (and is responsible for closing it).
     *
     * @param out             where to write the archive (will be wrapped in a ZipOutputStream)
     * @param includeRootDir  when true, the contents of {@link SHTTPSConfig#getRootDir()} will
     *                        be embedded under the {@code root/} folder in the archive.
     */
    public void export(OutputStream out, boolean includeRootDir) throws IOException {
        if (out == null) throw new IllegalArgumentException("out must not be null");

        File dbFile = config.isDatabaseEnabled() ? resolveDatabaseFile() : null;
        boolean certWillBeIncluded = config.getUseTLS();

        ZipOutputStream zip = new ZipOutputStream(out);

        // 1. Config (no certificate bytes; those go separately due to platform differences)
        JSONObject configJson = config.serializeAll();
        writeStringEntry(zip, FILE_CONFIG, configJson.toString(2));

        // 2. TLS certificate (if any)
        if (certWillBeIncluded) {
            byte[] certBytes = null;
            try {
                certBytes = config.getTLSCertBytes();
            } catch (Exception e) {
                logger.e("Failed to read TLS certificate bytes", e);
            }
            if (certBytes != null && certBytes.length > 0) {
                writeBytesEntry(zip, FILE_TLS_CERT, certBytes);
            } else {
                logger.w("TLS is enabled but no certificate bytes were available - skipping cert export");
            }
        }

        // 3. Database file
        if (config.isDatabaseEnabled()) {
            if (dbFile != null) {
                writeFileEntry(zip, FILE_DATABASE, dbFile);
            } else {
                logger.w("Database is enabled but no readable database file could be located - skipping db export");
            }
        }

        // 4. Optional: full root directory tree
        if (includeRootDir) {
            DocumentFile root = config.getRootDir();
            if (root != null && root.exists() && root.isDirectory()) {
                // Empty directory entry to make the structure visible even when root is empty.
                zip.putNextEntry(new ZipEntry(DIR_ROOT));
                zip.closeEntry();
                addDirRecursively(zip, root, root, DIR_ROOT);
            } else {
                logger.w("Root directory not available; root contents will not be included");
            }
        }

        // 5. CGI folder. Always uses a regular filesystem path. Only included when it lies
        //    OUTSIDE the main root folder (otherwise the entries would already be covered by the
        //    root/ tree above when includeRootDir is true).
        String cgiPath = config.getCGIFolder();
        if (cgiPath != null && !cgiPath.isEmpty()) {
            File cgiDir = new File(cgiPath);
            if (cgiDir.exists() && cgiDir.isDirectory()) {
                if (isCgiInsideRoot(cgiDir, config.getRootDir())) {
                    logger.i("CGI folder lies inside the root folder - skipping separate cgi/ entry");
                } else {
                    zip.putNextEntry(new ZipEntry(DIR_CGI));
                    zip.closeEntry();
                    addFileTreeRecursively(zip, cgiDir, cgiDir, DIR_CGI);
                }
            } else {
                logger.w("CGI folder is configured but does not exist on disk: " + cgiPath);
            }
        }

        zip.finish();
    }

    /**
     * Returns {@code true} if {@code cgiDir} is the root itself or a descendant of it on the
     * regular filesystem. When the root is backed by a non-file DocumentFile (e.g. Android
     * SAF tree or MediaStore URI), this returns {@code false} - in that case the CGI folder
     * can never overlap with the root.
     */
    private static boolean isCgiInsideRoot(File cgiDir, DocumentFile root) {
        if (root == null) return false;
        File rootFile = RawDocumentFile.getFile(root);
        if (rootFile == null) return false;
        try {
            String cgiCanon = cgiDir.getCanonicalPath();
            String rootCanon = rootFile.getCanonicalPath();
            return cgiCanon.equals(rootCanon)
                    || cgiCanon.startsWith(rootCanon + File.separator);
        } catch (IOException e) {
            logger.w("Failed to compare CGI / root paths, assuming separate", e);
            return false;
        }
    }

    /**
     * Tries to locate the SQLite database file on the local filesystem. Prefers
     * the path reported by an already-open {@link Database} instance (when an
     * {@link SHTTPSApp} was provided), falling back to {@link SHTTPSConfig#getDatabasePath()}.
     */
    @Nullable
    private File resolveDatabaseFile() {
        String path = null;
        if (app != null) {
            Database db = app.getDatabase();
            if (db != null) {
                try {
                    path = db.getPath();
                } catch (Exception e) {
                    logger.e("Failed to get path from open Database instance", e);
                }
            }
        }
        if (path == null || path.isEmpty()) {
            path = config.getDatabasePath();
        }
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return null;
        return f;
    }

    private static void addDirRecursively(ZipOutputStream zip, DocumentFile baseDir,
                                          DocumentFile current, String entryPrefix) throws IOException {
        DocumentFile[] children = current.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) continue;

            String relPath = baseDir.getRelativePath(child);
            if (relPath == null) {
                // Fall back to using the simple name when relative path resolution fails.
                relPath = name;
            }
            relPath = relPath.replace('\\', '/');
            if (relPath.startsWith("/")) relPath = relPath.substring(1);
            if (relPath.isEmpty()) continue;

            String entryName = entryPrefix + relPath;

            if (child.isDirectory()) {
                if (!entryName.endsWith("/")) entryName += "/";
                zip.putNextEntry(new ZipEntry(entryName));
                zip.closeEntry();
                addDirRecursively(zip, baseDir, child, entryPrefix);
            } else if (child.isFile()) {
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream is = child.openInputStream()) {
                    if (is != null) {
                        Utils.copyStream(is, zip);
                    }
                }
                zip.closeEntry();
            }
            // Skip virtual / unknown entry types.
        }
    }

    private static void writeStringEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeBytesEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void writeFileEntry(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            Utils.copyStream(in, zip);
        }
        zip.closeEntry();
    }

    /**
     * Recursively zips a regular filesystem directory into {@code zip} under {@code entryPrefix}.
     * Used for assets that are guaranteed to live on the plain filesystem (e.g. the CGI folder),
     * as opposed to the DocumentFile-backed root directory.
     */
    private static void addFileTreeRecursively(ZipOutputStream zip, File baseDir, File current,
                                               String entryPrefix) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        String baseCanonical;
        try {
            baseCanonical = baseDir.getCanonicalPath();
        } catch (IOException e) {
            baseCanonical = baseDir.getAbsolutePath();
        }
        for (File child : children) {
            String name = child.getName();
            if (name == null || name.isEmpty()) continue;

            String childCanonical;
            try {
                childCanonical = child.getCanonicalPath();
            } catch (IOException e) {
                childCanonical = child.getAbsolutePath();
            }
            String rel;
            if (childCanonical.startsWith(baseCanonical + File.separator)) {
                rel = childCanonical.substring(baseCanonical.length() + 1);
            } else {
                rel = name;
            }
            rel = rel.replace(File.separatorChar, '/');

            String entryName = entryPrefix + rel;

            if (child.isDirectory()) {
                if (!entryName.endsWith("/")) entryName += "/";
                zip.putNextEntry(new ZipEntry(entryName));
                zip.closeEntry();
                addFileTreeRecursively(zip, baseDir, child, entryPrefix);
            } else if (child.isFile()) {
                writeFileEntry(zip, entryName, child);
            }
        }
    }
}
