package com.phlox.simpleserver.auth.web;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class CaptchaRequestHandler implements RequestHandler {
    private final CaptchaManager captchaManager;

    public CaptchaRequestHandler(CaptchaManager captchaManager) {
        this.captchaManager = captchaManager;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }

        try {
            // Generate new captcha session and image in one call
            CaptchaManager.CaptchaResult captchaResult = captchaManager.generateCaptchaWithImage();
            
            // Create response with image
            ByteArrayInputStream bais = new ByteArrayInputStream(captchaResult.imageData.data);
            Response response = new Response(captchaResult.imageData.mimeType, captchaResult.imageData.data.length, bais);
            
            // Set cookie with captcha session ID
            Map<String, Object> cookieOptions = new HashMap<>();
            cookieOptions.put("Path", "/");
            cookieOptions.put("HttpOnly", true);
            cookieOptions.put("SameSite", "Lax");
            // Set cookie to expire in 5 minutes (same as captcha session expiry)
            cookieOptions.put("Max-Age", "300");
            
            response.headers.put("Set-Cookie",
                    HTTPUtils.buildSetCookieHeader(
                            "captcha_session_id", captchaResult.sessionId, cookieOptions
                    ));
            
            return response;
            
        } catch (Exception e) {
            return StandardResponses.INTERNAL_SERVER_ERROR("Failed to generate captcha: " + e.getMessage());
        }
    }
}
