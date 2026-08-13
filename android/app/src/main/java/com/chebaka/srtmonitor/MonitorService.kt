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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MonitorService : Service() {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val workers = ConcurrentHashMap<String, Future<*>>()
    private val workerMonitorIds = ConcurrentHashMap<String, Set<String>>()
    private val restarting = ConcurrentHashMap.newKeySet<String>()
    private val generations = ConcurrentHashMap<String, Long>()
    private val workerLock = Any()
    private lateinit var store: ProfileStore

    override fun onCreate() {
        super.onCreate()
        try {
            store = ProfileStore(this)
            startForeground(NOTIFICATION_ID, summaryNotification())
        } catch (_: SecureStoreException) {
            startForeground(NOTIFICATION_ID, basicNotification("암호화된 설정을 읽지 못했어"))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::store.isInitialized) return START_NOT_STICKY
        try {
        when (intent?.action) {
            ACTION_START_MONITOR -> intent.getStringExtra(EXTRA_MONITOR_ID)?.let {
                if (store.setMonitorActive(it, true)) { restartAccountWorker(it); startActiveWorkers() }
                else publishLimitError(it)
            }
            ACTION_STOP_MONITOR -> intent.getStringExtra(EXTRA_MONITOR_ID)?.let(::stopMonitor)
            ACTION_START_ALL -> { store.setAllActive(true); restartAllWorkers(); startActiveWorkers() }
            ACTION_STOP_ALL -> stopAll()
            ACTION_VERIFY_KORAIL_PAYMENT -> intent.getStringExtra(EXTRA_MONITOR_ID)?.let(::requestPaymentCheck)
            else -> startActiveWorkers() // START_STICKY process recovery
        }
        refreshSummary()
        stopIfIdle()
        return START_STICKY
        } catch (_: SecureStoreException) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, basicNotification("암호화된 설정을 읽지 못했어"))
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun startActiveWorkers() {
        synchronized(workerLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        }
        val active = store.monitors().filter { it.active }
        val byAccount = active.groupBy { it.accountId }
        synchronized(workerLock) {
            workers.keys.filterNot { it in byAccount }.forEach {
                generations[it] = (generations[it] ?: 0L) + 1L
                workers.remove(it)?.cancel(true)
                workerMonitorIds.remove(it)
            }
        }
        byAccount.forEach { (accountId, monitors) ->
            val configs = monitors.mapNotNull(store::configFor)
            if (configs.isEmpty()) return@forEach
            val json = JSONArray().apply { configs.forEach { put(JSONObject(it.toJson())) } }.toString()
            synchronized(workerLock) {
                if (workers[accountId]?.isDone == false) return@forEach
                val generation = (generations[accountId] ?: 0L) + 1L
                generations[accountId] = generation
                workerMonitorIds[accountId] = monitors.map { it.id }.toSet()
                workers[accountId] = executor.submit {
                    try {
                        val callback = StatusProxy { raw -> handleEvent(raw, accountId, generation) }
                        Python.getInstance().getModule("multi_engine").callAttr("run_account_json", json, callback)
                    } catch (error: Exception) {
                        if (generations[accountId] == generation) monitors.forEach { emitFailure(it.id, error) }
                    } finally {
                        synchronized(workerLock) {
                            if (generations[accountId] == generation) {
                                workers.remove(accountId)
                                workerMonitorIds.remove(accountId)
                            }
                        }
                        if (generations[accountId] == generation) {
                            startActiveWorkers()
                            refreshSummary()
                            stopIfIdle()
                        }
                    }
                }
            }
        }
    }

    private fun stopMonitor(id: String) {
        restarting.remove(id)
        store.setMonitorActive(id, false)
        if (Python.isStarted()) {
            try {
                Python.getInstance().getModule("multi_engine")
                    .callAttr("stop_monitors", JSONArray().put(id).toString())
            } catch (_: Exception) { }
        }
        store.updateLastStatus(id, "STOPPED", "감시 중지")
        broadcast(id, "STOPPED", "감시 중지")
    }

    private fun restartAccountWorker(monitorId: String) {
        val accountId = store.monitors().firstOrNull { it.id == monitorId }?.accountId ?: return
        if (workers[accountId]?.isDone != false || !Python.isStarted()) return
        val ids = workerMonitorIds[accountId].orEmpty().toList()
        restarting.addAll(ids)
        try {
            Python.getInstance().getModule("multi_engine")
                .callAttr("stop_monitors", JSONArray(ids).toString())
        } catch (_: Exception) { }
    }

    private fun restartAllWorkers() {
        if (!Python.isStarted() || workers.values.none { !it.isDone }) return
        val ids = workerMonitorIds.values.flatten()
        restarting.addAll(ids)
        try {
            Python.getInstance().getModule("multi_engine")
                .callAttr("stop_monitors", JSONArray(ids).toString())
        } catch (_: Exception) { }
    }

    private fun stopAll() {
        val ids = store.monitors().filter { it.active }.map { it.id }
        restarting.removeAll(ids.toSet())
        store.setAllActive(false)
        if (Python.isStarted() && ids.isNotEmpty()) {
            try {
                Python.getInstance().getModule("multi_engine")
                    .callAttr("stop_monitors", JSONArray(ids).toString())
            } catch (_: Exception) { }
        }
        ids.forEach { store.updateLastStatus(it, "STOPPED", "감시 중지"); broadcast(it, "STOPPED", "감시 중지") }
    }

    private fun requestPaymentCheck(id: String) {
        if (!Python.isStarted()) return
        try { Python.getInstance().getModule("multi_engine").callAttr("request_payment_check", id) }
        catch (_: Exception) { }
    }

    private fun handleEvent(raw: String, accountId: String, generation: Long) {
        if (generations[accountId] != generation) return
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        val id = event.optString("monitorId")
        val code = event.optString("statusCode")
        val terminal = event.optBoolean("terminal", code in TERMINAL_CODES)
        val message = event.optString("message")
        if (id.isBlank() || code.isBlank()) return
        if (code == "STOPPED" && restarting.remove(id)) {
            store.updateLastStatus(id, "STARTING", "감시 구성 갱신 중")
            broadcast(id, "STARTING", "감시 구성 갱신 중")
            return
        }
        store.updateLastStatus(id, code, message)
        broadcast(id, code, message)
        if (terminal) store.setMonitorActive(id, false)
        val monitor = store.monitors().firstOrNull { it.id == id }
        val text = "${monitor?.name ?: "감시"}: $message"
        val manager = getSystemService(NotificationManager::class.java)
        if (terminal || code == "PAYMENT_PENDING") {
            manager.notify(resultNotificationId(id), resultNotification(id, text, code, event))
        }
        refreshSummary()
        stopIfIdle()
    }

    private fun emitFailure(id: String, error: Exception) {
        val message = "감시 실행 실패: ${error::class.simpleName ?: "오류"}"
        store.updateLastStatus(id, "ERROR", message)
        store.setMonitorActive(id, false)
        broadcast(id, "ERROR", message)
    }

    private fun publishLimitError(id: String) {
        broadcast(id, "ERROR", "동시 활성 감시는 최대 ${ProfileStore.MAX_ACTIVE}개야")
    }

    private fun broadcast(id: String, code: String, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_MONITOR_ID, id).putExtra(EXTRA_STATUS_CODE, code).putExtra(EXTRA_STATUS, message))
    }

    private fun refreshSummary() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, summaryNotification())
    }

    private fun summaryNotification(): Notification {
        val active = if (::store.isInitialized) store.monitors().count { it.active } else 0
        val pending = if (::store.isInitialized) store.monitors().count {
            store.lastStatus(it.id)?.first == "PAYMENT_PENDING"
        } else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("Rail Watch")
            .setContentText("활성 $active/${ProfileStore.MAX_ACTIVE} · 결제 대기 $pending")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(active > 0).setOnlyAlertOnce(true).build()
    }

    private fun basicNotification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("Rail Watch").setContentText(message)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()

    private fun resultNotification(id: String, text: String, code: String, event: JSONObject): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("Rail Watch")
            .setContentText(text.take(180)).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(code == "PAYMENT_PENDING").setAutoCancel(code != "PAYMENT_PENDING")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (code == "PAYMENT_PENDING") {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KORAIL_PAYMENT_URL))
            builder.addAction(R.drawable.ic_stat_srt, "공식 결제", PendingIntent.getActivity(
                this, requestCode(id, 1), webIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val verify = Intent(this, MonitorService::class.java).apply {
                action = ACTION_VERIFY_KORAIL_PAYMENT
                putExtra(EXTRA_MONITOR_ID, id)
                putExtra(EXTRA_KORAIL_TRAIN_NO, event.optString("trainNo"))
            }
            builder.addAction(R.drawable.ic_stat_srt, "결제 확인", PendingIntent.getForegroundService(
                this, requestCode(id, 2), verify, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun requestCode(id: String, salt: Int): Int = 3000 + (id.hashCode() xor salt).ushr(1) % 500_000
    private fun resultNotificationId(id: String): Int = 10_000 + id.hashCode().ushr(1) % 500_000

    private fun stopIfIdle() {
        if (::store.isInitialized && store.monitors().none { it.active } && workers.values.none { !it.isDone }) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (::store.isInitialized && Python.isStarted()) {
            val ids = store.monitors().filter { it.active }.map { it.id }
            if (ids.isNotEmpty()) try {
                Python.getInstance().getModule("multi_engine").callAttr("stop_monitors", JSONArray(ids).toString())
            } catch (_: Exception) { }
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS = "com.chebaka.srtmonitor.STATUS"
        const val ACTION_START_MONITOR = "com.chebaka.srtmonitor.START_MONITOR"
        const val ACTION_STOP_MONITOR = "com.chebaka.srtmonitor.STOP_MONITOR"
        const val ACTION_START_ALL = "com.chebaka.srtmonitor.START_ALL"
        const val ACTION_STOP_ALL = "com.chebaka.srtmonitor.STOP_ALL"
        const val ACTION_VERIFY_KORAIL_PAYMENT = "com.chebaka.srtmonitor.VERIFY_KORAIL_PAYMENT"
        const val EXTRA_MONITOR_ID = "monitor_id"
        const val EXTRA_STATUS_CODE = "status_code"
        const val EXTRA_STATUS = "status"
        const val EXTRA_KORAIL_TRAIN_NO = "korail_train_no"
        const val CHANNEL_ID = "srt_monitor"
        const val NOTIFICATION_ID = 1001
        private val TERMINAL_CODES = setOf("COMPLETED", "ERROR", "EXPIRED", "STOPPED")
        private const val KORAIL_PAYMENT_URL = "https://www.korail.com/ticket/reservation/list"
    }
}
