package io.reascale.app.queue

import io.reascale.app.data.ImageJob
import io.reascale.app.data.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 队列管理器（万张级）
 *
 * [PERF 2026-08-26] 风暴修复（数千张批量导入闪退根因）：
 * - 旧实现：每次 update/markXxx/updateProgress 都 全量 map 复制列表 + 发射 StateFlow。
 *   引擎 tile 级 progress 回调极高频（每 tile 一次），数千张 × 并发 worker
 *   → 每秒数十上百次「万级列表复制 + UI 全量重组」→ CPU 爆炸 / ANR / OOM。
 * - 新实现：
 *   1. 权威数据为 Mutex 保护的 MutableList（原地修改，0 复制）
 *   2. 心跳协程每 400ms 合并「脏标记」，只发射一次快照（不可变 List 引用）
 *      → 无论 progress 多高频，UI 最多每秒 2.5 次更新，进度平滑度不受影响
 *   3. 读操作全部走 @Volatile 快照引用（无锁）
 *
 * M7 升级（占位 TODO）：Room 持久化 / WorkManager / 断点续传
 */
class QueueManager(private val scope: CoroutineScope) {

    private val mutex = Mutex()

    /** 权威存储（Mutex 保护，原地修改） */
    private val inner = mutableListOf<ImageJob>()

    /** 只读快照（心跳更新，UI/读取安全无锁） */
    @Volatile
    private var snapshot: List<ImageJob> = emptyList()

    private val _jobs = MutableStateFlow<List<ImageJob>>(emptyList())
    val jobs: StateFlow<List<ImageJob>> = _jobs.asStateFlow()

    /** 脏标记：有写未发射 */
    private var dirty = false
    private var flushJob: Job? = null

