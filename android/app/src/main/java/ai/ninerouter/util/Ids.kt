package ai.ninerouter.util

import java.security.SecureRandom
import java.util.UUID

object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun connectionId(): String = UUID.randomUUID().toString()

    fun apiKey(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val sb = StringBuilder("sk-9r-")
        for (b in bytes) sb.append(ALPHABET[(b.toInt() and 0xFF) % ALPHABET.length])
        return sb.toString()
    }

    fun requestId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
}
