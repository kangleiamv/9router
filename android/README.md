# 9Router for Android

Native Android port of the 9Router gateway. Kotlin + Jetpack Compose + Material 3,
an embedded Ktor CIO server on loopback, Room storage and OkHttp upstream calls.
There is **no WebView** and no bundled web build: every screen is a Compose
implementation of the dashboard, and all routing logic is ported to Kotlin.

## What is inside

| Area | Implementation |
| --- | --- |
| Gateway | Ktor CIO on `127.0.0.1:20127`, OpenAI-compatible `/v1` API |
| Storage | Room (`nine_router.db`), keys encrypted with Android Keystore AES-GCM |
| Upstreams | OpenAI-compatible, Anthropic, Gemini, Gemini CLI, Antigravity and Ollama wire formats |
| Providers | Full web registry (all visible providers, including transports that are not native yet), bundled provider logos, searchable provider directory |
| Model discovery | Per-provider live model fetch (OpenAI/Anthropic/Gemini/Ollama/Code Assist/Antigravity), cached on-device and merged into pickers, combos and `/v1/models` |
| Custom providers | User-defined OpenAI- or Anthropic-compatible nodes with a model prefix, model discovery and API-key connections |
| OAuth | Browser sign-in for Antigravity, Gemini CLI and Claude Code with a one-shot loopback callback, PKCE for Claude, automatic token refresh |
| Routing | Combos, vision combo, per-account fallback, exponential backoff, model locks |
| Telegram | Bot API long polling inside the foreground service, `/help` `/model` `/combo` `/vision` `/status` `/quota` `/clear` |
| Cloudflare Tunnel | Optional public endpoint via a bundled native `cloudflared` connector: quick `trycloudflare.com` URLs or named Zero Trust tunnels, plus local links that each choose their own quick connector or the shared named tunnel; HTTP apps, an optional on-device FTP host with a browser file view, and named TCP routes for databases and SSH |
| Hosting | Supervised on-device PHP, Node.js, Python, Redis, PostgreSQL and MySQL runtimes on loopback, with app-owned folders, generated credentials, log capture, restarts and one-tap publishing to a local link |
| UI | Compose Material 3, Obsidian dark theme, drawer navigation |
| Background | Foreground service + boot receiver (auto-start optional) |

## Screens

- **Dashboard** — gateway state, base URL, active key, daily stats, and a flat recent-activity feed with provider logos, status badges and error summaries
- **Providers** — searchable directory with logos, connections, priority order, test, live model fetch, edit, pause, delete, plus OpenAI/Anthropic compatible custom endpoints
- **Combo & Vision** — ordered fallback chains and the vision adapter
- **Endpoint & Key** — client credentials plus a copy-paste `curl` test
- **Usage** — animated 7-day/hourly charts, provider and status breakdowns, and a dedicated Overview / Request log switcher with a day-grouped, filterable request feed
- **Quota Tracker** — live provider limits (Antigravity, Gemini CLI, Claude, DeepSeek, Groq, GLM, OpenCode, Vercel AI Gateway, Ollama Cloud, MiniMax), animated gauges, reset countdowns, local usage fallback, and cooldown/model-lock tracking
- **Telegram Bot** — bot token, owner chat id, routing, notifications, commands
- **Cloudflare Tunnel** — quick or named tunnel, public URL with copy/open/retry, connector state and live log tail, an optional FTP host serving device storage, a hosting card that starts PHP/Node.js/Python/Redis/PostgreSQL/MySQL on loopback, and local links where every link chooses a dedicated Quick tunnel or the shared Named tunnel; the editor fills service presets and detects listening loopback ports
- **Console Log** — live day-grouped request inspector with status filters, collapsible search, expandable request/response payloads and one-tap copy
- **Settings** — port, auto-start, retention, storage, runtime info

## Build

The project targets JDK 17, AGP 8.10.1, Gradle 8.14.3, Kotlin 2.1.21, compileSdk 36
and minSdk 26.

### Locally

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

On an arm64 Linux host the x86_64 `aapt2` shipped by the Android Gradle plugin
cannot run. Pass a native arm64 build instead:

```bash
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/path/to/arm64/aapt2
```

### CI

`.github/workflows/android-apk.yml` builds on every change under `android/` and
uploads the debug APK as the `9router-android-debug` artifact (Actions → run →
Artifacts). No signing secrets are required for the debug build.

## Install

A debug-signed APK is published at
<https://github.com/kangleiamv/9router/releases/tag/android-v1.3.12>.

```bash
adb install -r 9router-android-1.3.12-debug.apk
```

