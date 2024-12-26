package com.phlox.simpleserver.utils;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import android.webkit.MimeTypeMap;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class PlatformUtilsAndroid implements SHTTPSPlatformUtils {
    private final Context ctx;
    private KeyStore keyStore;
    private SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public PlatformUtilsAndroid(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getMimeType(String uriStr) {
        Uri uri = Uri.parse(uriStr);
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return ctx.getContentResolver().getType(uri);
        } else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    MimeTypeMap.getFileExtensionFromUrl(uri.toString()).toLowerCase(Locale.US));
        } else return null;
    }

    @Override
    public InputStream openInputStream(String uri) throws FileNotFoundException {
        return ctx.getContentResolver().openInputStream(Uri.parse(uri));
    }

    @Override
    public OutputStream openOutputStream(String fileUri) throws FileNotFoundException {
        return Objects.requireNonNull(ctx.getContentResolver().openOutputStream(Uri.parse(fileUri)));
    }

    @SuppressLint("NewApi")//for ImageDecoder.DecodeException
    @Override
    public ImageThumbnail getImageThumbnail(String imageUriStr) throws FileNotFoundException, IOException {
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

            ImageThumbnail imageThumbnail = new ImageThumbnail();
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
            e.printStackTrace();
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
     * Maps array of interface names (or after KITKAT interface indexes) to NetworkInterface objects
     */
    @Override
    public Set<NetworkInterface> findInterfaces(String [] names) {
        if (names != null) {
            HashSet<NetworkInterface> interfaces = new HashSet<>();
            for (String name : names) {
                try {
                    NetworkInterface ni;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        ni = NetworkInterface.getByIndex(Integer.parseInt(name));
                    } else {
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
        File externalFilesDir = ctx.getExternalFilesDir(null);
        File rootDir = externalFilesDir != null ?
                externalFilesDir : ctx.getFilesDir();
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return DocumentFile.fromFile(rootDir);
    }
}
