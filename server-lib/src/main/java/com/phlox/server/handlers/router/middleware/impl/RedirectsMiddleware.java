package com.phlox.server.handlers.router.middleware.impl;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
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
    /** Same key as {@link RequestContext#ORIGINAL_PATH}, kept for existing callers. */
    public static final String ORIGINAL_PATH = RequestContext.ORIGINAL_PATH;
    private final List<RedirectRule> redirectRules = new ArrayList<>();

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        //the Router records it first; this covers the middleware being used without one
        context.data.putIfAbsent(ORIGINAL_PATH, request.path);
        for (RedirectRule rule : redirectRules) {
            if (!rule.enabled) continue;
            Response response = rule.tryApply(context, request);
            if (response != null) {
                return response;
            }
        }
        return chain.proceed(context, request);
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

        //compiled from 'from' on first use and again whenever 'from' changes: the field is public
        //and assigned directly (the Android rule editor does), which used to leave a stale pattern
        private transient Pattern fromPattern;
        private transient String fromPatternSource;

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

        //compiles eagerly so that an invalid expression fails where the rule is created
        private void updatePattern() {
            pattern();
        }

        private Pattern pattern() {
            String from = this.from;
            Pattern pattern = fromPattern;
            if (pattern == null || !from.equals(fromPatternSource)) {
                pattern = Pattern.compile(from);
                fromPattern = pattern;
                fromPatternSource = from;
            }
            return pattern;
        }

        public void setFrom(String from) {
            this.from = from;
            updatePattern();
        }

        public Response tryApply(RequestContext context, Request request) {
            Matcher m = pattern().matcher(request.path);
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
