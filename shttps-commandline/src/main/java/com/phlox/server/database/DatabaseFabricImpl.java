package com.phlox.server.database;

import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;

public class DatabaseFabricImpl implements SHTTPSDatabaseFabric {

    public DatabaseFabricImpl() {
    }

    @Override
    public Database createDatabase(String path) throws Exception {
        //database file created on first table creation (by org.xerial:sqlite-jdbc)
        throw new RuntimeException("Not implemented");
    }

    @Override
    public Database openDatabase(String path) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + path);
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
}
