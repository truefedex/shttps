package com.phlox.simpleserver.screens.misc.cors

import androidx.compose.ui.tooling.preview.Preview
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
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware
import com.phlox.simpleserver.theme.AppTheme
import org.jetbrains.compose.resources.stringResource
import com.phlox.simpleserver.shttps_desktop.generated.resources.*

@Composable
fun CORSRulesScreen(
    viewModel: CORSRulesListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Int, CORSMiddleware.CORSRule) -> Unit
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
                Column {
                    Text(
                        text = stringResource(Res.string.configure_cors),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground
                    )
                    Text(
                        text = stringResource(Res.string.cors_rules_screen_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // CORS Rules List
            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.configure_cors),
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
                                CORSRuleItem(
                                    rule = rule,
                                    index = index,
                                    onEdit = { onNavigateToEdit(index, rule) },
                                    onDelete = { viewModel.deleteCORSRule(index) },
                                    onMoveUp = { viewModel.moveCORSRuleUp(index) },
                                    onMoveDown = { viewModel.moveCORSRuleDown(index) },
                                    canMoveUp = index > 0,
                                    canMoveDown = index < uiState.rules.size - 1
                                )
                            }

                            if (uiState.rules.isEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.cors_rules_screen_hint),
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
                            adapter = rememberScrollbarAdapter(scrollState = state)
                        )
                    }

                    Button(
                        onClick = {
                            val newRule = CORSMiddleware.CORSRule().apply {
                                origin = "*"
                                allowMethods = null
                                allowHeaders = null
                                allowCredentials = null
                                exposeHeaders = null
                                maxAge = 0
                            }
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
                        Text(stringResource(Res.string.add_cors_rule))
                    }
                }
            }
        }
    }
}

@Composable
private fun CORSRuleItem(
    rule: CORSMiddleware.CORSRule,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.origin ?: "*",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.body1
                    )
                    val parts = buildList {
                        rule.allowMethods?.takeIf { it.isNotEmpty() }?.let {
                            add(stringResource(Res.string.cors_methods_summary, it.joinToString(", ")))
                        }
                        rule.allowHeaders?.takeIf { it.isNotEmpty() }?.let {
                            add(stringResource(Res.string.cors_headers_summary, it.joinToString(", ")))
                        }
                        if (rule.allowCredentials == true) {
                            add(stringResource(Res.string.cors_credentials_summary))
                        }
                        if ((rule.maxAge ?: 0) > 0) {
                            add(stringResource(Res.string.cors_max_age_summary, rule.maxAge!!))
                        }
                    }
                    Text(
                        text = parts.ifEmpty { listOf(stringResource(Res.string.cors_default_settings)) }.joinToString(" • "),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
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

@Preview
@Composable
fun CORSRuleItemPreview() {
    AppTheme {
        Surface {
            val rule = remember {
                CORSMiddleware.CORSRule().apply {
                    origin = "https://example.com"
                    allowMethods = arrayOf("GET", "POST")
                    allowHeaders = arrayOf("Content-Type", "Authorization")
                    allowCredentials = true
                    maxAge = 3600
                }
            }
            CORSRuleItem(
                rule = rule,
                index = 0,
                onEdit = {},
                onDelete = {},
                onMoveUp = {},
                onMoveDown = {},
                canMoveUp = false,
                canMoveDown = true
            )
        }
    }
}
