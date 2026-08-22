package com.phlox.simpleserver.screens.auth.roles

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.screens.auth.channelRightLabels
import com.phlox.simpleserver.screens.auth.dbRightLabels
import com.phlox.simpleserver.screens.auth.fileSystemRightLabels
import com.phlox.simpleserver.screens.auth.systemRightLabels
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun RoleDetailsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRules: (String, String) -> Unit, // ruleType, roleName
    config: AppConfig,
    shttpsApp: SHTTPSApp,
    role: UserRole
) {
    val viewModel: RoleDetailsViewModel =
        viewModel { RoleDetailsViewModel(config, shttpsApp, role) }
    val uiState by viewModel.uiState.collectAsState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                            text = stringResource(Res.string.role_details),
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
                                    viewModel.deleteRole()
                                    expanded = false
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.delete_role))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable content
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        // Role Information Section
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.role_information),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Role Name
                                OutlinedTextField(
                                    value = uiState.roleName,
                                    onValueChange = { viewModel.updateRoleName(it) },
                                    label = { Text(stringResource(Res.string.role_name_constraints)) },
                                    isError = uiState.roleNameError,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (uiState.roleNameError) {
                                    Text(
                                        text = stringResource(Res.string.role_name_cannot_be_empty),
                                        color = MaterialTheme.colors.error,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Storage Size Limit
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.storage_size_limit),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = uiState.storageSizeLimit,
                                        onValueChange = { viewModel.updateStorageSizeLimit(it) },
                                        label = { Text(stringResource(Res.string.storage_size_limit)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { viewModel.toggleStorageSizeUnits() }
                                    ) {
                                        Text(if (uiState.useGigabytesUnits) stringResource(Res.string.unit_gb) else stringResource(Res.string.unit_mb))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // File System Rights
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.file_system_rights),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                val fileRights = fileSystemRightLabels()

                                fileRights.forEach { (right, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.fileSystemRights.contains(right),
                                            onCheckedChange = { enabled ->
                                                viewModel.updateFileSystemRight(right, enabled)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Database Rights
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.database_rights),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                val dbRights = dbRightLabels()

                                dbRights.forEach { (right, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.dbRights.contains(right),
                                            onCheckedChange = { enabled ->
                                                viewModel.updateDBRight(right, enabled)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // System Rights
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.system_rights),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                
                                val systemRights = systemRightLabels()
                                
                                systemRights.forEach { (right, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.systemRights.contains(right),
                                            onCheckedChange = { enabled ->
                                                viewModel.updateSystemRight(right, enabled)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Channel Rights
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.channel_rights),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                //CONNECT and POST are not coupled on purpose: a one-shot publish
                                //over POST /api/channels/{id}/message needs POST alone
                                val channelRights = channelRightLabels()

                                channelRights.forEach { (right, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = uiState.channelRights.contains(right),
                                            onCheckedChange = { enabled ->
                                                viewModel.updateChannelRight(right, enabled)
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Access Rules Buttons
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.access_rules),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Button(
                                    onClick = { onNavigateToRules("db", role.name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(Res.string.db_access_rules))
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = { onNavigateToRules("fs", role.name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(Res.string.fs_access_rules))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Save Button
                        Button(
                            onClick = { viewModel.saveRole() },
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
                            Text(stringResource(Res.string.save_role))
                        }
                    }

                    VerticalScrollbar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(scrollState),
                        style = ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 8.dp,
                            shape = RoundedCornerShape(4.dp),
                            hoverDurationMillis = 300,
                            unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                            hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.50f)
                        )
                    )
                }
            }
        }
    }

    if (uiState.showMessageDialog) {
        MessageDialog(uiState.messageDialogData)
    }
}
