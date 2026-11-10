# TypeFree — Android 原生 AI 语音输入法与全局悬浮胶囊

<p align="center">
  <img src="docs/logo.svg" width="108" height="108" alt="TypeFree Logo" />
</p>

<p align="center">
  受现代极简声波美学启发，纯原生 <b>Kotlin + Jetpack Compose + Material 3</b> 构建的开源 Android AI 语音键盘与全局悬浮胶囊。<br/>
  全面践行 <b>BYOK (Bring Your Own Key)</b> 与私有化部署理念，零订阅门槛、零第三方隐私采集，让自然语音输入与智能改写回归纯粹自由。
</p>

<p align="center">
  <a href="https://github.com/dengxuezhao/TypeFree/releases/tag/v2.7.0"><img src="https://img.shields.io/badge/Release-v2.7.0-10B981?style=flat-square&logo=github" alt="Release v2.7.0" /></a>
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_1.7-4285F4?style=flat-square&logo=android&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Design-Material_3-000000?style=flat-square" alt="Material 3" />
  <img src="https://img.shields.io/badge/Min_SDK-26_(Android_8.0+)-brightgreen?style=flat-square" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Target_SDK-35_(Android_15)-green?style=flat-square" alt="Android 15" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=flat-square" alt="License: GPL v3.0" />
</p>

<p align="center">
  📦 <b>最新预编译安装包</b>：<a href="https://github.com/dengxuezhao/TypeFree/releases/download/v2.7.0/TypeFree-v2.7.0-release.apk">下载 TypeFree-v2.7.0-release.apk</a> · <a href="https://github.com/dengxuezhao/TypeFree/releases/tag/v2.7.0">查看 v2.7.0 发行说明</a>
</p>

---

## 🆕 v2.7.0 核心升级亮点

- **原生 C++ `librime 1.17.0` NDK 引擎与雾凇拼音 (`rime-ice`) 深度集成**：新增 `:rime` NDK/JNI 模块，将上游 `librime 1.17.0` 核心及 `librime-lua`、`librime-octagram`、`librime-predict` 插件编译为原生 `arm64-v8a` 动态库；开箱内置 **全拼（雾凇拼音）**、**小鹤双拼（整句）** 与 **小鹤音形（四码顶功）** 三大方案一键切换，支持拼音整句组词、简拼（如 `jttq` → `今天天气`）、动态词频自学习与 `2D` 展开候选网格，并支持在词典页一键 SHA-256 校验下载 `58 万` 雾凇完整词库或挂载自定义 YAML 方案。
- **全面板 5 档无极帧高统一 (`KeyboardMetrics`) & 4×2 快捷工具面板**：以单一 `KeyboardMetrics.frameHeight` 驱动语音、26 键、快译、剪贴板、帮我写、改写等全部面板，彻底消除展开文风条或切换面板时的 `298→334dp` 高度跳变；支持 **紧凑 (`268dp`) / 偏低 (`283dp`) / 标准 (`298dp`) / 偏高 (`316dp`) / 舒适 (`333dp`)** 五档高度调节，顶栏左侧新增 `⊞` 4×2 快捷工具面板（收纳方案切换、实时写入、联想词、高度调节、无痕模式等开关）。
- **流式边说边上屏 (`StreamingComposer`) & 超长听写无感续接**：新增基于 `InputConnection.setComposingText` 的流式直写状态机，支持在说话同时将识别草稿实时写入宿主输入框并在松手后无感替换为润色定稿（遇光标移动打断自动冻结防覆盖，支持一键退回原文）；针对超过 60 秒的长语音自动执行静音边界会话轮换与按句分块并发润色。
- **L1/L2/L3 三层紧邻联想词 & 隐私无痕模式 (`Incognito Mode`)**：汉字上屏后 `34.dp` 候选栏自动呈现静态高频搭配 (`L1`)、个人二元词频 (`L2 SQLite Bigram`) 与可选 AI 续写 (`L3`)；在密码框、宿主 `IME_FLAG_NO_PERSONALIZED_LEARNING` 或手动开启无痕模式时，自动暂停一切个人词频写入、Rime 用户词典落盘、剪贴板监听与语音历史记录。

<details>
<summary>📖 展开查看 v2.6.1 / v2.5.0 / v2.4.0 历史升级亮点</summary>

