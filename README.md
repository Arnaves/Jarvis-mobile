# JARVIS Launcher for Android

A sci-fi heads-up-display home screen for Android with an assistant that runs **entirely on your phone**.
There's no cloud and no account, and your voice, chats, notes and the AI model never leave the device.

## Features

**Launcher**
- Animated holographic HUD: a reactor core, hex-grid backdrop, scan lines and drifting particles
- A live telemetry panel showing battery, temperature, RAM, storage, network, uptime and your next alarm
- Dock of 5 pinned apps (long-press any app to pin or unpin it)
- Swipe up for the app index, which has instant search, fuzzy matching and an "Ask JARVIS" fallback
- Swipe down for notifications, double-tap to talk, and press home to collapse everything
- 6 colour schemes: Arc Reactor, Hot Rod, Titanium Gold, Stealth, Gamma and Vision
- Boot sequence and first-run setup
- Can be picked as the device's assist app (long-press home or power, depending on the device)

**Assistant: works offline with no model installed**
| Say… | It does |
|---|---|
| "open Spotify" / "launch camera" | Fuzzy app launch. It copes with misheard names. |
| "call mum" / "text Alex saying on my way" | Contact lookup, then the dialler or SMS |
| "set a timer for 1 hour 30 minutes" / "alarm for 7:30 pm" | Clock app, no UI |
| "flashlight on" / "torch off" | Camera torch |
| "pause music" / "next track" / "volume 40 percent" / "mute" | Media and audio control |
| "navigate to the airport" / "search for…" | Maps or the browser |
| "open wifi settings" / "bluetooth on" | Settings panels |
| "what's 18% of 240" / "five times three plus two" | Offline calculator |
| "remember I parked on level 3" / "what do you remember" | On-device memory |
| "system status" / "battery" / "what time is it" | Diagnostics |

**Neural core: an on-device LLM for open conversation**
- Runs on [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) with CPU or GPU backends
- Streams replies token by token, keeps multi-turn context and knows things you asked it to remember
- The model can trigger device actions: it emits `ACTION: …`, which is passed to the skill engine above
- Voice uses Android's on-device speech recogniser (Android 12+) and offline text-to-speech with a British-voice preference

## Installing a model

The app ships without a model, because model files are 0.5–4 GB. To add one:

1. Download a MediaPipe-compatible `.task` model on your phone, for example from the
   [LiteRT community on Hugging Face](https://huggingface.co/litert-community):
   - **Gemma 3 1B IT (int4)**: about 550 MB. This is the best default.
   - **Qwen 2.5 1.5B Instruct**: about 1.6 GB. Smarter, but needs 6 GB+ of RAM.
   - **Gemma 3n E2B / Phi-4-mini**: for flagship phones.
2. In JARVIS go to **⚙ → Neural core → Import model file**.
   You can also paste a direct URL, plus a Hugging Face token for gated models, and tap **Download**.
3. Tap the model to load it. The status changes to `NEURAL CORE · ONLINE`.

For the most reliable offline voice input, install your language under
*Settings → System → Languages → On-device speech recognition*.

## Building

You need JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # R8-minified, signed with the debug key
./gradlew testDebugUnitTest      # unit tests + Roborazzi UI screenshots → app/screenshots/
```

Every push runs GitHub Actions, which uploads the APKs and UI screenshots as build artifacts.

Install with `adb install app/build/outputs/apk/release/app-release.apk`, then press Home and choose **JARVIS**.

## Architecture

```
ai/      Assistant (orchestrator) · Skills (offline command engine) · LocalLlm (MediaPipe)
         VoiceInput (SpeechRecognizer) · Speaker (TTS) · MathEval
data/    AppRepository (LauncherApps, icons, fuzzy search) · Prefs
system/  SystemMonitor (battery / RAM / storage / network)
ui/      Jetpack Compose: HomeScreen, AppDrawer, ConsoleScreen, SettingsScreen, BootSequence,
         components (ArcReactor, HudBackground, HudPanel, gauges)
```

Requests are tried against the skills first, because they're instant and deterministic.
If none matches, the request goes to the LLM. If no model is installed, JARVIS offers a web search instead.

## Before publishing

"JARVIS" and related names are trademarks of Marvel/Disney. To sell this on the Play Store, rename the
app and the assistant (the persona prompt is in `ai/Assistant.kt` and the label in `res/values/strings.xml`),
and replace the debug signing config with your own keystore.
