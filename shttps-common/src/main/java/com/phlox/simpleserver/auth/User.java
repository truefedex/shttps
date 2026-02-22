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
    public static final String FIELD_FILE_STORAGE_SIZE_LIMIT = "storage_limit";
    public static final String FIELD_SYSTEM_RIGHTS = "system_rights";
    public static final String FIELD_USED_STORAGE = "used_storage";

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

    public enum SystemRights {
        READ_STATUS, EXECUTE_HANDLER
    }

    public @NonNull String identity;
    public @NonNull String passwordHash;
    public @Nullable String rootDir;
    public @NonNull EnumSet<FileSystemRights> fsRights;
    public @NonNull EnumSet<DBRights> dbRights;
    public @Nullable String role;
    public long registeredAt;
    public @Nullable Long lastLogin = null; // can be null if user never logged in

    // file storage size limit
    public @Nullable Long storageLimit;
    public @NonNull EnumSet<SystemRights> systemRights;
    public long usedStorage;

    public User(@NonNull String identity, @NonNull String passwordHash, @Nullable String rootDir,
                EnumSet<FileSystemRights> fsRights, EnumSet<DBRights> dbRights, @Nullable String role,
                long registeredAt, @Nullable Long lastLogin, @Nullable Long storageLimit,
                @NonNull EnumSet<SystemRights> systemRights, long usedStorage) {
        this.identity = identity;
        this.passwordHash = passwordHash;
        this.rootDir = rootDir;
        this.fsRights = EnumSet.copyOf(fsRights);
        this.dbRights = EnumSet.copyOf(dbRights);
        this.role = role;
        this.registeredAt = registeredAt;
        this.lastLogin = lastLogin;
        this.storageLimit = storageLimit;
        this.systemRights = EnumSet.copyOf(systemRights);
        this.usedStorage = usedStorage;
    }

    public User(@NonNull String identity, @NonNull String passwordHash) {
        this(identity, passwordHash, null,
                EnumSet.of(FileSystemRights.READ, FileSystemRights.LIST_CONTENTS),
                EnumSet.of(DBRights.READ), null,
                System.currentTimeMillis(), null, null,
                EnumSet.of(SystemRights.READ_STATUS), 0L);
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

        if (storageLimit != null) {
            object.put(FIELD_FILE_STORAGE_SIZE_LIMIT, storageLimit);
        }

        rightsMask = 0;
        for (SystemRights right : systemRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_SYSTEM_RIGHTS, rightsMask);

        object.put(FIELD_USED_STORAGE, usedStorage);
        
        return object;
    }

    public static @NonNull User deserialize(@NonNull JSONObject object) throws JSONException {
        String identity = object.getString(FIELD_IDENTITY);
        String password = object.getString(FIELD_PASSWORD);
        String rootDir = object.isNull(FIELD_ROOT_DIR) ? null : object.getString(FIELD_ROOT_DIR);
        
        // Deserialize fsRights from bitmask
        EnumSet<FileSystemRights> fsRights = EnumSet.noneOf(FileSystemRights.class);
        int rightsMask = object.optInt(FIELD_FS_RIGHTS, 0);
        for (FileSystemRights right : FileSystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                fsRights.add(right);
            }
        }

        EnumSet<DBRights> dbRights = EnumSet.noneOf(DBRights.class);
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

        Long storageLimit = (object.has(FIELD_FILE_STORAGE_SIZE_LIMIT) && !object.isNull(FIELD_FILE_STORAGE_SIZE_LIMIT)) ?
                object.getLong(FIELD_FILE_STORAGE_SIZE_LIMIT) : null;

        EnumSet<SystemRights> systemRights = EnumSet.noneOf(SystemRights.class);
        rightsMask = object.optInt(FIELD_SYSTEM_RIGHTS, 0);
        for (SystemRights right : SystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                systemRights.add(right);
            }
        }

        long usedStorage = object.optLong(FIELD_USED_STORAGE, 0L);
        
        return new User(identity, password, rootDir, fsRights, dbRights, role, registeredAt,
                lastLogin, storageLimit, systemRights, usedStorage);
    }

    @Override
    public User clone() {
        try {
            User clone = (User) super.clone();
            clone.fsRights = fsRights.clone();
            clone.dbRights = dbRights.clone();
            clone.systemRights = systemRights.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
} 