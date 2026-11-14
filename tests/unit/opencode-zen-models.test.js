import { describe, expect, it } from "vitest";
import { PROVIDER_MODELS, getModelSupportedFormats } from "../../open-sse/config/providerModels.js";
import { PROVIDERS } from "../../open-sse/config/providers.js";
import { resolveTransport } from "../../open-sse/services/provider.js";

// Chat-only models (no /messages, no /responses support on opencode-zen).
const CHAT_ONLY = [
  "deepseek-v4.1-flash", "deepseek-v4-pro",
  "glm-5.3-flash", "glm-5.3", "glm-5.2", "glm-5.1", "glm-5",
  "minimax-m3", "minimax-m2.7", "minimax-m2.5",
  "kimi-k3", "kimi-k2.7-code", "kimi-k2.6", "kimi-k2.5",
  "mistral-large-4", "qwen3.8-max",
  "big-pickle",
  "deepseek-v4.1-flash-free", "glm-5.3-flash-free", "mimo-v2.6-flash-free",
  "minimax-m3.1-flash-free", "longcat-2.5-free", "iceman-free",
  "inkling-2-small-free", "beam-free", "flint-40b-free",
  "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free",
];
// Models served natively by the Anthropic /messages endpoint.
const CLAUDE_CAPABLE = [
  "claude-fable-5", "claude-fable-5-1", "claude-fable-5-2",
  "claude-opus-5-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7",
  "claude-opus-4-6", "claude-opus-4-5",
  "claude-sonnet-5-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-sonnet-4-5",
  "claude-haiku-5-5", "claude-haiku-4-5",
  "qwen3.8-flash", "qwen3.6-plus", "qwen3.5-plus", "union-alpha",
];
// Models served natively by the OpenAI /responses endpoint.
const RESPONSES_CAPABLE = [
  "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna",
  "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
  "gpt-5.5", "gpt-5.5-pro",
  "gpt-5.4", "gpt-5.4-pro", "gpt-5.4-mini", "gpt-5.4-nano",
  "gpt-5.3-codex-spark", "gpt-5.3-codex",
  "gpt-5.2", "gpt-5.2-codex",
  "gpt-5.1", "gpt-5.1-codex-max", "gpt-5.1-codex", "gpt-5.1-codex-mini",
  "gpt-5", "gpt-5-codex", "gpt-5-nano",
  "grok-build-0.1", "grok-4.7", "grok-4.6", "grok-4.5",
  "muse-spark-1.3", "muse-spark-1.2",
  "muse-ember-1.0", "muse-wonder-1.0",
];

// Mirror of chatCore's per-model transport guard: use the sourceFormat-matched
// transport only when the model declares support for that sourceFormat.
function pickTransport(provider, sourceFormat, alias, model) {
  const supported = getModelSupportedFormats(alias, model);
  const rt = resolveTransport(provider, sourceFormat);
  return supported?.includes(sourceFormat) ? rt : null;
}

describe("OpenCode Zen model catalog", () => {
  it("matches the documented model IDs", () => {
    const ids = (PROVIDER_MODELS["ocz"] || []).map((m) => m.id);
    expect(ids).toContain("deepseek-v4.1-flash");
    expect(ids).toContain("gpt-6.1-sol");
    expect(ids).toContain("claude-opus-5-5");
    expect(ids).toContain("kimi-k3");
    expect(ids).toContain("deepseek-v4-pro");
    expect(ids.length).toBeGreaterThan(60);
  });

  it("carries the live free catalog and drops retired ids", () => {
    const ids = (PROVIDER_MODELS["ocz"] || []).map((m) => m.id);
    for (const id of [
      "deepseek-v4.1-flash-free", "big-pickle", "glm-5.3-flash-free",
      "mimo-v2.6-flash-free", "minimax-m3.1-flash-free", "longcat-2.5-free",
      "iceman-free", "inkling-2-small-free", "beam-free", "flint-40b-free",
      "nemotron-3.5-lightning-free", "nemotron-3.5-super-vl-free",
    ]) {
      expect(ids).toContain(id);
    }
    for (const retired of [
      "muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free",
      "jev-1.13", "jev-1.13-free", "deepseek-v4-flash-free",
      "nemotron-3-ultra-free", "x-preview-f-free",
    ]) {
      expect(ids).not.toContain(retired);
    }
  });
});

