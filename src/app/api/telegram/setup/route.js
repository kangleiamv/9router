import { NextResponse } from "next/server";
import crypto from "node:crypto";
import { getSettings, updateSettings } from "@/lib/localDb";
import { normalizeTelegramConfig } from "@/lib/telegram/config.js";
import {
  getTelegramMe,
  getTelegramWebhookInfo,
  setTelegramWebhook,
  deleteTelegramWebhook,
  setTelegramCommands,
} from "@/lib/telegram/api.js";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const WEBHOOK_PATH = "/api/telegram/webhook";

function resolveBaseUrl(request) {
  const explicit =
    process.env.TELEGRAM_PUBLIC_URL ||
    process.env.NEXT_PUBLIC_APP_URL ||
    (process.env.VERCEL_PROJECT_PRODUCTION_URL
      ? `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}`
      : "");
  const base = explicit || new URL(request.url).origin;
  return base.replace(/\/+$/, "");
}

function newWebhookSecret() {
  return crypto.randomBytes(32).toString("hex");
}

async function persistTelegram(partial) {
  const settings = await getSettings();
  const merged = { ...normalizeTelegramConfig(settings), ...partial };
  const updated = await updateSettings({ telegram: merged });
  return normalizeTelegramConfig({ telegram: updated.telegram });
}

async function readBody(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

// GET /api/telegram/setup - current bot + webhook status
export async function GET(request) {
  return handle({ request, body: { action: "status" } });
}

// POST /api/telegram/setup - { action: "status" | "connect" | "disconnect" | "commands" }
export async function POST(request) {
  const body = await readBody(request);
  return handle({ request, body });
}

async function handle({ request, body }) {
  const action = String(body.action || "status").toLowerCase();

  try {
    let cfg = normalizeTelegramConfig(await getSettings());
    if (!cfg.botToken) {
      return NextResponse.json(
        { ok: false, error: "Save a bot token first (get one from @BotFather)." },
        { status: 400 }
      );
    }

    if (action === "connect") {
      const baseUrl = resolveBaseUrl(request);
      const secret = cfg.webhookSecret || newWebhookSecret();
      const webhookUrl = `${baseUrl}${WEBHOOK_PATH}`;

      // Persist the secret + enabled flag BEFORE registering the webhook: once
      // Telegram starts posting, the route must already know the secret.
      cfg = await persistTelegram({ enabled: true, webhookSecret: secret });

      await setTelegramWebhook(cfg.botToken, webhookUrl, secret);
      await setTelegramCommands(cfg.botToken).catch((error) => {
        console.warn("[telegram] setMyCommands failed:", error?.message || error);
      });

      const [bot, webhook] = await Promise.all([
        getTelegramMe(cfg.botToken).catch(() => null),
        getTelegramWebhookInfo(cfg.botToken).catch(() => null),
      ]);

      return NextResponse.json({ ok: true, bot, webhook, webhookUrl, enabled: true });
    }

    if (action === "disconnect") {
      await deleteTelegramWebhook(cfg.botToken).catch((error) => {
        console.warn("[telegram] deleteWebhook failed:", error?.message || error);
      });
      await persistTelegram({ enabled: false, webhookSecret: "" });
      return NextResponse.json({ ok: true, enabled: false });
    }

    if (action === "commands") {
      await setTelegramCommands(cfg.botToken);
      return NextResponse.json({ ok: true });
    }

    if (action === "status") {
      const [bot, webhook] = await Promise.all([
        getTelegramMe(cfg.botToken),
        getTelegramWebhookInfo(cfg.botToken),
      ]);
      return NextResponse.json({
        ok: true,
        bot,
        webhook,
        enabled: cfg.enabled,
        expectedUrl: `${resolveBaseUrl(request)}${WEBHOOK_PATH}`,
        allowedChatIds: cfg.allowedChatIds,
        hasSecret: !!cfg.webhookSecret,
      });
    }

    return NextResponse.json({ ok: false, error: `Unknown action "${action}"` }, { status: 400 });
  } catch (error) {
    console.warn("[telegram] setup failed:", error?.message || error);
    return NextResponse.json({ ok: false, error: error?.message || "Telegram request failed" }, { status: 502 });
  }
}