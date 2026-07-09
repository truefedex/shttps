package com.phlox.simpleserver.handlers.files.webdav;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PropRequest {

    static final Set<String> SUPPORTED = Set.of(
            "resourcetype", "displayname", "getlastmodified",
            "creationdate", "getcontentlength", "getcontenttype", "getetag");

    private enum Mode { ALLPROP, PROPNAME, NAMED }

    private final Mode mode;
    private final Set<String> requested;

    private PropRequest(Mode mode, Set<String> requested) {
        this.mode = mode;
        this.requested = requested;
    }

    static PropRequest allprop()  { return new PropRequest(Mode.ALLPROP,  Set.of()); }
    static PropRequest propname() { return new PropRequest(Mode.PROPNAME, Set.of()); }
    static PropRequest named(Set<String> names) {
        return new PropRequest(Mode.NAMED, new LinkedHashSet<>(names));
    }

    boolean wants(String name) {
        switch (mode) {
            case ALLPROP:
            case PROPNAME:
                return true;
            case NAMED:
                return requested.contains(name);
            default:
                return false;
        }
    }

    void unsupportedAmongRequested(Set<String> supported, List<String> out) {
        if (mode != Mode.NAMED) return;
        for (String n : requested)
            if (!supported.contains(n)) out.add(n);
    }
}
