package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
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
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.location.LocationSource
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.MainScreen
import com.blizzardcaron.freeolleefaces.ui.MainScreenCallbacks
import com.blizzardcaron.freeolleefaces.ui.MainScreenState
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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
    val ble = remember { OlleeBleClient(context) }
    val locationSource = remember { LocationSource(context) }
    val scope = rememberCoroutineScope()

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

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val any = results.values.any { it }
        if (any) {
            scope.launch { fetchLocation(locationSource, prefs) { state = it(state) } }
        } else {
            state = state.copy(status = "Location permission denied — enter coordinates manually.")
        }
    }

    fun updateLatLng(lat: String, lng: String) {
        state = state.copy(lat = lat, lng = lng, latLngValid = validateCoords(lat, lng))
    }

    fun persistCoordsIfValid() {
        val lat = state.lat.toDoubleOrNull()
        val lng = state.lng.toDoubleOrNull()
        if (lat != null && lng != null) {
            prefs.lastLat = lat
            prefs.lastLng = lng
        }
    }

    val callbacks = MainScreenCallbacks(
        onLatChange = { updateLatLng(it, state.lng); persistCoordsIfValid() },
        onLngChange = { updateLatLng(state.lat, it); persistCoordsIfValid() },
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

        onUseMyLocation = {
            val hasAny = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (hasAny) {
                scope.launch { fetchLocation(locationSource, prefs) { state = it(state) } }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        },

        onSendTemperature = {
            val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
            val addr = prefs.watchAddress
            if (lat == null || lng == null || addr == null) return@MainScreenCallbacks
            scope.launch {
                state = state.copy(sending = true, status = "Fetching temperature…")
                OpenMeteoClient.currentTempF(lat, lng)
                    .onSuccess { temp ->
                        val value = DisplayFormatter.temperature(temp)
                        sendAndReport(ble, addr, value) { state = it(state) }
                    }
                    .onFailure { err ->
                        state = state.copy(sending = false, status = "Weather fetch failed: ${err.message}")
                    }
            }
        },

        onSendSunTime = {
            val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
            val addr = prefs.watchAddress
            if (lat == null || lng == null || addr == null) return@MainScreenCallbacks
            scope.launch {
                state = state.copy(sending = true, status = "Computing next sun event…")
                val event = SunCalc.nextEvent(Instant.now(), lat, lng, ZoneId.systemDefault())
                if (event == null) {
                    state = state.copy(sending = false, status = "No sunrise/sunset in next 24h at this location.")
                } else {
                    val value = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
                    sendAndReport(ble, addr, value) { state = it(state) }
                }
            }
        },

        onSendCustom = {
            val addr = prefs.watchAddress ?: return@MainScreenCallbacks
            scope.launch {
                val value = DisplayFormatter.custom(state.custom)
                state = state.copy(sending = true, status = "Sending '$value'…")
                sendAndReport(ble, addr, value) { state = it(state) }
            }
        },
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

private suspend fun sendAndReport(
    ble: OlleeBleClient,
    address: String,
    value: String,
    update: ((MainScreenState) -> MainScreenState) -> Unit,
) {
    update { it.copy(status = "Sending '$value'…", sending = true) }
    ble.send(address, value)
        .onSuccess { update { it.copy(sending = false, status = "Sent '$value'.") } }
        .onFailure { err -> update { it.copy(sending = false, status = "Send failed: ${err.message}") } }
}

private suspend fun fetchLocation(
    locationSource: LocationSource,
    prefs: Prefs,
    update: ((MainScreenState) -> MainScreenState) -> Unit,
) {
    update { it.copy(status = "Getting location fix…") }
    locationSource.fetch()
        .onSuccess { coords ->
            prefs.lastLat = coords.lat
            prefs.lastLng = coords.lng
            update {
                it.copy(
                    lat = coords.lat.toString(),
                    lng = coords.lng.toString(),
                    latLngValid = true,
                    status = "Got fix: %.6f, %.6f (%s, %s)".format(
                        coords.lat, coords.lng,
                        coords.provider ?: "?",
                        coords.accuracyM?.let { "±${it.toInt()} m" } ?: "no acc.",
                    ),
                )
            }
        }
        .onFailure { err ->
            update { it.copy(status = "Location failed: ${err.message}") }
        }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BluetoothDevice> {
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = mgr.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return adapter.bondedDevices?.toList().orEmpty()
}

@SuppressLint("MissingPermission")
private fun labelForAddress(context: Context, address: String?): String {
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
