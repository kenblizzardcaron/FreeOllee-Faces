# Preview-Before-Send + Temperature Unit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the v0.1 "three send buttons" main screen with two always-on previews (temperature + next sun event) computed from auto-fetched coordinates, plus a persisted °F/°C segmented control at the top. Each preview shows both the literal 6-char watch payload and a human-readable line; each has its own Send button. The custom-text send path is unchanged.

**Architecture:** No new infrastructure — same `OlleeBleClient`, `LocationSource`, `SunCalc`, `Prefs`. The temperature path gets a `TempUnit` enum and a parameterized API on `OpenMeteoClient` + `DisplayFormatter`. A `PreviewState` sealed class models each card's lifecycle (`Loading` / `Ready` / `Error` / `NoEvent`). `MainScreen` is rewritten for the new layout. `MainActivity` runs a `LaunchedEffect` on launch that does the location-fetch + parallel preview compute, and exposes a single `refreshPreviews` helper that the Refresh button, °F/°C toggle, and debounced coord edits all call.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose Material 3, AGP 9.1.1, JUnit 4 — same as v0.1. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-14-preview-and-temp-unit-design.md`
**Predecessor plan:** `docs/superpowers/plans/2026-05-14-freeollee-faces-app.md`

---

## File Structure

After all tasks, these are the files touched:

```
app/src/main/java/com/blizzardcaron/freeolleefaces/
  format/
    TempUnit.kt                      # NEW: enum FAHRENHEIT / CELSIUS
    DisplayFormatter.kt              # MODIFY: temperature(value, unit) overload
  prefs/
    Prefs.kt                         # MODIFY: var tempUnit
  weather/
    OpenMeteoClient.kt               # MODIFY: buildUrl + currentTemp(unit)
  ui/
    PreviewState.kt                  # NEW: sealed Loading/Ready/Error/NoEvent
    MainScreen.kt                    # REWRITE: cards + segmented control
  MainActivity.kt                    # REWRITE: auto-fetch + refreshPreviews

app/src/test/java/com/blizzardcaron/freeolleefaces/
  format/
    DisplayFormatterTest.kt          # MODIFY: 3 new °C / default-overload cases
  weather/
    OpenMeteoClientUrlTest.kt        # NEW: buildUrl tests

docs/superpowers/plans/
  2026-05-14-freeollee-faces-app-verification.md  # MODIFY: 4 new manual checks
```

No new Gradle deps. The °C glyph is plain ASCII so no resource changes.

---

## Task 1: `TempUnit` enum + persisted `Prefs.tempUnit`

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/TempUnit.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

No unit tests — the enum is trivial and Prefs interacts with Android `Context`. Both are exercised by the existing tests after Task 2 (DisplayFormatter) and by manual verification in Task 6.

- [ ] **Step 1.1: Create `TempUnit.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.format

enum class TempUnit(val symbol: Char, val openMeteoParam: String) {
    FAHRENHEIT('F', "fahrenheit"),
    CELSIUS('C', "celsius"),
}
```

- [ ] **Step 1.2: Add `tempUnit` to `Prefs.kt`**

Modify `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` so it imports `TempUnit` and adds a property. After the edit the file reads:

```kotlin
package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.blizzardcaron.freeolleefaces.format.TempUnit

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastLat: Double?
        get() = if (sp.contains(KEY_LAT)) sp.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LAT) else putFloat(KEY_LAT, value.toFloat()) }

    var lastLng: Double?
        get() = if (sp.contains(KEY_LNG)) sp.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LNG) else putFloat(KEY_LNG, value.toFloat()) }

    var watchAddress: String?
        get() = sp.getString(KEY_WATCH, null)
        set(value) = sp.edit { if (value == null) remove(KEY_WATCH) else putString(KEY_WATCH, value) }

    var tempUnit: TempUnit
        get() = sp.getString(KEY_TEMP_UNIT, null)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
            ?: TempUnit.FAHRENHEIT
        set(value) = sp.edit { putString(KEY_TEMP_UNIT, value.name) }

    companion object {
        private const val FILE = "freeollee_faces_prefs"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LNG = "last_lng"
        private const val KEY_WATCH = "watch_address"
        private const val KEY_TEMP_UNIT = "temp_unit"
    }
}
```

