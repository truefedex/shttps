package com.phlox.simpleserver.handlers.files;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.DocumentFileUtils;

import java.io.File;

public class RenameFileRequestHandler extends BaseFileRequestHandler {
    public RenameFileRequestHandler(SHTTPSConfig config, AuthManager authManager) {
        super(config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType) ) return StandardResponses.BAD_REQUEST();
        User user = checkUser(context);
        if (checkIsForbidden(user, User.FileSystemRights.CREATE,
                User.FileSystemRights.DELETE,
                User.FileSystemRights.READ,
                User.FileSystemRights.UPDATE)) return StandardResponses.FORBIDDEN("Insufficient rights");
        context.requestBodyReader.readRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        if (!destPath.startsWith("/")) {
            destPath = "/" + destPath;
        }
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);

        if (destFile == null) return StandardResponses.NOT_FOUND();
        String newName = request.urlEncodedPostParams.get("name");
        if (newName == null) {
            return StandardResponses.BAD_REQUEST("Parameter \"name\" not found");
        }
        if (newName.contains(File.separator)) {
            return StandardResponses.FORBIDDEN("Illegal name");
        }
        if (destFile.renameTo(newName)) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
