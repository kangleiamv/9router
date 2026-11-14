package ai.ninerouter.engine

import ai.ninerouter.data.model.ANTHROPIC_COMPATIBLE_TYPE
import ai.ninerouter.data.model.CustomProvider
import ai.ninerouter.data.model.OPENAI_COMPATIBLE_TYPE

/**
 * Native provider registry. Mirrors the web registry at a compact, Android-friendly
 * level: each entry declares the wire format, the default base URL and the models
 * that the dashboard can offer.
 */
enum class WireFormat { OPENAI, ANTHROPIC, GEMINI, GEMINI_CLI, ANTIGRAVITY, OLLAMA }

data class ModelSpec(
    val id: String,
    val name: String,
    val upstreamId: String? = null,
    val kind: String = "llm"
)

data class ProviderSpec(
    val id: String,
    val name: String,
    val alias: String? = null,
    val format: WireFormat = WireFormat.OPENAI,
    val baseUrl: String? = null,
    val chatPath: String = "/chat/completions",
    val authType: String = "apikey",
    val category: String = "apikey",
    val color: String = "#E87040",
    val website: String? = null,
    val models: List<ModelSpec> = emptyList(),
    /** Preferred model for new connections; falls back to the first catalog entry. */
    val defaultModel: String? = null,
    /** False for registry entries whose transport the native engine cannot speak yet. */
    val available: Boolean = true,
    /** Provider-specific model listing endpoint. Falls back to `<base>/models`. */
    val modelsUrl: String? = null,
    val modelsMethod: String = "GET",
    /** Extra query string appended to Anthropic-compatible message requests. */
    val querySuffix: String = "",
    /** True when the provider serves anonymous/free inference without a key. */
    val noAuth: Boolean = false
) {
    val allAliases: List<String>
        get() = listOfNotNull(id, alias, name.lowercase().replace(" ", "-")).distinct()

    /** True for user-defined OpenAI/Anthropic compatible endpoints. */
    val isCompatible: Boolean get() = category == "compatible"
}

object ProviderCatalog {
    private fun m(id: String, name: String, upstream: String? = null, kind: String = "llm") =
        ModelSpec(id, name, upstream, kind)

    val antigravity = ProviderSpec(
        id = "antigravity",
        name = "Antigravity",
        alias = "ag",
        format = WireFormat.ANTIGRAVITY,
        baseUrl = "https://daily-cloudcode-pa.googleapis.com",
        authType = "oauth",
        category = "oauth",
        color = "#F59E0B",
        website = "https://antigravity.google",
        models = listOf(
            m("gemini-4-carbon-high", "Gemini 4 Carbon (High)", "gemini-4-carbon-high(high)"),
            m("gemini-4-carbon-medium", "Gemini 4 Carbon (Medium)", "gemini-4-carbon-medium(medium)"),
            m("gemini-4-carbon-low", "Gemini 4 Carbon (Low)", "gemini-4-carbon-low(low)"),
            m("gemini-4-carbon", "Gemini 4 Carbon", "gemini-4-carbon-medium(medium)"),
            m("gemini-4-argon-high", "Gemini 4 Argon (High)", "gemini-4-argon-high(high)"),
            m("gemini-4-argon-medium", "Gemini 4 Argon (Medium)", "gemini-4-argon-medium(medium)"),
            m("gemini-4-argon-low", "Gemini 4 Argon (Low)", "gemini-4-argon-low(low)"),
            m("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)", "gemini-3.8-flash-high(high)"),
            m("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)", "gemini-3.8-flash-medium(medium)"),
            m("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)", "gemini-3.8-flash-low(low)"),
            m("gemini-3.8-flash", "Gemini 3.8 Flash", "gemini-3.8-flash-medium(medium)"),
            m("gemini-3.7-flash-high", "Gemini 3.7 Flash (High)", "gemini-3.7-flash-tiered(high)"),
            m("gemini-3.7-flash-medium", "Gemini 3.7 Flash (Medium)", "gemini-3.7-flash-tiered(medium)"),
            m("gemini-3.7-flash-low", "Gemini 3.7 Flash (Low)", "gemini-3.7-flash-tiered(low)"),
            m("gemini-3.6-flash-high", "Gemini 3.6 Flash (High)", "gemini-3.6-flash-tiered(high)"),
            m("gemini-3.6-flash-medium", "Gemini 3.6 Flash (Medium)", "gemini-3.6-flash-tiered(medium)"),
            m("gemini-3.6-flash-low", "Gemini 3.6 Flash (Low)", "gemini-3.6-flash-tiered(low)"),
            m("gemini-3.1-pro-high", "Gemini 3.1 Pro (High)", "gemini-pro-agent"),
            m("gemini-pro-agent", "Gemini 3.1 Pro (High)", "gemini-pro-agent"),
            m("gemini-3.1-pro-low", "Gemini 3.1 Pro (Low)", "gemini-3.1-pro-low(low)"),
            m("claude-sonnet-5-5-high", "Claude Sonnet 5.5 (High)", "claude-sonnet-5-5-high(high)"),
            m("claude-sonnet-5-5-medium", "Claude Sonnet 5.5 (Medium)", "claude-sonnet-5-5-medium(medium)"),
            m("claude-sonnet-5-5-low", "Claude Sonnet 5.5 (Low)", "claude-sonnet-5-5-low(low)"),
            m("claude-sonnet-5-5", "Claude Sonnet 5.5 (Thinking)", "claude-sonnet-5-5-high(high)"),
            m("claude-opus-5-5-high", "Claude Opus 5.5 (High)", "claude-opus-5-5-high(high)"),
            m("claude-opus-5-5-medium", "Claude Opus 5.5 (Medium)", "claude-opus-5-5-medium(medium)"),
            m("claude-opus-5-5-low", "Claude Opus 5.5 (Low)", "claude-opus-5-5-low(low)"),
            m("claude-opus-5-5", "Claude Opus 5.5 (Thinking)", "claude-opus-5-5-high(high)"),
            m("claude-sonnet-4-6", "Claude Sonnet 4.6 (Thinking)"),
            m("claude-opus-4-6-thinking", "Claude Opus 4.6 (Thinking)"),
            m("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)"),
            m("gemini-3.1-flash-image", "Gemini 3.1 Flash (Image)", kind = "image")
        ),
        defaultModel = "gemini-3.8-flash-medium"
    )

