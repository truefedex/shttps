package com.phlox.server.utils.docfile;

public final class DocumentFileUtils {
    public DocumentFileUtils() {}

    public static DocumentFile findChildByPath(DocumentFile root, String path) {
        return findChildByPath(root, path, null);
    }

    /**
     * Resolves a '/' separated path against {@code root}, one child at a time.
     *
     * @param pathPrefix prepended to {@code path} before resolving it (a user's own root
     *                   folder); may use either separator
     * @return the document, or null if some part of the path does not exist
     * @throws SecurityException if a part of the path is ".." or otherwise not a plain child name
     */
    public static DocumentFile findChildByPath(DocumentFile root, String path, String pathPrefix) {
        //the prefix is checked together with the path: "/.." must not climb out of a user's folder
        if (pathPrefix != null) {
            path = pathPrefix.replace('\\', '/') + path;
        }
        DocumentFile current = root;
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if (!DocumentFile.isValidChildName(part)) {
                throw new SecurityException("Invalid path");
            }
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
