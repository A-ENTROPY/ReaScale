package io.reascale.app

import android.app.Application
import io.reascale.app.core.processing.ImageProcessor
import io.reascale.app.debug.LogBus
import io.reascale.app.data.EngineRepository
import io.reascale.app.data.ParamsRepository
import io.reascale.app.data.SettingsRepository
import io.reascale.app.queue.QueueManager
import io.reascale.app.queue.QueueRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口
 * 对应 §18.1 应用启动
 *
 * M1+ 阶段：
 * - 初始化 EngineRepository（4 个内置引擎种子）
 * - 初始化 SettingsRepository
 * - 初始化 QueueManager（内存版队列）
 * - 启动 QueueRunner（自动执行 PENDING Job）
 */
class ReaScaleApp : Application() {

    /** App 级 CoroutineScope，对应 §6 流水线 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** 引擎档案仓储（单例） */
    val engineRepository: EngineRepository by lazy { EngineRepository(this) }

    /** 模型参数仓储（单例 · Phase B 2026-08-04） */
    val paramsRepository: ParamsRepository by lazy { ParamsRepository(this) }

    /** 全局设置仓储（单例） */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** 队列管理器（单例，持久化版——进程被杀重启后任务不丢） */
    val queueManager: QueueManager by lazy { QueueManager(appScope, this) }

    /**
     * 图像处理流水线（优先真 ONNX 推理，缺模型回退到 StubEngine）
     * 2026-08-04 Phase B：把 paramsRepository 接入 OnnxEngine
     * - engine 每次创建时从 paramsRepository 取该 engine 的最新参数
     * - OnnxEngine 内部用 paramsProvider 懒求值，参数改了不需要重建 engine
     */
    private val imageProcessor: ImageProcessor by lazy {
        ImageProcessor(
            context = this,
            engineProvider = { profileId ->
                val profile = engineRepository.findById(profileId)
                    ?: error("Engine not found: $profileId")
                ImageProcessor.defaultEngineProvider(
                    context = this,
                    profile = profile,
                    paramsProvider = { paramsRepository.get(profileId) }
                )
            },
            // [FIX 2026-08-17] 输出目录：设置页配置的 SAF 目录（空 = 默认相册）
            outputDirProvider = { settingsRepository.get().outputDirUri }
        )
    }

    /** 队列执行器（M1 内存版调度，M7 升级到 WorkManager） */
    val queueRunner: QueueRunner by lazy {
        QueueRunner(
            context = this,
            scope = appScope,
            queue = queueManager,
            processor = imageProcessor
        )
    }

    /**
     * Application 初始化是否完成（用于 UI 兜底 + 防 race）
     * 等 engineRepository.initialize() 跑完才让 QueueRunner 调度，
     * 否则 500ms 轮询时找不到 EngineProfile → 全部 markFailed
     */
    @Volatile private var _ready: Boolean = false
    val isReady: Boolean get() = _ready

    override fun onCreate() {
        super.onCreate()
        instance = this

        // === 初始化日志总线（必须在所有日志之前）===
        LogBus.setSinkDir(java.io.File(filesDir, "debug_logs"))
        LogBus.installCrashCapture() // 挂全局崩溃捕获 + logcat 镜像
        val vn = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
        LogBus.i("ReaScaleApp", "=== APP START === packageName=$packageName, versionName=$vn, modelsDir=${filesDir}/engines/models")

        // 异步初始化引擎档案；初始化完成后启动队列执行器
        // 这样能彻底避免 500ms 轮询比 initialize() 快 → "引擎不存在"
        appScope.launch {
            runCatching { engineRepository.initialize() }
            runCatching { paramsRepository.initialize() }
            Unit
            // === 启动时一次性打印所有关键状态 ===
            val profiles = engineRepository.profiles.value
            LogBus.i("ReaScaleApp", "=== ENGINES LOADED === count=${profiles.size}")
            profiles.forEach { p ->
                // [FIX] 原实现无脑 removePrefix("asset:")：ncnn: URI 打开失败会误报 model check failed
                val exists = when {
                    p.modelUri.startsWith("asset:") ->
                        runCatching { assets.open(p.modelUri.removePrefix("asset:")).use { true } }
                            .getOrDefault(false)
                    p.modelUri.startsWith("ncnn:") -> {
                        val paramPath = "ncnn/" + p.modelUri.removePrefix("ncnn:") + ".param"
                        runCatching { assets.open(paramPath).use { true } }.getOrDefault(false)
                    }
                    p.modelUri.endsWith(".param") -> java.io.File(p.modelUri).exists()
                    p.modelUri.endsWith(".onnx") -> java.io.File(p.modelUri).exists()
                    else -> false
                }
                LogBus.i("ReaScaleApp", "engine id=${p.id} model=${p.modelUri} exists=$exists source=${p.source} baseScale=${p.capabilities.baseScale} mean=${p.capabilities.mean} std=${p.capabilities.std}")
            }
            // 2026-08-18：ONNX Runtime 恢复（onnxruntime-android 1.23.2，用户导入 .onnx 模型）
            _ready = true
            queueRunner.start()
        }
    }

    companion object {
        @Volatile
        private var instance: ReaScaleApp? = null

        fun get(): ReaScaleApp = instance
            ?: error("ReaScaleApp not yet initialized. Did Application.onCreate run?")
    }
}