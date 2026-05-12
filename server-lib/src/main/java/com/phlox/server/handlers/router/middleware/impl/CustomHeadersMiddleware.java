package com.phlox.server.handlers.router.middleware.impl;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.RadixTree;

import java.util.HashSet;
import java.util.List;

public class CustomHeadersMiddleware implements Middleware {
    private final RadixTree<Rule[]> rules = new RadixTree<>();

    public enum IfHeadersExist {
        OVERRIDE,
        APPEND,
        IGNORE
    }

    public static final class Rule {
        public String path;
        public MultiMap<String, String> headers;
        public IfHeadersExist ifHeadersExist;
        public HashSet<String> filterMethods;
        public HashSet<Integer> filterStatusCodes;
        public HashSet<String> filterPostfixes;

        public Rule(String path, MultiMap<String, String> headers, IfHeadersExist ifHeadersExist,
                    HashSet<String> filterMethods, HashSet<Integer> filterStatusCodes,
                    HashSet<String> filterPostfixes) {
            this.path = path;
            this.headers = headers;
            this.ifHeadersExist = ifHeadersExist;
            this.filterMethods = filterMethods;
            this.filterStatusCodes = filterStatusCodes;
            this.filterPostfixes = filterPostfixes;
        }
    }

    public CustomHeadersMiddleware(List<Rule> rules) {
        MultiMap<String, Rule> groupedRules = new MultiMap<>();
        for (Rule rule : rules) {
            groupedRules.put(rule.path, rule);
        }
        groupedRules.forEach((key, ruleSet) ->
                this.rules.put(key, ruleSet.toArray(new Rule[0])));
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        Response response = chain.proceed(context, request);
        if (response == null) return null;
        rules.visitPrefixes(request.path, (key, prefixEndIndex, ruleSet) -> {
            for (Rule rule : ruleSet) {
                if (rule.filterMethods != null && !rule.filterMethods.contains(request.method)) {
                    continue;
                }
                if (rule.filterStatusCodes != null && !rule.filterStatusCodes.contains(response.code)) {
                    continue;
                }
                if (rule.filterPostfixes != null && !endsWithAny(request.path, rule.filterPostfixes)) {
                    continue;
                }
                rule.headers.forEach((name, values) -> {
                    if (response.headers.containsKey(name)) {
                        if (rule.ifHeadersExist == IfHeadersExist.IGNORE) {
                            return;
                        } else if (rule.ifHeadersExist == IfHeadersExist.APPEND) {
                            values.forEach(value -> response.headers.put(name, value));
                            return;
                        } else if (rule.ifHeadersExist == IfHeadersExist.OVERRIDE) {
                            response.headers.removeAll(name);
                        }
                    }
                    response.headers.put(name, String.join(", ", values));
                });
            }
            return true;
        });
        return response;
    }

    private boolean endsWithAny(String str, HashSet<String> postfixes) {
        for (String postfix : postfixes) {
            if (str.endsWith(postfix)) {
                return true;
            }
        }
        return false;
    }
}
