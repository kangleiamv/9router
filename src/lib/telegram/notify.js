/**
 * Outbound Telegram notifications (quota, connection errors, combo fallback).
 *
 * All functions are fail-open and never throw: they run inside request hot paths
 * (combo fallback, usage fetch, connection test) and must not affect the proxy.
 * Cooldowns are in-memory per instance — duplicates across cold instances are
 * preferable to writing settings/Blob on every event.
 */

import { getSettings } from "@/lib/localDb";
import { normalizeTelegramConfig, isTelegramConfigured } from "./config.js";
import { sendTelegramMessage } from "./api.js";

const COOLDOWN_MS = {
  quota: 6 * 60 * 60 * 1000,
  error: 6 * 60 * 60 * 1000,
  combo: 10 * 60 * 1000,
};

const lastSentAt = new Map();

function shouldSend(key, windowMs) {
  const now = Date.now();
  const last = lastSentAt.get(key) || 0;
  if (now - last < windowMs) return false;
  lastSentAt.set(key, now);
  if (lastSentAt.size > 1000) {
    for (const [k, t] of lastSentAt) {
      if (now - t > 24 * 60 * 60 * 1000) lastSentAt.delete(k);
    }
  }
  return true;
}

/** Test hook: reset in-memory cooldowns. */
export function __resetNotifyCooldowns() {
  lastSentAt.clear();
}

async function loadConfig() {
  try {
    return normalizeTelegramConfig(await getSettings());
  } catch {
    return null;
  }
}

function chatIdsOf(cfg) {
  return Array.isArray(cfg?.allowedChatIds) ? cfg.allowedChatIds : [];
}

export async function sendTelegramNotification(cfg, text) {
  try {
    if (!cfg || !isTelegramConfigured(cfg)) return false;
    const chatIds = chatIdsOf(cfg);
    if (!chatIds.length) return false;
    let sent = false;
    for (const chatId of chatIds) {
      try {
        await sendTelegramMessage(cfg.botToken, chatId, text);
        sent = true;
      } catch (err) {
        console.warn(`[telegram] notify failed for chat ${chatId}:`, err?.message || err);
      }
    }
    return sent;
  } catch (err) {
    console.warn("[telegram] notify error:", err?.message || err);
    return false;
  }
}

function connectionLabel(connection) {
  const name = connection?.name || connection?.email || connection?.id?.slice(0, 8) || "connection";
  return `${connection?.provider || "provider"} · ${name}`;
}

/** @param {Array<{label?: string, remainingPct?: number, resetAt?: string}>} items */
export async function notifyQuotaLow(connection, items) {
  try {
    // Throttle before reading settings: fallback/quota hooks can fire often.
    const key = `quota:${connection?.id || connection?.provider}`;
    if (!shouldSend(key, COOLDOWN_MS.quota)) return false;
    const cfg = await loadConfig();
    if (!cfg || !cfg.notifyQuota || !isTelegramConfigured(cfg)) return false;

    const lines = (items || []).map((item) => {
      const pct = typeof item.remainingPct === "number" ? `${Math.round(item.remainingPct)}% left` : "used up";
      const reset = item.resetAt ? ` · resets ${new Date(item.resetAt).toLocaleString()}` : "";
      return `• ${item.label || "quota"}: ${pct}${reset}`;
    });
    const text = `⚠️ Quota low — ${connectionLabel(connection)}\n${lines.join("\n")}`;
    return await sendTelegramNotification(cfg, text);
  } catch (err) {
    console.warn("[telegram] quota notify error:", err?.message || err);
    return false;
  }
}

export async function notifyConnectionError(connection, error) {
  try {
    const key = `error:${connection?.id || connection?.provider}`;
    if (!shouldSend(key, COOLDOWN_MS.error)) return false;
    const cfg = await loadConfig();
    if (!cfg || !cfg.notifyErrors || !isTelegramConfigured(cfg)) return false;
    const text = `❌ Connection failing — ${connectionLabel(connection)}\n${String(error || "test failed").slice(0, 300)}`;
    return await sendTelegramNotification(cfg, text);
  } catch (err) {
    console.warn("[telegram] error notify error:", err?.message || err);
    return false;
  }
}

export async function notifyComboFallback({ comboName, failedModel, reason, status, nextModel }) {
  try {
    // Hot path (every failed combo member): gate on the cooldown before touching settings.
    const key = `combo:${comboName || "combo"}`;
    if (!shouldSend(key, COOLDOWN_MS.combo)) return false;
    const cfg = await loadConfig();
    if (!cfg || !cfg.notifyCombos || !isTelegramConfigured(cfg)) return false;
    const next = nextModel ? `→ ${nextModel}` : "(no candidate left)";
    const detail = [reason, status ? `HTTP ${status}` : ""].filter(Boolean).join(" · ");
    const text = `🔁 Combo fallback — ${comboName || "combo"}\n${failedModel || "member"} failed${detail ? ` (${detail})` : ""} ${next}`;
    return await sendTelegramNotification(cfg, text);
  } catch (err) {
    console.warn("[telegram] combo notify error:", err?.message || err);
    return false;
  }
}
