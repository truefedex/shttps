package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.current_port
import com.phlox.simpleserver.shttps_desktop.generated.resources.edit_port
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_port_number
import com.phlox.simpleserver.shttps_desktop.generated.resources.ok
import com.phlox.simpleserver.shttps_desktop.generated.resources.port_number
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PortEditDialog(
    currentPort: Int,
    portText: String,
    onPortTextChange: (String) -> Unit,
    portError: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.edit_port),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(stringResource(Res.string.enter_port_number))
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = portText,
                    onValueChange = onPortTextChange,
                    placeholder = { Text(stringResource(Res.string.port_number)) },
                    isError = portError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )
                if (portError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = portError,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.current_port, currentPort),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = portText.isNotBlank() && portError == null
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}