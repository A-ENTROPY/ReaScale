package io.reascale.app.core.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.OutputFormat
import io.reascale.app.core.encode.QualityMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 输出到系统相册 / 用户选择的 SAF 目录
 * 对应 §19.6 输出位置
 *
 * - [outputDirUri] 非空：写入用户通过 SAF 选择的目录（设置页"输出目录"）
 * - 为空：Android 10+ 写 MediaStore Pictures/ReaScale/（免权限）；
 *   Android 9 及以下写 filesDir/exports 并通知 MediaScanner
 *
 * [FIX 2026-08-17] 设置页的"输出目录"此前只有字段没有实现，这里补全 SAF 写入；
 * 另：HEIC/HEIF/AVIF/JXL 系统编码器未实现，回退到 JPEG，避免任务静默失败。
 *
 * 返回 Uri（成功）或 null（失败）
 */
object MediaStoreWriter {

    private const val ALBUM_NAME = "ReaScale"

    /**
     * 把 bitmap 编码并写入目标位置
     *
     * @param displayName 用户可见文件名（不含后缀）
     * @param outputDirUri 用户选择的 SAF 目录 Uri（tree uri），为空走默认相册
     * @return 写入后的 Uri，失败返回 null
     */
    suspend fun write(
        context: Context,
        bitmap: Bitmap,
        options: EncodeOptions,
        displayName: String,
        outputDirUri: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        // [FIX 2026-08-17] HEIC/AVIF/JXL 系统编码器未实现 → 统一回退 JPEG（文件名/扩展名一致）
        val effective = if (QualityMapper.toBitmapCompressFormat(options.format) == null) {
            options.copy(format = OutputFormat.JPEG)
        } else options
        if (!outputDirUri.isNullOrBlank()) {
            return@withContext writeToSaf(context, bitmap, effective, displayName, outputDirUri)
        }
        writeToMediaStore(context, bitmap, effective, displayName)
    }

    /** 写入用户选择的 SAF 目录 */
    private fun writeToSaf(
        context: Context,
        bitmap: Bitmap,
        options: EncodeOptions,
        displayName: String,
        treeUri: String
    ): Uri? {
        return runCatching {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
            val ext = options.format.name.lowercase()
            val file = tree.createFile(mimeFor(options.format), "$displayName.$ext")
                ?: return null
            val ok = context.contentResolver.openOutputStream(file.uri)?.use { out ->
                writeBitmap(bitmap, options, out)
            } ?: false
            if (!ok) {
                file.delete()
                return null
            }
            file.uri
        }.getOrNull()
    }

    /** 写入系统相册（原逻辑） */
    private fun writeToMediaStore(
        context: Context,
        bitmap: Bitmap,
        options: EncodeOptions,
        displayName: String
    ): Uri? {
        val ext = options.format.name.lowercase()
        val mime = mimeFor(options.format)
        val fileName = "$displayName.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 走 MediaStore
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    writeBitmap(bitmap, options, out)
                } ?: return null
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            // Android 9 及以下：写 filesDir/exports
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM_NAME)
                .apply { mkdirs() }
            val outFile = File(dir, fileName)
            return try {
                FileOutputStream(outFile).use { out ->
                    writeBitmap(bitmap, options, out)
                }
                // 通知系统相册刷新
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun writeBitmap(bitmap: Bitmap, options: EncodeOptions, out: java.io.OutputStream): Boolean {
        val compressFormat = QualityMapper.toBitmapCompressFormat(options.format)
            ?: return false
        val quality = QualityMapper.directQuality(options)
        return bitmap.compress(compressFormat, quality, out)
    }

    private fun mimeFor(format: OutputFormat): String = when (format) {
        OutputFormat.JPEG -> "image/jpeg"
        OutputFormat.PNG  -> "image/png"
        OutputFormat.WEBP -> "image/webp"
        OutputFormat.HEIC -> "image/heic"
        OutputFormat.HEIF -> "image/heif"
        OutputFormat.AVIF -> "image/avif"
        OutputFormat.JXL  -> "image/jxl"
    }
}
