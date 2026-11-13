import { NextResponse } from "next/server";
import { getSettings } from "@/lib/localDb";
import { normalizeTelegramConfig } from "@/lib/telegram/config.js";
import { checkQuotaAndNotify } from "@/lib/telegram/quota.js";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

async function isAuthorized(request) {
  // Vercel cron invocations always carry the schedule header; older runtimes
  // also send `x-vercel-cron`. Both are client-controlled, so this route is
  // only a cheap notifier trigger: it does nothing unless the bot is enabled,
  // and run() enforces a server-side throttle.
  if (request.headers.get("x-vercel-cron-schedule") || request.headers.get("x-vercel-cron")) {
    return true;
  }

  const cronSecret = process.env.CRON_SECRET;
  if (cronSecret) {
    const auth = request.headers.get("authorization") || "";
    if (auth === `Bearer ${cronSecret}`) return true;
  }

  // Also allow the webhook secret so the job can be triggered manually with curl.
  try {
    const cfg = normalizeTelegramConfig(await getSettings());
    const provided = request.headers.get("x-telegram-bot-api-secret-token") || "";
    if (cfg.webhookSecret && provided === cfg.webhookSecret) return true;
  } catch {
    // Settings unavailable — deny below.
  }

  return false;
}

// Bounds the impact of a spoofed cron header on the shared instance.
const MIN_RUN_INTERVAL_MS = 5 * 60 * 1000;
let lastRunAt = 0;
let running = false;

async function run(request) {
  if (!(await isAuthorized(request))) {
    return NextResponse.json({ ok: false, error: "Unauthorized" }, { status: 401 });
  }
  if (running) return NextResponse.json({ ok: true, skipped: "running" });
  if (Date.now() - lastRunAt < MIN_RUN_INTERVAL_MS) {
    return NextResponse.json({ ok: true, skipped: "throttled" });
  }
  running = true;
  lastRunAt = Date.now();
  try {
    const summary = await checkQuotaAndNotify();
    return NextResponse.json({ ok: true, ...summary });
  } catch (error) {
    console.warn("[telegram] cron failed:", error?.message || error);
    return NextResponse.json({ ok: false, error: error?.message || "Cron failed" }, { status: 500 });
  } finally {
    running = false;
  }
}

export async function GET(request) {
  return run(request);
}

export async function POST(request) {
  return run(request);
}