package com.phlox.simpleserver.utils;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.utils.docfile.DocumentFile;

import org.junit.jupiter.api.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.util.Set;

/**
 * Battery and host reporting arrived as {@code default} methods so that a platform implementation
 * written before they existed - an app embedding {@code shttps-android-common}, or any host outside
 * this build - keeps compiling and simply reports neither.
 * <p>
 * {@link BareBonesPlatform} below is such an implementation: it overrides exactly the methods the
 * interface required beforehand and nothing else. If someone makes {@code getBatteryInfo} or
 * {@code getDeviceInfo} abstract, this class stops compiling, which is the point of it.
 */
public class SHTTPSPlatformUtilsDefaultsTest {

    /** Implements exactly what {@code SHTTPSPlatformUtils} required before either was added. */
    private static final class BareBonesPlatform implements SHTTPSPlatformUtils {
        @Override
        public String getMimeType(String uri) {
            return null;
        }

        @Override
        public InputStream openInputStream(String uri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public OutputStream openOutputStream(String fileUri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public ImageData getImageThumbnail(String uri) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public InputStream openAssetStream(String fileName) throws IOException {
            throw new IOException("not supported");
        }

        @Override
        public long getAssetSize(String fileName) {
            return -1;
        }

        @Override
        public long getAssetLastModified(String fileName) {
            return -1;
        }

        @Override
        public boolean isThumbnailsSupported() {
            return false;
        }

        @Override
        public Set<NetworkInterface> findInterfaces(String[] allowedInterfaces) {
            return null;
        }

        @Override
        public DocumentFile getDefaultRootDir() {
            return null;
        }

        @Override
        public ImageData generateCaptchaImage(String code, int width, int height) {
            return null;
        }

        @Override
        public XmlSerializer newXMLSerializer() {
            return null;
        }

        @Override
        public XmlPullParser newXMLPullParser() {
            return null;
        }
    }

    @Test
    public void aPlatformThatKnowsNothingAboutBatteriesReportsNone() {
        //null is what makes StatusRequestHandler leave the whole battery object out of the response,
        //so the status page renders no battery card at all
        assertNull(new BareBonesPlatform().getBatteryInfo());
    }

    @Test
    public void aPlatformThatCannotIntrospectItsHostReportsNothing() {
        //the Device card still renders from the system scope; it just shows no model and no RAM
        assertNull(new BareBonesPlatform().getDeviceInfo());
    }

    @Test
    public void aFreshDeviceInfoHoldsNoDataYet() {
        SHTTPSPlatformUtils.DeviceInfo info = new SHTTPSPlatformUtils.DeviceInfo();
        org.junit.jupiter.api.Assertions.assertFalse(info.hasAnyData());
        info.model = "Pixel 8";
        org.junit.jupiter.api.Assertions.assertTrue(info.hasAnyData());
    }

    @Test
    public void aFreshBatteryInfoHoldsNoDataYet() {
        //hasAnyData is the guard that stops an all-null read from producing an empty card
        SHTTPSPlatformUtils.BatteryInfo info = new SHTTPSPlatformUtils.BatteryInfo();
        org.junit.jupiter.api.Assertions.assertFalse(info.hasAnyData());
        info.levelPercent = 52;
        org.junit.jupiter.api.Assertions.assertTrue(info.hasAnyData());
    }
}
