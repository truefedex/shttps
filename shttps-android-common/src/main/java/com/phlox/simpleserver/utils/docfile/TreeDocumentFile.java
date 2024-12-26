package com.phlox.simpleserver.utils.docfile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import com.phlox.server.utils.docfile.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;


public class TreeDocumentFile extends DocumentFile {
    private final Context mContext;
    private Uri mUri;
    private String mName;
    private String mType;
    private Long mLastModified;
    private Long mLength;

    public static DocumentFile fromTreeUri(Context context, Uri treeUri) {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        if (DocumentsContract.isDocumentUri(context, treeUri)) {
            documentId = DocumentsContract.getDocumentId(treeUri);
        }
        return new TreeDocumentFile(null, context,
                DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        documentId));
    }

    TreeDocumentFile(DocumentFile parent, Context context, Uri uri) {
        super(parent);
        mContext = context;
        mUri = uri;
    }

    public TreeDocumentFile(DocumentFile parent, Context context, Uri uri, String name,
                            String type, long lastModified, long length) {
        super(parent);
        this.mContext = context;
        this.mUri = uri;
        this.mName = name;
        this.mType = type;
        this.mLastModified = lastModified;
        this.mLength = length;
    }

    @Override
    public DocumentFile createFile(String mimeType, String displayName) {
        final Uri result = TreeDocumentFile.createFile(mContext, mUri, mimeType, displayName);
        return (result != null) ? new TreeDocumentFile(this, mContext, result) : null;
    }

    private static Uri createFile(Context context, Uri self, String mimeType,
                                  String displayName) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 21");
        }
        int pos = displayName.lastIndexOf(".");
        if (pos > 0 && pos < (displayName.length() - 1)) { // If '.' is not the first or last character.
            displayName = displayName.substring(0, pos);
        }
        try {
            return DocumentsContract.createDocument(context.getContentResolver(), self, mimeType,
                    displayName);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public DocumentFile createDirectory(String displayName) {
        final Uri result = TreeDocumentFile.createFile(
                mContext, mUri, DocumentsContract.Document.MIME_TYPE_DIR, displayName);
        return (result != null) ? new TreeDocumentFile(this, mContext, result) : null;
    }

    @Override
    public String getUri() {
        return mUri.toString();
    }

    @Override
    public String getName() {
        if (mName == null) {
            mName = DocumentsContractApi19.getName(mContext, mUri);
        }
        return mName;
    }

    private String getRawType() {
        if (mType == null) {
            mType = DocumentsContractApi19.getRawType(mContext, mUri);
        }
        return mType;
    }

    @Override
    public String getType() {
        final String rawType = getRawType();
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(rawType)) {
            return null;
        } else {
            return rawType;
        }
    }

    @Override
    public boolean isDirectory() {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(getRawType());
    }

    @Override
    public boolean isFile() {
        final String type = getRawType();
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type) || TextUtils.isEmpty(type)) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public boolean isVirtual() {
        return DocumentsContractApi19.isVirtual(mContext, mUri);
    }

    @Override
    public long lastModified() {
        if (mLastModified == null) {
            mLastModified = DocumentsContractApi19.lastModified(mContext, mUri);
        }
        return mLastModified;
    }

    @Override
    public long length() {
        if (mLength == null) {
            mLength = DocumentsContractApi19.length(mContext, mUri);
        }
        return mLength;
    }

    @Override
    public boolean canRead() {
        return DocumentsContractApi19.canRead(mContext, mUri);
    }

    @Override
    public boolean canWrite() {
        return DocumentsContractApi19.canWrite(mContext, mUri);
    }

    @Override
    public boolean delete() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        try {
            return DocumentsContract.deleteDocument(mContext.getContentResolver(), mUri);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean exists() {
        return DocumentsContractApi19.exists(mContext, mUri);
    }

    @Override
    public DocumentFile[] listFiles() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 21");
        }
        final ContentResolver resolver = mContext.getContentResolver();
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(mUri,
                DocumentsContract.getDocumentId(mUri));
        final ArrayList<DocumentFile> results = new ArrayList<>();

        Cursor c = null;
        try {
            c = resolver.query(childrenUri, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            while (c.moveToNext()) {
                final String documentId = c.getString(0);
                final String documentName = c.getString(1);
                final String documentType = c.getString(2);
                final long documentLastModified = c.getLong(3);
                final long documentSize = c.getLong(4);
                final Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(mUri,
                        documentId);
                results.add(new TreeDocumentFile(this, mContext, documentUri, documentName,
                        documentType, documentLastModified, documentSize));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            closeQuietly(c);
        }

        return results.toArray(new DocumentFile[0]);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean renameTo(String displayName) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 21");
        }
        try {
            final Uri result = DocumentsContract.renameDocument(
                    mContext.getContentResolver(), mUri, displayName);
            if (result != null) {
                mUri = result;
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean copyTo(DocumentFile destDir) {
        DocumentFile dst = destDir.findFile(getName());
        if (isDirectory()) {
            if (dst == null) {
                dst = destDir.createDirectory(getName());
            }
            if (dst == null || !dst.isDirectory()) {
                return false;
            }
            boolean success = true;
            DocumentFile[] childs = listFiles();
            for (DocumentFile f: childs) {
                success &= f.copyTo(dst);
            }
            return success;
        } else {
            ContentResolver contentResolver = mContext.getContentResolver();
            if (dst == null) {
                dst = destDir.createFile(getType(), getName());
            }
            if (dst == null || dst.isDirectory()) {
                return false;
            }
            try (InputStream input = new BufferedInputStream(contentResolver.openInputStream(Uri.parse(getUri())));
                 OutputStream output = new BufferedOutputStream(contentResolver.openOutputStream(Uri.parse(dst.getUri())))) {
                com.phlox.server.utils.Utils.copyStream(input, output);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean moveTo(DocumentFile destDir) {
        ContentResolver contentResolver = mContext.getContentResolver();
        DocumentFile dst = destDir.findFile(getName());
        if (dst != null &&
                (dst.isDirectory() || (isFile() && !dst.delete()))) {
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Uri movedDocUri = DocumentsContract.moveDocument(contentResolver, mUri, Uri.parse(getParentFile().getUri()), Uri.parse(destDir.getUri()));
                return movedDocUri != null;
            } catch (FileNotFoundException e) {}
        }
        return false;
    }

    //ALARM: please don't try to override this method
    //I tried and found that API has many bugs and it is not possible to implement it correctly and efficiently
    //https://stackoverflow.com/questions/56263620/contentresolver-query-on-documentcontract-lists-all-files-disregarding-selection
    /*@Nullable
    @Override
    public DocumentFile findFile(@NonNull String displayName) {
        return super.findFile(displayName);
    }*/

    @Override
    public boolean storageHasEnoughFreeSpaceFor(long contentLength) {
        return true;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        return mContext.getContentResolver().openInputStream(mUri);
    }

    @Override
    public OutputStream openOutputStream() throws FileNotFoundException {
        return mContext.getContentResolver().openOutputStream(mUri);
    }
}

