# TypeFree

<p align="center">
  <img src="docs/logo.svg" width="108" height="108" alt="TypeFree Logo" />
</p>

<p align="center">
  <b>Android 原生「AI 语音 + librime 码表/拼音全键盘」双引擎输入法与全局悬浮胶囊</b><br/>
  纯原生 <b>Kotlin + Jetpack Compose + Material 3 + C++ librime 1.17.0</b> 构建。<br/>
  全面践行 <b>BYOK (Bring Your Own Key)</b> 与本地优先理念，零订阅门槛、零中心服务器、零隐私埋点。
</p>

<p align="center">
  <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><img src="https://img.shields.io/badge/Release-v3.7.0-10B981?style=flat-square&logo=github" alt="Release v3.7.0" /></a>
  <img src="https://img.shields.io/badge/Engine-librime_1.17.0_+_Lua-0EA5E9?style=flat-square" alt="librime 1.17.0" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?style=flat-square&logo=android&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Android-8.0+_(SDK_26~35)-brightgreen?style=flat-square" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/License-GPL_v3.0-blue.svg?style=flat-square" alt="License: GPL v3.0" />
</p>

<p align="center">
  📦 <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><b>下载最新公开标准版 APK</b></a> ·
  📘 <a href="docs/features-guide.md"><b>完整功能使用手册</b></a> ·
  🔌 <a href="docs/domestic-api-setup-guide.md"><b>国内 API / DeepSeek 配置指南</b></a> ·
  ✦ <a href="docs/jev-decision-tree.md"><b>Jev 军师决策树白皮书</b></a>
</p>

---

## 为什么做 TypeFree？

传统手机输入法往往在「**高效打字**」与「**现代 AI 语音/写作**」之间顾此失彼：
- 开源 Rime 输入法打字极客、隐私干净，但缺乏现代流式 AI 语音与智能创作体验；
- 商业 AI 语音键盘绑定月费订阅、数据过厂商云端，且对双拼、音形、五笔、键道等高效中文码表支持薄弱。

**TypeFree 把两套引擎做进了同一个轻量原生输入法里**：
- **想打字时**：原生 C++ `librime 1.17.0`（含 `librime-lua`）+ 26 键三向手势 + 零干扰码表增强 + 200 条智能剪贴板，完全离线、丝滑跟手；
- **想说话或求助 AI 时**：用你自己的 API Key 直连 Gemini Live / DeepSeek / 阿里云百炼 / 豆包 / 本地 Ollama，边说边写、随手查、纯净帮写、军师读屏一步到位。

---

## 🆕 v3.7.0 核心升级亮点

- **⌨️ 26 键三向手势与可定制键帽角标**
  - 每个字母键完全解耦 **点击（字母）/ 上滑（默认快符与撤回重复）/ 长按（默认数字、副符号与全选剪复粘）**，全部支持可视化逐键自定义。
  - 键帽角标升级为顶部居中紧凑符号（`Tab → ⇥`、`撤回 → ↩`、`重复 → ↻`、`全角 → ␣`），提供 **「上滑角标 / 长按角标 / 隐藏角标」** 三档切换；选择「隐藏角标」后主字母自动居中复原。
- **🧩 原生 `librime` 多方案码表增强与零干扰体系**
  - 内置 **雾凇全拼、小鹤双拼、小鹤音形**，词库页支持一键安装 **星空键道 6**、**五笔 86**，并支持同时导入、管理与切换多套自定义 Rime 方案。
  - 针对码表用户新增：四码 5 键宽次选底排、上滑句号 `/` 引导编码短语、`o*` 快捷指令（日期/时间/反查/单字/简繁）、L1+L2 本地造词（含重复打字一次性建议芯片与 `0ms` 即时生效）、自然延续与逆向切码、组合态回车直出英文无多余换行。
- **📐 系统大字体防扭曲保护 & 全新 4 标签应用架构**
  - IME 键盘根节点独立锁定 `fontScale = 1.0f`，宿主 App 合理钳制 `0.85x~1.15x`，系统调至极大字号时键帽与界面不再错位变形。
  - 主应用全面重构为 **「首页 · 键盘 · 语音与 AI · 通用」** 四大清爽标签，键盘能力与 AI 能力各自成区、按方案自动隔离无关开关。