Or download the artifact and open it on the device (allow install from unknown
sources for the file manager you use).

## Use

1. Open **Providers** and add a connection. For Antigravity, Gemini CLI or
   Claude Code tap **Sign in with Google/Claude**: the browser opens the
   provider's consent page and the app catches the loopback callback on
   `http://localhost:<port>/callback`. For any other provider paste an API key.
   Manual tokens (access token, or advanced JSON with `refreshToken` and
   `projectId`) still work as a fallback. Gemini CLI signs in even when Google
   has not provisioned a Code Assist project yet; the app resolves it on first
   use and retries onboarding when needed.
2. Optionally create a combo in **Combo & Vision** — members are tried in order.
3. The gateway starts automatically. Copy the base URL and an API key from
   **Endpoint & Key**.
4. From a local client on the device:

```bash
curl http://127.0.0.1:20127/v1/chat/completions \
  -H "Authorization: Bearer sk-9r-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"9vercel","messages":[{"role":"user","content":"hi"}]}'
```

From a desktop over USB:

```bash
adb forward tcp:20127 tcp:20127
curl http://127.0.0.1:20127/v1/models
```

### Antigravity 404: "Requested entity was not found"

Google returns this 404 both for a missing/stale Cloud Code project and for a
model that is not enabled for the account. The app handles it as follows:

- The Code Assist project is resolved at request time (or reused from the
  connection) and a missing project id is never sent as an empty string.
- If the upstream still answers with that exact 404, the project is refreshed
  once and the request is retried with the repaired id.
- **Test** retries the credential on `gemini-3.8-flash-medium` when the
  connection's selected model is unavailable, switches the connection default
  to the model that worked, and reports the change.
- Catalog tier suffixes such as `gemini-4-carbon-high(high)` are stripped before
  the wire request; the tier is sent as `thinkingConfig.thinkingLevel`, and the
  client uses the current `antigravity/hub/2.24.0` fingerprint.

If a client request still returns the 404, select `gemini-3.8-flash-medium` as
the model in that client (the model the web dashboard uses by default).

## Quota tracking

The **Quota Tracker** fetches each connection's own usage endpoint and renders
the returned windows as animated bars with reset countdowns:

- **Antigravity** — Cloud Code weekly/5h family summary, with a paid-tier
  per-model fallback when the summary is unavailable.
- **Gemini CLI** — per-model `retrieveUserQuota` buckets, tightest first.
- **Claude Code** — 5h, 7d and model-scoped weekly windows from the OAuth usage
  endpoint.
- **DeepSeek** — account balance.
- **Groq** — request and token rate-limit headers.
- **GLM / GLM-CN** — coding-plan token and credit limits.
- **OpenCode Go / Zen** — rolling, weekly and monthly windows.
- **Vercel AI Gateway** — remaining and used USD credit.
- **Ollama Cloud** — session, weekly, monthly and allowance windows.
- **MiniMax / MiniMax-CN** — 5h and weekly coding-plan limits.

Every connection keeps a card even when its provider has no public quota API.
That card shows today's request/token volume, a seven-day sparkline, rotation
status and any model locks, so providers never disappear from the tracker. The
screen refreshes automatically when opened and every two minutes afterwards;
the refresh-all button forces every connection, while each card has its own
refresh action. Snapshots are cached for 90 seconds to avoid upstream rate
limits.

## Provider directory and live models

The Providers screen is generated from the same registry as the web dashboard,
so every visible provider appears with its logo, category, website and static
model catalog. Providers whose transport is not implemented natively are shown
in the collapsed **More providers** section instead of being silently omitted.

For a provider that can route natively, tap **Models** on its connection card.
The app calls that provider's own model-listing endpoint (with the stored key or
OAuth token), caches the result in `files/provider_models.json`, and merges it
into:

- the connection's default-model picker and **Browse models** selector;
- **Combo & Vision** member choice;
- `/v1/models`;
- Telegram `/model` and `/combo`.

Refreshing is safe: static entries stay available as a fallback, and a failed
fetch only reports the HTTP error. Model discovery is implemented for OpenAI,
Anthropic, Gemini, Gemini CLI, Antigravity and Ollama-compatible endpoints,
including provider-specific URLs where the chat base URL is not enough.

## Custom providers (OpenAI/Anthropic compatible)

**Providers → Custom Providers (OpenAI/Anthropic Compatible)** creates a node
for any compatible endpoint:

1. Tap **Add OpenAI** or **Add Anthropic**.
2. Enter a name, a model prefix (for example `oc-prod`), and the base URL ending
   in `/v1`. The app appends `/chat/completions` for OpenAI and `/messages` for
   Anthropic.