    val opencodeZen = ProviderSpec(
        id = "opencode-zen",
        name = "OpenCode Zen",
        alias = "oc",
        format = WireFormat.OPENAI,
        baseUrl = "https://opencode.ai/zen/v1",
        chatPath = "/chat/completions",
        authType = "apikey",
        color = "#22D3EE",
        website = "https://opencode.ai",
        models = listOf(
            m("grok-code", "Grok Code"),
            m("big-pickle", "Big Pickle"),
            m("qwen3-coder", "Qwen3 Coder"),
            m("kimi-k2", "Kimi K2"),
            m("glm-4.6", "GLM 4.6"),
            m("longcat-2.5-free", "LongCat 2.5 (Free)"),
            m("iceman-free", "Iceman (Free)"),
            m("inkling-2-small-free", "Inkling 2 Small (Free)"),
            m("beam-free", "Beam (Free)"),
            m("flint-40b-free", "Flint 40B (Free)"),
            m("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning (Free)"),
            m("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash (Free)"),
            m("glm-5.3-flash-free", "GLM 5.3 Flash (Free)"),
            m("minimax-m3.1-flash-free", "MiniMax M3.1 Flash (Free)")
        )
    )

    val openai = ProviderSpec(
        id = "openai",
        name = "OpenAI",
        alias = "oa",
        baseUrl = "https://api.openai.com/v1",
        color = "#10B981",
        website = "https://platform.openai.com",
        models = listOf(
            m("gpt-5.2", "GPT-5.2"),
            m("gpt-5.2-mini", "GPT-5.2 mini"),
            m("gpt-4.1", "GPT-4.1"),
            m("gpt-4.1-mini", "GPT-4.1 mini"),
            m("o4", "o4"),
            m("o4-mini", "o4 mini")
        )
    )

    val anthropic = ProviderSpec(
        id = "anthropic",
        name = "Anthropic",
        alias = "cl",
        format = WireFormat.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        chatPath = "/messages",
        color = "#D97757",
        website = "https://console.anthropic.com",
        models = listOf(
            m("claude-opus-4-6", "Claude Opus 4.6"),
            m("claude-sonnet-4-6", "Claude Sonnet 4.6"),
            m("claude-haiku-4-6", "Claude Haiku 4.6"),
            m("claude-opus-4-5", "Claude Opus 4.5"),
            m("claude-sonnet-4-5", "Claude Sonnet 4.5")
        )
    )

    val gemini = ProviderSpec(
        id = "gemini",
        name = "Google Gemini",
        alias = "gm",
        format = WireFormat.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        chatPath = "/models",
        color = "#3B82F6",
        website = "https://aistudio.google.com",
        models = listOf(
            m("gemini-3.8-flash", "Gemini 3.8 Flash"),
            m("gemini-3.7-flash", "Gemini 3.7 Flash"),
            m("gemini-3.1-pro", "Gemini 3.1 Pro"),
            m("gemini-2.5-pro", "Gemini 2.5 Pro"),
            m("gemini-2.5-flash", "Gemini 2.5 Flash")
        )
    )

