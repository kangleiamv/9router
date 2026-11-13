/**
 * Telegram update handler: slash commands + two-way chat through the proxy.
 *
 * Runs after the webhook route has answered 200 (see webhook/route.js) so slow
 * model calls never make Telegram retry and double-process an update.
 */

import { getCombos, getSettings, updateSettings } from "@/lib/localDb";
import { AI_PROVIDERS } from "@/shared/constants/providers";
import { normalizeTelegramConfig, isChatAllowed } from "./config.js";
import { sendTelegramMessage, sendTelegramChatAction, getTelegramFile, downloadTelegramFile } from "./api.js";
import { runInternalChat } from "./internalChat.js";
import { collectUsage, formatQuotaReport, providerLabel } from "./quota.js";

const HISTORY_MAX = 50;
const chats = new Map();

const HELP_TEXT = [
  "🤖 9Router Telegram Bot",
  "",
  "Just send a message to chat through the proxy (the Vision Adapter handles images).",
  "",
  "Commands:",
  "/combo — list combos",
  "/combo <name> — use a combo for this bot",
  "/model — show the active model",
  "/model <provider/model> — set the model directly",
  "/vision — show the Vision Adapter pools",
  "/vision on|off — toggle it",
  "/vision add <model> / remove <model> — edit the vision pool",
  "/quota [provider] — usage + remaining quota",
  "/clear — clear this chat's history",
].join("\n");

function historyFor(chatId, limit) {
  let entry = chats.get(chatId);
  if (!entry) {
    entry = { messages: [], updatedAt: Date.now() };
    chats.set(chatId, entry);
  }
  entry.updatedAt = Date.now();
  if (chats.size > 200) {
    const cutoff = Date.now() - 12 * 60 * 60 * 1000;
    for (const [key, value] of chats) {
      if (value.updatedAt < cutoff) chats.delete(key);
    }
  }
  if (limit <= 0) return [];
  return entry.messages.slice(-limit);
}

async function updateTelegramConfig(partial) {
  const settings = await getSettings();
  const merged = { ...normalizeTelegramConfig(settings), ...partial };
  const { telegram } = await updateSettings({ telegram: merged });
  return normalizeTelegramConfig({ telegram });
}

async function updateVisionCapability(updater) {
  const settings = await getSettings();
  const capacityAdapter = { ...(settings.capacityAdapter || {}) };
  const current = {
    enabled: true,
    roundRobin: false,
    models: [],
    ...(capacityAdapter.vision || {}),
    models: [...(capacityAdapter.vision?.models || [])],
  };
  const nextVision = updater(current) || current;
  const updatedSettings = await updateSettings({
    capacityAdapter: { ...capacityAdapter, vision: nextVision },
  });
  return updatedSettings.capacityAdapter?.vision || nextVision;
}

function formatVision(vision) {
  const models = vision?.models?.length ? vision.models.join(", ") : "(none set — the built-in default vision fallback is used)";
  return [
    "👁 Vision Adapter",
    `Enabled: ${vision?.enabled ? "yes" : "no"}`,
    `Strategy: ${vision?.roundRobin ? "round-robin" : "priority"}`,
    `Models: ${models}`,
  ].join("\n");
}

async function listLlmCombos() {
  const combos = await listLlmCombos();
  return combos.filter((c) => !c.kind || c.kind === "llm");
}

async function resolveModel(cfg) {
  if (cfg.defaultModel) return cfg.defaultModel;
  try {
    const combos = await listLlmCombos();
    if (combos.length) return combos[0].name;
  } catch {
    // Ignore, fall through to the error message.
  }
  return "";
}

