package com.phlox.simpleserver.utils;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;

import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class DocumentFileUtils {
    public static DocumentFile findChildByPath(DocumentFile root, String path, User user) {
        if (path.contains("../") || path.contains("..\\")) throw new SecurityException("Invalid path");
        return com.phlox.server.utils.docfile.DocumentFileUtils.findChildByPath(root, path, user != null ? user.rootDir : null);
    }

    public static DocumentFile checkOrCreateUserDir(@NonNull DocumentFile root, @NonNull String userDir) {
        String[] folders = userDir.replace('\\', '/').split("/");
        DocumentFile current = root;
        for (String folderName: folders) {
            DocumentFile dir = current.findFile(folderName);
            if (dir == null) {
                dir = current.createDirectory(folderName);
                if (dir == null) {
                    return null;
                }
            } else if (!dir.isDirectory()) {
                return null;
            }
            current = dir;
        }
        return current;
    }

    public static ArrayList<DocumentFile> searchRecursive(@NonNull DocumentFile rootDir, @NonNull String pattern, int maxResults) {
        if (!rootDir.isDirectory()) {
            throw new IllegalArgumentException("rootDir must be a directory");
        }
        Pattern regex = Utils.wildcardToRegex(pattern);
        ArrayList<DocumentFile> result = new ArrayList<>();
        recursiveFindFiles(rootDir, regex, result, maxResults);
        return result;
    }

    private static void recursiveFindFiles(DocumentFile dir, Pattern regex, List<DocumentFile> result, int maxResults) {
        DocumentFile[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (DocumentFile f : entries) {
            String name = f.getName();
            if (regex.matcher(name).matches()) {
                result.add(f);
                if (maxResults > 0 && result.size() >= maxResults) {
                    return;
                }
            }
            if (f.isDirectory()) {
                recursiveFindFiles(f, regex, result, maxResults);
                if (maxResults > 0 && result.size() >= maxResults) {
                    return;
                }
            }
        }
    }
}
