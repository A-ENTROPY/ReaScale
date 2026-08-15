package io.reascale.app.queue

import io.reascale.app.data.ImageJob
import io.reascale.app.data.JobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 队列管理器（M1 内存版）
 * 对应 §18 千张级队列
 *
 * M1 实现：
 * - 内存 StateFlow（不持久化）
 * - 任务状态机
 * - 简单的入队 / 出队 / 移除
 * - 并发数限制（由 settings.concurrency 决定）
 *
 * M7 升级（占位 TODO）：
 * - 切换为 Room 数据库（§26 schema）
 * - 切换为 WorkManager + ForegroundService
 * - 资源公平闸（电量 / 温控 / 内存）
 * - 断点续传（每个 Job 的 sourceUri 持久化）
 * - 失败重试
 */
class QueueManager(private val scope: CoroutineScope) {

    private val mutex = Mutex()

    private val _jobs = MutableStateFlow<List<ImageJob>>(emptyList())
    val jobs: StateFlow<List<ImageJob>> = _jobs.asStateFlow()

    /** 添加单张图任务 */
    suspend fun enqueue(job: ImageJob): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val list = _jobs.value.toMutableList()
            list.add(job.copy(id = job.id.ifBlank { UUID.randomUUID().toString() }))
            _jobs.value = list
            list.last().id
        }
    }

    /** 批量入队（从 PhotoPicker 选 N 张） */
    suspend fun enqueueAll(jobs: List<ImageJob>): List<String> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val list = _jobs.value.toMutableList()
            val ids = jobs.map { it.copy(id = it.id.ifBlank { UUID.randomUUID().toString() }) }
            list.addAll(ids)
            _jobs.value = list
            ids.map { it.id }
        }
    }

    /** 取出下一张 PENDING（按 FIFO） */
    suspend fun dequeuePending(): ImageJob? = withContext(Dispatchers.Default) {
        mutex.withLock {
            _jobs.value.firstOrNull { it.status == JobStatus.PENDING }
        }
    }

    /** 取出最多 N 张 PENDING（用于并发执行） */
    suspend fun dequeuePendingBatch(n: Int): List<ImageJob> = withContext(Dispatchers.Default) {
        mutex.withLock {
            _jobs.value.filter { it.status == JobStatus.PENDING }.take(n)
        }
    }

    /** 更新单个 Job（用 id 匹配） */
    suspend fun update(job: ImageJob) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val list = _jobs.value.toMutableList()
            val idx = list.indexOfFirst { it.id == job.id }
            if (idx >= 0) {
                list[idx] = job
                _jobs.value = list
            }
        }
    }

    /** 取消任务（仅非终态可取消；正在执行的 worker 由 QueueRunner 负责取消） */
    suspend fun cancel(id: String): Boolean {
        val target = _jobs.value.firstOrNull { it.id == id } ?: return false
        if (target.status == JobStatus.COMPLETED || target.status == JobStatus.FAILED) return false
        update(target.copy(status = JobStatus.CANCELLED, finishedAt = System.currentTimeMillis()))
        return true
    }

    /** 移除已完成/已失败/已取消的 */
    suspend fun clearFinished() = withContext(Dispatchers.Default) {
        mutex.withLock {
            _jobs.value = _jobs.value.filter {
                it.status != JobStatus.COMPLETED &&
                        it.status != JobStatus.FAILED &&
                        it.status != JobStatus.CANCELLED
            }
        }
    }

    /** 全部取消 */
    suspend fun cancelAll() = withContext(Dispatchers.Default) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            _jobs.value = _jobs.value.map {
                if (it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING) {
                    it.copy(status = JobStatus.CANCELLED, finishedAt = now)
                } else it
            }
        }
    }

    /** 统计 */
    fun countBy(status: JobStatus): Int = _jobs.value.count { it.status == status }

    fun snapshotCounts(): Map<JobStatus, Int> = JobStatus.values().associateWith { countBy(it) }

    /**
     * 标记 Job 开始执行（RUNNING）—— 调用方在即将开始 process 时调用
     *
     * [FIX] 状态守卫：仅 PENDING → RUNNING 生效（返回 true）。
     * 防止 worker 启动前任务已被取消（CANCELLED）时被覆盖回 RUNNING。
     */
    suspend fun markRunning(id: String): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            var ok = false
            _jobs.value = _jobs.value.map {
                if (it.id == id && it.status == JobStatus.PENDING) {
                    ok = true
                    it.copy(
                        status = JobStatus.RUNNING,
                        startedAt = System.currentTimeMillis(),
                        progress = 0f
                    )
                } else it
            }
            ok
        }
    }

    /**
     * 更新进度（Worker 回调时调用）
     */
    suspend fun updateProgress(id: String, progress: Float) = withContext(Dispatchers.Default) {
        mutex.withLock {
            _jobs.value = _jobs.value.map {
                if (it.id == id) it.copy(progress = progress.coerceIn(0f, 1f)) else it
            }
        }
    }

    /**
     * 标记完成（outputUri 已填）
     *
     * [FIX] 状态守卫：仅 RUNNING → COMPLETED 生效。
     * 防止「用户已取消/暂停」的任务在 worker 收尾时被覆盖回 COMPLETED（任务复活）。
     */
    suspend fun markCompleted(id: String, outputUri: String): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            var ok = false
            _jobs.value = _jobs.value.map {
                if (it.id == id && it.status == JobStatus.RUNNING) {
                    ok = true
                    it.copy(
                        status = JobStatus.COMPLETED,
                        progress = 1f,
                        finishedAt = System.currentTimeMillis(),
                        outputUri = outputUri
                    )
                } else it
            }
            ok
        }
    }

    /**
     * 标记失败
     *
     * [FIX] 状态守卫：仅 PENDING / RUNNING → FAILED 生效。
     * 防止被取消（CANCELLED）或被暂停重置（PENDING 但用户已离开）的任务被覆盖。
     * 注意：PENDING → FAILED 用于「引擎不存在」等启动前失败。
     */
    suspend fun markFailed(id: String, error: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            _jobs.value = _jobs.value.map {
                if (it.id == id &&
                    (it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING)
                ) {
                    it.copy(
                        status = JobStatus.FAILED,
                        finishedAt = System.currentTimeMillis(),
                        lastError = error.take(200)
                    )
                } else it
            }
        }
    }
}