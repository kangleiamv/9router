import fs from "node:fs";
import { DATA_FILE, ensureDirs } from "./paths.js";

let currentEtag = null;
let lastCheckTime = 0;
const CHECK_INTERVAL_MS = 2000;
let pendingPush = null;

export function hasBlobStorage() {
  return Boolean(process.env.BLOB_READ_WRITE_TOKEN);
}

export function getCurrentEtag() {
  return currentEtag;
}

/**
 * Download data.sqlite from Vercel Blob store if present.
 * Returns true if downloaded, false otherwise.
 */
export async function pullFromBlob() {
  if (!hasBlobStorage()) return false;

  try {
    const { list } = await import("@vercel/blob");
    const listRes = await list({
      prefix: "data.sqlite",
      token: process.env.BLOB_READ_WRITE_TOKEN,
    });

    const blob = listRes.blobs.find((b) => b.pathname === "data.sqlite");
    if (!blob) {
      console.log("[BlobSync] No existing data.sqlite in Blob storage.");
      return false;
    }

    if (currentEtag && blob.etag === currentEtag && fs.existsSync(DATA_FILE)) {
      return true;
    }

    const res = await fetch(blob.url, {
      headers: {
        Authorization: `Bearer ${process.env.BLOB_READ_WRITE_TOKEN}`,
      },
    });

    if (!res.ok) {
      console.warn(`[BlobSync] Failed to download blob: HTTP ${res.status}`);
      return false;
    }

    const buf = Buffer.from(await res.arrayBuffer());
    ensureDirs();
    fs.writeFileSync(DATA_FILE, buf);
    currentEtag = blob.etag;
    lastCheckTime = Date.now();
    console.log(`[BlobSync] Pulled ${buf.length} bytes from Blob storage (${blob.pathname}, etag: ${blob.etag})`);
    return true;
  } catch (err) {
    console.error("[BlobSync] pullFromBlob error:", err?.message || err);
    return false;
  }
}

/**
 * Upload local data.sqlite to Vercel Blob store.
 */
export async function pushToBlob(adapter) {
  if (!hasBlobStorage()) return false;

  try {
    // Checkpoint SQLite so WAL pages are committed into the main database file
    if (adapter && typeof adapter.checkpoint === "function") {
      adapter.checkpoint();
    } else if (adapter && typeof adapter.exec === "function") {
      try {
        adapter.exec("PRAGMA wal_checkpoint(TRUNCATE)");
      } catch {}
    }

    if (!fs.existsSync(DATA_FILE)) {
      return false;
    }

    const buf = fs.readFileSync(DATA_FILE);
    if (!buf || buf.length === 0) {
      return false;
    }

    const { put } = await import("@vercel/blob");
    const putRes = await put("data.sqlite", buf, {
      access: "private",
      addRandomSuffix: false,
      allowOverwrite: true,
      token: process.env.BLOB_READ_WRITE_TOKEN,
    });

    currentEtag = putRes.etag;
    lastCheckTime = Date.now();
    console.log(`[BlobSync] Pushed ${buf.length} bytes to Blob storage (etag: ${putRes.etag})`);
    return true;
  } catch (err) {
    console.error("[BlobSync] pushToBlob error:", err?.message || err);
    return false;
  }
}

/**
 * Check if remote Blob storage has a newer version, and reload SQLite if needed.
 */
export async function refreshFromBlobIfUpdated(getAdapterInstance, reinitAdapter) {
  if (!hasBlobStorage()) return false;

  const now = Date.now();
  if (now - lastCheckTime < CHECK_INTERVAL_MS) return false;
  lastCheckTime = now;

  try {
    const { list } = await import("@vercel/blob");
    const listRes = await list({
      prefix: "data.sqlite",
      token: process.env.BLOB_READ_WRITE_TOKEN,
    });

    const blob = listRes.blobs.find((b) => b.pathname === "data.sqlite");
    if (!blob) return false;

    if (currentEtag && blob.etag === currentEtag) {
      return false;
    }

    console.log(`[BlobSync] Detected newer blob in remote storage (${blob.etag} vs ${currentEtag}), reloading...`);
    const res = await fetch(blob.url, {
      headers: {
        Authorization: `Bearer ${process.env.BLOB_READ_WRITE_TOKEN}`,
      },
    });

    if (!res.ok) return false;

    const buf = Buffer.from(await res.arrayBuffer());

    const currentAdapter = getAdapterInstance ? getAdapterInstance() : null;
    if (currentAdapter && typeof currentAdapter.close === "function") {
      try {
        currentAdapter.close();
      } catch {}
    }

    ensureDirs();
    fs.writeFileSync(DATA_FILE, buf);
    currentEtag = blob.etag;

    if (reinitAdapter) {
      await reinitAdapter();
    }
    console.log("[BlobSync] Database reloaded successfully from Blob storage.");
    return true;
  } catch (err) {
    console.error("[BlobSync] refreshFromBlobIfUpdated error:", err?.message || err);
    return false;
  }
}

/**
 * Flush any pending write to Blob storage immediately.
 */
export async function flushBlobSync() {
  if (!hasBlobStorage()) return false;

  try {
    const { getAdapter } = await import("./driver.js");
    const adapter = await getAdapter();
    return await pushToBlob(adapter);
  } catch (err) {
    console.error("[BlobSync] flushBlobSync error:", err?.message || err);
    return false;
  }
}
