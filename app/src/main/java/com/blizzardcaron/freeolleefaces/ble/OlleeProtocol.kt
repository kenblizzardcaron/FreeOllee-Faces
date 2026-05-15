package com.blizzardcaron.freeolleefaces.ble

object OlleeProtocol {

    const val MAX_VALUE_LENGTH = 6

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0)
                    (crc shl 1) xor 0x1021
                else
                    crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun buildPacket(value: String): ByteArray {
        require(value.length <= MAX_VALUE_LENGTH) {
            "value must be <= $MAX_VALUE_LENGTH chars (got ${value.length})"
        }
        require(value.all { it.code in 0..127 }) {
            "value must be ASCII (got '$value')"
        }

        val inner = byteArrayOf(0x02, 0x2f) + value.toByteArray(Charsets.US_ASCII)
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
}
