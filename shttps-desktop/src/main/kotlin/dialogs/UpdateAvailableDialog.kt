package com.phlox.simpleserver.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phlox.simpleserver.buildconfig.BuildConfig
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_available_message
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_available_title
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_download
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_later
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_skip_this_version
import com.phlox.simpleserver.shttps_desktop.generated.resources.update_whats_new
import com.phlox.simpleserver.updates.LatestRelease
import org.jetbrains.compose.resources.stringResource

/**
 * Tells the user a newer version was published and offers to open its release page.
 *
 * Its own dialog rather than a [MessageDialog], for two reasons that are easy to hit: that one
 * renders its message as a single unscrolled Text, which release notes will eventually overflow,
 * and it lays its buttons out in one Row at equal weight, which three actions would cramp.
 */
@Composable
fun UpdateAvailableDialog(
    release: LatestRelease,
    onDownload: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    val notes = remember(release.releaseNotes) { formatReleaseNotes(release.releaseNotes) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp), elevation = 8.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(Res.string.update_available_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(
                        Res.string.update_available_message,
                        release.versionName,
                        BuildConfig.VERSION_NAME
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                if (notes.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.update_whats_new),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )
                    //bounded and scrollable: the notes come from the release body, so their length
                    //is decided by whoever wrote RELEASE_NOTES.md rather than by this layout
                    Text(
                        text = notes,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }

                //Deliberately no weight(1f) on the three buttons, unlike MessageDialog: an equal
                //split gives every action the same third of the row, which is too narrow for
                //"Skip this version" and wraps it onto two lines beside two single-line labels.
                //Sized to their content and pushed to the end, they line up and still fit well
                //inside the window's width.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    //the two dismissive actions before the affirmative one, and in the theme's own
                    //accent rather than the default: TextButton's primary colour is nearly
                    //unreadable on this dark surface
                    TextButton(
                        onClick = onSkipVersion,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondaryVariant
                        )
                    ) {
                        Text(stringResource(Res.string.update_skip_this_version), maxLines = 1)
                    }
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondaryVariant
                        )
                    ) {
                        Text(stringResource(Res.string.update_later), maxLines = 1)
                    }
                    Button(onClick = onDownload) {
                        Text(stringResource(Res.string.update_download), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Renders the release body as plain bullet lines.
 *
 * RELEASE_NOTES.md is a flat list of "* " items with no headings, links or emphasis, and this
 * application bundles no Markdown renderer - so the whole of the conversion is turning the source
 * marker into a bullet, exactly as the Android update prompt does. Anything richer that ever
 * appears in the file still shows up as readable text rather than breaking.
 */
internal fun formatReleaseNotes(body: String): String = body
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n") { line ->
        val stripped = line.removePrefix("*").removePrefix("-").trimStart()
        if (stripped != line) "• $stripped" else line
    }
