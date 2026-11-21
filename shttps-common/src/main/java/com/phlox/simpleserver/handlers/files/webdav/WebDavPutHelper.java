package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyConsumer;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.upload.UploadFileRequestHandler;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.utils.ProgressOutputStream;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.StagedUpload;
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class WebDavPutHelper extends WebDavHelpersBase {
    private final boolean atomicUploads;

    public WebDavPutHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        this(platform, userStore, locks, false);
    }

    /** @param atomicUploads {@code SHTTPSConfig.isAtomicUploadsEnabled()} */
    public WebDavPutHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks,
                           boolean atomicUploads) {
        super(platform, userStore, locks);
        this.atomicUploads = atomicUploads;
    }

    public Response handlePutRequest(RequestContext context, FilesRequestHandler filesRequestHandler,
                                     Request request, DocumentFile userRoot, DocumentFile target,
                                     User user) throws Exception {
        String path = request.path;

        String lockTokenProvided = extractLockTokenFromIf(request.headers.get("if"));
        if (!locks.mayWrite(path, lockTokenProvided)) {
            return simpleStatus(423, "Locked");
        }

        if (target != null && target.isDirectory()) {
            return StandardResponses.METHOD_NOT_ALLOWED();
        }

        DocumentFile parent;
        String fileName;
        if (target != null) {
            parent = target.getParentFile();
            fileName = target.getName();
        } else {
            String parentPath = Utils.getParentPath(path);
            if (parentPath == null) {
                return simpleStatus(400, "Bad Request");
            }
            parent = DocumentFileUtils.findChildByPath(userRoot, parentPath);
            fileName = path.substring(path.lastIndexOf('/') + 1);
        }
        // The parent must exist (PUT does not create intermediate folders)
        if (parent == null || !parent.isDirectory()) {
            return StandardResponses.CONFLICT();
        }

        boolean existed = target != null && target.exists();
        long previousSize = existed ? target.length() : 0;

        if (filesRequestHandler.checkIsForbidden(user, request.path, UploadFileRequestHandler.UPLOAD_OPERATION, Map.of(
                        "contentLength", request.contentLength
                ), existed ? User.FileSystemRights.UPDATE : User.FileSystemRights.CREATE
                )) return StandardResponses.FORBIDDEN();

        long fileSize = request.contentLength;
        StorageQuota quota = new StorageQuota(user, userStore);
        //staged, the old file stays on disk next to the new one until the commit
        if (!quota.hasSpaceFor(parent, fileSize, atomicUploads ? 0 : previousSize)) {
            return new Response(507, "Insufficient Storage");
        }

        String type = null;
        String extension = Utils.getFileExtensionFromFilename(fileName);
        if (extension != null) {
            type = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
        }
        if (type == null) {
            type = "application/octet-stream";
        }

        //actual uploading. With atomic_uploads the bytes land in a sibling that replaces the
        //destination only in finish(), after the body has been read to its end
        WebDavUploadRequestDataConsumer consumer = new WebDavUploadRequestDataConsumer(quota, parent,
                fileName, type, existed ? target : null, previousSize, atomicUploads);
        try {
            context.requestBodyReader.readRequestBody(request, consumer);
            consumer.finish(request.contentLength);
        } catch (Exception e) {
            consumer.discard();
            DavException dav = davCause(e);
            if (dav != null) return new Response(dav.code, dav.reason);
            throw e;
        }

        return new Response(existed ? 204 : 201, existed ? "No Content" : "Created");
    }

    private static DavException davCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DavException) return (DavException) current;
            current = current.getCause();
        }
        return null;
    }


    public static class WebDavUploadRequestDataConsumer implements RequestBodyConsumer {
        private final StorageQuota quota;
        private final DocumentFile dir;
        private final String fileName;
        private final String mime;
        private final @Nullable DocumentFile existing;
        private final long previousSize;
        private final boolean atomic;
        private @Nullable StagedUpload staged;
        private @Nullable ProgressOutputStream direct;

        public WebDavUploadRequestDataConsumer(StorageQuota quota, DocumentFile dir, String fileName,
                                               String mime, @Nullable DocumentFile existing,
                                               long previousSize, boolean atomic) {
            this.quota = quota;
            this.dir = dir;
            this.fileName = fileName;
            this.mime = mime;
            this.existing = existing;
            this.previousSize = previousSize;
            this.atomic = atomic;
        }

        @Override
        public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws Exception {
            throw new DavException(415, "Unsupported Media Type");
        }

        @Override
        public OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws Exception {
            if (atomic) {
                staged = StagedUpload.open(dir, mime, fileName, existing, quota, request.contentLength,
                        msg -> new DavException(507, msg),
                        file -> SHTTPSApp.getInstance().platformUtils.openOutputStream(file.getUri()));
                return staged.stream();
            }
            DocumentFile target = existing != null ? existing : createTarget();
            OutputStream fileOutput = SHTTPSApp.getInstance().platformUtils.openOutputStream(target.getUri());
            direct = new ProgressOutputStream(
                    fileOutput,
                    1024 * 1024,//trigger callback to check available storage each 1 Mb,
                    quota.newWriteListener(dir, target, request.contentLength, previousSize,
                            msg -> new DavException(507, msg)));
            return new BufferedOutputStream(direct);
        }

        /**
         * After the body was read without error. A body shorter than its Content-Length is an
         * error even though the reader treats the client's EOF as the end of the body: staged, the
         * previous file survives it; written directly, it is already truncated, but the client is
         * at least not told it succeeded.
         */
        void finish(long contentLength) throws Exception {
            if (staged != null) {
                try {
                    staged.commit();
                } catch (StagedUpload.IncompleteUploadException e) {
                    throw new DavException(400, "Bad Request");
                } catch (IOException e) {
                    throw new DavException(500, "Internal Server Error");
                }
                return;
            }
            if (direct != null) {
                if (contentLength >= 0 && direct.getTotal() != contentLength) {
                    throw new DavException(400, "Bad Request");
                }
                return;
            }
            //an empty body: the reader never asked for a stream, and the result is an empty file
            if (existing == null) {
                createTarget();
            } else {
                SHTTPSApp.getInstance().platformUtils.openOutputStream(existing.getUri()).close();
                quota.addUsedQuietly(-previousSize);
            }
        }

        void discard() {
            if (staged != null) staged.discard();
        }

        private DocumentFile createTarget() throws DavException {
            DocumentFile created = dir.createFile(mime, fileName);
            if (created == null) {
                throw new DavException(403, "Forbidden");
            }
            return created;
        }
    }
}
