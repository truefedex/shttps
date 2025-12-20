package com.phlox.simpleserver.handlers.files.upload;

import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.DefaultBinaryDataConsumer;
import com.phlox.server.request.Request;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.ProgressOutputStream;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class DirectUploadRequestDataConsumer extends DefaultBinaryDataConsumer {
    public static final String ERROR_MSG_NO_SPACE_LEFT = "No file space left";

    private final User user;
    private final UserStore userStore;
    private final SHTTPSConfig config;

    public DirectUploadRequestDataConsumer(SHTTPSConfig config, User user, UserStore userStore) {
        this.config = config;
        this.user = user;
        this.userStore = userStore;
    }

    @Override
    public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws IOException {
        if (!config.getAllowEditing()) return null;
        DocumentFile root = config.getRootDir();
        String destPath = request.queryParams.get("path");
        if (name == null) return null;
        if (destPath == null) return null;
        if (name.equals("files[]")) {
            if (fileName.contains("..")) throw new SecurityException("Invalid file name");
            String fullDestPath = destPath;
            int pathSepLastIndex = fileName.lastIndexOf('/');
            if (pathSepLastIndex != -1) {
                fullDestPath = destPath + "/" + fileName.substring(0, pathSepLastIndex);
                fileName = fileName.substring(pathSepLastIndex + 1);
            }
            DocumentFile uploadDir = DocumentFileUtils.findChildByPath(root, fullDestPath, user);
            if (uploadDir == null) {
                //try to find parent dir and create missing dirs
                DocumentFile parent = DocumentFileUtils.findChildByPath(root, destPath, user);
                if (parent == null) return null;
                String[] parts = fullDestPath.substring(destPath.length() + 1).split("/");
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
            Long storageLimit = user != null ? userStore.provideUserRightsEvaluator().getStorageLimit(user) : null;
            Long spaceUsed = user != null ? user.usedStorage : null;
            long freeSpace;
            if (storageLimit != null) {
                freeSpace = storageLimit - spaceUsed;
            } else {
                freeSpace = uploadDir.getStorageFreeSpace();
            }
            if (freeSpace < fileSize) {
                throw new IOException(ERROR_MSG_NO_SPACE_LEFT);
            }

            DocumentFile file = uploadDir.findFile(fileName);
            if (file == null) {
                file = uploadDir.createFile(type, fileName);
                if (file == null) {
                    throw new IOException("Can not create file: " + fileName);
                }
            }

            OutputStream fileOutput = SHTTPSApp.getInstance().platformUtils.openOutputStream(file.getUri());

            final DocumentFile finalUploadDir = uploadDir;
            final DocumentFile finalFile = file;
            final Holder<Boolean> interruptedByExceptionMark = new Holder<>(false);

            ProgressOutputStream.WriteProgressListener writeProgressListener = new ProgressOutputStream.WriteProgressListener() {
                @Override
                public void onChunkWritten(long totalBytes, long chunkSize) throws Exception {
                    long freeSpace;
                    if (user != null) {
                        userStore.updateUserAtomically(user.identity, u -> {
                            u.usedStorage += chunkSize;
                            user.usedStorage = u.usedStorage;
                            return u;
                        });
                    }
                    if (storageLimit != null) {
                        freeSpace = storageLimit - user.usedStorage;
                    } else {
                        freeSpace = finalUploadDir.getStorageFreeSpace();
                    }

                    long leftToWrite = fileSize > 0 ? (fileSize - totalBytes) : 0;
                    if (freeSpace < leftToWrite) {
                        throw new IOException(ERROR_MSG_NO_SPACE_LEFT);
                    }
                }

                @Override
                public void onException(Exception e) {
                    interruptedByExceptionMark.set(true);
                }

                @Override
                public void onStreamClosed(long totalBytes) {
                    if (interruptedByExceptionMark.get()) {
                        if (finalFile.exists()) {
                            finalFile.delete();
                        }
                        if (user != null) {
                            try {
                                userStore.updateUserAtomically(user.identity, u -> {
                                    u.usedStorage -= totalBytes;
                                    user.usedStorage = u.usedStorage;
                                    return u;
                                });
                            } catch (Exception ignored) {}
                        }
                    }
                }
            };

            return new BufferedOutputStream(
                    new ProgressOutputStream(
                            fileOutput,
                            1024 * 1024,//trigger callback to check available storage each 1 Mb,
                            writeProgressListener
                    )
            );
        } else if (name.equals("emptyDirs[]")) {
            return super.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders);
        }
        return null;
    }
}
