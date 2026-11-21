package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.DeleteFileRequestHandler;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.StorageQuota;

import java.util.ArrayList;
import java.util.List;

public class WebDavDeleteHelper extends WebDavHelpersBase {
    public WebDavDeleteHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }

    public Response handleDeleteRequest(FilesRequestHandler filesRequestHandler, Request request,
                                        DocumentFile userRoot, DocumentFile file, User user) throws Exception {
        if (filesRequestHandler.checkIsForbidden(user, request.path,
                DeleteFileRequestHandler.DELETE_OPERATION, null,
                User.FileSystemRights.DELETE))
            return StandardResponses.FORBIDDEN();
        if (!file.exists()) {
            return StandardResponses.NOT_FOUND();
        }

        String provided = extractLockTokenFromIf(request.headers.get("if"));
        if (!locks.mayWrite(request.path, provided)) {
            return simpleStatus(423, "Locked");
        }

        StorageQuota quota = new StorageQuota(user, userStore);
        List<String> failures = new ArrayList<>();
        long freed = deleteTree(file, request.path, failures);
        quota.addUsed(-freed);

        if (failures.isEmpty()) {
            return StandardResponses.NO_CONTENT();
        }

        return multiStatusFailures(failures);
    }
}
