package com.phlox.simpleserver.utils.docfile;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import com.phlox.server.utils.docfile.DocumentFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MediaStoreFileCollectionFile extends DocumentFile {
    public static final String MEDIASTORE_FILES_DUMMY_URI = "mediastore://";
    private final Context context;
    private final Uri uri;
    private String name;
    private String type;
    private long length;
    private long lastModified;
    private String relativePath;

    //temporary virtual directories for file upload
    //key is relative path, value is list of subdirectories
    private static final Map<String, List<String>> temporaryVirtualDirectories = new HashMap<>();

    public static MediaStoreFileCollectionFile getMediaStoreRoot(Context context) {
        return new MediaStoreFileCollectionFile(context, null, Uri.parse("mediastore://"), "MediaStore", "/");
    }

    /**
     * Constructor for directory
     */
    public MediaStoreFileCollectionFile(Context context, DocumentFile parent, Uri uri, String name, String relativePath) {
        super(parent);
        this.context = context;
        this.uri = uri;
        this.name = name;
        this.relativePath = relativePath;
    }

    /**
     * Constructor for file
     */
    public MediaStoreFileCollectionFile(Context context, DocumentFile parent, Uri uri, String name, String type, long length, long lastModified, String relativePath) {
        super(parent);
        this.context = context;
        this.uri = uri;
        this.name = name;
        this.type = type;
        this.length = length;
        this.lastModified = lastModified;
        this.relativePath = relativePath;
    }

    @Override
    public DocumentFile createFile(String mimeType, String displayName) {
        if (isDirectory()) {
            if (relativePath == null || relativePath.isEmpty() || relativePath.equals("/")) {
                //can not create file in root
                return null;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath);
            //values.put(MediaStore.Files.FileColumns.IS_PENDING, 1);
            @SuppressLint("InlinedApi")
            Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
            
            ContentResolver contentResolver = context.getContentResolver();
            Uri newUri = contentResolver.insert(collection, values);
            if (newUri != null) {
                try (Cursor cursor = contentResolver.query(
                        newUri,
                        new String[] {
                                MediaStore.Files.FileColumns._ID,
                                MediaStore.Files.FileColumns.DISPLAY_NAME,
                                MediaStore.Files.FileColumns.MIME_TYPE,
                                MediaStore.Files.FileColumns.SIZE,
                                MediaStore.Files.FileColumns.DATE_MODIFIED,
                                MediaStore.Files.FileColumns.RELATIVE_PATH
                        },
                        null,
                        null,
                        null
                )) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME));
                        String type = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE));
                        long length = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE));
                        long lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED));
                        String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH));
                        return new MediaStoreFileCollectionFile(context, this, newUri, name, type, length, lastModified, path);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public DocumentFile createDirectory(String displayName) {
        if (isDirectory()) {
            if (relativePath == null || relativePath.isEmpty() || relativePath.equals("/")) {
                //can not create directory in root
                return null;
            }

            //we only able to create directories in Download and Documents directories (or their subdirectories)
            if (!relativePath.startsWith("Download/") && !relativePath.startsWith("Documents/")) {
                return null;
            }

            synchronized (temporaryVirtualDirectories) {
                if (temporaryVirtualDirectories.containsKey(relativePath)) {
                    List<String> subdirs = temporaryVirtualDirectories.get(relativePath);
                    assert subdirs != null;
                    if (subdirs.contains(displayName)) {
                        return null;
                    }
                    subdirs.add(displayName);
                } else {
                    List<String> subdirs = new ArrayList<>();
                    subdirs.add(displayName);
                    temporaryVirtualDirectories.put(relativePath, subdirs);
                }
            }
            return new MediaStoreFileCollectionFile(context, this, Uri.withAppendedPath(uri, displayName), displayName, relativePath + "/" + displayName);
        }
        return null;
    }

    @Override
    public String getUri() {
        return uri.toString();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getType() {
        if (!isDirectory()) {
            return type;
        }
        return "inode/directory";
    }

    @Override
    public boolean isDirectory() {
        return Objects.equals(uri.getScheme(), "mediastore");
    }

    public boolean isRoot() {
        return relativePath == null || relativePath.isEmpty() || relativePath.equals("/");
    }

    @Override
    public boolean isFile() {
        return !isDirectory();
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @Override
    public long lastModified() {
        if (isDirectory()) {
            return 0;
        }
        return lastModified;
    }

    @Override
    public long length() {
        if (isDirectory()) {
            return 0;
        }
        return length;
    }

    @Override
    public boolean canRead() {
        return !isDirectory();
    }

    @Override
    public boolean canWrite() {
        return !isDirectory();
    }

    @Override
    public boolean delete() {
        if (isDirectory()) {
            ContentResolver contentResolver = context.getContentResolver();
            String[] filesProjection = new String[]{
                    MediaStore.Files.FileColumns._ID
            };
            String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?";
            String[] selectionArgs = new String[]{relativePath + "%"};
            ArrayList<Uri> uris = new ArrayList<>();
            try (Cursor cursor = contentResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    filesProjection,
                    selection,
                    selectionArgs,
                    null
            )) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID));
                        Uri fileUri = Uri.withAppendedPath(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), Long.toString(id));
                        uris.add(fileUri);
                    }
                }
            }
            for (Uri fileUri : uris) {
                contentResolver.delete(fileUri, null, null);
            }
            //also delete temporary virtual directory
            int lastSlashIndex = relativePath.lastIndexOf('/');
            if (lastSlashIndex != -1) {
                String parentPath = relativePath.substring(0, lastSlashIndex);
                synchronized (temporaryVirtualDirectories) {
                    if (temporaryVirtualDirectories.containsKey(parentPath)) {
                        List<String> subdirs = temporaryVirtualDirectories.get(parentPath);
                        assert subdirs != null;
                        subdirs.remove(name);
                        if (subdirs.isEmpty()) {
                            temporaryVirtualDirectories.remove(parentPath);
                        }
                    }
                }
            }
            return true;
        } else {
            return context.getContentResolver().delete(uri, null, null) > 0;
        }
    }

    @Override
    public boolean exists() {
        if (isDirectory()) {
            return true;
        }
        ContentResolver contentResolver = context.getContentResolver();
        try (Cursor cursor = contentResolver.query(
                uri,
                new String[] { MediaStore.Files.FileColumns._ID },
                null,
                null,
                null
        )) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    @Override
    public DocumentFile[] listFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw new UnsupportedOperationException();
        }
        if (isDirectory()) {
            ArrayList<DocumentFile> files = new ArrayList<>();

            Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
            ContentResolver contentResolver = context.getContentResolver();

            //first load directories
            String[] dirsProjection = new String[]{
                    MediaStore.Files.FileColumns.RELATIVE_PATH
            };

            Bundle queryArgs = new Bundle();
            String currentPath = uri.getPath() != null ? uri.getPath() : "";
            if (currentPath.startsWith("/")) {
                currentPath = currentPath.substring(1);
            }
            if (!currentPath.isEmpty()) {
                //limit to subdirectories
                queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?");
                String relativePath = currentPath + "/%";
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }
                queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{relativePath});
            }
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, new String[]{MediaStore.Files.FileColumns.RELATIVE_PATH});
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{MediaStore.Files.FileColumns.RELATIVE_PATH});

            ArrayList<String> subdirs = new ArrayList<>();
            try (Cursor cursor = contentResolver.query(
                    collection,
                    dirsProjection,
                    queryArgs,
                    null
            )) {
                if (cursor != null) {
                    String[] currentPathParts = currentPath.isEmpty() ? new String[0] : currentPath.split("/");
                    int relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH);
                    while (cursor.moveToNext()) {
                        String path = cursor.getString(relativePathColumn);
                        if (path != null && !path.isEmpty()) {
                            String[] parts = path.split("/");
                            if (parts.length > currentPathParts.length) {
                                String subdir = parts[currentPathParts.length];
                                if (!subdirs.contains(subdir)) {
                                    subdirs.add(subdir);
                                    files.add(new MediaStoreFileCollectionFile(context, this, Uri.withAppendedPath(uri, subdir), subdir, path));
                                }
                            }
                        }
                    }
                }
            }
            //also check is this is root directory and if Download or Documents are not available in subdirs list - add them
            if (isRoot()) {
                if (!subdirs.contains("Download")) {
                    files.add(new MediaStoreFileCollectionFile(context, this, Uri.withAppendedPath(uri, "Download"), "Download", "Download"));
                }
                if (!subdirs.contains("Documents")) {
                    files.add(new MediaStoreFileCollectionFile(context, this, Uri.withAppendedPath(uri, "Documents"), "Documents", "Documents"));
                }
            }
            //also if temporary virtual directories are not available in subdirs list - add them
            synchronized (temporaryVirtualDirectories) {
                if (temporaryVirtualDirectories.containsKey(currentPath + "/")) {
                    for (String subdir : Objects.requireNonNull(temporaryVirtualDirectories.get(currentPath + "/"))) {
                        if (!subdirs.contains(subdir)) {
                            files.add(new MediaStoreFileCollectionFile(context, this, Uri.withAppendedPath(uri, subdir), subdir, currentPath + "/" + subdir));
                        }
                    }
                }
            }

            //now load files

            String[] filesProjection = new String[]{
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.RELATIVE_PATH
            };

            String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
            String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " = ?";
            String[] selectionArgs = new String[]{currentPath + "/"};

            try (Cursor cursor = contentResolver.query(
                    collection,
                    filesProjection,
                    selection,
                    selectionArgs,
                    sortOrder
            )) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                    int lengthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                    int lastModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                    int relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        Uri uri = Uri.withAppendedPath(collection, Long.toString(id));
                        String name = cursor.getString(nameColumn);
                        String type = cursor.getString(typeColumn);
                        long length = cursor.getLong(lengthColumn);
                        long lastModified = cursor.getLong(lastModifiedColumn);
                        String relativePath = cursor.getString(relativePathColumn);
                        files.add(new MediaStoreFileCollectionFile(context, this, uri, name, type, length, lastModified, relativePath));
                    }
                }
            }
            return files.toArray(new DocumentFile[0]);
        }
        return null;
    }

    @Override
    public boolean renameTo(String displayName) {
        if (isDirectory()) {
            return false;
        }

        ContentResolver contentResolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName);
        return contentResolver.update(uri, values, null, null) > 0;
    }

    @Override
    public boolean copyTo(DocumentFile destDir) {
        if (isDirectory()) {
            return false;
        }

        ContentResolver contentResolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, name);
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, type);
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, ((MediaStoreFileCollectionFile) destDir).getRelativePath());
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);

        Uri newUri = contentResolver.insert(collection, values);
        if (newUri != null) {
            try (InputStream is = contentResolver.openInputStream(uri);
                 OutputStream os = contentResolver.openOutputStream(newUri)) {
                if (is != null && os != null) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    return true;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public boolean moveTo(DocumentFile destDir) {
        if (isDirectory()) {
            return false;
        }

        ContentResolver contentResolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, ((MediaStoreFileCollectionFile) destDir).getRelativePath());
        return contentResolver.update(uri, values, null, null) > 0;
    }

    @Override
    public boolean storageHasEnoughFreeSpaceFor(long contentLength) {
        return true;
    }

    @Override
    public InputStream openInputStream() throws IOException {
        if (isDirectory()) {
            throw new IOException();
        }
        return context.getContentResolver().openInputStream(uri);
    }

    @Override
    public OutputStream openOutputStream() throws FileNotFoundException {
        if (isDirectory()) {
            throw new FileNotFoundException();
        }
        return context.getContentResolver().openOutputStream(uri);
    }

    @Override
    public String getRelativePath(DocumentFile file) {
        if (!isDirectory() || !(file instanceof MediaStoreFileCollectionFile)) {
            return null;
        }
        String basePath = relativePath;
        String filePath = ((MediaStoreFileCollectionFile) file).getRelativePath();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        if (basePath.startsWith("/")) {
            basePath = basePath.substring(1);
        }
        if (filePath.startsWith("/")) {
            filePath = filePath.substring(1);
        }
        if (filePath.startsWith(basePath)) {
            return filePath.substring(basePath.length());
        }
        return null;
    }

    public String getRelativePath() {
        return relativePath;
    }
}
