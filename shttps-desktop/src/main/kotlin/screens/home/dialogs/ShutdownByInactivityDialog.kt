package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.confirm
import com.phlox.simpleserver.shttps_desktop.generated.resources.disable_timeout
import com.phlox.simpleserver.shttps_desktop.generated.resources.shutdown_by_inactivity
import com.phlox.simpleserver.shttps_desktop.generated.resources.shutdown_by_inactivity_description
import com.phlox.simpleserver.shttps_desktop.generated.resources.timeout_in_minutes
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShutdownByInactivityDialog(
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val currentValue = remember { mutableStateOf(initialValue.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.shutdown_by_inactivity))
        },
        text = {
            Column {
                Text(stringResource(Res.string.shutdown_by_inactivity_description))
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = currentValue.value,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() }) {
                            currentValue.value = it
                        }
                    },
                    placeholder = { Text(stringResource(Res.string.timeout_in_minutes)) },
                    singleLine = true,
                )
            }
        },
        buttons = {
            Column (
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        onConfirm(0)
                    }
                ) {
                    Text(stringResource(Res.string.disable_timeout))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        val minutes = currentValue.value.toIntOrNull() ?: 0
                        onConfirm(minutes)
                    }
                ) {
                    Text(stringResource(Res.string.confirm))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onDismiss
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}