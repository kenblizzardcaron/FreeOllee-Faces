package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.MainScreen
import com.blizzardcaron.freeolleefaces.ui.MainScreenCallbacks
import com.blizzardcaron.freeolleefaces.ui.MainScreenState
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                Scaffold { inner ->
                    AppRoot(Modifier.padding(inner))
                }
            }
        }
    }
}

@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var state by remember {
        mutableStateOf(
            MainScreenState(
                lat = prefs.lastLat?.toString() ?: "",
                lng = prefs.lastLng?.toString() ?: "",
                watchLabel = labelForAddress(context, prefs.watchAddress),
                watchSelected = prefs.watchAddress != null,
                latLngValid = validateCoords(prefs.lastLat?.toString() ?: "", prefs.lastLng?.toString() ?: ""),
            )
        )
    }

    var showPicker by remember { mutableStateOf(false) }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPicker = true
        } else {
            state = state.copy(status = "Bluetooth permission denied — can't list paired watches.")
        }
    }

    fun updateLatLng(lat: String, lng: String) {
        state = state.copy(lat = lat, lng = lng, latLngValid = validateCoords(lat, lng))
    }

    val callbacks = MainScreenCallbacks(
        onLatChange = { updateLatLng(it, state.lng) },
        onLngChange = { updateLatLng(state.lat, it) },
        onCustomChange = { state = state.copy(custom = it) },
        onSelectWatch = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) {
                showPicker = true
            } else {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        },
        onUseMyLocation = { state = state.copy(status = "Location wiring lands in Task 12.") },
        onSendTemperature = { state = state.copy(status = "Send wiring lands in Task 12.") },
        onSendSunTime = { state = state.copy(status = "Send wiring lands in Task 12.") },
        onSendCustom = { state = state.copy(status = "Send wiring lands in Task 12.") },
    )

    MainScreen(state = state, callbacks = callbacks, modifier = modifier)

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                prefs.watchAddress = device.address
                state = state.copy(
                    watchLabel = "Watch: ${device.name ?: device.address}",
                    watchSelected = true,
                    status = "Selected ${device.name ?: device.address}.",
                )
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: android.content.Context): List<BluetoothDevice> {
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = mgr.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return adapter.bondedDevices?.toList().orEmpty()
}

@SuppressLint("MissingPermission")
private fun labelForAddress(context: android.content.Context, address: String?): String {
    if (address == null) return "Watch: none selected"
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return "Watch: $address"
    val device = mgr.adapter?.getRemoteDevice(address)
    return "Watch: ${device?.name ?: address}"
}

internal fun validateCoords(latStr: String, lngStr: String): Boolean {
    val lat = latStr.toDoubleOrNull() ?: return false
    val lng = lngStr.toDoubleOrNull() ?: return false
    return lat in -90.0..90.0 && lng in -180.0..180.0
}
