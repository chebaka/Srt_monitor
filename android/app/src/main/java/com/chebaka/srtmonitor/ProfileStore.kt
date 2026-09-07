package com.chebaka.srtmonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RailAccount(
    val id: String,
    val name: String,
    val operator: String,
    val loginId: String,
    val password: String,
    val cardNumber: String = "",
    val cardPassword: String = "",
    val cardExpire: String = "",
    val cardValidation: String = "",
    val needsReauth: Boolean = false,
)

data class MonitorDefinition(
    val id: String,
    val name: String,
    val accountId: String,
    val dep: String,
    val arr: String,
    val date: String,
    val timeFrom: String,
    val timeTo: String,
    val passengers: Int,
    val special: Boolean,
    val windowSeat: Boolean,
    val pollMin: Int,
    val pollMax: Int,
    val autoPay: Boolean,
    val depCode: String = "",
    val arrCode: String = "",
    val active: Boolean = false,
    val legacyReadOnly: Boolean = false,
    val migratedFromId: String = "",
    val maxFareWon: Int = 0,
    val trainNo: String = "",
    val allowHighSpeedAuto: Boolean = false,
)

data class MonitorConfig(
    val srtId: String,
    val srtPassword: String,
    val dep: String,
    val arr: String,
    val date: String,
    val timeFrom: String,
    val timeTo: String,
    val passengers: Int,
    val special: Boolean,
    val windowSeat: Boolean,
    val pollMin: Int,
    val pollMax: Int,
    val autoPay: Boolean,
    val cardNumber: String,
    val cardPassword: String,
    val cardExpire: String,
    val cardValidation: String,
    val operator: String = ProfileStore.KORAIL_OPERATOR,
    val depCode: String = "",
    val arrCode: String = "",
    val monitorId: String = "",
    val accountId: String = "",
    val migratedFromId: String = "",
    val maxFareWon: Int = 0,
    val trainNo: String = "",
    val allowHighSpeedAuto: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("srtId", srtId); put("srtPassword", srtPassword)
        put("dep", dep); put("arr", arr); put("date", date)
        put("timeFrom", timeFrom); put("timeTo", timeTo)
        put("passengers", passengers); put("special", special)
        put("windowSeat", windowSeat); put("pollMin", pollMin); put("pollMax", pollMax)
        put("autoPay", autoPay)
        put("maxFareWon", maxFareWon)
        put("trainNo", trainNo)
        put("allowHighSpeedAuto", allowHighSpeedAuto)
        if (autoPay) {
            put("cardNumber", cardNumber); put("cardPassword", cardPassword)
            put("cardExpire", cardExpire); put("cardValidation", cardValidation)
        }
        put("operator", operator)
        put("depCode", depCode); put("arrCode", arrCode)
        put("monitorId", monitorId); put("accountId", accountId)
    }.toString()
}

