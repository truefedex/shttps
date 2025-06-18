package com.phlox.simpleserver.handlers.main;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.HTMLTemplateResponse;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;

import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.web.WebAuthManager;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticFileRequestHandler;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class FilesRequestHandler extends StaticFileRequestHandler {
    final String template;
    public boolean renderFolders = false;
    public boolean allowEditing = false;

    private SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;

    public FilesRequestHandler(SHTTPSConfig config, AuthManager authManager) {
        super(config, authManager);

        try (InputStream is = platformUtils.openAssetStream("file-browser.html")) {
            template = new String(Utils.readAllBytes(is), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Can not read assets");
        }
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }
        User user = checkUser(context);
        if (checkIsForbidden(user, User.FileSystemRights.READ)) return StandardResponses.FORBIDDEN("Insufficient rights");

        String destPath = request.path;
        DocumentFile root = config.getRootDir();
        DocumentFile file = DocumentFileUtils.findChildByPath(root, destPath, user);
        if (file == null) {
            return StandardResponses.NOT_FOUND();
        }
        boolean forceShowContents = request.queryParams.containsKey("forceContents");
        boolean canRedirectToIndex = !forceShowContents && config.getRedirectToIndex() && file.isDirectory() && file.findFile("index.html") != null;
        if (file.isDirectory() && renderFolders && !canRedirectToIndex) {
            if (checkIsForbidden(user, User.FileSystemRights.LIST_CONTENTS)) return StandardResponses.FORBIDDEN("Insufficient rights");
            JSONArray json = FileListRequestHandler.prepareFileListJson(destPath, null, null, user, config);
            ArrayList<FileModel> files = new ArrayList<>(Objects.requireNonNull(json).length());
            for (int i = 0; i < json.length(); i++) {
                files.add(new FileModel(json.getJSONObject(i), destPath));
            }
            
            boolean displayMenuButton = authManager instanceof WebAuthManager && user != null;

            HTMLTemplateResponse response = new HTMLTemplateResponse(template, new HashMap<String, Object>() {{
                put("current_path", request.path);
                put("allowEditing", allowEditing && (user == null || user.hasAnyFileEditingRights()));
                put("thumbnails_support", SHTTPSApp.getInstance().platformUtils.isThumbnailsSupported());
                put("list_no_script", files);
                put("mediastore", root.getUri().startsWith("mediastore://"));
                put("display_menu_button", displayMenuButton);
                put("hasUser", user != null && !user.isGuest());
            }});

            if (isHead) {
                return new Response(response.getContentType(), response.getContentLength(), null);
            } else {
                return response;
            }
        } else if (canRedirectToIndex) {
            if (!destPath.endsWith("/")) {
                destPath += "/";
                return StandardResponses.REDIRECT(destPath, 301);
            } else {
                destPath += "index.html";
                request.path = destPath;
            }
        }

        return super.handleRequest(context, request);
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
