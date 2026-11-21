package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.MoveFileRequestHandler;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.StorageQuota;
import com.phlox.simpleserver.utils.Utils;

import org.json.JSONArray;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebDavCopyMoveHelper extends WebDavHelpersBase {

    private static final class DestInfo {
        final @Nullable DocumentFile destFile;
        final @NotNull DocumentFile destParent;
        final @NotNull String destPath;
        final @NotNull String destName;
        final boolean overwrite;
        DestInfo(@Nullable DocumentFile f, @NotNull DocumentFile p, @NotNull String path, @NotNull String name, boolean ow) {
            destFile = f; destParent = p; destPath = path; destName = name; overwrite = ow;
        }
    }

    public WebDavCopyMoveHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }

    public Response handleCopyMoveRequest(FilesRequestHandler filesRequestHandler, Request request,
                                          DocumentFile root, DocumentFile file, User user) throws Exception {
        boolean copy = request.method.equals(Request.METHOD_COPY);
        Map<String, Object> operationParams = new HashMap<>();
        operationParams.put("action", copy ? "copy" : "move");
        operationParams.put("files.size", 1);
        JSONArray jArr = new JSONArray();
        jArr.put(request.path);
        operationParams.put("files[]", jArr.toString());
        if (filesRequestHandler.checkIsForbidden(user, request.path,
                MoveFileRequestHandler.COPY_MOVE_OPERATION, operationParams,
                User.FileSystemRights.CREATE,
                User.FileSystemRights.DELETE,
                User.FileSystemRights.READ,
                User.FileSystemRights.UPDATE))
            return StandardResponses.FORBIDDEN();

        StorageQuota quota = new StorageQuota(user, userStore);
        if (copy) {
            return handleCopy(request, file, root, quota);
        } else {// MOVE
            return handleMove(request, file, root, quota);
        }
    }

    Response handleMove(Request req, DocumentFile src, DocumentFile root, StorageQuota quota) throws Exception {
        String srcPath = req.path;

        DestInfo d;
        try { d = parseDestination(req, root); }
        catch (DavException e) { return new Response(e.code, e.reason); }

        String provided = extractLockTokenFromIf(req.headers.get("if"));
        if (!locks.mayWrite(srcPath, provided)) {
            return simpleStatus(423, "Locked");
        } else if (!locks.mayWrite(d.destPath, provided)) {
            return simpleStatus(423, "Locked");
        }

        if (root.equals(src)) return StandardResponses.FORBIDDEN();

        boolean destExisted = d.destFile != null && d.destFile.exists();

        Response err = validateCopyMove(src, srcPath, d, quota);
        if (err != null) return err;

        // Fast way: atomic rename (works within a single file system)
        if (src.moveTo(d.destParent, d.destName)) {
            return new Response(destExisted ? 204 : 201,
                    destExisted ? "No Content" : "Created");
        }

        // Slow way: rename didn't work (different FS/volumes) - copy, then delete the source
        // Only the volume free space is checked here, not the user quota:
        // a move is quota-neutral (the source is removed afterwards)
        long srcSize = StorageQuota.sizeOf(src);
        if (d.destParent.getStorageFreeSpace() < srcSize) {
            return new Response(507, "Insufficient Storage");
        }
        List<String> failures = new ArrayList<>();
        long copied = copyTree(src, d.destFile, d.destParent, d.destName, srcPath, failures);
        quota.addUsed(copied);
        if (!failures.isEmpty()) {
            return multiStatusFailures(failures);
        }
        long freed = deleteTree(src, srcPath, failures);   // remove source after successful copying
        quota.addUsed(-freed);
        if (!failures.isEmpty()) {
            return multiStatusFailures(failures);
        }
        return new Response(destExisted ? 204 : 201,
                destExisted ? "No Content" : "Created");
    }

    Response handleCopy(Request req, DocumentFile src, DocumentFile root, StorageQuota quota) throws Exception {
        String srcPath = req.path;

        DestInfo d;
        try { d = parseDestination(req,root); }
        catch (DavException e) { return new Response(e.code, e.reason); }

        String provided = extractLockTokenFromIf(req.headers.get("if"));
        if (!locks.mayWrite(d.destPath, provided)) {
            return simpleStatus(423, "Locked");
        }

        boolean destExisted = d.destFile != null && d.destFile.exists();

        Response err = validateCopyMove(src, srcPath, d, quota);
        if (err != null) return err;

        // Depth for COPY: infinity (default) or 0 (only the collection itself)
        String depth = req.headers.get("depth");
        boolean shallow = "0".equals(depth);   // a rare case

        List<String> failures = new ArrayList<>();
        if (src.isDirectory() && shallow) {
            if (d.destParent.createDirectory(src.getName()) == null) // only empty folder
                failures.add(d.destPath);
        } else {
            long srcSize = StorageQuota.sizeOf(src);
            if (!quota.hasSpaceFor(d.destParent, srcSize, 0)) {
                return new Response(507, "Insufficient Storage");
            }
            long copied = copyTree(src, d.destFile, d.destParent, d.destName,  srcPath, failures);
            quota.addUsed(copied);
        }

        if (!failures.isEmpty()) {
            return multiStatusFailures(failures);
        }
        return new Response(destExisted ? 204 : 201,
                destExisted ? "No Content" : "Created");
    }

    /** Copies the file/tree, returns the total size of successfully copied files
     *  (for storage quota accounting). */
    private long copyTree(DocumentFile src, DocumentFile dst, DocumentFile dstParent, String dstName, String srcHref, List<String> failures) {
        long copiedBytes = 0;
        if (src.isDirectory()) {
            if (dst == null || !dst.isDirectory()) {
                dst = dstParent.createDirectory(dstName);
            }
            if (dst == null || !dst.isDirectory()) {
                failures.add(srcHref);
                return copiedBytes;
            }
            DocumentFile[] kids = src.listFiles();
            if (kids != null) {
                for (DocumentFile kid : kids) {
                    String kidHref = srcHref.endsWith("/")
                            ? srcHref + kid.getName()
                            : srcHref + "/" + kid.getName();
                    copiedBytes += copyTree(kid, null, dst, kid.getName(), kidHref, failures);
                }
            }
        } else {
            if (src.copyTo(dstParent, dstName)) {
                copiedBytes += src.length();
            } else {
                failures.add(srcHref);
            }
        }
        return copiedBytes;
    }

    private DestInfo parseDestination(Request req, DocumentFile root) throws DavException {
        String dest = req.headers.get("destination");
        if (dest == null || dest.isEmpty()) {
            throw new DavException(400, "Bad Request");
        }

        // Destination is the full URL. We take the path component and decode it as request-target.
        String destPath;
        try {
            java.net.URI u = new java.net.URI(dest);
            destPath = HTTPUtils.normalizePath(u.getPath());
        } catch (Exception e) {
            throw new DavException(400, "Bad Request");
        }
        if (destPath.isEmpty()) {
            throw new DavException(400, "Bad Request");
        }

        // Overwrite: T (default) / F
        String ow = req.headers.get("overwrite");
        boolean overwrite = (ow == null) || ow.trim().equalsIgnoreCase("T");

        DocumentFile destFile = DocumentFileUtils.findChildByPath(root, destPath);
        DocumentFile parent;
        if (destFile != null) {
            parent = destFile.getParentFile();
        } else {
            String parentPath = Utils.getParentPath(destPath);
            parent = DocumentFileUtils.findChildByPath(root, parentPath);
        }
        String destName;
        if (destFile != null) {
            destName = destFile.getName();
        } else {
            int lastIndexOfSlash = destPath.lastIndexOf('/');
            if (lastIndexOfSlash == -1) {
                destName = destPath;  // no slashes, the whole path is the name
            } else if (lastIndexOfSlash == destPath.length() - 1) {
                // path ends with '/', remove it and find the last segment
                String slashLessPath = destPath.substring(0, lastIndexOfSlash);
                lastIndexOfSlash = slashLessPath.lastIndexOf('/');
                if (lastIndexOfSlash == -1) {
                    destName = slashLessPath;
                } else {
                    destName = slashLessPath.substring(lastIndexOfSlash + 1);
                }
            } else {
                destName = destPath.substring(lastIndexOfSlash + 1);
            }
        }
        return new DestInfo(destFile, parent, destPath, destName, overwrite);
    }

    private Response validateCopyMove(DocumentFile src, String srcPath, DestInfo d, StorageQuota quota) throws Exception {
        if (src == null || !src.exists()) {
            return StandardResponses.NOT_FOUND();
        }
        if (src.equals(d.destFile)) {
            return StandardResponses.FORBIDDEN();
        }
        // You cannot copy/move a folder within itself.
        if (src.isDirectory() && isDescendantOrSame(src, d.destParent)) {
            return StandardResponses.CONFLICT();
        }
        // the destination parent must exist
        DocumentFile destParent = d.destParent;
        if (!destParent.isDirectory()) {
            return StandardResponses.CONFLICT();
        }
        if (d.destFile != null && d.destFile.exists()) {
            if (!d.overwrite) {
                return new Response(412, "Precondition Failed");
            }
            // Overwrite
            List<String> failures = new ArrayList<>();
            long freed = deleteTree(d.destFile, d.destPath, failures);
            quota.addUsed(-freed);
            if (!failures.isEmpty()) {
                return StandardResponses.FORBIDDEN();
            }
        }
        return null;
    }

    private boolean isDescendantOrSame(DocumentFile ancestor, DocumentFile maybeChild) {
        String a = ancestor.getUri();
        String c = maybeChild.getUri();
        return c.startsWith(a);
    }
}
