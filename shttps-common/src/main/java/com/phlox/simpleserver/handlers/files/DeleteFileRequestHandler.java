package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.server.utils.docfile.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

public class DeleteFileRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public DeleteFileRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_DELETE)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_DELETE});
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!"application/json".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        requestBodyReader.readRequestBody(request);

        JSONObject json = new JSONObject(request.body.toString());
        String destPath = json.getString("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);
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