### v2.6.1
- **Google Gemini Live 原生流式直出 & `45s` 热连接预热池**：将 `Gemini Live WebSocket` (`BidiGenerateContent`) 升级为一等公民实时流式引擎，边说边出字，原生支持 **智能净写直出 (`SMART`)**（端到端去语气词、自动标点与个人词典热词注入，省去二次 LLM 往返）与 **忠实逐字 (`VERBATIM`)** 双档切换；键盘弹出即后台预热握手连接池，配合尾帧人声能量门限与自适应静默收束，消除短词松手等待，已出终稿实现 **`0ms` 秒回**，网络受阻时自动回退 `.m4a` HTTP 极速转写。
- **三层「统一接口工作台」与厂商凭证凭证保险箱 (`Provider Vault`)**：重构 API 设置页为「凭证保险箱 → 一键极速配好 → 按需展开高级路由微调」三层清爽结构；独立加密保存 Google、阿里云百炼、火山引擎、硅基流动、DeepSeek 与自定义网关凭证，自由切换服务商与任意模型不丢 Key。
- **外放媒体音频焦点自动避让 & 轻声耳语增益 (`Whisper Mode`)**：录音按下瞬间自动请求 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` 暂停外放音乐/播客防止背景歌词串入，松手自动恢复；新增轻声耳语模式，对麦克风 16-bit PCM 流执行实时自适应软限幅增益放大，安静办公或深夜轻语同样高准识别。
- **零延迟口令短语展开 (`Voice Snippets`) & 历史诊断排版优化**：支持语音口令（如“发我的工作邮箱”）及四码键盘快捷码本地 **`0ms` 直接展开**长文本模板；优化历史记录原始初稿与流式诊断卡片布局，长模型名清晰对齐零遮挡。

### v2.5.0
- **双通道 WebSocket 实时流式 ASR（边说边出字 + 松手百毫秒直达）**：新增 **火山豆包流式 SeedASR 2.0**（二进制协议 + Gzip 帧编解码 + 个人词典热词直注）与 **阿里云百炼 DashScope 实时流式**（`paraformer-realtime-v2` / `fun-asr-realtime`，单 `sk-` Key 免签名直连），录音期间在键盘、全局悬浮胶囊与首页沙盒实时渲染增量预览，网络波动时自动无感回退 HTTP 整包转写。
- **全厂商通用 `Thinking` 思考开关 & `Doubao-Seed-2.x` 零思考极速适配**：支持用户在 LLM 与多模态卡片自主切换 `关闭思考 (极速)` / `模型默认` / `开启思考`；精准适配火山方舟 `Doubao-Seed-2.x`（`reasoning_effort="minimal"`）、`Seed-1.6` / 智谱 `GLM-4.5+`（`thinking.type`）、通义千问 `Qwen3+`（`enable_thinking`）、OpenAI `o/GPT-5` 与 `Gemini 2.5/3.x`，内置 `ep-...` 推理接入点双向语法自愈记忆，4G 移动网络下实测润色耗时从 `6629 ms` 直降至 **`839 ms`（提速 `7.9 倍`）**。
- **下拉模型列表 `1-Token` 真实毫秒级测速 & 内置请求诊断日志**：彻底移除硬编码速度标签，模型弹窗支持一键并发 Ping 账号下可用模型（展示真实 `ms` 徽章并按延迟自动升序置顶，直观区分 `403 未开通` / `401` / `429`）；连通性实测卡片内嵌 HTTP 逐跳耗时、Prefix Cache 命中与 `reasoning_tokens` 诊断面板并支持一键复制。
- **全链路低延迟基础设施重构**：长连接池保活 + 按下麦克风异步 `TLS/HTTP2` 端点预热、100% 静态 `System Prompt` 前缀缓存对齐、流式直写 WAV + `MediaCodec` 硬件 AAC `.m4a` 体积压缩（缩小 ~85%）及多帧自适应抗机械按键声 VAD。

### v2.4.0
- **全面板恒定 `298.dp` 帧高与顶栏 `(0, 0) px` 像素级坐标锁死**：统一六大工作台面板高度与 `34.dp` 顶栏锚点，跨模式切换实现 **0px 位移零抖动**。
- **可选顶部独立数字行 (`1–0`) 与 `~1.14 : 1` 黄金键帽人体工学重构**：新增独立数字行开关、3D 实体底棱按压反馈与底部防误触安全垫。
- **记忆上次输入面板模式 & 密码框安全保护**：跨会话恢复上次键盘面板与中英状态，密码框自动强制切入纯净英文键盘。
- **选中文字 `Speak to edit` 双向无感互通 & `360dp` 窄屏防挤压适配**：打字态选词顶栏按需呼出 `AI 改写`，支持一键切回打字覆盖或剪贴板替换。

</details>

---

## ✨ 核心特色概览

```
┌────────────────────────────────────────────────────────────────────────┐
│                               TypeFree                                 │
├──────────────────────────────────┬─────────────────────────────────────┤
│      🚀 无感全局悬浮胶囊 (Bubble)   │       ⌨️ 纯原生双模键盘 (Keyboard)    │
│  - 任意输入框聚焦自动无感呼出     │  - 极简暖白/曜石黑沉浸主题          │
│  - Android 13+ 光标级直插通道    │  - 左右横滑无感穿梭语音与打字       │
│  - 零剪贴板污染替换选中文本      │  - 开箱内置开源双拼与符号快捷映射   │
│  - 密码/受保护输入框主动避让     │  - Rime/TSV 四码音形码表热挂载      │
├──────────────────────────────────┴─────────────────────────────────────┤
│                          🧠 现代 AI 语音引擎流水线                     │
│  - BYOK 双引擎：STT + LLM 双阶段极速流水线 / 多模态音频一步直出         │
│  - 四大意图：口述净写 / 帮我写 / 选中文本语音改写 / 上滑弧形直译       │
│  - 宿主应用自适应文风感知 (App-Aware) / 打字改错 Diff 词典自学习      │
│  - 智能剪贴板关键信息提取 (验证码/单号/链接) / 脱敏配置一键安全分享    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🌟 核心独家优势

