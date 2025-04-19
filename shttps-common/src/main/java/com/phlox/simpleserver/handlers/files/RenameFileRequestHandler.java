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

public class RenameFileRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public RenameFileRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!config.getAllowEditing()) return StandardResponses.FORBIDDEN("Editing not allowed");
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType) ) return StandardResponses.BAD_REQUEST();
        requestBodyReader.readRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        if (!destPath.startsWith("/")) {
            destPath = "/" + destPath;
        }
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);

        if (destFile == null) return StandardResponses.NOT_FOUND();
        String newName = request.urlEncodedPostParams.get("name");
        if (newName == null) {
            return StandardResponses.BAD_REQUEST("Parameter \"name\" not found");
        }
        if (destFile.renameTo(newName)) {
            return StandardResponses.NO_CONTENT();
        } else {
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
