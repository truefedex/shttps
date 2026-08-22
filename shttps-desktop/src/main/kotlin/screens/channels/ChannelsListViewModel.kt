package com.phlox.simpleserver.screens.channels

import androidx.lifecycle.ViewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.channels.ChannelDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChannelsListState(
    val allowDynamicChannelCreation: Boolean = false,
    val maxDynamicChannels: Int = 20,
    val maxParticipantsPerChannel: Int = 50,
    /** Stored in milliseconds, shown in minutes - see [updateLimits]. */
    val idleTimeoutMinutes: Int = 10,
    val messageRateLimitPerSecond: Int = 20,
    val maxMessageKiB: Int = 1024,
    val channels: List<ChannelDefinition> = emptyList(),
)

/**
 * The server-wide channel settings plus the list of predefined channels.
 *
 * Every setting here is read when the server starts - the predefined channels are rebuilt in
 * `SHTTPSApp.startServer()`, the idle sweep is scheduled there, and the WebSocket message size caps
 * are read once when the endpoint is constructed - so each change asks for a restart through
 * [onRequestServerRestart], which is a no-op while the server is stopped.
 */
class ChannelsListViewModel(
    private val config: AppConfig,
    private val onRequestServerRestart: () -> Unit
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChannelsListState())
    val uiState: StateFlow<ChannelsListState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.value = ChannelsListState(
            allowDynamicChannelCreation = config.getAllowDynamicChannelCreation(),
            maxDynamicChannels = config.getMaxDynamicChannels(),
            maxParticipantsPerChannel = config.getMaxParticipantsPerChannel(),
            idleTimeoutMinutes = config.getChannelIdleTimeoutMillis() / 60000,
            messageRateLimitPerSecond = config.getChannelMessageRateLimitPerSecond(),
            maxMessageKiB = config.getChannelMaxMessageBytes() / 1024,
            channels = config.getPredefinedChannels() ?: emptyList()
        )
    }

    fun updateAllowDynamicChannelCreation(allow: Boolean) {
        config.setAllowDynamicChannelCreation(allow)
        _uiState.value = _uiState.value.copy(allowDynamicChannelCreation = allow)
        onRequestServerRestart()
    }

    /**
     * The idle timeout is asked for in minutes and the message size in KiB, because that is how a
     * human thinks about "how long may a room stand empty" and "how big a blob may pass" - the
     * configuration keeps them in milliseconds and bytes.
     */
    fun updateLimits(
        maxDynamicChannels: Int,
        maxParticipantsPerChannel: Int,
        idleTimeoutMinutes: Int,
        messageRateLimitPerSecond: Int,
        maxMessageKiB: Int
    ) {
        config.setMaxDynamicChannels(maxDynamicChannels)
        config.setMaxParticipantsPerChannel(maxParticipantsPerChannel)
        config.setChannelIdleTimeoutMillis(idleTimeoutMinutes * 60000)
        config.setChannelMessageRateLimitPerSecond(messageRateLimitPerSecond)
        config.setChannelMaxMessageBytes(maxMessageKiB * 1024)
        _uiState.value = _uiState.value.copy(
            maxDynamicChannels = maxDynamicChannels,
            maxParticipantsPerChannel = maxParticipantsPerChannel,
            idleTimeoutMinutes = idleTimeoutMinutes,
            messageRateLimitPerSecond = messageRateLimitPerSecond,
            maxMessageKiB = maxMessageKiB
        )
        onRequestServerRestart()
    }

    fun refreshChannels() {
        _uiState.value = _uiState.value.copy(
            channels = config.getPredefinedChannels() ?: emptyList()
        )
    }
}
