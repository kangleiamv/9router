/**
 * Thin Telegram Bot API client.
 *
 * Only the methods the bridge needs are wrapped. All calls fail loudly (throw)
 * so callers can decide whether to surface or swallow the error; notification
 * paths wrap them in try/catch to stay fail-open.
 */

const TELEGRAM_API_BASE = "https://api.telegram.org";
const MESSAGE_LIMIT = 4096;
const CHUNK_LIMIT = 3900;

export function telegramApiUrl(token, method) {
  return `${TELEGRAM_API_BASE}/bot${encodeURIComponent(token)}/${method}`;
}

export async function telegramCall(token, method, payload = {}, { timeoutMs = 20000 } = {}) {
  if (!token) throw new Error("Missing Telegram bot token");
  const res = await fetch(telegramApiUrl(token, method), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload ?? {}),
    signal: AbortSignal.timeout(timeoutMs),
  });

  let data = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }

  if (!res.ok || data?.ok === false) {
    const description = data?.description || res.statusText || `HTTP ${res.status}`;
    throw new Error(description);
  }
  return data?.result;
}

export function splitTelegramText(text, limit = CHUNK_LIMIT) {
  const body = String(text ?? "");
  if (body.length <= limit) return [body];

  const chunks = [];
  let rest = body;
  while (rest.length > limit) {
    // Prefer a newline boundary, then a space, so code blocks stay readable.
    let cut = rest.lastIndexOf("\n", limit);
    if (cut < limit * 0.5) cut = rest.lastIndexOf(" ", limit);
    if (cut < limit * 0.5) cut = limit;
    chunks.push(rest.slice(0, cut));
    rest = rest.slice(cut).replace(/^\s+/, "");
  }
  if (rest) chunks.push(rest);
  return chunks;
}

export async function sendTelegramMessage(token, chatId, text, extra = {}) {
  const parts = splitTelegramText(text);
  const results = [];
  for (let i = 0; i < parts.length; i++) {
    // Attach inline/markup extras only to the first part.
    const payload = {
      chat_id: chatId,
      text: parts[i] || "​",
      disable_web_page_preview: true,
      ...(i === 0 ? extra : {}),
    };
    if (payload.text.length > MESSAGE_LIMIT) payload.text = payload.text.slice(0, MESSAGE_LIMIT);
    results.push(await telegramCall(token, "sendMessage", payload));
  }
  return results;
}

export async function sendTelegramChatAction(token, chatId, action = "typing") {
  try {
    await telegramCall(token, "sendChatAction", { chat_id: chatId, action });
  } catch {
    // Cosmetic only.
  }
}

export async function answerCallbackQuery(token, callbackQueryId, text) {
  try {
    await telegramCall(token, "answerCallbackQuery", {
      callback_query_id: callbackQueryId,
      ...(text ? { text: String(text).slice(0, 200) } : {}),
    });
  } catch {
    // Cosmetic only.
  }
}

export async function getTelegramMe(token) {
  return telegramCall(token, "getMe");
}

export async function getTelegramWebhookInfo(token) {
  return telegramCall(token, "getWebhookInfo");
}

export async function setTelegramWebhook(token, url, secretToken) {
  return telegramCall(token, "setWebhook", {
    url,
    ...(secretToken ? { secret_token: secretToken } : {}),
    allowed_updates: ["message", "edited_message", "callback_query"],
    drop_pending_updates: true,
  });
}

export async function deleteTelegramWebhook(token) {
  return telegramCall(token, "deleteWebhook", { drop_pending_updates: true });
}

export async function setTelegramCommands(token) {
  return telegramCall(token, "setMyCommands", {
    commands: [
      { command: "help", description: "Show help" },
      { command: "combo", description: "List or switch the active combo" },
      { command: "model", description: "Show or set the model used by the bot" },
      { command: "vision", description: "Show or edit Vision Adapter pools" },
      { command: "quota", description: "Usage + remaining quota per provider" },
      { command: "clear", description: "Clear this chat's history" },
    ],
  });
}

export async function getTelegramFile(token, fileId) {
  return telegramCall(token, "getFile", { file_id: fileId });
}

export async function downloadTelegramFile(token, filePath) {
  const res = await fetch(`${TELEGRAM_API_BASE}/file/bot${encodeURIComponent(token)}/${filePath}`, {
    signal: AbortSignal.timeout(30000),
  });
  if (!res.ok) throw new Error(`Failed to download Telegram file (${res.status})`);
  const arrayBuffer = await res.arrayBuffer();
  return Buffer.from(arrayBuffer);
}
