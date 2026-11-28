package com.phlox.simpleserver

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.server.utils.SHTTPSLoggerProxy.TaggedJavaLogger
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.navigation.AppNavigationGraph
import com.phlox.simpleserver.security.ClientApprovalController
import com.phlox.simpleserver.security.NewClientPrompts
import com.phlox.simpleserver.security.ScreenAccessApprovalController
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.exit
import com.phlox.simpleserver.shttps_desktop.generated.resources.start_server
import com.phlox.simpleserver.shttps_desktop.generated.resources.stop_server
import com.phlox.simpleserver.shttps_desktop.generated.resources.tray
import com.phlox.simpleserver.shttps_desktop.generated.resources.window_icon
import com.phlox.simpleserver.theme.AppTheme
import com.phlox.simpleserver.tray.trayAnchoredWindowPosition
import dev.nucleusframework.composenativetray.tray.api.Tray
import dev.nucleusframework.core.runtime.SingleInstanceManager
import io.github.vinceglb.autolaunch.AutoLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import com.phlox.simpleserver.profiles.ConfigProfiles
import com.phlox.simpleserver.profiles.DesktopStorageLayout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * Emitted by the [SingleInstanceManager] watcher thread when another launch of this app (with the
 * same config file) asks the already running instance to show itself. Collected in [AppContent].
 */
private val restoreRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

/**
 * Emitted when the tray icon is clicked. Like [restoreRequests] this hops to the UI dispatcher:
 * the callback runs on the tray thread of the library, and showing the window from there means
 * querying screens and the native tray position, which is not safe to do outside the UI thread.
 */
private val trayToggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

//The smallest remembered size the main window will be restored at, in AWT logical units. Only a
//floor against a hand-edited or truncated config - the window itself stays freely resizable (there
//is no minimumSize), and a size the user dragged to is honoured as it is as long as it clears this.
private const val MIN_WINDOW_WIDTH_DP = 360
private const val MIN_WINDOW_HEIGHT_DP = 400

//How long the window has to sit still before its new size is written to the config. Every setter of
//SHTTPSConfigImpl rewrites the whole file, so saving on each resize event would mean one write per
//frame of a drag. The cost is that a resize less than this before quitting is lost.
private const val WINDOW_SIZE_SAVE_DELAY_MS = 500L

/**
 * The size the main window opens at: what the user left it at last time, or [AppConfig]'s default
 * on a first run.
 *
 * Clamped to the screen, because the usual way a remembered size breaks is a laptop that was last
 * used with a larger external monitor - the window would come back taller than the built-in screen
 * with its title bar above the top edge, where it cannot be grabbed. The position is deliberately
 * not remembered, so the window lands on the primary screen, which is the one maximumWindowBounds
 * describes. Both it and [androidx.compose.ui.window.WindowState.size] are in AWT logical units, so
 * there is nothing to convert between them.
 */
private fun initialWindowSize(config: AppConfig): DpSize {
    val screen = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull()
    //coerceIn throws if the ceiling ends up under the floor, which a screen smaller than the
    //minimum would do
    val maxWidth = (screen?.width ?: Int.MAX_VALUE).coerceAtLeast(MIN_WINDOW_WIDTH_DP)
    val maxHeight = (screen?.height ?: Int.MAX_VALUE).coerceAtLeast(MIN_WINDOW_HEIGHT_DP)
    return DpSize(
        config.windowWidth.coerceIn(MIN_WINDOW_WIDTH_DP, maxWidth).dp,
        config.windowHeight.coerceIn(MIN_WINDOW_HEIGHT_DP, maxHeight).dp
    )
}

