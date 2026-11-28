package com.phlox.simpleserver.screens.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Lists the config profiles, switches between them, and creates, renames and removes them.
 *
 * @param onSwitch hands the switch to `HomeViewModel`, which owns the live `SHTTPSApp`; it reports
 *                 null on success or a message to show
 */
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    onNavigateBack: () -> Unit,
    onSwitch: (String, (String?) -> Unit) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    var pendingSwitch by remember { mutableStateOf<ProfileRow?>(null) }
    var pendingDelete by remember { mutableStateOf<ProfileRow?>(null) }
    var pendingRename by remember { mutableStateOf<ProfileRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(false) }

    val createFailedMsg = stringResource(Res.string.profile_create_failed)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                Column {
                    Text(
                        text = stringResource(Res.string.config_profiles),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground
                    )
                    Text(
                        text = stringResource(Res.string.profiles_screen_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.rows, key = { it.profile.id }) { row ->
                    ProfileItem(
                        row = row,
                        enabled = !switching,
                        onClick = { if (!row.active) pendingSwitch = row },
                        onRename = { pendingRename = row },
                        onDelete = { pendingDelete = row },
                    )
                }
            }

            Button(
                onClick = { creating = true },
                enabled = !switching,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.new_profile))
            }
        }
    }

    pendingSwitch?.let { row ->
        ConfirmDialog(
            title = stringResource(Res.string.config_profiles),
            message = stringResource(Res.string.profile_switch_confirmation_fmt, row.profile.name),
            confirmText = stringResource(Res.string.profile_switch),
            onConfirm = {
                pendingSwitch = null
                switching = true
                onSwitch(row.profile.id) { error ->
                    switching = false
                    if (error != null) {
                        errorMessage = error
                    } else {
                        //back to the main screen, which now reflects the other profile
                        onNavigateBack()
                    }
                }
            },
            onDismiss = { pendingSwitch = null }
        )
    }

    pendingDelete?.let { row ->
        var alsoDeleteData by remember(row.profile.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.delete)) },
            text = {
                Column {
                    Text(stringResource(
                        Res.string.profile_delete_confirmation_fmt, row.profile.name))
                    if (row.ownsDeletableData) {
                        Row(
                            modifier = Modifier.padding(top = 12.dp)
                                .clickable { alsoDeleteData = !alsoDeleteData },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = alsoDeleteData,
                                onCheckedChange = { alsoDeleteData = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.profile_delete_data))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(row.profile.id, alsoDeleteData)
                    pendingDelete = null
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }

    pendingRename?.let { row ->
        NameDialog(
            title = stringResource(Res.string.rename),
            initialValue = row.profile.name,
            onConfirm = { name ->
                viewModel.renameProfile(row.profile.id, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    if (creating) {
        NameDialog(
            title = stringResource(Res.string.new_profile),
            initialValue = "",
            onConfirm = { name ->
                creating = false
                if (!viewModel.createProfile(name)) errorMessage = createFailedMsg
            },
            onDismiss = { creating = false }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(Res.string.error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(Res.string.ok))
                }
            }
        )
    }
}

@Composable
private fun ProfileItem(
    row: ProfileRow,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val inUse = stringResource(Res.string.profile_in_use)
    Card(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled && !row.active) { onClick() },
        elevation = if (row.active) 6.dp else 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.profile.name,
                    fontWeight = if (row.active) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colors.onSurface
                )
                Text(
                    text = if (row.active) "$inUse · ${row.directory}" else row.directory,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(Res.string.cd_menu),
                        tint = MaterialTheme.colors.onSurface
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onRename()
                    }) { Text(stringResource(Res.string.rename)) }
                    //the profile in use and the last one left are kept: the first would leave the
                    //app without a config mid-flight, the second with nothing to switch to
                    if (row.canDelete) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onDelete()
                        }) { Text(stringResource(Res.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.profile_name)) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank()
            ) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}