### 1. 完全自由的 BYOK 双引擎与本地离线架构
TypeFree 不绑定任何商业闭源云端，支持完全自主配置的 API 调度矩阵：
- **STT + LLM 双阶段 / 单流极速流水线**：
  - **语音转写 (STT)**：支持 **Google Gemini Live WebSocket**（支持 `SMART` 端到端智能净写一步直出与 `45s` 热连接预热池）、**火山豆包流式 SeedASR 2.0** 与 **阿里云百炼 Paraformer-Realtime** 三通道 WebSocket 实时流式识别（边说边出字、松手 `0~500ms` 直出），同时兼容硅基流动 (SiliconFlow) SenseVoiceSmall、Groq Whisper-large-v3、OpenAI Whisper API 及私有部署端点。
  - **智能净写与排版 (LLM)**：兼容 Google Gemini 3.5 / 3.8 / 2.5 系列、火山方舟 Doubao-Seed-2.x / 1.6、阿里云百炼 Qwen3 / Qwen-Turbo、DeepSeek-V3 / R1、OpenAI GPT-4o / GPT-5、智谱 GLM-4.5+ 等任意原生或 OpenAI 兼容接口，支持自定义开关深度思考（`Thinking`）与一键毫秒级测速选型。
- **Audio Direct 多模态原生音频一步直出**：
  - 支持 Google Gemini 3.5 Flash Lite / 3.8 Flash / 2.5 Flash、OpenAI GPT-4o Audio，将原生音频流直传多模态模型，一步完成听音理解、语意净写与格式重构。
- **局域网与端侧零成本私有化**：
  - 支持局域网连接 `whisper.cpp` server 与 `Ollama`（如 Qwen2.5:7b），全程离线运行，严苛保护敏感商务与政务数据。
- **聚合网关智能适配**：
  - 内置针对 NewAPI / OneAPI / 聚合中转网关的智能路由推导与 Path 自动重构；同一服务商下支持配置跨 STT/LLM 自动同步，避免重复粘贴 Key。

### 2. 免切输入法的「全局无感悬浮语音胶囊 (`Voice Bubble`)」
- **日常输入法零割裂**：无需放弃您多年习惯的 Gboard、微信键盘、搜狗或仓颉。只需开启 Android 无障碍与悬浮窗权限，当任意前台 App 的输入框聚焦时光标旁自动静默浮现极简声波圆球。
- **密码框安全避让**：自动检测 `TYPE_TEXT_VARIATION_PASSWORD`、PIN 码与指纹校验界面，遇敏感安全场景自动隐藏。
- **Android 13+ (`API 33+`) 原生光标级直插通道**：
  - 优先通过 `AccessibilityService.takeScreenshot()` 与 `AccessibilityInputConnection` 原生通道写入，**零剪贴板写入与零历史污染**，毫秒级实现光标定位插入或替换选中文本；
  - 旧版本系统自动无缝降级为无障碍选区替换。

### 3. 四大 AI 语音意图模式
- 🎙️ **口述净写 (Dictate)**：
  - 深度消除语气词口癖（“那个”、“然后”、“就是说”等）、句中口误自动改口识别、智能标点断句与多行条目自动排版；
  - 注入工业级反机器聊天护栏（Strict Guardrails），绝对禁止模型输出“好的”、“这是为您修改的内容”等废话，只输出纯净正文。