fun main(args: Array<String>) {
    SHTTPSLoggerProxy.setFactory { tag: String? ->
        TaggedJavaLogger(
            tag,
            SHTTPSLoggerProxy.Logger.ALL
        )
    }

    val logger = SHTTPSLoggerProxy.getLogger("Main")

    val appSettingsDir = File(System.getProperty("user.home") + File.separator + ".shttps")
    if (!appSettingsDir.exists() && !appSettingsDir.mkdirs()) {
        logger.e("Can't create directory " + appSettingsDir.absolutePath)
        return
    }

    //An explicit config path still means what it always did: this one file, no profile management.
    val explicitConfigPath = args.firstOrNull()
    val legacyConfigFile =
        DesktopStorageLayout.configFile(appSettingsDir, DesktopStorageLayout.MAIN_PROFILE_ID)

    //The lock is keyed on the *legacy* config path whichever profile is active, not on the active
    //profile's own. Keying it on the active one would mean that after a switch a second launch
    //computes a different, unheld identifier - and then two processes rewrite one config file,
    //which SHTTPSConfigImpl does in full on every setter. The constant is also exactly what
    //versions before profiles compute, so an old instance and a new one still exclude each other.
    //
    //Must happen before any config file is opened, so the instance that loses the race never
    //touches one.
    val lockKeyPath = explicitConfigPath ?: legacyConfigFile.absolutePath
    if (!acquireSingleInstanceLock(lockKeyPath, appSettingsDir, logger)) {
        return
    }

    if (explicitConfigPath != null) {
        val explicitConfigFile = File(explicitConfigPath).absoluteFile
        val explicitDir = explicitConfigFile.parentFile ?: File(".").absoluteFile
        //Its own app.json beside its config rather than the shared ~/.shttps/app.json.
        //DesktopAppState rewrites the whole file from an in-memory snapshot, so sharing it with
        //the default instance would let this one's window resize erase a profile the other had
        //created meanwhile. Globals are imported from this config, since that is where they used
        //to live for this instance. Databases go beside this config too, for the same reason
        //SHTTPSConfigImpl.getDefaultDatabasePath() puts them there.
        //Said out loud because this mode silently removes a feature: there is no profile manager
        //in the menu, and nothing on screen explains why. A run configuration that was set up
        //before profiles existed still passes a path, and then the entry is simply missing.
        logger.i("Started with an explicit config path ($explicitConfigPath), so config profiles " +
                "are off: this mode serves exactly that one file. Launch without an argument to " +
                "manage profiles.")
        val explicitState = DesktopAppState(File(explicitDir, DesktopAppState.FILE_NAME))
        explicitState.importLegacyGlobalsOnce(explicitConfigFile)
        val config = try {
            AppConfig(explicitConfigFile, explicitState)
        } catch (_: Exception) {
            logger.e("Can't create config file at $explicitConfigPath")
            return
        }
        AppNavigation(config, explicitDir, profiles = null)
        return
    }

    val appState = DesktopAppState(File(appSettingsDir, DesktopAppState.FILE_NAME))
    appState.importLegacyGlobalsOnce(legacyConfigFile)

    val profiles = ConfigProfiles(appSettingsDir, appState)
    //A hand-edited config throws here. Falling back to the legacy profile rather than returning,
    //because returning would leave the app unlaunchable with no UI to fix it from.
    val config = try {
        profiles.openActiveConfig()
    } catch (e: Exception) {
        logger.e("Can't open the config of profile ${profiles.activeProfileId}, " +
                "falling back to the default one", e)
        profiles.setActiveProfile(DesktopStorageLayout.MAIN_PROFILE_ID)
        try {
            profiles.openActiveConfig()
        } catch (_: Exception) {
            logger.e("Can't open ${legacyConfigFile.absolutePath} either")
            return
        }
    }

    AppNavigation(config, appSettingsDir, profiles)
}

/**
 * Makes sure we are the only process working with this config file.
 *
 * Two instances sharing a config silently overwrite each other: [com.phlox.server.SHTTPSConfigImpl]
 * keeps the whole config in memory and rewrites the entire file on every setter, so a write from
 * the second instance reverts everything the first one changed since it started. That happens even
 * without any user action, because a second instance fails to bind the already busy port and writes
 * running_state = false by itself.
 *
 * The lock is keyed on the config file instead of being global, so running a second instance with
 * an explicit config path (the optional first command line argument) still works.
 *
 * Must be called before the config file is opened, so that the instance which loses the race never
 * touches it.
 *
 * @return true if we got the lock and may continue starting up. When false, another instance owns
 * this config and has been asked to bring its window to front, so we just quit.
 */
