package com.phlox.simpleserver.channels;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.websocket.WebSocketCloseCodes;
import com.phlox.simpleserver.SHTTPSConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every live channel, by id. Lives on {@link com.phlox.simpleserver.SHTTPSApp} next to the
 * database holder and the WebDAV lock manager, and is handed to the channel handlers through their
 * constructors.
 * <p>
 * Channels come from two places: predefined ones built from configuration when the server starts
 * ({@link #applyPredefinedChannels}), which are persistent, and dynamic ones created through
 * {@code POST /api/channels} ({@link #createDynamic}), which are collected once they have been
 * empty for {@code getChannelIdleTimeoutMillis()} ({@link #collectIdleChannels}).
 * <p>
 * Creation, removal and collection are {@code synchronized} on the manager: the cap on the number
 * of dynamic channels, the duplicate-id check and the generation of a free id are all
 * check-then-act on the map. Nothing is <b>sent</b> under that lock - the methods hand back the
 * channels they took out, and the caller closes their sessions afterwards.
 */
public class ChannelManager {
    /** Bounded because ids are random: a retry means a collision, not a full keyspace. */
    private static final int ID_GENERATION_ATTEMPTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /**
     * Rebuilds the predefined channels from configuration. Called from
     * {@link com.phlox.simpleserver.SHTTPSApp#startServer()}, i.e. while nothing is connected;
     * channels that are no longer defined go away, dynamic ones are left alone.
     */
    public synchronized void applyPredefinedChannels(@NotNull SHTTPSConfig config) {
        for (Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Channel> entry = it.next();
            if (entry.getValue().persistent) {
                entry.getValue().closeAllSessions(WebSocketCloseCodes.GOING_AWAY, "Server restarting");
                it.remove();
            }
        }

        if (!config.isChannelsEnabled()) return;

        List<ChannelDefinition> definitions = config.getPredefinedChannels();
        if (definitions == null) return;
        int defaultMaxParticipants = config.getMaxParticipantsPerChannel();
        for (ChannelDefinition definition : definitions) {
            if (!ChannelDefinition.isValidId(definition.id)) {
                logger.w("Ignoring predefined channel with an unusable id: " + definition.id);
                continue;
            }
            if (channels.containsKey(definition.id)) {
                logger.w("Ignoring duplicate predefined channel: " + definition.id);
                continue;
            }
            channels.put(definition.id, Channel.fromDefinition(definition, defaultMaxParticipants));
        }
    }

    /** Why {@link #createDynamic} refused, when it did. */
    public enum Failure {
        /** The requested id is taken - §4's 409. */
        DUPLICATE_ID,
        /** {@code getMaxDynamicChannels()} is already reached. */
        LIMIT_REACHED
    }

    /** The outcome of {@link #createDynamic}: exactly one of the two fields is set. */
    public static final class Creation {
        public final @Nullable Channel channel;
        public final @Nullable Failure failure;

        private Creation(@Nullable Channel channel, @Nullable Failure failure) {
            this.channel = channel;
            this.failure = failure;
        }
    }

    /**
     * Creates a channel that exists only for as long as it is used: not persistent, so
     * {@link #collectIdleChannels} may take it away, and deletable, so
     * {@code DELETE /api/channels/{id}} may.
     *
     * @param requestedId the id from the request body, or {@code null} to have one generated
     * @param template    everything else the request asked for; its own id is not used
     */
    public synchronized @NotNull Creation createDynamic(@Nullable String requestedId,
                                                        @NotNull ChannelDefinition template,
                                                        @NotNull SHTTPSConfig config) {
        if (countDynamic() >= config.getMaxDynamicChannels()) {
            return new Creation(null, Failure.LIMIT_REACHED);
        }

        String id;
        if (requestedId != null) {
            if (channels.containsKey(requestedId)) {
                return new Creation(null, Failure.DUPLICATE_ID);
            }
            id = requestedId;
        } else {
            id = generateFreeId();
            if (id == null) {
                //ten collisions in a row on a 32 bit random id is not a state to invent an answer
                //for; the caller reports it as a conflict like any other unavailable id
                logger.w("Could not generate a free channel id");
                return new Creation(null, Failure.DUPLICATE_ID);
            }
        }

        int maxParticipants = template.maxParticipants != null && template.maxParticipants > 0 ?
                template.maxParticipants : config.getMaxParticipantsPerChannel();
        //note that the rate limit is passed through as it stands, without the "or zero" the line
        //above applies to the participant cap: zero participants is a channel nobody can use, zero
        //messages a second is a channel nobody throttles, so only null means "not set" here
        Channel channel = new Channel(id, template.mode, false, true,
                template.notifyPresence, template.guestsAllowed, maxParticipants,
                template.passwordHash, template.initialState, template.messageRateLimitPerSecond,
                template.binaryAllowed);
        channels.put(id, channel);
        return new Creation(channel, null);
    }

    private @Nullable String generateFreeId() {
        for (int attempt = 0; attempt < ID_GENERATION_ATTEMPTS; attempt++) {
            //short, url-safe and inside ChannelDefinition's id alphabet, like a participant id
            String id = String.format("c-%08x", RANDOM.nextInt());
            if (!channels.containsKey(id)) return id;
        }
        return null;
    }

    /**
     * Takes a channel out, so nothing can join it any more. <b>Disconnects nobody</b>: the caller
     * closes the sessions of the returned channel itself, outside this lock.
     *
     * @return the channel that was removed, or {@code null} if there was none by that id
     */
    public synchronized @Nullable Channel remove(@NotNull String id) {
        Channel channel = channels.get(id);
        if (channel == null) return null;
        channel.markRemoved();
        channels.remove(id, channel);
        return channel;
    }

    /**
     * Removes every dynamic channel that has had no participants for longer than
     * {@code getChannelIdleTimeoutMillis()}. Called on a timer by
     * {@link com.phlox.simpleserver.SHTTPSApp}, and directly by tests, which is why it takes the
     * configuration rather than reading a field: the timeout is re-read on every sweep, so editing
     * it takes effect without a restart.
     *
     * @return how many channels were collected
     */
    public synchronized int collectIdleChannels(@NotNull SHTTPSConfig config) {
        long timeout = config.getChannelIdleTimeoutMillis();
        //a non-positive timeout means "never collect", not "collect everything": nothing clamps the
        //setting, and an empty channel a client is about to reconnect to is not garbage
        if (timeout <= 0) return 0;

        long now = System.currentTimeMillis();
        int collected = 0;
        for (Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Channel> entry = it.next();
            Channel channel = entry.getValue();
            if (channel.persistent) continue;
            if (channel.participantsCount() > 0) continue;
            if (now - channel.getLastActivityAt() <= timeout) continue;
            //re-checks emptiness under the channel's own lock: somebody may have been admitted
            //between the count above and here
            if (!channel.markRemovedIfEmpty()) continue;
            it.remove();
            collected++;
            logger.i("Collected idle channel: " + entry.getKey());
        }
        return collected;
    }

    public @Nullable Channel get(@NotNull String id) {
        return channels.get(id);
    }

    public @NotNull Collection<Channel> all() {
        return new ArrayList<>(channels.values());
    }

    public int countPredefined() {
        int count = 0;
        for (Channel channel : channels.values()) {
            if (channel.persistent) count++;
        }
        return count;
    }

    public int countDynamic() {
        int count = 0;
        for (Channel channel : channels.values()) {
            if (!channel.persistent) count++;
        }
        return count;
    }

    public int totalParticipants() {
        int count = 0;
        for (Channel channel : channels.values()) {
            count += channel.participantsCount();
        }
        return count;
    }

    /** Disconnects everybody and forgets every channel; used when the server stops. */
    public void closeAll(int code, @NotNull String reason) {
        for (Channel channel : channels.values()) {
            channel.closeAllSessions(code, reason);
        }
        channels.clear();
    }
}
