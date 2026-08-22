package com.phlox.simpleserver.screens.channels

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.phlox.simpleserver.channels.Channel
import com.phlox.simpleserver.channels.ChannelDefinition
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChannelsListScreen(
    viewModel: ChannelsListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToChannelDetails: (String?) -> Unit,
    navController: NavHostController? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var showLimitsDialog by remember { mutableStateOf(false) }
    //local rather than the home screen's message dialog: that one is only composed on the home route
    var showDynamicCreationInfo by remember { mutableStateOf(false) }

    // Refresh when returning from the editor
    if (navController != null) {
        val backStackEntry = remember { navController.currentBackStackEntry!! }
        val savedStateHandle = backStackEntry.savedStateHandle
        val channelsWasEdited by savedStateHandle.getStateFlow("channelsWasEdited", initialValue = false)
            .collectAsState()

        LaunchedEffect(channelsWasEdited) {
            if (channelsWasEdited) {
                viewModel.refreshChannels()
                savedStateHandle["channelsWasEdited"] = false
            }
        }
    }

    val dynamicCreationTitle = stringResource(Res.string.allow_dynamic_channel_creation)
    val dynamicCreationInfo = stringResource(Res.string.allow_dynamic_channel_creation_info)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.cd_back),
                        tint = MaterialTheme.colors.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.channels_configuration),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // Let clients create channels
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.allowDynamicChannelCreation,
                    onCheckedChange = { viewModel.updateAllowDynamicChannelCreation(it) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dynamicCreationTitle,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showDynamicCreationInfo = true }) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colors.onBackground
                    )
                }
            }

            // Limits
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { showLimitsDialog = true }
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.channel_limits),
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            Res.string.channel_limits_summary,
                            uiState.maxDynamicChannels,
                            uiState.maxParticipantsPerChannel,
                            uiState.messageRateLimitPerSecond,
                            uiState.maxMessageKiB
                        ),
                        fontWeight = FontWeight.Light
                    )
                }
            }

            // Predefined channels section
            Text(
                text = stringResource(Res.string.predefined_channels),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(Res.string.channels_screen_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                elevation = 2.dp
            ) {
                if (uiState.channels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.no_channels_configured),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        //keyed by id rather than position: a channel is always addressed by its id,
                        //and the editor looks it up the same way
                        items(uiState.channels, key = { it.id }) { channel ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { onNavigateToChannelDetails(channel.id) },
                                elevation = 1.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = channel.id,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = channelSummary(channel),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Add channel button
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigateToChannelDetails(null) }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.add_channel))
            }
        }

        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 8.dp),
            adapter = rememberScrollbarAdapter(listState),
            style = ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.50f)
            )
        )
    }

    if (showDynamicCreationInfo) {
        AlertDialog(
            onDismissRequest = { showDynamicCreationInfo = false },
            title = { Text(dynamicCreationTitle) },
            text = { Text(dynamicCreationInfo) },
            confirmButton = {
                Button(onClick = { showDynamicCreationInfo = false }) {
                    Text(stringResource(Res.string.ok))
                }
            }
        )
    }

    if (showLimitsDialog) {
        ChannelLimitsDialog(
            currentMaxDynamicChannels = uiState.maxDynamicChannels,
            currentMaxParticipantsPerChannel = uiState.maxParticipantsPerChannel,
            currentIdleTimeoutMinutes = uiState.idleTimeoutMinutes,
            currentMessageRateLimitPerSecond = uiState.messageRateLimitPerSecond,
            currentMaxMessageKiB = uiState.maxMessageKiB,
            onSave = { maxDynamic, maxParticipants, idleMinutes, rateLimit, maxMessageKiB ->
                viewModel.updateLimits(
                    maxDynamic, maxParticipants, idleMinutes, rateLimit, maxMessageKiB
                )
            },
            onDismiss = { showLimitsDialog = false }
        )
    }
}

/** The one-line description under a channel's id in the list. */
@Composable
private fun channelSummary(channel: ChannelDefinition): String {
    val parts = mutableListOf<String>()
    parts.add(
        if (channel.mode == Channel.Mode.STATE) stringResource(Res.string.channel_mode_state)
        else stringResource(Res.string.channel_mode_echo)
    )
    parts.add(
        if (channel.guestsAllowed) stringResource(Res.string.channel_summary_guests_allowed)
        else stringResource(Res.string.channel_summary_registered_only)
    )
    if (channel.passwordHash != null) {
        parts.add(stringResource(Res.string.channel_summary_password))
    }
    if (channel.binaryAllowed) {
        parts.add(stringResource(Res.string.channel_summary_binary))
    }
    channel.maxParticipants?.let {
        parts.add(stringResource(Res.string.channel_summary_max_participants, it))
    }
    channel.messageRateLimitPerSecond?.let {
        //zero is a decision here, not an absence: this channel is deliberately unthrottled
        parts.add(
            if (it > 0) stringResource(Res.string.channel_summary_rate_limit, it)
            else stringResource(Res.string.channel_summary_unthrottled)
        )
    }
    return parts.joinToString(" • ")
}
