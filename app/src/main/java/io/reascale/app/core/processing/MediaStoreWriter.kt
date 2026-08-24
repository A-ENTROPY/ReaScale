package io.reascale.app.core.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.OutputFormat
import io.reascale.app.core.encode.JxlStreamWriter
import io.reascale.app.core.encode.QualityMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        // [FIX 2026-08-17] AVIF：avif-coder 内置软件 AV1 编码（libavif+libaom），全设备可用，
        // 不再依赖设备硬件 AV1 编码器（原实现仅 Android 11+ 且 MediaCodecList 有 AV1 才可用）
        OutputFormat.AVIF -> true
        // [FIX 2026-08-17] JXL：jxl-coder（libjxl）自带，API 21+ 全设备可用
        OutputFormat.JXL -> true
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
            // [FIX 2026-08-17] AVIF：avif-coder 软件编码（libavif+libaom），返回 ByteArray 直接写出
            OutputFormat.AVIF -> {
                try {
                    val q = QualityMapper.directQuality(options)
                    val coder = com.radzivon.bartoshyk.avif.coder.HeifCoder()
                    val bytes = coder.encodeAvif(
                        bitmap, q,
                        com.radzivon.bartoshyk.avif.coder.AvifSpeed.SIX,
                        if (q >= 100) {
                            com.radzivon.bartoshyk.avif.coder.PreciseMode.LOSSLESS
                        } else {
                            com.radzivon.bartoshyk.avif.coder.PreciseMode.LOSSY
                        },
                        com.radzivon.bartoshyk.avif.coder.AvifSurfaceMode.AUTO,
                        com.radzivon.bartoshyk.avif.coder.AvifChromaSubsampling.AUTO
                    )
                    out.write(bytes)
                    out.flush()
                    true
                } catch (t: Throwable) {
                    false
                }
            }
            // [FIX 2026-08-17] JXL：jxl-coder 2.2.0（libjxl）编码为 ByteArray，直接写出
            OutputFormat.JXL -> {
                try {
                    val q = QualityMapper.directQuality(options)
                    val bytes = com.awxkee.jxlcoder.JxlCoder.encode(
                        bitmap,
                        if (bitmap.hasAlpha()) {
                            com.awxkee.jxlcoder.JxlChannelsConfiguration.RGBA
                        } else {
                            com.awxkee.jxlcoder.JxlChannelsConfiguration.RGB
                        },
                        if (q >= 100) {
                            com.awxkee.jxlcoder.JxlCompressionOption.LOSSLESS
                        } else {
                            com.awxkee.jxlcoder.JxlCompressionOption.LOSSY
                        },
                        com.awxkee.jxlcoder.JxlEffort.FALCON,
                        q,
                        com.awxkee.jxlcoder.JxlDecodingSpeed.FAST
                    )
                    out.write(bytes)
                    out.flush()
                    true
                } catch (t: Throwable) {
                    false
                }
            }
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

    /**
     * 流式写入：输出超大 JXL（不驻留整图内存）。
     * 打开目标流（MediaStore pending / SAF / 旧系统文件），
     * 由 [writeJxlStream] 分块喂数据。
     *
     * @return 终 Uri；null 失败
     */
    suspend fun writeJxlStreaming(
        context: Context,
        displayName: String,
        outputDirUri: String?,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean,
        produce: suspend (feed: suspend (y: Int, xOff: Int, row: ByteArray) -> Unit) -> Unit,
        progress: (Float) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val ext = "jxl"
        val mime = "image/jxl"
        val fileName = "$displayName.$ext"
        val resolver = context.contentResolver

        // 1. 目标位置
        val (targetUri, pending) = if (!outputDirUri.isNullOrBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(outputDirUri)) ?: return@withContext null
            val file = tree.createFile(mime, fileName) ?: return@withContext null
            file.uri to false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return@withContext null
            uri to true
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM_NAME).apply { mkdirs() }
            val outFile = File(dir, fileName)
            val uri = Uri.fromFile(outFile)
            uri to false
        }

        return@withContext try {
            val out = resolver.openOutputStream(targetUri) ?: return@withContext null
            val ok = try {
                val handle = JxlStreamWriter.nativeCreate(width, height, quality, lossless)
                if (handle == 0L) return@withContext null
                try {
                    val encScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.Job())
                    val encJob = encScope.async {
                        val r = JxlStreamWriter.nativeStart(handle)
                        if (r != 0) throw IllegalStateException("JXL 编码启动失败")
                    }
                    produce { y, xOff, row -> JxlStreamWriter.nativeFeedRowAt(handle, y, xOff, row) }
                    JxlStreamWriter.nativeFinishInput(handle)
                    encJob.await()
                    // 追加 extra flush（求稳）
                    JxlStreamWriter.nativeFlushExtra(handle)
                    // drain 写流
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = JxlStreamWriter.nativeDrain(handle, buf, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        progress(0.95f)
                    }
                    true
                } finally {
                    JxlStreamWriter.nativeDestroy(handle)
                }
            } finally {
                out.close()
            }
            if (!ok) {
                runCatching { resolver.delete(targetUri, null, null) }
                null
            } else {
                if (pending) {
                    resolver.update(
                        targetUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null
                    )
                } else if (targetUri.scheme == "file") {
                    runCatching {
                        resolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.DATA, targetUri.path)
                            }
                        )
                    }
                }
                targetUri
            }
        } catch (t: Throwable) {
            android.util.Log.e("MediaStoreWriter", "writeJxlStreaming failed", t)
            runCatching { resolver.delete(targetUri, null, null) }
            null
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
