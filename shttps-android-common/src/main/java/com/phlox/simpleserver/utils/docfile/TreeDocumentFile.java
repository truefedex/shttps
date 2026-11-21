package com.phlox.simpleserver.utils.docfile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

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

    /**
     * A URI {@link android.content.Intent#ACTION_OPEN_DOCUMENT_TREE} handed back, or a document
     * URI rewritten into the tree form. Null when the URI is neither: {@code getTreeDocumentId}
     * throws {@link IllegalArgumentException} for anything whose first path segment is not
     * {@code tree}, which is what a single-document pick and several file managers return.
     */
    public static Uri normalizeTreeUri(Context context, Uri uri) {
        if (uri == null) return null;
        if (DocumentsContract.isTreeUri(uri)) return uri;
        try {
            if (!DocumentsContract.isDocumentUri(context, uri)) return null;
            String documentId = DocumentsContract.getDocumentId(uri);
            String authority = uri.getAuthority();
            if (documentId == null || authority == null) return null;
            Uri treeUri = DocumentsContract.buildTreeDocumentUri(authority, documentId);
            return DocumentsContract.isTreeUri(treeUri) ? treeUri : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static DocumentFile fromTreeUri(Context context, Uri treeUri) {
        Uri uri = normalizeTreeUri(context, treeUri);
        if (uri == null) return null;
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            if (DocumentsContract.isDocumentUri(context, uri)) {
                documentId = DocumentsContract.getDocumentId(uri);
            }
            return new TreeDocumentFile(null, context,
                    DocumentsContract.buildDocumentUriUsingTree(uri, documentId));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        if (result == null) return null;
        TreeDocumentFile created = new TreeDocumentFile(this, mContext, result);
        //the extension is cut off above so the provider does not append a second one from the
        //MIME type. It only appends one when it knows the type, so "data.bin" sent as
        //application/octet-stream became "data", and a staging name like "a.txt.shttps-1" became
        //"a.txt". Whatever came back, the name asked for is the name the file gets
        if (!displayName.equals(created.getName()) && !created.renameTo(displayName)) {
            created.delete();
            return null;
        }
        return created;
    }

    private static Uri createFile(Context context, Uri self, String mimeType,
                                  String displayName) {
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
    public long created() {
        return lastModified();
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
        try {
            final Uri result = DocumentsContract.renameDocument(
                    mContext.getContentResolver(), mUri, displayName);
            if (result != null) {
                mUri = result;
                mName = null;
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
        return copyTo(destDir, getName());
    }

    @Override
    public boolean moveTo(DocumentFile destDir) {
        return moveTo(destDir, getName());
    }

    /**
     * Moves this document into {@code destDir} under {@code destName}, replacing a file of that
     * name. Within the same directory this is a rename: {@code moveDocument} refuses a move onto
     * the source's own parent, and looking the destination up by name would find this very
     * document - which the old code then deleted before the move failed.
     */
    @Override
    public boolean moveTo(DocumentFile destDir, String destName) {
        ContentResolver contentResolver = mContext.getContentResolver();
        DocumentFile parent = getParentFile();
        if (parent == null) return false;
        DocumentFile dst = destDir.findFile(destName);
        if (dst != null) {
            if (sameDocument(dst.getUri(), getUri())) {
                return true;//already there
            }
            if (dst.isDirectory() || (isFile() && !dst.delete())) {
                return false;
            }
        }
        if (sameDocument(parent.getUri(), destDir.getUri())) {
            return renameTo(destName);
        }
        try {
            Uri movedDocUri = DocumentsContract.moveDocument(contentResolver, mUri,
                    Uri.parse(parent.getUri()), Uri.parse(destDir.getUri()));
            if (movedDocUri == null) return false;
            mUri = movedDocUri;
            mName = null;
        } catch (FileNotFoundException | RuntimeException e) {
            return false;
        }
        return destName.equals(getName()) || renameTo(destName);
    }

    private static boolean sameDocument(String a, String b) {
        try {
            return DocumentsContract.getDocumentId(Uri.parse(a)).equals(DocumentsContract.getDocumentId(Uri.parse(b)));
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    @Override
    public boolean copyTo(DocumentFile destDir, String destName) {
        DocumentFile dst = destDir.findFile(destName);
        if (isDirectory()) {
            if (dst == null) {
                dst = destDir.createDirectory(destName);
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
                dst = destDir.createFile(getType(), destName);
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

    //ALARM: please don't try to override this method
    //I tried and found that API has many bugs and it is not possible to implement it correctly and efficiently
    //https://stackoverflow.com/questions/56263620/contentresolver-query-on-documentcontract-lists-all-files-disregarding-selection
    /*@Nullable
    @Override
    public DocumentFile findFile(@NotNull String displayName) {
        return super.findFile(displayName);
    }*/

    @Override
    public InputStream openInputStream() throws IOException {
        return mContext.getContentResolver().openInputStream(mUri);
    }

    @Override
    public OutputStream openOutputStream() throws FileNotFoundException {
        return mContext.getContentResolver().openOutputStream(mUri);
    }

    @Override
    public String getRelativePath(DocumentFile directOrIndirectChild) {
        if (!isDirectory() || directOrIndirectChild == null) {
            return null;
        }
        String baseUri = getUri();
        String fileUri = directOrIndirectChild.getUri();
        if (fileUri.startsWith(baseUri)) {
            String relativeUriPart = fileUri.substring(baseUri.length());
            //uri is url encoded, need to decode
            return Uri.decode(relativeUriPart);
        }
        return null;
    }

    @Override
    public long getStorageSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getStorageFreeSpace() {
        return Long.MAX_VALUE;
    }
}

