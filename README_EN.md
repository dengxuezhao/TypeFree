<p align="center">
  <a href="README.md">简体中文</a> | <b>English</b>
</p>

# TypeFree

<p align="center">
  <img src="docs/logo.svg" width="108" height="108" alt="TypeFree Logo" />
</p>

<p align="center">
  <b>Native Android Dual-Engine IME: Smart English / librime Codetable Keyboard + On-Device Offline &amp; BYOK AI Voice</b><br/>
  Built natively with <b>Kotlin + Jetpack Compose + Material 3 + C++ librime 1.17.0 + sherpa-onnx + llama.cpp</b>.<br/>
  <b>Local-first &amp; BYOK (Bring Your Own Key)</b> — GPL-3.0 open-source, zero subscriptions, zero central servers, zero analytics.
</p>

<p align="center">
  <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><img src="https://img.shields.io/badge/Release-v3.9.3-10B981?style=flat-square&logo=github" alt="Release v3.9.3" /></a>
  <img src="https://img.shields.io/badge/Engine-librime_1.17.0_+_Lua-0EA5E9?style=flat-square" alt="librime 1.17.0" />
  <img src="https://img.shields.io/badge/Local_AI-sherpa--onnx_+_llama.cpp-F59E0B?style=flat-square" alt="Local AI" />
  <img src="https://img.shields.io/badge/UI-简体中文_%7C_English_%7C_繁體中文-6366F1?style=flat-square" alt="Trilingual UI" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-8.0+_(SDK_26~35)-brightgreen?style=flat-square" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=flat-square" alt="License: GPL v3.0" />
</p>

<p align="center">
  🏠 <a href="https://typefree.xuezhao.space/?lang=en"><b>Official Website</b></a> ·
  📦 <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><b>Download from GitHub Releases</b></a> ·
  📘 <a href="docs/features-guide_EN.md"><b>Feature Guide</b></a> ·
  🔌 <a href="docs/api-setup-guide_EN.md"><b>BYOK &amp; API Setup Guide</b></a> ·
  🇨🇳 <a href="README.md"><b>简体中文 README</b></a>
</p>

---

## Why TypeFree?

Most mobile keyboards force an unnecessary trade-off between **fast, private typing** and **modern AI voice & writing tools**:
- Open-source Rime keyboards excel at privacy and Chinese codetables, but lack modern swipe-to-type English intelligence and streaming AI dictation.
- Commercial AI voice keyboards lock features behind monthly subscriptions, route every keystroke or recording through vendor clouds, and poorly support power-user Chinese input methods.

**TypeFree unifies both engines in one lightweight, native Android open-source IME**:
- **For typing**: Smart English QWERTY (offline dictionary, swipe-to-type gesture trail, auto-correction, next-word bigram prediction, auto-capitalization, double-space period) alongside native C++ `librime 1.17.0` + `librime-lua`, 26-key 3-way gestures, native T9 9-key Pinyin, custom `.zip` Rime schema import, passive inline calculator, and a 200-item Smart Clipboard 2.0.
- **For voice & AI writing**: Works **100% offline with zero API keys** (`sherpa-onnx` on-device ASR + `llama.cpp` Qwen 0.8B local cleanup/rewrite in an isolated `:local_ai` process), OR connects directly via **BYOK** to your own Google Gemini Live, OpenAI, Groq, DeepSeek, Aliyun Bailian, Volcengine, or self-hosted Ollama / `whisper.cpp` endpoints.

---

## 🆕 What's New in v3.9.3

- **😊 Pinyin & Shuangpin Emoji Suggestions + 827-Emoji Panel**
  - Built-in 7,682-entry Simplified & Traditional Chinese offline Emoji lexicon (Unicode CLDR + curated daily chat phrases) powering both composing candidates and post-commit prediction bars on Full Pinyin, T9, and all 6 Shuangpin layouts (multi-tap consecutive emojis, skin-tone memory, Rime multi-step prefix guard, negation guard; strictly inactive on shape codetables/custom schemas and password/URI/email fields). Expression panel expanded from 187 to **827 mainstream emojis** with a new Travel category.
