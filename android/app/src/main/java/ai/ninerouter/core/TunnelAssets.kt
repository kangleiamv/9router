package ai.ninerouter.core

import android.content.Context
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Runtime assets and executable checks for the bundled Cloudflare connector.
 *
 * The binary is Termux's Android/arm64 build of cloudflared: an ELF PIE linked
 * against Bionic (`/system/bin/linker64`). Android can execute it from
 * nativeLibraryDir, and its cgo resolver uses Android's DNS directly, so no
 * hosts patch or launcher process is needed.
 *
 * The CA bundle is separate: Go's x509 loader does not read Android's hashed
 * system store, so SSL_CERT_FILE points at this extracted Mozilla bundle.
 */
object TunnelAssets {
    private const val CA_ASSET = "cacert.pem"
    private const val CA_MIN_BYTES = 1_000L
    private const val ELF_CLASS_64 = 2
    private const val ELF_DATA_LSB = 1
    private const val ET_DYN = 3
    private const val PT_INTERP = 3
    private const val ANDROID_LINKER = "/system/bin/linker64"

    /** Extract the bundled Mozilla CA store into the app's files directory. */
    fun prepareCaBundle(context: Context): Result<File> = runCatching {
        val workingDir = File(context.filesDir, "cloudflared").apply { mkdirs() }
        check(workingDir.isDirectory) { "Could not create the cloudflared directory." }
        val file = File(workingDir, CA_ASSET)
        context.assets.open(CA_ASSET).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        check(file.length() > CA_MIN_BYTES) { "The bundled CA store is empty." }
        file
    }

    /**
     * Return null when [file] is an Android PIE, or a human-readable reason.
     *
     * Android refuses ET_EXEC outright. A glibc PIE would pass the kernel check
     * but fail on its interpreter, so require the Bionic linker as well. Both
     * checks read only the ELF header and program headers.
     */
    fun verifyAndroidPie(file: File): String? {
        if (!file.isFile) return "cloudflared is missing."
        if (file.length() < 64L) return "cloudflared is truncated."
        return try {
            RandomAccessFile(file, "r").use { elf ->
                val ident = ByteArray(16)
                elf.readFully(ident)
                val magic = byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
                if (!ident.copyOfRange(0, 4).contentEquals(magic)) {
                    return "cloudflared is not an ELF binary."
                }
                if (ident[4].toInt() != ELF_CLASS_64 || ident[5].toInt() != ELF_DATA_LSB) {
                    return "cloudflared is not a 64-bit little-endian binary."
                }
                elf.seek(16)
                val type = elf.readU16Le()
                if (type != ET_DYN) {
                    return "cloudflared is not position-independent (ELF type $type)."
                }
                elf.seek(32)
                val programOffset = elf.readU64Le()
                elf.seek(54)
                val entrySize = elf.readU16Le()
                val entryCount = elf.readU16Le()
                if (entrySize < 56 || entryCount == 0) {
                    return "cloudflared has no usable program headers."
                }
                var interpreter: String? = null
                for (index in 0 until entryCount) {
                    val base = programOffset + index.toLong() * entrySize
                    elf.seek(base)
                    val headerType = elf.readU32Le()
                    if (headerType != PT_INTERP.toLong()) continue
                    elf.seek(base + 8)
                    val offset = elf.readU64Le()
                    elf.seek(base + 32)
                    val size = elf.readU64Le()
                    if (size <= 0L || size > 4096L) {
                        return "cloudflared has an invalid ELF interpreter."
                    }
                    val bytes = ByteArray(size.toInt())
                    elf.seek(offset)
                    elf.readFully(bytes)
                    interpreter = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                }
                when (interpreter) {
                    ANDROID_LINKER -> null
                    null -> "cloudflared has no ELF interpreter."
                    else -> "cloudflared targets $interpreter instead of $ANDROID_LINKER."
                }
            }
        } catch (_: EOFException) {
            "cloudflared's ELF headers are truncated."
        } catch (t: Throwable) {
            "Could not inspect cloudflared: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun RandomAccessFile.readU16Le(): Int {
        val first = read()
        val second = read()
        if (second < 0) throw EOFException()
        return (first and 0xff) or ((second and 0xff) shl 8)
    }

    private fun RandomAccessFile.readU32Le(): Long {
        var value = 0L
        for (shift in 0 until 32 step 8) {
            val byte = read()
            if (byte < 0) throw EOFException()
            value = value or ((byte.toLong() and 0xffL) shl shift)
        }
        return value
    }

    private fun RandomAccessFile.readU64Le(): Long {
        var value = 0L
        for (shift in 0 until 64 step 8) {
            val byte = read()
            if (byte < 0) throw EOFException()
            value = value or ((byte.toLong() and 0xffL) shl shift)
        }
        return value
    }
}
