package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class NpmRuntimeTest {
    private fun dir(): File = Files.createTempDirectory("npm-test").toFile()

    private fun write(dir: File, name: String, text: String): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    @Test
    fun `package manifests expose scripts and dependencies`() {
        val dir = dir()
        write(
            dir,
            "package.json",
            """
            {
              "name": "demo",
              "scripts": { "start": "node server.js", "build": "node build.js" },
              "dependencies": { "express": "^5.0.0" },
              "devDependencies": { "vite": "^7.0.0" }
            }
            """.trimIndent(),
        )
        val manifest = NpmRuntime.manifest(dir)
        assertEquals("demo", manifest?.name)
        assertTrue(manifest?.usesNpmStart == true)
        assertTrue(manifest?.usesNpmBuild == true)
        assertEquals(2, manifest?.dependencies)
        assertTrue(manifest?.needsInstall == true)
        assertTrue(NpmRuntime.hasScript(dir, "build"))
        assertFalse(NpmRuntime.hasScript(dir, "test"))
    }

    @Test
    fun `missing or malformed manifests are ignored`() {
        val dir = dir()
        assertNull(NpmRuntime.manifest(dir))
        write(dir, "package.json", "{not json")
        assertNull(NpmRuntime.manifest(dir))
        write(dir, "package.json", "{\"scripts\":{\"start\":\"node app.js\"}}")
        assertEquals(0, NpmRuntime.manifest(dir)?.dependencies)
    }

    @Test
    fun `next 16 builds use webpack for the android wasm fallback`() {
        val dir = dir()
        write(
            dir,
            "package.json",
            """
            {
              "scripts": { "build": "next build" },
              "dependencies": { "next": "^16.2.10" }
            }
            """.trimIndent(),
        )
        assertEquals("npm run build -- --webpack", NpmRuntime.buildCommand(dir))

        write(dir, "node_modules/next/package.json", "{\"version\":\"16.2.10\"}")
        assertEquals(16, NpmRuntime.nextMajor(dir))
        assertEquals("16.2.10", NpmRuntime.installedVersion(dir, "next"))
    }

    @Test
    fun `build commands keep custom flags and older next versions`() {
        val dir = dir()
        write(
            dir,
            "package.json",
            """
            {
              "scripts": { "build": "next build --webpack" },
              "dependencies": { "next": "16.2.10" }
            }
            """.trimIndent(),
        )
        assertEquals("npm run build", NpmRuntime.buildCommand(dir))

        write(
            dir,
            "package.json",
            """
            {
              "scripts": { "build": "next build && next-sitemap" },
              "dependencies": { "next": "16.2.10" }
            }
            """.trimIndent(),
        )
        assertEquals("npm run build", NpmRuntime.buildCommand(dir))

        write(
            dir,
            "package.json",
            """
            {
              "scripts": { "build": "tsc" },
              "dependencies": { "next": "16.2.10" }
            }
            """.trimIndent(),
        )
        assertEquals("npm run build", NpmRuntime.buildCommand(dir))

        write(
            dir,
            "package.json",
            """
            {
              "scripts": { "build": "next build" },
              "dependencies": { "next": "^15.5.0" }
            }
            """.trimIndent(),
        )
        assertEquals("npm run build", NpmRuntime.buildCommand(dir))
    }

    @Test
    fun `installed wasm swc is linked into next's fallback folder`() {
        val dir = dir()
        write(dir, "node_modules/@next/swc-wasm-nodejs/wasm.js", "wasm")
        write(dir, "node_modules/next/package.json", "{\"version\":\"16.2.10\"}")

        NpmRuntime.seedNextWasm(dir)

        val seeded = File(dir, "node_modules/next/wasm/@next/swc-wasm-nodejs/wasm.js")
        assertTrue(seeded.isFile)
        assertEquals("wasm", seeded.readText())
        // A second call is harmless.
        NpmRuntime.seedNextWasm(dir)
        assertTrue(seeded.isFile)
    }

    @Test
    fun `npm ci is used when a lockfile exists`() {
        val dir = dir()
        assertEquals("install", NpmRuntime.installArgs(dir).first())
        write(dir, "package-lock.json", "{}")
        assertEquals("ci", NpmRuntime.installArgs(dir).first())
        assertTrue(NpmRuntime.installArgs(dir).contains("--no-audit"))
        assertTrue(NpmRuntime.installArgs(dir).contains("--foreground-scripts"))
    }

    @Test
    fun `shell npm commands use the bundled cli`() {
        val root = dir()
        val node = File(root, "node")
        val npm = File(root, "npm/bin/npm-cli.js")
        write(npm.parentFile!!, "npm-cli.js", "")
        write(npm.parentFile!!, "npx-cli.js", "")
        val command = NpmRuntime.rewriteShell(
            "npm install && npm run build; npx vite build",
            node,
            npm,
        )
        val quotedNode = "'${node.absolutePath}'"
        val quotedNpm = "'${npm.absolutePath}'"
        val quotedNpx = "'${File(npm.parentFile!!, "npx-cli.js").absolutePath}'"
        assertTrue(command.startsWith("$quotedNode $quotedNpm install"))
        assertTrue(command.contains("&& $quotedNode $quotedNpm run build"))
        assertTrue(command.contains("; $quotedNode $quotedNpx vite build"))
        // Arguments that merely contain the word are untouched.
        assertEquals("echo npm", NpmRuntime.rewriteShell("echo npm", node, npm))
        assertEquals("echo node", NpmRuntime.rewriteShell("echo node", node, npm))
        assertEquals("echo $quotedNode", NpmRuntime.rewriteShell("echo $quotedNode", node, npm))
    }

    @Test
    fun `node and env-prefixed commands get the bundled binary`() {
        val root = dir()
        val node = File(root, "node")
        val npm = File(root, "npm/bin/npm-cli.js")
        write(npm.parentFile!!, "npm-cli.js", "")
        write(npm.parentFile!!, "npx-cli.js", "")
        val quotedNode = "'${node.absolutePath}'"
        val quotedNpm = "'${npm.absolutePath}'"
        val quotedNpx = "'${File(npm.parentFile!!, "npx-cli.js").absolutePath}'"
        val command = NpmRuntime.rewriteShell(
            "NODE_ENV=production node build.js && CI=1 npm run build | npx vite",
            node,
            npm,
        )
        assertTrue(command.startsWith("NODE_ENV=production $quotedNode build.js"))
        assertTrue(command.contains("&& CI=1 $quotedNode $quotedNpm run build"))
        assertTrue(command.contains("| $quotedNode $quotedNpx vite"))
        assertEquals("$quotedNode app.js", NpmRuntime.rewriteShell("node app.js", node, null))
    }

    @Test
    fun `bundled npm asset is complete and has safe paths`() {
        val zip = bundledAsset("npm-11.19.1.zip")
        assertNotNull(zip)
        var cli = false
        var npx = false
        var entries = 0
        ZipFile(zip!!).use { archive ->
            for (entry in archive.entries()) {
                entries++
                assertFalse(entry.name.startsWith("/"))
                assertFalse(entry.name.split('/').contains(".."))
                when (entry.name) {
                    "bin/npm-cli.js" -> cli = true
                    "bin/npx-cli.js" -> npx = true
                }
            }
        }
        assertTrue(cli)
        assertTrue(npx)
        assertTrue(entries > 1_500)
        val ca = bundledAsset("cacert.pem")
        assertNotNull(ca)
        assertTrue(ca!!.length() > 100_000)
    }

    private fun bundledAsset(name: String): File? {
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
            it.parentFile
        }
        return roots.take(6)
            .flatMap { root ->
                sequenceOf(
                    File(root, "app/src/main/assets/npm/$name"),
                    File(root, "src/main/assets/npm/$name"),
                )
            }
            .firstOrNull { it.isFile }
    }

    @Test
    fun `npm shims exec the bundled node and CLI`() {
        val node = File("/data/app/ai.ninerouter/lib/arm64/libnode.so")
        val cli = File("/data/user/0/ai.ninerouter/files/npm/11.19.1/bin/npm-cli.js")
        assertEquals(
            "#!/system/bin/sh\nexec '" + node.absolutePath + "' '" + cli.absolutePath + "' \"${'$'}@\"\n",
            NpmRuntime.shimText(node, cli),
        )
        val odd = File("/tmp/it's a node")
        assertTrue(
            NpmRuntime.shimText(odd, cli)
                .startsWith("#!/system/bin/sh\nexec '/tmp/it'\\''s a node' "),
        )
    }

    @Test
    fun `node preload resolves bin shims instead of execing them`() {
        val agent = HostingPlanner.nodeAgentScript()
        assertTrue(agent.contains("node_modules"))
        assertTrue(agent.contains(".bin"))
        assertTrue(agent.contains("fs.realpathSync"))
        assertTrue(agent.contains("readHeader"))
        assertTrue(agent.contains("child.spawn = function"))
        assertTrue(agent.contains("child.execSync = function"))
        assertTrue(agent.contains("/system/bin/sh"))
        // Global prefixes are on PATH too, not only node_modules/.bin.
        assertTrue(agent.contains("if (entry && !dirs.includes(entry)) dirs.push(entry)"))
        val runner = HostingPlanner.shellRunnerScript()
        assertTrue(runner.contains("spawn('/system/bin/sh', ['-c', command]"))
    }

    @Test
    fun `network environment points npm at the bundled roots and cache`() {
        val root = dir()
        val npm = File(root, "11.19.1/bin/npm-cli.js").apply { parentFile!!.mkdirs() }
        write(npm.parentFile!!.parentFile!!, "cacert.pem", "roots")
        val node = File(root, "node")
        val env = linkedMapOf<String, String>()
        NpmRuntime.applyEnvironment(env, npm, node)
        assertEquals(File(npm.parentFile!!.parentFile!!, "cacert.pem").absolutePath, env["NODE_EXTRA_CA_CERTS"])
        assertEquals(npm.absolutePath, env["NPM_CLI"])
        assertEquals(node.absolutePath, env["NODE_BIN"])
        assertEquals("false", env["npm_config_update_notifier"])
        assertTrue(env["npm_config_cache"]!!.endsWith("/cache"))
    }

    @Test
    fun `global installs use an app-owned prefix and resolvable modules`() {
        val root = dir()
        val npm = File(root, "11.19.1/bin/npm-cli.js").apply { parentFile!!.mkdirs() }
        write(npm.parentFile!!.parentFile!!, "cacert.pem", "roots")
        val env = linkedMapOf("NODE_PATH" to "/existing/modules")
        NpmRuntime.applyEnvironment(env, npm, File(root, "node"))
        val global = File(root, "global")
        assertEquals(global.absolutePath, env["npm_config_prefix"])
        assertTrue(File(global, "bin").isDirectory)
        assertEquals(
            File(global, "lib/node_modules").absolutePath + File.pathSeparator +
                "/existing/modules",
            env["NODE_PATH"],
        )
    }
}