- **🎙️ Instant Floating Voice Bubble & Background Accessibility Engine**
  - Automatically surfaces in chat apps (WeChat/QQ/Feishu/Telegram) without requiring tap-input + dismiss-keyboard first; moved accessibility tree scanning to a dedicated background thread with deferred-collection scanning (zero main-thread jank in huge group chats), recording-start target fingerprint verification, whole-page password shield, and strict unfocused-text privacy isolation.
- **🔧 Samsung One UI Bottom Nav Inset/Bleed-Through Fix & 0% Mute Key Sound**
  - Eliminates double bottom lift padding and transparent bottom navigation bar bleed-through on Samsung One UI (Android 16) while preserving OriginOS / MagicOS bottom protection; lowers key click sound minimum from `5%` to `0%` (silent).

### Earlier in v3.9.1

- **🀄 6 Mainstream Shuangpin (Double Pinyin) Layouts & Auto 27-Key `;` Row**
  - Built-in **Xiaohe (default), Ziranma, Microsoft, Sogou, Intelligent ABC, and Ziguang Shuangpin**, all sharing the open-source Rime Ice dictionary and mixed CN-EN tables.
  - Automatically renders a 27-key layout (adding `;` next to `L`) in Chinese mode for Microsoft, Sogou, and Ziguang Shuangpin, restoring standard 26-key in English mode.
- **🎛️ 1-Tap Tool Panel Scheme Switcher & 3-in-1 Keyboard Adjust**
  - Tap **Input Scheme** in the keyboard Tool Panel to expand an inline strip switching instantly across Full Pinyin, T9, all 6 Shuangpin layouts, and imported Rime codetables without leaving your chat.
  - Live **Keyboard Adjust** sliders combine **Key Height (5 presets)**, **Bottom Lift (0–56dp)**, and **Candidate Font Size (3 tiers)**, with unified panel heights across 26-key, T9, English, and numeric boards for zero layout jump.
- **📱 T9 Ergonomics, Built-in Emoji/Kaomoji Panel, Tiered Haptics & Hold-Space Voice**
  - Rebalanced T9 columns for a wider centered `3×3` keypad, idle `1`-key punctuation cycling (`， 。 @ ？ ！`), and direct Emoji access.
  - Built-in **Emoji / Kaomoji / Symbol Panel** (skin-tone picker + Incognito-safe), **hold-spacebar to speak**, **zero-latency tiered haptic engine**, and **375dp-referenced adaptive font scaling**.
- **🔐 Android 11+ Inline Password Autofill & Multi-Mirror Resilience**
  - Native Android 11+ inline autofill suggestions in the top bar (Google Password Manager, Bitwarden, 1Password, etc.).
  - Optional multi-mirror lexicon setup during onboarding with automatic fallback to built-in lexicons when offline, plus Android 15/16 bottom system-bar inset fixes (OriginOS 7 / MagicOS 11) and OriginOS onboarding IME picker focus fixes.

<details>
<summary>📖 Previous Milestones (v3.9.0)</summary>

- **v3.9.0**: 100% offline Smart English QWERTY (swipe-to-type, auto-correction, next-word bigram prediction); full Trilingual UI (`简体中文` / `English` / `繁體中文`); Measured Adaptive Icon-Collapse Top Bar; Selected-Text `Ask AI` in-keyboard card & zero-screen-read `Help Me Write`; local zero-PII Expression Insights & cloud token observability.

</details>

---

