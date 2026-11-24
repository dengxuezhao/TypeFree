<p align="center">
  <b>简体中文</b> | <a href="README_EN.md">English</a>
</p>

# TypeFree

<p align="center">
  <img src="docs/logo.svg" width="108" height="108" alt="TypeFree Logo" />
</p>

<p align="center">
  <b>Android 原生「智能英文 / librime 中文码表全键盘 + 端侧离线 / BYOK AI 语音与创作」双引擎输入法</b><br/>
  纯原生 <b>Kotlin + Jetpack Compose + Material 3 + C++ librime 1.17.0 + sherpa-onnx + llama.cpp</b> 构建。<br/>
  全面践行 <b>本地优先 + BYOK (Bring Your Own Key)</b> 理念，GPL-3.0 开源、零订阅门槛、零中心服务器、零隐私埋点。
</p>

<p align="center">
  <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><img src="https://img.shields.io/badge/Release-v3.9.0-10B981?style=flat-square&logo=github" alt="Release v3.9.0" /></a>
  <img src="https://img.shields.io/badge/Engine-librime_1.17.0_+_Lua-0EA5E9?style=flat-square" alt="librime 1.17.0" />
  <img src="https://img.shields.io/badge/Local_AI-sherpa--onnx_+_llama.cpp-F59E0B?style=flat-square" alt="Local AI" />
  <img src="https://img.shields.io/badge/UI-简体中文_%7C_English_%7C_繁體中文-6366F1?style=flat-square" alt="Trilingual UI" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-8.0+_(SDK_26~35)-brightgreen?style=flat-square" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=flat-square" alt="License: GPL v3.0" />
</p>

<p align="center">
  📦 <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><b>GitHub Releases 下载（开源标准版）</b></a> ·
  📘 <a href="docs/features-guide.md"><b>完整功能使用手册</b></a> ·
  🔌 <a href="docs/domestic-api-setup-guide.md"><b>国内 API / DeepSeek 配置指南</b></a> ·
  🌐 <a href="README_EN.md"><b>English README</b></a><br/>
  💬 功能建议与 Bug 反馈 QQ 群：<b>TypeFree输入法反馈群（<code>801143863</code>）</b>
</p>

---

## 为什么做 TypeFree？

传统手机输入法往往在「**高效打字**」与「**现代 AI 语音/写作**」之间顾此失彼：
- 开源 Rime 输入法打字极客、隐私干净，但缺乏现代智能英文滑行输入与流式 AI 语音体验；
- 商业 AI 语音键盘绑定月费订阅、数据过厂商云端，且对双拼、音形、五笔、键道等高效中文码表支持薄弱。

**TypeFree 把两套引擎做进了同一个轻量原生开源输入法里**：
- **想打字时**：原生 C++ `librime 1.17.0`（含 `librime-lua`）+ 26 键三向手势 / 9 键拼音（T9）+ **智能英文 QWERTY（滑行输入、自动纠错、下一词预测）** + 中英混输与离线联想 + 零干扰码表增强 + 200 条智能剪贴板，完全离线、丝滑跟手；
- **想说话或求助 AI 时**：既可**零 Key 纯端侧离线运行**（`sherpa-onnx` 离线语音 + `llama.cpp` 本地 Qwen 0.8B 净写/基础改写），也可用你自己的 API Key 直连 Gemini Live / DeepSeek / 阿里云百炼 / 豆包 / OpenAI / 本地 Ollama，边说边写、选中划词 `🔍 问 AI`、零读屏 `✍️ 帮写` 一步到位。

---

## 🆕 v3.9.0 核心升级亮点

- **🔤 智能英文 QWERTY 全面升级（纯离线零延迟）**
  - 内置离线英文词典、**滑行输入轨迹识别（Swipe-to-Type）**、**自动拼写纠错（Auto-Correction）**、**下一词二元预测（Next-Word Bigram Prediction）**、**句首自动大写（Auto-Capitalization）** 与 **双击空格自动句号（Double-Space Period）**。
  - 在密码框、邮箱、网址与搜索框内自动禁用纠错与自动大写，避免误改内容。
- **🌐 完整三语界面（`简体中文` / `English` / `繁體中文`）**
  - 主应用全部页面、设置项、引导流程、表达洞察图表、演练场与软键盘面板全面原生支持三语即时切换，并自动对齐中英文 AI 提示词语言。
- **📐 实测自适应图标折叠顶栏（Measured Adaptive Icon-Collapse TopBar）**
  - 软键盘顶栏根据实际可用宽度与当前语言标签宽度动态测算布局：宽屏完整展示 `[🔍 问AI]`、`[✍️ 帮写/改写]`、`[🎙 语音]` 文字胶囊；窄屏、英文界面或无痕状态下逐个平滑折叠为 `28dp` 圆形纯图标按钮，不遮挡、不截断、不丢失入口。
