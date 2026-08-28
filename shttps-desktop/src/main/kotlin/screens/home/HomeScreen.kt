package com.phlox.simpleserver.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.primarySurface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.phlox.server.utils.docfile.RawDocumentFile
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.components.ExpandableSection
import com.phlox.simpleserver.dialogs.DialogButton
import com.phlox.simpleserver.dialogs.MessageDialog
import com.phlox.simpleserver.dialogs.UpdateAvailableDialog
import com.phlox.simpleserver.screens.home.dialogs.ClientWhiteListDialog
import com.phlox.simpleserver.screens.home.dialogs.ExportChoiceDialog
import com.phlox.simpleserver.screens.home.dialogs.HostnameDialog
import com.phlox.simpleserver.screens.home.dialogs.ImportConfirmDialog
import com.phlox.simpleserver.screens.home.dialogs.InitDatabaseDialog
import com.phlox.simpleserver.screens.home.dialogs.KeyPasswordDialog
import com.phlox.simpleserver.screens.home.dialogs.KeystorePasswordDialog
import com.phlox.simpleserver.screens.home.dialogs.NetworkInterfacesDialog
import com.phlox.simpleserver.screens.home.dialogs.PasswordDialog
import com.phlox.simpleserver.screens.home.dialogs.PortEditDialog
import com.phlox.simpleserver.screens.home.dialogs.RateLimitDialog
import com.phlox.simpleserver.screens.home.dialogs.CharsetDialog
import com.phlox.simpleserver.screens.home.dialogs.ShutdownByInactivityDialog
import com.phlox.simpleserver.screens.home.dialogs.TransactionLimitsDialog
import com.phlox.simpleserver.screens.home.dialogs.UserCredentialsDialog
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.navigation.HeadersOverridesListRoute
import com.phlox.simpleserver.utils.copyToClipboard
import com.phlox.simpleserver.utils.openUrlInBrowser
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

//tags the collapsed state is persisted under; kept identical to the Android app's section tags
private const val SECTION_GENERAL = "general"
private const val SECTION_FILES = "files"
private const val SECTION_DATABASE = "database"
private const val SECTION_HANDLERS = "handlers"
private const val SECTION_CHANNELS = "channels"
private const val SECTION_SCREEN = "screen"
private const val SECTION_SECURITY = "security"
private const val SECTION_MISC = "misc"

private enum class ReachabilityState {
    ALL_INTERFACES,
    //restricted, and the interface selected in the IP info block is among the allowed ones
    RESTRICTED_TO_SHOWN,
    //restricted, but not to the interface whose address is displayed above
    RESTRICTED_TO_OTHER,
    //restricted to interfaces that do not exist anymore, so the server rejects every connection
    NOTHING,
}

private class Reachability(val state: ReachabilityState, val interfaceNames: String)

