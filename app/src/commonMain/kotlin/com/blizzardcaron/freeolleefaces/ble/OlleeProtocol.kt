package com.blizzardcaron.freeolleefaces.ble

// cohesive Ollee wire-protocol codec: one responsibility (frame build/parse/CRC) whose
// functions are tightly coupled to the byte layout and don't split into separate types cleanly
@Suppress("TooManyFunctions")
object OlleeProtocol {

    const val MAX_VALUE_LENGTH = 6

    /** Single-byte / ASCII bounds shared by several `require` guards below. */
    private const val ASCII_MAX = 127

    /** Minimum length (bytes) of a parseable frame: 00, len, AA, 55, crcHi, crcLo, cmd, target. */
    private const val MIN_FRAME_LENGTH = 8

    /** CRC-16/CCITT-FALSE parameters used by [crc16]. */
    private const val CRC16_INIT = 0xFFFF
    private const val CRC16_MASK = 0xFFFF
    private const val CRC16_HIGH_BIT = 0x8000
    private const val CRC16_POLY = 0x1021
    private const val CRC16_ROUNDS = 8

    /** Time math for the Timer-face header (see [buildTimerPacket]). */
    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
    private const val MAX_TIMER_DURATION_SEC = 359_999
    private const val TIMER_SLOT_COUNT = 10

    /** BLE field selectors (the second inner byte, after cmd 0x02). */
    const val TARGET_NAMEPLATE = 0x2f

    // Experimental: the Temperature face's field. The official app *reads* it at 0x2E
    // (response 0x4E, e.g. "  54 F"); whether *writing* 0x2E overrides what the face shows
    // is the open question this parameterization exists to test.
    // See docs/reference/ollee-ble-protocol.md.
    const val TARGET_TEMPERATURE = 0x2e

    // The weekday table. The watch's upper-left letter pair (the only BLE-writable text in the
    // upper panel) renders the current day's 2-char slot from this 7-entry table. The official
    // app writes it at 0x34 behind a 4-byte 00 00 7E 90 prefix. Foundation for a future custom
    // 2-char always-on label; no UI/face uses it yet.
    const val TARGET_WEEKDAYS = 0x34

    /** Timer-face slots (10 countdown durations) — write target. Ack at 0x46. */
    const val TARGET_TIMERS = 0x26

    /**
     * Header byte 3 of the 0x26 timer write: configure-only, or start now in single/interval mode.
     *
     * byte3 values verified on hardware 2026-06-13 (watch `panther`): `0x01` starts a **single**
     * countdown of the header MM:SS (quick timer); `0x02` starts the **interval** 10-slot sequence.
     * This is the REVERSE of the 2026-06-10 design doc's first guess — that capture mislabeled the
     * interval toggle, and the earlier `START_SINGLE=0x02` shipped FreeOllee sending interval frames
     * for the quick timer (the "starts the set in interval mode" bug).
     */
    enum class TimerStartMode(val byte3: Int) {
        SAVE(0x00), START_SINGLE(0x01), START_INTERVAL(0x02);

        companion object {
            /**
             * The official app exposes three *independent* controls — "Start timer from app",
             * "Interval timer mode", and "Send to watch" — that together select header byte 3:
             * start off → [SAVE]; start on + interval on → [START_INTERVAL]; start on + interval
             * off → [START_SINGLE].
             */
            fun of(startFromApp: Boolean, intervalMode: Boolean): TimerStartMode = when {
                !startFromApp -> SAVE
                intervalMode -> START_INTERVAL
                else -> START_SINGLE
            }
        }
    }

    /** Read-request targets and the reply-target shift (`02 <t>` → reply target `t + 0x20`). */
    const val TARGET_GET_TIMER = 0x2C
    const val RESPONSE_TARGET_OFFSET = 0x20

    /** Firmware / version read (drives image selection); its 0x4A reply also carries battery voltage. */
    const val TARGET_VERSION = 0x2a

    /** Config-register read/write (settings bitmask + autosleep period). Reply at 0x52. */
    const val TARGET_GET_CONFIG = 0x32
    const val TARGET_SET_CONFIG = 0x33

