import { NextResponse } from "next/server";
import { getSettings } from "@/lib/localDb";
import { normalizeTelegramConfig } from "@/lib/telegram/config.js";
import { checkQuotaAndNotify } from "@/lib/telegram/quota.js";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

async function isAuthorized(request) {
  // Vercel Cron sends this header on scheduled invocations.
  if (request.headers.get("x-vercel-cron")) return true;

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

async function run(request) {
  if (!(await isAuthorized(request))) {
    return NextResponse.json({ ok: false, error: "Unauthorized" }, { status: 401 });
  }
  try {
    const summary = await checkQuotaAndNotify();
    return NextResponse.json({ ok: true, ...summary });
  } catch (error) {
    console.warn("[telegram] cron failed:", error?.message || error);
    return NextResponse.json({ ok: false, error: error?.message || "Cron failed" }, { status: 500 });
  }
}

export async function GET(request) {
  return run(request);
}

export async function POST(request) {
  return run(request);
}