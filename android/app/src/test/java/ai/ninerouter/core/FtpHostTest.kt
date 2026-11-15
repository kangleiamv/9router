package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpHostTest {
    @Test
    fun `defaults are off and need no storage permission`() {
        val defaults = FtpHostStore.decode(null)
        assertFalse(defaults.enabled)
        assertEquals(2121, defaults.port)
        assertTrue(defaults.root.isBlank())
        assertTrue(defaults.password.isBlank())
        assertEquals(defaults, FtpHostStore.defaults)
    }

    @Test
    fun `settings survive storage with the password intact`() {
        val stored = FtpHostStore.encode(
            FtpHostSettings(
                enabled = true,
                port = 2222,
                username = "android",
                password = "hunter2",
                root = "/storage/emulated/0/Download",
                readOnly = true,
            ),
        )
        val restored = FtpHostStore.decode(stored)
        assertEquals(2222, restored.port)
        assertEquals("android", restored.username)
        assertEquals("hunter2", restored.password)
        assertEquals("/storage/emulated/0/Download", restored.root)
        assertTrue(restored.enabled)
        assertTrue(restored.readOnly)
    }

    @Test
    fun `malformed storage falls back to defaults`() {
        assertEquals(FtpHostStore.defaults, FtpHostStore.decode("{not json"))
        assertEquals("android", FtpHostStore.decode(null).username)
    }

    @Test
    fun `rejects ports outside the safe range`() {
        assertTrue(runCatching { FtpHostStore.require(FtpHostSettings(port = 21)) }.isFailure)
        assertTrue(runCatching { FtpHostStore.require(FtpHostSettings(port = 70000)) }.isFailure)
        assertTrue(runCatching { FtpHostStore.require(FtpHostSettings(port = 41000)) }.isFailure)
        assertEquals(2121, FtpHostStore.require(FtpHostSettings()).port)
    }

    @Test
    fun `rejects relative roots and traversal`() {
        assertTrue(runCatching { FtpHostStore.require(FtpHostSettings(root = "ftp")) }.isFailure)
        assertTrue(runCatching { FtpHostStore.require(FtpHostSettings(root = "/sdcard/../etc")) }.isFailure)
        assertEquals("/sdcard/files", FtpHostStore.require(FtpHostSettings(root = " /sdcard/files ")).root)
    }

    @Test
    fun `rejects a password without a username`() {
        assertTrue(
            runCatching { FtpHostStore.require(FtpHostSettings(username = " ", password = "secret")) }.isFailure,
        )
        assertTrue(
            runCatching { FtpHostStore.require(FtpHostSettings(password = "sec\nret")) }.isFailure,
        )
    }

    @Test
    fun `flags shared storage that needs broad access`() {
        assertFalse(FtpHostStore.needsBroadAccess(""))
        assertFalse(FtpHostStore.needsBroadAccess("/data/user/0/ai.ninerouter/files/ftp"))
        assertTrue(FtpHostStore.needsBroadAccess("/storage/emulated/0/Download"))
        assertTrue(FtpHostStore.needsBroadAccess("/mnt/media_rw/sdcard1/"))
    }

    @Test
    fun `reserves passive data ports`() {
        assertTrue(FtpHostStore.isPassivePort(41000))
        assertTrue(FtpHostStore.isPassivePort(41009))
        assertFalse(FtpHostStore.isPassivePort(41010))
        assertFalse(FtpHostStore.isPassivePort(2121))
        assertEquals("ftp://127.0.0.1:2121", FtpHostRuntime.target(2121))
    }
}
