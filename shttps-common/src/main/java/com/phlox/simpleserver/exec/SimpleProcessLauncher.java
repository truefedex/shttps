package com.phlox.simpleserver.exec;

import com.phlox.server.request.Request;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.User;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SimpleProcessLauncher extends ExternalProcessLauncher {
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public SimpleProcessLauncher(File cgiFolder, SHTTPSConfig config) {
        super(cgiFolder, config);
    }

    @Override
    Response launch(Request request, String executeWith, File cgiScriptFile, User user,
                    @Nullable Integer executionTimeout) throws Exception {
        // Extract all "arg" parameters from query string
        List<String> args = request.queryParams.getAll("arg");

        // Build process command: executeWith (if provided) + script file + args
        List<String> command = new ArrayList<>();
        if (executeWith != null) {
            //executeWith can be a path to a program with arguments, so we need to parse it and add the script file as the last argument
            String[] executeWithArr = executeWith.split(" ");
            command.addAll(Arrays.asList(executeWithArr));
        }
        //command.add(cgiScriptFile.getAbsolutePath());
        command.add(cgiScriptFile.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
            startStderrLoggerThread(process, logger, "SimpleProcess-StderrLogger");
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
            }, "SimpleProcess-ProcessTimeout");
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
            }, "SimpleProcess-BodyWriter");
            bodyWriterThread.start();
        } else {
            // No body, close output stream
            processOutput.close();
        }

        // For simple processes, output is raw (no headers to parse)
        // Stream the output directly
        ProcessDataStreamer dataStreamer = new ProcessDataStreamer(process, processInput);
        dataStreamer.startDataGenerationThread();
        Response response = new Response(dataStreamer.getInputStream());
        // Set content type from Accept header if not already specified
        if (response.headers.get(Response.HEADER_CONTENT_TYPE) == null) {
            String contentType = getContentTypeFromAcceptHeader(request);
            if (contentType != null) {
                response.setContentType(contentType);
            } else {
                // Fallback to default if no Accept header
                response.setContentType("application/octet-stream");
            }
        }
        return response;
    }

    /**
     * Gets the preferred content type from the Accept header.
     * Parses the Accept header and returns the first acceptable content type.
     * 
     * @param request The request object
     * @return The preferred content type, or null if no Accept header is present
     */
    private String getContentTypeFromAcceptHeader(Request request) {
        String acceptHeader = request.headers.get(Request.HEADER_ACCEPT);
        if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
            return null;
        }
        
        // Parse Accept header: "type/subtype;q=0.9, type2/subtype2, */*"
        // We'll take the first non-wildcard type, or the first type if all are wildcards
        String[] types = acceptHeader.split(",");
        for (String type : types) {
            type = type.trim();
            // Remove quality parameter if present (e.g., ";q=0.9")
            int semicolonIndex = type.indexOf(';');
            if (semicolonIndex > 0) {
                type = type.substring(0, semicolonIndex).trim();
            }
            
            // Skip wildcard types unless it's the only option
            if (!type.equals("*/*") && !type.equals("*")) {
                return type;
            }
        }
        
        // If we only have wildcards, return the first one (or null if empty)
        if (types.length > 0) {
            String firstType = types[0].trim();
            int semicolonIndex = firstType.indexOf(';');
            if (semicolonIndex > 0) {
                firstType = firstType.substring(0, semicolonIndex).trim();
            }
            return firstType.equals("*/*") || firstType.equals("*") ? null : firstType;
        }
        
        return null;
    }
}
