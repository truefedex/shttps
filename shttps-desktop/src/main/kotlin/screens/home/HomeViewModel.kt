package com.phlox.simpleserver.screens.home

import androidx.compose.runtime.MutableState
import androidx.compose.ui.window.ApplicationScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phlox.server.SimpleHttpServer
import com.phlox.server.handlers.router.Router
import com.phlox.server.database.DatabaseFabricImpl
import com.phlox.server.utils.HTTPUtils.parseHttpHeaders
import com.phlox.server.utils.PlatformUtilsImpl
import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.server.utils.docfile.DocumentFile
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.MainWindowEvents
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.auth.User
import com.phlox.simpleserver.backup.DataExporter
import com.phlox.simpleserver.backup.DataImporter
import com.phlox.simpleserver.auth.User.DBRights
import com.phlox.simpleserver.auth.User.FileSystemRights
import com.phlox.simpleserver.auth.UserStore
import com.phlox.simpleserver.buildconfig.BuildConfig
import com.phlox.simpleserver.database.Database
import com.phlox.simpleserver.database.DatabaseMigrator
import com.phlox.simpleserver.dialogs.DialogButton
import com.phlox.simpleserver.dialogs.MessageDialogData
import com.phlox.server.handlers.router.middleware.Middleware
import com.phlox.simpleserver.remotecontrol.RemoteInputProvider
import com.phlox.simpleserver.remotecontrol.RemoteInputProviders
import com.phlox.simpleserver.screenshare.ScreenCaptureProvider
import com.phlox.simpleserver.screenshare.ScreenCaptureProviders
import com.phlox.simpleserver.screenshare.ScreenStreamWebSocketHandler
import com.phlox.simpleserver.security.ClientApprovalController
import com.phlox.simpleserver.updates.GitHubUpdateChecker
import com.phlox.simpleserver.updates.LatestRelease
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.database_connected_status
import com.phlox.simpleserver.shttps_desktop.generated.resources.error
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_create_database
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_create_directory
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_disable_autostart
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_enable_autostart
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_load_tls_certificate
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_open_database
import com.phlox.simpleserver.shttps_desktop.generated.resources.failed_to_start_server
import com.phlox.simpleserver.shttps_desktop.generated.resources.port_already_in_use
import org.jetbrains.compose.resources.getString
import com.phlox.simpleserver.ext.DesktopExtension
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils
import com.phlox.simpleserver.utils.Utils
import com.phlox.simpleserver.utils.openSystemSettings
import io.github.vinceglb.autolaunch.AutoLaunch
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.BindException
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.EnumSet
import java.util.Objects

data class HomeState(
    val serverRunning: Boolean = false,
    val usableNetworkInterfaces: List<NetworkInterface> = emptyList(),
    val selectedNetworkInterface: NetworkInterface? = null,
    val selectedIPVersion: Int = 4, // 4 - IPv4, 6 - IPv6
    val ipAddressToDisplay: String? = null,
    val port: Int = 8080,
    val url: URL? = null,
    val rootDirectory: DocumentFile? = null,
    val renderFolderContent: Boolean = true,
    val allowFileModification: Boolean = false,
    val webDavEnabled: Boolean = false,
    val useTlsEncryption: Boolean = false,
    val tlsCertPath: String? = null,
    val isDatabaseEnabled: Boolean = false,
    val databasePath: String = "",
    val enabledDatabaseEditingApi: Boolean = false,
    val enabledDatabaseCustomSQLApi: Boolean = false,
    val dbTransactionMaxLifetimeMs: Int = 5000,
    val dbTransactionInactivityTimeoutMs: Int = 2000,
    val databaseConnectionStatus: String = "",
    val isCGIEnabled: Boolean = false,
    val isChannelsEnabled: Boolean = false,
    val isScreenShareEnabled: Boolean = false,
    val isRemoteControlEnabled: Boolean = false,
    val redirectsDefined: Boolean = false,
    val corsRulesDefined: Boolean = false,
    val closeAppToTray: Boolean = false,
    val autostartOnSystemStartup: Boolean = false,

    //whether this build ships a screen capture implementation at all, see ScreenCaptureProvider
    val screenShareSupported: Boolean = false,
    //and likewise an input injection one, see RemoteInputProvider - the two are separate because
    //watching a screen and driving it are separate abilities a platform may have one of
    val remoteControlSupported: Boolean = false,
    val autostartManagedBySystem: Boolean = false,
    val shutdownTimeout: Int = 15 * 60, // in seconds
    val customHeaders: String = "",
    val defaultTextCharset: String? = null,
    // null - all interfaces, otherwise the indices of the interfaces the persisted allow-list
    // resolves to right now. Config entries naming interfaces that are absent at the moment
    // resolve to nothing and are not listed here, but are still kept in the config.
    val allowedNetworkInterfaces: Array<Int>? = null,
    val verifyHost: Boolean = false,
    val hostname: String? = null,
    val authMode: SHTTPSConfig.AuthMode = SHTTPSConfig.AuthMode.NONE,
    val globalRateLimitPerMinute: Int = 0,
    val rateLimiterTrustToIPHeaders: Boolean = false,

    //empty means no client filtering at all; the addresses only count when the mode contains
    //PREDEFINED, see SHTTPSApp.startServer()
    val whiteListMode: Set<SHTTPSConfig.WhiteListMode> = emptySet(),
    val whiteList: Set<String> = emptySet(),

    //tags of the settings sections the user collapsed; everything not listed here is expanded
    val collapsedSections: Set<String> = emptySet(),

    val showInitialUserCredentialsDialog: Boolean = false,
    val showMessageDialog: Boolean = false,
    val messageDialogData: MessageDialogData = MessageDialogData(),
    val showRateLimitDialog: Boolean = false,

    //set when the startup update check found a newer release than this build, and the user has
    //neither skipped that version nor dismissed the prompt yet
    val availableUpdate: LatestRelease? = null,
)

