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
import com.phlox.simpleserver.utils.StagedUpload;
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

    static final class DestInfo {
        final @Nullable DocumentFile destFile;
        final @NotNull DocumentFile destParent;
        final @NotNull String destPath;
        final @NotNull String destName;
        final boolean overwrite;
        DestInfo(@Nullable DocumentFile f, @NotNull DocumentFile p, @NotNull String path, @NotNull String name, boolean ow) {
            destFile = f; destParent = p; destPath = path; destName = name; overwrite = ow;
        }
    }

    private final boolean atomicOverwrite;

    public WebDavCopyMoveHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        this(platform, userStore, locks, false);
    }

    /**
     * @param atomicOverwrite {@code SHTTPSConfig.isAtomicUploadsEnabled()}: an existing destination
     *                        is replaced only after the new tree was written next to it
     */
    public WebDavCopyMoveHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks,
                                boolean atomicOverwrite) {
        super(platform, userStore, locks);
        this.atomicOverwrite = atomicOverwrite;
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

        DestInfo destination;
        try {
            destination = parseDestination(request, root);
        } catch (DavException e) {
            return new Response(e.code, e.reason);
        }
        Response denied = forbidUnlessCopyMoveAllowed(filesRequestHandler, user, copy, request.path,
                destination, operationParams);
        if (denied != null) return denied;

        StorageQuota quota = new StorageQuota(user, userStore);
        if (copy) {
            return handleCopy(request, file, root, quota, destination);
        } else {// MOVE
            return handleMove(request, file, root, quota, destination);
        }
    }

    /**
     * READ on the source, plus DELETE when the source will be removed. CREATE on the destination's
     * parent. A copy does not delete the source, so it does not require DELETE there. An existing
     * destination that Overwrite: T is about to replace is also deleted and rewritten, so it needs
     * UPDATE and DELETE of its own - otherwise CREATE alone would be enough to destroy a file.
     */
    private static Response forbidUnlessCopyMoveAllowed(FilesRequestHandler filesRequestHandler, User user,
                                                         boolean copy, String srcPath, DestInfo destination,
                                                         Map<String, Object> operationParams) {
        String destPath = destination.destPath;
        User.FileSystemRights[] sourceRights = copy
                ? new User.FileSystemRights[]{User.FileSystemRights.READ}
                : new User.FileSystemRights[]{User.FileSystemRights.READ, User.FileSystemRights.DELETE};
        if (filesRequestHandler.checkIsSubtreeForbidden(user, srcPath, MoveFileRequestHandler.COPY_MOVE_OPERATION,
                operationParams, sourceRights)) {
            return StandardResponses.FORBIDDEN();
        }
        String parentPath = Utils.getParentPath(destPath);
        if (parentPath == null) parentPath = "/";
        if (filesRequestHandler.checkIsForbidden(user, parentPath, MoveFileRequestHandler.COPY_MOVE_OPERATION,
                operationParams, User.FileSystemRights.CREATE)) {
            return StandardResponses.FORBIDDEN();
        }
        if (destination.overwrite && destination.destFile != null && destination.destFile.exists()
                && filesRequestHandler.checkIsSubtreeForbidden(user, destPath, MoveFileRequestHandler.COPY_MOVE_OPERATION,
                operationParams, User.FileSystemRights.UPDATE, User.FileSystemRights.DELETE)) {
            return StandardResponses.FORBIDDEN();
        }
        return null;
    }

    Response handleMove(Request req, DocumentFile src, DocumentFile root, StorageQuota quota, DestInfo d) throws Exception {
        String srcPath = req.path;

        String provided = extractLockTokenFromIf(req.headers.get("if"));
        if (!locks.mayRemove(srcPath, provided)) {
            return simpleStatus(423, "Locked");
        } else if (!locks.mayRemove(d.destPath, provided)) {
            return simpleStatus(423, "Locked");
        }

        if (root.equals(src)) return StandardResponses.FORBIDDEN();

        Response err = validateCopyMove(src, d);
        if (err != null) return err;
        return commitCopyOrMove(src, srcPath, d, quota, true, false);
    }

    Response handleCopy(Request req, DocumentFile src, DocumentFile root, StorageQuota quota, DestInfo d) throws Exception {
        String srcPath = req.path;

        String provided = extractLockTokenFromIf(req.headers.get("if"));
        if (!locks.mayRemove(d.destPath, provided)) {
            return simpleStatus(423, "Locked");
        }

        Response err = validateCopyMove(src, d);
        if (err != null) return err;

        // Depth for COPY: infinity (default) or 0 (only the collection itself)
        boolean shallow = "0".equals(req.headers.get("depth"));
        return commitCopyOrMove(src, srcPath, d, quota, false, shallow);
    }

    /**
     * With {@link #atomicOverwrite}, an existing destination stays in place while the new tree is
     * written under a temporary name, and is deleted only after that succeeded, so a failure leaves
     * it as it was. That needs room for both trees at once, which is why it is optional. Without
     * it the destination is deleted first, as RFC 4918 describes for Overwrite: T, after checking
     * that the new tree fits into the space the old one gives back.
     */
    private Response commitCopyOrMove(DocumentFile src, String srcPath, DestInfo d, StorageQuota quota,
                                      boolean move, boolean shallow) throws Exception {
        boolean destExisted = d.destFile != null && d.destFile.exists();
        boolean stage = destExisted && atomicOverwrite;
        if (destExisted && !stage) {
            if (!move && !(src.isDirectory() && shallow)
                    && !quota.hasSpaceFor(d.destParent, StorageQuota.sizeOf(src), StorageQuota.sizeOf(d.destFile))) {
                return new Response(507, "Insufficient Storage");
            }
            List<String> deleteFailures = new ArrayList<>();
            long freed = deleteTree(d.destFile, d.destPath, deleteFailures);
            quota.addUsed(-freed);
            if (!deleteFailures.isEmpty()) {
                return StandardResponses.FORBIDDEN();
            }
        }
        String writeName = stage ? StagedUpload.stagingName(d.destName) : d.destName;
        List<String> failures = new ArrayList<>();
        boolean movedAside = false;
        if (move && !(src.isDirectory() && shallow)) {
            movedAside = src.moveTo(d.destParent, writeName);
        }
        if (!movedAside) {
            if (src.isDirectory() && shallow) {
                if (d.destParent.createDirectory(writeName) == null) {
                    failures.add(d.destPath);
                }
            } else {
                long srcSize = StorageQuota.sizeOf(src);
                if (move) {
                    if (d.destParent.getStorageFreeSpace() < srcSize) {
                        return new Response(507, "Insufficient Storage");
                    }
                } else if (!quota.hasSpaceFor(d.destParent, srcSize, 0)) {
                    return new Response(507, "Insufficient Storage");
                }
                long copied = copyTree(src, null, d.destParent, writeName, srcPath, failures);
                quota.addUsed(copied);
                if (failures.isEmpty() && move) {
                    long freed = deleteTree(src, srcPath, failures);
                    quota.addUsed(-freed);
                }
            }
        }
        if (!failures.isEmpty()) {
            discardStaging(d.destParent, writeName, stage, quota);
            return multiStatusFailures(failures);
        }
        if (stage) {
            DocumentFile staged = d.destParent.findFile(writeName);
            List<String> deleteFailures = new ArrayList<>();
            long freed = deleteTree(d.destFile, d.destPath, deleteFailures);
            quota.addUsed(-freed);
            if (!deleteFailures.isEmpty() || staged == null || !staged.renameTo(d.destName)) {
                return StandardResponses.INTERNAL_SERVER_ERROR();
            }
        }
        return new Response(destExisted ? 204 : 201, destExisted ? "No Content" : "Created");
    }

    private void discardStaging(DocumentFile parent, String writeName, boolean staged, StorageQuota quota) {
        if (!staged) return;
        DocumentFile partial = parent.findFile(writeName);
        if (partial == null) return;
        List<String> ignored = new ArrayList<>();
        long junk = deleteTree(partial, writeName, ignored);
        quota.addUsedQuietly(-junk);
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

    private Response validateCopyMove(DocumentFile src, DestInfo d) {
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
        if (d.destFile != null && d.destFile.exists() && !d.overwrite) {
            return new Response(412, "Precondition Failed");
        }
        return null;
    }

    private boolean isDescendantOrSame(DocumentFile ancestor, DocumentFile maybeChild) {
        String a = ancestor.getUri();
        String c = maybeChild.getUri();
        return c.startsWith(a);
    }
}
