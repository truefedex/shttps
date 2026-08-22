package com.phlox.simpleserver.handlers.channels;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.channels.Channel;
import com.phlox.simpleserver.channels.ChannelManager;
import com.phlox.simpleserver.channels.Participant;
import com.phlox.simpleserver.channels.ParticipantOutbox;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything under {@code /api/channels/}, dispatched by hand.
 * <p>
 * {@code Router} matches exact paths and longest prefixes; it has no {@code {id}} path parameters.
 * So, like {@code FilesRequestHandler} on {@code /} and {@code StaticAssetsRequestHandler} on
 * {@code /shttps-static-public}, this one handler is mounted on the prefix and parses the tail
 * itself:
 * <pre>
 * GET    /api/channels/{id}               metadata            (CONNECT)
 * GET    /api/channels/{id}/participants  who is connected    (LIST_PARTICIPANTS)
 * GET    /api/channels/{id}/connect       WebSocket handshake (CONNECT + §5)
 * POST   /api/channels/{id}/message       one-shot publish    (POST + §5)
 * DELETE /api/channels/{id}               delete, if deletable(DELETE)
 * </pre>
 * <p>
 * A prefix route carries no method filter (the radix tree is not keyed by method), so this handler
 * answers {@code 405} for the verbs it does not serve rather than relying on the router to.
 */
public class ChannelsPathRequestHandler implements RequestHandler {
    public static final String PATH_PREFIX = "/api/channels/";

    public static final String ACTION_CONNECT = "connect";
    public static final String ACTION_PARTICIPANTS = "participants";
    public static final String ACTION_MESSAGE = "message";

    public static final String FIELD_PAYLOAD = "payload";
    public static final String FIELD_SENDER_LABEL = "senderLabel";

    private final ChannelManager channels;
    private final SHTTPSConfig config;
    private final ChannelAccess access;
    private final ChannelWebSocketHandler webSocketHandler;

    public ChannelsPathRequestHandler(@NotNull ChannelManager channels, @NotNull SHTTPSConfig config,
                                      @NotNull AuthManager authManager,
                                      @NotNull ChannelWebSocketHandler webSocketHandler) {
        this.channels = channels;
        this.config = config;
        this.access = new ChannelAccess(config, authManager);
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.isChannelsEnabled()) {
            return StandardResponses.FORBIDDEN("Channels are disabled");
        }
        if (!request.path.startsWith(PATH_PREFIX)) {
            return StandardResponses.NOT_FOUND();
        }
        String tail = request.path.substring(PATH_PREFIX.length());
        int slash = tail.indexOf('/');
        String channelId = slash < 0 ? tail : tail.substring(0, slash);
        String action = slash < 0 ? "" : tail.substring(slash + 1);
        if (channelId.isEmpty()) {
            return StandardResponses.NOT_FOUND();
        }

        Channel channel = channels.get(channelId);
        if (channel == null) {
            return StandardResponses.NOT_FOUND("No such channel: " + channelId);
        }

