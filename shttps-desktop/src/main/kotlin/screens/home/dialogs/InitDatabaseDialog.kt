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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.attach_existing_database
import com.phlox.simpleserver.shttps_desktop.generated.resources.create_new_database
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_experimental_warning
import com.phlox.simpleserver.shttps_desktop.generated.resources.enable_sqlite_database
import com.phlox.simpleserver.shttps_desktop.generated.resources.warning_prefix
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun InitDatabaseDialog(
    onConfirmCreateNew: () -> Unit,
    onConfirmAttachExisting: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.enable_sqlite_database),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Yellow)) {
                        append(stringResource(Res.string.warning_prefix))
                    }
                    append(stringResource(Res.string.database_experimental_warning))
                }
            )
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
                    onClick = onConfirmCreateNew,
                ) {
                    Text(stringResource(Res.string.create_new_database))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onConfirmAttachExisting,
                ) {
                    Text(stringResource(Res.string.attach_existing_database))
                }
            }
        }
    )
}