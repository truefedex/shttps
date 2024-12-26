package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.util.Set;

public interface SHTTPSPlatformUtils {
    String getMimeType(String uri);

    InputStream openInputStream(String uri) throws IOException;

    OutputStream openOutputStream(String fileUri) throws IOException;

    ImageThumbnail getImageThumbnail(String uri) throws IOException;

    InputStream openAssetStream(String fileName) throws IOException;
    long getAssetSize(String fileName);
    long getAssetLastModified(String fileName);

    boolean isThumbnailsSupported();

    Set<NetworkInterface> findInterfaces(String[] allowedInterfaces);

    DocumentFile getDefaultRootDir();

    class ImageThumbnail {
        public int width;
        public int height;
        public String mimeType;
        public byte[] data;
    }

    public static class UnsupportedImageFormatException extends IOException {
        public UnsupportedImageFormatException(String message) {
            super(message);
        }
    }

    public static class UnknownFormatException extends IOException {
        public UnknownFormatException(String message) {
            super(message);
        }
    }
}
