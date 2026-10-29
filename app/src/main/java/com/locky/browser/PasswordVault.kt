package com.locky.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords are encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available) and never leaves it. Only ciphertext is stored on disk.
 */
class PasswordVault(context: Context) {
    data class Entry(val id: String, val site: String, val user: String, val password: String)

    private val prefs = context.getSharedPreferences("locky_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val b = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    fun list(): List<Entry> =
        prefs.all.entries.mapNotNull { (k, v) ->
            if (!k.startsWith("e_") || v !is String) return@mapNotNull null
            try {
                val o = JSONObject(decrypt(v))
                Entry(k.removePrefix("e_"), o.getString("site"), o.getString("user"), o.getString("password"))
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.site.lowercase() }

    fun save(site: String, user: String, password: String) {
        val id = UUID.randomUUID().toString()
        val json = JSONObject().put("site", site).put("user", user).put("password", password)
        prefs.edit().putString("e_$id", encrypt(json.toString())).apply()
    }

    fun delete(id: String) {
        prefs.edit().remove("e_$id").apply()
    }

    companion object {
        private const val ALIAS = "locky_vault_key"
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_"

        fun generatePassword(length: Int = 16): String {
            val r = SecureRandom()
            return buildString { repeat(length) { append(CHARS[r.nextInt(CHARS.length)]) } }
        }
    }
}