- ✍️ **帮我写 (Help me write)**：
  - 简短口述意图核心，AI 自动扩写为得体详实的正式回复、提案或工作总结。
- 🪄 **选中文本语音改写 (Speak to edit)**：
  - 实时捕获宿主应用选中的原文内容，并与内置全键盘、智能剪贴板双向一键桥接互通（打字态选词不强跳面板，顶栏按需轻点 `AI 改写` 呼出，或从改写面板右上角一键切回打字覆盖/剪贴板粘贴）；
  - 底部浮现快捷改写指令芯片（“润色文采”、“扩写详述”、“精简摘要”、“更委婉专业”、“转为英文”）；
  - 配套顶栏固定圆形一键撤销按钮（Undo），窄屏零挤压、随时还原初始文本。
- 🌐 **长按上滑弧形转盘直译 (Release to translate)**：
  - 悬浮胶囊长按录音过程中，向上滑动触发交互式弧形环轮，自由滑选目标语言（中、英、日、韩、西、德、法等），松手即刻上屏纯正外语译文。

### 4. 宿主应用感知自适应文风 (`App-Aware Styles`)
- 自动识别当前输入框所在的宿主应用程序包名；
- 动态映射最佳语气格式规范：
  - **即时通讯**（微信 / 企微 / Telegram / WhatsApp / 钉钉）：短句亲切自然、分段得体；
  - **办公写作**（飞书文档 / 语雀 / 钉钉文档 / Notion / 石墨）：要点清晰、逻辑分明、Markdown 列表；
  - **邮件沟通**（Gmail / Outlook / 网易邮箱大师）：正式礼貌、称谓完备；
  - **开发者终端 / 笔记**（Termux / Obsidian / GitHub / VS Code）：保留英文专有名词、代码符号与技术术语原样输出。

### 5. 硬核中英双模全键盘与音形/Rime 码表热挂载引擎
- **零抖动人体工学视觉美学**：
  - 极简暖白单色（Light Monochrome）/ 曜石暗夜（Dark Obsidian）/ 跟随系统深色模式，全模式恒定 `298.dp` 高度与顶栏 `(0, 0) px` 像素级对齐，左右横滑在「声波语音控制面板」、「打字全键盘」与「实时直译」间零位移丝滑切换；
  - `~1.14 : 1` 舒适键帽比例、3D 实体底棱按压反馈、可选顶部独立数字行（`1–0`）与底部防误触安全垫；支持记忆上次键盘面板模式及密码框自动切英文保护。
- **开源双拼开箱即用**：
  - 采用通用标准双拼方案与现代汉语规范常用字词，开箱即可流畅击键输入单字、双字词组与标点快捷符号。
