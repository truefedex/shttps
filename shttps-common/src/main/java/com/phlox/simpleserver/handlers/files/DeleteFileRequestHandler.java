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

import org.json.JSONArray;
import org.json.JSONObject;

public class DeleteFileRequestHandler extends BaseFileRequestHandler {

    public DeleteFileRequestHandler(SHTTPSConfig config, AuthManager authManager) {
        super(config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_DELETE)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_DELETE});
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!"application/json".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        User user = checkUser(context);
        if (checkIsForbidden(user, User.FileSystemRights.DELETE)) return StandardResponses.FORBIDDEN("Insufficient rights");
        context.requestBodyReader.readRequestBody(request);

        JSONObject json = new JSONObject(request.body.toString());
        String destPath = json.getString("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        JSONArray jArr = json.getJSONArray("files");
        for (int i = 0; i < jArr.length(); i++) {
            String name = jArr.getString(i);
            DocumentFile file = destFile.findFile(name);
            if (file == null || !file.delete()) {
                return StandardResponses.INTERNAL_SERVER_ERROR("Cannot delete file: " + name);
            }
        }
        return StandardResponses.NO_CONTENT();
    }
}
