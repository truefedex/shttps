package com.phlox.simpleserver.channels;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * One parsed mutation of a STATE channel's document - the design document, §8.
 * <pre>
 * C-&gt;S  {"id":1,"command":"set",      "path":"players/bob",         "value":{"score":0}}
 * C-&gt;S  {"id":2,"command":"merge",    "path":"players/alice",       "value":{"score":4}}
 * C-&gt;S  {"id":3,"command":"delete",   "path":"players/bob"}
 * C-&gt;S  {"id":4,"command":"increment","path":"players/alice/score", "by":1}
 * C-&gt;S  {"id":5,"command":"push",     "path":"log",                 "value":{"text":"hi"}}
 * </pre>
 * A command is parsed and validated here and applied here, but this class holds no lock and knows
 * nothing about connections: {@link Channel#mutateState} is what runs {@link #apply} against the
 * live document under the channel's lock, so that this half stays a pure function over a
 * {@code JSONObject} and can be tested as one.
 *
 * <h3>The event</h3>
 * The command <i>is</i> the diff - {@link #toEvent} restates it for the other participants rather
 * than anything computing a delta afterwards:
 * <pre>
 * S-&gt;C  {"type":"patch","op":"merge","path":"players/alice","value":{"score":4},
 *        "by":{"participantId":"p_8f2a","identity":"alice"},"seq":42}
 * </pre>
 * §8 writes the increment amount as {@code by} on the way in, but {@code by} on the way out is
 * §6's sender tag, which every event carries - so the amount travels as the event's {@code value},
 * and each op's event has the same shape. {@code delete} has no value and carries none.
 */
public final class ChannelStateCommand {
    public static final String SET = "set";
    public static final String MERGE = "merge";
    public static final String DELETE = "delete";
    public static final String INCREMENT = "increment";
    public static final String PUSH = "push";

    /** The {@code type} of the event an applied command is broadcast as. */
    public static final String EVENT_TYPE_PATCH = "patch";

    public static final String FIELD_PATH = "path";
    public static final String FIELD_VALUE = "value";
    public static final String FIELD_BY = "by";

    public final @NotNull String op;
    private final @NotNull JsonPointer pointer;
    /** The value to store, or the amount to add for {@code increment}; {@code null} for {@code delete}. */
    private final @Nullable Object value;

    private ChannelStateCommand(@NotNull String op, @NotNull JsonPointer pointer, @Nullable Object value) {
        this.op = op;
        this.pointer = pointer;
        this.value = value;
    }

    public static boolean isStateCommand(@Nullable String command) {
        return SET.equals(command) || MERGE.equals(command) || DELETE.equals(command) ||
                INCREMENT.equals(command) || PUSH.equals(command);
    }

    /**
     * @param envelope a client frame whose {@code command} {@link #isStateCommand} already accepted
     * @throws ChannelCommandException on anything a client can get wrong: an unusable path, a
     * missing or wrongly typed value, an operation that has no meaning at the document root
     */
    public static @NotNull ChannelStateCommand parse(@NotNull JSONObject envelope)
            throws ChannelCommandException {
        String op = envelope.optString("command", "");
        JsonPointer pointer = JsonPointer.parse(
                envelope.isNull(FIELD_PATH) ? null : envelope.optString(FIELD_PATH, null));
        switch (op) {
            case SET: {
                //a set of the root would be a whole-document replace, which the design document
                //drops from the protocol on purpose; merge is the operation that works there
                requireNotRoot(pointer, op);
                return new ChannelStateCommand(op, pointer, requireValue(envelope));
            }
            case MERGE: {
                Object merged = requireValue(envelope);
                if (!(merged instanceof JSONObject)) {
                    throw new ChannelCommandException("merge needs an object as its value");
                }
                return new ChannelStateCommand(op, pointer, merged);
            }
            case DELETE: {
                requireNotRoot(pointer, op);
                return new ChannelStateCommand(op, pointer, null);
            }
            case INCREMENT: {
                requireNotRoot(pointer, op);
                if (!envelope.has(FIELD_BY) || envelope.isNull(FIELD_BY)) {
                    throw new ChannelCommandException("increment needs a 'by' amount");
                }
                Object by = envelope.get(FIELD_BY);
                if (!(by instanceof Number)) {
                    throw new ChannelCommandException("increment needs a number as its 'by' amount");
                }
                return new ChannelStateCommand(op, pointer, by);
            }
            case PUSH: {
                requireNotRoot(pointer, op);
                return new ChannelStateCommand(op, pointer, requireValue(envelope));
            }
            default:
                throw new ChannelCommandException("Unknown command: " + op);
        }
    }

    /** Applies this command to a document. The caller holds the channel's lock. */
    public void apply(@NotNull JSONObject state) throws ChannelCommandException {
        switch (op) {
            case SET:
                JsonPointer.putChild(pointer.resolveParentCreating(state), pointer.lastToken(),
                        requireApplicableValue());
                return;
            case MERGE:
                mergeInto(targetForMerge(state));
                return;
            case DELETE: {
                Object parent = pointer.resolveParent(state);
                if (parent != null) {
                    //deleting what is not there is a no-op rather than an error: the document ends
                    //up in the state that was asked for either way
                    JsonPointer.removeChild(parent, pointer.lastToken());
                }
                return;
            }
            case INCREMENT: {
                Object parent = pointer.resolveParentCreating(state);
                Object current = JsonPointer.child(parent, pointer.lastToken());
                JsonPointer.putChild(parent, pointer.lastToken(),
                        add(current, (Number) requireApplicableValue()));
                return;
            }
            case PUSH: {
                Object parent = pointer.resolveParentCreating(state);
                Object existing = JsonPointer.child(parent, pointer.lastToken());
                JSONArray array;
                if (existing == null) {
                    array = new JSONArray();
                    JsonPointer.putChild(parent, pointer.lastToken(), array);
                } else if (existing instanceof JSONArray) {
                    array = (JSONArray) existing;
                } else {
                    throw new ChannelCommandException("push needs an array at '" + pointer.raw() +
                            "', and something else is there");
                }
                array.put(requireApplicableValue());
                return;
            }
            default:
                throw new ChannelCommandException("Unknown command: " + op);
        }
    }

    /** The §8 patch event, for everyone but the sender (who gets a plain ack instead). */
    public @NotNull JSONObject toEvent(long seq, @NotNull Participant by) {
        JSONObject event = new JSONObject();
        event.put("type", EVENT_TYPE_PATCH);
        event.put("op", op);
        event.put(FIELD_PATH, pointer.raw());
        if (value != null) {
            event.put(FIELD_VALUE, value);
        }
        event.put(FIELD_BY, by.toSenderJson());
        event.put("seq", seq);
        return event;
    }

    /** The object {@code merge} writes into, created as an empty one if the path is unused. */
    private @NotNull JSONObject targetForMerge(@NotNull JSONObject state) throws ChannelCommandException {
        if (pointer.isRoot()) {
            return state;
        }
        Object parent = pointer.resolveParentCreating(state);
        Object target = JsonPointer.child(parent, pointer.lastToken());
        if (target instanceof JSONObject) {
            return (JSONObject) target;
        }
        if (target != null) {
            throw new ChannelCommandException("merge needs an object at '" + pointer.raw() +
                    "', and something else is there");
        }
        JSONObject created = new JSONObject();
        JsonPointer.putChild(parent, pointer.lastToken(), created);
        return created;
    }

    private void mergeInto(@NotNull JSONObject target) {
        JSONObject merged = (JSONObject) value;
        for (Iterator<String> keys = merged.keys(); keys.hasNext(); ) {
            //shallow, like Object.assign: a nested object in the value replaces the one under that
            //key rather than being merged into it
            String key = keys.next();
            target.put(key, merged.get(key));
        }
    }

    private static @NotNull Object add(@Nullable Object current, @NotNull Number amount)
            throws ChannelCommandException {
        if (current == null) {
            //a counter nobody has touched yet starts at zero rather than being an error, so a
            //client does not have to create every score before it can raise one
            current = Integer.valueOf(0);
        }
        if (!(current instanceof Number)) {
            throw new ChannelCommandException("increment needs a number at this path");
        }
        Number stored = (Number) current;
        if (isIntegral(stored) && isIntegral(amount)) {
            //whole numbers stay whole: 3 + 1 has to serialize as 4, not as 4.0
            return Long.valueOf(stored.longValue() + amount.longValue());
        }
        return Double.valueOf(stored.doubleValue() + amount.doubleValue());
    }

    private static boolean isIntegral(@NotNull Number number) {
        return number instanceof Integer || number instanceof Long || number instanceof Short ||
                number instanceof Byte;
    }

    private static @NotNull Object requireValue(@NotNull JSONObject envelope)
            throws ChannelCommandException {
        if (!envelope.has(FIELD_VALUE) || envelope.isNull(FIELD_VALUE)) {
            throw new ChannelCommandException("Missing value");
        }
        return envelope.get(FIELD_VALUE);
    }

    private @NotNull Object requireApplicableValue() throws ChannelCommandException {
        if (value == null) {
            throw new ChannelCommandException("Missing value");
        }
        return value;
    }

    private static void requireNotRoot(@NotNull JsonPointer pointer, @NotNull String op)
            throws ChannelCommandException {
        if (pointer.isRoot()) {
            throw new ChannelCommandException(op + " needs a path inside the document");
        }
    }
}
