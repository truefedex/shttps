package com.phlox.simpleserver.screens.channels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The five server-wide channel limits. The idle timeout is in minutes and the message size in KiB;
 * the view model converts both to what the configuration stores.
 */
@Composable
fun ChannelLimitsDialog(
    currentMaxDynamicChannels: Int,
    currentMaxParticipantsPerChannel: Int,
    currentIdleTimeoutMinutes: Int,
    currentMessageRateLimitPerSecond: Int,
    currentMaxMessageKiB: Int,
    onSave: (
        maxDynamicChannels: Int,
        maxParticipantsPerChannel: Int,
        idleTimeoutMinutes: Int,
        messageRateLimitPerSecond: Int,
        maxMessageKiB: Int
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var maxDynamicChannelsText by remember { mutableStateOf(currentMaxDynamicChannels.toString()) }
    var maxParticipantsText by remember { mutableStateOf(currentMaxParticipantsPerChannel.toString()) }
    var idleTimeoutText by remember { mutableStateOf(currentIdleTimeoutMinutes.toString()) }
    var messageRateLimitText by remember { mutableStateOf(currentMessageRateLimitPerSecond.toString()) }
    var maxMessageSizeText by remember { mutableStateOf(currentMaxMessageKiB.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val invalidMessage = stringResource(Res.string.channel_limits_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.channel_limits)) },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                Text(stringResource(Res.string.channel_limits_description))
                Spacer(modifier = Modifier.height(12.dp))

                NumberField(
                    value = maxDynamicChannelsText,
                    onValueChange = { maxDynamicChannelsText = it; error = null },
                    label = stringResource(Res.string.channel_max_dynamic_channels),
                    hint = stringResource(Res.string.channel_max_dynamic_channels_hint)
                )
                NumberField(
                    value = maxParticipantsText,
                    onValueChange = { maxParticipantsText = it; error = null },
                    label = stringResource(Res.string.channel_max_participants_per_channel),
                    hint = stringResource(Res.string.channel_max_participants_per_channel_hint)
                )
                NumberField(
                    value = idleTimeoutText,
                    onValueChange = { idleTimeoutText = it; error = null },
                    label = stringResource(Res.string.channel_idle_timeout),
                    hint = stringResource(Res.string.channel_idle_timeout_hint)
                )
                NumberField(
                    value = messageRateLimitText,
                    onValueChange = { messageRateLimitText = it; error = null },
                    label = stringResource(Res.string.channel_message_rate_limit_global),
                    hint = stringResource(Res.string.channel_message_rate_limit_global_hint)
                )
                NumberField(
                    value = maxMessageSizeText,
                    onValueChange = { maxMessageSizeText = it; error = null },
                    label = stringResource(Res.string.channel_max_message_size),
                    hint = stringResource(Res.string.channel_max_message_size_hint)
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val maxDynamicChannels = maxDynamicChannelsText.trim().toIntOrNull()
                    val maxParticipants = maxParticipantsText.trim().toIntOrNull()
                    val idleTimeoutMinutes = idleTimeoutText.trim().toIntOrNull()
                    val messageRateLimit = messageRateLimitText.trim().toIntOrNull()
                    val maxMessageKiB = maxMessageSizeText.trim().toIntOrNull()
                    //a channel nobody may join and a message nobody may send are not settings
                    //anybody wants, so those two have to be positive; the rest may be zero
                    if (maxDynamicChannels == null || maxDynamicChannels < 0 ||
                        maxParticipants == null || maxParticipants < 1 ||
                        idleTimeoutMinutes == null || idleTimeoutMinutes < 0 ||
                        messageRateLimit == null || messageRateLimit < 0 ||
                        maxMessageKiB == null || maxMessageKiB < 1
                    ) {
                        error = invalidMessage
                        return@Button
                    }
                    onSave(
                        maxDynamicChannels, maxParticipants, idleTimeoutMinutes,
                        messageRateLimit, maxMessageKiB
                    )
                    onDismiss()
                }
            ) {
                Text(stringResource(Res.string.set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String
) {
    TextField(
        value = value,
        onValueChange = { if (it.all { char -> char.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    Text(
        text = hint,
        fontSize = 11.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(12.dp))
}
