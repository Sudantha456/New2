package com.app.youtube.lite.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.app.youtube.lite.core.util.L

/**
 * Keystore-backed persistence for the session.
 *
 * Values are encrypted with AES-256-GCM, keys with AES-256-SIV, under a master key that lives
 * in the Android Keystore and never leaves the TEE/StrongBox. The whole blob is ~1 KB, so the
 * cost of the crypto is noise next to a network round trip.
 *
 * A plaintext fallback exists **only** for the (rare) case where a device has a broken
 * keystore provider: the app must still be able to log in there. The fallback file is
 * private to the app's sandbox and the failure is logged loudly.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createEncryptedPrefs(appContext) ?: createFallbackPrefs()

    fun getString(key: String): String? = prefs.getString(key, null)

    fun put(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { throwable ->
        L.e("SecureStore") { "EncryptedSharedPreferences unavailable, using fallback: $throwable" }
        null
    }

    private fun createFallbackPrefs(): SharedPreferences {
        // A previously corrupted keystore entry can wedge the encrypted file; drop it once.
        runCatching { appContext.deleteSharedPreferences(FILE_ENCRYPTED) }
        return appContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
    }

    companion object {
        private const val FILE_ENCRYPTED = "youtube_lite_secure"
        private const val FILE_FALLBACK = "youtube_lite_secure_plain"

        const val KEY_SESSION = "session_cookies_v1"
        const val KEY_VISITOR_DATA = "visitor_data_v1"
    }
}
