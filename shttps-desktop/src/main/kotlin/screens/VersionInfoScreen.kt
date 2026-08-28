package com.phlox.simpleserver.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.Switch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.buildconfig.BuildConfig
import com.phlox.simpleserver.dialogs.UpdateAvailableDialog
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.updates.GitHubUpdateChecker
import com.phlox.simpleserver.updates.LatestRelease
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.openLicenseInBrowser
import com.phlox.simpleserver.utils.openUrlInBrowser
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

@Composable
fun VersionInfoScreen(
    config: AppConfig,
    onNavigateBack: () -> Unit,
    onNavigateToAttributions: () -> Unit
) {
    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with back button
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
                    text = stringResource(Res.string.version_info),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground
                )
            }

            // Version information card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.application_information),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    InfoRow(stringResource(Res.string.label_application_name), BuildConfig.APPLICATION_NAME)
                    InfoRow(stringResource(Res.string.label_version), BuildConfig.VERSION_NAME)
                    InfoRow(stringResource(Res.string.label_version_code), BuildConfig.VERSION_CODE.toString())
                    InfoRow(stringResource(Res.string.label_build_type), DesktopExtensions.editionName)
                    InfoRow(stringResource(Res.string.label_platform), stringResource(Res.string.platform_desktop))
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(Res.string.system_information),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )
                    
                    InfoRow(stringResource(Res.string.label_java_version), System.getProperty("java.version") ?: stringResource(Res.string.unknown))
                    InfoRow(stringResource(Res.string.label_os_name), System.getProperty("os.name") ?: stringResource(Res.string.unknown))
                    InfoRow(stringResource(Res.string.label_os_version), System.getProperty("os.version") ?: stringResource(Res.string.unknown))
                    InfoRow(stringResource(Res.string.label_os_architecture), System.getProperty("os.arch") ?: stringResource(Res.string.unknown))
                }
            }

            // Additional information card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.about),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    Text(
                        text = stringResource(Res.string.about_description),
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onSurface
                    )

                    Text(
                        text = stringResource(Res.string.for_more_information, stringResource(Res.string.project_website)),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.secondaryVariant
                    )

                    TextButton(
                        onClick = { openLicenseInBrowser() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondaryVariant
                        )
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = stringResource(Res.string.view_full_eula))
                    }

                    TextButton(
                        onClick = onNavigateToAttributions,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colors.secondaryVariant
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = stringResource(Res.string.attributions))
                    }
                }
            }

            //Absent entirely from a build an application store keeps up to date - this screen is
            //shared code that such a build runs too, and every artifact on the open project's
            //releases page is a different product with a different installer identity, so an
            //update offered from here would install a second application beside it.
            if (DesktopExtensions.updateChecksSupported) {
                UpdatesCard(config)
            }
        }
    }
}

/**
 * Manual update check, and the switch governing the automatic one.
 *
 * Deliberately ignores both the daily interval and any skipped version: this runs because the user
 * pressed a button. It also reports every outcome, where the startup check is silent unless it has
 * something to offer - a check that says nothing is indistinguishable from one that is broken.
 */
@Composable
private fun UpdatesCard(config: AppConfig) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var autoCheck by remember { mutableStateOf(config.updateCheckEnabled) }
    var found by remember { mutableStateOf<LatestRelease?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastChecked by remember { mutableStateOf(config.lastUpdateCheckTime) }

    //hoisted, because the callback below is not composable
    val upToDateFmt = stringResource(Res.string.update_up_to_date)
    val failedText = stringResource(Res.string.update_check_failed)
    val neverText = stringResource(Res.string.update_never_checked)

    Card(modifier = Modifier.fillMaxWidth(), elevation = 4.dp) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(Res.string.updates),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.secondaryVariant
            )

            InfoRow(
                stringResource(Res.string.update_last_checked),
                if (lastChecked > 0) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(lastChecked))
                } else {
                    neverText
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.update_check_enabled),
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoCheck,
                    onCheckedChange = {
                        autoCheck = it
                        config.updateCheckEnabled = it
                    }
                )
            }

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface
                )
            }

            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    status = null
                    scope.launch {
                        val release = GitHubUpdateChecker.fetchLatestRelease()
                        if (release == null) {
                            status = failedText
                        } else {
                            //deliberately shared with the startup throttle: a check is a check,
                            //whoever asked for it, so pressing this also resets the daily clock
                            //and the row above stays honest about when GitHub was last asked
                            config.lastUpdateCheckTime = System.currentTimeMillis()
                            lastChecked = config.lastUpdateCheckTime
                            if (GitHubUpdateChecker.isNewerThan(
                                    release.versionName,
                                    BuildConfig.VERSION_NAME
                                )
                            ) {
                                found = release
                            } else {
                                status = upToDateFmt.format(BuildConfig.VERSION_NAME)
                            }
                        }
                        checking = false
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colors.secondaryVariant
                )
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(
                        if (checking) Res.string.update_checking else Res.string.check_for_updates
                    )
                )
            }
        }
    }

    found?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDownload = {
                openUrlInBrowser(release.releaseUrl)
                found = null
            },
            //offered here too, so a user who came looking can silence a release they do not want
            //instead of being asked again by the startup prompt
            onSkipVersion = {
                config.skippedUpdateVersion = release.versionName
                found = null
            },
            onDismiss = { found = null }
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
