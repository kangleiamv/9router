package ai.ninerouter.service.server

import ai.ninerouter.core.AppRepository
import ai.ninerouter.core.LinkTunnelRuntime
import ai.ninerouter.core.TunnelLinks
import ai.ninerouter.core.TunnelRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards requests matched by a [ai.ninerouter.core.TunnelLink] to another
 * loopback service. This is intentionally a proxy, not a tunnel-owned web
 * server: websites, APIs, SSE streams and WebSockets all keep working.
 *
 * The OkHttp client never follows redirects (the browser should see the
 * target's 3xx) and has no response timeout so long-lived streams survive.
 */
internal object TunnelProxy {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val HOP_BY_HOP = setOf(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

    private val WS_SKIP = HOP_BY_HOP + setOf(
        "host",
        "content-length",
        "sec-websocket-key",
        "sec-websocket-version",
        "sec-websocket-accept",
        "sec-websocket-extensions",
        "sec-websocket-protocol",
    )

    /** Install the link matcher ahead of the built-in public routes. */
    fun Application.installLinkRouter(repo: AppRepository) {
        intercept(ApplicationCallPipeline.Call) {
            if (call.request.headers[HttpHeaders.Upgrade]?.equals("websocket", true) == true) {
                return@intercept
            }
            val links = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
            if (links.isEmpty()) return@intercept
            val route = TunnelLinks.match(
                links,
                call.request.headers[HttpHeaders.Host],
                call.request.uri,
                LinkTunnelRuntime.hosts.value,
            ) ?: return@intercept
            forward(call, route)
            finish()
        }
    }

    internal suspend fun forward(call: ApplicationCall, route: TunnelRoute) {
        // A mount without a trailing slash makes relative asset links resolve
        // from the site root. Send browsers to /site/ before proxying.
        val requestPath = call.request.uri.substringBefore('?')
        if (route.link.stripPrefix &&
            route.link.path.isNotBlank() &&
            requestPath == route.link.path &&
            (call.request.httpMethod == io.ktor.http.HttpMethod.Get ||
                call.request.httpMethod == io.ktor.http.HttpMethod.Head)
        ) {
            val query = call.request.uri.substringAfter('?', "")
            call.respondRedirect(
                route.link.path + "/" + if (query.isNotEmpty()) "?$query" else "",
                permanent = false,
            )
            return
        }

        // FTP links are served by the in-app browser; there is no HTTP origin
        // to forward to and cloudflared quick tunnels cannot carry FTP itself.
        if (TunnelLinks.isFtp(route.link)) {
            FtpBrowser.serve(call, route.link)
            return
        }

        val target = runCatching { Request.Builder().url(route.targetUrl) }.getOrNull()
            ?: return call.respondText(
                "Invalid local target for ${route.link.name}.",
                status = HttpStatusCode.BadGateway,
            )

        val method = call.request.httpMethod.value
        val mayHaveBody = method !in setOf("GET", "HEAD", "OPTIONS", "TRACE")
        val length = call.request.contentLength()
        val mediaType = call.request.contentType()?.toString()?.toMediaTypeOrNull()
        val body = when {
            !mayHaveBody -> null
            length == 0L -> ByteArray(0).toRequestBody(mediaType)
            else -> streamBody(call.receiveChannel().toInputStream(), mediaType)
        }

        val prefix = route.link.path.takeIf { route.link.stripPrefix && it.isNotBlank() }
        call.request.headers.entries().forEach { (name, values) ->
            if (name.lowercase() !in HOP_BY_HOP &&
                !name.equals(HttpHeaders.Host, true) &&
                !name.equals(HttpHeaders.ContentLength, true) &&
                !name.equals("X-Forwarded-Prefix", true)
            ) {
                values.forEach { value -> target.addHeader(name, value) }
            }
        }
        target.header("X-Forwarded-Proto", "https")
        call.request.headers[HttpHeaders.Host]?.let { target.header("X-Forwarded-Host", it) }
        call.request.headers["CF-Connecting-IP"]?.let { target.header("X-Forwarded-For", it) }
        if (!prefix.isNullOrBlank()) target.header("X-Forwarded-Prefix", prefix)
        target.method(method, body)

        val response = try {
            withContext(Dispatchers.IO) { client.newCall(target.build()).execute() }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            return call.respondText(
                "Cannot reach ${route.link.name} at ${route.link.target}: ${t.message ?: "connection failed"}",
                status = HttpStatusCode.BadGateway,
            )
        }

        try {
            call.response.status(HttpStatusCode.fromValue(response.code))
            response.headers.forEach { (name, value) ->
                val lower = name.lowercase()
                if (lower !in HOP_BY_HOP && lower != "content-length") {
                    val out = if (lower == "location") {
                        TunnelLinks.publicLocation(
                            value,
                            route.link,
                            call.request.headers[HttpHeaders.Host],
                        )
                    } else {
                        value
                    }
                    call.response.headers.append(name, out)
                }
            }
            if (method == "HEAD" || response.code == 204 || response.code == 304) {
                call.respondOutputStream(status = HttpStatusCode.fromValue(response.code)) { }
                return
            }
            val upstream = response.body ?: return
            call.respondOutputStream(status = HttpStatusCode.fromValue(response.code)) producer@{
                withContext(Dispatchers.IO) {
                    upstream.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            this@producer.write(buffer, 0, read)
                            this@producer.flush()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching {
                call.respondText(
                    "Local service ${route.link.name} failed: ${t.message ?: "proxy error"}",
                    status = HttpStatusCode.BadGateway,
                )
            }
        } finally {
            response.close()
        }
    }

    /** OkHttp 4 has no InputStream body; stream without buffering the upload. */
    private fun streamBody(input: java.io.InputStream, mediaType: okhttp3.MediaType?): okhttp3.RequestBody =
        object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType? = mediaType

            override fun writeTo(sink: okio.BufferedSink) {
                input.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }

    /** Bridge a browser WebSocket to the same loopback target as HTTP links. */
    suspend fun DefaultWebSocketServerSession.forwardWebSocket(repo: AppRepository) {
        val links = TunnelLinks.decode(repo.getSetting(TunnelLinks.SETTINGS_KEY))
        val route = TunnelLinks.match(
            links,
            call.request.headers[HttpHeaders.Host],
            call.request.uri,
            LinkTunnelRuntime.hosts.value,
        )
        if (route == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No tunnel link for this path"))
            return
        }
        if (TunnelLinks.isFtp(route.link)) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "FTP links use the HTTP file browser"))
            return
        }
        bridgeWebSocket(route)
    }

    internal suspend fun DefaultWebSocketServerSession.bridgeWebSocket(route: TunnelRoute) {
        val wsUrl = when {
            route.targetUrl.startsWith("https://") -> "wss://" + route.targetUrl.removePrefix("https://")
            route.targetUrl.startsWith("http://") -> "ws://" + route.targetUrl.removePrefix("http://")
            else -> route.targetUrl
        }
        val request = runCatching {
            Request.Builder().url(wsUrl).apply {
                call.request.headers.entries().forEach { (name, values) ->
                    if (name.lowercase() !in WS_SKIP) {
                        values.forEach { value -> addHeader(name, value) }
                    }
                }
            }.build()
        }.getOrNull()
        if (request == null) {
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Invalid local target"))
            return
        }

        val session = this
        val closed = AtomicBoolean(false)
        val outgoing = Channel<Frame>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = scope.launch {
            for (frame in outgoing) {
                runCatching { session.send(frame) }
            }
        }

        fun finish(code: Short, reason: String) {
            if (closed.compareAndSet(false, true)) {
                outgoing.trySend(Frame.Close(CloseReason(code, reason.take(120))))
                scope.launch { runCatching { session.close(CloseReason(code, reason.take(120))) } }
            }
        }

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed.get()) outgoing.trySend(Frame.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed.get()) outgoing.trySend(Frame.Binary(true, bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                finish(code.toShort(), reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(CloseReason.Codes.NORMAL.code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(CloseReason.Codes.INTERNAL_ERROR.code, t.message ?: "Upstream WebSocket failed")
            }
        }

        val socket = client.newWebSocket(request, listener)
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> socket.send(frame.readText())
                    is Frame.Binary -> socket.send(frame.data.toByteString())
                    is Frame.Ping -> socket.send(frame.data.toByteString())
                    is Frame.Pong -> Unit
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        socket.close(reason?.code?.toInt() ?: 1000, reason?.message)
                        break
                    }
                }
            }
        } catch (_: Throwable) {
            // Client disappeared or the bridge was cancelled.
        } finally {
            closed.set(true)
            outgoing.close()
            writer.cancel()
            socket.cancel()
            scope.cancel()
        }
    }
}
