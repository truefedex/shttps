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
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.clear
import com.phlox.simpleserver.shttps_desktop.generated.resources.custom_headers_example
import com.phlox.simpleserver.shttps_desktop.generated.resources.custom_headers_hint
import com.phlox.simpleserver.shttps_desktop.generated.resources.customize_headers
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_custom_headers
import com.phlox.simpleserver.shttps_desktop.generated.resources.invalid_header_format
import com.phlox.simpleserver.shttps_desktop.generated.resources.save
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomHeadersDialog(
    currentHeaders: String,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val headersText = remember { mutableStateOf(currentHeaders) }
    val isError = remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf("") }
    val invalidHeaderMsg = stringResource(Res.string.invalid_header_format)

    // Validate headers format
    fun validateHeaders(text: String): Boolean {
        if (text.isBlank()) return true
        
        val lines = text.split("\n")
        for (line in lines) {
            if (line.isNotBlank()) {
                val parts = line.split(":", limit = 2)
                if (parts.size != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                    isError.value = true
                    errorMessage.value = invalidHeaderMsg
                    return false
                }
            }
        }
        isError.value = false
        errorMessage.value = ""
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.customize_headers),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(stringResource(Res.string.enter_custom_headers))
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = headersText.value,
                    onValueChange = { newValue ->
                        headersText.value = newValue
                        validateHeaders(newValue)
                    },
                    placeholder = { 
                        Text(stringResource(Res.string.custom_headers_hint))
                    },
                    isError = isError.value,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None
                    )
                )
                if (isError.value) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage.value,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.custom_headers_example),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
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
                        if (validateHeaders(headersText.value)) {
                            onConfirm(headersText.value)
                        }
                    },
                    enabled = !isError.value
                ) {
                    Text(stringResource(Res.string.save))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onClear,
                    enabled = headersText.value.isNotBlank()
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