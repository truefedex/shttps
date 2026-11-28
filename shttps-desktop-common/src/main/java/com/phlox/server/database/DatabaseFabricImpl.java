package com.phlox.server.database;

import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.server.utils.Utils;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class DatabaseFabricImpl implements SHTTPSDatabaseFabric {
    /** Where databases go when the caller names no directory of its own. */
    public static final String DEFAULT_DATABASES_DIR_NAME = "databases";

    private final File databasesDir;

    /**
     * Puts an imported database in {@code databases} under the working directory.
     *
     * An app that keeps several server configurations needs the directory of whichever one is
     * live and should use {@link #DatabaseFabricImpl(File)} instead - two configurations sharing
     * one SQLite file would share one users table with it.
     */
    public DatabaseFabricImpl() {
        this(new File(DEFAULT_DATABASES_DIR_NAME));
    }

    /**
     * @param databasesDir where {@link #importDatabase(InputStream)} puts the file it creates.
     *                     {@link SHTTPSDatabaseFabric#importDatabase(InputStream)} takes no target
     *                     path, so the fabric is the only place that can be told.
     */
    public DatabaseFabricImpl(File databasesDir) {
        this.databasesDir = databasesDir;
    }

    @Override
    public Database createDatabase(String path) throws Exception {
        return openDatabase(path);
    }

    @Override
    public Database openDatabase(String path) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        //both must be set here, on the config, so that the driver applies them while opening
        //every connection. foreign_keys is a per-connection setting (unlike journal_mode, which
        //is persisted in the db file) and is a no-op inside a transaction, so setting it after
        //the connection is handed out is unreliable.
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + path);
        dataSource.setBusyTimeout(50000);
        return new DatabaseImpl(dataSource, path);
    }

    /**
     * Writes the stream to a new file in {@link #databasesDir} and opens it.
     *
     * Used by {@link com.phlox.simpleserver.backup.DataImporter}, which adopts the path this
     * method picks and writes it into the config - so until this was implemented, restoring any
     * backup archive that carried a database failed on this platform, and failed after the rest
     * of the configuration had already been applied.
     */
    @Override
    public Database importDatabase(InputStream inputStream) throws Exception {
        if (!databasesDir.isDirectory() && !databasesDir.mkdirs()) {
            throw new IOException("Can't create " + databasesDir.getAbsolutePath());
        }
        File databaseFile =
                new File(databasesDir, "database" + System.currentTimeMillis() + ".db");
        try (InputStream in = inputStream;
             FileOutputStream out = new FileOutputStream(databaseFile)) {
            Utils.copyStream(in, out);
        }
        return openDatabase(databaseFile.getAbsolutePath());
    }

    @Override
    public boolean deleteDatabase(String path) {
        if (path == null || path.isEmpty()) return true;
        File f = new File(path);
        boolean ok = !f.exists() || f.delete();
        // Remove SQLite companion files if any.
        new File(path + "-journal").delete();
        new File(path + "-wal").delete();
        new File(path + "-shm").delete();
        return ok;
    }
}