- [ ] **Step 1.3: Verify the project still builds**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 1.4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/TempUnit.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Add TempUnit enum and Prefs.tempUnit (defaults to Fahrenheit)"
```

---

## Task 2: `DisplayFormatter.temperature(value, unit)` — TDD

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt`
- Modify: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt`

Adds a two-arg overload taking `TempUnit`, and keeps the single-arg overload as a default that calls `temperature(value, TempUnit.FAHRENHEIT)` — preserves v0.1 behavior for any caller that hasn't moved yet.

- [ ] **Step 2.1: Write the failing tests**

Append to `app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt` (inside the existing `DisplayFormatterTest` class, after the last temperature test):

```kotlin
    @Test
    fun `temperature with explicit Celsius uses C suffix`() {
        assertEquals("  22 C", DisplayFormatter.temperature(22.0, TempUnit.CELSIUS))
        assertEquals(" -12 C", DisplayFormatter.temperature(-12.0, TempUnit.CELSIUS))
    }

    @Test
    fun `temperature with explicit Fahrenheit matches default overload`() {
        assertEquals(
            DisplayFormatter.temperature(72.0),
            DisplayFormatter.temperature(72.0, TempUnit.FAHRENHEIT)
        )
    }

    @Test
    fun `temperature default overload still produces F suffix`() {
        // Regression guard for v0.1 callers that pass just a Double.
        assertEquals("  72 F", DisplayFormatter.temperature(72.0))
    }
```

You also need an import at the top of the file (next to the existing `LocalTime` import):

```kotlin
import com.blizzardcaron.freeolleefaces.format.TempUnit
```

Note: the test file's package is `com.blizzardcaron.freeolleefaces.format` already, so the import is technically redundant inside the same package but harmless. If your tooling flags it, drop it; the tests still resolve.

- [ ] **Step 2.2: Run the tests, confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.format.DisplayFormatterTest"
```

Expected: 3 failures — `temperature(Double, TempUnit)` doesn't exist yet.

- [ ] **Step 2.3: Add the new overload in `DisplayFormatter.kt`**

Modify `app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt` so the `temperature` method becomes:

```kotlin
    fun temperature(value: Double, unit: TempUnit): String {
        val rounded = value.roundToInt()
        return "%4d ${unit.symbol}".format(rounded)
    }

    fun temperature(value: Double): String = temperature(value, TempUnit.FAHRENHEIT)
```

(Replace the existing single-arg `temperature` implementation; the rest of the file is unchanged.)

- [ ] **Step 2.4: Run the tests, confirm they all pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.format.DisplayFormatterTest"
```

Expected: 14/14 passing (the original 11 + the 3 new).

- [ ] **Step 2.5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt
git commit -m "DisplayFormatter: add temperature(value, unit) with TempUnit"
```

---

## Task 3: `OpenMeteoClient.buildUrl` + `currentTemp(unit)` — TDD

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt`
- Create: `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientUrlTest.kt`

Refactor: extract the URL building into a pure `buildUrl(lat, lng, unit): URL` so the unit param is unit-testable. Rename `currentTempF` → `currentTemp(lat, lng, unit)`. The JSON parser is unchanged.

The one v0.1 caller (`MainActivity.onSendTemperature`) is reached by Task 5; for this task we keep the build green by adding a thin `currentTempF` delegate that calls `currentTemp(lat, lng, TempUnit.FAHRENHEIT)`. The delegate is removed in Task 5 when the call site is rewritten.

- [ ] **Step 3.1: Write the failing URL tests**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientUrlTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoClientUrlTest {

    @Test
    fun `buildUrl puts lat and lng into the query string`() {
        val url = OpenMeteoClient.buildUrl(44.31, -72.04, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should contain latitude param: $url", url.contains("latitude=44.31"))
        assertTrue("url should contain longitude param: $url", url.contains("longitude=-72.04"))
    }

    @Test
    fun `buildUrl emits temperature_unit=fahrenheit for FAHRENHEIT`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should request fahrenheit: $url", url.contains("temperature_unit=fahrenheit"))
    }

    @Test
    fun `buildUrl emits temperature_unit=celsius for CELSIUS`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.CELSIUS).toString()
        assertTrue("url should request celsius: $url", url.contains("temperature_unit=celsius"))
    }

    @Test
    fun `buildUrl always requests current temperature_2m`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT).toString()
        assertTrue("url should request current temperature_2m: $url", url.contains("current=temperature_2m"))
    }

    @Test
    fun `buildUrl uses HTTPS and the Open-Meteo host`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT)
        assertEquals("https", url.protocol)
        assertEquals("api.open-meteo.com", url.host)
    }
}
```

