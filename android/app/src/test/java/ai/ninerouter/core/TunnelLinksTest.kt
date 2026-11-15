package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLinksTest {
    private fun draft(
        name: String = "Site",
        host: String = "",
        path: String = "",
        target: String = "http://127.0.0.1:8080",
        stripPrefix: Boolean = true,
        enabled: Boolean = true,
        mode: String = TunnelLinks.MODE_NAMED,
        kind: String = TunnelLinks.KIND_HTTP,
        username: String = "",
        password: String = "",
    ) = TunnelLinkDraft(name, host, path, target, stripPrefix, enabled, mode, kind, username, password)

    private fun link(
        id: String = "a",
        name: String = "Site",
        host: String = "",
        path: String = "",
        target: String = "http://127.0.0.1:8080",
        stripPrefix: Boolean = true,
        enabled: Boolean = true,
        mode: String = TunnelLinks.MODE_NAMED,
        kind: String = TunnelLinks.KIND_HTTP,
        key: String = "",
        username: String = "",
        password: String = "",
    ) = TunnelLink(id, name, host, path, target, stripPrefix, enabled, mode, kind, key, username, password)

    @Test
    fun `normalizes loopback targets`() {
        assertEquals("http://127.0.0.1:8080", TunnelLinks.normalizeTarget("8080"))
        assertEquals("http://localhost:3000", TunnelLinks.normalizeTarget("localhost:3000"))
        assertEquals("http://127.0.0.1:8080/base", TunnelLinks.normalizeTarget("http://127.0.0.1:8080/base/"))
        assertEquals("https://[::1]:8443", TunnelLinks.normalizeTarget("https://[::1]:8443/"))
    }

    @Test
    fun `rejects non-loopback and credentials`() {
        assertNull(TunnelLinks.normalizeTarget("http://192.168.1.10:8080"))
        assertNull(TunnelLinks.normalizeTarget("https://example.com"))
        assertNull(TunnelLinks.normalizeTarget("http://user:pass@127.0.0.1:8080"))
        assertNull(TunnelLinks.normalizeTarget("http://127.0.0.1:8080?x=1"))
        assertNull(TunnelLinks.normalizeTarget("ftp://127.0.0.1:21"))
    }

    @Test
    fun `rejects targets on the gateway ports`() {
        val error = runCatching {
            TunnelLinks.fromDraft(
                draft(path = "/site", target = "http://127.0.0.1:20127"),
                blockedPorts = setOf(20127, 20128),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("belongs to the 9Router gateway"))
    }

    @Test
    fun `normalizes loopback ftp targets and rejects traversal`() {
        assertEquals("ftp://127.0.0.1:2121", TunnelLinks.normalizeFtpTarget("2121"))
        assertEquals("ftp://localhost:2121/files", TunnelLinks.normalizeFtpTarget("ftp://localhost:2121/files/"))
        assertEquals("ftp://127.0.0.1", TunnelLinks.normalizeFtpTarget("ftp://127.0.0.1:21"))
        assertNull(TunnelLinks.normalizeFtpTarget("ftps://127.0.0.1:2121"))
        assertNull(TunnelLinks.normalizeFtpTarget("ftp://192.168.1.10:2121"))
        assertNull(TunnelLinks.normalizeFtpTarget("ftp://127.0.0.1:2121/../etc"))
        assertNull(TunnelLinks.normalizeFtpTarget("ftp://user:pass@127.0.0.1:2121"))
    }

    @Test
    fun `resolves browser paths under the ftp base`() {
        assertEquals("/files/a.txt", TunnelLinks.absoluteFtpPath("/files", "/a.txt"))
        assertEquals("/files", TunnelLinks.absoluteFtpPath("/files", "/"))
        assertEquals("/a", TunnelLinks.absoluteFtpPath("", "/a"))
        assertNull(TunnelLinks.absoluteFtpPath("/files", "/../secret"))
        assertNull(TunnelLinks.cleanRelativePath("/a/../b"))
        assertNull(TunnelLinks.cleanRelativePath("/a\\b"))
    }

    @Test
    fun `infers ftp kind from the target`() {
        assertEquals(TunnelLinks.KIND_FTP, TunnelLinks.normalizeKind("", "ftp://127.0.0.1:2121"))
        assertEquals(TunnelLinks.KIND_HTTP, TunnelLinks.normalizeKind("", "http://127.0.0.1:8080"))
        assertEquals(
            TunnelLinks.KIND_FTP,
            TunnelLinks.kindOf(link(target = "ftp://127.0.0.1:2121", kind = "")),
        )
    }

    @Test
    fun `quick ftp links get a key and no http target`() {
        val saved = TunnelLinks.fromDraft(
            draft(
                name = "",
                target = "ftp://127.0.0.1:2121",
                mode = TunnelLinks.MODE_QUICK,
                kind = TunnelLinks.KIND_FTP,
                username = "ftpuser",
                password = "secret",
            ),
        )
        assertEquals("FTP server", saved.name)
        assertEquals(TunnelLinks.KIND_FTP, saved.kind)
        assertEquals("ftpuser", saved.username)
        assertEquals("secret", saved.password)
        assertTrue(saved.key.isNotBlank())
        assertEquals("", saved.host)
        assertEquals("", saved.path)

        val route = TunnelLinks.match(
            listOf(saved),
            "random.trycloudflare.com",
            "/folder/file.bin",
            mapOf("random.trycloudflare.com" to saved.id),
        )
        assertEquals(saved.id, route!!.link.id)
        assertEquals("ftp://127.0.0.1:2121", route.targetUrl)
    }

    @Test
    fun `edit keeps the ftp access key`() {
        val first = TunnelLinks.fromDraft(
            draft(target = "2121", mode = TunnelLinks.MODE_QUICK, kind = TunnelLinks.KIND_FTP),
        )
        val second = TunnelLinks.fromDraft(
            draft(
                name = "Changed",
                target = "ftp://127.0.0.1:2121",
                mode = TunnelLinks.MODE_QUICK,
                kind = TunnelLinks.KIND_FTP,
            ),
            existingId = first.id,
            existingKey = first.key,
        )
        assertEquals(first.key, second.key)
    }

    @Test
    fun `named ftp links expose browser and native tcp details`() {
        val ftp = link(
            host = "ftp.example.com",
            target = "ftp://127.0.0.1:2121",
            kind = TunnelLinks.KIND_FTP,
            key = "abc123",
        )
        assertEquals("tcp://127.0.0.1:2121", TunnelLinks.nativeFtpTarget(ftp))
        assertEquals(
            "cloudflared access tcp --hostname ftp.example.com --url 127.0.0.1:2121",
            TunnelLinks.nativeFtpCommand("ftp.example.com", ftp),
        )
        assertEquals(
            "https://ftp.example.com/?k=abc123",
            TunnelLinks.browserUrl("https://ftp.example.com", ftp),
        )
        assertEquals(
            "https://abc.trycloudflare.com/?k=abc123",
            TunnelLinks.browserUrl("https://abc.trycloudflare.com", ftp.copy(mode = TunnelLinks.MODE_QUICK)),
        )
        assertEquals(
            "https://ftp.example.com/",
            TunnelLinks.browserUrl("https://ftp.example.com", ftp.copy(kind = TunnelLinks.KIND_HTTP)),
        )
    }

    @Test
    fun `ftp credentials survive storage without exposing the password`() {
        val ftp = link(
            target = "ftp://127.0.0.1:2121",
            kind = TunnelLinks.KIND_FTP,
            key = "k1",
            username = "user",
            password = "hunter2",
        )
        val stored = TunnelLinks.encode(listOf(ftp))
        val restored = TunnelLinks.decode(stored).single()
        assertEquals("user", restored.username)
        assertEquals("hunter2", restored.password)
        assertEquals("k1", restored.key)
        assertEquals(TunnelLinks.KIND_FTP, restored.kind)
    }

    @Test
    fun `requires a public path or hostname`() {
        val error = runCatching { TunnelLinks.fromDraft(draft()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `rejects reserved paths and traversal`() {
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/v1")) }.isFailure)
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/v1/models")) }.isFailure)
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/api/health")) }.isFailure)
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/a/../b")) }.isFailure)
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/a//b")) }.isFailure)
        assertTrue(runCatching { TunnelLinks.fromDraft(draft(path = "/")) }.isFailure)
    }

    @Test
    fun `normalizes paths and hostnames`() {
        val saved = TunnelLinks.fromDraft(
            draft(name = "  Blog  ", host = "https://Blog.Example.com:443/path", path = "blog/"),
        )
        assertEquals("Blog", saved.name)
        assertEquals("blog.example.com", saved.host)
        assertEquals("/blog", saved.path)
        assertEquals("https://blog.example.com/blog/", TunnelLinks.publicUrl("", "token", saved))
    }

    @Test
    fun `matches a path prefix on a boundary and prefers the longest`() {
        val short = link(id = "short", path = "/app", target = "http://127.0.0.1:3000")
        val long = link(id = "long", path = "/app/admin", target = "http://127.0.0.1:4000")
        val route = TunnelLinks.match(listOf(short, long), "abc.trycloudflare.com", "/app/admin/users")
        assertEquals("long", route!!.link.id)
        assertEquals("http://127.0.0.1:4000/users", route.targetUrl)

        assertNull(TunnelLinks.match(listOf(short), "abc.trycloudflare.com", "/application"))
    }

    @Test
    fun `matches a host link ignoring the port and case`() {
        val host = link(host = "blog.example.com", target = "http://127.0.0.1:8080")
        val route = TunnelLinks.match(listOf(host), "BLOG.example.com:443", "/posts/1?draft=1")
        assertEquals("http://127.0.0.1:8080/posts/1?draft=1", route!!.targetUrl)
    }

    @Test
    fun `host and path links require both to match`() {
        val both = link(host = "blog.example.com", path = "/blog")
        assertNull(TunnelLinks.match(listOf(both), "blog.example.com", "/other"))
        assertNull(TunnelLinks.match(listOf(both), "shop.example.com", "/blog"))
        assertEquals(
            "http://127.0.0.1:8080/",
            TunnelLinks.match(listOf(both), "blog.example.com", "/blog")!!.targetUrl,
        )
    }

    @Test
    fun `path links never shadow reserved gateway routes`() {
        val site = link(path = "/site")
        assertNull(TunnelLinks.match(listOf(site), "abc.trycloudflare.com", "/v1/models"))
        assertNull(TunnelLinks.match(listOf(site), "abc.trycloudflare.com", "/api/health"))
    }

    @Test
    fun `strips the public prefix by default and keeps the target base path`() {
        val site = link(path = "/site", target = "http://127.0.0.1:8080/app")
        val route = TunnelLinks.match(listOf(site), "abc.trycloudflare.com", "/site/css/app.css?v=2")
        assertEquals("http://127.0.0.1:8080/app/css/app.css?v=2", route!!.targetUrl)
    }

    @Test
    fun `can keep the public prefix for apps that expect it`() {
        val site = link(path = "/site", stripPrefix = false)
        val route = TunnelLinks.match(listOf(site), "abc.trycloudflare.com", "/site/page")
        assertEquals("http://127.0.0.1:8080/site/page", route!!.targetUrl)
    }

    @Test
    fun `disabled links are skipped`() {
        val site = link(path = "/site", enabled = false)
        assertNull(TunnelLinks.match(listOf(site), "abc.trycloudflare.com", "/site"))
    }

    @Test
    fun `builds public URLs for quick and named modes`() {
        val pathOnly = link(path = "/site")
        assertEquals(
            "https://abc.trycloudflare.com/site/",
            TunnelLinks.publicUrl("https://abc.trycloudflare.com/", "quick", pathOnly),
        )
        assertEquals("", TunnelLinks.publicUrl("", "quick", pathOnly))
        assertEquals(
            "https://blog.example.com",
            TunnelLinks.publicUrl("https://abc.trycloudflare.com", "token", link(host = "blog.example.com")),
        )
        assertEquals(
            "https://abc.trycloudflare.com/site/",
            TunnelLinks.publicUrl("https://abc.trycloudflare.com", "token", pathOnly),
        )
    }

    @Test
    fun `round trips storage and ignores malformed json`() {
        val links = listOf(link(id = "a", path = "/site"), link(id = "b", host = "blog.example.com"))
        assertEquals(links, TunnelLinks.decode(TunnelLinks.encode(links)))
        assertTrue(TunnelLinks.decode("{not json").isEmpty())
        assertTrue(TunnelLinks.decode(null).isEmpty())
    }

    @Test
    fun `summarizes hostname routing for the dashboard`() {
        val links = listOf(
            link(id = "a", host = "blog.example.com", target = "http://127.0.0.1:3000"),
            link(id = "b", host = "shop.example.com", path = "/shop"),
            link(id = "c", path = "/other"),
        )
        assertEquals(
            "blog.example.com  ->  http://127.0.0.1:20128\n" +
                "shop.example.com  ->  http://127.0.0.1:20128/shop",
            TunnelLinks.ingressSummary(links, 20128),
        )
    }

    @Test
    fun `rewrites redirects from the local service to the public mount`() {
        val site = link(path = "/site", target = "http://127.0.0.1:8080")
        assertEquals(
            "/site/login?next=%2Fsite%2F",
            TunnelLinks.publicLocation("/login?next=%2Fsite%2F", site, "abc.trycloudflare.com"),
        )
        assertEquals(
            "/site/login",
            TunnelLinks.publicLocation("/site/login", site, "abc.trycloudflare.com"),
        )
        assertEquals(
            "https://abc.trycloudflare.com/site/home?x=1",
            TunnelLinks.publicLocation("http://127.0.0.1:8080/home?x=1", site, "abc.trycloudflare.com"),
        )
        assertEquals(
            "https://example.com/elsewhere",
            TunnelLinks.publicLocation("https://example.com/elsewhere", site, "abc.trycloudflare.com"),
        )
        assertEquals(
            "/login",
            TunnelLinks.publicLocation("/login", site.copy(stripPrefix = false), "abc.trycloudflare.com"),
        )
    }

    @Test
    fun `hostname links keep redirect paths at the root`() {
        val host = link(host = "blog.example.com", target = "http://127.0.0.1:8080")
        assertEquals("/login", TunnelLinks.publicLocation("/login", host, "blog.example.com"))
        assertEquals(
            "https://blog.example.com/posts?x=1",
            TunnelLinks.publicLocation("https://localhost:8080/posts?x=1", host, "blog.example.com"),
        )
    }

    @Test
    fun `quick links need only a target`() {
        val saved = TunnelLinks.fromDraft(
            TunnelLinkDraft(name = "", target = "8080", mode = TunnelLinks.MODE_QUICK),
        )
        assertEquals(TunnelLinks.MODE_QUICK, saved.mode)
        assertEquals("Local link", saved.name)
        assertEquals("http://127.0.0.1:8080", saved.target)
    }

    @Test
    fun `quick links discard host and path values`() {
        val saved = TunnelLinks.fromDraft(
            TunnelLinkDraft(
                host = "not a valid host",
                path = "/v1",
                target = "8080",
                mode = TunnelLinks.MODE_QUICK,
            ),
        )
        assertEquals(TunnelLinks.MODE_QUICK, saved.mode)
        assertEquals("", saved.host)
        assertEquals("", saved.path)
    }

    @Test
    fun `named links require a hostname or path`() {
        val error = runCatching {
            TunnelLinks.fromDraft(
                TunnelLinkDraft(name = "Site", target = "8080", mode = TunnelLinks.MODE_NAMED),
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Named links need"))
    }

    @Test
    fun `legacy links infer a mode from the stored hostname`() {
        val legacyNamed = TunnelLinks.decode(
            """[{"id":"a","name":"Blog","host":"blog.example.com","target":"http://127.0.0.1:3000"}]""",
        )
        assertEquals(TunnelLinks.MODE_NAMED, legacyNamed.single().mode)
        val legacyPath = TunnelLinks.decode(
            """[{"id":"b","name":"Site","path":"/site","target":"http://127.0.0.1:8080"}]""",
        )
        assertEquals(TunnelLinks.MODE_QUICK, legacyPath.single().mode)
    }

    @Test
    fun `a quick hostname owns its link at the root`() {
        val quick = link(
            id = "q",
            mode = TunnelLinks.MODE_QUICK,
            path = "/ignored",
            target = "http://127.0.0.1:3000/base",
        )
        val named = link(id = "n", path = "/site")
        val hosts = mapOf("random.trycloudflare.com" to "q")

        val route = TunnelLinks.match(
            listOf(quick, named),
            "random.trycloudflare.com",
            "/css/app.css?x=1",
            hosts,
        )
        assertEquals("q", route!!.link.id)
        assertEquals("http://127.0.0.1:3000/base/css/app.css?x=1", route.targetUrl)

        // A dedicated quick host serves the link everywhere, including paths
        // that are reserved on the shared gateway hostname.
        val reserved = TunnelLinks.match(
            listOf(quick, named),
            "random.trycloudflare.com",
            "/v1/models",
            hosts,
        )
        assertEquals("q", reserved!!.link.id)
    }

    @Test
    fun `quick links never match a path on the shared tunnel`() {
        val quick = link(mode = TunnelLinks.MODE_QUICK, path = "/site")
        assertNull(TunnelLinks.match(listOf(quick), "abc.trycloudflare.com", "/site/page"))
    }

    @Test
    fun `quick links have no shared public URL`() {
        val quick = link(mode = TunnelLinks.MODE_QUICK, path = "/site")
        assertEquals("", TunnelLinks.publicUrl("https://abc.trycloudflare.com", "quick", quick))
        assertEquals("", TunnelLinks.ingressSummary(listOf(quick), 20128))
    }

    @Test
    fun `does not treat a path prefix as a substring match`() {
        assertTrue(TunnelLinks.pathMatches("/blog", "/blog"))
        assertTrue(TunnelLinks.pathMatches("/blog", "/blog/post"))
        assertFalse(TunnelLinks.pathMatches("/blog", "/blogger"))
        assertFalse(TunnelLinks.pathMatches("/blog", "/Blog"))
    }
}
