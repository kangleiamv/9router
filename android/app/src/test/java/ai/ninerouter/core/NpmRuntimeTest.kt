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
}
