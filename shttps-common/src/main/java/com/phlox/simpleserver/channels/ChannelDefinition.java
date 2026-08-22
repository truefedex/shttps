package com.phlox.simpleserver.channels;

import com.phlox.simpleserver.utils.Utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * A channel as it is written down in {@link com.phlox.simpleserver.SHTTPSConfig#getPredefinedChannels()}.
 * Channels built from these definitions are persistent: they exist for as long as the server runs,
 * whether or not anybody is connected, and are not removable through the API.
 * <p>
 * The channel secret is kept here <b>hashed</b>, never in plain text - the predefined channel list
 * travels through configuration export/import like every other setting, and a room PIN has no
 * business being readable there. Hashing is {@link #hashChannelPassword(String)}, which is the
 * project's one password hashing function, applied to the plain text a client sends in
 * {@code ?password=}.
 */
public class ChannelDefinition implements Serializable {
    public static final String FIELD_ID = "id";
    public static final String FIELD_MODE = "mode";
    public static final String FIELD_INITIAL_STATE = "initialState";
    public static final String FIELD_MAX_PARTICIPANTS = "maxParticipants";
    public static final String FIELD_DELETABLE = "deletable";
    public static final String FIELD_NOTIFY_PRESENCE = "notifyPresence";
    public static final String FIELD_GUESTS_ALLOWED = "guestsAllowed";
    public static final String FIELD_MESSAGE_RATE_LIMIT = "messageRateLimitPerSecond";
    public static final String FIELD_BINARY_ALLOWED = "binaryAllowed";
    public static final String FIELD_PASSWORD_HASH = "password_hash";

    /** Channel ids end up in URLs, so they stay to a conservative, path-safe alphabet. */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public final @NotNull String id;
    public final @NotNull Channel.Mode mode;
    /** STATE only, unused while ECHO is the only implemented mode. */
    public final @Nullable JSONObject initialState;
    /** {@code null} = use {@code SHTTPSConfig.getMaxParticipantsPerChannel()}. */
    public final @Nullable Integer maxParticipants;
    public final boolean deletable;
    public final boolean notifyPresence;
    public final boolean guestsAllowed;
    public final @Nullable String passwordHash;
    /**
     * This channel's own incoming-message limit per connection, per second.
     * <p>
     * {@code null} - the usual case - means the channel does not care and the server-wide
     * {@code SHTTPSConfig.getChannelMessageRateLimitPerSecond()} applies. <b>Zero is not the same as
     * null</b>: it is an explicit "no limit on this channel", for the one that carries a firehose
     * while the rest of the server stays protected. Anything less than one has that same meaning.
     * <p>
     * Note the contrast with {@link #maxParticipants}, where a zero <i>is</i> read as "unset": there
     * is no such thing as a channel nobody may join, but there very much is one nobody throttles.
     */
    public final @Nullable Integer messageRateLimitPerSecond;
    /**
     * Whether this channel also relays raw binary frames, off by default.
     * <p>
     * Deliberately a flag rather than a {@link Channel.Mode} of its own: a channel that ships blobs
     * almost always wants the JSON protocol alongside them - "somebody muted" next to the audio
     * frames - and a mode would make the two mutually exclusive. For the same reason it is not tied
     * to {@link Channel.Mode#ECHO}: a STATE channel that relays images past its document is a
     * perfectly sensible thing to ask for, and the relay never touches the document.
     */
    public final boolean binaryAllowed;

    public ChannelDefinition(@NotNull String id, @NotNull Channel.Mode mode,
                             @Nullable JSONObject initialState, @Nullable Integer maxParticipants,
                             boolean deletable, boolean notifyPresence,
                             boolean guestsAllowed, @Nullable String passwordHash,
                             @Nullable Integer messageRateLimitPerSecond, boolean binaryAllowed) {
        this.id = id;
        this.mode = mode;
        this.initialState = initialState;
        this.maxParticipants = maxParticipants;
        this.deletable = deletable;
        this.notifyPresence = notifyPresence;
        this.guestsAllowed = guestsAllowed;
        this.passwordHash = passwordHash;
        this.messageRateLimitPerSecond = messageRateLimitPerSecond;
        this.binaryAllowed = binaryAllowed;
    }

    /** A text-only channel, which is what a channel is unless it says otherwise. */
    public ChannelDefinition(@NotNull String id, @NotNull Channel.Mode mode,
                             @Nullable JSONObject initialState, @Nullable Integer maxParticipants,
                             boolean deletable, boolean notifyPresence,
                             boolean guestsAllowed, @Nullable String passwordHash,
                             @Nullable Integer messageRateLimitPerSecond) {
        this(id, mode, initialState, maxParticipants, deletable, notifyPresence, guestsAllowed,
                passwordHash, messageRateLimitPerSecond, false);
    }

    /** A channel that leaves the message rate limit to the server-wide setting. */
    public ChannelDefinition(@NotNull String id, @NotNull Channel.Mode mode,
                             @Nullable JSONObject initialState, @Nullable Integer maxParticipants,
                             boolean deletable, boolean notifyPresence,
                             boolean guestsAllowed, @Nullable String passwordHash) {
        this(id, mode, initialState, maxParticipants, deletable, notifyPresence,
                guestsAllowed, passwordHash, null);
    }

    /** An ECHO channel with everything at its default - what a settings dialog starts from. */
    public ChannelDefinition(@NotNull String id) {
        this(id, Channel.Mode.ECHO, null, null, false, true, true, null, null, false);
    }

    public static boolean isValidId(@Nullable String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /**
     * The hash to store for a channel password typed by a human. Deliberately the same function
     * the rest of the project uses for user passwords ({@code BasicAuthManager} applies both
     * stages to the plain text it reads out of the Authorization header) - a channel password is a
     * lighter-weight secret than a user's, but there is no reason to protect it more weakly.
     */
    public static @NotNull String hashChannelPassword(@NotNull String plaintext) {
        return Utils.sha256(Utils.hashFNV1a32(plaintext));
    }

    public @NotNull JSONObject serialize() {
        JSONObject object = new JSONObject();
        object.put(FIELD_ID, id);
        object.put(FIELD_MODE, mode.name());
        if (initialState != null) {
            object.put(FIELD_INITIAL_STATE, initialState);
        }
        if (maxParticipants != null) {
            object.put(FIELD_MAX_PARTICIPANTS, maxParticipants.intValue());
        }
        object.put(FIELD_DELETABLE, deletable);
        object.put(FIELD_NOTIFY_PRESENCE, notifyPresence);
        object.put(FIELD_GUESTS_ALLOWED, guestsAllowed);
        //null only, never "null or zero": an explicit zero is a decision to leave this channel
        //unthrottled, and dropping it here would quietly hand the channel back to the server default
        //the next time the configuration is exported and imported
        if (messageRateLimitPerSecond != null) {
            object.put(FIELD_MESSAGE_RATE_LIMIT, messageRateLimitPerSecond.intValue());
        }
        //written unconditionally, like the other booleans, rather than only when true: the stored
        //list is read back by a deserializer that defaults it to false anyway, and a settings screen
        //editing this JSON by hand should see the field it can turn on
        object.put(FIELD_BINARY_ALLOWED, binaryAllowed);
        if (passwordHash != null) {
            object.put(FIELD_PASSWORD_HASH, passwordHash);
        }
        return object;
    }

    public static @NotNull ChannelDefinition deserialize(@NotNull JSONObject object) throws JSONException {
        String id = object.getString(FIELD_ID);
        Channel.Mode mode = Channel.Mode.ECHO;
        String modeName = object.optString(FIELD_MODE, null);
        if (modeName != null && !modeName.isEmpty()) {
            try {
                mode = Channel.Mode.valueOf(modeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new JSONException("Unknown channel mode: " + modeName);
            }
        }
        JSONObject initialState = object.optJSONObject(FIELD_INITIAL_STATE);
        Integer maxParticipants = (object.has(FIELD_MAX_PARTICIPANTS) && !object.isNull(FIELD_MAX_PARTICIPANTS)) ?
                Integer.valueOf(object.getInt(FIELD_MAX_PARTICIPANTS)) : null;
        Integer messageRateLimit = (object.has(FIELD_MESSAGE_RATE_LIMIT) && !object.isNull(FIELD_MESSAGE_RATE_LIMIT)) ?
                Integer.valueOf(object.getInt(FIELD_MESSAGE_RATE_LIMIT)) : null;
        String passwordHash = (object.has(FIELD_PASSWORD_HASH) && !object.isNull(FIELD_PASSWORD_HASH)) ?
                object.getString(FIELD_PASSWORD_HASH) : null;
        if (passwordHash != null && passwordHash.isEmpty()) {
            passwordHash = null;
        }
        return new ChannelDefinition(id, mode, initialState, maxParticipants,
                object.optBoolean(FIELD_DELETABLE, false),
                object.optBoolean(FIELD_NOTIFY_PRESENCE, true),
                object.optBoolean(FIELD_GUESTS_ALLOWED, true),
                passwordHash, messageRateLimit,
                //absent in every channel stored before binary relay existed, and off is what those
                //channels were - so no configuration migration is needed to read them
                object.optBoolean(FIELD_BINARY_ALLOWED, false));
    }
}
