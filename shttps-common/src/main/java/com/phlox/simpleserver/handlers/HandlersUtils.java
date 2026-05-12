package com.phlox.simpleserver.handlers;

import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware;

import org.json.JSONObject;

public class HandlersUtils {
    private HandlersUtils() {
    }

    public static JSONObject ruleToJson(RedirectsMiddleware.RedirectRule rule) {
        JSONObject json = new JSONObject();
        json.put("enabled", rule.enabled);
        json.put("code", rule.code);
        json.put("from", rule.from);
        json.put("to", rule.to);
        json.put("comment", rule.comment);
        return json;
    }

    public static RedirectsMiddleware.RedirectRule ruleFromJson(JSONObject json) {
        boolean enabled = json.optBoolean("enabled", true);
        int code = json.optInt("code", 301);
        String from = json.optString("from", "");
        String to = json.optString("to", "");
        String comment = json.optString("comment", "");
        return new RedirectsMiddleware.RedirectRule(
                from, to, code, enabled, comment);
    }
}
