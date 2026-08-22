package com.phlox.simpleserver.screens.home.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults.textButtonColors
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.allow_all
import com.phlox.simpleserver.shttps_desktop.generated.resources.cancel
import com.phlox.simpleserver.shttps_desktop.generated.resources.interface_is_down
import com.phlox.simpleserver.shttps_desktop.generated.resources.save
import com.phlox.simpleserver.shttps_desktop.generated.resources.select_network_interfaces
import com.phlox.simpleserver.shttps_desktop.generated.resources.set_allowed_network_interfaces
import org.jetbrains.compose.resources.stringResource
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

data class NetworkInterfaceItem(
    val networkInterface: NetworkInterface,
    val isSelected: Boolean = false
)

@Composable
fun NetworkInterfacesDialog(
    networkInterfaces: List<NetworkInterface>,
    currentAllowedInterfaces: Array<Int>?,
    onConfirm: (Array<Int>) -> Unit,
    onAllowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedInterfaces = remember { 
        mutableStateOf(
            currentAllowedInterfaces?.toSet() ?: emptySet<Int>()
        )
    }
    
    val interfaceItems = remember(networkInterfaces, selectedInterfaces.value) {
        networkInterfaces.map { networkInterface ->
            NetworkInterfaceItem(
                networkInterface = networkInterface,
                isSelected = selectedInterfaces.value.contains(networkInterface.index)
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.set_allowed_network_interfaces),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.select_network_interfaces),
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.height(250.dp)
                ) {
                    items(interfaceItems) { item ->
                        NetworkInterfaceItemRow(
                            item = item,
                            onSelectionChange = { isSelected ->
                                val newSelection = selectedInterfaces.value.toMutableSet()
                                if (isSelected) {
                                    newSelection.add(item.networkInterface.index)
                                } else {
                                    newSelection.remove(item.networkInterface.index)
                                }
                                selectedInterfaces.value = newSelection
                            }
                        )
                    }
                }
            }
        },
        buttons = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(all = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = {
                        // Pass the selection through as-is. Deciding what an empty selection
                        // means is up to the view model, which also knows about allowed
                        // interfaces that are absent right now and so have no row here.
                        onConfirm(selectedInterfaces.value.toTypedArray())
                    }
                ) {
                    Text(stringResource(Res.string.save))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onAllowAll
                ) {
                    Text(stringResource(Res.string.allow_all))
                }
                TextButton(
                    colors = textButtonColors(
                        contentColor = MaterialTheme.colors.onSurface
                    ),
                    onClick = onDismiss
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun NetworkInterfaceItemRow(
    item: NetworkInterfaceItem,
    onSelectionChange: (Boolean) -> Unit
) {
    val networkInterface = item.networkInterface
    val isUp = try { networkInterface.isUp } catch (e: Exception) { false }
    
    val addresses = if (isUp) {
        networkInterface.inetAddresses.asSequence()
            .filter { it is Inet4Address || it is Inet6Address }
            .map { it.hostAddress }
            .joinToString(", ")
    } else {
        stringResource(Res.string.interface_is_down)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = item.isSelected,
                onClick = { onSelectionChange(!item.isSelected) }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = onSelectionChange,
            colors = CheckboxDefaults.colors()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = networkInterface.displayName ?: networkInterface.name,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Text(
                text = addresses,
                fontSize = 12.sp,
                color = if (isUp) {
                    MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colors.error
                }
            )
        }
    }
}