3. Optionally enter an API key and tap **Check**. Checking first calls
   `<baseUrl>/models`; if that endpoint is missing, enter a model id and the app
   validates with a minimal chat request instead. On create, the key also
   becomes the first connection.
4. Add models manually in **Models**, or use **Fetch from /models**. The same
   screen lets you remove model ids.

Requests use the prefix as the provider namespace:

```bash
curl http://127.0.0.1:20127/v1/chat/completions \
  -H "Authorization: Bearer sk-9r-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"oc-prod/gpt-4o","messages":[{"role":"user","content":"hi"}]}'
```

Custom models are listed by `/v1/models`, in the combo picker and by `/model`
in Telegram. Deleting a custom node also removes its connections; update any
combo that referenced its prefix.

## Telegram

Create a bot with @BotFather, paste the token and your chat id in the Telegram
screen, enable the bridge and save. Polling runs in the foreground service; the
app removes a stale webhook automatically. The same engine handles bot messages.

Proactive alerts are sent to the owner chat when every connection for a model is
cooling down (toggle in the Telegram screen).

## Cloudflare Tunnel

The **Cloudflare Tunnel** screen exposes the gateway to the internet without a
separate Termux install. The tunnel is optional and off by default.

1. Open **Cloudflare Tunnel** and choose a mode:
   - **Quick tunnel** needs no account. Tap **Start** and wait for a
     `https://<random>.trycloudflare.com` URL. The URL changes every time the
     connector restarts.
   - **Named tunnel** needs a Zero Trust tunnel token from the Cloudflare
     dashboard (`Networks → Tunnels`). Paste the token, optionally enter the
     public hostname you configured there, and start the tunnel. The token is
     stored encrypted with the Android Keystore.
2. Copy the public URL and use it as the base URL in a remote OpenAI-compatible
   client, with the same 9Router API key shown in **Endpoint & Key**.
3. The screen shows connector state and the last log lines. Use **Retry** after
   a network change, or **Stop** to close the public listener.

The connector runs inside the proxy foreground service and starts automatically
when **tunnel_enabled** is set, including after boot if auto-start is enabled.

### Local links

The tunnel can publish more than the gateway. In **Local links** add one entry
per service running on the device, then choose how that link is published:

- **Quick link** — the link runs its own `cloudflared` connector and gets a
  unique `https://<random>.trycloudflare.com` URL at its root. No account is
  needed. Each quick link has its own process, state, log tail and retry action,
  so one failing connector does not affect the others. The URL changes whenever
  that connector restarts. Public path and hostname are ignored in this mode.
- **Named link** — the link is served by the shared named tunnel configured at
  the top of the screen (the gateway must also be in **Named** mode). It is
  matched by public hostname, by a path prefix on the main hostname, or both.
  Use this for stable custom domains from your Cloudflare account.

The editor starts with the link type, then offers the matching presets:

- **Web app** — PHP (`8000`), Node.js (`3000`), Python `http.server` (`8000`),
  Flask/Django (`5000`), Vite (`5173`), nginx (`8080`) and Apache (`8080`).
- **FTP** — an FTP server on `2121`, or **Use this device** to point at the
  built-in host described below.
- **TCP** — Redis (`6379`), PostgreSQL (`5432`), MySQL/MariaDB (`3306`),
  MongoDB (`27017`), SSH (`22`) and any custom port. TCP services are Native
  only: quick tunnels carry HTTP, so every TCP link gets a Named route.

Press **Detect** (or the refresh icon in the Local links header) to probe those
ports on loopback in parallel. Each detected service is listed with its name
and port, and **Use** fills the editor. Link rows also show whether their target
is currently listening. Scanning only opens short-lived TCP connections to
`127.0.0.1`. The app does not bundle PHP, Node.js, nginx, MySQL or any other
runtime: it publishes services already running (for example in Termux) or starts
them from the **Hosting** card when a runtime is available on the device.

Every link also has:

- **Local URL** — loopback only (`localhost`, `127.0.0.1` or `[::1]`) with the
  port your app listens on, for example `http://127.0.0.1:8080`. The gateway
  ports are blocked so a link cannot point back at 9Router.
- **Public path** (named links) — a prefix such as `/site` on the tunnel's main
  hostname. `/site` redirects to `/site/` so relative asset links resolve
  correctly.
- **Public hostname** (named links) — a hostname such as `site.example.com` that
  you add to the tunnel in Zero Trust. The screen shows the ingress target
  (`http://127.0.0.1:<publicPort>`) to point the hostname at.