- **⚡ 日常打字效率套件**
  - 输入框算式自动提示（如 `125*8=` 自动给出计算结果芯片，支持空格边界隔离）、EN 模式本地前缀树英文补全、智能剪贴板 2.0 扩容至 **200 条**（含双胶囊取件码提取与大爆炸拆词）。

<details>
<summary>📖 展开查看 v3.5.x ~ v2.4.0 历史里程碑</summary>

- **v3.5.1**：DeepSeek 官方语音识别（`/v1/tools/asr`）单 Key 全链路闭环，一把 Key 同时驱动听音、润色、帮写、问 AI 与军师。
- **v3.5.0**：✦ Jev 军师升级五层正交决策树（新增事实筛子与内行平视三轴）；✍️ 帮写回归零读屏纯净创作台；🔍 顶栏动态「问 AI」键盘内安全解答卡。
- **v3.0.0**：Android 11+ 无障碍按需截屏多模态识别（解决微信/QQ 自绘气泡读屏难题）、顶栏验证码/取件码双胶囊粘贴、智能剪贴板 2.0 与大爆炸拆词。
- **v2.9.0**：键盘高度双模调节（5 档等比缩放 `STRETCH` + 5 档底部垫高 `LIFT`）。
- **v2.7.0**：集成原生 C++ `librime 1.17.0` NDK 引擎、雾凇拼音、`⊞` 4×2 快捷面板、流式边说边写（`StreamingComposer`）与无痕模式。
- **v2.6.1 / v2.5.0**：Google Gemini Live、火山豆包 SeedASR 2.0、阿里云百炼实时流式三通道语音引擎；三层统一接口工作台与 `1-Token` 实测测速。

</details>

---

## ✨ 双引擎能力全景

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                                TypeFree v3.7                                 │
├──────────────────────────────────────┬───────────────────────────────────────┤
│   ⌨️ 离线原生 librime 1.17.0 全键盘   │     🎙️ BYOK AI 语音与智能创作台      │
│  • 雾凇全拼 / 小鹤双拼 / 小鹤音形     │  • Gemini Live / 豆包 / 百炼 / DeepSeek│
│  • 一键预设 星空键道6 / 五笔86 + 自定义│  • 流式边说边写 + 超 60s 无感续接      │
│  • 26 键点击/上滑/长按独立可定制      │  • 全局悬浮语音胶囊（免切键盘语音输入）│
│  • 顶部角标三档切换（含极简居中复原） │  • ✦ Jev 军师（脱敏读屏 + 关系记忆）   │
│  • 上滑句号 / 短语 + 5 键宽次选底排   │  • ✍️ 零读屏纯净帮写 + 🔍 键盘内问AI   │
│  • L1+L2 自造词 + 单字模式 + o* 指令  │  • 🪄 选中改写 + 🌐 实时快译/弧形直译  │
├──────────────────────────────────────┴───────────────────────────────────────┤
│   ⚡ 效率与体验底座：输入框算式自动提示 · EN 离线联想 · 200 条剪贴板+大爆炸    │
│      缩放/垫高双模高度 · 大字体防扭曲 · 端侧脱敏 · 无痕模式 · 脱敏备份迁移    │
└──────────────────────────────────────────────────────────────────────────────┘
```

> 每一项功能的详细操作步骤、26 键默认上滑/长按速查表与 `o*` 指令大全，请查阅 👉 **[《TypeFree 详细功能使用手册》](docs/features-guide.md)**。

---

## 🚀 5 分钟快速上手

1. **安装与启用**：从 [GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest) 下载安装公开标准版 APK，打开主应用在 **首页** 完成「启用输入法 → 切换输入法」（如需悬浮胶囊或军师读屏，再按需开启无障碍服务）。
2. **纯打字即刻可用**：离线打字、26 键手势、剪贴板、算式提示与自定义 Rime 方案 **无需配置任何 API Key**，开箱即用。
3. **接入 AI 语音与创作（可选）**：进入 **语音与 AI → 模型与 API**，在凭证保险箱填入任一厂商 Key 并点选一键预设即可使用；支持 **⚡ 1-Token 实测测速** 自动按延迟排序。

### 推荐 API 组合对照

| 使用场景 | 语音识别引擎（STT） | 润色 / 创作 / 军师（LLM） | 参考延迟 | 特点 |
| :--- | :--- | :--- | :---: | :--- |
| **海外单 Key 极速流式** | Google `Gemini Live WebSocket` | `gemini-2.5-flash` / `flash-lite` | ~0–500ms | 边说边出字、多模态识图 |
| **DeepSeek 单 Key 闭环** | DeepSeek 官方 `/v1/tools/asr` | `deepseek-chat` | ~600ms | 1 把 Key 通吃语音与文本 |
| **国内双阶段流式极速** | 阿里云百炼 / 火山豆包 `SeedASR 2.0` | `doubao-seed` / `deepseek-chat` | ~500ms | 国内直连低延迟、极低成本 |
| **海外免配置流式备选** | Groq `whisper-large-v3` | OpenAI `gpt-4o-mini` | ~500ms | 轻量稳定 |
| **局域网全离线方案** | 本地 `whisper.cpp` Server | 本地 `Ollama`（如 `qwen2.5:7b`） | 视设备性能 | 完全本地化、¥0 成本 |

详细配置教程见 👉 [国内直连 API 与 DeepSeek 混搭配置指南](docs/domestic-api-setup-guide.md)。

---

## 📚 文档导航

| 文档 | 内容说明 |
| :--- | :--- |
| [📘 详细功能使用手册](docs/features-guide.md) | 全功能操作说明、4 标签导航指引、26 键手势速查表、`o*` 指令表与 FAQ |
| [🔌 国内直连 API 与 DeepSeek 混搭指南](docs/domestic-api-setup-guide.md) | 豆包、百炼千问、硅基流动与 DeepSeek 详细申请与配置步骤 |
| [✦ Jev 军师五层决策树白皮书](docs/jev-decision-tree.md) | 端侧脱敏、群聊角色拓扑、事实筛子与关系记忆设计说明 |
| [⚙️ GitHub 自动化与构建指南](docs/github-automation-guide.md) | CI 门禁、Release 流水线、Issue 分诊与多变体构建说明 |

---

## 💻 源码编译与测试

项目采用多模块结构（`:core` 纯 JVM 引擎层 + `:rime` C++ NDK 层 + `:app` Android UI 与服务层），提供 `standard`（公开标准版）与 `xiaohe`（本地隔离专享构建）两个 Product Flavor：

```bash
# 1. 克隆仓库
git clone https://github.com/dengxuezhao/TypeFree.git
cd TypeFree

