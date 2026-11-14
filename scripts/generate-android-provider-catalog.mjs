#!/usr/bin/env node
/**
 * Generates android/app/src/main/java/ai/ninerouter/engine/ProviderCatalogGenerated.kt
 * from the web provider registry in open-sse/providers/registry.
 *
 * Run from the repository root:
 *   node scripts/generate-android-provider-catalog.mjs
 */
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..");
const REGISTRY = path.join(ROOT, "open-sse/providers/registry");
const OUTPUT = path.join(
  ROOT,
  "android/app/src/main/java/ai/ninerouter/engine/ProviderCatalogGenerated.kt"
);

/** Providers maintained by hand in ProviderCatalog.kt. */
const CURATED = new Set([
  "antigravity",
  "claude",
  "gemini-cli",
  "opencode-zen",
  "openai",
  "anthropic",
  "gemini",
  "openrouter",
  "groq",
  "deepseek",
  "xai",
  "mistral",
  "together",
  "fireworks",
  "cerebras",
  "nvidia",
  "ollama",
]);

/** OAuth-category providers that also accept a pasted API key. */
const API_KEY_OAUTH = new Set(["glm", "kimi", "nous", "xiaomi-mimo"]);

/** Registry entries intentionally not surfaced as a separate Android entry. */
const SKIP = new Set(["ollama-local"]);

const FORMAT_MAP = {
  claude: "ANTHROPIC",
  anthropic: "ANTHROPIC",
  gemini: "GEMINI",
  "gemini-cli": "GEMINI_CLI",
  antigravity: "ANTIGRAVITY",
  ollama: "OLLAMA",
  openai: "OPENAI",
};

const KIND_ORDER = ["llm", "image", "embedding", "tts", "stt", "video", "systemone"];
const VALID_KINDS = new Set(KIND_ORDER);

function kstr(value) {
  return JSON.stringify(String(value)).replace(/\$/g, "\\$");
}

function kopt(value) {
  return value == null ? "null" : kstr(value);
}

function stripEndpoint(base, suffix) {
  let url = String(base || "").trim().replace(/\/+$/, "");
  if (suffix && url.endsWith(suffix)) url = url.slice(0, -suffix.length).replace(/\/+$/, "");
  return url;
}

function modelsUrlFor(provider, base) {
  const id = provider.id;
  if (id === "ollama") return "https://ollama.com/api/tags";
  if (id === "ollama-local") return "http://127.0.0.1:11434/api/tags";
  if (id === "opencode") return "https://opencode.ai/inference/v1/models";
  if (id === "perplexity") return "https://api.perplexity.ai/v1/models";
  if (id === "runcrate" || id === "synthetic") return `${base}/models`;

  const fetcher = provider.modelsFetcher?.url;
  if (fetcher && !fetcher.includes("models.dev")) return fetcher;
  const validate = provider.transport?.validateUrl;
  if (validate && /\/models(?:\?|$)|:models|api\/tags/.test(validate)) return validate;
  return `${base}/models`;
}

