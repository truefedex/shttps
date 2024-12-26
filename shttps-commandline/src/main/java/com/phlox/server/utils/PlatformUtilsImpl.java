package com.phlox.server.utils;

import static com.phlox.server.utils.docfile.RawDocumentFile.fileUriToFilePath;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

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
import java.util.Objects;
import java.util.Set;

public class PlatformUtilsImpl implements SHTTPSPlatformUtils {
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
    public ImageThumbnail getImageThumbnail(String uri) throws IOException {
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
        //try to get jar path and use folder www near it as root dir
        String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        File www = new File(jarPath).getParentFile().toPath().resolve("www").toFile();
        if (!www.exists()) {
            if (!www.mkdirs())
                throw new RuntimeException("Failed to create www folder near jar file: " + jarPath);
        }
        return DocumentFile.fromFile(www);
    }
}
