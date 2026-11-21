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
        //a renamed folder takes its whole subtree along, rules inside it included
        if (checkIsSubtreeForbidden(user, destPath, RENAME_OPERATION, Map.of(
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
        //a rename moves the whole subtree, so a lock on anything inside it has to agree too
        if (!locks.mayRemove(destPath, lockTokenProvided) || !locks.mayWrite(newPath, lockTokenProvided)) {
            return new Response(423, "Locked");
        }

        //File.renameTo replaces an existing file on Linux and Android: renaming "a.txt" to
        //"b.txt" silently destroyed b.txt, with no right on b.txt checked at all. A rename never
        //overwrites; moving over a file is what /api/file/move is for
        if (nameIsTakenBySomethingElse(destFile, newName)) {
            return StandardResponses.CONFLICT();
        }

        if (destFile.renameTo(newName)) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }

    /**
     * Whether a sibling other than {@code file} itself is called exactly {@code newName}. Exact
     * names from the listing, not {@code findFile}: on a case-insensitive volume findFile("a.txt")
     * finds "A.txt" itself, so changing only the case would be refused, while on Linux "A.txt"
     * and "a.txt" are two files and the second must not be overwritten.
     */
    static boolean nameIsTakenBySomethingElse(DocumentFile file, String newName) {
        if (newName.equals(file.getName())) return false;
        DocumentFile parent = file.getParentFile();
        if (parent == null) return false;
        DocumentFile[] siblings = parent.listFiles();
        if (siblings == null) return false;
        for (DocumentFile sibling : siblings) {
            if (newName.equals(sibling.getName())) return true;
        }
        return false;
    }
}