private fun acquireSingleInstanceLock(
    configPath: String,
    appSettingsDir: File,
    logger: SHTTPSLoggerProxy.Logger
): Boolean {
    val configFile = File(configPath)
    //canonical path so that different spellings of the same file (relative paths, symlinks, letter
    //case on Windows) end up on the same lock
    val canonicalConfigPath = try {
        configFile.canonicalPath
    } catch (e: IOException) {
        logger.w("Can't resolve canonical path of $configPath, falling back to the absolute one", e)
        configFile.absolutePath
    }

    //keep lock files next to our settings instead of the default java.io.tmpdir: temp cleaners
    //delete files there, and SingleInstanceManager watches this whole directory for restore
    //requests, which is better not pointed at the busiest directory on the machine
    SingleInstanceManager.configuration = SingleInstanceManager.Configuration(
        lockFilesDir = appSettingsDir.toPath(),
        lockIdentifier = "shttps-" + Integer.toHexString(canonicalConfigPath.hashCode())
    )

    if (!SingleInstanceManager.isSingleInstance(onRestoreRequest = { restoreRequests.tryEmit(Unit) })) {
        //isSingleInstance() also returns false when it fails to create or lock the file at all,
        //so this line is the only trace that such a startup leaves
        logger.i("Another instance already uses $canonicalConfigPath, asked it to show its window")
        return false
    }

    //a restore request file left over by a crashed run would break restoring forever: the sender
    //fails with FileAlreadyExistsException and the watcher only reacts to newly created files.
    //Safe to delete here because we already hold the lock, so nobody waits for a pending request.
    try {
        Files.deleteIfExists(SingleInstanceManager.configuration.restoreRequestFilePath)
    } catch (e: IOException) {
        logger.w("Can't delete stale restore request file", e)
    }

    return true
}

fun AppNavigation(
    config: AppConfig,
    appSettingsDir: File,
    profiles: ConfigProfiles?,
) {
    application {
        AppContent(this, config, appSettingsDir, profiles)
    }
}

sealed class MainWindowEvents {
    object ToggleServerRunning : MainWindowEvents()
}

