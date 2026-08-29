package io.reascale.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.reascale.app.data.ImageJob

/**
 * 内存预算 + 分块策略
 * 对应 §6.2 + §7.3 + §27.2 性能 SLA
 *
 * 设计目标：
 * - 任何设备不 OOM（即使 2 亿像素）
 * - 推理峰值 < 300MB
 * - 同时跑 1-3 个 Worker
 *
 * 算法（§6.2 内存预算）：
 * 1. 计算设备可用 heap（ActivityManager.memoryClass）
 * 2. 减去模型常驻 (engineBase, 50MB for 4x Real-ESRGAN)
 * 3. 减去输出缓冲（outputW * outputH * 4 bytes，2x 安全系数）
 * 4. 剩余 = 单图推理可用
 * 5. 用剩余推回最大 tile 边长（tileMax = sqrt(remaining / 4 / channels)）
 */
object MemoryBudget {

    private const val ENGINE_RESIDENT_MB = 60L   // 4x Real-ESRGAN 运行时 ~50MB
    private const val SYSTEM_OVERHEAD_MB = 120L  // 系统 + 其他进程占用
    private const val SAFETY_MARGIN = 0.7        // 实际只使用 70% 预算

    /**
     * 设备总可用预算 (MB)
     * 对应 §6.2 (1) memoryClass
     */
    fun deviceBudgetMB(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appClass = am.memoryClass.toLong()              // App 堆上限 (MB)
        val largeClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            am.largeMemoryClass.toLong()
        } else appClass
        return (appClass - SYSTEM_OVERHEAD_MB).coerceAtLeast(64L)
            .coerceAtMost(largeClass - SYSTEM_OVERHEAD_MB)
    }

    /**
     * 单次推理可用的输入 tile 边长 (像素)
     *
     * @param engineBaseMB 模型常驻内存
     * @param inputChannels 输入通道数（RGB=3）
     * @return tile 边长上限（确保不 OOM）
     */
    fun maxTileEdge(
        context: Context,
        engineBaseMB: Long = ENGINE_RESIDENT_MB,
        inputChannels: Int = 3
    ): Int {
        val budgetMB = (deviceBudgetMB(context) - engineBaseMB).coerceAtLeast(64L)
        val usableMB = (budgetMB * SAFETY_MARGIN).toLong()
        // float32 = 4 bytes，1 个输入 + 1 个输出 = 2 个张量
        val bytesPerPixel = 4L * 2L * inputChannels
        val usableBytes = usableMB * 1024L * 1024L
        val maxPixels = usableBytes / bytesPerPixel
        val edge = Math.sqrt(maxPixels.toDouble()).toInt()
        return (edge / 32) * 32  // 对齐到 32（多数 SR 模型要求 32 倍数）
    }

    /**
     * 把大图分块成 tile 列表
     * 每块大小 = maxTileEdge x maxTileEdge，相邻块有 32 像素 overlap
     *
     * 对应 §7.3 分块策略
     */
    fun planTiles(
        srcWidth: Int,
        srcHeight: Int,
        context: Context,
        engineBaseMB: Long = ENGINE_RESIDENT_MB
    ): TilePlan {
        // spec 2026-08-01：不私自降采样，tile 大小按设备预算计算
        // 不再 coerceAtMost(2048) —— 设备内存够就给大 tile
        val tile = maxTileEdge(context, engineBaseMB)
        val overlap = 32
        val stride = tile - overlap

        if (srcWidth <= tile && srcHeight <= tile) {
            return TilePlan(
                tiles = listOf(Tile(0, 0, srcWidth, srcHeight)),
                tileSize = tile,
                overlap = 0,
                needsTiling = false
            )
        }

        val tiles = mutableListOf<Tile>()
        var y = 0
        while (y < srcHeight) {
            val h = (srcHeight - y).coerceAtMost(tile)
            var x = 0
            while (x < srcWidth) {
                val w = (srcWidth - x).coerceAtMost(tile)
                tiles.add(Tile(x, y, w, h))
                if (x + w >= srcWidth) break
                x += stride
            }
            if (y + h >= srcHeight) break
            y += stride
        }

        return TilePlan(
            tiles = tiles,
            tileSize = tile,
            overlap = overlap,
            needsTiling = true
        )
    }

    /**
     * 估算 Job 峰值内存 (MB)
     * 用于 §27 性能 SLA 监控
     */
    fun estimatePeakMB(job: ImageJob, context: Context): Long {
        // [CRASH-FIX 2026-08-29] 尺寸未知（惰性 probe 后宽高=0）：保守按 12MP 输入估算，
        // 避免调度器因 0 尺寸低估内存而并发失控
        val w = if (job.sourceWidth > 0) job.sourceWidth else 3456
        val h = if (job.sourceHeight > 0) job.sourceHeight else 3456
        val outW = w.toLong() * job.upscalePlan.targetScale
        val outH = h.toLong() * job.upscalePlan.targetScale
        val outBytes = outW * outH * 4L
        // [CONCURRENCY-FIX 2026-08-29] 流式路径（输出 bitmap >180MB，走 processTiled
        // tile 级推理 + 流式写出）单张峰值与"整图输出"估算无关：tile 推理 ~60MB + 行带
        // 位图 ~80MB → 保守 160MB。原估算用整图输出（4x 大图 → 768MB）> 调度预算，
        // 导致所有大图永久只能 1 并发（用户实测"开极速也一次一张"）。
        // 整图路径（输出 ≤180MB bitmap）保持真实估算。
        val wholeImageBudget = 180L * 1024L * 1024L
        if (outBytes > wholeImageBudget) {
            return STREAMING_PEAK_MB
        }
        // 1 输入 float32 + 1 输出 uint8 = 4*3 + 4 = 16 字节/像素
        val tileEdge = maxTileEdge(context)
        val inTileMB = (tileEdge.toLong() * tileEdge * 16L) / (1024L * 1024L)
        val outMB = outBytes / (1024L * 1024L)
        return ENGINE_RESIDENT_MB + inTileMB + outMB + 30L // 30MB 系统开销
    }

    /** [CONCURRENCY-FIX] 流式路径单张峰值（tile 推理 + 行带缓冲 + 编码器） */
    private const val STREAMING_PEAK_MB = 160L
}

data class Tile(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

data class TilePlan(
    val tiles: List<Tile>,
    val tileSize: Int,
    val overlap: Int,
    val needsTiling: Boolean
) {
    val tileCount: Int get() = tiles.size
}