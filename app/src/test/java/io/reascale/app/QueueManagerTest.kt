package io.reascale.app

import io.reascale.app.data.EncodeOptions
import io.reascale.app.data.ImageJob
import io.reascale.app.data.JobStatus
import io.reascale.app.data.UpscalePlan
import io.reascale.app.queue.QueueManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列状态机单测
 * [FIX 2026-08-17] markRunning/markCompleted/markFailed 增加状态守卫：
 * - cancel 后 worker 收尾不能把 CANCELLED 覆盖回 COMPLETED/FAILED（任务复活）
 * - 已取消的任务不能重新进入 RUNNING
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest {

    private fun job(id: String) = ImageJob(
        id = id,
        sourceUri = "content://test/$id",
        sourceDisplayName = "$id.jpg",
        sourceSizeBytes = 100,
        sourceWidth = 10,
        sourceHeight = 10,
        engineId = "e1",
        upscalePlan = UpscalePlan(targetScale = 2),
        encodeOptions = EncodeOptions()
    )

    @Test
    fun `cancel then markCompleted does not resurrect job`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markRunning("a")
        assertTrue(qm.cancel("a"))
        // worker 收尾尝试写完成 —— 状态守卫应拦截
        qm.markCompleted("a", "content://out/1")
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `cancel then markFailed does not overwrite cancelled`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markRunning("a")
        qm.cancel("a")
        qm.markFailed("a", "late error")
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `markRunning fails after cancel`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.cancel("a")
        // 任务已取消，worker 不应再把它置为 RUNNING
        assertFalse(qm.markRunning("a"))
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `normal lifecycle pending to completed`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        assertTrue(qm.markRunning("a"))
        assertTrue(qm.markCompleted("a", "content://out/1"))
        assertEquals(JobStatus.COMPLETED, qm.jobs.value.single().status)
    }

    @Test
    fun `fail before running is allowed`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        // 引擎不存在等启动前失败：PENDING → FAILED
        qm.markFailed("a", "引擎不存在")
        assertEquals(JobStatus.FAILED, qm.jobs.value.single().status)
    }

    @Test
    fun `completed job cannot be cancelled`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markRunning("a")
        qm.markCompleted("a", "uri")
        assertFalse(qm.cancel("a"))
        assertEquals(JobStatus.COMPLETED, qm.jobs.value.single().status)
    }

    @Test
    fun `dequeue returns pending in fifo order`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueueAll(listOf(job("a"), job("b"), job("c")))
        val batch = qm.dequeuePendingBatch(2)
        assertEquals(listOf("a", "b"), batch.map { it.id })
        // 状态仍为 PENDING（RUNNING 由 worker 在真正开始时标记）
        assertEquals(3, qm.countBy(JobStatus.PENDING))
    }

    @Test
    fun `clearFinished removes finished jobs`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueueAll(listOf(job("a"), job("b")))
        qm.markRunning("a")
        qm.markCompleted("a", "uri1")
        qm.markFailed("b", "err")
        qm.clearFinished()
        assertEquals(0, qm.jobs.value.size)
    }
}
