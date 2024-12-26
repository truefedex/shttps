package com.phlox.simpleserver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.phlox.server.platform.Base64;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.utils.KeyStoreCrypt;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.docfile.MediaStoreFileCollectionFile;
import com.phlox.simpleserver.utils.docfile.TreeDocumentFile;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SHTTPSConfigAndroid implements SHTTPSConfig {
    private final Context context;
    private final SharedPreferences prefs;

    private final KeyStoreCrypt keyStoreCrypt;

    private static final String ALIAS_CONFIG_PREFIX = "config_";

    public SHTTPSConfigAndroid(Context context, String prefName, SHTTPSPlatformUtils platformUtils) {
        this.context = context;
        this.keyStoreCrypt = new KeyStoreCrypt(context);
        prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
    }

    @SuppressLint("NewApi")
    @Override
    public DocumentFile getRootDir() {
        String rootDirStr;
        if (prefs.contains(KEY_LEGACY_TYPO_ROOT_DIR)) {
            rootDirStr = prefs.getString(KEY_LEGACY_TYPO_ROOT_DIR, null);
            prefs.edit()
                .remove(KEY_LEGACY_TYPO_ROOT_DIR)
                .putString(KEY_ROOT_DIR, rootDirStr)
                .apply();
        } else {
            rootDirStr = prefs.getString(KEY_ROOT_DIR, null);
        }
        if (rootDirStr != null) {
            if (rootDirStr.startsWith(MediaStoreFileCollectionFile.MEDIASTORE_FILES_DUMMY_URI)) {
                String relativePath = rootDirStr.substring(MediaStoreFileCollectionFile.MEDIASTORE_FILES_DUMMY_URI.length());
                String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
                return new MediaStoreFileCollectionFile(context, null, Uri.parse(rootDirStr), name, relativePath);
            } else if (rootDirStr.startsWith("content:")) {
                return TreeDocumentFile.fromTreeUri(context, Uri.parse(rootDirStr));
            } else {
                return DocumentFile.fromFile(new File(rootDirStr));
            }
        } else {
            return null;
        }
    }

    @Override
    public void setRootDir(String value) {
        prefs.edit().putString(KEY_ROOT_DIR, value).apply();
    }

    @Override
    public boolean getRenderFolders() {
        return prefs.getBoolean(KEY_RENDER_FOLDERS, true);
    }

    @Override
    public void setRenderFolders(boolean value) {
        prefs.edit().putBoolean(KEY_RENDER_FOLDERS, value).apply();
    }

    @Override
    public boolean getAllowEditing() {
        return prefs.getBoolean(KEY_ALLOW_EDITING, false);
    }

    @Override
    public void setAllowEditing(boolean value) {
        prefs.edit().putBoolean(KEY_ALLOW_EDITING, value).apply();
    }

    @Override
    public int getPort() {
        return prefs.getInt(KEY_PORT, 8080);
    }

    @Override
    public void setPort(int value) {
        prefs.edit().putInt(KEY_PORT, value).apply();
    }

    @Override
    public boolean getUseBasicAuth() {
        return prefs.getBoolean(KEY_USE_BASIC_AUTH, false);
    }

    @Override
    public void setUseBasicAuth(boolean value) {
        prefs.edit().putBoolean(KEY_USE_BASIC_AUTH, value).apply();
    }

    @Override
    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    @Override
    public void setUsername(String value) {
        prefs.edit().putString(KEY_USERNAME, value).apply();
    }

    @Override
    public String getPassword() {
        try {
            String encrypted = prefs.getString(KEY_PASSWORD, null);
            if (encrypted == null) return "";
            if (keyStoreCrypt.getKeyStore().containsAlias(ALIAS_CONFIG_PREFIX + KEY_PASSWORD)) {
                return keyStoreCrypt.decrypt(encrypted, ALIAS_CONFIG_PREFIX + KEY_PASSWORD);
            } else {
                throw new Exception("Key alias not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public void setPassword(String value) {
        try {
            String encrypted = keyStoreCrypt.encrypt(value, ALIAS_CONFIG_PREFIX + KEY_PASSWORD);
            prefs.edit().putString(KEY_PASSWORD, encrypted).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean getRedirectToIndex() {
        return prefs.getBoolean(KEY_REDIRECT_TO_INDEX, true);
    }

    @Override
    public void setRedirectToIndex(boolean value) {
        prefs.edit().putBoolean(KEY_REDIRECT_TO_INDEX, value).apply();
    }

    @Override
    public boolean getUseTLS() {
        return prefs.getBoolean(KEY_USE_TLS, false);
    }

    @Override
    public void setUseTLS(boolean value) {
        prefs.edit().putBoolean(KEY_USE_TLS, value).apply();
    }

    @Override
    public byte[] getTLSCert() {
        String encoded = prefs.getString(KEY_TLS_CERT, null);
        if (encoded == null) return null;
        try {
            return Base64.decode(encoded);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setTLSCert(byte[] value) {
        if (value == null) {
            prefs.edit().remove(KEY_TLS_CERT).remove(KEY_TLS_CERT_PASS).apply();
            return;
        }
        try {
            //do not encrypt the cert, it's already encrypted and due to its size we can get IllegalBlockSizeException: input must be under 256 bytes
            prefs.edit().putString(KEY_TLS_CERT, Base64.encodeToString(value)).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getTLSCertPassword() {
        String encrypted = prefs.getString(KEY_TLS_CERT_PASS, null);
        if (encrypted == null) return null;
        try {
            if (keyStoreCrypt.getKeyStore().containsAlias(ALIAS_CONFIG_PREFIX + KEY_TLS_CERT_PASS)) {
                return keyStoreCrypt.decrypt(encrypted, ALIAS_CONFIG_PREFIX + KEY_TLS_CERT_PASS);
            } else {
                throw new Exception("Key alias not found");
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void setTLSCertPassword(String value) {
        /* old way
        try {
            String encrypted = AESCrypt.encrypt(value);
            prefs.edit().putString(KEY_TLS_CERT_PASS, encrypted).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }*/
        if (value == null) {
            prefs.edit().remove(KEY_TLS_CERT_PASS).apply();
            return;
        }
        try {
            String encrypted = keyStoreCrypt.encrypt(value, ALIAS_CONFIG_PREFIX + KEY_TLS_CERT_PASS);
            prefs.edit().putString(KEY_TLS_CERT_PASS, encrypted).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String[] getAllowedNetworkInterfaces() {
        String value = prefs.getString(KEY_ALLOWED_NETWORK_INTERFACES, "");
        if (value.isEmpty()) return null;
        return value.split(",");
    }

    @Override
    public void setAllowedNetworkInterfaces(String[] value) {
        if (value == null) {
            prefs.edit().remove(KEY_ALLOWED_NETWORK_INTERFACES).apply();
            return;
        }
        prefs.edit().putString(KEY_ALLOWED_NETWORK_INTERFACES, TextUtils.join(",", value)).apply();
    }

    @Override
    public Set<WhiteListMode> getWhiteListMode() {
        int value = prefs.getInt(KEY_WHITE_LIST_MODE, 0);
        return WhiteListMode.fromInt(value);
    }

    @Override
    public void setWhiteListMode(Set<WhiteListMode> value) {
        if (value == null) {
            prefs.edit().remove(KEY_WHITE_LIST_MODE).apply();
            return;
        }
        int intValue = WhiteListMode.toInt(value);
        prefs.edit().putInt(KEY_WHITE_LIST_MODE, intValue).apply();
    }

    @Override
    public HashSet<String> getWhiteList() {
        HashSet<String> result = new HashSet<>();
        String value = prefs.getString(KEY_WHITE_LIST_OF_IPS, "");
        if (value.isEmpty()) return result;
        String[] ips = value.split(",");
        Collections.addAll(result, ips);
        return result;
    }

    @Override
    public void setWhiteList(Set<String> value) {
        if (value == null) {
            prefs.edit().remove(KEY_WHITE_LIST_OF_IPS).apply();
            return;
        }
        prefs.edit().putString(KEY_WHITE_LIST_OF_IPS, TextUtils.join(",", value)).apply();
    }

    @Override
    public void setCustomHeaders(String value) {
        if (value == null) {
            prefs.edit().remove(KEY_CUSTOM_HEADERS).apply();
            return;
        }
        prefs.edit().putString(KEY_CUSTOM_HEADERS, value).apply();
    }

    @Override
    public String getCustomHeaders() {
        return prefs.getString(KEY_CUSTOM_HEADERS, "");
    }

    @Override
    public boolean isDatabaseEnabled() {
        return prefs.getBoolean(KEY_DATABASE_ENABLED, false);
    }

    @Override
    public void setDatabaseEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_DATABASE_ENABLED, value).apply();
    }

    @Override
    public String getDatabasePath() {
        return prefs.getString(KEY_DATABASE_PATH, null);
    }

    @Override
    public void setDatabasePath(String value) {
        if (value == null) {
            prefs.edit().remove(KEY_DATABASE_PATH).apply();
            return;
        }
        prefs.edit().putString(KEY_DATABASE_PATH, value).apply();
    }

    @Override
    public boolean isAllowDatabaseCustomSqlRemoteApi() {
        return prefs.getBoolean(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, false);
    }

    @Override
    public boolean isAllowDatabaseTableDataEditingApi() {
        return prefs.getBoolean(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, false);
    }

    @Override
    public void setAllowDatabaseTableDataEditingApi(boolean value) {
        prefs.edit().putBoolean(KEY_ALLOW_DATABASE_TABLE_DATA_EDITING_API, value).apply();
    }

    @Override
    public void setAllowDatabaseCustomSqlRemoteApi(boolean value) {
        prefs.edit().putBoolean(KEY_ALLOW_DATABASE_CUSTOM_SQL_REMOTE_API, value).apply();
    }

    // Android-only settings. This is historically managed by Android App and not SHTTPS itself
    // This is kept for backward compatibility currently but should be moved out of SHTTPSConfig

    @Deprecated
    public boolean getAutostart() {
        return prefs.getBoolean(KEY_AUTOSTART, false);
    }

    @Deprecated
    public void setAutostart(boolean value) {
        prefs.edit().putBoolean(KEY_AUTOSTART, value).apply();
    }

    @Deprecated
    public boolean getRunningState() {
        return prefs.getBoolean(KEY_RUNNING_STATE, false);
    }

    @Deprecated
    public void setRunningState(boolean value) {
        prefs.edit().putBoolean(KEY_RUNNING_STATE, value).apply();
    }

    // End of Android-only settings
}
