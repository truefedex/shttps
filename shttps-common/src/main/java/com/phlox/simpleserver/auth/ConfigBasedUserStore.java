package com.phlox.simpleserver.auth;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class ConfigBasedUserStore implements UserStore {
    private final SHTTPSConfig config;
    private Map<String, User> users;

    private final UserRightsEvaluator userRightsEvaluator = new UserRightsEvaluator(new Holder<>(null));

    public ConfigBasedUserStore(SHTTPSConfig config) {
        this.config = config;
        updateInMemoryUsersMap();
    }

    @Override
    public synchronized @Nullable User authenticate(@NonNull String username, @NonNull String password) {
        User user = users.get(username);
        if (user == null || !user.passwordHash.equals(password)) return null;
        return user;
    }

    @Override
    public synchronized @Nullable User find(@NonNull String identity) {
        return users.get(identity);
    }

    @Override
    public synchronized @Nullable User get(long i) {
        User[] usersList = users.values().toArray(new User[0]);
        return usersList.length > i ? usersList[(int) i] : null;
    }

    @Override
    public synchronized long count() {
        return users.size();
    }

    @Override
    public synchronized boolean isIdentityUsed(@NonNull String identity) {
        return users.containsKey(identity);
    }

    @Override
    public synchronized void create(@NonNull User user) {
        if (isIdentityUsed(user.identity)) {
            throw new IllegalStateException("Identity already used");
        }
        if (user.rootDir != null &&
                DocumentFileUtils.checkOrCreateUserDir(config.getRootDir(), user.rootDir) == null) {
            throw new IllegalStateException("Can not create user directory");
        }

        //we don't need to check role storage limit here because config-based users doesn't have roles
        if (user.storageLimit != null) {
            DocumentFile userRootDir;
            if (user.rootDir != null) {
                userRootDir = DocumentFileUtils.checkOrCreateUserDir(config.getRootDir(), user.rootDir);
            } else {
                userRootDir = config.getRootDir();
            }
            assert userRootDir != null;
            user.usedStorage = userRootDir.calculateDirectorySize();
        }
        users.put(user.identity, user);
        config.setUsers(users.values());
    }

    @Override
    public synchronized @Nullable User createEmptyUserWithAvailableIdentity() {
        String baseIdentity = "user";
        String identity = baseIdentity;
        int i = 1;
        while (isIdentityUsed(identity)) {
            identity = baseIdentity + i++;
        }

        String rootDir = formatNewUserDir(config.getNewUserDirPattern(), identity);

        User user = new User(identity, "", rootDir,
                EnumSet.of(User.FileSystemRights.READ, User.FileSystemRights.LIST_CONTENTS),
                EnumSet.of(User.DBRights.READ), null, System.currentTimeMillis(), null, null,
                EnumSet.of(User.SystemRights.READ_STATUS), 0);
        create(user);
        return user;
    }

    @Override
    public synchronized boolean isUserDirUsed(String userDir) {
        for (User user: users.values()) {
            if (user.rootDir != null && user.rootDir.equals(userDir))
                return true;
        }
        return false;
    }

    @Override
    public synchronized boolean update(@NonNull User user) {
        if (!users.containsKey(user.identity)) return false;
        users.put(user.identity, user);
        config.setUsers(users.values());
        return true;
    }

    @Override
    public synchronized boolean update(@NonNull String userIdentity, @NonNull String field, @Nullable Object value) {
        User user = users.get(userIdentity);
        if (user == null) return false;
        JSONObject json = user.serialize();
        json.put(field, value);
        User updatedUser = User.deserialize(json);
        users.put(userIdentity, updatedUser);
        config.setUsers(users.values());
        return true;
    }

    @Override
    public synchronized boolean delete(@NonNull String identity) {
        User removed = users.remove(identity);
        if (removed == null) return false;
        config.setUsers(users.values());
        return false;
    }

    @Override
    public synchronized boolean rename(@NonNull User user, @NonNull String newIdentity) {
        if (isIdentityUsed(newIdentity)) return false;
        users.remove(user.identity);
        user.identity = newIdentity;
        users.put(newIdentity, user);
        config.setUsers(users.values());
        return true;
    }

    public synchronized void updateInMemoryUsersMap() {
        Map<String, User> users = new ConcurrentHashMap<>();
        List<User> list = config.getUsers();
        for (User u : list) {
            users.put(u.identity, u);
        }
        this.users = users;
    }

    @Override
    public synchronized void deleteAll() {
        users.clear();
        config.setUsers(users.values());
    }

    @Override
    public UserRightsEvaluator provideUserRightsEvaluator() {
        return userRightsEvaluator;
    }

    @Override
    public synchronized @Nullable User registerNewUser(@NonNull String identity, @NonNull String password) {
        if (isIdentityUsed(identity)) {
            return null;
        }
        
        User newUser = new User(identity, password);
        newUser.rootDir = formatNewUserDir(config.getNewUserDirPattern(), identity);
        create(newUser);
        return newUser;
    }

    @Override
    public synchronized void updateUserAtomically(String identity, Updater<User> predicate) throws Exception {
        User user = users.get(identity);
        if (user == null) throw new IllegalStateException("User not found");
        User updated = predicate.process(user);
        if (updated != null) {
            update(updated);
        }
    }
}
