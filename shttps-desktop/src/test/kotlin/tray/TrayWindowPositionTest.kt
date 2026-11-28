package com.phlox.simpleserver.tray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards [anchorToTrayIcon], the half of tray positioning that is pure arithmetic.
 *
 * The bug this replaced was not a wrong formula but a geometry source that silently answered with a
 * screen that did not exist, so the property these tests care about most is the one that holds
 * whatever the inputs are: the window ends up inside the work area. The first test is the real case
 * from a 1920x1080 display at 125%, where the old code put the window 204 px past the right edge.
 */
class TrayWindowPositionTest {

    //1920x1080 at 125% is 1536x864 logical, less a 48 px taskbar at the bottom
    private val windowsWorkArea = LogicalRect(0, 0, 1536, 816)

    @Test
    fun `bottom taskbar anchors the window above it, centred on the icon`() {
        //the click this machine recorded, 1460x1020 physical, is 1168x816 logical
        val (x, y) = anchorToTrayIcon(
            iconX = 1168,
            iconY = 816,
            workArea = windowsWorkArea,
            windowWidth = 560,
            windowHeight = 580
        )
        assertEquals(1168 - 280, x, "the window should be centred on the icon")
        assertEquals(816 - 580, y, "the window should sit flush on top of the taskbar")
    }

    @Test
    fun `the old off-screen placement can no longer happen`() {
        val (x, y) = anchorToTrayIcon(1168, 816, windowsWorkArea, 560, 580)
        assertTrue(x + 560 <= windowsWorkArea.width, "the window hangs off the right edge: x=$x")
        assertTrue(y + 580 <= windowsWorkArea.height, "the window hangs below the work area: y=$y")
    }

    @Test
    fun `an icon near the right edge is pulled back onto the screen`() {
        val (x, _) = anchorToTrayIcon(1530, 816, windowsWorkArea, 560, 580)
        assertEquals(1536 - 560, x, "the window should stop at the right edge")
    }

    @Test
    fun `an icon near the left edge is pulled back onto the screen`() {
        val (x, _) = anchorToTrayIcon(6, 816, windowsWorkArea, 560, 580)
        assertEquals(0, x, "the window should stop at the left edge")
    }

    @Test
    fun `a top bar anchors the window below it`() {
        //a macOS menu bar or a GNOME panel: 25 px off the top, so the work area starts at y=25
        val workArea = LogicalRect(0, 25, 1440, 875)
        val (x, y) = anchorToTrayIcon(1200, 12, workArea, 400, 300)
        assertEquals(1000, x)
        assertEquals(25, y, "the window should hang from the top of the work area")
    }

    @Test
    fun `a bar down the left side anchors the window to that side`() {
        val workArea = LogicalRect(64, 0, 1472, 864)
        val (x, y) = anchorToTrayIcon(30, 400, workArea, 400, 300)
        assertEquals(64, x, "the window should sit against the left edge of the work area")
        assertEquals(250, y, "the window should be centred vertically on the icon")
    }

    @Test
    fun `a bar down the right side anchors the window to that side`() {
        val workArea = LogicalRect(0, 0, 1472, 864)
        val (x, y) = anchorToTrayIcon(1500, 400, workArea, 400, 300)
        assertEquals(1472 - 400, x, "the window should sit against the right edge of the work area")
        assertEquals(250, y)
    }

    @Test
    fun `a work area offset by another monitor keeps the window on that monitor`() {
        //a second display to the right of a 1536 wide primary one
        val workArea = LogicalRect(1536, 0, 1920, 1032)
        val (x, y) = anchorToTrayIcon(3400, 1032, workArea, 560, 580)
        assertTrue(x >= 1536, "the window left the monitor to the left: x=$x")
        assertTrue(x + 560 <= 1536 + 1920, "the window left the monitor to the right: x=$x")
        assertEquals(1032 - 580, y)
    }

    @Test
    fun `a window larger than the work area is placed at the origin rather than throwing`() {
        val (x, y) = anchorToTrayIcon(1168, 816, windowsWorkArea, 2000, 2000)
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `forceTop anchors to the top edge however low the icon reports itself`() {
        //macOS hands us a y we do not trust, possibly measured from the bottom of the screen
        val workArea = LogicalRect(0, 25, 1440, 875)
        val (x, y) = anchorToTrayIcon(1200, 870, workArea, 400, 300, forceTop = true)
        assertEquals(1000, x)
        assertEquals(25, y, "the menu bar is at the top whatever the reported y says")
    }

    @Test
    fun `the tray-less corner sits above a bottom taskbar`() {
        val (x, y) = trayLessCorner(
            bounds = LogicalRect(0, 0, 1536, 864),
            workArea = windowsWorkArea,
            windowWidth = 340,
            windowHeight = 230,
            margin = 16
        )
        assertEquals(1536 - 340 - 16, x, "the prompt should sit at the right hand end of the taskbar")
        assertEquals(816 - 230 - 16, y)
        assertTrue(y + 230 <= 816, "the prompt's buttons would be under the taskbar: y=$y")
    }

    @Test
    fun `the tray-less corner hangs below a top bar`() {
        val (_, y) = trayLessCorner(
            bounds = LogicalRect(0, 0, 1440, 900),
            workArea = LogicalRect(0, 25, 1440, 875),
            windowWidth = 340,
            windowHeight = 230,
            margin = 16
        )
        assertEquals(25 + 16, y, "a menu bar at the top puts the prompt at the top")
    }

    @Test
    fun `the tray-less corner stays inside a side bar's work area`() {
        val (x, y) = trayLessCorner(
            bounds = LogicalRect(0, 0, 1536, 864),
            workArea = LogicalRect(64, 0, 1472, 864),
            windowWidth = 340,
            windowHeight = 230,
            margin = 16
        )
        assertEquals(64 + 16, x, "a bar down the left side puts the prompt against it")
        assertTrue(y + 230 <= 864)
    }

    @Test
    fun `the tray-less corner survives a window larger than the screen`() {
        val (x, y) = trayLessCorner(
            bounds = LogicalRect(0, 0, 800, 600),
            workArea = LogicalRect(0, 0, 800, 560),
            windowWidth = 1000,
            windowHeight = 1000,
            margin = 16
        )
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun `an icon inside the work area still picks its nearest edge`() {
        //a panel that reports no insets at all, so the icon is not outside anything
        val workArea = LogicalRect(0, 0, 1536, 864)
        val (_, y) = anchorToTrayIcon(1400, 850, workArea, 560, 580)
        assertEquals(864 - 580, y, "the bottom edge is the nearest one")
    }
}
