package com.phlox.simpleserver.tray

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.composenativetray.tray.impl.MacTrayInitializer
import dev.nucleusframework.composenativetray.utils.TrayClickTracker
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Places a window next to the tray icon.
 *
 * This replaces `getTrayWindowPosition()` from the `composenativetray-app` artifact, which we no
 * longer depend on. That helper took all of its screen geometry from `nucleus.decorated-window-tao`
 * - an 11 MB windowing backend this build never shipped - and wrapped every call to it in
 * `runCatching`, which also swallows the `NoClassDefFoundError` that leaving it out causes. The
 * result was not a failure but a silent one: the scale factor came back as 1.0 and the work area as
 * a hardcoded 1920x1080, so on any scaled display the window was placed and clamped against a
 * screen that does not exist and ended up partly outside the real one.
 *
 * Everything needed to do it properly is in the core tray artifact we already ship:
 * [TrayClickTracker] records the click on Windows and Linux, [MacTrayInitializer] reports the status
 * item on macOS, and AWT knows the real geometry. Note that both of those are documented as the seam
 * the `-app` artifact reads, not as an API for application code, so the tray library version is
 * pinned and wants re-checking on every bump.
 */
fun trayAnchoredWindowPosition(
    windowWidth: Int,
    windowHeight: Int,
): WindowPosition.Absolute? {
    val icon = runCatching { trayIconPoint() }.getOrNull() ?: return null
    val device = if (icon.physical) {
        devicePhysicallyContaining(icon.x, icon.y)
    } else {
        deviceLogicallyContaining(icon.x, icon.y)
    }
    val configuration = (device ?: defaultDevice() ?: return null).defaultConfiguration
    val logical = if (icon.physical) toLogical(icon.x, icon.y, configuration) else icon.x to icon.y
    val (x, y) = anchorToTrayIcon(
        iconX = logical.first,
        iconY = logical.second,
        workArea = workArea(configuration),
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        forceTop = icon.forceTop
    )
    return WindowPosition.Absolute(x.dp, y.dp)
}

/**
 * Where a window goes when the tray icon's place is not known at all: the end of the bar the
 * taskbar occupies, which is where a notification area normally sits. Used for the approval prompts,
 * which can be raised before anyone has touched the tray icon - the app autostarts into the tray and
 * a client connects. That is the normal case on Linux, where only a real click is recorded; on
 * Windows the anchor is usually known by then anyway. See [trayIconPoint].
 *
 * [bounds] and [workArea] are the display's full and usable rectangles; the largest inset between
 * them is the bar, so its far end is the corner to use.
 */
internal fun trayLessCorner(
    bounds: LogicalRect,
    workArea: LogicalRect,
    windowWidth: Int,
    windowHeight: Int,
    margin: Int,
): Pair<Int, Int> {
    val insetLeft = workArea.x - bounds.x
    val insetTop = workArea.y - bounds.y
    val insetRight = (bounds.x + bounds.width) - (workArea.x + workArea.width)
    val insetBottom = (bounds.y + bounds.height) - (workArea.y + workArea.height)

    val vertical = max(insetLeft, insetRight) > max(insetTop, insetBottom)
    val x: Int
    val y: Int
    if (vertical) {
        //a bar down one side: hug that side, at the bottom of it
        x = if (insetLeft >= insetRight) workArea.x + margin else workArea.x + workArea.width - windowWidth - margin
        y = workArea.y + workArea.height - windowHeight - margin
    } else {
        //a horizontal bar (or none at all): the right hand end of it
        x = workArea.x + workArea.width - windowWidth - margin
        y = if (insetTop > insetBottom) workArea.y + margin else workArea.y + workArea.height - windowHeight - margin
    }

    val maxX = (workArea.x + workArea.width - windowWidth).coerceAtLeast(workArea.x)
    val maxY = (workArea.y + workArea.height - windowHeight).coerceAtLeast(workArea.y)
    return x.coerceIn(workArea.x, maxX) to y.coerceIn(workArea.y, maxY)
}

/**
 * The middle of the work area of the display a window opens on, or null in a headless environment.
 * Tells a caller whether a position is in the top or the bottom half of the *usable* screen, which
 * the full screen height gets wrong by half a taskbar.
 */
