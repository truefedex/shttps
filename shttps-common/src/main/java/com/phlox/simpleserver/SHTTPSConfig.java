package com.phlox.simpleserver;

import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.Utils;


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

    String KEY_LEGACY_TYPO_ROOT_DIR = "rot_dir";
    String KEY_ROOT_DIR = "root_dir";
    String KEY_RENDER_FOLDERS = "render_folders";
    String KEY_ALLOW_EDITING = "allow_editing";
    String KEY_PORT = "port";
    String KEY_USE_BASIC_AUTH = "use_basic_auth";
    String KEY_USERNAME = "username";
    String KEY_PASSWORD = "password";
    String KEY_AUTOSTART = "autostart";
    String KEY_RUNNING_STATE = "running_state";
    String KEY_REDIRECT_TO_INDEX = "redirect_to_index";
    String KEY_USE_TLS = "use_tls";
    String KEY_TLS_CERT = "tls_cert";
    String KEY_TLS_CERT_PASS = "tls_cert_pass";
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
            if (getUseBasicAuth()) {
                setAuthMode(AuthMode.WEB);
            }
            if (!getUsername().isEmpty()) {
                List<User> users = new ArrayList<>(1);
                users.add(new User(
                        getUsername(),
                        Objects.requireNonNull(Utils.sha256(Utils.hashFNV1a32(getPassword()))),
                        null,
                        EnumSet.allOf(User.FileSystemRights.class
                )));
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
    boolean getUseBasicAuth();

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

    String getTLSCertPassword();

    void setTLSCertPassword(String value);

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

    default void setConfigVersion(int value) {
        setInt(KEY_CONFIG_VERSION, value);
    }

    default int getConfigVersion() {
        return getInt(KEY_CONFIG_VERSION, 0);
    }

    int getInt(String key, int defaultValue);

    void setInt(String key, int value);

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
