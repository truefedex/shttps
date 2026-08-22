package com.phlox.simpleserver.handlers.channels;

import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.websocket.WebSocketRequestHandler;
import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.channels.Channel;
import com.phlox.simpleserver.channels.ChannelCommandException;
import com.phlox.simpleserver.channels.ChannelEvents;
import com.phlox.simpleserver.channels.ChannelManager;
import com.phlox.simpleserver.channels.ChannelStateCommand;
import com.phlox.simpleserver.channels.MessageRateLimiter;
import com.phlox.simpleserver.channels.Participant;
import com.phlox.simpleserver.channels.ParticipantOutbox;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/**
 * One WebSocket endpoint shared by every channel; which channel a connection belongs to is decided
 * by {@link ChannelsPathRequestHandler}, which parses the path and puts the id into
 * {@code context.data} before delegating here.
 *
 * <h3>The protocol (ECHO mode)</h3>
 * <pre>
 * C-&gt;S  {"id":1,"command":"send","payload":{"text":"hi"}}
 * S-&gt;C  {"id":1,"ok":true,"seq":41}                                      // to the sender
 * S-&gt;C  {"type":"message","from":{"participantId":"p_8f2a","identity":"alice"},
 *        "payload":{"text":"hi"},"seq":41,"source":"ws"}                  // to everyone else
 * S-&gt;C  {"id":1,"ok":false,"error":{"kind":"FORBIDDEN","message":"..."}}
 * </pre>
 * {@code payload} is arbitrary JSON - a chat line, a game move, a notification are all the same
 * primitive.
 *
 * <h3>Binary messages</h3>
 * A channel with {@code binaryAllowed} relays binary frames <i>verbatim</i>: the bytes one
 * participant sends are the bytes the others receive, with no envelope wrapped around them and no
 * acknowledgement sent back. There is nowhere in a binary frame to put an envelope without inventing
 * a framing both ends have to agree on, and a relay that needs no agreement is the one that can be
 * read by a three-line {@code onmessage} handler.
 * <p>
 * What that costs is that a recipient is told nothing about a blob it did not already know: not who
 * sent it, and not where it falls in the channel's order. A sender that needs either says so in the
 * bytes themselves, or announces the blob in an ordinary text message first.
 * <p>
 * <b>Binary messages do not consume a {@code seq}.</b> They sit outside the sequence entirely rather
 * than taking a number nobody can see, which would punch a hole in it: {@code seq} exists (§6) so a
 * client can notice it missed something, and a counter that skips for reasons a client cannot observe
 * would raise exactly the false alarm it is there to prevent. A channel's text events stay densely
 * numbered whether or not blobs are flowing past them.
 * <p>
 * The flag is orthogonal to the mode - a STATE channel may carry blobs too, and the relay never
 * touches its document. Sending one needs {@code POST}, like everything else a participant sends;
 * a channel without the flag answers a binary frame with an error and stays connected, exactly as it
 * would an unknown command.
 *
 * <h3>The protocol (STATE mode)</h3>
 * <pre>
 * S-&gt;C  {"type":"state","seq":40,"state":{...}}                          // on open, to the newcomer
 * C-&gt;S  {"id":2,"command":"merge","path":"players/alice","value":{"score":4}}
 * S-&gt;C  {"id":2,"ok":true,"seq":42}                                      // to the sender
 * S-&gt;C  {"type":"patch","op":"merge","path":"players/alice","value":{"score":4},
 *        "by":{"participantId":"p_8f2a","identity":"alice"},"seq":42}     // to everyone else
 * </pre>
 * The five commands are {@link ChannelStateCommand}; the applied command is itself the event, so
 * nothing here ever computes a diff. A command a channel's mode has no use for - {@code send} on a
 * STATE channel, {@code set} on an ECHO one - is answered with an error frame like any other
 * unknown command, not with a close.
 * <p>
 * Sending, in either mode, needs the {@code POST} right; {@code CONNECT} on its own makes the
 * channel read-only, which is a supported way to use it rather than an error - a STATE listener
 * still gets the snapshot and everybody else's patches.
 * <p>
 * Whether the sender also receives its own message as an event is a property of the connection, not
 * of the channel ({@code ?echoToSelf=true}): a test client and an application doing optimistic UI
 * want opposite answers on the same channel.
 *
 * <h3>Presence</h3>
 * A connection holding {@code LIST_PARTICIPANTS} is greeted with the participant list and then kept
 * up to date with {@code join}/{@code leave} events, so that the list it was given stays true
 * without it having to poll:
 * <pre>
 * S-&gt;C  {"type":"participants","seq":40,"participants":[{"participantId":"p_8f2a",...}]}
 * S-&gt;C  {"type":"join","participant":{"participantId":"p_1c30",...},"seq":40}
 * S-&gt;C  {"type":"leave","participant":{"participantId":"p_1c30",...},"seq":41}
 * </pre>
 * Without the right a connection is told nothing about anybody else, not even that somebody came or
 * went - it only ever knows about itself. The events are additionally a per-channel setting
 * ({@code notifyPresence}), the greeting is not: the server has the list in its hand at
 * {@code onOpen} anyway.
 *
 * <h3>Checks, twice</h3>
 * Everything {@link ChannelAccess} decides has already been decided over plain HTTP before the
 * upgrade, so a refused client gets a real status code instead of a socket that opens and closes.
 * It is all repeated in {@link #onOpen}: between the two the channel can be deleted, or somebody
 * else can take the last slot. Refusals close the session and return - throwing from {@code onOpen}
 * would skip {@code onClose} and leak the participant.
 */
