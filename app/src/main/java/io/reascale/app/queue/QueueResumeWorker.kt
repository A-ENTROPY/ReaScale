package io.reascale.app.queue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.reascale.app.ReaScaleApp

/**
 * [RESUME-FIX 2026-08-29] 队列恢复兜底 Worker。
 *
 * 背景：MIUI/ZUI 等厂商 SmartPower 会杀后台进程（实测 45s 可见窗口即被清理，
 * 连 FGS 也拦不住）。进程死后批量处理全部中断。
 *
 * 主流解法（谷歌官方推荐 + dontkillmyapp 共识）：用 WidgetManager/JobScheduler
 * 调度的任务受厂商豁免最彻底——进程被杀后系统会**自动重启** Worker，任务不丢。
 *
 * 本 Worker：每 15 分钟由系统调度唤醒一次（进程死了也会由 JobScheduler 拉起新进程），
 * 检查持久化队列是否还有未完成任务：有 → 启动 QueueRunner 调度循环继续处理。
 * 配合队列持久化（queue_jobs.json），被杀多少次最终都会处理完。
 */
class QueueResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = ReaScaleApp.get()
            if (app.queueManager.hasOutstanding()) {
                app.queueRunner.start()
            }
            Result.success()
        } catch (t: Throwable) {
            // 恢复失败不重试（下个周期再来），避免无限重试风暴
            Result.success()
        }
    }
}