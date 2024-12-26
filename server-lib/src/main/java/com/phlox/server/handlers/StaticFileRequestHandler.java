package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.platform.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class StaticFileRequestHandler implements RequestHandler {
    public File root;

    public StaticFileRequestHandler(File root) {
        this.root = root;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestParser requestParser) throws Exception {
        File root = this.root;
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }
        String destPath = root.getAbsolutePath() + request.path;
        File file = new File(destPath);
        if (!file.getCanonicalPath().startsWith(root.getCanonicalPath())) {
            return StandardResponses.FORBIDDEN("Access file outside root");
        }
        if (file.isDirectory()) {
            file = new File(file, "index.html");
        }
        if ((!file.exists()) || file.isDirectory()) {
            return null;
        }
        String ifModifiedSinceStr = request.headers.get(Request.HEADER_IF_MODIFIED_SINCE);
        if (ifModifiedSinceStr != null) {
            Date date = null;
            try {
                date = HTTPUtils.getHTTPDateFormat().parse(ifModifiedSinceStr);
            } catch (Exception e) { e.printStackTrace(); }

            if (date != null && (file.lastModified() / 1000) <= (date.getTime() / 1000)) {//comparing skipping milliseconds
                Response response = new Response();
                response.code = 304;
                response.phrase = "Not Modified";
                return response;
            }
        }
        String type = "application/octet-stream";
        String extension = MimeTypeMap.getInstance().getFileExtensionFromUrl(file.getAbsolutePath());
        if (extension != null) {
            type = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
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

    private Response makeFileResponse(File file, String type, Request request) throws FileNotFoundException {
        String rangesHeader = request.headers.get(Request.HEADER_RANGE);
        List<HTTPUtils.Range> ranges = null;
        if (rangesHeader != null) {
            try {
                ranges = HTTPUtils.parseRangeHeader(rangesHeader, file.length());
            } catch (Exception e) {}
        }

        Response response;
        if (ranges != null) {
            //currently only single-range requests supported
            response = new RangedFileResponse(type, ranges.get(0).length, new FileInputStream(file));
            response.code = 206;
            response.phrase = "Partial Content";
            response.headers.put(Response.HEADER_CONTENT_RANGE, "bytes " + ranges.get(0).start + "-" + ranges.get(0).end + "/" + file.length());
            ((RangedFileResponse)response).file = file;
            ((RangedFileResponse)response).ranges = ranges;
        } else {
            response = new Response(type, file.length(), new BufferedInputStream(new FileInputStream(file)));
        }
        return response;
    }

    public static class RangedFileResponse extends Response {
        public List<HTTPUtils.Range> ranges;
        public File file;

        public RangedFileResponse(String contentType, long contentLength, InputStream stream) {
            super(contentType, contentLength, stream);
            this.file = file;
        }

        @Override
        public void writeOut(OutputStream output) throws IOException {
            String header = makeResponseHeader();
            try (InputStream input = getStream()) {
                output.write(header.getBytes());
                output.flush();
                input.skip(ranges.get(0).start);
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
