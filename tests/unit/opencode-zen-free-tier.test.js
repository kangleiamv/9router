import { describe, expect, it } from "vitest";
import { OpenCodeZenExecutor } from "../../open-sse/executors/opencode-zen.js";
import { OPENCODE_SESSION_RE } from "../../open-sse/executors/opencode-zen.js";
import { isOpencodeFreeTierModel } from "../../open-sse/providers/models/helpers.js";

// Upstream 403s free-tier models on the keyed lane when the request carries a
// real oc_sk_* key (FreeTierError: "can only be used from within OpenCode").
// The executor must switch those to the attested `Bearer public` identity.
// big-pickle is a stealth free model without the "-free" suffix.

function preparedCredentials(executor, overrides = {}) {
  const body = { messages: [{ role: "user", content: "hi" }] };
  const credentials = {
    apiKey: "oc_sk_test_key",
    rawHeaders: {},
    connectionId: "conn_test",
    ...overrides,
  };
  return executor.prepareRequestCredentials({
    body,
    credentials,
    providerSessionId: "conversation-a",
    clientTool: "test",
  });
}

function headersFor(model, overrides) {
  const executor = new OpenCodeZenExecutor();
  const credentials = preparedCredentials(executor, overrides);
  return executor.buildHeaders(credentials, true, "https://opencode.ai/inference/openai/v1/chat/completions", model);
}

describe("isOpencodeFreeTierModel", () => {
  it("detects -free suffixed ids", () => {
    for (const m of [
      "mimo-v2.6-flash-free",
      "deepseek-v4.1-flash-free",
      "glm-5.3-flash-free",
      "minimax-m3.1-flash-free",
      "nemotron-3.5-lightning-free",
    ]) {
      expect(isOpencodeFreeTierModel(m)).toBe(true);
    }
  });

  it("ignores paid models", () => {
    for (const m of ["big-pickle", "muse-spark-1.3", "gpt-6.1-sol", "claude-opus-5-5", "kimi-k3"]) {
      expect(isOpencodeFreeTierModel(m)).toBe(false);
    }
  });

  it("strips thinking suffixes and provider prefixes before matching", () => {
    expect(isOpencodeFreeTierModel("mimo-v2.6-flash-free(xhigh)")).toBe(true);
    expect(isOpencodeFreeTierModel("glm-5.3-flash-free(max)")).toBe(true);
    expect(isOpencodeFreeTierModel("ocfree/mimo-v2.6-flash-free")).toBe(true);
    expect(isOpencodeFreeTierModel("big-pickle(max)")).toBe(false);
    expect(isOpencodeFreeTierModel("")).toBe(false);
    expect(isOpencodeFreeTierModel(null)).toBe(false);
  });
});

describe("OpenCodeZenExecutor free-tier auth", () => {
  it("sends Bearer public + the v2 session headers for free-tier models", () => {
    const headers = headersFor("mimo-v2.6-flash-free");
    expect(headers["Authorization"]).toBe("Bearer public");
    expect(headers["x-opencode-session"]).toMatch(OPENCODE_SESSION_RE);
    expect(headers["x-opencode-session-id"]).toBe(headers["x-opencode-session"]);
    expect(headers["x-session-affinity"]).toBe(headers["x-opencode-session"]);
    expect(headers["x-session-id"]).toBe(headers["x-opencode-session"]);
    expect("x-opencode-request" in headers).toBe(false);
    expect(headers["x-opencode-client"]).toBe("cli");
    expect(headers["User-Agent"]).toBe("opencode/latest/2.3.0/cli");
  });

  it("treats stealth big-pickle as a free model", () => {
    const headers = headersFor("big-pickle");
    expect(headers["Authorization"]).toBe("Bearer public");
    expect(headers["x-opencode-session"]).toMatch(OPENCODE_SESSION_RE);
  });

  it("keeps the connection key for paid models", () => {
    const headers = headersFor("deepseek-v4.1-flash");
    expect(headers["Authorization"]).toBe("Bearer oc_sk_test_key");
    expect("x-opencode-request" in headers).toBe(false);
    expect(headers["x-opencode-session"]).toMatch(OPENCODE_SESSION_RE);
    expect(headers["x-opencode-session-id"]).toBe(headers["x-opencode-session"]);
  });

  it("does not inject free-tier fingerprint tools into paid requests", () => {
    const executor = new OpenCodeZenExecutor();
    const paid = executor.transformRequest(
      "deepseek-v4.1-flash",
      { messages: [{ role: "user", content: "hi" }] },
      true,
      { apiKey: "oc_sk_test_key" },
    );
    expect(paid.tools).toBeUndefined();
    expect(paid.stream).toBeUndefined();

    const free = executor.transformRequest(
      "deepseek-v4.1-flash-free",
      { messages: [{ role: "user", content: "hi" }] },
      true,
      { apiKey: "oc_sk_test_key" },
    );
    expect(free.tools.map((t) => t.function?.name)).toEqual(["bash", "glob", "grep", "read"]);
    expect(free.stream).toBe(true);
  });
});
