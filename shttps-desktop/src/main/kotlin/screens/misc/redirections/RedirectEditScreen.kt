package com.phlox.simpleserver.screens.misc.redirections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RunCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware.RedirectRule

@Composable
fun RedirectEditScreen(
    rule: RedirectRule?,
    ruleIndex: Int,
    onNavigateBack: () -> Unit,
    onSave: (RedirectRule, Int) -> Unit
) {
    var fromPattern by remember { mutableStateOf(rule?.from ?: "") }
    var toDestination by remember { mutableStateOf(rule?.to ?: "") }
    var httpCode by remember { mutableStateOf(rule?.code?.toString() ?: "302") }
    var comment by remember { mutableStateOf(rule?.comment ?: "") }
    var showRegexTestDialog by remember { mutableStateOf(false) }
    var showValidationError by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back button
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
                Text(
                    text = if (ruleIndex >= 0) stringResource(Res.string.edit_redirect_rule) else stringResource(Res.string.add_redirect_rule),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.redirect_rule_configuration),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.primary
                    )

                    // From Pattern Field
                    OutlinedTextField(
                        value = fromPattern,
                        onValueChange = { fromPattern = it },
                        label = { Text(stringResource(Res.string.from_pattern_regex)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.from_pattern_hint)) },
                        isError = showValidationError && fromPattern.isEmpty()
                    )
                    if (showValidationError && fromPattern.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.from_pattern_required),
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption
                        )
                    }

                    // To Destination Field
                    OutlinedTextField(
                        value = toDestination,
                        onValueChange = { toDestination = it },
                        label = { Text(stringResource(Res.string.to_destination)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.to_destination_hint)) },
                        isError = showValidationError && toDestination.isEmpty()
                    )
                    if (showValidationError && toDestination.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.to_destination_required),
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption
                        )
                    }

                    // HTTP Code Field
                    OutlinedTextField(
                        value = httpCode,
                        onValueChange = { httpCode = it },
                        label = { Text(stringResource(Res.string.http_status_code)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("302") },
                        isError = showValidationError && (httpCode.isEmpty() || !isValidHttpCode(httpCode))
                    )
                    if (showValidationError && (httpCode.isEmpty() || !isValidHttpCode(httpCode))) {
                        Text(
                            text = stringResource(Res.string.valid_http_status_required),
                            color = MaterialTheme.colors.error,
                            style = MaterialTheme.typography.caption
                        )
                    }

                    // Comment Field
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(stringResource(Res.string.comment_optional)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.comment_placeholder)) }
                    )

                    // Test Regex Button
                    Button(
                        onClick = { showRegexTestDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = fromPattern.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.RunCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(Res.string.test_regex_pattern))
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateBack,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.surface
                            )
                        ) {
                            Text(stringResource(Res.string.cancel), color = MaterialTheme.colors.onSurface)
                        }

                        Button(
                            onClick = {
                                if (validateInput(fromPattern, toDestination, httpCode)) {
                                    val redirectCode = httpCode.toIntOrNull() ?: 302
                                    val newRule = RedirectRule(fromPattern, toDestination, redirectCode, true, comment)
                                    onSave(newRule, ruleIndex)
                                } else {
                                    showValidationError = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(stringResource(Res.string.save))
                        }
                    }
                }
            }

            // Help Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.help),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.primary
                    )
                    Text(
                        text = stringResource(Res.string.help_from_pattern),
                        style = MaterialTheme.typography.body2
                    )
                    Text(
                        text = stringResource(Res.string.help_to_destination),
                        style = MaterialTheme.typography.body2
                    )
                    Text(
                        text = stringResource(Res.string.help_http_status),
                        style = MaterialTheme.typography.body2
                    )
                    Text(
                        text = stringResource(Res.string.help_test_regex),
                        style = MaterialTheme.typography.body2
                    )
                }
            }
        }
    }

    // Regex Test Dialog
    if (showRegexTestDialog) {
        RegexTestDialog(
            pattern = fromPattern,
            onDismiss = { showRegexTestDialog = false }
        )
    }
}

@Composable
private fun RegexTestDialog(
    pattern: String,
    onDismiss: () -> Unit
) {
    var testUrl by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    val matchFound = stringResource(Res.string.regex_match_found)
    val noMatch = stringResource(Res.string.regex_no_match)
    val invalidRegexFmt = stringResource(Res.string.regex_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.test_regex_pattern)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(Res.string.pattern_label, pattern))
                OutlinedTextField(
                    value = testUrl,
                    onValueChange = { testUrl = it },
                    label = { Text(stringResource(Res.string.test_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.from_pattern_hint)) }
                )
                if (testResult.isNotEmpty()) {
                    Text(
                        text = testResult,
                        style = MaterialTheme.typography.body2,
                        color = if (testResult.startsWith("✓")) MaterialTheme.colors.primary else MaterialTheme.colors.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    testResult = try {
                        val regex = pattern.toRegex()
                        if (regex.matches(testUrl)) {
                            matchFound
                        } else {
                            noMatch
                        }
                    } catch (e: Exception) {
                        invalidRegexFmt.replace("%1\$s", e.message ?: "")
                    }
                },
                enabled = testUrl.isNotEmpty()
            ) {
                Text(stringResource(Res.string.test))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        }
    )
}

private fun validateInput(fromPattern: String, toDestination: String, httpCode: String): Boolean {
    return fromPattern.isNotEmpty() && 
           toDestination.isNotEmpty() && 
           isValidHttpCode(httpCode)
}

private fun isValidHttpCode(code: String): Boolean {
    val codeInt = code.toIntOrNull() ?: return false
    return codeInt in 300..399 // Valid redirect status codes
}
