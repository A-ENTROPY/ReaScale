package io.reascale.app.core.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.reascale.app.core.MemoryBudget
import io.reascale.app.core.Tile
import io.reascale.app.core.engine.NcnnEngine
import io.reascale.app.core.engine.OnnxEngine
import io.reascale.app.core.TilePlan
import io.reascale.app.core.engine.UpscaleEngine
import io.reascale.app.core.engine.UpscaleEngine.Companion.selectPath
import io.reascale.app.core.engine.UpscalePath
import io.reascale.app.core.encode.QualityMapper
import io.reascale.app.core.imageio.ImageProbe
import io.reascale.app.core.imageio.RegionDecoder
import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.ImageJob
import io.reascale.app.data.OutputFormat
import io.reascale.app.debug.LogBus

/**
 * 单张图超分全流程
 * 对应 §6.1 + §7 + §8 + §24
 *
 * 流程：
 * 1. ImageProbe 探元数据（宽/高/EXIF）
 * 2. MemoryBudget.planTiles 决定是否分块
 * 3. RegionDecoder 解码
 * 4. UpscaleEngine.upscale（StubEngine 占位 / M3 替换真 ORT）
 * 5. Bitmap.compress + MediaStoreWriter 写盘
 * 6. 返回 output Uri + 耗时
 */
