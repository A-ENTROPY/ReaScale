package io.reascale.app.core.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
 * [FIX 2026-08-17] 次世代格式编码支持：
 * - JPEG/PNG/WebP：Bitmap.compress（全版本）
 * - HEIC/HEIF：平台 android.graphics.HeifWriter（API 28+，HEVC/HEIC）
 * - AVIF：androidx.heifwriter.AvifWriter（API 30+ 且设备有 AV1 编码器）
 * - JXL：暂不支持（需第三方编解码组件，回退 JPEG）
 * 编码器需要文件路径 → 统一先编码到 cache 临时文件，再复制到目标输出流。
 *
 * - [outputDirUri] 非空：写入用户通过 SAF 选择的目录（设置页"输出目录"）
 * - 为空：Android 10+ 写 MediaStore Pictures/ReaScale/（免权限）；
 *   Android 9 及以下写 filesDir/exports 并通知 MediaScanner
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
        // 不可用格式（JXL / 低版本设备）统一回退 JPEG，避免任务静默失败
        val effective = if (isFormatSupported(options.format)) {
            options
        } else {
            options.copy(format = OutputFormat.JPEG)
        }
        if (!outputDirUri.isNullOrBlank()) {
            return@withContext writeToSaf(context, bitmap, effective, displayName, outputDirUri)
        }
        writeToMediaStore(context, bitmap, effective, displayName)
    }

    /** 当前设备是否支持该格式编码 */
    fun isFormatSupported(format: OutputFormat): Boolean = when (format) {
        OutputFormat.JPEG, OutputFormat.PNG, OutputFormat.WEBP -> true
        OutputFormat.HEIC, OutputFormat.HEIF -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        OutputFormat.AVIF -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasAv1Encoder()
        OutputFormat.JXL -> false
    }

    /** 设备是否有 AV1 编码器（AVIF 必需） */
    private fun hasAv1Encoder(): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.supportedTypes.contains("video/av1")
            }
        }.getOrDefault(false)
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
                encodeTo(context, bitmap, options, out)
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
                    encodeTo(context, bitmap, options, out)
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
                    encodeTo(context, bitmap, options, out)
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

    /**
     * 按格式编码到输出流。
     * JPEG/PNG/WebP 直接压缩；HEIC/HEIF/AVIF 编码器需要文件路径，
     * 先写 cache 临时文件再复制（避免大图 ByteArray 内存峰值）。
     */
    private fun encodeTo(context: Context, bitmap: Bitmap, options: EncodeOptions, out: java.io.OutputStream): Boolean {
        return when (options.format) {
            OutputFormat.JPEG, OutputFormat.PNG, OutputFormat.WEBP -> {
                val format = QualityMapper.toBitmapCompressFormat(options.format) ?: return false
                bitmap.compress(format, QualityMapper.directQuality(options), out)
            }
            OutputFormat.HEIC, OutputFormat.HEIF -> encodeViaTempFile(
                context, bitmap, options, "heic",
                { tmp -> encodeHeif(bitmap, options, tmp) }, out
            )
            OutputFormat.AVIF -> encodeViaTempFile(
                context, bitmap, options, "avif",
                { tmp -> encodeAvif(bitmap, options, tmp) }, out
            )
            OutputFormat.JXL -> false
        }
    }

    /** 编码到临时文件 → 复制到输出流 */
    private fun encodeViaTempFile(
        context: Context,
        bitmap: Bitmap,
        options: EncodeOptions,
        ext: String,
        encoder: (File) -> Boolean,
        out: java.io.OutputStream
    ): Boolean {
        val tmp = File(context.cacheDir, "encode_${System.currentTimeMillis()}_${options.format.name.lowercase()}.$ext")
        return try {
            if (!encoder(tmp)) return false
            tmp.inputStream().use { it.copyTo(out) }
            true
        } catch (t: Throwable) {
            false
        } finally {
            tmp.delete()
        }
    }

    /**
     * HEIC/HEIF：androidx.heifwriter.HeifWriter（API 28+，HEVC 压缩）
     * 注：platform 的 android.graphics.HeifWriter 是 @hide API 不在 SDK jar 中，
     * androidx 封装类等效且可用（AAR minSdk 28，调用前已检查 API 版本）
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun encodeHeif(bitmap: Bitmap, options: EncodeOptions, tmp: File): Boolean {
        return try {
            val writer = androidx.heifwriter.HeifWriter.Builder(
                tmp.absolutePath,
                bitmap.width,
                bitmap.height,
                androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP
            ).setQuality(QualityMapper.directQuality(options)).build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
            writer.close()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** AVIF：androidx AvifWriter（API 30+，需要设备 AV1 编码器） */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun encodeAvif(bitmap: Bitmap, options: EncodeOptions, tmp: File): Boolean {
        return try {
            val writer = androidx.heifwriter.AvifWriter.Builder(
                tmp.absolutePath,
                bitmap.width,
                bitmap.height,
                androidx.heifwriter.AvifWriter.INPUT_MODE_BITMAP
            ).setQuality(QualityMapper.directQuality(options)).build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
            writer.close()
            true
        } catch (t: Throwable) {
            false
        }
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
