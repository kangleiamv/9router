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
| Custom providers | User-defined OpenAI- or Anthropic-compatible nodes with a model prefix, model discovery and API-key connections |
| OAuth | Browser sign-in for Antigravity, Gemini CLI and Claude Code with a one-shot loopback callback, PKCE for Claude, automatic token refresh |
| Routing | Combos, vision combo, per-account fallback, exponential backoff, model locks |
| Telegram | Bot API long polling inside the foreground service, `/help` `/model` `/combo` `/vision` `/status` `/quota` `/clear` |
| UI | Compose Material 3, Obsidian dark theme, drawer navigation |
| Background | Foreground service + boot receiver (auto-start optional) |

## Screens

- **Dashboard** — gateway state, base URL, active key, daily stats, recent requests
- **Providers** — connections, priority order, test, edit, pause, delete, plus OpenAI/Anthropic compatible custom endpoints
- **Combo & Vision** — ordered fallback chains and the vision adapter
- **Endpoint & Key** — client credentials plus a copy-paste `curl` test
- **Usage** — per-provider analytics and a filterable request log
- **Quota Tracker** — live cooldowns and model locks with countdowns
- **Telegram Bot** — bot token, owner chat id, routing, notifications, commands
- **Console Log** — searchable request history with request/response payloads
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
<https://github.com/kangleiamv/9router/releases/tag/android-v1.2.0>.

```bash
adb install -r 9router-android-1.2.0-debug.apk
```

Or download the artifact and open it on the device (allow install from unknown
sources for the file manager you use).

## Use

1. Open **Providers** and add a connection. For Antigravity, Gemini CLI or
   Claude Code tap **Sign in with Google/Claude**: the browser opens the
   provider's consent page and the app catches the loopback callback on
   `http://localhost:<port>/callback`. For any other provider paste an API key.
   Manual tokens (access token, or advanced JSON with `refreshToken` and
   `projectId`) still work as a fallback.
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