public class ChannelWebSocketHandler extends WebSocketRequestHandler {
    /**
     * Close codes, private use range (4000-4999), mirroring the HTTP statuses they correspond to.
     * <p>
     * This one is reserved: a command the server cannot carry out is answered with an error frame
     * instead, because the connection is still perfectly good and a client that miscounted an
     * argument has not stopped being a participant.
     */
    public static final int CLOSE_CODE_BAD_REQUEST = 4400;
    public static final int CLOSE_CODE_FORBIDDEN = 4403;
    public static final int CLOSE_CODE_NOT_FOUND = 4404;
    public static final int CLOSE_CODE_FULL = 4409;
    /** Sent to every participant when {@code DELETE /api/channels/{id}} removes their channel. */
    public static final int CLOSE_CODE_CHANNEL_CLOSED = 4410;
    /**
     * {@code channelMessageRateLimitPerSecond} exceeded. This one <i>is</i> a close rather than an
     * error frame, unlike a command the server cannot carry out: a client sending faster than the
     * channel allows is not going to be slowed down by being answered, and answering it would cost
     * the server a frame per frame it is trying to refuse.
     */
    public static final int CLOSE_CODE_TOO_MANY_REQUESTS = 4429;
    public static final int CLOSE_CODE_INTERNAL_ERROR = 4500;
    /**
     * A connection that could not keep up with its channel's traffic, and whose queue of pending
     * frames therefore ran past its bound. Declared by {@link ParticipantOutbox}, which is the only
     * thing that sends it - a decision that can only be taken while writing.
     */
    public static final int CLOSE_CODE_TOO_SLOW = ParticipantOutbox.CLOSE_CODE_TOO_SLOW;

    /** Set by {@link ChannelsPathRequestHandler} on the request context before delegating. */
    public static final String CONTEXT_KEY_CHANNEL_ID = "channel_id";

    public static final String COMMAND_SEND = "send";

    public static final String EVENT_TYPE_MESSAGE = "message";
    //the snapshot and presence frames are queued by Channel itself, under its lock, so their names
    //live next to it - see ChannelEvents
    public static final String EVENT_TYPE_STATE = ChannelEvents.EVENT_TYPE_STATE;
    /** The list a connection holding {@code LIST_PARTICIPANTS} is greeted with, §9. */
    public static final String EVENT_TYPE_PARTICIPANTS = ChannelEvents.EVENT_TYPE_PARTICIPANTS;
    public static final String EVENT_TYPE_JOIN = ChannelEvents.EVENT_TYPE_JOIN;
    public static final String EVENT_TYPE_LEAVE = ChannelEvents.EVENT_TYPE_LEAVE;
    //the patch event is built by ChannelStateCommand, which names its own type

