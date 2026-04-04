package com.phlox.server.handlers;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CORSMiddleware implements  Middleware {
    private final Map<String, CORSRule> corsRules = new HashMap<>();
    public static class CORSRule implements Serializable {
        public String origin;
        public String[] allowMethods;
        public String[] allowHeaders;
        public Boolean allowCredentials;
        public String[] exposeHeaders;
        public int maxAge;
    }

    public CORSMiddleware(List<CORSRule> corsRules) {
        for (CORSRule corsRule : corsRules) {
            this.corsRules.put(corsRule.origin, corsRule);
        }
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        //handle OPTIONS
        if (request.method.equals(Request.METHOD_OPTIONS)) {
            String origin = request.headers.get(Request.HEADER_ORIGIN);
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
                            String requestedMethod = request.headers.get(Request.HEADER_ACCESS_CONTROL_REQUEST_METHOD);
                            if (requestedMethod != null) {
                                allowedMethods.add(requestedMethod);
                            }
                        }
                    }
                    response.headers.put(Response.HEADER_ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", allowedMethods));

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
                        response.headers.put(Response.HEADER_ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", allowedHeaders));
                    }

                    if (corsRule.allowCredentials != null) {
                        response.headers.put(Response.HEADER_ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(corsRule.allowCredentials));
                    }

                    if (corsRule.exposeHeaders != null) {
                        response.headers.put(Response.HEADER_ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", corsRule.exposeHeaders));
                    }

                    if (corsRule.maxAge > 0) {
                        response.headers.put(Response.HEADER_ACCESS_CONTROL_MAX_AGE, String.valueOf(corsRule.maxAge));
                    }
                    return response;
                }
            }
            Response response = StandardResponses.NO_CONTENT();
            response.headers.put(Response.HEADER_ALLOW, String.join(", ", Request.METHOD_GET, Request.METHOD_HEAD, Request.METHOD_POST, Request.METHOD_PUT, Request.METHOD_DELETE, Request.METHOD_OPTIONS, Request.METHOD_PATCH));
            return response;
        }

        //handle all other requests
        if (request.headers.containsKey(Request.HEADER_ORIGIN)) {
            String origin = request.headers.get(Request.HEADER_ORIGIN);
            CORSRule corsRule = corsRuleForOrigin(origin);
            if (corsRule != null) {
                addAllowOriginHeader(corsRule, origin, context.additionalResponseHeaders);
            }
        }
        return null;
    }

    private void addAllowOriginHeader(CORSRule rule, String origin, MultiMap<String, String> headers) {
        if ("*".equals(rule.origin)) {
            headers.put(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            headers.put(Response.HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.put(Response.HEADER_VARY, Response.HEADER_ORIGIN);
        }
    }

    private CORSRule corsRuleForOrigin(String origin) {
        CORSRule corsRule = corsRules.get(origin);
        if (corsRule == null) {
            corsRule = corsRules.get("*");//default rule
        }
        return corsRule;
    }
}
