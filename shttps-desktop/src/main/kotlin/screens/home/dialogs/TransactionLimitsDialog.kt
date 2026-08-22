package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.close
import com.phlox.simpleserver.shttps_desktop.generated.resources.db_transaction_limits
import com.phlox.simpleserver.shttps_desktop.generated.resources.save
import com.phlox.simpleserver.shttps_desktop.generated.resources.tx_limits_description
import com.phlox.simpleserver.shttps_desktop.generated.resources.tx_limits_invalid
import com.phlox.simpleserver.shttps_desktop.generated.resources.tx_limits_out_of_range
import com.phlox.simpleserver.shttps_desktop.generated.resources.tx_max_idle
import com.phlox.simpleserver.shttps_desktop.generated.resources.tx_max_lifetime
import org.jetbrains.compose.resources.stringResource

/**
 * The two `/api/db/transaction` timeouts. Both are clamped to
 * `[SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS]` before
 * they are stored, so the number saved is the number the server will enforce; when clamping changes
 * what was typed the fields are rewritten with the stored values and the dialog stays open, rather
 * than closing on a number that never took effect.
 */
@Composable
fun TransactionLimitsDialog(
    currentMaxLifetimeMs: Int,
    currentInactivityMs: Int,
    onSave: (maxLifetimeMs: Int, inactivityMs: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var maxLifetimeText by remember { mutableStateOf(currentMaxLifetimeMs.toString()) }
    var inactivityText by remember { mutableStateOf(currentInactivityMs.toString()) }
    var maxLifetimeError by remember { mutableStateOf(false) }
    var inactivityError by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clampedNotice by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val min = SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS
    val max = SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS
    val invalidMessage = stringResource(Res.string.tx_limits_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.db_transaction_limits))
        },
        text = {
            Column {
                Text(stringResource(Res.string.tx_limits_description, min, max))
                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = maxLifetimeText,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() }) {
                            maxLifetimeText = it
                            maxLifetimeError = false
                            error = null
                        }
                    },
                    label = { Text(stringResource(Res.string.tx_max_lifetime)) },
                    singleLine = true,
                    isError = maxLifetimeError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = inactivityText,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() }) {
                            inactivityText = it
                            inactivityError = false
                            error = null
                        }
                    },
                    label = { Text(stringResource(Res.string.tx_max_idle)) },
                    singleLine = true,
                    isError = inactivityError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error!!,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp
                    )
                }
                if (clampedNotice != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            Res.string.tx_limits_out_of_range,
                            clampedNotice!!.first,
                            clampedNotice!!.second
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        val typedMaxLifetime = maxLifetimeText.trim().toIntOrNull()
                        val typedInactivity = inactivityText.trim().toIntOrNull()
                        maxLifetimeError = typedMaxLifetime == null
                        inactivityError = typedInactivity == null
                        if (typedMaxLifetime == null || typedInactivity == null) {
                            error = invalidMessage
                            return@TextButton
                        }
                        val maxLifetime = SHTTPSConfig.clampDBTransactionTimeout(typedMaxLifetime)
                        val inactivity = SHTTPSConfig.clampDBTransactionTimeout(typedInactivity)
                        onSave(maxLifetime, inactivity)
                        if (maxLifetime != typedMaxLifetime || inactivity != typedInactivity) {
                            maxLifetimeText = maxLifetime.toString()
                            inactivityText = inactivity.toString()
                            clampedNotice = maxLifetime to inactivity
                        } else {
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(Res.string.save))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onDismiss
                ) {
                    Text(stringResource(Res.string.close))
                }
            }
        }
    )
}
