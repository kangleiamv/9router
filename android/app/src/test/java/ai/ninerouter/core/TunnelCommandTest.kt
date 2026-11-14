package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TunnelCommandTest {
    @Test
    fun `quick tunnel points cloudflared at the restricted loopback port`() {
        val args = TunnelCommand.build(
            binary = "/data/app/lib/libcloudflared.so",
            options = TunnelOptions(localPort = 20128),
            configPath = "/tmp/quick.yml",
        )
        assertEquals("/data/app/lib/libcloudflared.so", args.first())
        assertTrue(args.containsAll(listOf("tunnel", "--url", "http://127.0.0.1:20128")))
        assertTrue(args.containsAll(listOf("--protocol", "http2")))
        assertTrue(args.containsAll(listOf("--metrics", "127.0.0.1:0")))
        assertTrue(args.containsAll(listOf("--retries", "2")))
        assertTrue(args.containsAll(listOf("--config", "/tmp/quick.yml")))
        assertFalse(args.contains("--token"))
        assertFalse(args.contains("run"))
        assertFalse(args.contains("--edge-ip-version"))
    }

    @Test
    fun `named tunnel uses the token and never the local url`() {
        val args = TunnelCommand.build(
            binary = "cloudflared",
            options = TunnelOptions(localPort = 20128, mode = "token", token = "secret-token"),
        )
        assertTrue(args.containsAll(listOf("tunnel", "run", "--token", "secret-token")))
        assertTrue(args.containsAll(listOf("--protocol", "http2")))
        assertTrue(args.containsAll(listOf("--metrics", "127.0.0.1:0")))
        assertTrue(args.containsAll(listOf("--retries", "99")))
        assertFalse(args.contains("--url"))
        assertFalse(args.contains("--config"))
        assertFalse(args.contains("--edge-ip-version"))
    }

    @Test
    fun `quick tunnel url parser ignores the api host and keeps the last match`() {
        assertNull(TunnelLog.quickTunnelUrl("https://api.trycloudflare.com/v1/health"))
        assertEquals(
            "https://quiet-river.trycloudflare.com",
            TunnelLog.quickTunnelUrl(
                "INF https://first.trycloudflare.com then https://quiet-river.trycloudflare.com",
            ),
        )
        assertEquals(
            "https://upbeat-cloud.trycloudflare.com",
            TunnelLog.quickTunnelUrl("|  https://UPBEAT-CLOUD.trycloudflare.com  |"),
        )
    }

    @Test
    fun `registered log line detection is case insensitive`() {
        assertTrue(TunnelLog.isRegistered("INF Registered tunnel connection connIndex=0"))
        assertTrue(TunnelLog.isRegistered("registered tunnel connection"))
        assertFalse(TunnelLog.isRegistered("Connection terminated"))
    }

    @Test
    fun `public hostname is normalized to https`() {
        assertEquals("https://router.example.com", TunnelLog.publicUrlForHostname("router.example.com"))
        assertEquals("https://router.example.com", TunnelLog.publicUrlForHostname(" https://router.example.com/ "))
        assertEquals("http://router.example.com", TunnelLog.publicUrlForHostname("http://router.example.com"))
        assertEquals("", TunnelLog.publicUrlForHostname("  "))
    }

    @Test
    fun `public listener port stays inside the valid range`() {
        assertEquals(20128, TunnelPorts.publicPort(20127))
        assertEquals(20128, TunnelPorts.publicPort(65535))
        assertEquals(20128, TunnelPorts.publicPort(80))
        assertEquals(65535, TunnelPorts.publicPort(65534))
    }

    @Test
    fun `ansi escapes are stripped for the diagnostics view`() {
        assertEquals("INF Registered tunnel connection", TunnelLog.stripAnsi("\u001B[32mINF Registered tunnel connection\u001B[0m"))
        assertEquals("plain", TunnelLog.stripAnsi("plain"))
    }

    @Test
    fun `bundled cloudflared is an Android PIE`() {
        val binary = bundledBinary()
        assertNotNull("Bundled libcloudflared.so was not found", binary)
        assertNull(TunnelAssets.verifyAndroidPie(binary!!))
    }

    @Test
    fun `elf verifier rejects non PIE executables`() {
        val file = writeElf(type = 2, interpreter = "/system/bin/linker64")
        val reason = TunnelAssets.verifyAndroidPie(file)
        assertTrue(reason.orEmpty().contains("position-independent"))
    }

    @Test
    fun `elf verifier rejects binaries for another linker`() {
        val file = writeElf(type = 3, interpreter = "/lib/ld-linux-aarch64.so.1")
        val reason = TunnelAssets.verifyAndroidPie(file)
        assertTrue(reason.orEmpty().contains("targets /lib/ld-linux-aarch64.so.1"))
    }

    @Test
    fun `elf verifier rejects a PIE without an interpreter`() {
        val file = writeElf(type = 3, interpreter = null)
        val reason = TunnelAssets.verifyAndroidPie(file)
        assertTrue(reason.orEmpty().contains("no ELF interpreter"))
    }

    private fun bundledBinary(): File? {
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
            it.parentFile
        }
        return roots.take(5)
            .flatMap { root ->
                sequenceOf(
                    File(root, "app/src/main/jniLibs/arm64-v8a/libcloudflared.so"),
                    File(root, "src/main/jniLibs/arm64-v8a/libcloudflared.so"),
                )
            }
            .firstOrNull { it.isFile }
    }

    private fun writeElf(type: Int, interpreter: String?): File {
        val programOffset = 64
        val programEntrySize = 56
        val interpreterBytes = interpreter?.toByteArray(Charsets.UTF_8)?.plus(0)
        val interpreterOffset = programOffset + programEntrySize
        val data = ByteArray(interpreterOffset + (interpreterBytes?.size ?: 0))
        data[0] = 0x7f
        data[1] = 'E'.code.toByte()
        data[2] = 'L'.code.toByte()
        data[3] = 'F'.code.toByte()
        data[4] = 2
        data[5] = 1
        data[6] = 1
        putU16(data, 16, type)
        putU16(data, 18, 183)
        putU64(data, 32, programOffset.toLong())
        putU16(data, 54, programEntrySize)
        putU16(data, 56, 1)
        if (interpreterBytes != null) {
            putU32(data, programOffset, 3)
            putU64(data, programOffset + 8, interpreterOffset.toLong())
            putU64(data, programOffset + 32, interpreterBytes.size.toLong())
            interpreterBytes.copyInto(data, interpreterOffset)
        } else {
            putU32(data, programOffset, 1)
        }
        return File.createTempFile("cloudflared-elf", ".bin").apply {
            deleteOnExit()
            writeBytes(data)
        }
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) {
            target[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
        }
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = ((value ushr (index * 8)) and 0xff).toByte()
        }
    }
}
