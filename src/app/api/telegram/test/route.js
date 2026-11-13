import { NextResponse } from "next/server";
import { getSettings } from "@/lib/localDb";
import { normalizeTelegramConfig } from "@/lib/telegram/config.js";
import { sendTelegramMessage, getTelegramMe } from "@/lib/telegram/api.js";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// POST /api/telegram/test - { chatId? } send a confirmation message
export async function POST(request) {
  let body = {};
  try {
    body = await request.json();
  } catch {
    body = {};
  }

  try {
    const cfg = normalizeTelegramConfig(await getSettings());
    if (!cfg.botToken) {
      return NextResponse.json({ ok: false, error: "Save a bot token first." }, { status: 400 });
    }

    const chatId = String(body.chatId || cfg.allowedChatIds[0] || "").trim();
    if (!chatId) {
      return NextResponse.json(
        {
          ok: false,
          error:
            "No chat ID yet. Message the bot once (it replies with your chat ID), then add it to the allow-list.",
        },
        { status: 400 }
      );
    }

    const bot = await getTelegramMe(cfg.botToken).catch(() => null);
    const text = [
      "✅ 9Router Telegram bridge is connected.",
      bot?.username ? `Bot: @${bot.username}` : "",
      "Send a message to chat through your proxy, or /help for commands.",
    ]
      .filter(Boolean)
      .join("\n");

    await sendTelegramMessage(cfg.botToken, chatId, text);
    return NextResponse.json({ ok: true, chatId, bot });
  } catch (error) {
    return NextResponse.json(
      { ok: false, error: error?.message || "Failed to send test message" },
      { status: 502 }
    );
  }
}