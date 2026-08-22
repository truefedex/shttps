package com.phlox.simpleserver.screens.misc.headers

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.openUrlInBrowser
import org.jetbrains.compose.resources.stringResource

private val COMMON_HEADERS = listOf(
    "Cache-Control",
    "Content-Disposition",
    "Content-Encoding",
    "Content-Language",
    "Content-Security-Policy",
    "Content-Type",
    "Cross-Origin-Opener-Policy",
    "Cross-Origin-Resource-Policy",
    "ETag",
    "Expires",
    "Last-Modified",
    "Location",
    "Referrer-Policy",
    "Server",
    "Strict-Transport-Security",
    "Vary",
    "X-Content-Type-Options",
    "X-Frame-Options",
    "X-XSS-Protection",
)

@Composable
fun HeadersOverrideHeadersEditorDialog(
    rule: HeadersOverrideRule,
    onDismiss: () -> Unit,
    onSave: (HeadersOverrideRule) -> Unit,
) {
    val headers = remember(rule) { mutableStateListOf<HeaderEntry>().apply { addAll(rule.headers) } }
    var showEditEntry by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(Res.string.edit_headers), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    editingIndex = -1
                    showEditEntry = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.cd_add))
                }
            }
        },
        text = {
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(headers) { index, entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        entry.value,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                                TextButton(onClick = {
                                    editingIndex = index
                                    showEditEntry = true
                                }) { Text(stringResource(Res.string.edit)) }
                                IconButton(onClick = { headers.removeAt(index) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.cd_delete))
                                }
                            }
                        }

                        if (headers.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.no_headers),
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(listState)
                )
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onSave(rule.copy(headers = headers.toList())) }) { Text(stringResource(Res.string.save)) }
            }
        }
    )

    if (showEditEntry) {
        val initial = if (editingIndex in headers.indices) headers[editingIndex] else HeaderEntry("", "")
        HeaderEntryEditorDialog(
            initial = initial,
            onDismiss = { showEditEntry = false },
            onSave = { updated ->
                if (editingIndex in headers.indices) {
                    headers[editingIndex] = updated
                } else {
                    headers.add(updated)
                }
                showEditEntry = false
            }
        )
    }
}

@Composable
private fun HeaderEntryEditorDialog(
    initial: HeaderEntry,
    onDismiss: () -> Unit,
    onSave: (HeaderEntry) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var value by remember(initial) { mutableStateOf(initial.value) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val exampleFmt = stringResource(Res.string.header_example)
    val exampleDepends = stringResource(Res.string.header_example_depends)
    val exampleText = remember(name, exampleFmt, exampleDepends) {
        getHeaderValueExample(name, exampleFmt, exampleDepends)
    }
    val canOpenDocs = name.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) stringResource(Res.string.add_header) else stringResource(Res.string.edit_header), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.common_headers), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { dropdownExpanded = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(Res.string.cd_choose))
                    }
                    DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                        COMMON_HEADERS.forEach { h ->
                            DropdownMenuItem(onClick = {
                                dropdownExpanded = false
                                name = h
                            }) { Text(h) }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.header_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(Res.string.header_value)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = exampleText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
                )

                TextButton(
                    onClick = {
                        if (!canOpenDocs) return@TextButton
                        val url = "https://developer.mozilla.org/ru/docs/Web/HTTP/Reference/Headers/${name.trim()}"
                        openUrlInBrowser(url)
                    },
                    enabled = canOpenDocs
                ) {
                    Text(stringResource(Res.string.open_mdn_documentation))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(HeaderEntry(name.trim(), value)) },
                enabled = name.trim().isNotEmpty()
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

private fun getHeaderValueExample(headerName: String, exampleFmt: String, depends: String): String {
    val h = headerName.trim().lowercase()
    val sample = when (h) {
        "content-type" -> "text/html; charset=utf-8"
        "cache-control" -> "no-store"
        "content-disposition" -> "attachment; filename=\"file.txt\""
        "content-security-policy" -> "default-src 'self'"
        "strict-transport-security" -> "max-age=31536000; includeSubDomains"
        "x-content-type-options" -> "nosniff"
        "x-frame-options" -> "DENY"
        "location" -> "https://example.com/"
        "vary" -> "Accept-Encoding"
        else -> return depends
    }
    return exampleFmt.replace("%1\$s", sample)
}

