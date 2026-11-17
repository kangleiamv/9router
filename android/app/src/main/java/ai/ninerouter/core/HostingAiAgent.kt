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
        var lastAction = ""
        var repeatedActions = 0

        for (step in 1..maxSteps) {
            val reply = try {
                withTimeout(HostingAi.MAX_CALL_MS) {
                    chat(compact(messages), system)
                }
            } catch (timeout: TimeoutCancellationException) {
                return fail(
                    "The AI provider did not answer within 3 minutes.",
                    onStep,
                    restarted,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                return fail(
                    "Provider request failed: ${SecretMask.text(error.message ?: error.javaClass.simpleName)}",
                    onStep,
                    restarted,
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
                        restarted,
                    )
                }
                messages += "assistant" to reply.text.trim()
                    .take(HostingAi.MAX_ASSISTANT_CHARS)
                    .ifBlank { "I need to call a tool." }
                messages += "user" to "$formatCorrection\n${budget(step)}"
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
                    messages += "assistant" to reply.text.trim()
                        .take(HostingAi.MAX_ASSISTANT_CHARS)
                        .ifBlank { "I am ready to finish." }
                    messages += "user" to (
                        "TOOL ERROR: files changed but the service was not restarted. " +
                            "Call {\"action\":\"restart\"} and verify the log before done. " +
                            budget(step)
                        )
                    continue
                }
                val state = host.state()
                val running = state.status == HostStatus.RUNNING
                val modelSummary = SecretMask.text(action.summary)
                val summary = if (running) {
                    modelSummary
                } else {
                    "$modelSummary The site is still not running."
                }
                onStep(
                    HostingAiStep(
                        if (running) HostingAiTone.SUCCESS else HostingAiTone.ERROR,
                        if (running) "AI finished" else "AI stopped with the site still down",
                        summary.take(1200),
                    ),
                )
                return HostingAiRun(
                    ok = running,
                    summary = summary,
                    changedFiles = tools.changedFiles(),
                    restarted = restarted,
                    siteRunning = running,
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
            val signature = "${action.javaClass.simpleName}:${action.hashCode()}"
            if (signature == lastAction) {
                repeatedActions++
            } else {
                lastAction = signature
                repeatedActions = 1
            }
            val output = result.output.take(HostingAi.MAX_TOOL_OUTPUT)
            // Keep the model's own diagnosis and patch text. Replacing every
            // turn with "I called read_file" made models restart their
            // investigation and burn through the step budget.
            messages += "assistant" to reply.text.trim()
                .take(HostingAi.MAX_ASSISTANT_CHARS)
                .ifBlank { "I called ${label(action)}." }
            messages += "user" to buildString {
                appendLine("TOOL RESULT (${label(action)}):")
                appendLine(output)
                appendLine()
                appendLine(budget(step))
                if (repeatedActions >= 3) {
                    appendLine(
                        "REPEAT WARNING: you already ran this exact action " +
                            "$repeatedActions times. It is not producing new evidence. " +
                            "Change approach or call done with what you have.",
                    )
                }
                val left = maxSteps - step
                if (left in 1..4) {
                    appendLine(wrapUp(left, restarted))
                }
            }
        }

        return finishAtLimit(messages, system, chat, onStep, restarted)
    }

    private fun budget(step: Int): String =
        "STEP $step/$maxSteps · ${maxSteps - step} step(s) left · " +
            "site ${host.state().status.name}."

    private fun wrapUp(left: Int, restarted: Boolean): String = buildString {
        append("WRAP UP: $left step(s) left. ")
        if (tools.changedFiles().isNotEmpty() && !restarted) {
            append("Call restart now. ")
        } else {
            append("Do not start another investigation. ")
        }
        append(
            "Read the log once, check HTTP once, then call done with what " +
                "you verified and what the user must still do.",
        )
    }

    /**
     * The step limit is a safety rail, not a crash. Ask for one final summary
     * without tools, then report the real service state and changed files.
     */
    private suspend fun finishAtLimit(
        messages: List<Pair<String, String>>,
        system: String,
        chat: suspend (messages: List<Pair<String, String>>, system: String) -> HostingAiReply,
        onStep: (HostingAiStep) -> Unit,
        restarted: Boolean,
    ): HostingAiRun {
        val finalPrompt =
            "FINAL TURN: the tool budget is exhausted. Do not call a tool. " +
                "Reply with one JSON object: " +
                "{\"action\":\"done\",\"summary\":\"what you changed, what you verified " +
                "and what the user must still do\"}"
        val reply = try {
            withTimeout(HostingAi.MAX_CALL_MS) {
                chat(compact(messages + ("user" to finalPrompt)), system)
            }
        } catch (timeout: TimeoutCancellationException) {
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            null
        }
        val modelText = when {
            reply == null || !reply.ok -> ""
            else -> {
                val action = HostingAiParser.parse(reply.text)
                if (action is HostingAiAction.Done) action.summary
                else reply.text.trim()
            }
        }
        val files = tools.changedFiles()
        val state = host.state()
        val running = state.status == HostStatus.RUNNING
        val applied = files.isEmpty() || restarted
        val ok = running && applied
        val details = buildString {
            append("Reached the $maxSteps-step limit. ")
            if (modelText.isNotBlank()) {
                append(SecretMask.text(modelText).take(1600))
            } else {
                append("The agent could not produce a final summary.")
            }
            append(" Current status: ${state.status.name}")
            if (state.message.isNotBlank()) {
                append(" (${SecretMask.text(state.message).take(220)})")
            }
            append(".")
            if (files.isNotEmpty()) {
                append(" ${files.size} file(s) changed")
                if (restarted) {
                    append(" and the service was restarted.")
                } else {
                    append(" but the edits were not applied by a restart.")
                }
            }
        }
        onStep(
            HostingAiStep(
                if (ok) HostingAiTone.SUCCESS else HostingAiTone.ERROR,
                if (ok) "AI finished at the step limit" else "AI stopped at the step limit",
                details.take(1400),
            ),
        )
        return HostingAiRun(
            ok = ok,
            summary = details,
            changedFiles = files,
            restarted = restarted,
            siteRunning = running,
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
        restarted: Boolean = false,
    ): HostingAiRun {
        val summary = SecretMask.text(message)
        onStep(HostingAiStep(HostingAiTone.ERROR, "AI stopped", summary.take(1000)))
        return HostingAiRun(
            ok = false,
            summary = summary,
            changedFiles = tools.changedFiles(),
            restarted = restarted,
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
        appendLine("- You have about $maxSteps tool steps. Every tool result shows the step number.")
        appendLine("  Do not repeat the same read or listing. Once the evidence names a cause,")
        appendLine("  make the fix, restart, verify once, then call done.")
        appendLine("- Do not finish while the site is still failing, unless you need the user to")
        appendLine("  provide a missing source file, secret or external service.")
        appendLine("- Never write .env or other secret files; tell the user to use Environment.")
        appendLine("- Secret values are redacted in file and log output. Never try to recover them.")
        appendLine("- If a file is missing from the release, say so instead of creating a fake stub.")
        appendLine("  Do not keep searching for a file the user never deployed.")
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
