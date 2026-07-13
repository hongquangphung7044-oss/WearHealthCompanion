package com.wearhealth.companion.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Private, compressed full-resolution ECG archive. UI continues to use HistoryItem.ecgSamples. */
class EcgRawArchive(private val context: Context) {
    private val directory get() = context.getDir("ecg_raw", Context.MODE_PRIVATE)
    fun save(recordId: String, samples: List<Int>) {
        DataOutputStream(GZIPOutputStream(directory.resolve("$recordId.ecgz").outputStream())).use { out ->
            out.writeInt(samples.size); samples.forEach(out::writeInt)
        }
    }
    fun readCompressed(recordId: String): ByteArray? = directory.resolve("$recordId.ecgz").takeIf { it.exists() }?.readBytes()
    fun read(recordId: String): List<Int>? = try {
        DataInputStream(GZIPInputStream(directory.resolve("$recordId.ecgz").inputStream())).use { input ->
            List(input.readInt()) { input.readInt() }
        }
    } catch (_: Exception) { null }
    fun delete(recordId: String) { directory.resolve("$recordId.ecgz").delete() }
    fun deleteAll() { directory.listFiles()?.forEach { it.delete() } }
}
