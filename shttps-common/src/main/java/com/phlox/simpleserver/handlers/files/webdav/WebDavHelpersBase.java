package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class WebDavHelpersBase {
    protected static final int INFINITY = Integer.MAX_VALUE;

    protected SHTTPSPlatformUtils platform;
    protected UserStore userStore;
    protected LockManager locks;

    public WebDavHelpersBase(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        this.platform = platform;
        this.userStore = userStore;
        this.locks = locks;
    }

    public static class DavException extends Exception {
        final int code;
        final String reason;
        DavException(int c, String r) { code = c; reason = r; }
    }

    /** Deletes the file/tree, returns the total size of successfully deleted files
     *  (for storage quota accounting; directories count as 0, like in
     *  {@link DocumentFile#calculateDirectorySize()}). */
    protected long deleteTree(DocumentFile f, String path, List<String> failures) {
        long freedBytes = 0;
        if (f.isDirectory()) {
            DocumentFile[] kids = f.listFiles();
            if (kids != null) {
                for (DocumentFile kid : kids) {
                    String kidHref = path.endsWith("/")
                            ? path + kid.getName()
                            : path + "/" + kid.getName();
                    freedBytes += deleteTree(kid, kidHref, failures);
                }
            }
        }
        long size = f.isFile() ? f.length() : 0;
        if (!f.delete() && f.exists()) {       // it hasn't been removed and is still in place
            failures.add(path);
        } else {
            freedBytes += size;
        }
        return freedBytes;
    }

    protected Response multiStatusFailures(List<String> failedPaths) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        XmlSerializer s = platform.newXMLSerializer();
        s.setOutput(out, "UTF-8");
        s.startDocument("UTF-8", null);
        s.setPrefix("D", "DAV:");
        s.startTag("DAV:", "multistatus");
        for (String p : failedPaths) {
            s.startTag("DAV:", "response");
            text(s, "href", encodeHref(p, false));
            text(s, "status", "HTTP/1.1 403 Forbidden");
            s.endTag("DAV:", "response");
        }
        s.endTag("DAV:", "multistatus");
        s.endDocument();

        return new TextResponse(out.toByteArray(),
                "application/xml; charset=\"utf-8\"",  207, "Multi-Status");
    }

    protected static void text(XmlSerializer s, String name, String value) throws IOException {
        s.startTag("DAV:", name);
        s.text(value);
        s.endTag("DAV:", name);
    }

    public static String encodeHref(String path, boolean collection) {
        if (collection && !path.endsWith("/")) {
            path = path + "/";
        }
        try {
            return new java.net.URI(null, null, path, null, null).toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("bad path: " + path, e);
        }
    }

    protected static Response simpleStatus(int code, String reason) {
        return new Response(code, reason);
    }

    public static String extractLockTokenFromIf(String ifHeader) {
        if (ifHeader == null) return null;
        int i = ifHeader.indexOf("urn:uuid:");
        if (i < 0) return null;
        int end = ifHeader.indexOf('>', i);
        return (end > i) ? ifHeader.substring(i, end) : null;
    }
}
