package com.chebaka.srtmonitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
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
        startForeground(NOTIFICATION_ID, notification("철도 로그인·좌석 모니터링 준비 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine(); stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_VERIFY_KORAIL_PAYMENT) {
            if (!running) {
                running = true
                executor.execute {
                    verifyKorailPayment(intent.getStringExtra(EXTRA_KORAIL_TRAIN_NO).orEmpty())
                }
            }
            return START_NOT_STICKY
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
            val moduleName = if (config.operator == "KORAIL") "korail_engine" else "srt_engine"
            Python.getInstance().getModule(moduleName).callAttr("run_monitor_json", config.toJson(), callback)
        } catch (e: Exception) {
            update("🔴 모니터링 실패: ${e::class.simpleName ?: "오류"}")
        } finally {
            running = false
        }
    }

    private fun stopEngine() {
        if (Python.isStarted()) {
            val python = Python.getInstance()
            listOf("srt_engine", "korail_engine").forEach { moduleName ->
                try { python.getModule(moduleName).callAttr("stop_monitor") } catch (_: Exception) { }
            }
        }
        running = false
    }

    private fun update(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        val finished = isFinished(message)
        val n = notification(message)
        getSystemService(NotificationManager::class.java).notify(if (finished) RESULT_NOTIFICATION_ID else NOTIFICATION_ID, n)
        if (finished) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun verifyKorailPayment(trainNo: String) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val store = ProfileStore(this)
            val profile = store.activeProfile() ?: error("활성 프로필이 없어")
            val config = store.load(profile) ?: error("저장된 프로필이 없어")
            val callback = StatusProxy { message -> update(message) }
            Python.getInstance().getModule("korail_engine")
                .callAttr("verify_payment_json", config.toJson(), callback, trainNo)
        } catch (e: Exception) {
            update("KORAIL|결제 확인 실패|${e::class.simpleName ?: "오류"}")
        } finally {
            running = false
        }
    }

    private fun notification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("Rail Watch")
            .setContentText(text.take(180)).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!isFinished(text))
            .setAutoCancel(isFinished(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (text.startsWith(KORAIL_PAYMENT_REQUIRED_PREFIX)) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KORAIL_PAYMENT_URL))
            val webPendingIntent = activityPendingIntent(webIntent, KORAIL_PAYMENT_REQUEST_CODE)
            val korailTalkIntent = packageManager.getLaunchIntentForPackage(KORAIL_TALK_PACKAGE)
            builder.setContentIntent(korailTalkIntent?.let {
                activityPendingIntent(it, KORAIL_TALK_REQUEST_CODE)
            } ?: webPendingIntent)
            builder.addAction(R.drawable.ic_stat_srt, "공식 웹 결제 열기", webPendingIntent)
            val verifyIntent = Intent(this, MonitorService::class.java).apply {
                action = ACTION_VERIFY_KORAIL_PAYMENT
                putExtra(EXTRA_KORAIL_TRAIN_NO, fieldValue(text, "열차번호 "))
            }
            builder.addAction(
                R.drawable.ic_stat_srt,
                "결제 확인",
                PendingIntent.getForegroundService(
                    this,
                    KORAIL_VERIFY_REQUEST_CODE,
                    verifyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            if (korailTalkIntent != null) {
                builder.addAction(
                    R.drawable.ic_stat_srt,
                    "코레일톡 열기",
                    activityPendingIntent(korailTalkIntent, KORAIL_TALK_REQUEST_CODE)
                )
            }
        }
        return builder.build()
    }

    private fun fieldValue(text: String, prefix: String): String =
        text.split('|').firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()

    private fun activityPendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun isFinished(message: String): Boolean =
        message.startsWith("✅ 예약 성공:") ||
            message.contains("예약·결제 완료") ||
            message.startsWith("🔴 예약 완료") ||
            message.startsWith("🔴 입력 확인 실패:") ||
            message.contains("기존 예약이 있어") ||
            message.contains("연속 오류 5회") ||
            message.startsWith("🔴 모니터링 실패:") ||
            message.startsWith("KORAIL|예약 완료") ||
            message.startsWith("KORAIL|기존 예약 발견") ||
            message.startsWith("KORAIL|결제 확인") ||
            message.startsWith("KORAIL|입력 확인 실패") ||
            message.startsWith("KORAIL|연속 오류")

    override fun onDestroy() { stopEngine(); executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "com.chebaka.srtmonitor.STATUS"
        const val ACTION_STOP = "com.chebaka.srtmonitor.STOP"
        const val ACTION_VERIFY_KORAIL_PAYMENT = "com.chebaka.srtmonitor.VERIFY_KORAIL_PAYMENT"
        const val EXTRA_STATUS = "status"
        const val EXTRA_KORAIL_TRAIN_NO = "korail_train_no"
        const val CHANNEL_ID = "srt_monitor"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002
        private const val KORAIL_PAYMENT_REQUIRED_PREFIX = "KORAIL|예약 완료|결제 필요"
        private const val KORAIL_PAYMENT_URL = "https://www.korail.com/ticket/payment/payment"
        private const val KORAIL_TALK_PACKAGE = "com.korail.talk"
        private const val KORAIL_PAYMENT_REQUEST_CODE = 2001
        private const val KORAIL_TALK_REQUEST_CODE = 2002
        private const val KORAIL_VERIFY_REQUEST_CODE = 2003
    }
}
