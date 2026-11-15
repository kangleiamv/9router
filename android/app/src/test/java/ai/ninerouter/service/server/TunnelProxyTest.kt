package ai.ninerouter.service.server

import ai.ninerouter.core.TunnelLink
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelRoute
import ai.ninerouter.service.server.TunnelProxy.bridgeWebSocket
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TunnelProxyTest {
    private fun link(target: String) = TunnelLink(
        id = "test",
        name = "Test",
        path = "/site",
        target = target,
        mode = TunnelLinks.MODE_NAMED,
    )

    @Test
    fun `forwards method, path, query, body and response headers`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-Upstream", "yes")
                .setHeader("Content-Type", "text/plain")
                .setBody("hello from upstream"),
        )
        upstream.start()
        val proxy = startProxy(upstream.url("/").toString().trimEnd('/'))
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("http://127.0.0.1:${proxy.port}/site/hello?x=1")
                .post("payload".toRequestBody("text/plain".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("yes", response.header("X-Upstream"))
                assertEquals("hello from upstream", response.body!!.string())
            }
            val recorded = upstream.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/hello?x=1", recorded.path)
            assertEquals("payload", recorded.body.readUtf8())
            assertEquals("https", recorded.getHeader("X-Forwarded-Proto"))
            assertEquals("/site", recorded.getHeader("X-Forwarded-Prefix"))
            assertTrue(recorded.getHeader("X-Forwarded-Host")!!.startsWith("127.0.0.1:"))
        } finally {
            proxy.stop()
            upstream.shutdown()
        }
    }

    @Test
    fun `streams a large request body without truncation`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setResponseCode(204))
        upstream.start()
        val proxy = startProxy(upstream.url("/").toString().trimEnd('/'))
        try {
            val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("http://127.0.0.1:${proxy.port}/site/upload")
                .post(payload.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(204, response.code)
            }
            val recorded = upstream.takeRequest()
            assertEquals(payload.size.toLong(), recorded.body.size)
            assertTrue(recorded.body.readByteArray().contentEquals(payload))
        } finally {
            proxy.stop()
            upstream.shutdown()
        }
    }

    @Test
    fun `rewrites loopback redirects for the public mount`() = runBlocking<Unit> {
        val upstream = MockWebServer()
        upstream.start()
        upstream.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:${upstream.port}/login?next=%2Fdashboard"),
        )
        val proxy = startProxy(upstream.url("/").toString().trimEnd('/'))
        try {
            val client = OkHttpClient.Builder().followRedirects(false).build()
            val request = Request.Builder()
                .url("http://127.0.0.1:${proxy.port}/site/private")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(302, response.code)
                assertEquals(
                    "https://127.0.0.1:${proxy.port}/site/login?next=%2Fdashboard",
                    response.header("Location"),
                )
            }
            upstream.takeRequest()
        } finally {
            proxy.stop()
            upstream.shutdown()
        }
    }

    @Test
    fun `returns 502 when the local service is down`() = runBlocking {
        val upstream = MockWebServer()
        upstream.start()
        val target = upstream.url("/").toString().trimEnd('/')
        upstream.shutdown()
        val proxy = startProxy(target)
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("http://127.0.0.1:${proxy.port}/site/")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(502, response.code)
                assertTrue(response.body!!.string().contains("Cannot reach"))
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `bridges websocket text frames to the local service`() = runBlocking {
        val upstream = MockWebServer()
        upstream.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("echo:$text")
                }
            }),
        )
        upstream.start()
        val proxy = startProxy(
            target = upstream.url("/").toString().trimEnd('/'),
            webSockets = true,
        )
        try {
            val result = AtomicReference<String>()
            val closed = CountDownLatch(1)
            val client = OkHttpClient()
            val socket = client.newWebSocket(
                Request.Builder()
                    .url("ws://127.0.0.1:${proxy.port}/site/ws")
                    .build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("hello")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        result.set(text)
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.countDown()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.countDown()
                    }
                },
            )
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertEquals("echo:hello", result.get())
            val recorded = upstream.takeRequest()
            assertEquals("/ws", recorded.path)
            socket.cancel()
        } finally {
            proxy.stop()
            upstream.shutdown()
        }
    }

    private class ProxyHandle(
        val port: Int,
        private val engine: ApplicationEngine,
    ) {
        suspend fun stop() {
            engine.stop(0, 500)
        }
    }

    private suspend fun startProxy(target: String, webSockets: Boolean = false): ProxyHandle {
        val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            if (webSockets) {
                install(WebSockets) {
                    pingPeriodMillis = 20_000
                    timeoutMillis = 20_000
                    masking = false
                }
                routing {
                    webSocket("/{...}") {
                        val routeLink = link(target)
                        bridgeWebSocket(TunnelRoute(routeLink, TunnelLinks.forwardUrl(routeLink, call.request.uri)))
                    }
                }
            } else {
                routing {
                    route("/{...}") {
                        handle {
                            val routeLink = link(target)
                            TunnelProxy.forward(
                                call,
                                TunnelRoute(routeLink, TunnelLinks.forwardUrl(routeLink, call.request.uri)),
                            )
                        }
                    }
                }
            }
        }.start(wait = false)
        val port = engine.resolvedConnectors().first().port
        return ProxyHandle(port, engine)
    }
}
