package com.phlox.simpleserver.handlers.files.upload;

import com.phlox.server.request.FormDataPart;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.handlers.files.BaseFileRequestHandler;
import com.phlox.simpleserver.utils.DocumentFileUtils;

public class UploadFileRequestHandler extends BaseFileRequestHandler {

    public UploadFileRequestHandler(SHTTPSConfig config, AuthManager authManager) {
        super(config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_PUT)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_PUT});
        if (!Request.CONTENT_TYPE_MULTIPART_FORM.equals(request.contentType)) {
            return StandardResponses.BAD_REQUEST();
        }
        User user = checkUser(context);
        if (checkIsForbidden(user, User.FileSystemRights.CREATE,
                User.FileSystemRights.UPDATE)) return StandardResponses.FORBIDDEN("Insufficient rights");

        DocumentFile root = config.getRootDir();
        String destPath = request.queryParams.get("path");
        final DocumentFile uploadDir = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((uploadDir == null) || !uploadDir.isDirectory()) return StandardResponses.NOT_FOUND();
        if (!uploadDir.storageHasEnoughFreeSpaceFor(request.contentLength)) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Not enough free space");
        }
        try {
            context.requestBodyReader.readRequestBody(request, new DirectUploadRequestDataConsumer(config, user));//actual uploading
        } catch (SecurityException e) {
            return StandardResponses.FORBIDDEN("Access denied");
        }
        //we need also handle empty directory creation here
        for (FormDataPart part : request.multipartData) {
            if (part.name.equals("emptyDirs[]")) {
                String path = part.getDataAsString();
                if (path.contains("..")) return StandardResponses.FORBIDDEN("Invalid path");
                DocumentFile dir = DocumentFileUtils.findChildByPath(root, destPath + "/" + path, user);
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
}
