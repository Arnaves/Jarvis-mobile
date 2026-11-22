# JARVIS Launcher for Android

A sci-fi heads-up-display home screen for Android with an assistant that runs **entirely on your phone**.
There's no cloud and no account, and your voice, chats, notes and the AI model never leave the device.
Live internet lookups are optional; when one runs, only that question is sent.

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

**Hands-free: "Hey JARVIS"**
- Say **"Hey JARVIS"** at any time: on the home screen, inside other apps, or with the screen off.
  He replies **"Yes, sir?"** and listens for **5 seconds**. If you say nothing, he goes back to standby.
- You can say it in one breath: *"Hey JARVIS, what's the weather in Tokyo?"*
- **Custom wake word:** Settings › Hands-free › Wake word, e.g. "Friday" (say "Hey Friday") or a 2–3 word phrase.
  It's checked against the offline engine's vocabulary when you save, and takes effect immediately.
- Wake-word detection uses **Vosk**, which is offline, silent and low-power. It only triggers on the full phrase
  "hey / ok / hi JARVIS" with high word confidence, and a set of sound-alike decoy words absorbs everyday speech and noise. The model is bundled in CI builds;
  for local builds, install it in Settings with one tap. Until it's installed, JARVIS falls back to the system
  recogniser, which may chime on some phones.
- Runs as a foreground service with a "Stop listening" button in its notification.

**Sci-fi lock screen**
- Appears every time the screen turns on, and also when you wake JARVIS by voice while the phone is asleep.
  The intro is a radar sweep and identity scan, followed by the live core, clock, power and memory gauges,
  and voice control.
- Swipe up and the core overloads and flashes white, then hands over to your normal PIN or fingerprint.
  Biometric unlock plays the same exit animation.
- Android doesn't allow third-party apps to replace the secure system lock, so JARVIS is drawn *over* it.
  Your phone stays exactly as secure as before. This needs the "Display over other apps" permission.
  JARVIS is pre-loaded when the screen turns off, so it's already on top when you wake the phone.
  It never turns the display on by itself, so your sleep timer works normally. The lock screen has no tap-to-talk;
  use the wake word or unlock first.
  **Xiaomi/Redmi/Poco** also need "Show on Lock screen", and Oppo/Vivo/Realme need "Lock screen display".
  Settings has a shortcut to the right page.

**Tasks you assign by voice**
| Say… | What happens |
|---|---|
| "Hey JARVIS, send a WhatsApp to Mummy Kolkata saying good morning, reached office **when I reach office**" | Geofence on your saved "office". On arrival JARVIS opens the chat with the message and presses Send (with WhatsApp auto-send enabled) |
| "save this place as office" / "this is my gym" | Saves your current location as a named place |
| "**at 6 pm** text Rahul saying running late" / "**in 20 minutes** remind me to call dad" | Exact alarm, then runs the command. SMS goes out automatically with the SMS permission |
| "remind me to buy milk **when I leave work**" | Notification + spoken reminder on exit |
| "what are my tasks" / "cancel the task about milk" | Manage the pending tasks (also listed in Settings) |

If the phone is locked when a task needs another app's screen (WhatsApp, calls), JARVIS says so and posts a "Run now" notification.

**Notes & daily briefing**
- "Note for tomorrow: call the bank" / "Remember the Wi-Fi password is tiger42" / "Note down dentist on Monday"
- "**What do you have for me today?**" / "Brief me" / "What's on my agenda". You get:
  - today's notes, including any carried over from earlier days
  - tasks scheduled for today
  - location tasks still waiting
  - your next alarm and the weather
  - upcoming dated notes and your general notes
- "Read my notes" / "Delete the note about the bank" / "Delete all notes". Notes are also listed in Settings.
- With a neural core loaded, the AI also sees your notes, so "when is my dentist?" just works.

