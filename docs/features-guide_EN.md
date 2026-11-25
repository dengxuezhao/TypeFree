English | [简体中文](features-guide.md)

# TypeFree Complete Feature Guide (v3.9.0)

This guide covers **TypeFree v3.9.0** (Android 8.0+ / Target SDK 35), organized by the actual in-app navigation structure and everyday workflows — including Smart English QWERTY, native `librime 1.17.0` Chinese schemas, on-device offline speech & `Qwen 0.8B` cleanup models, BYOK streaming voice & AI tools, the History Voice Latency Waterfall, 26-key gesture tables, and privacy boundaries.

---

## Table of Contents

1. [App Navigation Structure](#1-app-navigation-structure)
2. [4-Step Onboarding & Offline / BYOK API Setup](#2-4-step-onboarding--offline--byok-api-setup)
3. [AI Voice Input & Global Floating Voice Bubble](#3-ai-voice-input--global-floating-voice-bubble)
4. [AI Writing, Selected-Text Ask AI, Insights & Latency Waterfall](#4-ai-writing-selected-text-ask-ai-insights--latency-waterfall)
5. [Native librime Keyboard & Multi-Schema Codetable System](#5-native-librime-keyboard--multi-schema-codetable-system)
6. [26-Key 3-Way Gestures, Keycap Badges & Coded Snippets](#6-26-key-3-way-gestures-keycap-badges--coded-snippets)
7. [Typing Productivity Helpers & Smart Clipboard 2.0](#7-typing-productivity-helpers--smart-clipboard-20)
8. [Appearance, Ergonomics & System Font-Scale Protection](#8-appearance-ergonomics--system-font-scale-protection)
9. [Privacy Guardrails, Lossless Backup & FAQ](#9-privacy-guardrails-lossless-backup--faq)

---

## 1. App Navigation Structure

The TypeFree companion app uses 4 bottom navigation tabs with scenario-oriented sub-tabs:

| Bottom Tab | Sub-Tabs / Core Features |
| :--- | :--- |
| **Home** (`首页`) | Setup status & 1-tap repair, **Expression Insights & Custom Poster** (voice × keyboard dual-track metrics, keystrokes saved, voice duration, cloud token usage), **Recent Voice & Edit History** (with collapsible **Voice Latency Waterfall**), Quick Playground |
| **Keyboard** (`键盘`) | **Layouts** (`方案与布局`: schema switcher, Smart English settings, codetable options, height & keycap badges, typing toggles) · **Gestures** (`手势与短语`: 26-key swipe-up / long-press editor, `/` coded snippets & voice triggers) · **Dictionaries** (`词库管理`: Rime Ice lexicon download, 1-tap open-source presets, custom Rime `.zip` import & multi-schema manager, personal dictionary & word learning) |
| **Voice & AI** (`语音与 AI`) | **Voice** (`语音与词典`: live streaming composer, global floating voice bubble, Myers-diff typo hotword auto-learning, whisper sensitivity & media ducking) · **AI Assist** (`AI 助手`: ✍️ Help Me Write, 🔍 Ask AI, custom tones & per-app tone binding) · **Models** (`模型与 API`: on-device offline ASR & `Qwen 0.8B` SLM hub, Provider Vault, 1-tap presets, `1-Token` live latency test) |
| **General** (`通用`) | UI Language (`简体中文` / `English` / `繁體中文`), Theme (Light Monochrome / Dark Obsidian / Follow System), Keypress Sound & Haptics, Smart Clipboard settings, Incognito Mode, Config Backup & Restore, Replay Onboarding |

---

## 2. 4-Step Onboarding & Offline / BYOK API Setup

### 2.1 4-Step Full-Screen Setup Wizard (Replayable anytime in `General → Replay Onboarding`)

1. **Enable & Switch IME**: Deep-links directly to system settings to enable and select `TypeFree`, then automatically returns to the app as soon as selection is detected.
2. **Choose Your Typing Layout**: Pick Smart English QWERTY, 26-Key Full Pinyin, Native T9 9-Key Pinyin, Double Pinyin (Flypy), or a shape/phonetic-shape codetable in one tap.
3. **Choose Your Voice & AI Mode**: Side-by-side comparison between **100% On-Device Offline (Zero Key)** (8 open-source local ASR engines + fine-tuned `Qwen 0.8B` local cleanup SLM, works in airplane mode) and **BYOK Cloud Streaming** (~0.3–0.5s live dictation plus full `✍️ Help Me Write` & `🔍 Ask AI`).
4. **Live Playground**: Try typing and voice dictation immediately inside an interactive test card.

### 2.2 3-Layer Unified Model Studio (`Voice & AI → Models`)

TypeFree follows a strict **Local-First + BYOK (Bring Your Own Key)** design — zero central servers, zero subscriptions:

- **Layer 1 · Quick Setup: On-Device & Cloud Keys (Provider Vault)**
  - Supports **📱 On-Device Offline**, **Google Gemini**, **DeepSeek**, **Alibaba Bailian (DashScope)**, **Volcengine Doubao**, **SiliconFlow**, and **Custom / Gateway** (OpenAI, Groq, OpenRouter, OneAPI/NewAPI, local LAN Ollama).
  - Each provider's API key is encrypted independently on-device via Android Keystore (`SecretVault` / `EncryptedSharedPreferences`). Switching providers never overwrites saved keys.
- **Layer 2 · Workflow & Multi-Provider Mix**
  - Switch between **`⚡ Live / Dual-Stage`** and **`🎙️ Multimodal Direct`**, or mix any **👂 Speech Engine (STT)** with any **🧠 Stage-2 Polish, Writing & Translation LLM** (including **Hybrid Mode**: local `sherpa-onnx` ASR + cloud LLM).
  - Step-by-step global, hybrid, local LAN, and China-direct recipes are documented in the [BYOK & API Setup Guide](api-setup-guide_EN.md).
- **Layer 3 · Advanced Overrides & `1-Token` Live Speed Test**
  - Tap **⚡ Fetch & Speed Test** on any model slot to measure real time-to-first-token (`ms`) concurrently across models on your current network and sort fastest first.
  - Configure per-model **Thinking Mode**: keep Thinking disabled for everyday dictation and live translation to minimize latency, and enable it only when needed for complex writing.

### 2.3 Single-Key Cloud Setups (Google Gemini & DeepSeek)

- **Google Gemini Single-Key Live**: One Gemini API key drives **`gemini-3.5-transcribe-live`** over WebSocket (~0.5s live filler removal and mid-sentence self-correction) while **`gemini-3.5-flash-lite`** handles `✍️ Help Me Write`, `🔍 Ask AI`, `🪄 Speak-to-Edit`, and `🌐 Live Translate`.
- **DeepSeek Single-Key All-in-One**: One official DeepSeek API key connects Stage-1 ASR directly to `POST https://api.deepseek.com/v1/tools/asr` (16 kHz mono WAV, multilingual ZH/Yue/EN/JA/KO) and Stage-2 polish, writing, and Ask AI to `deepseek-chat` — or pairs `deepseek-chat` with a live WebSocket ASR engine.

### 2.4 On-Device Offline Speech & Cleanup SLM (Zero Keys, Zero Network)

Inside `Voice & AI → Models → 📱 On-Device Offline` (`Model Hub`), you can run voice and cleanup completely offline inside an isolated `:local_ai` process:

- **8 Open-Source Offline ASR Models (`sherpa-onnx`)**: Includes `SenseVoiceSmall` (fast multilingual ZH/EN/Yue/JA/KO), streaming & offline `Paraformer` (ZH-EN), `FireRedASR`, `Moonshine`, `Whisper`, and compact Zipformer variants.
- **Fine-Tuned Local Cleanup SLM (`llama.cpp`)**: Quantized `Qwen 0.8B` local model tuned specifically to strip spoken fillers, resolve mid-sentence self-corrections, restore punctuation, and perform basic rewrites without network access.

---

## 3. AI Voice Input & Global Floating Voice Bubble

### 3.1 Core Voice Panel Controls

- **Tap Microphone**: Start hands-free recording; tap again to finish, polish, and insert text.
- **Hold Microphone**: Push-to-talk — hold while speaking and release to finalize immediately.
- **Cancel (`✕`)**: Tap `✕` on the left during recording to discard the session without inserting anything.
- **Automatic Spoken Cleanup**: Removes spoken fillers ("um", "uh", "like", "嗯", "那个"), resolves mid-sentence self-corrections ("let's meet at 3 — wait no, 4 PM" → "Let's meet at 4 PM"), and formats punctuation naturally.
- **Seamless >60s Long Dictation**: Recordings exceeding 60 seconds automatically rotate sessions on natural silence boundaries and polish segments concurrently without dropping words.

### 3.2 Live Streaming Dictation (`Live Stream into Input Field`)

- Location: `Voice & AI → Voice → Live Stream into Input Field` (or top-left quick menu `⊞ → Live Write`).
- How it works: Partial speech transcripts stream directly into the host editor as underlined composing text while you speak, then atomically swap to the polished text upon release with a top-bar **↶ Undo** button.
- Editor conflict guard: Built-in idempotent deduplication handles editors with asynchronous selection callbacks (such as QQ). If you manually move the cursor mid-recording, composing updates freeze automatically so existing text is never overwritten.

### 3.3 Audio & Context Enhancements

- **Whisper Sensitivity Mode**: Applies soft-limited 16-bit PCM gain amplification when speaking quietly at night or in libraries (toggleable right from the voice panel).
- **Auto-Pause Media Playback**: Automatically pauses active music/video playback when recording starts and resumes when finished.
- **Cursor Context Awareness**: Reads up to 80 characters before the cursor locally as context hints for accurate punctuation continuation and pronoun disambiguation.
- **Myers-Diff Typo Hotword Auto-Learning**: If you manually fix a proper noun within 60 seconds after voice insertion, TypeFree computes an on-device diff and promotes the corrected spelling into your personal hotword dictionary for future recognition.

### 3.4 Global Floating Voice Bubble (Dictate Without Switching Keyboards)

- Location: `Voice & AI → Voice → Enable Floating Voice Bubble` (requires Android Accessibility Service).
- Appears unobtrusively whenever any text field gains focus across apps. Supports tap-to-record, push-to-talk, and voice-guided editing of selected text, using clean native cursor insertion on Android 13+.
- Automatically hides in password fields, banking apps, and payment screens.

---

## 4. AI Writing, Selected-Text Ask AI, Insights & Latency Waterfall

### 4.1 ✍️ Help Me Write (Zero-Screen-Read Studio)

Triggered from the top bar **✍️ Write** pill — **zero screen-reading and zero screenshots**:

- **On an Empty Field**: Displays quick genre chips (Social Post, Product Review, Weekly Report, Leave Note, Follow-Up, Thank-You Note, Free Draft). Tap a chip and dictate key points or generate directly.
- **With Existing Draft Notes**: No manual select-all needed — tap quick actions (`Polish`, `Concise`, `Expand`, `Bullet Points`, `Social Style`) or speak custom instructions to transform your draft in place.

### 4.2 🔍 Top-Bar `Ask AI` In-Keyboard Card (Selected-Text Aware)

- Location: `Voice & AI → AI Assist → Top-Bar "Ask AI" Pill`.
- Type `≥2` characters in a normal input field — or **select any span of text** — to illuminate the **🔍 Ask AI** pill on the keyboard top bar (smoothly adapting via measured icon-collapse on narrow screens).
- Answers stream safely inside an **in-keyboard answer card** without sending anything to your chat window:
  - Tap **Done (Clear Input)** to dismiss cleanly (with 1-tap undo to restore the original query), **Copy**, or **Insert** to paste the answer into the editor.

### 4.3 🪄 Speak-to-Edit Selection & 🌐 Live Translate

- **Speak-to-Edit**: Select text in any editor to surface **AI Edit** in the top bar; tap a preset rewrite chip or hold the mic to speak custom edit instructions, with 1-tap undo.
- **Live Translate**: Translate inline from the keyboard translation panel, or hold the voice mic and slide up onto the radial language wheel to dictate directly into your target language.

### 4.4 Custom Tones & Per-App Tone Auto-Binding

- In `Voice & AI → AI Assist`, define custom writing tones and bind specific host apps (e.g., Slack, Email, WeChat, Feishu, Terminal/IDE) to switch tones automatically whenever you focus that app.

### 4.5 Expression Insights & Custom Shareable Posters (`Home → Expression Insights`)

Aggregated 100% locally on your device (daily numeric rollups only — zero text persisted, automatically skipped in passwords and Incognito mode):

- **Voice × Keyboard Dual-Track Ledger**: Tracks committed character counts, equivalent keystrokes saved, cloud vs. offline voice duration, and cloud API token consumption (including prompt-cache hit rate).
- **Multi-Range Trends & Heatmaps**: 7-day, monthly, yearly, and all-time views with a 56-day activity heatmap and 24-hour rhythm chart.
- **Custom Expression Posters**: Export Obsidian Dark, Paper White, or Emerald Night summary cards to your gallery.

### 4.6 Full-Chain Voice Latency Waterfall (`Home → Recent Voice & Edit History`)

Every non-incognito voice or rewrite card in History includes a collapsible **Raw & Latency** (`VoiceLatencyWaterfallSubCard`) diagnostic profiler measured with a monotonic clock:

- **Per-Stage Obsidian × Emerald Waterfall Bar**: Breaks down exact milliseconds across `Mic Init → Recording / First Partial → Audio Flush → Stage-1 ASR → Stage-2 LLM (TTFT & Stream) → Editor Commit`.
- **Zero-PII Diagnostic Export**: Tap **Copy Trace Log** to copy a sanitized timing trace (zero transcript text or API keys included), or inspect live in terminal via `adb logcat -s TypeFreePerf`.

---

## 5. Native librime Keyboard & Multi-Schema Codetable System

TypeFree embeds native C++ **`librime 1.17.0`** (with `librime-lua`, `librime-octagram`, and `librime-predict`) alongside an offline **Smart English QWERTY** engine.

### 5.1 Built-In Engines, 1-Tap Presets & Custom Schema Import

| Category | Schema / Engine | Highlights |
| :--- | :--- | :--- |
| Built-in Ready | **Smart English QWERTY** | 100% offline frequency dictionary, swipe-to-type gesture trail, touch-zone & apostrophe auto-correction, next-word bigram prediction, auto-cap, double-space period |
| Built-in Ready | **Full Pinyin (`rime_ice`)** | 26-key full-sentence Pinyin, abbreviated Pinyin, dynamic frequency learning, 1-tap SHA-256 verified full 580k lexicon download |
| Built-in Ready | **9-Key Pinyin (`t9`)** | Native T9 9-grid full-sentence Pinyin sharing the open-source `rime_ice` lexicon, left-hand live syllable filter strip, and 3×4 large-key number pad |
| Built-in Double Pinyin | **Double Pinyin (`double_pinyin_flypy`)** | Community Flypy double-pinyin keymap paired with the open-source `rime_ice` sentence dictionary (zero proprietary tables) |
| 1-Tap Open Preset | **Xingkong Jiandao 6 (`xkjd6`, GPL-3.0)** | Open-source phonetic-shape top-up schema powered by native `librime-lua` `topup_processor` |
| 1-Tap Open Preset | **Wubi 86 (`wubi86`, LGPL-3.0)** | Classic fixed 4-code pure shape table; 5th key auto-commits primary candidate |
| 1-Tap Open Preset | **Tiger Code (`tigress` / `tiger`)** | Official open-source phrase (`tigress`) and single-character (`tiger`) 4-code auto-commit presets |
| Custom Multi-Format Import | **Full Rime `.zip` Bundles & Extension Dicts** | Dual-track importer supporting both plain-text bundles (`.schema.yaml` + `.dict.yaml` / `.yaml` / `.tsv` / `.txt` + optional `.lua` / `opencc/`) and precompiled bundles (`build/*.table.bin` / `.prism.bin` / `.reverse.bin`), plus standalone extension dictionaries |

> **Automatic Schema Archetype Detection & Isolation**: When importing custom schemas, `SchemaInspector` identifies the schema archetype (fixed-length phonetic-shape, top-up codetable, pure shape, or full-sentence Pinyin) and isolates schema-specific shortcuts — e.g., Flypy `o*` commands never intercept keystrokes in non-Flypy schemas.

### 5.1.1 Native T9 9-Key Pinyin

- **Zero-Bloat Shared Lexicon**: Shares the built-in `rime_ice` dictionary for full-sentence input with a floating readable Pinyin segmentation preview (`ni hao`).
- **Smart Left Vertical Rail**: Shows scrollable common punctuation when idle, and switches automatically into a **valid Pinyin syllable filter strip** while composing so you can lock syllables with one tap.
- **3-State Natural Backspace**: Smoothly steps through `undo partial word selection → unlock previous syllable → delete digit`, alongside a dedicated **3×4 phone-style number pad** (`123`) and bilingual symbol pages.

### 5.2 Zero-Interference Codetable Enhancements (`Keyboard → Layouts`)

- **Pure 4-Code Auto-Commit & Raw English Commit**: Preserves authentic codetable ordering without mangling valid 4-character codes; pressing `Enter` while composing commits the raw ASCII letters cleanly without inserting a newline.
- **Single-Character Mode (`odz` or Quick Toggle)**: Strictly filters candidates to single characters (full UTF-8 4-byte CJK Extension B–I codepoint counting) for names and classical text.
- **`,` / `.` Candidate Selection**: When candidates are visible, bottom-row `,` selects the 2nd candidate and `.` selects the 3rd candidate.
- **2D Expandable Candidate Grid & Long-Press Actions**: Expand the candidate bar into a multi-column grid; long-press any candidate to **Pin to Top** or **Delete Custom Word**.
- **Brief-Code Continuation (`BriefPrefixIndex v3`) & Reverse Fallback**: Non-intrusive continuation hints and empty-code reverse segmentation toggles.
- **L1 + L2 Local Word Learning**:
  - **L1 Explicit Entry**: Add custom phrases manually in `Keyboard → Dictionaries` with automatic code inference and manual override.
  - **L2 Repeated-Phrase Chip**: When you type the same new phrase repeatedly, a non-intrusive 1-tap **Add** chip appears in the suggestion rail (`0ms` activation, or `✕` to dismiss permanently).

### 5.3 Flypy `o*` Quick Commands Reference (Active Only on Imported Flypy Codetable)

Toggleable in `Keyboard → Layouts → Xiaohe o* Quick Commands`:

| Code | Function | Description |
| :--- | :--- | :--- |
| `orq` | Date & Weekday | Outputs current date/weekday in multiple formats (supports day offsets) |
| `ouj` | Current Time | Outputs current `HH:mm` or full timestamp |
| `ofi` | Reverse Code Lookup | Looks up component breakdown codes for the last committed or copied character |
| `oqm` | Full-Code Hint Toggle | Toggles full-code hint display |
| `odz` | Single-Char Mode Toggle | Toggles single-character candidate filtering on/off |
| `ovy` | Voice / Helper Trigger | Triggers quick built-in voice/helper action |
| `ojf` | Simplified / Traditional Toggle | Switches Simplified / Traditional Chinese output instantly |
| `oai` | Punctuation Mode Toggle | Switches between Chinese full-width and English ASCII punctuation |

---

## 6. 26-Key 3-Way Gestures, Keycap Badges & Coded Snippets

### 6.1 Independent Tap / Swipe-Up / Long-Press per Key

Every letter key decouples **Tap** (letter), **Swipe-Up** (fast punctuation & editing actions), and **Long-Press** (digits, secondary symbols & clipboard shortcuts). Every slot can be customized, disabled, or reset to defaults in `Keyboard → Gestures → 26-Key Swipe-Up / Long-Press`.

#### Default 26-Key Swipe-Up & Long-Press Reference Table

| Key | Default Swipe-Up | Compact Badge | Default Long-Press (ZH / EN) |
| :---: | :--- | :---: | :--- |
| `q` | `：“` | `:“` | `1` |
| `w` | `？` | `？` | `2` |
| `e` | `（` | `（` | `3` |
| `r` | `）` | `）` | `4` |
| `t` | `Tab` (`\t`) | `⇥` | `5` |
| `y` | `《》` (paired, cursor inside) | `《》` | `6` |
| `u` | `『` | `『` | `7` |
| `i` | Undo last commit | `↩` | `8` |
| `o` | `「」` (paired, cursor inside) | `「」` | `9` |
| `p` | `『』` (paired, cursor inside) | `『』` | `0` |
| `a` | `！` | `！` | Select All (`SELECT_ALL`) |
| `s` | `……` | `…` | `@` |
| `d` | `、` | `、` | `#` |
| `f` | Repeat last commit | `↻` | `¥` / `$` |
| `g` | `·` | `·` | `~` |
| `h` | `[]` (paired, cursor inside) | `[]` | `-` |
| `j` | `“”` (paired, cursor inside) | `“”` | `“”` / `""` |
| `k` | `（）` (paired, cursor inside) | `（）` | `（）` / `()` |
| `l` | `〔〕` (paired, cursor inside) | `〔〕` | `：` / `:` |
| `z` | `”` | `”` | `` ` `` |
| `x` | `→` | `→` | Cut (`CUT`) |
| `c` | `’` | `’` | Copy (`COPY`) |
| `v` | `——` | `—` | Paste (`PASTE`) |
| `b` | Full-width space (`\u3000`) | `␣` | `、` / `/` |
| `n` | `=` | `=` | `！` / `!` |
| `m` | `@` | `@` | `？` / `?` |

- **Auto-Centered Paired Brackets**: Inserting `《》`, `「」`, `“”`, `（）`, or `[]` automatically places the cursor between the brackets.
- **Backspace Swipe-Up**: Reverts the last committed text (or optionally clears the input field if enabled in settings).

### 6.2 3 Keycap Badge Display Modes

Location: `Keyboard → Layouts → Keycap Badge Display` (or `Keyboard → Gestures`):

- **Swipe-Up Badges (Default)**: Displays compact top-centered glyphs for swipe-up actions with zero collision against tall ascenders (`t/i/f/b/h/k/l`).
- **Long-Press Badges**: Displays top-row digits `1–0` and long-press symbols while keeping swipe-up gestures active (popup preview reflects the actual gesture triggered).
- **Hidden (Minimal)**: Clean letter-only keycaps vertically centered at `0dp` offset, with all swipe-up and long-press gestures still active.

### 6.3 Period Swipe-Up `/` Coded Snippets & Voice Triggers

- Location: `Keyboard → Gestures`.
- **Keyboard Trigger**: Swipe up on the bottom-row period key (`.` / `。`) to emit `/` and type a short alias (e.g., `/addr`, `/mail`, `/sig`) for `0ms` candidate expansion.
- **Voice Trigger**: Bind a spoken trigger phrase to the same snippet so saying that phrase in voice mode inserts the exact snippet locally in `0ms` without calling an LLM.

### 6.4 4-Code 5-Key Bottom Row

- Location: `Keyboard → Layouts → 4-Code 5-Key Bottom Row (Wide 2nd-Candidate Comma)`.
- Streamlines the bottom row into **`123 · , (wide 2nd-choice) · Space (hold to switch ZH/EN) · . (3rd-choice) · Enter`**, enlarging the comma hit target and reducing accidental period taps.

---

## 7. Typing Productivity Helpers & Smart Clipboard 2.0

### 7.1 Inline Expression Calculator (Passive Math Chip)

- Location: `Keyboard → Layouts → Inline Math Suggestions`.
- Type any arithmetic expression (`125*8=`, `(35+15)*2`, `(12×3)÷4=`) in any app to surface an instant result chip in the candidate bar.
- Respects whitespace boundaries (`123*8=984 1*1` evaluates only `1*1`) and ignores non-math identifiers like `a=50` or `v1+2`.

### 7.2 Smart English QWERTY (Offline Dictionary / Swipe / Auto-Correct / Bigram)

- Location: `Keyboard → Layouts → Smart English Input`.
- Works 100% offline in English mode with **dynamic touch-zone typo correction**, **apostrophe/contraction auto-fix** (`dont` → `don't`, `im` → `I'm`), **swipe-to-type gesture trail**, **next-word bigram prediction**, **sentence auto-capitalization**, and **double-space period** (with 1-tap revert on backspace).
- Automatically disables auto-correction and auto-capitalization inside password, email, URL, and search fields.

### 7.3 Smart Clipboard 2.0 (Up to 200 Items) & Big Bang Word Splitter

- Location: `General → Clipboard Settings` and keyboard top bar clipboard icon / `⊞ → Clipboard`.
- **Dual-Capsule OTP / Pickup-Code Bar**: Copying an SMS with a verification or parcel pickup code surfaces two top-bar pills side-by-side — **Extracted Code Only** on the left and **Full Text** on the right.
- **Multi-Entity Extraction**: Recognizes OTPs, parcel locker codes, tracking numbers, phone numbers, URLs, emails, and street addresses, offering separate 1-tap chips when multiple entities appear in one copied message.
- **💥 Big Bang Word Splitter**: Tap **Split** on any clipboard item to tokenize long text into selectable word chips right inside the keyboard for multi-select, punctuation stripping, and custom reassembly.
- **Automatic Privacy Filtering**: Skips clipboard entries originating from password managers and retains up to 200 unpinned items (pinned items never expire).

### 7.4 `⊞` 4×2 Quick Tool Panel & Measured Adaptive Top Bar

- **Measured Adaptive Icon-Collapse Top Bar**: Dynamically measures viewport width and localized label widths (`简体中文` / `English` / `繁體中文`) so `[🔍 Ask AI]`, `[✍️ Write/Edit]`, and `[🎙 Voice]` smoothly collapse into `28dp` icon buttons on narrow split-screen windows without clipping.
- **`⊞` 4×2 Quick Grid**: Tap `⊞` at the far left of the top bar to toggle 8 in-place tools without leaving your current app:
  - Row 1: **Clipboard** · **Keyboard Height** · **Input Schema** · **Writing Tone**
  - Row 2: **AI Completion** · **Live Write** · **Dictionaries** · **Settings**

---

## 8. Appearance, Ergonomics & System Font-Scale Protection

- **Dual-Mode Height Adjustment**:
  - **Proportional Scale (`STRETCH`)**: 5 overall keyboard height steps to free up chat viewport space or enlarge keys.
  - **Bottom Chin Padding (`LIFT`)**: Preserves standard keycap proportions while adding `0dp / 8dp / 16dp / 24dp / 32dp` bottom ergonomics padding above gesture navigation bars.
- **Optional Dedicated Number Row & Keypress Popup Preview**: Independently toggleable in `Keyboard → Layouts`.
- **System Large-Font Distortion Guard**:
  - Locks the IME keyboard root to `fontScale = 1.0f` so extreme system font sizes never clip keycaps, badges, or top-bar pills.
  - Clamps companion app UI between `0.85x` and `1.15x` (`1.0x` on mini keyboard previews) for readable, unbroken layouts.
- **3 Theme Modes**: Light Monochrome, Dark Obsidian, and Follow System.

---

## 9. Privacy Guardrails, Lossless Backup & FAQ

### 9.1 Privacy & Security Architecture

1. **Zero Central Servers**: No vendor cloud relay, no analytics SDKs, no crash telemetry, and no ads.
2. **Local `:local_ai` Isolation & Direct BYOK Egress**: Offline speech and SLM inference run strictly inside the local `:local_ai` process. Cloud requests connect directly from your device to the provider URL you configure.
3. **Incognito Mode**: Automatically activates in password fields, `IME_FLAG_NO_PERSONALIZED_LEARNING` private editors, or when toggled manually — pausing word-frequency learning, Rime user-dict writes, clipboard capture, history archiving, and numeric stats.
4. **Sensitive-App Circuit Breakers & `LocalPiiRedactor`**: Banking apps, payment sheets, and password fields forcibly disable floating bubbles and AI completion. `LocalPiiRedactor` provides on-device ID-card (GB 11643-1999), Luhn bank-card, API-key, password, and OTP redaction primitives.

### 9.2 Lossless v2 Configuration Backup & Migration

- Export a complete v2 JSON backup in `General → Backup & Restore` covering schemas, 26-key custom gestures, keycap badge modes, `/` snippets, custom tones, model routing, and numeric Expression Insights (API keys are redacted by default unless you explicitly opt in).
- Restoring merges whitelisted fields safely with full forward compatibility from older backup versions.

### 9.3 Importing Official Third-Party Rime Schema Packages (`.zip`)

To respect third-party codetable copyrights and EULAs, public TypeFree releases bundle **only open-source lexicons** (`rime_ice`, community double-pinyin keymaps, `xkjd6`, `wubi86`, `tigress`/`tiger`, and the open-source English dictionary) and **no proprietary shape tables**.

To import Xiaohe Flypy (phonetic-shape) or any other custom Rime schema:

1. Obtain a legitimate **Rime `.zip` bundle** from the schema author's official channel (both plain-text `.schema.yaml` + `.dict.yaml` archives and precompiled `build/*.table.bin` bundles are supported without unzipping).
2. Open TypeFree → **`Keyboard → Dictionaries`** → tap **`Import Rime Schema Package (.zip)`** and select the `.zip` file.
3. Switch to the imported schema in **`Keyboard → Layouts`**. TypeFree automatically detects the schema archetype and enables matching enhancements (such as 4-code auto-commit, wide 2nd-candidate comma, and `o*` commands).
4. Optional personal word lists (`.dict.yaml`, `.yaml`, `.tsv`, `.txt`) can also be attached separately in **`Keyboard → Dictionaries`**.
