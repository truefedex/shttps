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
import com.phlox.simpleserver.shttps_desktop.generated.resources.enter_hostname
import com.phlox.simpleserver.shttps_desktop.generated.resources.host_header_should_match
import com.phlox.simpleserver.shttps_desktop.generated.resources.hostname_consecutive_dots
import com.phlox.simpleserver.shttps_desktop.generated.resources.hostname_example_hint
import com.phlox.simpleserver.shttps_desktop.generated.resources.hostname_start_end
import com.phlox.simpleserver.shttps_desktop.generated.resources.hostname_verification_description
import com.phlox.simpleserver.shttps_desktop.generated.resources.invalid_hostname_format
import com.phlox.simpleserver.shttps_desktop.generated.resources.save
import org.jetbrains.compose.resources.stringResource

@Composable
fun HostnameDialog(
    currentHostname: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val hostnameText = remember { mutableStateOf(currentHostname ?: "") }
    val isError = remember { mutableStateOf(false) }
    val errorMessage = remember { mutableStateOf("") }
    val invalidFormatMsg = stringResource(Res.string.invalid_hostname_format)
    val consecutiveDotsMsg = stringResource(Res.string.hostname_consecutive_dots)
    val startEndMsg = stringResource(Res.string.hostname_start_end)

    // Validate hostname format
    fun validateHostname(text: String): Boolean {
        if (text.isBlank()) return true // Empty hostname is allowed (means no verification)
        
        // Basic hostname validation
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        
        // Check for valid hostname characters (letters, digits, dots, hyphens)
        val validHostnameRegex = Regex("^[a-zA-Z0-9.-]+$")
        if (!validHostnameRegex.matches(trimmed)) {
            isError.value = true
            errorMessage.value = invalidFormatMsg
            return false
        }
        
        // Check for consecutive dots
        if (trimmed.contains("..")) {
            isError.value = true
            errorMessage.value = consecutiveDotsMsg
            return false
        }
        
        // Check if it starts or ends with dot or hyphen
        if (trimmed.startsWith(".") || trimmed.endsWith(".") || 
            trimmed.startsWith("-") || trimmed.endsWith("-")) {
            isError.value = true
            errorMessage.value = startEndMsg
            return false
        }
        
        isError.value = false
        errorMessage.value = ""
        return true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.host_header_should_match),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.enter_hostname),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = hostnameText.value,
                    onValueChange = { newValue ->
                        hostnameText.value = newValue
                        validateHostname(newValue)
                    },
                    placeholder = { 
                        Text(stringResource(Res.string.hostname_example_hint))
                    },
                    isError = isError.value,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
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
                    text = stringResource(Res.string.hostname_verification_description),
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
                        if (validateHostname(hostnameText.value)) {
                            val hostname = hostnameText.value.trim().takeIf { it.isNotEmpty() }
                            onConfirm(hostname)
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
                    onClick = onDismiss
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}
