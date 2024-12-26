package com.phlox.server.utils;

import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;

public class ThumbnailManager {
    private static final String THUMBNAILS_DIR = "thumbnails";
    private static final int THUMBNAIL_WIDTH = 512;
    private static final int THUMBNAIL_HEIGHT = 384;

    private final PlatformUtilsImpl platformUtils;
    private final String thumbnailsDir;

    public ThumbnailManager(PlatformUtilsImpl platformUtils) {
        this.platformUtils = platformUtils;
        this.thumbnailsDir = System.getProperty("java.io.tmpdir") + File.separator + THUMBNAILS_DIR;
    }

    public SHTTPSPlatformUtils.ImageThumbnail getImageThumbnail(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        String mimeType = platformUtils.getMimeType(RawDocumentFile.FILE_URI_PREFIX + filePath);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new SHTTPSPlatformUtils.UnknownFormatException("Unsupported video format: " + mimeType);
        }
        //make thumbnail name from hash of file name and file modification time
        String fileExt;
        if (mimeType.equals("image/png")) {
            fileExt = ".png";
        } else {
            fileExt = ".jpg";
        }
        String thumbnailFilePath = thumbnailsDir + File.separator + com.phlox.simpleserver.utils.Utils.md5(filePath + file.lastModified()) + fileExt;
        File thumbnailFile = new File(thumbnailFilePath);
        if (thumbnailFile.exists()) {
            return loadThumbnail(thumbnailFile);
        }
        //create thumbnail
        if (!thumbnailFile.getParentFile().exists()) {
            if (!thumbnailFile.getParentFile().mkdirs()) {
                throw new IOException("Failed to create thumbnail directory: " + thumbnailFile.getParentFile().getAbsolutePath());
            }
        }
        BufferedImage source;
        try {
            source = javax.imageio.ImageIO.read(file);
        } catch (IOException e) {
            throw new SHTTPSPlatformUtils.UnsupportedImageFormatException("Unsupported image format: " + e.getMessage());
        }
        BufferedImage thumbnail = scale(source, Math.min(THUMBNAIL_WIDTH / (double) source.getWidth(), THUMBNAIL_HEIGHT / (double) source.getHeight()));
        if (mimeType.equals("image/png")) {
            javax.imageio.ImageIO.write(thumbnail, "png", thumbnailFile);
        } else {
            javax.imageio.ImageIO.write(thumbnail, "jpg", thumbnailFile);
        }
        return loadThumbnail(thumbnailFile);
    }

    private SHTTPSPlatformUtils.ImageThumbnail loadThumbnail(File thumbnailFile) throws IOException {
        SHTTPSPlatformUtils.ImageThumbnail thumbnail = new SHTTPSPlatformUtils.ImageThumbnail();
        thumbnail.mimeType = platformUtils.getMimeType(RawDocumentFile.FILE_URI_PREFIX + thumbnailFile.getAbsolutePath());
        thumbnail.data = Files.readAllBytes(thumbnailFile.toPath());
        return thumbnail;
    }

    private BufferedImage scale(BufferedImage source, double ratio) {
        int w = (int) (source.getWidth() * ratio);
        int h = (int) (source.getHeight() * ratio);
        BufferedImage bi = getCompatibleImage(w, h);
        Graphics2D g2d = bi.createGraphics();
        double xScale = (double) w / source.getWidth();
        double yScale = (double) h / source.getHeight();
        AffineTransform at = AffineTransform.getScaleInstance(xScale,yScale);
        g2d.drawRenderedImage(source, at);
        g2d.dispose();
        return bi;
    }

    private BufferedImage getCompatibleImage(int w, int h) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice gd = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = gd.getDefaultConfiguration();
        return gc.createCompatibleImage(w, h);
    }
}
