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
import com.phlox.simpleserver.shttps_desktop.generated.resources.export_choose
import com.phlox.simpleserver.shttps_desktop.generated.resources.export_everything
import com.phlox.simpleserver.shttps_desktop.generated.resources.export_everything_except_root
import com.phlox.simpleserver.shttps_desktop.generated.resources.export_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ExportChoiceDialog(
    onSelectEverything: () -> Unit,
    onSelectExceptRoot: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.export_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(stringResource(Res.string.export_choose))
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    colors = textButtonColors(contentColor = MaterialTheme.colors.onSurface),
                    onClick = onSelectEverything,
                ) {
                    Text(stringResource(Res.string.export_everything))
                }
                TextButton(
                    colors = textButtonColors(contentColor = MaterialTheme.colors.onSurface),
                    onClick = onSelectExceptRoot,
                ) {
                    Text(stringResource(Res.string.export_everything_except_root))
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
