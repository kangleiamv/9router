"use client";

import { useCallback, useEffect, useState } from "react";
import { Card } from "@/shared/components";

const WINDOWS = [
  { value: "1h", label: "1h" },
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" },
];

function formatUptime(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  if (value >= 99.95) return "100%";
  return `${value.toFixed(value >= 99 ? 1 : 0)}%`;
}

function uptimeTone(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return { bar: "bg-surface-3", text: "text-text-muted" };
  }
  if (value >= 99) return { bar: "bg-emerald-500", text: "text-emerald-500" };
  if (value >= 95) return { bar: "bg-amber-500", text: "text-amber-500" };
  return { bar: "bg-red-500", text: "text-red-500" };
}

function providerMeta(entry) {
  if (entry.source === "connections") {
    const tested = entry.connections ? entry.connections.healthy + entry.connections.error : 0;
    return `connection health · ${tested} tested`;
  }
  if (entry.source === "requests") {
    return `${entry.requests} request${entry.requests === 1 ? "" : "s"}`;
  }
  return "no data";
}

export default function ProviderUptimeCard() {
  const [windowKey, setWindowKey] = useState("24h");
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async (signal) => {
    try {
      const response = await fetch(`/api/providers/uptime?window=${windowKey}`, {
        cache: "no-store",
        signal,
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const payload = await response.json();
      if (signal?.aborted) return;
      setData(payload);
      setError("");
    } catch (err) {
      if (err?.name === "AbortError") return;
      setError(err?.message || "Unable to load provider uptime");
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, [windowKey]);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    load(controller.signal);
    const timer = setInterval(() => load(), 60_000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [load]);

  const totals = data?.totals || {};
  const providers = Array.isArray(data?.providers) ? data.providers : [];
  const shown = providers.slice(0, 8);
  const overall = typeof totals.uptime === "number" ? totals.uptime : totals.connections?.uptime;
  const overallTone = uptimeTone(overall);
  const hasSamples = (totals.requests || 0) > 0;

  return (
    <Card>
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <span className="material-symbols-outlined text-primary">monitor_heart</span>
              Provider Uptime
            </h2>
            <p className="mt-1 text-sm text-text-muted">
              Upstream request success rate across your configured providers.
            </p>
          </div>
          <div className="flex items-center gap-1 rounded-lg border border-border bg-bg-subtle p-1">
            {WINDOWS.map((option) => (
              <button
                key={option.value}
                type="button"
                onClick={() => setWindowKey(option.value)}
                className={`rounded-md px-2.5 py-1 text-xs font-medium transition-colors ${
                  windowKey === option.value
                    ? "bg-primary text-white shadow-sm"
                    : "text-text-muted hover:bg-bg-hover hover:text-text-main"
                }`}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        {error && (
          <div className="rounded-lg border border-red-500/20 bg-red-500/10 px-3 py-2 text-sm text-red-500">
            {error}
          </div>
        )}

        <div className="flex flex-wrap items-end justify-between gap-4 rounded-xl border border-border bg-bg-subtle px-4 py-3">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-text-muted">Overall uptime</p>
            <p className={`mt-1 text-3xl font-semibold tabular-nums ${overallTone.text}`}>
              {loading && !data ? "…" : formatUptime(overall)}
            </p>
          </div>
          <div className="text-right text-xs text-text-muted">
            <p>
              {hasSamples
                ? `${totals.requests.toLocaleString()} request${totals.requests === 1 ? "" : "s"} · ${(totals.failures || 0).toLocaleString()} failed`
                : "No request samples in this window"}
            </p>
            <p className="mt-1">
              {providers.length} provider{providers.length === 1 ? "" : "s"} tracked
              {!hasSamples && totals.connections?.tested
                ? ` · ${totals.connections.healthy}/${totals.connections.tested} connections healthy`
                : ""}
            </p>
          </div>
        </div>

        {loading && !data ? (
          <div className="flex items-center justify-center py-8 text-text-muted">
            <span className="material-symbols-outlined animate-spin text-[28px]">progress_activity</span>
          </div>
        ) : shown.length === 0 ? (
          <div className="rounded-xl border border-dashed border-border px-4 py-8 text-center text-sm text-text-muted">
            No uptime samples yet. Send a request or test a connection to start tracking.
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {shown.map((entry) => {
              const tone = uptimeTone(entry.uptime);
              return (
                <div key={entry.provider} className="flex items-center gap-3">
                  <div className="w-40 min-w-0">
                    <p className="truncate text-sm font-medium text-text-main">{entry.name || entry.provider}</p>
                    <p className="truncate text-[11px] text-text-muted">{providerMeta(entry)}</p>
                  </div>
                  <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-surface-2">
                    <div
                      className={`h-full rounded-full transition-all ${tone.bar}`}
                      style={{ width: `${Math.max(0, Math.min(100, entry.uptime ?? 0))}%` }}
                    />
                  </div>
                  <span className={`w-14 shrink-0 text-right text-xs font-semibold tabular-nums ${tone.text}`}>
                    {formatUptime(entry.uptime)}
                  </span>
                  <span className="hidden w-20 shrink-0 text-right text-[11px] text-text-muted sm:block">
                    {entry.avgLatencyMs != null ? `${entry.avgLatencyMs} ms` : ""}
                  </span>
                </div>
              );
            })}
            {providers.length > shown.length && (
              <p className="text-xs text-text-muted">+{providers.length - shown.length} more provider{providers.length - shown.length === 1 ? "" : "s"}</p>
            )}
          </div>
        )}

        <p className="text-[11px] text-text-muted">
          Uptime is built from recorded request outcomes. Providers without traffic fall back to the last connection test health.
        </p>
      </div>
    </Card>
  );
}