async function handleCommand(cfg, token, chatId, command, args) {
  switch (command) {
    case "start":
    case "help":
      await sendTelegramMessage(token, chatId, HELP_TEXT);
      return true;

    case "clear":
      chats.delete(chatId);
      await sendTelegramMessage(token, chatId, "🧹 History cleared.");
      return true;

    case "model": {
      if (!args) {
        const model = await resolveModel(cfg);
        await sendTelegramMessage(
          token,
          chatId,
          model ? `Model: ${model}` : "No model set. Use /model <provider/model> or /combo <name>."
        );
        return true;
      }
      await updateTelegramConfig({ defaultModel: args });
      await sendTelegramMessage(token, chatId, `✅ Model set to ${args}`);
      return true;
    }

    case "combo": {
      const combos = await listLlmCombos();
      if (!args) {
        if (!combos.length) {
          await sendTelegramMessage(token, chatId, "No combos defined yet. Create one in Dashboard → Combo & Vision Adapter.");
          return true;
        }
        const active = cfg.defaultModel;
        const list = combos
          .map((c) => `${c.name === active ? "✅" : "•"} ${c.name} — ${(c.models || []).length} models`)
          .join("\n");
        await sendTelegramMessage(token, chatId, `🎛 Combos:\n${list}\n\nUse /combo <name> to switch.`);
        return true;
      }
      if (args.toLowerCase() === "off" || args.toLowerCase() === "none") {
        await updateTelegramConfig({ defaultModel: "" });
        await sendTelegramMessage(token, chatId, "✅ Combo cleared — the bot will auto-pick the first combo.");
        return true;
      }
      const match = combos.find((c) => c.name.toLowerCase() === args.toLowerCase());
      if (!match) {
        await sendTelegramMessage(token, chatId, `Combo "${args}" not found. Use /combo to list.`);
        return true;
      }
      await updateTelegramConfig({ defaultModel: match.name });
      await sendTelegramMessage(
        token,
        chatId,
        `✅ Combo set to ${match.name}\n${(match.models || []).join("\n")}`
      );
      return true;
    }

    case "vision": {
      const tokens = args.split(/\s+/).filter(Boolean);
      const action = (tokens[0] || "").toLowerCase();
      if (!action) {
        await sendTelegramMessage(token, chatId, formatVision((await getSettings()).capacityAdapter?.vision));
        return true;
      }
      if (action === "on" || action === "off") {
        const vision = await updateVisionCapability((v) => ({ ...v, enabled: action === "on" }));
        await sendTelegramMessage(token, chatId, formatVision(vision));
        return true;
      }
      if (action === "add" || action === "remove") {
        const model = tokens[1];
        if (!model) {
          await sendTelegramMessage(token, chatId, `Usage: /vision ${action} <model>`);
          return true;
        }
        const vision = await updateVisionCapability((v) => {
          const models = action === "add"
            ? Array.from(new Set([...v.models, model]))
            : v.models.filter((m) => m !== model);
          return { ...v, enabled: true, models };
        });
        await sendTelegramMessage(token, chatId, formatVision(vision));
        return true;
      }
      if (action === "clear") {
        const vision = await updateVisionCapability((v) => ({ ...v, models: [] }));
        await sendTelegramMessage(token, chatId, formatVision(vision));
        return true;
      }
      await sendTelegramMessage(token, chatId, "Usage: /vision [on|off|add <model>|remove <model>|clear]");
      return true;
    }

    case "quota": {
      await sendTelegramChatAction(token, chatId, "typing");
      const providerArg = (args.split(/\s+/)[0] || "").toLowerCase();
      const provider = providerArg && AI_PROVIDERS[providerArg] ? providerArg : undefined;
      if (providerArg && !provider) {
        await sendTelegramMessage(token, chatId, `Unknown provider "${providerArg}".`);
        return true;
      }
      const results = await collectUsage(provider ? { provider } : {});
      await sendTelegramMessage(token, chatId, formatQuotaReport(results));
      return true;
    }

    default:
      return false;
  }
}

async function resolveIncomingImage(token, message) {
  const photos = Array.isArray(message.photo) ? message.photo : [];
  const largest = photos[photos.length - 1];
  const document = message.document;
  const fileId = largest?.file_id || (document?.mime_type?.startsWith("image/") ? document.file_id : null);
  if (!fileId) return null;
  try {
    const file = await getTelegramFile(token, fileId);
    if (!file?.file_path) return null;
    const buffer = await downloadTelegramFile(token, file.file_path);
    const mime = document?.mime_type || "image/jpeg";
    return `data:${mime};base64,${buffer.toString("base64")}`;
  } catch (err) {
    console.warn("[telegram] image download failed:", err?.message || err);
    return null;
  }
}

export async function handleTelegramUpdate(cfg, update) {
  const message = update?.message || update?.edited_message;
  if (!message) return;

  const token = cfg.botToken;
  const chatId = message.chat?.id;
  if (!chatId) return;

  if (!isChatAllowed(cfg, chatId)) {
    const ids = cfg.allowedChatIds || [];
    const hint = ids.length
      ? "This chat is not authorized. Ask the admin to add it in Dashboard → Telegram."
      : `Your chat ID is ${chatId}. Add it in Dashboard → Telegram to authorize this chat.`;
    await sendTelegramMessage(token, chatId, `🔒 ${hint}`).catch(() => {});
    return;
  }

  const text = typeof message.text === "string" ? message.text : typeof message.caption === "string" ? message.caption : "";

  if (text.startsWith("/")) {
    const [rawCommand, ...rest] = text.slice(1).split(/\s+/);
    const command = rawCommand.split("@")[0].toLowerCase();
    const args = rest.join(" ").trim();
    const handled = await handleCommand(cfg, token, chatId, command, args);
    if (handled) return;
  }

  const imageUrl = await resolveIncomingImage(token, message);
  if (!text && !imageUrl) return;

  const model = await resolveModel(cfg);
  if (!model) {
    await sendTelegramMessage(
      token,
      chatId,
      "No model available. Create a combo (Dashboard → Combo & Vision Adapter) or set one with /model <provider/model>."
    );
    return;
  }

  const userContent = imageUrl
    ? [{ type: "text", text: text || "Describe this image." }, { type: "image_url", image_url: { url: imageUrl } }]
    : text;

  const messages = [...historyFor(chatId, cfg.historyLimit), { role: "user", content: userContent }];

  await sendTelegramChatAction(token, chatId, "typing");

  let reply;
  try {
    const result = await runInternalChat({ model, messages, systemPrompt: cfg.systemPrompt });
    if (!result.ok) {
      await sendTelegramMessage(token, chatId, `⚠️ ${result.error || "Request failed"}`);
      return;
    }
    reply = result.text;
  } catch (err) {
    await sendTelegramMessage(token, chatId, `⚠️ ${err?.message || "Request failed"}`);
    return;
  }

  const entry = chats.get(chatId) || { messages: [], updatedAt: Date.now() };
  entry.messages.push({ role: "user", content: userContent }, { role: "assistant", content: reply });
  entry.messages = entry.messages.slice(-HISTORY_MAX);
  entry.updatedAt = Date.now();
  chats.set(chatId, entry);

  await sendTelegramMessage(token, chatId, reply);
}