    val openrouter = ProviderSpec(
        id = "openrouter",
        name = "OpenRouter",
        alias = "or",
        baseUrl = "https://openrouter.ai/api/v1",
        color = "#8B5CF6",
        website = "https://openrouter.ai",
        models = listOf(
            m("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6"),
            m("openai/gpt-5.2", "GPT-5.2"),
            m("google/gemini-3.8-flash", "Gemini 3.8 Flash"),
            m("deepseek/deepseek-v4", "DeepSeek V4"),
            m("qwen/qwen3-coder", "Qwen3 Coder"),
            m("meta-llama/llama-4-405b", "Llama 4 405B")
        )
    )

    val groq = ProviderSpec(
        id = "groq",
        name = "Groq",
        alias = "gq",
        baseUrl = "https://api.groq.com/openai/v1",
        color = "#F97316",
        website = "https://console.groq.com",
        models = listOf(
            m("llama-4-405b-versatile", "Llama 4 405B"),
            m("llama-4-70b-versatile", "Llama 4 70B"),
            m("qwen3-32b", "Qwen3 32B"),
            m("kimi-k2-instruct", "Kimi K2")
        )
    )

    val deepseek = ProviderSpec(
        id = "deepseek",
        name = "DeepSeek",
        alias = "ds",
        baseUrl = "https://api.deepseek.com/v1",
        color = "#2563EB",
        website = "https://platform.deepseek.com",
        models = listOf(
            m("deepseek-chat", "DeepSeek Chat"),
            m("deepseek-reasoner", "DeepSeek Reasoner"),
            m("deepseek-v4", "DeepSeek V4")
        )
    )

    val xai = ProviderSpec(
        id = "xai",
        name = "xAI",
        alias = "grok",
        baseUrl = "https://api.x.ai/v1",
        color = "#111827",
        website = "https://console.x.ai",
        models = listOf(
            m("grok-4", "Grok 4"),
            m("grok-4-fast", "Grok 4 Fast"),
            m("grok-code-fast-1", "Grok Code Fast")
        )
    )

    val mistral = ProviderSpec(
        id = "mistral",
        name = "Mistral",
        alias = "ms",
        baseUrl = "https://api.mistral.ai/v1",
        color = "#F59E0B",
        website = "https://console.mistral.ai",
        models = listOf(
            m("mistral-large-latest", "Mistral Large"),
            m("mistral-medium-latest", "Mistral Medium"),
            m("codestral-latest", "Codestral")
        )
    )

    val together = ProviderSpec(
        id = "together",
        name = "Together AI",
        alias = "tg",
        baseUrl = "https://api.together.xyz/v1",
        color = "#0EA5E9",
        website = "https://api.together.ai",
        models = listOf(
            m("meta-llama/Llama-4-405B-Instruct-Turbo", "Llama 4 405B"),
            m("Qwen/Qwen3-Coder-480B-A35B-Instruct", "Qwen3 Coder 480B"),
            m("deepseek-ai/DeepSeek-V4", "DeepSeek V4")
        )
    )

    val fireworks = ProviderSpec(
        id = "fireworks",
        name = "Fireworks AI",
        alias = "fw",
        baseUrl = "https://api.fireworks.ai/inference/v1",
        color = "#EF4444",
        website = "https://fireworks.ai",
        models = listOf(
            m("accounts/fireworks/models/llama4-maverick-instruct-basic", "Llama 4 Maverick"),
            m("accounts/fireworks/models/qwen3-coder-480b-a35b-instruct", "Qwen3 Coder 480B"),
            m("accounts/fireworks/models/deepseek-v4", "DeepSeek V4")
        )
    )

    val cerebras = ProviderSpec(
        id = "cerebras",
        name = "Cerebras",
        alias = "cb",
        baseUrl = "https://api.cerebras.ai/v1",
        color = "#F97316",
        website = "https://cloud.cerebras.ai",
        models = listOf(
            m("llama-4-scout-17b-16e-instruct", "Llama 4 Scout"),
            m("qwen-3-coder-480b", "Qwen3 Coder 480B"),
            m("gpt-oss-120b", "GPT-OSS 120B")
        )
    )

