package ai.ninerouter.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HostingAiTest {
    private fun root(): File = Files.createTempDirectory("hosting-ai").toFile()

    private fun write(root: File, path: String, text: String): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    @Test
    fun `parser reads fenced json and function arguments`() {
        val read = HostingAiParser.parse(
            """
            I will inspect the server.
            ```json
            {"action":"read_file","path":"server.js","limit":20}
            ```
            """.trimIndent(),
        )
        assertTrue(read is HostingAiAction.ReadFile)
        assertEquals("server.js", (read as HostingAiAction.ReadFile).path)
        assertEquals(20, read.limit)

        val write = HostingAiParser.parse(
            """{"name":"write_file","arguments":{"path":"src/a.js","content":"hello"}}""",
        )
        assertTrue(write is HostingAiAction.WriteFile)
        assertEquals("src/a.js", (write as HostingAiAction.WriteFile).path)

        val done = HostingAiParser.parse(
            """{"tool":"done","summary":"fixed the port"}""",
        )
        assertTrue(done is HostingAiAction.Done)
        assertEquals("fixed the port", (done as HostingAiAction.Done).summary)
    }

    @Test
    fun `parser ignores prose and unknown actions`() {
        assertTrue(HostingAiParser.parse("I will read the file next.") == null)
        assertTrue(HostingAiParser.parse("""{"action":"drop_database"}""") == null)
        assertTrue(
            HostingAiParser.parse(
                """{"action":"read_file"}""",
            ) == null,
        )
    }

    @Test
    fun `secrets are masked without hiding environment references`() {
        val masked = SecretMask.text(
            """
            DATABASE_URL=postgres://user:hunter2@localhost/db
            apiKey = "sk-12345678901234567890"
            PASSWORD=supersecret
            API_TOKEN="quoted secret with spaces"
            const password = process.env.PASSWORD
            Authorization: Bearer abcdefghijklmnop
            """.trimIndent(),
        )
        assertFalse(masked.contains("hunter2"))
        assertFalse(masked.contains("supersecret"))
        assertFalse(masked.contains("quoted secret with spaces"))
        assertFalse(masked.contains("sk-12345678901234567890"))
        assertFalse(masked.contains("abcdefghijklmnop"))
        assertTrue(masked.contains("[redacted]"))
        assertTrue(masked.contains("process.env.PASSWORD"))
        assertTrue(masked.contains("DATABASE_URL=postgres://user:[redacted]@localhost/db"))
    }

    @Test
    fun `file tools stay in the release and mask secrets`() {
        val root = root()
        write(root, "server.js", "const token = 'supersecret';\nconsole.log('old');\n")
        File(root, "node_modules").mkdirs()
        write(root, "node_modules/lib.js", "hidden")
        val backup = File(root, "backup")
        val tools = HostingAiTools(root, backup)

        val read = tools.read("server.js", 0, 50)
        assertTrue(read.ok)
        assertFalse(read.output.contains("supersecret"))
        assertTrue(read.output.contains("[redacted]"))

        assertTrue(tools.list("").output.contains("server.js"))
        assertFalse(tools.list("").output.contains("node_modules"))
        assertFalse(tools.read("../outside.txt", 0, 20).ok)
        assertFalse(tools.write("node_modules/x.js", "x").ok)
        assertFalse(tools.write(".env", "A=1").ok)
        assertFalse(tools.write("x.js", "[redacted]").ok)

        val edit = tools.replace("server.js", "console.log('old');", "console.log('new');")
        assertTrue(edit.ok)
        assertTrue(File(root, "server.js").readText().contains("console.log('new');"))
        assertTrue(File(backup, "server.js").isFile)
        assertEquals(listOf("server.js"), tools.changedFiles())

        val ambiguous = write(root, "dup.js", "x\nx\n")
        assertTrue(ambiguous.isFile)
        assertFalse(tools.replace("dup.js", "x", "y").ok)
    }

    @Test
    fun `agent reads, edits, restarts and verifies before finishing`() = runBlocking {
        val root = root()
        write(root, "server.js", "listen(process.env.PORT || 3000);\nconsole.log('old');\n")
        write(root, "package.json", """{"scripts":{"start":"node server.js"}}""")
        val host = FakeHost()
        val tools = HostingAiTools(root)
        val replies = ArrayDeque(
            listOf(
                """{"action":"read_file","path":"server.js"}""",
                """{"action":"replace_in_file","path":"server.js","old":"console.log('old');","new":"console.log('new');"}""",
                """{"action":"done","summary":"too early"}""",
                """{"action":"restart"}""",
                """{"action":"check_http","path":"/"}""",
                """{"action":"done","summary":"Fixed the startup log and verified HTTP 200."}""",
            ),
        )
        val steps = mutableListOf<HostingAiStep>()

        val run = HostingAiAgent(tools, host).run(
            service = HostedService(id = "site", kind = "node", name = "Demo", port = 3000),
            model = "test/model",
            chat = { _, _ -> HostingAiReply(true, replies.removeFirst()) },
            onStep = { steps += it },
        )

        assertTrue(run.ok)
        assertEquals(1, host.restarts)
        assertTrue(File(root, "server.js").readText().contains("console.log('new');"))
        assertTrue(run.changedFiles.contains("server.js"))
        assertTrue(run.restarted)
        assertTrue(run.siteRunning)
        assertTrue(steps.any { it.tone == HostingAiTone.ERROR && it.title == "Restart required" })
        assertTrue(steps.any { it.tone == HostingAiTone.SUCCESS && it.title == "AI finished" })
    }

    @Test
    fun `agent restarts again after an edit made after a restart`() = runBlocking {
        val root = root()
        write(root, "server.js", "console.log('one');\nconsole.log('two');\n")
        val host = FakeHost()
        val replies = ArrayDeque(
            listOf(
                """{"action":"restart"}""",
                """{"action":"replace_in_file","path":"server.js","old":"console.log('two');","new":"console.log('three');"}""",
                """{"action":"done","summary":"too early"}""",
                """{"action":"restart"}""",
                """{"action":"done","summary":"Edited the log and verified the service."}""",
            ),
        )
        val steps = mutableListOf<HostingAiStep>()
        val run = HostingAiAgent(HostingAiTools(root), host).run(
            service = HostedService(id = "site", kind = "node", name = "Demo", port = 3000),
            model = "test/model",
            chat = { _, _ -> HostingAiReply(true, replies.removeFirst()) },
            onStep = { steps += it },
        )

        assertTrue(run.ok)
        assertEquals(2, host.restarts)
        assertTrue(run.restarted)
        assertTrue(File(root, "server.js").readText().contains("console.log('three');"))
        assertTrue(steps.any { it.tone == HostingAiTone.ERROR && it.title == "Restart required" })
    }

    @Test
    fun `agent stops after repeated non-json replies`() = runBlocking {
        val root = root()
        val steps = mutableListOf<HostingAiStep>()
        val run = HostingAiAgent(HostingAiTools(root), FakeHost()).run(
            service = HostedService(id = "site", kind = "node", name = "Demo", port = 3000),
            model = "test/model",
            chat = { _, _ -> HostingAiReply(true, "I think the problem is the port.") },
            onStep = { steps += it },
        )
        assertFalse(run.ok)
        assertEquals(3, steps.count { it.tone == HostingAiTone.ERROR && it.title.contains("tool action") })
    }

    private class FakeHost : HostingAiHost {
        var restarts = 0
        private var current = HostServiceState(
            status = HostStatus.ERROR,
            port = 3000,
            message = "Exited",
            log = listOf("Error: boom"),
        )

        override fun state(): HostServiceState = current

        override fun logs(maxLines: Int): String =
            current.log.takeLast(maxLines).joinToString("\n")

        override suspend fun restart(): String {
            restarts++
            current = current.copy(status = HostStatus.RUNNING, message = "")
            return "Status: RUNNING\nLogs:\nready"
        }

        override suspend fun checkHttp(path: String): String = "HTTP 200\nok"
    }
}
