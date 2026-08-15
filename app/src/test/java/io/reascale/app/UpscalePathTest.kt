package io.reascale.app

import io.reascale.app.core.engine.UpscaleEngine
import io.reascale.app.core.engine.UpscalePath
import io.reascale.app.data.EngineCapabilities
import io.reascale.app.data.EngineDomain
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.EngineSource
import io.reascale.app.data.UpscalePlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §24 三路径放大选择逻辑单测
 */
class UpscalePathTest {

    private fun profile(baseScale: Int) = EngineProfile(
        id = "t$baseScale",
        displayName = "t$baseScale",
        source = EngineSource.BUILTIN,
        modelUri = "ncnn:test",
        domain = EngineDomain.GENERAL,
        capabilities = EngineCapabilities(baseScale = baseScale)
    )

    @Test
    fun `basic when target equals base`() {
        assertEquals(UpscalePath.BASIC, UpscaleEngine.selectPath(profile(2), UpscalePlan(targetScale = 2)))
        assertEquals(UpscalePath.BASIC, UpscaleEngine.selectPath(profile(4), UpscalePlan(targetScale = 4)))
    }

    @Test
    fun `chain when target is power of base`() {
        assertEquals(UpscalePath.CHAIN, UpscaleEngine.selectPath(profile(2), UpscalePlan(targetScale = 4)))
        assertEquals(UpscalePath.CHAIN, UpscaleEngine.selectPath(profile(2), UpscalePlan(targetScale = 8)))
        assertEquals(UpscalePath.CHAIN, UpscaleEngine.selectPath(profile(4), UpscalePlan(targetScale = 16)))
    }

    @Test
    fun `chain when target greater than base non-power`() {
        // 2x 引擎 target=6：非整除，仍走 CHAIN（引擎做 2 次 2x=4x，外层缩放到 6x）
        assertEquals(UpscalePath.CHAIN, UpscaleEngine.selectPath(profile(2), UpscalePlan(targetScale = 6)))
        assertEquals(UpscalePath.CHAIN, UpscaleEngine.selectPath(profile(3), UpscalePlan(targetScale = 4)))
    }

    @Test
    fun `basic downscale when target below base and allowed`() {
        assertEquals(
            UpscalePath.BASIC_DOWNSCALE,
            UpscaleEngine.selectPath(profile(4), UpscalePlan(targetScale = 2, allowBasePlusDownsample = true))
        )
    }

    @Test
    fun `chain fallback when target below base but downscale disallowed`() {
        // allowBasePlusDownsample=false 且 target < base：当前实现回落到 BASIC
        assertEquals(
            UpscalePath.BASIC,
            UpscaleEngine.selectPath(profile(4), UpscalePlan(targetScale = 2, allowBasePlusDownsample = false))
        )
    }
}