- [ ] **Step 3.2: Run the tests, confirm they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.weather.OpenMeteoClientUrlTest"
```

Expected: unresolved reference `OpenMeteoClient.buildUrl`.

- [ ] **Step 3.3: Refactor `OpenMeteoClient.kt`**

Replace the contents of `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt` with:

```kotlin
package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    /** Pure URL builder so the unit param is unit-testable without HTTP. */
    fun buildUrl(lat: Double, lng: Double, unit: TempUnit): URL =
        URL(
            "$BASE?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m&temperature_unit=${unit.openMeteoParam}"
        )

    /** Fetches `current.temperature_2m` in the requested [unit]. */
    suspend fun currentTemp(lat: Double, lng: Double, unit: TempUnit): Result<Double> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = buildUrl(lat, lng, unit).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        error("HTTP $code from Open-Meteo: $errBody")
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseCurrentTemperatureF(body)
                } finally {
                    conn.disconnect()
                }
            }
        }

    /**
     * v0.1 delegate kept temporarily so callers that hard-code Fahrenheit compile.
     * Task 5 removes this when MainActivity is rewritten to call [currentTemp] directly.
     */
    suspend fun currentTempF(lat: Double, lng: Double): Result<Double> =
        currentTemp(lat, lng, TempUnit.FAHRENHEIT)

    /** Extracts `current.temperature_2m` from the response JSON. Unit is whatever the URL requested. */
    fun parseCurrentTemperatureF(json: String): Double {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalStateException("response is not valid JSON: ${e.message}", e)
        }
        val current = root.optJSONObject("current")
            ?: throw IllegalStateException("response missing 'current' block")
        if (!current.has("temperature_2m")) {
            throw IllegalStateException("response 'current' missing 'temperature_2m'")
        }
        return current.getDouble("temperature_2m")
    }
}
```

Note: `parseCurrentTemperatureF` keeps its name even though the value isn't necessarily in Fahrenheit anymore — the parser doesn't know or care; the unit was decided at URL-build time. Renaming the parser is gratuitous churn for the 4 existing tests; leaving it. (A short-form rename to `parseCurrentTemperature` is a fine v0.3 follow-up.)

- [ ] **Step 3.4: Run all tests, confirm everything passes**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: 19/19 passing (original 26 minus the merge means we should now see 6 OlleeProtocol + 14 DisplayFormatter + 5 SunCalc + 4 OpenMeteoClientParser + 5 OpenMeteoClientUrl = 34 total). Confirm 0 failures.

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClientUrlTest.kt
git commit -m "OpenMeteoClient: extract buildUrl, parameterize unit"
```

---

## Task 4: `PreviewState` sealed class

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/PreviewState.kt`

No tests — it's a pure data shape. Used by Tasks 5 and 6.

- [ ] **Step 4.1: Create `PreviewState.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ui

sealed class PreviewState {
    /** No fetch yet, or a fetch is in flight. */
    object Loading : PreviewState()

    /** The fetch resolved cleanly. [payload] is the literal 6-char string that would go to the watch. */
    data class Ready(val payload: String, val human: String) : PreviewState()

    /** The fetch failed. [message] is shown in the card. */
    data class Error(val message: String) : PreviewState()

