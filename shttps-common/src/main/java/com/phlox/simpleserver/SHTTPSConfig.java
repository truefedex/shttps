package com.phlox.simpleserver;

import com.phlox.server.handlers.CORSMiddleware;
import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.exec.CgiType;
import com.phlox.simpleserver.utils.Utils;


import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import proguard.annotation.Keep;

public interface SHTTPSConfig {
    int CONFIG_VERSION = 4;
    String KEY_ROOT_DIR = "root_dir";
    String KEY_RENDER_FOLDERS = "render_folders";
    String KEY_ALLOW_EDITING = "allow_editing";
    String KEY_PORT = "port";
    @Deprecated String KEY_USE_BASIC_AUTH = "use_basic_auth";
    String KEY_USERNAME = "username";
    String KEY_PASSWORD = "password";
    String KEY_REDIRECT_TO_INDEX = "redirect_to_index";
    String KEY_USE_TLS = "use_tls";
    String KEY_TLS_CERT = "tls_cert";
    String KEY_TLS_CERT_KEYSTORE_PASS = "tls_cert_pass";
    String KEY_TLS_CERT_KEY_PASS = "tls_key_pass";
    String KEY_ALLOWED_NETWORK_INTERFACES = "allowed_network_interfaces";
    String KEY_WHITE_LIST_MODE = "white_list_mode";
    String KEY_WHITE_LIST_OF_IPS = "white_list_of_ips";
    String KEY_CUSTOM_HEADERS = "custom_headers";
    String KEY_DATABASE_ENABLED = "database_enabled";
    String KEY_DATABASE_PATH = "database_path";
    String KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API = "allow_database_table_data_editing_api";
    String KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API = "allow_database_custom_sql_remote_api";
    String KEY_REDIRECT_RULES = "redirect_rules";
    String KEY_CONFIG_VERSION = "config_version";
    String KEY_AUTH_MODE = "auth_mode";
    String KEY_USERS = "users";
    String KEY_STORE_USERS_IN_DATABASE = "store_users_in_database";
    String KEY_HOST = "host";
    String KEY_VERIFY_HOST = "verify_host";
    String KEY_ALLOW_USER_REGISTRATION = "allow_user_registration";
    String KEY_DEFAULT_ROLE_FOR_NEW_USER = "default_new_user_role";
    String KEY_GLOBAL_RATE_LIMIT = "global_rate_limit";
    String KEY_RATE_LIMITER_TRUST_IP_HEADERS = "rate_limiter_trust_ip_headers";
    String KEY_NEW_USER_DIR_PATTERN = "new_user_dir_pattern";
    String KEY_ENABLE_CGI = "enable_cgi";
    String KEY_CGI_FOLDER = "cgi_folder";
    String KEY_CGI_PATH_PREFIX = "cgi_path_prefix";
    String KEY_CGI_TYPES = "cgi_types";
    String KEY_CORS_RULES = "cors_rules";
    String KEY_DEFAULT_TEXT_CHARSET = "default_text_charset";

    default void runMigrations() {
        if (getConfigVersion() == CONFIG_VERSION) return;
        if (getConfigVersion() <= 2) {
            List<RedirectsMiddleware.RedirectRule> redirects = getRedirectRules();
            if (redirects != null) {
                for (Iterator<RedirectsMiddleware.RedirectRule> iter = redirects.iterator(); iter.hasNext(); ) {
                    RedirectsMiddleware.RedirectRule rule = iter.next();
                    if (rule.shttpsInternal) {
                        iter.remove();
                    }
                }
                setRedirectRules(redirects);
            }
            setConfigVersion(3);
        }
        if (getConfigVersion() == 3) {
            if (getBoolean(KEY_USE_BASIC_AUTH, false)) {
                setAuthMode(AuthMode.WEB);
            }
            if (!getUsername().isEmpty()) {
                List<User> users = new ArrayList<>(1);
                users.add(new User(
                        getUsername(),
                        Objects.requireNonNull(Utils.sha256(Utils.hashFNV1a32(getPassword()))),
                        null,
                        EnumSet.allOf(User.FileSystemRights.class),
                        EnumSet.allOf(User.DBRights.class),
                        null,
                        System.currentTimeMillis(), null, null,
                        EnumSet.of(User.SystemRights.READ_STATUS),
                        0
                ));
                setUsers(users);
            }
            setConfigVersion(4);
        }
    }

    DocumentFile getRootDir();

    void setRootDir(String value);

    boolean getRenderFolders();

    void setRenderFolders(boolean value);

    boolean getAllowEditing();

    void setAllowEditing(boolean value);

    int getPort();

    void setPort(int value);

    @Deprecated
    String getUsername();

    @Deprecated
    String getPassword();

    boolean getRedirectToIndex();

    void setRedirectToIndex(boolean value);

    boolean getUseTLS();

    void setUseTLS(boolean value);

