package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.WorldTimeSlotUi
import com.blizzardcaron.freeolleefaces.ui.WorldTimeUiState
import com.blizzardcaron.freeolleefaces.worldtime.SetClock
import com.blizzardcaron.freeolleefaces.worldtime.WorldTime
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeHeader
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeReadback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Drives the World Time card: slot management, one-tap zone switching, and the app-open
 * reconcile that adopts on-watch changes (spec Approach A — the app owns the value between
 * opens, the user's on-watch change wins at reconcile). Swap arrives with the Wave B tasks.
 */
class WorldTimeController(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    // state is unused by the current methods (update's `it` already supplies read access) but kept
    // in the signature so construction stays uniform with TimerController/ComplicationController —
    // same rationale as ActivityPushDecider.shouldPush's unused newText.
    @Suppress("UnusedPrivateProperty") private val state: () -> HomeState,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val clock: Clock = Clock.System,
    private val homeZoneId: () -> String = { TimeZone.currentSystemDefault().id },
) {
    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    /** True while a [pushHeader] BLE write is in flight — set/cleared only there. */
    private var pushInFlight = false

    fun addSlot(zoneId: String) {
        val slots = prefs.worldTimeSlots
        if (slots.size >= WorldTime.MAX_SLOTS || zoneId in slots) return
        if (WorldTime.offsetSecondsOf(zoneId, nowMs()) == null) return
        prefs.worldTimeSlots = slots + zoneId
        refreshPreviews()
    }

    fun removeSlot(zoneId: String) {
        val wasActive = prefs.worldTimeActiveZone == zoneId
        prefs.worldTimeSlots = prefs.worldTimeSlots - zoneId
        if (wasActive) prefs.worldTimeActiveZone = null
        // Removing the swapped-in zone would strand the watch's main clock on a zone we no longer
        // track, with no chip left to un-swap from. Un-swap: clear the flag and restore home time
        // to the clock (pushSwappedPair sees swapped=false and writes home to the clock register).
        if (wasActive && prefs.worldTimeSwapped) {
            prefs.worldTimeSwapped = false
            refreshPreviews()
            pushSwappedPair(zoneId)
            return
        }
        refreshPreviews()
    }

    /**
     * Chip tap: persist the intent first, then push the new offset to the watch. While swapped,
     * the tapped zone drives the main clock (the Casio pair) instead of the world register alone.
     */
    fun activate(zoneId: String) {
        if (zoneId !in prefs.worldTimeSlots) return
        prefs.worldTimeActiveZone = zoneId
        prefs.worldTimeCustomOffsetSec = null
        refreshPreviews()
        if (prefs.worldTimeSwapped) {
            pushSwappedPair(zoneId)
        } else {
            pushHeader("World time → ${WorldTime.cityOf(zoneId)}")
        }
    }

    /** Casio swap: persist intent, then write the clock and the world register (spec §swap). */
    fun toggleSwap() {
        val active = prefs.worldTimeActiveZone ?: return
        prefs.worldTimeSwapped = !prefs.worldTimeSwapped
        refreshPreviews()
        pushSwappedPair(active)
    }

    /** Best-effort adopt of an on-watch change at app open; silent on read failure. */
    fun reconcileOnOpen() {
        // A push we just initiated hasn't landed yet — reconciling now would adopt the watch's
        // stale zone and revert the user's tap. Swap is a deliberate transient the user controls;
        // 0x35 cannot observe the clock register, so reconciling while swapped could clear the
        // swap flag without restoring the clock.
        if (pushInFlight || prefs.worldTimeSwapped) return
        val addr = prefs.watchAddress ?: return
        scope.launch {
            val watchSec = WorldTimeReadback.read(ble, addr) ?: return@launch
            // Compare against our last SUCCESSFUL write, not a recomputed value: 0x35 only ever
            // reflects the phone-written offset (Phase 0), so a divergence from lastPushed is the
            // only real signal that something external changed the register. Matching lastPushed
            // (even after DST drift) means our write still stands — the background chain handles DST.
            val lastPushed = prefs.worldTimeLastPushedOffsetSec ?: return@launch
            if (watchSec != lastPushed) {
                prefs.saveWorldTimeState(WorldTime.reconcile(prefs.worldTimeState(), watchSec, nowMs()))
                refreshPreviews()
            }
        }
    }

    /** Recomputes the card's labels from prefs + the injected clock. */
    fun refreshPreviews() {
        val now = nowMs()
        val s = prefs.worldTimeState()
        update {
            it.copy(
                worldTime = WorldTimeUiState(
                    slots = s.slots.map { zone ->
                        WorldTimeSlotUi(
                            zoneId = zone,
                            city = WorldTime.cityOf(zone),
                            timeLabel = WorldTime.timeLabel(zone, now) ?: "--:--",
                            offsetLabel = WorldTime.offsetLabel(WorldTime.offsetSecondsOf(zone, now) ?: 0),
                        )
                    },
                    activeZoneId = s.activeZoneId,
                    customOffsetLabel = s.customOffsetSec?.let { sec -> WorldTime.offsetLabel(sec) },
                    swapped = s.swapped,
                    homeCity = WorldTime.cityOf(homeZoneId()),
                ),
            )
        }
    }

    private fun pushHeader(successMessage: String) {
        val addr = prefs.watchAddress ?: return
        val header = WorldTimeHeader.fromPrefs(prefs, nowMs(), homeZoneId())
        val count = if (prefs.notificationsEnabled) prefs.notificationCount else 0
        pushInFlight = true
        scope.launch {
            try {
                ble.sendPacket(addr, NotificationCount.packetFor(count, header))
                    .onSuccess {
                        prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header)
                        showSnackbar(successMessage)
                    }
                    .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
            } finally {
                pushInFlight = false
            }
        }
    }

    /**
     * Casio swap write: the main clock takes [activeZone]'s offset (or home's, on un-swap) and
     * the world register takes the complementary value via [WorldTimeHeader]. The set-clock frame
     * (`02 23`) carries the phone's GPS coordinates for the watch's Sun & Moon face, so this fetches
     * a fresh fix first — inside the launch, guarded by [pushInFlight] exactly like [pushHeader] so
     * [reconcileOnOpen] can't adopt a stale value mid-push. On a missing fix (no permission / no
     * fix), this aborts before touching the watch: no clock write, no world-register write, no 0/0
     * coordinates (that would corrupt the watch's Sun & Moon position). The swap intent already
     * persisted in [toggleSwap]/[activate] stays as-is — the user retries by tapping again.
     */
    private fun pushSwappedPair(activeZone: String) {
        val addr = prefs.watchAddress ?: return
        val now = nowMs()
        val zoneForClock = if (prefs.worldTimeSwapped) activeZone else homeZoneId()
        val clockOffset = WorldTime.offsetSecondsOf(zoneForClock, now) ?: return
        val header = WorldTimeHeader.fromPrefs(prefs, now, homeZoneId())
        val count = if (prefs.notificationsEnabled) prefs.notificationCount else 0
        pushInFlight = true
        scope.launch {
            try {
                val coords = locationProvider.fetch().getOrNull()
                if (coords == null) {
                    showSnackbar("Location needed to set the clock — enable location and retry")
                    return@launch
                }
                val latE3 = (coords.lat * COORD_E3_SCALE).toInt()
                val lonE3 = (coords.lng * COORD_E3_SCALE).toInt()
                val clockOk = ble.sendPacket(addr, SetClock.build(now, clockOffset, latE3, lonE3)).isSuccess
                if (clockOk) {
                    prefs.worldTimeLastPushedClockOffsetSec = clockOffset
                    prefs.worldTimeSwapLatE3 = latE3
                    prefs.worldTimeSwapLonE3 = lonE3
                }
                ble.sendPacket(addr, NotificationCount.packetFor(count, header))
                    .onSuccess { prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header) }
                val where = if (prefs.worldTimeSwapped) WorldTime.cityOf(activeZone) else "home"
                showSnackbar(
                    if (clockOk) {
                        "Clock → $where"
                    } else {
                        "Send failed — long-press ALARM to wake the watch, then retry"
                    },
                )
            } finally {
                pushInFlight = false
            }
        }
    }

    private companion object {
        /** Degrees → SetClock's fixed-point coordinate scale (see [SetClock.build]'s latE3/lonE3). */
        const val COORD_E3_SCALE = 1000
    }
}
