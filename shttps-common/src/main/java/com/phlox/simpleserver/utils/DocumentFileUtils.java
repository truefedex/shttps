package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;

public final class DocumentFileUtils {
    public static DocumentFile findChildByPath(DocumentFile root, String path, User user) {
        return com.phlox.server.utils.docfile.DocumentFileUtils.findChildByPath(root, path, user != null ? user.rootDir : null);
    }
}
