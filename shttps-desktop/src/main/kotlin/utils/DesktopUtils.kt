package com.phlox.simpleserver.utils

import com.phlox.simpleserver.ext.DesktopExtensions
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

fun openUrlInBrowser(url: String) {
    try {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (e: Exception) {
        // Handle error silently or show a message
        println("Failed to open URL: $url")
    }
}

/**
 * Opens a shell URI that is handled by the operating system rather than by a browser, such as
 * "ms-settings:startupapps".
 *
 * Deliberately not [openUrlInBrowser]: Desktop.browse() is specified for http/https and does not
 * reliably hand a custom scheme to the shell, so this shells out to let the shell resolve it. The
 * empty argument after "start" is the window title that "start" would otherwise take the URI for.
 */
fun openSystemSettings(uri: String) {
    try {
        ProcessBuilder("cmd", "/c", "start", "", uri).start()
    } catch (e: Exception) {
        println("Failed to open system settings: $uri (${e.message})")
    }
}

/**
 * Opens the EULA of this build in the user's default browser.
 * Copies the resource to a temp file and opens it via file:// URI.
 *
 * Which document that is depends on the build: the open source one bundled here, or whatever a
 * [DesktopExtensions] implementation on the classpath ships instead.
 */
fun openLicenseInBrowser() {
    try {
        val resource = DesktopExtensions.eulaResource
        val stream = DesktopExtensions.openResource(resource)
            ?: throw IllegalStateException("$resource not found in resources")
        val tempFile = File.createTempFile("shttps-eula-", ".html")
        tempFile.deleteOnExit()
        tempFile.outputStream().use { out ->
            stream.copyTo(out)
        }
        openUrlInBrowser(tempFile.toURI().toString())
    } catch (e: Exception) {
        println("Failed to open EULA: ${e.message}")
    }
}