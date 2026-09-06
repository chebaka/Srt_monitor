package com.chebaka.srtmonitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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
    private val paymentChecks = ConcurrentHashMap.newKeySet<String>()
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
                if (store.setMonitorActive(it, true, intent.getStringExtra(EXTRA_AUTO_PAY_ARM).orEmpty())) { restartAccountWorker(it); startActiveWorkers() }
                else publishStartError(it)
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
        store.monitors().filter {
            it.active && store.lastStatus(it.id)?.first in setOf("PAID", "COMPLETED")
        }.forEach { store.setMonitorActive(it.id, false) }
        val runningIds = synchronized(workerLock) { workerMonitorIds.values.flatten().toSet() }
        store.monitors().filter {
            it.active && it.id !in runningIds && store.lastStatus(it.id)?.first in setOf(
                "RESERVE_IN_FLIGHT", "HOLD_CREATED", "PAYMENT_IN_FLIGHT"
            )
        }.forEach {
            store.setMonitorActive(it.id, false)
            store.updateLastStatus(it.id, "UNCERTAIN", "예약 요청 결과 불명 · KORAIL+ 예약내역 확인 필요")
            broadcast(it.id, "UNCERTAIN", "예약 요청 결과 불명 · KORAIL+ 예약내역 확인 필요")
        }
        synchronized(workerLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        }
        val active = store.monitors().filter { it.active }
        val byAccount = active.groupBy { store.workerKey(it) ?: it.accountId }
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
            val json = JSONArray().apply { configs.forEach { put(runtimeConfig(it)) } }.toString()
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

    private fun runtimeConfig(config: MonitorConfig): JSONObject = JSONObject(config.toJson()).apply {
        put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty())
        put("osVersion", Build.VERSION.RELEASE.orEmpty())
        put("deviceModel", Build.MODEL.orEmpty())
        put("deviceWidth", resources.displayMetrics.widthPixels)
        put("deviceHeight", resources.displayMetrics.heightPixels)
        put("androidSdkInt", Build.VERSION.SDK_INT)
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
        recordStop(id)
    }

    private fun restartAccountWorker(monitorId: String) {
        val monitor = store.monitors().firstOrNull { it.id == monitorId } ?: return
        val workerKey = store.workerKey(monitor) ?: return
        if (workers[workerKey]?.isDone != false || !Python.isStarted()) return
        val ids = workerMonitorIds[workerKey].orEmpty().toList()
        if (ids.any { store.lastStatus(it)?.first in MUTATION_CODES }) return
        restarting.addAll(ids)
        try {
            Python.getInstance().getModule("multi_engine")
                .callAttr("stop_monitors", JSONArray(ids).toString())
        } catch (_: Exception) { }
    }

    private fun restartAllWorkers() {
        if (!Python.isStarted() || workers.values.none { !it.isDone }) return
        val ids = workerMonitorIds.values.filter { group ->
            group.none { store.lastStatus(it)?.first in MUTATION_CODES }
        }.flatten()
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
        ids.forEach(::recordStop)
    }

    private fun recordStop(id: String) {
        val previous = store.lastStatus(id)?.first
        if (previous == "PAID") return
        val code = if (previous in MUTATION_CODES) "UNCERTAIN" else "STOPPED"
        val message = if (code == "UNCERTAIN") "감시 중지 · 예약·결제 결과 재확인 필요" else "감시 중지"
        store.updateLastStatus(id, code, message)
        broadcast(id, code, message)
    }

    private fun requestPaymentCheck(id: String) {
        val config = store.configForId(id) ?: return
        val attempt = store.lastStatusDetail(id) ?: return
        if (store.monitors().any { it.id == id && it.active } ||
            workerMonitorIds.values.any { id in it } ||
            attempt.optString("code") != "UNCERTAIN" ||
            attempt.optString("pnr").isBlank() || attempt.optString("attemptId").isBlank() ||
            !paymentChecks.add(id)) return
        val savedConfig = config.toJson()
        executor.submit {
            try {
                synchronized(workerLock) {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                }
                val result = JSONObject(Python.getInstance().getModule("korail_engine")
                    .callAttr("check_payment_json", runtimeConfig(config).toString(), attempt.toString()).toString())
                if (store.lastStatusDetail(id)?.optString("attemptId") != attempt.optString("attemptId") ||
                    store.configForId(id)?.toJson() != savedConfig || store.lastStatus(id)?.first != "UNCERTAIN") return@submit
                val code = if (result.optString("statusCode") == "PAID") "PAID" else "UNCERTAIN"
                val message = result.optString("message", "결제 결과 재확인 필요")
                store.updateLastStatus(id, code, message, result.optString("pnr"), result.optInt("amount"),
                    result.optString("attemptId"), result.optJSONObject("trip"))
                broadcast(id, code, message)
                getSystemService(NotificationManager::class.java).notify(resultNotificationId(id),
                    resultNotification(id, message, code, result))
            } catch (_: Exception) {
                broadcast(id, "UNCERTAIN", "결제 내역 조회 실패 · 저장된 예약 정보 유지")
            } finally {
                paymentChecks.remove(id)
                refreshSummary()
                stopIfIdle()
            }
        }
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
        store.updateLastStatus(
            id, code, message,
            event.optString("pnr"), event.optInt("amount"), event.optString("attemptId"),
            event.optJSONObject("trip"),
        )
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
        if (store.lastStatus(id)?.first == "PAID") return
        val code = if (store.lastStatus(id)?.first in MUTATION_CODES) "UNCERTAIN" else "ERROR"
        store.updateLastStatus(id, code, message)
        store.setMonitorActive(id, false)
        broadcast(id, code, message)
    }

    private fun publishStartError(id: String) {
        val monitor = store.monitors().firstOrNull { it.id == id }
        val message = monitor?.let(store::activationError) ?: "동시 활성 감시는 최대 ${ProfileStore.MAX_ACTIVE}개야"
        broadcast(id, "ERROR", message)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_srt).setContentTitle("Rail Watch")
            .setContentText("활성 좌석 감시 $active/${ProfileStore.MAX_ACTIVE}")
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
        if (code == "PAYMENT_PENDING" || code == "UNCERTAIN") {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KORAIL_PAYMENT_URL))
            builder.addAction(R.drawable.ic_stat_srt, if (code == "PAYMENT_PENDING") "공식 결제" else "예약내역 확인", PendingIntent.getActivity(
                this, requestCode(id, 1), webIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (code == "UNCERTAIN") {
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
        if (::store.isInitialized && store.monitors().none { it.active } && workers.values.none { !it.isDone } && paymentChecks.isEmpty()) {
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
        const val EXTRA_AUTO_PAY_ARM = "auto_pay_arm"
        const val CHANNEL_ID = "srt_monitor"
        const val NOTIFICATION_ID = 1001
        private val MUTATION_CODES = setOf("RESERVE_IN_FLIGHT", "HOLD_CREATED", "PAYMENT_IN_FLIGHT", "UNCERTAIN")
        private val TERMINAL_CODES = setOf(
            "API_INCOMPATIBLE", "AUTH_REJECTED", "AUTH_REQUIRED", "AUTH_UNVERIFIED",
            "COMPLETED", "ERROR", "EXPIRED", "LEGACY_DISABLED", "PAID", "SEAT_FOUND",
            "STOPPED", "UNCERTAIN",
        )
        private const val KORAIL_PAYMENT_URL = "https://www.korail.com/ticket/reservation/list"
    }
}
