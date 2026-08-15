package io.reascale.app.core.imageio

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 图片元数据探测
 * 对应 §7.2 + §9 EXIF 处理
 *
 * - 不解码全图（避免 OOM）
 * - 用 BitmapFactory.Options.inJustDecodeBounds = true 仅读 header
 * - JPEG/HEIC 额外用 ExifInterface 读 EXIF 方向
 * - 大图 ( > 8192 像素边长) 提示后续走 BitmapRegionDecoder
 */
data class ImageMeta(
    val width: Int,
    val height: Int,
    val mimeType: String,    // image/jpeg, image/png, image/webp, image/heic, image/heif, image/avif
    val fileSizeBytes: Long,
    val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL,
    val isAnimated: Boolean = false, // GIF/WebP 动图
    val hasAlpha: Boolean = false,    // PNG / 带 alpha 的 WebP
    val iccProfilePresent: Boolean = false,
    /** 像素总数（宽 * 高） */
    val pixelCount: Long = width.toLong() * height.toLong(),
    /** 是否超大图（> 100M 像素，1 亿像素） */
    val isHuge: Boolean = pixelCount > 100_000_000L
)

object ImageProbe {

    /**
     * 探测 SAF Uri 指向的图片元数据
     */
    fun probe(context: Context, uri: Uri): ImageMeta? {
        val cr = context.contentResolver
        val mimeType = cr.getType(uri) ?: guessFromUri(uri)
        val size = runCatching {
            cr.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: 0L

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }

        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val exif = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()

        return ImageMeta(
            width = opts.outWidth,
            height = opts.outHeight,
            mimeType = mimeType,
            fileSizeBytes = size,
            exifOrientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL,
            hasAlpha = mimeType?.contains("png") == true
                    || (mimeType?.contains("webp") == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P),
            // [FIX] 原实现判断 outMimeType 是否含 "icc"（恒 false，outMimeType 是 image/jpeg 之类）。
            // 平台与 androidx ExifInterface 均未暴露 ICC_PROFILE 常量（正确探测需解析 JPEG APP2 段），
            // 暂时诚实置 false，保留字段供后续实现
            iccProfilePresent = false
        )
    }

    /**
     * 探测本地 File 指向的图片元数据
     */
    fun probe(file: File): ImageMeta? {
        if (!file.exists() || !file.canRead()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        return ImageMeta(
            width = opts.outWidth,
            height = opts.outHeight,
            mimeType = opts.outMimeType ?: "image/*",
            fileSizeBytes = file.length(),
            exifOrientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
        )
    }

    private fun guessFromUri(uri: Uri): String = when (uri.lastPathSegment?.lowercase()) {
        in arrayOf("jpg", "jpeg") -> "image/jpeg"
        in arrayOf("png") -> "image/png"
        in arrayOf("webp") -> "image/webp"
        in arrayOf("heic") -> "image/heic"
        in arrayOf("heif") -> "image/heif"
        in arrayOf("avif") -> "image/avif"
        in arrayOf("jxl") -> "image/jxl"
        else -> "image/*"
    }
}