    /** Sun-time only: no rise/set event in the next 24h (polar). */
    object NoEvent : PreviewState()
}
```

- [ ] **Step 4.2: Verify the project still builds**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4.3: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/PreviewState.kt
git commit -m "Add PreviewState sealed class for per-card UI state"
```

---

## Task 5: Rewrite `MainScreen` + `MainActivity` for previews and unit toggle

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

This is the biggest task — one commit that changes the screen and the wiring together. Both files end up in their v0.2 final state.

- [ ] **Step 5.1: Replace `MainScreen.kt` with the new layout**

Overwrite `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt` with:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class MainScreenState(
    val lat: String = "",
    val lng: String = "",
    val custom: String = "",
    val watchLabel: String = "Watch: none selected",
    val status: String = "Ready.",
    val sending: Boolean = false,
    val watchSelected: Boolean = false,
    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.Loading,
    val sunPreview: PreviewState = PreviewState.Loading,
)

data class MainScreenCallbacks(
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSelectWatch: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onRefresh: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onSendTemperature: () -> Unit,
    val onSendSunTime: () -> Unit,
    val onSendCustom: () -> Unit,
    val onRetryTemperature: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainScreenState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("FreeOllee Faces", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = callbacks.onRefresh) { Text("Refresh") }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.tempUnit == TempUnit.FAHRENHEIT,
                onClick = { callbacks.onTempUnitChange(TempUnit.FAHRENHEIT) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("°F") }
            SegmentedButton(
                selected = state.tempUnit == TempUnit.CELSIUS,
                onClick = { callbacks.onTempUnitChange(TempUnit.CELSIUS) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("°C") }
        }

        Text(state.watchLabel, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = callbacks.onSelectWatch, modifier = Modifier.fillMaxWidth()) {
            Text("Select watch")
        }

        OutlinedTextField(
            value = state.lat,
            onValueChange = callbacks.onLatChange,
            label = { Text("Latitude") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.lng,
            onValueChange = callbacks.onLngChange,
            label = { Text("Longitude") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = callbacks.onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
            Text("Use my location")
        }

        HorizontalDivider()

        PreviewCard(
            title = "Temperature",
            state = state.tempPreview,
            onRetry = callbacks.onRetryTemperature,
            onSend = callbacks.onSendTemperature,
            sendEnabled = state.tempPreview is PreviewState.Ready && state.watchSelected && !state.sending,
        )

        PreviewCard(
            title = "Next sun event",
            state = state.sunPreview,
            onRetry = null, // sun is local; no retry path
            onSend = callbacks.onSendSunTime,
            sendEnabled = state.sunPreview is PreviewState.Ready && state.watchSelected && !state.sending,
        )

        HorizontalDivider()

        OutlinedTextField(
            value = state.custom,
            onValueChange = callbacks.onCustomChange,
            label = { Text("Custom (up to 6 chars)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = callbacks.onSendCustom,
            enabled = state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send custom") }

        HorizontalDivider()

        Text(state.status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreviewCard(
    title: String,
    state: PreviewState,
    onRetry: (() -> Unit)?,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (state) {
                is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                is PreviewState.Ready -> {
                    Text(
                        "Watch: '${state.payload}'",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(state.human, style = MaterialTheme.typography.bodyMedium)
                }
                is PreviewState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                PreviewState.NoEvent -> Text(
                    "No sunrise/sunset in next 24 h.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send to watch") }
        }
    }
}
```

- [ ] **Step 5.2: Replace `MainActivity.kt` with the v0.2 wiring**

Overwrite `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt` with:

```kotlin
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
```

Notes:
- The `currentTempF` delegate in `OpenMeteoClient` is now unused. **Delete it** as part of this commit (so the file ends up with only `buildUrl`, `currentTemp`, `parseCurrentTemperatureF`). Edit:

  Remove these lines from `app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt`:

  ```kotlin
      /**
       * v0.1 delegate kept temporarily so callers that hard-code Fahrenheit compile.
       * Task 5 removes this when MainActivity is rewritten to call [currentTemp] directly.
       */
      suspend fun currentTempF(lat: Double, lng: Double): Result<Double> =
          currentTemp(lat, lng, TempUnit.FAHRENHEIT)
  ```

- The `validateCoords` helper from v0.1 isn't needed anymore — the validity check is inline in `refreshFromState`. If your editor's "unused symbol" hint flags it, leave it for now (v0.3 cleanup), or remove with the same commit.

- [ ] **Step 5.3: Build & verify the project assembles**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5.4: Run the unit tests as a regression check**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: 34/34 passing (no test changes in this task).

- [ ] **Step 5.5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/weather/OpenMeteoClient.kt
git commit -m "Auto-fetch previews, F/C segmented control, per-card Send"
```

---

## Task 6: Update on-device verification report

**Files:**
- Modify: `docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md`

Add the v0.2 manual checks alongside the v0.1 ones. Done before merge; recorded as PASS / FAIL with notes the same way as the v0.1 entries.

- [ ] **Step 6.1: Append the v0.2 section**

Add this new section near the bottom of `docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md`, immediately before the `## Defects & follow-ups` heading:

````markdown
## v0.2 — Preview & temperature unit

1. **Auto-preview on launch (permission held):** Open app cold. Both preview cards transition from Loading to Ready within ~3 s. Status line reads `Got fix: …`.
2. **Auto-preview on launch (permission denied, saved coords present):** Revoke location, reopen app. Status line reads `Using saved coordinates.` Both previews populate without any prompt.
3. **Auto-preview on launch (no saved coords, no permission):** Clear app data, reopen. Both cards show `Enter coordinates manually to see previews`; manual fields work as the recovery path.
4. **F / C toggle:** Tap °C. Temperature preview re-fetches, payload format becomes `"  22 C"` (or similar), persists across app restart. Sun preview is unaffected.
5. **Refresh button:** Tap Refresh. Both cards transition Loading → Ready again (within ~3 s); previews update with current values.
6. **Coord edit debounce:** Type a new lat/lng one character at a time. Refresh fires only once, ~500 ms after the last keystroke (not on every key).
7. **°C on the watch LCD:** Switch to °C, tap Send on the temperature card. Photograph the watch; the `C` character must be readable and distinguishable from `F` on the temperature face. If not, tweak `DisplayFormatter.temperature` or `TempUnit.symbol` and commit a regression test alongside.
8. **Per-card send disable when no watch:** Forget the watch. Both Send buttons go disabled; Custom Send also disables.
9. **Per-card send disable while sending:** Tap Send on a Ready card. While the BLE write is in flight, both Send buttons (temp + sun) and Custom Send disable until the status flips to `Sent …` or `Send failed: …`.
10. **Sun NoEvent rendering:** Enter `lat = 78.0, lng = 15.0` (high arctic) on a date near summer solstice. Sun preview must show `No sunrise/sunset in next 24 h.` and its Send button must disable.

| Sub-check | Result | Notes |
|---|---|---|
| 1. Auto-preview on launch (permission held) | _TBD_ | |
| 2. Auto-preview on launch (permission denied + saved coords) | _TBD_ | |
| 3. Auto-preview on launch (no saved coords, no permission) | _TBD_ | |
| 4. F / C toggle | _TBD_ | |
| 5. Refresh button | _TBD_ | |
| 6. Coord edit debounce | _TBD_ | |
| 7. °C on the watch LCD | _TBD_ | attach photo |
| 8. Per-card send disable (no watch) | _TBD_ | |
| 9. Per-card send disable (while sending) | _TBD_ | |
| 10. Sun NoEvent rendering | _TBD_ | |

````

- [ ] **Step 6.2: Commit**

```bash
git add docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md
git commit -m "Verification report: add v0.2 preview + unit toggle checks"
```

---

## Done criteria

- `./gradlew :app:testDebugUnitTest` reports 34/34 passing (v0.1's 26 + the 5 URL tests + the 3 new formatter tests).
- `./gradlew :app:assembleDebug` succeeds.
- The app launches, populates both previews from saved or fresh coords, lets you toggle °F/°C with persistence, and pushes either preview to a paired Ollee.
- All 10 v0.2 verification rows recorded with PASS (or with explicit follow-ups committed).
