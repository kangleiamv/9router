import crypto from "node:crypto";
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

/** ETags come back quoted and sometimes weakly prefixed (`W/"abc"`). */
function normalizeEtag(value) {
  return String(value || "")
    .replace(/^W\//, "")
    .replace(/"/g, "");
}

/**
 * Fetch the remote blob metadata with head() (strongly consistent), unlike
 * list() which is eventually consistent and can report a stale etag right
 * after another instance uploads a newer database.
 */
async function getRemoteMeta() {
  const { head } = await import("@vercel/blob");
  try {
    const meta = await head(BLOB_PATHNAME, { token: token() });
    return { ...meta, etag: normalizeEtag(meta.etag) };
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

function md5(buf) {
  return crypto.createHash("md5").update(buf).digest("hex");
}

function isSqlite(buf) {
  return buf.length > 100 && buf.subarray(0, 15).toString("utf8") === "SQLite format 3";
}

/**
 * Download the newest database bytes and verify them.
 *
 * Blob downloads are served by the CDN under a stable URL. Right after an
 * overwrite, that URL keeps returning the previous body for a while even
 * though head() already reports the new etag — which made a just-added
 * connection invisible (and could later be pushed back over the new data).
 *
 * get(..., { useCache: false }) sends `?cache=0`, which bypasses the CDN and
 * reads straight from origin. Vercel Blob etags are the MD5 of the body, so
 * we also verify the bytes we got really are the version we think they are.
 */
async function downloadBlob() {
  const { get } = await import("@vercel/blob");

  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const res = await get(BLOB_PATHNAME, {
        access: "private",
        token: token(),
        useCache: false,
      });

      if (!res || res.statusCode !== 200 || !res.stream) {
        console.warn(
          `[BlobSync] Failed to download blob (status ${res?.statusCode ?? "none"}, attempt ${attempt})`
        );
        continue;
      }

      const chunks = [];
      for await (const chunk of res.stream) {
        chunks.push(Buffer.from(chunk));
      }
      const buf = Buffer.concat(chunks);

      const etag = normalizeEtag(res.blob?.etag);
      const actual = md5(buf);
      if (etag && actual !== etag) {
        console.warn(`[BlobSync] Blob hash mismatch (${actual} != ${etag}); discarding download`);
        continue;
      }
      if (!isSqlite(buf)) {
        console.warn("[BlobSync] Downloaded data is not a SQLite database; discarding download");
        continue;
      }

      return { buf, etag: etag || actual };
    } catch (err) {
      console.warn(`[BlobSync] Download attempt ${attempt} failed: ${err?.message || err}`);
    }
  }

  return null;
}

/**
 * Download data.sqlite from Vercel Blob store if present.
 * Returns true if a usable local copy exists afterwards, false otherwise.
 */
export async function pullFromBlob() {
  if (!hasBlobStorage()) return false;

  // Let metadata/download errors propagate: initAdapter needs to distinguish
  // "no remote database yet" (safe to seed) from "remote exists but could not
  // be read" (must not overwrite it with an empty local database).
  const remote = await getRemoteMeta();
  if (!remote) {
    console.log("[BlobSync] No existing data.sqlite in Blob storage.");
    return false;
  }

  if (currentEtag && remote.etag === currentEtag && fs.existsSync(DATA_FILE)) {
    return true;
  }

  const downloaded = await downloadBlob();
  if (!downloaded) {
    throw new Error("remote database exists but download failed");
  }

  ensureDirs();
  fs.writeFileSync(DATA_FILE, downloaded.buf);
  currentEtag = downloaded.etag;
  lastCheckTime = Date.now();
  console.log(
    `[BlobSync] Pulled ${downloaded.buf.length} bytes from Blob storage (etag: ${downloaded.etag})`
  );
  return true;
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

    currentEtag = normalizeEtag(putRes.etag);
    lastCheckTime = Date.now();
    console.log(`[BlobSync] Pushed ${buf.length} bytes to Blob storage (etag: ${currentEtag})`);
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
    const downloaded = await downloadBlob();
    if (!downloaded) return false;

    const currentAdapter = getAdapterInstance ? getAdapterInstance() : null;
    if (currentAdapter && typeof currentAdapter.close === "function") {
      try {
        currentAdapter.close();
      } catch {}
    }

    ensureDirs();
    fs.writeFileSync(DATA_FILE, downloaded.buf);
    // Use the etag of the bytes we actually verified, not the one from the
    // earlier head(): another instance may have written again in between.
    currentEtag = downloaded.etag;

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
    // Deliberately no freshness refresh here: this runs after a mutation, and
    // swapping in the remote snapshot now would erase the row just written.
    const { getAdapterNoRefresh, isBlobSyncBlocked } = await import("./driver.js");
    if (isBlobSyncBlocked()) {
      console.warn(
        "[BlobSync] Push skipped: local DB was never synchronized with remote storage"
      );
      return false;
    }
    const adapter = await getAdapterNoRefresh();
    return await pushToBlob(adapter);
  } catch (err) {
    console.error("[BlobSync] flushBlobSync error:", err?.message || err);
    return false;
  }
}
