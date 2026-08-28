package com.phlox.simpleserver.screens.misc.cors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
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
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware
import org.jetbrains.compose.resources.stringResource
import com.phlox.simpleserver.shttps_desktop.generated.resources.*

@Composable
fun CORSEditScreen(
    rule: CORSMiddleware.CORSRule?,
    ruleIndex: Int,
    onNavigateBack: () -> Unit,
    onSave: (CORSMiddleware.CORSRule, Int) -> Unit
) {
    var origin by remember { mutableStateOf(rule?.origin?.takeIf { it.isNotEmpty() } ?: "*") }
    var allowMethods by remember {
        mutableStateOf(rule?.allowMethods?.joinToString(", ") ?: "")
    }
    var allowHeaders by remember {
        mutableStateOf(rule?.allowHeaders?.joinToString(", ") ?: "")
    }
    var allowCredentials by remember {
        mutableStateOf(rule?.allowCredentials == true)
    }
    var exposeHeaders by remember {
        mutableStateOf(rule?.exposeHeaders?.joinToString(", ") ?: "")
    }
    var maxAge by remember {
        mutableStateOf(if ((rule?.maxAge ?: 0) > 0) rule!!.maxAge.toString() else "")
    }

    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
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
                    text = if (ruleIndex >= 0) stringResource(Res.string.edit_cors_rule) else stringResource(Res.string.add_cors_rule),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // Form Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.configure_cors),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    OutlinedTextField(
                        value = origin,
                        onValueChange = { origin = it },
                        label = { Text(stringResource(Res.string.cors_origin)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.cors_origin_hint)) }
                    )

                    OutlinedTextField(
                        value = allowMethods,
                        onValueChange = { allowMethods = it },
                        label = { Text(stringResource(Res.string.cors_allow_methods)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.cors_allow_methods_hint)) }
                    )

                    OutlinedTextField(
                        value = allowHeaders,
                        onValueChange = { allowHeaders = it },
                        label = { Text(stringResource(Res.string.cors_allow_headers)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        placeholder = { Text(stringResource(Res.string.cors_allow_headers_hint)) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allowCredentials,
                            onCheckedChange = { allowCredentials = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.cors_allow_credentials),
                            style = MaterialTheme.typography.body1
                        )
                    }

                    OutlinedTextField(
                        value = exposeHeaders,
                        onValueChange = { exposeHeaders = it },
                        label = { Text(stringResource(Res.string.cors_expose_headers)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        placeholder = { Text(stringResource(Res.string.cors_expose_headers_hint)) }
                    )

                    OutlinedTextField(
                        value = maxAge,
                        onValueChange = { maxAge = it },
                        label = { Text(stringResource(Res.string.cors_max_age)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.cors_max_age_hint)) }
                    )

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateBack,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.surface
                            )
                        ) {
                            Text(stringResource(Res.string.cancel), color = MaterialTheme.colors.onSurface)
                        }

                        Button(
                            onClick = {
                                val result = CORSMiddleware.CORSRule().apply {
                                    this.origin = origin.trim().ifEmpty { "*" }
                                    this.allowMethods = allowMethods.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray().takeIf { it.isNotEmpty() }
                                    this.allowHeaders = allowHeaders.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray().takeIf { it.isNotEmpty() }
                                    this.allowCredentials = if (allowCredentials) true else null
                                    this.exposeHeaders = exposeHeaders.trim().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray().takeIf { it.isNotEmpty() }
                                    this.maxAge = maxAge.trim().toIntOrNull() ?: 0
                                }
                                onSave(result, ruleIndex)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(stringResource(Res.string.save))
                        }
                    }
                }
            }
        }
    }
}
