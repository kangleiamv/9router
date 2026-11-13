/**
 * Telegram bot configuration helpers.
 *
 * Settings live in the `telegram` key of the app settings row
 * (see src/lib/db/repos/settingsRepo.js). This module normalizes that blob so
 * every caller sees the same shape regardless of what the user stored.
 */

export const DEFAULT_TELEGRAM = {
  enabled: false,
  botToken: "",
  webhookSecret: "",
  allowedChatIds: [],
  defaultModel: "",
  systemPrompt: "",
  historyLimit: 12,
  notifyQuota: true,
  notifyErrors: true,
  notifyCombos: true,
  quotaThreshold: 20,
};

function toIdList(value) {
  const arr = Array.isArray(value)
    ? value
    : String(value || "").split(/[\s,;]+/);
  return arr
    .map((v) => String(v).trim())
    .filter(Boolean)
    .filter((v, i, all) => all.indexOf(v) === i);
}

function toInt(value, fallback, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(n)));
}

function toBool(value, fallback) {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return String(value).toLowerCase() === "true" || String(value) === "1";
}

export function normalizeTelegramConfig(settings) {
  const raw = settings?.telegram && typeof settings.telegram === "object" ? settings.telegram : {};
  return {
    enabled: toBool(raw.enabled, DEFAULT_TELEGRAM.enabled),
    botToken: String(raw.botToken || "").trim(),
    webhookSecret: String(raw.webhookSecret || "").trim(),
    allowedChatIds: toIdList(raw.allowedChatIds),
    defaultModel: String(raw.defaultModel || "").trim(),
    systemPrompt: typeof raw.systemPrompt === "string" ? raw.systemPrompt : "",
    historyLimit: toInt(raw.historyLimit, DEFAULT_TELEGRAM.historyLimit, { min: 0, max: 50 }),
    notifyQuota: toBool(raw.notifyQuota, DEFAULT_TELEGRAM.notifyQuota),
    notifyErrors: toBool(raw.notifyErrors, DEFAULT_TELEGRAM.notifyErrors),
    notifyCombos: toBool(raw.notifyCombos, DEFAULT_TELEGRAM.notifyCombos),
    quotaThreshold: toInt(raw.quotaThreshold, DEFAULT_TELEGRAM.quotaThreshold, { min: 1, max: 100 }),
  };
}

export function isTelegramConfigured(config) {
  return !!(config?.enabled && config?.botToken);
}

/** Whether a chat may use the bot. Empty allow-list denies everyone (see handler). */
export function isChatAllowed(config, chatId) {
  const id = String(chatId);
  return (config?.allowedChatIds || []).includes(id);
}
