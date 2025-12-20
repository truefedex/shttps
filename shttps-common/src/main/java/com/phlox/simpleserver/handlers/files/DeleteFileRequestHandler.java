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
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class DeleteFileRequestHandler extends BaseFileRequestHandler {
    public static final String DELETE_OPERATION = "DELETE";

    public DeleteFileRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore) {
        super(config, authManager, userStore);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_DELETE)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_DELETE});
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!"application/json".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        context.requestBodyReader.readRequestBody(request);

        JSONObject json = new JSONObject(request.body.toString());
        String destPath = json.getString("path");
        DocumentFile root = config.getRootDir();
        User user = checkUser(context);
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        JSONArray jArr = json.getJSONArray("files");

        Map<String, Object> operationParams = new HashMap<>();
        operationParams.put("files.size", jArr.length());
        operationParams.put("files[]", jArr.toString());

        if (checkIsForbidden(user, destPath, DELETE_OPERATION, operationParams,
                User.FileSystemRights.DELETE))
            return StandardResponses.FORBIDDEN();

        for (int i = 0; i < jArr.length(); i++) {
            String name = jArr.getString(i);
            DocumentFile file = destFile.findFile(name);
            if (file == null) {
                return StandardResponses.NOT_FOUND("Cannot find file: " + name);
            }
            long fileSize = file.length();
            if (!file.delete()) {
                return StandardResponses.INTERNAL_SERVER_ERROR("Cannot delete file: " + name);
            } else {
                if (user != null) {
                    userStore.updateUserAtomically(user.identity, (User u) -> {
                        u.usedStorage -= fileSize;
                        return u;
                    });
                }
            }
        }
        return StandardResponses.NO_CONTENT();
    }
}
