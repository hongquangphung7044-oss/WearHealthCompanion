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
        for (mtu in 0..BleSyncProtocol.PREFERRED_MTU) {
            val payloadBytes = BleSyncProtocol.chunkPayloadBytesForMtu(mtu)
            assertTrue(payloadBytes >= 0)
            if (payloadBytes > 0) {
                assertTrue(BleSyncProtocol.chunkFrame(0, ByteArray(payloadBytes)).size <= mtu - 3)
            }
        }
        assertEquals(180, BleSyncProtocol.chunkPayloadBytesForMtu(BleSyncProtocol.MIN_ECG_MTU))
        assertEquals(239, BleSyncProtocol.chunkPayloadBytesForMtu(BleSyncProtocol.PREFERRED_MTU))
    }

    @Test
    fun measurementBinaryRoundTripPreservesMetadataAndWaveforms() {
        assertEquals(transfer, BleMeasurementCodec.decode(BleMeasurementCodec.encode(transfer)))
        assertEquals(emptyList<Int>(), EcgBinaryCodec.decode(EcgBinaryCodec.encode(emptyList())))
        assertEquals(transfer.rawEcgData, EcgBinaryCodec.decode(EcgBinaryCodec.encode(transfer.rawEcgData)))
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
