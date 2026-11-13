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
| Upstreams | OpenAI-compatible, Anthropic, Gemini, Antigravity and Ollama wire formats |
| Routing | Combos, vision combo, per-account fallback, exponential backoff, model locks |
| Telegram | Bot API long polling inside the foreground service, `/help` `/model` `/combo` `/vision` `/status` `/quota` `/clear` |
| UI | Compose Material 3, Obsidian dark theme, drawer navigation |
| Background | Foreground service + boot receiver (auto-start optional) |

## Screens

- **Dashboard** — gateway state, base URL, active key, daily stats, recent requests
- **Providers** — connections, priority order, test, edit, pause, delete
- **Combo & Vision** — ordered fallback chains and the vision adapter
- **Endpoint & Key** — client credentials plus a copy-paste `curl` test
- **Usage** — per-provider analytics and a filterable request log
- **Quota Tracker** — live cooldowns and model locks with countdowns
- **Telegram Bot** — bot token, owner chat id, routing, notifications, commands
- **Console Log** — searchable request history with request/response payloads
- **Settings** — port, auto-start, retention, storage, runtime info

## Build

The project targets JDK 17, AGP 8.5.2, Gradle 8.7, Kotlin 1.9.24, compileSdk 34
and minSdk 26.

### Locally

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

### CI

`.github/workflows/android-apk.yml` builds on every change under `android/` and
uploads the debug APK as the `9router-android-debug` artifact (Actions → run →
Artifacts). No signing secrets are required for the debug build.

## Install

```bash
adb install -r app-debug.apk
```

Or download the artifact and open it on the device (allow install from unknown
sources for the file manager you use).

## Use

1. Open **Providers** and add a connection. Paste an API key (or an Antigravity
   access token / advanced JSON with `refreshToken` and `projectId`).
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

## Telegram

Create a bot with @BotFather, paste the token and your chat id in the Telegram
screen, enable the bridge and save. Polling runs in the foreground service; the
app removes a stale webhook automatically. The same engine handles bot messages.

Proactive alerts are sent to the owner chat when every connection for a model is
cooling down (toggle in the Telegram screen).

## Notes and limits

- The gateway binds to loopback only. Use `adb forward` for desktop clients.
- Streaming is relayed for OpenAI/Ollama upstreams and translated to OpenAI SSE
  chunks for Anthropic/Gemini/Antigravity.
- Token counts for streaming responses are estimated (prompt only); buffered
  responses report upstream usage when the provider returns it.
- Android may stop the foreground service under extreme battery pressure; the
  boot receiver re-arms it when auto-start is enabled.
- Antigravity OAuth refresh is implemented, but the app never asks for a Google
  password — supply tokens/refresh tokens explicitly.
