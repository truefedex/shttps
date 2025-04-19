package com.phlox.simpleserver.handlers.main;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.HTMLTemplateResponse;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;

import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticFileRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class MainRequestHandler extends StaticFileRequestHandler {
    final String template;
    public boolean renderFolders = false;
    public boolean allowEditing = false;

    private SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;

    public MainRequestHandler(DocumentFile root) {
        super(root);

        try (InputStream is = platformUtils.openAssetStream("file-browser.html")) {
            template = new String(Utils.readAllBytes(is));
        } catch (IOException e) {
            throw new RuntimeException("Can not read assets");
        }
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }

        String destPath = request.path;
        DocumentFile file = DocumentFileUtils.findChildByPath(root, destPath);
        if (file == null) {
            return StandardResponses.NOT_FOUND();
        }
        if (file.isDirectory() && renderFolders) {
            JSONArray json = FileListRequestHandler.prepareFileListJson(destPath, null, null);
            ArrayList<FileModel> files = new ArrayList<>(Objects.requireNonNull(json).length());
            for (int i = 0; i < json.length(); i++) {
                files.add(new FileModel(json.getJSONObject(i), destPath));
            }

            HTMLTemplateResponse response = new HTMLTemplateResponse(template, new HashMap<String, Object>() {{
                put("current_path", request.path);
                put("allowEditing", allowEditing);
                put("thumbnails_support", SHTTPSApp.getInstance().platformUtils.isThumbnailsSupported());
                put("list_no_script", files);
                put("mediastore", root.getUri().startsWith("mediastore://"));
            }});

            if (isHead) {
                return new Response(response.getContentType(), response.getContentLength(), null);
            } else {
                return response;
            }
        }

        return super.handleRequest(context, request, requestBodyReader);
    }

    @Override
    public boolean canHandle(String path, String method) {
        boolean isHead = method.equals(Request.METHOD_HEAD);
        if (!method.equals(Request.METHOD_GET) && !isHead) {
            return false;
        }

        DocumentFile file = DocumentFileUtils.findChildByPath(root, path);
        if (file == null) {
            return false;
        }
        if (file.isDirectory()) {
            return renderFolders;
        }
        return true;
    }

    private static class FileModel {
        public String path;
        public String name;
        public boolean isFolder;

        public FileModel(String path, String name, boolean isFolder) {
            this.path = path;
            this.name = name;
            this.isFolder = isFolder;
        }

        public FileModel(JSONObject json, String parentPath) throws JSONException {
            name = json.getString("name");
            if ("..".equals(name)) {
                int lastSlashPos = parentPath.lastIndexOf('/');
                if (lastSlashPos >= 0 && lastSlashPos < (parentPath.length() - 1)) {
                    path = parentPath.substring(0, lastSlashPos + 1);
                } else path = parentPath;
            } else {
                path = parentPath + name;
            }
            isFolder = json.getBoolean("directory");
        }
    }
}
