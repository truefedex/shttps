package com.phlox.simpleserver.database;

import com.phlox.simpleserver.database.model.TableData;

import org.json.JSONObject;

public final class DatabaseMigrator {
    public static final int CURRENT_SCHEMA_VERSION = 5;
    private DatabaseMigrator() {}

    public static void runMigrations(Database db, boolean usersInDB) {
        int shttpsDBVersion = 1;
        try {
            shttpsDBVersion = db.runTransaction((DatabaseOperations database) -> {
                database.execute("CREATE TABLE IF NOT EXISTS shttps_version" +
                        " ( 'version' INTEGER PRIMARY KEY )");
                try (TableData td = database.getTableDataSecure("shttps_version")) {
                    if (td.next()) {
                        return td.getInt(td.getColumnIndex("version"));
                    } else {
                        int guessVersion = 1;// first version had no version table, but had users table if usersInDB is true
                        try (TableData userTD = database.query("SELECT count(name) AS count FROM sqlite_master WHERE type='table' AND name = 'user'", null, false)) {
                            if (userTD.next()) {
                                int count = userTD.getInt(userTD.getColumnIndex("count"));
                                if (usersInDB) {
                                    if (count == 0) {
                                        // users should be in DB, but user table is missing
                                        toggleUsersInDB(database, true);
                                        guessVersion = CURRENT_SCHEMA_VERSION;
                                    } // else stay with version 1
                                } else {
                                    if (count > 0) {
                                        // users shouldn't be in DB, but user table exists - drop it and assume version is current
                                        toggleUsersInDB(database, false);
                                        guessVersion = CURRENT_SCHEMA_VERSION;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }

                        database.insert("shttps_version", new JSONObject("{\"version\": 1}"));
                        return 1;
                    }
                }
            });

            if (shttpsDBVersion == 1) {
                runVersion1to2Migration(db, usersInDB);
                shttpsDBVersion = 2;
            }

            if (shttpsDBVersion == 2) {
                runVersion2to3Migration(db, usersInDB);
                shttpsDBVersion = 3;
            }

            if (shttpsDBVersion == 3) {
                runVersion3to4Migration(db, usersInDB);
                shttpsDBVersion = 4;
            }

            if (shttpsDBVersion == 4) {
                runVersion4to5Migration(db, usersInDB);
                shttpsDBVersion = 5;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void runVersion1to2Migration(Database db, boolean usersInDB) throws Exception {
        db.runTransaction((DatabaseOperations database) -> {
            if (usersInDB) {
                database.execute("CREATE TABLE IF NOT EXISTS 'user_role' (" +
                        "'name' TEXT NOT NULL UNIQUE," +
                        "'fs_rights' INTEGER NOT NULL," +
                        "'db_rights' INTEGER NOT NULL," +
                        "PRIMARY KEY('name')" +
                        ");");
                database.execute("ALTER TABLE 'user'" +
                        " ADD COLUMN 'role' TEXT REFERENCES 'user_role' ON DELETE SET NULL");
                database.execute("CREATE INDEX role_index ON 'user'('role')");
            }
            database.update("shttps_version", new JSONObject("{\"version\": 2}"), null, null);
            return true;
        });
    }

    private static void runVersion2to3Migration(Database db, boolean usersInDB) throws Exception {
        // Migrate existing users to have registered_at and last_login column with current timestamp by default
        db.runTransaction((DatabaseOperations database) -> {
            if (usersInDB) {
                database.execute("ALTER TABLE 'user'" +
                        " ADD COLUMN 'registered_at' INTEGER NOT NULL DEFAULT 0");
                database.execute("ALTER TABLE 'user'" +
                        " ADD COLUMN 'last_login' INTEGER");
                database.execute("UPDATE user SET registered_at = CAST(strftime('%s','now') AS INTEGER) * 1000;");
            }
            database.update("shttps_version", new JSONObject("{\"version\": 3}"), null, null);
            return true;
        });
    }

    private static void runVersion3to4Migration(Database db, boolean usersInDB) throws Exception {
        db.runTransaction((DatabaseOperations database) -> {
            if (usersInDB) {
                database.execute("ALTER TABLE 'user' ADD COLUMN 'storage_limit' INTEGER");
                database.execute("ALTER TABLE 'user'" +
                        " ADD COLUMN 'system_rights' INTEGER NOT NULL DEFAULT 0");
                database.execute("ALTER TABLE 'user'" +
                        " ADD COLUMN 'used_storage' INTEGER NOT NULL DEFAULT 0");

                database.execute("ALTER TABLE 'user_role' ADD COLUMN 'storage_limit' INTEGER");
                database.execute("ALTER TABLE 'user_role'" +
                        " ADD COLUMN 'system_rights' INTEGER NOT NULL DEFAULT 0");
            }
            database.update("shttps_version", new JSONObject("{\"version\": 4}"), null, null);
            return true;
        });
    }

    private static void runVersion4to5Migration(Database db, boolean usersInDB) throws Exception {
        db.runTransaction((DatabaseOperations database) -> {
            if (usersInDB) {
                // Create new tables for access rules
                database.execute("CREATE TABLE IF NOT EXISTS 'shttps_db_access_rule' (" +
                        "'role_name' TEXT NOT NULL REFERENCES 'user_role' ON DELETE CASCADE ON UPDATE CASCADE," +
                        "'subject' TEXT NOT NULL," +
                        "'operation' TEXT NOT NULL," +
                        "'allow' INTEGER NOT NULL," +
                        "'expression' TEXT," +
                        "PRIMARY KEY('role_name', 'subject', 'operation')" +
                        ");");
                database.execute("CREATE INDEX db_access_rule_role_index ON 'shttps_db_access_rule'('role_name')");
                database.execute("CREATE TABLE IF NOT EXISTS 'shttps_fs_access_rule' (" +
                        "'role_name' TEXT NOT NULL REFERENCES 'user_role' ON DELETE CASCADE ON UPDATE CASCADE," +
                        "'subject' TEXT NOT NULL," +
                        "'operation' TEXT NOT NULL," +
                        "'allow' INTEGER NOT NULL," +
                        "'expression' TEXT," +
                        "PRIMARY KEY('role_name', 'subject', 'operation')" +
                        ");");
                database.execute("CREATE INDEX fs_access_rule_role_index ON 'shttps_fs_access_rule'('role_name')");
            }
            database.update("shttps_version", new JSONObject("{\"version\": 5}"), null, null);
            return true;
        });
    }

    public static void toggleUsersInDB(Database db, boolean usersInDB) throws Exception {
        db.runTransaction((DatabaseOperations database) -> {
            toggleUsersInDB(database, usersInDB);
            return null;
        });
    }

    private static void toggleUsersInDB(DatabaseOperations database, boolean usersInDB) throws Exception {
        if (usersInDB) {
            database.execute("CREATE TABLE IF NOT EXISTS 'user_role' (" +
                    "'name' TEXT NOT NULL UNIQUE," +
                    "'fs_rights' INTEGER NOT NULL," +
                    "'db_rights' INTEGER NOT NULL," +
                    "'storage_limit' INTEGER," +
                    "'system_rights' INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY('name')" +
                    ");");
            database.execute("CREATE TABLE IF NOT EXISTS 'shttps_db_access_rule' (" +
                    "'role_name' TEXT NOT NULL REFERENCES 'user_role' ON DELETE CASCADE ON UPDATE CASCADE," +
                    "'subject' TEXT NOT NULL," +
                    "'operation' TEXT NOT NULL," +
                    "'allow' INTEGER NOT NULL," +
                    "'expression' TEXT," +
                    "PRIMARY KEY('role_name', 'subject', 'operation')" +
                    ");");
            database.execute("CREATE INDEX db_access_rule_role_index ON 'shttps_db_access_rule'('role_name')");
            database.execute("CREATE TABLE IF NOT EXISTS 'shttps_fs_access_rule' (" +
                    "'role_name' TEXT NOT NULL REFERENCES 'user_role' ON DELETE CASCADE ON UPDATE CASCADE," +
                    "'subject' TEXT NOT NULL," +
                    "'operation' TEXT NOT NULL," +
                    "'allow' INTEGER NOT NULL," +
                    "'expression' TEXT," +
                    "PRIMARY KEY('role_name', 'subject', 'operation')" +
                    ");");
            database.execute("CREATE INDEX fs_access_rule_role_index ON 'shttps_fs_access_rule'('role_name')");
            database.execute("create table IF NOT EXISTS 'user'" +
                    " ( 'identity' text primary key, " +
                    "'password' text not null, " +
                    "'root_dir' text, " +
                    "'fs_rights' integer not null, " +
                    "'db_rights' integer not null, " +
                    "'role' TEXT REFERENCES 'user_role' ON DELETE SET NULL ON UPDATE CASCADE, " +
                    "'registered_at' INTEGER NOT NULL DEFAULT 0, " +
                    "'last_login' INTEGER," +
                    "'storage_limit' INTEGER," +
                    "'system_rights' INTEGER NOT NULL DEFAULT 0," +
                    "'used_storage' INTEGER NOT NULL DEFAULT 0" +
                    ");");
            database.execute("CREATE INDEX role_index ON 'user'('role')");
        } else {
            database.execute("DROP TABLE IF EXISTS user");
            database.execute("DROP TABLE IF EXISTS user_role");
        }
    }
}
