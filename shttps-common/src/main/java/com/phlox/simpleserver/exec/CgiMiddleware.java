package com.phlox.simpleserver.exec;

import com.phlox.server.handlers.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.utils.Utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CgiMiddleware implements Middleware {
    private final @Nullable String cgiPathPrefix;
    private final @NonNull String cgiFolder;
    private final @NonNull List<CgiType> cgiTypes;
    private final @NonNull AuthManager authManager;
    private final SHTTPSConfig.AuthMode authMode;
    private boolean isCgiDirMixedWithStaticFilesDir = false;
    private final SimpleProcessLauncher simplePl;
    private final CgiProcessLauncher cgiPl;

    public CgiMiddleware(@NonNull SHTTPSConfig config, AuthManager authManager) {
        this.authManager = authManager;
        this.cgiPathPrefix = config.getCGIPathPrefix();
        this.authMode = config.getAuthMode();
        String cgiFolder = config.getCGIFolder();
        File cgiFolderFile;
        if (cgiFolder != null) {
            cgiFolderFile = new File(cgiFolder);
        } else {
            DocumentFile rootFolder = config.getRootDir();
            if (rootFolder instanceof RawDocumentFile) {
                cgiFolderFile = ((RawDocumentFile)rootFolder).getFile();
                isCgiDirMixedWithStaticFilesDir = true;
            } else {
                throw new RuntimeException("Only 'File API'-backed folders suitable for CGI scripts!");
            }
        }

        if (!cgiFolderFile.exists()) {
            throw new RuntimeException("CGI folder does not exist!: " + cgiFolder);
        }
        cgiFolder = cgiFolderFile.getAbsolutePath();
        if (!cgiFolder.endsWith("/") && !cgiFolder.endsWith("\\")) {
            cgiFolder = cgiFolder + File.separator;
        }
        this.cgiFolder = cgiFolder;

        List<CgiType> cgiTypes = config.getCGITypes();
        this.cgiTypes = cgiTypes != null ? cgiTypes : new ArrayList<>();

        DocumentFile rootFolder = config.getRootDir();
        simplePl = new SimpleProcessLauncher(cgiFolderFile, config);
        cgiPl = new CgiProcessLauncher(cgiFolderFile, config);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        // Get the raw path (before URL decoding) to preserve path structure
        String rawPath = request.rawPathAndQuery;
        // Remove query string from rawPath if present
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex >= 0) {
            rawPath = rawPath.substring(0, queryIndex);
        }
        
        String requestPath = rawPath;
        if (cgiPathPrefix != null && !cgiPathPrefix.isEmpty() && !"/".equals(cgiPathPrefix)) {
            if (!requestPath.startsWith(cgiPathPrefix)) {
                return null;
            } else {
                requestPath = requestPath.substring(cgiPathPrefix.length());
            }
        }

        if (requestPath.contains("../") || requestPath.contains("..\\")) throw new SecurityException("Invalid path");

        // Find the script file by walking the path
        // We need to find the longest path that is a file with a valid CGI extension
        // The remaining path after the script becomes PATH_INFO (calculated in launcher)
        File cgiScriptFile = null;
        CgiType cgiType = null;
        
        // Normalize path separators
        String normalizedPath = requestPath.replace('\\', '/');
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        
        // Split path into segments
        String[] pathSegments = normalizedPath.split("/");
        StringBuilder currentPath = new StringBuilder();
        
        // Try to find a script file by progressively building the path
        // This handles URLs like /cgi-bin/somesubdir/somescript.py/this/is/path/info
        // where we need to find the script file and the remaining path is PATH_INFO
        for (int i = 0; i < pathSegments.length; i++) {
            if (pathSegments[i].isEmpty()) {
                continue;
            }
            
            // Decode URL-encoded path segment before using it for file path construction
            String decodedSegment;
            try {
                decodedSegment = java.net.URLDecoder.decode(pathSegments[i], "UTF-8");
            } catch (Exception e) {
                // If decoding fails, use original segment
                decodedSegment = pathSegments[i];
            }
            
            if (currentPath.length() > 0) {
                currentPath.append("/");
            }
            currentPath.append(decodedSegment);
            
            // Check if this path is a file with a valid CGI extension
            File testFile = new File(cgiFolder + currentPath.toString());
            if (testFile.exists() && testFile.isFile()) {
                String extension = Utils.getFileExtensionFromFilename(testFile.getName());
                if (extension != null) {
                    extension = extension.toLowerCase();
                    // Check if this extension is a valid CGI type
                    for (CgiType c : cgiTypes) {
                        if (c.extension.equals(extension)) {
                            // Found a valid script file!
                            cgiScriptFile = testFile;
                            cgiType = c;
                            // PATH_INFO will be calculated in the launcher from the remaining path
                            break;
                        }
                    }
                    if (cgiScriptFile != null) {
                        break;
                    }
                }
            }
        }
        
        if (cgiScriptFile == null) {
            if (isCgiDirMixedWithStaticFilesDir) {
                if (cgiPathPrefix == null || cgiPathPrefix.isEmpty() || "/".equals(cgiPathPrefix)) {
                    //next in pipeline we have a FilesRequestHandler that should have a chance
                    // to handle request normally
                    return null;
                } else {
                    //next in pipeline we have a FilesRequestHandler but request starts with
                    // cgi prefix so we already know that it can not be handled by FilesRequestHandler
                    return StandardResponses.NOT_FOUND();
                }
            } else {
                return StandardResponses.NOT_FOUND();
            }
        }

        User user = authManager.getAuthenticatedUser(context);
        if ((!authMode.equals(SHTTPSConfig.AuthMode.NONE)) && (user == null ||
                !authManager.getUserRightsEvaluator().userSystemRights(user).contains(User.SystemRights.EXECUTE_HANDLER))) {
            return StandardResponses.FORBIDDEN("The user does not have permission to execute handlers.");
        }
        if (com.phlox.server.utils.Utils.isAndroid() && !cgiScriptFile.canExecute()) {
            //It is problematic for Android users to manually set these flags.
            if (!cgiScriptFile.setExecutable(true, false)) {
                return StandardResponses.INTERNAL_SERVER_ERROR("CGI Script needs to be executable!");
            }
        }
        ExternalProcessLauncher pl = cgiType.mode.equals(CgiType.Mode.CGI) ? cgiPl : simplePl;
        return pl.launch(request, cgiType.executeWith, cgiScriptFile, user, cgiType.executionTimeout);
    }
}
