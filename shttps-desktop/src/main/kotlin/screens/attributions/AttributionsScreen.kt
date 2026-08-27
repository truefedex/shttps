package com.phlox.simpleserver.screens.attributions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.openUrlInBrowser
import org.jetbrains.compose.resources.stringResource

/**
 * The third-party components this copy of the application ships, as resolved by [Attributions].
 *
 * A [LazyColumn] rather than the `verticalScroll` its sibling [com.phlox.simpleserver.screens.VersionInfoScreen]
 * uses: that screen is two cards, this one is a few dozen.
 */
@Composable
fun AttributionsScreen(
    onNavigateBack: () -> Unit
) {
    val attributions = Attributions.current

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.cd_back),
                            tint = MaterialTheme.colors.onBackground
                        )
                    }
                    Text(
                        text = stringResource(Res.string.attributions),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onBackground
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.attributions_intro),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground
                    )
                    //which build and which operating system the list below was filtered for; two
                    //machines legitimately show different lists, and this is what explains that
                    Text(
                        text = stringResource(
                            Res.string.attributions_shown_for,
                            DesktopExtensions.editionName,
                            System.getProperty("os.name") ?: stringResource(Res.string.unknown)
                        ),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackground
                    )
                }
            }

            if (attributions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.attributions_none),
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onBackground
                    )
                }
            } else {
                items(attributions) { attribution ->
                    AttributionCard(attribution)
                }
            }
        }
    }
}

@Composable
private fun AttributionCard(attribution: Attribution) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = attribution.displayName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.secondaryVariant
            )

            attribution.copyright?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface
                )
            }

            Text(
                text = attribution.license,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface
            )

            attribution.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton(stringResource(Res.string.attribution_website), attribution.url)
                LinkButton(stringResource(Res.string.attribution_license), attribution.licenseUrl)
                LinkButton(stringResource(Res.string.attribution_source), attribution.sourceUrl)
            }
        }
    }
}

/** A link, or nothing at all when this entry does not carry that address. */
@Composable
private fun LinkButton(label: String, url: String?) {
    if (url.isNullOrBlank()) return
    TextButton(
        onClick = { openUrlInBrowser(url) },
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colors.secondaryVariant
        )
    ) {
        Text(text = label)
    }
}
