package io.reascale.app

import io.reascale.app.data.AutoProbe
import io.reascale.app.data.EngineDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AutoProbe 启发式探测单测
 * [FIX 2026-08-17] 补充 ncnn 命名兼容（up2x / scale2.0x / x4plus）
 */
class AutoProbeTest {

    private fun probe(filename: String) =
        AutoProbe.probe(File("C:/fake/$filename"), filename)

    @Test
    fun `detect realcugan up2x`() {
        assertEquals(2, probe("up2x-no-denoise.param").baseScale)
        assertEquals(2, probe("up2x-conservative.param").baseScale)
    }

    @Test
    fun `detect realcugan up3x and up4x`() {
        assertEquals(3, probe("up3x-denoise3x.param").baseScale)
        assertEquals(4, probe("up4x-conservative.param").baseScale)
    }

    @Test
    fun `detect waifu2x scale2_0x`() {
        assertEquals(2, probe("noise0_scale2.0x_model.param").baseScale)
        assertEquals(2, probe("scale2.0x_model.bin").baseScale)
    }

    @Test
    fun `detect realesrgan x4plus`() {
        assertEquals(4, probe("realesrgan-x4plus.onnx").baseScale)
        assertEquals(2, probe("realesrgan-x2plus.onnx").baseScale)
    }

    @Test
    fun `default to 4x when unknown`() {
        assertEquals(4, probe("mystery-model.param").baseScale)
    }

    @Test
    fun `domain heuristics`() {
        assertEquals(EngineDomain.ANIME, probe("waifu2x_anime.param").domain)
        assertEquals(EngineDomain.ANIME, probe("up2x-anime.param").domain)
        assertEquals(EngineDomain.FACE, probe("gfpgan-v1.4.param").domain)
        assertEquals(EngineDomain.PHOTO, probe("up4x-photo.param").domain)
        assertEquals(EngineDomain.GENERAL, probe("up2x-no-denoise.param").domain)
    }

    @Test
    fun `gfpgan uses 0_5 mean std and fixed size`() {
        val r = probe("gfpgan-v1.4.param")
        assertEquals(0.5f, r.mean)
        assertEquals(0.5f, r.std)
        assertTrue(r.fixedSize)
    }
}