    val nvidia = ProviderSpec(
        id = "nvidia",
        name = "NVIDIA NIM",
        alias = "nv",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        color = "#76B900",
        website = "https://build.nvidia.com",
        models = listOf(
            m("deepseek-ai/deepseek-v4", "DeepSeek V4"),
            m("qwen/qwen3-coder-480b-a35b-instruct", "Qwen3 Coder 480B"),
            m("meta/llama-4-maverick-17b-128e-instruct", "Llama 4 Maverick")
        )
    )

    val ollama = ProviderSpec(
        id = "ollama",
        name = "Ollama (LAN)",
        alias = "ol",
        format = WireFormat.OLLAMA,
        baseUrl = "http://127.0.0.1:11434/v1",
        color = "#64748B",
        website = "https://ollama.com",
        models = listOf(
            m("llama4", "Llama 4"),
            m("qwen3-coder", "Qwen3 Coder"),
            m("deepseek-v4", "DeepSeek V4")
        )
    )

    val geminiCli = ProviderSpec(
        id = "gemini-cli",
        name = "Gemini CLI",
        alias = "gc",
        format = WireFormat.GEMINI_CLI,
        baseUrl = "https://cloudcode-pa.googleapis.com/v1internal",
        authType = "oauth",
        category = "oauth",
        color = "#4285F4",
        website = "https://github.com/google-gemini/gemini-cli",
        // Start on the free-tier friendly Flash preview. The 3.1 Pro preview
        // is not provisioned for every Code Assist account, so using it as the
        // first connection default made a working sign-in look broken.
        defaultModel = "gemini-3-flash-preview",
        models = listOf(
            m("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview"),
            m("gemini-3-pro-preview", "Gemini 3 Pro Preview"),
            m("gemini-3-flash-preview", "Gemini 3 Flash Preview"),
            m("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite Preview"),
            m("gemini-2.5-pro", "Gemini 2.5 Pro"),
            m("gemini-2.5-flash", "Gemini 2.5 Flash"),
            m("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite")
        )
    )

