package com.phlox.simpleserver.handlers.files.upload;

import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.DefaultRequestBodyConsumer;
import com.phlox.server.request.Request;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.ProgressOutputStream;
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.Map;

public class DirectUploadRequestDataConsumer extends DefaultRequestBodyConsumer {
    public static final String ERROR_MSG_NO_SPACE_LEFT = StorageQuota.ERROR_MSG_NO_SPACE_LEFT;

    private final User user;
    private final SHTTPSConfig config;
    private final StorageQuota quota;
    private final LockManager locks;
    private final String lockToken;

    public DirectUploadRequestDataConsumer(SHTTPSConfig config, User user, StorageQuota quota, LockManager locks, String lockToken) {
        this.config = config;
        this.user = user;
        this.quota = quota;
        this.locks = locks;
        this.lockToken = lockToken;
    }

    @Override
    public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws Exception {
        if (!config.getAllowEditing()) return null;
        DocumentFile root = config.getRootDir();
        String destPath = request.queryParams.get("path");
        if (name == null) return null;
        if (destPath == null) return null;
        if (name.equals("files[]")) {
            if (fileName.contains("..")) throw new UploadException(400, "Bad request");
            String fullDestPath = destPath;
            String relativeDirs = null;
            int pathSepLastIndex = fileName.lastIndexOf('/');
            if (pathSepLastIndex != -1) {
                relativeDirs = fileName.substring(0, pathSepLastIndex);
                fullDestPath = Utils.joinPaths(destPath, relativeDirs);
                fileName = fileName.substring(pathSepLastIndex + 1);
            }

            String filePath = Utils.joinPaths(fullDestPath, fileName);
            if (!locks.mayWrite(filePath, lockToken)) {
                throw new UploadException(423, "Locked");
            }

            DocumentFile uploadDir = DocumentFileUtils.findChildByPath(root, fullDestPath, user);
            if (uploadDir == null) {
                //try to find parent dir and create missing dirs
                DocumentFile parent = DocumentFileUtils.findChildByPath(root, destPath, user);
                if (parent == null || relativeDirs == null) return null;
                String[] parts = relativeDirs.split("/");
                for (String part : parts) {
                    DocumentFile child = parent.findFile(part);
                    if (child != null) {
                        if (!child.isDirectory()) return null;
                    } else {
                        child = parent.createDirectory(part);
                        if (child == null) return null;
                    }
                    parent = child;
                }
                uploadDir = parent;
            }
            String type = null;
            String extension = Utils.getFileExtensionFromFilename(fileName);
            if (extension != null) {
                type = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
            }
            if (type == null) {
                type = "application/octet-stream";
            }

            String contentLengthHeader = partHeaders.get(Request.HEADER_CONTENT_LENGTH);
            long fileSize = contentLengthHeader != null ? Long.parseLong(contentLengthHeader) : 0L;

            DocumentFile file = uploadDir.findFile(fileName);
            long previousSize = file != null ? file.length() : 0;
            if (!quota.hasSpaceFor(uploadDir, fileSize, previousSize)) {
                throw new UploadException(413, ERROR_MSG_NO_SPACE_LEFT);
            }
            if (file == null) {
                file = uploadDir.createFile(type, fileName);
                if (file == null) {
                    throw new UploadException(400, "Can not create file: " + fileName);
                }
            }

            OutputStream fileOutput = SHTTPSApp.getInstance().platformUtils.openOutputStream(file.getUri());

            return new BufferedOutputStream(
                    new ProgressOutputStream(
                            fileOutput,
                            1024 * 1024,//trigger callback to check available storage each 1 Mb,
                            quota.newWriteListener(uploadDir, file, fileSize, previousSize,
                                    msg -> new UploadException(413, msg))
                    )
            );
        } else if (name.equals("emptyDirs[]")) {
            return super.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders);
        }
        return null;
    }

    @Override
    public OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws Exception {
        throw new UploadException(400, "Bad request");
    }

    public static class UploadException extends Exception {
        final int code;
        final String reason;
        UploadException(int c, String r) { code = c; reason = r; }
    }
}
