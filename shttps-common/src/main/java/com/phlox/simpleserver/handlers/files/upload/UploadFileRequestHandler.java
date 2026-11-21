package com.phlox.simpleserver.handlers.files.upload;

import static com.phlox.simpleserver.handlers.files.webdav.WebDavHelpersBase.extractLockTokenFromIf;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;

import com.phlox.server.request.FormDataPart;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.BaseFileRequestHandler;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import java.io.IOException;
import java.util.Map;

public class UploadFileRequestHandler extends BaseFileRequestHandler {
    public static final String UPLOAD_OPERATION = "UPLOAD";

    public UploadFileRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore, LockManager locks) {
        super(config, authManager, userStore, locks);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_PUT)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_PUT});
        if (!Request.CONTENT_TYPE_MULTIPART_FORM.equals(request.contentType)) {
            return StandardResponses.BAD_REQUEST();
        }

        DocumentFile root = config.getRootDir();
        String destPath = request.queryParams.get("path");
        User user = checkUser(context);
        final DocumentFile uploadDir = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((uploadDir == null) || !uploadDir.isDirectory()) return StandardResponses.NOT_FOUND();

        String lockTokenProvided = extractLockTokenFromIf(request.headers.get("if"));

        StorageQuota quota = new StorageQuota(user, userStore);
        if (request.contentLength > 0 && !quota.hasSpaceFor(uploadDir, request.contentLength, 0)) {
            return StandardResponses.PAYLOAD_TOO_LARGE();
        }

        if (checkIsForbidden(user, destPath, UPLOAD_OPERATION, Map.of(
                "contentLength", request.contentLength
                ), User.FileSystemRights.CREATE,
                User.FileSystemRights.UPDATE)) return StandardResponses.FORBIDDEN();

        DirectUploadRequestDataConsumer consumer =
                new DirectUploadRequestDataConsumer(config, user, quota, locks, lockTokenProvided);
        try {
            //actual uploading. Staged uploads (atomic_uploads) are published only once the whole
            //body is in: the reader closes each part's stream even when the client went away
            context.requestBodyReader.readRequestBody(request, consumer);
            consumer.commitStaged();
        } catch (Exception e) {
            consumer.discardStaged();
            DirectUploadRequestDataConsumer.UploadException upload = uploadCause(e);
            if (upload != null) return new Response(upload.code, upload.reason);
            throw e;
        }
        //we need also handle empty directory creation here
        for (FormDataPart part : request.multipartData) {
            if (part.name.equals("emptyDirs[]")) {
                String path = part.getDataAsString();
                if (path.contains("..")) return StandardResponses.FORBIDDEN("Invalid path");
                String fullDirPath = Utils.joinPaths(destPath, path);
                if (!locks.mayWrite(fullDirPath, lockTokenProvided)) {
                    return new Response(423, "Locked");
                }
                DocumentFile dir = DocumentFileUtils.findChildByPath(root, fullDirPath, user);
                if (dir == null) {
                    DocumentFile parent = DocumentFileUtils.findChildByPath(root, destPath, user);
                    if (parent == null) return StandardResponses.INTERNAL_SERVER_ERROR("Parent not found");
                    String[] parts = path.split("/");
                    for (String partName : parts) {
                        DocumentFile child = parent.findFile(partName);
                        if (child != null) {
                            if (!child.isDirectory()) return StandardResponses.INTERNAL_SERVER_ERROR("Not a directory");
                        } else {
                            child = parent.createDirectory(partName);
                            if (child == null) return StandardResponses.INTERNAL_SERVER_ERROR("Cannot create directory");
                        }
                        parent = child;
                    }
                }

            }
        }
        return StandardResponses.NO_CONTENT();
    }

    private static DirectUploadRequestDataConsumer.UploadException uploadCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DirectUploadRequestDataConsumer.UploadException) {
                return (DirectUploadRequestDataConsumer.UploadException) current;
            }
            current = current.getCause();
        }
        return null;
    }
}
