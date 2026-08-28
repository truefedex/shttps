package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;

import org.jetbrains.annotations.Nullable;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.util.Set;

public interface SHTTPSPlatformUtils {
    String getMimeType(String uri);

    InputStream openInputStream(String uri) throws IOException;

    OutputStream openOutputStream(String fileUri) throws IOException;

    ImageData getImageThumbnail(String uri) throws IOException;

    InputStream openAssetStream(String fileName) throws IOException;
    long getAssetSize(String fileName);
    long getAssetLastModified(String fileName);

    boolean isThumbnailsSupported();

    Set<NetworkInterface> findInterfaces(String[] allowedInterfaces);

    DocumentFile getDefaultRootDir();

    ImageData generateCaptchaImage(String code, int width, int height);

    XmlSerializer newXMLSerializer();
    XmlPullParser newXMLPullParser() throws XmlPullParserException;

    /**
     * Battery state of the machine this server runs on, or null when it has no battery, or when this
     * platform has no way to read one. Implementations must never throw: this is optional information
     * displayed on the status page, and a failure here must not take the rest of the status response
     * down with it.
     * <p>
     * Defaulted so that adding it does not break platform implementations living outside this build.
     */
    default @Nullable BatteryInfo getBatteryInfo() {
        return null;
    }

    /**
     * The machine this server runs on - what it is, how much memory it has, how long it has been up -
     * or null when this platform can tell us none of it. Same contract as {@link #getBatteryInfo()}:
     * optional information for the status page, so implementations must never throw.
     */
    default @Nullable DeviceInfo getDeviceInfo() {
        return null;
    }

    class ImageData {
        public int width;
        public int height;
        public String mimeType;
        public byte[] data;
    }

    /**
     * A snapshot of the host battery. Every field is boxed and every field is optional - null means
     * "this machine cannot tell us", not "zero". Callers put them into JSON as-is: org.json drops a key
     * whose value is null, so an unavailable field simply never reaches the browser.
     */
    class BatteryInfo {
        public static final String STATUS_CHARGING = "charging";
        public static final String STATUS_DISCHARGING = "discharging";
        public static final String STATUS_FULL = "full";
        public static final String STATUS_NOT_CHARGING = "not_charging";

        public static final String HEALTH_GOOD = "good";
        public static final String HEALTH_OVERHEAT = "overheat";
        public static final String HEALTH_COLD = "cold";
        public static final String HEALTH_DEAD = "dead";
        public static final String HEALTH_OVER_VOLTAGE = "over_voltage";
        public static final String HEALTH_UNSPECIFIED_FAILURE = "unspecified_failure";

        public static final String POWER_SOURCE_NONE = "none";
        public static final String POWER_SOURCE_AC = "ac";
        public static final String POWER_SOURCE_USB = "usb";
        public static final String POWER_SOURCE_WIRELESS = "wireless";

        /** Charge level, 0..100. */
        public @Nullable Integer levelPercent;
        public @Nullable Float temperatureCelsius;
        /** One of the STATUS_* constants. */
        public @Nullable String status;
        /** One of the HEALTH_* constants. */
        public @Nullable String health;
        /** Design capacity of a full battery, in mAh. Not to be confused with {@link #chargeCounterMah}. */
        public @Nullable Integer capacityMah;
        /** Charge remaining right now, in mAh. */
        public @Nullable Integer chargeCounterMah;
        public @Nullable Integer voltageMillivolts;
        /** e.g. "Li-ion". */
        public @Nullable String technology;
        /** One of the POWER_SOURCE_* constants. */
        public @Nullable String powerSource;

        /** True when there is at least one thing worth showing. */
        public boolean hasAnyData() {
            return levelPercent != null || temperatureCelsius != null || status != null || health != null
                    || capacityMah != null || chargeCounterMah != null || voltageMillivolts != null
                    || technology != null || powerSource != null;
        }
    }

    /**
     * What the host machine is and how it is doing. Every field is boxed and optional, with the same
     * meaning as in {@link BatteryInfo}: null is "this machine cannot tell us", never zero.
     */
    class DeviceInfo {
        /** The name the machine goes by - the phone's name, or the computer name. */
        public @Nullable String deviceName;
        public @Nullable String manufacturer;
        public @Nullable String model;
        /**
         * The user facing OS name, where it differs from the {@code os.name} system property - that
         * property says "Linux" on a phone, which is true and useless.
         */
        public @Nullable String osName;
        /**
         * The user facing OS version, where it differs from the {@code os.version} system property.
         * On Android that property is the kernel version, which is not what anyone means by it.
         */
        public @Nullable String osRelease;
        /** Android API level. Null everywhere else. */
        public @Nullable Integer apiLevel;
        /** Physical memory, not the JVM heap. */
        public @Nullable Long totalRamBytes;
        /** Memory that could still be handed out, which is not the same as memory that is unused. */
        public @Nullable Long availableRamBytes;
        /** How long the machine itself has been up, as opposed to this server. */
        public @Nullable Long systemUptimeMillis;

        /** True when there is at least one thing worth showing. */
        public boolean hasAnyData() {
            return deviceName != null || manufacturer != null || model != null || osName != null
                    || osRelease != null || apiLevel != null || totalRamBytes != null
                    || availableRamBytes != null || systemUptimeMillis != null;
        }
    }

    class UnsupportedImageFormatException extends IOException {
        public UnsupportedImageFormatException(String message) {
            super(message);
        }
    }

    class UnknownFormatException extends IOException {
        public UnknownFormatException(String message) {
            super(message);
        }
    }
}
