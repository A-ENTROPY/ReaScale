package io.reascale.app.queue

import android.content.Context
import io.reascale.app.ReaScaleApp
import io.reascale.app.core.processing.ImageProcessor
import io.reascale.app.data.ConcurrencyProfile
import io.reascale.app.debug.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 队列执行器（M1 内存版调度，M7 升级到 WorkManager）
 *
 * - 持续轮询 QueueManager 的 PENDING Job
 * - 按 concurrency 设置限制同时跑的 worker 数
 * - 每个 worker 调 ImageProcessor.process
 * - 状态/进度实时写回 QueueManager.jobs StateFlow
 *
 * 关键不变量：并发数 ≤ maxConcurrent；worker 失败/异常不导致其他 worker 退出
 *
 * [FIX 2026-08-17] 取消/暂停语义（配合 QueueManager 状态守卫）：
 * - cancel(jobId)：取消 worker 协程 + 状态置 CANCELLED；worker 收尾时
 *   markCompleted/markFailed 因状态守卫（仅 RUNNING 生效）不会覆盖取消状态
 * - pause()：只停止调度新任务，不取消正在执行的 worker（原生 ncnn 推理无法中断，
 *   强制取消会导致任务卡在 RUNNING 或重复处理产生重复输出）
 * - 被取消的 worker 在挂起点抛 CancellationException 并向上传播，不标记 FAILED
 */
