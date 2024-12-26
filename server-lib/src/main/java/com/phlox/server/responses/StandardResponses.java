package com.phlox.server.responses;

import static java.net.HttpURLConnection.*;

import com.phlox.server.SimpleHttpServer;

public final class StandardResponses {
    public static final String MSG_NOT_FOUND = "Not found";
    public static final String MSG_OK = "Ok";
    public static final String MSG_NO_CONTENT = "No Content";
    public static final String MSG_INTERNAL_SERVER_ERROR = "Internal Server Error";
    public static final String MSG_BAD_REQUEST = "Bad Request";
    public static final String MSG_FORBIDDEN = "Forbidden";
    public static final String MSG_MOVED_PERMANENTLY = "Moved Permanently";

    public static final String MSG_METHOD_NOT_ALLOWED = "Method Not Allowed";

    public StandardResponses() {
    }

    public static Response NO_CONTENT() { return new Response(HTTP_NO_CONTENT, MSG_NO_CONTENT); }
    public static Response OK(String msg) { return new TextResponse(HTTP_OK, MSG_OK, msg); }
    public static Response OK(String msg, String mimeType) { return new TextResponse(HTTP_OK, MSG_OK, msg, mimeType); }
    public static Response NOT_FOUND() { return new Response(HTTP_NOT_FOUND, MSG_NOT_FOUND); }
    public static Response NOT_FOUND(String msg) { return new TextResponse(HTTP_NOT_FOUND, MSG_NOT_FOUND, msg); }
    public static Response INTERNAL_SERVER_ERROR() { return new Response(HTTP_INTERNAL_ERROR, MSG_INTERNAL_SERVER_ERROR); }
    public static Response INTERNAL_SERVER_ERROR(String msg) { return new TextResponse(HTTP_INTERNAL_ERROR, MSG_INTERNAL_SERVER_ERROR, msg); }
    public static Response BAD_REQUEST() { return new Response(HTTP_BAD_REQUEST, MSG_BAD_REQUEST); }
    public static Response BAD_REQUEST(String msg) { return new TextResponse(HTTP_BAD_REQUEST, MSG_BAD_REQUEST, msg); }
    public static Response FORBIDDEN(String msg) { return new TextResponse(HTTP_FORBIDDEN, MSG_FORBIDDEN, msg); }
    public static Response MOVED_PERMANENTLY(String newLocation) {
        Response response = new Response(HTTP_MOVED_PERM, MSG_MOVED_PERMANENTLY);
        response.headers.put(Response.HEADER_LOCATION, newLocation);
        return response;
    }

    public static Response METHOD_NOT_ALLOWED(String[] allowedMethods) {
        Response response = new Response(HTTP_BAD_METHOD, MSG_METHOD_NOT_ALLOWED);
        response.headers.put(Response.HEADER_ALLOW, String.join(", ", allowedMethods));
        return response;
    }

    public static Response NOT_MODIFIED() { return new Response(HTTP_NOT_MODIFIED, "Not Modified"); }

    public static Response UNSUPPORTED_MEDIA_TYPE() { return new Response(HTTP_UNSUPPORTED_TYPE, "Unsupported Media Type"); }
}
