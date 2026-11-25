English | [简体中文](domestic-api-setup-guide.md)

# TypeFree BYOK & API Setup Guide (Global, On-Device Hybrid & China-Direct Providers)

TypeFree follows a **Local-First + BYOK (Bring Your Own Key)** architecture with zero central servers and zero subscriptions. You can run voice dictation and text cleanup **100% offline with zero API keys**, connect **one single cloud API key** (such as Google Gemini or DeepSeek), connect **self-hosted LAN Ollama**, or mix a fast streaming **Speech Engine (Stage 1)** with your favorite **Text & Writing LLM (Stage 2)**.

---

## 1. How TypeFree's Voice & AI Pipeline Works

In **`Voice & AI → Models → 2. Workflow & Multi-Provider Mix`**, selecting **`⚡ Live / Dual-Stage`** splits voice and writing into two modular slots so you can either use one provider for everything or combine the fastest ASR with the sharpest LLM:

```text
🎙️ Your Voice (16 kHz PCM / WAV)
   │
   ▼  [Stage 1: 👂 Speech Engine (STT)]
   │   • On-Device Offline: sherpa-onnx (SenseVoiceSmall / Paraformer — zero network)
   │   • Live WebSocket:    Google Gemini Live (~0.5s) / Alibaba Bailian / Volcengine Doubao
   │   • Fast HTTP Batch:   Groq Whisper / OpenAI / DeepSeek (/v1/tools/asr) / SiliconFlow / LAN whisper.cpp
   │
   ▼  Raw or Live-Cleaned Transcript
   │
   ▼  [Stage 2: 🧠 Stage-2 Polish, Writing & Translation LLM]
       • On-Device Offline: llama.cpp (Qwen 0.8B local cleanup & basic rewrite)
       • Cloud / LAN BYOK:  gemini-3.5-flash-lite / gpt-4o-mini / llama-3.3-70b-versatile /
                            deepseek-chat / qwen-turbo-latest / doubao-seed-2-1-lite / LAN Ollama
       • Powers spoken cleanup (optional toggle) + ✍️ Help Me Write + 🔍 Ask AI + 🪄 Edit + 🌐 Translate
```

> 💡 **Single-Hop Multimodal Alternative**: You can also switch the mode pill to **`🎙️ Multimodal Direct`** to send compressed `.m4a` audio straight to `gemini-3.5-flash-lite` or `gpt-4o-mini-audio-preview` in a single request. For everyday dictation, **`⚡ Live / Dual-Stage`** with WebSocket streaming or Hybrid local ASR offers faster visual feedback.

---

## 2. Recommended Configurations at a Glance

All provider keys are encrypted independently inside **`1. Quick Setup: On-Device & Cloud Keys` (Provider Vault)** via Android Keystore (`EncryptedSharedPreferences`) — switching presets never erases your saved keys.

| Preset | Combination | Keys | Live Stream? | Stage-1 Speech (STT) | Stage-2 Polish / Write / Ask AI | Best For |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| **O1** | **100% On-Device Offline** | 0 | Local | `SenseVoiceSmall` / `Paraformer` (`sherpa-onnx`) | `Qwen 0.8B` (`llama.cpp`) | Airplane mode, $0 API cost, zero audio/text egress |
| **G1** | **Google Gemini Single-Key** | 1 | **Yes (~0.5s)** | `gemini-3.5-transcribe-live` (Live WS) | `gemini-3.5-flash-lite` | **Top Global Pick**: 1 key covers live dictation + writing + Ask AI |
| **G2** | **Groq Whisper + Llama** | 1 | Batch (~1s) | `whisper-large-v3-turbo` | `llama-3.3-70b-versatile` | Ultra-fast batch multilingual ASR + open-weights LLM |
| **G3** | **OpenAI Single-Key** | 1 | Batch (~1.2s) | `gpt-4o-mini-transcribe` / `whisper-1` | `gpt-4o-mini` | Simple single-key OpenAI setup |
| **H1** | **Hybrid: Local ASR + Cloud LLM** | 1 | Local (~0.4s) | On-device `SenseVoiceSmall` (`sherpa-onnx`) | `gemini-3.5-flash-lite` / `gpt-4o-mini` / `deepseek-chat` | **Privacy + Power**: raw audio stays on phone; cloud polishes & writes |
| **L1** | **Self-Hosted LAN Ollama** | 0 | Local / LAN | On-device `SenseVoiceSmall` or LAN `whisper.cpp` | LAN `Ollama` (`qwen3:8b` / `qwen2.5:7b`) | Home/office Wi-Fi, zero external cloud traffic |
| **C0** | **DeepSeek Single-Key** | 1 | Batch (~1.5s) | `deepseek-asr` (`/v1/tools/asr`) | `deepseek-chat` | **Simplest CN/Bilingual 1-Key**: WAV ASR + writing + Ask AI |
| **C1** | **Alibaba Bailian Single-Key** | 1 | **Yes** | `paraformer-realtime-v2` (WS) | `qwen-turbo-latest` / `qwen-plus-latest` | 1 DashScope `sk-` key for both live streaming ASR & Qwen |
| **C2** | **Volcengine Doubao Streaming** | 1–2 | **Yes** | `bigmodel` (SeedASR 2.0 WS) | `doubao-seed-2-1-lite-260915` | Ultra-accurate Chinese & dialect live dictation |
| **C3** | **SiliconFlow Single-Key** | 1 | Batch (~0.8s) | `FunAudioLLM/SenseVoiceSmall` | `Qwen/Qwen2.5-7B-Instruct` / `deepseek-ai/DeepSeek-V3` | Free high-speed cloud SenseVoice ASR |
| **M1** | **CN Streaming + DeepSeek Brain** | 2 | **Yes** | Bailian `paraformer-realtime-v2` or Doubao `bigmodel` | `deepseek-chat` | **Best CN Mix**: live word-by-word ASR + DeepSeek writing |