# 2. 拉取并校验上游 librime 1.17.0 C++ 依赖源码（首次编译需要）
bash rime/src/main/cpp/fetch_librime_deps.sh

# 3. 运行核心层与 Android 标准版单元测试
./gradlew :core:test :app:testStandardDebugUnitTest

# 4. 编译公开标准版 Debug / Release APK
./gradlew assembleStandardDebug
./gradlew assembleStandardRelease
# 输出路径: app/build/outputs/apk/standard/release/
```

> **版权与合规说明**：公开仓库严格遵循 GPL-3.0 协议，不包含任何受限私有商业/EULA 码表资产（`app/src/xiaohe/assets/rime/` 与 `local_private/` 均已通过 `.gitignore` 及 CI 门禁强制隔离）。用户可在应用内「键盘 → 词库管理」自由导入个人合法持有的 Rime 方案包。

---

## 🔒 隐私声明

1. **零中心服务器**：不收集用户输入内容、无埋点统计、无广告 SDK、无第三方追踪器。
2. **直连个人端点**：语音音频与 AI 文本请求仅发往用户在本地填写的 API 服务地址或自建内网服务。
3. **按需读屏与端侧脱敏**：仅在用户主动点击 **✦ 军师** 时按需读取当前可见聊天区域，手机号、身份证号、微信号等先在端侧正则脱敏再发往模型，关闭面板立即清零内存；**✍️ 帮写** 与 **🔍 问AI** 全程零读屏、零截屏。
4. **无痕与敏感界面熔断**：密码框、支付及银行应用自动禁用读屏、悬浮球与词频学习；本地凭证加密保存在 `EncryptedSharedPreferences`。

---

## 📄 开源许可证与反馈

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 开源。
欢迎通过 [GitHub Issues](https://github.com/dengxuezhao/TypeFree/issues) 提交缺陷报告与功能建议，或直接发起 Pull Request！
