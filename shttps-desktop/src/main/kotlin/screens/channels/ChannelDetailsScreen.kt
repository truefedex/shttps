package com.phlox.simpleserver.screens.channels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.phlox.simpleserver.theme.AppTheme
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.channels.Channel
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChannelDetailsScreen(
    viewModel: ChannelDetailsViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val uiState = viewModel.uiState.value
    val scrollState = rememberScrollState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenuExpanded by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back button and menu
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
                    text = stringResource(Res.string.channel_details),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )

                if (viewModel.canDelete()) {
                    Box {
                        IconButton(onClick = { showMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.cd_menu),
                                tint = MaterialTheme.colors.onBackground
                            )
                        }
                        DropdownMenu(
                            expanded = showMenuExpanded,
                            onDismissRequest = { showMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    showMenuExpanded = false
                                    showDeleteDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colors.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.delete_channel))
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.channel_details_subtitle),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    // Channel id
                    OutlinedTextField(
                        value = uiState.channelId,
                        onValueChange = { viewModel.updateChannelId(it) },
                        label = { Text(stringResource(Res.string.channel_id)) },
                        placeholder = { Text(stringResource(Res.string.channel_id_hint)) },
                        isError = uiState.idError != null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (uiState.idError != null) {
                        Text(
                            text = when (uiState.idError) {
                                ChannelIdError.INVALID -> stringResource(Res.string.channel_id_invalid)
                                ChannelIdError.DUPLICATE -> stringResource(Res.string.channel_id_already_exists)
                            },
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mode
                    Text(
                        text = stringResource(Res.string.channel_mode),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = uiState.mode == Channel.Mode.ECHO,
                            onClick = { viewModel.updateMode(Channel.Mode.ECHO) }
                        )
                        Text(stringResource(Res.string.channel_mode_echo))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = uiState.mode == Channel.Mode.STATE,
                            onClick = { viewModel.updateMode(Channel.Mode.STATE) }
                        )
                        Text(stringResource(Res.string.channel_mode_state))
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 1.dp,
                        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = if (uiState.mode == Channel.Mode.STATE) {
                                stringResource(Res.string.channel_mode_state_description)
                            } else {
                                stringResource(Res.string.channel_mode_echo_description)
                            },
                            modifier = Modifier.padding(12.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                        )
                    }

                    //the initial document belongs to STATE channels only; an ECHO channel keeps no
                    //state to seed
                    AnimatedVisibility(visible = uiState.mode == Channel.Mode.STATE) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = uiState.initialState,
                                onValueChange = { viewModel.updateInitialState(it) },
                                label = { Text(stringResource(Res.string.channel_initial_state)) },
                                placeholder = { Text(stringResource(Res.string.channel_initial_state_hint)) },
                                isError = uiState.initialStateError,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 8
                            )
                            if (uiState.initialStateError) {
                                Text(
                                    text = stringResource(Res.string.channel_initial_state_invalid),
                                    color = MaterialTheme.colors.error,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = stringResource(Res.string.channel_initial_state_description),
                                fontSize = 11.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Max participants
                    OutlinedTextField(
                        value = uiState.maxParticipants,
                        onValueChange = { viewModel.updateMaxParticipants(it) },
                        label = { Text(stringResource(Res.string.channel_max_participants)) },
                        isError = uiState.maxParticipantsError,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (uiState.maxParticipantsError) {
                        Text(
                            text = stringResource(Res.string.channel_max_participants_invalid),
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = stringResource(Res.string.channel_max_participants_description),
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Message rate limit
                    OutlinedTextField(
                        value = uiState.messageRateLimit,
                        onValueChange = { viewModel.updateMessageRateLimit(it) },
                        label = { Text(stringResource(Res.string.channel_message_rate_limit)) },
                        isError = uiState.messageRateLimitError,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (uiState.messageRateLimitError) {
                        Text(
                            text = stringResource(Res.string.channel_message_rate_limit_invalid),
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = stringResource(Res.string.channel_message_rate_limit_description),
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Flags
                    ChannelFlag(
                        checked = uiState.guestsAllowed,
                        onCheckedChange = { viewModel.updateGuestsAllowed(it) },
                        label = stringResource(Res.string.channel_guests_allowed),
                        description = stringResource(Res.string.channel_guests_allowed_description)
                    )
                    ChannelFlag(
                        checked = uiState.notifyPresence,
                        onCheckedChange = { viewModel.updateNotifyPresence(it) },
                        label = stringResource(Res.string.channel_notify_presence),
                        description = null
                    )
                    ChannelFlag(
                        checked = uiState.binaryAllowed,
                        onCheckedChange = { viewModel.updateBinaryAllowed(it) },
                        label = stringResource(Res.string.channel_binary_allowed),
                        description = stringResource(Res.string.channel_binary_allowed_description)
                    )
                    ChannelFlag(
                        checked = uiState.deletable,
                        onCheckedChange = { viewModel.updateDeletable(it) },
                        label = stringResource(Res.string.channel_deletable),
                        description = stringResource(Res.string.channel_deletable_description)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Password
                    Text(
                        text = stringResource(Res.string.channel_password),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(Res.string.channel_password_description),
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (uiState.hasStoredPassword) {
                            stringResource(Res.string.channel_password_set)
                        } else {
                            stringResource(Res.string.channel_password_not_set)
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                    )
                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = { viewModel.updatePassword(it) },
                        label = { Text(stringResource(Res.string.channel_password)) },
                        placeholder = { Text(stringResource(Res.string.channel_password_hint)) },
                        enabled = !uiState.removePassword,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (uiState.hasStoredPassword) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.removePassword,
                                onCheckedChange = { viewModel.updateRemovePassword(it) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.channel_remove_password))
                        }
                    }
                }
            }

            // Save button
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (viewModel.saveChannel()) {
                        onSaveSuccess()
                    }
                }
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.save))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(Res.string.delete_channel)) },
            text = {
                Text(
                    stringResource(
                        Res.string.delete_channel_confirmation,
                        //the stored id, not the field: an edited-but-unsaved rename would otherwise
                        //name a channel that delete is not going to touch
                        viewModel.storedId() ?: uiState.channelId
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.deleteChannel()) {
                            showDeleteDialog = false
                            onSaveSuccess()
                        }
                    }
                ) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ChannelFlag(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String?
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, modifier = Modifier.weight(1f))
        }
        if (description != null) {
            Text(
                text = description,
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 48.dp)
            )
        }
    }
}

@Preview
@Composable
fun ChannelFlagPreview() {
    AppTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                var readOnly by remember { mutableStateOf(true) }
                ChannelFlag(
                    checked = readOnly,
                    onCheckedChange = { readOnly = it },
                    label = "Read only",
                    description = "Clients of this channel can download files but not upload them"
                )
                Spacer(modifier = Modifier.height(12.dp))
                var enabled by remember { mutableStateOf(false) }
                ChannelFlag(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    label = "Enabled",
                    description = null
                )
            }
        }
    }
}
