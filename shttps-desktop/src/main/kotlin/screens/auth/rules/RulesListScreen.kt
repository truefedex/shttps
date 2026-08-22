package com.phlox.simpleserver.screens.auth.rules

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
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.auth.DBAccessRule
import com.phlox.simpleserver.auth.FSAccessRule
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.openUrlInBrowser
import org.jetbrains.compose.resources.stringResource

private const val ACCESS_RULES_DOCUMENTATION_URL = "https://shttps.phlox.dev/articles/security/rules/"

@Composable
fun RulesListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRuleDetails: (String, String, Any?) -> Unit, // ruleType, roleName, rule
    ruleType: String,
    roleName: String,
    config: AppConfig,
    shttpsApp: SHTTPSApp
) {
    val viewModel: RulesListViewModel = viewModel { RulesListViewModel(config, shttpsApp, ruleType, roleName) }
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshRules()
    }

    var showHeaderMenu by remember { mutableStateOf(false) }

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
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.access_rules_alpha),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.onBackground
                        )
                        Text(
                            text = if (ruleType == "db") stringResource(Res.string.db_access_rules_management) else stringResource(Res.string.fs_access_rules_management),
                            fontSize = 14.sp,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showHeaderMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.cd_menu))
                    }
                    DropdownMenu(
                        expanded = showHeaderMenu,
                        onDismissRequest = { showHeaderMenu = false }
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                openUrlInBrowser(ACCESS_RULES_DOCUMENTATION_URL)
                                showHeaderMenu = false
                            }
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.menu_documentation))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Rules Section
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
                            text = stringResource(Res.string.rules_count, uiState.rules.size),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.secondaryVariant
                        )

                        Button(
                            onClick = { onNavigateToRuleDetails(ruleType, roleName, null) },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.add_rule))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.add_rule))
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
                    } else if (uiState.rules.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.no_rules_found),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier.height(400.dp)
                        ) {
                            val scrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                            ) {
                                uiState.rules.forEach { rule ->
                                    RuleItem(
                                        rule = rule,
                                        isDBRule = ruleType == "db",
                                        onEdit = { onNavigateToRuleDetails(ruleType, roleName, rule) },
                                        onDelete = { viewModel.deleteRule(rule) }
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
fun RuleItem(
    rule: Any,
    isDBRule: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val subject = if (isDBRule) {
        (rule as DBAccessRule).subject
    } else {
        (rule as FSAccessRule).subject
    }
    val operation = if (isDBRule) {
        (rule as DBAccessRule).operation
    } else {
        (rule as FSAccessRule).operation
    }
    val expression = if (isDBRule) {
        (rule as DBAccessRule).expression
    } else {
        (rule as FSAccessRule).expression
    }
    val allow = if (isDBRule) {
        (rule as DBAccessRule).allow
    } else {
        (rule as FSAccessRule).allow
    }
    
    val statusText = if (expression != null && expression.isNotEmpty()) {
        stringResource(Res.string.custom_logic)
    } else {
        if (allow) stringResource(Res.string.allow) else stringResource(Res.string.deny)
    }

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
                    text = "$subject - $operation",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface
                )
                Text(
                    text = statusText,
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

