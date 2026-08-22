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
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.buildconfig.BuildConfig
import com.phlox.simpleserver.ext.DesktopExtensions
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.*
import com.phlox.simpleserver.utils.openLicenseInBrowser
import org.jetbrains.compose.resources.stringResource

@Composable
fun VersionInfoScreen(
    onNavigateBack: () -> Unit
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
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.primary
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
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.primary
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
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.primary
                    )

                    Text(
                        text = stringResource(Res.string.about_description),
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onSurface
                    )

                    Text(
                        text = stringResource(Res.string.for_more_information, stringResource(Res.string.project_website)),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.primary
                    )

                    TextButton(
                        onClick = { openLicenseInBrowser() }
                    ) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                            tint = MaterialTheme.colors.primary
                        )
                        Text(
                            text = stringResource(Res.string.view_full_eula),
                            color = MaterialTheme.colors.primary
                        )
                    }
                }
            }
        }
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
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
