package com.phlox.simpleserver.screens.auth.roles

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.auth.UserRole
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.phlox.simpleserver.theme.AppTheme
import java.util.EnumSet

@Composable
fun RolesListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRoleDetails: (UserRole) -> Unit,
    config: AppConfig,
    shttpsApp: SHTTPSApp
) {
    val viewModel: RolesListViewModel = viewModel { RolesListViewModel(config, shttpsApp) }
    val uiState by viewModel.uiState.collectAsState()
    val newRoleName = stringResource(Res.string.new_role)
    
    // Refresh roles when screen becomes visible
    LaunchedEffect(Unit) {
        viewModel.refreshRoles()
    }
    
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
                        text = stringResource(Res.string.roles),
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
                                viewModel.refreshRoles()
                                expanded = false
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.cd_refresh))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.refresh))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Roles Section
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
                            text = stringResource(Res.string.roles_count, uiState.roles.size),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant
                        )
                        
                        Button(
                            onClick = { 
                            val newRole = UserRole(
                                newRoleName,
                                EnumSet.noneOf(User.FileSystemRights::class.java),
                                EnumSet.noneOf(User.DBRights::class.java),
                                null,
                                EnumSet.noneOf(User.SystemRights::class.java)
                            )
                                onNavigateToRoleDetails(newRole)
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_role))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.add_role))
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
                    } else if (uiState.roles.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.no_roles_found),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier.height(300.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                            ) {
                                uiState.roles.forEach { role ->
                                    RoleItem(
                                        role = role,
                                        onEdit = { onNavigateToRoleDetails(role) },
                                        onDelete = { viewModel.deleteRole(role) }
                                    )
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
        }
    }

    if (uiState.showMessageDialog) {
        MessageDialog(uiState.messageDialogData)
    }
}

@Composable
fun RoleItem(
    role: UserRole,
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
                    text = role.name,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                Text(
                    text = stringResource(Res.string.file_rights_summary, role.fsRights.size, role.dbRights.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
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

@Preview
@Composable
fun RoleItemPreview() {
    AppTheme {
        Surface {
            RoleItem(
                role = UserRole(
                    "Editors",
                    EnumSet.of(User.FileSystemRights.READ, User.FileSystemRights.LIST_CONTENTS),
                    EnumSet.of(User.DBRights.READ, User.DBRights.READ_SCHEMA),
                    null,
                    EnumSet.noneOf(User.SystemRights::class.java)
                ),
                onEdit = {},
                onDelete = {}
            )
        }
    }
}
