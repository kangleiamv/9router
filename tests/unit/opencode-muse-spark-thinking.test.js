import { describe, expect, it } from "vitest";
import { getCapabilitiesForModel } from "../../open-sse/providers/capabilities.js";
import { PROVIDER_MODELS, getModelTargetFormat } from "../../open-sse/config/providerModels.js";
import { getThinkingLevels } from "../../open-sse/providers/thinkingLevels.js";
import { FORMATS } from "../../open-sse/translator/formats.js";
import { OpenCodeExecutor } from "../../open-sse/executors/opencode.js";
import "../translator/registerAll.js";
import { translateRequest } from "../../open-sse/translator/index.js";

const PROVIDER = "opencode";

const input = [{
  type: "message",
  role: "user",
  content: [{ type: "input_text", text: "Think, then answer: 2 + 2?" }],
}];

const LIVE_FREE = [
  "deepseek-v4.1-flash-free",
  "big-pickle",
  "glm-5.3-flash-free",
  "mimo-v2.6-flash-free",
  "minimax-m3.1-flash-free",
  "longcat-2.5-free",
  "iceman-free",
  "inkling-2-small-free",
  "beam-free",
  "flint-40b-free",
  "nemotron-3.5-lightning-free",
  "nemotron-3.5-super-vl-free",
];

describe("OpenCode Free live catalog", () => {
  it("advertises exactly the live free ids and drops retired ones", () => {
    const ids = new Set((PROVIDER_MODELS.oc || []).map((model) => model.id));
    for (const id of LIVE_FREE) expect(ids.has(id)).toBe(true);
    for (const retired of [
      "muse-spark-1.2-contributor-free",
      "muse-spark-1.3-contributor-free",
      "jev-1.13-free",
      "union-alpha",
      "deepseek-v4-flash-free",
      "x-preview-f-free",
    ]) {
      expect(ids.has(retired)).toBe(false);
    }
  });

  it("keeps every live free model on Chat Completions", () => {
    const executor = new OpenCodeExecutor();
    for (const m of LIVE_FREE) {
      expect(executor.buildUrl(m)).toBe("https://opencode.ai/inference/openai/v1/chat/completions");
      expect(getModelTargetFormat("oc", m)).not.toBe(FORMATS.OPENAI_RESPONSES);
      expect(getModelTargetFormat("oc", m)).not.toBe(FORMATS.CLAUDE);
    }
  });

  it("exposes reasoning levels for the live free models", () => {
    for (const m of LIVE_FREE) {
      expect(getCapabilitiesForModel(PROVIDER, m).reasoning).toBe(true);
      expect(getCapabilitiesForModel(PROVIDER, `oc/${m}`).reasoning).toBe(true);
      expect(Array.isArray(getThinkingLevels(PROVIDER, m))).toBe(true);
    }
    expect(getThinkingLevels(PROVIDER, "deepseek-v4.1-flash-free")).toEqual(["low", "high", "max"]);
    expect(getThinkingLevels(PROVIDER, "glm-5.3-flash-free")).toEqual([
      "minimal",
      "low",
      "medium",
      "high",
      "xhigh",
    ]);
  });

  it("leaves the chat body fields alone", () => {
    const executor = new OpenCodeExecutor();
    const body = { messages: [{ role: "user", content: "hi" }], max_tokens: 1024 };
    executor.transformRequest("big-pickle", body, true, {});
    expect(executor.buildUrl("big-pickle")).toBe("https://opencode.ai/inference/openai/v1/chat/completions");
    expect(body.max_tokens).toBe(1024);
    expect(body.max_output_tokens).toBeUndefined();
  });
});

describe("OpenCode Responses passthrough (paid Muse ids)", () => {
  it("routes the Muse family to /responses and clamps max to xhigh", () => {
    const executor = new OpenCodeExecutor();
    for (const m of ["muse-spark-1.3", "muse-spark-1.2", "muse-spark-1.4", "muse-spark-2.0"]) {
      expect(executor.buildUrl(m)).toBe("https://opencode.ai/inference/openai/v1/responses");
      expect(executor.buildUrl(`${m}(high)`)).toBe("https://opencode.ai/inference/openai/v1/responses");
    }

    const body = {
      input,
      reasoning: { effort: "max" },
      max_tokens: 131072,
    };
    const out = executor.transformRequest("muse-spark-1.3", body, true, {
      connectionId: "opencode-responses-test",
    });

    expect(out.reasoning).toEqual({ effort: "xhigh", summary: "auto" });
    expect(out.reasoning_effort).toBeUndefined();
    expect(out.max_output_tokens).toBe(131072);
    expect(out.max_tokens).toBeUndefined();
  });

  it("translates Chat Completions max thinking into a Responses request", () => {
    const body = {
      model: "oc/muse-spark-1.3",
      messages: [{ role: "user", content: "Think, then answer: 2 + 2?" }],
      reasoning_effort: "max",
      max_tokens: 131072,
    };

    const translated = translateRequest(
      FORMATS.OPENAI,
      FORMATS.OPENAI_RESPONSES,
      "muse-spark-1.3",
      body,
      true,
      {},
      PROVIDER,
    );
    const out = new OpenCodeExecutor().transformRequest("muse-spark-1.3", translated, true, {
      connectionId: "opencode-responses-translation-test",
    });

    expect(out.reasoning).toEqual({ effort: "xhigh", summary: "auto" });
    expect(out.max_output_tokens).toBe(131072);
    expect(out.max_tokens).toBeUndefined();
  });

  it("strips prior-turn reasoning items carrying encrypted_content from input", () => {
    const executor = new OpenCodeExecutor();
    const model = "muse-spark-1.3";
    const body = {
      model,
      input: [
        { type: "message", role: "user", content: [{ type: "input_text", text: "say hi" }] },
        {
          type: "reasoning",
          id: "rs_123",
          encrypted_content: "ENC_BLOB_TURN_1",
          summary: [{ type: "summary_text", text: "thinking text" }],
        },
        {
          type: "function_call",
          id: "fc_1",
          call_id: "call_1",
          name: "shell",
          arguments: JSON.stringify({ command: "echo hi" }),
        },
        {
          type: "function_call_output",
          call_id: "call_1",
          output: "hi",
        },
        { type: "message", role: "user", content: [{ type: "input_text", text: "now say bye" }] },
      ],
      tools: [
        {
          type: "function",
          function: {
            name: "shell",
            description: "Run shell command",
            parameters: { type: "object" },
          },
        },
      ],
    };

    const out = executor.transformRequest(model, body, true, {});
    expect(out.stream).toBe(true);
    expect(out.store).toBe(false);
    expect(out.input.some((item) => item.type === "reasoning")).toBe(false);
    expect(JSON.stringify(out.input)).not.toContain("ENC_BLOB_TURN_1");
    const types = out.input.map((item) => item.type);
    expect(types).toEqual(["message", "function_call", "function_call_output", "message"]);
    expect(out.tools.slice(0, 1)).toEqual([
      {
        type: "function",
        name: "shell_client",
        description: "Run shell command",
        parameters: { type: "object", properties: {} },
      },
    ]);
    expect(out.tools.map((t) => t.name)).toEqual(["shell_client", "read", "shell"]);
    expect(out.input.find((item) => item.type === "function_call").name).toBe("shell_client");
  });
});