fun workAreaCentreYOnDefaultScreen(): Float? {
    val area = workArea((defaultDevice() ?: return null).defaultConfiguration)
    return area.y + area.height / 2f
}

/** [trayLessCorner] for the display the window would open on, or null in a headless environment. */
fun trayLessCornerOnDefaultScreen(
    windowWidth: Int,
    windowHeight: Int,
    margin: Int,
): WindowPosition.Absolute? {
    val configuration = (defaultDevice() ?: return null).defaultConfiguration
    val bounds = configuration.bounds
    val (x, y) = trayLessCorner(
        bounds = LogicalRect(bounds.x, bounds.y, bounds.width, bounds.height),
        workArea = workArea(configuration),
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        margin = margin
    )
    return WindowPosition.Absolute(x.dp, y.dp)
}

/** A rectangle in the logical units AWT and Compose's window API both use. */
internal data class LogicalRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Where a window of [windowWidth] x [windowHeight] goes so that it sits flush against the screen
 * edge the tray icon lives on, centred on the icon along that edge. Pure, so it is unit tested.
 *
 * The icon is normally *outside* [workArea] - that is what a taskbar is - which is exactly how the
 * edge is found: the distance to the edge the bar sits on comes out negative, so the smallest of the
 * four picks it. The window is then anchored to the work area rather than to the icon, which keeps
 * it from being drawn under the bar, and clamped so that it cannot leave the screen whatever the
 * icon's coordinates turn out to be.
 *
 * [forceTop] skips that search and anchors to the top edge. macOS passes it, because its menu bar is
 * always at the top and [iconY] there cannot be trusted to say so.
 */
internal fun anchorToTrayIcon(
    iconX: Int,
    iconY: Int,
    workArea: LogicalRect,
    windowWidth: Int,
    windowHeight: Int,
    forceTop: Boolean = false,
): Pair<Int, Int> {
    val toLeft = iconX - workArea.x
    val toRight = workArea.x + workArea.width - iconX
    val toTop = iconY - workArea.y
    val toBottom = workArea.y + workArea.height - iconY

    val x: Int
    val y: Int
    if (forceTop) {
        x = iconX - windowWidth / 2
        y = workArea.y
    } else if (min(toTop, toBottom) <= min(toLeft, toRight)) {
        //a horizontal bar, at the top (a macOS menu bar, a GNOME panel) or the bottom
        x = iconX - windowWidth / 2
        y = if (toTop <= toBottom) workArea.y else workArea.y + workArea.height - windowHeight
    } else {
        //a bar down one side of the screen
        x = if (toLeft <= toRight) workArea.x else workArea.x + workArea.width - windowWidth
        y = iconY - windowHeight / 2
    }

    //coerceIn throws when the ceiling falls under the floor, which a window bigger than the work
    //area would do
    val maxX = (workArea.x + workArea.width - windowWidth).coerceAtLeast(workArea.x)
    val maxY = (workArea.y + workArea.height - windowHeight).coerceAtLeast(workArea.y)
    return x.coerceIn(workArea.x, maxX) to y.coerceIn(workArea.y, maxY)
}

/**
 * The tray icon's place on screen, or null when it is not known - the desktop has no tray, Linux has
 * not seen a click yet, or on macOS the status item has not been realised. Callers fall back rather
 * than guess, because a guess puts a window somewhere the user is not looking.
 *
 * `TrayClickTracker` is named after the Linux half, and the two platforms fill it in differently:
 *
 * - **Windows** records the *notification area* position in physical pixels, polled by the tray
 *   manager's own loop once the icon exists (so it is there without anyone clicking, which is what
 *   lets a prompt be placed on a server that started with the system). It is captured once; nothing
 *   in the core artifact refreshes it, so moving the taskbar mid-session leaves it stale. Harmless -
 *   [anchorToTrayIcon] clamps, so a stale anchor misplaces the window rather than losing it.
 * - **Linux** records the real click, in logical pixels, from inside the click callback and before
 *   our own handler runs - so there it is always current, and only available after a first click.
 */
private fun trayIconPoint(): IconPoint? = when (trayOs()) {
    TrayOs.WINDOWS -> TrayClickTracker.lastClick()?.let { IconPoint(it.x, it.y, physical = true) }
    TrayOs.LINUX -> TrayClickTracker.lastClick()?.let { IconPoint(it.x, it.y, physical = false) }
    TrayOs.MACOS -> macStatusItemPoint()
    null -> null
}

