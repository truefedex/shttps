package com.phlox.simpleserver.channels;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The subset of RFC 6901 the channel state commands need: parsing a {@code path} into segments and
 * walking a {@code JSONObject} document along them. Deliberately hand-written - the whole of it is
 * an escape rule and a loop, and neither {@code shttps-common} nor the platforms it runs on gain
 * anything from a dependency for that.
 *
 * <h3>The subset</h3>
 * <ul>
 *     <li>Segments are separated by {@code /}. A single leading {@code /} is optional: the design
 *     document writes paths as {@code players/alice}, a strict RFC 6901 pointer as
 *     {@code /players/alice}, and both are accepted as the same path.</li>
 *     <li>An empty or absent path is the document root; so, because of the rule above, is
 *     {@code "/"}.</li>
 *     <li>{@code ~1} is a literal {@code /} and {@code ~0} a literal {@code ~}. A {@code ~}
 *     followed by anything else is refused rather than passed through, because it is a typo
 *     every time.</li>
 * </ul>
 *
 * <h3>What walking creates</h3>
 * {@link #resolveParentCreating} fills in missing intermediate levels as {@code JSONObject}s - that
 * is what lets {@code set players/bob/score} work on an empty document. A segment is read as an
 * array index only when the level it addresses <b>already is</b> a {@code JSONArray}; nothing here
 * ever creates one, since {@code push} is the only command that may (and only at the leaf, where it
 * does it itself). A scalar standing where a level has to be is an error rather than something to
 * overwrite: a path that runs through a value is a client's mistake, not an instruction to discard
 * that value.
 */
public final class JsonPointer {
    private static final String[] NO_TOKENS = new String[0];

    private final @NotNull String raw;
    private final @NotNull String[] tokens;

    private JsonPointer(@NotNull String raw, @NotNull String[] tokens) {
        this.raw = raw;
        this.tokens = tokens;
    }

    /**
     * @param path the {@code path} field of a command, as the client wrote it
     * @throws ChannelCommandException on a broken escape sequence
     */
    public static @NotNull JsonPointer parse(@Nullable String path) throws ChannelCommandException {
        String raw = path == null ? "" : path;
        String body = raw.startsWith("/") ? raw.substring(1) : raw;
        if (body.isEmpty()) {
            return new JsonPointer(raw, NO_TOKENS);
        }
        String[] parts = body.split("/", -1);
        String[] tokens = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            tokens[i] = unescape(parts[i]);
        }
        return new JsonPointer(raw, tokens);
    }

    /** The path exactly as the client wrote it - what the patch event echoes back. */
    public @NotNull String raw() {
        return raw;
    }

    public boolean isRoot() {
        return tokens.length == 0;
    }

    public int depth() {
        return tokens.length;
    }

    /** The last segment: the key or index the command actually acts on. */
    public @NotNull String lastToken() {
        if (isRoot()) {
            throw new IllegalStateException("The document root has no last segment");
        }
        return tokens[tokens.length - 1];
    }

    /**
     * The container holding {@link #lastToken()}, creating the levels above it as needed.
     *
     * @return a {@code JSONObject} or a {@code JSONArray}
     * @throws ChannelCommandException for the root path, or when a level of the path is a value
     */
    public @NotNull Object resolveParentCreating(@NotNull JSONObject root) throws ChannelCommandException {
        if (isRoot()) {
            throw new ChannelCommandException("The document root has no parent element");
        }
        Object current = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            current = childCreating(current, tokens[i]);
        }
        return current;
    }

    /**
     * The container holding {@link #lastToken()} if it is already there, creating nothing.
     *
     * @return the container, or {@code null} when any level above the leaf is missing - which is
     * what lets {@code delete} of an absent path stay the no-op it is meant to be instead of
     * leaving a trail of empty objects behind it
     */
    public @Nullable Object resolveParent(@NotNull JSONObject root) {
        if (isRoot()) {
            return null;
        }
        Object current = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            current = child(current, tokens[i]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** The value at this path, or {@code null} when it is not there. The root resolves to {@code root}. */
    public @Nullable Object resolve(@NotNull JSONObject root) {
        Object current = root;
        for (String token : tokens) {
            current = child(current, token);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** One step down, without creating anything; {@code null} when there is nothing there. */
    public static @Nullable Object child(@NotNull Object container, @NotNull String token) {
        Object child = null;
        if (container instanceof JSONObject) {
            child = ((JSONObject) container).opt(token);
        } else if (container instanceof JSONArray) {
            JSONArray array = (JSONArray) container;
            int index = parseIndex(token);
            if (index >= 0 && index < array.length()) {
                child = array.opt(index);
            }
        }
        return JSONObject.NULL.equals(child) ? null : child;
    }

    /**
     * Writes into a container returned by one of the resolve methods. An index into an array has to
     * address an element that exists: growing an array is {@code push}'s job and only at its end.
     */
    public static void putChild(@NotNull Object container, @NotNull String token, @NotNull Object value)
            throws ChannelCommandException {
        if (container instanceof JSONObject) {
            ((JSONObject) container).put(token, value);
            return;
        }
        JSONArray array = requireArray(container, token);
        int index = parseIndex(token);
        if (index < 0 || index >= array.length()) {
            throw new ChannelCommandException("No element " + token + " in the array at this path");
        }
        array.put(index, value);
    }

    /** Removes a key from a container returned by one of the resolve methods; absent is fine. */
    public static void removeChild(@NotNull Object container, @NotNull String token)
            throws ChannelCommandException {
        if (container instanceof JSONObject) {
            ((JSONObject) container).remove(token);
            return;
        }
        //removing by index would shift every element after it, which the design document rules out
        requireArray(container, token);
        throw new ChannelCommandException("Elements cannot be removed from an array by index");
    }

    private static @NotNull Object childCreating(@NotNull Object container, @NotNull String token)
            throws ChannelCommandException {
        Object child = child(container, token);
        if (child instanceof JSONObject || child instanceof JSONArray) {
            return child;
        }
        if (child != null) {
            throw new ChannelCommandException(
                    "Path segment '" + token + "' addresses a value, not an object");
        }
        JSONObject created = new JSONObject();
        if (container instanceof JSONObject) {
            ((JSONObject) container).put(token, created);
            return created;
        }
        JSONArray array = requireArray(container, token);
        int index = parseIndex(token);
        if (index < 0 || index >= array.length()) {
            //a path pointing past the end of an array: growing one is push's job, at its end only
            throw new ChannelCommandException("No element " + token + " in the array at this path");
        }
        array.put(index, created);
        return created;
    }

    private static @NotNull JSONArray requireArray(@NotNull Object container, @NotNull String token)
            throws ChannelCommandException {
        if (!(container instanceof JSONArray)) {
            throw new ChannelCommandException(
                    "Path segment '" + token + "' addresses a value, not an object");
        }
        return (JSONArray) container;
    }

    /** @return the index, or -1 when the segment is not one */
    private static int parseIndex(@NotNull String token) {
        if (token.isEmpty() || token.length() > 9) {
            return -1;
        }
        int index = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            index = index * 10 + (c - '0');
        }
        //"01" is not the same pointer as "1" in RFC 6901, and accepting it would let two different
        //paths address one element
        if (token.length() > 1 && token.charAt(0) == '0') {
            return -1;
        }
        return index;
    }

    private static @NotNull String unescape(@NotNull String token) throws ChannelCommandException {
        if (token.indexOf('~') < 0) {
            return token;
        }
        StringBuilder unescaped = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '~') {
                unescaped.append(c);
                continue;
            }
            if (i + 1 == token.length()) {
                throw new ChannelCommandException("Unfinished escape sequence in path segment: " + token);
            }
            char escaped = token.charAt(++i);
            if (escaped == '0') {
                unescaped.append('~');
            } else if (escaped == '1') {
                unescaped.append('/');
            } else {
                throw new ChannelCommandException(
                        "Unknown escape sequence ~" + escaped + " in path segment: " + token);
            }
        }
        return unescaped.toString();
    }
}
