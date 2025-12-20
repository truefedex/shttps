package com.phlox.server.responses;

import static java.net.HttpURLConnection.*;

public final class StandardResponses {
    public static final String PHRASE_NOT_FOUND = "Not found";
    public static final String PHRASE_OK = "Ok";
    public static final String PHRASE_NO_CONTENT = "No Content";
    public static final String PHRASE_INTERNAL_SERVER_ERROR = "Internal Server Error";
    public static final String PHRASE_BAD_REQUEST = "Bad Request";
    public static final String PHRASE_FORBIDDEN = "Forbidden";
    public static final String PHRASE_MOVED_PERMANENTLY = "Moved Permanently";

    public static final String PHRASE_METHOD_NOT_ALLOWED = "Method Not Allowed";
    public static final String PHRASE_REDIRECT = "Redirect";
    public static final String PHRASE_NOT_MODIFIED = "Not Modified";

    public static final String PHRASE_UNAUTHORIZED = "Unauthorized";
    public static final String PHRASE_UNSUPPORTED_MEDIA_TYPE = "Unsupported Media Type";
    public static final String PHRASE_PAYLOAD_TOO_LARGE = "Payload Too Large";

    public StandardResponses() {
    }

    public static Response NO_CONTENT() { return new Response(HTTP_NO_CONTENT, PHRASE_NO_CONTENT); }
    public static Response OK(String msg) { return new TextResponse(HTTP_OK, PHRASE_OK, msg); }
    public static Response OK(String msg, String mimeType) { return new TextResponse(HTTP_OK, PHRASE_OK, msg, mimeType); }
    public static Response NOT_FOUND() { return new Response(HTTP_NOT_FOUND, PHRASE_NOT_FOUND); }
    public static Response NOT_FOUND(String msg) { return new TextResponse(HTTP_NOT_FOUND, PHRASE_NOT_FOUND, msg); }
    public static Response INTERNAL_SERVER_ERROR() { return new Response(HTTP_INTERNAL_ERROR, PHRASE_INTERNAL_SERVER_ERROR); }
    public static Response INTERNAL_SERVER_ERROR(String msg) { return new TextResponse(HTTP_INTERNAL_ERROR, PHRASE_INTERNAL_SERVER_ERROR, msg); }
    public static Response BAD_REQUEST() { return new Response(HTTP_BAD_REQUEST, PHRASE_BAD_REQUEST); }
    public static Response BAD_REQUEST(String msg) { return new TextResponse(HTTP_BAD_REQUEST, PHRASE_BAD_REQUEST, msg); }
    public static Response FORBIDDEN(String msg) { return new TextResponse(HTTP_FORBIDDEN, PHRASE_FORBIDDEN, msg); }
    public static Response FORBIDDEN() { return new TextResponse(HTTP_FORBIDDEN, PHRASE_FORBIDDEN, PHRASE_FORBIDDEN); }
    public static Response PAYLOAD_TOO_LARGE() {return new Response(HTTP_ENTITY_TOO_LARGE, PHRASE_PAYLOAD_TOO_LARGE); }
    public static Response UNAUTHORIZED() { return new Response(HTTP_UNAUTHORIZED, PHRASE_UNAUTHORIZED); }

    public static Response MOVED_PERMANENTLY(String newLocation) {
        Response response = new Response(HTTP_MOVED_PERM, PHRASE_MOVED_PERMANENTLY);
        response.headers.put(Response.HEADER_LOCATION, newLocation);
        return response;
    }
    public static Response REDIRECT(String newLocation, int code) {
        return REDIRECT(newLocation, PHRASE_REDIRECT, code);
    }

    public static Response REDIRECT(String newLocation, String phrase, int code) {
        Response response = new Response(code, phrase);
        response.headers.put(Response.HEADER_LOCATION, newLocation);
        return response;
    }

    public static Response METHOD_NOT_ALLOWED(String[] allowedMethods) {
        Response response = new Response(HTTP_BAD_METHOD, PHRASE_METHOD_NOT_ALLOWED);
        response.headers.put(Response.HEADER_ALLOW, String.join(", ", allowedMethods));
        return response;
    }

    public static Response NOT_MODIFIED() { return new Response(HTTP_NOT_MODIFIED, PHRASE_NOT_MODIFIED); }

    public static Response UNSUPPORTED_MEDIA_TYPE() { return new Response(HTTP_UNSUPPORTED_TYPE, PHRASE_UNSUPPORTED_MEDIA_TYPE); }
}
