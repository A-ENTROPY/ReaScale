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
 * 队列处理前台服务（[FIX 2026-08-17] 实现"锁屏也跑"设置项）
 *
 * QueueRunner 在队列有活跃任务时启动本服务并 startForeground，
 * 让系统知道 app 正在做数据处理，锁屏/后台时不被杀掉；
 * 队列空闲时由 QueueRunner 停止本服务。
 */
class ProcessingForegroundService : Service() {

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
            "图片处理",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "队列处理中的前台通知（锁屏继续运行）"
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
            .setContentTitle("ReaScale 正在处理图片")
            .setContentText("锁屏也会继续运行")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "reascale_processing"
        const val NOTIF_ID = 1001
    }
}