    val claude = ProviderSpec(
        id = "claude",
        name = "Claude Code",
        alias = "cc",
        format = WireFormat.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        chatPath = "/messages",
        authType = "oauth",
        category = "oauth",
        color = "#D97757",
        website = "https://claude.ai",
        models = listOf(
            m("claude-fable-5-2", "Claude Fable 5.2"),
            m("claude-opus-5-5", "Claude Opus 5.5"),
            m("claude-opus-5", "Claude Opus 5"),
            m("claude-fable-5", "Claude Fable 5"),
            m("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            m("claude-sonnet-5", "Claude Sonnet 5"),
            m("claude-haiku-5-5", "Claude Haiku 5.5"),
            m("claude-haiku-4-5-20251001", "Claude 4.5 Haiku")
        )
    )

    /** Hand-maintained entries that need Android-specific transport details. */
    private val curated: List<ProviderSpec> = listOf(
        antigravity, claude, geminiCli, opencodeZen, openai, anthropic, gemini,
        openrouter, groq, deepseek, xai, mistral, together, fireworks, cerebras,
        nvidia, ollama
    )

    private val curatedIds: Set<String> = curated.map { it.id }.toSet()

    /**
     * Every provider visible in the web dashboard, including entries the native
     * transport cannot serve yet ([ProviderSpec.available] = false).
     */
    val all: List<ProviderSpec>
        get() {
            val merged = curated + ProviderCatalogGenerated.providers
                .filter { it.id !in curatedIds }
            val dynamic = dynamicModels
            if (dynamic.isEmpty()) return merged
            return merged.map { spec ->
                val extra = dynamic[spec.id] ?: return@map spec
                spec.copy(models = mergeModels(spec.models, extra))
            }
        }

    /** Providers that can actually create a native connection. */
    val available: List<ProviderSpec> get() = all.filter { it.available }

    @Volatile
    private var dynamicModels: Map<String, List<ModelSpec>> = emptyMap()

    /** Replaces the in-memory cache of model ids fetched from provider APIs. */
    fun setDynamicModels(models: Map<String, List<ModelSpec>>) {
        dynamicModels = models.mapValues { (_, list) ->
            list.filter { it.id.isNotBlank() }.distinctBy { it.id.lowercase() }
        }.filterValues { it.isNotEmpty() }
    }

    fun dynamicModels(): Map<String, List<ModelSpec>> = dynamicModels

    private fun mergeModels(static: List<ModelSpec>, fetched: List<ModelSpec>): List<ModelSpec> {
        val byId = LinkedHashMap<String, ModelSpec>()
        // Fetched names win, but curated entries keep their upstream mapping and
        // kind when the provider reports only an id.
        static.forEach { byId[it.id.lowercase()] = it }
        fetched.forEach { model ->
            val previous = byId[model.id.lowercase()]
            byId[model.id.lowercase()] = if (previous == null) {
                model
            } else {
                previous.copy(
                    name = model.name.ifBlank { previous.name },
                    upstreamId = model.upstreamId ?: previous.upstreamId,
                    kind = if (model.kind == "llm") previous.kind else model.kind
                )
            }
        }
        return byId.values.toList()
    }

    fun byId(id: String): ProviderSpec? =
        all.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun byAliasOrId(value: String): ProviderSpec? {
        val key = value.trim().lowercase()
        return all.firstOrNull { spec -> spec.allAliases.any { it.lowercase() == key } }
    }

    /** Builds a runtime provider spec from a user-defined compatible endpoint. */
    fun customSpec(node: CustomProvider): ProviderSpec {
        val anthropic = node.type == ANTHROPIC_COMPATIBLE_TYPE
        return ProviderSpec(
            id = node.id,
            name = node.name.ifBlank {
                if (anthropic) "Anthropic Compatible" else "OpenAI Compatible"
            },
            alias = node.prefix,
            format = if (anthropic) WireFormat.ANTHROPIC else WireFormat.OPENAI,
            baseUrl = node.baseUrl,
            chatPath = if (anthropic) "/messages" else "/chat/completions",
            authType = "apikey",
            category = "compatible",
            color = if (anthropic) "#D97757" else "#10A37F",
            models = node.modelIds().map { m(it, it) }
        )
    }

    /**
     * Sanitises a user-entered base URL. Anthropic endpoints must not carry a
     * trailing `/messages` (the app appends it), and the same applies to
     * `/chat/completions` for OpenAI-compatible endpoints.
     */
    fun sanitizeBaseUrl(raw: String, type: String): String {
        var url = raw.trim().trimEnd('/')
        if (type == ANTHROPIC_COMPATIBLE_TYPE && url.endsWith("/messages")) {
            url = url.removeSuffix("/messages").trimEnd('/')
        }
        if (type == OPENAI_COMPATIBLE_TYPE && url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions").trimEnd('/')
        }
        return url
    }

    fun allWith(extras: List<ProviderSpec>): List<ProviderSpec> = all + extras

    fun availableWith(extras: List<ProviderSpec>): List<ProviderSpec> = available + extras

    /**
     * The user-facing model reference. Static providers use their provider id
     * (`openai/gpt-4o`); compatible endpoints use their configured prefix
     * (`oc-prod/gpt-4o`), which is what the user typed when creating the node.
     */
    fun modelRef(spec: ProviderSpec, modelId: String): String {
        val prefix = if (spec.isCompatible) spec.alias ?: spec.id else spec.id
        return "$prefix/$modelId"
    }

    fun resolve(modelRef: String, extras: List<ProviderSpec> = emptyList()): Pair<ProviderSpec?, String> {
        val trimmed = modelRef.trim()
        val slash = trimmed.indexOf('/')
        if (slash > 0) {
            val prefix = trimmed.substring(0, slash)
            val rest = trimmed.substring(slash + 1)
            val spec = byAliasOrId(prefix)?.takeIf { it.available } ?: extras.firstOrNull { option ->
                option.allAliases.any { it.equals(prefix, ignoreCase = true) }
            }
            if (spec != null) return spec to rest
        }
        // A bare model id that the user explicitly added to a compatible
        // endpoint wins over a same-named catalog entry.
        for (spec in extras + available) {
            val hit = spec.models.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
            if (hit != null) return spec to hit.id
        }
        return null to trimmed
    }

    fun displayName(providerId: String, modelId: String): String {
        val spec = byId(providerId)
        val model = spec?.models?.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
        return model?.name ?: modelId
    }

    fun displayName(spec: ProviderSpec?, modelId: String): String {
        val model = spec?.models?.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
        return model?.name ?: modelId
    }

    fun upstreamModel(providerId: String, modelId: String): String {
        val spec = byId(providerId) ?: return modelId
        return spec.models.firstOrNull { it.id.equals(modelId, ignoreCase = true) }?.upstreamId ?: modelId
    }

    fun modelsForSelection(): List<Triple<String, String, String>> =
        available.flatMap { spec ->
            spec.models.filter { it.kind == "llm" }.map { Triple(spec.id, it.id, it.name) }
        }

    fun modelsForSelection(extras: List<ProviderSpec>): List<Triple<String, String, String>> =
        availableWith(extras).flatMap { spec ->
            spec.models.filter { it.kind == "llm" }.map { Triple(spec.id, it.id, it.name) }
        }
}
