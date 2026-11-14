export default {
  id: "opencode-zen",
  priority: 205,
  alias: "ocz",
  aliases: [
    "opencode-zen",
  ],
  uiAlias: "ocz",
  display: {
    name: "OpenCode Zen",
    icon: "terminal",
    color: "#E87040",
    textIcon: "OZ",
    website: "https://opencode.ai/auth",
    notice: {
      text: "OpenCode Zen PAYG: pay-as-you-go, key from https://opencode.ai/auth. Same models as Zen: paid + free tiers on the fast lane.",
      apiKeyUrl: "https://opencode.ai/auth",
    },
  },
  category: "apikey",
  transport: {
    baseUrl: "https://opencode.ai/inference/openai/v1/chat/completions",
    headers: {},
    // Cheapest keyed-lane model used to verify an API key. A bad key is
    // rejected with 401 before inference starts; one token of a paid model
    // costs a fraction of a cent and avoids the free-tier client gate.
    validationModel: "deepseek-v4.1-flash",
    // Pay-as-you-go usage is not exposed by the public API (the console
    // Billing drawer uses an internal endpoint). getOpenCodeZenUsage returns a
    // clear message instead of querying a dead route.
  },
  // Multi-endpoint: pick the transport matching the client sourceFormat to skip
  // translation. Mirrors the live catalog `api` entries (2026-11-14):
  // chat-completions → /inference/openai/v1, messages → /inference/anthropic/v1,
  // responses → /inference/openai/v1.
  transports: [
    { format: "openai", baseUrl: "https://opencode.ai/inference/openai/v1/chat/completions", auth: { combined: true, header: "Authorization", scheme: "bearer" } },
    { format: "claude", baseUrl: "https://opencode.ai/inference/anthropic/v1/messages", auth: { combined: true, header: "Authorization", scheme: "bearer", anthropicVersion: true } },
    { format: "openai-responses", baseUrl: "https://opencode.ai/inference/openai/v1/responses", auth: { combined: true, header: "Authorization", scheme: "bearer" } },
  ],
  // Live /zen/v1/models catalog (86 ids, 2026-11-14). Gemini ids advertise a
  // generate-content endpoint this OpenAI/Anthropic provider cannot serve, so
  // they are intentionally omitted instead of failing with "Model is unavailable".
  models: [
    // DeepSeek / GLM / Kimi / MiniMax / Mistral / Qwen (chat completions)
    { id: "deepseek-v4.1-flash", name: "DeepSeek V4.1 Flash", supportedFormats: ["openai"] },
    { id: "deepseek-v4-pro", name: "DeepSeek V4 Pro", supportedFormats: ["openai"] },
    { id: "glm-5.3-flash", name: "GLM 5.3 Flash (Vision)", supportedFormats: ["openai"] },
    { id: "glm-5.3", name: "GLM 5.3", supportedFormats: ["openai"] },
    { id: "glm-5.2", name: "GLM 5.2", supportedFormats: ["openai"] },
    { id: "glm-5.1", name: "GLM 5.1", supportedFormats: ["openai"] },
    { id: "glm-5", name: "GLM 5", supportedFormats: ["openai"] },
    { id: "kimi-k3", name: "Kimi K3", supportedFormats: ["openai"] },
    { id: "kimi-k2.7-code", name: "Kimi K2.7 Code", supportedFormats: ["openai"] },
    { id: "kimi-k2.6", name: "Kimi K2.6", supportedFormats: ["openai"] },
    { id: "kimi-k2.5", name: "Kimi K2.5", supportedFormats: ["openai"] },
    { id: "minimax-m3", name: "MiniMax M3", supportedFormats: ["openai"] },
    { id: "minimax-m2.7", name: "MiniMax M2.7", supportedFormats: ["openai"] },
    { id: "minimax-m2.5", name: "MiniMax M2.5", supportedFormats: ["openai"] },
    { id: "mistral-large-4", name: "Mistral Large 4", supportedFormats: ["openai"] },
    { id: "qwen3.8-max", name: "Qwen 3.8 Max", supportedFormats: ["openai"] },
    { id: "big-pickle", name: "Big Pickle", supportedFormats: ["openai"] },
    // Claude (messages)
    { id: "claude-fable-5", name: "Claude Fable 5", supportedFormats: ["claude"] },
    { id: "claude-fable-5-1", name: "Claude Fable 5.1", supportedFormats: ["claude"] },
    { id: "claude-fable-5-2", name: "Claude Fable 5.2", supportedFormats: ["claude"] },
    { id: "claude-opus-5-5", name: "Claude Opus 5.5", supportedFormats: ["claude"] },
    { id: "claude-opus-5", name: "Claude Opus 5", supportedFormats: ["claude"] },
    { id: "claude-opus-4-8", name: "Claude Opus 4.8", supportedFormats: ["claude"] },
    { id: "claude-opus-4-7", name: "Claude Opus 4.7", supportedFormats: ["claude"] },
    { id: "claude-opus-4-6", name: "Claude Opus 4.6", supportedFormats: ["claude"] },
    { id: "claude-opus-4-5", name: "Claude Opus 4.5", supportedFormats: ["claude"] },
    { id: "claude-sonnet-5-5", name: "Claude Sonnet 5.5", supportedFormats: ["claude"] },
    { id: "claude-sonnet-5", name: "Claude Sonnet 5", supportedFormats: ["claude"] },
    { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", supportedFormats: ["claude"] },
    { id: "claude-sonnet-4-5", name: "Claude Sonnet 4.5", supportedFormats: ["claude"] },
    { id: "claude-haiku-5-5", name: "Claude Haiku 5.5", supportedFormats: ["claude"] },
    { id: "claude-haiku-4-5", name: "Claude Haiku 4.5", supportedFormats: ["claude"] },
    // Qwen (messages)
    { id: "qwen3.8-flash", name: "Qwen 3.8 Flash", supportedFormats: ["claude"] },
    { id: "qwen3.6-plus", name: "Qwen 3.6 Plus", supportedFormats: ["claude"] },
    { id: "qwen3.5-plus", name: "Qwen 3.5 Plus", supportedFormats: ["claude"] },
    { id: "union-alpha", name: "Union Alpha", supportedFormats: ["claude"] },
    // OpenAI Responses (GPT / Grok / Muse)
    { id: "gpt-6.1-sol", name: "GPT 6.1 Sol", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-6-astra", name: "GPT 6 Astra", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-6-sol", name: "GPT 6 Sol", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-6-luna", name: "GPT 6 Luna", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.6-sol", name: "GPT 5.6 Sol", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.6-terra", name: "GPT 5.6 Terra", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.6-luna", name: "GPT 5.6 Luna", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.5", name: "GPT 5.5", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.5-pro", name: "GPT 5.5 Pro", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.4", name: "GPT 5.4", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.4-pro", name: "GPT 5.4 Pro", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.4-mini", name: "GPT 5.4 Mini", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.4-nano", name: "GPT 5.4 Nano", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.3-codex-spark", name: "GPT 5.3 Codex Spark", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.3-codex", name: "GPT 5.3 Codex", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.2", name: "GPT 5.2", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.2-codex", name: "GPT 5.2 Codex", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.1", name: "GPT 5.1", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.1-codex-max", name: "GPT 5.1 Codex Max", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.1-codex", name: "GPT 5.1 Codex", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5.1-codex-mini", name: "GPT 5.1 Codex Mini", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5", name: "GPT 5", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5-codex", name: "GPT 5 Codex", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "gpt-5-nano", name: "GPT 5 Nano", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "grok-build-0.1", name: "Grok Build 0.1", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "grok-4.7", name: "Grok 4.7", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "grok-4.6", name: "Grok 4.6", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "grok-4.5", name: "Grok 4.5", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "muse-spark-1.3", name: "Muse Spark 1.3", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "muse-spark-1.2", name: "Muse Spark 1.2", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "muse-ember-1.0", name: "Muse Ember 1.0", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    { id: "muse-wonder-1.0", name: "Muse Wonder 1.0", targetFormat: "openai-responses", supportedFormats: ["openai-responses"] },
    // Free tier (chat completions, Bearer public when no key is configured)
    { id: "deepseek-v4.1-flash-free", name: "DeepSeek V4.1 Flash Free", supportedFormats: ["openai"] },
    { id: "glm-5.3-flash-free", name: "GLM 5.3 Flash Free", supportedFormats: ["openai"] },
    { id: "mimo-v2.6-flash-free", name: "MiMo V2.6 Flash Free", supportedFormats: ["openai"] },
    { id: "minimax-m3.1-flash-free", name: "MiniMax M3.1 Flash Free", supportedFormats: ["openai"] },
    { id: "longcat-2.5-free", name: "LongCat 2.5 Free", supportedFormats: ["openai"] },
    { id: "iceman-free", name: "Iceman Free", supportedFormats: ["openai"] },
    { id: "inkling-2-small-free", name: "Inkling 2 Small Free", supportedFormats: ["openai"] },
    { id: "beam-free", name: "Beam Free", supportedFormats: ["openai"] },
    { id: "flint-40b-free", name: "Flint 40B Free", supportedFormats: ["openai"] },
    { id: "nemotron-3.5-lightning-free", name: "Nemotron 3.5 Lightning Free", supportedFormats: ["openai"] },
    { id: "nemotron-3.5-super-vl-free", name: "Nemotron 3.5 Super VL Free", supportedFormats: ["openai"] },
  ],
  serviceKinds: ["llm", "systemone"],
  systemoneConfig: {
    baseUrl: "https://opencode.ai/inference/systemone/v1/systemone",
    headers: {
      "x-opencode-client": "desktop",
      "User-Agent": "opencode/latest/2.3.0/cli",
    },
  },
  modelsFetcher: { url: "https://opencode.ai/inference/v1/models", type: "opencode-free" },
  passthroughModels: true,
  features: {
    usage: false,
    usageApikey: false,
  },
};
