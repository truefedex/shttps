package com.phlox.simpleserver.auth;

import org.json.JSONException;
import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;

public class User implements Serializable {
    public static final String GUEST_IDENTITY = "guest";

    public enum FileSystemRights {
        CREATE,
        READ,
        UPDATE,
        DELETE,
        LIST_CONTENTS
    }

    public @NonNull String identity;
    public @NonNull String passwordHash;
    public @Nullable String rootDir;
    public @NonNull EnumSet<FileSystemRights> fsRights;

    public User(String identity, String passwordHash, String rootDir,
                Set<FileSystemRights> fsRights) {
        this.identity = identity;
        this.passwordHash = passwordHash;
        this.rootDir = rootDir;
        this.fsRights = EnumSet.copyOf(fsRights);
    }

    public boolean isGuest() {
        return GUEST_IDENTITY.equals(identity);
    }

    public boolean hasAnyFileEditingRights() {
        return fsRights.contains(FileSystemRights.CREATE) ||
                fsRights.contains(FileSystemRights.UPDATE) ||
                fsRights.contains(FileSystemRights.DELETE);
    }

    public JSONObject serialize() {
        JSONObject object = new JSONObject();
        object.put("identity", identity);
        object.put("password", passwordHash);
        if (rootDir != null) {
            object.put("root_dir", rootDir);
        }
        
        // Serialize fsRights as a bitmask for efficiency
        int rightsMask = 0;
        for (FileSystemRights right : fsRights) {
            rightsMask |= (1 << right.ordinal());
        }
        object.put("fs_rights", rightsMask);
        
        return object;
    }

    public static User deserialize(@NonNull JSONObject object) throws JSONException {
        String identity = object.getString("identity");
        String password = object.getString("password");
        String rootDir = object.has("root_dir") ? object.getString("root_dir") : null;
        
        // Deserialize fsRights from bitmask
        Set<FileSystemRights> fsRights = EnumSet.noneOf(FileSystemRights.class);
        int rightsMask = object.optInt("fs_rights", 0);
        for (FileSystemRights right : FileSystemRights.values()) {
            if ((rightsMask & (1 << right.ordinal())) != 0) {
                fsRights.add(right);
            }
        }
        
        return new User(identity, password, rootDir, fsRights);
    }
} 