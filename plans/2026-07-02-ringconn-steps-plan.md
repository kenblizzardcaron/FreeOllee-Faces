# RingConn Live Step Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read the RingConn Gen2 ring's live onboard step count over BLE as a fresher source for the Steps complication, behind an opt-in toggle, displaying `max(ring, HealthConnect)` and silently falling back to Health Connect on any failure.

**Architecture:** A pure, testable core (`RingConnDescriptor` frame parser, `RingAuth`/SM3 handshake, `RingStepsSource` interface) in commonMain; an Android `AndroidRingStepsSource` that does a short-lived connect → handshake → read-descriptor → close against the ring's GATT, plus bonded-device discovery. The merge lands in `ComplicationController.refreshSteps`, gated on a new `ringConnStepsEnabled` pref. It is a separate short-lived GATT from the watch's `WatchLink` singleton.

**Tech Stack:** Kotlin Multiplatform (androidTarget only), Compose Multiplatform, `com.russhwolf.settings` (Prefs), Android BluetoothGatt, kotlinx.coroutines, kotlin.test.

## Design rationale: full handshake, not a passive probe

The approved spec was "capture-spike-first" to avoid building SM3 if a passive (no-auth) read
sufficed. The 2026-07-02 btsnoop capture superseded that: it **proved** the SM3 algorithm on the
actual ring (challenge `0x11` → response `e1 b9 23`, a unique `V=0x4a`) and confirmed the exact
handshake the official app uses. Building the handshake is therefore no longer speculative, and
doing it removes all uncertainty for the cost of a sub-second exchange. This plan implements the
**full handshake unconditionally** (the proven path) rather than gambling on a passive read that
saves only that sub-second. The one thing the capture could not prove — that *our* Android GATT
client, doing that handshake, receives descriptors — is the **final on-device verification task
(Task 7)**, which is the real hardware gate.

## Global Constraints

