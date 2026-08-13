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
    val operator: String = "SRT",
    val depCode: String = "",
    val arrCode: String = "",
    val monitorId: String = "",
    val accountId: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("srtId", srtId); put("srtPassword", srtPassword)
        put("dep", dep); put("arr", arr); put("date", date)
        put("timeFrom", timeFrom); put("timeTo", timeTo)
        put("passengers", passengers); put("special", special)
        put("windowSeat", windowSeat); put("pollMin", pollMin); put("pollMax", pollMax)
        put("autoPay", autoPay)
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
    private val alias = "SrtWatchProfileKey"

    init { synchronized(PROCESS_LOCK) { migrateLegacyProfiles(); pauseAfterReboot(context) } }

    fun save(profileName: String, config: MonitorConfig): MonitorDefinition = synchronized(PROCESS_LOCK) {
        val accounts = accounts().toMutableList()
        val monitors = monitors().toMutableList()
        val old = monitors.firstOrNull { it.id == config.monitorId }
        if (config.monitorId.isNotBlank() && old == null) throw IllegalArgumentException("편집할 감시를 찾지 못했어")
        if (old == null && monitors.any { it.name == profileName }) throw IllegalArgumentException("같은 이름의 감시가 이미 있어")
        val selectedAccount = accounts.firstOrNull { it.id == config.accountId }
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
            cardExpire = config.cardExpire, cardValidation = config.cardValidation,
        )
        if (accountChanged && monitors.any { it.accountId == existingAccount?.id && it.active }) {
            throw IllegalArgumentException("실행 중인 감시가 쓰는 계정은 중지한 뒤 수정해")
        }
        val account = existingAccount?.copy(
            operator = config.operator, loginId = config.srtId, password = config.srtPassword,
            cardNumber = config.cardNumber, cardPassword = config.cardPassword,
            cardExpire = config.cardExpire, cardValidation = config.cardValidation,
        ) ?: RailAccount(
            UUID.randomUUID().toString(), "${config.operator} ${config.srtId.takeLast(4)}",
            config.operator, config.srtId, config.srtPassword, config.cardNumber,
            config.cardPassword, config.cardExpire, config.cardValidation,
        )
        existingAccount?.let(accounts::remove)
        accounts.add(account)
        val monitor = MonitorDefinition(
            old?.id ?: UUID.randomUUID().toString(), profileName, account.id, config.dep, config.arr,
            config.date, config.timeFrom, config.timeTo, config.passengers, config.special,
            config.windowSeat, config.pollMin, config.pollMax, config.autoPay, config.depCode,
            config.arrCode, old?.active ?: false,
        )
        old?.let(monitors::remove)
        monitors.add(monitor)
        write(accounts, monitors)
        prefs.edit().putString(ACTIVE_MONITOR_KEY, monitor.id).apply()
        monitor
    }

    fun load(profileName: String): MonitorConfig? =
        monitors().firstOrNull { it.name == profileName }?.let(::configFor)

    fun listProfiles(): List<String> = monitors().map { it.name }.sorted()

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
        return MonitorConfig(
            account.loginId, account.password, monitor.dep, monitor.arr, monitor.date,
            monitor.timeFrom, monitor.timeTo, monitor.passengers, monitor.special,
            monitor.windowSeat, monitor.pollMin, monitor.pollMax, monitor.autoPay,
            account.cardNumber, account.cardPassword, account.cardExpire, account.cardValidation,
            account.operator, monitor.depCode, monitor.arrCode, monitor.id, account.id,
        )
    }

    fun configForId(id: String): MonitorConfig? = monitors().firstOrNull { it.id == id }?.let(::configFor)

    fun setMonitorActive(id: String, active: Boolean): Boolean = synchronized(PROCESS_LOCK) {
        val all = monitors().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized false
        if (active && !all[index].active && all.count { it.active } >= MAX_ACTIVE) return@synchronized false
        all[index] = all[index].copy(active = active)
        write(accounts(), all)
        true
    }

    fun setAllActive(active: Boolean): Int = synchronized(PROCESS_LOCK) {
        val all = monitors()
        val changed = all.mapIndexed { index, item -> item.copy(active = active && index < MAX_ACTIVE) }
        write(accounts(), changed)
        changed.count { it.active }
    }

    fun deleteMonitor(id: String): Boolean = synchronized(PROCESS_LOCK) {
        val all = monitors()
        if (all.firstOrNull { it.id == id }?.active == true) return@synchronized false
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

    fun updateLastStatus(id: String, code: String, message: String) = synchronized(PROCESS_LOCK) {
        val root = readObject(STATUS_KEY)
        root.put(id, JSONObject().put("code", code).put("message", message.take(300)))
        writeEncrypted(STATUS_KEY, root.toString())
    }

    fun lastStatus(id: String): Pair<String, String>? {
        val value = readObject(STATUS_KEY).optJSONObject(id) ?: return null
        return value.optString("code") to value.optString("message")
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

    private fun migrateLegacyProfiles() {
        if (prefs.getInt(SCHEMA_KEY, 0) >= SCHEMA_VERSION) return
        val raw = prefs.getString(LEGACY_PROFILES_KEY, null)
        if (raw == null) {
            write(emptyList(), emptyList())
            return
        }
        val legacy = try { JSONObject(decrypt(raw)) } catch (_: Exception) { throw SecureStoreException() }
        val accounts = mutableListOf<RailAccount>()
        val monitors = mutableListOf<MonitorDefinition>()
        val keys = legacy.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = legacy.optJSONObject(name) ?: throw SecureStoreException()
            val operator = o.optString("operator", "SRT")
            val account = accounts.firstOrNull {
                accountKey(it.operator, it.loginId) == accountKey(operator, o.optString("srtId")) &&
                    it.password == o.optString("srtPassword") && it.cardNumber == o.optString("cardNumber") &&
                    it.cardPassword == o.optString("cardPassword") && it.cardExpire == o.optString("cardExpire") &&
                    it.cardValidation == o.optString("cardValidation")
            } ?: RailAccount(
                UUID.randomUUID().toString(), "$operator ${o.optString("srtId").takeLast(4)}", operator,
                o.optString("srtId"), o.optString("srtPassword"), o.optString("cardNumber"),
                o.optString("cardPassword"), o.optString("cardExpire"), o.optString("cardValidation"),
            ).also(accounts::add)
            monitors += MonitorDefinition(
                UUID.randomUUID().toString(), name, account.id, o.optString("dep"), o.optString("arr"),
                o.optString("date"), o.optString("timeFrom"), o.optString("timeTo"),
                o.optInt("passengers", 1), o.optBoolean("special"), o.optBoolean("windowSeat"),
                o.optInt("pollMin", 30), o.optInt("pollMax", 60), o.optBoolean("autoPay"),
                o.optString("depCode"), o.optString("arrCode"), false,
            )
        }
        write(accounts, monitors)
        prefs.getString(LEGACY_ACTIVE_KEY, null)?.let { legacyName ->
            monitors.firstOrNull { it.name == legacyName }?.let {
                prefs.edit().putString(ACTIVE_MONITOR_KEY, it.id).apply()
            }
        }
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

    private fun readArray(key: String): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        val array = try { JSONArray(decrypt(raw)) } catch (_: Exception) { throw SecureStoreException() }
        return (0 until array.length()).map { array.optJSONObject(it) ?: throw SecureStoreException() }
    }

    private fun readObject(key: String): JSONObject {
        val raw = prefs.getString(key, null) ?: return JSONObject()
        return try { JSONObject(decrypt(raw)) } catch (_: Exception) { JSONObject() }
    }

    private fun writeEncrypted(key: String, value: String) {
        check(prefs.edit().putString(key, encrypt(value)).commit()) { "상태 저장 실패" }
    }

    private fun accountJson(a: RailAccount) = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("operator", a.operator); put("loginId", a.loginId)
        put("password", a.password); put("cardNumber", a.cardNumber); put("cardPassword", a.cardPassword)
        put("cardExpire", a.cardExpire); put("cardValidation", a.cardValidation)
    }

    private fun accountKey(operator: String, loginId: String): String =
        "${operator.trim().uppercase(Locale.ROOT)}|${loginId.trim().lowercase(Locale.ROOT)}"

    private fun monitorJson(m: MonitorDefinition) = JSONObject().apply {
        put("id", m.id); put("name", m.name); put("accountId", m.accountId); put("dep", m.dep); put("arr", m.arr)
        put("date", m.date); put("timeFrom", m.timeFrom); put("timeTo", m.timeTo); put("passengers", m.passengers)
        put("special", m.special); put("windowSeat", m.windowSeat); put("pollMin", m.pollMin); put("pollMax", m.pollMax)
        put("autoPay", m.autoPay); put("depCode", m.depCode); put("arrCode", m.arrCode); put("active", m.active)
    }

    private fun accountFromJson(o: JSONObject) = try {
        RailAccount(o.getString("id"), o.optString("name"), o.getString("operator"), o.getString("loginId"),
            o.getString("password"), o.optString("cardNumber"), o.optString("cardPassword"),
            o.optString("cardExpire"), o.optString("cardValidation"))
    } catch (_: Exception) { null }

    private fun monitorFromJson(o: JSONObject) = try {
        MonitorDefinition(o.getString("id"), o.getString("name"), o.getString("accountId"), o.getString("dep"),
            o.getString("arr"), o.getString("date"), o.getString("timeFrom"), o.getString("timeTo"),
            o.getInt("passengers"), o.getBoolean("special"), o.getBoolean("windowSeat"), o.getInt("pollMin"),
            o.getInt("pollMax"), o.getBoolean("autoPay"), o.optString("depCode"), o.optString("arrCode"),
            o.optBoolean("active"))
    } catch (_: Exception) { null }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), StandardCharsets.UTF_8)
    }

    companion object {
        private val PROCESS_LOCK = Any()
        const val MAX_ACTIVE = 5
        const val NEW_ACCOUNT_ID = "__new_account__"
        private const val SCHEMA_VERSION = 2
        private const val SCHEMA_KEY = "schema_version"
        private const val ACCOUNTS_KEY = "rail_accounts"
        private const val MONITORS_KEY = "monitor_definitions"
        private const val STATUS_KEY = "monitor_status"
        private const val ACTIVE_MONITOR_KEY = "active_monitor"
        private const val LEGACY_PROFILES_KEY = "profiles"
        private const val LEGACY_ACTIVE_KEY = "active_profile"
        private const val BOOT_COUNT_KEY = "last_boot_count"
    }
}