function transform(provider) {
  const id = provider.id === "ollama" ? "ollama-cloud" : provider.id;
  let name = provider.display?.name || provider.id;
  if (id === "ollama-cloud") name = "Ollama Cloud";
  if (id === "ollama-local") name = "Ollama Local";

  const rawFormat = provider.transport?.format || "openai";
  const format = FORMAT_MAP[rawFormat] || "OPENAI";
  const rawBase = String(provider.transport?.baseUrl || "");
  const hasBase = rawBase.startsWith("http") && !rawBase.includes("{");

  let base = rawBase;
  let chatPath = "/chat/completions";
  if (format === "ANTHROPIC") {
    base = stripEndpoint(rawBase, "/messages");
    chatPath = "/messages";
  } else if (format === "GEMINI") {
    base = stripEndpoint(rawBase, "/models");
    chatPath = "/models";
  } else if (format === "OLLAMA" || format === "OPENAI") {
    base = stripEndpoint(rawBase, "/chat/completions");
    chatPath = "/chat/completions";
  } else if (format === "GEMINI_CLI") {
    chatPath = "/";
  } else if (format === "ANTIGRAVITY") {
    chatPath = "/";
  }

  // Registry quirks that need an Android-specific endpoint.
  if (id === "opencode") {
    // Free models use the v2 OpenAI-compatible Chat Completions gateway.
    base = "https://opencode.ai/inference/openai/v1";
    chatPath = "/chat/completions";
  }
  if (id === "ollama-cloud") {
    base = "https://ollama.com/v1";
    chatPath = "/chat/completions";
  }
  if (id === "ollama-local") {
    base = "http://127.0.0.1:11434/v1";
    chatPath = "/chat/completions";
  }

  const category = String(provider.category || "apikey");
  const webOnly = category === "webCookie" || provider.authType === "cookie";
  const oauthOnly = category === "oauth" && !API_KEY_OAUTH.has(provider.id);
  const specialFormat = !FORMAT_MAP[rawFormat];
  const pathLooksChat =
    /\/chat\/completions$/.test(rawBase) ||
    /\/messages$/.test(rawBase) ||
    (provider.id === "gemini" && /\/models$/.test(rawBase)) ||
    provider.id === "opencode" ||
    provider.id === "ollama" ||
    provider.id === "ollama-local";

  const available =
    !provider.hidden &&
    hasBase &&
    !webOnly &&
    !oauthOnly &&
    !specialFormat &&
    (provider.models || []).some((m) => (m.kind || "llm") === "llm" || !m.kind) ||
    false;

  // Providers with a valid chat endpoint but no static LLM models still work:
  // the Models button discovers them live.
  const availableFinal =
    (available ||
      (!provider.hidden &&
        hasBase &&
        !webOnly &&
        !oauthOnly &&
        !specialFormat &&
        pathLooksChat &&
        !["assemblyai", "deepgram", "nanobanana"].includes(provider.id))) &&
    !["azure", "bedrock", "bedrock-xai", "cloudflare-ai", "commandcode", "vertex", "vertex-partner"].includes(
      provider.id
    );

  const models = (provider.models || []).map((model) => {
    const kind = VALID_KINDS.has(model.kind) ? model.kind : "llm";
    return {
      id: model.id,
      name: model.name || model.id,
      upstreamId: model.upstreamModelId || null,
      kind,
    };
  });

  const firstLlm = models.find((m) => m.kind === "llm") || models[0] || null;

  return {
    id,
    name,
    alias: provider.alias || null,
    format,
    baseUrl: hasBase ? base : null,
    chatPath,
    authType:
      provider.authType === "cookie"
        ? "cookie"
        : category === "oauth" && !API_KEY_OAUTH.has(provider.id)
          ? "oauth"
          : "apikey",
    category: category === "freeTier" || category === "free" ? "free" : category,
    color: provider.display?.color || "#E87040",
    website: provider.display?.website || null,
    models,
    defaultModel: firstLlm?.id || null,
    available: Boolean(availableFinal),
    modelsUrl: availableFinal && hasBase ? modelsUrlFor(provider, base) : null,
    modelsMethod: "GET",
    querySuffix: provider.transport?.urlSuffix || "",
    noAuth: provider.noAuth === true || provider.transport?.noAuth === true,
  };
}

const files = fs
  .readdirSync(REGISTRY)
  .filter((file) => file.endsWith(".js") && file !== "index.js")
  .sort();

const providers = [];
const seen = new Set();
for (const file of files) {
  const module = await import(`file://${path.join(REGISTRY, file)}?t=${Date.now()}`);
  const provider = module.default;
  if (
    !provider?.id ||
    provider.hidden ||
    SKIP.has(provider.id) ||
    (CURATED.has(provider.id) && provider.id !== "ollama")
  ) {
    continue;
  }
  if (seen.has(provider.id)) continue;
  seen.add(provider.id);
  providers.push(transform(provider));
}

