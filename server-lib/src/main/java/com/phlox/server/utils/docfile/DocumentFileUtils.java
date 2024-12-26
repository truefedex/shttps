package com.phlox.server.utils.docfile;

public final class DocumentFileUtils {
    public DocumentFileUtils() {}

    public static DocumentFile findChildByPath(DocumentFile root, String path) {
        if ("/".equals(path) || "".equals(path)) {
            return root;
        }
        String[] parts = path.substring(1).split("/");
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