- **Steps only.** No HR, temperature, SpO2, sleep, or ring battery — out of scope.
- **Opt-in, off by default.** `ringConnStepsEnabled` defaults false; when off, the ring source is never consulted and no BLE traffic occurs.
- **Never a hard dependency.** Any ring failure (no bond, out of range, on charger, timeout, malformed frame, missing permission) falls back silently to the existing Health Connect path — no error surfaced on the card.
- **Merge rule is `max(ring, healthConnect)`**, computed before `prefs.recordStepsFetch(...)` so the cache and "Updated …" timestamp reflect the displayed value.
- **Separate short-lived GATT.** Connect-on-demand only; always `gatt.close()` in a finally; never hold a connection; never touch the watch's `WatchLink` singleton.
- **No BLE scan / no location permission.** Discovery enumerates *bonded* devices and connects by address. Only `BLUETOOTH_CONNECT` (already declared, already used by the watch feature).
- **detekt has NO baseline.** No new findings; targeted `@Suppress` with a justifying comment only where a rule is genuinely wrong for the code.
- **Triple gate** for any task touching code: `./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug` (run foreground on the Pi; never in parallel; never kill).
- **Branch:** a NEW feature branch off `main` (e.g. `feat/ringconn-steps`). NOT part of PR #35. Bump the root `VERSION` file before the release-bound push, per release process.
- **Verified protocol facts** (from `plans/2026-07-02-ringconn-steps-design.md`, btsnoop-confirmed on the user's ring): notify value handle `0x0804`, notify CCCD `0x0805` (enable `01 00`), write handle `0x0802`, in service `8327ad99-2d87-4a22-a8ce-6dd7971c0437`; write char `8327ad98-…`, notify char `8327ad97-…`. Descriptor is 19 bytes, step count at bytes `[4:6]` big-endian, XOR trailer = XOR of all preceding bytes. Handshake: enable CCCD → write `01 00 00` → notify `81 00 <challenge> <xor>` → write `01 01 <r0> <r1> <r2> 00` where `response = SM3(byteArrayOf(V, challenge))[29..31]`, `V = mac[3] xor mac[4] xor mac[5]`; then `d0 00 00` → notify `0x10 …` descriptor (or await a spontaneous one).

---

## File Structure

- **Create** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptor.kt` — pure frame parser (`parseSteps`).
- **Create** `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptorTest.kt` — parser tests over real captured frames.
- **Create** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/Sm3.kt` — pure SM3 (GB/T 32905) hash.
- **Create** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuth.kt` — challenge→response command builder.
- **Create** `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuthTest.kt` — SM3 KATs + on-device vector.
- **Create** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingStepsSource.kt` — interface + `NoopRingStepsSource`.
- **Create** `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ring/AndroidRingStepsSource.kt` — GATT connect/handshake/read.
- **Create** `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDiscovery.kt` — bonded-device enumeration.
- **Create** `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/FakeRingStepsSource.kt` — test double.
- **Modify** `prefs/Prefs.kt` — add `ringConnStepsEnabled`, `ringConnAddress` + KEY constants.
- **Modify** `vm/ComplicationController.kt` — inject `ringSteps`, merge in `refreshSteps`.
- **Modify** `AppViewModel.kt` — ctor param `ringSteps` (default Noop), pass to controller, expose enabled/name state + toggle.
- **Modify** `MainActivity.kt` — construct `AndroidRingStepsSource`.
- **Modify** `ui/ComplicationCards.kt` (`StepsCard`) + `ui/Callbacks.kt` (`HomeCallbacks`) — opt-in toggle row.
- **Modify** `commonTest/.../vm/ComplicationControllerTest.kt`, `commonTest/.../prefs/PrefsTest.kt`, `screenFixtures/.../ScreenFakes.kt` — tests + fixture wiring.

Note: the spec said "Settings screen — Steps section," but the codebase's Steps controls live in the expandable `StepsCard` (health-access button, push cadence text). Following the existing pattern, the toggle goes there, not in `SettingsScreen`.

---

### Task 1: `RingConnDescriptor` pure frame parser

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptor.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptorTest.kt`

**Interfaces:**
- Produces: `object RingConnDescriptor { fun parseSteps(frame: ByteArray): Long? }` — returns the ring's onboard step count from a `0x10`/`0x87` status descriptor, or null on any malformed frame.

- [ ] **Step 1: Write the failing test** (real captured frames from the btsnoop capture, all XOR-valid)

```kotlin
package com.blizzardcaron.freeolleefaces.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RingConnDescriptorTest {

    // Real captured descriptors (btsnoop, 2026-07-02, user's Gen2 ring). 19 bytes, XOR-valid.
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val fetch15 = bytes(0x87, 0x4f, 0x03, 0x00, 0x00, 0x0f, 0x01, 0x3b, 0x01, 0x45,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0x73)
    private val spont90 = bytes(0x10, 0x4f, 0x03, 0x00, 0x00, 0x5a, 0x01, 0x37, 0x01, 0x40,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0xb8)
    private val spont314 = bytes(0x10, 0x4f, 0x02, 0x00, 0x01, 0x3a, 0x01, 0x2a, 0x01, 0x38,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x28, 0x00, 0xff, 0x24)

    @Test fun parses_fetch_descriptor_0x87() = assertEquals(15L, RingConnDescriptor.parseSteps(fetch15))
    @Test fun parses_spontaneous_descriptor_0x10() = assertEquals(90L, RingConnDescriptor.parseSteps(spont90))
    @Test fun parses_high_step_count() = assertEquals(314L, RingConnDescriptor.parseSteps(spont314))

    @Test fun rejects_wrong_response_id() =
        assertNull(RingConnDescriptor.parseSteps(bytes(0x81, 0x00, 0x11, 0x90)))

    @Test fun rejects_truncated_frame() =
        assertNull(RingConnDescriptor.parseSteps(bytes(0x10, 0x4f, 0x03, 0x00, 0x00)))

    @Test fun rejects_bad_xor_trailer() {
        val corrupt = spont90.copyOf().also { it[it.size - 1] = 0x00 }
        assertNull(RingConnDescriptor.parseSteps(corrupt))
    }

    @Test fun parses_zero_steps() {
        // 0x10 with steps [4:6]=00 00; recompute a valid XOR trailer over [0..17].
        val body = bytes(0x10, 0x4f, 0x03, 0x00, 0x00, 0x00, 0x01, 0x37, 0x01, 0x40,
            0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0x00)
        var x = 0
        for (i in 0 until body.size - 1) x = x xor (body[i].toInt() and 0xff)
        body[body.size - 1] = x.toByte()
        assertEquals(0L, RingConnDescriptor.parseSteps(body))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RingConnDescriptorTest"`
Expected: FAIL — `RingConnDescriptor` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.ring

/**
 * Pure parser for the RingConn Gen2 status descriptor (the 19-byte frame the ring emits
 * spontaneously ~30-60 s while connected, and in reply to `d0 00 00` / `07 00 00`). Response id
 * `0x10` (spontaneous / `d0` reply) or `0x87` (reply to `07 00 00`); identical body. Step count
 * is the ring's onboard daily total at bytes [4:6], 16-bit big-endian. Byte-offsets and framing
 * are btsnoop-verified on real Gen2 hardware (see plans/2026-07-02-ringconn-steps-design.md).
 */
object RingConnDescriptor {

    private const val ID_SPONTANEOUS = 0x10
    private const val ID_FETCH_REPLY = 0x87
    private const val MIN_LENGTH = 6      // need [0] id + [4:6] steps
    private const val STEP_HI = 4
    private const val STEP_LO = 5
    private const val BYTE_MASK = 0xFF

    /** The ring's onboard step count, or null if [frame] is not a valid status descriptor. */
    fun parseSteps(frame: ByteArray): Long? {
        if (frame.size < MIN_LENGTH) return null
        val id = frame[0].toInt() and BYTE_MASK
        if (id != ID_SPONTANEOUS && id != ID_FETCH_REPLY) return null
        if (!xorValid(frame)) return null
        val hi = frame[STEP_HI].toInt() and BYTE_MASK
        val lo = frame[STEP_LO].toInt() and BYTE_MASK
        return ((hi shl 8) or lo).toLong()
    }

    /** Trailer (last byte) is the XOR of all preceding bytes. */
    private fun xorValid(frame: ByteArray): Boolean {
        var x = 0
        for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and BYTE_MASK)
        return (x and BYTE_MASK) == (frame[frame.size - 1].toInt() and BYTE_MASK)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*RingConnDescriptorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Run detekt**

Run: `./gradlew :app:detekt`
Expected: no findings on the new file.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptor.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDescriptorTest.kt
git commit -m "feat(ring): RingConn Gen2 status-descriptor step parser

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: SM3 hash + `RingAuth` handshake command

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/Sm3.kt`
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuth.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuthTest.kt`

**Interfaces:**
- Produces: `object Sm3 { fun digest(input: ByteArray): ByteArray }` (32-byte GB/T 32905 hash); `object RingAuth { fun authCommand(challenge: Int, mac: ByteArray): ByteArray }` returning the 6-byte command `01 01 r0 r1 r2 00`.

- [ ] **Step 1: Write the failing test** (GB/T 32905 known-answer vectors + the real on-device pair)

```kotlin
package com.blizzardcaron.freeolleefaces.ring

import kotlin.test.Test
import kotlin.test.assertEquals

class RingAuthTest {

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test fun sm3_abc_known_answer() {
        // GB/T 32905 KAT: SM3("abc")
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            hex(Sm3.digest("abc".encodeToByteArray())),
        )
    }

    @Test fun sm3_64byte_known_answer() {
        // GB/T 32905 KAT: SM3 of "abcd" repeated 16 times (64 bytes)
        val msg = "abcd".repeat(16).encodeToByteArray()
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            hex(Sm3.digest(msg)),
        )
    }

    @Test fun authCommand_matches_captured_on_device_pair() {
        // btsnoop 2026-07-02: challenge 0x11 -> response e1 b9 23, unique V=0x4a
        // (user's ring MAC ends ..:F6:96:2A; V = 0xf6 xor 0x96 xor 0x2a = 0x4a).
        val mac = byteArrayOf(
            0x00, 0x00, 0x00, 0xF6.toByte(), 0x96.toByte(), 0x2A,
        )
        val cmd = RingAuth.authCommand(challenge = 0x11, mac = mac)
        assertEquals(
            "01 01 e1 b9 23 00",
            cmd.joinToString(" ") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RingAuthTest"`
Expected: FAIL — `Sm3` / `RingAuth` unresolved.

- [ ] **Step 3: Write `Sm3.kt`** (standard GB/T 32905 implementation)

```kotlin
package com.blizzardcaron.freeolleefaces.ring

/**
 * Pure Kotlin SM3 (GB/T 32905) 256-bit hash. Used only to derive the RingConn per-connection
 * auth response (see [RingAuth]). Verified by the GB/T 32905 known-answer vectors in RingAuthTest.
 */
object Sm3 {

    private const val BLOCK_BYTES = 64
    private const val DIGEST_WORDS = 8

    /** Canonical GB/T 32905 initial vector (7380166f 4914b2b9 172442d7 da8a0600
     *  a96f30bc 163138aa e38dee4d b0fb0e4e). Values > 0x7fffffff need `.toInt()`. */
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )
    private const val T0 = 0x79cc4519 // Tj for 0 <= j < 16
    private const val T1 = 0x7a879d8a // Tj for 16 <= j < 64

    fun digest(input: ByteArray): ByteArray {
        val v = IV.copyOf()
        val padded = pad(input)
        val w = IntArray(68)
        val w1 = IntArray(64)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                w[i] = (padded[off + i * 4].toInt() and 0xff shl 24) or
                    (padded[off + i * 4 + 1].toInt() and 0xff shl 16) or
                    (padded[off + i * 4 + 2].toInt() and 0xff shl 8) or
                    (padded[off + i * 4 + 3].toInt() and 0xff)
            }
            for (j in 16 until 68) {
                val x = w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)
                w[j] = p1(x) xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]
            compress(v, w, w1)
            off += BLOCK_BYTES
        }
        val out = ByteArray(DIGEST_WORDS * 4)
        for (i in 0 until DIGEST_WORDS) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    private fun compress(v: IntArray, w: IntArray, w1: IntArray) {
        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
        var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
        for (j in 0 until 64) {
            val tj = if (j < 16) T0 else T1
            val ss1 = rotl(rotl(a, 12) + e + rotl(tj, j % 32), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = (if (j < 16) (a xor b xor c) else ((a and b) or (a and c) or (b and c))) +
                d + ss2 + w1[j]
            val tt2 = (if (j < 16) (e xor f xor g) else ((e and f) or (e.inv() and g))) +
                h + ss1 + w[j]
            d = c; c = rotl(b, 9); b = a; a = tt1
            h = g; g = rotl(f, 19); f = e; e = p0(tt2)
        }
        v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
        v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
    }

    private fun pad(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        var padLen = BLOCK_BYTES - ((input.size + 9) % BLOCK_BYTES)
        if (padLen == BLOCK_BYTES) padLen = 0
        val out = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(out)
        out[input.size] = 0x80.toByte()
        for (i in 0 until 8) out[out.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        return out
    }

    private fun rotl(x: Int, n: Int) = (x shl (n and 31)) or (x ushr (32 - (n and 31)))
    private fun p0(x: Int) = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int) = x xor rotl(x, 15) xor rotl(x, 23)
}
```

> Implementer note: the KAT tests are the correctness gate. If `sm3_abc_known_answer` and
> `sm3_64byte_known_answer` pass, the SM3 constants and rounds are right. Do not hand-verify the
> hash by eye — trust the vectors.

- [ ] **Step 4: Write `RingAuth.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ring

/**
 * RingConn Gen2 per-connection auth. The ring answers `01 00 00` with `81 00 <challenge> <xor>`;
 * the host must reply `01 01 <r0> <r1> <r2> 00` where the three response bytes are the last three
 * bytes of SM3(V, challenge) and V = XOR of the ring MAC's last three bytes. The only key material
 * is the ring's own MAC — computable offline. Algorithm verified against a captured on-device pair
 * (see RingAuthTest); see plans/2026-07-02-ringconn-steps-design.md.
 */
object RingAuth {

    private const val CMD = 0x01
    private const val SUB_AUTH = 0x01
    private const val TRAILER = 0x00
    private const val DIGEST_LEN = 32
    private const val RESP_BYTES = 3
    private const val BYTE_MASK = 0xFF

    /** Build the 6-byte auth command answering [challenge], keyed by the ring's [mac] (>= 6 bytes). */
    fun authCommand(challenge: Int, mac: ByteArray): ByteArray {
        require(mac.size >= 6) { "mac must be at least 6 bytes" }
        val v = (mac[mac.size - 3].toInt() xor mac[mac.size - 2].toInt() xor mac[mac.size - 1].toInt()) and BYTE_MASK
        val digest = Sm3.digest(byteArrayOf(v.toByte(), (challenge and BYTE_MASK).toByte()))
        val r = digest.copyOfRange(DIGEST_LEN - RESP_BYTES, DIGEST_LEN)
        return byteArrayOf(CMD.toByte(), SUB_AUTH.toByte(), r[0], r[1], r[2], TRAILER.toByte())
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RingAuthTest"`
Expected: PASS (3 tests). If the `abc` KAT fails, the SM3 constants are wrong — fix before proceeding.

- [ ] **Step 6: detekt + commit**

Run: `./gradlew :app:detekt`

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/Sm3.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuth.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ring/RingAuthTest.kt
git commit -m "feat(ring): SM3 hash + RingConn per-connection auth command

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `RingStepsSource` interface + Prefs opt-in keys

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingStepsSource.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt`

**Interfaces:**
- Produces: `interface RingStepsSource { suspend fun readSteps(): Result<Long?> }`; `object NoopRingStepsSource : RingStepsSource`; `Prefs.ringConnStepsEnabled: Boolean` (default false); `Prefs.ringConnAddress: String?` (default null).

- [ ] **Step 1: Write the failing test** (append to `PrefsTest.kt`)

```kotlin
@Test
fun ringConnSteps_defaults_off_and_roundtrips() {
    val prefs = Prefs(MapSettings())
    assertFalse(prefs.ringConnStepsEnabled)
    assertNull(prefs.ringConnAddress)
    prefs.ringConnStepsEnabled = true
    prefs.ringConnAddress = "F8:79:99:F6:96:2A"
    assertTrue(prefs.ringConnStepsEnabled)
    assertEquals("F8:79:99:F6:96:2A", prefs.ringConnAddress)
    prefs.ringConnAddress = null
    assertNull(prefs.ringConnAddress)
}
```

(Ensure `assertFalse`, `assertNull`, `assertTrue`, `assertEquals` and `MapSettings` are imported in `PrefsTest.kt` — add any missing imports.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PrefsTest"`
Expected: FAIL — `ringConnStepsEnabled` unresolved.

- [ ] **Step 3: Add the Prefs properties** (place beside the other boolean/string prefs, e.g. after `notificationsEnabled`)

```kotlin
    /** Opt-in: read live steps from the bonded RingConn ring over BLE (off by default). */
    var ringConnStepsEnabled: Boolean
        get() = settings.getBoolean(KEY_RINGCONN_STEPS, false)
        set(value) = settings.putBoolean(KEY_RINGCONN_STEPS, value)

    /** BLE address of the chosen RingConn ring; null until discovery selects one. */
    var ringConnAddress: String?
        get() = settings.getStringOrNull(KEY_RINGCONN_ADDRESS)
        set(value) = if (value == null) settings.remove(KEY_RINGCONN_ADDRESS) else settings.putString(KEY_RINGCONN_ADDRESS, value)
```

Add to the companion `KEY_` block:

```kotlin
        private const val KEY_RINGCONN_STEPS = "ringconn_steps_enabled"
        private const val KEY_RINGCONN_ADDRESS = "ringconn_address"
```

- [ ] **Step 4: Create `RingStepsSource.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ring

/**
 * Reads the ring's live onboard step count.
 *  - `success(n)`    — the ring's onboard count
 *  - `success(null)` — connected but no step frame arrived this cycle
 *  - `failure`       — read genuinely failed; the caller falls back to Health Connect
 * Nothing thrown escapes an implementation; all faults surface as `failure`.
 */
interface RingStepsSource {
    suspend fun readSteps(): Result<Long?>
}

/** Used when the feature is off or on non-Android targets: never contributes a value. */
object NoopRingStepsSource : RingStepsSource {
    override suspend fun readSteps(): Result<Long?> = Result.success(null)
}
```

- [ ] **Step 5: Run test + detekt + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "*PrefsTest" :app:detekt`
Expected: PASS.

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingStepsSource.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
git commit -m "feat(ring): RingStepsSource interface + opt-in prefs

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Merge ring steps into `ComplicationController.refreshSteps`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ComplicationController.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Create: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/FakeRingStepsSource.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ComplicationControllerTest.kt`

**Interfaces:**
- Consumes: `RingStepsSource` (Task 3), `Prefs.ringConnStepsEnabled` (Task 3).
- Produces: `ComplicationController` gains ctor param `ringSteps: RingStepsSource = NoopRingStepsSource`; `AppViewModel` gains ctor param `ringSteps: RingStepsSource = NoopRingStepsSource` and passes it to the controller.

- [ ] **Step 1: Create `FakeRingStepsSource.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.fakes

import com.blizzardcaron.freeolleefaces.ring.RingStepsSource

/** Test double: returns a preset result and counts how many times it was consulted. */
class FakeRingStepsSource(
    var result: Result<Long?> = Result.success(null),
) : RingStepsSource {
    var calls = 0
        private set

    override suspend fun readSteps(): Result<Long?> {
        calls++
        return result
    }
}
```

- [ ] **Step 2: Write the failing tests** (append to `ComplicationControllerTest.kt`; add imports for `FakeRingStepsSource` and `assertFalse`/`assertEquals` as needed)

```kotlin
@Test
fun refreshSteps_ringHigher_displaysRing() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()).apply { ringConnStepsEnabled = true }
    val holder = StateHolder()
    val steps = FakeStepsProvider(stepsResult = Result.success(1000L))
    val ring = FakeRingStepsSource(Result.success(1500L))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring, holder = holder)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(1500L, prefs.lastStepCount)
    assertTrue(holder.st.stepsPreview is PreviewState.Ready)
}

@Test
fun refreshSteps_healthConnectHigher_displaysHealthConnect() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()).apply { ringConnStepsEnabled = true }
    val steps = FakeStepsProvider(stepsResult = Result.success(2000L))
    val ring = FakeRingStepsSource(Result.success(500L))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(2000L, prefs.lastStepCount)
}

@Test
fun refreshSteps_ringNull_usesHealthConnect() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()).apply { ringConnStepsEnabled = true }
    val steps = FakeStepsProvider(stepsResult = Result.success(1200L))
    val ring = FakeRingStepsSource(Result.success(null))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(1200L, prefs.lastStepCount)
}

@Test
fun refreshSteps_ringFailure_usesHealthConnect() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()).apply { ringConnStepsEnabled = true }
    val steps = FakeStepsProvider(stepsResult = Result.success(1200L))
    val ring = FakeRingStepsSource(Result.failure(RuntimeException("out of range")))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(1200L, prefs.lastStepCount)
}

@Test
fun refreshSteps_healthConnectFailure_withRing_usesRingFresh() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()).apply { ringConnStepsEnabled = true }
    val holder = StateHolder()
    val steps = FakeStepsProvider(stepsResult = Result.failure(RuntimeException("HC down")))
    val ring = FakeRingStepsSource(Result.success(800L))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring, holder = holder)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(800L, prefs.lastStepCount)
    val preview = holder.st.stepsPreview
    assertTrue(preview is PreviewState.Ready && !preview.human.contains("stale"))
}

@Test
fun refreshSteps_toggleOff_ignoresRing() = runTest(testScheduler) {
    val prefs = Prefs(MapSettings()) // ringConnStepsEnabled defaults false
    val steps = FakeStepsProvider(stepsResult = Result.success(1000L))
    val ring = FakeRingStepsSource(Result.success(9999L))
    val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, ring = ring)
    c.refreshSteps(push = false)
    advanceUntilIdle()
    assertEquals(1000L, prefs.lastStepCount)
    assertEquals(0, ring.calls)
}
```

Extend the `controller(...)` test factory to accept and pass the fake:

```kotlin
        ring: FakeRingStepsSource = FakeRingStepsSource(),
        ...
    ) = ComplicationController(
        ...
        ringSteps = ring,
        clock = clock,
    )
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*ComplicationControllerTest"`
Expected: FAIL — `ringSteps` param does not exist.

- [ ] **Step 4: Add the ctor param + merge logic** in `ComplicationController.kt`

Add to the constructor (after `update`, before `clock`):

```kotlin
    private val ringSteps: RingStepsSource = NoopRingStepsSource,
```

Add the import `com.blizzardcaron.freeolleefaces.ring.RingStepsSource` and `com.blizzardcaron.freeolleefaces.ring.NoopRingStepsSource`.

Replace the body of `refreshSteps` so the Health Connect result is merged with the ring:

```kotlin
    fun refreshSteps(push: Boolean) {
        scope.launch {
            if (!steps.hasReadPermission()) {
                update {
                    it.copy(
                        stepsHealthGranted = false,
                        stepsPreview = PreviewState.Error("Grant Health access to read steps"),
                    )
                }
                return@launch
            }
            update { it.copy(stepsHealthGranted = true, stepsPreview = PreviewState.Loading) }
            steps.todaySteps()
                .onSuccess { count ->
                    val display = maxOf(count, ringStepsOrNull() ?: count)
                    showFreshSteps(display, push)
                }
                .onFailure {
                    val ring = ringStepsOrNull()
                    if (ring != null) {
                        showFreshSteps(ring, push)
                    } else {
                        showCachedOrError(push)
                    }
                }
        }
    }

    /** The ring's live count, or null when the feature is off or the ring did not contribute. */
    private suspend fun ringStepsOrNull(): Long? =
        if (!prefs.ringConnStepsEnabled) null else ringSteps.readSteps().getOrNull()

    private fun showFreshSteps(count: Long, push: Boolean) {
        prefs.recordStepsFetch(count)
        val payload = DisplayFormatter.steps(count)
        update {
            it.copy(
                stepsPreview = PreviewState.Ready(payload, stepsHuman(count)),
                stepsUpdated = "Updated ${clockTime(prefs.stepsFetchedMs!!)}",
            )
        }
        if (push) pushIfWatch(payload)
    }

    private fun showCachedOrError(push: Boolean) {
        val cached = prefs.lastStepCount
        if (cached != null) {
            val payload = DisplayFormatter.steps(cached, stale = true)
            update {
                it.copy(
                    stepsPreview = PreviewState.Ready(payload, stepsHuman(cached) + " (stale)"),
                    stepsUpdated = prefs.stepsFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                )
            }
            if (push) pushIfWatch(payload)
        } else {
            update {
                it.copy(
                    stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                )
            }
        }
    }
```

- [ ] **Step 5: Wire `AppViewModel`** — add ctor param and pass through

In `AppViewModel.kt` constructor add (near `steps`):

```kotlin
    private val ringSteps: RingStepsSource = NoopRingStepsSource,
```

Add imports for `RingStepsSource` / `NoopRingStepsSource`. In the `ComplicationController(...)` construction add:

```kotlin
        ringSteps = ringSteps,
```

- [ ] **Step 6: Run the full gate + commit**

Run: `./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug`
Expected: all green (existing steps tests still pass — with the toggle off, behavior is byte-identical).

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ComplicationController.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/FakeRingStepsSource.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ComplicationControllerTest.kt
git commit -m "feat(ring): merge live ring steps into the Steps complication (max, silent fallback)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Android GATT read + bonded-ring discovery

**Files:**
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ring/AndroidRingStepsSource.kt`
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ring/RingConnDiscovery.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`

**Interfaces:**
- Consumes: `RingConnDescriptor.parseSteps` (Task 1), `RingAuth.authCommand` (Task 2), `RingStepsSource` (Task 3), `BleRetryPolicy` (existing).
- Produces: `class AndroidRingStepsSource(context, addressProvider: () -> String?) : RingStepsSource`; `class RingConnDiscovery(context)` with `fun bondedRings(): List<RingDevice>` and `data class RingDevice(val address: String, val name: String)`.

This task is not unit-testable (raw GATT). Its gate is `:app:assembleDebug` (compile) plus the on-device verification in Task 7. Mirror `ble/WatchLink.kt` for the connect boilerplate: `@SuppressLint("MissingPermission")`, `TRANSPORT_LE`, the dual `onCharacteristicChanged` overrides (2-arg deprecated + 3-arg API 33), and `gatt.close()` semantics.

- [ ] **Step 1: Create `RingConnDiscovery.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context

/** A bonded RingConn ring the user can read steps from. */
data class RingDevice(val address: String, val name: String)

/**
 * Finds bonded RingConn rings by name prefix. Bonded-enumeration + connect-by-address needs only
 * BLUETOOTH_CONNECT — no scan, no location permission. Never throws.
 */
class RingConnDiscovery(context: Context) {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun bondedRings(): List<RingDevice> = runCatching {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        adapter.bondedDevices.orEmpty()
            .filter { it.name?.startsWith(RING_NAME_PREFIX) == true }
            .map { RingDevice(it.address, it.name) }
    }.getOrDefault(emptyList())

    private companion object {
        const val RING_NAME_PREFIX = "RingConn"
    }
}
```

- [ ] **Step 2: Create `AndroidRingStepsSource.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Reads the ring's live onboard step count with a short-lived GATT connection: connect →
 * enable notify → auth handshake → prompt a status descriptor → parse steps → close. Separate
 * from the watch's WatchLink singleton (different device). Never throws; every fault becomes
 * Result.failure, and a connected-but-silent cycle becomes success(null).
 *
 * Protocol (btsnoop-verified on Gen2, see plans/2026-07-02-ringconn-steps-design.md):
 *   CCCD(0x0805) <- 01 00 ; write(0x0802) 01 00 00 ; notify(0x0804) 81 00 <chal> <xor> ;
 *   write 01 01 <r0 r1 r2> 00 ; write d0 00 00 ; notify 10/87 descriptor (steps [4:6] BE).
 */
class AndroidRingStepsSource(
    context: Context,
    private val addressProvider: () -> String?,
) : RingStepsSource {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun readSteps(): Result<Long?> = withContext(Dispatchers.IO) {
        val address = addressProvider() ?: return@withContext Result.failure(IllegalStateException("no ring selected"))
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return@withContext Result.failure(IllegalStateException("no bluetooth adapter"))
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("bad ring address"))
        val mac = macBytes(address) ?: return@withContext Result.failure(IllegalStateException("bad mac"))

        val result = CompletableDeferred<Long?>()
        val callback = RingGattCallback(mac, result)
        val gatt = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothProfile.GATT.let { android.bluetooth.BluetoothDevice.TRANSPORT_LE })
        }.getOrNull() ?: return@withContext Result.failure(IllegalStateException("connectGatt failed"))

        try {
            val steps = withTimeoutOrNull(READ_TIMEOUT_MS) { result.await() }
            Result.success(steps) // null on timeout (connected but silent) or on internal give-up
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { gatt.close() }
        }
    }

    /** GATT state machine: enable notify -> status request -> auth -> descriptor prompt -> parse. */
    @SuppressLint("MissingPermission")
    private inner class RingGattCallback(
        private val mac: ByteArray,
        private val result: CompletableDeferred<Long?>,
    ) : android.bluetooth.BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!result.isCompleted) result.complete(null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val notify = gatt.getService(SERVICE)?.getCharacteristic(NOTIFY_CHAR)
            if (notify == null) { result.complete(null); return }
            gatt.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) { result.complete(null); return }
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // CCCD written -> request status/challenge.
            write(gatt, CMD_STATUS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handle(gatt, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handle(gatt, value)
        }

        private fun handle(gatt: BluetoothGatt, value: ByteArray?) {
            if (value == null || value.isEmpty()) return
            val steps = RingConnDescriptor.parseSteps(value)
            if (steps != null) {
                if (!result.isCompleted) result.complete(steps)
                return
            }
            // Challenge frame: 81 00 <chal> <xor>
            if (value.size >= 4 && (value[0].toInt() and 0xff) == 0x81 && (value[1].toInt() and 0xff) == 0x00) {
                val challenge = value[2].toInt() and 0xff
                write(gatt, RingAuth.authCommand(challenge, mac))
                write(gatt, CMD_DESCRIPTOR) // prompt a 0x10 descriptor
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Writes are sequenced by the notify responses; nothing to do here.
        }

        private fun write(gatt: BluetoothGatt, bytes: ByteArray) {
            val char = gatt.getService(SERVICE)?.getCharacteristic(WRITE_CHAR) ?: return
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    char.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
            }
        }

        private fun writeDescriptor(gatt: BluetoothGatt, d: BluetoothGattDescriptor, bytes: ByteArray) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(d, bytes)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(d)
                }
            }
        }
    }

    private fun macBytes(address: String): ByteArray? = runCatching {
        address.split(":").map { it.toInt(16).toByte() }.toByteArray().takeIf { it.size == 6 }
    }.getOrNull()

    private companion object {
        val SERVICE: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
        val WRITE_CHAR: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
        val NOTIFY_CHAR: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val CMD_STATUS = byteArrayOf(0x01, 0x00, 0x00)
        val CMD_DESCRIPTOR = byteArrayOf(0xd0.toByte(), 0x00, 0x00)
        const val READ_TIMEOUT_MS = 4_000L
    }
}
```

> Implementer note: the `connectGatt` line above is written oddly to avoid an unused import; simplify
> to `device.connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)`
> and drop the `BluetoothProfile.GATT.let { }` wrapper. Verify it compiles; mirror `WatchLink`'s exact
> connect call if the signature differs on this AGP/compileSdk.

- [ ] **Step 3: Wire `MainActivity.createAppViewModel`** — add after `steps = AndroidStepsProvider(context),`:

```kotlin
        ringSteps = AndroidRingStepsSource(context) { Prefs(appSettings(context)).ringConnAddress },
```

Add the import `com.blizzardcaron.freeolleefaces.ring.AndroidRingStepsSource`.

- [ ] **Step 4: Compile gate + commit**

Run: `./gradlew :app:assembleDebug :app:detekt`
Expected: BUILD SUCCESSFUL, no detekt findings.

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ring/ \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(ring): Android GATT step read + bonded-ring discovery

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Opt-in toggle in the Steps card

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ComplicationCards.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeState.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt` (or wherever `HomeCallbacks` is constructed — grep for `onGrantHealth =`)
- Modify: `app/src/screenFixtures/kotlin/com/blizzardcaron/freeolleefaces/screens/ScreenFakes.kt`

**Interfaces:**
- Consumes: `Prefs.ringConnStepsEnabled` (Task 3), `RingConnDiscovery` (Task 5).
- Produces: `HomeState.ringConnStepsEnabled: Boolean`, `HomeState.ringConnName: String?`; `HomeCallbacks.onToggleRingConnSteps: (Boolean) -> Unit`; `AppViewModel.toggleRingConnSteps(enabled: Boolean)`.

- [ ] **Step 1: Add state fields** to `HomeState.kt`:

```kotlin
    val ringConnStepsEnabled: Boolean = false,
    val ringConnName: String? = null,
```

- [ ] **Step 2: Add the callback** to `HomeCallbacks` in `Callbacks.kt`:

```kotlin
    val onToggleRingConnSteps: (Boolean) -> Unit,
```

- [ ] **Step 3: Render the toggle** in `StepsCard` (`ComplicationCards.kt`), inside the `else` branch (health granted), after the "Pushed every …" text:

```kotlin
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Read live steps from RingConn", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.ringConnName?.let { "Ring: $it" }
                            ?: "Pair your ring in the RingConn app first",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.ringConnStepsEnabled,
                    onCheckedChange = callbacks.onToggleRingConnSteps,
                )
            }
```

Add any missing imports (`Row`, `Column`, `Switch`, `Arrangement`, `Alignment`, `MaterialTheme`).

- [ ] **Step 4: Implement `toggleRingConnSteps`** in `AppViewModel.kt`. On enable, run discovery to pick/label the ring; persist the choice and the flag; seed `HomeState`:

```kotlin
    fun toggleRingConnSteps(enabled: Boolean) {
        prefs.ringConnStepsEnabled = enabled
        if (enabled && prefs.ringConnAddress == null) {
            val rings = ringDiscovery.bondedRings()
            rings.singleOrNull()?.let { prefs.ringConnAddress = it.address }
        }
        val name = prefs.ringConnAddress?.let { addr ->
            ringDiscovery.bondedRings().firstOrNull { it.address == addr }?.name
        }
        state = state.copy(ringConnStepsEnabled = enabled, ringConnName = name)
    }
```

Add a constructor dependency for discovery that defaults to a no-op on non-Android/test targets. Introduce a tiny common interface to keep `AppViewModel` in commonMain:

```kotlin
// in ring/RingStepsSource.kt (or a new ring/RingDiscovery.kt)
interface RingDiscovery { fun bondedRings(): List<RingDevice> }
object NoopRingDiscovery : RingDiscovery { override fun bondedRings(): List<RingDevice> = emptyList() }
data class RingDevice(val address: String, val name: String)  // move here from androidMain
```

Make `RingConnDiscovery` (Task 5, androidMain) implement `RingDiscovery` and delete the duplicate `RingDevice` from androidMain. Add `AppViewModel` ctor param `ringDiscovery: RingDiscovery = NoopRingDiscovery`, and seed initial `HomeState.ringConnStepsEnabled`/`ringConnName` from prefs in `initialState()`.

> Interface adjustment: this promotes `RingDevice` and a `RingDiscovery` interface into commonMain.
> If the reviewer prefers, `RingDevice` can live in the same `RingStepsSource.kt` file. Keep one
> definition only (delete the androidMain `data class RingDevice`).

- [ ] **Step 5: Construct the real discovery** in `MainActivity.createAppViewModel`:

```kotlin
        ringDiscovery = RingConnDiscovery(context),
```

- [ ] **Step 6: Update every `HomeCallbacks(...)` construction site + `ScreenFakes.kt`** — add `onToggleRingConnSteps = viewModel::toggleRingConnSteps` (production) and `onToggleRingConnSteps = {}` (fixtures). Grep: `rg "onGrantHealth ="` to find all sites.

- [ ] **Step 7: Full gate + commit**

Run: `./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug`
Expected: all green.

```bash
git add -A
git commit -m "feat(ring): opt-in RingConn steps toggle on the Steps card

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: On-device verification (hardware gate)

**Files:** none (verification only). This is the real gate the btsnoop capture could not settle: that *our* GATT client, doing the proven handshake, reads live steps.

- [ ] **Step 1:** Build + install the debug APK (`.debug` suffix — never uninstall the release app):

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2:** On the phone, open the Steps card, grant Health access if needed, toggle **Read live steps from RingConn** on. Confirm the ring name appears (auto-selected from the single bonded `RingConn Gen2-962A`).

- [ ] **Step 3:** Note the current Health Connect step count. Wear the ring, walk ~30–60 s, then trigger a Steps refresh (activate the Steps face / wait for the interval). Confirm via logcat that a descriptor was read:

```bash
adb logcat -d | rg -i "ring|steps" | tail -40
```

- [ ] **Step 4:** Confirm the displayed count reflects the **max** of the ring's live count and Health Connect (fresher than Health Connect alone right after walking), and that the "Updated …" timestamp advanced.

- [ ] **Step 5:** Toggle the feature **off**; confirm the Steps card returns to pure Health Connect behavior and no ring connection is attempted (logcat quiet). Turn **off** the Developer Options "Bluetooth HCI snoop log" now that captures are done.

- [ ] **Step 6:** Record the outcome in the progress ledger. No commit (verification only).

---

## Compute Offload (claude-local)

Gated, self-contained new-file chunks to delegate to `claude-local` (ASCII-only briefs, full file
contents, trailing newline; run `claude-local-brief-check BRIEF.md` first; always `git diff`-verify;
output is a draft until the gate is green). Falls back to a paid subagent or controller if
claude-local is off/unreachable.

- **Task 1** — `RingConnDescriptor.kt` + `RingConnDescriptorTest.kt` (whole new files). Gate:
  `:app:testDebugUnitTest --tests "*RingConnDescriptorTest"` green + `:app:detekt`.
- **Task 2** — `Sm3.kt`, `RingAuth.kt`, `RingAuthTest.kt` (whole new files). Gate: the GB/T 32905
  KATs + the on-device vector in `RingAuthTest` + `:app:detekt`. The KATs make per-shot
  reliability irrelevant — ideal delegation.
- **Task 3** — `RingStepsSource.kt` + `FakeRingStepsSource.kt` (whole new files). Gate: compile.

Modify-existing and cross-file work (the `refreshSteps` merge, Prefs edits, VM/factory wiring, the
Android GATT class, the Steps-card UI, the `HomeCallbacks` fan-out) is controller/subagent-authored
— not claude-local's reliable shape. The Android GATT class (Task 5) needs judgment and is
gated only by compile + on-device, so keep it controller-authored. Text-out (commit bodies, PR
body) authored directly.

## Self-Review

**Spec coverage:** opt-in toggle (Task 6, Task 3 pref) ✓; connect-on-demand at refresh (Task 4/5) ✓;
`max` merge before `recordStepsFetch` (Task 4) ✓; silent fallback incl. background (Task 4 — ring
faults collapse to null via `getOrNull()`) ✓; bonded discovery, no scan/location (Task 5) ✓;
separate short-lived GATT, close in finally (Task 5) ✓; pure parser + auth with real test vectors
(Tasks 1–2) ✓; steps-only / no persistent connection / Gen2 / no HC writes (scope honored) ✓;
on-device verification (Task 7) ✓.

**Placeholder scan:** the SM3 KAT digests are real GB/T 32905 vectors; the descriptor/auth frames
are real captured bytes; no "TBD"/"handle edge cases" left. Two implementer-notes flag spots to
simplify (the `connectGatt` wrapper, the promoted `RingDevice`) — these are guidance, not gaps.

**Type consistency:** `readSteps(): Result<Long?>` used identically in Tasks 3/4/5;
`authCommand(challenge: Int, mac: ByteArray): ByteArray` consistent Tasks 2/5;
`parseSteps(frame: ByteArray): Long?` consistent Tasks 1/5; `RingDevice(address, name)` single
definition after Task 6 promotion; `ringSteps` param name consistent in controller + VM.

