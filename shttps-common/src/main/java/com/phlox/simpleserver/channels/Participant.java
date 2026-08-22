package com.phlox.simpleserver.channels;

import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.auth.User;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * One connection inside one channel. Identity is deliberately optional: with authorization off
 * nobody has one, and a guest is not a person the other participants can be pointed at either, so
 * both connect as anonymous participants that only carry a {@link #participantId}.
 */
public class Participant {
    public static final String FIELD_PARTICIPANT_ID = "participantId";
    public static final String FIELD_IDENTITY = "identity";
    public static final String FIELD_JOINED_AT = "joinedAt";
    public static final String FIELD_LABEL = "label";

    private static final SecureRandom RANDOM = new SecureRandom();

    public final @NotNull String participantId;
    public final @Nullable String identity;
    public final @NotNull WebSocketSession session;
    public final long joinedAt;
    /**
     * Whether this connection holds {@link User.ChannelRights#LIST_PARTICIPANTS}, taken once at
     * connect the same way the POST right is - asking again per event would mean a database lookup
     * for every join and leave of a user that has a role. It decides two things: whether the
     * connection is greeted with the participant list (§9), and whether presence events reach it at
     * all. It lives here rather than in the handler's session attributes because {@link Channel}
     * itself has to pick the recipients of a presence event.
     */
    public final boolean mayListParticipants;
    /**
     * Everything queued for this connection but not yet written. Frames go in while the channel's
     * lock is held and go out once it is gone, which is what makes the order they arrive in match
     * the order {@code seq} numbered them - see {@link ParticipantOutbox}.
     */
    public final @NotNull ParticipantOutbox outbox;

    public Participant(@NotNull String participantId, @Nullable String identity,
                       @NotNull WebSocketSession session, long joinedAt,
                       boolean mayListParticipants, int maxQueuedBytes) {
        this.participantId = participantId;
        this.identity = identity;
        this.session = session;
        this.joinedAt = joinedAt;
        this.mayListParticipants = mayListParticipants;
        this.outbox = new ParticipantOutbox(session, maxQueuedBytes);
    }

    /** The identity to publish for a connection, or {@code null} when it is anonymous or a guest. */
    public static @Nullable String identityOf(@Nullable User user) {
        return (user == null || user.isGuest()) ? null : user.identity;
    }

    public static @NotNull String generateParticipantId() {
        return String.format("p_%08x", RANDOM.nextInt());
    }

    /** An entry of the participant list, see the design document, §9. */
    public @NotNull JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put(FIELD_PARTICIPANT_ID, participantId);
        if (identity != null) {
            object.put(FIELD_IDENTITY, identity);
        }
        object.put(FIELD_JOINED_AT, joinedAt);
        return object;
    }

    /** The sender tag carried by events, see §6. */
    public @NotNull JSONObject toSenderJson() {
        JSONObject object = new JSONObject();
        object.put(FIELD_PARTICIPANT_ID, participantId);
        object.put(FIELD_IDENTITY, identity != null ? identity : JSONObject.NULL);
        return object;
    }

    /**
     * The sender tag of a one-shot HTTP publish, §10. There is no participant behind it - whoever
     * posted did not join the channel - so {@link #FIELD_PARTICIPANT_ID} is present and null, the
     * same way an anonymous connection's identity already is. The label is whatever the publisher
     * called itself and is nothing the server vouches for, so it is simply absent when not given.
     *
     * @param identity the publisher's {@code User.identity}, or null when anonymous or a guest
     * @param label    the self-reported {@code senderLabel}, if any
     */
    public static @NotNull JSONObject httpSenderJson(@Nullable String identity, @Nullable String label) {
        JSONObject object = new JSONObject();
        object.put(FIELD_PARTICIPANT_ID, JSONObject.NULL);
        object.put(FIELD_IDENTITY, identity != null ? identity : JSONObject.NULL);
        if (label != null) {
            object.put(FIELD_LABEL, label);
        }
        return object;
    }
}
