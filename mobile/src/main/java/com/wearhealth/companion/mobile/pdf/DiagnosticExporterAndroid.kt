package com.wearhealth.companion.mobile.pdf

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wearhealth.companion.shared.DiagnosticExporter
import com.wearhealth.companion.shared.EcgMeasurementTransfer

/**
 * Android 端写入 Downloads 的薄封装：复用 [DiagnosticExporter.buildText] 生成纯文本，
 * 通过 MediaStore 写入 Download/WearHealthCompanion 目录（API 29+）。
 *
 * API 26-28 兼容：调用方通过 SAF 选择保存位置，用 [exportToUri] 写入。
 *
 * 设计：纯文本逻辑在 shared 模块（可单测），Android I/O 在本类（靠集成验证）。
 */
object DiagnosticExporterAndroid {

    private const val DOWNLOAD_SUBDIRECTORY = "WearHealthCompanion"

    /** API 29+: 直接发布到共享 Download/WearHealthCompanion。 */
    fun exportToDownloads(context: Context, transfer: EcgMeasurementTransfer): String {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android 8–9 请先选择保存位置"
        }
        val name = DiagnosticExporter.displayName(transfer.timestamp)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIRECTORY"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("系统无法在 Download 中创建诊断包文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(DiagnosticExporter.buildText(transfer).toByteArray(Charsets.UTF_8))
            } ?: error("系统无法打开诊断包输出流")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, published, null, null) == 1) { "诊断包已写入但无法发布到 Download" }
            return "$relativePath/$name"
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** API 26–28 兼容：写入 SAF 选择的位置。 */
    fun exportToUri(context: Context, transfer: EcgMeasurementTransfer, uri: Uri): String {
        val name = DiagnosticExporter.displayName(transfer.timestamp)
        context.contentResolver.openOutputStream(uri, "w")?.use { out ->
            out.write(DiagnosticExporter.buildText(transfer).toByteArray(Charsets.UTF_8))
        } ?: error("系统无法打开所选保存位置")
        return "所选文件位置/$name"
    }
}