    // Layout of the 0x52 config payload — confirmed on-device 2026-06-18 (see Task 1 note).
    private const val CONFIG_BITMASK_OFFSET = 0 // 4-byte big-endian settings word
    private const val CONFIG_PERIOD_OFFSET = 4 // autosleep_period, 4-byte big-endian uint32 (seconds)
    private const val CONFIG_PERIOD_WIDTH = 4
    private const val CONFIG_MIN_PAYLOAD = CONFIG_PERIOD_OFFSET + CONFIG_PERIOD_WIDTH // 8

    /** Bit position within the big-endian bitmask word. */
    const val CONFIG_BIT_AUTOSLEEP = 6

    /** Allowed autosleep_period values (seconds), from the official app's picker. */
    val CONFIG_PERIOD_VALUES_SEC = listOf(5, 10, 30, 60, 120)

    /**
     * Parsed config register. Holds the raw 0x52 payload so a write is a true read-modify-write:
     * [withAutoSleep] mutates only the auto-sleep bit and the period bytes, leaving every other
     * byte (DND, gestures, pedometer, BLE-continuous, …) byte-for-byte intact.
     */
    class WatchConfig(val raw: ByteArray) {
        private fun maskWord(): Int =
            ((raw[CONFIG_BITMASK_OFFSET].toInt() and 0xFF) shl 24) or
                ((raw[CONFIG_BITMASK_OFFSET + 1].toInt() and 0xFF) shl 16) or
                ((raw[CONFIG_BITMASK_OFFSET + 2].toInt() and 0xFF) shl 8) or
                (raw[CONFIG_BITMASK_OFFSET + 3].toInt() and 0xFF)

        private fun bit(i: Int): Boolean = (maskWord() ushr i) and 1 == 1

        val autoSleepOn: Boolean get() = bit(CONFIG_BIT_AUTOSLEEP)
        val periodSec: Int get() =
            ((raw[CONFIG_PERIOD_OFFSET].toInt() and 0xFF) shl 24) or
                ((raw[CONFIG_PERIOD_OFFSET + 1].toInt() and 0xFF) shl 16) or
                ((raw[CONFIG_PERIOD_OFFSET + 2].toInt() and 0xFF) shl 8) or
                (raw[CONFIG_PERIOD_OFFSET + 3].toInt() and 0xFF)

        fun withAutoSleep(on: Boolean, periodSec: Int): WatchConfig {
            require(!on || periodSec in CONFIG_PERIOD_VALUES_SEC) {
                "autosleep period must be one of $CONFIG_PERIOD_VALUES_SEC when enabling auto-sleep (got $periodSec)"
            }
            val copy = raw.copyOf()
            var word = maskWord()
            word = if (on) {
                word or (1 shl CONFIG_BIT_AUTOSLEEP)
            } else {
                word and (1 shl CONFIG_BIT_AUTOSLEEP).inv()
            }
            copy[CONFIG_BITMASK_OFFSET] = ((word ushr 24) and 0xFF).toByte()
            copy[CONFIG_BITMASK_OFFSET + 1] = ((word ushr 16) and 0xFF).toByte()
            copy[CONFIG_BITMASK_OFFSET + 2] = ((word ushr 8) and 0xFF).toByte()
            copy[CONFIG_BITMASK_OFFSET + 3] = (word and 0xFF).toByte()
            copy[CONFIG_PERIOD_OFFSET] = ((periodSec ushr 24) and 0xFF).toByte()
            copy[CONFIG_PERIOD_OFFSET + 1] = ((periodSec ushr 16) and 0xFF).toByte()
            copy[CONFIG_PERIOD_OFFSET + 2] = ((periodSec ushr 8) and 0xFF).toByte()
            copy[CONFIG_PERIOD_OFFSET + 3] = (periodSec and 0xFF).toByte()
            return WatchConfig(copy)
        }
    }

    /** Parses a 0x52 config reply into a [WatchConfig], or null if target/CRC/length is wrong. */
    fun parseConfig(frame: Frame): WatchConfig? {
        val isValid = frame.crcOk &&
            frame.target == TARGET_GET_CONFIG + RESPONSE_TARGET_OFFSET &&
            frame.payload.size >= CONFIG_MIN_PAYLOAD
        return if (isValid) WatchConfig(frame.payload) else null
    }

