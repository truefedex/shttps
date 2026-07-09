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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;

public class RawDocumentFile extends DocumentFile {
    public static final String FILE_URI_PREFIX = "file:/";
    private File mFile;
    private FileAttributes mAttributes;

    /**
     * Metadata snapshot obtained with a single readAttributes() call and cached for the
     * lifetime of this instance (instances are short-lived, typically one request).
     * Mutating operations on this instance reset the cache, but external filesystem
     * changes made after the first metadata query are not observed.
     */
    private static class FileAttributes {
        boolean exists;
        boolean directory;   // like File.isDirectory(): attribute of the symlink target
        boolean regularFile; // like File.isFile(): attribute of the symlink target
        boolean symbolicLink;
        long length;
        long lastModified;
        long created;
    }

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

    private FileAttributes getAttributes() {
        FileAttributes attributes = mAttributes;
        if (attributes == null) {
            attributes = readAttributes(mFile);
            mAttributes = attributes;
        }
        return attributes;
    }

    private static FileAttributes readAttributes(File file) {
        final FileAttributes result = new FileAttributes();
        try {
            Path path = file.toPath();
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            result.exists = true;
            result.directory = attrs.isDirectory();
            result.regularFile = attrs.isRegularFile();
            result.length = attrs.size();
            result.lastModified = attrs.lastModifiedTime().toMillis();
            long created = attrs.creationTime().toMillis();
            // Filesystems without birth time support report 0 here
            result.created = created > 0 ? created : result.lastModified;
            result.symbolicLink = Files.isSymbolicLink(path);
        } catch (IOException | RuntimeException e) {
            // Missing file or broken symlink: keep File-compatible defaults
            // (exists=false, length=0, lastModified=0)
        }
        return result;
    }

    private void invalidateAttributes() {
        mAttributes = null;
    }

    @Override
    public DocumentFile createFile(String mimeType, String displayName) {
        final File target = new File(mFile, displayName);
        try {
            if (!target.createNewFile()) {
                throw new RuntimeException("Can not create file: " + displayName);
            }
            invalidateAttributes();
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
            invalidateAttributes();
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
        if (getAttributes().directory) {
            return null;
        } else {
            return getTypeForName(mFile.getName());
        }
    }

    @Override
    public boolean isDirectory() {
        final FileAttributes attributes = getAttributes();
        return attributes.directory && !attributes.symbolicLink;
    }

    @Override
    public boolean isFile() {
        return getAttributes().regularFile;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public long lastModified() {
        return getAttributes().lastModified;
    }

    @Override
    public long created() {
        return getAttributes().created;
    }

    @Override
    public long length() {
        return getAttributes().length;
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
        boolean result = mFile.delete();
        invalidateAttributes();
        return result;
    }

    @Override
    public boolean exists() {
        return getAttributes().exists;
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
            invalidateAttributes();
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
        return copyTo(destDir, mFile.getName());
    }

    @Override
    public boolean moveTo(DocumentFile destDir) {
        return moveTo(destDir, mFile.getName());
    }

    @Override
    public boolean copyTo(DocumentFile destDir, String destName) {
        boolean result = Utils.copyFileOrDir(mFile, new File(((RawDocumentFile)destDir).mFile, destName));
        ((RawDocumentFile)destDir).invalidateAttributes();
        return result;
    }

    @Override
    public boolean moveTo(DocumentFile destDir, String destName) {
        boolean result = Utils.moveFileOrDir(mFile, new File(((RawDocumentFile)destDir).mFile, destName));
        invalidateAttributes();
        ((RawDocumentFile)destDir).invalidateAttributes();
        return result;
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
        invalidateAttributes();
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
    public String getRelativePath(DocumentFile directOrIndirectChild) {
        if (!isDirectory() || directOrIndirectChild == null) {
            return null;
        }
        String baseUri = getUri();
        String fileUri = directOrIndirectChild.getUri();
        if (fileUri.startsWith(baseUri)) {
            String rawRelativeUriPart = fileUri.substring(baseUri.length());
            try {
                return URLDecoder.decode(
                        rawRelativeUriPart.replace("+", "%2B"),// so that the '+' in the name doesn't become a space
                        "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
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
