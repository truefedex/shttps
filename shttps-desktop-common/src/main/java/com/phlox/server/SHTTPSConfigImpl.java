package com.phlox.server;

import static com.phlox.server.utils.docfile.RawDocumentFile.fileUriToFilePath;

import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.handlers.HandlersUtils;
import com.phlox.simpleserver.auth.User;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SHTTPSConfigImpl implements SHTTPSConfig {
    private static final String ALIAS_CONFIG_PREFIX = "config_";
    private final JSONObject json;
    private final File file;

    private boolean batchModifications = false;

    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public SHTTPSConfigImpl(File file) throws IOException {
        this.file = file;
        if (!file.exists()) {
            json = new JSONObject();
            saveDefaultValues();
        } else {
            try (InputStream is = new FileInputStream(file)) {
                byte[] data = new byte[is.available()];
                if (is.read(data) != data.length) {
                    throw new IOException("Failed to read config from file: " + file.getAbsolutePath());
                }
                json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.e("Failed to load config from file: " + file.getAbsolutePath(), e);
                throw new IOException("Failed to load config from file: " + file.getAbsolutePath());
            }
        }
    }

    @Override
    public void runMigrations() {
        SHTTPSConfig.super.runMigrations();
    }

    @Override
    public DocumentFile getRootDir() {
        String rootDirStr = json.optString(KEY_ROOT_DIR, null);
        return rootDirStr == null || rootDirStr.isEmpty() ? null : RawDocumentFile.fromFile(Paths.get(rootDirStr).toFile());
    }

    @Override
    public void setRootDir(String value) {
        if (value != null && value.startsWith(RawDocumentFile.FILE_URI_PREFIX)) {
            value = fileUriToFilePath(value);
        }
        json.put(KEY_ROOT_DIR, value);
        save();
    }

    @Override
    public boolean getRenderFolders() {
        return json.optBoolean(KEY_RENDER_FOLDERS, true);
    }

    @Override
    public void setRenderFolders(boolean value) {
        json.put(KEY_RENDER_FOLDERS, value);
        save();
    }

    @Override
    public boolean getAllowEditing() {
        return json.optBoolean(KEY_ALLOW_EDITING, false);
    }

    @Override
    public void setAllowEditing(boolean value) {
        json.put(KEY_ALLOW_EDITING, value);
        save();
    }

    @Override
    public int getPort() {
        return json.optInt(KEY_PORT, 8080);
    }

    @Override
    public void setPort(int value) {
        json.put(KEY_PORT, value);
        save();
    }

    @Override
    public String getUsername() {
        return json.optString(KEY_USERNAME, "");
    }

    @Override
    public String getPassword() {
        return json.optString(KEY_PASSWORD, null);
    }

    @Override
    public boolean getRedirectToIndex() {
        return json.optBoolean(KEY_REDIRECT_TO_INDEX, true);
    }

    @Override
    public void setRedirectToIndex(boolean value) {
        json.put(KEY_REDIRECT_TO_INDEX, value);
        save();
    }

    @Override
    public boolean getUseTLS() {
        return json.optBoolean(KEY_USE_TLS, false);
    }

    @Override
    public void setUseTLS(boolean value) {
        json.put(KEY_USE_TLS, value);
        save();
    }

    @Override
    public KeyStore getTLSCert() {
        String path = json.optString(KEY_TLS_CERT, null);
        if (path == null || path.isEmpty()) return null;
        try (FileInputStream fis = new FileInputStream(path)) {
            KeyStore ks;
            if (path.endsWith(".bks")) {
                ks = KeyStore.getInstance("BKS");
            } else {
                ks = KeyStore.getInstance("PKCS12");
            }
            String keyStorePassword = getTLSKeystorePassword();
            String keyPassword = getTLSKeyPassword();
            if (keyPassword == null) {
                keyPassword = keyStorePassword;
            }
            ks.load(fis, keyStorePassword.toCharArray());
            //ckeck if we have at least one key entry and can load key with provided password
            boolean hasKeyEntry = false;
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias)) {
                    hasKeyEntry = true;
                    ks.getKey(alias, keyPassword.toCharArray());
                    break;
                }
            }
            if (!hasKeyEntry) {
                logger.e("Keystore file doesn't contain any key entries: " + path);
                return null;
            }
            return ks;
        } catch (Exception e) {
            logger.e("Failed to read keystore file: " + path, e);
            return null;
        }
    }

    @Override
    public void setTLSCert(byte[] value) {
        //on commandline we assume value is path to file
        if (value == null) {
            json.remove(KEY_TLS_CERT);
            save();
            return;
        }
        json.put(KEY_TLS_CERT, new String(value));
        save();
    }

    @Override
    public String[] getAllowedNetworkInterfaces() {
        String value = json.optString(KEY_ALLOWED_NETWORK_INTERFACES, "");
        if (value.isEmpty()) return null;
        return value.split(",");
    }

    @Override
    public void setAllowedNetworkInterfaces(String[] value) {
        if (value == null) {
            json.remove(KEY_ALLOWED_NETWORK_INTERFACES);
            save();
            return;
        }
        json.put(KEY_ALLOWED_NETWORK_INTERFACES, String.join(",", value));
        save();
    }

    @Override
    public Set<WhiteListMode> getWhiteListMode() {
        int value = json.optInt(KEY_WHITE_LIST_MODE, 0);
        return WhiteListMode.fromInt(value);
    }

    @Override
    public void setWhiteListMode(Set<WhiteListMode> value) {
        if (value == null) {
            json.remove(KEY_WHITE_LIST_MODE);
            save();
            return;
        }
        json.put(KEY_WHITE_LIST_MODE, WhiteListMode.toInt(value));
        save();
    }

    @Override
    public HashSet<String> getWhiteList() {
        String value = json.optString(KEY_WHITE_LIST_OF_IPS, "");
        if (value.isEmpty()) return new HashSet<>();
        String[] split = value.split(",");
        HashSet<String> result = new HashSet<>();
        Collections.addAll(result, split);
        return result;
    }

    @Override
    public void setWhiteList(Set<String> value) {
        if (value == null) {
            json.remove(KEY_WHITE_LIST_OF_IPS);
            save();
            return;
        }
        json.put(KEY_WHITE_LIST_OF_IPS, String.join(",", value));
        save();
    }

    @Override
    public void setCustomHeaders(String value) {
        if (value == null) {
            json.remove(KEY_CUSTOM_HEADERS);
            save();
            return;
        }
        json.put(KEY_CUSTOM_HEADERS, value);
        save();
    }

    @Override
    public String getCustomHeaders() {
        return json.optString(KEY_CUSTOM_HEADERS, "");
    }

    @Override
    public boolean isDatabaseEnabled() {
        return json.optBoolean(KEY_DATABASE_ENABLED, false);
    }

    @Override
    public void setDatabaseEnabled(boolean value) {
        json.put(KEY_DATABASE_ENABLED, value);
        save();
    }

    @Override
    public String getDatabasePath() {
        return json.optString(KEY_DATABASE_PATH, "");
    }

    @Override
    public void setDatabasePath(String value) {
        json.put(KEY_DATABASE_PATH, value);
        save();
    }

    @Override
    public boolean isAllowDatabaseCustomSqlRemoteApi() {
        return json.optBoolean(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, false);
    }

    @Override
    public boolean isAllowDatabaseTableDataEditingApi() {
        return json.optBoolean(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, false);
    }

    @Override
    public void setAllowDatabaseTableDataEditingApi(boolean value) {
        json.put(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, value);
        save();
    }

    @Override
    public void setAllowDatabaseCustomSqlRemoteApi(boolean value) {
        json.put(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, value);
        save();
    }

    @Override
    public List<RedirectsMiddleware.RedirectRule> getRedirectRules() {
        JSONArray rules = json.optJSONArray(KEY_REDIRECT_RULES);
        if (rules == null) {
            return null;
        }
        List<RedirectsMiddleware.RedirectRule> result = new ArrayList<>(rules.length());
        for (int i = 0; i < rules.length(); i++) {
            result.add(HandlersUtils.ruleFromJson(rules.getJSONObject(i)));
        }
        return result;
    }

    @Override
    public void setRedirectRules(List<RedirectsMiddleware.RedirectRule> value) {
        if (value == null) {
            json.remove(KEY_REDIRECT_RULES);
            save();
            return;
        }
        JSONArray rules = new JSONArray();
        for (RedirectsMiddleware.RedirectRule rule : value) {
            rules.put(HandlersUtils.ruleToJson(rule));
        }
        json.put(KEY_REDIRECT_RULES, rules);
        save();
    }

    @Override
    public List<User> getUsers() {
        JSONArray jsArr = json.optJSONArray(KEY_USERS);
        if (jsArr == null) {
            return new ArrayList<>();
        }
        List<User> users = new ArrayList<>();
        for (int i = 0; i < jsArr.length(); i++) {
            users.add(User.deserialize(jsArr.getJSONObject(i)));
        }
        return users;
    }

    @Override
    public void setUsers(Collection<User> users) {
        JSONArray jsArr = new JSONArray();
        for (User user : users) {
            jsArr.put(user.serialize());
        }
        json.put(KEY_USERS, jsArr);
        save();
    }

    @Override
    public AuthMode getAuthMode() {
        return AuthMode.valueOf(json.optString(KEY_AUTH_MODE, AuthMode.NONE.name()));
    }

    @Override
    public void setAuthMode(AuthMode value) {
        json.put(KEY_AUTH_MODE, value.name());
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return json.optInt(key, defaultValue);
    }

    @Override
    public void setInt(String key, int value) {
        json.put(key, value);
        save();
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return json.optBoolean(key, defaultValue);
    }

    @Override
    public void setBoolean(String key, boolean value) {
        json.put(key, value);
        save();
    }

    @Override
    public String getString(String key, String defaultValue) {
        return json.optString(key, defaultValue);
    }

    @Override
    public void setString(String key, String value) {
        json.put(key, value);
        save();
    }

    @Override
    public void setJSONArray(String key, JSONArray value) {
        json.put(key, value);
        save();
    }

    @Override
    public JSONArray getJsonArray(String key, JSONArray defaultValue) {
        return json.optJSONArray(key, defaultValue);
    }

    private void save() {
        if (batchModifications) {
            return;
        }
        try {
            Files.writeString(file.toPath(), json.toString(4));
        } catch (IOException e) {
            logger.w("Failed to save config to file: " + file.getAbsolutePath() + ". This is expected in test environment.");
        }
    }

    public void startBatchModifications() {
        batchModifications = true;
    }

    public void endBatchModifications() {
        batchModifications = false;
        save();
    }

    public void saveDefaultValues() {
        startBatchModifications();
        json.put(KEY_CONFIG_VERSION, SHTTPSConfig.CONFIG_VERSION);
        json.put(KEY_PORT, getPort());
        json.put(KEY_ALLOW_EDITING, getAllowEditing());
        json.put(KEY_RENDER_FOLDERS, getRenderFolders());
        json.put(KEY_ROOT_DIR, getRootDir() == null ? JSONObject.NULL : getRootDir().getUri());
        json.put(KEY_USERNAME, getUsername());
        json.put(KEY_PASSWORD, getPassword());
        //json.put(KEY_AUTOSTART, getAutostart());
        //json.put(KEY_RUNNING_STATE, getRunningState());
        json.put(KEY_REDIRECT_TO_INDEX, getRedirectToIndex());
        json.put(KEY_USE_TLS, getUseTLS());
        if (getTLSCert() == null) {
            json.put(KEY_TLS_CERT, "");
        }
        json.put(KEY_TLS_CERT_KEYSTORE_PASS, getTLSKeystorePassword());
        json.put(KEY_ALLOWED_NETWORK_INTERFACES, getAllowedNetworkInterfaces() == null ? "" : String.join(",", getAllowedNetworkInterfaces()));
        json.put(KEY_WHITE_LIST_MODE, WhiteListMode.toInt(getWhiteListMode()));
        json.put(KEY_WHITE_LIST_OF_IPS, getWhiteList() == null ? "" : String.join(",", getWhiteList()));
        json.put(KEY_CUSTOM_HEADERS, getCustomHeaders());
        json.put(KEY_DATABASE_ENABLED, isDatabaseEnabled());
        json.put(KEY_DATABASE_PATH, getDatabasePath());
        json.put(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, isAllowDatabaseCustomSqlRemoteApi());
        json.put(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, isAllowDatabaseTableDataEditingApi());
        json.put(KEY_AUTH_MODE, getAuthMode());
        json.put(KEY_USERS, getUsers());
        json.put(KEY_STORE_USERS_IN_DATABASE, isStoreUsersInDatabase());
        json.put(KEY_HOST, getHost());
        json.put(KEY_VERIFY_HOST, getVerifyHost());
        json.put(KEY_ALLOW_USER_REGISTRATION, isAllowedUserRegistration());
        json.put(KEY_DEFAULT_ROLE_FOR_NEW_USER, getDefaultRoleForNewUser());
        json.put(KEY_GLOBAL_RATE_LIMIT, getGlobalRateLimit());
        json.put(KEY_RATE_LIMITER_TRUST_IP_HEADERS, getRateLimiterTrustToIPHeaders());
        json.put(KEY_NEW_USER_DIR_PATTERN, getNewUserDirPattern());
        json.put(KEY_ENABLE_CGI, isCGIEnabled());
        json.put(KEY_CGI_FOLDER, getCGIFolder());
        json.put(KEY_CGI_PATH_PREFIX, getCGIPathPrefix());
        endBatchModifications();
    }
}
