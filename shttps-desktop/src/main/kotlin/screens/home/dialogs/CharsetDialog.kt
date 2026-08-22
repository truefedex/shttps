package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import com.phlox.simpleserver.shttps_desktop.generated.resources.*

@Composable
fun CharsetDialog(
    currentCharset: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val charsetText = remember { mutableStateOf(currentCharset?.takeIf { it.isNotEmpty() } ?: "utf-8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.custom_charset),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.custom_charset_description),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = charsetText.value,
                    onValueChange = { charsetText.value = it },
                    placeholder = { Text("utf-8") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None
                    )
                )
            }
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        val value = charsetText.value.trim().takeIf { it.isNotEmpty() }
                        onConfirm(value)
                    }
                ) {
                    Text(stringResource(Res.string.save))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        onConfirm(null)
                    },
                    enabled = charsetText.value.isNotBlank()
                ) {
                    Text(stringResource(Res.string.clear))
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
