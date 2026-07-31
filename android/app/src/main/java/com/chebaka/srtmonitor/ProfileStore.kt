package com.chebaka.srtmonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    val cardValidation: String
) {
    fun toJson(): String = JSONObject().apply {
        put("srtId", srtId); put("srtPassword", srtPassword)
        put("dep", dep); put("arr", arr); put("date", date)
        put("timeFrom", timeFrom); put("timeTo", timeTo)
        put("passengers", passengers); put("special", special)
        put("windowSeat", windowSeat); put("pollMin", pollMin); put("pollMax", pollMax)
        put("autoPay", autoPay); put("cardNumber", cardNumber)
        put("cardPassword", cardPassword); put("cardExpire", cardExpire)
        put("cardValidation", cardValidation)
    }.toString()
}

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("srt_secure_profile", Context.MODE_PRIVATE)
    private val alias = "SrtWatchProfileKey"
    private val profilesKey = "profiles"
    private val activeKey = "active_profile"

    fun save(profileName: String, config: MonitorConfig) {
        val profiles = readProfiles()
        profiles.put(profileName, JSONObject(config.toJson()))
        val saved = prefs.edit()
            .putString(profilesKey, encrypt(profiles.toString()))
            .putString(activeKey, profileName)
            .commit()
        check(saved) { "프로필 저장 실패" }
    }

    fun load(profileName: String): MonitorConfig? {
        val o = readProfiles().optJSONObject(profileName) ?: return null
        return try {
            MonitorConfig(
                o.getString("srtId"), o.getString("srtPassword"), o.getString("dep"), o.getString("arr"),
                o.getString("date"), o.getString("timeFrom"), o.getString("timeTo"), o.getInt("passengers"),
                o.getBoolean("special"), o.getBoolean("windowSeat"), o.getInt("pollMin"), o.getInt("pollMax"),
                o.getBoolean("autoPay"), o.optString("cardNumber"), o.optString("cardPassword"),
                o.optString("cardExpire"), o.optString("cardValidation")
            )
        } catch (_: Exception) { null }
    }

    fun listProfiles(): List<String> {
        val result = mutableListOf<String>()
        val profiles = readProfiles()
        val keys = profiles.keys()
        while (keys.hasNext()) result += keys.next()
        return result.sorted()
    }

    fun activeProfile(): String? = prefs.getString(activeKey, null)

    fun setActiveProfile(profileName: String) {
        prefs.edit().putString(activeKey, profileName).apply()
    }

    private fun readProfiles(): JSONObject {
        val raw = prefs.getString(profilesKey, null) ?: return JSONObject()
        return try { JSONObject(decrypt(raw)) } catch (_: Exception) { JSONObject() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), StandardCharsets.UTF_8)
    }
}
