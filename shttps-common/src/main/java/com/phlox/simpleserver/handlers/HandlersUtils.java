package com.phlox.simpleserver.handlers;

import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.handlers.Router;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

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
        json.put("shttps_internal", rule.shttpsInternal);
        return json;
    }

    public static RedirectsMiddleware.RedirectRule ruleFromJson(JSONObject json) {
        boolean enabled = json.optBoolean("enabled", true);
        int code = json.optInt("code", 301);
        String from = json.optString("from", "");
        String to = json.optString("to", "");
        String comment = json.optString("comment", "");
        boolean shttpsInternal = json.optBoolean("shttps_internal", false);
        RedirectsMiddleware.RedirectRule rule = new RedirectsMiddleware.RedirectRule(
                from, to, code, enabled, comment);
        rule.shttpsInternal = shttpsInternal;
        return rule;
    }
}
