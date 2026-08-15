package io.reascale.app.data

import java.io.File

/**
 * ONNX 模型自动探测（§20.4 启发式）
 *
 * 无需解析 onnx protobuf 头部（M1 阶段用启发式 + 文件名匹配）：
 *  1. 文件名包含 "x2" / "x4" / "x8" → baseScale
 *  2. 文件名包含 "anime" / "ultrasharp" / "remacri" → domain
 *  3. 文件大小 → 推断输入尺寸上限（粗估）
 *
 * M4 阶段升级为真 onnx protobuf 解析（读 input/output tensor shape），
 * 但 M1 这个版本就能让 90% 用户不需要手填 JSON。
 */
object AutoProbe {

    data class ProbeResult(
        val baseScale: Int,
        val maxInputEdge: Int,
        val maxInputPixels: Long,
        val mean: Float,
        val std: Float,
        val fixedSize: Boolean,
        val domain: EngineDomain,
        val probeNote: String
    )

    fun probe(file: File, filename: String): ProbeResult {
        val name = filename.lowercase()
        val sizeMb = file.length() / 1024 / 1024

        // baseScale 推断
        // 兼容命名：x4/x4plus (Real-ESRGAN)、up4x (Real-CUGAN)、scale4.0x (waifu2x)、4x
        val baseScale: Int = when {
            "x8" in name || "8x" in name || "8.0x" in name -> 8
            "x4" in name || "4x" in name || "4.0x" in name -> 4
            "x3" in name || "3x" in name || "3.0x" in name -> 3
            "x2" in name || "2x" in name || "2.0x" in name -> 2
            "x1" in name || "1x" in name -> 1
            else -> 4  // 通用默认：Real-ESRGAN 系列多为 4x
        }

        // domain 推断
        val domain: EngineDomain = when {
            "anime" in name || "waifu" in name -> EngineDomain.ANIME
            "face" in name || "gfpgan" in name || "codeformer" in name -> EngineDomain.FACE
            "ultrasharp" in name || "remacri" in name || "photo" in name -> EngineDomain.PHOTO
            else -> EngineDomain.GENERAL
        }

        // 输入尺寸上限（粗估）
        // 4x Real-ESRGAN 模型 ~64MB → 最大输入 ~256×256
        // 4x 4MB 精简版 → 最大输入 ~512×512
        // x2 模型 ~18MB → 最大输入 ~512×512
        val (maxEdge, maxPixels) = when {
            baseScale >= 4 && sizeMb > 50 -> 192L to 192L * 192L
            baseScale >= 4 && sizeMb > 20 -> 256L to 256L * 256L
            baseScale >= 4 -> 512L to 512L * 512L
            baseScale >= 2 && sizeMb > 10 -> 512L to 512L * 512L
            else -> 1024L to 1024L * 1024L
        }

        // mean/std 推断（GFPGAN 用 0.5/0.5，其他多数用 0/1）
        val (mean, std) = if ("gfpgan" in name || "codeformer" in name) {
            0.5f to 0.5f
        } else {
            0.0f to 1.0f
        }

        // fixedSize 推断（GFPGAN v1.4 输出固定 512×512）
        val fixedSize = "gfpgan" in name

        return ProbeResult(
            baseScale = baseScale,
            maxInputEdge = maxEdge.toInt(),
            maxInputPixels = maxPixels,
            mean = mean,
            std = std,
            fixedSize = fixedSize,
            domain = domain,
            probeNote = "Auto-Probe · ${baseScale}x · ${sizeMb}MB"
        )
    }
}