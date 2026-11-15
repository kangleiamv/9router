package ai.ninerouter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostStorageTest {
    private fun root(): File = Files.createTempDirectory("host-storage").toFile()

    @Test
    fun `paths stay inside the service folder`() {
        val root = root()
        assertEquals(root.canonicalFile, HostStorage.resolve(root, ""))
        assertEquals(root.canonicalFile, HostStorage.resolve(root, "/"))
        assertEquals(
            File(root, "assets/app.css").canonicalFile,
            HostStorage.resolve(root, "/assets/app.css"),
        )
        assertNull(HostStorage.resolve(root, "../secret"))
        assertNull(HostStorage.resolve(root, "assets/../../secret"))
        assertNull(HostStorage.resolve(root, "a\\b"))
        assertNull(HostStorage.resolve(root, "assets//app.css"))
    }

    @Test
    fun `entries list folders first and ignore no files`() {
        val root = root()
        File(root, "z.txt").writeText("zz")
        File(root, "assets").mkdirs()
        File(root, "assets/logo.png").writeBytes(ByteArray(10))
        File(root, ".env").writeText("A=1")

        val entries = HostStorage.entries(root)
        assertEquals(listOf("assets", ".env", "z.txt"), entries.map { it.name })
        assertTrue(entries.first { it.name == "assets" }.directory)
        assertEquals(2L, entries.first { it.name == "z.txt" }.size)
    }

    @Test
    fun `usage counts nested files`() {
        val root = root()
        File(root, "index.php").writeText("<?php echo 1;")
        File(root, "assets/deep").mkdirs()
        File(root, "assets/deep/app.js").writeText("12345")
        assertEquals(File(root, "index.php").length() + 5L, HostStorage.usage(root))
    }

    @Test
    fun `text detection covers code and empty extensions`() {
        assertTrue(HostStorage.isText("index.php"))
        assertTrue(HostStorage.isText("Dockerfile"))
        assertFalse(HostStorage.isText("app.tar.gz"))
        assertFalse(HostStorage.isText("photo.png"))
        assertFalse(HostStorage.isText("archive.zip"))
    }

    @Test
    fun `write replaces contents and keeps a sibling temp out of the way`() {
        val root = root()
        val file = File(root, "server.js")
        file.writeText("old")
        HostStorage.writeText(file, "console.log('new');")
        assertEquals("console.log('new');", file.readText())
        assertFalse(File(root, ".server.js.tmp").exists())
    }

    @Test
    fun `editable rejects binary and oversized files`() {
        val root = root()
        val text = File(root, "notes.md")
        text.writeText("# hi")
        assertTrue(HostStorage.isEditable(text))

        val image = File(root, "logo.png")
        image.writeBytes(ByteArray(32))
        assertFalse(HostStorage.isEditable(image))

        val big = File(root, "big.sql")
        big.writeBytes(ByteArray((HostStorage.MAX_EDIT_BYTES + 1).toInt()))
        assertFalse(HostStorage.isEditable(big))
    }

    @Test
    fun `size formatting uses readable units`() {
        assertEquals("0 B", HostStorage.formatBytes(0))
        assertEquals("512 B", HostStorage.formatBytes(512))
        assertEquals("1.0 KB", HostStorage.formatBytes(1024))
        assertEquals("1.5 MB", HostStorage.formatBytes((1024L * 1024 * 3) / 2))
        assertEquals("2.0 GB", HostStorage.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `free space is reported`() {
        val root = root()
        assertNotNull(HostStorage.resolve(root, "index.html"))
        assertTrue(HostStorage.freeBytes(root) > 0)
    }
}
