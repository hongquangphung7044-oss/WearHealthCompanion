package com.wearhealth.companion.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Pure parser for 128-bit service UUIDs in legacy BLE advertising data. */
object BleAdvertisementMatcher {
    fun contains128BitServiceUuid(advertisement: ByteArray, expected: UUID): Boolean {
        val expectedLittleEndian = ByteBuffer.allocate(UUID_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(expected.leastSignificantBits)
            .putLong(expected.mostSignificantBits)
            .array()

        var offset = 0
        while (offset < advertisement.size) {
            val fieldLength = advertisement[offset].toInt() and 0xff
            if (fieldLength == 0) break
            val fieldEnd = offset + 1 + fieldLength
            if (fieldEnd > advertisement.size || fieldLength < 1) return false

            val type = advertisement[offset + 1].toInt() and 0xff
            if (type == AD_TYPE_INCOMPLETE_128_BIT_UUIDS || type == AD_TYPE_COMPLETE_128_BIT_UUIDS) {
                var uuidOffset = offset + 2
                while (uuidOffset + UUID_BYTES <= fieldEnd) {
                    var matches = true
                    for (index in 0 until UUID_BYTES) {
                        if (advertisement[uuidOffset + index] != expectedLittleEndian[index]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) return true
                    uuidOffset += UUID_BYTES
                }
            }
            offset = fieldEnd
        }
        return false
    }

    private const val UUID_BYTES = 16
    private const val AD_TYPE_INCOMPLETE_128_BIT_UUIDS = 0x06
    private const val AD_TYPE_COMPLETE_128_BIT_UUIDS = 0x07
}
