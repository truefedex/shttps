package com.phlox.simpleserver;

import com.phlox.server.handlers.RoutingRequestHandler;
import com.phlox.server.utils.docfile.DocumentFile;


import java.security.KeyStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface SHTTPSConfig {
    int CONFIG_VERSION = 2;

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

    default void runMigrations() {
        if (getConfigVersion() == CONFIG_VERSION) return;
        if (getConfigVersion() <= 0) {
            List<RoutingRequestHandler.RedirectRule> redirects = getRedirectRules();
            if (redirects != null) {
                for (RoutingRequestHandler.RedirectRule rule : redirects) {
                    if (rule.shttpsInternal && rule.from.equals("/(.*)/")) {
                        rule.from = "/(.*)/?";
                    }
                }
                setRedirectRules(redirects);
            }
            setConfigVersion(1);
        }
        if (getConfigVersion() == 1) {
            List<RoutingRequestHandler.RedirectRule> redirects = getRedirectRules();
            if (redirects != null) {
                for (RoutingRequestHandler.RedirectRule rule : redirects) {
                    if (rule.shttpsInternal && rule.from.equals("/(.*)/?")) {
                        rule.from = "^((?:/.+/)|/)$";
                        rule.to = "{1}index.html";
                    }
                }
                setRedirectRules(redirects);
            }
            setConfigVersion(2);
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

    boolean getUseBasicAuth();

    void setUseBasicAuth(boolean value);

    String getUsername();

    void setUsername(String value);

    String getPassword();

    void setPassword(String value);

    @Deprecated
    boolean getRedirectToIndex();

    @Deprecated
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

    List<RoutingRequestHandler.RedirectRule> getRedirectRules();

    void setRedirectRules(List<RoutingRequestHandler.RedirectRule> value);

    default void setConfigVersion(int value) {
        setInt(KEY_CONFIG_VERSION, value);
    }

    default int getConfigVersion() {
        return getInt(KEY_CONFIG_VERSION, 0);
    }

    int getInt(String key, int defaultValue);

    void setInt(String key, int value);

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