- **🔍 划词「问 AI」键盘内解答卡 & ✍️ 零读屏纯净帮写**
  - 在输入框选中任意文字或输入满 2 字即可点亮顶栏 **🔍 问AI** 胶囊，答案在键盘内部卡片流式呈现，支持一键复制、填入输入框或清空回退（可撤回原问题）。
  - **✍️ 帮写** 全程零读屏、零截屏，支持空框体裁模板与已有碎词草稿一键扩写/润色。
- **🧠 端侧离线 AI + BYOK 云端流式双引擎**
  - 独立 `:local_ai` 进程内置 `sherpa-onnx`（8 款开源离线语音模型）与 `llama.cpp`（`Qwen 0.8B` 端侧净写/基础改写），断网零 Key 可用；同时支持 BYOK 直连云端流式语音与大模型。
- **📊 本地零隐私表达洞察与 Token 可观测性**
  - 仅在设备端记录零文本每日数字计数，提供语音 × 打字双轨统计、56 天活跃热力图、24 小时节律、云端/离线语音时长拆分、云端 Token 用量（含缓存命中率）与 v2 完整无损备份迁移。

<details>
<summary>📖 展开查看 v3.8.x ~ v2.5.0 历史里程碑</summary>

- **v3.8.3**：完整端侧离线语音（`sherpa-onnx`）与 `llama.cpp` 本地小模型链路；雾凇全拼/小鹤双拼中英混输与离线联想；官方小鹤音形原包 `.zip` 一键导入；可选「上滑回退键清空输入框」。
- **v3.8.1 / v3.8.2**：内置虎码官方字词（`tigress`）/ 单字（`tiger`）、五笔 86、星空键道 6 一键开源预设；全新 T-Wing 图标。
- **v3.8.0**：原生九键拼音（T9 九宫格，共享雾凇开源词库、左侧音节筛选栏 + 3×4 大键数字盘）、26 键三向手势与顶部居中紧凑角标、系统大字体防扭曲保护。
- **v3.5.1**：DeepSeek 官方语音识别（`/v1/tools/asr`）单 Key 全链路闭环。
- **v3.5.0**：✍️ 帮写升级零读屏纯净创作台；🔍 顶栏动态「问 AI」键盘内安全解答卡。
- **v3.0.0**：顶栏验证码/取件码双胶囊粘贴、智能剪贴板 2.0 与大爆炸拆词。
- **v2.9.0**：键盘高度双模调节（5 档等比缩放 `STRETCH` + 5 档底部垫高 `LIFT`）。
- **v2.7.0**：集成原生 C++ `librime 1.17.0` NDK 引擎、雾凇拼音、`⊞` 4×2 快捷面板、流式边说边写（`StreamingComposer`）与无痕模式。
- **v2.6.1 / v2.5.0**：Google Gemini Live、火山豆包 SeedASR 2.0、阿里云百炼实时流式三通道语音引擎与 `1-Token` 实测测速。

</details>

---

