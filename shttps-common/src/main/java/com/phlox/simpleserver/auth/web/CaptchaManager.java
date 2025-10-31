package com.phlox.simpleserver.auth.web;

import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class CaptchaManager {
    private final SHTTPSPlatformUtils platformUtils;
    private final Map<String, CaptchaSession> captchaSessions = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    // Captcha configuration
    private static final int CAPTCHA_CODE_LENGTH = 6;
    private static final int CAPTCHA_WIDTH = 200;
    private static final int CAPTCHA_HEIGHT = 80;
    private static final long CAPTCHA_EXPIRY_TIME_MS = 5 * 60 * 1000; // 5 minutes
    private static final String CAPTCHA_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    public CaptchaManager(SHTTPSPlatformUtils platformUtils) {
        this.platformUtils = platformUtils;
    }

    /**
     * Generates a new captcha session and returns the session ID
     */
    public String generateCaptcha() {
        String code = generateRandomCode();
        String sessionId = generateSessionId();
        
        CaptchaSession session = new CaptchaSession(code, System.currentTimeMillis());
        captchaSessions.put(sessionId, session);
        
        // Clean up expired sessions
        cleanupExpiredSessions();
        
        return sessionId;
    }

    /**
     * Generates a new captcha session and image in one call
     */
    public CaptchaResult generateCaptchaWithImage() {
        String sessionId = generateCaptcha();
        SHTTPSPlatformUtils.ImageData imageData = generateCaptchaImage(sessionId);
        return new CaptchaResult(sessionId, imageData);
    }

    /**
     * Generates a captcha image for the given session ID
     */
    public SHTTPSPlatformUtils.ImageData generateCaptchaImage(String sessionId) {
        CaptchaSession session = captchaSessions.get(sessionId);
        if (session == null || isExpired(session)) {
            throw new IllegalArgumentException("Invalid or expired captcha session");
        }
        
        return platformUtils.generateCaptchaImage(session.code, CAPTCHA_WIDTH, CAPTCHA_HEIGHT);
    }

    /**
     * Validates a captcha code for the given session ID
     */
    public boolean validateCaptcha(String sessionId, String code) {
        CaptchaSession session = captchaSessions.get(sessionId);
        if (session == null || isExpired(session)) {
            return false;
        }
        
        // Remove the session after validation attempt
        captchaSessions.remove(sessionId);
        
        return session.code.equalsIgnoreCase(code);
    }

    /**
     * Validates a captcha code using session ID from cookies
     */
    public boolean validateCaptchaFromCookies(String cookieValue, String code) {
        if (cookieValue == null) {
            return false;
        }
        return validateCaptcha(cookieValue, code);
    }

    /**
     * Removes a captcha session (useful for cleanup)
     */
    public void removeCaptchaSession(String sessionId) {
        captchaSessions.remove(sessionId);
    }

    private String generateRandomCode() {
        StringBuilder code = new StringBuilder(CAPTCHA_CODE_LENGTH);
        for (int i = 0; i < CAPTCHA_CODE_LENGTH; i++) {
            code.append(CAPTCHA_CHARS.charAt(random.nextInt(CAPTCHA_CHARS.length())));
        }
        return code.toString();
    }

    private String generateSessionId() {
        return Long.toHexString(System.currentTimeMillis()) + 
               Integer.toHexString(random.nextInt());
    }

    private boolean isExpired(CaptchaSession session) {
        return System.currentTimeMillis() - session.timestamp > CAPTCHA_EXPIRY_TIME_MS;
    }

    private void cleanupExpiredSessions() {
        // Use iterator to remove expired sessions (compatible with Android API 21)
        java.util.Iterator<Map.Entry<String, CaptchaSession>> iterator = captchaSessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CaptchaSession> entry = iterator.next();
            if (isExpired(entry.getValue())) {
                iterator.remove();
            }
        }
    }

    private static class CaptchaSession {
        final String code;
        final long timestamp;

        CaptchaSession(String code, long timestamp) {
            this.code = code;
            this.timestamp = timestamp;
        }
    }

    public static class CaptchaResult {
        public final String sessionId;
        public final SHTTPSPlatformUtils.ImageData imageData;

        public CaptchaResult(String sessionId, SHTTPSPlatformUtils.ImageData imageData) {
            this.sessionId = sessionId;
            this.imageData = imageData;
        }
    }
}
