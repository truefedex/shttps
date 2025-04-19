package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSConfig;

import org.json.JSONArray;
import org.json.JSONObject;

public class MoveFileRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public MoveFileRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!"application/json".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        requestBodyReader.readRequestBody(request);

        JSONObject json = new JSONObject(request.body.toString());
        String destPath = json.getString("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        String action = json.getString("action");
        if ("copy".equals(action) || "move".equals(action)) {
            JSONArray jArr = json.getJSONArray("files");
            for (int i = 0; i < jArr.length(); i++) {
                String path = jArr.getString(i);
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                DocumentFile file = DocumentFileUtils.findChildByPath(root, path);
                if (file == null) {
                    return StandardResponses.NOT_FOUND();
                }
                if ("copy".equals(action)) {
                    if (!file.copyTo(destFile)) {
                        return StandardResponses.INTERNAL_SERVER_ERROR("Cannot copy file: " + file.getName());
                    }
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
