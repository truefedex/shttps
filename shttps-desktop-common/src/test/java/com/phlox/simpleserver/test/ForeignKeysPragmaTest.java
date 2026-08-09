package com.phlox.simpleserver.test;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForeignKeysPragmaTest {

    private File newDbFile() throws Exception {
        File dir = Files.createTempDirectory("fkpragma").toFile();
        return new File(dir, "test.db");
    }

    /** Fixture setup via raw JDBC - DatabaseImpl.execute() swallows exceptions. */
    private void initSchema(File dbFile) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER, " +
                    "FOREIGN KEY(parent_id) REFERENCES parent(id) ON DELETE CASCADE)");
            s.execute("INSERT INTO parent (id, name) VALUES (1, 'p1')");
            s.execute("INSERT INTO child (id, parent_id) VALUES (10, 1)");
            s.execute("INSERT INTO child (id, parent_id) VALUES (11, 1)");
        }
    }

    private String scalar(Database db, String sql) throws Exception {
        try (TableData data = db.query(sql, null, false)) {
            data.next();
            return data.getString(0);
        }
    }

    @Test
    public void pragmasAreAppliedOnEveryConnection() throws Exception {
        File dbFile = newDbFile();
        initSchema(dbFile);
        Database db = new DatabaseFabricImpl().openDatabase(dbFile.getAbsolutePath());
        try {
            assertEquals("wal", scalar(db, "PRAGMA journal_mode"));
            assertEquals("1", scalar(db, "PRAGMA foreign_keys"));
        } finally {
            db.close();
        }
    }

    @Test
    public void deletingParentCascadesToChildren() throws Exception {
        File dbFile = newDbFile();
        initSchema(dbFile);
        Database db = new DatabaseFabricImpl().openDatabase(dbFile.getAbsolutePath());
        try {
            db.delete("parent", new String[]{"id="}, new Object[]{1L});
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM child"));
        } finally {
            db.close();
        }
    }

    @Test
    public void cascadeAlsoWorksInsideTransaction() throws Exception {
        File dbFile = newDbFile();
        initSchema(dbFile);
        Database db = new DatabaseFabricImpl().openDatabase(dbFile.getAbsolutePath());
        try {
            db.runTransaction(ops -> ops.delete("parent", new String[]{"id="}, new Object[]{1L}));
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM child"));
        } finally {
            db.close();
        }
    }
}
