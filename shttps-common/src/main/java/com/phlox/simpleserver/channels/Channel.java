package com.phlox.simpleserver.channels;

import com.phlox.server.websocket.WebSocketSession;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A live channel: its settings plus the set of connections currently in it.
 * <p>
 * Two rules keep this class usable from many connection threads at once:
 * <ul>
 *     <li>admission, the {@code state} document and the {@code seq} counter are done under
 *     {@code synchronized(this)} - check-then-add against {@link #maxParticipants} on a
 *     {@link CopyOnWriteArraySet} would otherwise let two simultaneous handshakes both take the
 *     last slot, and two participants incrementing one counter would lose an increment;</li>
 *     <li>nothing is ever <b>sent</b> while that lock is held - but everything is
 *     <b>queued</b> while it is. A caller takes the lock, mutates, numbers the event and drops it
 *     into each recipient's {@link ParticipantOutbox}, releases, and only then lets those outboxes
 *     write. Queuing under the lock is what makes the order frames arrive in match the order
 *     {@code seq} gave them; writing outside it is what stops one participant that has stopped
 *     reading from stalling everybody else's commands. Doing both after the lock, as this class
 *     once did, gets neither: two senders then race to write into each recipient's socket and the
 *     numbers arrive complete but shuffled.</li>
 * </ul>
 */
public class Channel {
    public enum Mode {
        /** Messages are relayed between participants, the server keeps nothing. */
        ECHO,
        /** A shared JSON document mutated by {@link ChannelStateCommand}s. */
        STATE
    }

    public final @NotNull String id;
    public final @NotNull Mode mode;
    /** Defined in configuration: not removable through the API and not collected when empty. */
    public final boolean persistent;
    public final boolean deletable;
    /** Whether joins and leaves are announced to the participants allowed to see them (§9). */
    public final boolean notifyPresence;
    public final boolean guestsAllowed;
    public final int maxParticipants;
    /**
     * This channel's own message rate limit, or {@code null} to follow the server-wide setting.
     * Kept unresolved, unlike {@link #maxParticipants}: the server setting is read per connection,
     * so editing it takes effect for the next client rather than at the next restart, and resolving
     * it here would freeze whatever it happened to be when the channel was built.
     * See {@link #messageRateLimit(int)}.
     */
    public final @Nullable Integer messageRateLimitPerSecond;
    /**
     * Whether raw binary frames are relayed between the participants of this channel, §7.1. Off
     * unless the channel asked for it, and orthogonal to {@link #mode} - see
     * {@link ChannelDefinition#binaryAllowed}.
     */
    public final boolean binaryAllowed;
    private final @Nullable String passwordHash;

    private final Set<Participant> participants = new CopyOnWriteArraySet<>();
    /** STATE only; read and written under {@code synchronized(this)}, empty for an ECHO channel. */
    private final @NotNull JSONObject state;
    private long seq = 0;
    private volatile long lastActivityAt = System.currentTimeMillis();
    /**
     * Set once the channel has been taken out of {@link ChannelManager}, under the same lock that
     * admits participants: a connection whose HTTP checks passed just before a delete must not end
     * up admitted to a channel nobody can reach any more.
     */
    private boolean removed = false;

    public Channel(@NotNull String id, @NotNull Mode mode, boolean persistent, boolean deletable,
                   boolean notifyPresence, boolean guestsAllowed,
                   int maxParticipants, @Nullable String passwordHash,
                   @Nullable JSONObject initialState,
                   @Nullable Integer messageRateLimitPerSecond, boolean binaryAllowed) {
        //a copy, not the definition's own object: the initial state is part of a stored setting and
        //has no business changing because somebody sent a command
        this.state = initialState == null ? new JSONObject() : new JSONObject(initialState.toString());
        this.id = id;
        this.mode = mode;
        this.persistent = persistent;
        this.deletable = deletable;
        this.notifyPresence = notifyPresence;
        this.guestsAllowed = guestsAllowed;
        this.maxParticipants = maxParticipants;
        this.passwordHash = passwordHash;
        this.messageRateLimitPerSecond = messageRateLimitPerSecond;
        this.binaryAllowed = binaryAllowed;
    }

    public static @NotNull Channel fromDefinition(@NotNull ChannelDefinition definition,
                                                  int defaultMaxParticipants) {
        int maxParticipants = definition.maxParticipants != null && definition.maxParticipants > 0 ?
                definition.maxParticipants : defaultMaxParticipants;
        return new Channel(definition.id, definition.mode, true, definition.deletable,
                definition.notifyPresence, definition.guestsAllowed,
                maxParticipants, definition.passwordHash, definition.initialState,
                definition.messageRateLimitPerSecond, definition.binaryAllowed);
    }

    /**
     * How many messages a second one connection to this channel may send.
     *
     * @param serverDefault {@code SHTTPSConfig.getChannelMessageRateLimitPerSecond()}, used when
     *                      this channel does not override it
     * @return the limit to build a {@link MessageRateLimiter} with; zero or less means no limit.
     * The test is against {@code null} alone, deliberately: a channel that says zero is asking not
     * to be throttled, and reading that as "unset" would throttle it with the server default - the
     * one answer it explicitly did not want.
     */
    public int messageRateLimit(int serverDefault) {
        return messageRateLimitPerSecond != null ? messageRateLimitPerSecond : serverDefault;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * @param plaintext the value of {@code ?password=} / {@code X-Channel-Password}, hashed here
     * @return true when the channel has no password, or the given one matches it
     */
    public boolean checkPassword(@Nullable String plaintext) {
        if (passwordHash == null) return true;
        if (plaintext == null || plaintext.isEmpty()) return false;
        return passwordHash.equals(ChannelDefinition.hashChannelPassword(plaintext));
    }

    public int participantsCount() {
        return participants.size();
    }

    public boolean isFull() {
        return participants.size() >= maxParticipants;
    }

    /** A stable snapshot; safe to iterate while connections come and go. */
    public @NotNull Collection<Participant> participants() {
        return new ArrayList<>(participants);
    }

    /**
     * Takes a slot if there is one, together with the snapshot the new participant has to be sent.
     * <p>
     * The two happen under one lock on purpose: every state change numbered after this call is
     * numbered above {@link Admission#seq} <i>and</i> goes out to a participant list that already
     * contains this connection, so a client that holds patches until the snapshot arrives and then
     * applies those above its {@code seq} misses nothing and applies nothing twice.
     *
     * The presence half of §9 is decided here for the same reason. Computed outside this lock, two
     * simultaneous joins can hand a client a {@code join} for somebody its own snapshot already
     * listed; inside it the split is exact - the recipients are precisely those present
     * <i>before</i> this participant, and everybody else is in the snapshot it gets instead.
     *
     * @param mayListParticipants whether the connection holds {@code LIST_PARTICIPANTS}: without it
     *                            no list is built for it, and it is left out of presence entirely
     * @return the admission, or {@code null} when the channel is full or already deleted - which of
     * the two it was is {@link #isRemoved()}
     */
    public synchronized @Nullable Admission admit(@NotNull WebSocketSession session,
                                                  @Nullable String identity,
                                                  boolean mayListParticipants,
                                                  int maxQueuedBytes) {
        if (removed || participants.size() >= maxParticipants) {
            return null;
        }
        //taken before the new participant is added: nobody is told about their own arrival
        List<Participant> presenceRecipients = presenceRecipients();
        Participant participant = new Participant(Participant.generateParticipantId(), identity,
                session, System.currentTimeMillis(), mayListParticipants, maxQueuedBytes);
        participants.add(participant);
        lastActivityAt = participant.joinedAt;

        //queued here rather than by the caller, because here is inside the lock: an event numbered
        //after this call queues behind the snapshot in this participant's own outbox, so it can no
        //longer reach the wire ahead of it. Sent afterwards, as it used to be, a patch from another
        //thread could arrive before the document it patches
        if (mode == Mode.STATE) {
            participant.outbox.offerText(ChannelEvents.stateSnapshot(seq, state.toString()));
        }
        if (mayListParticipants) {
            //built after the participant was added, so the list it is greeted with contains itself
            //- the same list GET /api/channels/{id}/participants would answer with
            participant.outbox.offerText(ChannelEvents.participantsSnapshot(seq, participantsJson()));
        }
        ParticipantOutbox.offerTextTo(presenceRecipients,
                ChannelEvents.presence(ChannelEvents.EVENT_TYPE_JOIN, participant, seq));

        List<Participant> toFlush = new ArrayList<>(presenceRecipients.size() + 1);
        toFlush.add(participant);
        toFlush.addAll(presenceRecipients);
        return new Admission(participant, seq, toFlush);
    }

    /**
     * What {@link #admit} decided: who the connection is, and whose outboxes now have something in
     * them. The frames themselves are already queued - all the caller has to do is let them out.
     */
    public static final class Admission {
        public final @NotNull Participant participant;
        /** The number of the last event contained in the snapshot the newcomer was queued. */
        public final long seq;
        /** The newcomer plus whoever was told it arrived; flush these once the lock is gone. */
        public final @NotNull List<Participant> toFlush;

        Admission(@NotNull Participant participant, long seq, @NotNull List<Participant> toFlush) {
            this.participant = participant;
            this.seq = seq;
            this.toFlush = toFlush;
        }
    }

    /**
     * Takes a participant out and says who has to be told, under the lock that admits them - the
     * mirror image of {@link #admit}. Outside it, a client can be handed a snapshot listing somebody
     * whose {@code leave} went out just before that client was added, and keep a participant that
     * has long since gone.
     *
     * @return the participants the {@code leave} event was queued for, to be flushed once the lock
     * is gone; empty unless {@link #notifyPresence}
     */
    public synchronized @NotNull List<Participant> removeParticipant(
            @NotNull Participant participant) {
        participants.remove(participant);
        //the idle clock starts when the last participant leaves, which is what
        //ChannelManager.collectIdleChannels() measures against
        lastActivityAt = System.currentTimeMillis();
        List<Participant> recipients = presenceRecipients();
        ParticipantOutbox.offerTextTo(recipients,
                ChannelEvents.presence(ChannelEvents.EVENT_TYPE_LEAVE, participant, seq));
        return recipients;
    }

    /**
     * The participants that presence events go to: those currently in the channel that may see the
     * list at all. Empty when the channel does not announce presence.
     * <p>
     * There is no participant to leave out: a join is gathered before the newcomer is added and a
     * leave after the departing one is removed, so in both cases the set is already exactly right.
     * Call under the lock.
     */
    private @NotNull List<Participant> presenceRecipients() {
        if (!notifyPresence) return Collections.emptyList();
        List<Participant> recipients = new ArrayList<>(participants.size());
        for (Participant participant : participants) {
            if (participant.mayListParticipants) {
                recipients.add(participant);
            }
        }
        return recipients;
    }

    /** The participant list of §9, as it goes into a frame. Call under the lock. */
    private @NotNull String participantsJson() {
        JSONArray list = new JSONArray();
        for (Participant participant : participants) {
            list.put(participant.toJson());
        }
        return list.toString();
    }

    public synchronized boolean isRemoved() {
        return removed;
    }

    /**
     * Marks the channel deleted so nothing else can join it. Only {@link ChannelManager} calls this,
     * as one half of taking the channel out of its map; the sessions are closed by the caller
     * afterwards, outside the lock.
     */
    synchronized void markRemoved() {
        removed = true;
    }

    /**
     * The same, but only for a channel that is still empty - the idle sweep must not collect one
     * that somebody joined between the emptiness check and the removal.
     *
     * @return whether the channel was marked, i.e. whether it may be unmapped
     */
    synchronized boolean markRemovedIfEmpty() {
        if (!participants.isEmpty()) return false;
        removed = true;
        return true;
    }

    /** The next per-channel event number, see §6. */
    public synchronized long nextSeq() {
        lastActivityAt = System.currentTimeMillis();
        return ++seq;
    }

    public synchronized long currentSeq() {
        return seq;
    }

    /**
     * Marks the channel as used without numbering anything - what a binary relay does instead of
     * {@link #nextSeq()}, since a binary message stays outside the {@code seq} sequence (§7.1).
     * <p>
     * Not what keeps a busy channel alive: {@link ChannelManager#collectIdleChannels} skips any
     * channel that still has participants, so an occupied one is safe whatever this says. What it
     * keeps honest is the {@code lastActivityAt} the API reports - without it a channel carrying
     * nothing but blobs would be shown as untouched since the moment somebody joined it.
     * <p>
     * Unsynchronized on purpose: {@link #lastActivityAt} is volatile and this neither reads nor
     * writes anything else, so there is nothing here for the channel's lock to protect.
     */
    public void touch() {
        lastActivityAt = System.currentTimeMillis();
    }

    /** Builds the text of an event once its number is known. Runs under the channel's lock. */
    public interface EventBuilder {
        @NotNull String build(long seq);
    }

    /**
     * Numbers an event, builds it, and queues it for everybody who should get it - all under this
     * channel's lock, which is precisely what makes every recipient's queue agree with {@code seq}.
     * <b>Writes nothing</b>: the caller flushes {@link Dispatch#toFlush} once the lock is gone, so
     * that a participant which has stopped reading cannot stall anybody else's command.
     * <p>
     * The event is built here, inside the lock, and not only for ordering: {@code set} and
     * {@code push} store the client's own object into {@link #state}, and that is the object the
     * event serializes, so the two must not be separated by a window in which another thread can
     * change it.
     *
     * @param sender     the participant that caused this, or {@code null} for a one-shot HTTP
     *                   publish, which belongs to nobody in the channel
     * @param echoToSelf whether the sender asked to receive its own events as well (§7)
     * @param requestId  the {@code id} to acknowledge to the sender, or {@code null} for something
     *                   nobody asked for. The acknowledgement is queued ahead of the event, so a
     *                   sender using {@code echoToSelf} sees them in that order every time
     */
    public synchronized @NotNull Dispatch publish(@Nullable Participant sender, boolean echoToSelf,
                                                  @Nullable Object requestId,
                                                  @NotNull EventBuilder builder) {
        long seq = nextSeq();//reentrant on this same monitor
        List<Participant> toFlush = new ArrayList<>(participants.size() + 1);
        if (sender != null && requestId != null) {
            sender.outbox.offerText(ChannelEvents.ack(requestId, seq));
            toFlush.add(sender);
        }
        String event = builder.build(seq);
        for (Participant participant : participants) {
            if (participant == sender && !echoToSelf) continue;
            participant.outbox.offerText(event);
            //the sender can land here twice, having been added for its acknowledgement. Flushing an
            //outbox twice is a no-op, which is cheaper than keeping a set to avoid it
            toFlush.add(participant);
        }
        return new Dispatch(seq, toFlush);
    }

    /**
     * A binary relay, §7.1: the same queueing, without a number and without an acknowledgement.
     * It goes through the outbox all the same, so that a blob keeps its place among the text
     * messages around it - the documented way to say anything about a blob is to announce it in an
     * ordinary message first, which only works if that order survives.
     */
    public synchronized @NotNull List<Participant> relayBinary(@NotNull Participant sender,
                                                               boolean echoToSelf,
                                                               @NotNull byte[] message) {
        //touch, not nextSeq: this message takes no number, but it is still traffic
        touch();
        List<Participant> toFlush = new ArrayList<>(participants.size());
        for (Participant participant : participants) {
            if (participant == sender && !echoToSelf) continue;
            participant.outbox.offerBinary(message);
            toFlush.add(participant);
        }
        return toFlush;
    }

    /**
     * Applies a state command, then publishes it as its own description of what changed - the
     * command protocol never computes a diff.
     */
    public synchronized @NotNull Dispatch mutateState(@NotNull ChannelStateCommand command,
                                                      @NotNull Participant author,
                                                      boolean echoToSelf,
                                                      @Nullable Object requestId)
            throws ChannelCommandException {
        //before anything is numbered: a command the server cannot carry out has to leave the
        //channel's sequence untouched
        command.apply(state);
        return publish(author, echoToSelf, requestId, seq -> command.toEvent(seq, author).toString());
    }

    /** What {@link #publish} numbered, and whose outboxes it filled. */
    public static final class Dispatch {
        public final long seq;
        public final @NotNull List<Participant> toFlush;

        Dispatch(long seq, @NotNull List<Participant> toFlush) {
            this.seq = seq;
            this.toFlush = toFlush;
        }
    }

    public long getLastActivityAt() {
        return lastActivityAt;
    }

    public void closeAllSessions(int code, @NotNull String reason) {
        for (Participant participant : participants) {
            participant.session.close(code, reason);
        }
    }

    /** Channel metadata as the API exposes it. Never includes the password hash. */
    public @NotNull JSONObject toJson() {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("mode", mode.name().toLowerCase());
        object.put("persistent", persistent);
        object.put("deletable", deletable);
        object.put("guestsAllowed", guestsAllowed);
        object.put("passwordProtected", hasPassword());
        object.put("maxParticipants", maxParticipants);
        //only when this channel overrides it. maxParticipants above is reported already resolved,
        //because the channel stores it that way; this one cannot be, since the number it would fall
        //back to lives in the configuration and is read per connection rather than held here
        if (messageRateLimitPerSecond != null) {
            object.put(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT, messageRateLimitPerSecond.intValue());
        }
        object.put(ChannelDefinition.FIELD_BINARY_ALLOWED, binaryAllowed);
        object.put("participants", participantsCount());
        object.put("seq", currentSeq());
        object.put("lastActivityAt", lastActivityAt);
        return object;
    }
}
