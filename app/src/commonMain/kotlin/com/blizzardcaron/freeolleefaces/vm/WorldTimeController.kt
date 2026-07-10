package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.WorldTimeSlotUi
import com.blizzardcaron.freeolleefaces.ui.WorldTimeUiState
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

    fun addSlot(zoneId: String) {
        val slots = prefs.worldTimeSlots
        if (slots.size >= WorldTime.MAX_SLOTS || zoneId in slots) return
        if (WorldTime.offsetSecondsOf(zoneId, nowMs()) == null) return
        prefs.worldTimeSlots = slots + zoneId
        refreshPreviews()
    }

    fun removeSlot(zoneId: String) {
        prefs.worldTimeSlots = prefs.worldTimeSlots - zoneId
        if (prefs.worldTimeActiveZone == zoneId) prefs.worldTimeActiveZone = null
        refreshPreviews()
    }

    /** Chip tap: persist the intent first, then push the new offset to the watch. */
    fun activate(zoneId: String) {
        if (zoneId !in prefs.worldTimeSlots) return
        prefs.worldTimeActiveZone = zoneId
        prefs.worldTimeCustomOffsetSec = null
        refreshPreviews()
        pushHeader("World time → ${WorldTime.cityOf(zoneId)}")
    }

    /** Best-effort adopt of an on-watch change at app open; silent on read failure. */
    fun reconcileOnOpen() {
        val addr = prefs.watchAddress ?: return
        scope.launch {
            val watchSec = WorldTimeReadback.read(ble, addr) ?: return@launch
            val expected = WorldTimeCodec.decode(WorldTimeHeader.fromPrefs(prefs, nowMs(), homeZoneId()))
            if (watchSec == expected) return@launch
            prefs.saveWorldTimeState(WorldTime.reconcile(prefs.worldTimeState(), watchSec, nowMs()))
            refreshPreviews()
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
        scope.launch {
            ble.sendPacket(addr, NotificationCount.packetFor(count, header))
                .onSuccess {
                    prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header)
                    showSnackbar(successMessage)
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }
}
