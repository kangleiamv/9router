package ai.ninerouter.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runtime actions the agent needs from the hosting layer. */
interface HostingAiHost {
    fun state(): HostServiceState
    fun logs(maxLines: Int): String
    suspend fun restart(): String
    suspend fun checkHttp(path: String): String
}

/**
 * Runs one diagnosis/fix session. File tools are local; restart and HTTP checks
 * are delegated to [host] so they use the real supervised runtime.
 */
class HostingAiAgent(
    private val tools: HostingAiTools,
    private val host: HostingAiHost,
    private val maxSteps: Int = HostingAi.MAX_STEPS,
) {
    suspend fun run(
        service: HostedService,
        model: String,
        chat: suspend (messages: List<Pair<String, String>>, system: String) -> HostingAiReply,
        onStep: (HostingAiStep) -> Unit,
    ): HostingAiRun {
        val system = systemPrompt(service)
        val messages = mutableListOf(
            "user" to "Diagnose this site and fix the root cause. Start with one tool action.",
        )
        var restarted = false
        var invalidReplies = 0

        for (step in 1..maxSteps) {
            val reply = try {
                withTimeout(HostingAi.MAX_CALL_MS) {
                    chat(compact(messages), system)
                }
            } catch (timeout: TimeoutCancellationException) {
                return fail(
                    "The AI provider did not answer within 3 minutes.",
                    onStep,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                return fail(
                    "Provider request failed: ${SecretMask.text(error.message ?: error.javaClass.simpleName)}",
                    onStep,
                )
            }
            if (!reply.ok) {
                return fail(
                    "Provider error: ${SecretMask.text(reply.text).take(1000)}",
                    onStep,
                )
            }
            val action = HostingAiParser.parse(reply.text)
            if (action == null) {
                invalidReplies++
                onStep(
                    HostingAiStep(
                        HostingAiTone.ERROR,
                        "The model did not return a tool action",
                        SecretMask.text(reply.text).take(240),
                    ),
                )
                if (invalidReplies >= 3) {
                    return fail(
                        "The model kept replying without a JSON tool action. " +
                            "Use a stronger model for this site.",
                        onStep,
                    )
                }
                messages += "assistant" to "I need to call a tool."
                messages += "user" to formatCorrection
                continue
            }
            invalidReplies = 0

            if (action is HostingAiAction.Done) {
                val files = tools.changedFiles()
                if (files.isNotEmpty() && !restarted) {
                    onStep(
                        HostingAiStep(
                            HostingAiTone.ERROR,
                            "Restart required",
                            "Files changed, but the site has not been restarted.",
                        ),
                    )
                    messages += "assistant" to "I am ready to finish."
                    messages += "user" to (
                        "TOOL ERROR: files changed but the service was not restarted. " +
                            "Call {\"action\":\"restart\"} and verify the log before done."
                        )
                    continue
                }
                val state = host.state()
                onStep(
                    HostingAiStep(
                        if (state.status == HostStatus.RUNNING) HostingAiTone.SUCCESS
                        else HostingAiTone.INFO,
                        "AI finished",
                        action.summary.take(1200),
                    ),
                )
                return HostingAiRun(
                    ok = true,
                    summary = action.summary,
                    changedFiles = tools.changedFiles(),
                    restarted = restarted,
                    siteRunning = state.status == HostStatus.RUNNING,
                )
            }

            onStep(HostingAiStep(HostingAiTone.TOOL, label(action)))
            val result = try {
                execute(action)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                HostingAiToolResult(
                    ok = false,
                    output = "ERROR: ${SecretMask.text(error.message ?: error.javaClass.simpleName)}",
                    summary = "Tool failed",
                )
            }
            if (action is HostingAiAction.Restart) restarted = true
            if (result.ok && (
                    action is HostingAiAction.WriteFile ||
                        action is HostingAiAction.ReplaceInFile
                    )
            ) {
                // Edits made after a restart must be followed by another restart.
                restarted = false
            }
            onStep(
                HostingAiStep(
                    if (result.ok) HostingAiTone.SUCCESS else HostingAiTone.ERROR,
                    result.summary.take(240),
                    if (result.ok) "" else result.output.take(500),
                ),
            )
            val output = result.output.take(HostingAi.MAX_TOOL_OUTPUT)
            messages += "assistant" to "I called ${label(action)}."
            messages += "user" to "TOOL RESULT (${label(action)}):\n$output"
        }

        return fail(
            "Stopped after $maxSteps steps. The site may still need attention; " +
                "review the transcript or run the agent again.",
            onStep,
        )
    }

    private suspend fun execute(action: HostingAiAction): HostingAiToolResult = when (action) {
        is HostingAiAction.ListFiles -> withContext(Dispatchers.IO) {
            tools.list(action.path)
        }
        is HostingAiAction.ReadFile -> withContext(Dispatchers.IO) {
            tools.read(action.path, action.offset, action.limit)
        }
        is HostingAiAction.WriteFile -> withContext(Dispatchers.IO) {
            tools.write(action.path, action.content)
        }
        is HostingAiAction.ReplaceInFile -> withContext(Dispatchers.IO) {
            tools.replace(action.path, action.old, action.new)
        }
        HostingAiAction.Restart -> {
            val output = host.restart()
            val running = host.state().status == HostStatus.RUNNING
            HostingAiToolResult(
                ok = !output.startsWith("ERROR"),
                output = output,
                summary = if (running) "Site restarted · running" else "Restart finished · not running yet",
            )
        }
        is HostingAiAction.ReadLogs -> {
            val text = host.logs(action.lines)
            HostingAiToolResult(
                ok = true,
                output = SecretMask.text(text),
                summary = "Read ${action.lines.coerceAtMost(120)} log lines",
            )
        }
        is HostingAiAction.CheckHttp -> {
            val output = host.checkHttp(action.path)
            val status = output.lineSequence().firstOrNull().orEmpty()
            HostingAiToolResult(
                ok = status.startsWith("HTTP 2") || status.startsWith("HTTP 3"),
                output = SecretMask.text(output),
                summary = status.ifBlank { "HTTP check finished" },
            )
        }
        is HostingAiAction.Done -> error("done is handled by the loop")
    }

    private fun fail(
        message: String,
        onStep: (HostingAiStep) -> Unit,
    ): HostingAiRun {
        onStep(HostingAiStep(HostingAiTone.ERROR, "AI stopped", message.take(1000)))
        return HostingAiRun(
            ok = false,
            summary = message,
            changedFiles = tools.changedFiles(),
            restarted = false,
            siteRunning = host.state().status == HostStatus.RUNNING,
        )
    }

    private fun label(action: HostingAiAction): String = when (action) {
        is HostingAiAction.ListFiles -> "List ${action.path.ifBlank { "release root" }}"
        is HostingAiAction.ReadFile -> "Read ${action.path}"
        is HostingAiAction.WriteFile -> "Write ${action.path} · ${action.content.length} chars"
        is HostingAiAction.ReplaceInFile -> "Edit ${action.path}"
        HostingAiAction.Restart -> "Restart site"
        is HostingAiAction.ReadLogs -> "Read logs"
        is HostingAiAction.CheckHttp -> "Check HTTP ${action.path}"
        is HostingAiAction.Done -> "Finish"
    }

    private fun systemPrompt(service: HostedService): String = buildString {
        appendLine(
            """
            You are 9Router Hosting Doctor, a senior web hosting engineer.
            You diagnose and repair a site running inside the 9Router Android app.
            The user has already connected their AI provider, so use only the supplied route.
            """.trimIndent(),
        )
        appendLine()
        appendLine("SITE")
        appendLine("name: ${service.name}")
        appendLine("kind: ${service.kind} (${service.spec?.label ?: service.kind})")
        appendLine("port: ${service.port}")
        appendLine("entry: ${service.entry.ifBlank { "default" }}")
        appendLine("active release: ${service.active.ifBlank { "legacy folder" }}")
        if (service.publicDir.isNotBlank()) appendLine("served subfolder: ${service.publicDir}")
        appendLine("build command: ${service.build.ifBlank { "package.json build script" }}")
        appendLine("npm install: ${service.npmInstall}; npm build: ${service.npmBuild}")
        val state = host.state()
        appendLine("status: ${state.status.name}")
        if (state.message.isNotBlank()) appendLine("status message: ${SecretMask.text(state.message)}")
        if (service.env.isNotEmpty()) {
            appendLine("environment keys (values hidden): ${service.env.keys.sorted().joinToString(", ")}")
        }
        appendLine()
        appendLine("ANDROID LIMITS")
        appendLine("- Node.js 26 + npm 11 are bundled. targetSdk 28 allows app-storage scripts.")
        appendLine("- There is no clang, make or Python build toolchain. Do not install native modules.")
        appendLine("- Next.js 16 needs Webpack because no android/arm64 native SWC binding exists.")
        appendLine("- The app builds and starts Node releases itself. Do not add native binaries.")
        appendLine()
        appendLine("TOOLS - reply with exactly one JSON object and no other tool syntax.")
        appendLine("{\"action\":\"list_files\",\"path\":\"\"}")
        appendLine("{\"action\":\"read_file\",\"path\":\"server.js\",\"offset\":0,\"limit\":400}")
        appendLine("{\"action\":\"replace_in_file\",\"path\":\"server.js\",\"old\":\"exact text\",\"new\":\"replacement\"}")
        appendLine("{\"action\":\"write_file\",\"path\":\"src/lib/db.ts\",\"content\":\"full file\"}")
        appendLine("{\"action\":\"restart\"}")
        appendLine("{\"action\":\"read_logs\",\"lines\":80}")
        appendLine("{\"action\":\"check_http\",\"path\":\"/\"}")
        appendLine("{\"action\":\"done\",\"summary\":\"what changed and what you verified\"}")
        appendLine()
        appendLine("RULES")
        appendLine("- Prefer replace_in_file for small edits; keep changes minimal and real.")
        appendLine("- Read a file before editing it. Never invent package versions or file contents.")
        appendLine("- After any file change, call restart. Then read logs and check HTTP.")
        appendLine("- Do not finish while the site is still failing, unless you need the user to")
        appendLine("  provide a missing source file, secret or external service.")
        appendLine("- Never write .env or other secret files; tell the user to use Environment.")
        appendLine("- Secret values are redacted in file and log output. Never try to recover them.")
        appendLine("- If a file is missing from the release, say so instead of creating a fake stub.")
        appendLine()
        appendLine("CURRENT EVIDENCE")
        appendLine(tools.contextSummary())
        appendLine()
        appendLine("RECENT LOG")
        appendLine(SecretMask.text(host.logs(80)).take(16_000))
    }

    private fun compact(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        if (messages.sumOf { it.second.length } <= 130_000) return messages
        val kept = ArrayDeque<Pair<String, String>>()
        var budget = 110_000
        for (message in messages.asReversed()) {
            val text = if (message.second.length > HostingAi.MAX_TOOL_OUTPUT) {
                message.second.take(HostingAi.MAX_TOOL_OUTPUT) + "\n… truncated"
            } else {
                message.second
            }
            if (kept.isNotEmpty() && text.length > budget) break
            kept.addFirst(message.first to text)
            budget -= text.length
        }
        return kept.toList()
    }

    private val formatCorrection =
        "FORMAT ERROR: reply with exactly one JSON object using an action from the tool list. " +
            "Do not use markdown, XML, function syntax or multiple actions."
}
