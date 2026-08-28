package com.phlox.server.utils;

import static com.phlox.server.utils.docfile.RawDocumentFile.fileUriToFilePath;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class PlatformUtilsImpl implements SHTTPSPlatformUtils {
    private static final boolean IS_LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    private final ThumbnailManager thumbnailManager = new ThumbnailManager(this);
    private KeyStore keyStore;

    @Override
    public String getMimeType(String fileUri) {
        String filePath = fileUriToFilePath(fileUri);
        try {
            return Files.probeContentType(Path.of(filePath));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public InputStream openInputStream(String fileUri) throws IOException {
        String filePath = fileUriToFilePath(fileUri);
        return Files.newInputStream(Path.of(filePath));
    }

    @Override
    public OutputStream openOutputStream(String fileUri) throws IOException {
        String filePath = fileUriToFilePath(fileUri);
        return Files.newOutputStream(Path.of(filePath));
    }

    @Override
    public ImageData getImageThumbnail(String uri) throws IOException {
        return thumbnailManager.getImageThumbnail(fileUriToFilePath(uri));
    }

    @Override
    public InputStream openAssetStream(String fileName) throws IOException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(fileName);
        if (is == null) {
            throw new FileNotFoundException("Asset not found: " + fileName);
        }
        return is;
    }

    @Override
    public long getAssetSize(String fileName) {
        try {
            InputStream is = openAssetStream(fileName);
            long size = is.available();
            is.close();
            return size;
        } catch (IOException e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public long getAssetLastModified(String fileName) {
        try {
            return new File(Objects.requireNonNull(getClass().getClassLoader().getResource(fileName)).getFile()).lastModified();
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    @Override
    public boolean isThumbnailsSupported() {
        return true;
    }

    @Override
    public Set<NetworkInterface> findInterfaces(String [] names) {
        if (names != null) {
            HashSet<NetworkInterface> interfaces = new HashSet<>();
            for (String name : names) {
                try {
                    NetworkInterface ni;
                    try {
                        int index = Integer.parseInt(name);
                        ni = NetworkInterface.getByIndex(index);
                    } catch (NumberFormatException e) {
                        ni = NetworkInterface.getByName(name);
                    }
                    if (ni != null) interfaces.add(ni);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return interfaces;
        } else {
            return null;
        }
    }

    @Override
    public DocumentFile getDefaultRootDir() {
        File www = new File(System.getProperty("user.home"), ".shttps" + File.separator + "www");
        if (!www.isDirectory() && !www.mkdirs()) {
            throw new RuntimeException("Failed to create default www folder: " + www.getAbsolutePath());
        }
        return DocumentFile.fromFile(www);
    }

    @Override
    public ImageData generateCaptchaImage(String code, int width, int height) {
        return CaptchaImageGenerator.generateCaptchaImage(code, width, height);
    }

    @Override
    public BatteryInfo getBatteryInfo() {
        //Windows and macOS have no equivalent we can reach without a native library or a subprocess,
        //so they report nothing and the status page leaves the battery card out entirely
        if (!IS_LINUX) return null;
        return new SysfsBatteryReader().read();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        try {
            DeviceInfo info = new DeviceInfo();

            readPhysicalMemory(info);
            info.deviceName = envDeviceName();

            if (IS_LINUX) {
                //the kernel hands the rest out as plain files
                LinuxDeviceInfoReader linux = new LinuxDeviceInfoReader();
                info.systemUptimeMillis = linux.readUptimeMillis();
                info.manufacturer = linux.readManufacturer();
                info.model = linux.readModel();
                if (info.deviceName == null) {
                    info.deviceName = linux.readHostName();
                }
                //MemAvailable counts the page cache the kernel would hand back, which is the number a
                //person means by "free memory"; the MXBean reports MemFree, which does not
                Long total = linux.readTotalRamBytes();
                Long available = linux.readAvailableRamBytes();
                if (total != null) info.totalRamBytes = total;
                if (available != null) info.availableRamBytes = available;
            }

            return info.hasAnyData() ? info : null;
        } catch (Throwable e) {
            //optional information on the status page - it must never fail the status response
            return null;
        }
    }

    /**
     * Physical memory, through the one JDK API that reports it.
     * <p>
     * This lives behind its own catch because it is the single thing here that can vanish with the
     * runtime rather than with the OS: the packaged app images are jlinked, so {@code jdk.management}
     * is only present because the build asks for it by name.
     */
    private static void readPhysicalMemory(DeviceInfo info) {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunBean = (com.sun.management.OperatingSystemMXBean) bean;
                long total = sunBean.getTotalMemorySize();
                long free = sunBean.getFreeMemorySize();
                if (total > 0) info.totalRamBytes = total;
                if (free > 0) info.availableRamBytes = free;
            }
        } catch (Throwable ignored) {
            //a runtime image built without jdk.management
        }
    }

    /**
     * The machine name from the environment. Deliberately not InetAddress.getLocalHost(), which is a
     * reverse DNS lookup that can block for seconds on a misconfigured network - and this runs on a
     * request thread.
     */
    private static String envDeviceName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null) {
            //usually a shell variable rather than an exported one, so expect this to be null on Linux
            name = System.getenv("HOSTNAME");
        }
        if (name == null) return null;
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    @Override
    public XmlSerializer newXMLSerializer() {
        return new org.kxml2.io.KXmlSerializer();
    }

    @Override
    public XmlPullParser newXMLPullParser() throws XmlPullParserException {
        return new org.kxml2.io.KXmlParser();
    }
}
