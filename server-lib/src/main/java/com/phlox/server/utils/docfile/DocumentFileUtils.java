package com.phlox.server.utils.docfile;

public final class DocumentFileUtils {
    public DocumentFileUtils() {}

    public static DocumentFile findChildByPath(DocumentFile root, String path) {
        return findChildByPath(root, path, null);
    }

    public static DocumentFile findChildByPath(DocumentFile root, String path, String pathPrefix) {
        if (pathPrefix != null) {
            path = pathPrefix + path;
        }
        if ("/".equals(path) || "".equals(path)) {
            return root;
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        DocumentFile current = root;
        for (String part: parts) {
            current = current.findFile(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public static boolean isContentUri(String uri) {
        return uri != null && uri.startsWith("content://");
    }
}
