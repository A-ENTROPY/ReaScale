package io.reascale.app.core.encode

import android.graphics.Bitmap
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.OutputFormat

/**
 * 1-100 通用质量条 → 各编码器参数映射
 * 对应 §19.2 + §19.4
 *
 * 映射规则（每个都经过单独测试验证）：
 *
 * | 格式   | quality=100  →  quality=1
 * |--------|---------------|-----------------
 * | JPEG   | q=100         |  q=1
 * | PNG    | compression=0 | compression=9（无压缩 ↔ 最大压缩）
 * | WebP   | q=100 (lossless 自动) | q=1
 * | HEIC*  | CRF=18 (高质量) | CRF=51 (最低)
 * | HEIF*  | 同 HEIC
 * | AVIF*  | CQ=18 (高质量) | CQ=63 (最低)
 * | JXL*   | distance=0.0  | distance=15.0
 *
 * * = 系统编码器，M6 阶段才启用（依赖 libheif / libaom / libjxl）
 *
 * 线性插值公式：
 *   out = round(min + (max - min) * (100 - quality) / 99.0)
 * 对于反向（PNG、CRF、CQ）使用：
 *   out = round(max - (max - min) * (100 - quality) / 99.0)
 */
object QualityMapper {

    /** 选用 Android 系统内置 Bitmap.compress 的格式枚举 */
    fun toBitmapCompressFormat(format: OutputFormat): Bitmap.CompressFormat? = when (format) {
        OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        OutputFormat.WEBP -> Bitmap.CompressFormat.WEBP
        // HEIC/HEIF/AVIF/JXL 走系统 ImageDecoder + 第三方编码器（M6 阶段）
        else -> null
    }

    /**
     * 计算 [quality] 对应的 JPEG/WebP 质量值（0-100）
     * 直接传递：quality=1 → 1；quality=100 → 100
     */
    fun directQuality(options: EncodeOptions): Int = options.quality.coerceIn(1, 100)

    /**
     * 计算 [quality] 对应的 PNG 压缩等级（0-9）
     * quality=100 → 0（无压缩，最快）
     * quality=1   → 9（最大压缩，最慢）
     */
    fun pngCompressionLevel(options: EncodeOptions): Int {
        val q = options.quality.coerceIn(1, 100)
        // 反向映射：q=100 → 0；q=1 → 9
        return ((100 - q) * 9 / 99).coerceIn(0, 9)
    }

    /**
     * HEIC/HEIF 编码器的 CRF 值（18-51，x265）
     * quality=100 → CRF=18（高质量）
     * quality=1   → CRF=51（最低）
     */
    fun heicCrf(options: EncodeOptions): Int {
        val q = options.quality.coerceIn(1, 100)
        return (18 + (51 - 18) * (100 - q) / 99.0).toInt().coerceIn(18, 51)
    }

    /**
     * AVIF 编码器的 CQ 值（0-63，aom）
     * quality=100 → CQ=18
     * quality=1   → CQ=63
     */
    fun avifCq(options: EncodeOptions): Int {
        val q = options.quality.coerceIn(1, 100)
        return (18 + (63 - 18) * (100 - q) / 99.0).toInt().coerceIn(18, 63)
    }

    /**
     * JPEG XL distance 值（0.0-15.0，越小越好）
     * quality=100 → distance=0.0
     * quality=1   → distance=15.0
     */
    fun jxlDistance(options: EncodeOptions): Double {
        val q = options.quality.coerceIn(1, 100)
        return ((100 - q) * 15.0 / 99.0).coerceIn(0.0, 15.0)
    }

    /**
     * 估算输出文件大小（MB）—— 用于设置页预估
     */
    fun estimateOutputSizeMB(
        options: EncodeOptions,
        pixelCount: Long
    ): Double {
        // 经验公式：JPEG 1MB/2M 像素@q95
        val bpp = when (options.format) {
            OutputFormat.JPEG -> 0.35 * (100.0 / options.quality.coerceAtLeast(10))
            OutputFormat.PNG -> 1.5 // 接近无损
            OutputFormat.WEBP -> 0.30 * (100.0 / options.quality.coerceAtLeast(10))
            OutputFormat.HEIC, OutputFormat.HEIF -> 0.20 * (100.0 / options.quality.coerceAtLeast(10))
            OutputFormat.AVIF -> 0.18 * (100.0 / options.quality.coerceAtLeast(10))
            OutputFormat.JXL -> 0.22 * (100.0 / options.quality.coerceAtLeast(10))
        }
        // [FIX] bpp 是小数（0.18~1.5），toLong() 会把 <1 的值截断为 0 → 估算恒为 0
        val bits = pixelCount.toDouble() * bpp
        return bits / 8.0 / 1024.0 / 1024.0
    }
}