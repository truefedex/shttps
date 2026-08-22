package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.import_continue
import com.phlox.simpleserver.shttps_desktop.generated.resources.import_export_first
import com.phlox.simpleserver.shttps_desktop.generated.resources.import_title
import com.phlox.simpleserver.shttps_desktop.generated.resources.import_warning
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ImportConfirmDialog(
    onContinue: () -> Unit,
    onExportFirst: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.import_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(stringResource(Res.string.import_warning))
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    colors = textButtonColors(contentColor = MaterialTheme.colors.onSurface),
                    onClick = onContinue,
                ) {
                    Text(stringResource(Res.string.import_continue))
                }
                TextButton(
                    colors = textButtonColors(contentColor = MaterialTheme.colors.onSurface),
                    onClick = onExportFirst,
                ) {
                    Text(stringResource(Res.string.import_export_first))
                }
                TextButton(
                    colors = textButtonColors(contentColor = MaterialTheme.colors.onSurface),
                    onClick = onDismiss,
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}
