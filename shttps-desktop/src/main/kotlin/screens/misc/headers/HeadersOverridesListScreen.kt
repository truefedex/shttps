package com.phlox.simpleserver.screens.misc.headers

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware

@Composable
fun HeadersOverridesListScreen(
    viewModel: HeadersOverridesViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Int) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                    text = stringResource(Res.string.response_headers_overrides),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val newIndex = uiState.rules.size
                    viewModel.addRule(
                        HeadersOverrideRule(
                            path = "/",
                            ifHeadersExist = CustomHeadersMiddleware.IfHeadersExist.OVERRIDE,
                        )
                    )
                    onNavigateToEdit(newIndex)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.cd_add))
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp,
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 12.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(uiState.rules) { index, rule ->
                                HeadersOverrideRuleItem(
                                    rule = rule,
                                    index = index,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < uiState.rules.size - 1,
                                    onEdit = { onNavigateToEdit(index) },
                                    onDelete = { viewModel.deleteRule(index) },
                                    onMoveUp = { viewModel.moveUp(index) },
                                    onMoveDown = { viewModel.moveDown(index) }
                                )
                            }

                            if (uiState.rules.isEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.no_header_rules),
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }

                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(listState)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadersOverrideRuleItem(
    rule: HeadersOverrideRule,
    index: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onEdit() },
        elevation = 2.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.path.ifBlank { "/" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                val summary = buildString {
                    append(stringResource(Res.string.headers_summary, rule.headers.size, ifHeadersExistLabel(rule.ifHeadersExist)))
                    if (!rule.filterMethods.isNullOrEmpty()) {
                        append(" • ")
                        append(stringResource(Res.string.filter_methods_summary, rule.filterMethods.joinToString(", ")))
                    }
                    if (!rule.filterStatusCodes.isNullOrEmpty()) {
                        append(" • ")
                        append(stringResource(Res.string.filter_status_summary, rule.filterStatusCodes.joinToString(", ")))
                    }
                    if (!rule.filterPostfixes.isNullOrEmpty()) {
                        append(" • ")
                        append(stringResource(Res.string.filter_postfixes_summary, rule.filterPostfixes.joinToString(", ")))
                    }
                }
                Text(
                    text = summary,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }

            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.cd_more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onEdit()
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.edit))
                        }
                    }
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            if (canMoveUp) onMoveUp()
                        },
                        enabled = canMoveUp
                    ) { Text(stringResource(Res.string.move_up)) }
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            if (canMoveDown) onMoveDown()
                        },
                        enabled = canMoveDown
                    ) { Text(stringResource(Res.string.move_down)) }
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onDelete()
                        }
                    ) { Text(stringResource(Res.string.delete)) }
                }
            }
        }
    }
}

