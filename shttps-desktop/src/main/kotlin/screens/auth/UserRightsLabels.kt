package com.phlox.simpleserver.screens.auth

import androidx.compose.runtime.Composable
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_connect_to_channels
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_create
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_create_channels
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_delete
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_delete_channels
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_control_screen
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_execute_handlers
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_execute_sql
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_list_channels
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_list_contents
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_list_participants
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_read
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_read_schema
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_read_status
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_send_messages
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_update
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_use_transactions
import com.phlox.simpleserver.shttps_desktop.generated.resources.right_view_screen
import org.jetbrains.compose.resources.stringResource

@Composable
fun fileSystemRightLabels(): List<Pair<User.FileSystemRights, String>> = listOf(
    User.FileSystemRights.READ to stringResource(Res.string.right_read),
    User.FileSystemRights.LIST_CONTENTS to stringResource(Res.string.right_list_contents),
    User.FileSystemRights.CREATE to stringResource(Res.string.right_create),
    User.FileSystemRights.UPDATE to stringResource(Res.string.right_update),
    User.FileSystemRights.DELETE to stringResource(Res.string.right_delete)
)

@Composable
fun dbRightLabels(): List<Pair<User.DBRights, String>> = listOf(
    User.DBRights.READ to stringResource(Res.string.right_read),
    User.DBRights.READ_SCHEMA to stringResource(Res.string.right_read_schema),
    User.DBRights.CREATE to stringResource(Res.string.right_create),
    User.DBRights.UPDATE to stringResource(Res.string.right_update),
    User.DBRights.DELETE to stringResource(Res.string.right_delete),
    User.DBRights.EXEC_SQL to stringResource(Res.string.right_execute_sql),
    User.DBRights.USE_TRANSACTION to stringResource(Res.string.right_use_transactions)
)

@Composable
fun systemRightLabels(): List<Pair<User.SystemRights, String>> = listOf(
    User.SystemRights.READ_STATUS to stringResource(Res.string.right_read_status),
    User.SystemRights.EXECUTE_HANDLER to stringResource(Res.string.right_execute_handlers),
    User.SystemRights.VIEW_SCREEN to stringResource(Res.string.right_view_screen),
    User.SystemRights.CONTROL_SCREEN to stringResource(Res.string.right_control_screen)
)

/**
 * CONTROL_SCREEN is useless without VIEW_SCREEN. Granting control also grants view;
 * revoking view also revokes control, matching the Android user/role screens.
 */
fun applySystemRightChange(
    rights: MutableSet<User.SystemRights>,
    right: User.SystemRights,
    enabled: Boolean
) {
    if (enabled) {
        rights.add(right)
        if (right == User.SystemRights.CONTROL_SCREEN) {
            rights.add(User.SystemRights.VIEW_SCREEN)
        }
    } else {
        rights.remove(right)
        if (right == User.SystemRights.VIEW_SCREEN) {
            rights.remove(User.SystemRights.CONTROL_SCREEN)
        }
    }
}

@Composable
fun channelRightLabels(): List<Pair<User.ChannelRights, String>> = listOf(
    User.ChannelRights.CONNECT to stringResource(Res.string.right_connect_to_channels),
    User.ChannelRights.POST to stringResource(Res.string.right_send_messages),
    User.ChannelRights.CREATE to stringResource(Res.string.right_create_channels),
    User.ChannelRights.DELETE to stringResource(Res.string.right_delete_channels),
    User.ChannelRights.LIST_CHANNELS to stringResource(Res.string.right_list_channels),
    User.ChannelRights.LIST_PARTICIPANTS to stringResource(Res.string.right_list_participants)
)
