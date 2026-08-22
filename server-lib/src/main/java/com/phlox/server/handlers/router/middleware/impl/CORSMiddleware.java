package com.phlox.server.handlers.router.middleware.impl;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CORSMiddleware implements Middleware {
    private final List<CORSRule> corsRules = new ArrayList<>();
    public static class CORSRule implements Serializable {
        public String origin;
        public String[] allowMethods;
        public String[] allowHeaders;
        public Boolean allowCredentials;
        public String[] exposeHeaders;
        public int maxAge;
    }

    public CORSMiddleware(List<CORSRule> corsRules) {
        this.corsRules.addAll(corsRules);
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        //handle OPTIONS
        if (request.method.equals(Request.METHOD_OPTIONS)) {
            String origin = request.headers.get(Request.HEADER_ORIGIN);
            String requestedMethod = request.headers.get(Request.HEADER_ACCESS_CONTROL_REQUEST_METHOD);
            boolean isCORSPreflight = origin != null || requestedMethod != null;
            if (isCORSPreflight) {
                if (origin != null) {
                    CORSRule corsRule = corsRuleForOrigin(origin);
                    if (corsRule != null) {
                        Response response = StandardResponses.NO_CONTENT();
                        addAllowOriginHeader(corsRule, origin, response.headers);
                        List<String> allowedMethods;
                        if (corsRule.allowMethods != null) {
                            allowedMethods = Arrays.asList(corsRule.allowMethods);
                        } else {
                            allowedMethods = new ArrayList<>(Arrays.asList(Request.METHOD_GET,
                                    Request.METHOD_HEAD, Request.METHOD_POST, Request.METHOD_PUT,
                                    Request.METHOD_DELETE, Request.METHOD_OPTIONS, Request.METHOD_PATCH));
                            if (request.headers.containsKey(Request.HEADER_ACCESS_CONTROL_REQUEST_METHOD)) {
                                if (requestedMethod != null) {
                                    allowedMethods.add(requestedMethod);
                                }
                            }
                        }
                        response.headers.add(Response.HEADER_ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", allowedMethods));

                        List<String> allowedHeaders = null;
                        if (corsRule.allowHeaders != null) {
                            allowedHeaders = Arrays.asList(corsRule.allowHeaders);
                        } else if (request.headers.containsKey(Request.HEADER_ACCESS_CONTROL_REQUEST_HEADERS)) {
                            String requestedHeaders = request.headers.get(Request.HEADER_ACCESS_CONTROL_REQUEST_HEADERS);
                            if (requestedHeaders != null) {
                                allowedHeaders = Arrays.asList(requestedHeaders.split(","));
                            }
                        }
                        if (allowedHeaders != null) {
                            response.headers.add(Response.HEADER_ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", allowedHeaders));
                        }

                        if (corsRule.allowCredentials != null) {
                            response.headers.add(Response.HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(corsRule.allowCredentials));
                        }

                        if (corsRule.exposeHeaders != null) {
                            response.headers.add(Response.HEADER_ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", corsRule.exposeHeaders));
                        }

                        if (corsRule.maxAge > 0) {
                            response.headers.add(Response.HEADER_ACCESS_CONTROL_MAX_AGE, String.valueOf(corsRule.maxAge));
                        }
                        return response;
                    }
                }
                Response response = StandardResponses.NO_CONTENT();
                response.headers.add(Response.HEADER_ALLOW, String.join(", ", Request.METHOD_GET, Request.METHOD_HEAD, Request.METHOD_POST, Request.METHOD_PUT, Request.METHOD_DELETE, Request.METHOD_OPTIONS, Request.METHOD_PATCH));
                return response;
            }
        }

        Response response = chain.proceed(context, request);
        if (response == null) return null;

        if (request.headers.containsKey(Request.HEADER_ORIGIN)) {
            String origin = request.headers.get(Request.HEADER_ORIGIN);
            CORSRule corsRule = corsRuleForOrigin(origin);
            if (corsRule != null) {
                addAllowOriginHeader(corsRule, origin, response.headers);
            }
        }
        return response;
    }

    private void addAllowOriginHeader(CORSRule rule, String origin, MultiMap<String, String> headers) {
        if ("*".equals(rule.origin)) {
            headers.add(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            headers.add(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.add(Response.HEADER_VARY, Response.HEADER_ORIGIN);
        }
    }

    private CORSRule corsRuleForOrigin(String origin) {
        return findRuleForOrigin(corsRules, origin);
    }

    /**
     * The rule governing an origin: the one naming it exactly, or else the catch-all {@code "*"}
     * rule, or null when the configuration has neither.
     * <p>
     * Public because WebSocket endpoints have to answer this question for themselves. A browser
     * applies none of CORS to a WebSocket handshake - there is no preflight and no
     * {@code Access-Control-Allow-Origin} check - so an endpoint that wants the configured origins
     * respected has to consult this list at the handshake and refuse the connection itself.
     */
    public static @Nullable CORSRule findRuleForOrigin(@Nullable List<CORSRule> rules, @Nullable String origin) {
        if (rules == null || origin == null) {
            return null;
        }
        CORSRule wildcard = null;
        for (CORSRule rule : rules) {
            if (rule.origin == null) continue;
            if (rule.origin.equals(origin)) {
                return rule;
            }
            if (wildcard == null && "*".equals(rule.origin)) {
                wildcard = rule;
            }
        }
        return wildcard;
    }
}
