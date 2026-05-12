package com.phlox.simpleserver;

import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware;
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.exec.CgiType;
import com.phlox.simpleserver.handlers.HandlersUtils;
import com.phlox.simpleserver.utils.Utils;


import org.json.JSONArray;
import org.json.JSONException;
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

@SuppressWarnings("DeprecatedIsStillUsed")
public interface SHTTPSConfig {
    int CONFIG_VERSION = 5;
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
    @Deprecated String KEY_CUSTOM_HEADERS = "custom_headers";
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
    String KEY_HEADERS_OVERRIDES = "headers_overrides";

    default void runMigrations() {
        if (getConfigVersion() == CONFIG_VERSION) return;
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
        if (getConfigVersion() == 4) {
            String customResponseHeadersStr = getCustomHeaders();
            if (customResponseHeadersStr != null && !customResponseHeadersStr.isEmpty()) {
                MultiMap<String, String> headersMap = HTTPUtils.parseHttpHeaders(customResponseHeadersStr);
                if (!headersMap.isEmpty()) {
                    List<CustomHeadersMiddleware.Rule> rules = getHeadersOverrides();
                    if (rules == null) rules = new ArrayList<>();
                    rules.add(new CustomHeadersMiddleware.Rule(
                            "/",
                            headersMap,
                            CustomHeadersMiddleware.IfHeadersExist.APPEND,
                            null,
                            null,
                            null
                    ));
                    setHeadersOverrides(rules);
                }
                setCustomHeaders(null);
            }
            setConfigVersion(5);
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

    default void setHeadersOverrides(@Nullable List<CustomHeadersMiddleware.Rule> rules) {
        JSONArray jRules = new JSONArray();
        if (rules != null) {
            for (CustomHeadersMiddleware.Rule rule : rules) {
                JSONObject json = new JSONObject();

                json.put("path", rule.path != null ? rule.path : "");

                JSONObject jHeaders = new JSONObject();
                if (rule.headers != null) {
                    rule.headers.forEach((name, values) -> {
                        JSONArray jValues = new JSONArray();
                        if (values != null) {
                            for (String value : values) {
                                jValues.put(value);
                            }
                        }
                        jHeaders.put(name, jValues);
                    });
                }
                json.put("headers", jHeaders);

                CustomHeadersMiddleware.IfHeadersExist ifExists = rule.ifHeadersExist;
                json.put("if_headers_exist", ifExists != null ? ifExists.name() : "");

                if (rule.filterMethods != null) {
                    JSONArray jMethods = new JSONArray();
                    for (String m : rule.filterMethods) {
                        jMethods.put(m);
                    }
                    json.put("filter_methods", jMethods);
                }

                if (rule.filterStatusCodes != null) {
                    JSONArray jCodes = new JSONArray();
                    for (Integer code : rule.filterStatusCodes) {
                        if (code != null) {
                            jCodes.put(code);
                        }
                    }
                    json.put("filter_status_codes", jCodes);
                }

                if (rule.filterPostfixes != null) {
                    JSONArray jPostfixes = new JSONArray();
                    for (String postfix : rule.filterPostfixes) {
                        jPostfixes.put(postfix);
                    }
                    json.put("filter_postfixes", jPostfixes);
                }

                jRules.put(json);
            }
        }
        setJSONArray(KEY_HEADERS_OVERRIDES, jRules);
    }

    default @Nullable List<CustomHeadersMiddleware.Rule> getHeadersOverrides() {
        JSONArray jRules = getJsonArray(KEY_HEADERS_OVERRIDES, null);
        if (jRules == null) return null;

        ArrayList<CustomHeadersMiddleware.Rule> rules = new ArrayList<>(jRules.length());
        for (int i = 0; i < jRules.length(); ++i) {
            Object obj = jRules.opt(i);
            if (!(obj instanceof JSONObject)) continue;

            JSONObject json = (JSONObject) obj;

            String path = json.optString("path", "");

            MultiMap<String, String> headers = new MultiMap<>();
            JSONObject jHeaders = json.optJSONObject("headers");
            if (jHeaders != null) {
                Iterator<String> keys = jHeaders.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    Object valuesObj = jHeaders.opt(name);
                    if (valuesObj instanceof JSONArray) {
                        JSONArray jValues = (JSONArray) valuesObj;
                        for (int vi = 0; vi < jValues.length(); ++vi) {
                            Object v = jValues.opt(vi);
                            if (v != null) {
                                headers.put(name, String.valueOf(v));
                            }
                        }
                    } else if (valuesObj != null) {
                        // Backward/forward compatibility: accept scalar as single value.
                        headers.put(name, String.valueOf(valuesObj));
                    }
                }
            }

            CustomHeadersMiddleware.IfHeadersExist ifHeadersExist = CustomHeadersMiddleware.IfHeadersExist.OVERRIDE;
            String ifExists = json.optString("if_headers_exist", null);
            if (ifExists != null && !ifExists.isEmpty()) {
                try {
                    ifHeadersExist = CustomHeadersMiddleware.IfHeadersExist.valueOf(ifExists);
                } catch (IllegalArgumentException ignored) {
                    // keep default
                }
            }

            HashSet<String> filterMethods = null;
            JSONArray jMethods = json.optJSONArray("filter_methods");
            if (jMethods != null && jMethods.length() > 0) {
                filterMethods = new HashSet<>();
                for (int mi = 0; mi < jMethods.length(); ++mi) {
                    String m = jMethods.optString(mi, null);
                    if (m != null && !m.isEmpty()) {
                        filterMethods.add(m);
                    }
                }
                if (filterMethods.isEmpty()) filterMethods = null;
            }

            HashSet<Integer> filterStatusCodes = null;
            JSONArray jCodes = json.optJSONArray("filter_status_codes");
            if (jCodes != null && jCodes.length() > 0) {
                filterStatusCodes = new HashSet<>();
                for (int ci = 0; ci < jCodes.length(); ++ci) {
                    Object c = jCodes.opt(ci);
                    if (c instanceof Number) {
                        filterStatusCodes.add(((Number) c).intValue());
                    } else if (c != null) {
                        try {
                            filterStatusCodes.add(Integer.parseInt(String.valueOf(c)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (filterStatusCodes.isEmpty()) filterStatusCodes = null;
            }

            HashSet<String> filterPostfixes = null;
            JSONArray jPostfixes = json.optJSONArray("filter_postfixes");
            if (jPostfixes != null && jPostfixes.length() > 0) {
                filterPostfixes = new HashSet<>();
                for (int pi = 0; pi < jPostfixes.length(); ++pi) {
                    String p = jPostfixes.optString(pi, null);
                    if (p != null && !p.isEmpty()) {
                        filterPostfixes.add(p);
                    }
                }
                if (filterPostfixes.isEmpty()) filterPostfixes = null;
            }

            rules.add(new CustomHeadersMiddleware.Rule(
                    path,
                    headers,
                    ifHeadersExist,
                    filterMethods,
                    filterStatusCodes,
                    filterPostfixes
            ));
        }
        return rules;
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

    /**
     * Returns the raw bytes of the TLS keystore (PKCS12/BKS) currently configured,
     * or {@code null} if no certificate is set. Implementations should resolve the
     * platform-specific storage of the certificate (e.g. file path on desktop,
     * Base64-encoded blob on Android) and return the raw keystore file contents
     * suitable for re-importing.
     */
    default byte[] getTLSCertBytes() {
        return null;
    }

    /**
     * Serializes the entire configuration into a single {@link JSONObject}.
     * <p>
     * The TLS certificate raw bytes are intentionally NOT included here because
     * the storage format differs between platforms (file path on desktop,
     * Base64 blob on Android). The certificate should be exported separately
     * via {@link #getTLSCertBytes()}.
     * <p>
     * Implementations may override this if they need to include
     * platform-specific keys; the default implementation covers all keys
     * defined by this interface.
     */
    @SuppressWarnings("deprecation")
    default JSONObject serializeAll() {
        JSONObject result = new JSONObject();
        result.put(KEY_CONFIG_VERSION, getConfigVersion());

        DocumentFile rootDir = getRootDir();
        if (rootDir != null) {
            result.put(KEY_ROOT_DIR, rootDir.getUri());
        }
        result.put(KEY_RENDER_FOLDERS, getRenderFolders());
        result.put(KEY_ALLOW_EDITING, getAllowEditing());
        result.put(KEY_PORT, getPort());

        String username = getUsername();
        if (username != null && !username.isEmpty()) {
            result.put(KEY_USERNAME, username);
        }
        String password = getPassword();
        if (password != null && !password.isEmpty()) {
            result.put(KEY_PASSWORD, password);
        }

        result.put(KEY_REDIRECT_TO_INDEX, getRedirectToIndex());
        result.put(KEY_USE_TLS, getUseTLS());
        // KEY_TLS_CERT itself is intentionally omitted - the keystore bytes are exported separately.
        String tlsKeystorePass = getTLSKeystorePassword();
        if (tlsKeystorePass != null) {
            result.put(KEY_TLS_CERT_KEYSTORE_PASS, tlsKeystorePass);
        }
        String tlsKeyPass = getTLSKeyPassword();
        if (tlsKeyPass != null) {
            result.put(KEY_TLS_CERT_KEY_PASS, tlsKeyPass);
        }

        String[] interfaces = getAllowedNetworkInterfaces();
        if (interfaces != null && interfaces.length > 0) {
            JSONArray jArr = new JSONArray();
            for (String i : interfaces) jArr.put(i);
            result.put(KEY_ALLOWED_NETWORK_INTERFACES, jArr);
        }

        result.put(KEY_WHITE_LIST_MODE, WhiteListMode.toInt(getWhiteListMode()));
        Set<String> whiteList = getWhiteList();
        if (whiteList != null && !whiteList.isEmpty()) {
            JSONArray jArr = new JSONArray();
            for (String s : whiteList) jArr.put(s);
            result.put(KEY_WHITE_LIST_OF_IPS, jArr);
        }

        String customHeaders = getCustomHeaders();
        if (customHeaders != null && !customHeaders.isEmpty()) {
            result.put(KEY_CUSTOM_HEADERS, customHeaders);
        }

        result.put(KEY_DATABASE_ENABLED, isDatabaseEnabled());
        String dbPath = getDatabasePath();
        if (dbPath != null) {
            result.put(KEY_DATABASE_PATH, dbPath);
        }
        result.put(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, isAllowDatabaseTableDataEditingApi());
        result.put(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, isAllowDatabaseCustomSqlRemoteApi());

        List<RedirectsMiddleware.RedirectRule> redirectRules = getRedirectRules();
        if (redirectRules != null) {
            JSONArray jArr = new JSONArray();
            for (RedirectsMiddleware.RedirectRule r : redirectRules) {
                jArr.put(HandlersUtils.ruleToJson(r));
            }
            result.put(KEY_REDIRECT_RULES, jArr);
        }

        result.put(KEY_AUTH_MODE, getAuthMode().name());

        List<User> users = getUsers();
        if (users != null && !users.isEmpty()) {
            JSONArray jUsers = new JSONArray();
            for (User u : users) jUsers.put(u.serialize());
            result.put(KEY_USERS, jUsers);
        }
        result.put(KEY_STORE_USERS_IN_DATABASE, isStoreUsersInDatabase());

        String host = getHost();
        if (host != null) result.put(KEY_HOST, host);
        result.put(KEY_VERIFY_HOST, getVerifyHost());
        result.put(KEY_ALLOW_USER_REGISTRATION, isAllowedUserRegistration());
        result.put(KEY_DEFAULT_ROLE_FOR_NEW_USER, getDefaultRoleForNewUser());
        result.put(KEY_GLOBAL_RATE_LIMIT, getGlobalRateLimit());
        result.put(KEY_RATE_LIMITER_TRUST_IP_HEADERS, getRateLimiterTrustToIPHeaders());
        result.put(KEY_NEW_USER_DIR_PATTERN, getNewUserDirPattern());

        result.put(KEY_ENABLE_CGI, isCGIEnabled());
        String cgiFolder = getCGIFolder();
        if (cgiFolder != null) result.put(KEY_CGI_FOLDER, cgiFolder);
        String cgiPathPrefix = getCGIPathPrefix();
        if (cgiPathPrefix != null) result.put(KEY_CGI_PATH_PREFIX, cgiPathPrefix);

        // CGI types are stored as a JSON array of objects, fetch the raw form directly.
        JSONArray cgiTypesRaw = getJsonArray(KEY_CGI_TYPES, null);
        if (cgiTypesRaw != null) {
            result.put(KEY_CGI_TYPES, cgiTypesRaw);
        }

        // CORS rules and Header overrides are also serialized into JSON arrays internally.
        JSONArray corsRulesRaw = getJsonArray(KEY_CORS_RULES, null);
        if (corsRulesRaw != null) {
            result.put(KEY_CORS_RULES, corsRulesRaw);
        }

        JSONArray headerOverridesRaw = getJsonArray(KEY_HEADERS_OVERRIDES, null);
        if (headerOverridesRaw != null) {
            result.put(KEY_HEADERS_OVERRIDES, headerOverridesRaw);
        }

        String defaultCharset = getDefaultTextCharset();
        if (defaultCharset != null) result.put(KEY_DEFAULT_TEXT_CHARSET, defaultCharset);

        return result;
    }

    /**
     * Installs the given raw TLS keystore bytes (PKCS12/BKS) as the active certificate.
     * Symmetric counterpart of {@link #getTLSCertBytes()} - implementations should
     * persist the bytes in the platform-native form (e.g. write to disk and store
     * the file path on desktop, Base64-encode into preferences on Android) and
     * update {@code KEY_TLS_CERT} accordingly.
     * <p>
     * Default no-op so that platforms which do not support importing a certificate
     * (e.g. simple test stubs) can still implement {@link SHTTPSConfig}.
     */
    default void installTLSCertBytes(byte[] bytes) {
        // no-op
    }

    /**
     * Applies the values from a previously serialized configuration (see
     * {@link #serializeAll()}) onto this instance. Any key that is present in
     * {@code json} replaces the current value. Keys that are absent are left
     * untouched.
     * <p>
     * The TLS certificate bytes are intentionally NOT handled here - the caller
     * should invoke {@link #installTLSCertBytes(byte[])} separately.
     * <p>
     * The deprecated {@code KEY_USERNAME}/{@code KEY_PASSWORD} pair is skipped
     * because (a) it is replaced by {@code KEY_USERS} during config migration
     * and (b) writing the password through generic {@link #setString(String, String)}
     * would bypass platform-specific encryption.
     */
    @SuppressWarnings("deprecation")
    default void applyAll(JSONObject json) {
        if (json == null) return;

        if (json.has(KEY_ROOT_DIR)) {
            Object v = json.opt(KEY_ROOT_DIR);
            setRootDir(v == null || v == JSONObject.NULL ? null : String.valueOf(v));
        }
        if (json.has(KEY_RENDER_FOLDERS)) setRenderFolders(json.optBoolean(KEY_RENDER_FOLDERS, true));
        if (json.has(KEY_ALLOW_EDITING)) setAllowEditing(json.optBoolean(KEY_ALLOW_EDITING, false));
        if (json.has(KEY_PORT)) setPort(json.optInt(KEY_PORT, 8080));

        // KEY_USERNAME / KEY_PASSWORD intentionally skipped - handled by config migration into KEY_USERS.

        if (json.has(KEY_REDIRECT_TO_INDEX)) setRedirectToIndex(json.optBoolean(KEY_REDIRECT_TO_INDEX, true));
        if (json.has(KEY_USE_TLS)) setUseTLS(json.optBoolean(KEY_USE_TLS, false));
        // KEY_TLS_CERT intentionally skipped - handled via installTLSCertBytes().
        if (json.has(KEY_TLS_CERT_KEYSTORE_PASS)) {
            setTLSKeystorePassword(json.optString(KEY_TLS_CERT_KEYSTORE_PASS, null));
        }
        if (json.has(KEY_TLS_CERT_KEY_PASS)) {
            setTLSKeyPassword(json.optString(KEY_TLS_CERT_KEY_PASS, null));
        }

        if (json.has(KEY_ALLOWED_NETWORK_INTERFACES)) {
            JSONArray arr = json.optJSONArray(KEY_ALLOWED_NETWORK_INTERFACES);
            if (arr != null && arr.length() > 0) {
                String[] interfaces = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) interfaces[i] = arr.optString(i, "");
                setAllowedNetworkInterfaces(interfaces);
            } else {
                setAllowedNetworkInterfaces(null);
            }
        }

        if (json.has(KEY_WHITE_LIST_MODE)) {
            int mode = json.optInt(KEY_WHITE_LIST_MODE, 0);
            setWhiteListMode(WhiteListMode.fromInt(mode));
        }
        if (json.has(KEY_WHITE_LIST_OF_IPS)) {
            JSONArray arr = json.optJSONArray(KEY_WHITE_LIST_OF_IPS);
            HashSet<String> set = new HashSet<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, null);
                    if (s != null && !s.isEmpty()) set.add(s);
                }
            }
            setWhiteList(set);
        }
        if (json.has(KEY_CUSTOM_HEADERS)) {
            setCustomHeaders(json.optString(KEY_CUSTOM_HEADERS, null));
        }

        if (json.has(KEY_DATABASE_ENABLED)) setDatabaseEnabled(json.optBoolean(KEY_DATABASE_ENABLED, false));
        if (json.has(KEY_DATABASE_PATH)) setDatabasePath(json.optString(KEY_DATABASE_PATH, null));
        if (json.has(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API)) {
            setAllowDatabaseTableDataEditingApi(json.optBoolean(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, false));
        }
        if (json.has(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API)) {
            setAllowDatabaseCustomSqlRemoteApi(json.optBoolean(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, false));
        }

        if (json.has(KEY_REDIRECT_RULES)) {
            JSONArray arr = json.optJSONArray(KEY_REDIRECT_RULES);
            if (arr != null) {
                List<RedirectsMiddleware.RedirectRule> rules = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) rules.add(HandlersUtils.ruleFromJson(obj));
                }
                setRedirectRules(rules);
            } else {
                setRedirectRules(null);
            }
        }

        if (json.has(KEY_AUTH_MODE)) {
            String mode = json.optString(KEY_AUTH_MODE, AuthMode.NONE.name());
            try {
                setAuthMode(AuthMode.valueOf(mode));
            } catch (IllegalArgumentException ignored) {
                setAuthMode(AuthMode.NONE);
            }
        }

        if (json.has(KEY_USERS)) {
            JSONArray arr = json.optJSONArray(KEY_USERS);
            List<User> users = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) {
                        try {
                            users.add(User.deserialize(obj));
                        } catch (JSONException ignored) {
                            // skip malformed user
                        }
                    }
                }
            }
            setUsers(users);
        }

        if (json.has(KEY_STORE_USERS_IN_DATABASE)) setStoreUsersInDatabase(json.optBoolean(KEY_STORE_USERS_IN_DATABASE, false));
        if (json.has(KEY_HOST)) setHost(json.optString(KEY_HOST, null));
        if (json.has(KEY_VERIFY_HOST)) setVerifyHost(json.optBoolean(KEY_VERIFY_HOST, false));
        if (json.has(KEY_ALLOW_USER_REGISTRATION)) setAllowedUserRegistration(json.optBoolean(KEY_ALLOW_USER_REGISTRATION, false));
        if (json.has(KEY_DEFAULT_ROLE_FOR_NEW_USER)) setDefaultRoleForNewUser(json.optString(KEY_DEFAULT_ROLE_FOR_NEW_USER, ""));
        if (json.has(KEY_GLOBAL_RATE_LIMIT)) setGlobalRateLimit(json.optInt(KEY_GLOBAL_RATE_LIMIT, 0));
        if (json.has(KEY_RATE_LIMITER_TRUST_IP_HEADERS)) setRateLimiterTrustToIPHeaders(json.optBoolean(KEY_RATE_LIMITER_TRUST_IP_HEADERS, false));
        if (json.has(KEY_NEW_USER_DIR_PATTERN)) setNewUserDirPattern(json.optString(KEY_NEW_USER_DIR_PATTERN, ""));

        if (json.has(KEY_ENABLE_CGI)) setCGIEnabled(json.optBoolean(KEY_ENABLE_CGI, false));
        if (json.has(KEY_CGI_FOLDER)) setCGIFolder(json.optString(KEY_CGI_FOLDER, null));
        if (json.has(KEY_CGI_PATH_PREFIX)) setCGIPathPrefix(json.optString(KEY_CGI_PATH_PREFIX, "/cgi-bin"));

        if (json.has(KEY_CGI_TYPES)) {
            JSONArray arr = json.optJSONArray(KEY_CGI_TYPES);
            setJSONArray(KEY_CGI_TYPES, arr != null ? arr : new JSONArray());
        }
        if (json.has(KEY_CORS_RULES)) {
            JSONArray arr = json.optJSONArray(KEY_CORS_RULES);
            setJSONArray(KEY_CORS_RULES, arr != null ? arr : new JSONArray());
        }
        if (json.has(KEY_HEADERS_OVERRIDES)) {
            JSONArray arr = json.optJSONArray(KEY_HEADERS_OVERRIDES);
            setJSONArray(KEY_HEADERS_OVERRIDES, arr != null ? arr : new JSONArray());
        }
        if (json.has(KEY_DEFAULT_TEXT_CHARSET)) setDefaultTextCharset(json.optString(KEY_DEFAULT_TEXT_CHARSET, null));

        // Apply config version last so any incompatible/old field shapes were already accepted with their
        // documented semantics. The caller is responsible for invoking runMigrations() afterwards if needed.
        if (json.has(KEY_CONFIG_VERSION)) setConfigVersion(json.optInt(KEY_CONFIG_VERSION, CONFIG_VERSION));
    }


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
