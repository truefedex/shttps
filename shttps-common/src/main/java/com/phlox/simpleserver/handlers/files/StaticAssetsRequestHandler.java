package com.phlox.simpleserver.handlers.files;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.platform.MimeTypeMap;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.Utils;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

public class StaticAssetsRequestHandler implements RequestHandler {
    /** What a bundled page writes where the build token belongs, e.g. {@code common.js?v={{v}}}. */
    private static final String VERSION_PLACEHOLDER = "{{v}}";
    /** Only a guard on the initial buffer size - a bundled page is kilobytes, not megabytes. */
    private static final int MAX_PAGE_SIZE = 1024 * 1024;

    private final String baseAssetsPath;
    private final String baseRequestPath;
    private final SHTTPSConfig config;
    private final SHTTPSPlatformUtils platformUtils = SHTTPSApp.getInstance().platformUtils;

    public StaticAssetsRequestHandler(String baseAssetsPath, String baseRequestPath, SHTTPSConfig config) {
        this.baseAssetsPath = baseAssetsPath;
        this.baseRequestPath = baseRequestPath;
        this.config = config;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        boolean isHead = request.method.equals(Request.METHOD_HEAD);
        if (!request.method.equals(Request.METHOD_GET) && !isHead) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET, Request.METHOD_HEAD});
        }

        String destPath = request.path;
        if (baseRequestPath != null) {
            if (!destPath.startsWith(baseRequestPath)) {
                return StandardResponses.NOT_FOUND();
            }
            destPath = destPath.substring(baseRequestPath.length());
        }
        // Check if the path contains ".." to prevent directory traversal
        if (Utils.contains(destPath.split("/"), "..")) {
            return StandardResponses.FORBIDDEN("Path contains '..'");
        }

        destPath = baseAssetsPath + (destPath.startsWith("/") ? destPath : ("/" + destPath));
        if (config.getRedirectToIndex() && destPath.endsWith("/")) {
            String checkIndexPath = destPath + "index.html";
            if (platformUtils.getAssetSize(checkIndexPath) != -1) {
                destPath = checkIndexPath;
            }
        }
        long assetSize = platformUtils.getAssetSize(destPath);
        if (assetSize == -1) {
            return StandardResponses.NOT_FOUND();
        }
        long assetLastModified = platformUtils.getAssetLastModified(destPath);
        String version = SHTTPSApp.getInstance().getWebAssetsVersion();
        //a page is the one asset that names the others, so it is the one we stamp the token into
        byte[] page = destPath.toLowerCase(Locale.ROOT).endsWith(".html")
                ? stampVersion(platformUtils.openAssetStream(destPath), assetSize, version) : null;
        if (page != null) {
            assetSize = page.length;
        }
        String etag = assetETag(assetSize, assetLastModified, version);
        if (isNotModified(request, etag, assetLastModified)) {
            Response notModified = StandardResponses.NOT_MODIFIED();
            addCacheHeaders(notModified, etag, assetLastModified);
            return notModified;
        }
        String type = "application/octet-stream";

        String extension = null;
        int i = destPath.lastIndexOf('.');
        if (i > 0 && i < destPath.length() - 1) {
            extension = destPath.substring(i + 1);
        }
        if (extension != null) {
            String mimeTypeFromExtension = MimeTypeMap.getInstance().getMimeTypeFromExtension(extension);
            if (mimeTypeFromExtension != null) {
                type = mimeTypeFromExtension;
            }
        }

        if (HTTPUtils.isTextContentType(type)) {
            String charset = config.getDefaultTextCharset();
            if (charset == null || charset.isEmpty()) {
                charset = StandardCharsets.UTF_8.name();
            }
            type += "; charset=" + charset;
        }

        Response response;
        if (isHead) {
            response = new Response(type, assetSize, null);
        } else if (page != null) {
            response = new Response(type, assetSize, new ByteArrayInputStream(page));
        } else {
            response = new Response(type, assetSize, new BufferedInputStream(platformUtils.openAssetStream(destPath)));
        }

        addCacheHeaders(response, etag, assetLastModified);

        return response;
    }

    /**
     * A validator that changes whenever the file behind it does. Built from the asset's own size
     * and, when the platform can tell us, its modification time - the pair nginx and friends use.
     * It exists because {@link SHTTPSPlatformUtils#getAssetLastModified} has nothing to report on
     * some platforms: a jar entry carries no usable timestamp, so every bundled file of a desktop
     * build dates to the epoch, and a date is then no validator at all.
     */
    private static String assetETag(long assetSize, long assetLastModified, String version) {
        String value = Long.toHexString(assetSize);
        if (assetLastModified > 0) {
            value += "-" + Long.toHexString(assetLastModified);
        }
        return "\"" + value + "-" + version + "\"";
    }

    /**
     * Replaces the {@value #VERSION_PLACEHOLDER} the bundled pages write into the URL of every
     * script, stylesheet and icon they pull in. Those URLs are what a browser keys its cache on,
     * so stamping the build onto them is what gets a page that is already in a cache the new
     * files rather than the ones it kept from the build before. The pages are a few kilobytes,
     * which is why this is done per request rather than kept anywhere.
     *
     * @return the page with the token filled in, or null if the asset could not be read
     */
    private static byte[] stampVersion(InputStream stream, long assetSize, String version) throws IOException {
        byte[] raw;
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(
                    assetSize > 0 && assetSize < MAX_PAGE_SIZE ? (int) assetSize : 8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            raw = buffer.toByteArray();
        }
        String page = new String(raw, StandardCharsets.UTF_8);
        if (!page.contains(VERSION_PLACEHOLDER)) {
            return raw;
        }
        return page.replace(VERSION_PLACEHOLDER, version).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isNotModified(Request request, String etag, long assetLastModified) {
        //If-None-Match wins over If-Modified-Since when both are sent, as RFC 7232 requires
        String ifNoneMatch = request.headers.get(Request.HEADER_IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                candidate = candidate.trim();
                if (candidate.startsWith("W/")) {
                    candidate = candidate.substring(2);
                }
                if (candidate.equals("*") || candidate.equals(etag)) {
                    return true;
                }
            }
            return false;
        }
        if (assetLastModified <= 0) {
            return false;
        }
        String ifModifiedSinceStr = request.headers.get(Request.HEADER_IF_MODIFIED_SINCE);
        if (ifModifiedSinceStr == null) {
            return false;
        }
        Date date;
        try {
            date = HTTPUtils.getHTTPDateFormat().parse(ifModifiedSinceStr);
        } catch (Exception e) {
            return false;
        }
        //comparing skipping milliseconds
        return date != null && (assetLastModified / 1000) <= (date.getTime() / 1000);
    }

    /**
     * Every bundled asset is revalidated rather than trusted for a while, and an unchanged one
     * still costs only a 304.
     * <p>
     * Without {@code no-cache} a response that carries no expiry at all is cached by a heuristic:
     * a tenth of the time since its Last-Modified date. An asset dated to the epoch - which is
     * what a jar entry with no timestamp gives us - is therefore considered fresh for over five
     * years, and the browser never asks for it again. Updating the app then leaves the old page
     * scripts in place against the new markup, which looks like a dead UI rather than a stale
     * file: the menu button that no longer has the handler the current page expects, say. A LAN
     * file server has no reason to trade that away for one saved round trip per file.
     */
    private static void addCacheHeaders(Response response, String etag, long assetLastModified) {
        response.headers.add(Response.HEADER_ETAG, etag);
        response.headers.add(Response.HEADER_CACHE_CONTROL, "no-cache");
        if (assetLastModified > 0) {
            response.headers.add(Response.HEADER_LAST_MODIFIED,
                    HTTPUtils.getHTTPDateFormat().format(new Date(assetLastModified)));
        }
    }
}
