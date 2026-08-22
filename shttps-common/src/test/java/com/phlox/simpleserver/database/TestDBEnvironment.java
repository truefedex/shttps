package com.phlox.simpleserver.database;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserRightsEvaluator;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.server.utils.docfile.DocumentFile;

import org.json.JSONArray;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.KeyStore;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * The bits of the server a database operation needs, built in memory: a configuration backed by a
 * map and an auth manager with a real {@link UserRightsEvaluator}.
 * <p>
 * The rights evaluator is the real one on purpose - it is the thing these tests are checking the
 * operations use correctly. Users here carry no role, so evaluation stays on the general-rights
 * path and never has to load one from a database.
 */
public class TestDBEnvironment {
    public final Config config = new Config();
    public final AuthManager authManager = new TestAuthManager();

    public TestDBEnvironment() {
        //the default in these tests: authentication on, so rights are actually consulted
        config.setAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH);
        config.setAllowDatabaseTableDataEditingApi(true);
        config.setAllowDatabaseCustomSqlRemoteApi(true);
        config.setStoreUsersInDatabase(false);
    }

    /** A user holding exactly the given database rights and nothing else. */
    public static User userWith(User.DBRights... rights) {
        EnumSet<User.DBRights> dbRights = rights.length == 0
                ? EnumSet.noneOf(User.DBRights.class) : EnumSet.copyOf(java.util.Arrays.asList(rights));
        return new User("tester", "", null,
                EnumSet.noneOf(User.FileSystemRights.class), dbRights, null,
                0L, null, null, EnumSet.noneOf(User.SystemRights.class), 0L);
    }

    /** An in-memory {@link SHTTPSConfig}; every typed setting comes from the interface defaults. */
    public static class Config implements SHTTPSConfig {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public int getInt(String key, int defaultValue) {
            Object v = values.get(key);
            return v == null ? defaultValue : (Integer) v;
        }

        @Override
        public void setInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object v = values.get(key);
            return v == null ? defaultValue : (Boolean) v;
        }

        @Override
        public void setBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object v = values.get(key);
            return v == null ? defaultValue : (String) v;
        }

        @Override
        public void setString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public JSONArray getJsonArray(String key, JSONArray defaultValue) {
            Object v = values.get(key);
            return v == null ? defaultValue : (JSONArray) v;
        }

        @Override
        public void setJSONArray(String key, JSONArray value) {
            values.put(key, value);
        }

        @Override
        public DocumentFile getRootDir() {
            return null;
        }

        @Override
        public void setRootDir(String value) {
        }

        @Override
        public KeyStore getTLSCert() {
            return null;
        }

        @Override
        public void setTLSCert(byte[] value) {
        }
    }

    private static class TestAuthManager implements AuthManager {
        private final UserRightsEvaluator evaluator = new UserRightsEvaluator(new Holder<>(null));

        @Override
        public @Nullable User getAuthenticatedUser(@NotNull RequestContext context) {
            return null;
        }

        @Override
        public @Nullable User authenticate(RequestContext context, Request request) {
            return null;
        }

        @Override
        public void logout(@NotNull RequestContext context, @NotNull Request request) {
        }

        @Override
        public @NotNull UserRightsEvaluator getUserRightsEvaluator() {
            return evaluator;
        }
    }
}
