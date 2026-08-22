package com.phlox.simpleserver.dialogs

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import com.phlox.simpleserver.theme.AppTheme
import com.phlox.server.utils.docfile.DocumentFile
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.clear
import com.phlox.simpleserver.shttps_desktop.generated.resources.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Folder chooser restricted to the server root folder and its subfolders.
 *
 * Navigation is done over [DocumentFile] starting at [root], so there is no way to step outside
 * of the served folder tree. The value reported by [onResult] is a path relative to [root]
 * (without leading/trailing separators) - the form expected by
 * DocumentFileUtils.checkOrCreateUserDir() - or null when the root folder itself is chosen or the
 * selection is cleared.
 */
@Composable
fun RootSubfolderPickerDialog(
    root: DocumentFile,
    initialRelativePath: String?,
    onResult: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    //path from the root to the currently browsed folder, first element is always the root itself
    val path = remember { mutableStateListOf(root) }
    var pathInitialized by remember { mutableStateOf(false) }
    var subFolders by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val currentDir = path.last()
    val relativePath = remember(path.size, currentDir) {
        path.drop(1).joinToString("/") { it.name }
    }

    //start browsing from the folder currently assigned to the user (if it still resolves)
    LaunchedEffect(Unit) {
        val resolved = withContext(Dispatchers.IO) { resolveSubFolder(root, initialRelativePath) }
        path.addAll(resolved)
        pathInitialized = true
    }

    LaunchedEffect(currentDir, pathInitialized) {
        if (!pathInitialized) return@LaunchedEffect
        loading = true
        subFolders = withContext(Dispatchers.IO) {
            (currentDir.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (relativePath.isEmpty()) "/" else relativePath,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Divider()

                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)) {
                        if (path.size > 1) {
                            item {
                                FolderRow(
                                    name = "..",
                                    icon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = { path.removeAt(path.lastIndex) }
                                )
                            }
                        }
                        items(subFolders) { folder ->
                            FolderRow(
                                name = folder.name,
                                icon = {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = { path.add(folder) }
                            )
                        }
                    }
                }

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onResult(null) }) {
                        Text(stringResource(Res.string.clear))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onResult(relativePath.ifEmpty { null }) }) {
                        Text(stringResource(Res.string.set))
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colors.surface)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = name, modifier = Modifier.weight(1f))
    }
}

/**
 * Resolves [relativePath] against [root] and returns the chain of folders leading to it, excluding
 * the root itself. Returns an empty list when the path is empty or does not point to an existing
 * subfolder of [root] - which is the case for values stored by older app versions, when an
 * arbitrary absolute path could be assigned.
 */
private fun resolveSubFolder(root: DocumentFile, relativePath: String?): List<DocumentFile> {
    if (relativePath.isNullOrEmpty()) return emptyList()
    val chain = ArrayList<DocumentFile>()
    var current = root
    for (name in relativePath.replace('\\', '/').split('/')) {
        if (name.isEmpty()) continue
        val child = current.findFile(name) ?: return emptyList()
        if (!child.isDirectory) return emptyList()
        chain.add(child)
        current = child
    }
    return chain
}

@Preview
@Composable
fun FolderRowPreview() {
    AppTheme {
        Surface {
            Column {
                FolderRow(
                    name = "..",
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {}
                )
                FolderRow(
                    name = "public_html",
                    icon = {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {}
                )
            }
        }
    }
}
