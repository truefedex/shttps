package com.phlox.simpleserver.auth;

import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;

public class User implements Serializable, Cloneable {
    public static final String GUEST_IDENTITY = "guest";
    public static final String FIELD_IDENTITY = "identity";
    public static final String FIELD_PASSWORD = "password";
    public static final String FIELD_ROOT_DIR = "root_dir";
    public static final String FIELD_FS_RIGHTS = "fs_rights";
    public static final String FIELD_DB_RIGHTS = "db_rights";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_REGISTERED_AT = "registered_at";
    public static final String FIELD_LAST_LOGIN = "last_login";

    public enum FileSystemRights {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        LIST_CONTENTS
    }

    public enum DBRights {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        READ_SCHEMA,
        EXEC_SQL
    }

    public @NonNull String identity;
    public @NonNull String passwordHash;
    public @Nullable String rootDir;
    public @NonNull EnumSet<FileSystemRights> fsRights;
    public @NonNull EnumSet<DBRights> dbRights;
    public @Nullable String role = null;
    public long registeredAt = System.currentTimeMillis();
    public @Nullable Long lastLogin = null; // can be null if user never logged in

    public User(@NonNull String identity, @NonNull String passwordHash, @Nullable String rootDir,
                Set<FileSystemRights> fsRights, Set<DBRights> dbRights, @Nullable String role,
                long registeredAt, @Nullable Long lastLogin) {
        this.identity = identity;
        this.passwordHash = passwordHash;
        this.rootDir = rootDir;
        this.fsRights = EnumSet.copyOf(fsRights);
        this.dbRights = EnumSet.copyOf(dbRights);
        this.role = role;
        this.registeredAt = registeredAt;
        this.lastLogin = lastLogin;
    }

    public User(@NonNull String identity, @NonNull String passwordHash) {
        this(identity, passwordHash, null,
                EnumSet.of(FileSystemRights.READ, FileSystemRights.LIST_CONTENTS),
                EnumSet.of(DBRights.READ), null,
                System.currentTimeMillis(), null);
    }

    public boolean isGuest() {
        return GUEST_IDENTITY.equals(identity);
    }

    public @NonNull JSONObject serialize() {
        JSONObject object = new JSONObject();
        object.put(FIELD_IDENTITY, identity);
        object.put(FIELD_PASSWORD, passwordHash);
        if (rootDir != null) {
            object.put(FIELD_ROOT_DIR, rootDir);
        }
        
        // Serialize fsRights as a bitmask for efficiency
        int rightsMask = 0;
        for (FileSystemRights right : fsRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_FS_RIGHTS, rightsMask);

        rightsMask = 0;
        for (DBRights right : dbRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_DB_RIGHTS, rightsMask);

        object.put(FIELD_ROLE, role != null ? role : JSONObject.NULL);

        object.put(FIELD_REGISTERED_AT, registeredAt);
        if (lastLogin != null) {
            object.put(FIELD_LAST_LOGIN, lastLogin);
        }
        
        return object;
    }

    public static @NonNull User deserialize(@NonNull JSONObject object) throws JSONException {
        String identity = object.getString(FIELD_IDENTITY);
        String password = object.getString(FIELD_PASSWORD);
        String rootDir = object.isNull(FIELD_ROOT_DIR) ? null : object.getString(FIELD_ROOT_DIR);
        
        // Deserialize fsRights from bitmask
        Set<FileSystemRights> fsRights = EnumSet.noneOf(FileSystemRights.class);
        int rightsMask = object.optInt(FIELD_FS_RIGHTS, 0);
        for (FileSystemRights right : FileSystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                fsRights.add(right);
            }
        }

        Set<DBRights> dbRights = EnumSet.noneOf(DBRights.class);
        rightsMask = object.optInt(FIELD_DB_RIGHTS, 0);
        for (DBRights right : DBRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                dbRights.add(right);
            }
        }

        String role = object.optString(FIELD_ROLE, null);

        long registeredAt = object.optLong(FIELD_REGISTERED_AT, System.currentTimeMillis());
        Long lastLogin = (object.has(FIELD_LAST_LOGIN) && !object.isNull(FIELD_LAST_LOGIN)) ?
                object.getLong(FIELD_LAST_LOGIN) : null;
        
        return new User(identity, password, rootDir, fsRights, dbRights, role, registeredAt, lastLogin);
    }

    @Override
    public User clone() {
        try {
            User clone = (User) super.clone();
            clone.fsRights = fsRights.clone();
            clone.dbRights = dbRights.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
} 