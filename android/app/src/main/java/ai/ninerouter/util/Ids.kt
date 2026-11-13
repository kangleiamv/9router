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

    /**
     * Antigravity expects a managed project id in its envelope. When Google has
     * not provisioned one (or the lookup is temporarily unavailable) the web
     * client sends a generated id in the same shape instead of an empty value,
     * which the API rejects with 404 "Requested entity was not found".
     */
    fun generatedProjectId(): String {
        val adjectives = arrayOf("useful", "bright", "swift", "calm", "bold")
        val nouns = arrayOf("fuze", "wave", "spark", "flow", "core")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(5)
        return "${adjectives[random.nextInt(adjectives.size)]}-" +
            "${nouns[random.nextInt(nouns.size)]}-$suffix"
    }
}
