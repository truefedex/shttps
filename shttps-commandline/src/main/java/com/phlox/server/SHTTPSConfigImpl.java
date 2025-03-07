package com.phlox.server;

import static com.phlox.server.utils.docfile.RawDocumentFile.fileUriToFilePath;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
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
                json = new JSONObject(new String(data));
            } catch (Exception e) {
                logger.e("Failed to load config from file: " + file.getAbsolutePath(), e);
                throw new IOException("Failed to load config from file: " + file.getAbsolutePath());
            }
        }
    }

    @Override
    public void runMigrations() {
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
    public boolean getUseBasicAuth() {
        return json.optBoolean(KEY_USE_BASIC_AUTH, false);
    }

    @Override
    public void setUseBasicAuth(boolean value) {
        json.put(KEY_USE_BASIC_AUTH, value);
        save();
    }

    @Override
    public String getUsername() {
        return json.optString(KEY_USERNAME, "");
    }

    @Override
    public void setUsername(String value) {
        json.put(KEY_USERNAME, value);
        save();
    }

    @Override
    public String getPassword() {
        return json.optString(KEY_PASSWORD, null);
    }

    @Override
    public void setPassword(String value) {
        json.put(KEY_PASSWORD, value);
        save();
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
        try {
            KeyStore ks;
            if (path.endsWith(".bks")) {
                ks = KeyStore.getInstance("BKS");
            } else {
                ks = KeyStore.getInstance("PKCS12");
            }
            ks.load(new FileInputStream(path), getTLSCertPassword().toCharArray());
            return ks;
        } catch (Exception e) {
            logger.e("Failed to read keystore file: " + path, e);
            return null;
        }
    }

    @Override
    public void setTLSCert(byte[] value) {
        //on commandline we assume value is path to file
        json.put(KEY_TLS_CERT, new String(value));
        save();
    }

    @Override
    public String getTLSCertPassword() {
        return json.optString(KEY_TLS_CERT_PASS, null);
    }

    @Override
    public void setTLSCertPassword(String value) {
        json.put(KEY_TLS_CERT_PASS, value);
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

    private void save() {
        if (batchModifications) {
            return;
        }
        try {
            Files.write(file.toPath(), json.toString(4).getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
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
        json.put(KEY_PORT, getPort());
        json.put(KEY_ALLOW_EDITING, getAllowEditing());
        json.put(KEY_RENDER_FOLDERS, getRenderFolders());
        json.put(KEY_ROOT_DIR, getRootDir() == null ? JSONObject.NULL : getRootDir().getUri());
        json.put(KEY_USE_BASIC_AUTH, getUseBasicAuth());
        json.put(KEY_USERNAME, getUsername());
        json.put(KEY_PASSWORD, getPassword());
        //json.put(KEY_AUTOSTART, getAutostart());
        //json.put(KEY_RUNNING_STATE, getRunningState());
        json.put(KEY_REDIRECT_TO_INDEX, getRedirectToIndex());
        json.put(KEY_USE_TLS, getUseTLS());
        if (getTLSCert() == null) {
            json.put(KEY_TLS_CERT, "");
        }
        json.put(KEY_TLS_CERT_PASS, getTLSCertPassword());
        json.put(KEY_ALLOWED_NETWORK_INTERFACES, getAllowedNetworkInterfaces() == null ? "" : String.join(",", getAllowedNetworkInterfaces()));
        json.put(KEY_WHITE_LIST_MODE, WhiteListMode.toInt(getWhiteListMode()));
        json.put(KEY_WHITE_LIST_OF_IPS, getWhiteList() == null ? "" : String.join(",", getWhiteList()));
        json.put(KEY_CUSTOM_HEADERS, getCustomHeaders());
        json.put(KEY_DATABASE_ENABLED, isDatabaseEnabled());
        json.put(KEY_DATABASE_PATH, getDatabasePath());
        json.put(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, isAllowDatabaseCustomSqlRemoteApi());
        json.put(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, isAllowDatabaseTableDataEditingApi());
        endBatchModifications();
    }
}
