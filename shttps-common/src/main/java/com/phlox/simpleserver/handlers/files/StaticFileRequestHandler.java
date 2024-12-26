package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;

public class StaticFileRequestHandler implements RequestHandler {
    public DocumentFile root;

    public StaticFileRequestHandler(DocumentFile root) {
        this.root = root;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        DocumentFile root = this.root;
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }
        String queryPath = request.queryParams.get("path");
        String destPath = queryPath != null ? queryPath : request.path;
        DocumentFile file = DocumentFileUtils.findChildByPath(root, destPath);
        if (file == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (file.isDirectory()) {
            DocumentFile indexFile = file.findFile("index.html");
            if (indexFile != null && !destPath.endsWith("/")) {
                return StandardResponses.MOVED_PERMANENTLY(destPath + "/");
            }
            file = indexFile;
        }
        if (file == null || file.isDirectory()) {
            return null;
        }
        String ifModifiedSinceStr = request.headers.get(Request.HEADER_IF_MODIFIED_SINCE);
        if (ifModifiedSinceStr != null) {
            Date date = null;
            try {
                date = HTTPUtils.getHTTPDateFormat().parse(ifModifiedSinceStr);
            } catch (Exception e) { e.printStackTrace(); }

            if (date != null && (file.lastModified() / 1000) <= (date.getTime() / 1000)) {//comparing skipping milliseconds
                return StandardResponses.NOT_MODIFIED();
            }
        }
        String type = "application/octet-stream";
        if (file.getType() != null) {
            type = file.getType();
        } else {
            String extension = MimeTypeMap.getInstance().getFileExtensionFromUrl(file.getUri());
            if (extension != null) {
                String mimeTypeFromExtension = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
                if (mimeTypeFromExtension != null) {
                    type = mimeTypeFromExtension;
                }
            }
        }

        Response response;
        if (isHead) {
            response = new Response(type, file.length(), null);
        } else {
            response = makeFileResponse(file, type, request);
        }

        response.headers.put(Response.HEADER_ACCEPT_RANGES, "bytes");
        response.headers.put(Response.HEADER_LAST_MODIFIED, HTTPUtils.getHTTPDateFormat().format(new Date( file.lastModified() )));

        return response;
    }

    private Response makeFileResponse(DocumentFile file, String type, Request request) throws IOException {
        Response response;
        String rangesHeader = request.headers.get(Request.HEADER_RANGE);
        List<HTTPUtils.Range> ranges = null;
        if (rangesHeader != null) {
            try {
                ranges = HTTPUtils.parseRangeHeader(rangesHeader, file.length());
            } catch (Exception e) {}
        }
        SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;

        if (ranges != null) {
            //currently only single-range requests supported
            response = new RangedFileResponse(type, ranges.get(0).length, platformUtils.openInputStream(file.getUri()));
            response.code = 206;
            response.phrase = "Partial Content";
            response.headers.put(Response.HEADER_CONTENT_RANGE, "bytes " + ranges.get(0).start + "-" + ranges.get(0).end + "/" + file.length());
            ((RangedFileResponse)response).ranges = ranges;
        } else {
            response = new Response(type, file.length(), new BufferedInputStream(platformUtils.openInputStream(file.getUri())));
        }

        if (request.queryParams.containsKey("download")) {
            response.headers.put(Response.HEADER_CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"");
        }
        return response;
    }

    public static class RangedFileResponse extends Response {
        public List<HTTPUtils.Range> ranges;

        public RangedFileResponse(String contentType, long contentLength, InputStream stream) {
            super(contentType, contentLength, stream);
        }

        @Override
        public void writeOut(OutputStream output) throws IOException {
            String header = makeResponseHeader();
            try (InputStream input = getStream()) {
                output.write(header.getBytes());
                output.flush();
                long skipped = input.skip(ranges.get(0).start);
                if (skipped != ranges.get(0).start) {
                    throw new IOException("Can not skip to start position");
                }
                long toRead = ranges.get(0).length;
                int read;
                byte[] buffer = new byte[20480];
                while ((read = input.read(buffer)) > 0) {
                    if ((toRead -= read) > 0) {
                        output.write(buffer, 0, read);
                    } else {
                        output.write(buffer, 0, (int) toRead + read);
                        break;
                    }
                }
            }
        }
    }
}
