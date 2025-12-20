package com.phlox.simpleserver.auth;

import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;

public class UserRole implements Serializable {
    public static final String ROLES_TABLE_NAME = "user_role";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_FS_RIGHTS = "fs_rights";
    public static final String FIELD_DB_RIGHTS = "db_rights";
    public static final String FIELD_FILE_STORAGE_SIZE_LIMIT = "storage_limit";
    public static final String FIELD_SYSTEM_RIGHTS = "system_rights";

    public @NonNull String name;
    public @NonNull EnumSet<User.FileSystemRights> fsRights;
    public @NonNull EnumSet<User.DBRights> dbRights;
    public @Nullable Long storageLimit = null;
    public @NonNull EnumSet<User.SystemRights> systemRights;

    public UserRole(@NonNull String name,
                    @NonNull EnumSet<User.FileSystemRights> fsRights,
                    @NonNull EnumSet<User.DBRights> dbRights,
                    @Nullable Long storageLimit,
                    @NonNull EnumSet<User.SystemRights> systemRights) {
        this.name = name;
        this.fsRights = fsRights;
        this.dbRights = dbRights;
        this.storageLimit = storageLimit;
        this.systemRights = systemRights;
    }

    public @NonNull JSONObject serialize() {
        JSONObject object = new JSONObject();
        object.put(FIELD_NAME, name);

        // Serialize fsRights as a bitmask for efficiency
        int rightsMask = 0;
        for (User.FileSystemRights right : fsRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_FS_RIGHTS, rightsMask);

        rightsMask = 0;
        for (User.DBRights right : dbRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_DB_RIGHTS, rightsMask);

        if (storageLimit != null) {
            object.put(FIELD_FILE_STORAGE_SIZE_LIMIT, storageLimit);
        }

        rightsMask = 0;
        for (User.SystemRights right : systemRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_SYSTEM_RIGHTS, rightsMask);

        return object;
    }

    public static @NonNull UserRole deserialize(JSONObject object)  throws JSONException {
        String name = object.getString(FIELD_NAME);

        // Deserialize fsRights from bitmask
        EnumSet<User.FileSystemRights> fsRights = EnumSet.noneOf(User.FileSystemRights.class);
        int rightsMask = object.optInt(FIELD_FS_RIGHTS, 0);
        for (User.FileSystemRights right : User.FileSystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                fsRights.add(right);
            }
        }

        EnumSet<User.DBRights> dbRights = EnumSet.noneOf(User.DBRights.class);
        rightsMask = object.optInt(FIELD_DB_RIGHTS, 0);
        for (User.DBRights right : User.DBRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                dbRights.add(right);
            }
        }

        Long storageLimit = (object.has(FIELD_FILE_STORAGE_SIZE_LIMIT) && !object.isNull(FIELD_FILE_STORAGE_SIZE_LIMIT)) ?
                object.getLong(FIELD_FILE_STORAGE_SIZE_LIMIT) : null;

        EnumSet<User.SystemRights> systemRights = EnumSet.noneOf(User.SystemRights.class);
        rightsMask = object.optInt(FIELD_SYSTEM_RIGHTS, 0);
        for (User.SystemRights right : User.SystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                systemRights.add(right);
            }
        }

        return new UserRole(name, fsRights, dbRights, storageLimit, systemRights);
    }
}
