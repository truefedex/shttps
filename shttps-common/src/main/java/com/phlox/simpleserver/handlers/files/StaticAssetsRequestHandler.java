package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedInputStream;
import java.util.Date;

public class StaticAssetsRequestHandler implements RequestHandler {
    private final String baseAssetsPath;
    private final String baseRequestPath;
    private final SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;

    public StaticAssetsRequestHandler(String baseAssetsPath, String baseRequestPath) {
        this.baseAssetsPath = baseAssetsPath;
        this.baseRequestPath = baseRequestPath;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }

        String destPath = request.path;
        if (baseRequestPath != null) {
            if (!destPath.startsWith(baseRequestPath)) {
                return StandardResponses.NOT_FOUND();
            }
            destPath = destPath.substring(baseRequestPath.length());
        }
        // Check if the path contains ".." to prevent directory traversal
        if (Utils.contains(destPath.split("/"), "..")) {
            return StandardResponses.FORBIDDEN("Path contains '..'");
        }

        destPath = baseAssetsPath + (destPath.startsWith("/") ? destPath : ("/" + destPath));
        long assetSize = platformUtils.getAssetSize(destPath);
        if (assetSize == -1) {
            return StandardResponses.NOT_FOUND();
        }
        long assetLastModified = platformUtils.getAssetLastModified(destPath);
        String ifModifiedSinceStr = request.headers.get(Request.HEADER_IF_MODIFIED_SINCE);
        if (ifModifiedSinceStr != null) {
            Date date = null;
            try {
                date = HTTPUtils.getHTTPDateFormat().parse(ifModifiedSinceStr);
            } catch (Exception e) { e.printStackTrace(); }

            if (date != null && (assetLastModified / 1000) <= (date.getTime() / 1000)) {//comparing skipping milliseconds
                return StandardResponses.NOT_MODIFIED();
            }
        }
        String type = "application/octet-stream";

        String extension = null;
        int i = destPath.lastIndexOf('.');
        if (i > 0 && i < destPath.length() - 1) {
            extension = destPath.substring(i + 1);
        }
        if (extension != null) {
            String mimeTypeFromExtension = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
            if (mimeTypeFromExtension != null) {
                type = mimeTypeFromExtension;
            }
        }

        Response response;
        if (isHead) {
            response = new Response(type, assetSize, null);
        } else {
            response = new Response(type, assetSize, new BufferedInputStream(platformUtils.openAssetStream(destPath)));
        }

        response.headers.put(Response.HEADER_LAST_MODIFIED, HTTPUtils.getHTTPDateFormat().format(new Date( assetLastModified )));

        return response;
    }

    @Override
    public boolean canHandle(String path, String method) {
        boolean isHead = method.equals(Request.METHOD_HEAD);
        if (!method.equals(Request.METHOD_GET) && !isHead) {
            return false;
        }
        String destPath = path;
        if (baseRequestPath != null) {
            if (!destPath.startsWith(baseRequestPath)) {
                return false;
            }
            destPath = destPath.substring(baseRequestPath.length());
        }
        // Check if the path contains ".." to prevent directory traversal
        if (Utils.contains(destPath.split("/"), "..")) {
            return false;
        }

        destPath = baseAssetsPath + (destPath.startsWith("/") ? destPath : ("/" + destPath));
        long assetSize = platformUtils.getAssetSize(destPath);
        return assetSize != -1;
    }
}
