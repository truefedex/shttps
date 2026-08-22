package com.phlox.simpleserver.handlers.channels;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.channels.Channel;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Who may do what with a channel. One implementation, used both by the HTTP handlers before a
 * handshake and by {@link ChannelWebSocketHandler} after it, so a WebSocket connection can not be
 * held to a weaker standard than the REST endpoints of the same feature.
 * <p>
 * Connecting passes three independent layers, in this order (design document, §5):
 * <ol>
 *     <li><b>server authorization</b> - with {@code AuthMode.NONE} everyone is let in as an
 *     anonymous participant; otherwise the request has already been through the same
 *     {@code authMiddlewares} as the rest of the API, and an unauthenticated one is refused here
 *     too;</li>
 *     <li><b>guest access</b> - an unauthenticated visitor arrives as the configured guest user, and
 *     a channel may close itself to guests with {@code guestsAllowed=false};</li>
 *     <li><b>channel password</b> - orthogonal to both: when a channel has one it is always
 *     required, of everybody, however well authenticated and however many rights they hold.</li>
 * </ol>
 */
public class ChannelAccess {
    /**
     * A browser's {@code WebSocket} can not set headers on a handshake, so the password has to be
     * reachable through the URL for the endpoint to be usable from an ordinary page at all. Clients
     * that would rather keep it out of URLs and logs may send the header instead, which wins when
     * both are present.
     */
    public static final String QUERY_PARAM_PASSWORD = "password";
    public static final String HEADER_CHANNEL_PASSWORD = "x-channel-password";

    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(ChannelAccess.class);

    private final SHTTPSConfig config;
    private final AuthManager authManager;

    public ChannelAccess(@NotNull SHTTPSConfig config, @NotNull AuthManager authManager) {
        this.config = config;
        this.authManager = authManager;
    }

    /**
     * One refusal, expressed once and answerable either way: as an HTTP response before the
     * upgrade, or as a close code once the socket is open. The two always say the same thing.
     */
    public static final class Refusal {
        public final int httpCode;
        public final int closeCode;
        public final @NotNull String message;

        private Refusal(int httpCode, int closeCode, @NotNull String message) {
            this.httpCode = httpCode;
            this.closeCode = closeCode;
            this.message = message;
        }

        public static @NotNull Refusal forbidden(@NotNull String message) {
            return new Refusal(403, ChannelWebSocketHandler.CLOSE_CODE_FORBIDDEN, message);
        }

        public static @NotNull Refusal notFound(@NotNull String message) {
            return new Refusal(404, ChannelWebSocketHandler.CLOSE_CODE_NOT_FOUND, message);
        }

        public static @NotNull Refusal full(@NotNull String message) {
            return new Refusal(409, ChannelWebSocketHandler.CLOSE_CODE_FULL, message);
        }

        public @NotNull Response toResponse() {
            switch (httpCode) {
                case 404:
                    return StandardResponses.NOT_FOUND(message);
                case 409:
                    return StandardResponses.CONFLICT(message);
                case 403:
                default:
                    return StandardResponses.FORBIDDEN(message);
            }
        }
    }

    public boolean isAuthRequired() {
        return !config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE);
    }

    /**
     * The user behind a request, or {@code null} when authorization is off and there are no users
     * to speak of - the same shape {@code StatusRequestHandler.checkUser} uses.
     */
    public @Nullable User resolveUser(@NotNull RequestContext context) {
        if (!isAuthRequired()) return null;
        return authManager.getAuthenticatedUser(context);
    }

    /** The channel password a request carries, header first, then query parameter. */
    public static @Nullable String extractPassword(@NotNull Request request) {
        String fromHeader = request.headers.get(HEADER_CHANNEL_PASSWORD);
        if (fromHeader != null && !fromHeader.isEmpty()) return fromHeader;
        return request.queryParams.get(QUERY_PARAM_PASSWORD);
    }

    public static boolean isEchoToSelfRequested(@NotNull Request request) {
        return "true".equalsIgnoreCase(request.queryParams.get("echoToSelf"));
    }

    /**
     * @param user the authenticated user, or null when authorization is off
     * @return null when every requested right is held
     */
    public @Nullable Refusal checkRights(@Nullable User user, @NotNull User.ChannelRights... required) {
        if (!isAuthRequired()) {
            //no users, so no rights to consult - as everywhere else in the API
            return null;
        }
        if (user == null) {
            return Refusal.forbidden("Not authenticated");
        }
        EnumSet<User.ChannelRights> rights;
        try {
            rights = authManager.getUserRightsEvaluator().userChannelRights(user);
        } catch (Exception e) {
            //reading the rights of a user with a role is a database lookup, which can fail; this is
            //not the place to guess in the user's favour
            logger.stackTrace(e);
            return Refusal.forbidden("Could not read channel rights");
        }
        for (User.ChannelRights right : required) {
            if (!rights.contains(right)) {
                return Refusal.forbidden("Missing channel right: " + right.name());
            }
        }
        return null;
    }

    public boolean mayPost(@Nullable User user) {
        return checkRights(user, User.ChannelRights.POST) == null;
    }

    /**
     * Whether a connection may be told who else is in a channel, §9 - the same decision
     * {@code GET /api/channels/{id}/participants} makes, so that opening a socket is not a way
     * around the right the REST endpoint asks for.
     */
    public boolean mayListParticipants(@Nullable User user) {
        return checkRights(user, User.ChannelRights.LIST_PARTICIPANTS) == null;
    }

    /**
     * The three layers of §5 - server authorization, guest access, channel password - without the
     * right that the particular thing being asked for needs. Every way into a channel passes these
     * same three, so that publishing into one over HTTP can not be an easier door than connecting
     * a socket to it.
     *
     * @param password the plain-text channel password the client supplied, if any
     */
    private @Nullable Refusal checkAccessLayers(@Nullable User user, @NotNull Channel channel,
                                                @Nullable String password) {
        if (isAuthRequired() && user == null) {
            return Refusal.forbidden("Not authenticated");
        }
        if (user != null && user.isGuest() && !channel.guestsAllowed) {
            return Refusal.forbidden("This channel is not open to guests");
        }
        if (!channel.checkPassword(password)) {
            return Refusal.forbidden("Wrong or missing channel password");
        }
        return null;
    }

    /**
     * The full connect decision, minus capacity - taking a slot has to happen atomically with the
     * check that there is one, so it lives on {@link Channel#admit}.
     *
     * @param password the plain-text channel password the client supplied, if any
     */
    public @Nullable Refusal checkConnect(@Nullable User user, @NotNull Channel channel,
                                          @Nullable String password) {
        Refusal refusal = checkAccessLayers(user, channel, password);
        if (refusal != null) return refusal;
        return checkRights(user, User.ChannelRights.CONNECT);
    }

    /**
     * The one-shot publish decision, §10: the same three layers as connecting, but the right it
     * asks for is POST rather than CONNECT - a publisher never joins the channel, so there is no
     * membership for CONNECT to be about.
     *
     * @param password the plain-text channel password the client supplied, if any
     */
    public @Nullable Refusal checkPublish(@Nullable User user, @NotNull Channel channel,
                                          @Nullable String password) {
        Refusal refusal = checkAccessLayers(user, channel, password);
        if (refusal != null) return refusal;
        return checkRights(user, User.ChannelRights.POST);
    }
}
