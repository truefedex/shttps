package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.AbstractDataStreamer;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDownloadRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public ZipDownloadRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!"application/x-www-form-urlencoded".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        requestBodyReader.readRequestBody(request);

        String destPath = request.urlEncodedPostParams.get("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        JSONArray jArr = new JSONArray(request.urlEncodedPostParams.get("files"));

        ZipDataStreamer streamer = new ZipDataStreamer(destFile, jArr);
        streamer.startDataGenerationThread();

        Response response = new Response(streamer.getInputStream());
        response.setContentType("application/zip");

        return response;
    }

    private class ZipDataStreamer extends AbstractDataStreamer {
        DocumentFile baseFolder;
        JSONArray jFilesArray;

        public ZipDataStreamer(DocumentFile baseFolder, JSONArray jFilesArray) {
            this.baseFolder = baseFolder;
            this.jFilesArray = jFilesArray;
        }

        @Override
        protected void generateData(PipedOutputStream output) throws Exception {
            ZipOutputStream zipOutputStream = new ZipOutputStream(output);

            for (int i = 0; i < jFilesArray.length(); i++) {
                String name = jFilesArray.getString(i);
                if (name.contains("..")) throw new SecurityException("Invalid file name: " + name);
                DocumentFile file = baseFolder.findFile(name);
                if (file == null) {
                    continue;//skip non-existing files
                } else {
                    zipFileOrDirRecursively(baseFolder, file, zipOutputStream);
                }
            }

            zipOutputStream.close();
        }

        private void zipFileOrDirRecursively(DocumentFile baseFolder, DocumentFile file, ZipOutputStream zipOutputStream) throws IOException {
            if (file.isDirectory()) {
                DocumentFile[] files = file.listFiles();
                for (DocumentFile f: files) {
                    zipFileOrDirRecursively(baseFolder, f, zipOutputStream);
                }
            } else {
                String relativePath = baseFolder.getRelativePath(file);
                if (relativePath == null) return;
                relativePath = relativePath.replace('\\', '/');
                if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                //InputStream fis = file.openInputStream();
                try(InputStream fis = file.openInputStream()) {
                    ZipEntry entry = new ZipEntry(relativePath);
                    zipOutputStream.putNextEntry(entry);
                    Utils.copyStream(fis, zipOutputStream);
                    zipOutputStream.closeEntry();
                }
            }
        }
    }
}
