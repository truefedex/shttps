package com.phlox.server.database;

import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.io.InputStream;

public class DatabaseFabricImpl implements SHTTPSDatabaseFabric {

    public DatabaseFabricImpl() {
    }

    @Override
    public Database createDatabase(String path) throws Exception {
        return openDatabase(path);
    }

    @Override
    public Database openDatabase(String path) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + path);
        dataSource.setBusyTimeout(50000);
        return new DatabaseImpl(dataSource, path);
    }

    @Override
    public Database importDatabase(InputStream inputStream) throws Exception {
        //copy inputStream to defaultDatabasesPath and then open it
        /*File databaseFile = new File(defaultDatabasesPath, "database" + System.currentTimeMillis() + ".db");
        try (FileOutputStream fileOutputStream = new FileOutputStream(databaseFile)) {
            Utils.copyStream(inputStream, fileOutputStream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return openDatabase(databaseFile.getAbsolutePath());*/
        throw new RuntimeException("Not implemented");
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