---

## 3. Global, Hybrid & Local LAN Setups (Step-by-Step)

### 3.1 Google Gemini — Single Key for ~0.5s Live Streaming + `gemini-3.5-flash-lite`

1. Create an API key (`AIza...`) in [Google AI Studio](https://aistudio.google.com/apikey).
2. Open TypeFree → **`Voice & AI → Models → Quick Setup: On-Device & Cloud Keys`**, and tap the **`Google Gemini`** tile.
3. Paste your API key and tap **`🚀 Set as Primary Provider`**:
   - **Stage-1 Speech Engine**: Automatically configures **`gemini-3.5-transcribe-live`** over `GEMINI_LIVE_WS` (`https://generativelanguage.googleapis.com/v1beta`), streaming partial words in ~0.5s while stripping fillers and merging self-corrections on the fly (with automatic HTTP `.m4a` fallback via `gemini-3.5-flash-lite` if WebSockets are blocked by a corporate proxy).
   - **Stage-2 Writing & Ask AI**: Pre-binds **`gemini-3.5-flash-lite`** (with Thinking disabled for snappy response). Because Gemini Live already cleans spoken fillers during streaming, Stage-2 post-dictation polish is left **OFF** by default for instant ~0.5s release, while **`✍️ Help Me Write`**, **`🔍 Ask AI`**, **`🪄 Speak-to-Edit`**, and **`🌐 Live Translate`** automatically invoke `gemini-3.5-flash-lite` on the same key. (Turn the Stage-2 toggle **ON** anytime if you want custom per-app writing tones applied after dictation.)

---

### 3.2 Groq — Ultra-Fast `whisper-large-v3-turbo` + `llama-3.3-70b-versatile`

1. Create an API key (`gsk_...`) in [Groq Console](https://console.groq.com/keys).
2. In **`Quick Setup: On-Device & Cloud Keys`**, tap **`Custom / Gateway`**.
3. Set **Base URL** to `https://api.groq.com/openai/v1` and paste your `gsk_...` key.
4. Tap **`🚀 Set as Primary Provider`**, then in **`2. Workflow & Multi-Provider Mix`**:
   - Under **`👂 Speech Engine`**, select or type **`whisper-large-v3-turbo`**.
   - Under **`🧠 Stage-2 Polish, Writing & Translation LLM`**, select or type **`llama-3.3-70b-versatile`**.

---

### 3.3 OpenAI — Single Key (`gpt-4o-mini-transcribe` + `gpt-4o-mini`)

1. Create an API key (`sk-...`) in [OpenAI Platform](https://platform.openai.com/api-keys).
2. In **`Quick Setup: On-Device & Cloud Keys`**, tap **`Custom / Gateway`**.
3. Keep **Base URL** as `https://api.openai.com/v1`, paste your `sk-...` key, and tap **`🚀 Set as Primary Provider`**.
4. Pick **`gpt-4o-mini-transcribe`** (or `whisper-1`) for **`👂 Speech Engine`** and **`gpt-4o-mini`** for **`🧠 Stage-2 LLM`**.

---

### 3.4 Local LAN Ollama (+ On-Device ASR or `whisper.cpp`)

Run LLMs on your desktop/home server over local Wi-Fi with $0 API cost and zero external cloud traffic:

1. Start Ollama on your computer exposed to your local network:
   ```bash
   OLLAMA_HOST=0.0.0.0:11434 ollama serve
   ollama pull qwen2.5:7b   # or qwen3:8b / llama3.2
   ```
2. For **Stage-1 Speech**, install **`SenseVoiceSmall`** inside TypeFree's **`📱 On-Device Offline`** (`Model Hub`) — or run an OpenAI-compatible `whisper.cpp` / `faster-whisper-server` endpoint on your LAN.
3. In **`Quick Setup: On-Device & Cloud Keys`**, tap **`Custom / Gateway`**:
   - **Base URL**: `http://192.168.x.x:11434/v1` (replace with your computer's LAN IP)
   - **API Key**: `ollama` (any non-empty placeholder token)
4. In **`2. Workflow & Multi-Provider Mix`**, tap **⚡ Fetch & Speed Test** on the Stage-2 LLM slot to load your installed Ollama models (`qwen2.5:7b`, `qwen3:8b`, etc.) and select one.

---

### 3.5 Hybrid Mode: On-Device `SenseVoiceSmall` + Cloud LLM

Hybrid routing gives you **100% local audio privacy** (voice recordings never leave your phone) paired with **frontier cloud LLM quality** for cleanup, `✍️ Help Me Write`, and `🔍 Ask AI`:

1. In **`Quick Setup: On-Device & Cloud Keys`**, tap **`📱 On-Device Offline`** → open **`Model Hub`** and download **`SenseVoiceSmall`** (or streaming `Paraformer`), keeping the local **ASR slot enabled** in Hybrid mode (do **not** lock Pure Offline mode).
2. Save your preferred cloud key (**Google Gemini**, **OpenAI**, **Groq**, or **DeepSeek**) in its Provider Vault tile and select it under **`🧠 Stage-2 Polish, Writing & Translation LLM`**.
3. When you dictate, `sherpa-onnx` transcribes speech locally in `:local_ai`, and only the resulting text transcript (or your writing prompt) is sent to your chosen cloud LLM.

---

## 4. China-Direct Providers & DeepSeek Mix-and-Match

For users on Mainland China direct connections (without proxies) or users who prefer Chinese-optimized speech and writing models:

### 4.1 DeepSeek Official — 1 Key for ASR + Polish + Help Write + Ask AI

1. Create an API key (`sk-...`) on [DeepSeek Platform](https://platform.deepseek.com/).
2. In **`Quick Setup: On-Device & Cloud Keys`**, select **`DeepSeek`**, paste your `sk-...` key, and tap **`🚀 Set as Primary Provider`**:
   - **Stage-1 STT**: Connects directly to `POST https://api.deepseek.com/v1/tools/asr` (uploads 16 kHz mono WAV; auto-detects Mandarin, Cantonese, English, Japanese, and Korean).
   - **Stage-2 LLM**: Configures `deepseek-chat` with Thinking disabled for low-latency polish, `✍️ Help Me Write`, and `🔍 Ask AI`.

### 4.2 Alibaba Bailian (DashScope) — 1 `sk-` Key for WebSocket Live ASR + Qwen

1. Create a DashScope API key (`sk-...`) in the [Alibaba Bailian Console](https://bailian.console.aliyun.com/).
2. Select **`Alibaba Bailian`** in the Provider Vault, paste your `sk-...` key, and tap **`🚀 Set as Primary Provider`**:
   - **Stage-1 STT**: Enables live WebSocket streaming with `paraformer-realtime-v2` (alternatives: `fun-asr-realtime` or multilingual `gummy-realtime-v1`).
   - **Stage-2 LLM**: Uses `qwen-turbo-latest` (non-thinking fast mode; switchable to `qwen-plus-latest`).

### 4.3 Volcengine Doubao — SeedASR 2.0 Live WebSocket + Ark Seed-2.1

Volcengine separates **Doubao Speech (streaming ASR)** and **Volcengine Ark (text LLMs)** into two console sections, and TypeFree provides dedicated fields for both:

1. **Doubao Speech Credentials (Stage-1 `bigmodel` SeedASR 2.0)**:
   - Open [Volcengine Speech Console](https://console.volcengine.com/speech/app), create an app, and activate **Streaming ASR BigModel** (default Resource ID `volc.seedasr.sauc.duration` is pre-filled; if your account uses legacy 1.0 `volc.bigasr.sauc.duration`, override `Volcengine Resource ID` in Layer 3 Advanced Overrides).
   - If given **`App ID` + `Access Token`**, fill field 2 (`Speech Access Token`) and field 3 (`Speech AppID`). If given a unified **`X-Api-Key`**, fill field 2 only and leave `AppID` blank.
2. **Volcengine Ark Credentials (Stage-2 LLM)**:
   - Open [Volcengine Ark Console](https://console.volcengine.com/ark), create an API key for field 1 (`Ark API Key`), and enable `doubao-seed-2-1-lite-260915` (or enter a custom endpoint `ep-xxx`).
3. Tap **`🚀 Set as Primary Provider`**.

### 4.4 SiliconFlow — Free High-Speed `SenseVoiceSmall` + Qwen / DeepSeek

1. Create an `sk-...` key in [SiliconFlow Console](https://cloud.siliconflow.cn/).
2. Select **`SiliconFlow`**, paste your key, and tap **`🚀 Set as Primary Provider`** to pair free `FunAudioLLM/SenseVoiceSmall` HTTP transcription with `Qwen/Qwen2.5-7B-Instruct` (or `deepseek-ai/DeepSeek-V3`).

### 4.5 Two-Step Mix: Live Streaming ASR + DeepSeek Text Brain

To combine **word-by-word live streaming ASR** (Alibaba Bailian or Volcengine Doubao — or free SiliconFlow ASR) with **official `deepseek-chat`**:

1. **Step 1 (Set the Ears)**: Select **Alibaba Bailian**, **Volcengine Doubao**, or **SiliconFlow** in the Provider Vault, enter its key, and tap **`🚀 Set as Primary Provider`**.
2. **Step 2 (Swap the Brain to DeepSeek)**: Select **`DeepSeek`**, enter your `sk-...` key, and tap **`🧠 Keep Current ASR & Use DeepSeek for Text Brain Only`**.
   - TypeFree preserves your Stage-1 streaming ASR configuration while routing Stage-2 spoken cleanup, `✍️ Help Me Write`, `🔍 Ask AI`, and `🌐 Live Translate` to `deepseek-chat`.

---

## 5. Latency Tuning & Troubleshooting (FAQ)

### ⚡ Speed Tip: Instant Streaming Commit vs. Stage-2 LLM Polish

At the top-right corner of the **`🧠 Stage-2 Polish, Writing & Translation LLM`** card sits an enable switch:
- **Switch ON (Default for non-Gemini presets)**: Every dictation runs a quick non-thinking LLM pass (~0.4–0.8s) to strip fillers, fix mid-sentence self-corrections, and adapt tone to the current app.
- **Switch OFF (~0.3s Instant Release)**: If your Stage-1 streaming engine (`Gemini Live`, `Doubao SeedASR 2.0`, or `Bailian Paraformer`) is already accurate enough, turn this switch **OFF** for immediate commit on mic release — **`✍️ Help Me Write`**, **`🔍 Ask AI`**, and **`🪄 Speak-to-Edit`** still use your configured Stage-2 LLM automatically!

### Common Issues

1. **"Speech Engine (Stage-1 STT) has no API key configured…"**
   - Your active Stage-1 provider slot does not have a saved key yet (and no local offline ASR model is active). Pick a ready provider tile in Layer 1 (`Google Gemini`, `DeepSeek`, `Bailian`, `Custom / Gateway`, or `📱 On-Device Offline`) and tap **`🚀 Set as Primary Provider`**.
2. **HTTP 404 / Model Not Found on SiliconFlow, Groq, or OpenAI**
   - Verify you did not accidentally enter a text LLM ID (`deepseek-chat`, `gpt-4o-mini`) inside **`👂 Speech Engine`** or an audio model (`whisper-large-v3-turbo`, `FunAudioLLM/SenseVoiceSmall`) inside **`🧠 Stage-2 LLM`**.
3. **Volcengine Doubao `401` / Handshake Error**
   - Make sure field 2 contains your **Doubao Speech** `Access Token` / `X-Api-Key` (not just an Ark text key) and that free/paid quota for **`volc.seedasr.sauc.duration`** is activated in the Volcengine Speech console.
4. **How do I benchmark real latency or diagnose a slow stage?**
   - Tap **⚡ Fetch & Speed Test** next to any model input to measure live `1-Token` latency (`ms`) across models.
   - After dictating, expand **Raw & Latency** on any item in **`Home → Recent Voice & Edit History`** to view the **Full-Chain Voice Latency Waterfall** (`Mic Init → First Partial → Audio Flush → Stage-1 ASR → Stage-2 TTFT & Stream → Editor Commit`).
