package io.reascale.app.core.imageio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import io.reascale.app.core.MemoryBudget
import io.reascale.app.core.Tile
import io.reascale.app.core.TilePlan
import java.io.File
import java.io.InputStream

/**
 * BitmapRegionDecoder 包装器
 * 对应 §7.2 + §7.3 大图分块解码
 *
 * 关键点：
 * - API 10+ 提供，仅解码图像的矩形区域，内存 = 区域大小
 * - 适合 JPEG/PNG/WEBP（HEIC/AVIF 在 Android 9+ 也有支持但需要 ImageDecoder）
 * - 不支持 GIF 多帧
 *
 * 流程：
 * 1. 用 ImageProbe 拿到 width/height
 * 2. 如果 < tileEdge → 直接 BitmapFactory 解码全图
 * 3. 否则用 BitmapRegionDecoder 按 Tile 列表逐块解码
 */
object RegionDecoder {

    /** 简易读取：单块解码 */
    fun decodeRegion(
        context: Context,
        uri: Uri,
        rect: Rect,
        sampleSize: Int = 1
    ): Bitmap? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val decoder: BitmapRegionDecoder = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    decoder.decodeRegion(rect, opts)
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
    }

    /** 简易读取：本地文件 */
    fun decodeRegion(
        file: File,
        rect: Rect,
        sampleSize: Int = 1
    ): Bitmap? {
        if (!file.exists()) return null
        return runCatching {
            file.inputStream().use { input ->
                val decoder: BitmapRegionDecoder = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
                try {
                    val opts = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    decoder.decodeRegion(rect, opts)
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
    }

    /**
     * 全图分块解码 —— 返回所有 tile 的 bitmap 列表
     * 调用方负责及时 recycle
     */
    fun decodeAllTiles(
        context: Context,
        uri: Uri,
        imageWidth: Int,
        imageHeight: Int,
        plan: TilePlan
    ): List<Bitmap> {
        val out = ArrayList<Bitmap>(plan.tiles.size)
        plan.tiles.forEach { tile ->
            val bmp = decodeTile(context, uri, tile, imageWidth, imageHeight) ?: return@forEach
            out.add(bmp)
        }
        return out
    }

    private fun decodeTile(
        context: Context,
        uri: Uri,
        tile: Tile,
        imageWidth: Int,
        imageHeight: Int
    ): Bitmap? {
        val rect = Rect(
            tile.x,
            tile.y,
            (tile.x + tile.w).coerceAtMost(imageWidth),
            (tile.y + tile.h).coerceAtMost(imageHeight)
        )
        return decodeRegion(context, uri, rect, sampleSize = 1)
    }

    /**
     * 流式输出版本 —— 把每个 tile 解码 → 立即交给 [onTile] 回调 → recycle
     * 适合 1-2 亿像素超大图，内存峰值 ≈ 1 个 tile
     *
     * 对应 §7.4 "流式到输出"
     */
    fun streamDecode(
        context: Context,
        uri: Uri,
        imageWidth: Int,
        imageHeight: Int,
        plan: TilePlan,
        onTile: (Bitmap, Tile) -> Unit
    ) {
        val cr = context.contentResolver
        val pfd = cr.openFileDescriptor(uri, "r") ?: return
        pfd.use { pfdInner ->
            // 用 FileDescriptor 重用同一个 decoder，更高效
            val raw: BitmapRegionDecoder = BitmapRegionDecoder.newInstance(pfdInner.fileDescriptor, false)
            try {
                plan.tiles.forEach { tile ->
                    val rect = Rect(
                        tile.x,
                        tile.y,
                        (tile.x + tile.w).coerceAtMost(imageWidth),
                        (tile.y + tile.h).coerceAtMost(imageHeight)
                    )
                    val bmp: Bitmap? = runCatching {
                        raw.decodeRegion(
                            rect,
                            android.graphics.BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    }.getOrNull()
                    if (bmp == null) return@forEach
                    try {
                        onTile(bmp, tile)
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
            } finally {
                raw.recycle()
            }
        }
    }

    /**
     * 选择解码策略
     *
     * 规则（§7.2）：
     * - 像素总数 < 32M（≈ 800万像素，4K）：走 inSampleSize 缩放直解
     * - 像素总数 >= 32M：走 BitmapRegionDecoder
     */
    fun chooseStrategy(
        context: Context,
        uri: Uri
    ): DecodeStrategy {
        val meta = ImageProbe.probe(context, uri) ?: return DecodeStrategy.ERROR
        val plan = MemoryBudget.planTiles(meta.width, meta.height, context)
        return when {
            meta.pixelCount > 32_000_000L || plan.needsTiling -> DecodeStrategy.REGION_DECODE
            else -> DecodeStrategy.DIRECT_DECODE
        }
    }
}

enum class DecodeStrategy {
    /** 直接 BitmapFactory.decodeStream 一次解码（适合 < 32M 像素） */
    DIRECT_DECODE,
    /** 分块 BitmapRegionDecoder 区域解码（适合超大图） */
    REGION_DECODE,
    /** 探测失败 */
    ERROR
}

/**
 * 缩放工具：把超大的输出 bitmap 缩到合适大小（设置页预览用）
 */
object BitmapScaler {

    fun fitMaxEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longer = maxOf(bmp.width, bmp.height)
        if (longer <= maxEdge) return bmp
        val ratio = maxEdge.toFloat() / longer
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}