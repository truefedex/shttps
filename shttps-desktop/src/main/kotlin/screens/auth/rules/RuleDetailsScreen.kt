package com.phlox.simpleserver.screens.auth.rules

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
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.components.syntaxh.SqlSyntaxHighlighter
import com.phlox.simpleserver.components.syntaxh.SyntaxHighlightTextField
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun RuleDetailsScreen(
    onNavigateBack: () -> Unit,
    ruleType: String,
    roleName: String,
    subject: String,
    operation: String,
    allow: Boolean,
    expression: String,
    config: AppConfig,
    shttpsApp: SHTTPSApp
) {
    val viewModel: RuleDetailsViewModel = viewModel {
        RuleDetailsViewModel(config, shttpsApp, ruleType, roleName, subject, operation, allow, expression)
    }
    val uiState by viewModel.uiState.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                    }
                    Text(
                        text = stringResource(Res.string.rule_details),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground
                    )
                }

                var expanded by remember { mutableStateOf(false) }

                Box {
                    IconButton(
                        onClick = { expanded = true }
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(Res.string.cd_menu))
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                viewModel.deleteRule()
                                expanded = false
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.delete_rule))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable content
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            ) {
                // Role Name (read-only)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.role_name_label),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = roleName,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Subject
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.subject),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = if (ruleType == "db") stringResource(Res.string.table_or_view_name) else stringResource(Res.string.directory_or_file_path),
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        OutlinedTextField(
                            value = uiState.subject,
                            onValueChange = { viewModel.updateSubject(it) },
                            label = { Text(stringResource(Res.string.subject)) },
                            isError = uiState.subjectError,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (uiState.subjectError) {
                            Text(
                                text = stringResource(Res.string.subject_cannot_be_empty),
                                color = MaterialTheme.colors.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Operation
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.operation),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        // Dropdown for operation
                        var expanded by remember { mutableStateOf(false) }
                        val operations = if (ruleType == "db") {
                            listOf("EXECUTE", "DELETE", "INSERT", "READ_SCHEMA", "READ_CELL", "READ_TABLE", "UPDATE")
                        } else {
                            listOf("DELETE", "LIST_CONTENTS", "COPY_MOVE", "NEW_FOLDER", "RENAME", "DOWNLOAD", "THUMBNAIL", "UPLOAD", "ZIP_DOWNLOAD")
                        }
                        
                        Box {
                            Button(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(uiState.operation.ifEmpty { stringResource(Res.string.select_operation) })
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                operations.forEach { op ->
                                    DropdownMenuItem(
                                        onClick = {
                                            viewModel.updateOperation(op)
                                            expanded = false
                                        }
                                    ) {
                                        Text(op)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Allow/Deny
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.allow,
                                onCheckedChange = { viewModel.updateAllow(it) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.allow),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = stringResource(Res.string.used_only_if_sql_not_set),
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 40.dp, top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Expression
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.sql_expression),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = stringResource(Res.string.sql_expression_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SyntaxHighlightTextField(
                            text = uiState.expression,
                            onTextChange = { viewModel.updateExpression(it) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            highlighter = SqlSyntaxHighlighter()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save Button
                Button(
                    onClick = { viewModel.saveRule() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.Save, contentDescription = stringResource(Res.string.cd_save))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.save_rule))
                }
            }
        }
    }

    if (uiState.showMessageDialog) {
        MessageDialog(uiState.messageDialogData)
    }
}

