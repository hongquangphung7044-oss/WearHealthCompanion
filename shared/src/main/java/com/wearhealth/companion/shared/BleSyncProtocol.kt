package com.wearhealth.companion.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * GMS-free BLE GATT protocol for a directly paired phone and watch.
 *
 * The phone advertises [SERVICE_UUID] while the ECG synchronizer is open. The watch scans for
 * that service, sends one framed [EcgMeasurementTransfer], and only marks its local history item
 * as synced after receiving a persistence ACK notification from the phone.
 *
 * This is deliberately independent of Google Wearable Data Layer so it can be used on devices
 * where Google Play services are absent or unavailable.
 */
object BleSyncProtocol {
    const val VERSION: Byte = 1

    val SERVICE_UUID: UUID = UUID.fromString("9a4b7d00-2b58-4b02-8bb8-6f15f03e2a01")
    val UPLOAD_UUID: UUID = UUID.fromString("9a4b7d01-2b58-4b02-8bb8-6f15f03e2a01")
    val ACK_UUID: UUID = UUID.fromString("9a4b7d03-2b58-4b02-8bb8-6f15f03e2a01")
    val API_KEY_UUID: UUID = UUID.fromString("9a4b7d02-2b58-4b02-8bb8-6f15f03e2a01")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val TYPE_BEGIN: Byte = 1
    const val TYPE_CHUNK: Byte = 2
    const val TYPE_END: Byte = 3
    const val TYPE_ACK: Byte = 4

    const val ACK_SUCCESS: Byte = 0
    const val ACK_FAILURE: Byte = 1

    /** Payload bytes inside a GATT write after 3-byte ATT and 5-byte protocol overhead. */
    fun chunkPayloadBytesForMtu(mtu: Int): Int = (mtu - 3 - 1 - Int.SIZE_BYTES).coerceAtLeast(0)
    const val MIN_ECG_MTU = 188
    const val PREFERRED_MTU = 247
    const val MAX_TRANSFER_BYTES = 1_000_000

    /** BEGIN = type + version + payload length + CRC-32. */
    fun beginFrame(payload: ByteArray): ByteArray = ByteBuffer
        .allocate(1 + 1 + Int.SIZE_BYTES + Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(TYPE_BEGIN)
        .put(VERSION)
        .putInt(payload.size)
        .putLong(crc32(payload))
        .array()

    /** CHUNK = type + zero-based sequence + bytes. */
    fun chunkFrame(sequence: Int, bytes: ByteArray): ByteArray = ByteBuffer
        .allocate(1 + Int.SIZE_BYTES + bytes.size)
        .order(ByteOrder.BIG_ENDIAN)
        .put(TYPE_CHUNK)
        .putInt(sequence)
        .put(bytes)
        .array()

    /** END = type + expected number of chunks. */
    fun endFrame(chunkCount: Int): ByteArray = ByteBuffer
        .allocate(1 + Int.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(TYPE_END)
        .putInt(chunkCount)
        .array()

    /** ACK = type + result + original measurement timestamp. */
    fun ackFrame(success: Boolean, timestamp: Long): ByteArray = ByteBuffer
        .allocate(1 + 1 + Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(TYPE_ACK)
        .put(if (success) ACK_SUCCESS else ACK_FAILURE)
        .putLong(timestamp)
        .array()

    fun parseAck(frame: ByteArray): BleAck? {
        return try {
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            if (buffer.remaining() != 1 + 1 + Long.SIZE_BYTES || buffer.get() != TYPE_ACK) {
                return null
            }
            val result = buffer.get()
            if (result != ACK_SUCCESS && result != ACK_FAILURE) return null
            BleAck(success = result == ACK_SUCCESS, timestamp = buffer.long)
        } catch (_: Exception) {
            null
        }
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}

data class BleAck(val success: Boolean, val timestamp: Long)

/**
 * Binary payload used inside BLE frames.
 *
 * Metadata deliberately reuses [MeasurementSerializer]'s JSON representation. Waveforms remain
 * binary (four bytes per sample) so BLE never has to transmit a large JSON integer array.
 */
object BleMeasurementCodec {
    private const val MAGIC = 0x57484331 // "WHC1"
    private const val HEADER_BYTES = Int.SIZE_BYTES + 1 + Int.SIZE_BYTES * 3

    fun encode(transfer: EcgMeasurementTransfer): ByteArray {
        val metadata = MeasurementSerializer.toJson(transfer).toByteArray(Charsets.UTF_8)
        val raw = EcgBinaryCodec.encode(transfer.rawEcgData)
        val thumbnail = EcgBinaryCodec.encode(transfer.downsampledEcg)
        val total = HEADER_BYTES + metadata.size + raw.size + thumbnail.size
        require(total <= BleSyncProtocol.MAX_TRANSFER_BYTES) { "ECG BLE payload is too large: $total" }

        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(BleSyncProtocol.VERSION)
            .putInt(metadata.size)
            .putInt(raw.size)
            .putInt(thumbnail.size)
            .put(metadata)
            .put(raw)
            .put(thumbnail)
            .array()
    }

    fun decode(bytes: ByteArray): EcgMeasurementTransfer {
        require(bytes.size in HEADER_BYTES..BleSyncProtocol.MAX_TRANSFER_BYTES) { "Invalid BLE payload size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Unknown BLE payload" }
        require(buffer.get() == BleSyncProtocol.VERSION) { "Unsupported BLE payload version" }

        val metadataSize = buffer.int
        val rawSize = buffer.int
        val thumbnailSize = buffer.int
        require(metadataSize >= 0 && rawSize >= 0 && thumbnailSize >= 0) { "Negative BLE payload length" }
        require(rawSize % Int.SIZE_BYTES == 0 && thumbnailSize % Int.SIZE_BYTES == 0) {
            "Invalid ECG binary length"
        }
        require(metadataSize + rawSize + thumbnailSize == buffer.remaining()) { "Truncated BLE payload" }

        val metadata = ByteArray(metadataSize).also(buffer::get)
        val raw = ByteArray(rawSize).also(buffer::get)
        val thumbnail = ByteArray(thumbnailSize).also(buffer::get)
        return MeasurementSerializer.fromJson(metadata.toString(Charsets.UTF_8)).copy(
            rawEcgData = EcgBinaryCodec.decode(raw),
            downsampledEcg = EcgBinaryCodec.decode(thumbnail),
        )
    }
}