- **Strip public path** (named links) — forwards `/site/page` as `/page`, which
  is what most sub-path deployments expect. Turn it off for apps that need the
  full prefix.
- **Enable switch** — temporarily stop a link without deleting it.

Quick links own their generated hostname completely, including `/v1`; they are
never mixed with the gateway API. Named links are matched by hostname first,
then by the longest matching path. Reserved gateway routes (`/v1`, `/models`,
`/chat`, `/api/health`) always go to 9Router, so a named link cannot shadow the
API. HTTP, SSE and WebSocket traffic are relayed without buffering, redirects are
passed to the browser instead of followed, and requests carry
`X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-Prefix`. At most 12
links are stored.

Edits apply immediately: saving, deleting or toggling a link reconciles only the
quick connectors that changed, without restarting the gateway connector or
changing its URL.

### FTP host on this device

**FTP host on this device** turns the phone into the FTP server, so a tunnel
link can publish it without any external process. It uses Apache FtpServer
inside the app, binds the control port to `127.0.0.1` only (default `2121`) and
uses loopback passive data ports `41000-41009`, so nothing is reachable from the
LAN. It starts and stops with the proxy foreground service.

- **Folder** — empty means the app-private external folder
  (`Android/data/ai.ninerouter/files/ftp`), which needs no permission. **App
  folder** selects it again.
- **Shared storage** — point the folder at `/storage/emulated/0` (or a
  subfolder) to serve Downloads, DCIM and documents. Android 11+ requires
  **All files access**; the card shows a **Grant file access** button that opens
  the system setting. Android 10 and older use the legacy read/write
  permissions instead.
- **Username / password** — blank username serves anonymous FTP; any password is
  accepted for anonymous, as FTP clients expect. A configured username requires
  the matching password. The password is stored with the Android Keystore.
- **Read-only** — clients can list and download but uploads, renames and deletes
  are refused. Writable folders are verified with a small write probe before the
  server starts.
- **Control port** — must be between `1024` and `65535`, cannot be a 9Router
  gateway port, and cannot fall in the passive range.

The server uses UTF-8, MLSD listings and passive mode. Every FTP link that
points at `ftp://127.0.0.1:<port>` gets the browser file view described below,
with access-key protection and the same read/write rules.

### FTP servers

Choose **FTP server** when adding a link to publish the built-in host or an FTP
server running on the device in a Termux session:

- **Local FTP URL** — plain FTP on loopback only, for example
  `ftp://127.0.0.1:2121`, with an optional base folder such as
  `ftp://127.0.0.1:2121/files`. FTPS/SFTP, credentials in the URL, non-loopback
  hosts and `..` segments are rejected.
- **FTP username / password** — optional; leave both blank for anonymous FTP.
  The password is stored with the Android Keystore and is only used inside the
  app. A base folder limits everything the link can reach.
- **Quick FTP** — Cloudflare quick tunnels carry HTTP only and the bundled
  `cloudflared` rejects `ftp://` origins, so a quick FTP link gets its own
  `https://<random>.trycloudflare.com` **file browser** instead: folder
  navigation, download, and upload up to 64 MB per file. The shown URL carries
  a random access key (`?k=...`); without it the page and upload form return
  401.
- **Named FTP** — the same browser works on the named hostname or path. For the
  real FTP protocol add a *separate* hostname in Zero Trust with service
  `tcp://127.0.0.1:<ftpPort>` and a Service Auth or WARP policy. The link row
  shows that ingress target and the client command
  `cloudflared access tcp --hostname <ftp-hostname> --url 127.0.0.1:<port>`.
  Native FTP is unavailable on quick tunnels, and one hostname cannot serve
  both HTTP and TCP, so the browser and native FTP always use different
  hostnames. Note that FTP opens a second connection for every transfer:
  a single Cloudflare TCP route forwards the control channel only, so most
  clients fail to list or transfer files through it. The access-key protected
  browser is the reliable way to move files through the tunnel.

Anyone with the browser URL and access key can list, download and change files,
so share FTP links as carefully as the server's own credentials.

### TCP services

Redis, PostgreSQL, MySQL, MongoDB, SSH and any other non-HTTP service cannot use
the HTTP browser or a quick tunnel. Add a **TCP** link instead:

- **Local TCP address** — loopback with a port, for example
  `tcp://127.0.0.1:6379`. A bare port such as `6379` means
  `127.0.0.1:6379`; paths and credentials are rejected.
- **TCP hostname** — a hostname you add to the same Zero Trust tunnel. Create a
  public hostname with service `tcp://127.0.0.1:<port>` in the dashboard, then
  attach a Service Auth or WARP policy.
