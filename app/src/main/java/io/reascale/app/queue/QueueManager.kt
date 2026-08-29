package io.reascale.app.queue

import android.content.Context
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
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 队列管理器（万张级，持久化）
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
 * [PERSIST 2026-08-26] 系统 SIGKILL 防护（MIUI/Android14 后台冻结杀进程）：
 * - 进程被杀重启后任务不丢：心跳节流（2s）把队列序列化到 filesDir/queue_jobs.json
 * - 启动时同步加载；上次遗留 RUNNING 任务重置为 PENDING（重启后继续跑）
 *
 * M7 升级（占位 TODO）：Room 持久化 / WorkManager / 断点续传
 */
class QueueManager(
    private val scope: CoroutineScope,
    context: Context? = null
) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val queueFile: File? = context?.let { File(it.filesDir, PERSIST_FILE) }

    /** 权威存储（Mutex 保护，原地修改） */
    private val inner = mutableListOf<ImageJob>()

    /** 只读快照（心跳更新，UI/读取安全无锁） */
    @Volatile
    private var snapshot: List<ImageJob> = emptyList()

    private val _jobs = MutableStateFlow<List<ImageJob>>(emptyList())
    val jobs: StateFlow<List<ImageJob>> = _jobs.asStateFlow()

    /** 脏标记：有写未发射 */
    private var dirty = false

    private var lastPersistedAt = 0L
    private var flushJob: Job? = null

    init {
        // [CRASH-FIX 2026-08-29] 恢复队列改后台线程：
        // 同步主线程解析大 JSON 阻塞启动 → startForegroundService 5s 超时被杀
        // （ForegroundServiceDidNotStartInTimeException）
        scope.launch(Dispatchers.IO) {
            loadFromDisk()
            mutex.withLock {
                dirty = true
                snapshot = inner.toList()
                _jobs.value = snapshot
            }
        }

        flushJob = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                mutex.withLock {
                    if (dirty) {
                        dirty = false
                        snapshot = inner.toList()
                        _jobs.value = snapshot
                    }
                    // 写盘节流 2s：过程被杀也不丢任务
                    val now = System.currentTimeMillis()
                    if (now - lastPersistedAt >= DISK_INTERVAL_MS) {
                        lastPersistedAt = now
                        persistLocked()
                    }
                }
            }
        }
    }

    /** 启动时从磁盘恢复（同步，万级 JSON 解析 <100ms） */
    private fun loadFromDisk() {
        runCatching {
            val qf = queueFile ?: return
            if (!qf.exists()) return
            val decoded = json.decodeFromString(QueueEnvelope.serializer(), qf.readText()).jobs
            if (decoded.isEmpty()) return
            // 悬空 RUNNING → PENDING（进程被杀重启后自动续跑）
            val now = System.currentTimeMillis()
            for (j in decoded) {
                inner.add(
                    if (j.status == JobStatus.RUNNING) {
                        j.copy(status = JobStatus.PENDING, startedAt = 0L, progress = 0f, lastError = if (j.lastError.isEmpty()) "" else j.lastError)
                    } else j
                )
            }
            snapshot = inner.toList()
        }.onFailure {
            android.util.Log.e("QueueManager", "恢复队列失败（忽略）", it)
        }
    }

    /** 写盘（调用方需持有 mutex） */
    private fun persistLocked() {
        runCatching {
            val qf = queueFile ?: return
            qf.parentFile?.mkdirs()
            val tmp = File(qf.parentFile, "$PERSIST_FILE.tmp")
            tmp.writeText(json.encodeToString(QueueEnvelope.serializer(), QueueEnvelope(inner.toList())))
            if (tmp.exists()) {
                val bak = File(qf.parentFile, "$PERSIST_FILE.bak")
                if (qf.exists()) {
                    // 先备份旧的（新文件写坏时能回滚）
                    if (bak.exists()) bak.delete()
                    qf.copyTo(bak, overwrite = true)
                }
                if (tmp.renameTo(qf)) {
                    bak.delete()
                } else {
                    // rename 失败：直接覆盖（tmp 内容已完整）
                    tmp.copyTo(qf, overwrite = true)
                    tmp.delete()
                }
            }
        }.onFailure {
            android.util.Log.e("QueueManager", "持久化队列失败（忽略）", it)
        }
    }

    /** 立即发射一次快照（进度精确性要求高的场景可手动调用） */
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

    /**
     * [PERSIST-FIX 2026-08-29] 结构性变更（清空/移除/取消）立即落盘：
     * 心跳写盘有 30s 节流，若进程在节流窗口内被杀，清理前的旧队列恢复到磁盘，
     * 重启后任务"复活"。这里 mutate 后立刻持久化，确保清理即时生效。
     */
    private suspend fun mutatePersist(change: (MutableList<ImageJob>) -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock {
            change(inner)
            dirty = true
            snapshot = inner.toList()
            _jobs.value = snapshot
            persistLocked()
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

    /** [OOM-FIX] 璁嗛樋涓嬩竴涓狤ENDING锛堝彧璇讳笉鍔ㄧ姸鎬侊級 */
    fun peekPending(): ImageJob? = snapshot.firstOrNull { it.status == JobStatus.PENDING }

    /**
     * 更新单个 Job（用 id 匹配）
     * [PERF] 原地替换，不复制全列表
     */
    suspend fun update(job: ImageJob) = mutate { list ->
        val idx = list.indexOfFirst { it.id == job.id }
        if (idx >= 0) list[idx] = job
    }

    /**
     * [STATE-FIX 2026-08-29] 元数据补全（惰性 probe 的宽高/大小）专用：
     * **只改尺寸字段，绝不覆盖 status/progress/终态**。
     * 原代码用 update() 全量替换，会把 RUNNING 覆盖回 PENDING →
     * 同一任务被调度两次并发执行（日志实锤：同 id 双 worker 推理）→"停顿"假象。
     */
    suspend fun updateMeta(id: String, width: Int, height: Int, sizeBytes: Long) = mutate { list ->
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(
                sourceWidth = width,
                sourceHeight = height,
                sourceSizeBytes = sizeBytes
            )
        }
    }

    /** 取消任务（仅非终态可取消；正在执行的 worker 由 QueueRunner 负责取消）[实时性：锁内查询] */
    suspend fun cancel(id: String): Boolean = withContext(Dispatchers.Default) {
        var ok = false
        mutate { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val st = list[idx].status
                if (st != JobStatus.COMPLETED && st != JobStatus.FAILED) {
                    ok = true
                    list[idx] = list[idx].copy(status = JobStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                }
            }
        }
        ok
    }

    /** 移除已完成/已失败/已取消的（[PERSIST-FIX] 结构性变更立即写盘，防被杀后残留旧任务） */
    suspend fun clearFinished() = mutatePersist { list ->
        list.removeAll {
            it.status == JobStatus.COMPLETED ||
                it.status == JobStatus.FAILED ||
                it.status == JobStatus.CANCELLED
        }
    }

    /** 全部取消（结构性变更立即写盘） */
    suspend fun cancelAll() = mutatePersist { list ->
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val st = list[i].status
            if (st == JobStatus.PENDING || st == JobStatus.RUNNING) {
                list[i] = list[i].copy(status = JobStatus.CANCELLED, finishedAt = now)
            }
        }
    }

    /** [UX-FIX 2026-08-29] 移除单个任务（任意状态，立即写盘） */
    suspend fun remove(id: String) = mutatePersist { list ->
        list.removeAll { it.id == id }
    }

    /** [UX-FIX 2026-08-29] 清空整个队列（全部状态，立即写盘） */
    suspend fun clearAll() = mutatePersist { list -> list.clear() }

    /**
     * [UX-FIX 2026-08-29] 重试任务：FAILED/CANCELLED → PENDING
     */
    suspend fun retry(id: String): Boolean = withContext(Dispatchers.Default) {
        var ok = false
        mutate { list ->
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val st = list[idx].status
                if (st == JobStatus.FAILED || st == JobStatus.CANCELLED) {
                    ok = true
                    list[idx] = list[idx].copy(
                        status = JobStatus.PENDING,
                        progress = 0f,
                        retryCount = list[idx].retryCount + 1,
                        lastError = "",
                        finishedAt = 0L
                    )
                }
            }
        }
        ok
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
            list[idx] = list[idx].copy(progress = progress.coerceIn(0f, 1f))
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

    /** [RESUME-FIX] 是否有未完成任务（PENDING/RUNNING）——WorkManager 恢复 Worker 用 */
    fun hasOutstanding(): Boolean {
        for (j in snapshot) {
            val s = j.status
            if (s == JobStatus.PENDING || s == JobStatus.RUNNING) return true
        }
        return false
    }

    /** 单条读取 */
    fun findById(id: String): ImageJob? = snapshot.firstOrNull { it.id == id }

    @Serializable
    private data class QueueEnvelope(val jobs: List<ImageJob>)

    companion object {
        private const val FLUSH_INTERVAL_MS = 400L
        private const val DISK_INTERVAL_MS = 30_000L
        private const val PERSIST_FILE = "queue_jobs.json"
    }
}
