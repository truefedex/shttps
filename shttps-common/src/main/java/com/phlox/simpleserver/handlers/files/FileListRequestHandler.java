package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class FileListRequestHandler implements RequestHandler {
    private final SHTTPSConfig config;

    public FileListRequestHandler(SHTTPSConfig config) {
        this.config = config;
    }

    public static JSONArray prepareFileListJson(String path, String sort, String sortReversedParam) throws JSONException {
        SHTTPSConfig config = SHTTPSApp.getInstance().config;
        if (sort == null) {
            sort = "default";
        }
        boolean sortReversed = "true".equals(sortReversedParam);
        final int sortMultiplier = sortReversed ? -1 : 1;
        DocumentFile root = config.getRootDir();
        final DocumentFile destFile = DocumentFileUtils.findChildByPath(root, path);
        if ((destFile == null) || !destFile.isDirectory()) return null;
        DocumentFile[] files = destFile.listFiles();
        JSONArray json = new JSONArray();

        if (!destFile.equals(root)) {
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
                            return 1 * sortMultiplier;
                        }
                        if (type1.startsWith("video/") && !type2.startsWith("video/")) {
                            return -1 * sortMultiplier;
                        } else if (type2.startsWith("video/") && !type1.startsWith("video/")) {
                            return 1 * sortMultiplier;
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
            json.put(jsonFile);
        }
        return json;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        String path = request.queryParams.get("path");
        if (path == null) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/"+path;
        }
        String sort = request.queryParams.get("sort");
        String sortReversedParam = request.queryParams.get("sort-reversed");

        JSONArray json = prepareFileListJson(path, sort, sortReversedParam);

        if (json == null) {
            return StandardResponses.NOT_FOUND();
        }

        return new TextResponse(json.toString(), "application/json");
    }
}
