package com.phlox.simpleserver.auth.web;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;

public class UserRegistrationRequestHandler implements RequestHandler {
    private final WebAuthManager authManager;
    private final CaptchaManager captchaManager;

    public UserRegistrationRequestHandler(WebAuthManager authManager, CaptchaManager captchaManager) {
        this.captchaManager = captchaManager;
        this.authManager = authManager;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_POST)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }

        if (!Request.CONTENT_TYPE_URL_ENCODED_FORM.equals(request.contentType)) {
            return StandardResponses.BAD_REQUEST("Content-Type must be application/x-www-form-urlencoded");
        }

        // Parse request body
        context.requestBodyReader.readRequestBody(request);

        // Extract form parameters
        String identity = request.urlEncodedPostParams.get("identity");
        String password = request.urlEncodedPostParams.get("password");
        String captchaCode = request.urlEncodedPostParams.get("captcha_code");
        String captchaSessionId = request.cookies.get("captcha_session_id");

        // Validate required parameters
        if (identity == null || identity.trim().isEmpty()) {
            return createErrorResponse("Identity is required");
        }
        if (password == null || password.trim().isEmpty()) {
            return createErrorResponse("Password is required");
        }
        if (captchaCode == null || captchaCode.trim().isEmpty()) {
            return createErrorResponse("Captcha code is required");
        }
        if (captchaSessionId == null) {
            return createErrorResponse("Captcha session is required");
        }

        // Trim whitespace
        identity = identity.trim();
        password = password.trim();
        captchaCode = captchaCode.trim();

        // Validate captcha
        if (!captchaManager.validateCaptchaFromCookies(captchaSessionId, captchaCode)) {
            return createErrorResponse("Invalid captcha code");
        }

        // Validate user registration data
        String errorMessage = authManager.registerUser(identity, password);
        if (errorMessage != null) {
            return createErrorResponse(errorMessage);
        }

        try {
            // Return success response
            String successResponse = "{\"success\":true,\"message\":\"User registered successfully\"}";
            return new TextResponse(successResponse, "application/json");

        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Registration failed: " + e.getMessage());
        }
    }

    private Response createErrorResponse(String message) {
        String errorResponse = "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";
        return new TextResponse(400, "Bad Request", errorResponse, "application/json");
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
