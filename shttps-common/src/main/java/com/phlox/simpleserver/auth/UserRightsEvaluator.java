package com.phlox.simpleserver.auth;

import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;

public class UserRightsEvaluator {
    private final @NonNull Holder<Database> dbHolder;

    public UserRightsEvaluator(@NonNull Holder<Database> dbHolder) {
        this.dbHolder = dbHolder;
    }

    public @Nullable UserRole loadRole(String role) {
        if (role == null) return null;
        Database db = dbHolder.get();
        if (db == null) return null;
        try (TableData data = db.getTableDataSecure(UserRole.ROLES_TABLE_NAME, null, null, null,
                new String[]{"name="},
                new Object[]{role}, null, false, false, null)) {
            if (data.next()) {
                return UserRole.deserialize(data.currentRowToJsonObject());
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasAnyFileEditingRights(@NonNull User user) {
        EnumSet<User.FileSystemRights> fsRights = userFSRights(user);
        return fsRights.contains(User.FileSystemRights.CREATE) ||
                fsRights.contains(User.FileSystemRights.UPDATE) ||
                fsRights.contains(User.FileSystemRights.DELETE);
    }

    public EnumSet<User.FileSystemRights> userFSRights(@NonNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.fsRights : user.fsRights;
    }

    public EnumSet<User.DBRights> userDBRights(@NonNull User user) {
        UserRole role = loadRole(user.role);
        return role != null ? role.dbRights : user.dbRights;
    }
}