    /**
     * How a message reached the channel, §10: sent by a connected participant, or published in one
     * shot over HTTP by somebody who never joined. A recipient sees both as the same kind of event
     * and can tell them apart by this field alone.
     */
    public static final String SOURCE_WS = "ws";
    public static final String SOURCE_HTTP = "http";

    public static final String ERROR_KIND_BAD_REQUEST = "BAD_REQUEST";
    public static final String ERROR_KIND_FORBIDDEN = "FORBIDDEN";

    private static final String ATTRIBUTE_USER = "channelUser";
    private static final String ATTRIBUTE_CHANNEL_ID = "channelId";
    private static final String ATTRIBUTE_PARTICIPANT = "channelParticipant";
    private static final String ATTRIBUTE_CHANNEL = "channelChannel";
    private static final String ATTRIBUTE_MAY_POST = "channelMayPost";
    private static final String ATTRIBUTE_ECHO_TO_SELF = "channelEchoToSelf";
    private static final String ATTRIBUTE_RATE_LIMITER = "channelRateLimiter";

    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(ChannelWebSocketHandler.class);

    private final ChannelManager channels;
    private final SHTTPSConfig config;
    private final AuthManager authManager;
    private final ChannelAccess access;

    public ChannelWebSocketHandler(@NotNull ChannelManager channels, @NotNull SHTTPSConfig config,
                                   @NotNull AuthManager authManager) {
        this.channels = channels;
        this.config = config;
        this.authManager = authManager;
        this.access = new ChannelAccess(config, authManager);
        //a close from another thread does not interrupt a pending read, so the read loop has to come
        //up for air often enough that a disconnected participant's socket does not linger
        options.idlePingIntervalMillis = 1000;
        options.closeHandshakeTimeoutMillis = 1000;
        //read once, here, rather than per connection like the rate limit: these are the reader's own
        //limits and it is built with them when the session is, so there is no later point at which a
        //change to them could take effect on an existing connection anyway. Both are set - a client
        //sending one 4 MB blob in a single frame hits the frame cap first, and being told the
        //message limit is 8 MB while a 4 MB frame is refused would be a puzzle to debug
        int maxMessageBytes = Math.max(1, config.getChannelMaxMessageBytes());
        options.maxFramePayloadLength = maxMessageBytes;
        options.maxMessagePayloadLength = maxMessageBytes;
    }

    /**
     * A browser applies no CORS to a WebSocket handshake - it sends {@code Origin}, performs no
     * preflight and ignores {@code Access-Control-Allow-Origin} - so the configured rules only mean
     * anything on this endpoint if the server enforces them itself. Read per handshake, so editing
     * them takes effect without a restart.
     */
    @Override
    protected boolean isOriginAllowed(@NotNull Request request, @Nullable String origin) {
        if (isSameOriginAsHost(request, origin)) {
            return true;
        }
        return CORSMiddleware.findRuleForOrigin(config.getCORSRules(), origin) != null;
    }

    /** Takes the handshake's user and channel along; the request context is gone by the first frame. */
    @Override
    protected void onSessionCreated(RequestContext context, WebSocketSession session) {
        //a wrapper, because the attribute map rejects nulls and "no user" is a real, allowed state
        //when authorization is off - an absent attribute must stay distinguishable from it
        session.getAttributes().put(ATTRIBUTE_USER,
                new AuthenticatedUser(authManager.getAuthenticatedUser(context)));
        Object channelId = context.data.get(CONTEXT_KEY_CHANNEL_ID);
        if (channelId instanceof String) {
            session.getAttributes().put(ATTRIBUTE_CHANNEL_ID, channelId);
        }
    }

