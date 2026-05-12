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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class IntentSendersMiddleware implements Middleware {
    private final String urlPathPrefix;
    private final List<IntentSender> intentSenders;
    private final Context context;
    public IntentSendersMiddleware(SHTTPSConfigAndroid config, Context context) {
        this.urlPathPrefix = config.getIntentSendingHandlersUrlPathPrefix();
        this.intentSenders = config.getIntentSenders();
        this.context = context;
    }

    @Override
    public Response handle(RequestContext context, Request request, HandlerExecutionChain chain) throws Exception {
        String path = request.path;
        if (!path.startsWith(urlPathPrefix)) {
            return chain.proceed(context, request);
        }
        path = path.substring(urlPathPrefix.length());
        MultiMap<String, String> params = request.queryParams;
        if (request.method.equals(Request.METHOD_POST)) {
            context.requestBodyReader.readRequestBody(request);
            params = request.urlEncodedPostParams;
        }
        IntentSender sender = null;
        for (IntentSender is : intentSenders) {
            if (path.equals(is.urlPath)) {
                sender = is;
                break;
            }
        }
        if (sender == null) {
            return chain.proceed(context, request);
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
                    for (String key: extras.keySet()) {
                        response.headers.put(key, Objects.requireNonNull(extras.get(key)).toString());
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
