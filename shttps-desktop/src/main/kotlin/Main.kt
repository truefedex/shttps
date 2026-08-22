package com.phlox.simpleserver

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import com.kdroid.composetray.tray.api.Tray
import com.kdroid.composetray.utils.SingleInstanceManager
import com.kdroid.composetray.utils.getTrayWindowPosition
import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.server.utils.SHTTPSLoggerProxy.TaggedJavaLogger
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.navigation.AppNavigationGraph
import com.phlox.simpleserver.security.ClientApprovalController
import com.phlox.simpleserver.security.NewClientPrompts
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.exit
import com.phlox.simpleserver.shttps_desktop.generated.resources.start_server
import com.phlox.simpleserver.shttps_desktop.generated.resources.stop_server
import com.phlox.simpleserver.shttps_desktop.generated.resources.tray
import com.phlox.simpleserver.shttps_desktop.generated.resources.window_icon
import com.phlox.simpleserver.theme.AppTheme
import io.github.vinceglb.autolaunch.AutoLaunch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.io.IOException
import java.nio.file.Files

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

//the same numbers as the window size, in the AWT logical units that getTrayWindowPosition() expects
private const val WINDOW_WIDTH_DP = 500
private const val WINDOW_HEIGHT_DP = 580

fun main(args: Array<String>) {
    SHTTPSLoggerProxy.setFactory { tag: String? ->
        TaggedJavaLogger(
            tag,
            SHTTPSLoggerProxy.Logger.ALL
        )
    }

    val logger = SHTTPSLoggerProxy.getLogger("Main")

    val appSettingsDir = File(System.getProperty("user.home") + File.separator + ".shttps")
    val configPath = if (args.isEmpty()) {
        appSettingsDir.absolutePath + File.separator + "config.json"
    } else {
        args[0]
    }
    if (!appSettingsDir.exists() && !appSettingsDir.mkdirs()) {
        logger.e("Can't create directory " + appSettingsDir.absolutePath)
        return
    } else {
        if (!acquireSingleInstanceLock(configPath, appSettingsDir, logger)) {
            return
        }

        val config = try {
            AppConfig(File(configPath))
        } catch (_: Exception) {
            logger.e("Can't create config file at $configPath")
            return
        }

        AppNavigation(config, appSettingsDir)
    }
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

fun AppNavigation(config: AppConfig, appSettingsDir: File) {
    application {
        AppContent(this, config, appSettingsDir)
    }
}

sealed class MainWindowEvents {
    object ToggleServerRunning : MainWindowEvents()
}

@Composable
fun ApplicationScope.AppContent(appScope: ApplicationScope, config: AppConfig, appSettingsDir: File) {
    val navController = rememberNavController()

    val autoLaunch = remember { AutoLaunch(appPackageName = "com.phlox.simpleserver") }

    val windowState = rememberWindowState(width = WINDOW_WIDTH_DP.dp, height = WINDOW_HEIGHT_DP.dp)

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
    val approvalController = remember { ClientApprovalController(config) }

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
                //The library refreshes the icon position on this very click before calling us,
                //so what we read here is up to date.
                windowState.position = getTrayWindowPosition(WINDOW_WIDTH_DP, WINDOW_HEIGHT_DP)
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
                    onHideAppToTrayValueChange = {
                        if (config.closeToTray != closeToTray.value) {
                            closeToTray.value = config.closeToTray
                        }
                    },
                    mainWindowEvents = events,
                    serverRunning = serverRunning,
                    autoLaunch = autoLaunch,
                    approvalController = approvalController
                )
            }
        }
    }

    //deliberately outside the closeToTray check below: an unknown client has to be reported no
    //matter whether the app uses a tray icon
    NewClientPrompts(approvalController)

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
