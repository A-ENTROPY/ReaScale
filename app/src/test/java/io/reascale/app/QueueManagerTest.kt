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
 * [FIX 2026-08-17] markRunning/markCompleted/markFailed 增加状态守卫
 * [FIX 2026-08-29] 心跳快照：断言前调用 flushNow() 冲刷（生产由 400ms 心跳节流发射）
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
        qm.markCompleted("a", "content://out/1")
        qm.flushNow()
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `cancel then markFailed does not overwrite cancelled`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markRunning("a")
        qm.cancel("a")
        qm.markFailed("a", "late error")
        qm.flushNow()
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `markRunning fails after cancel`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.cancel("a")
        qm.flushNow()
        assertFalse(qm.markRunning("a"))
        qm.flushNow()
        assertEquals(JobStatus.CANCELLED, qm.jobs.value.single().status)
    }

    @Test
    fun `normal lifecycle pending to completed`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        assertTrue(qm.markRunning("a"))
        assertTrue(qm.markCompleted("a", "content://out/1"))
        qm.flushNow()
        assertEquals(JobStatus.COMPLETED, qm.jobs.value.single().status)
    }

    @Test
    fun `fail before running is allowed`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markFailed("a", "引擎不存在")
        qm.flushNow()
        assertEquals(JobStatus.FAILED, qm.jobs.value.single().status)
    }

    @Test
    fun `completed job cannot be cancelled`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markRunning("a")
        qm.markCompleted("a", "uri")
        qm.flushNow()
        assertFalse(qm.cancel("a"))
        qm.flushNow()
        assertEquals(JobStatus.COMPLETED, qm.jobs.value.single().status)
    }

    @Test
    fun `dequeue returns pending in fifo order`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueueAll(listOf(job("a"), job("b"), job("c")))
        qm.flushNow()
        val batch = qm.dequeuePendingBatch(2)
        assertEquals(listOf("a", "b"), batch.map { it.id })
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
        qm.flushNow()
        assertEquals(0, qm.jobs.value.size)
    }

    @Test
    fun `retry moves failed back to pending`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueue(job("a"))
        qm.markFailed("a", "boom")
        assertTrue(qm.retry("a"))
        qm.flushNow()
        val j = qm.jobs.value.single()
        assertEquals(JobStatus.PENDING, j.status)
        assertEquals(1, j.retryCount)
        assertEquals("", j.lastError)
    }

    @Test
    fun `remove and clearAll`() = runTest {
        val qm = QueueManager(backgroundScope)
        qm.enqueueAll(listOf(job("a"), job("b"), job("c")))
        qm.remove("b")
        qm.clearAll()
        qm.flushNow()
        assertEquals(0, qm.jobs.value.size)
    }
}