package com.phlox.simpleserver.handlers;

import com.phlox.server.handlers.RoutingRequestHandler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class HandlersUtils {
    private HandlersUtils() {
    }

    public static JSONObject ruleToJson(RoutingRequestHandler.RedirectRule rule) {
        JSONObject json = new JSONObject();
        json.put("enabled", rule.enabled);
        json.put("mode", rule.mode.name());
        JSONArray flags = new JSONArray();
        for (RoutingRequestHandler.RedirectFlags flag : rule.flags) {
            flags.put(flag.name());
        }
        json.put("flags", flags);
        json.put("code", rule.code);
        json.put("from", rule.from);
        json.put("to", rule.to);
        json.put("comment", rule.comment);
        json.put("shttps_internal", rule.shttpsInternal);
        return json;
    }

    public static RoutingRequestHandler.RedirectRule ruleFromJson(JSONObject json) {
        boolean enabled = json.optBoolean("enabled", true);
        RoutingRequestHandler.RedirectMode mode = RoutingRequestHandler.RedirectMode.valueOf(json.optString("mode", RoutingRequestHandler.RedirectMode.INTERNAL.name()));
        JSONArray flags = json.optJSONArray("flags");
        Set<RoutingRequestHandler.RedirectFlags> flagsSet = new HashSet<>();
        if (flags != null) {
            for (int i = 0; i < flags.length(); i++) {
                flagsSet.add(RoutingRequestHandler.RedirectFlags.valueOf(flags.getString(i)));
            }
        }
        int code = json.optInt("code", 301);
        String from = json.optString("from", "");
        String to = json.optString("to", "");
        String comment = json.optString("comment", "");
        boolean shttpsInternal = json.optBoolean("shttps_internal", false);
        RoutingRequestHandler.RedirectRule rule = new RoutingRequestHandler.RedirectRule(
                from, to, code, mode, flagsSet.toArray(new RoutingRequestHandler.RedirectFlags[0]), enabled, comment);
        rule.shttpsInternal = shttpsInternal;
        return rule;
    }
}