class ImageProcessor(
    private val context: Context,
    private val engineProvider: (String) -> UpscaleEngine,
    // [FIX 2026-08-17] 用户选择的输出目录（SAF tree uri，设置页配置；空=默认相册）
    private val outputDirProvider: suspend () -> String = { "" }
) {
    // [FIX 2026-08-11] 引擎缓存：复用 NcnnEngine 实例避免反复加载模型
    // 同时防止多个引擎实例并发推理导致 ncnn 内部死锁
    private val engineCache = mutableMapOf<String, UpscaleEngine>()

    // [FIX 2026-08-18] 流式 JXL 路径已直接写盘完成，此字段携带最终 Uri（单任务实例）
    @Volatile
    private var streamingDoneUri: Uri? = null

    /**
     * 处理单个 Job，更新进度 + 最终状态
     *
     * 注意：调用方应负责把 Job 标 RUNNING + COMPLETED/FAILED，
     *       本方法只通过 [progress] 回调返回中间进度（0..1）。
     * progress 是普通 lambda，因为 UpscaleEngine.upscale 接受非 suspend 回调
     *
     * 闪退防护（systematic-debugging 2026-08-01）：
     * - 每个 step 都包 try-catch（ImageProbe / engineProvider / decode / upscale / encode / write）
     * - 单步失败 → markFailed，不让 app crash
     *
     * 超大图像分块策略（spec §7.2 + §24）：
     * - 单张图总面积 > 32M pixel → 走 processTiled（BitmapRegionDecoder 流式分块）
     * - 单块 ≤ 2M pixel（2000x1000 长条形），按 baseScale 4x 输出 = 8000x4000 (32M) 安全
     * - 引擎处理后再按 baseScale 缩放回输出位置
     */
    suspend fun process(
        job: ImageJob,
        profile: EngineProfile,
        progress: (Float) -> Unit
    ): Result<Uri> = runCatching {
        progress(0.05f)
        LogBus.i("ImageProcessor", "🚀 process START: job=${job.id.take(8)}, src=${job.sourceDisplayName}, engine=${profile.id}, targetScale=${job.upscalePlan.targetScale}, sizeBytes=${job.sourceSizeBytes}")

        // 1. 探元数据
        val srcUri = Uri.parse(job.sourceUri)
        val meta = try {
            ImageProbe.probe(context, srcUri)
        } catch (t: Throwable) {
            throw IllegalStateException("图片元数据读取失败: ${t.message}", t)
        } ?: throw IllegalStateException("无法读取图片元数据: ${job.sourceUri}")
        progress(0.10f)
        LogBus.i("ImageProcessor", "📷 meta: ${meta.width}x${meta.height}, ${meta.fileSizeBytes} bytes")

        // 2. 选择引擎（来自 profile）— 缓存复用
        val engine = try {
            val e = engineCache.getOrPut(profile.id) {
                engineProvider(profile.id).also {
                    LogBus.i("ImageProcessor", "🎯 新引擎创建: ${it::class.java.simpleName}, id=${it.engineId}")
                }
            }
            e
        } catch (t: Throwable) {
            // 缓存创建失败，清除缓存项
            engineCache.remove(profile.id)
            LogBus.e("ImageProcessor", "🔴 engine loading FAILED for ${profile.id}", t)
            throw IllegalStateException("引擎加载失败: ${t.message}", t)
        }

        // 3. 选路径（§24 三路径放大）
        val path = selectPath(profile, job.upscalePlan)
        val factor = when (path) {
            UpscalePath.BASIC -> profile.capabilities.baseScale
            UpscalePath.CHAIN -> job.upscalePlan.targetScale
            UpscalePath.BASIC_DOWNSCALE -> profile.capabilities.baseScale
        }

        // 4. 解块 + 推理 + 拼回
        // v18 策略：
        //   - 超大图（> 32M pixel）：BitmapRegionDecoder 流式分块 + 每块独立推理（串行但每块小）
        //   - 大图（> 2M pixel）：整图解码后交给 OnnxEngine 内部并行 tile 处理
        //   - 小图（≤ 2M pixel）：整图解码 + 一次推理
        // 这样 OnnxEngine 的并行 tile 能力在中等大图上能充分发挥
        val baseNameEarly = try { job.sourceDisplayName.substringBeforeLast('.') }
            catch (t: Throwable) { "image" }
        var finalBitmap: Bitmap = try {
            // [FIX 2026-08-18] 决策改为：输入 >32M 像素 或 输出可能 >200MB bitmap 时
            // 都走外部分块路径。输出超大的 JXL 由 processTiled 内部转流式编码
            // （引擎不产出整图输出位图，避免 OOM）。
            val hugeOutput = meta.width.toLong() * factor * meta.height * factor * 4L >
                200L * 1024L * 1024L
            if (meta.pixelCount > 32_000_000L || hugeOutput) {
                // 超大图：BitmapRegionDecoder 流式分块（外部串行）或流式 JXL 直写
                val plan = MemoryBudget.planTiles(meta.width, meta.height, context)
                processTiled(engine, srcUri, meta.width, meta.height, plan, factor, profile, baseNameEarly, job, progress)
            } else {
                // 普通图/大图：整图解码 → OnnxEngine 内部并行 tile
                val src = safeDecodeBitmap(context, srcUri, meta.width, meta.height)
                    ?: throw IllegalStateException("解码失败")
                try {
                    // [FIX 2026-08-17] 引擎 tile 级进度 → 平滑映射 0.10..0.90
                    engine.upscale(src, job.upscalePlan) { p ->
                        progress(0.10f + p * 0.80f)
                    }
                } finally {
                    if (!src.isRecycled) src.recycle()
                }
            }
        } catch (oom: OutOfMemoryError) {
            throw IllegalStateException("内存不足（OOM），请缩小图片或降低并发数", oom)
        }
        // [FIX 2026-08-18] 流式 JXL：超大输出已直接写盘，跳过后续 bitmap 编码路径
        streamingDoneUri?.let {
            progress(1.0f)
            return@runCatching it
        }
        // [FIX 2026-08-16] 确保实际输出尺寸 = 目标放大倍数 × 原图
        // 引擎可能因 CHAIN 非整除（如 3x 模型 target=4）只输出了 baseScale 次方倍，
        // 这里把 finalBitmap 缩放到准确的 targetScale 尺寸
        val desiredW = meta.width * job.upscalePlan.targetScale
        val desiredH = meta.height * job.upscalePlan.targetScale
        if (finalBitmap.width != desiredW || finalBitmap.height != desiredH) {
            LogBus.i("ImageProcessor", "🔧 输出尺寸校正: ${finalBitmap.width}x${finalBitmap.height} → ${desiredW}x${desiredH}")
            val scaled = Bitmap.createScaledBitmap(finalBitmap, desiredW, desiredH, true)
            if (scaled !== finalBitmap && !finalBitmap.isRecycled) finalBitmap.recycle()
            finalBitmap = scaled
        }
        progress(0.90f)

        // 5. C 路径：4x→2x 下采样（user 选了比 baseScale 小的 target）
        val outBitmap: Bitmap = if (path == UpscalePath.BASIC_DOWNSCALE &&
            job.upscalePlan.targetScale < profile.capabilities.baseScale) {
            try {
                val targetW = meta.width * job.upscalePlan.targetScale
                val targetH = meta.height * job.upscalePlan.targetScale
                Bitmap.createScaledBitmap(finalBitmap, targetW, targetH, true).also {
                    if (it !== finalBitmap && !finalBitmap.isRecycled) finalBitmap.recycle()
                }
            } catch (oom: OutOfMemoryError) {
                throw IllegalStateException("下采样 OOM", oom)
            }
        } else {
            finalBitmap
        }
        progress(0.92f)

        // 6. 编码 + 写盘（[FIX 2026-08-17] 支持设置页配置的输出目录）
        val outUri = try {
            MediaStoreWriter.write(
                context = context,
                bitmap = outBitmap,
                options = job.encodeOptions,
                displayName = "${baseNameEarly}_${profile.capabilities.baseScale}x",
                outputDirUri = outputDirProvider()
            )
        } catch (t: Throwable) {
            throw IllegalStateException("输出失败: ${t.message}", t)
        } ?: throw IllegalStateException("写入失败")
        if (!outBitmap.isRecycled) outBitmap.recycle()

        progress(1.0f)
        outUri
    }

    /**
     * 分块流式处理
     * 每块单独解码 → 推理 → 画到最终 canvas
     * 内存峰值 ≈ 1 个 tile 大小 + 引擎常驻
     *
     * 2026-08-08 v8 OOM 终极修复：sub-canvas 拆分（支持 1 亿像素）
     * 之前（v7）：申请整张 outBitmap（1 亿像素@2x = 1.6GB）→ 必 OOM
     * 现在（v8.1）：先尝试单 canvas（< 200MB bitmap）。
     *           单 canvas OOM → fallback 到 multi-PNG 输出（4x4 = 16 个 sub-canvas PNG）
     */
    private suspend fun processTiled(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        plan: TilePlan,
        factor: Int,
        profile: EngineProfile,
        baseName: String,  // 2026-08-08 v8.1: 大图 multi-PNG 需此参数
        job: ImageJob,
        progress: (Float) -> Unit
    ): Bitmap {
        val outW = srcW * factor
        val outH = srcH * factor
        val outPx = outW.toLong() * outH.toLong()
        LogBus.i("ImageProcessor", "🟦 processTiled: src=${srcW}x${srcH} → out=${outW}x${outH} (${outPx}px), factor=${factor}x")

        // === v8 决策：output bitmap 大小 vs heap ===
        val outputBitmapBytes = outPx * 4L
        val maxOutputBitmapBytes = 200L * 1024L * 1024L  // 200MB
        val canFitSingleBitmap = outputBitmapBytes <= maxOutputBitmapBytes

        // [FIX 2026-08-18] 超大输出（>200MB bitmap）：
        // JXL → libjxl 流式编码；PNG → 纯 Kotlin StreamingPngWriter；
        // 其他格式暂不支持流式，明确报错并给出建议。
        if (!canFitSingleBitmap) {
            when (job.encodeOptions.format) {
                OutputFormat.JXL -> {
                    processTiledStreamingJxl(
                        engine, srcUri, srcW, srcH, factor, baseName, job, progress
                    )
                    // 占位：process() 检测 streamingDoneUri 后直接返回，不走到尺寸校正
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                OutputFormat.PNG -> {
                    processTiledStreamingPng(
                        engine, srcUri, srcW, srcH, factor, baseName, job, progress
                    )
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                OutputFormat.JPEG -> {
                    processTiledStreamingJpeg(
                        engine, srcUri, srcW, srcH, factor, baseName, job, progress
                    )
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
                else -> throw IllegalStateException(
                    "输出图片过大（${outPx / 1_000_000}MP ≈ ${outputBitmapBytes / 1024 / 1024}MB）。" +
                        "该尺寸请把输出格式切换为 JPEG / PNG / JXL（支持流式写出）"
                )
            }
        }

        // [FIX 2026-08-11] 输出 bitmap > 200MB 时抛异常，避免 OOM → native SIGSEGV
        // 超大图可后续走 processTiledMultiPng（v8.1 已实现但输出路径需重构）
        if (!canFitSingleBitmap) {
            throw IllegalStateException(
                "输出图片过大（${outPx}px ≈ ${outputBitmapBytes / 1024 / 1024}MB），" +
                "超过单 canvas 上限 200MB。请缩小图片或降低放大倍数"
            )
        }
        return processTiledSingleCanvas(
            engine, srcUri, srcW, srcH, plan, factor, outW, outH, progress
        )
    }

    /**
     * 流式 JPEG：超大输出不驻留整图（纯 Kotlin StreamingJpegWriter）。
     * 行带两阶段，同 PNG 版。
     */
    private suspend fun processTiledStreamingJpeg(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        factor: Int,
        baseName: String,
        job: ImageJob,
        progress: (Float) -> Unit
    ): Bitmap {
        val outW = srcW * factor
        val outH = srcH * factor
        LogBus.i("ImageProcessor", "🟩 streaming-JPEG: src=${srcW}x${srcH} → out=${outW}x${outH} (${outW.toLong() * outH}px)")

        val tile = MemoryBudget.maxTileEdge(context).coerceIn(128, 256)
        val outTile = tile * factor
        val bands = (srcH + tile - 1) / tile
        val cols = (srcW + tile - 1) / tile
        val totalTiles = bands * cols
        var doneTiles = 0

        val displayName = "${baseName}_${factor}x"
        val quality = QualityMapper.directQuality(job.encodeOptions)

        val uri = MediaStoreWriter.writeJpegStreaming(
            context = context,
            displayName = displayName,
            outputDirUri = outputDirProvider(),
            width = outW,
            height = outH,
            quality = quality,
            produce = { feed ->
                for (band in 0 until bands) {
                    val y0 = band * tile
                    val th = (srcH - y0).coerceAtMost(tile)
                    data class BandTile(val xOff: Int, val bmp: Bitmap)
                    val rowBitmaps = mutableListOf<BandTile>()
                    try {
                        for (col in 0 until cols) {
                            val x0 = col * tile
                            val tw = (srcW - x0).coerceAtMost(tile)
                            val rect = android.graphics.Rect(x0, y0, x0 + tw, y0 + th)
                            val tileBmp = RegionDecoder.decodeRegion(context, srcUri, rect)
                                ?: throw IllegalStateException("分块解码失败 at $rect")
                            try {
                                val up = engine.upscale(
                                    input = tileBmp,
                                    plan = io.reascale.app.data.UpscalePlan(targetScale = factor)
                                ) { }
                                rowBitmaps.add(BandTile(x0 * factor, up))
                            } finally {
                                if (!tileBmp.isRecycled) tileBmp.recycle()
                            }
                            doneTiles++
                            progress(0.10f + (doneTiles.toFloat() / totalTiles) * 0.82f)
                        }
                        val bandOutH = rowBitmaps.maxOf { it.bmp.height }
                        val row = ByteArray(outW * 3)
                        for (yy in 0 until bandOutH) {
                            java.util.Arrays.fill(row, 0)
                            for (bt in rowBitmaps) {
                                if (yy >= bt.bmp.height) continue
                                val w = bt.bmp.width
                                val pxRow = IntArray(w)
                                bt.bmp.getPixels(pxRow, 0, w, 0, yy, w, 1)
                                for (xx in 0 until w) {
                                    val p = pxRow[xx]
                                    val o = (bt.xOff + xx) * 3
                                    row[o] = ((p shr 16) and 0xFF).toByte()
                                    row[o + 1] = ((p shr 8) and 0xFF).toByte()
                                    row[o + 2] = (p and 0xFF).toByte()
                                }
                            }
                            feed(band * outTile + yy, 0, row)
                        }
                    } finally {
                        rowBitmaps.forEach { if (!it.bmp.isRecycled) it.bmp.recycle() }
                        rowBitmaps.clear()
                    }
                }
            },
            progress = progress
        )
        if (uri == null) throw IllegalStateException("流式 JPEG 输出失败")
        streamingDoneUri = uri
        LogBus.i("ImageProcessor", "✅ streaming-JPEG done: $uri")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * 流式 PNG：超大输出不驻留整图（纯 Kotlin StreamingPngWriter）。
     * 行带两阶段：先推理行带内所有列 tile（保留位图），再逐输出行拼整行喂入。
     * 行带位图内存 ≈ nCols × bandOutH × bandOutW × 4（tile=256 → ~80MB）。
     */
    private suspend fun processTiledStreamingPng(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        factor: Int,
        baseName: String,
        job: ImageJob,
        progress: (Float) -> Unit
    ): Bitmap {
        val outW = srcW * factor
        val outH = srcH * factor
        LogBus.i("ImageProcessor", "🟩 streaming-PNG: src=${srcW}x${srcH} → out=${outW}x${outH} (${outW.toLong() * outH}px)")

        val tile = MemoryBudget.maxTileEdge(context).coerceIn(128, 256)
        val outTile = tile * factor
        val bands = (srcH + tile - 1) / tile
        val cols = (srcW + tile - 1) / tile
        val totalTiles = bands * cols
        var doneTiles = 0

        val displayName = "${baseName}_${factor}x"
        // PNG 无损：压缩级别取中低档平衡速度/体积
        val compressionLevel = if (job.encodeOptions.quality >= 100) 6 else 4

        val uri = MediaStoreWriter.writePngStreaming(
            context = context,
            displayName = displayName,
            outputDirUri = outputDirProvider(),
            width = outW,
            height = outH,
            compressionLevel = compressionLevel,
            produce = { feed ->
                for (band in 0 until bands) {
                    val y0 = band * tile
                    val th = (srcH - y0).coerceAtMost(tile)
                    // 1. 推理本行带所有列
                    data class BandTile(val xOff: Int, val bmp: Bitmap)
                    val rowBitmaps = mutableListOf<BandTile>()
                    try {
                        for (col in 0 until cols) {
                            val x0 = col * tile
                            val tw = (srcW - x0).coerceAtMost(tile)
                            val rect = android.graphics.Rect(x0, y0, x0 + tw, y0 + th)
                            val tileBmp = RegionDecoder.decodeRegion(context, srcUri, rect)
                                ?: throw IllegalStateException("分块解码失败 at $rect")
                            try {
                                val up = engine.upscale(
                                    input = tileBmp,
                                    plan = io.reascale.app.data.UpscalePlan(targetScale = factor)
                                ) { }
                                rowBitmaps.add(BandTile(x0 * factor, up))
                            } finally {
                                if (!tileBmp.isRecycled) tileBmp.recycle()
                            }
                            doneTiles++
                            progress(0.10f + (doneTiles.toFloat() / totalTiles) * 0.82f)
                        }
                        // 2. 行带内逐行拼整行喂出
                        val bandOutH = rowBitmaps.maxOf { it.bmp.height }
                        val row = ByteArray(outW * 3)
                        for (yy in 0 until bandOutH) {
                            java.util.Arrays.fill(row, 0)
                            for (bt in rowBitmaps) {
                                if (yy >= bt.bmp.height) continue
                                val w = bt.bmp.width
                                val pxRow = IntArray(w)
                                bt.bmp.getPixels(pxRow, 0, w, 0, yy, w, 1)
                                var xx = 0
                                while (xx < w) {
                                    val p = pxRow[xx]
                                    val o = (bt.xOff + xx) * 3
                                    row[o] = ((p shr 16) and 0xFF).toByte()
                                    row[o + 1] = ((p shr 8) and 0xFF).toByte()
                                    row[o + 2] = (p and 0xFF).toByte()
                                    xx++
                                }
                            }
                            feed(band * outTile + yy, 0, row)
                        }
                    } finally {
                        rowBitmaps.forEach { if (!it.bmp.isRecycled) it.bmp.recycle() }
                        rowBitmaps.clear()
                    }
                }
            },
            progress = progress
        )
        if (uri == null) throw IllegalStateException("流式 PNG 输出失败")
        streamingDoneUri = uri
        LogBus.i("ImageProcessor", "✅ streaming-PNG done: $uri")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * 流式 JXL：超大输出（bitmap >200MB）不驻留整图。
     * 外部按无重叠 tile 网格逐个解码→推理，输出行直接流式
     * 喂入 libjxl（jxl_stream_writer JNI），完成后写 MediaStore/SAF。
     *
     * 完成后设置 [streamingDoneUri]，process() 直接返回该 Uri。
     * 返回 1x1 占位 Bitmap（process() 不会走到尺寸校正分支）。
     */
    private suspend fun processTiledStreamingJxl(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        factor: Int,
        baseName: String,
        job: ImageJob,
        progress: (Float) -> Unit
    ): Bitmap {
        val outW = srcW * factor
        val outH = srcH * factor
        LogBus.i("ImageProcessor", "🟩 streaming-JXL: src=${srcW}x${srcH} → out=${outW}x${outH} (${outW.toLong() * outH}px)")

        // 无重叠 tile 网格（步长限制 512：引擎每块输出位图 + 累加缓冲受控，
        // 避免单块输出位图 >200MB 再 OOM）
        val tile = MemoryBudget.maxTileEdge(context).coerceIn(128, 512)
        val tiles = mutableListOf<Tile>()
        var ty = 0
        while (ty < srcH) {
            val th = (srcH - ty).coerceAtMost(tile)
            var tx = 0
            while (tx < srcW) {
                val tw = (srcW - tx).coerceAtMost(tile)
                tiles.add(Tile(tx, ty, tw, th))
                tx += tile
            }
            ty += tile
        }
        val tileCount = tiles.size.coerceAtLeast(1)
        LogBus.i("ImageProcessor", "🟩 streaming-JXL tiles=${tileCount} tile=${tile}")

        val displayName = "${baseName}_${factor}x"
        val quality = QualityMapper.directQuality(job.encodeOptions)
        val lossless = quality >= 100

        val uri = MediaStoreWriter.writeJxlStreaming(
            context = context,
            displayName = displayName,
            outputDirUri = outputDirProvider(),
            width = outW,
            height = outH,
            quality = quality,
            lossless = lossless,
            produce = { feed ->
                var processed = 0
                for (t in tiles) {
                    val rect = android.graphics.Rect(
                        t.x, t.y,
                        (t.x + t.w).coerceAtMost(srcW),
                        (t.y + t.h).coerceAtMost(srcH)
                    )
                    val tileBmp = RegionDecoder.decodeRegion(context, srcUri, rect)
                        ?: throw IllegalStateException("分块解码失败 at $rect")
                    try {
                        val upscaled = engine.upscale(
                            input = tileBmp,
                            plan = io.reascale.app.data.UpscalePlan(targetScale = factor)
                        ) { }
                        try {
                            val uW = upscaled.width
                            val uH = upscaled.height
                            val px = IntArray(uW * uH)
                            upscaled.getPixels(px, 0, uW, 0, 0, uW, uH)
                            val row = ByteArray(uW * 3)
                            val outX = t.x * factor
                            val outY = t.y * factor
                            // 行池覆盖语义 = 画布覆盖拼接（后块覆盖重叠区）
                            for (yy in 0 until uH) {
                                val base = yy * uW
                                for (xx in 0 until uW) {
                                    val p = px[base + xx]
                                    row[xx * 3] = ((p shr 16) and 0xFF).toByte()
                                    row[xx * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                                    row[xx * 3 + 2] = (p and 0xFF).toByte()
                                }
                                feed(outY + yy, outX, row)
                            }
                        } finally {
                            if (!upscaled.isRecycled) upscaled.recycle()
                        }
                    } finally {
                        if (!tileBmp.isRecycled) tileBmp.recycle()
                    }
                    processed++
                    progress(0.10f + (processed.toFloat() / tileCount) * 0.82f)
                }
            },
            progress = progress
        )
        if (uri == null) {
            throw IllegalStateException("流式 JXL 输出失败")
        }
        streamingDoneUri = uri
        LogBus.i("ImageProcessor", "✅ streaming-JXL done: $uri")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    /**
     * v8 子函数：单 canvas 分块处理（适用于 output ≤ 200MB bitmap）
     */
    private suspend fun processTiledSingleCanvas(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        plan: TilePlan,
        factor: Int,
        outW: Int,
        outH: Int,
        progress: (Float) -> Unit
    ): Bitmap {
        val canvas = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(canvas)
        val tileCount = plan.tiles.size.coerceAtLeast(1)
        var processedTiles = 0

        for (tile in plan.tiles) {
            val rect = android.graphics.Rect(
                tile.x, tile.y,
                (tile.x + tile.w).coerceAtMost(srcW),
                (tile.y + tile.h).coerceAtMost(srcH)
            )
            val tileBmp = RegionDecoder.decodeRegion(context, srcUri, rect)
                ?: throw IllegalStateException("分块解码失败 at $rect")
            try {
                // [FIX 2026-08-17] 块内引擎进度也细分：总进度 = (已处理块 + 块内进度) / 总块数
                val upscaledTile = engine.upscale(
                    input = tileBmp,
                    plan = io.reascale.app.data.UpscalePlan(targetScale = factor),
                    progress = { p ->
                        progress(0.10f + ((processedTiles + p) / tileCount) * 0.80f)
                    }
                )
                try {
                    val tileOutX = tile.x * factor
                    val tileOutY = tile.y * factor
                    androidCanvas.drawBitmap(
                        upscaledTile,
                        tileOutX.toFloat(),
                        tileOutY.toFloat(),
                        null
                    )
                } finally {
                    if (!upscaledTile.isRecycled) upscaledTile.recycle()
                }
            } finally {
                if (!tileBmp.isRecycled) tileBmp.recycle()
            }
            processedTiles++
            progress(0.10f + (processedTiles.toFloat() / tileCount) * 0.78f)
        }
        return canvas
    }

    /**
     * v8.1 子函数：multi-PNG 输出（适用于 output > 200MB bitmap）
     *
     * 1 亿像素 @ 2x = 20000x20000 = 400M pixel = 1.6GB bitmap → Android Bitmap 100% 失败
     * 解决：拆 4x4 = 16 个 sub-canvas（每块 ≤ 100MB bitmap），每个 sub-canvas 完成后立即 compress(PNG, 95)
     *       写入 MediaStore 文件：displayName_row_col.png
     * 内存峰值：sub-canvas 100MB + tile bitmap + 其它 ~80MB = 180MB < 256MB ✅
     */
    private suspend fun processTiledMultiPng(
        engine: UpscaleEngine,
        srcUri: Uri,
        srcW: Int,
        srcH: Int,
        plan: TilePlan,
        factor: Int,
        outW: Int,
        outH: Int,
        baseName: String,
        profile: EngineProfile,
        progress: (Float) -> Unit
    ): Uri {
        // 决定 4x4 sub-canvas 数（output 长宽方向）
        val subCanvasCount = 4
        val subW = outW / subCanvasCount
        val subH = outH / subCanvasCount
        LogBus.i("ImageProcessor", "🟩 v8.1 multi-PNG: out=${outW}x${outH}, sub-canvas=4x4 of ~${subW}x${subH}")

        var primaryUri: Uri? = null
        val baseDisplayName = "${baseName}_${profile.capabilities.baseScale.toInt()}x"
        val totalSubCanvas = subCanvasCount * subCanvasCount
        var processedSubCanvas = 0

        for (subRow in 0 until subCanvasCount) {
            for (subCol in 0 until subCanvasCount) {
                val sxOrg = subCol * subW
                val syOrg = subRow * subH
                val sxEnd = if (subCol == subCanvasCount - 1) outW else (subCol + 1) * subW
                val syEnd = if (subRow == subCanvasCount - 1) outH else (subRow + 1) * subH
                val curSubW = sxEnd - sxOrg
                val curSubH = syEnd - syOrg
                val subCanvasBitmap = Bitmap.createBitmap(curSubW, curSubH, Bitmap.Config.ARGB_8888)
                val subCanvas = android.graphics.Canvas(subCanvasBitmap)
                LogBus.i("ImageProcessor", "🟩 sub-canvas [$subRow,$subCol]: ${curSubW}x${curSubH}")

                // 处理所有影响此 sub-canvas 的 tile
                for (tile in plan.tiles) {
                    val rect = android.graphics.Rect(
                        tile.x, tile.y,
                        (tile.x + tile.w).coerceAtMost(srcW),
                        (tile.y + tile.h).coerceAtMost(srcH)
                    )
                    val tileBmp = RegionDecoder.decodeRegion(context, srcUri, rect)
                        ?: throw IllegalStateException("分块解码失败 at $rect")
                    try {
                        val upscaledTile = engine.upscale(
                            input = tileBmp,
                            plan = io.reascale.app.data.UpscalePlan(targetScale = factor),
                            progress = { /* 块内进度不细分 */ }
                        )
                        try {
                            val tileOutX = tile.x * factor
                            val tileOutY = tile.y * factor
                            val localX = tileOutX - sxOrg
                            val localY = tileOutY - syOrg
                            if (tileOutX + upscaledTile.width > sxOrg && tileOutX < sxEnd &&
                                tileOutY + upscaledTile.height > syOrg && tileOutY < syEnd) {
                                subCanvas.drawBitmap(
                                    upscaledTile,
                                    localX.toFloat(),
                                    localY.toFloat(),
                                    null
                                )
                            }
                        } finally {
                            if (!upscaledTile.isRecycled) upscaledTile.recycle()
                        }
                    } finally {
                        if (!tileBmp.isRecycled) tileBmp.recycle()
                    }
                }

                // 立即 compress(PNG, 95) → 写 MediaStore
                val subDisplayName = "${baseDisplayName}_r${subRow}_c${subCol}"
                val subUri = MediaStoreWriter.write(
                    context = context,
                    bitmap = subCanvasBitmap,
                    options = io.reascale.app.data.EncodeOptions(
                        format = io.reascale.app.data.OutputFormat.PNG,
                        quality = 95
                    ),
                    displayName = subDisplayName
                )
                if (!subCanvasBitmap.isRecycled) subCanvasBitmap.recycle()
                if (subUri != null) {
                    if (primaryUri == null) primaryUri = subUri
                    LogBus.i("ImageProcessor", "✅ sub-canvas [$subRow,$subCol] written: $subUri")
                } else {
                    LogBus.w("ImageProcessor", "❌ sub-canvas [$subRow,$subCol] write FAILED")
                }
                processedSubCanvas++
                progress(0.10f + (processedSubCanvas.toFloat() / totalSubCanvas) * 0.85f)
            }
        }
        return primaryUri ?: throw IllegalStateException("multi-PNG 全部 sub-canvas 写入失败")
    }

    companion object {

        /**
         * 解码原始分辨率图像
         */
        fun safeDecodeBitmap(
            context: android.content.Context,
            srcUri: Uri,
            srcW: Int,
            srcH: Int
        ): Bitmap? {
            val resolver = context.contentResolver
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(srcUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            val origW = opts.outWidth
            val origH = opts.outHeight
            if (origW <= 0 || origH <= 0) return null

            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            return resolver.openInputStream(srcUri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        }

        /**
         * 默认的 engineProvider
         *
         * 2026-08-18 兼容策略（ONNX 后端恢复）：
         * 1. `ncnn:xxx/yyy/file` → NCNN 原生（libreascale_ncnn.so）
         *    modelDir = "ncnn/xxx/yyy"，paramName = "file.param"
         * 2. `*.param` 文件路径 → NCNN 原生（用户导入 ncnn 模型）
         * 3. `*.onnx` 文件路径 → OnnxEngine（用户导入 ONNX 模型，ORT 自动探测）
         * 4. `asset:models/` 前缀 → 内置 ONNX 资产缺失，显式报错
         * 5. 其它 → 抛异常
         */
        fun defaultEngineProvider(
            context: Context,
            profile: EngineProfile,
            paramsProvider: () -> io.reascale.app.data.ModelParameters = { io.reascale.app.data.ModelParameters() }
        ): UpscaleEngine {
            val uri = profile.modelUri

            // 1) NCNN 原生路径
            if (uri.startsWith("ncnn:")) {
                val rest = uri.removePrefix("ncnn:")  // realcugan/models-se/up2x-no-denoise
                val lastSlash = rest.lastIndexOf('/')
                val modelDir = if (lastSlash >= 0) "ncnn/" + rest.substring(0, lastSlash) else "ncnn"
                val baseName = if (lastSlash >= 0) rest.substring(lastSlash + 1) else rest
                val paramName = "$baseName.param"
                try {
                    val engine = NcnnEngine.create(
                        profile = profile,
                        context = context,
                        modelDir = modelDir,
                        paramName = paramName,
                        scale = profile.capabilities.baseScale,
                        paramsProvider = paramsProvider
                    )
                    LogBus.i("ImageProcessor", "🟢 使用 NCNN 原生推理: ${profile.id} ($modelDir/$paramName)")
                    return engine
                } catch (t: Throwable) {
                    LogBus.e("ImageProcessor", "🔴 NCNN 加载失败: ${profile.id}", t)
                    throw IllegalStateException("NCNN 引擎加载失败: ${t.message}", t)
                }
            }

            // [FIX 2026-08-16] 用户导入模型：modelUri = 内部文件绝对路径（.param）
            if (uri.endsWith(".param") || uri.endsWith(".bin")) {
                val paramFile = java.io.File(uri)
                if (paramFile.exists() && uri.endsWith(".param")) {
                    val binFile = java.io.File(uri.replace(".param", ".bin"))
                    if (binFile.exists()) {
                        val engine = NcnnEngine.create(
                            profile = profile,
                            context = context,
                            modelDir = "",
                            paramName = paramFile.name,
                            scale = profile.capabilities.baseScale,
                            paramsProvider = paramsProvider,
                            fileParamPath = paramFile.absolutePath,
                            fileBinPath = binFile.absolutePath
                        )
                        LogBus.i("ImageProcessor", "🟢 使用导入的 NCNN 模型: ${profile.id} (${paramFile.name})")
                        return engine
                    }
                }
                throw IllegalStateException("导入的模型文件不存在: $uri")
            }

            // [FIX 2026-08-18] 用户导入 ONNX 模型：modelUri = 内部文件绝对路径（.onnx）
            // OnnxEngine 自动探测尺寸/语义（scale、残差、输入域），无需档案元数据准确
            if (uri.endsWith(".onnx")) {
                val onnxFile = java.io.File(uri)
                if (!onnxFile.exists()) {
                    throw IllegalStateException("导入的模型文件不存在: $uri")
                }
                try {
                    val engine = OnnxEngine.create(
                        profile = profile,
                        context = context,
                        modelFilePath = onnxFile.absolutePath,
                        paramsProvider = paramsProvider
                    )
                    LogBus.i("ImageProcessor", "🟢 使用导入的 ONNX 模型: ${profile.id} (${onnxFile.name})")
                    return engine
                } catch (t: Throwable) {
                    LogBus.e("ImageProcessor", "🔴 ONNX 加载失败: ${profile.id}", t)
                    throw IllegalStateException("ONNX 引擎加载失败: ${t.message}", t)
                }
            }

            // 2) 旧 asset:models/ 路径：内置 ONNX 资产不存在（GFPGAN 待补），显式报错
            if (uri.startsWith("asset:models/")) {
                LogBus.e("ImageProcessor", "🔴 内置 ONNX 资产缺失: ${profile.id} (uri=$uri)")
                throw IllegalStateException("内置模型文件缺失: $uri")
            }

            // 3) 其它路径
            LogBus.e("ImageProcessor", "🔴 未知模型 URI: ${profile.id} (uri=$uri)")
            throw IllegalStateException("不支持的模型路径: $uri")
        }
    }
}