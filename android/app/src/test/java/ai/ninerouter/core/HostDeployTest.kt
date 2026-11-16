package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HostDeployTest {
    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                if (bytes != null) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun dir(): File = Files.createTempDirectory("host-deploy").toFile()

    @Test
    fun `extracts files and flattens a wrapper folder`() {
        val target = File(dir(), "release")
        HostDeploy.extract(
            zip(
                "my-app/" to null,
                "my-app/index.php" to "<?php echo 1;".toByteArray(),
                "my-app/assets/app.css" to "body{}".toByteArray(),
            ).inputStream(),
            target,
        )
        HostDeploy.flatten(target)
        assertTrue(File(target, "index.php").isFile)
        assertTrue(File(target, "assets/app.css").isFile)
        assertFalse(File(target, "my-app").exists())
    }

    @Test
    fun `rejects archive paths that leave the folder`() {
        val target = File(dir(), "release")
        val failure = runCatching {
            HostDeploy.extract(
                zip("../escaped.txt" to "no".toByteArray()).inputStream(),
                target,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(target.exists())
    }

    @Test
    fun `rejects nested traversal segments`() {
        val failure = runCatching {
            HostDeploy.extract(
                zip("assets/../../escape.txt" to "no".toByteArray()).inputStream(),
                File(dir(), "release"),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `imports a folder above the old 5000 item limit`() {
        val target = File(dir(), "release")
        val children = ArrayList<HostFolderEntry>(5_100)
        val contents = HashMap<String, ByteArray>(5_100)
        repeat(5_100) { index ->
            val id = "file-$index"
            children += HostFolderEntry(id, "$id.txt", false)
            contents[id] = "x".toByteArray()
        }
        val tree = FakeTree(mapOf("root" to children), contents)
        HostDeploy.importTree(tree, "root", target)
        assertEquals(5_100, target.listFiles()?.size)
    }

    @Test
    fun `skips git metadata in archives and folders`() {
        val archive = File(dir(), "release")
        HostDeploy.extract(
            zip(
                ".git/config" to "[core]".toByteArray(),
                "assets/.git/objects/ab/cdef" to "x".toByteArray(),
                "index.php" to "<?php echo 1;".toByteArray(),
            ).inputStream(),
            archive,
        )
        assertFalse(File(archive, ".git").exists())
        assertFalse(File(archive, "assets/.git").exists())
        assertTrue(File(archive, "index.php").isFile)

        val folder = File(dir(), "release")
        val tree = FakeTree(
            nodes = mapOf(
                "root" to listOf(
                    HostFolderEntry("git", ".git", true),
                    HostFolderEntry("index", "index.php", false),
                ),
                "git" to listOf(HostFolderEntry("config", "config", false)),
            ),
            contents = mapOf(
                "config" to "[core]".toByteArray(),
                "index" to "<?php echo 1;".toByteArray(),
            ),
        )
        HostDeploy.importTree(tree, "root", folder)
        assertFalse(File(folder, ".git").exists())
        assertTrue(File(folder, "index.php").isFile)
    }

    private class FakeTree(
        private val nodes: Map<String, List<HostFolderEntry>>,
        private val contents: Map<String, ByteArray>,
    ) : HostFolderSource {
        override fun children(parent: String): List<HostFolderEntry> =
            nodes[parent].orEmpty()

        override fun open(document: String): InputStream? =
            contents[document]?.inputStream()
    }

    @Test
    fun `copies a selected folder without flattening its layout`() {
        val target = File(dir(), "release")
        val tree = FakeTree(
            nodes = mapOf(
                "root" to listOf(
                    HostFolderEntry("assets", "assets", true),
                    HostFolderEntry("index", "index.php", false),
                ),
                "assets" to listOf(HostFolderEntry("css", "app.css", false)),
            ),
            contents = mapOf(
                "index" to "<?php echo 1;".toByteArray(),
                "css" to "body{}".toByteArray(),
            ),
        )
        HostDeploy.importTree(tree, "root", target)
        assertTrue(File(target, "index.php").isFile)
        assertTrue(File(target, "assets/app.css").isFile)
    }

    @Test
    fun `rejects unsafe folder names and clears the release`() {
        val target = File(dir(), "release")
        val tree = FakeTree(
            nodes = mapOf("root" to listOf(HostFolderEntry("x", "..", false))),
            contents = mapOf("x" to "no".toByteArray()),
        )
        val failure = runCatching { HostDeploy.importTree(tree, "root", target) }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(target.exists())
    }

    @Test
    fun `rejects a selected folder with no files`() {
        val target = File(dir(), "release")
        val tree = FakeTree(
            nodes = mapOf("root" to listOf(HostFolderEntry("empty", "empty", true))),
            contents = emptyMap(),
        )
        val failure = runCatching { HostDeploy.importTree(tree, "root", target) }
            .exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(target.exists())
    }

    @Test
    fun `recognises the old generated node starter`() {
        val root = dir()
        val starter = File(root, "server.js")
        starter.writeText(
            "// Created by 9Router. Replace this file with your own app.\n" +
                "const target = path.join(root, path.normalize(url));\n",
        )
        assertTrue(HostDeploy.isLegacyNodeStarter(starter))

        starter.writeText("// My own app\nconsole.log('hi')\n")
        assertFalse(HostDeploy.isLegacyNodeStarter(starter))
    }

    @Test
    fun `detects a public document root for php`() {
        val root = dir()
        File(root, "public").mkdirs()
        File(root, "public/index.php").writeText("<?php")
        assertEquals(HostAppLayout("public", ""), HostDeploy.detect(root, "php"))
    }

    @Test
    fun `detects the node entry from files or package json`() {
        val root = dir()
        File(root, "index.js").writeText("1")
        assertEquals("index.js", HostDeploy.detect(root, "node").entry)

        val pkg = dir()
        File(pkg, "package.json").writeText("""{"main":"src/boot.js"}""")
        assertEquals("src/boot.js", HostDeploy.detect(pkg, "node").entry)

        val esm = dir()
        File(esm, "index.mjs").writeText("1")
        assertEquals("index.mjs", HostDeploy.detect(esm, "node").entry)

        val empty = dir()
        assertEquals("server.js", HostDeploy.detect(empty, "node").entry)
    }

    @Test
    fun `keeps a valid entry and replaces a stale one`() {
        val root = dir()
        File(root, "index.js").writeText("1")
        assertEquals("index.js", HostDeploy.entryFor(root, "node", ""))
        assertEquals("index.js", HostDeploy.entryFor(root, "node", "missing.js"))

        File(root, "server.js").writeText("1")
        assertEquals("server.js", HostDeploy.entryFor(root, "node", "server.js"))
    }

    @Test
    fun `detects app py for python`() {
        val root = dir()
        assertEquals("", HostDeploy.detect(root, "python").entry)
        File(root, "app.py").writeText("print(1)")
        assertEquals("app.py", HostDeploy.detect(root, "python").entry)
    }
}
