import { beforeEach, describe, expect, it, vi } from "vitest";
import { PROVIDERS } from "../../open-sse/config/providers.js";
import { OpenCodeExecutor } from "../../open-sse/executors/opencode.js";
import { proxyAwareFetch } from "../../open-sse/utils/proxyFetch.js";
import { OPENCODE_FINGERPRINT_TOOLS } from "../../open-sse/utils/opencodeFingerprint.js";

vi.mock("../../open-sse/utils/proxyFetch.js", () => ({
  proxyAwareFetch: vi.fn(async () => ({ ok: true, status: 200, headers: { get: () => "" } })),
}));

// Live catalog capabilities.tools.choice == ["auto"] for these chat models;
// anything else is answered with 400. The other free ids keep their choice.
const AUTO_ONLY = ["glm-5.3-flash-free", "mimo-v2.6-flash-free", "minimax-m3.1-flash-free"];
const UNRESTRICTED = "deepseek-v4.1-flash-free";
const CREDS = { connectionId: "opencode-free-tool-choice-test" };
const USER_MESSAGE = { role: "user", content: "hi" };
const TOOL = { type: "function", function: { name: "get_weather", description: "w", parameters: { type: "object", properties: {} } } };
const WIRE_TOOLS = [
  TOOL,
  ...OPENCODE_FINGERPRINT_TOOLS.map((name) =>
    expect.objectContaining({ type: "function", function: expect.objectContaining({ name }) })),
];

function chatBody(overrides = {}) {
  return {
    messages: [structuredClone(USER_MESSAGE)],
    tools: [structuredClone(TOOL)],
    ...overrides,
  };
}

beforeEach(() => {
  proxyAwareFetch.mockClear();
});

describe("opencode free chat tool_choice", () => {
  it("declares the auto-only quirk for the live restricted models only", () => {
    expect(PROVIDERS.opencode.quirks?.forceAutoToolChoiceModels).toEqual(AUTO_ONLY);
  });

  it.each([
    ["named", { type: "function", function: { name: "get_weather" } }],
    ["required", "required"],
    ["none", "none"],
  ])("demotes %s to auto on auto-only models", (_label, choice) => {
    for (const model of AUTO_ONLY) {
      for (const suffix of ["", "(max)"]) {
        const out = new OpenCodeExecutor().transformRequest(
          `${model}${suffix}`,
          chatBody({ tool_choice: structuredClone(choice) }),
          true,
          CREDS,
        );
        expect(out.tool_choice).toBe("auto");
        expect(out.tools).toEqual(WIRE_TOOLS);
        expect(out.messages.at(-1)).toEqual(USER_MESSAGE);
      }
    }
  });

  it("keeps auto and absent; tools/messages stay intact", () => {
    const autoOut = new OpenCodeExecutor().transformRequest(
      AUTO_ONLY[0], chatBody({ tool_choice: "auto" }), true, CREDS,
    );
    expect(autoOut.tool_choice).toBe("auto");
    expect(autoOut.tools).toEqual(WIRE_TOOLS);
    expect(autoOut.messages.at(-1)).toEqual(USER_MESSAGE);

    const absentOut = new OpenCodeExecutor().transformRequest(
      AUTO_ONLY[0], chatBody(), true, CREDS,
    );
    expect(absentOut.tool_choice).toBeUndefined();
    expect(absentOut.tools).toEqual(WIRE_TOOLS);
  });

  it("does not touch tool_choice on unrestricted free models", () => {
    const choice = { type: "function", function: { name: "get_weather" } };
    const out = new OpenCodeExecutor().transformRequest(
      UNRESTRICTED, chatBody({ tool_choice: structuredClone(choice) }), true, CREDS,
    );
    expect(out.tool_choice).toEqual(choice);
  });

  it("wire: execute sends the demoted choice to /inference/openai/v1/chat/completions", async () => {
    const ex = new OpenCodeExecutor();
    const model = AUTO_ONLY[0];
    const { url, transformedBody } = await ex.execute({
      model,
      body: chatBody({ tool_choice: { type: "function", function: { name: "get_weather" } } }),
      stream: true,
      credentials: CREDS,
    });
    expect(url).toBe("https://opencode.ai/inference/openai/v1/chat/completions");
    expect(transformedBody.tool_choice).toBe("auto");
    expect(proxyAwareFetch).toHaveBeenCalledTimes(1);
    const [actualUrl, actualInit] = proxyAwareFetch.mock.calls[0];
    expect(actualUrl).toBe("https://opencode.ai/inference/openai/v1/chat/completions");
    const sent = JSON.parse(actualInit.body);
    expect(sent.tool_choice).toBe("auto");
    expect(sent.model).toBe(model);
    expect(sent.tools).toEqual(WIRE_TOOLS);
  });
});
