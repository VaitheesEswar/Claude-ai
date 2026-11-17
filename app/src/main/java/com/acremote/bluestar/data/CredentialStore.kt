package com.acremote.bluestar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedLogin(val authId: String, val password: String, val isEmail: Boolean)

/**
 * Keeps the Blue Star login on the device so the app can sign in again when the
 * cloud session expires. The password is encrypted with an AES-GCM key that
 * lives in the Android Keystore and never leaves it.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("acremote", Context.MODE_PRIVATE)

    fun load(): SavedLogin? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val enc = prefs.getString(KEY_PW, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val password = runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return SavedLogin(id, password, prefs.getBoolean(KEY_EMAIL, false))
    }

    /** The last-used ID (without password), to prefill the login form. */
    fun lastAuthId(): Pair<String, Boolean>? =
        prefs.getString(KEY_ID, null)?.let { it to prefs.getBoolean(KEY_EMAIL, false) }

    fun save(login: SavedLogin) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val enc = cipher.doFinal(login.password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_ID, login.authId)
            .putBoolean(KEY_EMAIL, login.isEmail)
            .putString(KEY_PW, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** Forgets the password but remembers the ID for convenience. */
    fun clearPassword() {
        prefs.edit().remove(KEY_PW).remove(KEY_IV).remove(KEY_DEVICE).apply()
    }

    var selectedDeviceId: String?
        get() = prefs.getString(KEY_DEVICE, null)
        set(value) = prefs.edit().putString(KEY_DEVICE, value).apply()

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

    private companion object {
        const val ALIAS = "acremote_login_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_ID = "auth_id"
        const val KEY_EMAIL = "is_email"
        const val KEY_PW = "pw_enc"
        const val KEY_IV = "pw_iv"
        const val KEY_DEVICE = "device_id"
    }
}
