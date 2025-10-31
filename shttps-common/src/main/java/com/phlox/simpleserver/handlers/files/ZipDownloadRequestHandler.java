package com.phlox.simpleserver.handlers.files;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.AbstractDataStreamer;
import com.phlox.simpleserver.utils.DocumentFileUtils;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDownloadRequestHandler extends BaseFileRequestHandler {
    public ZipDownloadRequestHandler(SHTTPSConfig config, AuthManager authManager) {
        super(config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        if (!"application/x-www-form-urlencoded".equals(request.contentType)) return StandardResponses.BAD_REQUEST();
        User user = checkUser(context);
        if (checkIsForbidden(user, User.FileSystemRights.READ)) return StandardResponses.FORBIDDEN("Insufficient rights");
        context.requestBodyReader.readRequestBody(request);

        Integer compressionLevel;
        try {
            compressionLevel = Integer.parseInt(request.urlEncodedPostParams.get("level"));
        } catch (NumberFormatException ex) {
            compressionLevel = null;
        }
        if (compressionLevel != null && (compressionLevel < 0 || compressionLevel > 9)) {
            return StandardResponses.BAD_REQUEST("Invalid compression level");
        }

        List<String> excludedExtensions = new ArrayList<>();
        String excludedExtensionsStr = request.urlEncodedPostParams.get("uncompressed");
        if (excludedExtensionsStr != null) {
            excludedExtensionsStr = excludedExtensionsStr.toLowerCase();
            excludedExtensions.addAll(Arrays.asList(excludedExtensionsStr.split(",")));
        }

        String destPath = request.urlEncodedPostParams.get("path");
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath, user);
        if ((destFile == null) || !destFile.isDirectory()) return StandardResponses.NOT_FOUND();
        String filesStr = request.urlEncodedPostParams.get("files");
        if (filesStr == null) {
            return StandardResponses.BAD_REQUEST("No files specified");
        }
        JSONArray filesJArr = new JSONArray(filesStr);

        ZipDataStreamer streamer = new ZipDataStreamer(destFile, filesJArr, excludedExtensions, compressionLevel);
        streamer.startDataGenerationThread();

        Response response = new Response(streamer.getInputStream());
        response.setContentType("application/zip");
        String zipFileName = request.urlEncodedPostParams.containsKey("outFileName") ? request.urlEncodedPostParams.get("outFileName") : getTimestampedFilename();
        response.headers.put("Content-Disposition", "attachment; filename=\"" + zipFileName + "\"");

        return response;
    }

    private String getTimestampedFilename() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        String timestamp = sdf.format(new Date());
        return "files_" + timestamp + ".zip";
    }

    private static class ZipDataStreamer extends AbstractDataStreamer {
        private final DocumentFile baseFolder;
        private final JSONArray jFilesArray;
        private final Integer compressionLevel;
        private final List<String> excludedExtensions;

        public ZipDataStreamer(DocumentFile baseFolder, JSONArray jFilesArray,
                               List<String> excludedExtensions, Integer compressionLevel) {
            this.baseFolder = baseFolder;
            this.jFilesArray = jFilesArray;
            this.excludedExtensions = excludedExtensions;
            this.compressionLevel = compressionLevel;
        }

        @Override
        protected void generateData(OutputStream output) throws Exception {
            ZipOutputStream zipOutputStream = new ZipOutputStream(output);
            if (compressionLevel != null) {
                zipOutputStream.setLevel(compressionLevel);
            }

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

                ZipEntry entry = new ZipEntry(relativePath);

                String extension = file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase();
                if (excludedExtensions.contains(extension)) {
                    entry.setMethod(ZipEntry.STORED);
                    //need to calculate crc and size
                    CRC32 crc = new CRC32();
                    long size = 0;
                    try (InputStream in = file.openInputStream()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            crc.update(buffer, 0, read);
                            size += read;
                        }
                    }
                    entry.setSize(size);
                    entry.setCompressedSize(size);
                    entry.setCrc(crc.getValue());
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                try(InputStream fis = file.openInputStream()) {
                    zipOutputStream.putNextEntry(entry);
                    Utils.copyStream(fis, zipOutputStream);
                    zipOutputStream.closeEntry();
                }
            }
        }
    }
}
