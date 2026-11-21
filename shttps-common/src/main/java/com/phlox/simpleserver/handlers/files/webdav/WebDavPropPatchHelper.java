package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

/**
 * Dummy PROPPATCH handler - for now for simplicity we just return ok for all prop change requests
 */
public class WebDavPropPatchHelper extends WebDavHelpersBase {
    public WebDavPropPatchHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }

    public Response handlePropPatch(FilesRequestHandler filesRequestHandler, RequestContext context,
                                    Request request, DocumentFile userRoot, DocumentFile target,
                                    String destPath, User user) throws Exception {

        if (target == null || !target.exists()) {
            return StandardResponses.NOT_FOUND();
        }

        String provided = extractLockTokenFromIf(request.headers.get("if"));
        if (!locks.mayWrite(request.path, provided)) {
            return simpleStatus(423, "Locked");
        }

        context.requestBodyReader.readRequestBody(request);

        // We analyze which properties the client wants to set/remove.
        // We only need their names (namespace + local), we ignore the values.
        List<QName> props = parseProppatchProps(request.body.asBytes());

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        XmlSerializer s = platform.newXMLSerializer();
        s.setOutput(out, "UTF-8");
        s.startDocument("UTF-8", null);
        s.setPrefix("D", "DAV:");
        s.startTag("DAV:", "multistatus");
        s.startTag("DAV:", "response");
        text(s, "href", encodeHref(request.path, target.isDirectory()));

        // One propstat with status 200 for all requested properties:
        // We make believe we applied them. Win32 timestamps are simply swallowed.
        s.startTag("DAV:", "propstat");
        s.startTag("DAV:", "prop");
        for (QName p : props) {
            // declare the property namespace and write an empty element-name
            s.startTag(p.getNamespaceURI(), p.getLocalPart());
            s.endTag(p.getNamespaceURI(), p.getLocalPart());
        }
        s.endTag("DAV:", "prop");
        text(s, "status", "HTTP/1.1 200 OK");
        s.endTag("DAV:", "propstat");

        s.endTag("DAV:", "response");
        s.endTag("DAV:", "multistatus");
        s.endDocument();

        return new TextResponse(out.toByteArray(),
                "application/xml; charset=\"utf-8\"",  207, "Multi-Status");
    }

    private List<QName> parseProppatchProps(byte[] body) throws Exception {
        List<QName> result = new ArrayList<>();
        if (body == null || body.length == 0) return result;

        XmlPullParser p = platform.newXMLPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); }
        catch (XmlPullParserException ignore) {}
        p.setInput(new ByteArrayInputStream(body), "UTF-8");

        boolean inProp = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                if ("DAV:".equals(p.getNamespace()) && "prop".equals(p.getName())) {
                    inProp = true;
                } else if (inProp) {
                    result.add(new QName(p.getNamespace(), p.getName()));
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("DAV:".equals(p.getNamespace()) && "prop".equals(p.getName())) {
                    inProp = false;
                }
            }
        }
        return result;
    }
}
