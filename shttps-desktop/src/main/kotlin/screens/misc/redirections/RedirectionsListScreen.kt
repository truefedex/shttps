package com.phlox.simpleserver.screens.misc.redirections

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
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
import androidx.compose.material.icons.filled.MoreVert
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
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware.RedirectRule

@Composable
fun RedirectionsListScreen(
    viewModel: RedirectionsListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Int, RedirectRule) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val state = rememberLazyListState()
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
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
                    text = stringResource(Res.string.redirect_rules),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // Redirect to Index toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = uiState.redirectToIndex,
                        onCheckedChange = { viewModel.toggleRedirectToIndex() }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.redirect_to_index_html),
                        style = MaterialTheme.typography.body1
                    )
                }
            }

            // Redirect Rules List
            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.redirect_rules),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp), 
                            state = state,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(uiState.rules) { index, rule ->
                                RedirectRuleItem(
                                    rule = rule,
                                    index = index,
                                    onEdit = { onNavigateToEdit(index, rule) },
                                    onDelete = { viewModel.deleteRedirectRule(index) },
                                    onMoveUp = { viewModel.moveRedirectRuleUp(index) },
                                    onMoveDown = { viewModel.moveRedirectRuleDown(index) },
                                    onToggleEnabled = { viewModel.toggleRedirectRuleEnabled(index) },
                                    canMoveUp = index > 0,
                                    canMoveDown = index < uiState.rules.size - 1
                                )
                            }
                            
                            if (uiState.rules.isEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.no_redirect_rules),
                                        style = MaterialTheme.typography.body2,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(end = 8.dp),
                            adapter = rememberScrollbarAdapter(
                                scrollState = state
                            )
                        )
                    }

                    Button(
                        onClick = { 
                            // Navigate to edit screen for new rule
                            val newRule = RedirectRule("/", "/", 301, true, "")
                            onNavigateToEdit(-1, newRule)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(Res.string.add_redirect_rule))
                    }
                }
            }
        }
    }
}

@Composable
private fun RedirectRuleItem(
    rule: RedirectRule,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.from.ifEmpty { stringResource(Res.string.no_pattern) },
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.body1
                    )
                    Text(
                        text = stringResource(Res.string.redirect_arrow, rule.to.ifEmpty { stringResource(Res.string.no_destination) }),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    if (rule.comment.isNotEmpty()) {
                        Text(
                            text = rule.comment,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = stringResource(Res.string.http_status, rule.code),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.secondaryVariant
                    )
                }
                
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(Res.string.cd_more)
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                showMenu = false
                                onEdit()
                            }
                        ) {
                            Text(stringResource(Res.string.edit))
                        }
                        DropdownMenuItem(
                            onClick = {
                                showMenu = false
                                onMoveUp()
                            },
                            enabled = canMoveUp
                        ) {
                            Text(stringResource(Res.string.move_up))
                        }
                        DropdownMenuItem(
                            onClick = {
                                showMenu = false
                                onMoveDown()
                            },
                            enabled = canMoveDown
                        ) {
                            Text(stringResource(Res.string.move_down))
                        }
                        DropdownMenuItem(
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        ) {
                            Text(stringResource(Res.string.delete))
                        }
                    }
                }
            }
        }
    }
}
