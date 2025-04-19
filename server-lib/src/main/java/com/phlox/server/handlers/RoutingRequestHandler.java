package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.RadixTree;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import proguard.annotation.KeepClassMemberNames;

public class RoutingRequestHandler implements RequestHandler {
    public static String ORIGINAL_PATH = "original_path";
    private final Map<String, Map<String, RequestHandler>> routes = new HashMap<>();//method -> path -> route
    private final RadixTree<RequestHandler> prefixedRoutes = new RadixTree<>();
    private final List<RedirectRule> redirectRules = new ArrayList<>();

    @Override
    public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
        String path = request.path;
        context.data.put(ORIGINAL_PATH, path);
        RequestHandler srcHandler = findMatchingHandler(request.method, path);
        for (RedirectRule rule : redirectRules) {
            if (!rule.enabled) continue;
            String to = rule.tryMatch(path, request.method, srcHandler);
            if (to == null) continue;
            RequestHandler destHandler = findMatchingHandler(request.method, to);
            if (rule.flags.contains(RedirectFlags.IF_DEST_CAN_BE_PROCESSED)) {
                if (destHandler == null) destHandler = srcHandler;
                if (destHandler == null || !destHandler.canHandle(to, request.method)) {
                    continue;
                }
            }
            srcHandler  = destHandler;

            switch (rule.mode) {
                case BY_HTTP_CODE:
                    return StandardResponses.REDIRECT(to, rule.code);
                case INTERNAL:
                    path = to;
                    break;
                case INTERNAL_EXTRACT_SUB_PATH:
                    if (path.startsWith(to)) {
                        path = path.substring(to.length());
                    }
                    break;
                case INTERNAL_PRESERVE_PATH:
                    //nothing to do, preserve the original path
                    break;
            }
        }

        request.path = path;
        if (srcHandler == null) {
            return null;
        }
        return srcHandler.handleRequest(context, request, requestBodyReader);
    }

    private RequestHandler findMatchingHandler(String method, String path) {
        Map<String, RequestHandler> methodRoutes = routes.get(method);
        if (methodRoutes == null) {
            return null;
        }
        RequestHandler handler = methodRoutes.get(path);
        if (handler != null) {
            return handler;
        }
        return prefixedRoutes.findLongestPrefix(path);
    }

    public void addRoute(String path, Set<String> methods, RequestHandler handler) {
        for (String method : methods) {
            Map<String, RequestHandler> methodRoutes = routes.get(method);
            if (methodRoutes == null) {
                methodRoutes = new HashMap<>();
                routes.put(method, methodRoutes);
            }
            //computeIfAbsent is available in Android starting API 23
            //Map<String, RequestHandler> methodRoutes = routes.computeIfAbsent(method, k -> new HashMap<>());
            methodRoutes.put(path, handler);
        }
    }

    public void addRouteByPathPrefix(String pathPrefix, RequestHandler handler) {
        prefixedRoutes.put(pathPrefix, handler);
    }

    public void addRedirectRule(RedirectRule rule) {
        redirectRules.add(rule);
    }

    public void setRedirectRules(List<RedirectRule> rules) {
        redirectRules.clear();
        redirectRules.addAll(rules);
    }

    public static class RedirectRule implements Serializable {
        public String from;
        public String to;
        public int code;
        public RedirectMode mode;
        public Set<RedirectFlags> flags;
        public boolean enabled;
        public String comment;
        public boolean shttpsInternal = false;

        private Pattern fromPattern;

        public RedirectRule() {
            this.from = "";
            this.to = "";
            this.code = 302;
            this.mode = RedirectMode.BY_HTTP_CODE;
            this.flags = new HashSet<>();
            this.enabled = true;
            this.comment = "";
            updatePattern();
        }

        public RedirectRule(String from, String to, int code, RedirectMode mode, RedirectFlags[] flags,
                            boolean enabled, String comment) {
            this.from = from;
            this.to = to;
            this.code = code;
            this.mode = mode;
            this.flags = flags != null ? new HashSet<>(Arrays.asList(flags)) : new HashSet<>();
            this.enabled = enabled;
            this.comment = comment;
            updatePattern();
        }

        private void updatePattern() {
            this.fromPattern = Pattern.compile(from);
        }

        public void setFrom(String from) {
            this.from = from;
            updatePattern();
        }

        public String tryMatch(String path, String method, RequestHandler srcHandler) {
            if (flags.contains(RedirectFlags.IF_SOURCE_CAN_BE_PROCESSED)) {
                if (srcHandler == null || !srcHandler.canHandle(path, method)) {
                    return null;
                }
            }
            if (flags.contains(RedirectFlags.IF_SOURCE_CANT_BE_PROCESSED)) {
                if (srcHandler != null && srcHandler.canHandle(path, method)) {
                    return null;
                }
            }

            Matcher m = fromPattern.matcher(path);
            if (m.matches()) {
                //loop named groups and replace them in the 'to' string
                String to = this.to;
                //seems named groups are not supported yet
                /*for (Map.Entry<String, Integer> entry : m.namedGroups().entrySet()) {
                    to = to.replace("{" + entry.getKey() + "}", m.group(entry.getValue()));
                }*/
                for (int i = 1; i <= m.groupCount(); i++) {
                    String group = m.group(i);
                    if (group == null) {
                        group = "";
                    }
                    to = to.replace("{" + i + "}", group);
                }
                return to;
            }
            return null;
        }
    }

    @KeepClassMemberNames
    public enum RedirectMode {BY_HTTP_CODE, INTERNAL, INTERNAL_PRESERVE_PATH, INTERNAL_EXTRACT_SUB_PATH }
    @KeepClassMemberNames
    public enum RedirectFlags {IF_DEST_CAN_BE_PROCESSED, IF_SOURCE_CAN_BE_PROCESSED, IF_SOURCE_CANT_BE_PROCESSED}
}
