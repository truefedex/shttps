package com.phlox.simpleserver.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.phlox.server.utils.Utils;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class DatabaseFabricAndroid implements SHTTPSDatabaseFabric {
    private final Context context;
    private final SHTTPSPlatformUtils platformUtils;

    public DatabaseFabricAndroid(Context context, SHTTPSPlatformUtils platformUtils) {
        this.context = context;
        this.platformUtils = platformUtils;
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
        File databaseDirectory = new File(context.getFilesDir(), "databases");
        if (!databaseDirectory.exists()) {
            databaseDirectory.mkdirs();
        }
        String uniqueDatabaseName = "database" + System.currentTimeMillis() + ".db";
        File databaseFile = new File(databaseDirectory, uniqueDatabaseName);
        try (FileOutputStream fileOutputStream = new FileOutputStream(databaseFile); inputStream) {
            Utils.copyStream(inputStream, fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new DatabaseAndroid(context, databaseDirectory, uniqueDatabaseName);
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
