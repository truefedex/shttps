package com.phlox.simpleserver.exec;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CgiMiddleware implements Middleware {
    private final @Nullable String cgiPathPrefix;
    private final @NotNull File cgiRoot;
    private final @NotNull List<CgiType> cgiTypes;
    private final @NotNull AuthManager authManager;
    private final SHTTPSConfig.AuthMode authMode;
    private boolean isCgiDirMixedWithStaticFilesDir = false;
    private final SimpleProcessLauncher simplePl;
    private final CgiProcessLauncher cgiPl;

    public CgiMiddleware(@NotNull SHTTPSConfig config, AuthManager authManager) {
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
        this.cgiRoot = cgiFolderFile;

        List<CgiType> cgiTypes = config.getCGITypes();
        this.cgiTypes = cgiTypes != null ? cgiTypes : new ArrayList<>();

        simplePl = new SimpleProcessLauncher(cgiFolderFile, config);
        cgiPl = new CgiProcessLauncher(cgiFolderFile, config);
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
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
                return chain.proceed(context, request); // Not a CGI request, let other handlers try to handle it
            } else {
                requestPath = requestPath.substring(cgiPathPrefix.length());
            }
        }

        ResolvedScript resolved = resolveScript(cgiRoot, requestPath, cgiTypes);
        File cgiScriptFile = resolved == null ? null : resolved.script;
        CgiType cgiType = resolved == null ? null : resolved.type;
        
        if (cgiScriptFile == null) {
            if (isCgiDirMixedWithStaticFilesDir) {
                if (cgiPathPrefix == null || cgiPathPrefix.isEmpty() || "/".equals(cgiPathPrefix)) {
                    //next in pipeline we have a FilesRequestHandler that should have a chance
                    // to handle request normally
                    return chain.proceed(context, request);
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

    static final class ResolvedScript {
        final @NotNull File script;
        final @NotNull CgiType type;

        ResolvedScript(@NotNull File script, @NotNull CgiType type) {
            this.script = script;
            this.type = type;
        }
    }

    /**
     * Walks {@code requestPath} one decoded segment at a time and returns the first file whose
     * extension is a configured CGI type. A segment that is {@code ..} or that contains a slash
     * or a backslash is rejected before it is appended, and no segment along the walk may
     * be a link, a junction or a special file. Together that is what stops
     * {@code %2e%2e%5c} from leaving the CGI folder on Windows, where {@code \} is a separator
     * and the request parser only rejects {@code ..} between slashes.
     *
     * @return the script, or null when the path stays inside the folder but names no CGI file
     */
    static @Nullable ResolvedScript resolveScript(@NotNull File cgiRoot, @NotNull String requestPath,
                                                   @NotNull List<CgiType> cgiTypes) {
        String normalized = requestPath.replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        File cursor = cgiRoot;
        for (String rawSegment : normalized.split("/")) {
            if (rawSegment.isEmpty()) continue;
            String decoded;
            try {
                decoded = URLDecoder.decode(rawSegment, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new SecurityException("Invalid path");
            }
            if (decoded.isEmpty() || ".".equals(decoded)) continue;
            if (decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
                    || !DocumentFile.isValidChildName(decoded)) {
                throw new SecurityException("Invalid path");
            }
            cursor = new File(cursor, decoded);
            //the same rule as the file handlers: no link, junction or special file is followed
            if (com.phlox.server.utils.Utils.isLinkOrSpecial(cursor)) {
                throw new SecurityException("Invalid path");
            }
            if (!cursor.isFile()) continue;
            String extension = Utils.getFileExtensionFromFilename(cursor.getName());
            if (extension == null) continue;
            extension = extension.toLowerCase();
            for (CgiType type : cgiTypes) {
                if (type.extension.equals(extension)) {
                    return new ResolvedScript(cursor, type);
                }
            }
        }
        return null;
    }
}
