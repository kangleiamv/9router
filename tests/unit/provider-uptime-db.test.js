// Drives the real node:sqlite adapter in a throwaway DATA_DIR so the table
// definition, hourly upsert and window query are exercised end to end.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "9router-uptime-"));
process.env.DATA_DIR = DATA_DIR;

const { recordProviderUptime, getProviderUptime } = await import(
  "../../src/lib/db/repos/uptimeRepo.js"
);
const { getAdapter } = await import("../../src/lib/db/driver.js");

let adapter;

beforeAll(async () => {
  adapter = await getAdapter();
});

afterAll(() => {
  try { adapter?.close?.(); } catch {}
  delete process.env.DATA_DIR;
  fs.rmSync(DATA_DIR, { recursive: true, force: true });
});

describe("provider uptime storage", () => {
  it("creates the table and folds requests into hourly buckets", async () => {
    const now = Date.now();
    await recordProviderUptime({ provider: "opencode", ok: true, status: 200, latencyMs: 100, timestamp: now - 60_000 });
    await recordProviderUptime({ provider: "opencode", ok: true, status: 200, latencyMs: 200, timestamp: now - 30_000 });
    await recordProviderUptime({ provider: "opencode", ok: false, status: 429, latencyMs: 300, timestamp: now - 10_000 });
    await recordProviderUptime({ provider: "openrouter", ok: true, status: 200, latencyMs: 400, timestamp: now - 60 * 60 * 1000 });

    const rows = adapter.all(
      `SELECT provider, requests, failures, latencyMs, lastStatus FROM providerUptime ORDER BY provider, bucket`
    );
    expect(rows).toHaveLength(2);
    const current = rows.find((row) => row.provider === "opencode" && row.requests === 3);
    expect(current.failures).toBe(1);
    expect(current.latencyMs).toBe(600);
    expect(current.lastStatus).toBe(429);

    const result = await getProviderUptime({
      windowMs: 2 * 60 * 60 * 1000,
      connections: [
        { provider: "opencode", isActive: true, testStatus: "error", lastError: "429", lastErrorAt: new Date(now).toISOString() },
        { provider: "silent", isActive: true, testStatus: "active" },
      ],
    });

    expect(result.totals.requests).toBe(4);
    expect(result.totals.failures).toBe(1);
    expect(result.totals.uptime).toBeCloseTo(75, 6);

    const byId = Object.fromEntries(result.providers.map((entry) => [entry.provider, entry]));
    expect(byId.opencode.source).toBe("requests");
    expect(byId.opencode.requests).toBe(3);
    expect(byId.opencode.uptime).toBeCloseTo((2 / 3) * 100, 6);
    expect(byId.openrouter.uptime).toBe(100);
    // The connection fallback fills a provider with no traffic.
    expect(byId.silent.source).toBe("connections");
    expect(byId.silent.uptime).toBe(100);
    expect(byId.silent.requests).toBe(0);
  });
});
