# TypeFree — Android 原生 AI 语音输入法与全局悬浮胶囊

<p align="center">
  <img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="108" height="108" alt="TypeFree Logo" />
</p>

<p align="center">
  受现代极简声波美学启发，纯原生 <b>Kotlin + Jetpack Compose + Material 3</b> 构建的开源 Android AI 语音键盘与全局悬浮胶囊。<br/>
  全面践行 <b>BYOK (Bring Your Own Key)</b> 与私有化部署理念，零订阅门槛、零第三方隐私采集，让自然语音输入与智能改写回归纯粹自由。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_1.7-4285F4?style=flat-square&logo=android&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Design-Material_3-000000?style=flat-square" alt="Material 3" />
  <img src="https://img.shields.io/badge/Min_SDK-26_(Android_8.0+)-brightgreen?style=flat-square" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Target_SDK-35_(Android_15)-green?style=flat-square" alt="Android 15" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=flat-square" alt="License: GPL v3.0" />
</p>

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
- **STT + LLM 双阶段极速流水线**：
  - **语音转写 (STT)**：兼容 Groq Whisper-large-v3（~300ms 极速响应）、硅基流动 (SiliconFlow) SenseVoiceSmall（~150ms 超低延迟、极度精准的中英粤识别）、OpenAI Whisper API 及私有部署端点。
  - **智能净写与排版 (LLM)**：兼容 DeepSeek-V3 / R1、OpenAI GPT-4o-mini、Claude 3.5 Haiku、Qwen 2.5 等任意 OpenAI 兼容接口。
- **Audio Direct 多模态原生音频一步直出**：
  - 支持 Google Gemini 2.5 Flash / Flash Lite、OpenAI GPT-4o Audio，将 16kHz 16-bit 单声道 WAV 原生流直传多模态模型，一步完成听音理解、语意净写与格式重构。
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
  - 实时捕获宿主应用选中的原文内容；
  - 底部浮现快捷改写指令芯片（“润色文采”、“扩写详述”、“精简摘要”、“更委婉专业”、“转为英文”）；
  - 配套光标旁一键撤销（Undo），随时还原初始文本。
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
- **优雅视觉美学**：
  - 极简暖白单色（Light Monochrome）/ 曜石暗夜（Dark Obsidian）/ 跟随系统深色模式，左右横滑无感在「声波语音控制面板」与「打字全键盘」间无缝滑移。
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
| **极致极速推荐** | 硅基流动 `SenseVoiceSmall` | DeepSeek `deepseek-chat` (V3) | ~600ms | 极低（低至 ¥0.001/千字） |
| **海外免翻极速** | Groq `whisper-large-v3` | OpenAI `gpt-4o-mini` | ~500ms | 极低或免费 Tier |
| **多模态直出** | Google Gemini `gemini-2.5-flash` (Audio Direct) | 一步直出 | ~900ms | 极低 API 计费 |
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
   - 可直接粘贴 TSV 文本，或输入手机本地文件路径（如 `/sdcard/Download/my_flypy_dict.tsv`）；
   - 点击 **「导入挂载」** 即可完成热挂载。若需恢复默认双拼，随时点击 **「恢复默认」**。

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
# 产物输出路径: app/build/outputs/apk/release/app-release-unsigned.apk
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