    KeyStore getTLSCert();

    void setTLSCert(byte [] value);

    default String getTLSKeystorePassword() {
        return getString(KEY_TLS_CERT_KEYSTORE_PASS, null);
    }

    default void setTLSKeystorePassword(String value) {
        setString(KEY_TLS_CERT_KEYSTORE_PASS, value);
    }

    default String getTLSKeyPassword() {
        return getString(KEY_TLS_CERT_KEY_PASS, null);
    }

    default void setTLSKeyPassword(String value) {
        setString(KEY_TLS_CERT_KEY_PASS, value);
    }

    String[] getAllowedNetworkInterfaces();

    void setAllowedNetworkInterfaces(String[] value);

    Set<WhiteListMode> getWhiteListMode();

    void setWhiteListMode(Set<WhiteListMode> value);

    HashSet<String> getWhiteList();

    void setWhiteList(Set<String> value);

    void setCustomHeaders(String value);

    String getCustomHeaders();

    boolean isDatabaseEnabled();

    void setDatabaseEnabled(boolean value);

    String getDatabasePath();

    void setDatabasePath(String value);

    boolean isAllowDatabaseTableDataEditingApi();

    void setAllowDatabaseTableDataEditingApi(boolean value);

    boolean isAllowDatabaseCustomSqlRemoteApi();

    void setAllowDatabaseCustomSqlRemoteApi(boolean value);

    List<RedirectsMiddleware.RedirectRule> getRedirectRules();

    void setRedirectRules(List<RedirectsMiddleware.RedirectRule> value);

    List<User> getUsers();

    void setUsers(Collection<User> users);

    AuthMode getAuthMode();
    void setAuthMode(AuthMode value);

    default boolean isStoreUsersInDatabase() {
        return getBoolean(KEY_STORE_USERS_IN_DATABASE, false);
    }

    default void setStoreUsersInDatabase(boolean value) {
        setBoolean(KEY_STORE_USERS_IN_DATABASE, value);
    }

    default String getHost() {
        return getString(KEY_HOST, null);
    }

    default void setHost(String value) {
        setString(KEY_HOST, value);
    }

    default boolean getVerifyHost() { return getBoolean(KEY_VERIFY_HOST, false); }

    default void setVerifyHost(boolean value) {
        setBoolean(KEY_VERIFY_HOST, value);
    }

    default void setConfigVersion(int value) {
        setInt(KEY_CONFIG_VERSION, value);
    }

    default int getConfigVersion() {
        return getInt(KEY_CONFIG_VERSION, 0);
    }

    default boolean isAllowedUserRegistration() {
        return getBoolean(KEY_ALLOW_USER_REGISTRATION, false);
    }

    default void setAllowedUserRegistration(boolean value) {
        setBoolean(KEY_ALLOW_USER_REGISTRATION, value);
    }

    default String getDefaultRoleForNewUser() {
        return getString(KEY_DEFAULT_ROLE_FOR_NEW_USER, "");
    }

    default void setDefaultRoleForNewUser(String value) {
        setString(KEY_DEFAULT_ROLE_FOR_NEW_USER, value);
    }

    default int getGlobalRateLimit() {
        return getInt(KEY_GLOBAL_RATE_LIMIT, 0);
    }

    default void setGlobalRateLimit(int requestsPerMinute) {
        setInt(KEY_GLOBAL_RATE_LIMIT, requestsPerMinute);
    }

    default boolean getRateLimiterTrustToIPHeaders() {
        return getBoolean(KEY_RATE_LIMITER_TRUST_IP_HEADERS, false);
    }

    default void setRateLimiterTrustToIPHeaders(boolean value) {
        setBoolean(KEY_RATE_LIMITER_TRUST_IP_HEADERS, value);
    }

    default void setNewUserDirPattern(String pattern) {
        setString(KEY_NEW_USER_DIR_PATTERN, pattern);
    }

    default String getNewUserDirPattern() {
        return getString(KEY_NEW_USER_DIR_PATTERN, "");
    }

    default boolean isCGIEnabled() {
        return getBoolean(KEY_ENABLE_CGI, false);
    }

    default void setCGIEnabled(boolean enabled) {
        setBoolean(KEY_ENABLE_CGI, enabled);
    }

    default @Nullable String getCGIFolder() {
        return getString(KEY_CGI_FOLDER, null);
    }

    default void setCGIFolder(@Nullable String folder) {
        setString(KEY_CGI_FOLDER, folder);
    }

    default @Nullable String getCGIPathPrefix() {
        return getString(KEY_CGI_PATH_PREFIX, "/cgi-bin");
    }

    default void setCGIPathPrefix(@Nullable String prefix) {
        setString(KEY_CGI_PATH_PREFIX, prefix);
    }

