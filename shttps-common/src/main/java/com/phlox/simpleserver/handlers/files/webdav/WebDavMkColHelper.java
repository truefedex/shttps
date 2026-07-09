package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.BaseFileRequestHandler;
import com.phlox.simpleserver.handlers.files.NewFolderRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.Utils;

import java.util.Map;

public class WebDavMkColHelper extends WebDavHelpersBase {
    public WebDavMkColHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }

    public Response handleMkcolRequest(BaseFileRequestHandler baseHandler, Request request, DocumentFile userRoot, DocumentFile target, User user) {
        if (request.contentLength > 0) {
            return StandardResponses.UNSUPPORTED_MEDIA_TYPE();
        }

        // Already exists, it doesn't matter - file or folder
        if (target != null && target.exists()) {
            return new Response(405, "Method Not Allowed");
        }

        String path = request.path;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return new Response(409, "Conflict");
        }
        String parentPath = Utils.getParentPath(path);
        if (parentPath == null) {
            return simpleStatus(400, "Bad Request");
        }
        String name = path.substring(lastSlash + 1);
        DocumentFile parent = DocumentFileUtils.findChildByPath(userRoot, parentPath);
        if (parent == null || !parent.isDirectory()) {
            return new Response(409, "Conflict");
        }

        if (baseHandler.checkIsForbidden(user, parentPath, NewFolderRequestHandler.NEW_FOLDER_OPERATION, Map.of(
                "name", name
        ), User.FileSystemRights.CREATE))
            return StandardResponses.FORBIDDEN();

        String provided = extractLockTokenFromIf(request.headers.get("if"));
        if (!locks.mayWrite(path, provided)) {
            return simpleStatus(423, "Locked");
        }

        DocumentFile newDir = parent.createDirectory(name);
        if (newDir == null) {
            return new Response(403, "Forbidden");
        }

        return new Response(201, "Created");
    }
}