/**
 * The menu bar status item on macOS. Not tested on a real Mac, so it deliberately mirrors what the
 * `-app` artifact's own code does rather than what looks right:
 *
 * - **Logical, not physical units.** `MacTrayInitializer`'s own KDoc says physical pixels, but the
 *   library feeds the result straight into a function documented as taking logical ones, with no
 *   division anywhere. The running code wins over the comment; on a Retina display the other reading
 *   would put the window at half the distance from the left edge.
 * - **The y is not used to find an edge.** The library never derives the corner from it - it asks
 *   `statusItemRegion()` instead - and a Cocoa y would be measured from the bottom. The menu bar is
 *   always at the top, so [anchorToTrayIcon] is told that directly.
 * - **An x too close to the left edge means "not laid out yet"**, which is the library's own guard
 *   (`isLaidOutMacStatusItem`, 24 px, y explicitly unused). The status item is created
 *   asynchronously, and without this a prompt raised during startup would be placed at the origin.
 */
private fun macStatusItemPoint(): IconPoint? {
    val out = IntArray(2)
    if (!MacTrayInitializer.statusItemPosition(out)) return null
    if (out[0] < MAC_STATUS_ITEM_MIN_X) return null
    return IconPoint(out[0], out[1], physical = false, forceTop = true)
}

private const val MAC_STATUS_ITEM_MIN_X = 24

private data class IconPoint(
    val x: Int,
    val y: Int,
    val physical: Boolean,
    val forceTop: Boolean = false,
)

private enum class TrayOs { WINDOWS, MACOS, LINUX }

private fun trayOs(osName: String? = System.getProperty("os.name")): TrayOs? {
    val name = osName?.lowercase() ?: return null
    return when {
        name.contains("win") -> TrayOs.WINDOWS
        name.contains("mac") || name.contains("darwin") -> TrayOs.MACOS
        name.contains("nux") || name.contains("nix") || name.contains("aix") -> TrayOs.LINUX
        else -> null
    }
}

/**
 * The display holding a point given in physical pixels. AWT states every display's bounds in logical
 * units, so a display's physical rectangle is its logical one times its own scale factor - the same
 * approximation `ScreenCaptureDevice.physicalMonitorBounds()` makes in desktop-plus, and an exact
 * answer whenever the displays all run at one scale.
 */
private fun devicePhysicallyContaining(
    x: Int,
    y: Int,
): GraphicsDevice? = screenDevices().firstOrNull {
    val bounds = it.defaultConfiguration.bounds
    val transform = it.defaultConfiguration.defaultTransform
    val left = (bounds.x * transform.scaleX).roundToInt()
    val top = (bounds.y * transform.scaleY).roundToInt()
    val width = (bounds.width * transform.scaleX).roundToInt()
    val height = (bounds.height * transform.scaleY).roundToInt()
    x >= left && x < left + width && y >= top && y < top + height
}

private fun deviceLogicallyContaining(
    x: Int,
    y: Int,
): GraphicsDevice? = screenDevices().firstOrNull { it.defaultConfiguration.bounds.contains(x, y) }

private fun toLogical(
    physicalX: Int,
    physicalY: Int,
    configuration: GraphicsConfiguration,
): Pair<Int, Int> {
    val bounds = configuration.bounds
    val transform = configuration.defaultTransform
    //the offset inside the display is what gets scaled; the origin is already logical
    val left = (bounds.x * transform.scaleX).roundToInt()
    val top = (bounds.y * transform.scaleY).roundToInt()
    return (bounds.x + ((physicalX - left) / transform.scaleX).roundToInt()) to
        (bounds.y + ((physicalY - top) / transform.scaleY).roundToInt())
}

/** The display's area minus the taskbar, menu bar or panel, in logical units. */
private fun workArea(configuration: GraphicsConfiguration): LogicalRect {
    val bounds = configuration.bounds
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }.getOrNull()
        ?: return LogicalRect(bounds.x, bounds.y, bounds.width, bounds.height)
    return LogicalRect(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom
    )
}

private fun screenDevices(): List<GraphicsDevice> =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList() }
        .getOrDefault(emptyList())

private fun defaultDevice(): GraphicsDevice? =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice }.getOrNull()
