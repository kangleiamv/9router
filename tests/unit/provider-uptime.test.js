import { describe, expect, it } from "vitest";
import {
  uptimeBucketStart,
  summarizeUptimeRows,
  mergeUptimeWithConnections,
} from "../../src/lib/db/repos/uptimeRepo.js";

describe("provider uptime aggregation", () => {
  it("floors timestamps to the UTC hour", () => {
    expect(uptimeBucketStart("2026-11-14T04:37:12.345Z")).toBe("2026-11-14T04:00:00.000Z");
    expect(uptimeBucketStart(new Date("2026-11-14T23:59:59.999Z"))).toBe("2026-11-14T23:00:00.000Z");
  });

  it("folds hourly buckets into per-provider totals and a timeline", () => {
    const { providers, timeline } = summarizeUptimeRows([
      {
        provider: "opencode",
        bucket: "2026-11-14T03:00:00.000Z",
        requests: 10,
        failures: 1,
        latencyMs: 1000,
        lastStatus: 200,
        lastAt: "2026-11-14T03:59:00.000Z",
      },
      {
        provider: "opencode",
        bucket: "2026-11-14T04:00:00.000Z",
        requests: 5,
        failures: 0,
        latencyMs: 500,
        lastStatus: 200,
        lastAt: "2026-11-14T04:10:00.000Z",
      },
      {
        provider: "openrouter",
        bucket: "2026-11-14T04:00:00.000Z",
        requests: 4,
        failures: 4,
        latencyMs: 800,
        lastStatus: 429,
        lastAt: "2026-11-14T04:20:00.000Z",
      },
      // Ignored: no provider / malformed counters degrade to zero.
      { bucket: "2026-11-14T04:00:00.000Z", requests: 9, failures: 9 },
      { provider: "empty", bucket: "2026-11-14T04:00:00.000Z" },
    ]);

    const byId = Object.fromEntries(providers.map((entry) => [entry.provider, entry]));
    expect(byId.opencode.requests).toBe(15);
    expect(byId.opencode.failures).toBe(1);
    expect(byId.opencode.uptime).toBeCloseTo((14 / 15) * 100, 6);
    expect(byId.opencode.avgLatencyMs).toBe(100);
    expect(byId.opencode.lastStatus).toBe(200);
    expect(byId.opencode.lastAt).toBe("2026-11-14T04:10:00.000Z");

    expect(byId.openrouter.uptime).toBe(0);
    expect(byId.empty.requests).toBe(0);
    expect(byId.empty.uptime).toBeNull();
    expect(byId.empty.avgLatencyMs).toBeNull();

    // Timeline is ordered and aggregates every provider in the bucket.
    expect(timeline.map((point) => point.bucket)).toEqual([
      "2026-11-14T03:00:00.000Z",
      "2026-11-14T04:00:00.000Z",
    ]);
    expect(timeline[1].requests).toBe(9);
    expect(timeline[1].failures).toBe(4);
    expect(timeline[1].uptime).toBeCloseTo((5 / 9) * 100, 6);
  });

  it("uses request uptime first and connection health only for silent providers", () => {
    const providers = [
      { provider: "opencode", requests: 10, failures: 1, uptime: 90, latencyMs: 100, avgLatencyMs: 10 },
      { provider: "openrouter", requests: 4, failures: 4, uptime: 0, latencyMs: 0, avgLatencyMs: 0 },
      { provider: "noluck", requests: 0, failures: 0, uptime: null, latencyMs: 0, avgLatencyMs: null },
    ];
    const connections = [
      { provider: "opencode", isActive: true, testStatus: "active" },
      // Silent provider with three tested connections, one broken.
      { provider: "broken", isActive: true, testStatus: "active" },
      { provider: "broken", isActive: true, testStatus: "active" },
      { provider: "broken", isActive: true, testStatus: "error", lastError: "401", lastErrorAt: "2026-11-14T04:00:00.000Z" },
      // Untested connection: no health signal yet.
      { provider: "untested", isActive: true, testStatus: "unknown" },
      // Inactive connections must not contribute.
      { provider: "disabled", isActive: false, testStatus: "error" },
    ];

    const merged = mergeUptimeWithConnections(providers, connections);
    const byId = Object.fromEntries(merged.map((entry) => [entry.provider, entry]));

    expect(byId.opencode.source).toBe("requests");
    expect(byId.opencode.uptime).toBe(90);
    expect(byId.opencode.connections.healthy).toBe(1);

    expect(byId.broken.source).toBe("connections");
    expect(byId.broken.requests).toBe(0);
    expect(byId.broken.uptime).toBeCloseTo((2 / 3) * 100, 6);
    expect(byId.broken.connections.error).toBe(1);
    expect(byId.broken.lastAt).toBe("2026-11-14T04:00:00.000Z");

    expect(byId.untested.source).toBe("none");
    expect(byId.untested.uptime).toBeNull();
    expect(byId.disabled).toBeUndefined();

    // Worst uptime first, unknown last, then traffic.
    expect(merged.map((entry) => entry.provider)).toEqual([
      "openrouter",
      "broken",
      "opencode",
      "noluck",
      "untested",
    ]);
  });
});
