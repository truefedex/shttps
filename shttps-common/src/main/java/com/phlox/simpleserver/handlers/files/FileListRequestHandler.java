package com.phlox.simpleserver.handlers.files;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.DocumentFileUtils;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

public class FileListRequestHandler extends BaseFileRequestHandler {
    public static final String LIST_CONTENTS_OPERATION = "LIST_CONTENTS";

    public FileListRequestHandler(SHTTPSConfig config, AuthManager authManager, UserStore userStore) {
        super(config, authManager, userStore);
    }

    public static JSONArray prepareFileListJson(String path, String sort, String sortReversedParam,
                                                String searchPattern,
                                                User user, SHTTPSConfig config) throws JSONException {
        if (sort == null) {
            sort = "default";
        }
        boolean sortReversed = "true".equals(sortReversedParam);
        final int sortMultiplier = sortReversed ? -1 : 1;
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, path, user);
        if ((destFile == null) || !destFile.isDirectory()) return null;
        DocumentFile[] files;
        if (searchPattern != null && !searchPattern.isEmpty()) {
            ArrayList<DocumentFile> searchResults = DocumentFileUtils.searchRecursive(
                    destFile, searchPattern, 0);
            files = new DocumentFile[searchResults.size()];
            searchResults.toArray(files);
        } else {
            files = destFile.listFiles();
            if (files == null) {
                files = new DocumentFile[0];
            }
        }
        JSONArray json = new JSONArray();

        if (!"/".equals(path)) {
            JSONObject jsonFile = new JSONObject();
            jsonFile.put("name", "..");
            jsonFile.put("directory", true);
            jsonFile.put("length", 0);
            jsonFile.put("modified", destFile.lastModified());
            json.put(jsonFile);
        }

        ArrayList<DocumentFile> filtered = new ArrayList<>(files.length);
        for (DocumentFile f : files) {
            if (f.getName() != null) {
                filtered.add(f);
            }
        }
        files = new DocumentFile[filtered.size()];
        filtered.toArray(files);

        Comparator<DocumentFile> comparator;
        switch (sort) {
            case "gallery":
                comparator = (f1, f2) -> {
                    int result = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                    if (result != 0) {
                        return result;
                    }
                    if (!f1.isDirectory()) {
                        String type1 = SHTTPSApp.getInstance().platformUtils.getMimeType(f1.getUri());
                        if (type1 == null) {
                            type1 = "application/octet-stream";
                        }
                        String type2 = SHTTPSApp.getInstance().platformUtils.getMimeType(f2.getUri());
                        if (type2 == null) {
                            type2 = "application/octet-stream";
                        }
                        if (type1.startsWith("image/") && !type2.startsWith("image/")) {
                            return -1 * sortMultiplier;
                        } else if (type2.startsWith("image/") && !type1.startsWith("image/")) {
                            return sortMultiplier;
                        }
                        if (type1.startsWith("video/") && !type2.startsWith("video/")) {
                            return -1 * sortMultiplier;
                        } else if (type2.startsWith("video/") && !type1.startsWith("video/")) {
                            return sortMultiplier;
                        }
                    }
                    return f1.getName().compareToIgnoreCase(f2.getName()) * sortMultiplier;
                };
                break;

            case "modified":
                comparator = (f1, f2) -> {
                    int result = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                    if (result != 0) {
                        return result;
                    }
                    result = Long.compare(f1.lastModified(), f2.lastModified()) * sortMultiplier;
                    if (result != 0) {
                        return result;
                    }
                    return f1.getName().compareToIgnoreCase(f2.getName()) * sortMultiplier;
                };
                break;

            case "size":
                comparator = (f1, f2) -> {
                    int result = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                    if (result != 0) {
                        return result;
                    }
                    result = Long.compare(f1.length(), f2.length()) * sortMultiplier;
                    if (result != 0) {
                        return result;
                    }
                    return f1.getName().compareToIgnoreCase(f2.getName()) * sortMultiplier;
                };
                break;

            default:
                comparator = (f1, f2) -> {
                    int result = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                    if (result != 0) {
                        return result;
                    }
                    return f1.getName().compareToIgnoreCase(f2.getName()) * sortMultiplier;
                };
                break;
        }
        Arrays.sort(files, comparator);

        for (DocumentFile file : files) {
            JSONObject jsonFile = new JSONObject();
            jsonFile.put("name", file.getName());
            jsonFile.put("directory", file.isDirectory());
            jsonFile.put("length", Utils.formatFileSize(file.length()));
            jsonFile.put("modified", file.lastModified());
            if (searchPattern != null && !searchPattern.isEmpty()) {
                String relativePath = destFile.getRelativePath(file);
                //remove file or folder name from the end
                if (relativePath.endsWith("/" + file.getName())) {
                    relativePath = relativePath.substring(0, relativePath.length() - file.getName().length());
                }
                //remove leading slash
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }
                jsonFile.put("relativePath", relativePath);
            }
            json.put(jsonFile);
        }
        return json;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        String path = request.queryParams.get("path");
        if (path == null) {
            path = "/";
        } else if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String sort = request.queryParams.get("sort");
        String sortReversedParam = request.queryParams.get("sort-reversed");
        String searchPattern = request.queryParams.get("search");

        User user = checkUser(context);
        if (checkIsForbidden(user, path, LIST_CONTENTS_OPERATION, Map.of(
                "sort", sort != null ? sort : "default",
                "search", searchPattern != null ? searchPattern : "",
                "sort-reversed", sortReversedParam != null ? sortReversedParam : "false"
        ), User.FileSystemRights.LIST_CONTENTS))
            return StandardResponses.FORBIDDEN();

        JSONArray json = prepareFileListJson(path, sort, sortReversedParam, searchPattern, user, config);

        if (json == null) {
            return StandardResponses.NOT_FOUND();
        }

        return new TextResponse(json.toString(), "application/json; charset=utf-8");
    }
}
