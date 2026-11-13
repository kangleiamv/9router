import { ensureDirs, DATA_FILE } from "./paths.js";
import { stripNullBytes } from "./helpers/nullBytes.js";
import { pullFromBlob, pushToBlob, refreshFromBlobIfUpdated, hasBlobStorage, remoteDbExists } from "./blobSync.js";

// Use global to survive Next.js dev hot-reload (module state resets on reload)
if (!global._dbAdapter) {
  global._dbAdapter = {
    instance: null,
    initPromise: null,
    logged: false,
    blobSyncBlocked: false,
  };
}
const state = global._dbAdapter;
if (state.blobSyncBlocked === undefined) state.blobSyncBlocked = false;

async function tryBunSqlite() {
  // Bun runtime only — built-in, no install needed
  if (!process.versions.bun) return null;
  try {
    const { createBunSqliteAdapter } = await import("./adapters/bunSqliteAdapter.js");
    return await createBunSqliteAdapter(DATA_FILE);
  } catch (e) {
    console.warn(`[DB] bun:sqlite unavailable: ${e.message}`);
    return null;
  }
}

async function tryBetterSqlite() {
  // Skip on Bun — better-sqlite3 native bindings unsupported
  if (process.versions.bun) return null;
  // Skip on Node >= 24: the native addon SIGSEGVs on load there, which is a
  // process-level crash the try/catch below cannot recover from. node:sqlite covers it.
  const [nodeMajor] = process.versions.node.split(".").map(Number);
  if (nodeMajor >= 24) return null;
  try {
    const { createBetterSqliteAdapter } = await import("./adapters/betterSqliteAdapter.js");
    return createBetterSqliteAdapter(DATA_FILE);
  } catch (e) {
    console.warn(`[DB] better-sqlite3 unavailable: ${e.message}`);
    return null;
  }
}

async function tryNodeSqlite() {
  // Built-in since Node 22.5.0 — no install needed. Skip under Bun (no node:sqlite).
  if (process.versions.bun) return null;
  const [maj, min] = process.versions.node.split(".").map(Number);
  if (maj < 22 || (maj === 22 && min < 5)) return null;
  try {
    const { createNodeSqliteAdapter } = await import("./adapters/nodeSqliteAdapter.js");
    return await createNodeSqliteAdapter(DATA_FILE);
  } catch (e) {
    console.warn(`[DB] node:sqlite unavailable: ${e.message}`);
    return null;
  }
}

async function trySqlJs() {
  try {
    const { createSqlJsAdapter } = await import("./adapters/sqljsAdapter.js");
    return await createSqlJsAdapter(DATA_FILE);
  } catch (e) {
    console.warn(`[DB] sql.js unavailable: ${e.message}`);
    return null;
  }
}

/**
 * Make every bound string NUL-safe, once, for all drivers.
 *
 * SQLite stores TEXT as a NUL-terminated C string, so a bound string with an
 * embedded NUL is rejected by better-sqlite3 ("String contains null byte") and
 * silently truncated by node:sqlite. Values that carry raw bytes — a model id
 * or endpoint from the request, tool output, a diff — can contain one, and the
 * write then either aborts the flush worker or loses the tail without a
 * trace. Sanitising at the binding boundary covers every driver and every
 * column type with a single rule, instead of one call site at a time.
 *
 * @param {object} adapter
 * @returns {object} adapter with NUL-safe run/get/all
 */
function withNullByteSafeParams(adapter) {
  const clean = (params) => (Array.isArray(params) ? params.map(stripNullBytes) : params);
  return {
    ...adapter,
    run: (sql, params = []) => adapter.run(sql, clean(params)),
    get: (sql, params = []) => adapter.get(sql, clean(params)),
    all: (sql, params = []) => adapter.all(sql, clean(params)),
  };
}

