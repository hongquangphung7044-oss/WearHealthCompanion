package com.wearhealth.companion.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleSyncProtocolTest {
    private val transfer = EcgMeasurementTransfer(
        timestamp = 1_720_000_000_123L,
        diagnosis = listOf("SN", "VPB"),
        avgHeartRate = 71,
        minHeartRate = 58,
        maxHeartRate = 104,
        signalQuality = 0.93,
        isAbnormal = true,
        avgQrs = 92,
        prInterval = 148,
        avgQt = 390,
        avgQtc = 421,
        pacCount = 2,
        pvcCount = 1,
        rawEcgData = listOf(-120, 0, 450, Int.MIN_VALUE, Int.MAX_VALUE),
        downsampledEcg = listOf(-10, 20, 30),
        sampleRate = 500,
    )

    @Test
    fun ackRoundTripAndMalformedFrames() {
        val success = BleSyncProtocol.parseAck(BleSyncProtocol.ackFrame(true, transfer.timestamp))
        assertEquals(BleAck(true, transfer.timestamp), success)
        assertEquals(BleAck(false, transfer.timestamp), BleSyncProtocol.parseAck(
            BleSyncProtocol.ackFrame(false, transfer.timestamp),
        ))
        assertNull(BleSyncProtocol.parseAck(byteArrayOf()))
        assertNull(BleSyncProtocol.parseAck(byteArrayOf(BleSyncProtocol.TYPE_ACK)))
        assertNull(BleSyncProtocol.parseAck(
            BleSyncProtocol.ackFrame(true, transfer.timestamp).also { it[1] = 99 },
        ))
    }

    @Test
    fun beginChunkEndFramesContainLengthCrcAndSequence() {
        val payload = ByteArray(513) { (it * 31).toByte() }
        val begin = ByteBuffer.wrap(BleSyncProtocol.beginFrame(payload)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(BleSyncProtocol.TYPE_BEGIN, begin.get())
        assertEquals(BleSyncProtocol.VERSION, begin.get())
        assertEquals(payload.size, begin.int)
        assertEquals(BleSyncProtocol.crc32(payload), begin.long)
        assertFalse(begin.hasRemaining())

        val chunkBytes = byteArrayOf(3, 4, 5)
        val chunk = ByteBuffer.wrap(BleSyncProtocol.chunkFrame(7, chunkBytes)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(BleSyncProtocol.TYPE_CHUNK, chunk.get())
        assertEquals(7, chunk.int)
        assertArrayEquals(chunkBytes, ByteArray(chunk.remaining()).also(chunk::get))

        val end = ByteBuffer.wrap(BleSyncProtocol.endFrame(9)).order(ByteOrder.BIG_ENDIAN)
        assertEquals(BleSyncProtocol.TYPE_END, end.get())
        assertEquals(9, end.int)
        assertFalse(end.hasRemaining())
    }

    @Test
    fun mtuCalculationNeverProducesOversizeWrite() {
        for (mtu in 0..1_024) {
            val payloadBytes = BleSyncProtocol.chunkPayloadBytesForMtu(mtu)
            assertTrue(payloadBytes >= 0)
            if (payloadBytes > 0) {
                val frameSize = BleSyncProtocol.chunkFrame(0, ByteArray(payloadBytes)).size
                assertTrue(frameSize <= (mtu - 3).coerceAtLeast(0))
                assertTrue(frameSize <= BleSyncProtocol.MAX_ATTRIBUTE_VALUE_BYTES)
            }
        }
        assertEquals(180, BleSyncProtocol.chunkPayloadBytesForMtu(BleSyncProtocol.MIN_ECG_MTU))
        assertEquals(239, BleSyncProtocol.chunkPayloadBytesForMtu(BleSyncProtocol.PREFERRED_MTU))
        // Android 14 reports MTU 517 regardless of the smaller requested value. The resulting
        // complete GATT attribute value must stay at 512 bytes, leaving 507 bytes after our header.
        assertEquals(507, BleSyncProtocol.chunkPayloadBytesForMtu(517))
        assertEquals(
            BleSyncProtocol.MAX_ATTRIBUTE_VALUE_BYTES,
            BleSyncProtocol.chunkFrame(
                0,
                ByteArray(BleSyncProtocol.chunkPayloadBytesForMtu(517)),
            ).size,
        )
    }

    @Test
    fun measurementBinaryRoundTripPreservesMetadataAndWaveforms() {
        assertEquals(transfer, BleMeasurementCodec.decode(BleMeasurementCodec.encode(transfer)))
        assertEquals(emptyList<Int>(), EcgBinaryCodec.decode(EcgBinaryCodec.encode(emptyList())))
        assertEquals(transfer.rawEcgData, EcgBinaryCodec.decode(EcgBinaryCodec.encode(transfer.rawEcgData)))
    }

    @Test
    fun rawAdvertisementParserFindsComplete128BitServiceUuid() {
        val uuidLittleEndian = byteArrayOf(
            0x01, 0x2a, 0x3e.toByte(), 0xf0.toByte(), 0x15, 0x6f, 0xb8.toByte(), 0x8b.toByte(),
            0x02, 0x4b, 0x58, 0x2b, 0x00, 0x7d, 0x4b, 0x9a.toByte(),
        )
        val advertisement = byteArrayOf(2, 0x01, 0x06, 17, 0x07) + uuidLittleEndian + byteArrayOf(0)
        assertTrue(BleAdvertisementMatcher.contains128BitServiceUuid(
            advertisement,
            BleSyncProtocol.SERVICE_UUID,
        ))
        assertFalse(BleAdvertisementMatcher.contains128BitServiceUuid(
            advertisement,
            BleSyncProtocol.ACK_UUID,
        ))
    }

    @Test
    fun rawAdvertisementParserRejectsTruncatedField() {
        val truncated = byteArrayOf(17, 0x07, 0x01, 0x02)
        assertFalse(BleAdvertisementMatcher.contains128BitServiceUuid(
            truncated,
            BleSyncProtocol.SERVICE_UUID,
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun measurementDecoderRejectsTrailingData() {
        BleMeasurementCodec.decode(BleMeasurementCodec.encode(transfer) + 0x01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun measurementDecoderRejectsCorruptWaveformLength() {
        val bytes = BleMeasurementCodec.encode(transfer)
        // Header: magic(4), version(1), metadata length(4), raw length(4), thumbnail length(4).
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(9, 3)
        BleMeasurementCodec.decode(bytes)
    }
}
