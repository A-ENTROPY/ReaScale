package io.reascale.app.core.engine

import android.graphics.Bitmap
import android.util.Log
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.ImageJob
import io.reascale.app.data.UpscalePlan

/**
 * 引擎统一接口
 * 对应 §6.1 + §24 三路径放大
 *
 * 实现层级：
 * - M1（当前）：NoopEngine（占位）+ BaseScaleEngine（B 路径：baseScale * N）
 * - M3：接入 ONNX Runtime / NCNN
 */
interface UpscaleEngine {

    /** 引擎 ID（与 EngineProfile.id 一致） */
    val engineId: String

    /**
     * 同步执行放大
     *
     * @param input 输入 bitmap（已 tile / 已预处理）
     * @param plan 放大计划
     * @param progress 进度回调 0..1（每张图只调一次；多 tile 走 batchUpscale）
     * @return 放大后的 bitmap
     */
    fun upscale(
        input: Bitmap,
        plan: UpscalePlan,
        progress: (Float) -> Unit = {}
    ): Bitmap

    /**
     * 批量放大（一次加载模型，多张图复用）
     * 默认实现 = 循环 upscale()
     */
    fun upscaleBatch(
        inputs: List<Bitmap>,
        plan: UpscalePlan,
        progress: (Int, Float) -> Unit = { _, _ -> }
    ): List<Bitmap> {
        return inputs.mapIndexed { i, bmp ->
            upscale(bmp, plan) { p -> progress(i, p) }
        }
    }

    /**
     * 释放资源（M3 阶段会卸载模型）
     */
    fun close() {}

    companion object {
        /**
         * 根据 EngineProfile 选取执行路径
         * 对应 §24 三路径放大选择算法
         *
         * 路径 A（基础）：target == baseScale → 一次推理
         * 路径 B（串接）：target = baseScale^N（4x*2=8x, 4x*4=16x, 2x*4=8x）
         * 路径 C（基础+下采样）：target < baseScale 且 allowBasePlusDownsample → 一次推理 + Lanczos 下采样
         */
        fun selectPath(profile: EngineProfile, plan: UpscalePlan): UpscalePath {
            val base = profile.capabilities.baseScale
            val target = plan.targetScale
            return when {
                target == base -> UpscalePath.BASIC
                target > base && isPowerOf(base, target) -> UpscalePath.CHAIN
                target < base && plan.allowBasePlusDownsample -> UpscalePath.BASIC_DOWNSCALE
                target > base -> UpscalePath.CHAIN   // 向上取 N 次
                else -> UpscalePath.BASIC            // 默认走基础
            }
        }

        private fun isPowerOf(base: Int, target: Int): Boolean {
            var t = target
            var safety = 8
            while (safety-- > 0) {
                if (t == base) return true
                if (t % base != 0) return false
                t /= base
                if (t < 1) return false
            }
            return false
        }
    }
}

enum class UpscalePath {
    /** 路径 A：基础放大（一次推理） */
    BASIC,
    /** 路径 B：串接放大（baseScale × N 次） */
    CHAIN,
    /** 路径 C：基础+下采样（4x 模型做 2x，Lanczos3 核） */
    BASIC_DOWNSCALE
}

/**
 * M1 阶段的占位实现：不做真实推理，输出 = 输入
 * 用于让 UI 流转能跑通，M3 阶段替换
 */
class NoopEngine(override val engineId: String) : UpscaleEngine {
    override fun upscale(
        input: Bitmap,
        plan: UpscalePlan,
        progress: (Float) -> Unit
    ): Bitmap {
        progress(1.0f)
        return input
    }
}

/**
 * 干运行 —— 直接调用系统 Bitmap 的 createScaledBitmap 做上采样
 * 用于在没有 ONNX Runtime 的情况下，验证 §24 C 路径（基础+下采样）
 * 真实 M3 阶段会替换成 NCNN/ORT 推理
 *
 * M1 新增 [hasRealModel]：标识 assets/models/ 下是否有真 .onnx
 * - true  → UI 引擎卡片显示"已内置真模型"标签
 * - false → 显示"M3 待补"
 */
class StubEngine(
    override val engineId: String,
    private val baseScale: Int,
    val hasRealModel: Boolean = false
) : UpscaleEngine {
    override fun upscale(
        input: Bitmap,
        plan: UpscalePlan,
        progress: (Float) -> Unit
    ): Bitmap {
        Log.w("StubEngine", "⚠️ 使用 StubEngine（纯 Bitmap 缩放）—— 模型不存在，质量远差于模型推理: $engineId, " +
                "input=${input.width}x${input.height}")
        val path = UpscaleEngine.selectPath(
            EngineProfile(
                id = engineId,
                displayName = "stub",
                source = io.reascale.app.data.EngineSource.BUILTIN,
                modelUri = "",
                domain = io.reascale.app.data.EngineDomain.GENERAL,
                capabilities = io.reascale.app.data.EngineCapabilities(baseScale = baseScale)
            ),
            plan
        )
        val factor = when (path) {
            UpscalePath.BASIC -> baseScale
            UpscalePath.CHAIN -> plan.targetScale
            UpscalePath.BASIC_DOWNSCALE -> baseScale  // 然后外层 downsample
        }
        progress(0.5f)
        val w = input.width * factor
        val h = input.height * factor
        val scaled = Bitmap.createScaledBitmap(input, w, h, true)
        progress(1.0f)
        return scaled
    }
}