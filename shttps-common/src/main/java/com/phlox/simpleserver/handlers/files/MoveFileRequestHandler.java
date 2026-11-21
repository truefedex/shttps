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
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class MoveFileRequestHandler extends BaseFileRequestHandler {
    public static final String COPY_MOVE_OPERATION = "COPY_MOVE";

    public MoveFileRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore, LockManager locks) {
        super(config, authManager, userStore, locks);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!"application/json".equals(request.contentType)) return StandardResponses.BAD_REQUEST();

        context.requestBodyReader.readRequestBody(request);

        JSONObject json = new JSONObject(request.body.toString());
        String destPath = json.getString("path");
        DocumentFile root = config.getRootDir();
        User user = checkUser(context);
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        String action = json.getString("action");
        if ("copy".equals(action) || "move".equals(action)) {
            JSONArray jArr = json.getJSONArray("files");

            Map<String, Object> operationParams = new HashMap<>();
            operationParams.put("action", action);
            operationParams.put("files.size", jArr.length());
            operationParams.put("files[]", jArr.toString());
            if (checkIsForbidden(user, destPath,
                    COPY_MOVE_OPERATION, operationParams,
                    User.FileSystemRights.CREATE,
                    User.FileSystemRights.DELETE,
                    User.FileSystemRights.READ,
                    User.FileSystemRights.UPDATE))
                return StandardResponses.FORBIDDEN();

            String lockTokenProvided = extractLockTokenFromIf(request.headers.get("if"));
            StorageQuota quota = new StorageQuota(user, userStore);

            for (int i = 0; i < jArr.length(); i++) {
                String path = jArr.getString(i);
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                DocumentFile file = DocumentFileUtils.findChildByPath(root, path, user);
                if (file == null) {
                    return StandardResponses.NOT_FOUND();
                }

                String destFilePath = Utils.joinPaths(destPath, file.getName());
                if ("move".equals(action) && !locks.mayWrite(path, lockTokenProvided)) {
                    return new Response(423, "Locked");
                }
                if (!locks.mayWrite(destFilePath, lockTokenProvided)) {
                    return new Response(423, "Locked");
                }

                if ("copy".equals(action)) {
                    long size = StorageQuota.sizeOf(file);
                    if (!quota.hasSpaceFor(destFile, size, 0)) {
                        return new Response(413, StorageQuota.ERROR_MSG_NO_SPACE_LEFT);
                    }
                    if (!file.copyTo(destFile)) {
                        return StandardResponses.INTERNAL_SERVER_ERROR("Cannot copy file: " + file.getName());
                    }
                    quota.addUsed(size);
                } else {
                    if (!file.moveTo(destFile)) {
                        return StandardResponses.INTERNAL_SERVER_ERROR("Cannot move file: " + file.getName());
                    }
                }
            }
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.BAD_REQUEST("Unknown action");
        }
    }
}