- **四码定长音形 / Rime 码表热挂载（独家特色）**：
  - App 词典页面原生支持导入用户的私有 TSV 码表（如小鹤音形、五笔 86/98、虎码、仓颉等标准四码定长顶功码表）；
  - 码表导入后覆盖写入应用私有存储目录，键盘引擎自动热重载并优先生效，支持 `;` 快捷符号映射、次选快速上屏与 `?` / `` ` `` 通配符双向反查；
  - 随时提供一键「恢复默认内置词库」机制，兼顾开源纯净与重度输入法极客的深度可玩性。

### 6. 智能剪贴板提取面板 (`Smart Clipboard`)
- 键盘内集成智能剪贴板工作台；
- 基于本地 Regex 引擎对剪贴板内容进行隐私安全的纯本地高频实体提取：
  - ⚡ **短信验证码**（4-6 位数字与字母验证码）
  - 📦 **快递取件码 / 单号**
  - 🔗 **纯净 URL 链接**（一键剔除杂乱说明文本）
  - 📧 **电子邮箱地址**
- 支持项目置顶收藏、按类别快速筛选与过期历史一键清除。

### 7. 个人词典 Diff 自学习与安全脱敏配置分享
- **输入纠错 Diff 自动沉淀**：
  - 当语音识别输出后，若用户手动在键盘中改动了专有名词或特定用字，TypeFree 自动比对改动前后的文本差异（Myers Diff），智能萃取修正项沉淀为候选专有名词。
- **安全脱敏配置导出分享**：
  - 一键将自己的精心调优的模型提示词、文风规则、常用词表打包为 JSON 配置文件；
  - **内置安全红线**：导出时自动脱敏剥离全部真实的 API Key，可放心发送给同事或社区好友导入合并。

---

## 🛠️ 推荐 API 配置组合指南

进入应用 `设置 -> API 配置` 页面，可根据个人使用习惯自由搭配以下高性价比方案：

| 场景需求 | 录音识别引擎 (STT) | 智能润色引擎 (LLM) | 综合延迟 | 成本预估 |
| :--- | :--- | :--- | :--- | :--- |
| **单 Key 流式极速直出** | Google `Gemini Live WebSocket` (`SMART` 净写直出) | `gemini-3.5-flash-lite`（仅写作/改写按需调用） | **0~500ms** | 极低 API 计费 |
| **国内双阶段极速** | 阿里云百炼 `paraformer-realtime-v2` / 火山 `SeedASR 2.0` | `doubao-seed-2-1-lite` / `deepseek-chat` | ~500ms | 极低（低至 ¥0.001/千字） |
| **海外免翻极速** | Groq `whisper-large-v3` | OpenAI `gpt-4o-mini` | ~500ms | 极低或免费 Tier |
| **多模态整包直出** | Google Gemini `gemini-3.5-flash-lite` (Audio Direct) | 一步直出 | ~600ms | 极低 API 计费 |
| **局域网全离线** | 本地 `whisper.cpp` Server (`192.168.x.x`) | 本地 `Ollama` (`qwen2.5:7b`) | 视硬件性能 | **¥0 完全免费** |

---

## 📖 自定义音形/Rime 码表制作与导入指引

TypeFree 支持用户将个人常用的码表（如小鹤音形、五笔、自然码等）一键挂载至键盘中：

### 1. 码表格式规范
码表为 UTF-8 编码的纯文本制表符分隔（TSV）文件：
- **主词库 TSV (`dict.tsv`)**：每一行格式为 `编码\t候选词1\t候选词2...`
  ```tsv
  uijm	时间	实践
  xhyx	小鹤音形
  tfre	TypeFree
  ```
- **单字四码反查表 TSV (`dz.tsv`, 可选)**：用于通配符（`` ` ``）查形查音，格式为 `编码\t单字`
  ```tsv
  jibo	瘠
  dkal	丁
  ```

### 2. 生成与挂载方式
1. **使用内置脚本转换 Rime 词典**：
   ```bash
   python3 scripts/build_rime_assets.py \
       --base-dict /path/to/flypy.dict.yaml \
       --dz-dict /path/to/flypydz.dict.yaml \
       --out-dir ./my_tables
   ```
2. **手机端直接导入**：
   - 打开 TypeFree 主应用，切换到底部 **「词典」** 选项卡；
   - 在顶部的 **「四码音形 / Rime 码表」** 卡片中点击 **「导入自定义码表 (TSV)」**；
   - 点击 **「选择主词库文件 (.tsv/.txt)」**（通过 Android SAF 系统安全文件选择器直接选取手机存储或网盘中的码表文件），可选导入单字四码反查表；
   - 确认后后台协程流式写入私有存储并即刻完成热挂载。若需恢复默认双拼，随时点击 **「恢复默认」**。

---

## 💻 源码编译与运行

### 环境要求
- **Android SDK**: Compile SDK 35 / Min SDK 26 (Android 8.0 Oreo+)
- **JDK**: OpenJDK 17 或 21
- **构建工具**: Gradle 8.11+ / Android Gradle Plugin 8.8+

### 编译命令
```bash
# 克隆仓库
git clone https://github.com/dengxuezhao/TypeFree.git
cd TypeFree

# 执行全量单元测试
./gradlew test

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease
# 产物输出路径: app/build/outputs/apk/release/app-release.apk
```

---

## 🔒 隐私声明与安全性

1. **零中心服务器**：TypeFree 本身不运营任何数据收集服务器，无埋点统计、无广告 SDK、无追踪器。
2. **直连您的服务端点**：您的音频流与改写文本仅向您在本地配置的 API 端点（或您自建的本地 Ollama/Whisper 服务）发送。
3. **密码框主动静默**：全局悬浮胶囊遇到密码、PIN、指纹等受保护输入框自动避让隐藏。
4. **可控历史留存**：支持选择历史记录留存周期（3 天 / 7 天 / 30 天 / 永久 / 立即清除），数据均加密持久化存储在 Android 私有空间 `EncryptedSharedPreferences` 中。

---

## 📄 开源许可证

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 协议开源。
欢迎社区开发者提交 Issue、Pull Request 与优化建议！
