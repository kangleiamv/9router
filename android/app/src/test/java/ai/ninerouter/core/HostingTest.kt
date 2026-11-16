package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostingTest {
    private fun layout(): HostLayout {
        val root = Files.createTempDirectory("hosting-test").toFile()
        return HostLayout(
            root = root,
            content = File(root, "public"),
            data = File(root, "data"),
            home = File(root, "home"),
            temp = File(root, "tmp"),
            runtime = File(root, "runtime"),
        )
    }

    private fun binaries(vararg names: String): Map<String, File> =
        names.associateWith { File("/data/usr/bin/$it") }

    private fun service(
        kind: String,
        port: Int,
        entry: String = "",
        password: String = "",
    ) = HostedService(
        id = kind,
        kind = kind,
        name = kind.replaceFirstChar { it.uppercase() },
        port = port,
        entry = entry,
        password = password,
    )

    @Test
    fun `environment keeps custom values and refuses runtime keys`() {
        val accepted = HostingStore.require(
            listOf(
                service("node", 3000).copy(
                    env = mapOf("API_URL" to "https://example.com", "FEATURE_X" to "1"),
                    build = "npm run build",
                ),
            ),
        ).single()
        assertEquals("https://example.com", accepted.env["API_URL"])
        assertEquals("npm run build", accepted.build)

        val reserved = runCatching {
            HostingStore.require(listOf(service("node", 3000).copy(env = mapOf("PORT" to "1"))))
        }.exceptionOrNull()
        assertTrue(reserved is IllegalArgumentException)

        val invalid = runCatching {
            HostingStore.require(listOf(service("node", 3000).copy(env = mapOf("1BAD" to "x"))))
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
    }

    @Test
    fun `releases keep the active pointer only when it exists`() {
        val release = HostRelease("1000", 1L, 42L)
        val active = service("php", 8000).copy(releases = listOf(release), active = "1000")
        assertEquals("1000", HostingStore.require(listOf(active)).single().active)

        val stale = HostingStore.require(
            listOf(active.copy(active = "missing")),
        ).single()
        assertEquals("", stale.active)
    }

    @Test
    fun `planner applies environment variables to the process`() {
        val layout = layout()
        val plan = HostingPlanner.plan(
            service("node", 3001).copy(env = mapOf("API_URL" to "https://example.com")),
            layout,
            binaries("node"),
        )
        assertEquals("https://example.com", plan.env["API_URL"])
        assertEquals("3001", plan.env["PORT"])
        assertEquals("127.0.0.1", plan.env["HOST"])
    }

    @Test
    fun `kind catalog exposes the supported runtimes`() {
        val ids = HostedKinds.all.map { it.id }
        assertEquals(
            listOf("php", "node", "python", "redis", "postgres", "mysql"),
            ids,
        )
        assertTrue(HostedKinds.php.web)
        assertFalse(HostedKinds.redis.web)
        assertEquals(TunnelLinks.KIND_TCP, HostedKinds.mysql.linkKind)
        assertEquals("postgres", HostedKinds.postgres.defaultUser)
    }

    @Test
    fun `php runs the built-in server over the public folder`() {
        val layout = layout()
        val plan = HostingPlanner.plan(
            service("php", 8008),
            layout,
            binaries("php"),
            searchDirs = listOf(File("/data/usr/bin")),
        )
        assertEquals(File("/data/usr/bin/php"), plan.program)
        assertEquals(
            listOf("-n", "-S", "127.0.0.1:8008", "-t", layout.content.absolutePath),
            plan.args,
        )
        assertEquals(layout.content, plan.workDir)
        assertEquals("http://127.0.0.1:8008/", plan.webUrl)
        assertTrue(plan.scaffold.keys.single().name == "index.php")
        assertEquals(layout.content.absolutePath, plan.env["DOCUMENT_ROOT"])
        assertTrue(plan.env["PATH"]!!.isNotEmpty())
        assertEquals(File("/data/usr/bin").absolutePath, plan.env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `node receives PORT and a starter server`() {
        val layout = layout()
        val plan = HostingPlanner.plan(service("node", 3100), layout, binaries("node"))
        assertEquals(listOf("server.js"), plan.args)
        assertEquals("3100", plan.env["PORT"])
        assertEquals("127.0.0.1", plan.env["HOST"])
        assertEquals(layout.content.absolutePath, plan.env["DOCROOT"])
        assertEquals("server.js", plan.scaffold.keys.single().name)
    }

    @Test
    fun `python supports http server and a custom entrypoint`() {
        val layout = layout()
        val http = HostingPlanner.plan(service("python", 8100), layout, binaries("python3"))
        assertEquals(
            listOf(
                "-m", "http.server", "8100",
                "--bind", "127.0.0.1",
                "--directory", layout.content.absolutePath,
            ),
            http.args,
        )
        assertEquals("index.html", http.scaffold.keys.single().name)

        val app = HostingPlanner.plan(
            service("python", 8100, entry = "app.py"),
            layout,
            binaries("python"),
        )
        assertEquals(listOf("app.py"), app.args)
        assertTrue(app.scaffold.isEmpty())
    }

    @Test
    fun `redis binds loopback and requires a password`() {
        val layout = layout()
        val plan = HostingPlanner.plan(
            service("redis", 6380, password = "s3cret"),
            layout,
            binaries("redis-server"),
        )
        assertTrue(plan.args.containsAll(listOf("--bind", "127.0.0.1", "--port", "6380")))
        assertTrue(plan.args.containsAll(listOf("--appendonly", "yes")))
        val pass = plan.args.indexOf("--requirepass")
        assertEquals("s3cret", plan.args[pass + 1])
        assertTrue(plan.args.contains(layout.data.absolutePath))
        assertEquals(0, plan.setup.size)
    }

    @Test
    fun `postgres initializes once with scram auth and a password file`() {
        val layout = layout()
        val plan = HostingPlanner.plan(
            service("postgres", 5433, password = "pgpass1"),
            layout,
            binaries("pg", "initdb"),
        )
        assertEquals(
            listOf(
                "-D", layout.data.absolutePath,
                "-p", "5433",
                "-h", "127.0.0.1",
                "-k", File(layout.root, "run").absolutePath,
            ),
            plan.args,
        )
        val step = plan.setup.single()
        assertEquals(File("/data/usr/bin/initdb"), step.program)
        assertTrue(step.args.contains("--auth-host=scram-sha-256"))
        assertEquals(File(layout.data, "PG_VERSION"), step.skipIfExists)
        val pwfile = plan.files.keys.single()
        assertEquals("pgpass1\n", plan.files[pwfile])
        assertTrue(step.args.contains(pwfile.absolutePath))
        assertEquals(listOf(pwfile), plan.privateFiles)
    }

    @Test
    fun `mysql bootstraps once and seeds app credentials`() {
        val layout = layout()
        val plan = HostingPlanner.plan(
            service("mysql", 3307, password = "mysqlpw"),
            layout,
            binaries("mariadbd"),
        )
        assertEquals(File("/data/usr/bin/mariadbd"), plan.program)
        assertTrue(plan.args.contains("--bind-address=127.0.0.1"))
        assertTrue(plan.args.contains("--port=3307"))
        assertTrue(plan.args.contains("--basedir=${layout.runtime.absolutePath}"))
        assertTrue(
            plan.args.contains("--lc-messages-dir=${File(layout.runtime, "share/mariadb")}"),
        )
        assertTrue(plan.args.any { it.startsWith("--init-file=") })
        assertEquals(0, plan.setup.size)

        val init = plan.init!!
        assertEquals(File("/data/usr/bin/mariadbd"), init.program)
        assertTrue(init.args.contains("--bootstrap"))
        assertEquals(File(layout.data, "mariadb_upgrade_info"), init.skipIfExists)
        assertEquals(File(layout.data, "mariadb_upgrade_info"), init.marker)
        assertTrue(init.prelude.contains("SET @auth_root_socket=NULL;"))
        assertEquals(6, init.scripts.size)
        assertTrue(init.scripts.all { it.startsWith(File(layout.runtime, "share/mariadb")) })

        val sql = plan.files.values.single()
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS app;"))
        assertTrue(sql.contains("IDENTIFIED BY 'mysqlpw'"))
        assertTrue(sql.contains("ALTER USER '9router'@'%' IDENTIFIED BY 'mysqlpw';"))
    }

    @Test
    fun `missing binary explains where to install it`() {
        val error = runCatching {
            HostingPlanner.plan(service("php", 8000), layout(), emptyMap())
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("PHP is not installed"))
        assertTrue(error.message!!.contains("php"))
    }

    @Test
    fun `store generates database passwords and normalizes defaults`() {
        val saved = HostingStore.require(
            listOf(HostedService(id = "db", kind = "mysql", name = "  App DB ", port = 3306)),
        ).single()
        assertEquals("App DB", saved.name)
        assertEquals("9router", saved.username)
        assertEquals(34, saved.password.length)
        assertTrue(saved.password.isNotBlank())

        val redis = HostingStore.require(
            listOf(HostedService(id = "cache", kind = "redis", name = "Cache", port = 6379)),
        ).single()
        assertTrue(redis.password.isNotBlank())
        assertTrue(redis.username.isBlank())
    }

    @Test
    fun `store rejects duplicate, blocked and privileged ports`() {
        val base = listOf(
            HostedService(id = "a", kind = "php", name = "A", port = 8000),
            HostedService(id = "b", kind = "node", name = "B", port = 8000),
        )
        assertTrue(runCatching { HostingStore.require(base) }.isFailure)
        assertTrue(
            runCatching {
                HostingStore.require(base.take(1), blockedPorts = setOf(8000))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                HostingStore.require(
                    listOf(HostedService(id = "c", kind = "python", name = "C", port = 80)),
                )
            }.isFailure,
        )
    }

    @Test
    fun `store rejects unsafe entries and ids`() {
        fun fail(service: HostedService): Boolean =
            runCatching { HostingStore.require(listOf(service)) }.isFailure && true

        assertTrue(
            fail(
                HostedService(
                    id = "web",
                    kind = "node",
                    name = "Web",
                    port = 3000,
                    entry = "../escape.js",
                ),
            ),
        )
        assertTrue(
            fail(HostedService(id = "Bad Id", kind = "php", name = "Web", port = 3001)),
        )
        assertTrue(
            fail(HostedService(id = "ok", kind = "php", name = "", port = 3002)),
        )
    }

    @Test
    fun `settings survive storage with credentials intact`() {
        val services = HostingStore.require(
            listOf(
                HostedService(
                    id = "pg",
                    kind = "postgres",
                    name = "Postgres",
                    port = 5432,
                    password = "keepme",
                    autoStart = true,
                ),
            ),
        )
        val restored = HostingStore.decode(HostingStore.encode(services))
        assertEquals(1, restored.size)
        assertEquals("keepme", restored.single().password)
        assertEquals("postgres", restored.single().username)
        assertTrue(restored.single().autoStart)
        assertEquals("tcp://127.0.0.1:5432", restored.single().target)
    }

    @Test
    fun `new ids stay unique per runtime`() {
        val existing = listOf(
            HostedService(id = "php", kind = "php", name = "PHP", port = 8000),
            HostedService(id = "php-2", kind = "php", name = "PHP 2", port = 8001),
        )
        assertEquals("php-3", HostingStore.newId("php", existing))
        assertEquals("node", HostingStore.newId("node", existing))
    }
}
