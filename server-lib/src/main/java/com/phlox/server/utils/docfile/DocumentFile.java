package com.phlox.server.utils.docfile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction over a raw {@link File}, a {android.provider.DocumentsProvider} or
 * other file or document systems.
 */
public abstract class DocumentFile {
    static final String TAG = "DocumentFile";

    
    private final DocumentFile mParent;

    public DocumentFile(DocumentFile parent) {
        mParent = parent;
    }
    
    public static DocumentFile fromFile(File file) {
        return new RawDocumentFile(null, file);
    }
    
    public abstract DocumentFile createFile(String mimeType, String displayName);
    
    public abstract DocumentFile createDirectory(String displayName);
    
    public abstract String getUri();
    
    public abstract String getName();
    
    public abstract String getType();
    
    public DocumentFile getParentFile() {
        return mParent;
    }

    public abstract boolean isDirectory();

    public abstract boolean isFile();

    public abstract boolean isVirtual();

    public abstract long lastModified();

    public abstract long length();

    public abstract boolean canRead();

    public abstract boolean canWrite();

    public abstract boolean delete();

    public abstract boolean exists();
    
    public abstract DocumentFile[] listFiles();
    
    public DocumentFile findFile(String displayName) {
        for (DocumentFile doc : listFiles()) {
            if (displayName.equals(doc.getName())) {
                return doc;
            }
        }
        return null;
    }
    public abstract boolean renameTo(String displayName);

    public abstract boolean copyTo(DocumentFile destDir);

    public abstract boolean moveTo(DocumentFile destDir);

    public abstract boolean storageHasEnoughFreeSpaceFor(long contentLength);

    public abstract InputStream openInputStream() throws IOException;

    public InputStream openInputStream(long start) throws IOException {
        InputStream is = openInputStream();
        if (is == null) {
            return null;
        }
        try {
            if (start > 0) {
                if (is.skip(start) != start) {
                    is.close();
                    throw new IOException("Couldn't seek to " + start);
                }
            }
        } catch (IOException e) {
            is.close();
            throw e;
        }
        return is;
    }

    public abstract OutputStream openOutputStream() throws FileNotFoundException;

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
}
