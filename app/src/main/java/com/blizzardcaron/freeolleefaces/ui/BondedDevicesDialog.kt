package com.blizzardcaron.freeolleefaces.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_CONNECT
@Composable
fun BondedDevicesDialog(
    devices: List<BluetoothDevice>,
    onPick: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Select watch") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices. Pair the watch in Android Bluetooth settings first.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { d ->
                        Text(
                            "${d.name ?: "Unknown"} — ${d.address}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(d) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
