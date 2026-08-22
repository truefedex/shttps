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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
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

private val COMMON_METHODS = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

@Composable
fun HeadersOverrideFiltersEditorDialog(
    rule: HeadersOverrideRule,
    onDismiss: () -> Unit,
    onSave: (HeadersOverrideRule) -> Unit,
) {
    var selectedMethods by remember(rule) {
        mutableStateOf<Set<String>>(rule.filterMethods?.toSet() ?: emptySet())
    }
    var statusCodesText by remember(rule) { mutableStateOf(rule.filterStatusCodes?.joinToString(", ") ?: "") }
    var postfixesText by remember(rule) { mutableStateOf(rule.filterPostfixes?.joinToString(", ") ?: "") }

    var error by remember { mutableStateOf<String?>(null) }
    val invalidStatusCodesMsg = stringResource(Res.string.invalid_status_codes)

    fun parseStatusCodes(raw: String): Set<Int>? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val out = mutableSetOf<Int>()
        val parts = t.split(",")
        for (p in parts) {
            val s = p.trim()
            if (s.isEmpty()) continue
            val v = s.toIntOrNull() ?: return null
            if (v <= 0) return null
            out.add(v)
        }
        return out.ifEmpty { null }
    }

    fun parsePostfixes(raw: String): Set<String>? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val out = t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return out.ifEmpty { null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.filters), fontWeight = FontWeight.Bold) },
        text = {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(Res.string.methods), fontWeight = FontWeight.Medium)
                    COMMON_METHODS.forEach { method ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val checked = selectedMethods.contains(method)
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedMethods = if (isChecked) {
                                        (selectedMethods + method)
                                    } else {
                                        (selectedMethods - method)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(method)
                        }
                    }

                    OutlinedTextField(
                        value = statusCodesText,
                        onValueChange = {
                            statusCodesText = it
                            error = null
                        },
                        label = { Text(stringResource(Res.string.status_codes)) },
                        placeholder = { Text(stringResource(Res.string.status_codes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = postfixesText,
                        onValueChange = { postfixesText = it },
                        label = { Text(stringResource(Res.string.path_postfixes)) },
                        placeholder = { Text(stringResource(Res.string.path_postfixes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (error != null) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp
                        )
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    adapter = rememberScrollbarAdapter(scrollState)
                )
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val codes = parseStatusCodes(statusCodesText)
                    if (statusCodesText.trim().isNotEmpty() && codes == null) {
                        error = invalidStatusCodesMsg
                        return@Button
                    }
                    val postfixes = parsePostfixes(postfixesText)
                    val methods = selectedMethods
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                        .takeIf { it.isNotEmpty() }
                    onSave(
                        rule.copy(
                            filterMethods = methods,
                            filterStatusCodes = codes,
                            filterPostfixes = postfixes,
                        )
                    )
                }) { Text(stringResource(Res.string.save)) }
            }
        }
    )
}

