package ai.ninerouter.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LocalServicesTest {
    @Test
    fun `catalog covers web files and tcp services`() {
        assertTrue(LocalServices.forKind(TunnelLinks.KIND_HTTP).isNotEmpty())
        assertTrue(LocalServices.forKind(TunnelLinks.KIND_FTP).isNotEmpty())
        assertTrue(LocalServices.forKind(TunnelLinks.KIND_TCP).isNotEmpty())
        assertEquals("redis", LocalServices.byPort(6379)?.id)
        assertEquals("postgres", LocalServices.byPort(5432)?.id)
        assertEquals("mysql", LocalServices.byPort(3306)?.id)
        assertEquals("ftp", LocalServices.byPort(2121)?.id)
        assertNull(LocalServices.byPort(65534))
    }

    @Test
    fun `every preset produces a valid loopback target`() {
        LocalServices.presets.forEach { preset ->
            assertEquals(preset.kind, TunnelLinks.normalizeKind(preset.kind, preset.target))
            assertTrue(preset.target.startsWith("${preset.scheme}://127.0.0.1:${preset.port}"))
        }
    }

    @Test
    fun `scans only ports that are actually listening`() = runBlocking {
        val open = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val closed = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            val found = LocalServices.scan(listOf(open.localPort, closed), timeoutMs = 300)
            assertTrue(found.contains(open.localPort))
            assertFalse(found.contains(closed))
        } finally {
            open.close()
        }
    }

    @Test
    fun `client commands match the detected service`() {
        assertEquals("redis-cli -h 127.0.0.1 -p 6379", LocalServices.clientCommand(6379))
        assertEquals("psql -h 127.0.0.1 -p 5432 -U postgres", LocalServices.clientCommand(5432))
        assertEquals("mysql -h 127.0.0.1 -P 3306 -u root -p", LocalServices.clientCommand(3306))
        assertNull(LocalServices.clientCommand(8080))
    }

    @Test
    fun `remote setup runs access then the service client`() {
        val redis = TunnelLink(
            id = "r",
            name = "Redis",
            host = "redis.example.com",
            target = "tcp://127.0.0.1:6379",
            mode = TunnelLinks.MODE_NAMED,
            kind = TunnelLinks.KIND_TCP,
        )
        assertEquals(
            "cloudflared access tcp --hostname redis.example.com --url 127.0.0.1:6379\n" +
                "redis-cli -h 127.0.0.1 -p 6379",
            LocalServices.remoteCommand("redis.example.com", redis),
        )
    }
}
