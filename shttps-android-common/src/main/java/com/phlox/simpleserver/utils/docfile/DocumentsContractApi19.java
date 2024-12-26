package com.phlox.simpleserver.utils.docfile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;
import com.phlox.server.utils.SHTTPSLoggerProxy;

class DocumentsContractApi19 {
    private static final Logger logger = SHTTPSLoggerProxy.getLogger(DocumentsContractApi19.class);

    // DocumentsContract API level 24.
    private static final int FLAG_VIRTUAL_DOCUMENT = 1 << 9;

    public static boolean isVirtual(Context context, Uri self) {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        if (!DocumentsContract.isDocumentUri(context, self)) {
            return false;
        }

        return (getFlags(context, self) & FLAG_VIRTUAL_DOCUMENT) != 0;
    }

    public static String getName(Context context, Uri self) {
        return queryForString(context, self, DocumentsContract.Document.COLUMN_DISPLAY_NAME, null);
    }

    public static String getRawType(Context context, Uri self) {
        return queryForString(context, self, DocumentsContract.Document.COLUMN_MIME_TYPE, null);
    }

    public static String getType(Context context, Uri self) {
        final String rawType = getRawType(context, self);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(rawType)) {
            return null;
        } else {
            return rawType;
        }
    }

    public static long getFlags(Context context, Uri self) {
        return queryForLong(context, self, DocumentsContract.Document.COLUMN_FLAGS, 0);
    }

    public static boolean isDirectory(Context context, Uri self) {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(getRawType(context, self));
    }

    public static boolean isFile(Context context, Uri self) {
        final String type = getRawType(context, self);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type) || TextUtils.isEmpty(type)) {
            return false;
        } else {
            return true;
        }
    }

    public static long lastModified(Context context, Uri self) {
        return queryForLong(context, self, DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0);
    }

    public static long length(Context context, Uri self) {
        return queryForLong(context, self, DocumentsContract.Document.COLUMN_SIZE, 0);
    }

    public static boolean canRead(Context context, Uri self) {
        // Ignore if grant doesn't allow read
        if (context.checkCallingOrSelfUriPermission(self, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        // Ignore documents without MIME
        if (TextUtils.isEmpty(getRawType(context, self))) {
            return false;
        }

        return true;
    }

    public static boolean canWrite(Context context, Uri self) {
        // Ignore if grant doesn't allow write
        if (context.checkCallingOrSelfUriPermission(self, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final String type = getRawType(context, self);
        final int flags = queryForInt(context, self, DocumentsContract.Document.COLUMN_FLAGS, 0);

        // Ignore documents without MIME
        if (TextUtils.isEmpty(type)) {
            return false;
        }

        // Deletable documents considered writable
        if ((flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0) {
            return true;
        }

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)
                && (flags & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0) {
            // Directories that allow create considered writable
            return true;
        } else if (!TextUtils.isEmpty(type)
                && (flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0) {
            // Writable normal files considered writable
            return true;
        }

        return false;
    }

    public static boolean exists(Context context, Uri self) {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        final ContentResolver resolver = context.getContentResolver();

        Cursor c = null;
        try {
            c = resolver.query(self, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID }, null, null, null);
            return c.getCount() > 0;
        } catch (Exception e) {
            logger.w("Failed query: " + e);
            return false;
        } finally {
            closeQuietly(c);
        }
    }

    private static String queryForString(Context context, Uri self, String column,
                                         String defaultValue) {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        final ContentResolver resolver = context.getContentResolver();

        Cursor c = null;
        try {
            c = resolver.query(self, new String[] { column }, null, null, null);
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            } else {
                return defaultValue;
            }
        } catch (Exception e) {
            logger.w("Failed query: " + e);
            return defaultValue;
        } finally {
            closeQuietly(c);
        }
    }

    private static int queryForInt(Context context, Uri self, String column,
                                   int defaultValue) {
        return (int) queryForLong(context, self, column, defaultValue);
    }

    private static long queryForLong(Context context, Uri self, String column,
                                     long defaultValue) {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            throw new IllegalStateException("TreeDocumentFile is not supported on API < 19");
        }
        final ContentResolver resolver = context.getContentResolver();

        Cursor c = null;
        try {
            c = resolver.query(self, new String[] { column }, null, null, null);
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            } else {
                return defaultValue;
            }
        } catch (Exception e) {
            logger.w("Failed query: " + e);
            return defaultValue;
        } finally {
            closeQuietly(c);
        }
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

    private DocumentsContractApi19() {
    }
}

