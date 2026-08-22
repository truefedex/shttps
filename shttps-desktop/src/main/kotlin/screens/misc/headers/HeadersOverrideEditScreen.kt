package com.phlox.simpleserver.screens.misc.headers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
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
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware

@Composable
fun HeadersOverrideEditScreen(
    ruleIndex: Int,
    rule: HeadersOverrideRule,
    onNavigateBack: () -> Unit,
    onSave: (HeadersOverrideRule) -> Unit,
) {
    var path by remember(ruleIndex) { mutableStateOf(rule.path) }
    var ifExists by remember(ruleIndex) { mutableStateOf(rule.ifHeadersExist) }
    var currentRule by remember(ruleIndex) { mutableStateOf(rule) }

    var showHeadersEditor by remember { mutableStateOf(false) }
    var showFiltersEditor by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                    contentDescription = stringResource(Res.string.cd_back)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(Res.string.header_override_rule),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = {
                val updated = currentRule.copy(
                    path = path.trim().ifBlank { "/" },
                    ifHeadersExist = ifExists,
                )
                onSave(updated)
            }) {
                Text(stringResource(Res.string.save))
            }
        }

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(stringResource(Res.string.path_prefix)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        IfExistsDropdown(
            value = ifExists,
            onChange = { ifExists = it },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.headers), fontWeight = FontWeight.Medium)
                Text(
                    stringResource(Res.string.headers_count, currentRule.headers.size),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }
            Button(onClick = { showHeadersEditor = true }) {
                Text(stringResource(Res.string.edit_headers))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.filters), fontWeight = FontWeight.Medium)
                val summary = buildString {
                    val parts = mutableListOf<String>()
                    currentRule.filterMethods?.takeIf { it.isNotEmpty() }?.let {
                        parts.add(stringResource(Res.string.filter_methods_summary, it.joinToString(", ")))
                    }
                    currentRule.filterStatusCodes?.takeIf { it.isNotEmpty() }?.let {
                        parts.add(stringResource(Res.string.filter_status_summary, it.joinToString(", ")))
                    }
                    currentRule.filterPostfixes?.takeIf { it.isNotEmpty() }?.let {
                        parts.add(stringResource(Res.string.filter_postfixes_summary, it.joinToString(", ")))
                    }
                    append(if (parts.isEmpty()) stringResource(Res.string.no_filters) else parts.joinToString(" • "))
                }
                Text(
                    summary,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                    fontSize = 12.sp
                )
            }
            Button(onClick = { showFiltersEditor = true }) {
                Text(stringResource(Res.string.configure))
            }
        }
    }

    if (showHeadersEditor) {
        HeadersOverrideHeadersEditorDialog(
            rule = currentRule,
            onDismiss = { showHeadersEditor = false },
            onSave = { updated ->
                currentRule = updated
                showHeadersEditor = false
            }
        )
    }

    if (showFiltersEditor) {
        HeadersOverrideFiltersEditorDialog(
            rule = currentRule,
            onDismiss = { showFiltersEditor = false },
            onSave = { updated ->
                currentRule = updated
                showFiltersEditor = false
            }
        )
    }
}

@Composable
private fun IfExistsDropdown(
    value: CustomHeadersMiddleware.IfHeadersExist,
    onChange: (CustomHeadersMiddleware.IfHeadersExist) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val options = CustomHeadersMiddleware.IfHeadersExist.values().toList()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.if_header_already_exists), fontWeight = FontWeight.Medium)
            Text(ifHeadersExistLabel(value), color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f))
        }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(Res.string.cd_choose))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(onClick = {
                    expanded = false
                    onChange(opt)
                }) {
                    Text(ifHeadersExistLabel(opt))
                }
            }
        }
    }
}

