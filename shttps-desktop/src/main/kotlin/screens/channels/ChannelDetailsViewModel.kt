package com.phlox.simpleserver.screens.channels

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.channels.Channel
import com.phlox.simpleserver.channels.ChannelDefinition
import org.json.JSONException
import org.json.JSONObject

/** The two ways a typed channel id can be refused, so the screen can say which one happened. */
enum class ChannelIdError { INVALID, DUPLICATE }

data class ChannelDetailsState(
    val channelId: String = "",
    val mode: Channel.Mode = Channel.Mode.ECHO,
    val initialState: String = "",
    val maxParticipants: String = "",
    val messageRateLimit: String = "",
    //the defaults of ChannelDefinition(String): an open, presence-announcing ECHO channel
    val guestsAllowed: Boolean = true,
    val notifyPresence: Boolean = true,
    val binaryAllowed: Boolean = false,
    val deletable: Boolean = false,
    val password: String = "",
    /** Whether the stored channel carries a password hash - it can never be shown, only replaced. */
    val hasStoredPassword: Boolean = false,
    val removePassword: Boolean = false,
    val idError: ChannelIdError? = null,
    val initialStateError: Boolean = false,
    val maxParticipantsError: Boolean = false,
    val messageRateLimitError: Boolean = false,
)

/**
 * Editor for one predefined channel.
 *
 * The channel is looked up by id rather than by list position, matching the Android app and the way
 * the API addresses a channel. Saving rewrites the whole predefined-channel list and asks for a
 * server restart, because the list is turned into live channels once, when the server starts.
 */
