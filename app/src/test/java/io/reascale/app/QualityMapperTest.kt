package io.reascale.app

import io.reascale.app.core.encode.QualityMapper
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QualityMapper 质量映射单测
 * [FIX 2026-08-17] estimateOutputSizeMB 原实现 bpp.toLong() 截断导致恒为 0，已修复
 */
class QualityMapperTest {

    @Test
    fun `estimate JPEG size is positive`() {
        val mb = QualityMapper.estimateOutputSizeMB(
            EncodeOptions(format = OutputFormat.JPEG, quality = 95),
            2_000_000L
        )
        assertTrue("JPEG 2M 像素 q95 估算应 > 0，实得 $mb", mb > 0)
    }

    @Test
    fun `estimate WebP and AVIF sizes are positive`() {
        for (format in listOf(OutputFormat.WEBP, OutputFormat.HEIC, OutputFormat.AVIF, OutputFormat.JXL)) {
            val mb = QualityMapper.estimateOutputSizeMB(
                EncodeOptions(format = format, quality = 90),
                1_000_000L
            )
            assertTrue("${format.name} 估算应 > 0，实得 $mb", mb > 0)
        }
    }

    @Test
    fun `estimate PNG is larger than JPEG at same pixels`() {
        val png = QualityMapper.estimateOutputSizeMB(EncodeOptions(format = OutputFormat.PNG), 1_000_000L)
        val jpg = QualityMapper.estimateOutputSizeMB(EncodeOptions(format = OutputFormat.JPEG, quality = 95), 1_000_000L)
        assertTrue("PNG($png) 应大于 JPEG($jpg)", png > jpg)
    }

    @Test
    fun `directQuality clamps to range 1-100`() {
        assertEquals(100, QualityMapper.directQuality(EncodeOptions(quality = 150)))
        assertEquals(1, QualityMapper.directQuality(EncodeOptions(quality = 0)))
        assertEquals(95, QualityMapper.directQuality(EncodeOptions(quality = 95)))
    }

    @Test
    fun `pngCompressionLevel is inverse of quality`() {
        assertEquals(0, QualityMapper.pngCompressionLevel(EncodeOptions(quality = 100)))
        assertEquals(9, QualityMapper.pngCompressionLevel(EncodeOptions(quality = 1)))
        // q=50 → 中间值
        val mid = QualityMapper.pngCompressionLevel(EncodeOptions(quality = 50))
        assertTrue("q=50 压缩级应在 0..9 中间，实得 $mid", mid in 4..5)
    }
}
