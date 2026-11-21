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
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.Map;

public class WebDavPutHelper extends WebDavHelpersBase {
    public WebDavPutHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
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
        if (!quota.hasSpaceFor(parent, fileSize, previousSize)) {
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

        if (target == null) {
            target = parent.createFile(type, fileName);
            if (target == null) {
                return StandardResponses.FORBIDDEN();
            }
        }

        //actual uploading
        try {
            context.requestBodyReader.readRequestBody(request, new WebDavUploadRequestDataConsumer(quota, parent, target, previousSize));
        } catch (DavException e) {
            return new Response(e.code, e.reason);
        }

        return new Response(existed ? 204 : 201, existed ? "No Content" : "Created");
    }


    public static class WebDavUploadRequestDataConsumer implements RequestBodyConsumer {
        private final StorageQuota quota;
        private final DocumentFile dir;
        private final DocumentFile target;
        private final long previousSize;

        public WebDavUploadRequestDataConsumer(StorageQuota quota, DocumentFile dir, DocumentFile target, long previousSize) {
            this.quota = quota;
            this.dir = dir;
            this.target = target;
            this.previousSize = previousSize;
        }

        @Override
        public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws Exception {
            throw new DavException(415, "Unsupported Media Type");
        }

        @Override
        public OutputStream prepareBinaryOutputForRequestBodyData(Request request) throws Exception {
            OutputStream fileOutput = SHTTPSApp.getInstance().platformUtils.openOutputStream(target.getUri());

            return new BufferedOutputStream(
                    new ProgressOutputStream(
                            fileOutput,
                            1024 * 1024,//trigger callback to check available storage each 1 Mb,
                            quota.newWriteListener(dir, target, request.contentLength, previousSize,
                                    msg -> new DavException(507, msg))
                    )
            );
        }
    }
}
