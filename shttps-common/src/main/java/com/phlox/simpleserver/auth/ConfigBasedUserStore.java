package com.phlox.simpleserver.auth;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public synchronized boolean create(@NonNull User user) {
        if (isIdentityUsed(user.identity)) {
            return false;
        }
        users.put(user.identity, user);
        config.setUsers(users.values());
        return true;
    }

    @Override
    public synchronized @Nullable User createEmptyUserWithAvailableIdentity() {
        String baseIdentity = "user";
        String identity = baseIdentity;
        int i = 1;
        while (isIdentityUsed(identity)) {
            identity = baseIdentity + i++;
        }
        User user = new User(identity, "", null,
                EnumSet.of(User.FileSystemRights.READ, User.FileSystemRights.LIST_CONTENTS),
                EnumSet.of(User.DBRights.READ), null, System.currentTimeMillis(), null);
        return create(user) ? user : null;
    }

    @Override
    public synchronized boolean update(@NonNull User user) {
        if (!users.containsKey(user.identity)) return false;
        users.put(user.identity, user);
        config.setUsers(users.values());
        return false;
    }

    @Override
    public boolean update(@NonNull String userIdentity, @NonNull String field, @Nullable Object value) {
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
        updateInMemoryUsersMap(null);
    }

    public synchronized void updateInMemoryUsersMap(@Nullable List<User> withList) {
        Map<String, User> users = new HashMap<>();
        List<User> list = withList == null ? config.getUsers() : withList;
        for (User u : list) {
            users.put(u.identity, u);
        }
        this.users = users;
    }

    @Override
    public void deleteAll() {
        users.clear();
        config.setUsers(users.values());
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
        
        User newUser = new User(identity, password);
        return create(newUser) ? newUser : null;
    }
}
