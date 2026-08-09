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
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.auth.web.WebAuthManager;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticFileRequestHandler;
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.handlers.files.webdav.WebDavCopyMoveHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavDeleteHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavLockHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavMkColHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavPropFindHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavPropPatchHelper;
import com.phlox.simpleserver.handlers.files.webdav.WebDavPutHelper;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FilesRequestHandler extends StaticFileRequestHandler {
    public static final String EDITING_NOT_ALLOWED = "Editing not allowed";
    final String template;
    public boolean renderFolders = false;
    public boolean allowEditing = false;

    private final SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;
    private final List<String> allowedMethods =
            List.of(Request.METHOD_GET, Request.METHOD_HEAD, Request.METHOD_OPTIONS);
    private final List<String> allowedMethodsIfWebDav =
            List.of(Request.METHOD_GET, Request.METHOD_HEAD, Request.METHOD_OPTIONS,
                    Request.METHOD_PROP_FIND, Request.METHOD_MKCOL, Request.METHOD_DELETE,
                    Request.METHOD_COPY, Request.METHOD_MOVE, Request.METHOD_PUT,
                    Request.METHOD_PROP_PATCH, Request.METHOD_LOCK, Request.METHOD_UNLOCK);

    private final WebDavPropFindHelper propFindHelper;
    private final WebDavPropPatchHelper propPatchHelper;
    private final WebDavMkColHelper mkColHelper;
    private final WebDavDeleteHelper deleteHelper;
    private final WebDavCopyMoveHelper copyMoveHelper;
    private final WebDavPutHelper putHelper;
    private final WebDavLockHelper lockHelper;

    public FilesRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore, LockManager locks) {
        super(config, authManager, userStore, locks);
        propFindHelper = new WebDavPropFindHelper(platformUtils, userStore, locks);
        propPatchHelper = new WebDavPropPatchHelper(platformUtils, userStore, locks);
        mkColHelper = new WebDavMkColHelper(platformUtils, userStore, locks);
        deleteHelper = new WebDavDeleteHelper(platformUtils, userStore, locks);
        copyMoveHelper = new WebDavCopyMoveHelper(platformUtils, userStore, locks);
        putHelper = new WebDavPutHelper(platformUtils, userStore, locks);
        lockHelper = new WebDavLockHelper(platformUtils, userStore, locks);

        try (InputStream is = platformUtils.openAssetStream("file-browser.html")) {
            template = new String(Utils.readAllBytes(is), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Can not read assets");
        }
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (config.getWebDavSupport()) {
            if (!allowedMethodsIfWebDav.contains(request.method)) {
                return StandardResponses.METHOD_NOT_ALLOWED(allowedMethodsIfWebDav.toArray(new String[0]));
            }
        } else {
            if (!allowedMethods.contains(request.method)) {
                return StandardResponses.METHOD_NOT_ALLOWED(allowedMethods.toArray(new String[0]));
            }
        }

        String destPath = request.path;
        DocumentFile root = config.getRootDir();
        User user = checkUser(context);
        DocumentFile file = DocumentFileUtils.findChildByPath(root, destPath, user);
        DocumentFile userRoot = (user != null && user.rootDir != null) ?
                DocumentFileUtils.checkOrCreateUserDir(root, user.rootDir) : root;
        if (userRoot == null) throw new IllegalStateException("Can not locate user root dir");

        switch (request.method) {
            case Request.METHOD_OPTIONS:
                return prepareOptionsResponse(file, config);
            case Request.METHOD_PROP_FIND:
                return propFindHelper.handlePropFind(this, context, request, userRoot, file,
                        destPath, user);
            case Request.METHOD_PROP_PATCH:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return propPatchHelper.handlePropPatch(this, context, request, userRoot, file,
                        destPath, user);
            case Request.METHOD_MKCOL:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return mkColHelper.handleMkcolRequest(this, request, userRoot, file, user);
            case Request.METHOD_DELETE:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return deleteHelper.handleDeleteRequest(this, request, userRoot, file, user);
            case Request.METHOD_COPY:
            case Request.METHOD_MOVE:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return copyMoveHelper.handleCopyMoveRequest(this, request, userRoot, file, user);
            case Request.METHOD_PUT:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return putHelper.handlePutRequest(context, this, request, userRoot, file, user);
            case Request.METHOD_LOCK:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return lockHelper.handleLockRequest(context, this, request, userRoot, file, user);
            case Request.METHOD_UNLOCK:
                if (!config.getAllowEditing())
                    return StandardResponses.FORBIDDEN(EDITING_NOT_ALLOWED);
                return lockHelper.handleUnlockRequest(this, request, userRoot, file, user);
        }

        if (file == null) {
            return StandardResponses.NOT_FOUND();
        }
        boolean forceShowContents = request.queryParams.containsKey("forceContents");
        boolean canRedirectToIndex = !forceShowContents &&
                config.getRedirectToIndex() &&
                file.isDirectory() &&
                file.findFile("index.html") != null;
        if (file.isDirectory() && renderFolders && !canRedirectToIndex) {
            if (checkIsForbidden(user,
                    destPath, FileListRequestHandler.LIST_CONTENTS_OPERATION, Map.of(
                            "sort", "default",
                            "search", "",
                            "sort-reversed", "false"
                    ),
                    User.FileSystemRights.LIST_CONTENTS))
                return StandardResponses.FORBIDDEN();
            JSONArray json = FileListRequestHandler.prepareFileListJson(destPath,
                    null, null, null, user, config);
            ArrayList<FileModel> files = new ArrayList<>(Objects.requireNonNull(json).length());
            for (int i = 0; i < json.length(); i++) {
                files.add(new FileModel(json.getJSONObject(i), destPath));
            }

            boolean needAuthBlock = authManager instanceof WebAuthManager && user != null;

            HTMLTemplateResponse response = new HTMLTemplateResponse(template, new HashMap<String, Object>() {{
                put("current_path", request.path);
                put("allowEditing", allowEditing && (user == null || authManager.getUserRightsEvaluator().hasAnyFileEditingRights(user)));
                put("thumbnails_support", SHTTPSApp.getInstance().platformUtils.isThumbnailsSupported());
                put("list_no_script", files);
                put("mediastore", root.getUri().startsWith("mediastore://"));
                put("needAuthBlock", needAuthBlock);
                put("hasUser", user != null && !user.isGuest());
                put("dbConnected", config.isDatabaseEnabled());
                put("screenShare", config.isScreenShareEnabled());
            }});

            if (request.method.equals(Request.METHOD_HEAD)) {
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

    public static Response prepareOptionsResponse(DocumentFile fileOrDirectory, SHTTPSConfig config) {
        Response response = new Response(200, StandardResponses.PHRASE_OK);
        String allowedMethods = "OPTIONS";
        if (config.getWebDavSupport()) {
            if (fileOrDirectory == null) {
                allowedMethods += ", MKCOL, PUT, LOCK, UNLOCK";
            } else if (fileOrDirectory.isDirectory()) {
                allowedMethods += ", GET, HEAD, PROPFIND, MKCOL, DELETE, COPY, MOVE, PROPPATCH, LOCK, UNLOCK";
            } else {
                allowedMethods += ", GET, HEAD, PROPFIND, DELETE, COPY, MOVE, PUT, PROPPATCH, LOCK, UNLOCK";
            }
        } else {
            if (fileOrDirectory != null) {
                allowedMethods += ", GET, HEAD";
            }
        }
        response.headers.add("Allow", allowedMethods);

        if (config.getWebDavSupport()) {
            response.headers.add("DAV", "1, 2");
            response.headers.add("MS-Author-Via", "DAV");
        }
        return response;
    }

    private static class FileModel {
        public String path;
        public String name;
        public boolean isFolder;

        public FileModel(JSONObject json, String parentPath) throws JSONException {
            name = json.getString("name");
            if ("..".equals(name)) {
                int lastSlashPos = parentPath.lastIndexOf('/');
                if (lastSlashPos >= 0 && lastSlashPos < (parentPath.length() - 1)) {
                    path = parentPath.substring(0, lastSlashPos + 1);
                } else path = parentPath;
            } else {
                if (!parentPath.endsWith("/")) {
                    parentPath += "/";
                }
                path = parentPath + name;
            }
            isFolder = json.getBoolean("directory");
        }
    }
}
