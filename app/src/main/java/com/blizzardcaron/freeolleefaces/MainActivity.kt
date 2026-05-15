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
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.location.LocationSource
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.NextEvent
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.MainScreen
import com.blizzardcaron.freeolleefaces.ui.MainScreenCallbacks
import com.blizzardcaron.freeolleefaces.ui.MainScreenState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                tempUnit = prefs.tempUnit,
            )
        )
    }

    var showPicker by remember { mutableStateOf(false) }
    // Tracks the currently-in-flight refresh job so unit toggles / Refresh taps cancel stale runs.
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    fun update(transform: (MainScreenState) -> MainScreenState) {
        state = transform(state)
    }

    fun refreshPreviews(lat: Double, lng: Double, unit: TempUnit) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            update { it.copy(tempPreview = PreviewState.Loading, sunPreview = PreviewState.Loading) }

            val tempCoroutine = launch {
                OpenMeteoClient.currentTemp(lat, lng, unit)
                    .onSuccess { temp ->
                        val payload = DisplayFormatter.temperature(temp, unit)
                        val human = "Currently: %.1f°%s".format(Locale.US, temp, unit.symbol)
                        update { it.copy(tempPreview = PreviewState.Ready(payload, human)) }
                    }
                    .onFailure { err ->
                        update { it.copy(tempPreview = PreviewState.Error("Weather fetch failed: ${err.message}")) }
                    }
            }

            val sunCoroutine = launch {
                val event: NextEvent? = SunCalc.nextEvent(Instant.now(), lat, lng, ZoneId.systemDefault())
                val newSun = if (event == null) {
                    PreviewState.NoEvent
                } else {
                    val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
                    val pretty = event.time.format(DateTimeFormatter.ofPattern("h:mm a"))
                    val kindLabel = event.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                    PreviewState.Ready(payload, "Next: $kindLabel at $pretty local")
                }
                update { it.copy(sunPreview = newSun) }
            }

            tempCoroutine.join()
            sunCoroutine.join()
        }
    }

    fun refreshFromState() {
        val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            update { it.copy(
                tempPreview = PreviewState.Error("Enter coordinates manually to see previews"),
                sunPreview = PreviewState.Error("Enter coordinates manually to see previews"),
            ) }
            return
        }
        refreshPreviews(lat, lng, state.tempUnit)
    }

    // Auto-fetch on launch: try LocationSource if permission held; either way kick off refreshPreviews.
    LaunchedEffect(Unit) {
        val hasAnyLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasAnyLocation) {
            update { it.copy(status = "Getting location fix…") }
            locationSource.fetch()
                .onSuccess { coords ->
                    prefs.lastLat = coords.lat
                    prefs.lastLng = coords.lng
                    update { it.copy(
                        lat = coords.lat.toString(),
                        lng = coords.lng.toString(),
                        status = "Got fix: %.6f, %.6f (%s, %s)".format(
                            coords.lat, coords.lng,
                            coords.provider ?: "?",
                            coords.accuracyM?.let { "±${it.toInt()} m" } ?: "no acc.",
                        ),
                    ) }
                }
                .onFailure { err ->
                    update { it.copy(status = "Location failed: ${err.message}. Using saved coordinates.") }
                }
        } else {
            update { it.copy(status = "Using saved coordinates.") }
        }
        refreshFromState()
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPicker = true
        } else {
            update { it.copy(status = "Bluetooth permission denied — can't list paired watches.") }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val anyGranted = results.values.any { it }
        if (anyGranted) {
            scope.launch { fetchLocationAndRefresh(locationSource, prefs, ::refreshPreviews, state.tempUnit, ::update) }
        } else {
            update { it.copy(status = "Location permission denied — enter coordinates manually.") }
        }
    }

    fun onCoordEdit(lat: String, lng: String) {
        update { it.copy(lat = lat, lng = lng) }
        // Persist immediately if valid; debounce the refresh.
        val latD = lat.toDoubleOrNull(); val lngD = lng.toDoubleOrNull()
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            refreshFromState()
        }
    }

    val callbacks = MainScreenCallbacks(
        onLatChange = { onCoordEdit(it, state.lng) },
        onLngChange = { onCoordEdit(state.lat, it) },
        onCustomChange = { update { s -> s.copy(custom = it) } },

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
                scope.launch { fetchLocationAndRefresh(locationSource, prefs, ::refreshPreviews, state.tempUnit, ::update) }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        },

        onRefresh = { refreshFromState() },

        onTempUnitChange = { newUnit ->
            prefs.tempUnit = newUnit
            update { it.copy(tempUnit = newUnit) }
            val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
            if (lat != null && lng != null) refreshPreviews(lat, lng, newUnit)
        },

        onSendTemperature = {
            val preview = state.tempPreview
            val addr = prefs.watchAddress
            if (preview !is PreviewState.Ready || addr == null) return@MainScreenCallbacks
            scope.launch { sendAndReport(ble, addr, preview.payload, ::update) }
        },

        onSendSunTime = {
            val preview = state.sunPreview
            val addr = prefs.watchAddress
            if (preview !is PreviewState.Ready || addr == null) return@MainScreenCallbacks
            scope.launch { sendAndReport(ble, addr, preview.payload, ::update) }
        },

        onSendCustom = {
            val addr = prefs.watchAddress ?: return@MainScreenCallbacks
            scope.launch {
                val value = DisplayFormatter.custom(state.custom)
                sendAndReport(ble, addr, value, ::update)
            }
        },

        onRetryTemperature = {
            val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
            if (lat != null && lng != null) refreshPreviews(lat, lng, state.tempUnit)
        },
    )

    MainScreen(state = state, callbacks = callbacks, modifier = modifier)

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                prefs.watchAddress = device.address
                update { it.copy(
                    watchLabel = "Watch: ${device.name ?: device.address}",
                    watchSelected = true,
                    status = "Selected ${device.name ?: device.address}.",
                ) }
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

private suspend fun fetchLocationAndRefresh(
    locationSource: LocationSource,
    prefs: Prefs,
    refreshPreviews: (Double, Double, TempUnit) -> Unit,
    unit: TempUnit,
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
                    status = "Got fix: %.6f, %.6f (%s, %s)".format(
                        coords.lat, coords.lng,
                        coords.provider ?: "?",
                        coords.accuracyM?.let { "±${it.toInt()} m" } ?: "no acc.",
                    ),
                )
            }
            refreshPreviews(coords.lat, coords.lng, unit)
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