**Bookings & appointments**
"Book a table for two at Peter Cat at 8 pm" or "call Dr Mehta clinic and take an appointment for tomorrow morning":
JARVIS finds the place in your contacts or on OpenStreetMap near you, then dials it, opens its booking website, or falls
back to Maps. It also shows a script to read out. Android doesn't let any app speak into a phone call, so a fully
autonomous calling agent would need a cloud telephony service; that's deliberately not part of this offline-first app.

**Charging animation**
Plugging in plays a full-screen *power surge*: shockwaves and the core igniting. While charging, energy streams and
lightning flow into the core on the home and lock screens, with live percent, watts, time to full and cell temperature.

**Live internet knowledge (no API keys)**
| Say… | Source |
|---|---|
| "what's the weather" / "will it rain in London tomorrow" | Open-Meteo |
| "top headlines" / "news about Tesla" | Google News RSS |
| "who was Ada Lovelace" / "look up black holes" / "why is the sky blue" | DuckDuckGo + Wikipedia |
| "bitcoin price" | CoinGecko |
| "convert 100 dollars to rupees" | open.er-api.com |

When a neural core is loaded, fact lookups are fed to the LLM as grounding, so its answers are both current and conversational.

**Assistant: works offline with no model installed**
| Say… | It does |
|---|---|
| "open Spotify" / "launch camera" | Fuzzy app launch. It copes with misheard names. |
| "call mum" / "text Alex saying on my way" | Contact lookup, then the dialler or SMS |
| "set a timer for 1 hour 30 minutes" / "alarm for 7:30 pm" | Clock app, no UI |
| "flashlight on" / "torch off" | Camera torch |
| "pause music" / "next track" / "volume 40 percent" / "mute" | Media and audio control |
| "navigate to the airport" / "search the web for…" | Maps or the browser |
| "open wifi settings" / "bluetooth on" | Settings panels |
| "what's 18% of 240" / "five times three plus two" | Offline calculator |
| "remember I parked on level 3" / "what do you remember" | On-device memory |
| "system status" / "battery" / "what time is it" | Diagnostics |

**Neural core: an on-device LLM for open conversation**
- Runs on [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) with CPU or GPU backends
- Streams replies token by token, keeps multi-turn context and knows things you asked it to remember
- The model can trigger device actions: it emits `ACTION: …`, which is passed to the skill engine above
- Voice uses Android's on-device speech recogniser (Android 12+) and offline text-to-speech with a British-voice preference

## AI brain: Google Gemini

1. Get an API key at Google AI Studio (aistudio.google.com) → **Get API key**.
2. In JARVIS: **⚙ → AI brain · Google Gemini**, paste the key, then tap **Save & verify**.
   JARVIS checks the key and lists the Gemini models it can use. Pick one (a *flash* model is fast and cheap).
3. That's it. The status bar shows `GEMINI UPLINK · ONLINE`.

How requests are routed:
- **Phone commands** (calls, WhatsApp, timers, torch, volume, tasks, notes) run instantly on-device, without Gemini.
- **Weather, news, crypto and currency** use their fast dedicated sources.
- **Everything else** goes to Gemini, with Google Search grounding for facts and current events. That covers conversation, advice, explanations and "what's planned this week?" (it sees your notes). Gemini can also trigger phone actions.
- **No internet:** commands still work, and the optional offline core (a `.task` file you import) takes over if you have one.

**Quota failover:** free-tier quotas are per model, and Google Search grounding has its own quota. When a request
hits a quota limit (HTTP 429), JARVIS first retries without search. Then it switches to the next model with quota left:
flash-lite, then flash, then others, with pro last. Each exhausted model is skipped for the cooldown Google reports.
Settings shows which model is answering. If every model is out of quota, factual questions fall back to Wikipedia/DuckDuckGo.

The key is encrypted with the Android Keystore and only ever sent to Google. When Gemini answers, your question,
the recent conversation, saved notes and the time/battery are sent to Google.

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
