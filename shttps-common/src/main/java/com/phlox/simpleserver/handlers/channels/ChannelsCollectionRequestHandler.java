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
import com.phlox.simpleserver.channels.ChannelDefinition;
import com.phlox.simpleserver.channels.ChannelManager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code /api/channels} - the channel collection.
 * <p>
 * {@code GET} lists every channel, behind {@code LIST_CHANNELS}. {@code POST} creates a dynamic one,
 * behind {@code CREATE} and the {@code allowDynamicChannelCreation} setting (off by default), and
 * subject to {@code getMaxDynamicChannels()}.
 * <p>
 * Both refusals {@code POST} can end in are conflicts - a taken id and a reached limit - so they
 * differ in their message rather than their status code.
 */
public class ChannelsCollectionRequestHandler implements RequestHandler {
    public static final String PATH = "/api/channels";

    /**
     * The plain text a channel is protected with, sent once at creation time and never stored as
     * given - {@link ChannelDefinition} keeps the hash. Not the same field name as the stored one.
     */
    public static final String FIELD_PASSWORD = "password";
    /** Where to point a {@code WebSocket} at, in the answer to a create. */
    public static final String FIELD_WS_URL = "wsUrl";

    private final ChannelManager channels;
    private final SHTTPSConfig config;
    private final ChannelAccess access;

    public ChannelsCollectionRequestHandler(@NotNull ChannelManager channels,
                                            @NotNull SHTTPSConfig config,
                                            @NotNull AuthManager authManager) {
        this.channels = channels;
        this.config = config;
        this.access = new ChannelAccess(config, authManager);
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!config.isChannelsEnabled()) {
            return StandardResponses.FORBIDDEN("Channels are disabled");
        }

        if (Request.METHOD_POST.equals(request.method)) {
            if (!config.getAllowDynamicChannelCreation()) {
                return StandardResponses.FORBIDDEN("Dynamic channel creation is disabled");
            }
            ChannelAccess.Refusal refusal = access.checkRights(access.resolveUser(context),
                    User.ChannelRights.CREATE);
            if (refusal != null) return refusal.toResponse();
            return createChannel(context, request);
        }

        if (!Request.METHOD_GET.equals(request.method)) {
            return StandardResponses.METHOD_NOT_ALLOWED(
                    new String[]{Request.METHOD_GET, Request.METHOD_POST});
        }

        ChannelAccess.Refusal refusal = access.checkRights(access.resolveUser(context),
                User.ChannelRights.LIST_CHANNELS);
        if (refusal != null) return refusal.toResponse();

        JSONArray list = new JSONArray();
        for (Channel channel : channels.all()) {
            list.put(channel.toJson());
        }
        JSONObject answer = new JSONObject();
        answer.put("channels", list);
        return new TextResponse(200, "Ok", answer.toString(), "application/json");
    }

    /**
     * {@code POST /api/channels}, §4. Every field of the body is optional, an absent {@code id}
     * means "generate one", and the answer is the channel's own metadata plus the address to
     * connect a socket to.
     */
    private Response createChannel(RequestContext context, Request request) throws Exception {
        context.requestBodyReader.readRequestBody(request);
        JSONObject body;
        try {
            //no body at all is a legal request: it asks for a channel with every default
            body = request.body == null || request.body.size() == 0 ?
                    new JSONObject() : new JSONObject(request.body.toString());
        } catch (JSONException e) {
            return StandardResponses.BAD_REQUEST("Malformed JSON body");
        }

        String id = optionalString(body, ChannelDefinition.FIELD_ID);
        if (id != null && !ChannelDefinition.isValidId(id)) {
            return StandardResponses.BAD_REQUEST("Unusable channel id: " + id);
        }

        Channel.Mode mode = Channel.Mode.ECHO;
        String modeName = optionalString(body, ChannelDefinition.FIELD_MODE);
        if (modeName != null) {
            try {
                mode = Channel.Mode.valueOf(modeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                return StandardResponses.BAD_REQUEST("Unknown channel mode: " + modeName);
            }
        }

        Integer maxParticipants = null;
        if (body.has(ChannelDefinition.FIELD_MAX_PARTICIPANTS) &&
                !body.isNull(ChannelDefinition.FIELD_MAX_PARTICIPANTS)) {
            int requested = body.optInt(ChannelDefinition.FIELD_MAX_PARTICIPANTS, 0);
            if (requested <= 0) {
                return StandardResponses.BAD_REQUEST("maxParticipants must be a positive number");
            }
            maxParticipants = requested;
        }

        Integer messageRateLimit = null;
        if (body.has(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT) &&
                !body.isNull(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT)) {
            int requested = body.optInt(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT, -1);
            if (requested < 0) {
                //zero is not refused here the way it is for maxParticipants: it is the way to ask
                //for a channel that is not throttled at all
                return StandardResponses.BAD_REQUEST(ChannelDefinition.FIELD_MESSAGE_RATE_LIMIT +
                        " must be zero (no limit) or a positive number");
            }
            messageRateLimit = requested;
        }

        String password = optionalString(body, FIELD_PASSWORD);
        //a channel created through the API is always deletable: the idle sweep would otherwise be
        //the only thing that could ever remove it
        ChannelDefinition template = new ChannelDefinition(id == null ? "" : id, mode,
                body.optJSONObject(ChannelDefinition.FIELD_INITIAL_STATE), maxParticipants,
                /*deletable*/ true, /*notifyPresence*/ true,
                body.optBoolean(ChannelDefinition.FIELD_GUESTS_ALLOWED, true),
                password == null ? null : ChannelDefinition.hashChannelPassword(password),
                messageRateLimit,
                //off unless asked for: relaying blobs is the more expensive thing a channel can do,
                //and a client that did not ask for it is not made to pay for it
                body.optBoolean(ChannelDefinition.FIELD_BINARY_ALLOWED, false));

        ChannelManager.Creation creation = channels.createDynamic(id, template, config);
        if (creation.failure == ChannelManager.Failure.LIMIT_REACHED) {
            return StandardResponses.CONFLICT("Too many dynamic channels (max " +
                    config.getMaxDynamicChannels() + ")");
        }
        if (creation.channel == null) {
            //an id the caller chose is named back to it; without one, the conflict is the server
            //having failed to find a free id of its own, which says nothing useful about an id
            return StandardResponses.CONFLICT(id != null ? "Channel already exists: " + id :
                    "Could not allocate a channel id");
        }

        JSONObject answer = creation.channel.toJson();
        //a path rather than an absolute url: the handler knows neither the scheme the client reached
        //it by nor the name it used for this host
        answer.put(FIELD_WS_URL, ChannelsPathRequestHandler.PATH_PREFIX + creation.channel.id + "/" +
                ChannelsPathRequestHandler.ACTION_CONNECT);
        return new TextResponse(201, "Created", answer.toString(), "application/json");
    }

    /** A string field that is allowed to be absent, null or empty - all of which mean "not given". */
    private static @Nullable String optionalString(JSONObject body, String field) {
        String value = body.optString(field, null);
        return value == null || value.isEmpty() ? null : value;
    }
}