## ✨ Architecture Overview

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                               TypeFree v3.9.1                                │
├──────────────────────────────────────┬───────────────────────────────────────┤
│  ⌨️ Smart English + librime 1.17.0    │   🎙️ Offline + BYOK AI Voice Studio    │
│  • Smart EN: swipe/autocorrect/bigram│  • On-device: sherpa-onnx + llama.cpp │
│  • Built-in ZH: Pinyin/T9/6 Shuangpin│  • Cloud BYOK: Gemini/OpenAI/DeepSeek │
│  • 1-Tap Presets: XKJD6/Wubi86/Tiger │  • Streaming dictation + Hold-Space   │
│  • Custom Rime .zip (plain & bin)    │  • Global Floating Voice Bubble       │
│  • 1-Tap Scheme Strip + 3-in-1 Adjust│  • 🔍 Selected-Text Ask AI Card       │
│  • Adaptive TopBar + Emoji Panel     │  • ✍️ Zero-Screen-Read Help Me Write  │
│  • L1+L2 Word Learning + o* Commands │  • 🪄 Speak-to-Edit + 🌐 Live Translate│
├──────────────────────────────────────┴───────────────────────────────────────┤
│   ⚡ Core Foundation: Trilingual UI · Inline Autofill · 200-Item Clipboard    │
│      Isolated :local_ai · LocalPiiRedactor · Incognito · Lossless v2 Backup   │
└──────────────────────────────────────────────────────────────────────────────┘
```

> For complete walkthroughs, the 26-key swipe-up/long-press lookup table, and `o*` commands, see the **[TypeFree Complete Feature Guide](docs/features-guide_EN.md)**.

---

## 🚀 Quick Start (5 Minutes)

1. **Install & Enable**: Download the latest APK (`TypeFree-vX.Y.Z.apk` Official Full Edition or `TypeFree-vX.Y.Z-foss-libre.apk` Pure FOSS Build) from **[GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest)** or the **[Official Website](https://typefree.xuezhao.space/?lang=en)**, launch TypeFree, and follow the setup wizard to enable and switch to TypeFree (optional: grant Accessibility Service only if you want the Global Floating Voice Bubble).
2. **Type & Dictate Offline (Zero API Keys)**: Smart English QWERTY, Rime Chinese input, 26-key gestures, Smart Clipboard 2.0, inline calculator, and custom `.zip` Rime schemas work immediately offline. To dictate offline with zero keys, download a `sherpa-onnx` voice model and `Qwen 0.8B` inside **Voice & AI → Offline Models**.
3. **Connect Cloud AI via BYOK (Optional)**: Go to **Voice & AI → Models & API**, store your own API key in the encrypted **Provider Vault**, pick a 1-tap preset, and run **⚡ 1-Token Speed Test** to rank models by real latency on your network.

### Recommended Voice & LLM Combinations

| Scenario | Speech-to-Text (STT) | Polish / Write / Ask AI (LLM) | Experience | Best For |
| :--- | :--- | :--- | :--- | :--- |
| **100% On-Device Offline (Zero Key)** | Local `SenseVoiceSmall` / `Paraformer` (`sherpa-onnx`) | Local `Qwen 0.8B` (`llama.cpp`) | Runs in isolated `:local_ai` process completely offline | $0 API cost, audio never leaves device |
| **Global Single-Key Streaming** | Google `Gemini Live WebSocket` | `gemini-3.5-flash-lite` / `gemini-3.8-flash` | Real-time streaming transcript (~0.5–1.5s first word) | Single key for streaming ASR + writing |
| **Global Fast Non-Streaming** | Groq `whisper-large-v3-turbo` | `gpt-4o-mini` / `gemini-3.5-flash-lite` | Fast batch upload + cleanup (~1–2.5s) | High multilingual accuracy |
| **DeepSeek Single-Key** | DeepSeek `/v1/tools/asr` | `deepseek-chat` | Single key for WAV ASR + LLM polish/write/Ask AI | Simplest 1-key Chinese + English setup |
| **CN Dual-Stage Streaming** | Aliyun Bailian / Volcengine `SeedASR 2.0` | `doubao-seed-2-1-lite` / `deepseek-chat` | Live streaming draft + instant finalize on release | Low-latency Mainland China direct routes |
| **Self-Hosted LAN Offline** | Local `whisper.cpp` Server | Local `Ollama` (e.g. `qwen3:8b`) | Depends on host CPU / GPU | Zero cloud egress, $0 API cost |

Step-by-step global, hybrid, local LAN, and China-direct recipes: 👉 **[BYOK & API Setup Guide](docs/api-setup-guide_EN.md)**.

---

## 📦 Built-In Input Engines & Rime Schema Import

TypeFree embeds native C++ **`librime 1.17.0`** + **`librime-lua`**:
- **Built-in Open-Source Engines**:
  - **Smart English QWERTY**: Offline English dictionary, swipe-to-type, auto-correct, bigram next-word prediction, auto-cap, and double-space period.
  - **Rime Ice Full Pinyin (`rime_ice`)** & **Native T9 9-Key Pinyin (`t9`)**: Full-sentence Pinyin sharing the open-source Rime Ice dictionary (plus 1-tap SHA-256 verified full 580k lexicon download).
  - **6 Shuangpin (Double Pinyin) Layouts**: Built-in **Xiaohe (`double_pinyin_flypy`), Ziranma (`double_pinyin`), Microsoft (`double_pinyin_mspy`), Sogou (`double_pinyin_sogou`), Intelligent ABC (`double_pinyin_abc`), and Ziguang (`double_pinyin_ziguang`)** reusing the open-source Rime Ice dictionary, with automatic 27-key `;` row switching in Chinese mode for Microsoft, Sogou, and Ziguang.
- **1-Tap Open-Source Codetable Presets** (`Keyboard → Lexicon`):
  - **Xingkong Jiandao 6 (`xkjd6`, GPL-3.0)** with native `librime-lua` `topup_processor`
  - **Wubi 86 (`wubi86`, LGPL-3.0)** fixed 4-code shape table
  - **Tiger Code (`tigress` / `tiger`)** official open presets
- **Custom Rime `.zip` & Extension Dictionary Import**:
  - Dual-track `.zip` importer supporting both plain-text schemas (`.schema.yaml` + `.dict.yaml` / `.tsv` / `.txt` + optional `.lua` / `opencc/`) and precompiled bundles (`build/*.table.bin` / `.prism.bin` / `.reverse.bin`).
  - Automatic schema archetype detection keeps schema-specific shortcuts strictly isolated between schemas.
  - Strictly complies with third-party EULA/copyright boundaries: no restricted proprietary codetables are bundled in this repository or public releases.

---

## 📚 Documentation

| Document | Description |
| :--- | :--- |
| [🇨🇳 简体中文 README](README.md) | Simplified Chinese project overview |
| [📘 Complete Feature Guide](docs/features-guide_EN.md) | Full v3.9.1 walkthrough, 4-tab navigation, 26-key gesture reference, `o*` commands, Voice Latency Waterfall & FAQ |
| [🔌 BYOK & API Setup Guide](docs/api-setup-guide_EN.md) | Step-by-step setup for Google Gemini Live, Groq, OpenAI, LAN Ollama, Hybrid local ASR, DeepSeek, Bailian, Doubao & SiliconFlow |
| [⚙️ GitHub Automation Guide](docs/github-automation-guide_EN.md) | CI hygiene gates, Release pipeline, label sync & Issue triage workflows |

---

## 📦 Editions & Open-Core Licensing (`Official Full` vs. `FOSS Libre`)

TypeFree follows a transparent **Open-Core** model: the complete typing engine, on-device offline AI pipeline, PII redactor, and BYOK voice studio are open-sourced here under GPL-3.0. On [GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest) and the [Official Website](https://typefree.xuezhao.space/?lang=en), we publish two APKs signed with the same release certificate (you can cross-install/upgrade seamlessly without losing dictionaries or settings):

- **Official Full Edition (`TypeFree-vX.Y.Z.apk` · Recommended Default)**: Identical to the website build (first-party copyright build with GPL-copyleft plugins excluded). Includes 100% of the free-forever core, plus a **14-day full-feature Early-Bird trial** and an **in-app TypeFree Pro lifetime seat activation panel** so you can unlock Pro features in place without reinstalling.
- **Pure FOSS Libre Build (`TypeFree-vX.Y.Z-foss-libre.apk` · Pure Open-Source Alternative)**: Compiled 100% from the public GPL-3.0 source tree in this repository with zero proprietary extensions or licensing UI — tailored for F-Droid and strict FOSS environments.

| Capability | Pure FOSS (`foss-libre`) | Official Full (Free Tier) | Official Full (Pro Lifetime Seat) |
| :--- | :---: | :---: | :---: |
| Smart English QWERTY + Pinyin / Shuangpin / T9 / Rime Codetables | ✅ Free Forever | ✅ Free Forever | ✅ Free Forever |
| All 8 Open-Source Offline ASR Models + `Qwen 0.8B` Local Polish | ✅ Free Forever | ✅ Free Forever | ✅ Free Forever |
| Unlimited BYOK Streaming Voice, Help Write, Ask AI & Latency Waterfall | ✅ Free Forever | ✅ Free Forever | ✅ Free Forever |
| 7-Day Zero-PII Expression Insights & Obsidian Share Card | ✅ Free Forever | ✅ Free Forever | ✅ Free Forever |
| Context-Aware Reply Assistant, Preference Memory & Redacted Vision *(Available Now)* | — | 🎁 14-Day Trial + 30 Free Runs | ✅ Unlimited Forever |
| One-Tap `Qwen 2B` Fine-Tuned On-Device SLM Download *(Available Now)* | — *(manual `.gguf` import)* | 🎁 14-Day Trial | ✅ Unlocked Forever |
| Long-Range Insights (Monthly / Yearly / All-Time Heatmaps) & Studio Themes *(Available Now)* | — | 🎁 14-Day Trial | ✅ Unlocked Forever |
| Upcoming Roadmap: `Qwen 4B` One-Tap Pack, Mac/Win Companions, iOS & Encrypted Sync | — | — | ⏳ *In Dev / Planned* (Included for Seat Holders) |

> Per-device seat plans (1 / 3 / 5 devices, one-time purchase with zero subscriptions) and community contributor free-license paths: [typefree.xuezhao.space/pro.html?lang=en](https://typefree.xuezhao.space/pro.html?lang=en).

---

## 💻 Build & Test from Source

Multi-module Gradle architecture (`:core` pure JVM engine, `:rime` C++ NDK JNI, `:localai-native` `llama.cpp` JNI, `:app` Android IME & Compose UI):

```bash
# 1. Clone the repository
git clone https://github.com/dengxuezhao/TypeFree.git
cd TypeFree

# 2. Fetch and verify upstream librime 1.17.0 C++ sources (first build only)
bash rime/src/main/cpp/fetch_librime_deps.sh

# 3. Fetch and verify on-device AI dependencies (sherpa-onnx AAR & pinned llama.cpp commit)
bash scripts/fetch_offline_deps.sh

# 4. Run JVM core & Android standard unit tests
./gradlew :core:test :app:testStandardDebugUnitTest

# 5. Assemble Standard Debug / Release APKs
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleStandardRelease
# Output: app/build/outputs/apk/standard/release/
```

---

## 🔒 Privacy & Security Commitments

1. **Zero Central Servers**: No telemetry, no crash trackers, no ad SDKs, and no cloud relay operated by TypeFree.
2. **Local-First & Direct BYOK Egress**: Offline models execute strictly inside the isolated `:local_ai` process on your phone. Cloud AI calls go directly from your device to the provider or self-hosted endpoint you configure.
3. **Zero Screen-Reading AI Tools & On-Device PII Guard**: **✍️ Help Me Write** and **🔍 Ask AI** never read or screenshot other apps. `LocalPiiRedactor` provides on-device ID-card (GB 11643-1999), Luhn bank-card, API-key, password, and OTP redaction primitives.
4. **Incognito & Sensitive-Field Circuit Breakers**: Password inputs, banking/payment apps, and manual Incognito mode automatically disable floating bubbles, word-frequency learning, clipboard capture, and numeric stats. API keys are encrypted locally with Android Keystore + AES-GCM (`SecretVault` / `EncryptedSharedPreferences`).

---

## 📄 License & Community

Licensed under the **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.

- **Issues & PRs**: Bug reports, feature ideas, and pull requests are welcome via [GitHub Issues](https://github.com/dengxuezhao/TypeFree/issues).
- **Downloads**: Official open-source APK builds are published on [GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest).
