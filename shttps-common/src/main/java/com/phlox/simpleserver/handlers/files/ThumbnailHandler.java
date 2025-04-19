package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.util.Date;

public class ThumbnailHandler implements RequestHandler {
    private final SHTTPSConfig config;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public ThumbnailHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        if (!request.queryParams.containsKey("path"))
            return StandardResponses.BAD_REQUEST();
        String path = request.queryParams.get("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, path);
        if ((destFile == null) || destFile.isDirectory()) return StandardResponses.NOT_FOUND();

        String ifModifiedSinceStr = request.headers.get(Request.HEADER_IF_MODIFIED_SINCE);
        if (ifModifiedSinceStr != null) {
            Date date = null;
            try {
                date = HTTPUtils.getHTTPDateFormat().parse(ifModifiedSinceStr);
            } catch (Exception e) { logger.stackTrace(e); }

            if (date != null && (destFile.lastModified() / 1000) <= (date.getTime() / 1000)) {//comparing skipping milliseconds
                return StandardResponses.NOT_MODIFIED();
            }
        }

        try {
            SHTTPSPlatformUtils.ImageThumbnail thumbnail = SHTTPSApp.getInstance().platformUtils.getImageThumbnail(destFile.getUri());

            ByteArrayInputStream bais = new ByteArrayInputStream(thumbnail.data);
            Response response = new Response(thumbnail.mimeType, thumbnail.data.length, bais);
            response.headers.put(Response.HEADER_LAST_MODIFIED, HTTPUtils.getHTTPDateFormat().format(new Date( destFile.lastModified() )));
            return response;
        } catch (FileNotFoundException e) {
            return StandardResponses.NOT_FOUND();
        } catch (SHTTPSPlatformUtils.UnknownFormatException | SHTTPSPlatformUtils.UnsupportedImageFormatException e) {
            return StandardResponses.UNSUPPORTED_MEDIA_TYPE();
        } catch (Exception e) {
            logger.stackTrace(e);
            return StandardResponses.INTERNAL_SERVER_ERROR();
        }
    }
}
