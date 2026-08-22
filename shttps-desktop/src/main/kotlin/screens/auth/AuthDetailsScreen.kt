package com.phlox.simpleserver.screens.auth

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AuthDetailsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUserDetails: (User) -> Unit,
    onNavigateToRoles: () -> Unit,
    config: AppConfig,
    shttpsApp: SHTTPSApp
) {
    val viewModel: AuthDetailsViewModel = viewModel { AuthDetailsViewModel(config, shttpsApp) }
    val uiState by viewModel.uiState.collectAsState()
    
    // Refresh users when screen becomes visible
    LaunchedEffect(Unit) {
        viewModel.refreshUsers()
    }
    
    // Also refresh when the composable is recomposed (this happens when returning from other screens)
    LaunchedEffect(viewModel) {
        viewModel.refreshUsers()
    }
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(Res.string.tab_users), stringResource(Res.string.tab_settings))
    
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
                        text = stringResource(Res.string.auth_details),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Tab Row
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Tab Content
            when (selectedTabIndex) {
                0 -> UsersTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    onNavigateToUserDetails = onNavigateToUserDetails
                )
                1 -> SettingsTab(
                    uiState = uiState,
                    viewModel = viewModel,
                    onNavigateToRoles = onNavigateToRoles
                )
            }
        }
    }

    if (uiState.showMessageDialog) {
        MessageDialog(
            uiState.messageDialogData
        )
    }
}

@Composable
fun UsersTab(
    uiState: AuthDetailsState,
    viewModel: AuthDetailsViewModel,
    onNavigateToUserDetails: (User) -> Unit
) {
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
                    text = stringResource(Res.string.users_count, uiState.users.size),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.secondaryVariant
                )
                
                Button(
                    onClick = { viewModel.createNewUser() },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_user))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_user))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.users.isEmpty()) {
                Text(
                    text = stringResource(Res.string.no_users_found),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Row(
                    modifier = Modifier.weight(1f)
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                    ) {
                        uiState.users.forEach { user ->
                        UserItem(
                            user = user,
                            onEdit = { onNavigateToUserDetails(user) },
                            onDelete = { viewModel.deleteUser(user) }
                        )
                        }
                    }
                    
                    VerticalScrollbar(
                        modifier = Modifier
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
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SettingsTab(
    uiState: AuthDetailsState,
    viewModel: AuthDetailsViewModel,
    onNavigateToRoles: () -> Unit
) {
    val scrollState = rememberScrollState()
    val dbNotEnabledTitle = stringResource(Res.string.database_not_enabled)
    val dbMustEnableRolesMsg = stringResource(Res.string.database_must_be_enabled_for_roles)
    
    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
        // Authentication Mode Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.authentication_mode),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.secondaryVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Session-based Authentication
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.updateAuthMode(SHTTPSConfig.AuthMode.WEB) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = uiState.authMode == SHTTPSConfig.AuthMode.WEB,
                        onClick = { viewModel.updateAuthMode(SHTTPSConfig.AuthMode.WEB) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.session_based_auth),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Basic Authentication
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.updateAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = uiState.authMode == SHTTPSConfig.AuthMode.BASIC_AUTH,
                        onClick = { viewModel.updateAuthMode(SHTTPSConfig.AuthMode.BASIC_AUTH) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.basic_auth),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // General Settings Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.general_settings),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.secondaryVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Allow Guest Access
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.toggleGuestAccess(!uiState.allowGuestAccess) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.allowGuestAccess,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleGuestAccess(isChecked)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.allow_guest_access),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Store Users in Database
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.toggleStoreUsersInDatabase(!uiState.storeUsersInDatabase) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.storeUsersInDatabase,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleStoreUsersInDatabase(isChecked)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.store_users_in_database),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // Allow User Registration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.toggleUserRegistration(!uiState.isAllowedUserRegistration) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.isAllowedUserRegistration,
                        onCheckedChange = { isChecked ->
                            viewModel.toggleUserRegistration(isChecked)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.allow_user_registration),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // User Registration Settings Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.user_registration_settings),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.secondaryVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Default Role for New User
                Text(
                    text = stringResource(Res.string.default_role_for_new_users),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                if (uiState.availableRoles.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    val noRole = stringResource(Res.string.no_role)
                    val roleOptions = listOf(noRole) + uiState.availableRoles
                    val selectedRole = uiState.defaultRoleForNewUser.ifEmpty { noRole }
                    
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        TextField(
                            value = selectedRole,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.select_role)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.textFieldColors(
                                backgroundColor = MaterialTheme.colors.surface
                            )
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            roleOptions.forEach { role ->
                                DropdownMenuItem(
                                    onClick = {
                                        viewModel.updateDefaultRoleForNewUser(if (role == noRole) "" else role)
                                        expanded = false
                                    }
                                ) {
                                    Text(role)
                                }
                            }
                        }
                    }
                } else {
                    TextField(
                        value = uiState.defaultRoleForNewUser,
                        onValueChange = { viewModel.updateDefaultRoleForNewUser(it) },
                        label = { Text(stringResource(Res.string.role_name)) },
                        placeholder = { Text(stringResource(Res.string.role_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.textFieldColors(
                            backgroundColor = MaterialTheme.colors.surface
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // New User Root Directory Pattern
                Text(
                    text = stringResource(Res.string.assign_root_folder_pattern),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                TextField(
                    value = uiState.newUserDirPattern,
                    onValueChange = { viewModel.updateNewUserDirPattern(it) },
                    label = { Text(stringResource(Res.string.directory_pattern)) },
                    placeholder = { Text(stringResource(Res.string.not_set)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = MaterialTheme.colors.surface
                    )
                )
                
                Text(
                    text = stringResource(Res.string.directory_pattern_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Roles Management Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.roles_management),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.secondaryVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Button(
                    onClick = {
                        if (uiState.storeUsersInDatabase) {
                            onNavigateToRoles()
                        } else {
                            viewModel.showMessageDialog(
                                dbNotEnabledTitle,
                                dbMustEnableRolesMsg
                            )
                        }
                    },
                    enabled = uiState.storeUsersInDatabase,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.primary
                    )
                ) {
                    Text(stringResource(Res.string.edit_roles))
                }
                
                if (!uiState.storeUsersInDatabase) {
                    Text(
                        text = stringResource(Res.string.enable_store_users_to_manage_roles),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        }
        
        VerticalScrollbar(
            modifier = Modifier
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

@Composable
fun UserItem(
    user: User,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onEdit() },
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = user.identity,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                if (user.identity == User.GUEST_IDENTITY) {
                    Text(
                        text = stringResource(Res.string.guest_user),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            Box {
                IconButton(
                    onClick = { showMenu = true }
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.cd_options))
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        onClick = {
                            onEdit()
                            showMenu = false
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.cd_edit))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.edit))
                    }
                    DropdownMenuItem(
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.delete))
                    }
                }
            }
        }
    }
}