package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RedirectsMiddleware implements Middleware {
    public static String ORIGINAL_PATH = "original_path";
    private final List<RedirectRule> redirectRules = new ArrayList<>();

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        String path = request.path;
        context.data.put(ORIGINAL_PATH, path);
        for (RedirectRule rule : redirectRules) {
            if (!rule.enabled) continue;
            Response response = rule.tryApply(context, request);
            if (response != null) {
                return response;
            }
        }
        return null;
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
        public boolean enabled;
        public String comment;
        @Deprecated
        public boolean shttpsInternal = false;

        private Pattern fromPattern;

        public RedirectRule() {
            this.from = "";
            this.to = "";
            this.code = 302;
            this.enabled = true;
            this.comment = "";
            updatePattern();
        }

        public RedirectRule(String from, String to, int code,
                            boolean enabled, String comment) {
            this.from = from;
            this.to = to;
            this.code = code;
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

        public Response tryApply(RequestContext context, Request request) {
            Matcher m = fromPattern.matcher(request.path);
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

                return StandardResponses.REDIRECT(to, code);
            }
            return null;
        }
    }
}
