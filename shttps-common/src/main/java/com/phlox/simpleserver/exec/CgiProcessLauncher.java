package com.phlox.simpleserver.exec;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.RawDocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CgiProcessLauncher extends ExternalProcessLauncher {
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public CgiProcessLauncher(File cgiFolder, SHTTPSConfig config) {
        super(cgiFolder, config);
    }

    @Override
    Response launch(Request request, String executeWith, File cgiScriptFile, User user,
                    @Nullable Integer executionTimeout) throws Exception {
        ProcessBuilder pb;
        if (executeWith != null) {
            //executeWith can be a path to a program with arguments, so we need to parse it and add the script file as the last argument
            String[] args = executeWith.split(" ");
            List<String> argsList = new ArrayList<>(Arrays.asList(args));
            argsList.add(cgiScriptFile.getAbsolutePath());
            String[] argsWithScriptFile = argsList.toArray(new String[0]);
            pb = new ProcessBuilder(argsWithScriptFile);
        } else {
            pb = new ProcessBuilder("\"" + cgiScriptFile.getAbsolutePath() + "\"");
        }
        Map<String, String> env = pb.environment();
        
        // Extract query string from rawPathAndQuery
        String queryString = null;
        String rawPathAndQuery = request.rawPathAndQuery;
        int queryIndex = rawPathAndQuery.indexOf('?');
        if (queryIndex >= 0) {
            queryString = rawPathAndQuery.substring(queryIndex + 1);
        }
        
        // Set CGI environment variables
        setupCGIEnvironment(env, request, cgiScriptFile, queryString, user);
        Process process;
        try {
            process = pb.start();
            startStderrLoggerThread(process, logger, "CGI-StderrLogger");
        } catch (Exception e) {
            logger.stackTrace(e);
            return StandardResponses.INTERNAL_SERVER_ERROR(e.getMessage());
        }
        if (executionTimeout != null && executionTimeout > 0) {
            final Process p = process;
            Thread timeoutThread = new Thread(() -> {
                try {
                    Thread.sleep(executionTimeout);
                    destroyProcessForcibly(p);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "CGI-ProcessTimeout");
            timeoutThread.setDaemon(true);
            timeoutThread.start();
        }
        InputStream processInput = process.getInputStream();
        OutputStream processOutput = process.getOutputStream();

        // Write request body to process in background thread
        if (request.shouldHaveABody() && request.contentLength != 0) {
            final OutputStream out = processOutput;
            final InputStream requestBodyInput = request.input;
            final long contentLength = request.contentLength;

            Thread bodyWriterThread = new Thread(() -> {
                try {
                    if (contentLength == -1) {
                        // Undefined content length
                        Utils.copyStream(requestBodyInput, out);
                    } else if (contentLength > 0) {
                        // Known content length
                        Utils.copyStream(requestBodyInput, out, contentLength);
                    }
                    out.flush();
                    out.close();
                } catch (Exception e) {
                    // Log error but don't fail - process might handle it
                    logger.stackTrace(e);
                }
            }, "CGI-BodyWriter");
            bodyWriterThread.start();
        } else {
            // No body, close output stream
            processOutput.close();
        }

        // Read CGI response headers
        // CGI spec: headers are separated from body by a blank line (\r\n\r\n or \n\n)
        // We need to read headers byte-by-byte until we find the blank line
        StringBuilder headerBuffer = new StringBuilder();
        int lastByte = -1;
        int secondLastByte = -1;

        while (true) {
            int b = processInput.read();
            if (b == -1) {
                // Stream ended before headers complete
                logger.w("CGI process output ended before headers were complete");
                return StandardResponses.INTERNAL_SERVER_ERROR(headerBuffer.toString());
            }

            // Check for blank line: \r\n\r\n or \n\n
            if (b == '\n') {
                if (lastByte == '\n' || (lastByte == '\r' && secondLastByte == '\n')) {
                    // Found blank line - end of headers
                    break;
                }
            }

            // Store bytes for header parsing
            if (b != '\r' || (lastByte != '\n' && lastByte != '\r')) {
                // Only append if not a standalone \r (part of \r\n)
                headerBuffer.append((char) b);
            }

            secondLastByte = lastByte;
            lastByte = b;
        }

        // Parse headers
        String headerText = headerBuffer.toString();
        String[] headerLines = headerText.split("\r?\n");
        int statusCode = 200;
        String statusPhrase = StandardResponses.PHRASE_OK;
        MultiMap<String, String> responseHeaders = new MultiMap<>();

        for (String headerLine : headerLines) {
            if (headerLine.trim().isEmpty()) {
                continue;
            }

            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String headerName = headerLine.substring(0, colonIndex).trim();
                String headerValue = headerLine.substring(colonIndex + 1).trim();

                // Handle special CGI headers
                if ("Status".equalsIgnoreCase(headerName)) {
                    // Status header format: "200 OK" or just "200"
                    String[] statusParts = headerValue.split(" ", 2);
                    try {
                        statusCode = Integer.parseInt(statusParts[0]);
                        if (statusParts.length > 1) {
                            statusPhrase = statusParts[1];
                        }
                    } catch (NumberFormatException e) {
                        // Invalid status, ignore
                    }
                } else {
                    // Regular header
                    responseHeaders.put(headerName, headerValue);
                }
            }
        }

        // Create ProcessDataStreamer that will read the remaining body from processInput
        ProcessDataStreamer dataStreamer = new ProcessDataStreamer(process, processInput);
        dataStreamer.startDataGenerationThread();
        Response response = new Response(dataStreamer.getInputStream());
        response.code = statusCode;
        response.phrase = statusPhrase;
        response.headers = responseHeaders;
        return response;
    }

    /**
     * Sets up standard CGI environment variables.
     */
    private void setupCGIEnvironment(Map<String, String> env, Request request, File cgiScriptFile,
                                     String queryString, User user) {
        // Request method
        env.put("REQUEST_METHOD", request.method);
        
        // Query string
        if (queryString != null && !queryString.isEmpty()) {
            env.put("QUERY_STRING", queryString);
        }
        
        // Content type and length
        if (request.contentType != null) {
            env.put("CONTENT_TYPE", request.contentType);
        }
        if (request.contentLength >= 0) {
            env.put("CONTENT_LENGTH", String.valueOf(request.contentLength));
        }

        DocumentFile rootFolder = config.getRootDir();
        if (rootFolder instanceof RawDocumentFile) {
            env.put("DOCUMENT_ROOT", ((RawDocumentFile)rootFolder).getFile().getAbsolutePath());
        } else {
            env.put("DOCUMENT_ROOT", rootFolder.getUri());
        }
        
        // Script name and path info
        // Calculate the relative path from cgiFolder to the script file
        String cgiFolderPath = cgiFolder.getAbsolutePath();
        String scriptFilePath = cgiScriptFile.getAbsolutePath();
        
        // SCRIPT_NAME is the virtual path to the script (URL path, not filesystem)
        // It's the path relative to the cgiFolder, converted to URL format
        String scriptRelativePath = scriptFilePath.substring(cgiFolderPath.length());
        // Normalize path separators to forward slashes for URL
        scriptRelativePath = scriptRelativePath.replace('\\', '/');
        // Ensure it starts with /
        if (!scriptRelativePath.startsWith("/")) {
            scriptRelativePath = "/" + scriptRelativePath;
        }
        env.put("SCRIPT_NAME", scriptRelativePath);
        
        // PATH_INFO is the path after the script name in the request
        // Use rawPathAndQuery to preserve path structure
        String rawRequestPath = request.rawPathAndQuery;
        // Remove query string
        int queryIndex = rawRequestPath.indexOf('?');
        if (queryIndex >= 0) {
            rawRequestPath = rawRequestPath.substring(0, queryIndex);
        }
        
        // Normalize the request path
        String normalizedRequestPath = rawRequestPath.replace('\\', '/');
        if (!normalizedRequestPath.startsWith("/")) {
            normalizedRequestPath = "/" + normalizedRequestPath;
        }
        
        // Find PATH_INFO by comparing the request path with the script path
        // rawRequestPath is always URL-encoded, scriptRelativePath is always decoded
        // So we need to decode the request path before comparing
        String pathInfo = "";
        
        try {
            String decodedRequestPath = java.net.URLDecoder.decode(normalizedRequestPath, "UTF-8");
            int scriptIndex = decodedRequestPath.indexOf(scriptRelativePath);
            
            if (scriptIndex >= 0) {
                // Found the script path in the request
                int pathInfoStart = scriptIndex + scriptRelativePath.length();
                if (pathInfoStart < decodedRequestPath.length()) {
                    pathInfo = decodedRequestPath.substring(pathInfoStart);
                    // Only set PATH_INFO if it's a valid path (starts with /)
                    if (!pathInfo.startsWith("/")) {
                        pathInfo = "";
                    }
                }
            }
        } catch (Exception e) {
            // If decoding fails, PATH_INFO remains empty
            logger.stackTrace(e);
        }
        
        env.put("PATH_INFO", pathInfo);
        
        // PATH_TRANSLATED is the filesystem path corresponding to PATH_INFO
        // It's the filesystem path of the rootFolder + PATH_INFO
        if (!pathInfo.isEmpty()) {
            if (rootFolder instanceof RawDocumentFile) {
                String rootFolderPath = ((RawDocumentFile) rootFolder).getFile().getAbsolutePath();

                String pathTranslated = cgiFolderPath + pathInfo.replace('/', File.separatorChar);
                env.put("PATH_TRANSLATED", pathTranslated);
            } else {
                env.put("PATH_TRANSLATED", "");
            }
        } else {
            env.put("PATH_TRANSLATED", "");
        }
        
        // Server information from Host header
        String hostHeader = request.headers.get(Request.HEADER_HOST);
        if (hostHeader != null) {
            // Parse host:port from Host header
            String serverName = hostHeader;
            String serverPort = Integer.toString(config.getPort());
            int colonIndex = hostHeader.indexOf(':');
            if (colonIndex > 0) {
                serverName = hostHeader.substring(0, colonIndex);
                serverPort = hostHeader.substring(colonIndex + 1);
            }
            env.put("SERVER_NAME", serverName);
            env.put("SERVER_PORT", serverPort);
        } else {
            // Fallback to values from config
            String configuredHost = config.getHost();
            env.put("SERVER_NAME", configuredHost != null ? configuredHost : "localhost");
            env.put("SERVER_PORT", Integer.toString(config.getPort()));
        }
        
        // Server protocol (HTTP version)
        env.put("SERVER_PROTOCOL", SimpleHttpServer.HTTP_PROTOCOL);
        
        // Server software
        env.put("SERVER_SOFTWARE", SimpleHttpServer.SERVER_NAME);
        
        // Gateway interface
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        
        // Remote address
        if (request.hostAddress != null) {
            env.put("REMOTE_ADDR", request.hostAddress);
        }
        
        // Request URI (path + query string)
        env.put("REQUEST_URI", request.rawPathAndQuery);
        
        // HTTP_* variables - convert all HTTP headers to environment variables
        // Headers are converted: "User-Agent" -> "HTTP_USER_AGENT"
        for (String headerName : request.headers.keys()) {
            // Skip headers that are already set as CGI variables
            if (headerName.equalsIgnoreCase(Request.HEADER_CONTENT_TYPE) ||
                headerName.equalsIgnoreCase(Request.HEADER_CONTENT_LENGTH) ||
                headerName.equalsIgnoreCase(Request.HEADER_HOST)) {
                continue;
            }
            
            // Convert header name to HTTP_* format
            String envName = "HTTP_" + headerName.toUpperCase().replace("-", "_");
            // Get first value (CGI typically uses first value for headers)
            String headerValue = request.headers.get(headerName);
            if (headerValue != null) {
                env.put(envName, headerValue);
            }
        }

        if (user != null) {
            env.put("REMOTE_USER", user.identity);
            env.put("AUTH_TYPE", config.getAuthMode().equals(SHTTPSConfig.AuthMode.BASIC_AUTH) ?
                    "Basic" : "SHTTPSAuth");
        }

        //some more environment variables that are not part of the CGI spec but are useful
        env.put("REQUEST_TIME", String.valueOf(System.currentTimeMillis()));
        env.put("REDIRECT_STATUS", "200");
        env.put("SCRIPT_FILENAME", cgiScriptFile.getAbsolutePath());
    }
}
