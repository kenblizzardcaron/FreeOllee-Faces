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
    private const val MIN_MAC_LEN = 6 // Standard Bluetooth MAC address length

    /** Build the 6-byte auth command answering [challenge], keyed by the ring's [mac] (>= 6 bytes). */
    fun authCommand(challenge: Int, mac: ByteArray): ByteArray {
        require(mac.size >= MIN_MAC_LEN) { "mac must be at least $MIN_MAC_LEN bytes" }
        val xorMac = mac[mac.size - RESP_BYTES].toInt() xor mac[mac.size - RESP_BYTES + 1].toInt() xor
            mac[mac.size - 1].toInt()
        val v = xorMac and BYTE_MASK
        val digest = Sm3.digest(byteArrayOf(v.toByte(), (challenge and BYTE_MASK).toByte()))
        val r = digest.copyOfRange(DIGEST_LEN - RESP_BYTES, DIGEST_LEN)
        return byteArrayOf(CMD.toByte(), SUB_AUTH.toByte(), r[0], r[1], r[2], TRAILER.toByte())
    }
}
