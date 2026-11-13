import { NextResponse } from "next/server";
import { testSingleConnection } from "./testUtils.js";
import { getProviderConnectionById } from "@/lib/localDb";
import { notifyConnectionError } from "@/lib/telegram/notify.js";

// POST /api/providers/[id]/test - Test connection
export async function POST(request, { params }) {
  try {
    const { id } = await params;
    const result = await testSingleConnection(id);

    if (result.error === "Connection not found") {
      return NextResponse.json({ error: "Connection not found" }, { status: 404 });
    }

    // Telegram alert on failure (fail-open; cooldown inside the notifier).
    if (!result.valid && result.error) {
      try {
        const connection = await getProviderConnectionById(id);
        await notifyConnectionError(connection || { id }, result.error);
      } catch {
        // Never let a notification affect the test result.
      }
    }

    return NextResponse.json({
      valid: result.valid,
      error: result.error,
      refreshed: result.refreshed || false,
    });
  } catch (error) {
    console.log("Error testing connection:", error);
    return NextResponse.json({ error: "Test failed" }, { status: 500 });
  }
}
