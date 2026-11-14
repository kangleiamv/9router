import { getAdapter } from "../driver.js";

const HOUR_MS = 60 * 60 * 1000;
const PRUNE_AFTER_MS = 45 * 24 * HOUR_MS;
const PRUNE_INTERVAL_MS = 6 * HOUR_MS;

// Prune at most once per process per interval; a failed prune must not stop
// recording, so every DB call is best-effort at the caller.
if (!global._providerUptimePrunedAt) global._providerUptimePrunedAt = 0;

function toEpochMs(value, fallback = Date.now()) {
  if (value instanceof Date) {
    return Number.isFinite(value.getTime()) ? value.getTime() : fallback;
  }
  if (typeof value === "number") return Number.isFinite(value) ? value : fallback;
  const parsed = new Date(value).getTime();
  return Number.isFinite(parsed) ? parsed : fallback;
}

/** ISO timestamp for the UTC hour containing `ts` (ms, Date or ISO string). */
export function uptimeBucketStart(ts = Date.now()) {
  const ms = toEpochMs(ts);
  return new Date(Math.floor(ms / HOUR_MS) * HOUR_MS).toISOString();
}

/**
 * Record one upstream outcome. Buckets are hourly so a busy instance cannot
 * grow the table by one row per request: the primary key folds every request
 * in the same provider/hour into a single counter row.
 */
export async function recordProviderUptime({
  provider,
  ok,
  status = null,
  latencyMs = 0,
  timestamp = Date.now(),
} = {}) {
  if (!provider) return;
  const db = await getAdapter();
  const at = toEpochMs(timestamp);
  const bucket = uptimeBucketStart(at);

  db.run(
    `INSERT INTO providerUptime(provider, bucket, requests, failures, latencyMs, lastStatus, lastAt)
     VALUES(?, ?, 1, ?, ?, ?, ?)
     ON CONFLICT(provider, bucket) DO UPDATE SET
       requests = requests + 1,
       failures = failures + excluded.failures,
       latencyMs = latencyMs + excluded.latencyMs,
       lastStatus = excluded.lastStatus,
       lastAt = excluded.lastAt`,
    [
      provider,
      bucket,
      ok ? 0 : 1,
      Math.max(0, Math.round(Number(latencyMs) || 0)),
      Number.isFinite(Number(status)) ? Number(status) : (ok ? 200 : 0),
      new Date(at).toISOString(),
    ]
  );

  if (Date.now() - global._providerUptimePrunedAt > PRUNE_INTERVAL_MS) {
    global._providerUptimePrunedAt = Date.now();
    db.run(`DELETE FROM providerUptime WHERE bucket < ?`, [
      new Date(Date.now() - PRUNE_AFTER_MS).toISOString(),
    ]);
  }
}

/** Fold hourly rows into per-provider totals plus the overall timeline. */
export function summarizeUptimeRows(rows = []) {
  const byProvider = new Map();
  const timeline = new Map();

  for (const row of rows) {
    const provider = row?.provider;
    if (!provider) continue;
    const requests = Number(row.requests) || 0;
    const failures = Number(row.failures) || 0;
    const latencyMs = Number(row.latencyMs) || 0;

    let entry = byProvider.get(provider);
    if (!entry) {
      entry = {
        provider,
        requests: 0,
        failures: 0,
        latencyMs: 0,
        lastStatus: null,
        lastAt: null,
      };
      byProvider.set(provider, entry);
    }
    entry.requests += requests;
    entry.failures += failures;
    entry.latencyMs += latencyMs;
    if (row.lastAt && (!entry.lastAt || row.lastAt > entry.lastAt)) {
      entry.lastAt = row.lastAt;
      entry.lastStatus = row.lastStatus ?? null;
    }

    const bucket = row.bucket;
    if (bucket) {
      const point = timeline.get(bucket) || { bucket, requests: 0, failures: 0 };
      point.requests += requests;
      point.failures += failures;
      timeline.set(bucket, point);
    }
  }

  const providers = [...byProvider.values()].map((entry) => ({
    ...entry,
    uptime: entry.requests > 0 ? ((entry.requests - entry.failures) / entry.requests) * 100 : null,
    avgLatencyMs: entry.requests > 0 ? Math.round(entry.latencyMs / entry.requests) : null,
  }));

  const timelinePoints = [...timeline.values()]
    .sort((a, b) => (a.bucket < b.bucket ? -1 : 1))
    .map((point) => ({
      ...point,
      uptime: point.requests > 0 ? ((point.requests - point.failures) / point.requests) * 100 : null,
    }));

  return { providers, timeline: timelinePoints };
}

