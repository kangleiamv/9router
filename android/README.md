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
<https://github.com/kangleiamv/9router/releases/tag/android-v1.3.5>.

```bash
adb install -r 9router-android-1.3.5-debug.apk
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

## Notes and limits

- The gateway binds to loopback only. Use `adb forward` for desktop clients.
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
