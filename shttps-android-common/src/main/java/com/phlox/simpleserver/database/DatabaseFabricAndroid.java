package com.phlox.simpleserver.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.phlox.server.utils.Utils;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class DatabaseFabricAndroid implements SHTTPSDatabaseFabric {
    /** Where databases go when the caller names no directory of its own. */
    public static final String DEFAULT_DATABASES_DIR_NAME = "databases";

    private final Context context;
    private final SHTTPSPlatformUtils platformUtils;
    private final File databasesDir;

    /**
     * Puts imported databases in {@code filesDir/databases}, which is where this class always put
     * them. An app that keeps several server configurations needs the directory of whichever one
     * is live and should use {@link #DatabaseFabricAndroid(Context, SHTTPSPlatformUtils, File)}
     * instead - two configurations sharing one SQLite file would share one users table with it.
     */
    public DatabaseFabricAndroid(Context context, SHTTPSPlatformUtils platformUtils) {
        this(context, platformUtils, new File(context.getFilesDir(), DEFAULT_DATABASES_DIR_NAME));
    }

    /**
     * @param databasesDir where {@link #importDatabase(InputStream)} puts the file it creates.
     *                     {@link SHTTPSDatabaseFabric#importDatabase(InputStream)} takes no target
     *                     path, so the fabric is the only place that can be told.
     */
    public DatabaseFabricAndroid(Context context, SHTTPSPlatformUtils platformUtils,
                                 File databasesDir) {
        this.context = context;
        this.platformUtils = platformUtils;
        this.databasesDir = databasesDir;
    }

    @Override
    public Database createDatabase(String path) {
        File dbFile = new File(path);
        return new DatabaseAndroid(context, dbFile.getParentFile(), dbFile.getName());
    }

    @Override
    public Database openDatabase(String path) {
        File databaseFile = new File(path);
        File databaseDirectory = databaseFile.getParentFile();
        return new DatabaseAndroid(context, databaseDirectory, databaseFile.getName());
    }

    @Override
    public Database importDatabase(InputStream inputStream) {
        if (!databasesDir.exists()) {
            databasesDir.mkdirs();
        }
        String uniqueDatabaseName = "database" + System.currentTimeMillis() + ".db";
        File databaseFile = new File(databasesDir, uniqueDatabaseName);
        try (FileOutputStream fileOutputStream = new FileOutputStream(databaseFile); inputStream) {
            Utils.copyStream(inputStream, fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new DatabaseAndroid(context, databasesDir, uniqueDatabaseName);
    }

    @Override
    public boolean deleteDatabase(String path) {
        if (path == null || path.isEmpty()) return true;
        File f = new File(path);
        if (!f.exists()) return true;
        // SQLiteDatabase.deleteDatabase removes the main file plus -journal, -shm, -wal companions.
        return SQLiteDatabase.deleteDatabase(f);
    }
}
