package io.reascale.app.core.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.OutputFormat
import io.reascale.app.core.encode.QualityMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 输出到系统相册 / 用户目录
 * 对应 §19.6 输出位置
 *
 * - Android 10+ (API 29+)：用 MediaStore.Images 写入 Pictures/ReaScale/，
 *   无需 WRITE_EXTERNAL_STORAGE 权限
 * - Android 9 及以下：直接写 filesDir/exports/，然后通知 MediaScanner
 *
 * 返回 Uri（成功）或 null（失败）
 */
object MediaStoreWriter {

    private const val ALBUM_NAME = "ReaScale"

    /**
     * 把 bitmap 编码并写入系统相册
     *
     * @param displayName 用户可见文件名（不含后缀）
     * @return 写入后的 content://media/... Uri，失败返回 null
     */
    suspend fun write(
        context: Context,
        bitmap: Bitmap,
        options: EncodeOptions,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
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
            val uri = resolver.insert(collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    writeBitmap(bitmap, options, out)
                } ?: return@withContext null
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
            try {
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
            ?: return false   // HEIC/AVIF/JXL 暂不支持内置编码（M6 才接）
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