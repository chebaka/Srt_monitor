package com.chebaka.srtmonitor

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.Executors

class MonitorService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("SRT 로그인·좌석 모니터링 준비 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine(); stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            executor.execute { runEngine() }
        }
        return START_STICKY
    }

    private fun runEngine() {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val store = ProfileStore(this)
            val profile = store.activeProfile() ?: error("활성 프로필이 없어")
            val config = store.load(profile) ?: error("저장된 프로필이 없어")
            val callback = StatusProxy { message -> update(message) }
            Python.getInstance().getModule("srt_engine").callAttr("run_monitor_json", config.toJson(), callback)
        } catch (e: Exception) {
            update("🔴 모니터링 실패: ${e.message?.take(220) ?: "알 수 없는 오류"}")
        } finally {
            running = false
        }
    }

    private fun stopEngine() {
        try { if (Python.isStarted()) Python.getInstance().getModule("srt_engine").callAttr("stop_monitor") } catch (_: Exception) { }
        running = false
    }

    private fun update(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        val finished = message.contains("예약 성공") || message.contains("결제 완료") || message.contains("실패")
        val n = notification(message)
        getSystemService(NotificationManager::class.java).notify(if (finished) RESULT_NOTIFICATION_ID else NOTIFICATION_ID, n)
        if (finished) stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("SRT Watch")
        .setContentText(text.take(180)).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(!text.contains("성공") && !text.contains("완료") && !text.contains("실패"))
        .setPriority(NotificationCompat.PRIORITY_HIGH).build()

    override fun onDestroy() { stopEngine(); executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "com.chebaka.srtmonitor.STATUS"
        const val ACTION_STOP = "com.chebaka.srtmonitor.STOP"
        const val EXTRA_STATUS = "status"
        const val CHANNEL_ID = "srt_monitor"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002
    }
}