    default @Nullable List<CgiType> getCGITypes() {
        JSONArray jCgiTypes = getJsonArray(KEY_CGI_TYPES, null);
        if (jCgiTypes == null) return null;
        ArrayList<CgiType> cgiTypes = new ArrayList<>(jCgiTypes.length());
        for (int i = 0; i < jCgiTypes.length(); ++i) {
            Object obj = jCgiTypes.opt(i);
            if (obj instanceof JSONObject) {
                cgiTypes.add(CgiType.deserialize((JSONObject) obj));
            }
        }
        return cgiTypes;
    }
    default void setCGITypes(@Nullable List<CgiType> list) {
        JSONArray jCgiTypes = new JSONArray();
        if (list != null) {
            for (CgiType type : list) {
                jCgiTypes.put(type.serialize());
            }
        }
        setJSONArray(KEY_CGI_TYPES, jCgiTypes);
    }

    default @Nullable List<CORSMiddleware.CORSRule> getCORSRules() {
        JSONArray jCorsRules = getJsonArray(KEY_CORS_RULES, null);
        if (jCorsRules == null) return null;
        ArrayList<CORSMiddleware.CORSRule> corsRules = new ArrayList<>(jCorsRules.length());
        for (int i = 0; i < jCorsRules.length(); ++i) {
            Object obj = jCorsRules.opt(i);
            if (obj instanceof JSONObject) {
                JSONObject json = (JSONObject) obj;
                CORSMiddleware.CORSRule corsRule = new CORSMiddleware.CORSRule();
                corsRule.origin = json.optString("origin", "*");
                String allowMethods = json.optString("allow_methods", null);
                if (allowMethods != null && !allowMethods.isEmpty()) {
                    corsRule.allowMethods = allowMethods.split(",");
                }
                String allowHeaders = json.optString("allow_headers", null);
                if (allowHeaders != null && !allowHeaders.isEmpty()) {
                    corsRule.allowHeaders = allowHeaders.split(",");
                }
                if (json.has("allow_credentials")) {
                    corsRule.allowCredentials = json.getBoolean("allow_credentials");
                }
                String exposeHeaders = json.optString("expose_headers", null);
                if (exposeHeaders != null && !exposeHeaders.isEmpty()) {
                    corsRule.exposeHeaders = exposeHeaders.split(",");
                }
                int maxAge = json.optInt("max_age", 0);
                if (maxAge > 0) {
                    corsRule.maxAge = maxAge;
                }
                corsRules.add(corsRule);
            }
        }
        return corsRules;
    }

    default void setCORSRules(@Nullable List<CORSMiddleware.CORSRule> list) {
        JSONArray jCorsRules = new JSONArray();
        if (list != null) {
            for (CORSMiddleware.CORSRule corsRule : list) {
                JSONObject json = new JSONObject();
                json.put("origin", corsRule.origin != null ? corsRule.origin : "");
                json.put("allow_methods", corsRule.allowMethods != null ? String.join(",", corsRule.allowMethods) : "");
                json.put("allow_headers", corsRule.allowHeaders != null ? String.join(",", corsRule.allowHeaders) : "");
                if (corsRule.allowCredentials != null) {
                    json.put("allow_credentials", corsRule.allowCredentials);
                }
                json.put("expose_headers", corsRule.exposeHeaders != null ? String.join(",", corsRule.exposeHeaders) : "");
                json.put("max_age", corsRule.maxAge);
                jCorsRules.put(json);
            }
        }
        setJSONArray(KEY_CORS_RULES, jCorsRules);
    }

    default @Nullable String getDefaultTextCharset() {
        return getString(KEY_DEFAULT_TEXT_CHARSET, null);
    }

    default void setDefaultTextCharset(@Nullable String charset) {
        setString(KEY_DEFAULT_TEXT_CHARSET, charset);
    }

    int getInt(String key, int defaultValue);

    void setInt(String key, int value);

    boolean getBoolean(String key, boolean defaultValue);

    void setBoolean(String key, boolean value);

    String getString(String key, String defaultValue);

    void setString(String key, String value);
    JSONArray getJsonArray(String key, JSONArray defaultValue);

    /* Store array of JSONObject (other types currently not supported!) */
    void setJSONArray(String key, JSONArray value);


    @Keep
    enum AuthMode {
        NONE, WEB, BASIC_AUTH
    }

    enum WhiteListMode {
        ASK_AT_RUNTIME(1),
        PREDEFINED(2);

        private final int value;

        WhiteListMode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public static Set<WhiteListMode> fromInt(int value) {
            Set<WhiteListMode> result = new HashSet<>();
            for (WhiteListMode mode : SHTTPSConfig.WhiteListMode.values()) {
                if ((mode.getValue() & value) != 0) {
                    result.add(mode);
                }
            }
            return result;
        }

        public static int toInt(Set<WhiteListMode> value) {
            int result = 0;
            for (WhiteListMode mode : value) {
                result |= mode.getValue();
            }
            return result;
        }
    }
}