/**
 * Providers with traffic keep their request-derived uptime. Providers without
 * samples fall back to the connection test health so a freshly configured or
 * silent provider still has a meaningful state instead of an empty row.
 */
export function mergeUptimeWithConnections(providers = [], connections = []) {
  const health = new Map();
  for (const connection of connections) {
    if (!connection?.provider || connection.isActive === false) continue;
    let entry = health.get(connection.provider);
    if (!entry) {
      entry = { total: 0, healthy: 0, error: 0, unknown: 0, lastError: null, lastErrorAt: null };
      health.set(connection.provider, entry);
    }
    entry.total += 1;
    if (connection.testStatus === "active") entry.healthy += 1;
    else if (connection.testStatus === "error") entry.error += 1;
    else entry.unknown += 1;
    if (connection.lastErrorAt && (!entry.lastErrorAt || connection.lastErrorAt > entry.lastErrorAt)) {
      entry.lastErrorAt = connection.lastErrorAt;
      entry.lastError = connection.lastError || null;
    }
  }

  const merged = new Map();
  for (const entry of providers) {
    merged.set(entry.provider, {
      ...entry,
      source: "requests",
      connections: health.get(entry.provider) || null,
    });
  }

  for (const [provider, state] of health) {
    if (merged.has(provider)) continue;
    const tested = state.healthy + state.error;
    merged.set(provider, {
      provider,
      requests: 0,
      failures: 0,
      latencyMs: 0,
      avgLatencyMs: null,
      lastStatus: state.error > 0 ? 503 : null,
      lastAt: state.lastErrorAt || null,
      uptime: tested > 0 ? (state.healthy / tested) * 100 : null,
      source: tested > 0 ? "connections" : "none",
      connections: state,
    });
  }

  return [...merged.values()].sort((a, b) => {
    // Worst first, then most traffic, then stable name order.
    const ua = a.uptime ?? 101;
    const ub = b.uptime ?? 101;
    if (ua !== ub) return ua - ub;
    if (a.requests !== b.requests) return b.requests - a.requests;
    return String(a.provider).localeCompare(String(b.provider));
  });
}

/** Read the window's hourly buckets and merge in connection-health fallbacks. */
export async function getProviderUptime({ windowMs = 24 * HOUR_MS, connections = [] } = {}) {
  const db = await getAdapter();
  const since = new Date(Date.now() - Math.max(HOUR_MS, Number(windowMs) || 24 * HOUR_MS)).toISOString();
  const rows = db.all(
    `SELECT provider, bucket, requests, failures, latencyMs, lastStatus, lastAt
       FROM providerUptime
      WHERE bucket >= ?
      ORDER BY bucket ASC`,
    [since]
  );

  const { providers, timeline } = summarizeUptimeRows(rows);
  const merged = mergeUptimeWithConnections(providers, connections);

  const requests = merged.reduce((sum, entry) => sum + entry.requests, 0);
  const failures = merged.reduce((sum, entry) => sum + entry.failures, 0);
  const testedConnections = merged.reduce(
    (sum, entry) => sum + (entry.connections ? entry.connections.healthy + entry.connections.error : 0),
    0
  );
  const healthyConnections = merged.reduce(
    (sum, entry) => sum + (entry.connections?.healthy || 0),
    0
  );

  return {
    since,
    providers: merged,
    timeline,
    totals: {
      providers: merged.length,
      requests,
      failures,
      uptime: requests > 0 ? ((requests - failures) / requests) * 100 : null,
      connections: {
        tested: testedConnections,
        healthy: healthyConnections,
        error: testedConnections - healthyConnections,
        uptime: testedConnections > 0 ? (healthyConnections / testedConnections) * 100 : null,
      },
    },
  };
}
