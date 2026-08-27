package com.phlox.simpleserver.utils;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.platform.MimeTypeMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class PlatformUtilsAndroid implements SHTTPSPlatformUtils {
    private final Context ctx;
    private KeyStore keyStore;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public PlatformUtilsAndroid(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getMimeType(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return ctx.getContentResolver().getType(uri);
        } else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            int lastIndexOf = uriStr.lastIndexOf(".");
            if (lastIndexOf == -1) {
                return null; // empty extension
            }
            String ext = uriStr.substring(lastIndexOf + 1);
            return MimeTypeMap.getInstance().getMimeTypeFromExtension(ext);
        } else return null;
    }

    @Override
    public InputStream openInputStream(String uri) throws FileNotFoundException {
        return ctx.getContentResolver().openInputStream(Uri.parse(uri));
    }

    @Override
    public OutputStream openOutputStream(String fileUri) throws FileNotFoundException {
        return Objects.requireNonNull(ctx.getContentResolver().openOutputStream(Uri.parse(fileUri), "wt"));
    }

    @SuppressLint("NewApi")//for ImageDecoder.DecodeException
    @Override
    public ImageData getImageThumbnail(String imageUriStr) throws FileNotFoundException, IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            throw new UnsupportedOperationException("Not supported on Android < 10");

        Size thumbSize;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            thumbSize = MediaStore.Images.Thumbnails.getKindSize(MediaStore.Images.Thumbnails.MINI_KIND);
        } else {
            thumbSize = new Size(512, 384);
        }

        ContentResolver contentResolver = ctx.getContentResolver();
        String type = getMimeType(imageUriStr);
        if (type == null) {
            throw new FileNotFoundException();
        }
        if (type.startsWith("image/") || type.startsWith("video/")) {
            Bitmap thumb;
            Uri uri = Uri.parse(imageUriStr);
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                File file = new File(Objects.requireNonNull(uri.getPath()));
                if (type.startsWith("image/")) {
                    thumb = ThumbnailUtils.createImageThumbnail(file, thumbSize, null);
                } else {
                    thumb = ThumbnailUtils.createVideoThumbnail(file, thumbSize, null);
                }
            } else if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                try {
                    thumb = contentResolver.loadThumbnail(uri, thumbSize, null);
                } catch (ImageDecoder.DecodeException e) {
                    logger.e("Failed to load thumbnail using ContentResolver for " + uri + ": " + e.getMessage());
                    throw new UnsupportedImageFormatException(e.getMessage());
                }
            } else {
                throw new FileNotFoundException();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Bitmap.CompressFormat format;
            int quality;
            if ("image/png".equals(type)) {
                quality = 100;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format = Bitmap.CompressFormat.WEBP_LOSSLESS;
                } else {
                    format = Bitmap.CompressFormat.WEBP;
                }
            } else {
                quality = 95;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format = Bitmap.CompressFormat.WEBP_LOSSY;
                } else {
                    format = Bitmap.CompressFormat.WEBP;
                }
            }
            thumb.compress(format, quality, baos);

            ImageData imageThumbnail = new ImageData();
            imageThumbnail.width = thumb.getWidth();
            imageThumbnail.height = thumb.getHeight();
            imageThumbnail.mimeType = "image/webp";
            imageThumbnail.data = baos.toByteArray();
            return imageThumbnail;
        }
        throw new FileNotFoundException();
    }

    @Override
    public boolean isThumbnailsSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    @Override
    public InputStream openAssetStream(String fileName) throws IOException {
        return ctx.getResources().getAssets().open(fileName);
    }

    @Override
    public long getAssetSize(String fileName) {
        try {
            InputStream is = openAssetStream(fileName);
            long size = is.available();
            is.close();
            return size;
        } catch (IOException e) {
            logger.e("Failed to get asset size for " + fileName + ": " + e.getMessage());
            return -1;
        }
    }

    @Override
    public long getAssetLastModified(String fileName) {
        //application package files are not modified after installation so return installation time
        PackageManager pm = ctx.getPackageManager();
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(ctx.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
        long firstInstallTime = pi.firstInstallTime;
        long lastUpdateTime = pi.lastUpdateTime;
        return Math.max(firstInstallTime, lastUpdateTime);
    }

    /**
     * Maps array of interface indexes (written by this app since KITKAT) or interface names
     * (written by older versions, by the desktop/command-line builds and by hand-edited configs)
     * to NetworkInterface objects. Both forms are accepted so that an allow-list stays meaningful
     * when a config is carried over between versions or platforms; resolving nothing here would
     * leave the server up but refusing every connection.
     */
    @Override
    public Set<NetworkInterface> findInterfaces(String [] names) {
        if (names != null) {
            HashSet<NetworkInterface> interfaces = new HashSet<>();
            for (String name : names) {
                try {
                    NetworkInterface ni;
                    try {
                        ni = NetworkInterface.getByIndex(Integer.parseInt(name));
                    } catch (NumberFormatException e) {
                        ni = NetworkInterface.getByName(name);
                    }
                    if (ni != null) interfaces.add(ni);
                } catch (Exception e) {
                    logger.e("Failed to find network interface " + name + ": " + e.getMessage());
                }
            }
            return interfaces;
        } else {
            return null;
        }
    }

    @Override
    public DocumentFile getDefaultRootDir() {
        File externalFilesDir = ctx.getExternalFilesDir(null);
        File rootDir = externalFilesDir != null ?
                externalFilesDir : ctx.getFilesDir();
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return DocumentFile.fromFile(rootDir);
    }

    @Override
    public ImageData generateCaptchaImage(String code, int width, int height) {
        return CaptchaImageGenerator.generateCaptchaImage(code, width, height);
    }

    @Override
    public XmlSerializer newXMLSerializer() {
        return android.util.Xml.newSerializer();
    }

    @Override
    public XmlPullParser newXMLPullParser() throws XmlPullParserException {
        return android.util.Xml.newPullParser();
    }

    @Override
    public BatteryInfo getBatteryInfo() {
        try {
            //a null receiver just reads the sticky broadcast - nothing is registered and nothing has to be
            //unregistered. Needs no permission, same for the BatteryManager properties below.
            Intent sticky = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky == null) {
                //happens on some emulators and ROMs
                return null;
            }
            if (!sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
                //no battery in this machine at all - show no card rather than a card full of zeroes
                return null;
            }

            BatteryInfo info = new BatteryInfo();

            int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                //the scale is not always 100
                info.levelPercent = Math.min(100, level * 100 / scale);
            }

            //tenths of a degree Celsius. A device without a battery thermistor reports 0, which would
            //otherwise render as a perfectly plausible looking "0.0 C"
            int temperature = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            if (temperature != 0 && temperature > -500 && temperature < 1500) {
                info.temperatureCelsius = temperature / 10f;
            }

            info.status = mapBatteryStatus(sticky.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN));
            info.health = mapBatteryHealth(sticky.getIntExtra(BatteryManager.EXTRA_HEALTH,
                    BatteryManager.BATTERY_HEALTH_UNKNOWN));
            info.powerSource = mapPowerSource(sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));

            int voltage = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (voltage > 0) {
                //documented as millivolts, but a few devices report microvolts
                info.voltageMillivolts = voltage > 100000 ? voltage / 1000 : voltage;
            }

            info.technology = trimToNull(sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));

            BatteryManager batteryManager = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (batteryManager != null) {
                //microampere-hours remaining. Devices that do not implement it answer with 0 or one
                //of the int extremes, and a few report the wrong unit altogether - so the result has to
                //land somewhere a battery plausibly could, the same test the design capacity gets
                int chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (chargeCounter > 0) {
                    int mah = chargeCounter / 1000;
                    if (mah >= 50 && mah <= 100000) {
                        info.chargeCounterMah = mah;
                    }
                }
            }

            info.capacityMah = readDesignCapacityMah();

            return info.hasAnyData() ? info : null;
        } catch (Throwable e) {
            //this is optional information on the status page - it must never fail the status response
            logger.w("Failed to read battery info", e);
            return null;
        }
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        try {
            DeviceInfo info = new DeviceInfo();

            info.manufacturer = trimToNull(Build.MANUFACTURER);
            info.model = trimToNull(Build.MODEL);
            //the os.name property says "Linux" here, which is true and useless on a phone
            info.osName = "Android";
            info.osRelease = trimToNull(Build.VERSION.RELEASE);
            info.apiLevel = Build.VERSION.SDK_INT;

            //the name the user gave the phone. Often unset, and readable without any permission
            try {
                info.deviceName = trimToNull(
                        Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME));
            } catch (Throwable ignored) {
                //not every ROM has the setting
            }

            ActivityManager activityManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                if (memoryInfo.totalMem > 0) {
                    info.totalRamBytes = memoryInfo.totalMem;
                }
                if (memoryInfo.availMem > 0) {
                    info.availableRamBytes = memoryInfo.availMem;
                }
            }

            //elapsedRealtime and not uptimeMillis: the latter stops while the device sleeps, so on a
            //phone it would report a fraction of the real uptime
            info.systemUptimeMillis = SystemClock.elapsedRealtime();

            return info.hasAnyData() ? info : null;
        } catch (Throwable e) {
            logger.w("Failed to read device info", e);
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Design capacity of the battery, in mAh, or null when we cannot get it.
     * <p>
     * There is no public API for this. PowerProfile is a blocked non-SDK interface, so on most current
     * devices this simply fails and the row is left out - which is the intended outcome. The obvious
     * alternative, dividing the charge counter by the charge level, is deliberately not used: it swings
     * wildly at low levels and means nothing at zero, and a wrong mAh figure is worse than a missing one.
     */
    private Integer readDesignCapacityMah() {
        try {
            Class<?> powerProfileClass = Class.forName("com.android.internal.os.PowerProfile");
            Object powerProfile = powerProfileClass.getConstructor(Context.class).newInstance(ctx);
            Object capacity = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile);
            if (capacity instanceof Number) {
                double mah = ((Number) capacity).doubleValue();
                //a device that has the class but no profile entry answers 0
                if (mah >= 100 && mah <= 100000) {
                    return (int) Math.round(mah);
                }
            }
        } catch (Throwable ignored) {
            //expected on any device that enforces the non-SDK interface restrictions
        }
        return null;
    }

    private static String mapBatteryStatus(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return BatteryInfo.STATUS_CHARGING;
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return BatteryInfo.STATUS_DISCHARGING;
            case BatteryManager.BATTERY_STATUS_FULL:
                return BatteryInfo.STATUS_FULL;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return BatteryInfo.STATUS_NOT_CHARGING;
            default:
                return null;
        }
    }

    private static String mapBatteryHealth(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return BatteryInfo.HEALTH_GOOD;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return BatteryInfo.HEALTH_OVERHEAT;
            case BatteryManager.BATTERY_HEALTH_COLD:
                return BatteryInfo.HEALTH_COLD;
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return BatteryInfo.HEALTH_DEAD;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return BatteryInfo.HEALTH_OVER_VOLTAGE;
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return BatteryInfo.HEALTH_UNSPECIFIED_FAILURE;
            default:
                return null;
        }
    }

    private static String mapPowerSource(int plugged) {
        switch (plugged) {
            case 0:
                return BatteryInfo.POWER_SOURCE_NONE;
            case BatteryManager.BATTERY_PLUGGED_AC:
                return BatteryInfo.POWER_SOURCE_AC;
            case BatteryManager.BATTERY_PLUGGED_USB:
                return BatteryInfo.POWER_SOURCE_USB;
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return BatteryInfo.POWER_SOURCE_WIRELESS;
            default:
                return null;
        }
    }
}
