package com.phlox.simpleserver.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.kdroid.composetray.utils.getTrayWindowPosition
import com.phlox.simpleserver.screenshare.ScreenAccessRequest
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.add_to_whitelist
import com.phlox.simpleserver.shttps_desktop.generated.resources.allow
import com.phlox.simpleserver.shttps_desktop.generated.resources.deny
import com.phlox.simpleserver.shttps_desktop.generated.resources.more_connections_waiting
import com.phlox.simpleserver.shttps_desktop.generated.resources.new_connection
import com.phlox.simpleserver.shttps_desktop.generated.resources.screen_access_request
import com.phlox.simpleserver.shttps_desktop.generated.resources.screen_access_user
import com.phlox.simpleserver.shttps_desktop.generated.resources.screen_access_view
import com.phlox.simpleserver.shttps_desktop.generated.resources.screen_access_view_control
import com.phlox.simpleserver.shttps_desktop.generated.resources.view_only
import com.phlox.simpleserver.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit

//in the AWT logical units that getTrayWindowPosition() expects, like the main window size in Main.kt
private const val PROMPT_WIDTH = 340
private const val PROMPT_HEIGHT = 230
private const val PROMPT_GAP = 8
private const val SCREEN_MARGIN = 16

/**
 * How many prompts are shown as windows at once. The pending list itself is unbounded and never
 * expires (same as Android), but on Android any number of them collapse into the notification
 * shade, while here each one is an always-on-top window - a subnet scan would otherwise paper
 * over the whole screen. The rest wait their turn and are announced on the last visible prompt.
 */
private const val MAX_VISIBLE_PROMPTS = 3

/** One question waiting for the user, of either kind. */
private sealed class Prompt {
    abstract val key: Any

    data class NewClient(val ip: String) : Prompt() {
        override val key: Any get() = "client:$ip"
    }

    data class ScreenAccess(val request: ScreenAccessRequest) : Prompt() {
        override val key: Any get() = "screen:${request.id}"
    }
}

/**
 * Everything that asks the user whether to let someone in, as one stack of windows:
 * - the "Ask at runtime" half of the client whitelist: one window per unknown client that the
 *   server has just dropped, asking whether to let it in;
 * - "Ask before anyone sees the screen": one window per viewer the screen stream is holding at
 *   the door. These come first, because they expire and the client is sitting there waiting.
 *
 * Hosted in AppContent next to the tray, not inside the main window - it has to show up while
 * the app sits hidden in the tray, which is the normal state for a running server.
 */
@Composable
fun NewClientPrompts(
    controller: ClientApprovalController,
    screenAccessController: ScreenAccessApprovalController
) {
    val prompts = screenAccessController.prompts.map { Prompt.ScreenAccess(it) } +
            controller.prompts.map { Prompt.NewClient(it) }
    val visible = prompts.take(MAX_VISIBLE_PROMPTS)
    visible.forEachIndexed { index, prompt ->
        key(prompt.key) {
            val queued = if (index == visible.lastIndex) prompts.size - visible.size else 0
            when (prompt) {
                is Prompt.NewClient -> NewClientPromptWindow(
                    ip = prompt.ip,
                    slot = index,
                    queued = queued,
                    onAllow = { controller.allow(prompt.ip) },
                    onDeny = { controller.deny(prompt.ip) },
                    onAllowAndSave = { controller.allowAndSave(prompt.ip) }
                )
                is Prompt.ScreenAccess -> ScreenAccessPromptWindow(
                    request = prompt.request,
                    slot = index,
                    queued = queued,
                    onAllow = { screenAccessController.allow(prompt.request) },
                    onViewOnly = { screenAccessController.allowViewOnly(prompt.request) },
                    onDeny = { screenAccessController.deny(prompt.request) }
                )
            }
        }
    }
}

@Composable
private fun NewClientPromptWindow(
    ip: String,
    slot: Int,
    queued: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAllowAndSave: () -> Unit
) {
    PromptWindow(title = ip, slot = slot, onDeny = onDeny) {
        PromptHeader(stringResource(Res.string.new_connection), ip)
        QueuedNote(queued)

        Spacer(modifier = Modifier.weight(1f))

        AllowDenyRow(onAllow = onAllow, onDeny = onDeny)
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onAllowAndSave
        ) {
            Text(stringResource(Res.string.add_to_whitelist))
        }
    }
}

