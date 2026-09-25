package dev.aarso.data.workdeck

import android.content.Context
import android.util.Base64
import dev.aarso.security.KeystoreSecret
import java.security.MessageDigest
import java.security.SecureRandom

class WorkdeckPairingStore(context: Context) {
    private val preferences = context.getSharedPreferences("workdeck_pairing", Context.MODE_PRIVATE)

    @Synchronized fun secret(): ByteArray {
        preferences.getString(KEY_SECRET, null)?.let { encrypted ->
            return Base64.decode(KeystoreSecret.decrypt(encrypted), Base64.NO_WRAP).also {
                require(it.size == SECRET_BYTES) { "Stored Workdeck pairing secret is invalid." }
            }
        }

        // Migrate the short-lived pre-encryption prototype without rotating an already-paired
        // Kindle. It used a different key, so arbitrary damaged ciphertext is never treated as
        // a valid legacy value.
        preferences.getString(LEGACY_KEY_SECRET, null)?.let { legacy ->
            val decoded = runCatching { Base64.decode(legacy, Base64.NO_WRAP) }.getOrNull()
            if (decoded?.size == SECRET_BYTES) {
                persist(decoded)
                preferences.edit().remove(LEGACY_KEY_SECRET).apply()
                return decoded
            }
        }

        return ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes).also(::persist)
    }

    private fun persist(secret: ByteArray) {
        val encoded = Base64.encodeToString(secret, Base64.NO_WRAP)
        check(preferences.edit().putString(KEY_SECRET, KeystoreSecret.encrypt(encoded)).commit()) {
            "Unable to persist Workdeck pairing secret."
        }
    }

    fun pairingCode(): String = MessageDigest.getInstance("SHA-256").digest(secret())
        .take(6).joinToString("") { "%02X".format(it) }

    fun exportSecret(): String = Base64.encodeToString(secret(), Base64.NO_WRAP)

    fun rotate(): String {
        preferences.edit().remove(KEY_SECRET).commit()
        secret()
        return pairingCode()
    }

    private companion object {
        const val KEY_SECRET = "pairing_secret_encrypted_v2"
        const val LEGACY_KEY_SECRET = "pairing_secret_v1"
        const val SECRET_BYTES = 32
    }
}
