package io.reascale.app.core.engine

import android.content.Context
import android.graphics.Bitmap
import io.reascale.app.data.EngineProfile
import io.reascale.app.data.ModelParameters
import io.reascale.app.data.UpscalePlan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.reascale.app.debug.LogBus

/**
 * NCNN 原生引擎（自己实现，不依赖参考 app 的 .so）
 * 封装 libreascale_ncnn.so（ncnn::Net + Vulkan 推理）
 *
 * 模型加载策略：
 * 1. 从 assets/ncnn/<modelDir>/ 加载 .param（通过 AAssetManager）+ .bin（ByteArray）
 * 2. 推理时按 tile 分块（避免大图单次 OOM）
 */
class NcnnEngine(
    override val engineId: String,
    private val baseScale: Int,
    private val context: Context,
    private val modelDir: String,
    private val paramName: String,
    private val gpuid: Int = -1,
    private val paramsProvider: () -> ModelParameters = { ModelParameters() },
    // [FIX 2026-08-16] 用户导入模型：从文件系统加载（param/bin 完整路径）
    private val fileParamPath: String? = null,
    private val fileBinPath: String? = null
) : UpscaleEngine {

    // [FIX 2026-08-11] @Volatile：QueueRunner 多 worker 线程可能并发访问
    // synchronized 块外的可见性需要 volatile 保证
    @Volatile private var engine: ReascaleNcnn? = null
    @Volatile private var isInitialized = false
    // [FIX 2026-08-11] Mutex 串行化全部推理：ncnn::Net 非线程安全，多 worker 并发调用必崩
    private val inferenceLock = Mutex()

    // [FIX 2026-08-16] 当前加载的 noise 值（变化时重载模型）
    private var currentNoise: Int = -1

    /**
     * noise → 模型文件名后缀（Real-CUGAN，官方 realcugan-ncnn-vulkan -n 参数）：
     * -1=no-denoise, 0=conservative, 1=denoise1x, 2=denoise2x, 3=denoise3x
     * （README: noise-level = -1/0/1/2/3，数值越大去噪越强，-1 无效果；
     *   assets/ncnn/realcugan/models-se|models-pro 均提供对应文件）
     */
    private fun noiseModelSuffix(noise: Int): String = when (noise) {
        0 -> "conservative"
        1 -> "denoise1x"
        2 -> "denoise2x"
        3 -> "denoise3x"
        else -> "no-denoise"
    }

    private fun ensureInit() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return

            val params = paramsProvider()
            val rn = ReascaleNcnn()
            try {
                // [PERF 2026-08-25] 默认 = CPU 核数（上限 6）：C++ 层 tile 行带 std::thread 并行，
                // 并行度即同时推理的 tile 数。原"默认 1 防 OpenMP 死锁"已由行带并行方案替代
                // （死锁场景是 Kotlin 多 worker 并发进 native，现仍被 inferenceLock 挡住）。
                val numThreads = if (params.concurrencyOverride.enabled) {
                    params.concurrencyOverride.value.coerceIn(1, 8)
                } else {
                    Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                }
                rn.init(gpuid = gpuid, ttaMode = params.ttaMode.effective(), numThreads = numThreads)

                val loaded: Boolean
                if (fileParamPath != null && fileBinPath != null) {
                    // 用户导入模型：文件系统加载
                    loaded = rn.loadFromFile(fileParamPath, fileBinPath)
                    if (!loaded) {
                        throw IllegalStateException("loadFromFile 返回 false（C++ 层日志有详细原因）")
                    }
                } else {
                    // 内置模型：assets 加载（noise 变化时切换对应模型文件）
                    val assetManager = context.assets
                    // paramName 形如 "up2x-no-denoise.param"，按 noise 替换后缀
                    val base = paramName.removeSuffix(".param")
                    val noiseParam = if (currentNoise >= 0) {
                        // up2x-no-denoise → up2x-denoise3x
                        // 取 scale 前缀（up2x/up3x/up4x）：base 是 "up2x-no-denoise" → 前缀 "up2x"
                        val scalePrefix = base.substringBefore('-')  // "up2x"
                        scalePrefix + "-" + noiseModelSuffix(currentNoise) + ".param"
                    } else {
                        paramName
                    }
                    val paramPath = "$modelDir/$noiseParam"
                    val binPath = paramPath.replace(".param", ".bin")
                    val binBytes = assetManager.open(binPath).use { it.readBytes() }
                    loaded = rn.loadFromAssets(assetManager, paramPath, binBytes)
                    if (!loaded) {
                        throw IllegalStateException("loadFromAssets 返回 false（C++ 层日志有详细原因）")
                    }
                    LogBus.i("NcnnEngine", "🔄 加载模型: $paramPath (noise=$currentNoise)")
                }

                rn.setTileSize(192)  // 固定官方推荐值
                rn.setNumThreads(numThreads)

                engine = rn
                isInitialized = true
                LogBus.i("NcnnEngine", "✅ NCNN 引擎初始化成功: $engineId, gpuid=$gpuid, tileSize=192")
            } catch (t: Throwable) {
                rn.release()
                LogBus.e("NcnnEngine", "❌ ncnn init failed", t)
                throw t
            }
        }
    }

    override fun upscale(
        input: Bitmap,
        plan: UpscalePlan,
        progress: (Float) -> Unit
    ): Bitmap {
        // [FIX 2026-08-11] 串行化推理防止 ncnn 内部状态冲突
        // 使用 runBlocking 在同步接口中调用协程 Mutex
        // 因为 UpscaleEngine.upscale 是同步接口，不能挂起
        return kotlinx.coroutines.runBlocking {
            inferenceLock.withLock {
                _upscale(input, plan, progress)
            }
        }
    }

    private fun _upscale(
        input: Bitmap,
        plan: UpscalePlan,
        progress: (Float) -> Unit
    ): Bitmap {
        ensureInit()

        val params = paramsProvider()
        val nativeScale = baseScale
        // [FIX 2026-08-16] 只读 3 个可调参数，其余强制模型默认值
        // tileSize 固定 192；prepadding 由 C++ 自动探测（模型精确 crop 反推），Kotlin 不猜测
        val prepad = 0  // 0 = 让 C++ 用探测值
        val noise = if (params.noiseLevel.enabled) params.noiseLevel.value else -1
        val tileSize = 192
        // [FIX 2026-08-16] noise 变化 → 重载对应模型
        if (noise != currentNoise) {
            close()  // 释放旧引擎
            currentNoise = noise
            ensureInit()
        }

        val rn = engine ?: throw IllegalStateException("引擎未初始化")

        // [FIX 2026-08-16 关键] CHAIN 路径：targetScale > baseScale 时循环放大
        // 例：2x 模型 target=4 → 2 次 2x；2x 模型 target=8 → 3 次 2x
        // 非整除（如 3x 模型 target=4）：只能做 baseScale 次，输出后由外层缩放
        val targetScale = plan.targetScale
        var chainCount = 1
        var remaining = targetScale
        while (remaining > nativeScale && remaining % nativeScale == 0) {
            remaining /= nativeScale
            chainCount++
        }
        if (targetScale < nativeScale) {
            // BASIC_DOWNSCALE：引擎做 baseScale，外层 ImageProcessor 下采样
            chainCount = 1
        }

        // [FIX 2026-08-17] tile 级实时进度：
        // C++ 每完成一个 tile 回调 0..1；这里映射到链式放大的总进度（节流 1% 防抖）
        var lastSent = -1f
        fun emit(p: Float) {
            val clamped = p.coerceIn(0f, 1f)
            if (clamped - lastSent >= 0.01f || clamped >= 1f) {
                lastSent = clamped
                progress(clamped)
            }
        }
        emit(0.02f)

        LogBus.i("NcnnEngine", "▶️ upscale START: in=${input.width}x${input.height}, baseScale=$nativeScale, target=$targetScale, chain=$chainCount, tileSize=$tileSize, prepad=$prepad, noise=$noise")
        progress(0.05f)

        var current = input
        var result: Bitmap = current
        try {
            repeat(chainCount) { idx ->
                val r = rn.process(
                    input = current,
                    scale = nativeScale,
                    noise = noise,
                    tileSize = tileSize,
                    prepadding = prepad,
                    onProgress = { tileP ->
                        // 本次 chain 内 tile 进度 → 全局进度
                        emit((idx + tileP) / chainCount)
                    }
                )
                if (idx < chainCount - 1 && current !== input && !current.isRecycled) {
                    current.recycle()
                }
                current = r
                result = r
            }
        } catch (t: Throwable) {
            // [FIX 2026-08-11] process 失败 = 引擎状态损坏，释放后重建
            LogBus.e("NcnnEngine", "❌ process failed: in=${input.width}x${input.height} scale=$nativeScale", t)
            close()
            throw t
        }
        LogBus.i("NcnnEngine", "✅ upscale OK: out=${result.width}x${result.height} (chain=$chainCount)")
        emit(1f)
        return result
    }

    override fun close() {
        engine?.release()
        engine = null
        isInitialized = false
    }

    companion object {
        fun create(
            profile: EngineProfile,
            context: Context,
            modelDir: String,
            paramName: String,
            scale: Int,
            paramsProvider: () -> ModelParameters = { ModelParameters() },
            fileParamPath: String? = null,
            fileBinPath: String? = null
        ): NcnnEngine {
            return NcnnEngine(
                engineId = profile.id,
                baseScale = scale,
                context = context,
                modelDir = modelDir,
                paramName = paramName,
                gpuid = -1,
                paramsProvider = paramsProvider,
                fileParamPath = fileParamPath,
                fileBinPath = fileBinPath
            )
        }
    }
}