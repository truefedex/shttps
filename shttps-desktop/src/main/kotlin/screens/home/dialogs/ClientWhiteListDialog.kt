package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.SHTTPSConfig
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.add
import com.phlox.simpleserver.shttps_desktop.generated.resources.ask_at_runtime_by_notification
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.cd_delete
import com.phlox.simpleserver.shttps_desktop.generated.resources.invalid_ip_address
import com.phlox.simpleserver.shttps_desktop.generated.resources.ip_address_already_added
import com.phlox.simpleserver.shttps_desktop.generated.resources.ip_address_hint
import com.phlox.simpleserver.shttps_desktop.generated.resources.no_ip_addresses
import com.phlox.simpleserver.shttps_desktop.generated.resources.save
import com.phlox.simpleserver.shttps_desktop.generated.resources.white_list_of_clients_ips
import org.jetbrains.compose.resources.stringResource
import java.net.InetAddress

/**
 * Editor for the client whitelist, the desktop counterpart of Android's ClientWhiteListDialogView.
 *
 * Owns nothing but its own local state: the two modes and the list of addresses are handed back
 * through [onSave], and writing them to the config (and to the running server) is the caller's job.
 */
@Composable
fun ClientWhiteListDialog(
    currentMode: Set<SHTTPSConfig.WhiteListMode>,
    currentWhiteList: Set<String>,
    onSave: (Set<SHTTPSConfig.WhiteListMode>, Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    //keyed on the incoming values: answering a connection prompt with "allow and save" writes the
    //whitelist while this dialog can be open on top of it, and starting over from what was just
    //persisted is better than saving a stale list back over the address the user has approved
    var askAtRuntime by remember(currentMode) {
        mutableStateOf(currentMode.contains(SHTTPSConfig.WhiteListMode.ASK_AT_RUNTIME))
    }
    var predefined by remember(currentMode) {
        mutableStateOf(currentMode.contains(SHTTPSConfig.WhiteListMode.PREDEFINED))
    }
    val whiteList = remember(currentWhiteList) {
        mutableStateListOf<String>().apply { addAll(currentWhiteList) }
    }
    var newAddress by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }

    val alreadyAddedMsg = stringResource(Res.string.ip_address_already_added)
    val invalidAddressMsg = stringResource(Res.string.invalid_ip_address)

    fun addAddress() {
        val address = newAddress.trim()
        if (address.isEmpty()) return
        if (whiteList.contains(address)) {
            addressError = alreadyAddedMsg
            return
        }
        //same check the server does when it applies the list, so anything accepted here is
        //something the server can actually resolve later (a host name included)
        val resolved = runCatching { InetAddress.getByName(address) }.getOrNull()
        if (resolved == null) {
            addressError = invalidAddressMsg
            return
        }
        whiteList.add(address)
        newAddress = ""
        addressError = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.white_list_of_clients_ips),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = askAtRuntime,
                        onCheckedChange = { askAtRuntime = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.ask_at_runtime_by_notification),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = predefined,
                        onCheckedChange = { predefined = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.white_list_of_clients_ips),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = newAddress,
                        onValueChange = {
                            newAddress = it
                            addressError = null
                        },
                        label = { Text(stringResource(Res.string.ip_address_hint)) },
                        singleLine = true,
                        isError = addressError != null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { addAddress() },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(Res.string.add))
                    }
                }
                if (addressError != null) {
                    Text(
                        text = addressError!!,
                        color = MaterialTheme.colors.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp)
                ) {
                    items(whiteList, key = { it }) { address ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = address, modifier = Modifier.weight(1f))
                            IconButton(onClick = { whiteList.remove(address) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(Res.string.cd_delete)
                                )
                            }
                        }
                    }
                    if (whiteList.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.no_ip_addresses),
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val mode = HashSet<SHTTPSConfig.WhiteListMode>()
                    if (askAtRuntime) mode.add(SHTTPSConfig.WhiteListMode.ASK_AT_RUNTIME)
                    if (predefined) mode.add(SHTTPSConfig.WhiteListMode.PREDEFINED)
                    onSave(mode, whiteList.toSet())
                }) { Text(stringResource(Res.string.save)) }
            }
        }
    )
}