    @Override
    public void onOpen(WebSocketSession session) {
        AuthenticatedUser authenticated = (AuthenticatedUser) session.getAttributes().get(ATTRIBUTE_USER);
        if (authenticated == null) {
            //onSessionCreated did not run, so nothing is known about who this is
            session.close(CLOSE_CODE_FORBIDDEN, "Not authenticated");
            return;
        }
        String channelId = (String) session.getAttributes().get(ATTRIBUTE_CHANNEL_ID);
        if (channelId == null) {
            //reached without going through the path handler, so no channel was ever chosen
            session.close(CLOSE_CODE_NOT_FOUND, "No channel specified");
            return;
        }
        Channel channel = channels.get(channelId);
        if (channel == null) {
            session.close(CLOSE_CODE_NOT_FOUND, "No such channel");
            return;
        }
        if (!config.isChannelsEnabled()) {
            session.close(CLOSE_CODE_FORBIDDEN, "Channels are disabled");
            return;
        }
        ChannelAccess.Refusal refusal = access.checkConnect(authenticated.user, channel,
                ChannelAccess.extractPassword(session.handshakeRequest));
        if (refusal != null) {
            session.close(refusal.closeCode, refusal.message);
            return;
        }
        boolean mayListParticipants = access.mayListParticipants(authenticated.user);
        //twice the biggest message the endpoint accepts, so a connection may fall a message or two
        //behind without being given up on, and one at the maximum size still fits. Derived rather
        //than configured: a setting nobody could reason about would be worse than a number that
        //follows the message size the administrator already chose
        Channel.Admission admission = channel.admit(session,
                Participant.identityOf(authenticated.user), mayListParticipants,
                2 * Math.max(1, config.getChannelMaxMessageBytes()));
        if (admission == null) {
            //the channel can also have been deleted, or collected as idle, since it was looked up
            //above - a client that was refused for that must not be told the room was crowded
            if (channel.isRemoved()) {
                session.close(CLOSE_CODE_NOT_FOUND, "Channel no longer exists");
            } else {
                session.close(CLOSE_CODE_FULL, "Channel is full");
            }
            return;
        }
        //rights are snapshotted at connect: asking again per message would mean a database lookup
        //for every frame of a user that has a role
        session.getAttributes().put(ATTRIBUTE_MAY_POST, access.mayPost(authenticated.user));
        session.getAttributes().put(ATTRIBUTE_ECHO_TO_SELF,
                ChannelAccess.isEchoToSelfRequested(session.handshakeRequest));
        session.getAttributes().put(ATTRIBUTE_CHANNEL, channel);
        //the channel's own limit when it has one, the server-wide setting otherwise; either way read
        //per connection, so a change to it applies to the next client rather than the next restart
        session.getAttributes().put(ATTRIBUTE_RATE_LIMITER, new MessageRateLimiter(
                channel.messageRateLimit(config.getChannelMessageRateLimitPerSecond())));
        //every refusal above this point is free of cleanup; from the admission on, the participant
        //is in the channel and the attribute has to be there for onClose to take it out again
        session.getAttributes().put(ATTRIBUTE_PARTICIPANT, admission.participant);

        //the document a STATE participant starts from (§8), the participant list of §9 and the join
        //event that tells everybody else are already queued - Channel.admit did it under its own
        //lock, which is what stops a patch numbered straight afterwards from reaching this
        //connection ahead of the snapshot it patches. All that is left is to let them out, and that
        //deliberately happens here, with no lock held.
        ParticipantOutbox.flushAll(admission.toFlush);
    }

    @Override
    public void onTextMessage(WebSocketSession session, String message) {
        Participant participant = (Participant) session.getAttributes().get(ATTRIBUTE_PARTICIPANT);
        Channel channel = (Channel) session.getAttributes().get(ATTRIBUTE_CHANNEL);
        if (participant == null || channel == null) {
            return;//refused in onOpen; the close frame is already on its way
        }
        //checked before the message is even parsed: a limiter that only refused work after doing
        //most of it would not be much of a limiter
        MessageRateLimiter rateLimiter =
                (MessageRateLimiter) session.getAttributes().get(ATTRIBUTE_RATE_LIMITER);
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            session.close(CLOSE_CODE_TOO_MANY_REQUESTS, "Message rate limit exceeded");
            return;
        }

