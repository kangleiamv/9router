package ai.ninerouter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExecPolicyTest {
    private val files = Files.createTempDirectory("exec-policy").toFile()
        .resolve("files")
        .apply { mkdirs() }
    private val native = File("/data/app/ai.ninerouter/lib/arm64")

    @Test
    fun `legacy devices and legacy targets keep app storage executable`() {
        val script = File(files, "npm/shim/npm").absolutePath
        assertFalse(ExecPolicy.blocked(28, 34, script, listOf(files)))
        assertFalse(ExecPolicy.blocked(34, 28, script, listOf(files)))
    }

    @Test
    fun `modern targets cannot exec writable app storage`() {
        val script = File(files, "npm/shim/npm").absolutePath
        assertTrue(ExecPolicy.blocked(29, 29, script, listOf(files)))
        assertTrue(ExecPolicy.blocked(34, 34, script, listOf(files)))
    }

    @Test
    fun `native library and prefix-adjacent paths are not app storage`() {
        val binary = File(native, "libnode.so").absolutePath
        assertFalse(ExecPolicy.blocked(34, 34, binary, listOf(files)))
        val adjacent = files.absolutePath + "2/node"
        assertFalse(ExecPolicy.blocked(34, 34, adjacent, listOf(files)))
        val exact = files.absolutePath
        assertTrue(ExecPolicy.blocked(34, 34, exact, listOf(files)))
    }
}
