package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun RateLimitDialog(
    currentRateLimit: Int,
    currentTrustToIPHeaders: Boolean,
    onConfirm: (Int, Boolean) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit
) {
    var rateLimitText by remember { mutableStateOf(if (currentRateLimit > 0) currentRateLimit.toString() else "") }
    var trustToIPHeaders by remember { mutableStateOf(currentTrustToIPHeaders) }
    var rateLimitError by remember { mutableStateOf<String?>(null) }
    val rateLimitInvalidMsg = stringResource(Res.string.rate_limit_invalid)
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colors.surface,
            elevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.request_rate_limit),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = stringResource(Res.string.max_requests_per_minute),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                TextField(
                    value = rateLimitText,
                    onValueChange = { 
                        rateLimitText = it
                        rateLimitError = null // Clear error when user types
                    },
                    label = { Text(stringResource(Res.string.requests_per_minute)) },
                    isError = rateLimitError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (rateLimitError != null) {
                    Text(
                        text = rateLimitError!!,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = trustToIPHeaders,
                        onCheckedChange = { trustToIPHeaders = it },
                        colors = CheckboxDefaults.colors()
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(Res.string.trust_ip_headers),
                            style = MaterialTheme.typography.body1
                        )
                        Text(
                            text = stringResource(Res.string.trust_ip_headers_info),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            val rateLimit = rateLimitText.toIntOrNull()
                            if (rateLimit == null || rateLimit <= 0) {
                                rateLimitError = rateLimitInvalidMsg
                            } else {
                                onConfirm(rateLimit, trustToIPHeaders)
                            }
                        }
                    ) {
                        Text(stringResource(Res.string.set_rate_limit))
                    }
                    
                    Button(
                        onClick = onDisable
                    ) {
                        Text(stringResource(Res.string.disable_rate_limiter))
                    }
                    
                    Button(
                        onClick = onDismiss
                    ) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            }
        }
    }
}
