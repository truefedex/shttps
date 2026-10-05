package com.phlox.simpleserver.middleware.intentsenders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.phlox.server.handlers.router.middleware.HandlerExecutionChain;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.MultiMap;
import com.phlox.simpleserver.SHTTPSConfigAndroid;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class IntentSendersMiddleware implements Middleware {
    private final String urlPathPrefix;
    private final List<IntentSender> intentSenders;
    private final Context context;

    public IntentSendersMiddleware(SHTTPSConfigAndroid config, Context context) {
        this.urlPathPrefix = config.getIntentSendingHandlersUrlPathPrefix();
        List<IntentSender> senders = config.getIntentSenders();
        this.intentSenders = senders != null ? senders : Collections.emptyList();
        this.context = context;
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        if (intentSenders.isEmpty()) {
            return chain.proceed(context, request);
        }

        String requestPath = request.path;
        String prefix = urlPathPrefix;
        if (prefix.endsWith("/") && prefix.length() > 1) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String subPath = null;
        if (requestPath.equalsIgnoreCase(prefix)) {
            subPath = "/";
        } else if (requestPath.startsWith(prefix + "/")) {
            subPath = requestPath.substring(prefix.length());
        }

        IntentSender sender = null;
        for (IntentSender is : intentSenders) {
            String isPath = is.urlPath.trim();
            String isPathWithSlash = isPath.startsWith("/") ? isPath : "/" + isPath;

            if (subPath != null && subPath.equalsIgnoreCase(isPathWithSlash)) {
                sender = is;
                break;
            }
            if (requestPath.equalsIgnoreCase(isPathWithSlash)) {
                sender = is;
                break;
            }
        }

        if (sender == null) {
            return chain.proceed(context, request);
        }

        MultiMap<String, String> params = request.queryParams;
        if (request.method.equals(Request.METHOD_POST)) {
            context.requestBodyReader.readRequestBody(request);
            params = request.urlEncodedPostParams;
        }

        if (sender.target.equals(IntentSender.IntentTarget.ORDERED_BROADCAST)) {
            CompletableFuture<Response> future = new CompletableFuture<>();
            sender.send(this.context, params, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Response response;
                    String data = getResultData();
                    if (data != null) {
                        response = new Response(getResultCode(), "OK", new ByteArrayInputStream(data.getBytes()));
                    } else {
                        response = new Response(getResultCode(), "OK");
                    }
                    Bundle extras = getResultExtras(true);
                    if (extras != null) {
                        for (String key : extras.keySet()) {
                            Object value = extras.get(key);
                            if (value != null) {
                                response.headers.add(key, value.toString());
                            }
                        }
                    }
                    future.complete(response);
                }
            });

            return future.get(30, TimeUnit.SECONDS);
        } else {
            sender.send(this.context, params, null);
            return StandardResponses.NO_CONTENT();
        }
    }
}
