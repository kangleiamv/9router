package ai.ninerouter.service.server

import ai.ninerouter.core.FtpHostSettings
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Starts the embedded FTP server on a real loopback socket and drives it with
 * Apache Commons Net, which is what the in-app browser uses.
 */
class EmbeddedFtpServerTest {
    private lateinit var root: File
    private val client = FTPClient()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ftp-host-test").toFile()
    }

    @After
    fun tearDown() {
        runCatching { if (client.isConnected) client.disconnect() }
        EmbeddedFtpServer.stop()
        root.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(
        port: Int,
        readOnly: Boolean = false,
        username: String = "android",
        password: String = "secret",
    ) {
        EmbeddedFtpServer.start(
            FtpHostSettings(
                enabled = true,
                port = port,
                username = username,
                password = password,
                root = root.absolutePath,
                readOnly = readOnly,
            ),
            root,
        )
    }

    private fun connect(port: Int, user: String = "android", pass: String = "secret") {
        client.connect("127.0.0.1", port)
        assertTrue("connect reply ${client.replyCode}", FTPReply.isPositiveCompletion(client.replyCode))
        client.enterLocalPassiveMode()
        assertTrue("login reply ${client.replyCode}", client.login(user, pass))
    }

    @Test
    fun `serves uploads and downloads over loopback`() {
        val port = freePort()
        start(port)
        assertTrue(EmbeddedFtpServer.isRunning())
        connect(port)

        assertTrue(client.storeFile("hello.txt", ByteArrayInputStream("hello ftp".toByteArray())))
        assertEquals("hello ftp", File(root, "hello.txt").readText())

        val out = ByteArrayOutputStream()
        assertTrue(client.retrieveFile("/hello.txt", out))
        assertEquals("hello ftp", out.toString(Charsets.UTF_8.name()))

        val names = client.listNames("/").orEmpty().toList()
        assertTrue(names.contains("hello.txt"))
        assertTrue(client.logout())
    }

    @Test
    fun `read only mode refuses writes but allows reads`() {
        val port = freePort()
        File(root, "existing.txt").writeText("keep")
        start(port, readOnly = true)
        connect(port)

        val out = ByteArrayOutputStream()
        assertTrue(client.retrieveFile("/existing.txt", out))
        assertEquals("keep", out.toString(Charsets.UTF_8.name()))

        val stored = runCatching {
            client.storeFile("blocked.txt", ByteArrayInputStream("nope".toByteArray()))
        }.getOrDefault(false)
        assertFalse(stored)
        assertFalse(File(root, "blocked.txt").exists())
    }

    @Test
    fun `anonymous accounts accept any password`() {
        val port = freePort()
        start(port, username = "", password = "")
        connect(port, user = "anonymous", pass = "guest@example.com")

        assertTrue(client.storeFile("anon.txt", ByteArrayInputStream("a".toByteArray())))
        assertEquals("a", File(root, "anon.txt").readText())
    }

    @Test
    fun `wrong credentials are rejected`() {
        val port = freePort()
        start(port)
        client.connect("127.0.0.1", port)
        assertFalse(client.login("android", "wrong"))
    }

    @Test
    fun `stop releases the listener`() {
        val port = freePort()
        start(port)
        assertTrue(EmbeddedFtpServer.isRunning())
        EmbeddedFtpServer.stop()
        assertFalse(EmbeddedFtpServer.isRunning())
        var bound = false
        var attempts = 0
        while (!bound && attempts < 40) {
            bound = runCatching { ServerSocket(port).use { it.isBound } }.getOrDefault(false)
            attempts++
            if (!bound) Thread.sleep(50)
        }
        assertTrue(bound)
    }
}