@Composable
private fun ScreenAccessPromptWindow(
    request: ScreenAccessRequest,
    slot: Int,
    queued: Int,
    onAllow: () -> Unit,
    onViewOnly: () -> Unit,
    onDeny: () -> Unit
) {
    PromptWindow(title = request.remoteAddress, slot = slot, onDeny = onDeny) {
        PromptHeader(stringResource(Res.string.screen_access_request), request.remoteAddress)
        request.userName?.let {
            Text(text = stringResource(Res.string.screen_access_user, it), fontSize = 14.sp)
        }
        Text(
            text = stringResource(
                if (request.includesControl) Res.string.screen_access_view_control
                else Res.string.screen_access_view
            ),
            fontSize = 14.sp
        )
        QueuedNote(queued)

        Spacer(modifier = Modifier.weight(1f))

        AllowDenyRow(onAllow = onAllow, onDeny = onDeny)
        //the only answer in between, and only when there is something to hold back
        if (request.includesControl) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onViewOnly
            ) {
                Text(stringResource(Res.string.view_only))
            }
        }
    }
}

@Composable
private fun PromptWindow(
    title: String,
    slot: Int,
    onDeny: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val windowState = rememberWindowState(
        width = PROMPT_WIDTH.dp,
        height = PROMPT_HEIGHT.dp,
        position = promptPosition(slot)
    )

    //answering an earlier prompt frees its slot, so the ones behind it move up
    LaunchedEffect(slot) {
        windowState.position = promptPosition(slot)
    }

    Window(
        //there is no title bar to close this from, but a window manager can still ask; treat that
        //as a refusal, the way swiping the Android notification away does
        onCloseRequest = onDeny,
        state = windowState,
        title = title,
        undecorated = true,
        resizable = false,
        alwaysOnTop = true,
        //a prompt that yanks the focus out of whatever is being typed is worse than one that just
        //sits on top. Non-focusable AWT windows still receive mouse clicks, so the buttons work
        focusable = false
    ) {
        AppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background,
                elevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp), content = content)
            }
        }
    }
}

@Composable
private fun PromptHeader(label: String, address: String) {
    Text(
        text = label,
        fontSize = 14.sp,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    )
    Text(
        text = address,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun QueuedNote(queued: Int) {
    if (queued > 0) {
        Text(
            text = stringResource(Res.string.more_connections_waiting, queued),
            fontSize = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun AllowDenyRow(onAllow: () -> Unit, onDeny: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            modifier = Modifier.weight(1f),
            onClick = onDeny
        ) {
            Text(stringResource(Res.string.deny))
        }
        Button(
            modifier = Modifier.weight(1f),
            onClick = onAllow
        ) {
            Text(stringResource(Res.string.allow))
        }
    }
}

/**
 * Places prompt number [slot] near the tray icon, falling back to the bottom right corner of the
 * screen when the tray position can not be determined (no tray icon, unsupported desktop).
 * Stacking goes away from the edge the first prompt landed on, so prompts never walk off screen.
 */
private fun promptPosition(slot: Int): WindowPosition {
    val screenSize = Toolkit.getDefaultToolkit().screenSize
    val base = runCatching { getTrayWindowPosition(PROMPT_WIDTH, PROMPT_HEIGHT) }
        .getOrNull() as? WindowPosition.Absolute
        ?: WindowPosition.Absolute(
            x = (screenSize.width - PROMPT_WIDTH - SCREEN_MARGIN).dp,
            y = (screenSize.height - PROMPT_HEIGHT - SCREEN_MARGIN).dp
        )
    if (slot == 0) return base
    val step = slot * (PROMPT_HEIGHT + PROMPT_GAP)
    //a tray at the top of the screen (a macOS menu bar, a top panel) puts the first prompt there,
    //and the stack has to grow downwards instead
    val growsDown = base.y.value < screenSize.height / 2f
    return WindowPosition.Absolute(
        x = base.x,
        y = (base.y.value + if (growsDown) step else -step).dp
    )
}
