package com.blizzardcaron.freeolleefaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme

/**
 * Debug-only host that renders the Home screen with every face populated, so a clean README
 * screenshot can be captured on a real device via `adb screencap`. This exists because the
 * Espresso/Compose screenshot instrumentation (ScreenScreenshotTest) cannot run on API 37
 * (Android 17 removed `InputManager.getInstance`, which Espresso reflects on). Never shipped in
 * release. The state mirrors ScreenFakes.homeState (the fixture CI renders).
 */
class ScreenshotHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the status/nav bars so the capture is pure app content (matches the isolated
        // Compose render the CI screenshot job produces).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            FreeOlleeFacesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(state = SHOWCASE_STATE, callbacks = NOOP_CALLBACKS)
                }
            }
        }
    }

    private companion object {
        val SHOWCASE_STATE = HomeState(
            watchLabel = "Watch: Ollee",
            watchSelected = true,
            connectionStatus = ConnectionStatus.Connected,
            versionLabel = "v0.31.0",
            locationLabel = "Location: 40.015, -105.270",
            locationFreshness = "just now",
            lat = "40.015",
            lng = "-105.270",
            tempPreview = PreviewState.Ready("  72#F", "Currently: 72.0°F"),
            tempUpdated = "Updated 2:45 PM",
            tempNext = "Next update 3:00 PM",
            batteryReadout = BatteryReadout.PERCENT,
            batteryPreview = PreviewState.Ready("   85P", "Battery: 85%"),
            batteryUpdated = "Updated 2:45 PM",
            batteryNext = "Next update 3:00 PM",
            pressurePreview = PreviewState.Ready("  1013", "Currently: 1013.0 hPa"),
            pressureUpdated = "Updated 2:45 PM",
            pressureNext = "Next update 3:00 PM",
            altitudePreview = PreviewState.Ready(" 1609m", "Elevation: 1609 m"),
            altitudeUpdated = "Updated 2:45 PM",
            altitudeNext = "Next update 3:00 PM",
            stepsPreview = PreviewState.Ready("  8432", "Today: 8,432 steps"),
            stepsUpdated = "Updated 2:45 PM",
            stepsHealthGranted = true,
            notificationCount = 3,
            notificationAccessGranted = true,
            notificationsEnabled = true,
        )

        val NOOP_CALLBACKS = HomeCallbacks(
            onActivate = {},
            onUpdateNow = {},
            onTempUnitChange = {},
            onSetBatteryReadout = {},
            onCustomChange = {},
            onSendCustom = {},
            onGrantHealth = {},
            onToggleRingConnSteps = {},
            onGrantNotificationAccess = {},
            onToggleNotifications = {},
            onNotificationsUpdateNow = {},
        )
    }
}
