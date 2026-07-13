package com.wearhealth.companion.mobile.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Small, GMS-free BLE GATT protocol shared by watch and phone. */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("9a4b7d00-2b58-4b02-8bb8-6f15f03e2a01")
    val UPLOAD_UUID: UUID = UUID.fromString("9a4b7d01-2b58-4b02-8bb8-6f15f03e2a01")
    val API_KEY_UUID: UUID = UUID.fromString("9a4b7d02-2b58-4b02-8bb8-6f15f03e2a01")
    const val TYPE_BEGIN: Byte = 1
    const val TYPE_CHUNK: Byte = 2
    const val TYPE_END: Byte = 3
    const val TYPE_ACK: Byte = 4
    const val CHUNK_SIZE = 160

    fun uuidBytes(id: String): ByteArray {
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }
    fun uuidFrom(bytes: ByteArray): String = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        .let { UUID(it.long, it.long).toString() }
}
