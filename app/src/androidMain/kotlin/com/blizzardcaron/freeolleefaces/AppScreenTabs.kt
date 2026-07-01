package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.activity.AndroidActivityTrackStore
import com.blizzardcaron.freeolleefaces.ui.ActivityCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityDetailScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityMetricsConfigCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityMetricsConfigScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityScreen
import com.blizzardcaron.freeolleefaces.ui.Screen

@Composable
fun ActivityTab(viewModel: AppViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val activityState by viewModel.activity.state.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.activity.onStart() }
    val livePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.activity.onShowLive() }
    fun hasLocation() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val startWithPermission: () -> Unit = {
        if (hasLocation()) {
            viewModel.activity.onStart()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val showLiveWithPermission: () -> Unit = {
        if (hasLocation()) {
            viewModel.activity.onShowLive()
        } else {
            livePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    // Auto-start the live instrument glance on tab entry — but only when location is already
    // granted, so merely opening the Activity tab never fires a system permission dialog. If a
    // session is already running (e.g. returning mid-recording), leave it untouched.
    LaunchedEffect(Unit) {
        if (!activityState.running && hasLocation()) viewModel.activity.onShowLive()
    }
    ActivityScreen(
        state = activityState,
        unit = viewModel.activity.activityUnit,
        watchSelected = viewModel.activity.watchSelected,
        lastSummary = AndroidActivityTrackStore(context).latest()?.summary,
        config = viewModel.activity.metricsConfig(),
        pushIntervalMs = viewModel.activity.pushIntervalMs,
        intervalPresetsMs = viewModel.activity.pushIntervalPresetsMs,
        callbacks = ActivityCallbacks(
            onStart = startWithPermission,
            onShowLive = showLiveWithPermission,
            onStop = { viewModel.activity.onStop() },
            onMode = { viewModel.activity.onMode() },
            onToggleUnit = { viewModel.activity.toggleUnit() },
            onOpenHistory = { viewModel.navigateTo(Screen.ActivityHistory) },
            onConfigureMetrics = { viewModel.navigateTo(Screen.ActivityMetricsConfig) },
            onSelectInterval = { viewModel.activity.setPushInterval(it) },
        ),
        modifier = modifier,
    )
}

@Composable
fun ActivityHistoryTab(viewModel: AppViewModel, modifier: Modifier) {
    viewModel.historyRevision // subscribe: recompose after a delete
    ActivityHistoryScreen(
        tracks = viewModel.activityHistory(),
        unit = viewModel.activity.activityUnit,
        callbacks = ActivityHistoryCallbacks(
            onOpen = { viewModel.openActivity(it) },
            onDelete = { viewModel.deleteActivity(it) },
            onBack = { viewModel.navigateTo(Screen.Activity) },
        ),
        modifier = modifier,
    )
}

@Composable
fun ActivityDetailTab(viewModel: AppViewModel, modifier: Modifier) {
    val track = viewModel.activityHistory().firstOrNull { it.id == viewModel.selectedActivityId }
    if (track == null) {
        viewModel.navigateTo(Screen.ActivityHistory)
    } else {
        ActivityDetailScreen(
            track = track,
            unit = viewModel.activity.activityUnit,
            onBack = { viewModel.navigateTo(Screen.ActivityHistory) },
            modifier = modifier,
        )
    }
}

@Composable
fun ActivityMetricsConfigTab(viewModel: AppViewModel, modifier: Modifier) {
    var revision by remember { mutableStateOf(0) }
    revision // read so edits recompose
    ActivityMetricsConfigScreen(
        config = viewModel.activity.metricsConfig(),
        unit = viewModel.activity.activityUnit,
        callbacks = ActivityMetricsConfigCallbacks(
            onMoveUp = { mode, i ->
                viewModel.activity.moveMetricUp(mode, i)
                revision++
            },
            onMoveDown = { mode, i ->
                viewModel.activity.moveMetricDown(mode, i)
                revision++
            },
            onToggle = { mode, m, on ->
                viewModel.activity.setMetricEnabled(mode, m, on)
                revision++
            },
            onBack = { viewModel.navigateTo(Screen.Activity) },
        ),
        modifier = modifier,
    )
}
