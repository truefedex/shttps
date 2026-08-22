package com.phlox.simpleserver.screens.handlers

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.exec.CgiType
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun CgiListScreen(
    viewModel: CgiListViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCgiTypeDetails: (Int?, CgiType?) -> Unit,
    navController: NavHostController? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var urlPathPrefixText by remember { mutableStateOf(uiState.cgiPathPrefix ?: "") }
    
    // Update text field when state changes
    LaunchedEffect(uiState.cgiPathPrefix) {
        urlPathPrefixText = uiState.cgiPathPrefix ?: ""
    }
    
    // Refresh when returning from details screen
    if (navController != null) {
        val backStackEntry = remember { navController.currentBackStackEntry!! }
        val savedStateHandle = backStackEntry.savedStateHandle
        val cgiTypesWasEdited by savedStateHandle.getStateFlow("cgiTypesWasEdited", initialValue = false)
            .collectAsState()
        
        LaunchedEffect(cgiTypesWasEdited) {
            if (cgiTypesWasEdited) {
                viewModel.refreshCgiTypes()
                savedStateHandle["cgiTypesWasEdited"] = false
            }
        }
    }

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
                    text = stringResource(Res.string.cgi_configuration),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // CGI Folder button
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (uiState.cgiFolder != null) {
                        viewModel.updateCgiFolder(null)
                    } else {
                        scope.launch {
                            val directory = FileKit.openDirectoryPicker()
                            if (directory != null) {
                                viewModel.updateCgiFolder(directory.absolutePath())
                            }
                        }
                    }
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.cgi_root_folder),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = uiState.cgiFolder ?: stringResource(Res.string.not_set_same_as_document_root),
                            fontWeight = FontWeight.Light
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (uiState.cgiFolder != null) Icons.Default.Clear else Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colors.onPrimary,
                        modifier = Modifier.width(32.dp).height(32.dp)
                    )
                }
            }

            // Root dir warning (when CGI folder not set and allow editing is on)
            if (uiState.cgiFolder == null && uiState.allowEditing) {
                Text(
                    text = stringResource(Res.string.cgi_doc_the_same_and_writable_warning),
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // URL Path Prefix
            Text(
                text = stringResource(Res.string.url_path_prefix),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            TextField(
                value = urlPathPrefixText,
                onValueChange = { newValue ->
                    urlPathPrefixText = newValue
                    viewModel.updateCgiPathPrefix(newValue)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(Res.string.e_g_cgi_bin)) }
            )

            // CGI Types section
            Text(
                text = stringResource(Res.string.types),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            // List of CGI types
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                elevation = 2.dp
            ) {
                if (uiState.cgiTypes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.no_cgi_types_configured),
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(uiState.cgiTypes) { index, cgiType ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable {
                                        onNavigateToCgiTypeDetails(index, cgiType)
                                    },
                                elevation = 1.dp
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = ".${cgiType.extension}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = buildString {
                                            append(cgiType.mode.name)
                                            if (cgiType.executeWith != null && cgiType.executeWith!!.isNotEmpty()) {
                                                append(" - ")
                                                append(stringResource(Res.string.cgi_execute_with_summary, cgiType.executeWith!!))
                                            }
                                            if (cgiType.executionTimeout != null) {
                                                if (length > 0) append(" - ")
                                                append(stringResource(Res.string.cgi_timeout_summary, cgiType.executionTimeout!!))
                                            }
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Add CGI Type button
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onNavigateToCgiTypeDetails(null, null)
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.add_file_type))
            }
        }

        // Vertical scrollbar
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 8.dp),
            adapter = rememberScrollbarAdapter(state),
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