class QueueRunner(
    private val context: Context,
    @Suppress("unused") private val scope: CoroutineScope,
    private val queue: QueueManager,
    private val processor: ImageProcessor
) {

    private val runnerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var schedulerJob: Job? = null
    /** jobId → worker Job（取消单个任务时联动取消 worker） */
    private val activeWorkers = mutableMapOf<String, Job>()
    // [FIX 2026-08-17] 前台服务状态（"锁屏也跑"）：有活跃任务时启动，空闲时停止
    private var fgServiceStarted = false
    // [OOM-FIX 2026-08-26] 内存感知调度：活跃 worker 峰值内存总和（MB）
    private val activePeakMB = java.util.concurrent.atomic.AtomicLong(0L)
    // heap 预算 = 70% × 可用 heap（largeHeap 后典型 512MB → 预算 ~358MB）
    private val heapBudgetMB = (Runtime.getRuntime().maxMemory() * 0.7 / 1024L / 1024L).toLong()
    // [CRASH-FIX 2026-08-29] 前台服务启动冷却
    private var schedulerStartedAt = 0L

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 启动调度循环（幂等） */
    fun start() {
        if (schedulerJob?.isActive == true) return
        // [CRASH-FIX 2026-08-29] 启动冷却：Application 初始化窗口内不启动前台服务，
        // 避免 startForegroundService 5s 超时被杀（ForegroundServiceDidNotStartInTimeException）
        schedulerStartedAt = System.currentTimeMillis()
        schedulerJob = runnerScope.launch {
            _isRunning.value = true
            try {
                runSchedulerLoop()
            } finally {
                _isRunning.value = false
            }
        }
    }

    /**
     * 暂停：停止调度新任务。
     * 正在执行的 worker 让其自然完成（原生推理无法中断），完成后正常标记 COMPLETED；
     * 尚未开始的 PENDING 任务保留，恢复（start）后继续处理。
     */
    suspend fun pause() {
        schedulerJob?.cancelAndJoin()
        schedulerJob = null
    }

    /**
     * 取消单个任务：取消 worker 协程 + 状态置 CANCELLED。
     * 正在执行的原生推理会跑完，但后续状态写入被守卫拦截，任务保持 CANCELLED。
     */
    fun cancelJob(jobId: String) {
        activeWorkers.remove(jobId)?.cancel()
        runnerScope.launch { queue.cancel(jobId) }
    }

    /** 全部取消：取消所有 worker + 状态全部置 CANCELLED */
    fun cancelAll() {
        activeWorkers.values.forEach { it.cancel() }
        activeWorkers.clear()
        runnerScope.launch { queue.cancelAll() }
    }

    /**
     * [FIX 2026-08-17] 前台服务联动（"锁屏也跑"）：
     * 设置开启且队列有活跃任务 → startForegroundService；
     * 队列空闲或设置关闭 → stopService。状态守卫避免频繁调用。
     */
    private fun syncForegroundService(enabled: Boolean) {
        val hasActive = activeWorkers.isNotEmpty()
        // [CRASH-FIX 2026-08-29] 启动冷却：进程刚冷启 1.5s 内不启动前台服务
        // （Application 初始化 + 队列恢复窗口，服务 5s 超时会被系统杀进程）
        val cold = System.currentTimeMillis() - schedulerStartedAt < FG_START_COOLDOWN_MS
        if (enabled && hasActive && !fgServiceStarted && !cold) {
            try {
                context.startForegroundService(
                    android.content.Intent(context, io.reascale.app.service.ProcessingForegroundService::class.java)
                )
                fgServiceStarted = true
            } catch (t: Throwable) {
                // Android 12+ 后台启动限制 / 通知权限缺失等：忽略，不影响处理
                android.util.Log.w("QueueRunner", "startForegroundService failed", t)
            }
        } else if ((!enabled || !hasActive) && fgServiceStarted) {
            runCatching {
                context.stopService(
                    android.content.Intent(context, io.reascale.app.service.ProcessingForegroundService::class.java)
                )
            }
            fgServiceStarted = false
        }
    }

    private suspend fun runSchedulerLoop() {
        val app = ReaScaleApp.get()

        // 闪退防护：等 EngineRepository 初始化完成（之前是 500ms 轮询可能在 initialize 之前 →
        // findById 返回 null → 所有 Job 都 markFailed；本身不崩，但会让 UI 看上去"没反应"
        while (!app.isReady) {
            delay(200)
        }

        while (true) {
            try {
                // 读一次当前 concurrency 设置（避免持续 collect 阻塞循环）
                val settings = app.settingsRepository.settingsFlow.first()
                val maxConcurrent = when (settings.concurrency) {
                    ConcurrencyProfile.SAVER -> 1
                    ConcurrencyProfile.BALANCED -> 2
                    ConcurrencyProfile.PERFORMANCE -> 3
                }

                // 清理已完成的 worker 引用
                activeWorkers.entries.removeAll { !it.value.isActive }

                // [FIX 2026-08-17] 前台服务联动：有活跃任务 → 启动；空闲 → 停止
                syncForegroundService(settings.enableForegroundService)

                // [OOM-FIX 2026-08-26] 内存感知调度：并发峰值总和 ≤ heap 预算
                // 每张图（输入解码 + 引擎输出 + tile 缓冲）峰值可达数百 MB，2-3 并发 × 大图
                // 会突破 heap 上限 → OOM 闪退。调度器按 estimatePeakMB 累计预算，
                // 内存不够就等待（图片处理是串行降级，任务不丢）。
                val available = maxConcurrent - activeWorkers.size
                if (available <= 0) {
                    delay(300)
                    continue
                }
                val job = queue.peekPending()
                if (job == null) {
                    delay(500)
                    continue
                }
                val peakMB = io.reascale.app.core.MemoryBudget.estimatePeakMB(job, context)
                if (activePeakMB.get() + peakMB > heapBudgetMB) {
                    delay(600)  // 内存不足：等待高占用 worker 释放
                    continue
                }
                activePeakMB.addAndGet(peakMB)
                val w = runnerScope.launch {
                    try {
                        runOneJobSafe(job.id)
                    } finally {
                        activePeakMB.addAndGet(-peakMB)
                        activeWorkers.remove(job.id)
                    }
                }
                activeWorkers[job.id] = w
                delay(100)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                android.util.Log.e("QueueRunner", "scheduler loop error", t)
                delay(1000)
            }
        }
    }

    /**
     * 跑单个 Job，全程包 try-catch
     * 闪退防护：Worker 抛任何异常都不能让 app crash
     * [FIX] CancellationException 直接向上传播（取消不是失败，不标记 FAILED）
     */
    private suspend fun runOneJobSafe(jobId: String) {
        LogBus.i("QueueRunner", "▶️ runOneJobSafe: $jobId")
        try {
            runOneJob(jobId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (oom: OutOfMemoryError) {
            queue.markFailed(jobId, "内存不足: ${oom.message}")
            LogBus.e("QueueRunner", "❌ OOM on $jobId", oom)
        } catch (t: Throwable) {
            queue.markFailed(jobId, t.message ?: "未知异常")
            LogBus.e("QueueRunner", "❌ job $jobId failed", t)
        }
    }

    private suspend fun runOneJob(jobId: String) {
        val app = ReaScaleApp.get()
        var job = app.queueManager.jobs.value.firstOrNull { it.id == jobId } ?: return

        // [CRASH-FIX 2026-08-29] 惰性元数据补全：入队时未 probe，处理前补宽高（调度内存估算用）
        if (job.sourceWidth <= 0 || job.sourceHeight <= 0) {
            val meta = io.reascale.app.core.imageio.ImageProbe.probe(
                app, android.net.Uri.parse(job.sourceUri)
            )
            if (meta != null) {
                job = job.copy(
                    sourceWidth = meta.width,
                    sourceHeight = meta.height,
                    sourceSizeBytes = meta.fileSizeBytes
                )
                queue.update(job)
            }
        }

        // 防御：如果 EngineRepository 还没初始化完（race），等 100ms 再查一次，最多 10 次
        var profile = app.engineRepository.findById(job.engineId)
        var retries = 0
        while (profile == null && retries < 10) {
            delay(100)
            profile = app.engineRepository.findById(job.engineId)
            retries++
        }
        if (profile == null) {
            queue.markFailed(jobId, "引擎不存在: ${job.engineId}")
            return
        }

        // [FIX] markRunning 有状态守卫：若任务在等待期间已被取消/移除则不再启动
        if (!queue.markRunning(jobId)) return
        try {
            val result = processor.process(
                job = job,
                profile = profile,
                progress = { p ->
                    runnerScope.launch { queue.updateProgress(jobId, p) }
                }
            )
            result.fold(
                onSuccess = { outUri -> queue.markCompleted(jobId, outUri.toString()) },
                onFailure = { err -> queue.markFailed(jobId, err.message ?: "未知错误") }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            queue.markFailed(jobId, t.message ?: "未知异常")
            android.util.Log.e("QueueRunner", "runOneJob failed", t)
        }
    }

    companion object {
        private const val FG_START_COOLDOWN_MS = 1500L
    }
}