describe("OpenCode Zen per-model supportedFormats", () => {
  it("declares [claude] for Claude + Qwen + union-alpha models", () => {
    for (const m of CLAUDE_CAPABLE) {
      expect(getModelSupportedFormats("ocz", m)).toEqual(["claude"]);
    }
  });

  it("declares [openai-responses] for GPT/Grok/Muse responses models", () => {
    for (const m of RESPONSES_CAPABLE) {
      expect(getModelSupportedFormats("ocz", m)).toEqual(["openai-responses"]);
    }
  });

  it("declares [openai] only for chat-only models → guards /messages routing", () => {
    for (const m of CHAT_ONLY) {
      expect(getModelSupportedFormats("ocz", m)).toEqual(["openai"]);
    }
  });
});

describe("OpenCode Zen multi-endpoint transports", () => {
  it("declares openai / claude / openai-responses transports", () => {
    const formats = (PROVIDERS["opencode-zen"].transports || []).map((t) => t.format);
    expect(formats).toEqual(["openai", "claude", "openai-responses"]);
  });

  it("resolveTransport picks the v2 endpoint matching the client sourceFormat", () => {
    expect(resolveTransport("opencode-zen", "claude").baseUrl).toBe("https://opencode.ai/inference/anthropic/v1/messages");
    expect(resolveTransport("opencode-zen", "openai-responses").baseUrl).toBe("https://opencode.ai/inference/openai/v1/responses");
    expect(resolveTransport("opencode-zen", "openai").baseUrl).toBe("https://opencode.ai/inference/openai/v1/chat/completions");
  });

  it("uses Bearer auth + anthropicVersion on the claude transport", () => {
    const t = resolveTransport("opencode-zen", "claude");
    expect(t.auth.header).toBe("Authorization");
    expect(t.auth.scheme).toBe("bearer");
    expect(t.auth.anthropicVersion).toBe(true);
  });
});

describe("OpenCode Zen per-model transport guard (chatCore logic)", () => {
  it("routes Qwen/Claude + claude-format client to /messages", () => {
    for (const m of CLAUDE_CAPABLE) {
      expect(pickTransport("opencode-zen", "claude", "ocz", m)?.baseUrl).toBe("https://opencode.ai/inference/anthropic/v1/messages");
    }
  });

  it("does NOT route chat-only models to /messages on a claude-format request", () => {
    for (const m of CHAT_ONLY) {
      expect(pickTransport("opencode-zen", "claude", "ocz", m)).toBeNull();
    }
  });

  it("routes GPT/Grok/Muse + responses-format client to /responses", () => {
    for (const m of RESPONSES_CAPABLE) {
      expect(pickTransport("opencode-zen", "openai-responses", "ocz", m)?.baseUrl).toBe("https://opencode.ai/inference/openai/v1/responses");
    }
  });

  it("routes Muse Spark (responses-only) to /responses, never to /messages", () => {
    for (const m of ["muse-spark-1.2", "muse-spark-1.3", "grok-4.6", "gpt-5.6-luna"]) {
      expect(getModelSupportedFormats("ocz", m)).toEqual(["openai-responses"]);
      expect(pickTransport("opencode-zen", "openai-responses", "ocz", m)?.baseUrl).toBe("https://opencode.ai/inference/openai/v1/responses");
      expect(pickTransport("opencode-zen", "claude", "ocz", m)).toBeNull();
      expect(pickTransport("opencode-zen", "openai", "ocz", m)).toBeNull();
    }
  });

  it("does NOT route Claude/Qwen models to /responses", () => {
    for (const m of CLAUDE_CAPABLE) {
      expect(pickTransport("opencode-zen", "openai-responses", "ocz", m)).toBeNull();
    }
  });
});
