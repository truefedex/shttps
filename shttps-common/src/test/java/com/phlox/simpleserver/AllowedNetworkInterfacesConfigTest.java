package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.utils.docfile.DocumentFile;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

/**
 * The allowed-interfaces list is a comma-separated string in which every entry is either a
 * numeric interface index or an interface name — both forms are resolved by the platform
 * implementations of {@code SHTTPSPlatformUtils.findInterfaces()}. Since this list is routinely
 * hand-edited and carried between platforms, these tests pin down what the config layer hands to
 * the resolvers: entries stay verbatim (never coerced to numbers) and surrounding whitespace is
 * not passed along, because an entry that resolves to nothing leaves the server running while
 * refusing every connection.
 */
public class AllowedNetworkInterfacesConfigTest {

    @Test
    public void interfaceNamesSurviveARoundTrip() {
        SHTTPSConfig config = new InMemoryConfig();
        config.setAllowedNetworkInterfaces(new String[]{"eth0", "wlan0"});

        assertArrayEquals(new String[]{"eth0", "wlan0"}, config.getAllowedNetworkInterfaces());
    }

    @Test
    public void indicesAndNamesCanBeMixed() {
        SHTTPSConfig config = new InMemoryConfig();
        config.setAllowedNetworkInterfaces(new String[]{"3", "eth0"});

        assertArrayEquals(new String[]{"3", "eth0"}, config.getAllowedNetworkInterfaces());
    }

    @Test
    public void handEditedSpacingIsTrimmed() {
        InMemoryConfig config = new InMemoryConfig();
        config.setString(SHTTPSConfig.KEY_ALLOWED_NETWORK_INTERFACES, "3, eth0 ,  wlan0");

        assertArrayEquals(new String[]{"3", "eth0", "wlan0"}, config.getAllowedNetworkInterfaces());
    }

    @Test
    public void noRestrictionIsReportedAsNull() {
        InMemoryConfig config = new InMemoryConfig();
        assertNull(config.getAllowedNetworkInterfaces(), "unset list means every interface is allowed");

        config.setAllowedNetworkInterfaces(null);
        assertNull(config.getAllowedNetworkInterfaces());

        config.setAllowedNetworkInterfaces(new String[0]);
        assertNull(config.getAllowedNetworkInterfaces(), "an empty list must not read back as an empty restriction");

        // A list of nothing but separators would otherwise produce blank entries that match no
        // interface at all, which reads as "restrict to nothing" rather than "do not restrict".
        config.setString(SHTTPSConfig.KEY_ALLOWED_NETWORK_INTERFACES, " , ");
        assertNull(config.getAllowedNetworkInterfaces());
    }

    /**
     * Minimal backing store exercising only the primitives {@link SHTTPSConfig}'s default methods
     * are built on.
     */
    private static class InMemoryConfig implements SHTTPSConfig {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Integer) value;
        }

        @Override
        public void setInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Boolean) value;
        }

        @Override
        public void setBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (String) value;
        }

        @Override
        public void setString(String key, String value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        @Override
        public JSONArray getJsonArray(String key, JSONArray defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (JSONArray) value;
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
}
