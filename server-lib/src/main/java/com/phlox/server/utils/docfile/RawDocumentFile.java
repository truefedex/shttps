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
import java.util.ArrayList;

public class RawDocumentFile extends DocumentFile {
    public static final String FILE_URI_PREFIX = "file://";
    private File mFile;

    public static File getFile(DocumentFile document) {
        if (document instanceof RawDocumentFile) {
            return ((RawDocumentFile)document).getFile();
        }
        return null;
    }

    RawDocumentFile(DocumentFile parent, File file) {
        super(parent);
        mFile = file;
    }

    @Override
    public DocumentFile createFile(String mimeType, String displayName) {
        final File target = new File(mFile, displayName);
        try {
            target.createNewFile();
            return new RawDocumentFile(this, target);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    
    public DocumentFile createDirectory(String displayName) {
        final File target = new File(mFile, displayName);
        if (target.isDirectory() || target.mkdir()) {
            return new RawDocumentFile(this, target);
        } else {
            return null;
        }
    }

    @Override
    public String getUri() {
        return FILE_URI_PREFIX + mFile.getAbsolutePath();
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

    @Override
    public DocumentFile[] listFiles() {
        final ArrayList<DocumentFile> results = new ArrayList<DocumentFile>();
        final File[] files = mFile.listFiles();
        if (files != null) {
            for (File file : files) {
                results.add(new RawDocumentFile(this, file));
            }
        }
        return results.toArray(new DocumentFile[results.size()]);
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
    public boolean storageHasEnoughFreeSpaceFor(long contentLength) {
        return mFile.getFreeSpace() > contentLength;
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

    public static String fileUriToFilePath(String uri) {
        if (!uri.startsWith(FILE_URI_PREFIX)) {
            throw new IllegalArgumentException("Invalid file uri: " + uri);
        }
        return uri.substring(FILE_URI_PREFIX.length());
    }
}