// Stable, useful ordering: usable providers first, then alphabetical.
providers.sort((a, b) => {
  if (a.available !== b.available) return a.available ? -1 : 1;
  return a.name.localeCompare(b.name);
});

// Make aliases unambiguous. Provider ids always work as refs.
const usedAliases = new Set(
  [
    "antigravity",
    "ag",
    "claude",
    "cc",
    "claude-code",
    "gemini-cli",
    "gc",
    "opencode-zen",
    "oc",
    "openai",
    "oa",
    "anthropic",
    "cl",
    "gemini",
    "gm",
    "google-gemini",
    "openrouter",
    "or",
    "groq",
    "gq",
    "deepseek",
    "ds",
    "xai",
    "grok",
    "mistral",
    "ms",
    "together",
    "tg",
    "together-ai",
    "fireworks",
    "fw",
    "fireworks-ai",
    "cerebras",
    "cb",
    "nvidia",
    "nv",
    "nvidia-nim",
    "ollama",
    "ol",
    "ollama-(lan)",
  ].map((value) => value.toLowerCase())
);
for (const provider of providers) {
  if (provider.alias && usedAliases.has(provider.alias.toLowerCase())) provider.alias = null;
  if (provider.alias) usedAliases.add(provider.alias.toLowerCase());
  usedAliases.add(provider.id.toLowerCase());
}

const lines = [];
lines.push("package ai.ninerouter.engine");
lines.push("");
lines.push("// Auto-generated by scripts/generate-android-provider-catalog.mjs.");
lines.push("// Do not edit by hand; change the web registry and regenerate.");
lines.push("internal object ProviderCatalogGenerated {");
lines.push("    val providers: List<ProviderSpec> = listOf(");
for (const provider of providers) {
  lines.push("        ProviderSpec(");
  lines.push(`            id = ${kstr(provider.id)},`);
  lines.push(`            name = ${kstr(provider.name)},`);
  lines.push(`            alias = ${kopt(provider.alias)},`);
  lines.push(`            format = WireFormat.${provider.format},`);
  lines.push(`            baseUrl = ${kopt(provider.baseUrl)},`);
  lines.push(`            chatPath = ${kstr(provider.chatPath)},`);
  lines.push(`            authType = ${kstr(provider.authType)},`);
  lines.push(`            category = ${kstr(provider.category)},`);
  lines.push(`            color = ${kstr(provider.color)},`);
  lines.push(`            website = ${kopt(provider.website)},`);
  lines.push(`            available = ${provider.available},`);
  if (provider.modelsUrl) lines.push(`            modelsUrl = ${kstr(provider.modelsUrl)},`);
  if (provider.modelsMethod !== "GET") lines.push(`            modelsMethod = ${kstr(provider.modelsMethod)},`);
  if (provider.querySuffix) lines.push(`            querySuffix = ${kstr(provider.querySuffix)},`);
  if (provider.noAuth) lines.push("            noAuth = true,");
  if (provider.defaultModel) lines.push(`            defaultModel = ${kstr(provider.defaultModel)},`);
  lines.push("            models = listOf(");
  for (const model of provider.models) {
    const args = [`id = ${kstr(model.id)}`, `name = ${kstr(model.name)}`];
    if (model.upstreamId) args.push(`upstreamId = ${kstr(model.upstreamId)}`);
    if (model.kind !== "llm") args.push(`kind = ${kstr(model.kind)}`);
    lines.push(`                ModelSpec(${args.join(", ")}),`);
  }
  lines.push("            )");
  lines.push("        ),");
}
lines.push("    )");
lines.push("}");
lines.push("");

fs.writeFileSync(OUTPUT, lines.join("\n"));
const usable = providers.filter((provider) => provider.available).length;
console.log(
  `Wrote ${providers.length} providers (${usable} usable) to ${path.relative(ROOT, OUTPUT)}`
);