async function initAdapter() {
  ensureDirs();
  let pulled = false;
  let pullFailed = false;
  if (hasBlobStorage()) {
    try {
      pulled = await pullFromBlob();
    } catch (e) {
      pullFailed = true;
      console.warn(`[DB] pullFromBlob failed: ${e?.message}`);
    }
  }

  // Order per runtime:
  //   Bun:  bun:sqlite → sql.js
  //   Node: better-sqlite3 → node:sqlite (≥22.5) → sql.js
  let adapter = await tryBunSqlite();
  if (!adapter) adapter = await tryBetterSqlite();
  if (!adapter) adapter = await tryNodeSqlite();
  if (!adapter) adapter = await trySqlJs();
  if (!adapter) throw new Error("[DB] No SQLite driver available (bun/better/node/sql.js all failed)");

  adapter = withNullByteSafeParams(adapter);

  if (!state.logged) {
    console.log(`[DB] Driver: ${adapter.driver} | file: ${DATA_FILE}`);
    state.logged = true;
  }

  const { runMigrationOnce } = await import("./migrate.js");
  await runMigrationOnce(adapter);

  // Seed the remote store only when it does not exist yet. Pushing on every
  // cold start lets a stale instance clobber newer data written by another
  // instance (last-write-wins), which is exactly how connections vanished.
  // Also never seed after a failed pull: a transient download error must not
  // overwrite a remote database that is still there.
  if (hasBlobStorage()) {
    const remoteExists = await remoteDbExists();
    if (!pulled && !remoteExists) {
      try {
        await pushToBlob(adapter);
      } catch (e) {
        console.warn(`[DB] initial pushToBlob failed: ${e?.message}`);
      }
    } else if (!pulled && remoteExists) {
      // The local DB is empty but the remote still holds the user's data.
      // Block pushes until a later refresh succeeds, otherwise one write
      // would replace the remote database with this empty snapshot.
      state.blobSyncBlocked = true;
      console.warn(
        `[DB] Remote DB exists but could not be pulled${pullFailed ? " (download error)" : ""}; blocking pushes until refresh succeeds`
      );
    }
  }

  return adapter;
}

let refreshing = false;

/**
 * Force a remote freshness check and reload the DB when another instance has
 * written a newer snapshot. Used by the provider list endpoint and other
 * read-after-write paths where a stale view is the visible bug.
 */
export async function refreshNow() {
  if (!state.instance || !hasBlobStorage() || refreshing) return false;
  refreshing = true;
  try {
    const refreshed = await refreshFromBlobIfUpdated(
      () => state.instance,
      async () => {
        state.instance = null;
        state.initPromise = null;
        await getAdapter();
      },
      { force: true }
    );
    if (refreshed) state.blobSyncBlocked = false;
    return refreshed;
  } finally {
    refreshing = false;
  }
}

export async function getAdapter() {
  if (state.instance) {
    if (hasBlobStorage()) {
      const refreshed = await refreshFromBlobIfUpdated(
        () => state.instance,
        async () => {
          state.instance = null;
          state.initPromise = null;
          await getAdapter();
        }
      );
      if (refreshed) state.blobSyncBlocked = false;
    }
    return state.instance;
  }
  if (!state.initPromise) state.initPromise = initAdapter().then((a) => { state.instance = a; return a; });
  return state.initPromise;
}

export function isBlobSyncBlocked() {
  return state.blobSyncBlocked === true;
}

/**
 * Return the current adapter without checking Blob freshness.
 *
 * Write paths call this when flushing: a refresh here would be destructive,
 * because it closes the adapter and replaces the local file with the remote
 * snapshot *after* the row was inserted, then uploads that snapshot — losing
 * the write the caller just made.
 */
export async function getAdapterNoRefresh() {
  if (state.instance) return state.instance;
  if (!state.initPromise) {
    state.initPromise = initAdapter().then((a) => {
      state.instance = a;
      return a;
    });
  }
  return state.initPromise;
}

export function getAdapterSync() {
  if (!state.instance) throw new Error("[DB] adapter not initialized — await getAdapter() first");
  return state.instance;
}
