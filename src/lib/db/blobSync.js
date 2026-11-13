import fs from "node:fs";
import { DATA_FILE, ensureDirs } from "./paths.js";

const BLOB_PATHNAME = "data.sqlite";

let currentEtag = null;
let lastCheckTime = 0;
const CHECK_INTERVAL_MS = 1000;

export function hasBlobStorage() {
  return Boolean(process.env.BLOB_READ_WRITE_TOKEN);
}

export function getCurrentEtag() {
  return currentEtag;
}

function token() {
  return process.env.BLOB_READ_WRITE_TOKEN;
}

/**
 * Fetch the remote blob metadata with head() (strongly consistent), unlike
 * list() which is eventually consistent and can report a stale etag right
 * after another instance uploads a newer database.
 */
async function getRemoteMeta() {
  const { head } = await import("@vercel/blob");
  try {
    return await head(BLOB_PATHNAME, { token: token() });
  } catch (err) {
    // head() throws a plain Error ("The requested blob does not exist") for a
    // missing pathname instead of BlobNotFoundError.
    if (/does not exist|not found/i.test(err?.message || "")) return null;
    throw err;
  }
}

/** True when a remote database exists (used to decide whether seeding is safe). */
export async function remoteDbExists() {
  if (!hasBlobStorage()) return false;
  try {
    return (await getRemoteMeta()) !== null;
  } catch {
    // On a failed probe assume it exists: pushing local state could wipe it.
    return true;
  }
}

async function downloadBlob(url) {
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token()}` },
  });
  if (!res.ok) {
    console.warn(`[BlobSync] Failed to download blob: HTTP ${res.status}`);
    return null;
  }
  return Buffer.from(await res.arrayBuffer());
}

/**
 * Download data.sqlite from Vercel Blob store if present.
 * Returns true if a usable local copy exists afterwards, false otherwise.
 */
export async function pullFromBlob() {
  if (!hasBlobStorage()) return false;

  try {
    const remote = await getRemoteMeta();
    if (!remote) {
      console.log("[BlobSync] No existing data.sqlite in Blob storage.");
      return false;
    }

    if (currentEtag && remote.etag === currentEtag && fs.existsSync(DATA_FILE)) {
      return true;
    }

    const buf = await downloadBlob(remote.url);
    if (!buf) return false;

    ensureDirs();
    fs.writeFileSync(DATA_FILE, buf);
    currentEtag = remote.etag;
    lastCheckTime = Date.now();
    console.log(`[BlobSync] Pulled ${buf.length} bytes from Blob storage (etag: ${remote.etag})`);
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
    const putRes = await put(BLOB_PATHNAME, buf, {
      access: "private",
      addRandomSuffix: false,
      allowOverwrite: true,
      token: token(),
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
 * Pass { force: true } to bypass the throttle (used before writes and on the
 * provider list endpoint, where showing a stale list right after an add is
 * exactly the bug users see).
 */
export async function refreshFromBlobIfUpdated(getAdapterInstance, reinitAdapter, options = {}) {
  if (!hasBlobStorage()) return false;

  const now = Date.now();
  if (!options.force && now - lastCheckTime < CHECK_INTERVAL_MS) return false;
  lastCheckTime = now;

  try {
    const remote = await getRemoteMeta();
    if (!remote) return false;

    if (currentEtag && remote.etag === currentEtag) {
      return false;
    }

    console.log(`[BlobSync] Detected newer blob in remote storage (${remote.etag} vs ${currentEtag}), reloading...`);
    const buf = await downloadBlob(remote.url);
    if (!buf) return false;

    const currentAdapter = getAdapterInstance ? getAdapterInstance() : null;
    if (currentAdapter && typeof currentAdapter.close === "function") {
      try {
        currentAdapter.close();
      } catch {}
    }

    ensureDirs();
    fs.writeFileSync(DATA_FILE, buf);
    currentEtag = remote.etag;

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
