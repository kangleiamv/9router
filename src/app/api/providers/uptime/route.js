import { NextResponse } from "next/server";
import { getProviderConnections } from "@/models";
import { AI_PROVIDERS } from "@/shared/constants/providers";
import { getProviderUptime } from "@/lib/db/repos/uptimeRepo.js";

export const dynamic = "force-dynamic";

const WINDOWS = {
  "1h": 60 * 60 * 1000,
  "24h": 24 * 60 * 60 * 1000,
  "7d": 7 * 24 * 60 * 60 * 1000,
  "30d": 30 * 24 * 60 * 60 * 1000,
};

// GET /api/providers/uptime?window=24h
// Uptime from recorded upstream outcomes, with connection test health as the
// fallback for providers that have no traffic in the window.
export async function GET(request) {
  try {
    const { searchParams } = new URL(request.url);
    const requested = searchParams.get("window") || "24h";
    const windowKey = WINDOWS[requested] ? requested : "24h";

    const connections = await getProviderConnections({ isActive: true });
    const result = await getProviderUptime({
      windowMs: WINDOWS[windowKey],
      connections,
    });

    const providers = result.providers.map((entry) => ({
      ...entry,
      name: AI_PROVIDERS[entry.provider]?.name || entry.provider,
    }));

    return NextResponse.json({
      window: windowKey,
      generatedAt: new Date().toISOString(),
      ...result,
      providers,
    });
  } catch (error) {
    console.log("Error fetching provider uptime:", error);
    return NextResponse.json({ error: "Failed to fetch provider uptime" }, { status: 500 });
  }
}
