/**
 * Internal chat bridge: run a prompt through this same 9Router instance without
 * going out over HTTP, so the Telegram bot reuses every provider, combo, key
 * access rule and the Vision Adapter that the proxy already has.
 *
 * The proxy enforces `requireApiKey` inside handleChat, so the bridge provisions
 * a dedicated unrestricted API key ("Telegram Bot") and presents it.
 */

import { getApiKeys, createApiKey } from "@/lib/localDb";
import { getConsistentMachineId } from "@/shared/utils/machineId";
import { handleChat } from "@/sse/handlers/chat.js";
import { initTranslators } from "open-sse/translator/index.js";

const INTERNAL_KEY_NAME = "Telegram Bot";
const INTERNAL_URL = "http://localhost/api/v1/chat/completions";

let translatorsReady = false;
let cachedKey = null;
let cachedKeyPromise = null;

async function ensureTranslators() {
  if (translatorsReady) return;
  await initTranslators();
  translatorsReady = true;
}

export async function ensureInternalApiKey() {
  if (cachedKey) return cachedKey;
  if (cachedKeyPromise) return cachedKeyPromise;

  cachedKeyPromise = (async () => {
    const keys = await getApiKeys();
    const existing = keys.find((k) => k.name === INTERNAL_KEY_NAME && k.isActive);
    if (existing?.key) {
      cachedKey = existing.key;
      return cachedKey;
    }
    const machineId = await getConsistentMachineId();
    const created = await createApiKey(INTERNAL_KEY_NAME, machineId);
    cachedKey = created.key;
    return cachedKey;
  })().finally(() => {
    cachedKeyPromise = null;
  });

  return cachedKeyPromise;
}

function extractOpenAIText(json) {
  if (!json || typeof json !== "object") return "";
  const choice = Array.isArray(json.choices) ? json.choices[0] : null;
  const message = choice?.message || choice?.delta || null;
  let content = message?.content;
  if (Array.isArray(content)) {
    content = content
      .map((part) => (typeof part === "string" ? part : part?.text || ""))
      .join("");
  }
  if (typeof content === "string" && content.trim()) return content;

  // OpenAI Responses API shape
  if (typeof json.output_text === "string" && json.output_text.trim()) return json.output_text;
  if (Array.isArray(json.output)) {
    const text = json.output
      .flatMap((item) => (Array.isArray(item?.content) ? item.content : []))
      .map((part) => part?.text || "")
      .join("");
    if (text.trim()) return text;
  }
  return "";
}

function extractSseText(raw) {
  let out = "";
  for (const line of String(raw).split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed.startsWith("data:")) continue;
    const data = trimmed.slice(5).trim();
    if (!data || data === "[DONE]") continue;
    let json = null;
    try {
      json = JSON.parse(data);
    } catch {
      continue;
    }
    if (json?.error) {
      const message = json.error?.message || json.error;
      if (message) out += `${out ? "\n" : ""}[error] ${message}`;
      continue;
    }
    const piece = extractOpenAIText(json);
    if (piece) out += piece;
    else if (typeof json?.delta?.text === "string") out += json.delta.text;
    else if (typeof json?.delta === "string") out += json.delta;
  }
  return out;
}

function extractErrorText(raw, contentType) {
  try {
    const json = JSON.parse(raw);
    const err = json?.error;
    if (typeof err === "string") return err;
    if (err?.message) return err.message;
    if (json?.message) return json.message;
  } catch {
    // Not JSON.
  }
  if (String(contentType).includes("text/event-stream")) {
    const sse = extractSseText(raw);
    if (sse) return sse.replace(/^\[error\]\s*/, "");
  }
  return String(raw || "").slice(0, 400) || "Request failed";
}

/**
 * Run one turn through the proxy.
 * @param {{ model: string, messages: Array, systemPrompt?: string }} args
 * @returns {Promise<{ ok: boolean, text: string, error?: string, status?: number }>}
 */
export async function runInternalChat({ model, messages, systemPrompt }) {
  await ensureTranslators();
  const key = await ensureInternalApiKey();

  const payloadMessages = Array.isArray(messages) ? [...messages] : [];
  if (systemPrompt && systemPrompt.trim()) {
    payloadMessages.unshift({ role: "system", content: systemPrompt });
  }

  const request = new Request(INTERNAL_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${key}`,
    },
    body: JSON.stringify({ model, messages: payloadMessages, stream: false }),
  });

  const response = await handleChat(request);
  const contentType = (response.headers.get("content-type") || "").toLowerCase();
  const raw = await response.text();

  if (!response.ok) {
    return { ok: false, status: response.status, text: "", error: extractErrorText(raw, contentType) };
  }

  if (contentType.includes("text/event-stream")) {
    const text = extractSseText(raw);
    if (text && !text.startsWith("[error]")) return { ok: true, text, status: 200 };
    return { ok: false, status: 200, text: "", error: text ? text.replace(/^\[error\]\s*/, "") : "Empty response" };
  }

  try {
    const json = JSON.parse(raw);
    if (json?.error) {
      return { ok: false, status: 200, text: "", error: json.error?.message || json.error };
    }
    const text = extractOpenAIText(json);
    if (text) return { ok: true, text, status: 200 };
    return { ok: false, status: 200, text: "", error: "Empty response" };
  } catch {
    return { ok: raw.trim().length > 0, status: 200, text: raw.trim(), error: raw.trim() ? undefined : "Empty response" };
  }
}
