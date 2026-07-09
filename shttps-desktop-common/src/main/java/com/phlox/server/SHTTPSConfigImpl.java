package com.phlox.server;

import static com.phlox.server.utils.docfile.RawDocumentFile.fileUriToFilePath;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;

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
import java.util.Collections;

@SuppressWarnings({"deprecation"})
public class SHTTPSConfigImpl implements SHTTPSConfig {
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
    public byte[] getTLSCertBytes() {
        // On desktop the certificate is stored on disk and KEY_TLS_CERT contains the file path.
        String path = json.optString(KEY_TLS_CERT, null);
        if (path == null || path.isEmpty()) return null;
        try {
            File certFile = new File(path);
            if (!certFile.exists() || !certFile.isFile()) {
                logger.w("TLS certificate file does not exist: " + path);
                return null;
            }
            return Files.readAllBytes(certFile.toPath());
        } catch (IOException e) {
            logger.e("Failed to read TLS certificate bytes from: " + path, e);
            return null;
        }
    }

    /**
     * Default name of the file used to store an imported TLS keystore alongside the config file
     * when no existing certificate path is set. The actual extension is irrelevant - the keystore
     * type is decided by {@link #getTLSCert()} based on the .bks vs anything-else suffix.
     */
    public static final String IMPORTED_TLS_CERT_FILE_NAME = "imported_tls_cert.pfx";

    @Override
    public void installTLSCertBytes(byte[] bytes) {
        if (bytes == null) {
            // Clear the cert.
            setTLSCert(null);
            return;
        }
        try {
            // Prefer overwriting the contents of the *existing* certificate file so that any
            // directory permissions or external references stay valid. Only fall back to a
            // default location next to the config file when no path has been set yet.
            String existingPath = json.optString(KEY_TLS_CERT, null);
            File certFile;
            if (existingPath != null && !existingPath.isEmpty()) {
                certFile = new File(existingPath);
                File parent = certFile.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    logger.e("Failed to create directory for TLS cert: " + parent);
                    return;
                }
            } else {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent == null) parent = new File(".");
                if (!parent.exists() && !parent.mkdirs()) {
                    logger.e("Failed to create directory for imported TLS cert: " + parent);
                    return;
                }
                certFile = new File(parent, IMPORTED_TLS_CERT_FILE_NAME);
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(certFile)) {
                fos.write(bytes);
            }
            json.put(KEY_TLS_CERT, certFile.getAbsolutePath());
            save();
        } catch (IOException e) {
            logger.e("Failed to install imported TLS certificate", e);
        }
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
        json.put(KEY_DATABASE_PATH, getDatabasePath() == null ? "" : getDatabasePath());
        json.put(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, isAllowDatabaseCustomSqlRemoteApi());
        json.put(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, isAllowDatabaseTableDataEditingApi());
        json.put(KEY_AUTH_MODE, getAuthMode().name());
        json.put(KEY_USERS, new JSONArray());
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