- **Client command** — the link row (and the editor) shows
  `cloudflared access tcp --hostname <host> --url 127.0.0.1:<port>` plus the
  usual CLI for the detected service, such as `redis-cli`. Run the first command
  on your own machine, leave it running, and connect with the second. WARP
  clients can reach the hostname directly instead.

TCP links are always Named, never match HTTP requests, and are excluded from the
generated ingress summary because they do not point at the local HTTP router.


Only the OpenAI-compatible surface and the enabled local links are published:
`/v1/models`, `/v1/chat/completions`, their short aliases, quick-link hostnames
and named-link paths. Cloudflared connects to a separate loopback listener; the
management API, settings, keys, logs and Telegram endpoints stay reachable only
on the device. A local link can expose a service that has no authentication of
its own, so only publish apps you intend to share.

The bundled `cloudflared` is the Termux Android/arm64 build (2026.11.0),
executed directly from `nativeLibraryDir`. It is packaged for `arm64-v8a` only:
on other ABIs the rest of the app works, but the tunnel reports that the
connector is unavailable.

### Hosting runtimes

The **Hosting** card starts real PHP, Node.js, Python, Redis, PostgreSQL and
MySQL processes. Each service gets an app-owned folder under
`files/hosting/<id>` with `public` for web content, `data` for database files,
and `home`, `tmp` and `logs` beside them.

- **PHP** — `php -S 127.0.0.1:<port> -t <public>` plus a starter `index.php`.
- **Node.js** — `node server.js` with `PORT`, `HOST=127.0.0.1` and `DOCROOT`,
  plus a small static `server.js`. Set an entrypoint to run your own file.
- **Python** — `python3 -m http.server <port> --bind 127.0.0.1 --directory
  <public>`, or your own entrypoint such as `app.py`.
- **Redis** — AOF persistence in `data`, loopback bind and a generated
  `requirepass`.
- **PostgreSQL** — `initdb` with `scram-sha-256` on first start, then `postgres`
  on loopback. The generated password is shown while it runs.
- **MySQL / MariaDB** — `mariadb-install-db` once, then `mariadbd` bound to
  `127.0.0.1` with an `app` database and a generated user and password.

Processes bind loopback only and are supervised: output is captured (the card
shows the tail), an unexpected exit restarts up to three times with a backoff,
and stopping a service kills its process. **Start with 9Router** starts a
service together with the foreground service, and **Publish** opens the local
link editor prefilled with the loopback target: an HTTP link for web runtimes,
a Named TCP link for databases. Credentials are stored with the Android
Keystore. The hosting card validates ports against the gateway, public
listener, FTP host and passive range.

**Runtimes are not bundled.** 9Router looks for `php`, `node`, `python3`,
`redis-server`, `postgres`, `mariadbd`/`mysqld` and the matching setup tools in:

1. the APK native library directory (`libphp.so`, `libnode.so`, …), the only
   app location Android 10+ lets an app execute;
2. directories on `PATH`;
3. `files/hosting/bin` and `files/bin` (works on Android 9 and older; Android
   10+ blocks executing binaries copied into app storage).

Termux keeps its binaries in the Termux app sandbox, so 9Router cannot execute
them. Run the service in Termux as usual and publish it with a local link:
**Detect** finds the port, and the link editor fills the target.

## Notes and limits

- The management gateway binds to loopback only. Use `adb forward` for desktop
  clients, or the Cloudflare Tunnel screen for remote access.
- Custom OpenAI-compatible nodes use Chat Completions. The Responses API is not
  supported yet; choose Chat Completions when creating a node.
- Streaming is relayed for OpenAI/Ollama upstreams and translated to OpenAI SSE
  chunks for Anthropic/Gemini/Antigravity.
- Token counts for streaming responses are estimated (prompt only); buffered
  responses report upstream usage when the provider returns it.
- Android may stop the foreground service under extreme battery pressure; the
  boot receiver re-arms it when auto-start is enabled.
- OAuth sign-in uses the system browser and a random loopback port; no Google
  or Anthropic password ever reaches the app. Tokens are stored encrypted and
  refreshed automatically before they expire.
- The FTP host and the service scan only work while the app process is alive;
  the host starts with the proxy foreground service. The app publishes existing
  loopback services and does not bundle their runtimes.
- Cloudflare quick tunnels carry HTTP only. TCP services and native FTP need a
  Named tunnel, a Zero Trust hostname and a client policy.
- Hosted runtimes need a binary that Android lets the app execute. Copying a
  binary into app storage is not enough on Android 10+; bundle it as a native
  library or keep running it in Termux and publish the port instead.