## ✨ 双引擎能力全景

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                               TypeFree v3.9.0                                │
├──────────────────────────────────────┬───────────────────────────────────────┤
│  ⌨️ 离线智能英文 + librime 1.17.0     │   🎙️ 端侧离线 + BYOK AI 语音与创作台  │
│  • 智能英文：滑行输入/纠错/下一词预测 │  • 端侧离线：sherpa-onnx + llama.cpp  │
│  • 中文内置：雾凇全拼 / 九键T9 / 双拼 │  • 云端直连：Gemini/豆包/百炼/DeepSeek│
│  • 一键预设：星空键道6 / 五笔86 / 虎码│  • 流式边说边写 + 超 60s 无感续接      │
│  • 原包导入：官方 Rime .zip 直接导入  │  • 全局悬浮语音胶囊（免切键盘语音输入）│
│  • 26键三向手势 + 九键左侧音节筛选栏  │  • 🔍 划词/顶栏问AI（键盘内安全卡片）  │
│  • 实测自适应图标折叠顶栏（三语适配） │  • ✍️ 零读屏纯净帮写 + 🪄 选中改写     │
│  • L1+L2 自造词 + 单字模式 + o* 指令  │  • 🌐 实时快译/弧形语种轮盘直译        │
├──────────────────────────────────────┴───────────────────────────────────────┤
│   ⚡ 效率与隐私底座：三语原生UI · 表达式计算 · 200条剪贴板+大爆炸 · 表达洞察   │
│      独立 :local_ai 进程 · LocalPiiRedactor 脱敏 · 无痕模式 · 完整无损备份    │
└──────────────────────────────────────────────────────────────────────────────┘
```

> 每一项功能的详细操作步骤、26 键默认上滑/长按速查表与 `o*` 指令大全，请查阅 👉 **[《TypeFree 详细功能使用手册》](docs/features-guide.md)**。

---

## 📦 内置方案与多格式 Rime 方案导入

TypeFree 内置原生 `librime 1.17.0` + `librime-lua` 引擎，兼顾开箱即用与自由扩展：

- **开箱内置（开源词库：`rime_ice` 雾凇拼音 + 智能英文）**
  - **智能英文 QWERTY**：纯离线英文词典、滑行输入、自动纠错、下一词预测、自动大写与双击空格句号；
  - **雾凇全拼（`rime_ice`）**：26 键整句全拼，支持应用内一键 SHA-256 校验下载 58 万完整词库；
  - **九键拼音（`t9`）**：原生九宫格整句拼音（共享雾凇开源词库），自带左侧实时拼音音节筛选栏与 3×4 大键数字盘。
- **内置双拼键位方案（复用雾凇开源词库）**
  - **小鹤双拼（整句 `double_pinyin_flypy`）**：基于 Rime 社区双拼键位映射规则并复用开源雾凇词库实现整句双拼（不含任何非开源私有词库）。
- **应用内一键安装开源码表（`键盘 → 词库管理`）**
  - **星空键道 6（`xkjd6`，GPL-3.0）**：内置 `librime-lua` `topup_processor` 原生顶功；
  - **五笔 86（`wubi86`，LGPL-3.0）**：经典四码定长纯形码表，第 5 码自动顶首选；
  - **虎码官方字词（`tigress`）/ 虎码官方单字（`tiger`）**：四码自动顶屏、全码直观输入。
- **支持导入的方案与词库格式**
  - **完整 Rime 方案包（`.zip` 双轨导入）**：同时兼容明文词库轨道（`.schema.yaml` + `.dict.yaml` / `.yaml` / `.tsv` / `.txt` + 可选 `.lua` / `opencc/`）与预编译二进制轨道（`build/*.table.bin` / `.prism.bin` / `.reverse.bin`）；
  - **独立分词库挂载** & **多方案并存切换**：在「键盘 → 方案与布局」自由切换，非对应方案的专属指令自动隔离互不干扰。
- **小鹤音形用户导入指引**
  - 为严格遵守小鹤官方版权与授权协议，本仓库与公开安装包**不包含任何小鹤音形官方私有形码码表**。
  - 请自行前往小鹤官方渠道下载合法的 **Rime 挂接包（`.zip`）**，无需解压，在 **「键盘 → 词库管理 → 导入 Rime 方案包 (.zip)」** 中直接导入即可自动启用四码顶功、5 键宽次选底排、`;` 快捷符号、`o*` 指令与本地造词增强。

---

## 🚀 5 分钟快速上手

1. **安装与启用**：前往 **[GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest)** 下载最新开源标准版 APK（`TypeFree-v*-standard-release.apk`），打开主应用按 4 步引导完成「启用输入法 → 切换输入法」（如需全局悬浮语音胶囊，可按需开启无障碍服务）。
2. **纯打字与端侧离线语音零 Key 可用**：智能英文、离线中文打字、26 键手势、剪贴板、计算提示与 Rime 方案导入开箱即用；在 **语音与 AI → 离线模型** 下载本地语音模型与 `Qwen 0.8B` 后，断网也能完成语音听写、口语净写与基础改写。
3. **接入云端 AI 语音与创作（可选 BYOK）**：进入 **语音与 AI → 模型与 API**，在凭证保险箱填入任一厂商 Key 并点选一键预设即可使用；支持 **⚡ 1-Token 实测测速** 自动按当前网络真实延迟排序，并可按「混合智能 / 仅离线 / 仅云端」自由路由。

### 推荐语音与模型组合对照

> 实际响应时间受设备算力、网络环境、语音时长与模型服务端排队波动影响，以下为常规短句（3–10 秒）体感参考，云端模型建议在应用内使用 **⚡ 测速** 实测。

| 使用场景 | 语音识别引擎（STT） | 润色 / 帮写 / 问 AI（LLM） | 交互方式与体感延迟参考 | 特点 |
| :--- | :--- | :--- | :--- | :--- |
| **手机端侧纯离线（零 Key）** | 本地 `SenseVoiceSmall` / 流式 `Paraformer`（`sherpa-onnx`） | 本地 `Qwen 0.8B` 净写模型（`llama.cpp`） | 手机端独立进程推理，断网可听写、净写与基础改写 | 零 API 费、数据完全不出手机 |
| **海外单 Key 流式** | Google `Gemini Live WebSocket` | `gemini-3.5-flash-lite` / `gemini-3.8-flash` | 流式边说边出字（首字通常 0.5–1.5s，受海外链路影响） | 单 Key 覆盖流式听音与创作问答 |
| **国内双阶段流式** | 阿里云百炼 / 火山豆包 `SeedASR 2.0` | `doubao-seed-2-1-lite` / `deepseek-chat` | 流式实时出草稿，松手后润色定稿（定稿通常 0.8–2s） | 国内直连稳定、边说边看反馈快 |
| **DeepSeek 单 Key** | DeepSeek 官方 `/v1/tools/asr` | `deepseek-chat` | 整段音频上传识别 + 润色（通常 1.5–3.5s，视语音长度） | 1 把 Key 通吃语音与文本，配置最简 |
| **海外非流式备选** | Groq `whisper-large-v3-turbo` | `gpt-4o-mini` / `gemini-3.5-flash-lite` | 整段音频上传识别 + 润色（通常 1–2.5s，受海外链路影响） | 识别准确度高、组合成熟 |
| **局域网全离线** | 本地 `whisper.cpp` Server | 本地 `Ollama`（如 `qwen3:8b`） | 取决于本地主机 GPU / CPU 算力 | 数据不出内网、¥0 API 成本 |

详细国内配置教程见 👉 [国内直连 API 与 DeepSeek 配置指南](docs/domestic-api-setup-guide.md)。

---

## 📚 文档导航

| 文档 | 内容说明 |
| :--- | :--- |
| [🌐 English README](README_EN.md) | Complete English documentation for TypeFree v3.9.0 |
| [📘 详细功能使用手册](docs/features-guide.md) | 全功能操作说明、4 标签导航指引、26 键手势速查表、`o*` 指令表与 FAQ |
| [🔌 国内直连 API 与 DeepSeek 配置指南](docs/domestic-api-setup-guide.md) | DeepSeek、豆包、百炼千问与硅基流动详细申请与混搭配置步骤 |
| [⚙️ GitHub 自动化与构建指南](docs/github-automation-guide.md) | CI 门禁、Release 流水线与 Issue 分诊说明 |

---

## 💻 源码编译与测试

项目采用多模块结构（`:core` 纯 JVM 引擎层 + `:rime` C++ NDK 层 + `:localai-native` `llama.cpp` JNI 层 + `:app` Android UI 与服务层）：

```bash
# 1. 克隆仓库
git clone https://github.com/dengxuezhao/TypeFree.git
cd TypeFree

# 2. 拉取并校验上游 librime 1.17.0 C++ 依赖源码（首次编译需要）
bash rime/src/main/cpp/fetch_librime_deps.sh

# 3. 拉取并校验端侧离线 AI 依赖（sherpa-onnx AAR 与 llama.cpp 锁定提交，首次编译需要）
bash scripts/fetch_offline_deps.sh

# 4. 运行核心层与 Android 单元测试
./gradlew :core:test :app:testStandardDebugUnitTest

# 5. 编译开源标准版 Debug / Release APK
./gradlew :app:assembleStandardDebug
./gradlew :app:assembleStandardRelease
# 输出路径: app/build/outputs/apk/standard/release/
```

> **版权与合规说明**：本仓库与公开安装包严格遵循 GPL-3.0 协议，仅包含开源词表（雾凇拼音、星空键道 6、五笔 86、虎码预设、开源英文词典）及复用开源词库的社区双拼键位映射，不含任何受限私有商业/EULA 码表资产，并通过 `.gitignore` 与 CI 门禁持续防误提交。用户可在应用内「键盘 → 词库管理」自由导入个人合法获取的第三方官方 Rime 方案包。

---

## 🔒 隐私声明

1. **零中心服务器**：不收集用户输入内容、无埋点统计、无广告 SDK、无第三方追踪器。
2. **端侧离线与直连个人端点**：启用离线模型后，语音识别与端侧小模型推理完全在本地独立 `:local_ai` 进程完成，音频与文本不出设备；使用云端模型时，请求仅直连用户在本地填写的 API 服务地址或自建内网服务。
3. **零读屏创作与端侧脱敏引擎**：**✍️ 帮写** 与 **🔍 问AI** 全程零读屏、零截屏；内置端侧隐私脱敏引擎（`LocalPiiRedactor`），支持身份证、银行卡、密钥与口令本地识别防护。
4. **无痕与敏感界面熔断**：密码框、支付及银行应用自动禁用悬浮球、词频学习与统计记录；本地 API 凭证采用 Android Keystore + AES-GCM（`SecretVault` / `EncryptedSharedPreferences`）加密存储。

---

## 📄 开源许可证与反馈

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 开源。

- **GitHub 反馈与贡献**：欢迎通过 [GitHub Issues](https://github.com/dengxuezhao/TypeFree/issues) 提交缺陷报告与功能建议，或直接发起 Pull Request。
- **QQ 反馈交流群**：功能建议、日常使用交流或 Bug 反馈，欢迎加入 **TypeFree输入法反馈群**（QQ 群号：`801143863`）。
