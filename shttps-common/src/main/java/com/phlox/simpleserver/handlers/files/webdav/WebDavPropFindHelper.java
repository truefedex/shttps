package com.phlox.simpleserver.handlers.files.webdav;

import static com.phlox.simpleserver.handlers.files.webdav.PropRequest.SUPPORTED;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.files.BaseFileRequestHandler;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class WebDavPropFindHelper extends WebDavHelpersBase{
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC);
    public WebDavPropFindHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }


    public Response handlePropFind(@NotNull BaseFileRequestHandler baseHandler,
                                   @NotNull RequestContext context, @NotNull Request req,
                                   @NotNull DocumentFile root, @Nullable DocumentFile res,
                                   @NotNull String destPath, @Nullable User user) throws Exception {
        if (res == null || !res.exists()) {
            return StandardResponses.NOT_FOUND();
        }

        if (baseHandler.checkIsForbidden(user,
                destPath, FileListRequestHandler.LIST_CONTENTS_OPERATION, Map.of(
                        "sort", "default",
                        "search", "",
                        "sort-reversed", "false"
                ),
                User.FileSystemRights.LIST_CONTENTS))
            return StandardResponses.FORBIDDEN();

        String depth = req.headers.get("depth");
        if (depth == null) depth = "1";
        depth = depth.split(",")[0].trim();          // cut non-standard ",noroot" from Windows
        if ("infinity".equals(depth)) {
            return finiteDepthRequired();
        }

        context.requestBodyReader.readRequestBody(req);
        PropRequest want = parsePropfindBody(req.body != null ? req.body.asBytes() : null);

        List<DocumentFile> targets = new ArrayList<>();
        targets.add(res);
        if ("1".equals(depth) && res.isDirectory()) {
            targets.addAll(Arrays.asList(res.listFiles()));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        XmlSerializer s = platform.newXMLSerializer();
        s.setOutput(out, "UTF-8");
        s.startDocument("UTF-8", null);
        s.setPrefix("D", "DAV:");
        s.startTag("DAV:", "multistatus");
        for (DocumentFile r : targets) writeResponse(s, r, want, root);
        s.endTag("DAV:", "multistatus");
        s.endDocument();

        return new TextResponse(out.toByteArray(),
                "application/xml; charset=\"utf-8\"",  207, "Multi-Status");
    }

    private void writeResponse(XmlSerializer s, DocumentFile r, PropRequest want, @NotNull DocumentFile root) throws IOException {
        boolean isCollection = r.isDirectory();
        s.startTag("DAV:", "response");
        text(s, "href", encodeHref(calcPath(r, root), isCollection));

        s.startTag("DAV:", "propstat");
        s.startTag("DAV:", "prop");
        if (want.wants("resourcetype")) {
            s.startTag("DAV:", "resourcetype");
            if (isCollection) { s.startTag("DAV:", "collection"); s.endTag("DAV:", "collection"); }
            s.endTag("DAV:", "resourcetype");
        }
        if (want.wants("displayname"))     text(s, "displayname", r.getName());
        if (want.wants("getlastmodified")) text(s, "getlastmodified",
                HTTP_DATE.format(Instant.ofEpochMilli(r.lastModified())));
        if (want.wants("creationdate"))    text(s, "creationdate",
                ISO_DATE.format(Instant.ofEpochMilli(r.created())));
        if (!isCollection) {
            if (want.wants("getcontentlength")) text(s, "getcontentlength", Long.toString(r.length()));
            if (want.wants("getcontenttype"))   text(s, "getcontenttype", r.getType());
            //if (want.wants("getetag"))          text(s, "getetag", etag(r));
        }
        s.endTag("DAV:", "prop");
        text(s, "status", "HTTP/1.1 200 OK");
        s.endTag("DAV:", "propstat");

        List<String> missing = new ArrayList<>();
        want.unsupportedAmongRequested(SUPPORTED, missing);
        if (!missing.isEmpty()) {
            s.startTag("DAV:", "propstat");
            s.startTag("DAV:", "prop");
            for (String n : missing) { s.startTag("DAV:", n); s.endTag("DAV:", n); }
            s.endTag("DAV:", "prop");
            text(s, "status", "HTTP/1.1 404 Not Found");
            s.endTag("DAV:", "propstat");
        }
        s.endTag("DAV:", "response");
    }

    private String calcPath(DocumentFile r, @NotNull DocumentFile root) {
        String relativePath = root.getRelativePath(r);
        if (!relativePath.startsWith("/")) relativePath = "/" + relativePath;
        if (r.isDirectory() && !relativePath.endsWith("/")) relativePath = relativePath + "/";
        return relativePath;
    }

    private PropRequest parsePropfindBody(byte[] body) throws Exception {
        if (body == null || body.length == 0) return PropRequest.allprop();   // empty body == allprop
        XmlPullParser p = platform.newXMLPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); // best-effort; default anyway false
        } catch (XmlPullParserException ignore) {
            // it is ok
        }
        p.setInput(new ByteArrayInputStream(body), "UTF-8");

        Set<String> names = new LinkedHashSet<>();
        boolean allprop = false, propname = false, inProp = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev != XmlPullParser.START_TAG) continue;
            boolean dav = "DAV:".equals(p.getNamespace());
            String n = p.getName();
            if (dav && "allprop".equals(n))       allprop = true;
            else if (dav && "propname".equals(n)) propname = true;
            else if (dav && "prop".equals(n))     inProp = true;
            else if (inProp)                      names.add(n);   // foreign namespace also → go to 404
        }
        if (allprop)  return PropRequest.allprop();
        if (propname) return PropRequest.propname();
        return PropRequest.named(names);
    }

    private Response finiteDepthRequired() {
        String body =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                        "<D:error xmlns:D=\"DAV:\"><D:propfind-finite-depth/></D:error>";
        TextResponse response = new TextResponse(403, "Forbidden", body);
        response.setContentType("application/xml; charset=\"utf-8\"");
        return response;
    }
}
