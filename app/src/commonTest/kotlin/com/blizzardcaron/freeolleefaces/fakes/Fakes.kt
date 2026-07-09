package com.blizzardcaron.freeolleefaces.fakes

import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.ble.WatchConnection
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------------------
// FakeBleClient
// ---------------------------------------------------------------------------

/**
 * Hand-written fake BleClient. All three send variants record into [callLog] and return
 * [sendResult]. The gate deferred, if set, suspends every send until completed — used by
 * the in-flight guard test.
 *
 * For cross-fake ordering assertions (e.g. reschedule-before-send), pass the SAME list
 * instance here and to [FakeScheduler]; separately constructed logs can't be compared.
 */
class FakeBleClient(
    private val callLog: MutableList<String> = mutableListOf(),
    var sendResult: Result<Unit> = Result.success(Unit),
    /** When non-null, each call suspends until [gate].await() resolves. */
    var gate: CompletableDeferred<Unit>? = null,
) : BleClient {

    /** Every packet passed to [sendPacket], in order — lets tests assert on the framed bytes. */
    val sentPackets: MutableList<ByteArray> = mutableListOf()

    /** Nameplate (0x2F) text writes recorded by the targeted send overload. */
    val nameplateWrites: MutableList<String> = mutableListOf()
    fun sentNameplate(): List<String> = nameplateWrites

    override suspend fun send(deviceAddress: String, value: String): Result<Unit> {
        gate?.await()
        callLog += "ble.send($deviceAddress,$value)"
        return sendResult
    }

    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        gate?.await()
        callLog += "ble.send($deviceAddress,$value,target=$target)"
        if (target == OlleeProtocol.TARGET_NAMEPLATE) nameplateWrites += value
        return sendResult
    }

    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> {
        gate?.await()
        callLog += "ble.sendPacket($deviceAddress)"
        sentPackets += packet
        return sendResult
    }

    /** Reply returned by [sendAndAwait]; default is a failure so tests must opt into a reply. */
    var awaitResult: Result<OlleeProtocol.Frame> =
        Result.failure(IllegalStateException("no reply configured"))

    /** Replies consumed in order by successive [sendAndAwait] calls; falls back to [awaitResult]. */
    val awaitResults: ArrayDeque<Result<OlleeProtocol.Frame>> = ArrayDeque()

    override suspend fun sendAndAwait(
        deviceAddress: String,
        requestPacket: ByteArray,
        expectedTarget: Int,
        timeoutMs: Long,
    ): Result<OlleeProtocol.Frame> {
        gate?.await()
        callLog += "ble.sendAndAwait($deviceAddress,target=$expectedTarget)"
        // Read requests are recorded in callLog only — sentPackets tracks actual sendPacket writes.
        return if (awaitResults.isNotEmpty()) awaitResults.removeFirst() else awaitResult
    }
}

// ---------------------------------------------------------------------------
// FakeStepsProvider
// ---------------------------------------------------------------------------

class FakeStepsProvider(
    var availability: StepsProvider.Availability = StepsProvider.Availability.AVAILABLE,
    var readPermission: Boolean = true,
    var stepsResult: Result<Long> = Result.success(0L),
) : StepsProvider {

    override fun availability(): StepsProvider.Availability = availability

    override suspend fun hasReadPermission(): Boolean = readPermission

    override suspend fun todaySteps(): Result<Long> = stepsResult
}

// ---------------------------------------------------------------------------
// FakeLocationProvider
// ---------------------------------------------------------------------------

class FakeLocationProvider(
    var fetchResult: Result<Coords> = Result.failure(IllegalStateException("no location")),
) : LocationProvider {

    override suspend fun fetch(timeoutMs: Long): Result<Coords> = fetchResult
}

// ---------------------------------------------------------------------------
// FakeNotificationAccessChecker
// ---------------------------------------------------------------------------

class FakeNotificationAccessChecker(
    var granted: Boolean = false,
) : NotificationAccessChecker {

    override fun isGranted(): Boolean = granted
}

// ---------------------------------------------------------------------------
// FakeScheduler
// ---------------------------------------------------------------------------

/**
 * Records "scheduler.reschedule" into the shared [callLog] so tests can assert ordering
 * relative to BLE sends.
 */
class FakeScheduler(
    private val callLog: MutableList<String> = mutableListOf(),
) : Scheduler {

    override fun reschedule() {
        callLog += "scheduler.reschedule"
    }
}

// ---------------------------------------------------------------------------
// FakeWatchConnection
// ---------------------------------------------------------------------------

/**
 * Scriptable WatchConnection. [connect] records into the shared [callLog], drives [status] through
 * Connecting then [connectResult], and bumps [connectCount]. [disconnect] records and resets status.
 * Status starts at Connecting (the production link assumes a connect is imminent on foreground).
 */
class FakeWatchConnection(
    var connectResult: ConnectionStatus = ConnectionStatus.Connected,
    private val callLog: MutableList<String> = mutableListOf(),
) : WatchConnection {

    private val _status = MutableStateFlow(ConnectionStatus.Connecting)
    override val status: StateFlow<ConnectionStatus> = _status

    var connectCount = 0
        private set
    var disconnectCount = 0
        private set
    val connectedAddresses: MutableList<String> = mutableListOf()

    override suspend fun connect(address: String) {
        connectCount++
        connectedAddresses += address
        callLog += "watch.connect($address)"
        _status.value = ConnectionStatus.Connecting
        _status.value = connectResult
    }

    override fun disconnect() {
        disconnectCount++
        callLog += "watch.disconnect"
        _status.value = ConnectionStatus.NotReachable
    }
}

// ---------------------------------------------------------------------------
// FakeActivityTrackStore
// ---------------------------------------------------------------------------

class FakeActivityTrackStore : com.blizzardcaron.freeolleefaces.activity.ActivityTrackStore {
    private val saved = mutableListOf<com.blizzardcaron.freeolleefaces.activity.ActivityTrack>()
    override fun save(track: com.blizzardcaron.freeolleefaces.activity.ActivityTrack) {
        saved.removeAll { it.id == track.id }
        saved += track
    }
    override fun latest(): com.blizzardcaron.freeolleefaces.activity.ActivityTrack? =
        saved.maxByOrNull { it.startedAtMs }
    override fun list(): List<com.blizzardcaron.freeolleefaces.activity.ActivityTrack> =
        saved.sortedByDescending { it.startedAtMs }
    override fun delete(id: String) {
        saved.removeAll { it.id == id }
    }
    override fun prune(endedBeforeMs: Long): Int {
        val before = saved.size
        saved.removeAll { it.endedAtMs?.let { ended -> ended < endedBeforeMs } == true }
        return before - saved.size
    }
}

// ---------------------------------------------------------------------------
// FakeSessionAutoSleep
// ---------------------------------------------------------------------------

class FakeSessionAutoSleep(
    var disableResult: Boolean = true,
    var restoreResult: Boolean = true,
) : com.blizzardcaron.freeolleefaces.activity.SessionAutoSleep {
    val calls = mutableListOf<String>()

    /** When non-null, [restoreAfterActivity] suspends until this resolves (models the BLE wait). */
    var restoreGate: CompletableDeferred<Unit>? = null

    override suspend fun disableForActivity(address: String): Boolean {
        calls += "disable($address)"; return disableResult
    }
    override suspend fun restoreAfterActivity(address: String): Boolean {
        calls += "restore($address)"
        restoreGate?.await()
        return restoreResult
    }
}
