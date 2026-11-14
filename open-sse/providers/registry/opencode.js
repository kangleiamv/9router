export default {
  id: "opencode",
  priority: 40,
  hasFree: true,
  alias: "oc",
  uiAlias: "oc",
  display: {
    name: "OpenCode Free",
    icon: "terminal",
    color: "#E87040",
    textIcon: "OC",
  },
  category: "free",
  noAuth: true,
  transport: {
    baseUrl: "https://opencode.ai",
    headers: {
      "x-opencode-client": "desktop",
    },
    forceStream: true,
    noAuth: true,
    // Zen reasoning models reject multi-turn history whose assistant messages
    // carry no reasoning_content (400 missing_required_parameter), and the
    // response pipeline strips the field before it reaches the client, so the
    // echo can never come from the client. Inject a placeholder instead.
    reasoningInject: {
      scope: "all",
    },
    quirks: {
      // The free lane's MiMo route (OpenRouter-backed) streams thinking as
      // `reasoning` + `reasoning_details[]`, not reasoning_content. stream.js lifts
      // the alias into reasoning_content for OpenAI-shaped clients; every other
      // model on the lane already emits reasoning_content, so the lift is a no-op.
      reasoningAliasFields: true,
      // Live catalog capabilities.tools.choice == ["auto"] for these ids; any
      // other choice (named/required/none) is rejected with 400. big-pickle and
      // the rest do not declare the restriction, so leave them untouched.
      forceAutoToolChoiceModels: [
        "glm-5.3-flash-free",
        "mimo-v2.6-flash-free",
        "minimax-m3.1-flash-free",
      ],
    },
  },
  // Live free catalog from https://opencode.ai/inference/v1/models (2026-11-14).
  // Every id below advertises api.chat-completions on /inference/openai/v1 and
  // a zero output price. Stale contributor/union/jev ids were retired upstream
  // and now answer 400 "Model is unavailable", so they are intentionally gone.
  models: [
    { id: "deepseek-v4.1-flash-free", name: "DeepSeek V4.1 Flash Free" },
    { id: "big-pickle", name: "Big Pickle" },
    { id: "glm-5.3-flash-free", name: "GLM-5.3-Flash Free" },
    { id: "mimo-v2.6-flash-free", name: "MiMo-V2.6-Flash Free" },
    { id: "minimax-m3.1-flash-free", name: "MiniMax M3.1 Flash Free" },
    { id: "longcat-2.5-free", name: "LongCat 2.5 Free" },
    { id: "iceman-free", name: "Iceman Free" },
    { id: "inkling-2-small-free", name: "Inkling 2 Small Free" },
    { id: "beam-free", name: "Beam Free" },
    { id: "flint-40b-free", name: "Flint 40B Free" },
    { id: "nemotron-3.5-lightning-free", name: "Nemotron 3.5 Lightning Free" },
    { id: "nemotron-3.5-super-vl-free", name: "Nemotron 3.5 Super VL Free" },
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
};
