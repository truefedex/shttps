package com.phlox.simpleserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.phlox.server.platform.Base64;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.middleware.intentsenders.IntentSender;
import com.phlox.simpleserver.utils.KeyStoreCrypt;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.docfile.MediaStoreFileCollectionFile;
import com.phlox.simpleserver.utils.docfile.TreeDocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class SHTTPSConfigAndroid implements SHTTPSConfig {
    private final Context context;
    private final SharedPreferences prefs;

    private final KeyStoreCrypt keyStoreCrypt;

    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    private static final String ALIAS_CONFIG_PREFIX = "config_";

    private static final String KEY_ENABLE_INTENT_SENDING_HANDLERS = "enable_intent_sending_handlers";
    private static final String KEY_INTENT_SENDING_HANDLERS_URL_PATH_PREFIX = "intent_sending_handlers_url_path_prefix";
    private static final String KEY_INTENT_SENDERS = "intent_senders";
    private static final String KEY_KEEP_SCREEN_ON_WHILE_SHARING = "keep_screen_on_while_sharing";

    public SHTTPSConfigAndroid(Context context, String prefName, SHTTPSPlatformUtils platformUtils) {
        this.context = context;
        this.keyStoreCrypt = new KeyStoreCrypt(context);
        prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
    }

    @Override
    public DocumentFile getRootDir() {
        String rootDirStr = prefs.getString(KEY_ROOT_DIR, null);
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
    public KeyStore getTLSCert() {
        String encoded = prefs.getString(KEY_TLS_CERT, null);
        if (encoded == null) return null;
        try {
            byte[] cert = Base64.decode(encoded);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(cert), getTLSKeystorePassword().toCharArray());
            return ks;
        } catch (Exception e) {
            logger.e("Failed to get TLS cert", e);
            return null;
        }
    }

    @Override
    public void setTLSCert(byte[] value) {
        if (value == null) {
            prefs.edit().remove(KEY_TLS_CERT).remove(KEY_TLS_CERT_KEYSTORE_PASS).apply();
            return;
        }
        try {
            //do not encrypt the cert, it's already encrypted
            prefs.edit().putString(KEY_TLS_CERT, Base64.encodeToString(value)).apply();
        } catch (Exception e) {
            logger.e("Failed to set TLS cert", e);
        }
    }

    @Override
    public byte[] getTLSCertBytes() {
        // On Android the certificate raw bytes are stored Base64-encoded in shared preferences.
        String encoded = prefs.getString(KEY_TLS_CERT, null);
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return Base64.decode(encoded);
        } catch (Exception e) {
            logger.e("Failed to decode TLS cert bytes", e);
            return null;
        }
    }

    @Override
    public void installTLSCertBytes(byte[] bytes) {
        // On Android setTLSCert(byte[]) already takes raw keystore bytes and Base64-encodes them.
        setTLSCert(bytes);
    }

    // Android-specific settings. Not handled automatically by SHTTPSApp

    public boolean isEnableIntentSendingHandlers() {
        return getBoolean(KEY_ENABLE_INTENT_SENDING_HANDLERS, false);
    }

    public void setEnableIntentSendingHandlers(boolean value) {
        setBoolean(KEY_ENABLE_INTENT_SENDING_HANDLERS, value);
    }

    public String getIntentSendingHandlersUrlPathPrefix() {
        return getString(KEY_INTENT_SENDING_HANDLERS_URL_PATH_PREFIX, "/intent");
    }

    public void setIntentSendingHandlersUrlPathPrefix(String value) {
        setString(KEY_INTENT_SENDING_HANDLERS_URL_PATH_PREFIX, value);
    }

    public List<IntentSender> getIntentSenders() {
        String jsonArray = prefs.getString(KEY_INTENT_SENDERS, null);
        if (jsonArray == null) return null;
        List<IntentSender> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(jsonArray);
            for (int i = 0; i < array.length(); i++) {
                result.add(IntentSender.deserialize(array.getJSONObject(i)));
            }
            return result;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setIntentSenders(List<IntentSender> value) {
        if (value == null) {
            prefs.edit().remove(KEY_INTENT_SENDERS).apply();
            return;
        }
        JSONArray array = new JSONArray();
        for (IntentSender intentSender : value) {
            try {
                array.put(intentSender.serialize());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        prefs.edit().putString(KEY_INTENT_SENDERS, array.toString()).apply();
    }

    /**
     * Whether the device should be kept awake while a screen capture session exists. Android ends
     * screen capture as soon as the keyguard appears, so without this a shared screen only lasts
     * until the display times out. Off by default - it keeps the display powered.
     */
    public boolean isKeepScreenOnWhileSharing() {
        return getBoolean(KEY_KEEP_SCREEN_ON_WHILE_SHARING, false);
    }

    public void setKeepScreenOnWhileSharing(boolean value) {
        setBoolean(KEY_KEEP_SCREEN_ON_WHILE_SHARING, value);
    }

    //isRemoteControlEnabled/setRemoteControlEnabled moved up to SHTTPSConfig when the desktop
    //gained remote control. The key string went with them unchanged, so preferences written by
    //older versions of this app are still read.

    // General utility methods

    @Override
    public int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    @Override
    public void setInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    @Override
    public void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    @Override
    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    @Override
    public void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @Override
    public String getSecretString(String key, String defaultValue) {
        String encrypted = prefs.getString(key, null);
        if (encrypted == null) return defaultValue;
        try {
            if (keyStoreCrypt.getKeyStore().containsAlias(ALIAS_CONFIG_PREFIX + key)) {
                return keyStoreCrypt.decrypt(encrypted, ALIAS_CONFIG_PREFIX + key);
            } else {
                throw new Exception("Key alias not found");
            }
        } catch (Exception e) {
            logger.e("Failed to decrypt secret config value: " + key, e);
            return defaultValue;
        }
    }

    @Override
    public void setSecretString(String key, String value) {
        if (value == null) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            String encrypted = keyStoreCrypt.encrypt(value, ALIAS_CONFIG_PREFIX + key);
            prefs.edit().putString(key, encrypted).apply();
        } catch (Exception e) {
            logger.e("Failed to encrypt secret config value: " + key, e);
        }
    }

    @Override
    public JSONArray getJsonArray(String key, JSONArray defaultValue) {
        if (!prefs.contains(key)) {
            return defaultValue;
        }
        JSONArray array = new JSONArray();
        for (String s : prefs.getStringSet(key, new HashSet<>())) {
            try {
                array.put(new JSONObject(s));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return array;
    }

    @Override
    public void setJSONArray(String key, JSONArray value) {
        if (value == null) {
            prefs.edit().remove(key).apply();
            return;
        }
        HashSet<String> stringSet = new HashSet<>();
        for (int i = 0; i < value.length(); ++i) {
            JSONObject jObject = value.optJSONObject(i);
            if (jObject != null) {
                stringSet.add(jObject.toString());
            }
        }

        prefs.edit().putStringSet(key, stringSet).apply();
    }

}