class ChannelDetailsViewModel(
    private val config: AppConfig,
    channelId: String?,
    private val onRequestServerRestart: () -> Unit
) : ViewModel() {

    /** The id this editor opened with; `null` for a channel that does not exist yet. */
    private var originalId: String? = null
    /** The stored channel, when editing one - the only place the existing password hash lives. */
    private var storedChannel: ChannelDefinition? = null

    private val _uiState = mutableStateOf(ChannelDetailsState())
    val uiState: State<ChannelDetailsState> = _uiState

    init {
        val stored = channelId?.let { id ->
            config.getPredefinedChannels()?.firstOrNull { it.id == id }
        }
        if (stored != null) {
            originalId = stored.id
            storedChannel = stored
            _uiState.value = ChannelDetailsState(
                channelId = stored.id,
                mode = stored.mode,
                initialState = stored.initialState?.toString() ?: "",
                maxParticipants = stored.maxParticipants?.toString() ?: "",
                //printed even when it is zero: that zero is this channel's decision to stay
                //unthrottled, and an empty field would read as "use the server default"
                messageRateLimit = stored.messageRateLimitPerSecond?.toString() ?: "",
                guestsAllowed = stored.guestsAllowed,
                notifyPresence = stored.notifyPresence,
                binaryAllowed = stored.binaryAllowed,
                deletable = stored.deletable,
                hasStoredPassword = stored.passwordHash != null
            )
        } else {
            //a new channel, or one that disappeared while this screen was away: start from the
            //defaults with whatever id we were given already filled in
            _uiState.value = ChannelDetailsState(channelId = channelId ?: "")
        }
    }

    fun canDelete(): Boolean = originalId != null

    /**
     * The id the channel is stored under, which is what a delete removes - not the text currently in
     * the id field, which may already have been edited into a rename.
     */
    fun storedId(): String? = originalId

    fun updateChannelId(value: String) {
        _uiState.value = _uiState.value.copy(channelId = value, idError = null)
    }

    fun updateMode(mode: Channel.Mode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun updateInitialState(value: String) {
        _uiState.value = _uiState.value.copy(initialState = value, initialStateError = false)
    }

    fun updateMaxParticipants(value: String) {
        _uiState.value = _uiState.value.copy(maxParticipants = value, maxParticipantsError = false)
    }

    fun updateMessageRateLimit(value: String) {
        _uiState.value = _uiState.value.copy(messageRateLimit = value, messageRateLimitError = false)
    }

    fun updateGuestsAllowed(value: Boolean) {
        _uiState.value = _uiState.value.copy(guestsAllowed = value)
    }

    fun updateNotifyPresence(value: Boolean) {
        _uiState.value = _uiState.value.copy(notifyPresence = value)
    }

    fun updateBinaryAllowed(value: Boolean) {
        _uiState.value = _uiState.value.copy(binaryAllowed = value)
    }

    fun updateDeletable(value: Boolean) {
        _uiState.value = _uiState.value.copy(deletable = value)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value)
    }

    /** A password that is being removed is not also being replaced, so the field is cleared. */
    fun updateRemovePassword(value: Boolean) {
        _uiState.value = _uiState.value.copy(
            removePassword = value,
            password = if (value) "" else _uiState.value.password
        )
    }

    fun saveChannel(): Boolean {
        val state = _uiState.value
        val id = state.channelId.trim()
        if (!ChannelDefinition.isValidId(id)) {
            _uiState.value = state.copy(idError = ChannelIdError.INVALID)
            return false
        }

        val channels = config.getPredefinedChannels()?.toMutableList() ?: mutableListOf()
        if (channels.any { it.id == id && it.id != originalId }) {
            _uiState.value = state.copy(idError = ChannelIdError.DUPLICATE)
            return false
        }

        var initialState: JSONObject? = null
        if (state.mode == Channel.Mode.STATE) {
            val initialStateText = state.initialState.trim()
            if (initialStateText.isNotEmpty()) {
                initialState = try {
                    JSONObject(initialStateText)
                } catch (e: JSONException) {
                    _uiState.value = state.copy(initialStateError = true)
                    return false
                }
            }
        }

        //empty and zero both mean "use the server-wide limit" here - there is no such thing as a
        //channel nobody may join
        val maxParticipantsText = state.maxParticipants.trim()
        val maxParticipants: Int? = if (maxParticipantsText.isEmpty()) {
            null
        } else {
            val parsed = maxParticipantsText.toIntOrNull()
            if (parsed == null || parsed < 0) {
                _uiState.value = state.copy(maxParticipantsError = true)
                return false
            }
            if (parsed == 0) null else parsed
        }

        //unlike the participant cap, zero is a value rather than an absence: it asks for this one
        //channel to go unthrottled while the server-wide limit still protects the rest
        val messageRateLimitText = state.messageRateLimit.trim()
        val messageRateLimit: Int? = if (messageRateLimitText.isEmpty()) {
            null
        } else {
            val parsed = messageRateLimitText.toIntOrNull()
            if (parsed == null || parsed < 0) {
                _uiState.value = state.copy(messageRateLimitError = true)
                return false
            }
            parsed
        }

        //the stored hash cannot be shown, so an untouched field must not be read as "no password" -
        //that would quietly unlock the channel on any other edit
        val passwordHash: String? = when {
            state.removePassword -> null
            state.password.isNotEmpty() -> ChannelDefinition.hashChannelPassword(state.password)
            else -> storedChannel?.passwordHash
        }

        val newChannel = ChannelDefinition(
            id,
            state.mode,
            initialState,
            maxParticipants,
            state.deletable,
            state.notifyPresence,
            state.guestsAllowed,
            passwordHash,
            messageRateLimit,
            state.binaryAllowed
        )

        //dropping the old entry by id covers a rename too, where the id being replaced is not the
        //one being written
        val replacedId = originalId ?: id
        val updated = channels.filter { it.id != replacedId }.toMutableList()
        updated.add(newChannel)
        config.setPredefinedChannels(updated)
        onRequestServerRestart()
        return true
    }

    fun deleteChannel(): Boolean {
        val original = originalId ?: return false
        val channels = config.getPredefinedChannels() ?: return false
        config.setPredefinedChannels(channels.filter { it.id != original })
        onRequestServerRestart()
        return true
    }
}
