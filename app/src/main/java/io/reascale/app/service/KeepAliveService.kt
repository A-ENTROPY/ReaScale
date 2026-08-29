package io.reascale.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.reascale.app.R
import io.reascale.app.ui.MainActivity

/**
 * [CRASH-FIX 2026-08-29] 选图保活前台服务。
 *
 * 根因：文件管理器批量选数千张图片耗时长，期间本进程在后台，
 * 被 MIUI/系统 cgroup 清理（SIGKILL，无任何异常）→ 返回时闪退。
 *
 * 方案：点击"相册/文件管理器"调起选择器前（仍是前台时刻，启动前台服务合法）
 * 立即启动本服务 → 选图期间进程有前台服务，系统不回收；
 * 选图返回（ActivityResult 回调）后由 MainActivity 停止。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "图片选择",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "正在选择图片时保持应用运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ReaScale 选择图片中")
            .setContentText("正在浏览并选择图片，应用保持运行")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "reascale_keepalive"
        const val NOTIF_ID = 1002
    }
}