    /** Builds the 0x33 config write from a (read-modified) [config]. */
    fun buildConfigPacket(config: WatchConfig): ByteArray =
        buildRawPacket(TARGET_SET_CONFIG, config.raw)

    fun crc16(data: ByteArray): Int {
        var crc = CRC16_INIT
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(CRC16_ROUNDS) {
                crc = if ((crc and CRC16_HIGH_BIT) != 0) {
                    (crc shl 1) xor CRC16_POLY
                } else {
                    crc shl 1
                }
                crc = crc and CRC16_MASK
            }
        }
        return crc
    }

    /** Builds a nameplate (0x2F) packet — the original FreeOllee behaviour. */
    fun buildPacket(value: String): ByteArray = buildPacket(TARGET_NAMEPLATE, value)

    /**
     * Builds a framed packet writing [value] to an arbitrary [target] field.
     * Framing: 00, len, AA, 55, crcHi, crcLo, 0x02, target, value… (len = inner.size + 4),
     * CRC-16/CCITT-FALSE over the inner bytes.
     */
    fun buildPacket(target: Int, value: String): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }
        require(value.length <= MAX_VALUE_LENGTH) {
            "value must be <= $MAX_VALUE_LENGTH chars (got ${value.length})"
        }
        require(value.all { it.code in 0..ASCII_MAX }) {
            "value must be ASCII (got '$value')"
        }

        return buildRawPacket(target, value.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Builds a framed packet writing arbitrary raw [payload] bytes to [target]. Unlike
     * [buildPacket] this imposes no ASCII or 6-char limit, so it can carry binary-prefixed
     * fields like the weekday table (0x34). Framing/CRC are identical.
     */
    fun buildRawPacket(target: Int, payload: ByteArray): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }

        val inner = byteArrayOf(0x02, target.toByte()) + payload
        // LEN is a single byte the firmware uses to reassemble fragments; guard against a payload
        // large enough to truncate it (no current caller approaches this).
        require(inner.size + 4 <= 0xFF) { "payload too large for single-byte LEN (inner ${inner.size})" }
        val crc = crc16(inner)

        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    /** The `02 <target>` read-request frame (no payload). Reply arrives with target `+0x20`. */
    fun readRequest(target: Int): ByteArray = buildRawPacket(target, ByteArray(0))

    /** Byte width of the weekday-register header (the World Time offset — see WorldTimeCodec). */
    const val WEEKDAY_HEADER_SIZE = 4

    /**
     * Builds the weekday-table write (0x34). [slots] must be 7 entries of exactly 2 ASCII chars,
     * in Mon..Sun order (captured default: `MO TU WE TH FR SA SU`). The firmware shows the slot
     * matching the current date in the upper-left letter pair. Pass all-identical slots (e.g.
     * `List(7){"TE"}`) to make the panel show a fixed 2-char label regardless of weekday.
     *
     * [header] is the register's 4-byte prefix — the World Time offset (WorldTimeCodec). Every
     * caller must pass the app-computed value (WorldTimeHeader.fromPrefs); replaying the captured
     * constant `00 00 7E 90` is the issue-#34 bug that reset World Time to +9:00 on every push.
     */
    fun buildWeekdayPacket(slots: List<String>, header: ByteArray): ByteArray {
        require(header.size == WEEKDAY_HEADER_SIZE) {
            "weekday header must be $WEEKDAY_HEADER_SIZE bytes (got ${header.size})"
        }
        require(slots.size == 7) { "weekday table needs 7 slots (got ${slots.size})" }
        require(slots.all { it.length == 2 && it.all { c -> c.code in 0..ASCII_MAX } }) {
            "each slot must be exactly 2 ASCII chars (got $slots)"
        }
        val payload = header + slots.joinToString("").toByteArray(Charsets.US_ASCII)
        return buildRawPacket(TARGET_WEEKDAYS, payload)
    }

    /**
     * Builds the Timer-slots write (0x26). [durationsSeconds] must be exactly 10 entries, each a
     * countdown length in seconds (0 = blank slot). Emits a 4-byte header followed by ten
     * little-endian uint32 durations, then delegates to [buildRawPacket]. Per-slot labels are
     * phone-side only and never sent.
     *
     * The header is `[HH, MM, SS, byte3]` where HH:MM:SS seeds the Timer face's **Quick-timer**
     * (the standalone countdown shown before scrolling into the ten slots) from [headerSeconds] —
     * independent of the ten slot durations. A 2026-06-10 BLE capture confirmed the header HH:MM:SS
     * is a separate "Quick timer" value, not derived from slot 1. Byte 0 = hours decoded from a
     * 2026-06-15 capture of the official app sending 20h05m00s (`14 05 00 01`); the hours byte is
     * clamped to `0xFF` only for values beyond ~255h. [startMode] drives byte 3: SAVE (0x00)
     * persists the table without starting, START_SINGLE (0x01) starts a single countdown of the
     * header immediately, START_INTERVAL (0x02) starts the 10-slot interval sequence immediately
     * (byte3 values verified on hardware — see [TimerStartMode]).
     */
    fun buildTimerPacket(
        durationsSeconds: List<Int>,
        headerSeconds: Int,
        startMode: TimerStartMode = TimerStartMode.SAVE,
    ): ByteArray {
        require(durationsSeconds.size == TIMER_SLOT_COUNT) {
            "timer table needs exactly $TIMER_SLOT_COUNT slots (got ${durationsSeconds.size})"
        }
        require(durationsSeconds.all { it in 0..MAX_TIMER_DURATION_SEC }) {
            "each duration must be 0..$MAX_TIMER_DURATION_SEC s (got $durationsSeconds)"
        }
        require(headerSeconds >= 0) { "headerSeconds must be >= 0 (got $headerSeconds)" }
        val payload = ByteArray(4 + TIMER_SLOT_COUNT * 4) // 4-byte header + 10 LE-uint32 words
        payload[0] = (headerSeconds / SECONDS_PER_HOUR).coerceAtMost(0xFF).toByte() // HH (clamp for safety)
        payload[1] = ((headerSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toByte() // MM (0-59)
        payload[2] = (headerSeconds % SECONDS_PER_MINUTE).toByte() // SS (0-59)
        payload[3] = startMode.byte3.toByte() // start/mode selector
        durationsSeconds.forEachIndexed { i, s ->
            val off = 4 + i * 4
            payload[off] = (s and 0xFF).toByte()
            payload[off + 1] = ((s shr 8) and 0xFF).toByte()
            payload[off + 2] = ((s shr 16) and 0xFF).toByte()
            payload[off + 3] = ((s shr 24) and 0xFF).toByte()
        }
        return buildRawPacket(TARGET_TIMERS, payload)
    }

    /** °F string matching the watch's read format ("  54 F"): right-justified to 6 chars. */
    fun formatTemperatureF(tempF: Int): String = "%4d F".format(tempF)

    /** A parsed frame. [crcOk] reports whether the CRC validated over the inner bytes. */
    data class Frame(val cmd: Int, val target: Int, val payload: ByteArray, val crcOk: Boolean)

    /**
     * Parses a complete framed message (00, len, AA, 55, crcHi, crcLo, cmd, target, payload…).
     * Returns null if it is too short or lacks the AA 55 magic. The CRC is checked over the inner
     * bytes (cmd, target, payload) and surfaced as [Frame.crcOk] rather than rejected, so callers
     * can inspect malformed/partial frames during reverse-engineering. Use to decode watch
     * responses, e.g. a temperature read-back at target 0x4E with payload "  54 F".
     */
    fun parseFrame(bytes: ByteArray): Frame? {
        val hasValidHeader = bytes.size >= MIN_FRAME_LENGTH &&
            bytes[2] == 0xAA.toByte() && bytes[3] == 0x55.toByte()
        if (!hasValidHeader) return null
        val crc = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        val inner = bytes.copyOfRange(6, bytes.size)
        val cmd = inner[0].toInt() and 0xFF
        val target = inner[1].toInt() and 0xFF
        val payload = inner.copyOfRange(2, inner.size)
        return Frame(cmd, target, payload, crc16(inner) == crc)
    }
}
