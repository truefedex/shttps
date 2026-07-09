package com.phlox.simpleserver.handlers.files.webdav;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.utils.docfile.DocumentFileUtils;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.Utils;

import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class WebDavLockHelper extends WebDavHelpersBase {
    public WebDavLockHelper(SHTTPSPlatformUtils platform, UserStore userStore, LockManager locks) {
        super(platform, userStore, locks);
    }

    public Response handleLockRequest(RequestContext context, FilesRequestHandler filesRequestHandler, Request req, DocumentFile userRoot, DocumentFile target, User user) throws Exception {
        long timeoutMs = parseTimeout(req.headers.get("timeout"));
        context.requestBodyReader.readRequestBody(req);
        byte[] body = req.body.asBytes();

        // Option 1: updating the lock - the body is empty, the token is in If
        if (body == null || body.length == 0) {
            String token = extractLockTokenFromIf(req.headers.get("if"));
            LockManager.Lock l = (token != null) ? locks.refresh(token, timeoutMs) : null;
            if (l == null) return simpleStatus(412, "Precondition Failed");
            return lockResponse(l, 200, "OK");
        }

        // Option 2: New lock - disassemble the body <lockinfo>
        LockInfo info = parseLockInfo(body);   // exclusive?, owner xml
        if (info == null) return simpleStatus(400, "Bad Request");

        // Depth for LOCK: 0 or infinity (for collections). Default is infinity.
        String depthHdr = req.headers.get("depth");
        int depth = "0".equals(depthHdr) ? 0 : INFINITY;

        // check: the parent must exist for the lock to make sense
        // (cannot reserve /a/b/c if /a/b does not exist - parallel to PUT/MKCOL 409)
        boolean createdEmpty = false;
        if (target == null || !target.exists()) {
            String parentPath = Utils.getParentPath(req.path);
            if (parentPath == null) {
                return simpleStatus(400, "Bad Request");
            }
            DocumentFile parent = DocumentFileUtils.findChildByPath(userRoot, parentPath);
            if (parent == null || !parent.isDirectory()) return simpleStatus(409, "Conflict");
            createdEmpty = true;
        }

        LockManager.Lock l = locks.acquire(req.path, info.exclusive, depth, info.ownerXml, timeoutMs);
        if (l == null) {
            return simpleStatus(423, "Locked");
        }

        // Success: 200 if the resource existed, 201 if we created a lock-null resource
        return lockResponse(l, createdEmpty ? 201 : 200, createdEmpty ? "Created" : "OK");
    }

    public Response handleUnlockRequest(FilesRequestHandler filesRequestHandler, Request req, DocumentFile userRoot, DocumentFile file, User user) {
        String hdr = req.headers.get("lock-token");   // "<urn:uuid:...>"
        if (hdr == null) return simpleStatus(400, "Bad Request");
        String token = hdr.trim();
        if (token.startsWith("<") && token.endsWith(">")) token = token.substring(1, token.length()-1);

        String path = req.path;
        if (locks.release(path, token)) {
            return simpleStatus(204, "No Content");
        }
        // the token did not match the lock on this path
        return simpleStatus(409, "Conflict");
    }

    private Response lockResponse(LockManager.Lock l, int code, String reason) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(384);
        XmlSerializer s = platform.newXMLSerializer();
        s.setOutput(out, "UTF-8");
        s.startDocument("UTF-8", null);
        s.setPrefix("D", "DAV:");
        s.startTag("DAV:", "prop");
        s.startTag("DAV:", "lockdiscovery");
        writeActiveLock(s, l);
        s.endTag("DAV:", "lockdiscovery");
        s.endTag("DAV:", "prop");
        s.endDocument();

        Response resp = new TextResponse(out.toByteArray(),
                "application/xml; charset=\"utf-8\"", code, reason);
        if (code == 201 || code == 200) {
            // Lock-Token in angle brackets is required when CREATING a lock
            resp.headers.put("Lock-Token", "<" + l.token + ">");
        }
        return resp;
    }

    private void writeActiveLock(XmlSerializer s, LockManager.Lock l) throws IOException {
        s.startTag("DAV:", "activelock");
        s.startTag("DAV:", "locktype");  s.startTag("DAV:", "write"); s.endTag("DAV:", "write"); s.endTag("DAV:", "locktype");
        s.startTag("DAV:", "lockscope"); s.startTag("DAV:", "exclusive"); s.endTag("DAV:", "exclusive"); s.endTag("DAV:", "lockscope");
        s.startTag("DAV:", "depth"); s.text(l.depth == 0 ? "0" : "infinity"); s.endTag("DAV:", "depth");
        // timeout in seconds
        long secs = Math.max(0, (l.expiresAt - System.currentTimeMillis()) / 1000);
        s.startTag("DAV:", "timeout"); s.text("Second-" + secs); s.endTag("DAV:", "timeout");
        // locktoken → href with token
        s.startTag("DAV:", "locktoken");
        s.startTag("DAV:", "href"); s.text(l.token); s.endTag("DAV:", "href");
        s.endTag("DAV:", "locktoken");
        // lockroot → href of resource (important for some clients)
        s.startTag("DAV:", "lockroot");
        s.startTag("DAV:", "href"); s.text(encodeHref(l.path, false)); s.endTag("DAV:", "href");
        s.endTag("DAV:", "lockroot");
        s.endTag("DAV:", "activelock");
    }

    private long parseTimeout(String hdr) {
        long max = 3600_000L;               // 1 hour is our max
        if (hdr == null) return max;
        // can be comma-separated list
        for (String part : hdr.split(",")) {
            part = part.trim();
            if (part.startsWith("Second-")) {
                try {
                    long s = Long.parseLong(part.substring(7).trim());
                    return Math.min(s * 1000L, max);
                } catch (NumberFormatException ignore) {}
            }
        }
        return max;   // Infinite or undefined → our max
    }

    private LockInfo parseLockInfo(byte[] body) {
        // The fact that the body is lockinfo is enough for us. We always treat scope as exclusive write.
        if (body == null || body.length == 0) return null;
        // exclusive = true by default
        return new LockInfo();
    }

    static final class LockInfo {
        boolean exclusive = true;   // default: we support exclusive writing
        String ownerXml = null;     // raw content of <owner>...</owner>, may be null (not used for now)
    }
}
