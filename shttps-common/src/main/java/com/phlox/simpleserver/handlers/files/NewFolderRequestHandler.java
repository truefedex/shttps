package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSConfig;

public class NewFolderRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public NewFolderRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType) ) return StandardResponses.BAD_REQUEST();
        requestParser.parseRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);

        if ((destFile == null) || !destFile.isDirectory())
            return StandardResponses.NOT_FOUND();
        String newFolderName = request.urlEncodedPostParams.get("name");
        if (newFolderName == null) {
            return StandardResponses.BAD_REQUEST("Parameter \"name\" not found");
        }
        DocumentFile newDir = destFile.createDirectory(newFolderName);
        if (newDir != null) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
