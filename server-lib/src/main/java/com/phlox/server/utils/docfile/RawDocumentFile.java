package com.phlox.server.utils.docfile;

import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;

public class RawDocumentFile extends DocumentFile {
    public static final String FILE_URI_PREFIX = "file:/";
    private File mFile;

    public static File getFile(DocumentFile document) {
        if (document instanceof RawDocumentFile) {
            return ((RawDocumentFile)document).getFile();
        }
        return null;
    }

    public RawDocumentFile(DocumentFile parent, File file) {
        super(parent);
        mFile = file;
    }

    @Override
    public DocumentFile createFile(String mimeType, String displayName) {
        final File target = new File(mFile, displayName);
        try {
            if (!target.createNewFile()) {
                throw new RuntimeException("Can not create file: " + displayName);
            }
            return new RawDocumentFile(this, target);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public DocumentFile createDirectory(String displayName) {
        final File target = new File(mFile, displayName);
        if (target.exists() && target.isDirectory()) {
            return null;
        }
        if (target.mkdirs()) {
            return new RawDocumentFile(this, target);
        } else {
            return null;
        }
    }

    @Override
    public String getUri() {
        return mFile.toURI().toString();
    }

    @Override
    public String getName() {
        return mFile.getName();
    }

    @Override
    
    public String getType() {
        if (mFile.isDirectory()) {
            return null;
        } else {
            return getTypeForName(mFile.getName());
        }
    }

    @Override
    public boolean isDirectory() {
        return mFile.isDirectory();
    }

    @Override
    public boolean isFile() {
        return mFile.isFile();
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public long lastModified() {
        return mFile.lastModified();
    }

    @Override
    public long length() {
        return mFile.length();
    }

    @Override
    public boolean canRead() {
        return mFile.canRead();
    }

    @Override
    public boolean canWrite() {
        return mFile.canWrite();
    }

    @Override
    public boolean delete() {
        deleteContents(mFile);
        return mFile.delete();
    }

    @Override
    public boolean exists() {
        return mFile.exists();
    }

    public interface ListFilesFallback {
        File[] onListFilesFailed(File dir);
    }
    public static ListFilesFallback listFilesFallback = null;

    @Override
    public DocumentFile[] listFiles() {
        final ArrayList<DocumentFile> results = new ArrayList<>();
        File[] files = mFile.listFiles();
        if (files == null) {
            if (listFilesFallback != null) {
                files = listFilesFallback.onListFilesFailed(mFile);
            } else {
                files = new File[0];
            }
        }
        for (File file : files) {
            results.add(new RawDocumentFile(this, file));
        }
        return results.toArray(new DocumentFile[0]);
    }

    @Override
    public boolean renameTo(String displayName) {
        final File target = new File(mFile.getParentFile(), displayName);
        if (mFile.renameTo(target)) {
            mFile = target;
            return true;
        } else {
            return false;
        }
    }

    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    private static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    public boolean copyTo(DocumentFile destDir) {
        return Utils.copyFileOrDir(mFile, new File(((RawDocumentFile)destDir).mFile, mFile.getName()));
    }

    @Override
    public boolean moveTo(DocumentFile destDir) {
        return Utils.moveFileOrDir(mFile, new File(((RawDocumentFile)destDir).mFile, mFile.getName()));
    }
    
    @Override
    public DocumentFile findFile(String displayName) {
        File file = new File(mFile, displayName);
        if (file.exists()) {
            return new RawDocumentFile(this, file);
        }
        return null;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return new FileInputStream(mFile);
    }

    @Override
    public OutputStream openOutputStream() throws FileNotFoundException {
        return new FileOutputStream(mFile);
    }

    public File getFile() {
        return mFile;
    }

    public static String fileUriToFilePath(String uriString) {
        try {
            URI uri = new java.net.URI(uriString);
            return new File(uri).getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("Invalid file uri: " + uriString, e);
        }
    }

    @Override
    public String getRelativePath(DocumentFile file) {
        if (!isDirectory() || file == null) {
            return null;
        }
        String baseUri = getUri();
        String fileUri = file.getUri();
        if (fileUri.startsWith(baseUri)) {
            return fileUri.substring(baseUri.length());
        }
        return null;
    }

    @Override
    public long getStorageSize() {
        return getFile().getTotalSpace();
    }

    @Override
    public long getStorageFreeSpace() {
        return getFile().getFreeSpace();
    }
}
