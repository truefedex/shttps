package com.phlox.simpleserver.handlers.files.upload;

import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.DefaultBinaryDataConsumer;
import com.phlox.server.request.Request;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public class DirectUploadRequestDataConsumer extends DefaultBinaryDataConsumer {
    private SHTTPSConfig config;

    public DirectUploadRequestDataConsumer(SHTTPSConfig config) {
        this.config = config;
    }

    @Override
    public OutputStream prepareBinaryOutputForMultipartData(Request request, String contentType, String name, String fileName, Map<String, String> partHeaders) throws IOException {
        if (!config.getAllowEditing()) return null;
        DocumentFile root = config.getRootDir();
        String destPath = request.queryParams.get("path");
        if (name == null) return null;
        if (destPath == null) return null;
        if (name.equals("files[]")) {
            if (fileName.contains("..")) throw new SecurityException("Invalid file name");
            String fullDestPath = destPath;
            int pathSepLastIndex = fileName.lastIndexOf('/');
            if (pathSepLastIndex != -1) {
                fullDestPath = destPath + "/" + fileName.substring(0, pathSepLastIndex);
                fileName = fileName.substring(pathSepLastIndex + 1);
            }
            DocumentFile uploadDir = DocumentFileUtils.findChildByPath(root, fullDestPath);
            if (uploadDir == null) {
                //try to find parent dir and create missing dirs
                DocumentFile parent = DocumentFileUtils.findChildByPath(root, destPath);
                if (parent == null) return null;
                String[] parts = fullDestPath.substring(destPath.length() + 1).split("/");
                for (String part : parts) {
                    DocumentFile child = parent.findFile(part);
                    if (child != null) {
                        if (!child.isDirectory()) return null;
                    } else {
                        child = parent.createDirectory(part);
                        if (child == null) return null;
                    }
                    parent = child;
                }
                uploadDir = parent;
            }
            String type = null;
            String extension = Utils.getFileExtensionFromFilename(fileName);
            if (extension != null) {
                type = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
            }
            if (type == null) {
                type = "application/octet-stream";
            }
            DocumentFile file = uploadDir.findFile(fileName);
            if (file == null) {
                file = uploadDir.createFile(type, fileName);
                if (file == null) return null;
            }
            return new BufferedOutputStream(SHTTPSApp.getInstance().platformUtils.openOutputStream(file.getUri()));
        } else if (name.equals("emptyDirs[]")) {
            return super.prepareBinaryOutputForMultipartData(request, contentType, name, fileName, partHeaders);
        }
        return null;
    }
}
