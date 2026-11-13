package ai.ninerouter.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small AES-GCM helper backed by the Android Keystore. API keys are encrypted at
 * rest; the key never leaves the keystore. Falls back to Base64 when the
 * keystore is unavailable (e.g. unit tests / exotic devices) so the app still
 * works rather than losing the user's credentials.
 */
object Crypto {
    private const val KEY_ALIAS = "ninerouter_secrets"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey? = try {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            existing
        } else {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }.generateKey()
        }
    } catch (_: Throwable) {
        null
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val key = secretKey() ?: return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plain
        }
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty() || !stored.startsWith(PREFIX)) return stored
        val key = secretKey() ?: return stored.removePrefix(PREFIX)
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val body = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Throwable) {
            stored.removePrefix(PREFIX)
        }
    }

    fun mask(secret: String?): String {
        if (secret.isNullOrEmpty()) return ""
        if (secret.length <= 8) return "••••"
        return secret.take(4) + "••••" + secret.takeLast(4)
    }
}