    init {
        flushJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                mutex.withLock {
                    if (dirty) {
                        dirty = false
                        snapshot = inner.toList()
                        _jobs.value = snapshot
                    }
                }
            }
        }
    }

    /** [INTERNAL] 立即发射一次快照（进度精确性要求高的场景可手动调用） */
    suspend fun flushNow() = withContext(Dispatchers.Default) {
        mutex.withLock {
            dirty = false
            snapshot = inner.toList()
            _jobs.value = snapshot
        }
    }

    /** 写操作统一入口：原地修改 + 置脏（由心跳合并发射） */
    private suspend fun mutate(change: (MutableList<ImageJob>) -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock {
            change(inner)
            dirty = true
        }
    }

    /** 添加单张图任务 */
    suspend fun enqueue(job: ImageJob): String {
        val fixed = job.copy(id = job.id.ifBlank { UUID.randomUUID().toString() })
        mutate { it.add(fixed) }
        return fixed.id
    }

    /** 批量入队（从选择器选 N 张）：一次写入 + 心跳合并发射 */
    suspend fun enqueueAll(jobs: List<ImageJob>): List<String> {
        if (jobs.isEmpty()) return emptyList()
        val ids = jobs.map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
        mutate { list -> list.addAll(ids) }
        return ids.map { it.id }
    }

    /** 取出最多 N 张 PENDING（用于并发执行）；所有读走快照 */
    suspend fun dequeuePendingBatch(n: Int): List<ImageJob> = withContext(Dispatchers.Default) {
        snapshot.filter { it.status == JobStatus.PENDING }.take(n)
    }

    /**
     * 更新单个 Job（用 id 匹配）
     * [PERF] 原地替换，不复制全列表
     */
    suspend fun update(job: ImageJob) = mutate { list ->
        val idx = list.indexOfFirst { it.id == job.id }
        if (idx >= 0) list[idx] = job
    }

    /** 取消任务（仅非终态可取消；正在执行的 worker 由 QueueRunner 负责取消） */
    suspend fun cancel(id: String): Boolean {
        val target = snapshot.firstOrNull { it.id == id } ?: return false
        if (target.status == JobStatus.COMPLETED || target.status == JobStatus.FAILED) return false
        update(target.copy(status = JobStatus.CANCELLED, finishedAt = System.currentTimeMillis()))
        return true
    }

    /** 移除已完成/已失败/已取消的 */
    suspend fun clearFinished() = mutate { list ->
        list.removeAll {
            it.status == JobStatus.COMPLETED ||
                it.status == JobStatus.FAILED ||
                it.status == JobStatus.CANCELLED
        }
    }

    /** 全部取消 */
    suspend fun cancelAll() = mutate { list ->
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val st = list[i].status
            if (st == JobStatus.PENDING || st == JobStatus.RUNNING) {
                list[i] = list[i].copy(status = JobStatus.CANCELLED, finishedAt = now)
            }
        }
    }

    /** 统计（读快照） */
    fun countBy(status: JobStatus): Int {
        var c = 0
        for (j in snapshot) if (j.status == status) c++
        return c
    }

    fun snapshotCounts(): Map<JobStatus, Int> {
        val m = JobStatus.values().associateWith { 0 }.toMutableMap()
        for (j in snapshot) m[j.status] = (m[j.status] ?: 0) + 1
        return m
    }

    /**
     * 标记 Job 开始执行（RUNNING）—— 调用方在即将开始 process 时调用
     *
     * [FIX] 状态守卫：仅 PENDING → RUNNING 生效（返回 true）。
     * 防止 worker 启动前任务已被取消（CANCELLED）时被覆盖回 RUNNING。
     */
    suspend fun markRunning(id: String): Boolean = withContext(Dispatchers.Default) {
        var ok = false
        mutate { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0 && list[idx].status == JobStatus.PENDING) {
                ok = true
                list[idx] = list[idx].copy(
                    status = JobStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                    progress = 0f
                )
            }
        }
        ok
    }

    /**
     * 更新进度（Worker 回调时调用）
     * [PERF] 原地更新 + 脏标记：高频回调被 400ms 心跳合并，不会触发发射风暴
     */
    suspend fun updateProgress(id: String, progress: Float) = mutate { list ->
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val p = progress.coerceIn(0f, 1f)
            // 变化 < 0.5% 且相差 < 100ms 仍合并进心跳（无需单独判定——心跳自然节流）
            list[idx] = list[idx].copy(progress = p)
        }
    }

    /**
     * 标记完成（outputUri 已填）
     *
     * [FIX] 状态守卫：仅 RUNNING → COMPLETED 生效。
     * 防止「用户已取消/暂停」的任务在 worker 收尾时被覆盖回 COMPLETED（任务复活）。
     */
    suspend fun markCompleted(id: String, outputUri: String): Boolean = withContext(Dispatchers.Default) {
        var ok = false
        mutate { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0 && list[idx].status == JobStatus.RUNNING) {
                ok = true
                list[idx] = list[idx].copy(
                    status = JobStatus.COMPLETED,
                    progress = 1f,
                    finishedAt = System.currentTimeMillis(),
                    outputUri = outputUri
                )
            }
        }
        ok
    }

    /**
     * 标记失败
     *
     * [FIX] 状态守卫：仅 PENDING / RUNNING → FAILED 生效。
     * 防止被取消（CANCELLED）或被暂停重置（PENDING 但用户已离开）的任务被覆盖。
     * 注意：PENDING → FAILED 用于「引擎不存在」等启动前失败。
     */
    suspend fun markFailed(id: String, error: String) = mutate { list ->
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val st = list[idx].status
            if (st == JobStatus.PENDING || st == JobStatus.RUNNING) {
                list[idx] = list[idx].copy(
                    status = JobStatus.FAILED,
                    finishedAt = System.currentTimeMillis(),
                    lastError = error.take(200)
                )
            }
        }
    }

    /** 单条读取 */
    fun findById(id: String): ImageJob? = snapshot.firstOrNull { it.id == id }

    companion object {
        private const val FLUSH_INTERVAL_MS = 400L
    }
}