        switch (action) {
            case "":
                return handleChannel(context, request, channel);
            case ACTION_PARTICIPANTS:
                return handleParticipants(context, request, channel);
            case ACTION_CONNECT:
                return handleConnect(context, request, channel);
            case ACTION_MESSAGE:
                return handleMessage(context, request, channel);
            default:
                return StandardResponses.NOT_FOUND();
        }
    }

    private Response handleChannel(RequestContext context, Request request, Channel channel) {
        if (Request.METHOD_DELETE.equals(request.method)) {
            ChannelAccess.Refusal refusal = access.checkRights(access.resolveUser(context),
                    User.ChannelRights.DELETE);
            if (refusal != null) return refusal.toResponse();
            if (!channel.deletable) {
                //the flag decides, not where the channel came from: a predefined channel whose
                //definition says deletable may go too, and comes back on the next server start
                return StandardResponses.FORBIDDEN("Channel is not deletable");
            }
            Channel removed = channels.remove(channel.id);
            if (removed == null) {
                //another delete got there first
                return StandardResponses.NOT_FOUND("No such channel: " + channel.id);
            }
            //outside the manager's lock, and outside the channel's: a participant that has stopped
            //reading must not hold up the answer to this request
            removed.closeAllSessions(ChannelWebSocketHandler.CLOSE_CODE_CHANNEL_CLOSED,
                    "Channel deleted");
            JSONObject answer = new JSONObject();
            answer.put("deleted", true);
            answer.put("id", removed.id);
            return json(answer);
        }
        if (!Request.METHOD_GET.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(
                    new String[]{Request.METHOD_GET, Request.METHOD_DELETE});
        }
        ChannelAccess.Refusal refusal = access.checkRights(access.resolveUser(context),
                User.ChannelRights.CONNECT);
        if (refusal != null) return refusal.toResponse();
        return json(channel.toJson());
    }

    private Response handleParticipants(RequestContext context, Request request, Channel channel) {
        if (!Request.METHOD_GET.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        ChannelAccess.Refusal refusal = access.checkRights(access.resolveUser(context),
                User.ChannelRights.LIST_PARTICIPANTS);
        if (refusal != null) return refusal.toResponse();

        JSONArray list = new JSONArray();
        for (Participant participant : channel.participants()) {
            list.put(participant.toJson());
        }
        JSONObject answer = new JSONObject();
        answer.put("channel", channel.id);
        answer.put("participants", list);
        return json(answer);
    }

    /**
     * Everything is decided here, as an ordinary HTTP answer, and only a request that passes gets
     * upgraded - a refused client sees 403/404/409 instead of a socket that opens and closes
     * immediately. {@link ChannelWebSocketHandler#onOpen} repeats the checks, because the channel
     * can go away or fill up between this decision and the upgrade.
     */
    private Response handleConnect(RequestContext context, Request request, Channel channel) throws Exception {
        if (!Request.METHOD_GET.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        ChannelAccess.Refusal refusal = access.checkConnect(access.resolveUser(context), channel,
                ChannelAccess.extractPassword(request));
        if (refusal != null) return refusal.toResponse();
        if (channel.isFull()) {
            //the binding check is the one inside Channel.admit; this only spares a client the
            //upgrade when the channel is visibly full already
            return StandardResponses.CONFLICT("Channel is full");
        }
        context.data.put(ChannelWebSocketHandler.CONTEXT_KEY_CHANNEL_ID, channel.id);
        return webSocketHandler.handleRequest(context, request);
    }

    /**
     * {@code POST /api/channels/{id}/message}, §10: one message into a channel without holding a
     * socket for it - a cron job, a webhook, a sensor. The publisher is not registered anywhere: it
     * joins nothing, appears in no participant list and takes no slot of {@code maxParticipants};
     * all it gets back is how many sockets its message went to.
     * <p>
     * ECHO only. A STATE channel is answered with a conflict rather than quietly ignored: publishing
     * a payload nobody would apply to the document is a mistake worth naming.
     * <p>
     * The access checks are the connect checks with one right swapped
     * ({@link ChannelAccess#checkPublish}), so this endpoint is not an easier way into a channel
     * than a socket is. Rate limiting is the ordinary {@code RateLimitingMiddleware} the route
     * already carries - a publisher here is an HTTP client like any other.
     */
    private Response handleMessage(RequestContext context, Request request, Channel channel) throws Exception {
        if (!Request.METHOD_POST.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_POST});
        }
        //before any refusal: the connection is kept alive, and a body left unread would be taken
        //for the next request on the same socket
        context.requestBodyReader.readRequestBody(request);

        User user = access.resolveUser(context);
        ChannelAccess.Refusal refusal = access.checkPublish(user, channel,
                ChannelAccess.extractPassword(request));
        if (refusal != null) return refusal.toResponse();

        if (channel.mode != Channel.Mode.ECHO) {
            return StandardResponses.CONFLICT("Not an echo channel: " + channel.id +
                    " (a state channel is changed with commands over a socket)");
        }

        JSONObject body;
        try {
            body = request.body == null || request.body.size() == 0 ?
                    new JSONObject() : new JSONObject(request.body.toString());
        } catch (JSONException e) {
            return StandardResponses.BAD_REQUEST("Malformed JSON body");
        }
        if (!body.has(FIELD_PAYLOAD) || body.isNull(FIELD_PAYLOAD)) {
            //the same wording the send command answers with, for the same mistake
            return StandardResponses.BAD_REQUEST("Missing payload");
        }
        Object payload = body.get(FIELD_PAYLOAD);
        String senderLabel = body.optString(FIELD_SENDER_LABEL, null);
        if (senderLabel != null && senderLabel.isEmpty()) {
            senderLabel = null;
        }

        //numbered and queued for every participant under the channel's lock, so this message takes
        //its place in the same order everybody else's does; written afterwards, outside it. Nothing
        //has been numbered until here, so a refused publish above left the sequence untouched.
        //There is no sender and no request id: the publisher never joined and gets its answer over
        //HTTP instead of as a frame
        String senderJson = Participant.httpSenderJson(
                Participant.identityOf(user), senderLabel).toString();
        Channel.Dispatch dispatch = channel.publish(null, false, null,
                seq -> ChannelWebSocketHandler.messageEvent(new JSONObject(senderJson),
                        payload, seq, ChannelWebSocketHandler.SOURCE_HTTP).toString());
        ParticipantOutbox.flushAll(dispatch.toFlush);

        JSONObject answer = new JSONObject();
        answer.put("delivered", true);
        //the delivery that was actually attempted, not a second look taken afterwards
        answer.put("recipientCount", dispatch.toFlush.size());
        answer.put("seq", dispatch.seq);
        return json(answer);
    }

    private static Response json(JSONObject object) {
        return new TextResponse(200, "Ok", object.toString(), "application/json");
    }
}
