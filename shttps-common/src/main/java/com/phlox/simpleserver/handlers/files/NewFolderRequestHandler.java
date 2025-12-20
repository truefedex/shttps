package com.phlox.simpleserver.handlers.files;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.DocumentFileUtils;

import java.util.Map;

public class NewFolderRequestHandler extends BaseFileRequestHandler {
    public static final String NEW_FOLDER_OPERATION = "NEW_FOLDER";

    public NewFolderRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore) {
        super(config, authManager, userStore);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType) ) return StandardResponses.BAD_REQUEST();

        context.requestBodyReader.readRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        DocumentFile root = config.getRootDir();
        User user = checkUser(context);
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);

        if ((destFile == null) || !destFile.isDirectory())
            return StandardResponses.NOT_FOUND();
        String newFolderName = request.urlEncodedPostParams.get("name");
        if (newFolderName == null) {
            return StandardResponses.BAD_REQUEST("Parameter \"name\" not found");
        }
        if (checkIsForbidden(user, destPath, NEW_FOLDER_OPERATION, Map.of(
                "name", newFolderName
        ), User.FileSystemRights.CREATE))
            return StandardResponses.FORBIDDEN();
        DocumentFile newDir = destFile.createDirectory(newFolderName);
        if (newDir != null) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
