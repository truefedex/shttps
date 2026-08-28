package com.phlox.simpleserver.screens.handlers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import com.phlox.simpleserver.exec.CgiType
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CgiTypeDetailsScreen(
    viewModel: CgiTypeDetailsViewModel,
    onNavigateBack: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val uiState = viewModel.uiState.value
    val scrollState = rememberScrollState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenuExpanded by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back button and menu
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
                    text = stringResource(Res.string.cgi_type_details),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
                
                if (viewModel.canDelete()) {
                    Box {
                        IconButton(onClick = { showMenuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(Res.string.cd_menu),
                                tint = MaterialTheme.colors.onBackground
                            )
                        }
                        DropdownMenu(
                            expanded = showMenuExpanded,
                            onDismissRequest = { showMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    showMenuExpanded = false
                                    showDeleteDialog = true
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colors.onSurface
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.delete_cgi_type))
                            }
                        }
                    }
                }
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
                        text = stringResource(Res.string.cgi_type_details_subtitle),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    // Extension field
                    OutlinedTextField(
                        value = uiState.extension,
                        onValueChange = { viewModel.updateExtension(it) },
                        label = { Text(stringResource(Res.string.extension)) },
                        placeholder = { Text(stringResource(Res.string.extension_hint)) },
                        isError = uiState.extensionError != null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (uiState.extensionError != null) {
                        Text(
                            text = when (uiState.extensionError) {
                                CgiExtensionError.EMPTY -> stringResource(Res.string.extension_cannot_be_empty)
                                CgiExtensionError.DUPLICATE -> stringResource(Res.string.extension_already_exists)
                                null -> ""
                            },
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mode selection
                    Text(
                        text = stringResource(Res.string.program_subtype),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.mode == CgiType.Mode.CGI,
                            onClick = { viewModel.updateMode(CgiType.Mode.CGI) }
                        )
                        Text(stringResource(Res.string.cgi_mode))
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = uiState.mode == CgiType.Mode.SIMPLE,
                            onClick = { viewModel.updateMode(CgiType.Mode.SIMPLE) }
                        )
                        Text(stringResource(Res.string.simple_mode))
                    }

                    // Mode description
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 1.dp,
                        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = if (uiState.mode == CgiType.Mode.CGI) {
                                stringResource(Res.string.cgi_mode_description)
                            } else {
                                stringResource(Res.string.simple_mode_description)
                            },
                            modifier = Modifier.padding(12.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Execute with field
                    OutlinedTextField(
                        value = uiState.executeWith ?: "",
                        onValueChange = { viewModel.updateExecuteWith(it) },
                        label = { Text(stringResource(Res.string.execute_with)) },
                        placeholder = { Text(stringResource(Res.string.execute_with_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        text = stringResource(Res.string.execute_with_description),
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Execution timeout field
                    OutlinedTextField(
                        value = uiState.executionTimeout,
                        onValueChange = { viewModel.updateExecutionTimeout(it) },
                        label = { Text(stringResource(Res.string.execution_timeout)) },
                        placeholder = { Text(stringResource(Res.string.execution_timeout_hint)) },
                        isError = uiState.executionTimeoutError,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (uiState.executionTimeoutError) {
                        Text(
                            text = stringResource(Res.string.execution_timeout_invalid),
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = stringResource(Res.string.execution_timeout_description),
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Shebang notice
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = 1.dp,
                        backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = stringResource(Res.string.shebang_notice),
                            modifier = Modifier.padding(12.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.error.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Save button
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (viewModel.saveCgiType()) {
                        onSaveSuccess()
                    }
                }
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.save))
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(Res.string.delete_cgi_type))
            },
            text = {
                Text(
                    stringResource(
                        Res.string.delete_cgi_type_confirmation,
                        ".${uiState.originalExtension ?: uiState.extension}"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.deleteCgiType()) {
                            showDeleteDialog = false
                            onSaveSuccess()
                        }
                    }
                ) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        )
    }
}