class HomeViewModel(
    val appScope: ApplicationScope,
    var config: AppConfig,
    val appSettingsDir: File,
    val mainWindowEvents: MutableSharedFlow<MainWindowEvents>,
    val serverRunning: MutableState<Boolean>,
    val autoLaunch: AutoLaunch,
    val approvalController: ClientApprovalController
): ViewModel(), SHTTPSApp.Callback {
    private val logger = SHTTPSLoggerProxy.getLogger("HomeViewModel")
    private val _uiState = MutableStateFlow(HomeState())
    val uiState: StateFlow<HomeState> = _uiState.asStateFlow()
    private var shttpsApp: SHTTPSApp
    private val configPath = appSettingsDir.absolutePath + File.separator + "config.json"
    private val newDatabasesPath = appSettingsDir.absolutePath + File.separator + "databases"

    /**
     * The screen capture implementation this build ships, or null if it ships none - which is the
     * case for the open source build, where screen sharing is hidden entirely rather than offered
     * and then unable to serve. Resolved once: finding it loads native libraries.
     */
    private val screenCaptureProvider: ScreenCaptureProvider? = ScreenCaptureProviders.get()

    /**
     * The input injection implementation this build ships, or null if it ships none. Resolved once,
     * for the same reason as [screenCaptureProvider], and separately from it: a platform may be
     * able to show its screen without being able to accept input for it.
     */
    private val remoteInputProvider: RemoteInputProvider? = RemoteInputProviders.get()

    /**
     * The allowed-interfaces entries exactly as they are persisted in the config: each one is
     * either a numeric interface index or an interface name — the server accepts both, see
     * [PlatformUtilsImpl.findInterfaces]. Kept around so [updateAllowedNetworkInterfaces] can
     * write back the spelling the user chose and preserve entries for interfaces that are not
     * present right now (those have no checkbox in the dialog) instead of dropping them.
     */
    private var allowedNetworkInterfaceEntries: Array<String>? = null

    init {
        val platformUtils: SHTTPSPlatformUtils = PlatformUtilsImpl()
        val databaseFabric = DatabaseFabricImpl()
        shttpsApp = SHTTPSApp.init(config, platformUtils, databaseFabric)
        shttpsApp.serverVersionInfo = SHTTPSApp.ServerVersionInfo(
            "SHTTPS Desktop",
            BuildConfig.VERSION_NAME,
        )
        shttpsApp.setShutdownTimeout(config.shutdownTimeout)//TODO: move this setting to SHTTPSConfig?
        shttpsApp.callback = this
        approvalController.attach(shttpsApp, viewModelScope)
        approvalController.onWhiteListPersisted = { refreshWhiteListFromConfig() }
        _uiState.value = _uiState.value.copy(
            serverRunning = shttpsApp.isServerRunning,
            usableNetworkInterfaces = getUsableNetworkInterfaces(),
            port = config.port,
            rootDirectory = config.rootDir,
            renderFolderContent = config.renderFolders,
            allowFileModification = config.allowEditing,
            webDavEnabled = config.webDavSupport,
            isDatabaseEnabled = config.isDatabaseEnabled,
            databasePath = config.databasePath ?: "",
            databaseConnectionStatus = "",
            enabledDatabaseEditingApi = config.isAllowDatabaseTableDataEditingApi,
            enabledDatabaseCustomSQLApi = config.isAllowDatabaseCustomSqlRemoteApi,
            dbTransactionMaxLifetimeMs = config.getDBTransactionMaxLifetimeMillis(),
            dbTransactionInactivityTimeoutMs = config.getDBTransactionInactivityTimeoutMillis(),
            isCGIEnabled = config.isCGIEnabled(),
            isChannelsEnabled = config.isChannelsEnabled(),
            isScreenShareEnabled = config.isScreenShareEnabled(),
            isRemoteControlEnabled = config.isRemoteControlEnabled(),
            screenShareSupported = screenCaptureProvider != null,
            remoteControlSupported = remoteInputProvider != null,
            autostartManagedBySystem = DesktopExtensions.autostartManagedBySystem,
            closeAppToTray = config.closeToTray,
            redirectsDefined = config.redirectRules?.isNotEmpty() == true,
            corsRulesDefined = config.corsRules?.isNotEmpty() == true,
            autostartOnSystemStartup = config.autostart,
            shutdownTimeout = config.shutdownTimeout,
            customHeaders = config.customHeaders,
            defaultTextCharset = config.defaultTextCharset,
            selectedIPVersion = config.ipVersionToShow,
            selectedNetworkInterface = if (config.networkInterfaceToShowIpFrom != -1) NetworkInterface.getByIndex(config.networkInterfaceToShowIpFrom) else null,
            allowedNetworkInterfaces = loadAllowedNetworkInterfacesFromConfig(),
            verifyHost = config.verifyHost,
            hostname = config.host,
            useTlsEncryption = config.useTLS,
            tlsCertPath = config.tlsCertPath,
            authMode = config.authMode,
            globalRateLimitPerMinute = config.globalRateLimit,
            rateLimiterTrustToIPHeaders = config.getRateLimiterTrustToIPHeaders(),
            whiteListMode = config.getWhiteListMode(),
            whiteList = config.getWhiteList(),
            collapsedSections = config.collapsedSections,
        )
        viewModelScope.launch(Dispatchers.IO) {
            shttpsApp.initIO()
            if (config.isDatabaseEnabled) {
                _uiState.value = _uiState.value.copy(
                    databaseConnectionStatus = shttpsApp.database?.let {
                        formatDatabaseConnectionStatus(it.status)
                    } ?: "Error"
                )
            }
            if (config.runningState && !shttpsApp.isServerRunning) {
                startServerSafely()
            }

            if (config.networkInterfaceToShowIpFrom == -1) {
                // Select the first available interface by default
                val interfaces = getUsableNetworkInterfaces()
                if (interfaces.isNotEmpty()) {
                    val firstInterface = interfaces[0]
                    config.networkInterfaceToShowIpFrom = firstInterface.index
                    _uiState.value = _uiState.value.copy(
                        selectedNetworkInterface = firstInterface,
                    )
                }
            }
            updateDisplayedIpAddressAndUrl()

            withContext(Dispatchers.Main) {
                mainWindowEvents.collect {
                    when (it) {
                        is MainWindowEvents.ToggleServerRunning -> {
                            toggleServer()
                        }
                    }
                }
            }
        }

        //Its own launch, deliberately: the block above ends on a collect that never returns, so
        //anything appended to it would never run.
        viewModelScope.launch(Dispatchers.IO) { checkForUpdatesIfNeeded() }
    }

    /**
     * Asks GitHub about newer releases, at most once a day, and publishes the answer to the UI.
     *
     * Silent about everything except a version the user has not seen yet: this runs unasked at
     * every launch, so a failure is a log line and nothing more.
     */
    private suspend fun checkForUpdatesIfNeeded() {
        //a build delivered by an application store is updated by that store
        if (!DesktopExtensions.updateChecksSupported) return
        if (!config.updateCheckEnabled) return

        val sinceLastCheck = System.currentTimeMillis() - config.lastUpdateCheckTime
        //note the direction: this skips the check while the last one is still *fresh*. The Android
        //app has it inverted, which makes it check on every launch and then stop forever the first
        //time the app goes a day unopened
        if (config.lastUpdateCheckTime > 0 && sinceLastCheck < GitHubUpdateChecker.UPDATE_CHECK_INTERVAL_MS) return

        val release = GitHubUpdateChecker.fetchLatestRelease() ?: return
        //only a successful check consumes the daily slot - otherwise one failed request would buy
        //a day of silence, which is how a broken update check stays unnoticed
        config.lastUpdateCheckTime = System.currentTimeMillis()

        if (!GitHubUpdateChecker.isNewerThan(release.versionName, BuildConfig.VERSION_NAME)) return
        if (release.versionName == config.skippedUpdateVersion) return

        logger.i("Update available: ${release.versionName} (running ${BuildConfig.VERSION_NAME})")
        _uiState.value = _uiState.value.copy(availableUpdate = release)
    }

    /** "Later" - say nothing more this run; the next check will offer it again. */
    fun dismissUpdate() {
        _uiState.value = _uiState.value.copy(availableUpdate = null)
    }

    /** "Skip this version" - stay quiet about this one release only, not about every future one. */
    fun skipUpdateVersion(versionName: String) {
        config.skippedUpdateVersion = versionName
        dismissUpdate()
    }

    fun showMessageDialog(title: String, message: String, buttons: List<DialogButton>? = null) {
        _uiState.value = _uiState.value.copy(
            showMessageDialog = true,
            messageDialogData = MessageDialogData(
                title = title,
                message = message,
                onDismiss = {
                    _uiState.value = _uiState.value.copy(showMessageDialog = false)
                },
                buttons = buttons
            ),
        )
    }

    fun toggleServer() {
        if (shttpsApp.isServerRunning) {
            shttpsApp.stopServer()
        } else {
            startServerSafely()
        }
    }

    /**
     * Starts the server, reporting a failed start (a port already taken by another application
     * being the usual one) in a dialog. Every start goes through here: an exception escaping into
     * the Compose application scope would take the whole app down instead of just leaving the
     * server stopped.
     *
     * @return true if the server is listening afterwards
     */
    private fun startServerSafely(): Boolean {
        return try {
            enforceDisabledFeatures()
            shttpsApp.startServer()
            true
        } catch (e: Exception) {
            logger.e("Failed to start server", e)
            //a start that failed part way through still leaves its rate limiters and the channel
            //idle sweep behind, so release them and leave the app in a consistent stopped state
            shttpsApp.stopServer()
            config.runningState = false
            serverRunning.value = false
            _uiState.value = _uiState.value.copy(serverRunning = false)
            viewModelScope.launch {
                val message = if (e is BindException) {
                    getString(Res.string.port_already_in_use, config.port)
                } else {
                    getString(Res.string.failed_to_start_server, e.toString())
                }
                showMessageDialog(getString(Res.string.error), message)
            }
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        approvalController.detach()
        shttpsApp.callback = null
        if (shttpsApp.isServerRunning) {
            shttpsApp.stopServer()
        }
    }

    // SHTTPSApp.Callback implementations
    /**
     * Called on the server accept loop thread for every connection the whitelist dropped.
     * Only ASK_AT_RUNTIME turns that into a question - with just PREDEFINED configured the user
     * has already said which clients are welcome and does not want to be asked about the rest.
     */
    override fun onConnectionRejected(socket: Socket?, reason: Int) {
        if (reason != SimpleHttpServer.REASON_CLIENT_ADDRESS_NOT_ALLOWED) return
        if (!config.getWhiteListMode().contains(SHTTPSConfig.WhiteListMode.ASK_AT_RUNTIME)) return
        val ip = socket?.inetAddress?.hostAddress ?: return
        approvalController.onClientRejected(ip)
    }

    override fun onNewConnection(socket: Socket?) {

    }

    override fun onServerStarted() {
        _uiState.value = _uiState.value.copy(serverRunning = true)
        config.runningState = true
        serverRunning.value = true
    }

    override fun onServerStopped() {
        _uiState.value = _uiState.value.copy(serverRunning = false)
        config.runningState = false
        serverRunning.value = false
        //an open prompt would let a client onto a server that is not listening any more
        approvalController.onServerStopped()
        //a stopped server must not still be holding the framebuffer open
        screenCaptureProvider?.shutdown()
        //nor a mouse button or a modifier that a client was pressing as it went away
        remoteInputProvider?.shutdown()
    }

    fun updateRenderFolderContent(checked: Boolean) {
        config.renderFolders = checked
        _uiState.value = _uiState.value.copy(renderFolderContent = checked)
    }

    fun updateAllowFileModification(checked: Boolean) {
        config.allowEditing = checked
        _uiState.value = _uiState.value.copy(allowFileModification = checked)
    }

    fun updateWebDavEnabled(checked: Boolean) {
        config.webDavSupport = checked
        _uiState.value = _uiState.value.copy(webDavEnabled = checked)
    }

    fun refreshRedirects() {
        _uiState.value = _uiState.value.copy(
            redirectsDefined = config.redirectRules?.isNotEmpty() == true,
            corsRulesDefined = config.corsRules?.isNotEmpty() == true
        )
    }

    fun refreshCorsRules() {
        _uiState.value = _uiState.value.copy(
            corsRulesDefined = config.corsRules?.isNotEmpty() == true
        )
    }
    
    fun getSHTTPSApp(): SHTTPSApp {
        return shttpsApp
    }

    /**
     * Exports the current server state into a ZIP file produced by {@link DataExporter}.
     * Runs on Dispatchers.IO; invokes [onResult] back on the Main dispatcher with either
     * a null error (success) or the exception message (failure).
     */
    fun exportBackup(target: File, includeRoot: Boolean, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val errorMessage: String? = try {
                FileOutputStream(target).use { out ->
                    DataExporter(shttpsApp).export(out, includeRoot)
                }
                null
            } catch (t: Throwable) {
                t.printStackTrace()
                t.message ?: t::class.java.simpleName
            }
            withContext(Dispatchers.Main) {
                onResult(errorMessage)
            }
        }
    }

    /**
     * Result of an [importBackup] call. [error] is null on success.
     */
    data class ImportResult(val error: String?, val archiveContainedCgi: Boolean)

    /**
     * Restores a backup written by [DataExporter]. Always overrides existing data.
     * Caller is responsible for stopping the server before invoking this method.
     *
     * Runs on Dispatchers.IO; invokes [onResult] back on the Main dispatcher with the
     * [ImportResult]. When [ImportResult.archiveContainedCgi] is true the UI should warn
     * the user that the CGI folder embedded in the archive was deliberately skipped.
     */
    fun importBackup(source: File, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val importer = DataImporter(shttpsApp)
            val errorMessage: String? = try {
                FileInputStream(source).use { input ->
                    importer.importData(input, true)
                }
                // Re-run config migrations in case the imported archive was older.
                try { config.runMigrations() } catch (e: Exception) { e.printStackTrace() }
                null
            } catch (t: Throwable) {
                t.printStackTrace()
                t.message ?: t::class.java.simpleName
            }

            if (errorMessage == null) {
                // Refresh the entire UI state from the (now-mutated) config.
                reloadUiStateFromConfig()
            }
            val result = ImportResult(errorMessage, importer.archiveContainedCgi)
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    /**
     * Reloads the StateFlow with values read straight from [config]. Called after an
     * import so every settings widget on HomeScreen reflects the freshly applied state.
     * Keeps networking-derived fields (URL, ipAddressToDisplay, selectedNetworkInterface,
     * selectedIPVersion) intact since those are device-local.
     */
    private suspend fun reloadUiStateFromConfig() {
        val current = _uiState.value
        _uiState.value = current.copy(
            serverRunning = shttpsApp.isServerRunning,
            port = config.port,
            rootDirectory = config.rootDir,
            renderFolderContent = config.renderFolders,
            allowFileModification = config.allowEditing,
            webDavEnabled = config.webDavSupport,
            isDatabaseEnabled = config.isDatabaseEnabled,
            databasePath = config.databasePath ?: "",
            databaseConnectionStatus = if (config.isDatabaseEnabled)
                shttpsApp.database?.let { runCatching { formatDatabaseConnectionStatus(it.status) }.getOrNull() } ?: ""
            else "",
            enabledDatabaseEditingApi = config.isAllowDatabaseTableDataEditingApi,
            enabledDatabaseCustomSQLApi = config.isAllowDatabaseCustomSqlRemoteApi,
            dbTransactionMaxLifetimeMs = config.getDBTransactionMaxLifetimeMillis(),
            dbTransactionInactivityTimeoutMs = config.getDBTransactionInactivityTimeoutMillis(),
            isCGIEnabled = config.isCGIEnabled(),
            isChannelsEnabled = config.isChannelsEnabled(),
            isScreenShareEnabled = config.isScreenShareEnabled(),
            isRemoteControlEnabled = config.isRemoteControlEnabled(),
            redirectsDefined = config.redirectRules?.isNotEmpty() == true,
            corsRulesDefined = config.corsRules?.isNotEmpty() == true,
            customHeaders = config.customHeaders,
            defaultTextCharset = config.defaultTextCharset,
            allowedNetworkInterfaces = loadAllowedNetworkInterfacesFromConfig(),
            verifyHost = config.verifyHost,
            hostname = config.host,
            useTlsEncryption = config.useTLS,
            tlsCertPath = config.tlsCertPath,
            authMode = config.authMode,
            globalRateLimitPerMinute = config.globalRateLimit,
            rateLimiterTrustToIPHeaders = config.getRateLimiterTrustToIPHeaders(),
            whiteListMode = config.getWhiteListMode(),
            whiteList = config.getWhiteList(),
        )
        updateDisplayedIpAddressAndUrl()
    }

    fun refreshNetworkInterfaces() {
        _uiState.value = _uiState.value.copy(usableNetworkInterfaces = getUsableNetworkInterfaces())
    }

    /**
     * The interfaces worth offering in the "Show address for" picker and in the allowed-interfaces
     * dialog: those that are up and carry at least one address a client could actually be pointed
     * at, see [isDialableAddress].
     */
    private fun getUsableNetworkInterfaces(): List<NetworkInterface> {
        val usableInterfaces = mutableListOf<NetworkInterface>()

        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()

                // Skip loopback interfaces
                //if (networkInterface.isLoopback) continue

                // Skip interfaces that are down
                if (!networkInterface.isUp) continue

                // Skip virtual interfaces (like Docker, VMware, etc.)
                if (networkInterface.name.startsWith("docker") || 
                    networkInterface.name.startsWith("veth") ||
                    networkInterface.name.startsWith("br-") ||
                    networkInterface.name.startsWith("vmnet") ||
                    networkInterface.name.startsWith("vboxnet")) {
                    continue
                }

                val hasDialableAddress = networkInterface.inetAddresses.asSequence().any {
                    isDialableAddress(it)
                }

                if (hasDialableAddress) {
                    usableInterfaces.add(networkInterface)
                }
            }
        } catch (e: Exception) {
            // Log error but don't crash the app
            println("Error getting network interfaces: ${e.message}")
        }
        
        // Sort interfaces by name for consistent ordering
        return usableInterfaces.sortedBy { it.name }
    }

    fun updateSelectedNetworkInterface(selectedInterface: NetworkInterface?) {
        config.networkInterfaceToShowIpFrom = selectedInterface?.index ?: -1
        _uiState.value = _uiState.value.copy(
            selectedNetworkInterface = selectedInterface,
        )
        updateDisplayedIpAddressAndUrl()
    }

    fun updateIpVersion(version: Int) {
        _uiState.value = _uiState.value.copy(selectedIPVersion = version)
        updateDisplayedIpAddressAndUrl()
    }

    private fun updateDisplayedIpAddressAndUrl() {
        val selectedInterface = _uiState.value.selectedNetworkInterface
        val ipVersion = _uiState.value.selectedIPVersion
        if (selectedInterface != null) {
            val displayAddress =
                selectDisplayAddress(selectedInterface.inetAddresses.toList(), ipVersion)?.hostAddress
            val port = _uiState.value.port
            val useTlsEncryption = _uiState.value.useTlsEncryption
            val url = if (displayAddress != null) {
                val protocol = if (useTlsEncryption) "https" else "http"
                val urlIP = if (ipVersion == 6) {
                    val scopeIdIndex = displayAddress.indexOf('%')
                    val unscopedIP = if (scopeIdIndex != -1) {
                        displayAddress.substring(0, scopeIdIndex)
                    } else displayAddress
                    "[$unscopedIP]" // IPv6 addresses in URLs must be enclosed in brackets
                } else {
                    if (displayAddress == "127.0.0.1") "localhost" else displayAddress
                }
                URL("$protocol://$urlIP:$port/")
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                ipAddressToDisplay = displayAddress,
                url = url,
            )
        } else {
            _uiState.value = _uiState.value.copy()
        }
    }

    fun updatePort(newPort: Int) {
        if (newPort in 1..65535) {
            config.port = newPort
            _uiState.value = _uiState.value.copy(port = newPort)
        }
    }

    fun updateRootDirectory(directory: PlatformFile) {
        config.setRootDir(directory.absolutePath())
        _uiState.value = _uiState.value.copy(rootDirectory = config.rootDir)
    }

    fun updateEnableDatabaseEditingApi(checked: Boolean) {
        config.isAllowDatabaseTableDataEditingApi = checked
        _uiState.value = _uiState.value.copy(enabledDatabaseEditingApi = checked)
    }

    fun updateEnableDatabaseCustomSQLApi(checked: Boolean) {
        config.isAllowDatabaseCustomSqlRemoteApi = checked
        _uiState.value = _uiState.value.copy(enabledDatabaseCustomSQLApi = checked)
    }

    /**
     * Clamps once more on the way in so the state the dialog shows is the state the server reads -
     * the config getters clamp too, and a value stored outside the range would read back as a
     * different number. No restart: the transaction handler reads both per transaction.
     */
    fun updateDBTransactionTimeouts(maxLifetimeMs: Int, inactivityMs: Int) {
        val maxLifetime = SHTTPSConfig.clampDBTransactionTimeout(maxLifetimeMs)
        val inactivity = SHTTPSConfig.clampDBTransactionTimeout(inactivityMs)
        config.setDBTransactionMaxLifetimeMillis(maxLifetime)
        config.setDBTransactionInactivityTimeoutMillis(inactivity)
        _uiState.value = _uiState.value.copy(
            dbTransactionMaxLifetimeMs = maxLifetime,
            dbTransactionInactivityTimeoutMs = inactivity
        )
    }

    fun updateEnableDatabase(checked: Boolean) {
        if (checked) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val db: Database = shttpsApp.databaseFabric.openDatabase(config.databasePath)
                    onDatabaseAttached(db)
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    config.isDatabaseEnabled = false
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_open_database, e.message ?: ""))
                }
            }
        } else {
            config.isDatabaseEnabled = false
            config.databasePath = ""
            _uiState.value = _uiState.value.copy(
                isDatabaseEnabled = false,
                databasePath = "",
            )
        }
    }

    fun updateDatabasePath(databaseFile: PlatformFile) {
        config.databasePath = databaseFile.absolutePath()
        updateEnableDatabase(true)
    }

    private suspend fun onDatabaseAttached(db: Database) {
        try {
            val status = withContext(Dispatchers.IO) {
                DatabaseMigrator.runMigrations(db, false)
                db.getStatus()
            }
            shttpsApp.database = db
            config.setDatabaseEnabled(true)
            _uiState.value = _uiState.value.copy(
                isDatabaseEnabled = true,
                databasePath = config.databasePath ?: "",
                databaseConnectionStatus = formatDatabaseConnectionStatus(status)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            config.isDatabaseEnabled = false
            config.databasePath = ""
            showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_open_database, e.message ?: ""))
            _uiState.value = _uiState.value.copy(
                isDatabaseEnabled = false,
                databasePath = "",
                databaseConnectionStatus = "",
            )
        }
    }

    private suspend fun formatDatabaseConnectionStatus(status: MutableMap<String, Any>): String {
        return getString(
            Res.string.database_connected_status,
            Utils.formatFileSize(Objects.requireNonNull(status["size"]) as Long),
            status["tablesCount"].toString()
        )
    }

    fun createDatabase() = viewModelScope.launch(Dispatchers.IO) {
        try {
            newDatabasesPath.let {
                val dir = File(it)
                if (!dir.exists() && !dir.mkdirs()) {
                    throw Exception(getString(Res.string.failed_to_create_directory, it))
                }
            }
            val uniqueDatabaseName = "database" + System.currentTimeMillis() + ".db"
            val db: Database = shttpsApp.databaseFabric.createDatabase(
                File(
                    newDatabasesPath,
                    uniqueDatabaseName
                ).absolutePath
            )
            config.databasePath = db.path
            onDatabaseAttached(db)
        } catch (e: Exception) {
            e.printStackTrace()
            config.isDatabaseEnabled = false
            showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_create_database, e.message ?: ""))
        }
    }

    fun setSectionExpanded(sectionTag: String, expanded: Boolean) {
        //a new set instance is required: MutableStateFlow conflates by equals(), so mutating the
        //stored one in place would not trigger a recomposition
        val collapsedSections = _uiState.value.collapsedSections.toMutableSet()
        if (expanded) {
            collapsedSections.remove(sectionTag)
        } else {
            collapsedSections.add(sectionTag)
        }
        config.collapsedSections = collapsedSections
        _uiState.value = _uiState.value.copy(collapsedSections = collapsedSections)
    }

    fun updateCloseAppToTray(checked: Boolean) {
        config.closeToTray = checked
        _uiState.value = _uiState.value.copy(closeAppToTray = checked)
    }

    /**
     * Sends the user to the system page where autostart is turned on, for builds that can not do
     * it themselves - see [DesktopExtension.autostartManagedBySystem].
     */
    fun openAutostartSystemSettings() {
        openSystemSettings(DesktopExtensions.autostartSettingsUri)
    }

    fun updateAutostart(checked: Boolean) {
        if (DesktopExtensions.autostartManagedBySystem) {
            //nothing this process can write would take effect; the UI offers the settings link
            //instead of a switch, so this should not be reachable
            logger.w("Ignoring autostart change: autostart is managed by the system in this build")
            return
        }
        config.autostart = checked
        _uiState.value = _uiState.value.copy(autostartOnSystemStartup = checked)
        viewModelScope.launch(Dispatchers.IO) {
            if (checked) {
                try {
                    if (!autoLaunch.isEnabled()) {
                        autoLaunch.enable()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_enable_autostart, e.message ?: ""))
                }
            } else {
                try {
                    if (autoLaunch.isEnabled()) {
                        autoLaunch.disable()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_disable_autostart, e.message ?: ""))
                }
            }
        }
    }

    fun updateShutdownTimeout(timeout: Int) {
        config.shutdownTimeout = timeout
        _uiState.value = _uiState.value.copy(shutdownTimeout = timeout)
        shttpsApp.setShutdownTimeout(timeout)
    }

    fun updateCustomHeaders(headers: String) {
        config.customHeaders = headers
        _uiState.value = _uiState.value.copy(customHeaders = headers)
        
        // Update server headers if running
        if (shttpsApp.isServerRunning) {
            val server = shttpsApp.server
            if (server != null) {
                if (headers.isBlank()) {
                    server.additionalResponseHeaders = null
                } else {
                    val headersMap = parseHttpHeaders(headers)
                    server.additionalResponseHeaders = headersMap
                }
            }
        }
    }

    fun clearCustomHeaders() {
        updateCustomHeaders("")
    }

    /**
     * Resolves one persisted allow-list entry through the very same code path the server uses
     * when it applies this setting, so the UI can never disagree with it about what an entry
     * means. Returns null when no interface currently matches.
     */
    private fun resolveAllowedNetworkInterfaceEntry(entry: String): NetworkInterface? =
        shttpsApp.platformUtils.findInterfaces(arrayOf(entry))?.firstOrNull()

    /**
     * Re-reads the persisted allow-list into [allowedNetworkInterfaceEntries] and maps it to the
     * interface indices the UI works with. Returns null when no restriction is configured.
     */
    private fun loadAllowedNetworkInterfacesFromConfig(): Array<Int>? {
        val entries = config.allowedNetworkInterfaces
        allowedNetworkInterfaceEntries = entries
        return entries?.mapNotNull { resolveAllowedNetworkInterfaceEntry(it)?.index }?.toTypedArray()
    }

    /**
     * Builds the entries to persist for the interfaces the user checked. Entries that already
     * allowed a checked interface are reused verbatim, so an allow-list written by hand as
     * "eth0" is not silently rewritten into an index; entries that resolve to no interface at
     * the moment are carried over untouched, since the dialog never had a chance to show them.
     */
    private fun mergeAllowedNetworkInterfaceEntries(checkedIndices: Array<Int>): List<String> {
        val previousEntries = allowedNetworkInterfaceEntries ?: emptyArray()
        val resolvedIndices = previousEntries.associateWith { resolveAllowedNetworkInterfaceEntry(it)?.index }
        val checked = checkedIndices.map { index ->
            resolvedIndices.entries.firstOrNull { it.value == index }?.key ?: index.toString()
        }
        val unresolved = previousEntries.filter { resolvedIndices[it] == null }
        return (checked + unresolved).distinct()
    }

    fun updateAllowedNetworkInterfaces(allowedInterfaces: Array<Int>?) {
        // An empty allow-list reads back as "no restriction", so collapse it to null here to keep
        // the config and the UI telling the same story.
        val entries = if (allowedInterfaces == null) null else
            mergeAllowedNetworkInterfaceEntries(allowedInterfaces).takeIf { it.isNotEmpty() }?.toTypedArray()
        allowedNetworkInterfaceEntries = entries
        config.allowedNetworkInterfaces = entries
        _uiState.value = _uiState.value.copy(
            allowedNetworkInterfaces = if (entries == null) null else allowedInterfaces
        )
        restartServerIfRunning()
    }

    /**
     * Persists the client whitelist and pushes it into the running server right away. Unlike the
     * network interface restriction this needs no restart: the server keeps the allowed addresses
     * in a field that stays writable while it is listening.
     */
    /**
     * Re-reads the whitelist the settings editor works with. Needed because "Allow and save to
     * whitelist" on a connection prompt writes it behind this screen's back.
     */
    fun refreshWhiteListFromConfig() {
        _uiState.value = _uiState.value.copy(
            whiteListMode = config.getWhiteListMode(),
            whiteList = config.getWhiteList()
        )
    }

    fun updateWhiteList(mode: Set<SHTTPSConfig.WhiteListMode>, whiteList: Set<String>) {
        config.setWhiteListMode(mode)
        config.setWhiteList(whiteList)
        _uiState.value = _uiState.value.copy(whiteListMode = mode, whiteList = whiteList)
        approvalController.applyWhiteList(mode, whiteList)
    }

    fun updateVerifyHost(verifyHost: Boolean) {
        config.verifyHost = verifyHost
        _uiState.value = _uiState.value.copy(verifyHost = verifyHost)
        restartServerIfRunning()
    }

    fun updateHostname(hostname: String?) {
        config.host = hostname
        _uiState.value = _uiState.value.copy(hostname = hostname)
        restartServerIfRunning()
    }

    fun updateDefaultTextCharset(charset: String?) {
        config.setDefaultTextCharset(charset)
        _uiState.value = _uiState.value.copy(defaultTextCharset = charset)
        restartServerIfRunning()
    }

    fun updateUseTlsEncryption(checked: Boolean) {
        config.useTLS = checked
        if (!checked) {
            config.setTLSCert(null)
            config.tlsKeystorePassword = null
            config.tlsKeyPassword = null
            _uiState.value = _uiState.value.copy(tlsCertPath = null)
        }
        _uiState.value = _uiState.value.copy(useTlsEncryption = checked)
        updateDisplayedIpAddressAndUrl()
        restartServerIfRunning()
    }

    fun restartServerIfRunning() {
        if (shttpsApp.isServerRunning) {
            shttpsApp.stopServer()
            startServerSafely()
        }
    }

    fun hasHeadersOverridesDefined(): Boolean {
        return try {
            val rules = config.getHeadersOverrides()
            rules != null && rules.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun updateTlsCertPathAndPasswords(file: PlatformFile, keystorePassword: String?, keyPassword: String?) {
        config.setTLSCert(file.absolutePath().toByteArray())
        config.tlsKeystorePassword = keystorePassword
        config.tlsKeyPassword = keyPassword
        val certValid = config.tlsCert != null

        if (!certValid) {
            config.setTLSCert(null)
            config.tlsKeystorePassword = null
            config.tlsKeyPassword = null
            viewModelScope.launch {
                showMessageDialog(getString(Res.string.error), getString(Res.string.failed_to_load_tls_certificate))
            }
        } else {
            _uiState.value = _uiState.value.copy(tlsCertPath = file.absolutePath())
            restartServerIfRunning()
        }
    }

    fun updateAuthMode(mode: SHTTPSConfig.AuthMode) {
        config.authMode = mode
        _uiState.value = _uiState.value.copy(authMode = mode)
        restartServerIfRunning()
        val userStore: UserStore = shttpsApp.provideUserStore()
        if (mode != SHTTPSConfig.AuthMode.NONE && userStore.count() == 0L) {
            setShowInitialUserCredentialsDialog(true)
        }
    }

    fun createFirstUser(username: String, password: String) {
        _uiState.value = _uiState.value.copy(showInitialUserCredentialsDialog = false)
        viewModelScope.launch(Dispatchers.IO) {
            val passwordHash = Utils.sha256(Utils.hashFNV1a32(password))

            val userStore: UserStore = shttpsApp.provideUserStore()
            userStore.create(
                User(
                    username, passwordHash!!, null, 
                    EnumSet.allOf(FileSystemRights::class.java),
                    EnumSet.allOf(DBRights::class.java), null,
                    System.currentTimeMillis(), null, null,
                    EnumSet.allOf(User.SystemRights::class.java),
                    0
                )
            )
        }
    }

    fun setShowInitialUserCredentialsDialog(bool: Boolean) {
        _uiState.value = _uiState.value.copy(showInitialUserCredentialsDialog = bool)
    }

    fun showRateLimitDialog() {
        _uiState.value = _uiState.value.copy(showRateLimitDialog = true)
    }

    fun hideRateLimitDialog() {
        _uiState.value = _uiState.value.copy(showRateLimitDialog = false)
    }

    fun updateRateLimit(rateLimit: Int, trustToIPHeaders: Boolean) {
        config.globalRateLimit = rateLimit
        config.setRateLimiterTrustToIPHeaders(trustToIPHeaders)
        _uiState.value = _uiState.value.copy(
            globalRateLimitPerMinute = rateLimit,
            rateLimiterTrustToIPHeaders = trustToIPHeaders,
            showRateLimitDialog = false
        )
        restartServerIfRunning()
    }

    fun disableRateLimit() {
        config.globalRateLimit = 0
        _uiState.value = _uiState.value.copy(
            globalRateLimitPerMinute = 0,
            showRateLimitDialog = false
        )
        restartServerIfRunning()
    }

    fun updateCGIEnabled(checked: Boolean) {
        config.setCGIEnabled(checked)
        _uiState.value = _uiState.value.copy(isCGIEnabled = checked)
        restartServerIfRunning()
    }

    /**
     * Turns off whatever this build is not allowed to run, immediately before every server start.
     *
     * Hiding the switch in the settings is not enough on its own: the config file lives in the
     * user's home directory and is shared with any other build of the app installed alongside this
     * one, and importing a configuration can set the flag as well - so the value has to be forced
     * where it is actually read rather than only where it is edited.
     */
    private fun enforceDisabledFeatures() {
        if (screenCaptureProvider == null && config.isScreenShareEnabled()) {
            //without this the file browser would offer a "Remote screen" entry leading to a
            //page that can never connect - the flag also arrives through an imported config
            logger.i("Screen sharing is not available in this build, disabling it")
            config.setScreenShareEnabled(false)
            _uiState.value = _uiState.value.copy(isScreenShareEnabled = false)
        }
        if (remoteInputProvider == null && config.isRemoteControlEnabled()) {
            //arrives the same two ways the screen sharing flag does - a config file shared with
            //another build installed alongside, or an imported configuration - and left alone it
            //would have the page offer a mouse and a keyboard nothing here can carry out
            logger.i("Remote control is not available in this build, disabling it")
            config.setRemoteControlEnabled(false)
            _uiState.value = _uiState.value.copy(isRemoteControlEnabled = false)
        }
    }

    fun updateScreenShareEnabled(checked: Boolean) {
        config.setScreenShareEnabled(checked)
        _uiState.value = _uiState.value.copy(isScreenShareEnabled = checked)
        if (!checked && config.isRemoteControlEnabled()) {
            //switching sharing off takes remote control with it, rather than leaving the flag set
            //for whenever sharing comes back: handing over the mouse and keyboard has to be a
            //deliberate act every time, which is what the warning under the checkbox promises
            logger.i("Screen sharing was switched off, so remote control goes with it")
            config.setRemoteControlEnabled(false)
            _uiState.value = _uiState.value.copy(isRemoteControlEnabled = false)
            remoteInputProvider?.shutdown()
        }
        //the route is registered once, while the server is starting, so this only takes effect
        //on a restart - and switching it off has to actually let go of the screen
        restartServerIfRunning()
    }

    fun updateRemoteControlEnabled(checked: Boolean) {
        config.setRemoteControlEnabled(checked)
        _uiState.value = _uiState.value.copy(isRemoteControlEnabled = checked)
        //no restart needed either way, unlike screen sharing: the input target is looked up for
        //every message rather than captured when the route is registered
        if (!checked) {
            //let go of anything held at this instant rather than leaving it to the watchdog
            remoteInputProvider?.shutdown()
        }
    }

    /**
     * Adds the screen stream endpoint, if this build can capture the screen and the user has
     * asked for it. Everything else the server offers is registered by [SHTTPSApp] itself; this
     * is the one route that depends on code the open source build does not necessarily have.
     */
    override fun onRouterPrepared(router: Router, filesRequestHandlerMiddlewares: MutableList<Middleware>) {
        if (!config.isScreenShareEnabled()) return
        val source = screenCaptureProvider?.getOrCreate(config) ?: return
        //registered with the middlewares every route gets - authentication first of all - and
        //before that list is extended, because the router snapshots it at registration time
        router.addRoute(
            ScreenStreamWebSocketHandler.PATH, setOf("GET"),
            ScreenStreamWebSocketHandler(
                config, shttpsApp.authManager, { source },
                //resolved per message rather than captured here, mirroring what ServerService
                //does on Android: the user can switch remote control off while a client is
                //connected, and the handler is expected to notice within one event
                {
                    if (config.isRemoteControlEnabled()) {
                        remoteInputProvider?.getOrCreate(config)
                    } else {
                        null
                    }
                },
            ),
            filesRequestHandlerMiddlewares,
        )
    }

    fun updateChannelsEnabled(checked: Boolean) {
        config.setChannelsEnabled(checked)
        _uiState.value = _uiState.value.copy(isChannelsEnabled = checked)
        //the channel routes read this per request, but the idle sweep is only scheduled at startup
        restartServerIfRunning()
    }
}

/**
 * Whether [address] is one that a client could actually be pointed at, which is what decides
 * whether the interface carrying it is offered in the UI at all.
 *
 * Everything except a link-local address qualifies - loopback included, since 127.0.0.1 is a
 * legitimate choice for local-only testing and the picker has always offered it.
 *
 * The exclusion is what keeps the macOS list honest. A Mac is permanently full of interfaces the
 * user never configured: awdl0 and llw0 (Apple Wireless Direct Link and its low-latency companion,
 * the peer-to-peer radios behind AirDrop, Sidecar, AirPlay and Handoff) and the system's own utunN
 * tunnels (iCloud Private Relay, Personal Hotspot, content filters). All of them are up, and all of
 * them carry nothing but an IPv6 fe80:: address. Such an address cannot be handed to a browser: it
 * is only meaningful together with the scope id of the *client's* own interface
 * (`http://[fe80::1%en0]:8080/`), which no ordinary user will type and which is wrong the moment it
 * is copied to another machine. Windows has the same thing in its Teredo/ISATAP pseudo-interfaces
 * and Linux in a freshly-up NIC that has not got a lease yet (IPv4 169.254/16 is link-local too,
 * and is caught by the same check).
 *
 * Filtering on the address rather than on a name blacklist is deliberate: a utunN brought up by a
 * real VPN (WireGuard, IKEv2, Tailscale) carries a routable address and stays in the list, where a
 * name-based rule would have hidden exactly the interface a VPN user wants to serve on.
 */
internal fun isDialableAddress(address: java.net.InetAddress): Boolean = !address.isLinkLocalAddress

/**
 * The address to put on the status screen for [ipVersion] (4 or 6), out of everything the selected
 * interface carries, or null when that interface has none of that version a client could be pointed
 * at - which the screen renders as "not available".
 *
 * Link-local addresses are skipped here for the same reason they keep an interface out of the
 * picker entirely: everything on that screen - the URL, the QR code, "Open in browser", the copy
 * buttons - derives from this one address, and en0 carrying an fe80:: address is not the same as
 * this machine being reachable over IPv6. Saying so plainly beats offering a QR code that encodes
 * an address whose scope id was stripped to make it fit in a URL.
 */
internal fun selectDisplayAddress(
    addresses: List<java.net.InetAddress>,
    ipVersion: Int,
): java.net.InetAddress? = addresses.firstOrNull { address ->
    ((ipVersion == 4 && address is java.net.Inet4Address) ||
            (ipVersion == 6 && address is java.net.Inet6Address)) && isDialableAddress(address)
}
