package com.phlox.simpleserver.channels;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The frames a {@link Channel} has to build for itself.
 * <p>
 * Most of the protocol is assembled in {@code ChannelWebSocketHandler}, where it belongs. These few
 * are the exception: the snapshot a newcomer is greeted with, the presence events, and the
 * acknowledgement that has to sit in front of the sender's own echoed event. All of them are queued
 * <i>while the channel's lock is held</i> - that is what keeps them in {@code seq} order - so they
 * have to be buildable without reaching back into the handler package.
 */
public final class ChannelEvents {
    public static final String EVENT_TYPE_STATE = "state";
    public static final String EVENT_TYPE_PARTICIPANTS = "participants";
    public static final String EVENT_TYPE_JOIN = "join";
    public static final String EVENT_TYPE_LEAVE = "leave";

    private ChannelEvents() {
    }

    /** The document a STATE participant starts from, §8. */
    static @NotNull String stateSnapshot(long seq, @NotNull String stateJson) {
        JSONObject frame = new JSONObject();
        frame.put("type", EVENT_TYPE_STATE);
        frame.put("seq", seq);
        frame.put("state", new JSONObject(stateJson));
        return frame.toString();
    }

    /** The list a connection holding {@code LIST_PARTICIPANTS} is greeted with, §9. */
    static @NotNull String participantsSnapshot(long seq, @NotNull String participantsJson) {
        JSONObject frame = new JSONObject();
        frame.put("type", EVENT_TYPE_PARTICIPANTS);
        frame.put("seq", seq);
        //the same field name GET /api/channels/{id}/participants answers with; that it also matches
        //the event's type is a coincidence, so it is not the same constant
        frame.put("participants", new JSONArray(participantsJson));
        return frame.toString();
    }

    /**
     * A {@code join} or {@code leave}, §9.
     * <p>
     * The participant is described with {@link Participant#toJson()} - identity <i>absent</i> when
     * there is none - and not with the {@link Participant#toSenderJson()} of a message event, where
     * it is present and null: a client merges these events into the list it was greeted with, and
     * the entries have to be the same shape as the entries in that list.
     * <p>
     * The {@code seq} carried is the channel's current one, not a new one. It exists (§6) so a
     * client can tell it missed a message or a patch; presence goes only to the participants
     * allowed to see it, so numbering it would leave a hole in everybody else's sequence and raise
     * exactly the false alarm {@code seq} is there to prevent. Two presence events can therefore
     * carry the same number, and they cannot be told apart by it.
     */
    static @NotNull String presence(@NotNull String type, @NotNull Participant participant, long seq) {
        JSONObject frame = new JSONObject();
        frame.put("type", type);
        frame.put("participant", participant.toJson());
        frame.put("seq", seq);
        return frame.toString();
    }

    /**
     * The plain acknowledgement a sender gets back, §6. It rides the sender's own queue rather than
     * being written straight to the socket, so that a connection using {@code ?echoToSelf=true}
     * always sees its acknowledgement ahead of its own echoed event instead of by luck.
     */
    static @NotNull String ack(@Nullable Object requestId, long seq) {
        JSONObject frame = new JSONObject();
        frame.put("id", requestId == null ? JSONObject.NULL : requestId);
        frame.put("ok", true);
        frame.put("seq", seq);
        return frame.toString();
    }
}