class SecureStoreException : IllegalStateException("암호화된 설정을 읽지 못했어. 앱 데이터를 복구하거나 초기화해야 해.")

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("srt_secure_profile", Context.MODE_PRIVATE)
    private val alias = "RailWatchProfileKeyV3"

    init { synchronized(PROCESS_LOCK) {
        migrateProfiles()
        if (!processInitialized) {
            pauseUncertainReservations()
            pauseUnarmedAutoPay()
            pauseAfterReboot(context)
            processInitialized = true
        }
    } }

    fun save(profileName: String, config: MonitorConfig): MonitorDefinition = synchronized(PROCESS_LOCK) {
        require(config.operator == KORAIL_OPERATOR) { "KORAIL+ 감시만 새로 저장할 수 있어" }
        val accounts = accounts().toMutableList()
        val monitors = monitors().toMutableList()
        val old = monitors.firstOrNull { it.id == config.monitorId }
        require(old?.active != true) { "실행 중인 감시는 중지한 뒤 수정해" }
        require(old == null || lastStatus(old.id)?.first !in UNCERTAIN_CODES) {
            "예약·결제 결과를 확인한 뒤 조건을 수정해"
        }
        old?.let { AUTO_PAY_ARMS.remove(it.id) }
        if (config.monitorId.isNotBlank() && old == null) throw IllegalArgumentException("편집할 감시를 찾지 못했어")
        if (old?.legacyReadOnly == true) throw IllegalArgumentException("기존 SRT 감시는 KORAIL+로 복사해")
        if (old == null && monitors.any { it.name == profileName }) throw IllegalArgumentException("같은 이름의 감시가 이미 있어")
        if (config.migratedFromId.isNotBlank()) {
            require(monitors.any { it.id == config.migratedFromId && it.legacyReadOnly }) { "전환할 SRT 감시를 찾지 못했어" }
            require(monitors.none { it.migratedFromId == config.migratedFromId }) { "이미 KORAIL+로 전환한 감시야" }
        }
        val selectedAccount = accounts.firstOrNull { it.id == config.accountId }
        if (selectedAccount != null && selectedAccount.operator != KORAIL_OPERATOR) {
            throw IllegalArgumentException("KORAIL+ 계정을 선택해")
        }
        if (config.accountId == NEW_ACCOUNT_ID && accounts.any {
                accountKey(it.operator, it.loginId) == accountKey(config.operator, config.srtId)
            }) {
            throw IllegalArgumentException("같은 로그인 계정이 이미 있어. 저장된 계정을 선택해")
        }
        val existingAccount = selectedAccount
            ?: if (config.accountId == NEW_ACCOUNT_ID) null else old?.let { prior -> accounts.firstOrNull { it.id == prior.accountId } }
            ?: if (config.accountId == NEW_ACCOUNT_ID) null else accounts.firstOrNull {
                accountKey(it.operator, it.loginId) == accountKey(config.operator, config.srtId)
            }
        if (selectedAccount != null && accountKey(selectedAccount.operator, selectedAccount.loginId) != accountKey(config.operator, config.srtId)) {
            throw IllegalArgumentException("로그인 ID나 운영사를 바꾸려면 '새 계정 입력'을 선택해")
        }
        val accountChanged = existingAccount != null && existingAccount != existingAccount.copy(
            operator = config.operator, loginId = config.srtId, password = config.srtPassword,
            cardNumber = config.cardNumber, cardPassword = config.cardPassword,
            cardExpire = config.cardExpire, cardValidation = config.cardValidation, needsReauth = false,
        )
        if (accountChanged && monitors.any { it.accountId == existingAccount?.id && it.active }) {
            throw IllegalArgumentException("실행 중인 감시가 쓰는 계정은 중지한 뒤 수정해")
        }
        val account = existingAccount?.copy(
            operator = config.operator, loginId = config.srtId, password = config.srtPassword,
            cardNumber = config.cardNumber, cardPassword = config.cardPassword,
            cardExpire = config.cardExpire, cardValidation = config.cardValidation, needsReauth = false,
        ) ?: RailAccount(
            UUID.randomUUID().toString(), "${config.operator} ${config.srtId.takeLast(4)}",
            config.operator, config.srtId, config.srtPassword, config.cardNumber,
            config.cardPassword, config.cardExpire, config.cardValidation, false,
        )
        if (config.autoPay && monitors.any {
                it.id != old?.id && it.accountId == account.id && it.autoPay
            }) throw IllegalArgumentException("계정당 자동결제 감시는 하나만 저장할 수 있어")
        existingAccount?.let(accounts::remove)
        accounts.add(account)
        val monitor = MonitorDefinition(
            old?.id ?: UUID.randomUUID().toString(), profileName, account.id, config.dep, config.arr,
            config.date, config.timeFrom, config.timeTo, config.passengers, config.special,
            false, config.pollMin, config.pollMax, config.autoPay, config.depCode,
            config.arrCode, old?.active ?: false, false, old?.migratedFromId ?: config.migratedFromId,
            config.maxFareWon, config.trainNo, config.allowHighSpeedAuto,
        )
        old?.let(monitors::remove)
        monitors.add(monitor)
        write(accounts, monitors)
        prefs.edit().putString(ACTIVE_MONITOR_KEY, monitor.id).apply()
        monitor
    }

    fun load(profileName: String): MonitorConfig? =
        monitors().firstOrNull { it.name == profileName }?.let(::configFor)

    fun listProfiles(): List<String> = monitors().filterNot { it.legacyReadOnly }.map { it.name }.sorted()

    fun activeProfile(): String? {
        val id = prefs.getString(ACTIVE_MONITOR_KEY, null)
        return monitors().firstOrNull { it.id == id }?.name
            ?: prefs.getString(LEGACY_ACTIVE_KEY, null)
    }

    fun setActiveProfile(profileName: String) {
        monitors().firstOrNull { it.name == profileName }?.let {
            prefs.edit().putString(ACTIVE_MONITOR_KEY, it.id).apply()
        }
    }

    fun accounts(): List<RailAccount> = readArray(ACCOUNTS_KEY).map {
        accountFromJson(it) ?: throw SecureStoreException()
    }

    fun monitors(): List<MonitorDefinition> = readArray(MONITORS_KEY).map {
        monitorFromJson(it) ?: throw SecureStoreException()
    }

    fun configFor(monitor: MonitorDefinition): MonitorConfig? {
        val account = accounts().firstOrNull { it.id == monitor.accountId } ?: return null
        if (monitor.legacyReadOnly || account.operator != KORAIL_OPERATOR || account.needsReauth) return null
        return MonitorConfig(
            account.loginId, account.password, monitor.dep, monitor.arr, monitor.date,
            monitor.timeFrom, monitor.timeTo, monitor.passengers, monitor.special,
            monitor.windowSeat, monitor.pollMin, monitor.pollMax, monitor.autoPay,
            account.cardNumber, account.cardPassword, account.cardExpire, account.cardValidation,
            account.operator, monitor.depCode, monitor.arrCode, monitor.id, account.id, monitor.migratedFromId,
            monitor.maxFareWon, monitor.trainNo, monitor.allowHighSpeedAuto,
        )
    }

    fun configForId(id: String): MonitorConfig? = monitors().firstOrNull { it.id == id }?.let(::configFor)

    fun armAutoPay(id: String): String? = synchronized(PROCESS_LOCK) {
        val monitor = monitors().firstOrNull { it.id == id } ?: return@synchronized null
        val account = accounts().firstOrNull { it.id == monitor.accountId } ?: return@synchronized null
        if (!monitor.autoPay || activationError(monitor) != null) return@synchronized null
        UUID.randomUUID().toString().also { token ->
            AUTO_PAY_ARMS[id] = AutoPayArm(token, fingerprint(monitor, account), System.currentTimeMillis() + ARM_TTL_MS)
        }
    }

    fun setMonitorActive(id: String, active: Boolean, armingToken: String = ""): Boolean = synchronized(PROCESS_LOCK) {
        val all = monitors().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        if (active && activationError(all[index]) != null) return@synchronized false
        if (active && all[index].autoPay) {
            val account = accounts().firstOrNull { it.id == all[index].accountId } ?: return@synchronized false
            val arm = AUTO_PAY_ARMS.remove(id) ?: return@synchronized false
            if (arm.token != armingToken || arm.expiresAt < System.currentTimeMillis() || arm.fingerprint != fingerprint(all[index], account)) return@synchronized false
        }
        if (active && !all[index].active && all.count { it.active } >= MAX_ACTIVE) return@synchronized false
        all[index] = all[index].copy(active = active)
        write(accounts(), all)
        true
    }

    fun setAllActive(active: Boolean): Int = synchronized(PROCESS_LOCK) {
        val all = monitors()
        var enabled = all.count { it.active && it.autoPay }
        // Never touch a live autoPay monitor here: deactivating it mid-payment
        // would cancel its worker and lose the payment callback. AutoPay monitors
        // are started/stopped individually through the arming flow.
        val changed = all.map { item ->
            if (!active || item.autoPay) {
                if (active) item else item.copy(active = false)
            } else {
                val canStart = enabled < MAX_ACTIVE && activationError(item) == null
                if (canStart) enabled += 1
                item.copy(active = canStart)
            }
        }
        write(accounts(), changed)
        changed.count { it.active }
    }

    fun activationError(monitor: MonitorDefinition): String? {
        if (monitor.legacyReadOnly) return "통합 전 기존 감시는 실행할 수 없어. KORAIL+로 복사해"
        if (lastStatus(monitor.id)?.first in UNCERTAIN_CODES) return "예약 결과가 불명확해. KORAIL+ 예약내역 확인 후 상태를 해제해"
        if (lastStatus(monitor.id)?.first == "PAID") return "이미 결제 완료된 감시야. 새 감시를 만들어"
        val account = accounts().firstOrNull { it.id == monitor.accountId } ?: return "연결된 계정을 찾지 못했어"
        if (account.operator != KORAIL_OPERATOR) return "KORAIL+ 계정을 연결해"
        if (account.needsReauth || account.password.isBlank()) return "KORAIL+ 비밀번호를 다시 입력해"
        if (monitor.autoPay) {
            if (monitor.maxFareWon <= 0) return "자동결제 최대 금액을 입력해"
            if (listOf(account.cardNumber, account.cardPassword, account.cardExpire, account.cardValidation).any { it.isBlank() })
                return "자동결제 카드정보를 입력해"
            if (monitors().any { it.id != monitor.id && it.accountId == monitor.accountId && it.active && it.autoPay })
                return "이 계정의 다른 자동결제 감시가 실행 중이야"
        }
        if (monitor.allowHighSpeedAuto && !monitor.autoPay) return "고속열차 자동 처리는 자동결제 감시에서만 켤 수 있어"
        return null
    }

    fun workerKey(monitor: MonitorDefinition): String? = accounts().firstOrNull { it.id == monitor.accountId }
        ?.let { accountKey(it.operator, it.loginId) }

    fun clearReservationUncertainty(id: String): Boolean = synchronized(PROCESS_LOCK) {
        if (monitors().any { it.id == id && it.active }) return@synchronized false
        if (lastStatus(id)?.first !in UNCERTAIN_CODES) return@synchronized false
        updateLastStatus(id, "STOPPED", "KORAIL+ 예약내역 확인 완료 · 중지됨")
        true
    }

    fun migrationNoticePending(): Boolean = prefs.getBoolean(MIGRATION_NOTICE_KEY, false)

    fun dismissMigrationNotice() { prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, false).apply() }

    fun deleteMonitor(id: String): Boolean = synchronized(PROCESS_LOCK) {
        val all = monitors()
        if (all.firstOrNull { it.id == id }?.active == true) return@synchronized false
        if (lastStatus(id)?.first in UNCERTAIN_CODES) return@synchronized false
        write(accounts(), all.filterNot { it.id == id })
        all.any { it.id == id }
    }

    fun deleteAccount(id: String): Boolean = synchronized(PROCESS_LOCK) {
        if (monitors().any { it.accountId == id }) return@synchronized false
        val all = accounts()
        write(all.filterNot { it.id == id }, monitors())
        all.any { it.id == id }
    }

    fun overlaps(target: MonitorDefinition): Boolean = monitors().any {
        it.id != target.id && it.active && it.accountId == target.accountId &&
            it.date == target.date && it.dep == target.dep && it.arr == target.arr &&
            it.timeFrom <= target.timeTo && target.timeFrom <= it.timeTo
    }

    fun updateLastStatus(
        id: String, code: String, message: String,
        pnr: String = "", amount: Int = 0, attemptId: String = "",
        trip: JSONObject? = null,
    ) = synchronized(PROCESS_LOCK) {
        val root = readObject(STATUS_KEY)
        val previous = root.optJSONObject(id)
        val item = JSONObject().put("code", code).put("message", message.take(300))
        val keepMutation = (attemptId.isBlank() || attemptId == previous?.optString("attemptId")) &&
            code in setOf("RESERVE_IN_FLIGHT", "HOLD_CREATED", "PAYMENT_IN_FLIGHT", "UNCERTAIN", "PAID")
        val finalPnr = pnr.ifBlank { if (keepMutation) previous?.optString("pnr").orEmpty() else "" }
        val finalAttempt = attemptId.ifBlank { if (keepMutation) previous?.optString("attemptId").orEmpty() else "" }
        val finalAmount = if (amount > 0) amount else if (keepMutation) previous?.optInt("amount") ?: 0 else 0
        if (finalPnr.isNotBlank()) item.put("pnr", finalPnr)
        if (finalAttempt.isNotBlank()) item.put("attemptId", finalAttempt)
        if (finalAmount > 0) item.put("amount", finalAmount)
        val finalTrip = trip?.takeIf { it.length() > 0 } ?: if (keepMutation) previous?.optJSONObject("trip") else null
        if (finalTrip != null) item.put("trip", JSONObject(finalTrip.toString()))
        root.put(id, item)
        writeEncrypted(STATUS_KEY, root.toString())
    }

    fun lastStatus(id: String): Pair<String, String>? {
        val value = readObject(STATUS_KEY).optJSONObject(id) ?: return null
        return value.optString("code") to value.optString("message")
    }

    fun lastStatusDetail(id: String): JSONObject? = synchronized(PROCESS_LOCK) {
        readObject(STATUS_KEY).optJSONObject(id)?.let { JSONObject(it.toString()) }
    }

    private fun write(accounts: List<RailAccount>, monitors: List<MonitorDefinition>) {
        val accountArray = JSONArray().apply { accounts.forEach { put(accountJson(it)) } }
        val monitorArray = JSONArray().apply { monitors.forEach { put(monitorJson(it)) } }
        val saved = prefs.edit()
            .putString(ACCOUNTS_KEY, encrypt(accountArray.toString()))
            .putString(MONITORS_KEY, encrypt(monitorArray.toString()))
            .putInt(SCHEMA_KEY, SCHEMA_VERSION)
            .commit()
        check(saved) { "감시 설정 저장 실패" }
    }

    private fun migrateProfiles() {
        when (prefs.getInt(SCHEMA_KEY, 0)) {
            in SCHEMA_VERSION..Int.MAX_VALUE -> Unit
            2 -> migrateV2Profiles()
            else -> migrateLegacyProfiles()
        }
        verifyGraph(accounts(), monitors())
        cleanupLegacySecrets()
    }

    private fun migrateLegacyProfiles() {
        val raw = prefs.getString(LEGACY_PROFILES_KEY, null)
        if (raw == null) {
            write(emptyList(), emptyList())
            prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, false).apply()
            return
        }
        val legacy = try { JSONObject(decrypt(raw, LEGACY_KEY_ALIAS)) } catch (_: Exception) { throw SecureStoreException() }
        val accounts = mutableListOf<RailAccount>()
        val monitors = mutableListOf<MonitorDefinition>()
        val keys = legacy.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = legacy.optJSONObject(name) ?: throw SecureStoreException()
            val operator = o.optString("operator", "SRT")
            val account = accounts.firstOrNull {
                accountKey(it.operator, it.loginId) == accountKey(operator, o.optString("srtId"))
            } ?: RailAccount(
                UUID.randomUUID().toString(), "$operator ${o.optString("srtId").takeLast(4)}", operator,
                o.optString("srtId"), "", "", "", "", "", true,
            ).also(accounts::add)
            monitors += MonitorDefinition(
                UUID.randomUUID().toString(), name, account.id, o.optString("dep"), o.optString("arr"),
                o.optString("date"), o.optString("timeFrom"), o.optString("timeTo"),
                o.optInt("passengers", 1), o.optBoolean("special"), o.optBoolean("windowSeat"),
                o.optInt("pollMin", 30), o.optInt("pollMax", 60), false,
                o.optString("depCode"), o.optString("arrCode"), false, operator != KORAIL_OPERATOR,
            )
        }
        verifyV3(accounts, monitors)
        write(accounts, monitors)
        prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, accounts.isNotEmpty() || monitors.isNotEmpty()).apply()
    }

    private fun migrateV2Profiles() {
        val oldAccounts = readArray(LEGACY_ACCOUNTS_KEY, LEGACY_KEY_ALIAS).map {
            accountFromJson(it) ?: throw SecureStoreException()
        }
        val oldMonitors = readArray(LEGACY_MONITORS_KEY, LEGACY_KEY_ALIAS).map {
            monitorFromJson(it) ?: throw SecureStoreException()
        }
        verifyGraph(oldAccounts, oldMonitors)
        val operatorByAccount = oldAccounts.associate { it.id to it.operator }
        val migratedAccounts = oldAccounts.map {
            it.copy(password = "", cardNumber = "", cardPassword = "", cardExpire = "", cardValidation = "", needsReauth = true)
        }
        val migratedMonitors = oldMonitors.map {
            it.copy(
                active = false,
                autoPay = false,
                legacyReadOnly = operatorByAccount[it.accountId] != KORAIL_OPERATOR,
            )
        }
        verifyV3(migratedAccounts, migratedMonitors)
        write(migratedAccounts, migratedMonitors)
        prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, migratedAccounts.isNotEmpty() || migratedMonitors.isNotEmpty()).apply()
    }

    private fun verifyGraph(accounts: List<RailAccount>, monitors: List<MonitorDefinition>) {
        if (accounts.map { it.id }.toSet().size != accounts.size) throw SecureStoreException()
        if (monitors.map { it.id }.toSet().size != monitors.size) throw SecureStoreException()
        val accountIds = accounts.map { it.id }.toSet()
        if (monitors.any { it.accountId !in accountIds }) throw SecureStoreException()
    }

    private fun verifyV3(accounts: List<RailAccount>, monitors: List<MonitorDefinition>) {
        verifyGraph(accounts, monitors)
        if (accounts.any { it.cardNumber.isNotEmpty() || it.cardPassword.isNotEmpty() || it.cardExpire.isNotEmpty() || it.cardValidation.isNotEmpty() }) {
            throw SecureStoreException()
        }
        if (monitors.any { it.active || it.autoPay }) throw SecureStoreException()
        val operatorByAccount = accounts.associate { it.id to it.operator }
        if (monitors.any { it.legacyReadOnly != (operatorByAccount[it.accountId] != KORAIL_OPERATOR) }) throw SecureStoreException()
    }

    private fun cleanupLegacySecrets() {
        val readable = try { verifyGraph(accounts(), monitors()); true } catch (_: Exception) { false }
        if (!readable) throw SecureStoreException()
        check(prefs.edit()
            .remove(LEGACY_ACCOUNTS_KEY).remove(LEGACY_MONITORS_KEY).remove(LEGACY_PROFILES_KEY)
            .remove(LEGACY_ACTIVE_KEY).remove(LEGACY_SELECTED_MONITOR_KEY).remove(LEGACY_STATUS_KEY)
            .commit()) { "이전 보안정보 삭제 실패" }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(LEGACY_KEY_ALIAS)) ks.deleteEntry(LEGACY_KEY_ALIAS)
    }

    private fun pauseAfterReboot(context: Context) {
        val current = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
        catch (_: Exception) { return }
        val previous = prefs.getInt(BOOT_COUNT_KEY, -1)
        if (previous >= 0 && previous != current) {
            val all = monitors()
            if (all.any { it.active }) write(accounts(), all.map { it.copy(active = false) })
        }
        prefs.edit().putInt(BOOT_COUNT_KEY, current).apply()
    }

    private fun pauseUncertainReservations() {
        val affected = monitors().filter { it.active && lastStatus(it.id)?.first in UNCERTAIN_CODES }
        if (affected.isEmpty()) return
        val ids = affected.map { it.id }.toSet()
        write(accounts(), monitors().map { if (it.id in ids) it.copy(active = false) else it })
        affected.forEach { updateLastStatus(it.id, "UNCERTAIN", "예약 요청 결과 불명 · KORAIL+ 예약내역 확인 필요") }
    }

    private fun pauseUnarmedAutoPay() {
        val all = monitors()
        if (all.none { it.active && it.autoPay }) return
        write(accounts(), all.map { if (it.active && it.autoPay) it.copy(active = false) else it })
    }

    private fun fingerprint(monitor: MonitorDefinition, account: RailAccount): String = listOf(
        monitor.id, monitor.accountId, monitor.depCode, monitor.arrCode, monitor.date,
        monitor.timeFrom, monitor.timeTo, monitor.passengers, monitor.special,
        monitor.maxFareWon, account.cardNumber, account.cardExpire, account.cardValidation,
        monitor.trainNo, monitor.allowHighSpeedAuto,
    ).joinToString("|")

    private fun readArray(key: String, keyAlias: String = alias): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = try { JSONArray(decrypt(raw, keyAlias)) } catch (_: Exception) { throw SecureStoreException() }
        return (0 until array.length()).map { array.optJSONObject(it) ?: throw SecureStoreException() }
    }

    private fun readObject(key: String): JSONObject {
        val raw = prefs.getString(key, null) ?: return JSONObject()
        try {
            return JSONObject(decrypt(raw, alias))
        } catch (_: Exception) {
            throw SecureStoreException()
        }
    }

    private fun writeEncrypted(key: String, value: String) {
        check(prefs.edit().putString(key, encrypt(value)).commit()) { "상태 저장 실패" }
    }

    private fun accountJson(a: RailAccount) = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("operator", a.operator); put("loginId", a.loginId)
        put("password", a.password); put("cardNumber", a.cardNumber); put("cardPassword", a.cardPassword)
        put("cardExpire", a.cardExpire); put("cardValidation", a.cardValidation); put("needsReauth", a.needsReauth)
    }

    private fun accountKey(operator: String, loginId: String): String =
        "${operator.trim().uppercase(Locale.ROOT)}|${loginId.trim().lowercase(Locale.ROOT)}"

    private fun monitorJson(m: MonitorDefinition) = JSONObject().apply {
        put("id", m.id); put("name", m.name); put("accountId", m.accountId); put("dep", m.dep); put("arr", m.arr)
        put("date", m.date); put("timeFrom", m.timeFrom); put("timeTo", m.timeTo); put("passengers", m.passengers)
        put("special", m.special); put("windowSeat", m.windowSeat); put("pollMin", m.pollMin); put("pollMax", m.pollMax)
        put("autoPay", m.autoPay); put("depCode", m.depCode); put("arrCode", m.arrCode); put("active", m.active)
        put("legacyReadOnly", m.legacyReadOnly); put("migratedFromId", m.migratedFromId)
        put("maxFareWon", m.maxFareWon); put("trainNo", m.trainNo); put("allowHighSpeedAuto", m.allowHighSpeedAuto)
    }

    private fun accountFromJson(o: JSONObject) = try {
        RailAccount(o.getString("id"), o.optString("name"), o.getString("operator"), o.getString("loginId"),
            o.getString("password"), o.optString("cardNumber"), o.optString("cardPassword"),
            o.optString("cardExpire"), o.optString("cardValidation"), o.optBoolean("needsReauth"))
    } catch (_: Exception) { null }

    private fun monitorFromJson(o: JSONObject) = try {
        MonitorDefinition(o.getString("id"), o.getString("name"), o.getString("accountId"), o.getString("dep"),
            o.getString("arr"), o.getString("date"), o.getString("timeFrom"), o.getString("timeTo"),
            o.getInt("passengers"), o.getBoolean("special"), o.getBoolean("windowSeat"), o.getInt("pollMin"),
            o.getInt("pollMax"), o.getBoolean("autoPay"), o.optString("depCode"), o.optString("arrCode"),
            o.optBoolean("active"), o.optBoolean("legacyReadOnly"), o.optString("migratedFromId"),
            o.optInt("maxFareWon"), o.optString("trainNo"), o.optBoolean("allowHighSpeedAuto"))
    } catch (_: Exception) { null }

    private fun key(keyAlias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String, keyAlias: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(keyAlias), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), StandardCharsets.UTF_8)
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private data class AutoPayArm(val token: String, val fingerprint: String, val expiresAt: Long)
        private val AUTO_PAY_ARMS = ConcurrentHashMap<String, AutoPayArm>()
        private var processInitialized = false
        private const val ARM_TTL_MS = 5 * 60 * 1000L
        const val MAX_ACTIVE = 5
        const val NEW_ACCOUNT_ID = "__new_account__"
        const val KORAIL_OPERATOR = "KORAIL"
        private const val SCHEMA_VERSION = 3
        private const val SCHEMA_KEY = "schema_version"
        private const val ACCOUNTS_KEY = "rail_accounts_v3"
        private const val MONITORS_KEY = "monitor_definitions_v3"
        private const val STATUS_KEY = "monitor_status_v3"
        private const val LEGACY_ACCOUNTS_KEY = "rail_accounts"
        private const val LEGACY_MONITORS_KEY = "monitor_definitions"
        private const val LEGACY_STATUS_KEY = "monitor_status"
        private const val ACTIVE_MONITOR_KEY = "active_monitor_v3"
        private const val LEGACY_SELECTED_MONITOR_KEY = "active_monitor"
        private const val LEGACY_PROFILES_KEY = "profiles"
        private const val LEGACY_ACTIVE_KEY = "active_profile"
        private const val LEGACY_KEY_ALIAS = "SrtWatchProfileKey"
        private const val MIGRATION_NOTICE_KEY = "migration_v3_notice"
        private const val BOOT_COUNT_KEY = "last_boot_count"
        private val UNCERTAIN_CODES = setOf("RESERVE_IN_FLIGHT", "HOLD_CREATED", "PAYMENT_IN_FLIGHT", "UNCERTAIN")
    }
}
