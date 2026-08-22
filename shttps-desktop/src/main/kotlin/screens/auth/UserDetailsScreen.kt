package com.phlox.simpleserver.screens.auth

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.dialogs.RootSubfolderPickerDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun UserDetailsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRoles: () -> Unit,
    config: AppConfig,
    shttpsApp: SHTTPSApp,
    user: User
) {
    val viewModel: UserDetailsViewModel = viewModel { UserDetailsViewModel(config, shttpsApp, user) }
    val uiState by viewModel.uiState.collectAsState()
    val deleteUserTitle = stringResource(Res.string.delete_user)
    val deleteUserConfirmation = stringResource(Res.string.delete_user_confirmation, user.identity)
    val errorTitle = stringResource(Res.string.error)
    val rootFolderNotSetMessage = stringResource(Res.string.root_folder_not_set)
    //the user root folder is stored relative to the server root folder, so it can only be picked
    //inside of it - see RootSubfolderPickerDialog
    var showRootFolderPicker by remember { mutableStateOf(false) }

    // Refresh roles when screen becomes visible (similar to Android onResume)
    LaunchedEffect(Unit) {
        viewModel.refreshRoles()
    }
    
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
                            text = stringResource(Res.string.user_details),
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
                                    viewModel.showMessageDialog(
                                        deleteUserTitle,
                                        deleteUserConfirmation
                                    )
                                    expanded = false
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.delete_user))
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
                        // User Information Section
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.user_information),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                
                                // Username
                                OutlinedTextField(
                                    value = uiState.username,
                                    onValueChange = { viewModel.updateUsername(it) },
                                    label = { Text(stringResource(Res.string.username)) },
                                    enabled = !uiState.isGuest,
                                    isError = uiState.usernameError != null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (uiState.usernameError != null) {
                                    Text(
                                        text = when (uiState.usernameError) {
                                            UsernameFieldError.EMPTY -> stringResource(Res.string.username_cannot_be_empty)
                                            UsernameFieldError.IN_USE -> stringResource(Res.string.username_already_in_use)
                                            null -> ""
                                        },
                                        color = MaterialTheme.colors.error,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Password
                                if (!uiState.isGuest) {
                                    OutlinedTextField(
                                        value = uiState.password,
                                        onValueChange = { viewModel.updatePassword(it) },
                                        label = { Text(stringResource(Res.string.password)) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Custom Root Folder Section
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(Res.string.custom_root_folder),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.secondaryVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            if (config.rootDir == null) {
                                                viewModel.showMessageDialog(errorTitle, rootFolderNotSetMessage)
                                            } else {
                                                showRootFolderPicker = true
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(Res.string.root_folder),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = uiState.customRootFolder ?: "/",
                                                    fontWeight = FontWeight.Light
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colors.onPrimary,
                                                modifier = Modifier.width(32.dp).height(32.dp)
                                            )
                                        }
                                    }
                                    
                                    if (uiState.customRootFolder != null) {
                                        Button(
                                            onClick = {
                                                viewModel.updateCustomRootFolder(null)
                                            }
                                        ) {
                                            Text(stringResource(Res.string.clear))
                                        }
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Role Selection
                        if (config.isStoreUsersInDatabase && uiState.availableRoles.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = 4.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = stringResource(Res.string.role),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colors.secondaryVariant,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    
                                    var expanded by remember { mutableStateOf(false) }
                                    val noRole = stringResource(Res.string.no_role)
                                    val roleOptions = listOf(noRole) + uiState.availableRoles
                                    val selectedRole = uiState.selectedRole ?: noRole
                                    
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it }
                                    ) {
                                        OutlinedTextField(
                                            value = selectedRole,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(Res.string.select_role)) },
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            roleOptions.forEach { role ->
                                                DropdownMenuItem(
                                                    onClick = {
                                                        viewModel.updateSelectedRole(if (role == noRole) null else role)
                                                        expanded = false
                                                    }
                                                ) {
                                                    Text(role)
                                                }
                                            }
                                        }
                                    }
                                    
                                    if (config.isStoreUsersInDatabase) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = onNavigateToRoles,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(stringResource(Res.string.edit_roles))
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        
                        // Check if user has a role (rights sections should be disabled when role is selected)
                        val selectedRole = uiState.selectedRole
                        val hasRole = selectedRole != null && selectedRole.isNotEmpty()
                        
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
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .alpha(if (hasRole) 0.5f else 1.0f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = uiState.storageSizeLimit,
                                        onValueChange = { if (!hasRole) viewModel.updateStorageSizeLimit(it) },
                                        label = { Text(stringResource(Res.string.storage_size_limit)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        enabled = !hasRole,
                                        modifier = Modifier
                                            .weight(1f)
                                            .alpha(if (hasRole) 0.5f else 1.0f)
                                    )
                                    Button(
                                        onClick = { if (!hasRole) viewModel.toggleStorageSizeUnits() },
                                        enabled = !hasRole,
                                        modifier = Modifier.alpha(if (hasRole) 0.5f else 1.0f)
                                    ) {
                                        Text(if (uiState.useGigabytesUnits) stringResource(Res.string.unit_gb) else stringResource(Res.string.unit_mb))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Storage Used
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(Res.string.storage_used),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colors.secondaryVariant
                                    )
                                    
                                    if (uiState.isRecalculatingStorage) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.width(24.dp).height(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        IconButton(
                                            onClick = { viewModel.updateUsedStorage(true) },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = stringResource(Res.string.cd_recalculate_storage),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Text(
                                    text = if (uiState.isRecalculatingStorage) {
                                        stringResource(Res.string.calculating)
                                    } else {
                                        uiState.usedStorageDisplayText
                                    },
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colors.onBackground
                                )
                                
                                if (uiState.storageProgress != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = (uiState.storageProgress ?: 0) / 100f,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colors.primaryVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(Res.string.percent_used, uiState.storageProgress ?: 0),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                                    )
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
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .alpha(if (hasRole) 0.5f else 1.0f)
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
                                                if (!hasRole) {
                                                    viewModel.updateFileSystemRight(right, enabled)
                                                }
                                            },
                                            enabled = !hasRole,
                                            modifier = Modifier.alpha(if (hasRole) 0.5f else 1.0f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier
                                                .weight(1f)
                                                .alpha(if (hasRole) 0.5f else 1.0f)
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
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .alpha(if (hasRole) 0.5f else 1.0f)
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
                                                if (!hasRole) {
                                                    viewModel.updateDBRight(right, enabled)
                                                }
                                            },
                                            enabled = !hasRole,
                                            modifier = Modifier.alpha(if (hasRole) 0.5f else 1.0f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier
                                                .weight(1f)
                                                .alpha(if (hasRole) 0.5f else 1.0f)
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
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .alpha(if (hasRole) 0.5f else 1.0f)
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
                                                if (!hasRole) {
                                                    viewModel.updateSystemRight(right, enabled)
                                                }
                                            },
                                            enabled = !hasRole,
                                            modifier = Modifier.alpha(if (hasRole) 0.5f else 1.0f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier
                                                .weight(1f)
                                                .alpha(if (hasRole) 0.5f else 1.0f)
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
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .alpha(if (hasRole) 0.5f else 1.0f)
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
                                                if (!hasRole) {
                                                    viewModel.updateChannelRight(right, enabled)
                                                }
                                            },
                                            enabled = !hasRole,
                                            modifier = Modifier.alpha(if (hasRole) 0.5f else 1.0f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = label,
                                            modifier = Modifier
                                                .weight(1f)
                                                .alpha(if (hasRole) 0.5f else 1.0f)
                                        )
                                    }
                                }
                            }
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

    val serverRoot = config.rootDir
    if (showRootFolderPicker && serverRoot != null) {
        RootSubfolderPickerDialog(
            root = serverRoot,
            initialRelativePath = uiState.customRootFolder,
            onResult = {
                viewModel.updateCustomRootFolder(it)
                showRootFolderPicker = false
            },
            onDismiss = { showRootFolderPicker = false }
        )
    }
}