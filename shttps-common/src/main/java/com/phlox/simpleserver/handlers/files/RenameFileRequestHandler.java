package com.phlox.simpleserver.handlers.files;

import static com.phlox.simpleserver.handlers.files.webdav.WebDavHelpersBase.extractLockTokenFromIf;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.Utils;

import java.io.File;
import java.util.Map;

public class RenameFileRequestHandler extends BaseFileRequestHandler {
    public static final String RENAME_OPERATION = "RENAME";

    public RenameFileRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore, LockManager locks) {
        super(config, authManager, userStore, locks);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType) ) return StandardResponses.BAD_REQUEST();

        context.requestBodyReader.readRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        if (!destPath.startsWith("/")) {
            destPath = "/" + destPath;
        }
        DocumentFile root = config.getRootDir();
        User user = checkUser(context);
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);

        if (destFile == null) return StandardResponses.NOT_FOUND();
        String newName = request.urlEncodedPostParams.get("name");
        if (newName == null) {
            return StandardResponses.BAD_REQUEST("Parameter \"name\" not found");
        }
        if (newName.contains(File.separator)) {
            return StandardResponses.FORBIDDEN("Illegal name");
        }
        if (checkIsForbidden(user, destPath, RENAME_OPERATION, Map.of(
                "name", newName
                ), User.FileSystemRights.CREATE,
                User.FileSystemRights.DELETE,
                User.FileSystemRights.READ,
                User.FileSystemRights.UPDATE)) return StandardResponses.FORBIDDEN();

        String parentPath = Utils.getParentPath(destPath);
        if (parentPath == null) {
            return StandardResponses.BAD_REQUEST();
        }
        String newPath = Utils.joinPaths(parentPath, newName);
        String lockTokenProvided = extractLockTokenFromIf(request.headers.get("if"));
        if (!locks.mayWrite(destPath, lockTokenProvided) || !locks.mayWrite(newPath, lockTokenProvided)) {
            return new Response(423, "Locked");
        }

        if (destFile.renameTo(newName)) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
