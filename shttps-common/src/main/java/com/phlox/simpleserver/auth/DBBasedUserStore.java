package com.phlox.simpleserver.auth;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.HashSet;

public class DBBasedUserStore implements UserStore {
    public static final String USERS_TABLE_NAME = "user";

    private final Holder<Database> database;
    private final SHTTPSConfig config;

    private final UserRightsEvaluator userRightsEvaluator;

    public DBBasedUserStore(Holder<Database> database, SHTTPSConfig config) {
        this.database = database;
        this.config = config;
        this.userRightsEvaluator = new UserRightsEvaluator(database);
    }

    @Override
    public @Nullable User authenticate(@NonNull String username, @NonNull String password) {
        Database db = database.get();
        if (db == null) return null;
        try (TableData data = db.getTableDataSecure(USERS_TABLE_NAME, null, null, null, 
                new String[]{"identity=", "password="},
                new Object[]{username, password}, null, false, false, null)) {
            if (data.next()) {
                return User.deserialize(data.currentRowToJsonObject());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public @Nullable User find(@NonNull String identity) {
        Database db = database.get();
        if (db == null) return null;
        try (TableData data = db.getTableDataSecure(USERS_TABLE_NAME, null, null, null, 
                new String[]{"identity="},
                new Object[]{identity}, null, false, false, null)) {
            if (data.next()) {
                return User.deserialize(data.currentRowToJsonObject());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public @Nullable User get(long i) {
        Database db = database.get();
        if (db == null) return null;
        try (TableData data = db.getTableDataSecure(USERS_TABLE_NAME, null, i, 1L, 
                null, null, null, false, false, null)) {
            if (data.next()) {
                return User.deserialize(data.currentRowToJsonObject());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public long count() {
        Database db = database.get();
        if (db == null) return 0;
        try (TableData data = db.query("select count(*) from " + USERS_TABLE_NAME)) {
            if (!data.next()) throw new IllegalStateException("Can not retrieve users count");
            return data.getLong(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isIdentityUsed(@NonNull String identity) {
        Database db = database.get();
        if (db == null) return false;
        try (TableData data = db.getTableDataSecure(USERS_TABLE_NAME, new String[]{"identity"}, null, null, 
                new String[]{"identity="},
                new Object[]{identity}, null, false, false, null)) {
            return data.next();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean create(@NonNull User user) {
        if (isIdentityUsed(user.identity)) {
            return false;
        }
        Database db = database.get();
        if (db == null) return false;
        try {
            db.insert(USERS_TABLE_NAME, user.serialize());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public @Nullable User createEmptyUserWithAvailableIdentity() {
        String baseIdentity = "user";
        String identity = baseIdentity;
        int i = 1;
        while (isIdentityUsed(identity)) {
            identity = baseIdentity + i++;
        }
        User user = new User(identity, "", null,
                EnumSet.of(User.FileSystemRights.READ, User.FileSystemRights.LIST_CONTENTS),
                EnumSet.of(User.DBRights.READ), config.getDefaultRoleForNewUser(), System.currentTimeMillis(), null);
        return create(user) ? user : null;
    }

    @Override
    public boolean update(@NonNull User user) {
        Database db = database.get();
        if (db == null) return false;
        try {
            int updated = db.update(USERS_TABLE_NAME, user.serialize(), 
                    new String[]{"identity="},
                    new Object[]{user.identity});
            return updated > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean update(@NonNull String userIdentity, @NonNull String field, @Nullable Object value) {
        Database db = database.get();
        if (db == null) return false;
        try {
            JSONObject updateData = new JSONObject();
            updateData.put(field, value);
            int updated = db.update(USERS_TABLE_NAME, updateData,
                    new String[]{"identity="},
                    new Object[]{userIdentity});
            return updated > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean delete(@NonNull String identity) {
        Database db = database.get();
        if (db == null) return false;
        try {
            int deleted = db.delete(USERS_TABLE_NAME, 
                    new String[]{"identity="},
                    new Object[]{identity});
            return deleted > 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean rename(@NonNull User user, @NonNull String newIdentity) {
        if (isIdentityUsed(newIdentity)) return false;
        
        Database db = database.get();
        if (db == null) return false;
        try {
            // Update the user's identity
            JSONObject updateData = new JSONObject();
            updateData.put("identity", newIdentity);
            
            int updated = db.update(USERS_TABLE_NAME, updateData, 
                    new String[]{"identity="},
                    new Object[]{user.identity});
            
            if (updated > 0) {
                user.identity = newIdentity;
                return true;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    @Override
    public void deleteAll() {
        Database db = database.get();
        if (db == null) return;
        try {
            db.delete(USERS_TABLE_NAME, null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public UserRightsEvaluator provideUserRightsEvaluator() {
        return userRightsEvaluator;
    }

    @Override
    public @Nullable User registerNewUser(@NonNull String identity, @NonNull String password) {
        if (isIdentityUsed(identity)) {
            return null;
        }

        String defaultRole = config.getDefaultRoleForNewUser();
        if (defaultRole == null) {
            throw new IllegalStateException("Default role for new user is not configured");
        }
        UserRole role = userRightsEvaluator.loadRole(defaultRole);
        if (role == null) {
            throw new IllegalStateException("Default role for new user is not found: " + defaultRole);
        }
        
        User newUser = new User(identity, password, null,
                EnumSet.noneOf(User.FileSystemRights.class),
                EnumSet.noneOf(User.DBRights.class),
                defaultRole,
                System.currentTimeMillis(), null);
        return create(newUser) ? newUser : null;
    }
}
