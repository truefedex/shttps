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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        //an expression this runtime cannot compile. remembered so a stored bad rule does not
        //throw on every request, and does not take the redirects screen down while it is loaded
        private transient boolean patternBroken;

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

        /**
         * A rule read back from config. The constructor compiles eagerly and throws on a bad
         * expression, which is what the editors want when the user is saving. Stored rules cannot
         * do that: the Android editor writes {@code from} straight onto the field, so an invalid
         * expression does get persisted, and a pattern the desktop runtime accepted can still be
         * rejected by Android's regex engine. Either one used to throw out of
         * {@code getRedirectRules} and crash the redirects screen (and server start) before the
         * rule could be edited or deleted. A rule restored here keeps its text and never matches.
         */
        public static RedirectRule restored(String from, String to, int code,
                                            boolean enabled, String comment) {
            RedirectRule rule = new RedirectRule();
            rule.from = from == null ? "" : from;
            rule.to = to == null ? "" : to;
            rule.code = code;
            rule.enabled = enabled;
            rule.comment = comment == null ? "" : comment;
            rule.pattern();
            return rule;
        }

        //compiles eagerly so that an invalid expression fails where the rule is created
        private void updatePattern() {
            String from = this.from;
            Pattern pattern = Pattern.compile(from == null ? "" : from);
            fromPattern = pattern;
            fromPatternSource = from;
            patternBroken = false;
        }

        //null when the current 'from' cannot be compiled. does not throw: a request must keep
        //going, and the field may have been assigned an expression the constructor would reject
        private Pattern pattern() {
            String from = this.from;
            if (patternBroken && Objects.equals(from, fromPatternSource)) {
                return null;
            }
            Pattern pattern = fromPattern;
            if (pattern != null && Objects.equals(from, fromPatternSource)) {
                return pattern;
            }
            try {
                pattern = Pattern.compile(from == null ? "" : from);
                fromPattern = pattern;
                fromPatternSource = from;
                patternBroken = false;
                return pattern;
            } catch (PatternSyntaxException e) {
                fromPattern = null;
                fromPatternSource = from;
                patternBroken = true;
                return null;
            }
        }

        public void setFrom(String from) {
            this.from = from;
            updatePattern();
        }

        public Response tryApply(RequestContext context, Request request) {
            Pattern pattern = pattern();
            if (pattern == null) {
                return null;
            }
            Matcher m = pattern.matcher(request.path);
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