        Object requestId = JSONObject.NULL;
        try {
            JSONObject envelope;
            try {
                envelope = new JSONObject(message);
            } catch (JSONException e) {
                sendError(session, requestId, ERROR_KIND_BAD_REQUEST, "Malformed JSON");
                return;
            }
            if (envelope.has("id") && !envelope.isNull("id")) {
                requestId = envelope.get("id");
            }
            String command = envelope.optString("command", null);
            boolean stateChannel = channel.mode == Channel.Mode.STATE;
            boolean known = stateChannel ?
                    ChannelStateCommand.isStateCommand(command) : COMMAND_SEND.equals(command);
            if (!known) {
                //a client that misspells a command, speaks a protocol from a later version, or aims
                //a command at the wrong kind of channel, is told so and left connected - only the
                //checks in onOpen end a session
                sendError(session, requestId, ERROR_KIND_BAD_REQUEST,
                        unknownCommandMessage(command, channel.mode));
                return;
            }
            Boolean mayPost = (Boolean) session.getAttributes().get(ATTRIBUTE_MAY_POST);
            if (mayPost == null || !mayPost) {
                sendError(session, requestId, ERROR_KIND_FORBIDDEN,
                        "Missing channel right: " + User.ChannelRights.POST.name());
                return;
            }
            if (stateChannel) {
                handleStateCommand(session, envelope, requestId, channel, participant);
            } else {
                handleSend(session, envelope, requestId, channel, participant);
            }
        } catch (IOException e) {
            //the sender's own socket went away mid-answer; the read loop notices next
            logger.w("Could not answer a channel message: " + e.getMessage());
        } catch (Throwable t) {
            logger.stackTrace(t);
            session.close(CLOSE_CODE_INTERNAL_ERROR, "Internal server error");
        }
    }

    /**
     * A binary frame: relayed to everybody else byte for byte, see the class comment.
     * <p>
     * Deliberately short of everything {@link #onTextMessage} does after the rate limiter - there is
     * no envelope to parse, no command to dispatch on, no {@code seq} to hand out and no ack to send
     * back. What is left is the two checks a relay cannot skip.
     */
    @Override
    public void onBinaryMessage(WebSocketSession session, byte[] message) {
        Participant participant = (Participant) session.getAttributes().get(ATTRIBUTE_PARTICIPANT);
        Channel channel = (Channel) session.getAttributes().get(ATTRIBUTE_CHANNEL);
        if (participant == null || channel == null) {
            return;//refused in onOpen; the close frame is already on its way
        }
        //one budget for both kinds of message: a client that has spent its allowance on text frames
        //cannot get more by switching to binary ones
        MessageRateLimiter rateLimiter =
                (MessageRateLimiter) session.getAttributes().get(ATTRIBUTE_RATE_LIMITER);
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            session.close(CLOSE_CODE_TOO_MANY_REQUESTS, "Message rate limit exceeded");
            return;
        }

        try {
            //the errors carry a null id rather than one echoed back: a binary frame has no envelope
            //and so no request id to answer with. A client that wants its refusals correlated has
            //the text protocol for that
            if (!channel.binaryAllowed) {
                sendError(session, JSONObject.NULL, ERROR_KIND_BAD_REQUEST,
                        "Binary messages are not enabled on this channel");
                return;
            }
            Boolean mayPost = (Boolean) session.getAttributes().get(ATTRIBUTE_MAY_POST);
            if (mayPost == null || !mayPost) {
                sendError(session, JSONObject.NULL, ERROR_KIND_FORBIDDEN,
                        "Missing channel right: " + User.ChannelRights.POST.name());
                return;
            }
            //queued under the channel's lock and written after it, like everything else, so that a
            //blob keeps its place among the text messages around it
            ParticipantOutbox.flushAll(channel.relayBinary(participant, echoToSelf(session), message));
        } catch (IOException e) {
            //the sender's own socket went away mid-answer; the read loop notices next
            logger.w("Could not answer a channel binary message: " + e.getMessage());
        } catch (Throwable t) {
            logger.stackTrace(t);
            session.close(CLOSE_CODE_INTERNAL_ERROR, "Internal server error");
        }
    }

    /** ECHO: relay the payload to everybody else, unchanged. */
    private void handleSend(WebSocketSession session, JSONObject envelope, Object requestId,
                            Channel channel, Participant participant) throws IOException {
        if (!envelope.has("payload") || envelope.isNull("payload")) {
            sendError(session, requestId, ERROR_KIND_BAD_REQUEST, "Missing payload");
            return;
        }
        Object payload = envelope.get("payload");

        //numbering, the acknowledgement and the event all queue under the channel's lock, so every
        //recipient receives them in the order the channel numbered them; the writing happens after,
        //so one client that has stopped reading cannot hold up everybody else's messages
        Channel.Dispatch dispatch = channel.publish(participant, echoToSelf(session), requestId,
                seq -> messageEvent(participant.toSenderJson(), payload, seq, SOURCE_WS).toString());
        ParticipantOutbox.flushAll(dispatch.toFlush);
    }

    /**
     * The {@code message} event of an ECHO channel, built in one place for both of the ways a
     * message can arrive: from a participant's own socket, and from
     * {@code POST /api/channels/{id}/message} (§10). The two differ only in {@code source} and in
     * the shape of {@code from}.
     *
     * @param from    the sender tag, {@link Participant#toSenderJson()} or
     *                {@link Participant#httpSenderJson}
     * @param payload whatever the sender sent, relayed unchanged
     */
    static @NotNull JSONObject messageEvent(@NotNull JSONObject from, @NotNull Object payload,
                                            long seq, @NotNull String source) {
        JSONObject event = new JSONObject();
        event.put("type", EVENT_TYPE_MESSAGE);
        event.put("from", from);
        event.put("payload", payload);
        event.put("seq", seq);
        event.put("source", source);
        return event;
    }

    /**
     * STATE: change the document and tell everybody else what changed.
     * <p>
     * The applying and the numbering happen inside {@link Channel#mutateState}, under the channel's
     * lock; the broadcast deliberately happens here, after it has been released, so that one
     * participant that has stopped reading cannot hold up everybody else's commands.
     */
    private void handleStateCommand(WebSocketSession session, JSONObject envelope, Object requestId,
                                    Channel channel, Participant participant) throws IOException {
        Channel.Dispatch dispatch;
        try {
            ChannelStateCommand command = ChannelStateCommand.parse(envelope);
            //the acknowledgement - a plain one, without the change in it, since the sender already
            //knows what it sent - is queued in there too, ahead of the patch
            dispatch = channel.mutateState(command, participant, echoToSelf(session), requestId);
        } catch (ChannelCommandException e) {
            //a command the server understood but cannot carry out: the connection is fine, only
            //this one command is not
            sendError(session, requestId, ERROR_KIND_BAD_REQUEST, e.getMessage());
            return;
        }
        ParticipantOutbox.flushAll(dispatch.toFlush);
    }

    private static String unknownCommandMessage(@Nullable String command, Channel.Mode mode) {
        if (command == null) {
            return "Missing command";
        }
        if (COMMAND_SEND.equals(command) || ChannelStateCommand.isStateCommand(command)) {
            return "Command not available in a " + mode.name().toLowerCase() + " channel: " + command;
        }
        return "Unknown command: " + command;
    }

    private static boolean echoToSelf(WebSocketSession session) {
        Boolean echoToSelf = (Boolean) session.getAttributes().get(ATTRIBUTE_ECHO_TO_SELF);
        return echoToSelf != null && echoToSelf;
    }

    @Override
    public void onClose(WebSocketSession session, int code, String reason) {
        Participant participant = (Participant) session.getAttributes().get(ATTRIBUTE_PARTICIPANT);
        Channel channel = (Channel) session.getAttributes().get(ATTRIBUTE_CHANNEL);
        if (participant != null && channel != null) {
            //the leave is queued under the channel's lock, together with the removal; the writing
            //happens here, outside it, like every other frame this class sends
            ParticipantOutbox.flushAll(channel.removeParticipant(participant));
        }
    }

    private void sendError(WebSocketSession session, Object requestId, String kind, String message)
            throws IOException {
        JSONObject error = new JSONObject();
        error.put("kind", kind);
        error.put("message", message);
        JSONObject answer = new JSONObject();
        answer.put("id", requestId);
        answer.put("ok", false);
        answer.put("error", error);
        session.sendText(answer.toString());
    }

    /** Wrapper so that "no user because authorization is off" is not the same as "no attribute". */
    private static final class AuthenticatedUser {
        final @Nullable User user;

        AuthenticatedUser(@Nullable User user) {
            this.user = user;
        }
    }
}
