package com.phlox.simpleserver.channels;

import org.jetbrains.annotations.NotNull;

/**
 * A channel command the server understood the shape of but cannot carry out: an unusable path, a
 * missing field, a value of the wrong kind. Always answered as an ordinary
 * {@code {"ok":false,"error":{"kind":"BAD_REQUEST",...}}} frame on an otherwise healthy connection -
 * a client sending nonsense is not a reason to close a socket other participants are sharing.
 * <p>
 * The message is written for whoever is holding the client and lands verbatim in that frame, so it
 * names the path segment or field at fault rather than describing the failure in the abstract.
 */
public class ChannelCommandException extends Exception {
    public ChannelCommandException(@NotNull String message) {
        super(message);
    }
}