@Composable
fun ApplicationScope.AppContent(
    appScope: ApplicationScope,
    config: AppConfig,
    appSettingsDir: File,
    //null when an explicit config path was given on the command line: that mode is one file by
    //definition, so there is nothing to switch between
    profiles: ConfigProfiles?,
) {
    val navController = rememberNavController()

    val autoLaunch = remember { AutoLaunch(appPackageName = "com.phlox.simpleserver") }

    val windowState = rememberWindowState(size = remember { initialWindowSize(config) })

    val closeToTray = remember { mutableStateOf(config.closeToTray) }

    //A packaged build starts with the system through a startup task declared in its manifest, which
    //the auto launch library knows nothing about - isStartedViaAutostart() is simply always false
    //there, and nothing distinguishes that launch from the user clicking the icon.
    //
    //So "close to tray is on" stands in for "was auto-started". The cost is that a manual launch
    //also comes up in the tray rather than on screen; that is the milder failure, because the tray
    //icon is always created and launching a second time restores the window through
    //SingleInstanceManager, whereas guessing the other way would show an unwanted window at logon.
    val isWindowHiddenInitially = closeToTray.value &&
            (DesktopExtensions.autostartManagedBySystem || autoLaunch.isStartedViaAutostart())
    val isVisible = remember { mutableStateOf(!isWindowHiddenInitially) }

    val scope = rememberCoroutineScope()
    val stopServerLabel = stringResource(Res.string.stop_server)
    val startServerLabel = stringResource(Res.string.start_server)
    val exitLabel = stringResource(Res.string.exit)
    val events = remember { MutableSharedFlow<MainWindowEvents>() }
    val serverRunning = remember { mutableStateOf(config.runningState) }
    //lives here rather than in HomeViewModel so that the prompts it drives can be shown while the
    //main window is hidden in the tray - which is where a running server normally sits
    val approvalController = remember { ClientApprovalController() }
    val screenAccessController = remember { ScreenAccessApprovalController() }

    //Remember the size the user leaves the window at. The placement and minimized flags are read
    //again *after* the wait rather than filtered upstream: the size arrives from AWT's
    //componentResized, which on maximizing can land before placement flips to Maximized, and on
    //Windows a minimize reports the tiny iconic size - either would otherwise be saved as the
    //window's own size. collectLatest restarts the wait on every emission, which is what debounces
    //the drag without needing the @FlowPreview debounce() operator.
    LaunchedEffect(windowState) {
        snapshotFlow { Triple(windowState.size, windowState.placement, windowState.isMinimized) }
            .collectLatest {
                delay(WINDOW_SIZE_SAVE_DELAY_MS)
                val size = windowState.size
                if (windowState.placement == WindowPlacement.Floating &&
                    !windowState.isMinimized && size.isSpecified
                ) {
                    config.setWindowSize(
                        size.width.value.roundToInt(),
                        size.height.value.roundToInt()
                    )
                }
            }
    }

    //someone launched the app again while we were already running (typically from a shortcut, while
    //we sit hidden in the tray). The request arrives on the SingleInstanceManager watcher thread,
    //collecting it here moves it to the UI dispatcher.
    LaunchedEffect(Unit) {
        restoreRequests.collect {
            windowState.isMinimized = false
            isVisible.value = true
        }
    }

    LaunchedEffect(Unit) {
        trayToggleRequests.collect {
            if (isVisible.value) {
                isVisible.value = false
            } else {
                //show the window next to the tray icon instead of wherever it was left. Only for
                //this path: showing it any other way (the single instance restore, for example)
                //must not drag it away from where the user put it.
                //Where the anchor comes from differs per platform - Linux records this very click
                //before calling us, Windows polled the notification area at startup (see
                //trayIconPoint). A null answer means it is not known at all, and then leaving the
                //window where the user last had it beats guessing.
                trayAnchoredWindowPosition(
                    windowState.size.width.value.roundToInt(),
                    windowState.size.height.value.roundToInt()
                )?.let { windowState.position = it }
                windowState.isMinimized = false
                isVisible.value = true
            }
        }
    }

    Window(
        onCloseRequest = {
            if (config.closeToTray) {
                isVisible.value = false
            } else {
                appScope.exitApplication()
            }
        },
        title = "SHTTPS",
        icon = painterResource(Res.drawable.window_icon),
        state = windowState,
        resizable = true,
        visible = isVisible.value
    ) {
        //toFront() does nothing while the window is still hidden, so it waits for the composition
        //that applied visible = true rather than running right after the state write above
        LaunchedEffect(isVisible.value) {
            if (isVisible.value) {
                this@Window.window.toFront()
            }
        }

        AppTheme {
            //Surface (not a plain background-painted Box): it is what provides LocalContentColor,
            //so unstyled Text/TextField/Checkbox labels inherit onBackground instead of the
            //compose-material default of black
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background
            ) {
                AppNavigationGraph(
                    navController = navController,
                    appScope = appScope,
                    window = this@Window.window,
                    config = config,
                    appSettingsDir = appSettingsDir,
                    profiles = profiles,
                    onHideAppToTrayValueChange = {
                        if (config.closeToTray != closeToTray.value) {
                            closeToTray.value = config.closeToTray
                        }
                    },
                    mainWindowEvents = events,
                    serverRunning = serverRunning,
                    autoLaunch = autoLaunch,
                    approvalController = approvalController,
                    screenAccessController = screenAccessController,
                    windowVisible = isVisible
                )
            }
        }
    }

    //deliberately outside the closeToTray check below: an unknown client has to be reported no
    //matter whether the app uses a tray icon
    NewClientPrompts(approvalController, screenAccessController)

    if (closeToTray.value) {
        Tray(
            icon = painterResource(Res.drawable.tray),
            tooltip = "SHTTPS",
            primaryAction = {
                trayToggleRequests.tryEmit(Unit)
            }
        ) {
            if (serverRunning.value) {
                Item(label = stopServerLabel) {
                    scope.launch {
                        events.emit(MainWindowEvents.ToggleServerRunning)
                    }
                }
            } else {
                Item(label = startServerLabel) {
                    scope.launch {
                        events.emit(MainWindowEvents.ToggleServerRunning)
                    }
                }
            }

            Divider()

            Item(label = exitLabel) {
                exitApplication()
            }
        }
    }
}
