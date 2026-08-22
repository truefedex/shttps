package com.phlox.simpleserver.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import com.phlox.simpleserver.utils.ServerLogsCollector
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MasterDetailBreakpoint = 600.dp
private const val DialogTitleMaxPath = 96

@Composable
fun LogsScreen(
    viewModel: LogsViewModel,
    @Suppress("UNUSED_PARAMETER") navController: NavHostController,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var filterText by remember { mutableStateOf("") }
    var dialogEntry by remember { mutableStateOf<ServerLogsCollector.LogEntry?>(null) }
    val detailLabels = rememberLogEntryDetailLabels()
    val exportLogsTitle = stringResource(Res.string.export_logs)
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = MaterialTheme.colors.onBackground,
                    contentDescription = stringResource(Res.string.cd_back),
                )
            }
            Text(
                text = stringResource(Res.string.server_logs),
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.togglePause() }) {
                Icon(
                    imageVector = if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    tint = MaterialTheme.colors.onBackground,
                    contentDescription = if (uiState.isPaused) stringResource(Res.string.cd_resume) else stringResource(Res.string.cd_pause),
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        tint = MaterialTheme.colors.onBackground,
                        contentDescription = stringResource(Res.string.cd_more),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        enabled = uiState.filteredLogs.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            exportLogs(uiState.filteredLogs, exportLogsTitle, detailLabels)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.export_logs))
                    }
                    DropdownMenuItem(
                        enabled = uiState.logs.isNotEmpty(),
                        onClick = {
                            menuExpanded = false
                            filterText = ""
                            viewModel.setFilter("")
                            dialogEntry = null
                            viewModel.clearLogs()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.clear_logs))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.logs.isNotEmpty()) {
            OutlinedTextField(
                value = filterText,
                onValueChange = {
                    filterText = it
                    viewModel.setFilter(it)
                },
                label = { Text(stringResource(Res.string.filter_logs)) },
                placeholder = { Text(stringResource(Res.string.filter_logs_placeholder)) },
                trailingIcon = {
                    if (filterText.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                filterText = ""
                                viewModel.setFilter("")
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(Res.string.cd_clear_filter),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.setFilter(filterText) },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val isWide = maxWidth >= MasterDetailBreakpoint

            LaunchedEffect(isWide) {
                if (!isWide) {
                    viewModel.selectEntry(null)
                }
            }

            LaunchedEffect(isWide, uiState.filteredLogs, uiState.selectedEntry) {
                if (isWide && uiState.filteredLogs.isNotEmpty() && uiState.selectedEntry == null) {
                    viewModel.selectEntry(uiState.filteredLogs.first())
                }
            }

            if (uiState.logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.no_logs_available),
                            style = MaterialTheme.typography.h6,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.start_server_to_see_logs),
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            } else if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = uiState.filteredLogs,
                            key = { System.identityHashCode(it) },
                        ) { logEntry ->
                            LogEntryCard(
                                logEntry = logEntry,
                                isSelected = logEntry === uiState.selectedEntry,
                                onClick = { viewModel.selectEntry(logEntry) },
                            )
                        }
                    }
                    LogDetailSidePane(
                        entry = uiState.selectedEntry,
                        labels = detailLabels,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = uiState.filteredLogs,
                        key = { System.identityHashCode(it) },
                    ) { logEntry ->
                        LogEntryCard(
                            logEntry = logEntry,
                            isSelected = false,
                            onClick = { dialogEntry = logEntry },
                        )
                    }
                }
            }
        }
    }

    dialogEntry?.let { entry ->
        LogDetailScrollDialog(
            entry = entry,
            labels = detailLabels,
            onDismiss = { dialogEntry = null },
        )
    }
}

/**
 * Material [AlertDialog]'s text slot clips scrollable content on Compose Desktop.
 * A window [Dialog] with an explicit max-height scroll region avoids top/bottom text cutoff.
 */
@Composable
private fun LogDetailScrollDialog(
    entry: ServerLogsCollector.LogEntry,
    labels: LogEntryDetailLabels,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(entry.startTimeMillis, entry.endTimeMillis, entry.connectionId) {
        scrollState.scrollTo(0)
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(24.dp),
            elevation = 8.dp,
            backgroundColor = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = buildLogDialogTitle(entry),
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 480.dp),
                ) {
                    Text(
                        text = LogEntryDetailFormatter.format(entry, labels),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogDetailSidePane(
    entry: ServerLogsCollector.LogEntry?,
    labels: LogEntryDetailLabels,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.surface, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        if (entry == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.log_detail_placeholder),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            val detailScroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = buildLogDialogTitle(entry),
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = LogEntryDetailFormatter.format(entry, labels),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .fillMaxWidth()
                        .verticalScroll(detailScroll),
                )
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    logEntry: ServerLogsCollector.LogEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val method = logEntry.method.orEmpty()
    val path = logEntry.path.orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = 2.dp,
        backgroundColor = if (isSelected) {
            Color(0x402196F3)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = method,
                    fontWeight = FontWeight.Bold,
                    color = getMethodColor(method),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            getMethodColor(method).copy(alpha = 0.1f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = path,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${logEntry.responseCode} ${logEntry.responsePhrase}",
                    fontWeight = FontWeight.Medium,
                    color = getResponseColor(logEntry.responseCode),
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateFormat.format(Date(logEntry.startTimeMillis)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun buildLogDialogTitle(entry: ServerLogsCollector.LogEntry): String {
    val method = entry.method.orEmpty()
    var path = entry.path.orEmpty()
    if (path.length > DialogTitleMaxPath) {
        path = path.take(DialogTitleMaxPath) + "…"
    }
    return "$method $path".trim()
}

private fun getMethodColor(method: String): Color {
    return when (method.uppercase(Locale.getDefault())) {
        "GET" -> Color(0xFF4CAF50)
        "POST" -> Color(0xFF2196F3)
        "PUT" -> Color(0xFFFF9800)
        "DELETE" -> Color(0xFFF44336)
        "PATCH" -> Color(0xFF9C27B0)
        else -> Color(0xFF607D8B)
    }
}

private fun getResponseColor(code: Int): Color {
    return when (code) {
        in 200..299 -> Color(0xFF4CAF50)
        in 300..399 -> Color(0xFF2196F3)
        in 400..499 -> Color(0xFFFF9800)
        in 500..599 -> Color(0xFFF44336)
        else -> Color(0xFF607D8B)
    }
}

private fun exportLogs(
    entries: List<ServerLogsCollector.LogEntry>,
    title: String,
    labels: LogEntryDetailLabels,
) {
    if (entries.isEmpty()) return
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
    val defaultName = "shttps-logs-$timestamp.txt"
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
        file = defaultName
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    val target = File(dir, name)
    val text = entries.joinToString(separator = "\n\n") { LogEntryDetailFormatter.format(it, labels) }
    try {
        target.writeText(text, Charsets.UTF_8)
    } catch (e: Exception) {
        println("Failed to export logs: ${e.message}")
    }
}
