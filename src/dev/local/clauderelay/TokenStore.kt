package dev.local.clauderelay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pairing token is encrypted at rest with an AndroidKeyStore-backed AES-GCM
 * key (plain javax.crypto/android.security.keystore -- platform APIs, not a
 * library, so this works without androidx.security-crypto which the
 * gradle-less Termux toolchain can't fetch). Host/port aren't secret and
 * live in plain SharedPreferences.
 */
object TokenStore {
    private const val KEYSTORE_ALIAS = "clauderelay_token_key"
    private const val PREFS = "clauderelay_prefs"
    private const val TOKEN_FILE = "token.enc"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun save(context: Context, token: String, host: String, port: Int) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        File(context.filesDir, TOKEN_FILE).writeText(packed)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", host)
            .putInt("port", port)
            .putBoolean("paired", true)
            .apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paired", false)

    fun getHost(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("host", "") ?: ""

    fun getPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port", 8790)

    fun getToken(context: Context): String? {
        val f = File(context.filesDir, TOKEN_FILE)
        if (!f.exists()) return null
        return try {
            val packed = f.readText()
            val parts = packed.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, TOKEN_FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
        }
    }
}
