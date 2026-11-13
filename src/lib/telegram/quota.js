/**
 * Quota/usage aggregation for the Telegram bridge (/quota command + cron alerts).
 *
 * Reuses the same service the dashboard Quota Tracker calls so the bot reports
 * exactly what the UI shows.
 */

import "open-sse/index.js";

import { getProviderConnections, getSettings } from "@/lib/localDb";
import { resolveConnectionProxyConfig } from "@/lib/network/connectionProxy";
import { getUsageForProvider } from "open-sse/services/usage.js";
import { USAGE_APIKEY_PROVIDERS, AI_PROVIDERS } from "@/shared/constants/providers";
import { normalizeTelegramConfig, isTelegramConfigured } from "./config.js";
import { notifyQuotaLow } from "./notify.js";

const MAX_CONNECTIONS = 12;

export function providerLabel(provider) {
  return AI_PROVIDERS[provider]?.name || provider;
}

function connectionLabel(connection) {
  return connection.name || connection.email || connection.id?.slice(0, 8) || "default";
}

function remainingPct(item) {
  if (typeof item?.remainingPercentage === "number") return item.remainingPercentage;
  if (item?.unlimited) return 100;
  const total = Number(item?.total);
  const used = Number(item?.used);
  if (Number.isFinite(total) && total > 0 && Number.isFinite(used)) {
    return Math.max(0, Math.min(100, ((total - used) / total) * 100));
  }
  return null;
}

/** Normalize the many provider usage shapes into a flat list of quota rows. */
export function normalizeUsageItems(usage) {
  if (!usage || typeof usage !== "object") return [];

  const quotas = usage.quotas ?? usage.limits ?? null;
  const rows = [];

  const push = (key, item) => {
    if (!item || typeof item !== "object") return;
    const pct = remainingPct(item);
    rows.push({
      label: item.displayName || item.name || item.label || key,
      remainingPct: pct,
      resetAt: item.resetAt || item.reset_time || null,
      unlimited: item.unlimited === true,
    });
  };

  if (Array.isArray(quotas)) {
    quotas.forEach((item, index) => push(item?.name || item?.id || `limit ${index + 1}`, item));
  } else if (quotas && typeof quotas === "object") {
    for (const [key, item] of Object.entries(quotas)) push(key, item);
  } else if (typeof usage.remaining === "number" || typeof usage.total === "number") {
    push(usage.plan || "usage", usage);
  }

  return rows;
}

function isEligible(connection) {
  if (connection.isActive === false) return false;
  const authType = connection.authType;
  if (authType === "oauth") return true;
  if (authType === "apikey" || authType === "api_key") {
    return USAGE_APIKEY_PROVIDERS.includes(connection.provider);
  }
  return false;
}

async function fetchConnectionUsage(connection) {
  const proxyConfig = await resolveConnectionProxyConfig(connection.providerSpecificData || {});
  const proxyOptions = {
    connectionProxyEnabled: proxyConfig.connectionProxyEnabled === true,
    connectionProxyUrl: proxyConfig.connectionProxyUrl || "",
    connectionNoProxy: proxyConfig.connectionNoProxy || "",
    vercelRelayUrl: proxyConfig.vercelRelayUrl || "",
    strictProxy: false,
  };
  const usage = await getUsageForProvider(connection, proxyOptions, {});
  return usage;
}

/**
 * @param {{ provider?: string }} [options]
 * @returns {Promise<Array<{ connection: object, items: Array, error?: string }>>}
 */
export async function collectUsage(options = {}) {
  let connections;
  try {
    connections = await getProviderConnections({ isActive: true });
  } catch {
    connections = [];
  }

  const filtered = connections
    .filter(isEligible)
    .filter((c) => !options.provider || c.provider === options.provider)
    .slice(0, MAX_CONNECTIONS);

  const results = [];
  for (const connection of filtered) {
    try {
      const usage = await fetchConnectionUsage(connection);
      results.push({ connection, usage, items: normalizeUsageItems(usage), error: usage?.message || null });
    } catch (err) {
      results.push({ connection, items: [], error: err?.message || String(err) });
    }
  }
  return results;
}

export function formatQuotaReport(results) {
  if (!results.length) return "No quota-tracked connections found. Connect a provider first.";

  const blocks = results.map(({ connection, items, error }) => {
    const head = `${providerLabel(connection.provider)} · ${connectionLabel(connection)}`;
    if (error && !items.length) return `${head}\n  ⚠️ ${String(error).slice(0, 160)}`;
    if (!items.length) return `${head}\n  (no quota data)`;
    const rows = items.map((item) => {
      const pct = item.unlimited ? "unlimited" : item.remainingPct == null ? "?" : `${Math.round(item.remainingPct)}% left`;
      const reset = item.resetAt ? ` · resets ${new Date(item.resetAt).toLocaleString()}` : "";
      return `  • ${item.label}: ${pct}${reset}`;
    });
    return `${head}\n${rows.join("\n")}`;
  });

  return `📊 Quota Tracker\n\n${blocks.join("\n\n")}`;
}

/**
 * Dashboard quota hook: called after a provider usage fetch. Notifies at most
 * once per connection per 10 minutes, and only when a quota sits at/below the
 * configured threshold. Fail-open and never throws.
 */
const lastUsageCheckAt = new Map();
const USAGE_CHECK_INTERVAL_MS = 10 * 60 * 1000;

export async function notifyLowQuotaFromUsage(connection, usage, settings) {
  try {
    const key = connection?.id || connection?.provider || "unknown";
    const now = Date.now();
    if (now - (lastUsageCheckAt.get(key) || 0) < USAGE_CHECK_INTERVAL_MS) return false;
    lastUsageCheckAt.set(key, now);
    if (lastUsageCheckAt.size > 500) {
      for (const [k, t] of lastUsageCheckAt) {
        if (now - t > 24 * 60 * 60 * 1000) lastUsageCheckAt.delete(k);
      }
    }

    const cfg = normalizeTelegramConfig(settings ?? (await getSettings()));
    if (!cfg.notifyQuota) return false;

    const low = normalizeUsageItems(usage).filter(
      (item) => !item.unlimited && typeof item.remainingPct === "number" && item.remainingPct <= cfg.quotaThreshold
    );
    if (!low.length) return false;
    return await notifyQuotaLow(connection, low);
  } catch {
    return false;
  }
}

/**
 * Cron/notification entry point: fetch usage and alert on connections whose
 * remaining quota dropped to/below the configured threshold.
 */
export async function checkQuotaAndNotify() {
  const settings = await getSettings();
  const cfg = normalizeTelegramConfig(settings);
  if (!isTelegramConfigured(cfg) || !cfg.notifyQuota) return { checked: 0, alerted: 0, skipped: true };

  const results = await collectUsage();
  let alerted = 0;
  for (const { connection, items } of results) {
    const low = items.filter(
      (item) => !item.unlimited && typeof item.remainingPct === "number" && item.remainingPct <= cfg.quotaThreshold
    );
    if (low.length) {
      const sent = await notifyQuotaLow(connection, low);
      if (sent) alerted += 1;
    }
  }
  return { checked: results.length, alerted };
}