//the interface selector in the IP info block only chooses which address to display, it does not
//limit the server in any way. This tells what the server actually answers on, so that nobody
//assumes the selector did it for them.
private fun resolveReachability(allowedInterfaces: Array<Int>?, shownInterface: NetworkInterface?): Reachability {
    if (allowedInterfaces == null || allowedInterfaces.isEmpty()) {
        return Reachability(ReachabilityState.ALL_INTERFACES, "")
    }
    val names = StringBuilder()
    var shownInterfaceIsAllowed = false
    for (index in allowedInterfaces) {
        val networkInterface = try {
            NetworkInterface.getByIndex(index)
        } catch (e: Exception) {
            null
        } ?: continue//interface is gone since the moment it was allowed
        if (names.isNotEmpty()) names.append(", ")
        names.append(networkInterface.displayName)
        if (shownInterface != null && networkInterface.index == shownInterface.index) {
            shownInterfaceIsAllowed = true
        }
    }
    return when {
        names.isEmpty() -> Reachability(ReachabilityState.NOTHING, "")
        shownInterfaceIsAllowed -> Reachability(ReachabilityState.RESTRICTED_TO_SHOWN, names.toString())
        else -> Reachability(ReachabilityState.RESTRICTED_TO_OTHER, names.toString())
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    navController: NavHostController,
    onNavigateToRedirectionsListScreen: () -> Unit,
    onNavigateToCORSRulesListScreen: () -> Unit,
    onNavigateToVersionInfo: () -> Unit,
    onNavigateToQr: (String) -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateToAuthDetails: () -> Unit,
    onNavigateToCgiList: () -> Unit,
    onNavigateToChannelsList: () -> Unit,
    window: ComposeWindow,
    onHideAppToTrayValueChange: () -> Unit,
    //whether the main window is actually on screen right now. The update prompt waits for it -
    //see the comment where it is shown below
    windowVisible: State<Boolean>
) {
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val backStackEntry = remember { navController.currentBackStackEntry!! }
    val savedStateHandle = backStackEntry.savedStateHandle
    val redirectsWasEdited by savedStateHandle.getStateFlow("redirectsWasEdited", initialValue = false)
        .collectAsState()
    val corsRulesWasEdited by savedStateHandle.getStateFlow("corsRulesWasEdited", initialValue = false)
        .collectAsState()
    val headersOverridesWasEdited by savedStateHandle.getStateFlow("headersOverridesWasEdited", initialValue = false)
        .collectAsState()
    
    // Snackbar state
    data class SnackbarData(
        val message: String,
        val duration: SnackbarDuration = SnackbarDuration.Short
    )
    var snackbarData by remember { mutableStateOf<SnackbarData?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Port edit dialog state
    var showPortEditDialog by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(uiState.port.toString()) }
    var portError by remember { mutableStateOf<String?>(null) }

    var showInitDatabaseDialog by remember { mutableStateOf(false) }

    var showTransactionLimitsDialog by remember { mutableStateOf(false) }

    var showShutdownByInactivityDialog by remember { mutableStateOf(false) }
    
    // kept for compatibility with old code paths; custom headers UI is now routed to Headers Overrides screen
    
    var showNetworkInterfacesDialog by remember { mutableStateOf(false) }

    var showClientWhiteListDialog by remember { mutableStateOf(false) }

    //positions (in root coordinates) needed to scroll the "Restrict network interfaces" setting
    //into view from the "Reachable on" line
    var scrolledContentYInRoot by remember { mutableStateOf(0f) }
    var restrictInterfacesRowYInRoot by remember { mutableStateOf(0f) }
    var highlightRestrictInterfacesRow by remember { mutableStateOf(false) }

    var showHostnameDialog by remember { mutableStateOf(false) }
    var showCharsetDialog by remember { mutableStateOf(false) }

    var tmpTlsKeyPath: PlatformFile? by remember { mutableStateOf (null) }
    var showKeystorePasswordDialog by remember { mutableStateOf(false) }
    var tmpKeystorePassword: String? by remember { mutableStateOf (null) }
    var showKeyPasswordDialog by remember { mutableStateOf(false) }

    var showExportChoiceDialog by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    // String resources used inside coroutines / non-Composable callbacks.
    val exportInProgressMsg = stringResource(Res.string.export_in_progress)
    val exportDoneMsg = stringResource(Res.string.export_done)
    val exportFailedFmt = stringResource(Res.string.export_failed)
    val importInProgressMsg = stringResource(Res.string.import_in_progress)
    val importDoneMsg = stringResource(Res.string.import_done)
    val importFailedFmt = stringResource(Res.string.import_failed)
    val importServerRunningMsg = stringResource(Res.string.import_server_running)
    val exportDefaultFileName = stringResource(Res.string.export_default_filename)
    val selectBackupArchiveTitle = stringResource(Res.string.select_backup_archive)
    val selectSqliteDatabaseTitle = stringResource(Res.string.select_sqlite_database_file)
    val selectTlsCertTitle = stringResource(Res.string.select_tls_certificate_file)
    val ipCopiedMsg = stringResource(Res.string.ip_copied_to_clipboard)
    val urlCopiedMsg = stringResource(Res.string.url_copied_to_clipboard)
    val portInvalidNumberMsg = stringResource(Res.string.port_invalid_number)
    val portOutOfRangeMsg = stringResource(Res.string.port_out_of_range)

    // Helper function to show snackbar with custom message and duration
    fun showSnackbar(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        snackbarData = SnackbarData(message, duration)
    }

    fun revealRestrictNetworkInterfacesSetting() {
        scope.launch {
            if (SECTION_SECURITY in uiState.collapsedSections) {
                viewModel.setSectionExpanded(SECTION_SECURITY, true)
                //the row has no position at all until it gets composed for the first time (only
                //relevant when the section starts collapsed - afterwards a stale position from
                //before the collapse is still there), and it keeps moving while the section
                //expands, so the delay is what actually waits for the animation to settle
                snapshotFlow { restrictInterfacesRowYInRoot }.first { it != 0f }
                delay(350)
            }
            val offsetInsideContent = restrictInterfacesRowYInRoot - scrolledContentYInRoot
            scrollState.animateScrollTo(
                (scrollState.value + offsetInsideContent - 40f).toInt()
                    .coerceIn(0, scrollState.maxValue)
            )
            highlightRestrictInterfacesRow = true
            delay(1200)
            highlightRestrictInterfacesRow = false
        }
    }

    fun runExport(includeRoot: Boolean) {
        scope.launch {
            val saved = FileKit.openFileSaver(
                suggestedName = exportDefaultFileName.removeSuffix(".zip"),
                extension = "zip",
            ) ?: return@launch
            val targetFile = File(saved.absolutePath())
            showSnackbar(exportInProgressMsg, SnackbarDuration.Indefinite)
            viewModel.exportBackup(targetFile, includeRoot) { error ->
                // Updating snackbarData triggers LaunchedEffect to cancel the indefinite snackbar
                // and show the result message.
                showSnackbar(
                    if (error == null) exportDoneMsg else exportFailedFmt.format(error)
                )
            }
        }
    }

    val cgiSkippedTitle = stringResource(Res.string.import_cgi_skipped_title)
    val cgiSkippedMessage = stringResource(Res.string.import_cgi_skipped_message)

    fun runImport() {
        if (uiState.serverRunning) {
            showSnackbar(importServerRunningMsg, SnackbarDuration.Long)
            return
        }
        scope.launch {
            val picked = FileKit.openFilePicker(
                type = FileKitType.File(extension = "zip"),
                title = selectBackupArchiveTitle
            ) ?: return@launch
            val sourceFile = File(picked.absolutePath())
            showSnackbar(importInProgressMsg, SnackbarDuration.Indefinite)
            viewModel.importBackup(sourceFile) { result ->
                showSnackbar(
                    if (result.error == null) importDoneMsg
                    else importFailedFmt.format(result.error)
                )
                if (result.error == null && result.archiveContainedCgi) {
                    // Reuse the shared MessageDialog. The viewmodel already shows it through
                    // uiState.showMessageDialog when showMessageDialog() is called.
                    viewModel.showMessageDialog(cgiSkippedTitle, cgiSkippedMessage)
                }
            }
        }
    }
    
    LaunchedEffect(redirectsWasEdited) {
        if (redirectsWasEdited) {
            viewModel.refreshRedirects()
            savedStateHandle["redirectsWasEdited"] = false
        }
    }
    LaunchedEffect(corsRulesWasEdited) {
        if (corsRulesWasEdited) {
            viewModel.refreshCorsRules()
            savedStateHandle["corsRulesWasEdited"] = false
        }
    }

    LaunchedEffect(headersOverridesWasEdited) {
        if (headersOverridesWasEdited) {
            viewModel.restartServerIfRunning()
            savedStateHandle["headersOverridesWasEdited"] = false
        }
    }
    
    // Update port text when UI state changes
    LaunchedEffect(uiState.port) {
        portText = uiState.port.toString()
    }
    
    //the desktop app has no equivalent of Android's onResume/connectivity broadcast, so the list of
    //existing interfaces (which the "Reachable on" line is derived from) is refreshed when the
    //window comes back to the front
    DisposableEffect(window) {
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                viewModel.refreshNetworkInterfaces()
            }

            override fun windowLostFocus(e: WindowEvent?) {}
        }
        window.addWindowFocusListener(focusListener)
        onDispose { window.removeWindowFocusListener(focusListener) }
    }

    // Show snackbar when data is set
    LaunchedEffect(snackbarData) {
        snackbarData?.let { data ->
            snackbarHostState.showSnackbar(
                message = data.message,
                duration = data.duration
            )
            snackbarData = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    //placed before verticalScroll on purpose: this must be the viewport position,
                    //which does not move while scrolling
                    .onGloballyPositioned { scrolledContentYInRoot = it.positionInRoot().y }
                    .verticalScroll(scrollState)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.app_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    var expanded by remember { mutableStateOf(false) }
                    
                    Box {
                        Button(
                            onClick = { expanded = true },
                            modifier = Modifier.padding(bottom = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color.Transparent
                            ),
                            elevation = ButtonDefaults.elevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp,
                                focusedElevation = 0.dp,
                                hoveredElevation = 0.dp
                            )
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                        
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                onClick = {
                                    expanded = false
                                    showExportChoiceDialog = true
                                }
                            ) {
                                Text(stringResource(Res.string.menu_export))
                            }
                            DropdownMenuItem(
                                onClick = {
                                    expanded = false
                                    showImportConfirmDialog = true
                                }
                            ) {
                                Text(stringResource(Res.string.menu_import))
                            }
                            DropdownMenuItem(
                                onClick = {
                                    expanded = false
                                    openUrlInBrowser("https://shttps.phlox.dev/articles/")
                                }
                            ) {
                                Text(stringResource(Res.string.menu_documentation))
                            }
                            DropdownMenuItem(
                                onClick = {
                                    expanded = false
                                    onNavigateToVersionInfo()
                                }
                            ) {
                                Text(stringResource(Res.string.menu_version_info))
                            }
                        }
                    }
                }

                // General Section
                ExpandableSection(
                    title = stringResource(Res.string.general),
                    expanded = SECTION_GENERAL !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_GENERAL, it) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(Res.string.server_status) + ": "
                        )
                        Text(
                            text = if (uiState.serverRunning) stringResource(Res.string.server_running) else stringResource(Res.string.server_stopped),
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.serverRunning) Color.Green else Color.Gray
                        )
                    }

                    Text(stringResource(Res.string.ip_info))
                    Row (verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.show_address_for_))
                        Spacer(modifier = Modifier.width(8.dp))
                        val options = uiState.usableNetworkInterfaces.map { it.displayName }
                        var expanded by remember { mutableStateOf(false) }
                        val textFieldState = rememberTextFieldState(uiState.selectedNetworkInterface?.displayName?: stringResource(Res.string.all_interfaces))
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            TextField(
                                readOnly = true,
                                state = textFieldState,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEach { selectionOption ->
                                    DropdownMenuItem(
                                        onClick = {
                                            textFieldState.setTextAndPlaceCursorAtEnd(selectionOption)
                                            expanded = false
                                            val selectedInterface = uiState.usableNetworkInterfaces.find { it.displayName == selectionOption }
                                            viewModel.updateSelectedNetworkInterface(selectedInterface)
                                        }
                                    ) {
                                        Text(text = selectionOption)
                                    }
                                }
                            }
                        }
                    }
                    Row (verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.ip_version))
                        Spacer(modifier = Modifier.width(8.dp))
                        val selectedIndex = if (uiState.selectedIPVersion == 4) 0 else 1
                        val options = listOf("IPv4", "IPv6")
                        SingleChoiceSegmentedButtonRow {
                            options.forEachIndexed { index, label ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                    onClick = {
                                        viewModel.updateIpVersion(if (index == 0) 4 else 6)
                                    },
                                    selected = index == selectedIndex,
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = MaterialTheme.colors.primarySurface,
                                        activeContentColor = MaterialTheme.colors.onPrimary,
                                        inactiveContainerColor = MaterialTheme.colors.surface,
                                        inactiveContentColor = MaterialTheme.colors.primary,
                                        activeBorderColor = MaterialTheme.colors.primary,
                                        inactiveBorderColor = MaterialTheme.colors.primary,
                                    )
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    Row (verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.ip_address))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.ipAddressToDisplay ?: stringResource(Res.string.not_available))
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (uiState.ipAddressToDisplay != null) {
                                copyToClipboard(uiState.ipAddressToDisplay ?: "")
                                showSnackbar(ipCopiedMsg)
                            }
                        }) {
                            Icon(
                                Icons.Default.CopyAll,
                                contentDescription = null,
                                tint = if (uiState.ipAddressToDisplay != null) Color.White else Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(Res.string.port_label, uiState.port.toString()))
                        IconButton(onClick = {
                            showPortEditDialog = true
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(Res.string.url_label, uiState.url?.toString() ?: stringResource(Res.string.not_available)))
                        IconButton(
                            onClick = {
                                if (uiState.url != null) {
                                    copyToClipboard(uiState.url.toString())
                                    showSnackbar(urlCopiedMsg)
                                }
                            },
                            enabled = uiState.url != null
                        ) {
                            Icon(
                                Icons.Default.CopyAll,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (uiState.url != null) {
                                    onNavigateToQr(uiState.url.toString())
                                }
                            },
                            enabled = uiState.url != null
                        ) {
                            Icon(
                                Icons.Default.QrCode,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (uiState.url != null) {
                                    openUrlInBrowser(uiState.url.toString())
                                }
                            },
                            enabled = uiState.url != null
                        ) {
                            Icon(
                                Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(Res.string.reachable_on_))
                        Spacer(modifier = Modifier.width(8.dp))
                        val reachability = remember(
                            uiState.allowedNetworkInterfaces,
                            uiState.selectedNetworkInterface,
                            //so that interfaces appearing/disappearing re-evaluate the line too
                            uiState.usableNetworkInterfaces
                        ) {
                            resolveReachability(
                                uiState.allowedNetworkInterfaces,
                                uiState.selectedNetworkInterface
                            )
                        }
                        val reachabilityColor = when (reachability.state) {
                            ReachabilityState.ALL_INTERFACES -> MaterialTheme.colors.onSurface
                            ReachabilityState.RESTRICTED_TO_SHOWN -> Color.Green
                            else -> MaterialTheme.colors.error
                        }
                        //TextDecoration.Underline is not used here: on Compose Desktop (Skiko) the
                        //underline is painted in a fixed color instead of following the Text's
                        //color, so the line is drawn manually to match reachabilityColor.
                        var underlineWidthPx by remember { mutableStateOf(0f) }
                        Text(
                            text = when (reachability.state) {
                                ReachabilityState.ALL_INTERFACES ->
                                    stringResource(Res.string.reachable_on_all_interfaces)
                                ReachabilityState.RESTRICTED_TO_SHOWN ->
                                    reachability.interfaceNames
                                ReachabilityState.RESTRICTED_TO_OTHER ->
                                    stringResource(Res.string.reachable_on_not_shown_interface, reachability.interfaceNames)
                                ReachabilityState.NOTHING ->
                                    stringResource(Res.string.reachable_on_nothing)
                            },
                            color = reachabilityColor,
                            onTextLayout = { underlineWidthPx = it.size.width.toFloat() },
                            modifier = Modifier
                                .clickable { revealRestrictNetworkInterfacesSetting() }
                                .drawBehind {
                                    val y = size.height - 1.dp.toPx()
                                    drawLine(
                                        color = reachabilityColor,
                                        start = Offset(0f, y),
                                        end = Offset(underlineWidthPx, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                        )
                    }

                    Row {
                        Button(
                            modifier = Modifier.weight(0.5f),
                            onClick = { viewModel.toggleServer() },
                        ) {
                            Text(if (uiState.serverRunning) stringResource(Res.string.stop) else stringResource(Res.string.start))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            modifier = Modifier.weight(0.5f),
                            onClick = { onNavigateToLogs() },
                        ) {
                            Text(stringResource(Res.string.logs))
                        }
                    }
                }

                // Files Section
                ExpandableSection(
                    title = stringResource(Res.string.files),
                    expanded = SECTION_FILES !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_FILES, it) }
                ) {

                    Button(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), onClick = {
                        scope.launch {
                            val directory = FileKit.openDirectoryPicker()
                            if (directory != null) {
                                viewModel.updateRootDirectory(directory)
                            }
                        }
                    }) {
                        Row (
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ){
                            Column(
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp) // Optional padding for visual comfort
                            ) {
                                Text(
                                    text = stringResource(Res.string.root_folder),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = (uiState.rootDirectory as? RawDocumentFile)?.file?.absolutePath ?: stringResource(Res.string.not_available),
                                    fontWeight = FontWeight.Light
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary,
                                modifier = Modifier.width(32.dp).height(32.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.renderFolderContent,
                            colors = CheckboxDefaults.colors(
                                //checkedColor = MaterialTheme.colors.secondaryVariant
                            ),
                            onCheckedChange = { isChecked ->
                                viewModel.updateRenderFolderContent(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.render_folder_content),
                            modifier = Modifier.weight(1f)
                        )
                        val renderFoldersInfoTitle = stringResource(Res.string.render_folder_content)
                        val renderFoldersInfoMessage = stringResource(Res.string.render_folders_info)
                        IconButton(onClick = {
                            viewModel.showMessageDialog(renderFoldersInfoTitle, renderFoldersInfoMessage)
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.allowFileModification,
                            onCheckedChange = { isChecked ->
                                viewModel.updateAllowFileModification(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.allow_file_modification),
                            modifier = Modifier.weight(1f)
                        )
                        val allowFileModificationInfoTitle = stringResource(Res.string.allow_file_modification)
                        val allowFileModificationInfoMessage = stringResource(Res.string.allow_file_modification_info)
                        IconButton(onClick = {
                            viewModel.showMessageDialog(allowFileModificationInfoTitle, allowFileModificationInfoMessage)
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.webDavEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.updateWebDavEnabled(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.enable_webdav),
                            modifier = Modifier.weight(1f)
                        )
                        val webDavInfoTitle = stringResource(Res.string.enable_webdav)
                        val webDavInfoMessage = stringResource(Res.string.enable_webdav_info)
                        val learnMoreText = stringResource(Res.string.learn_more)
                        val okText = stringResource(Res.string.ok)
                        IconButton(onClick = {
                            viewModel.showMessageDialog(webDavInfoTitle, webDavInfoMessage, listOf(
                                DialogButton(learnMoreText) {
                                    openUrlInBrowser("https://en.wikipedia.org/wiki/WebDAV")
                                },
                                DialogButton(okText) {}
                            ))
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }
                }

                // Remote screen Section - only in a build that can actually capture the
                // screen, so that no build offers a menu entry it cannot serve
                // (see ScreenCaptureProvider)
                if (uiState.screenShareSupported) ExpandableSection(
                    title = stringResource(Res.string.remote_screen),
                    expanded = SECTION_SCREEN !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_SCREEN, it) }
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.isScreenShareEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.updateScreenShareEnabled(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val screenTitle = stringResource(Res.string.remote_screen)
                        val screenInfo = stringResource(Res.string.enable_screen_share_info)
                        Text(
                            text = stringResource(Res.string.enable_screen_share),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            viewModel.showMessageDialog(screenTitle, screenInfo)
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    // Letting someone drive the machine is a bigger step than letting them watch
                    // it, and it is meaningless without the picture - so it appears only once
                    // sharing is on, rather than sitting there greyed out. Switching sharing back
                    // off clears the flag too, so this never comes back already ticked.
                    AnimatedVisibility(
                        visible = uiState.remoteControlSupported && uiState.isScreenShareEnabled
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.isRemoteControlEnabled,
                                onCheckedChange = { isChecked ->
                                    viewModel.updateRemoteControlEnabled(isChecked)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val controlTitle = stringResource(Res.string.enable_remote_control)
                            val controlInfo =
                                stringResource(Res.string.enable_remote_control_info)
                            Text(
                                text = stringResource(Res.string.enable_remote_control),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                viewModel.showMessageDialog(controlTitle, controlInfo)
                            }) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colors.onPrimary
                                )
                            }
                        }
                    }
                }

                // Database Section
                ExpandableSection(
                    title = stringResource(Res.string.database),
                    expanded = SECTION_DATABASE !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_DATABASE, it) }
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.isDatabaseEnabled,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    showInitDatabaseDialog = true
                                } else {
                                    viewModel.updateEnableDatabase(false)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.enable_sqlite_database),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.isDatabaseEnabled
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                onClick = {
                                    scope.launch {
                                        val file = FileKit.openFilePicker(
                                            type = FileKitType.File(
                                                extension = "db"
                                            ),
                                            title = selectSqliteDatabaseTitle
                                        )
                                        if (file != null) {
                                            viewModel.updateDatabasePath(file)
                                        }
                                    }
                                }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.database_file),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = uiState.databasePath.ifEmpty { stringResource(Res.string.not_available) },
                                            fontWeight = FontWeight.Light
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colors.onPrimary,
                                        modifier = Modifier.width(32.dp).height(32.dp)
                                    )
                                }
                            }

                            //DB connection status & info
                            Text(
                                text = uiState.databaseConnectionStatus.ifEmpty {
                                    if (uiState.isDatabaseEnabled) stringResource(Res.string.database_initializing) else ""
                                },
                                fontWeight = FontWeight.Light,
                                color = Color.Green
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = uiState.enabledDatabaseEditingApi,
                                    onCheckedChange = { isChecked ->
                                        viewModel.updateEnableDatabaseEditingApi(isChecked)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(Res.string.enable_sqlite_editing_api),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = uiState.enabledDatabaseCustomSQLApi,
                                    onCheckedChange = { isChecked ->
                                        viewModel.updateEnableDatabaseCustomSQLApi(isChecked)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(Res.string.enable_sqlite_custom_sql_api),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Button(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                onClick = { showTransactionLimitsDialog = true }
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = stringResource(Res.string.db_transaction_limits),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${uiState.dbTransactionMaxLifetimeMs} ms lifetime, ${uiState.dbTransactionInactivityTimeoutMs} ms idle",
                                        fontWeight = FontWeight.Light
                                    )
                                }
                            }
                        }
                    }
                }

                // Handlers Section
                ExpandableSection(
                    title = stringResource(Res.string.handlers),
                    expanded = SECTION_HANDLERS !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_HANDLERS, it) }
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.isCGIEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.updateCGIEnabled(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val cgiTitle = stringResource(Res.string.cgi_commandline_scripts)
                        Text(
                            text = cgiTitle,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            openUrlInBrowser("https://shttps.phlox.dev/articles/handlers/cgi/")
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = uiState.isCGIEnabled
                    ) {
                        val cgiTitle = stringResource(Res.string.cgi_configuration)
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            onClick = {
                                onNavigateToCgiList()
                            }
                        ) {
                            Text(stringResource(Res.string.cgi_configuration))
                        }
                    }
                }

                // WebSocket channels Section
                ExpandableSection(
                    title = stringResource(Res.string.channels),
                    expanded = SECTION_CHANNELS !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_CHANNELS, it) }
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.isChannelsEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.updateChannelsEnabled(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val channelsTitle = stringResource(Res.string.channels)
                        val channelsInfo = stringResource(Res.string.enable_channels_info)
                        Text(
                            text = stringResource(Res.string.enable_channels),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            viewModel.showMessageDialog(channelsTitle, channelsInfo)
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = uiState.isChannelsEnabled
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            onClick = {
                                onNavigateToChannelsList()
                            }
                        ) {
                            Text(stringResource(Res.string.channels_configuration))
                        }
                    }
                }

                // Security Section
                ExpandableSection(
                    title = stringResource(Res.string.security),
                    expanded = SECTION_SECURITY !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_SECURITY, it) }
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.authMode != SHTTPSConfig.AuthMode.NONE,
                            onCheckedChange = { isChecked ->
                                viewModel.updateAuthMode(
                                    if (isChecked)
                                        SHTTPSConfig.AuthMode.WEB
                                    else
                                        SHTTPSConfig.AuthMode.NONE
                                )
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.require_auth),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.authMode != SHTTPSConfig.AuthMode.NONE
                    ) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            onClick = {
                                onNavigateToAuthDetails()
                            }) {
                            Text(stringResource(Res.string.auth_details))
                        }
                    }

                    val restrictInterfacesHighlight by animateColorAsState(
                        targetValue = if (highlightRestrictInterfacesRow) {
                            MaterialTheme.colors.secondaryVariant.copy(alpha = 0.35f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(300)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { restrictInterfacesRowYInRoot = it.positionInRoot().y }
                            .background(restrictInterfacesHighlight, RoundedCornerShape(4.dp))
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.allowedNetworkInterfaces != null,
                            onCheckedChange = { isChecked ->
                                showNetworkInterfacesDialog = true
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.restrict_network_interfaces),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.whiteListMode.isNotEmpty(),
                            //the checkbox is only a way into the editor, which is where both the
                            //modes and the addresses are actually chosen - unchecking it there
                            //(by clearing both modes) is what turns the feature off
                            onCheckedChange = { showClientWhiteListDialog = true }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.white_list_of_clients_ips),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.useTlsEncryption,
                            onCheckedChange = { isChecked ->
                                viewModel.updateUseTlsEncryption(isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.use_tls_encryption),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AnimatedVisibility(
                        visible = uiState.useTlsEncryption
                    ) {
                        Column {
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                onClick = {
                                    scope.launch {
                                        val file = FileKit.openFilePicker(
                                            type = FileKitType.File(
                                                extensions = arrayOf("p12", "pfx")
                                            ),
                                            title = selectTlsCertTitle
                                        )
                                         if (file != null) {
                                             tmpTlsKeyPath = file
                                             showKeystorePasswordDialog = true
                                         }
                                    }
                                }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.tls_certificate_file),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = uiState.tlsCertPath ?: stringResource(Res.string.not_available),
                                            fontWeight = FontWeight.Light
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colors.onPrimary,
                                        modifier = Modifier.width(32.dp).height(32.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (uiState.tlsCertPath != null)
                                    stringResource(Res.string.certificate_installed) else
                                        stringResource(Res.string.not_installed_cert_hint),
                                fontSize = 12.sp,
                                color = if (uiState.tlsCertPath != null)
                                    Color.Green else
                                    MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.verifyHost,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    showHostnameDialog = true
                                } else {
                                    viewModel.updateVerifyHost(false)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.verify_host_http_header),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.globalRateLimitPerMinute > 0,
                            onCheckedChange = { isChecked ->
                                viewModel.showRateLimitDialog()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.request_rate_limit),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Misc Section
                ExpandableSection(
                    title = stringResource(Res.string.misc),
                    expanded = SECTION_MISC !in uiState.collapsedSections,
                    onExpandedChange = { viewModel.setSectionExpanded(SECTION_MISC, it) }
                ) {

                    if (uiState.autostartManagedBySystem) {
                        //a packaged build declares autostart in its manifest and can not switch it
                        //on from here, so point at the system page that owns it
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(text = stringResource(Res.string.autostart_managed_by_system))
                            Button(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                                onClick = { viewModel.openAutostartSystemSettings() }
                            ) {
                                Text(stringResource(Res.string.open_system_startup_settings))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.autostartOnSystemStartup,
                                onCheckedChange = { isChecked ->
                                    viewModel.updateAutostart(isChecked)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.autostart_on_system_startup),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.closeAppToTray,
                            onCheckedChange = { isChecked ->
                                viewModel.updateCloseAppToTray(isChecked)
                                onHideAppToTrayValueChange.invoke()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.close_app_to_tray),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val shouldAutoShutdown = uiState.shutdownTimeout > 0
                    val shutdownByInactivityCheckboxText =
                    if (shouldAutoShutdown) {
                        var strVal = (uiState.shutdownTimeout / 60.0f).toString()
                        if (strVal.endsWith(".0")) {
                            strVal = strVal.substring(0, strVal.length - 2)
                        }
                        stringResource(Res.string.auto_shutdown_by_inactivity_fmt, strVal)
                    } else {
                        stringResource(Res.string.auto_shutdown_by_inactivity)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shouldAutoShutdown,
                            onCheckedChange = { isChecked ->
                                showShutdownByInactivityDialog = true
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = shutdownByInactivityCheckboxText,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = viewModel.hasHeadersOverridesDefined(),
                            onCheckedChange = { isChecked ->
                                navController.navigate(HeadersOverridesListRoute)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.customize_headers),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = (uiState.defaultTextCharset ?: "").isNotEmpty(),
                            onCheckedChange = { isChecked ->
                                showCharsetDialog = true
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.custom_charset),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.redirectsDefined,
                            onCheckedChange = { isChecked ->
                                onNavigateToRedirectionsListScreen.invoke()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.redirections),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.corsRulesDefined,
                            onCheckedChange = { isChecked ->
                                onNavigateToCORSRulesListScreen.invoke()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.cors),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            openUrlInBrowser("https://en.wikipedia.org/wiki/Cross-origin_resource_sharing")
                        }) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colors.onPrimary
                            )
                        }
                    }
                }
            }

            // Vertical scrollbar
            VerticalScrollbar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 8.dp),
                adapter = rememberScrollbarAdapter(scrollState),
                style = ScrollbarStyle(
                    minimalHeight = 16.dp,
                    thickness = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 300,
                    unhoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                    hoverColor = MaterialTheme.colors.onSurface.copy(alpha = 0.50f)
                )
            )
            
            // Snackbar
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            
            // Port Edit Dialog
            if (showPortEditDialog) {
                PortEditDialog(
                    currentPort = uiState.port,
                    portText = portText,
                    onPortTextChange = { 
                        portText = it
                        portError = null // Clear error when user types
                    },
                    portError = portError,
                    onConfirm = {
                        val newPort = portText.toIntOrNull()
                        if (newPort == null) {
                            portError = portInvalidNumberMsg
                        } else if (newPort !in 1..65535) {
                            portError = portOutOfRangeMsg
                        } else {
                            viewModel.updatePort(newPort)
                            showPortEditDialog = false
                        }
                    },
                    onDismiss = {
                        showPortEditDialog = false
                        portText = uiState.port.toString() // Reset to current port
                        portError = null
                    }
                )
            }

            if (showInitDatabaseDialog) {
                InitDatabaseDialog(
                    onConfirmCreateNew = {
                        showInitDatabaseDialog = false
                        viewModel.createDatabase()
                    },
                    onConfirmAttachExisting = {
                        showInitDatabaseDialog = false
                        scope.launch {
                            val file = FileKit.openFilePicker(
                                type = FileKitType.File(
                                    extension = "db"
                                ),
                                title = selectSqliteDatabaseTitle
                            )
                            if (file != null) {
                                viewModel.updateDatabasePath(file)
                            }
                        }
                    },
                    onDismiss = {
                        showInitDatabaseDialog = false
                    }
                )
            }

            if (showTransactionLimitsDialog) {
                TransactionLimitsDialog(
                    currentMaxLifetimeMs = uiState.dbTransactionMaxLifetimeMs,
                    currentInactivityMs = uiState.dbTransactionInactivityTimeoutMs,
                    onSave = { maxLifetimeMs, inactivityMs ->
                        viewModel.updateDBTransactionTimeouts(maxLifetimeMs, inactivityMs)
                    },
                    onDismiss = {
                        showTransactionLimitsDialog = false
                    }
                )
            }

            if (showShutdownByInactivityDialog) {
                ShutdownByInactivityDialog(
                    initialValue = if (uiState.shutdownTimeout > 0) uiState.shutdownTimeout / 60 else 0,
                    onDismiss = {
                        showShutdownByInactivityDialog = false
                    },
                    onConfirm = { minutes ->
                        showShutdownByInactivityDialog = false
                        val timeout = if (minutes <= 0) 0 else minutes * 60
                        viewModel.updateShutdownTimeout(timeout)
                    }
                )
            }

            if (showNetworkInterfacesDialog) {
                NetworkInterfacesDialog(
                    networkInterfaces = uiState.usableNetworkInterfaces,
                    currentAllowedInterfaces = uiState.allowedNetworkInterfaces,
                    onConfirm = { allowedInterfaces ->
                        showNetworkInterfacesDialog = false
                        viewModel.updateAllowedNetworkInterfaces(allowedInterfaces)
                    },
                    onAllowAll = {
                        showNetworkInterfacesDialog = false
                        viewModel.updateAllowedNetworkInterfaces(null)
                    },
                    onDismiss = {
                        showNetworkInterfacesDialog = false
                    }
                )
            }

            if (showClientWhiteListDialog) {
                ClientWhiteListDialog(
                    currentMode = uiState.whiteListMode,
                    currentWhiteList = uiState.whiteList,
                    onSave = { mode, whiteList ->
                        showClientWhiteListDialog = false
                        viewModel.updateWhiteList(mode, whiteList)
                    },
                    onDismiss = {
                        showClientWhiteListDialog = false
                    }
                )
            }

            if (showCharsetDialog) {
                CharsetDialog(
                    currentCharset = uiState.defaultTextCharset ?: "utf-8",
                    onConfirm = { charset ->
                        showCharsetDialog = false
                        viewModel.updateDefaultTextCharset(charset)
                    },
                    onDismiss = {
                        showCharsetDialog = false
                    }
                )
            }

            if (showHostnameDialog) {
                HostnameDialog(
                    currentHostname = uiState.hostname,
                    onConfirm = { hostname ->
                        showHostnameDialog = false
                        viewModel.updateHostname(hostname)
                        viewModel.updateVerifyHost(hostname != null)
                    },
                    onDismiss = {
                        showHostnameDialog = false
                    }
                )
            }

            if (showKeystorePasswordDialog) {
                KeystorePasswordDialog(
                    onConfirm = { password ->
                        showKeystorePasswordDialog = false
                        tmpKeystorePassword = password
                        showKeyPasswordDialog = true
                    },
                    onDismiss = {
                        showKeystorePasswordDialog = false
                    }
                )
            }

            if (showKeyPasswordDialog) {
                KeyPasswordDialog(
                    keystorePassword = tmpKeystorePassword ?: "",
                    onConfirm = { keyPassword ->
                        showKeyPasswordDialog = false
                        viewModel.updateTlsCertPathAndPasswords(
                            tmpTlsKeyPath!!,
                            tmpKeystorePassword,
                            keyPassword
                        )
                    },
                    onDismiss = {
                        showKeyPasswordDialog = false
                    }
                )
            }

            if (showExportChoiceDialog) {
                ExportChoiceDialog(
                    onSelectEverything = {
                        showExportChoiceDialog = false
                        runExport(includeRoot = true)
                    },
                    onSelectExceptRoot = {
                        showExportChoiceDialog = false
                        runExport(includeRoot = false)
                    },
                    onDismiss = {
                        showExportChoiceDialog = false
                    }
                )
            }

            if (showImportConfirmDialog) {
                ImportConfirmDialog(
                    onContinue = {
                        showImportConfirmDialog = false
                        runImport()
                    },
                    onExportFirst = {
                        showImportConfirmDialog = false
                        showExportChoiceDialog = true
                    },
                    onDismiss = {
                        showImportConfirmDialog = false
                    }
                )
            }

            if (uiState.showInitialUserCredentialsDialog) {
                UserCredentialsDialog(
                    onConfirm = { username, password ->
                        viewModel.createFirstUser(username, password)
                    },
                    onDismiss = {
                        viewModel.setShowInitialUserCredentialsDialog(false)
                        viewModel.updateAuthMode(SHTTPSConfig.AuthMode.NONE)
                    }
                )
            }

            if (uiState.showMessageDialog) {
                MessageDialog(
                    uiState.messageDialogData
                )
            }

            if (uiState.showRateLimitDialog) {
                RateLimitDialog(
                    currentRateLimit = uiState.globalRateLimitPerMinute,
                    currentTrustToIPHeaders = uiState.rateLimiterTrustToIPHeaders,
                    onConfirm = { rateLimit, trustToIPHeaders ->
                        viewModel.updateRateLimit(rateLimit, trustToIPHeaders)
                    },
                    onDisable = {
                        viewModel.disableRateLimit()
                    },
                    onDismiss = {
                        viewModel.hideRateLimitDialog()
                    }
                )
            }

            //Gated on the window being visible, unlike every other dialog here. The update check
            //runs at startup, and a server that starts with the system sits in the tray with this
            //window hidden - which Compose still composes, so an ungated Dialog would pop up over
            //the desktop with no window to belong to. The state survives the wait: HomeViewModel is
            //hoisted above the NavHost, so the prompt appears the first time the user opens the
            //window (NewClientPrompts solves the same problem the other way, with a window of its
            //own, which an update notice does not warrant).
            val update = uiState.availableUpdate
            if (update != null && windowVisible.value) {
                UpdateAvailableDialog(
                    release = update,
                    onDownload = {
                        openUrlInBrowser(update.releaseUrl)
                        viewModel.dismissUpdate()
                    },
                    onSkipVersion = { viewModel.skipUpdateVersion(update.versionName) },
                    onDismiss = { viewModel.dismissUpdate() }
                )
            }
        }
    }
}
