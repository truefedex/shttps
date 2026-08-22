package com.phlox.simpleserver.auth;

import org.json.JSONException;
import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    public static final String FIELD_CHANNEL_RIGHTS = "channel_rights";

    public enum FileSystemRights {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        LIST_CONTENTS
    }

    /**
     * Serialized as a bitmask of ordinals, so new rights may only be appended - inserting one in
     * the middle silently re-maps the rights of every stored user and role.
     */
    public enum DBRights {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        READ_SCHEMA,
        EXEC_SQL,
        /**
         * Open a transaction over {@code /api/db/transaction}. Separate from the rights above
         * because such a session holds the database's single write thread for as long as it lives,
         * so everything else waits behind it - it is worth granting deliberately rather than as a
         * side effect of being able to read or write.
         */
        USE_TRANSACTION
    }

    /**
     * Serialized as a bitmask of ordinals, so new rights may only be appended - inserting one in
     * the middle silently re-maps the rights of every stored user and role.
     */
    public enum SystemRights {
        READ_STATUS, EXECUTE_HANDLER,
        /** Watch the device screen. Only platforms that implement screen capture act on it. */
        VIEW_SCREEN,
        /** Send touches, keys and text to the device. Useless without {@link #VIEW_SCREEN}. */
        CONTROL_SCREEN
    }

    /**
     * Rights over WebSocket channels ({@code /api/channels/**}).
     * <p>
     * Serialized as a bitmask of ordinals, so new rights may only be appended - inserting one in
     * the middle silently re-maps the rights of every stored user and role.
     */
    public enum ChannelRights {
        /** Join an existing channel. On its own this is listen-only in every channel mode. */
        CONNECT,
        /** Send into a channel: ECHO messages, and later STATE mutations. */
        POST,
        /** Create a channel over {@code POST /api/channels}. */
        CREATE,
        /** Delete a deletable channel over {@code DELETE /api/channels/{id}}. */
        DELETE,
        /** See the whole channel list over {@code GET /api/channels}. */
        LIST_CHANNELS,
        /** See who is connected to a channel. */
        LIST_PARTICIPANTS
    }

    /**
     * What a freshly created principal starts with - <b>not</b> a fallback for stored state.
     * A user read back from configuration or the database carries exactly the rights that were
     * stored for it (an absent/zero mask means no channel rights at all), the same way
     * {@code system_rights} behaves.
     */
    public static @NotNull EnumSet<ChannelRights> defaultChannelRights(@NotNull String identity) {
        //a guest may listen, but not speak: anonymous visitors are the one group that can not be
        //held responsible for what they post
        return GUEST_IDENTITY.equals(identity) ?
                EnumSet.of(ChannelRights.CONNECT) :
                EnumSet.of(ChannelRights.CONNECT, ChannelRights.POST);
    }

    public @NotNull String identity;
    public @NotNull String passwordHash;
    public @Nullable String rootDir;
    public @NotNull EnumSet<FileSystemRights> fsRights;
    public @NotNull EnumSet<DBRights> dbRights;
    public @Nullable String role;
    public long registeredAt;
    public @Nullable Long lastLogin = null; // can be null if user never logged in

    // file storage size limit
    public @Nullable Long storageLimit;
    public @NotNull EnumSet<SystemRights> systemRights;
    public long usedStorage;
    public @NotNull EnumSet<ChannelRights> channelRights;

    /**
     * The channel-less constructor, kept so callers that predate channels keep compiling; the new
     * user gets {@link #defaultChannelRights(String)}.
     */
    public User(@NotNull String identity, @NotNull String passwordHash, @Nullable String rootDir,
                EnumSet<FileSystemRights> fsRights, EnumSet<DBRights> dbRights, @Nullable String role,
                long registeredAt, @Nullable Long lastLogin, @Nullable Long storageLimit,
                @NotNull EnumSet<SystemRights> systemRights, long usedStorage) {
        this(identity, passwordHash, rootDir, fsRights, dbRights, role, registeredAt, lastLogin,
                storageLimit, systemRights, usedStorage, defaultChannelRights(identity));
    }

    public User(@NotNull String identity, @NotNull String passwordHash, @Nullable String rootDir,
                EnumSet<FileSystemRights> fsRights, EnumSet<DBRights> dbRights, @Nullable String role,
                long registeredAt, @Nullable Long lastLogin, @Nullable Long storageLimit,
                @NotNull EnumSet<SystemRights> systemRights, long usedStorage,
                @NotNull EnumSet<ChannelRights> channelRights) {
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
        this.channelRights = EnumSet.copyOf(channelRights);
    }

    public User(@NotNull String identity, @NotNull String passwordHash) {
        this(identity, passwordHash, null,
                EnumSet.of(FileSystemRights.READ, FileSystemRights.LIST_CONTENTS),
                EnumSet.of(DBRights.READ), null,
                System.currentTimeMillis(), null, null,
                EnumSet.of(SystemRights.READ_STATUS), 0L,
                defaultChannelRights(identity));
    }

    public boolean isGuest() {
        return GUEST_IDENTITY.equals(identity);
    }

    public @NotNull JSONObject serialize() {
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

        rightsMask = 0;
        for (ChannelRights right : channelRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put(FIELD_CHANNEL_RIGHTS, rightsMask);

        return object;
    }

    public static @NotNull User deserialize(@NotNull JSONObject object) throws JSONException {
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

        //no default here on purpose: a stored user holds exactly the channel rights it was stored
        //with, and everything written before channels existed holds none
        EnumSet<ChannelRights> channelRights = EnumSet.noneOf(ChannelRights.class);
        rightsMask = object.optInt(FIELD_CHANNEL_RIGHTS, 0);
        for (ChannelRights right : ChannelRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                channelRights.add(right);
            }
        }

        return new User(identity, password, rootDir, fsRights, dbRights, role, registeredAt,
                lastLogin, storageLimit, systemRights, usedStorage, channelRights);
    }

    @Override
    public User clone() {
        try {
            User clone = (User) super.clone();
            clone.fsRights = fsRights.clone();
            clone.dbRights = dbRights.clone();
            clone.systemRights = systemRights.clone();
            clone.channelRights = channelRights.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
} 