package io.reascale.app.core.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.ModelParameters
import io.reascale.app.data.UpscalePlan
import io.reascale.app.debug.LogBus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer

/**
 * ONNX 推理引擎（onnxruntime-android，CPU）
 *
 * 2026-08-18 恢复：onnxruntime-android 1.23.2 重新接入，支持用户导入 .onnx 模型。
 *
 * 与 NCNN 引擎一致的三大自动探测（解决"第三方模型尺寸/语义未知"问题）：
 * 1. probe-size   —— 双尺寸灰输入推理，差分 scale、out = in*scale - k、crop/prepadding
 * 2. probe-semantic—— 灰 0.5 输入的输出均值判定残差 vs 完整图像（0.5 灰 → 残差≈0）
 * 3. probe-domain —— 灰 127.5 对照，判定模型输入域 0..1（归一化）vs 0..255（原始）
 *
 * 推理按 tile 分块（默认 192 + prepadding 重叠），与 NCNN 路径一致；
 * 输出公式：residual → out = in + y；完整图像 → out = y*std + mean（域 0..255 时 /255）。
 */
class OnnxEngine(
    override val engineId: String,
    private val context: Context,
    /** 用户导入模型：内部文件绝对路径（.onnx） */
    private val modelFilePath: String? = null,
    /** 内置模型：assets 相对路径（当前无内置 ONNX，预留） */
    private val assetPath: String? = null,
    /** 档案启发式 baseScale（探测失败时兜底） */
    private val profileScale: Int = 1,
    /** 档案启发式归一化参数（探测后按模型实际行为覆盖） */
    private val profileMean: Float = 0f,
    private val profileStd: Float = 1f,
    private val paramsProvider: () -> ModelParameters = { ModelParameters() }
) : UpscaleEngine {

    /** 探测结果（一次会话建立后固定） */
    private data class Probe(
        val scale: Int,          // 模型原生倍率（差分探测）
        val k: Int,              // out = in*scale - k（k>=0）
        val crop: Int,           // k/scale（对称裁剪语义）
        val prepadding: Int,     // max(16, crop/2)：tile 重叠
        val residual: Boolean,   // 残差模型（输出 = 输入 + 残差）
        val domain255: Boolean,  // 模型期望 0..255 原始输入（否则 0..1 归一化）
        val fixed: Boolean,      // 固定输入尺寸（不 tile，整图 resize 后单次推理）
        val inH: Int, val inW: Int, val inC: Int,     // 模型输入（fixed 时有效）
        val outH: Int, val outW: Int,                 // 模型输出（fixed 时有效）
        val nchw: Boolean,       // 输入 NCHW（false = NHWC）
        val outC: Int,           // 输出通道（1 = 灰图复制 RGB，>=3 取前 3）
        val wholeImage: Boolean  // 探测失败兜底：整图单次推理（不 tile）
    )

    @Volatile private var session: OrtSession? = null
    @Volatile private var probe: Probe? = null
    private val inferenceLock = Mutex()

    private fun ensureInit(): Pair<OrtSession, Probe> {
        session?.let { return it to probe!! }
        synchronized(this) {
            session?.let { return it to probe!! }
            val params = paramsProvider()
            val numThreads = if (params.concurrencyOverride.enabled) {
                params.concurrencyOverride.value.coerceIn(1, 4)
            } else {
                minOf(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                setIntraOpNumThreads(numThreads)
                setInterOpNumThreads(1)
            }
            try {
                val bytes = modelFilePath?.let { java.io.File(it).readBytes() }
                    ?: context.assets.open(assetPath!!).use { it.readBytes() }
                val s = env.createSession(bytes, opts)
                val p = probeModel(env, s, numThreads)
                session = s
                probe = p
                LogBus.i(
                    "OnnxEngine",
                    "✅ ONNX 引擎初始化成功: $engineId scale=${p.scale} k=${p.k} residual=${p.residual} " +
                        "domain255=${p.domain255} fixed=${p.fixed} threads=$numThreads"
                )
                return s to p
            } catch (t: Throwable) {
                LogBus.e("OnnxEngine", "❌ ONNX init failed: $engineId", t)
                throw t
            } finally {
                opts.close()
            }
        }
    }

    // ============ 模型探测 ============

    /** 单次灰输入推理，返回输出浮点数组 + 输出形状 */
    private data class GrayOut(val data: FloatArray, val h: Int, val w: Int, val c: Int)

    private fun runGray(
        env: OrtEnvironment, session: OrtSession,
        inName: String, outName: String, inC: Int, nchw: Boolean,
        h: Int, w: Int, value: Float
    ): GrayOut {
        val buf = FloatBuffer.allocate(inC * h * w)
        for (i in 0 until inC * h * w) buf.put(value)
        buf.flip()
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, inC.toLong(), h.toLong(), w.toLong()))
        try {
            val res = session.run(mapOf(inName to tensor))
            try {
                val t = res.get(outName).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("模型输出缺失: $outName")
                val info = t.info as? TensorInfo
                    ?: throw IllegalStateException("输出不是张量")
                if (info.type != OnnxJavaType.FLOAT) {
                    throw IllegalStateException("输出类型 ${info.type} 不支持（需要 float32）")
                }
                val (oh, ow, oc) = tensorDims(info.shape)
                return GrayOut(flattenFloatArray(t.value, oh * ow * oc), oh, ow, oc)
            } finally {
                res.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun probeModel(env: OrtEnvironment, session: OrtSession, threads: Int): Probe {
        val inInfo = session.inputInfo.values.first()
        val outInfo = session.outputInfo.values.first()
        val inName = inInfo.name
        val outName = outInfo.name
        val inT = inInfo.info as? TensorInfo
            ?: throw IllegalStateException("输入不是张量")
        val inShape = inT.shape
        if (inShape.size != 4) {
            throw IllegalStateException("不支持的输入维度 ${inShape.size}D（需要 4D NCHW/NHWC）")
        }
        // NCHW [N,C,H,W] vs NHWC [N,H,W,C]：末位是通道 → NHWC
        val nchw = inShape[3] != 3L
        val inC = (if (nchw) inShape[1] else inShape[3]).toInt()
        if (inC != 1 && inC != 3) {
            throw IllegalStateException("不支持的输入通道数 $inC（需要 1 或 3）")
        }
        val inH = if (nchw) inShape[2].toInt() else inShape[1].toInt()
        val inW = if (nchw) inShape[3].toInt() else inShape[2].toInt()
        val fixed = inH > 0 && inW > 0

        val m = profileMean
        val s = profileStd
        val grayA = if (m == 0f) 0.5f else (0.5f - m) / s   // 0..1 域中性灰（归一化空间）

        return try {
            // === 动态尺寸：T1/T2 差分探测 ===
            val T1 = 64
            val T2 = 96
            val out1 = runGray(env, session, inName, outName, inC, nchw, T1, T1, grayA)
            val out2 = runGray(env, session, inName, outName, inC, nchw, T2, T2, grayA)
            val out255 = runGray(env, session, inName, outName, inC, nchw, T1, T1, 127.5f)

            // === 语义探测（残差 vs 完整图像）===
            val aA = avgAbsDenorm(out1, m, s)
            val aB = avgAbsDenorm(out255, m, s)
            val residual: Boolean
            val domain255: Boolean
            when {
                aA >= 0.05f -> { residual = false; domain255 = false }   // 0..1 完整图像
                aB >= 1.0f -> { residual = false; domain255 = true }     // 0..255 完整图像
                else -> { residual = true; domain255 = false }           // 残差（0..1 域）
            }
            LogBus.i(
                "OnnxEngine",
                "probe-semantic: in0.5→avg|v|=%.5f | in127.5→avg|v|=%.4f | 模型=%s".format(aA, aB,
                    if (residual) "残差(输出=输入+残差)" else if (domain255) "完整图像(0..255 域)" else "完整图像(0..1 域)")
            )

            // === 尺寸公式探测 ===
            val scaleEst = (out2.w - out1.w).toDouble() / (T2 - T1)
            if (scaleEst < 1.0 || scaleEst > 16.0) {
                LogBus.w("OnnxEngine", "探测失败: 差分 scale=$scaleEst 不合理，回退档案 baseScale=$profileScale")
                return buildProbe(
                    scale = profileScale, k = 0, residual = residual, domain255 = domain255,
                    fixed = false, inH = 0, inW = 0, inC = inC, outH = 0, outW = 0,
                    nchw = nchw, outC = out1.c, wholeImage = true
                )
            }
            val scale = scaleEst.toInt().coerceIn(1, 16)
            var k = (T1 * scale - out1.w).coerceAtLeast(0)
            if (k > 0 && k % scale != 0) k = 0  // 非整数倍裁剪：放弃 k（模型非常规）
            if (k > 0) {
                // k>0（左对齐裁剪模型）：tile 子区域映射不自洽 → 整图单次推理
                LogBus.w(
                    "OnnxEngine",
                    "probe-size: k=$k>0（裁剪模型），禁用 tile，整图单次推理（大图可能 OOM）"
                )
                return buildProbe(
                    scale = scale, k = k, residual = residual, domain255 = domain255,
                    fixed = false, inH = 0, inW = 0, inC = inC, outH = 0, outW = 0,
                    nchw = nchw, outC = out1.c, wholeImage = true
                )
            }
            val crop = k / scale
            val prepadding = maxOf(16, crop / 2)
            LogBus.i(
                "OnnxEngine",
                "probe-size: ${T1}x$T1→${out1.w}x${out1.h}, ${T2}x$T2→${out2.w}x${out2.h}, scale=$scale, out=in*$scale-$k, crop=$crop, prepadding=$prepadding"
            )
            buildProbe(
                scale = scale, k = k, residual = residual, domain255 = domain255,
                fixed = false, inH = 0, inW = 0, inC = inC, outH = 0, outW = 0,
                nchw = nchw, outC = out1.c, wholeImage = false
            ).let { it.copy(prepadding = prepadding, crop = crop) }
        } catch (t: OrtException) {
            // === 固定尺寸模型（如 GFPGAN 512×512）：整图 resize 后单次推理 ===
            if (fixed) {
                LogBus.i("OnnxEngine", "probe: 固定输入 ${inShape.contentToString()}，整图单次推理")
                val gray = if (m == 0f) 0.5f else (0.5f - m) / s
                val out = runGray(env, session, inName, outName, inC, nchw, inH, inW, gray)
                // 语义探测：灰输入输出均值 < 0.05 → 残差模型（mean=0 时生效）
                val aA = avgAbsDenorm(out, m, s)
                val residual = aA < 0.05f && m == 0f
                val scale = if (inH > 0 && out.h > 0) (out.h.toDouble() / inH).toInt().coerceIn(1, 16) else profileScale
                LogBus.i(
                    "OnnxEngine",
                    "probe-fixed: ${inH}x$inW→${out.h}x${out.w}, scale=$scale, residual=$residual (avg|v|=$aA)"
                )
                buildProbe(
                    scale = scale, k = 0, residual = residual, domain255 = false,
                    fixed = true, inH = inH, inW = inW, inC = inC, outH = out.h, outW = out.w,
                    nchw = nchw, outC = out.c, wholeImage = false
                )
            } else {
                LogBus.w("OnnxEngine", "probe: 动态尺寸探测失败（${t.message}），回退整图单次推理")
                buildProbe(
                    scale = profileScale, k = 0, residual = false, domain255 = false,
                    fixed = false, inH = 0, inW = 0, inC = inC, outH = 0, outW = 0,
                    nchw = nchw, outC = 3, wholeImage = true
                )
            }
        } catch (t: Throwable) {
            LogBus.e("OnnxEngine", "probe 失败", t)
            throw t
        }
    }

    private fun buildProbe(
        scale: Int, k: Int, residual: Boolean, domain255: Boolean,
        fixed: Boolean, inH: Int, inW: Int, inC: Int, outH: Int, outW: Int,
        nchw: Boolean, outC: Int, wholeImage: Boolean
    ): Probe = Probe(
        scale = scale, k = k, crop = if (k > 0) k / scale else 0, prepadding = 16,
        residual = residual, domain255 = domain255, fixed = fixed,
        inH = inH, inW = inW, inC = inC, outH = outH, outW = outW,
        nchw = nchw, outC = outC, wholeImage = wholeImage
    )

    /** 输出张量形状 → (h, w, c)
     * 支持 4D [N,C,H,W] / [N,H,W,C] 与 3D [C,H,W] / [H,W,C](常见于无 batch 输出)
     */
    private fun tensorDims(shape: LongArray): Triple<Int, Int, Int> {
        when (shape.size) {
            4 -> return if (shape[3] == 3L) {
                // NHWC [N,H,W,C]
                Triple(shape[1].toInt(), shape[2].toInt(), shape[3].toInt())
            } else {
                // NCHW [N,C,H,W]
                Triple(shape[2].toInt(), shape[3].toInt(), shape[1].toInt())
            }
            3 -> {
                // 3D: [C,H,W] (NCHW) 或 [H,W,C] (NHWC)
                // 启发式:末位=3 → NHWC;首维=3 且末位≠3 → NCHW;灰图(1) → NCHW
                val nhwc = shape[2] == 3L || (shape[0] != 1L && shape[2] == 1L)
                return if (nhwc) {
                    Triple(shape[0].toInt(), shape[1].toInt(), shape[2].toInt())
                } else {
                    Triple(shape[1].toInt(), shape[2].toInt(), shape[0].toInt())
                }
            }
            else -> throw IllegalStateException("输出维度 ${shape.size}D 不支持（需要 3D 或 4D）")
        }
    }

    /** 输出均值（|v|，反归一化到 0..1/0..255 域） */
    private fun avgAbsDenorm(g: GrayOut, m: Float, s: Float): Float {
        var sum = 0.0
        for (i in g.data.indices) {
            val v = g.data[i] * s + m
            sum += if (v < 0) -v else v
        }
        return if (g.data.isEmpty()) 0f else (sum / g.data.size).toFloat()
    }

    // ============ 推理 ============

    override fun upscale(input: Bitmap, plan: UpscalePlan, progress: (Float) -> Unit): Bitmap {
        return kotlinx.coroutines.runBlocking {
            inferenceLock.withLock { _upscale(input, plan, progress) }
        }
    }

    private fun _upscale(input: Bitmap, plan: UpscalePlan, progress: (Float) -> Unit): Bitmap {
        val (sess, p) = ensureInit()
        val params = paramsProvider()

        // [FIX 2026-08-18] TTA 暂不支持 ONNX（NCNN 路径已支持），参数一致时忽略
        if (params.ttaMode.effective()) {
            LogBus.w("OnnxEngine", "⚠️ TTA 模式暂不支持 ONNX 引擎，忽略（engine=$engineId）")
        }

        var lastSent = -1f
        fun emit(pp: Float) {
            val clamped = pp.coerceIn(0f, 1f)
            if (clamped - lastSent >= 0.01f || clamped >= 1f) {
                lastSent = clamped
                progress(clamped)
            }
        }
        emit(0.02f)

        val targetScale = plan.targetScale
        if (p.fixed) {
            // === 固定输入模型：按模型输入尺寸 tile（边缘 REPLICATE pad）→ 输出 = 输入×scale 精确 ===
            // 旧实现：整图 resize→单次推理。问题：大图外部 tile（ImageProcessor.processTiled）
            // 把每块 resize 到模型输入，输出固定尺寸 → 拼接错乱。改为按模型输入尺寸切块，
            // 每块输出 in*scale，与任意输入尺寸兼容（可被外部 tile 驱动）。
            LogBus.i(
                "OnnxEngine",
                "▶️ upscale(fixed-tile): in=${input.width}x${input.height}, model=${p.inW}x${p.inH}, scale=${p.scale} (engine=$engineId)"
            )
            progress(0.05f)
            val out = runTiledFixed(sess, p, input) { tileP -> emit(0.10f + tileP * 0.85f) }
            emit(1f)
            return out
        }

        if (p.wholeImage) {
            // === 探测失败兜底：整图单次推理（不 tile）===
            LogBus.w("OnnxEngine", "▶️ upscale(whole): in=${input.width}x${input.height} (engine=$engineId)")
            val out = runOnce(sess, p, input)
            emit(1f)
            return out
        }

        // === tile 分块 + 链式放大（与 NCNN 路径一致）===
        val nativeScale = p.scale
        var chainCount = 1
        var remaining = targetScale
        while (remaining > nativeScale && remaining % nativeScale == 0) {
            remaining /= nativeScale
            chainCount++
        }
        if (targetScale < nativeScale) chainCount = 1  // BASIC_DOWNSCALE：外层下采样

        LogBus.i(
            "OnnxEngine",
            "▶️ upscale START: in=${input.width}x${input.height}, scale=$nativeScale, target=$targetScale, chain=$chainCount, tile=192, prepad=${p.prepadding}, k=${p.k} (engine=$engineId)"
        )
        progress(0.05f)

        var current = input
        var result: Bitmap = current
        try {
            repeat(chainCount) { idx ->
                val r = runTiled(sess, p, current) { tileP ->
                    emit((idx + tileP) / chainCount)
                }
                if (idx < chainCount - 1 && current !== input && !current.isRecycled) {
                    current.recycle()
                }
                current = r
                result = r
            }
        } catch (t: Throwable) {
            LogBus.e("OnnxEngine", "❌ process failed: in=${input.width}x${input.height} scale=$nativeScale", t)
            close()
            throw t
        }
        LogBus.i("OnnxEngine", "✅ upscale OK: out=${result.width}x${result.height} (chain=$chainCount)")
        emit(1f)
        return result
    }

    private fun runTiled(
        sess: OrtSession, p: Probe, input: Bitmap,
        onTile: (Float) -> Unit
    ): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val inName = sess.inputInfo.keys.first()
        val outName = sess.outputInfo.keys.first()
        val sc = p.scale
        val k = p.k
        val pad = p.prepadding
        val tile = 192
        val inW = input.width
        val inH = input.height
        val outW = inW * sc - k
        val outH = inH * sc - k
        if (outW <= 0 || outH <= 0) {
            throw IllegalStateException("输出尺寸非法: ${inW}x${inH} scale=$sc k=$k → ${outW}x$outH")
        }
        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val xtiles = (inW + tile - 1) / tile
        val ytiles = (inH + tile - 1) / tile
        val tileTotal = xtiles * ytiles
        var done = 0

        for (ty in 0 until ytiles) {
            val y0 = ty * tile
            for (tx in 0 until xtiles) {
                val x0 = tx * tile
                // 输入区域（replicate 裁剪 + 4 对齐）
                val inX0 = maxOf(0, x0 - pad)
                val inY0 = maxOf(0, y0 - pad)
                val inX1 = minOf(inW, align4(x0 + tile + pad))
                val inY1 = minOf(inH, align4(y0 + tile + pad))
                if (inX1 <= inX0 || inY1 <= inY0) continue
                val rw = inX1 - inX0
                val rh = inY1 - inY0

                val region = IntArray(rw * rh)
                input.getPixels(region, 0, rw, inX0, inY0, rw, rh)

                // 构建 NCHW/NHWC float 输入
                val inC = p.inC
                val buf = FloatBuffer.allocate(inC * rh * rw)
                val m = profileMean
                val s = profileStd
                val use255 = p.domain255
                for (y in 0 until rh) {
                    for (x in 0 until rw) {
                        val px = region[y * rw + x]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        if (p.nchw) {
                            if (inC == 1) {
                                // 单通道模型：亮度近似
                                buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                            } else {
                                buf.put(y * rw + x, norm(r, m, s, use255))
                                buf.put(rh * rw + y * rw + x, norm(g, m, s, use255))
                                buf.put(2 * rh * rw + y * rw + x, norm(b, m, s, use255))
                            }
                        } else {
                            if (inC == 1) {
                                buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                            } else {
                                val idx = (y * rw + x) * 3
                                buf.put(idx, norm(r, m, s, use255))
                                buf.put(idx + 1, norm(g, m, s, use255))
                                buf.put(idx + 2, norm(b, m, s, use255))
                            }
                        }
                    }
                }

                // 推理
                val tensor = OnnxTensor.createTensor(
                    env, buf,
                    longArrayOf(1, inC.toLong(), rh.toLong(), rw.toLong())
                )
                var outData: FloatArray
                var outTW = 0
                var outTH = 0
                var outOC = p.outC
                var outNchw = false
                try {
                    val res = sess.run(mapOf(inName to tensor))
                    try {
                        val t = res.get(outName).orElse(null) as? OnnxTensor
                            ?: throw IllegalStateException("模型输出缺失: $outName")
                        val info = t.info as TensorInfo
                        if (info.type != OnnxJavaType.FLOAT) {
                            throw IllegalStateException("输出类型 ${info.type} 不支持（需要 float32）")
                        }
                        val (oh, ow, oc) = tensorDims(info.shape)
                        if (oc !in 1..4) {
                            throw IllegalStateException("无法识别输出布局（通道=$oc）")
                        }
                        outNchw = (info.shape.size == 4 && info.shape[3] != 3L) || (info.shape.size == 3 && info.shape[2] != 3L)
                        outTH = oh
                        outTW = ow
                        outOC = oc
                        outData = flattenFloatArray(t.value, ow * oh * oc)
                    } finally {
                        res.close()
                    }
                } finally {
                    tensor.close()
                }

                // 回写（输出 = 输入*scale - k，左对齐；边缘处裁剪负偏移）
                val tensorStartX = inX0 * sc - k
                val tensorStartY = inY0 * sc - k
                val dstX = x0 * sc - k
                val dstY = y0 * sc - k
                val copyX0 = maxOf(0, dstX)
                val copyY0 = maxOf(0, dstY)
                val copyX1 = minOf(outW, dstX + tile * sc)
                val copyY1 = minOf(outH, dstY + tile * sc)
                val copyW = copyX1 - copyX0
                val copyH = copyY1 - copyY0
                if (copyW <= 0 || copyH <= 0) continue
                val offX = copyX0 - tensorStartX
                val offY = copyY0 - tensorStartY
                if (offX < 0 || offY < 0 || offX + copyW > outTW || offY + copyH > outTH) {
                    LogBus.w(
                        "OnnxEngine",
                        "tile 输出越界: off=($offX,$offY) + ($copyW,$copyH) > out=($outTW,$outTH) (tile=$tx,$ty)"
                    )
                    continue
                }
                val outPixels = IntArray(copyW * copyH)
                val residual = p.residual && profileMean == 0f
                val mm = profileMean
                val ss = profileStd
                for (oy in 0 until copyH) {
                    val ty2 = offY + oy
                    val inY = (copyY0 + oy + k) / sc
                    for (ox in 0 until copyW) {
                        val tx2 = offX + ox
                        val inX = (copyX0 + ox + k) / sc
                        val rv: Double
                        val gv: Double
                        val bv: Double
                        if (outOC == 1) {
                            // 单通道：平面/交错布局同索引
                            rv = outData[ty2 * outTW + tx2].toDouble()
                            gv = rv
                            bv = rv
                        } else if (outNchw) {
                            // NCHW 平面布局：C*H*W 连续块，通道间隔 H*W
                            val stride = outTW * outTH
                            val base = ty2 * outTW + tx2
                            rv = outData[base].toDouble()
                            gv = outData[stride + base].toDouble()
                            bv = outData[2 * stride + base].toDouble()
                        } else {
                            // NHWC 交错布局：每像素 C 个连续值
                            val oi = (ty2 * outTW + tx2) * outOC
                            rv = outData[oi].toDouble()
                            gv = outData[oi + 1].toDouble()
                            bv = outData[oi + 2].toDouble()
                        }
                        // 反归一化 + 残差回加
                        var rr: Double
                        var gg: Double
                        var bb: Double
                        if (residual) {
                            // 残差模型（mean=0,std=1 域）：out = 输入(0..1) + 残差
                            val ix = (inX - inX0).coerceIn(0, rw - 1)
                            val iy = (inY - inY0).coerceIn(0, rh - 1)
                            val ip = region[iy * rw + ix]
                            val ir = ((ip shr 16) and 0xFF) / 255.0
                            val ig = ((ip shr 8) and 0xFF) / 255.0
                            val ib = (ip and 0xFF) / 255.0
                            rr = if (use255) (ir + rv) / 255.0 else ir + rv
                            gg = if (use255) (ig + gv) / 255.0 else ig + gv
                            bb = if (use255) (ib + bv) / 255.0 else ib + bv
                        } else if (use255) {
                            rr = rv / 255.0
                            gg = gv / 255.0
                            bb = bv / 255.0
                        } else {
                            rr = rv * ss + mm
                            gg = gv * ss + mm
                            bb = bv * ss + mm
                        }
                        val o = (oy * copyW + ox)
                        outPixels[o] = (0xFF shl 24) or
                            (clampByte(rr * 255.0) shl 16) or
                            (clampByte(gg * 255.0) shl 8) or
                            clampByte(bb * 255.0)
                    }
                }
                outBmp.setPixels(outPixels, 0, copyW, copyX0, copyY0, copyW, copyH)

                done++
                onTile(done.toFloat() / tileTotal)
            }
        }
        return outBmp
    }

    /**
     * 固定输入模型：按模型输入尺寸 (p.inW x p.inH) tile 推理
     *
     * 模型输入必须精确 p.inW x p.inH（ORT 固定张量要求），
     * 边缘不足时 REPLICATE pad（复制边缘像素），输出裁剪有效区域。
     * 输出 = 输入 × p.scale 精确（无 k 偏移）。
     * 与 runTiled 不同：无 pad 重叠，输入区域直接对齐模型输入尺寸。
     */
    private fun runTiledFixed(
        sess: OrtSession, p: Probe, input: Bitmap,
        onTile: (Float) -> Unit
    ): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val inName = sess.inputInfo.keys.first()
        val outName = sess.outputInfo.keys.first()
        val sc = p.scale
        val tile = p.inW  // 模型输入尺寸（128）
        val tileH = p.inH
        val inW = input.width
        val inH = input.height
        val outW = inW * sc
        val outH = inH * sc
        if (outW <= 0 || outH <= 0) {
            throw IllegalStateException("固定模型输出尺寸非法: ${inW}x${inH} scale=$sc → ${outW}x$outH")
        }
        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val xtiles = (inW + tile - 1) / tile
        val ytiles = (inH + tileH - 1) / tileH
        val tileTotal = xtiles * ytiles
        var done = 0

        for (ty in 0 until ytiles) {
            val y0 = ty * tileH
            for (tx in 0 until xtiles) {
                val x0 = tx * tile
                // 有效输入区域（无 pad 重叠）
                val inX0 = x0
                val inY0 = y0
                val inX1 = minOf(inW, x0 + tile)
                val inY1 = minOf(inH, y0 + tileH)
                val validW = inX1 - inX0
                val validH = inY1 - inY0
                if (validW <= 0 || validH <= 0) continue

                // 构建 REPLICATE pad 到 exact 模型输入尺寸
                val rw = tile
                val rh = tileH
                val region = IntArray(rw * rh)
                // 读有效像素填入 region 左上
                if (validW > 0 && validH > 0) {
                    val tmp = IntArray(validW * validH)
                    input.getPixels(tmp, 0, validW, inX0, inY0, validW, validH)
                    for (yy in 0 until validH) {
                        System.arraycopy(tmp, yy * validW, region, yy * rw, validW)
                    }
                    // 右侧 pad：复制最后一列
                    val lastCol = validW - 1
                    for (yy in 0 until validH) {
                        val edgePx = region[yy * rw + lastCol]
                        for (xx in validW until rw) {
                            region[yy * rw + xx] = edgePx
                        }
                    }
                    // 下方 pad：复制最后一行
                    val lastRowOff = (validH - 1) * rw
                    for (yy in validH until rh) {
                        System.arraycopy(region, lastRowOff, region, yy * rw, rw)
                    }
                }

                // 构建 NCHW float 输入（模型固定 NCHW [1,C,tile,tileH]）
                val inC = p.inC
                val buf = FloatBuffer.allocate(inC * rh * rw)
                val m = profileMean
                val s = profileStd
                val use255 = p.domain255
                for (y in 0 until rh) {
                    for (x in 0 until rw) {
                        val px = region[y * rw + x]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        if (p.nchw) {
                            if (inC == 1) {
                                buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                            } else {
                                buf.put(y * rw + x, norm(r, m, s, use255))
                                buf.put(rh * rw + y * rw + x, norm(g, m, s, use255))
                                buf.put(2 * rh * rw + y * rw + x, norm(b, m, s, use255))
                            }
                        } else {
                            if (inC == 1) {
                                buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                            } else {
                                val idx = (y * rw + x) * 3
                                buf.put(idx, norm(r, m, s, use255))
                                buf.put(idx + 1, norm(g, m, s, use255))
                                buf.put(idx + 2, norm(b, m, s, use255))
                            }
                        }
                    }
                }

                // 推理
                val tensor = OnnxTensor.createTensor(
                    env, buf,
                    longArrayOf(1, inC.toLong(), rh.toLong(), rw.toLong())
                )
                var outData: FloatArray
                var outTW = 0
                var outTH = 0
                var outOC = p.outC
                var outNchw = false
                try {
                    val res = sess.run(mapOf(inName to tensor))
                    try {
                        val t = res.get(outName).orElse(null) as? OnnxTensor
                            ?: throw IllegalStateException("固定模型输出缺失: $outName")
                        val info = t.info as TensorInfo
                        if (info.type != OnnxJavaType.FLOAT) {
                            throw IllegalStateException("固定模型输出类型 ${info.type} 不支持（需要 float32）")
                        }
                        val (oh, ow, oc) = tensorDims(info.shape)
                        if (oc !in 1..4) {
                            throw IllegalStateException("固定模型无法识别输出布局（通道=$oc）")
                        }
                        outNchw = (info.shape.size == 4 && info.shape[3] != 3L) || (info.shape.size == 3 && info.shape[2] != 3L)
                        outTH = oh
                        outTW = ow
                        outOC = oc
                        outData = flattenFloatArray(t.value, ow * oh * oc)
                    } finally {
                        res.close()
                    }
                } finally {
                    tensor.close()
                }

                // 回写：输出 = 输入 × scale 精确，从 tensor 左上复制有效区域
                val dstX = x0 * sc
                val dstY = y0 * sc
                val copyX0 = dstX
                val copyY0 = dstY
                val copyX1 = minOf(outW, dstX + tile * sc)
                val copyY1 = minOf(outH, dstY + tileH * sc)
                val copyW = copyX1 - copyX0
                val copyH = copyY1 - copyY0
                if (copyW <= 0 || copyH <= 0) continue
                // offX=0：tensor 从 0 开始（整块输出，无 pad 偏移）
                val offX = 0
                val offY = 0
                if (offX + copyW > outTW || offY + copyH > outTH) {
                    LogBus.w(
                        "OnnxEngine",
                        "固定模型 tile 输出越界: off=($offX,$offY) + ($copyW,$copyH) > out=($outTW,$outTH) (tile=$tx,$ty)"
                    )
                    continue
                }
                val outPixels = IntArray(copyW * copyH)
                val residual = p.residual && profileMean == 0f
                val mm = profileMean
                val ss = profileStd
                for (oy in 0 until copyH) {
                    val ty2 = offY + oy
                    val inY = (copyY0 + oy) / sc
                    for (ox in 0 until copyW) {
                        val tx2 = offX + ox
                        val inX = (copyX0 + ox) / sc
                        val rv: Double
                        val gv: Double
                        val bv: Double
                        if (outOC == 1) {
                            // 单通道：平面/交错布局同索引
                            rv = outData[ty2 * outTW + tx2].toDouble()
                            gv = rv
                            bv = rv
                        } else if (outNchw) {
                            // NCHW 平面布局：C*H*W 连续块，通道间隔 H*W
                            val stride = outTW * outTH
                            val base = ty2 * outTW + tx2
                            rv = outData[base].toDouble()
                            gv = outData[stride + base].toDouble()
                            bv = outData[2 * stride + base].toDouble()
                        } else {
                            // NHWC 交错布局：每像素 C 个连续值
                            val oi = (ty2 * outTW + tx2) * outOC
                            rv = outData[oi].toDouble()
                            gv = outData[oi + 1].toDouble()
                            bv = outData[oi + 2].toDouble()
                        }
                        var rr: Double
                        var gg: Double
                        var bb: Double
                        if (residual) {
                            // 残差：输入(0..1) + 残差
                            val ix = (inX - inX0).coerceIn(0, rw - 1)
                            val iy = (inY - inY0).coerceIn(0, rh - 1)
                            val ip = region[iy * rw + ix]
                            val ir = ((ip shr 16) and 0xFF) / 255.0
                            val ig = ((ip shr 8) and 0xFF) / 255.0
                            val ib = (ip and 0xFF) / 255.0
                            rr = if (use255) (ir + rv) / 255.0 else ir + rv
                            gg = if (use255) (ig + gv) / 255.0 else ig + gv
                            bb = if (use255) (ib + bv) / 255.0 else ib + bv
                        } else if (use255) {
                            rr = rv / 255.0
                            gg = gv / 255.0
                            bb = bv / 255.0
                        } else {
                            rr = rv * ss + mm
                            gg = gv * ss + mm
                            bb = bv * ss + mm
                        }
                        val o = (oy * copyW + ox)
                        outPixels[o] = (0xFF shl 24) or
                            (clampByte(rr * 255.0) shl 16) or
                            (clampByte(gg * 255.0) shl 8) or
                            clampByte(bb * 255.0)
                    }
                }
                outBmp.setPixels(outPixels, 0, copyW, copyX0, copyY0, copyW, copyH)

                done++
                onTile(done.toFloat() / tileTotal)
            }
        }
        return outBmp
    }

    /** 固定尺寸：整图单次推理（无 tile） */
    private fun runOnce(sess: OrtSession, p: Probe, input: Bitmap): Bitmap {
        val env = OrtEnvironment.getEnvironment()
        val inName = sess.inputInfo.keys.first()
        val outName = sess.outputInfo.keys.first()
        val inW = input.width
        val inH = input.height
        val rw = if (p.fixed) p.inW else inW
        val rh = if (p.fixed) p.inH else inH
        val inC = p.inC

        val region = IntArray(rw * rh)
        if (p.fixed) {
            // 固定尺寸：整图拉伸到模型输入尺寸
            val resized = Bitmap.createScaledBitmap(input, rw, rh, true)
            resized.getPixels(region, 0, rw, 0, 0, rw, rh)
            if (resized !== input && !resized.isRecycled) resized.recycle()
        } else {
            input.getPixels(region, 0, rw, 0, 0, rw, rh)
        }

        val m = profileMean
        val s = profileStd
        val use255 = p.domain255
        val buf = FloatBuffer.allocate(inC * rh * rw)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val px = region[y * rw + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                if (p.nchw) {
                    if (inC == 1) {
                        buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                    } else {
                        buf.put(y * rw + x, norm(r, m, s, use255))
                        buf.put(rh * rw + y * rw + x, norm(g, m, s, use255))
                        buf.put(2 * rh * rw + y * rw + x, norm(b, m, s, use255))
                    }
                } else {
                    if (inC == 1) {
                        buf.put(y * rw + x, norm((r + g + b) / 3, m, s, use255))
                    } else {
                        val idx = (y * rw + x) * 3
                        buf.put(idx, norm(r, m, s, use255))
                        buf.put(idx + 1, norm(g, m, s, use255))
                        buf.put(idx + 2, norm(b, m, s, use255))
                    }
                }
            }
        }

        val tensor = OnnxTensor.createTensor(
            env, buf,
            longArrayOf(1, inC.toLong(), rh.toLong(), rw.toLong())
        )
        var outData: FloatArray
        var outW: Int
        var outH: Int
        var outOC = p.outC
        var outNchw = false
        try {
            val res = sess.run(mapOf(inName to tensor))
            try {
                val t = res.get(outName).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("模型输出缺失: $outName")
                val info = t.info as TensorInfo
                if (info.type != OnnxJavaType.FLOAT) {
                    throw IllegalStateException("输出类型 ${info.type} 不支持（需要 float32）")
                }
                val (oh, ow, oc) = tensorDims(info.shape)
                if (oc !in 1..4) {
                    throw IllegalStateException("无法识别输出布局（通道=$oc）")
                }
                outNchw = (info.shape.size == 4 && info.shape[3] != 3L) || (info.shape.size == 3 && info.shape[2] != 3L)
                outH = oh
                outW = ow
                outOC = oc
                outData = flattenFloatArray(t.value, ow * oh * oc)
            } finally {
                res.close()
            }
        } finally {
            tensor.close()
        }

        val residual = p.residual && profileMean == 0f
        val outPixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val rv: Double
                val gv: Double
                val bv: Double
                if (outOC == 1) {
                    rv = outData[y * outW + x].toDouble()
                    gv = rv
                    bv = rv
                } else if (outNchw) {
                    val stride = outW * outH
                    val base = y * outW + x
                    rv = outData[base].toDouble()
                    gv = outData[stride + base].toDouble()
                    bv = outData[2 * stride + base].toDouble()
                } else {
                    val oi = (y * outW + x) * outOC
                    rv = outData[oi].toDouble()
                    gv = outData[oi + 1].toDouble()
                    bv = outData[oi + 2].toDouble()
                }
                var rr: Double
                var gg: Double
                var bb: Double
                if (residual) {
                    // 输出像素 x' 对应输入像素 (x' + k)/scale；整图模式 k 参与
                    val ix = ((x + p.k) / p.scale).coerceIn(0, inW - 1)
                    val iy = ((y + p.k) / p.scale).coerceIn(0, inH - 1)
                    val ip = region[iy * rw + ix]
                    val ir = ((ip shr 16) and 0xFF) / 255.0
                    val ig = ((ip shr 8) and 0xFF) / 255.0
                    val ib = (ip and 0xFF) / 255.0
                    rr = if (use255) (ir + rv) / 255.0 else ir + rv
                    gg = if (use255) (ig + gv) / 255.0 else ig + gv
                    bb = if (use255) (ib + bv) / 255.0 else ib + bv
                } else if (use255) {
                    rr = rv / 255.0
                    gg = gv / 255.0
                    bb = bv / 255.0
                } else {
                    rr = rv * s + m
                    gg = gv * s + m
                    bb = bv * s + m
                }
                outPixels[y * outW + x] = (0xFF shl 24) or
                    (clampByte(rr * 255.0) shl 16) or
                    (clampByte(gg * 255.0) shl 8) or
                    clampByte(bb * 255.0)
            }
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun norm(v: Int, m: Float, s: Float, use255: Boolean): Float =
        if (use255) v.toFloat() else ((v / 255.0f) - m) / s

    private fun clampByte(v: Double): Int = when {
        v <= 0.0 -> 0
        v >= 255.0 -> 255
        else -> v.toInt()
    }

    /** 展平 ORT 多维度输出数组为一维 FloatArray */
    private fun flattenFloatArray(value: Any, expectedSize: Int): FloatArray {
        if (value is FloatArray) return value
        val result = FloatArray(expectedSize)
        var idx = 0
        fun walk(arr: Any) {
            when (arr) {
                is FloatArray -> {
                    System.arraycopy(arr, 0, result, idx, arr.size)
                    idx += arr.size
                }
                is Array<*> -> arr.forEach { it?.let { walk(it) } }
                else -> throw IllegalStateException("无法展平输出: ${arr::class.java.name}")
            }
        }
        walk(value)
        if (idx != expectedSize) {
            throw IllegalStateException("展平尺寸不匹配: $idx != $expectedSize")
        }
        return result
    }

    private fun align4(v: Int): Int = (v + 3) and 0x7FFFFFFC

    override fun close() {
        session?.close()
        session = null
        probe = null
    }

    companion object {
        fun create(
            profile: EngineProfile,
            context: Context,
            modelFilePath: String? = null,
            assetPath: String? = null,
            paramsProvider: () -> ModelParameters = { ModelParameters() }
        ): OnnxEngine {
            return OnnxEngine(
                engineId = profile.id,
                context = context,
                modelFilePath = modelFilePath,
                assetPath = assetPath,
                profileScale = profile.capabilities.baseScale,
                profileMean = profile.capabilities.mean,
                profileStd = profile.capabilities.std,
                paramsProvider = paramsProvider
            )
        }
    }
}
