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
  <a href="https://github.com/dengxuezhao/TypeFree/releases/latest"><img src="https://img.shields.io/badge/Release-v3.8.0-10B981?style=flat-square&logo=github" alt="Release v3.8.0" /></a>
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
  ✦ <a href="docs/jev-decision-tree.md"><b>Jev 军师决策树白皮书</b></a><br/>
  💬 功能建议与 Bug 反馈 QQ 群：<b>TypeFree输入法反馈群（<code>801143863</code>）</b>
</p>

---

## 为什么做 TypeFree？

传统手机输入法往往在「**高效打字**」与「**现代 AI 语音/写作**」之间顾此失彼：
- 开源 Rime 输入法打字极客、隐私干净，但缺乏现代流式 AI 语音与智能创作体验；
- 商业 AI 语音键盘绑定月费订阅、数据过厂商云端，且对双拼、音形、五笔、键道等高效中文码表支持薄弱。

**TypeFree 把两套引擎做进了同一个轻量原生输入法里**：
- **想打字时**：原生 C++ `librime 1.17.0`（含 `librime-lua`）+ 26 键三向手势 / 9 键拼音（T9）+ 零干扰码表增强 + 200 条智能剪贴板，完全离线、丝滑跟手；
- **想说话或求助 AI 时**：用你自己的 API Key 直连 Gemini Live / DeepSeek / 阿里云百炼 / 豆包 / 本地 Ollama，边说边写、随手查、纯净帮写、军师读屏一步到位。

---

## 🆕 v3.8.0 核心升级亮点

- **🔢 原生九键拼音（T9 九宫格）正式上线**
  - 新增基于 `librime` + 雾凇开源词库的九键拼音方案（`t9.schema.yaml`），不额外增加词库体积，支持整句输入、顶部悬浮胶囊实时显示可读拼音切分（如 `ni hao`、`今天 tian qi`）。
  - 左侧竖列空闲为高频标点、编码时自动切换为**合法拼音音节筛选栏**（支持点击锁定、部分选词后自动推进下一段、退格三态自然回退），并内置独立 **3×4 大键数字九宫格** 与中/英双页符号层；切英文或进入密码框自动落至 26 键英文。
- **⌨️ 26 键三向手势与可定制键帽角标**
  - 每个字母键完全解耦 **点击（字母）/ 上滑（默认快符与撤回重复）/ 长按（默认数字、副符号与全选剪复粘）**，全部支持可视化逐键自定义。
  - 键帽角标升级为顶部居中紧凑符号（`Tab → ⇥`、`撤回 → ↩`、`重复 → ↻`、`全角 → ␣`），提供 **「上滑角标 / 长按角标 / 隐藏角标」** 三档切换；选择「隐藏角标」后主字母自动居中复原。
- **🧩 原生 `librime` 多方案码表增强与预编译挂接自愈**
  - 开箱内置开源 **雾凇全拼、九键拼音（T9）** 以及复用雾凇词库的 **小鹤双拼** 键位方案；词库页一键安装开源 **星空键道 6**、**五笔 86**，并支持同时导入、管理与切换多套自定义 Rime 方案（兼容明文词库包与预编译 `.table.bin` 挂接包，含预编译码表防误删与自动自愈保护）。
  - 针对码表用户提供：四码 5 键宽次选底排、上滑句号 `/` 引导编码短语、`o*` 快捷指令（日期/时间/反查/单字/简繁）、L1+L2 本地造词（含重复打字一次性建议芯片与 `0ms` 即时生效）、空码实时 26 槽自然延续探测与逆向切码、组合态回车直出英文无多余换行。
- **📐 系统大字体防扭曲保护 & 全新 4 标签应用架构**
  - IME 键盘根节点独立锁定 `fontScale = 1.0f`，宿主 App 合理钳制 `0.85x~1.15x`，系统调至极大字号时键帽与界面不再错位变形。
  - 主应用全面重构为 **「首页 · 键盘 · 语音与 AI · 通用」** 四大清爽标签，首页支持 Jev 军师无障碍软引导，键盘能力与 AI 能力各自成区、按方案自动隔离无关开关。
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
│                                TypeFree v3.8                                 │
├──────────────────────────────────────┬───────────────────────────────────────┤
│   ⌨️ 离线原生 librime 1.17.0 双布局   │     🎙️ BYOK AI 语音与智能创作台      │
│  • 内置方案：雾凇全拼 / 九键T9 / 双拼 │  • Gemini Live / 豆包 / 百炼 / DeepSeek│
│  • 一键预设：星空键道6 / 五笔86       │  • 流式边说边写 + 超 60s 无感续接      │
│  • 多格式导入：Rime .zip / 分词库挂载 │  • 全局悬浮语音胶囊（免切键盘语音输入）│
│  • 26键三向手势 + 九键左侧音节筛选栏  │  • ✦ Jev 军师（脱敏读屏 + 关系记忆）   │
│  • 上滑句号 / 短语 + 5 键宽次选底排   │  • ✍️ 零读屏纯净帮写 + 🔍 键盘内问AI   │
│  • L1+L2 自造词 + 单字模式 + o* 指令  │  • 🪄 选中改写 + 🌐 实时快译/弧形直译  │
├──────────────────────────────────────┴───────────────────────────────────────┤
│   ⚡ 效率与体验底座：输入框算式自动提示 · EN 离线联想 · 200 条剪贴板+大爆炸    │
│      缩放/垫高双模高度 · 大字体防扭曲 · 端侧脱敏 · 无痕模式 · 脱敏备份迁移    │
└──────────────────────────────────────────────────────────────────────────────┘
```

> 每一项功能的详细操作步骤、26 键默认上滑/长按速查表与 `o*` 指令大全，请查阅 👉 **[《TypeFree 详细功能使用手册》](docs/features-guide.md)**。

---

## 📦 内置方案与多格式 Rime 方案导入

TypeFree 内置原生 `librime 1.17.0` + `librime-lua` 引擎，兼顾开箱即用与自由扩展：

- **开箱内置（开源词库：`rime_ice` 雾凇拼音）**
  - **雾凇全拼（`rime_ice`）**：26 键整句全拼，支持应用内一键 SHA-256 校验下载 58 万完整词库；
  - **九键拼音（`t9`）**：原生九宫格整句拼音（共享雾凇开源词库），自带左侧实时拼音音节筛选栏与 3×4 大键数字盘。
- **内置双拼键位方案（复用雾凇开源词库）**
  - **小鹤双拼（整句 `double_pinyin_flypy`）**：基于 Rime 社区双拼键位映射规则并复用开源雾凇词库实现整句双拼（不含任何非开源私有词库）。
- **应用内一键安装开源码表（`键盘 → 词库管理`）**
  - **星空键道 6（`xkjd6`，GPL-3.0）**：内置 `librime-lua` `topup_processor` 原生顶功；
  - **五笔 86（`wubi86`，LGPL-3.0）**：经典四码定长纯形码表，第 5 码自动顶首选。
- **支持导入的方案与词库格式**
  - **完整 Rime 方案包（`.zip` 双轨导入）**：
    - **明文词库轨道**：包含 `.schema.yaml` + `.dict.yaml` / `.yaml` / `.tsv` / `.txt`（及可选 `.lua` 脚本、`opencc/` 转换表、`.toml` 增强配置），导入后在设备端自动编译部署；
    - **预编译二进制轨道**：直接兼容包含 `build/*.table.bin`、`build/*.prism.bin`、`build/*.reverse.bin` 与 `.schema.yaml` 的预编译挂接包，无需明文码表即可原样加载并自动识别码表原型。
  - **独立分词库 / 补充词表挂载**：支持单独导入 `.dict.yaml`、`.yaml` / `.yml`、`.tsv`、`.txt` 文件挂载为当前方案的扩展词库并按需开关。
  - **多方案并存切换**：可同时导入多套自定义 Rime 方案，在「键盘 → 方案与布局」一键切换，非对应方案的专属指令自动隔离互不干扰。
- **小鹤音形用户导入指引**
  - 为严格遵守小鹤官方版权与授权协议，本仓库与公开安装包**不包含任何小鹤音形官方私有形码码表**。
  - 请自行前往小鹤官方渠道下载合法的 **Rime 挂接包（`.zip`）**，在应用内进入 **「键盘 → 词库管理 → 导入 Rime 方案包 (.zip)」** 选择该压缩包，导入完成后即可自动启用四码顶功、5 键宽次选底排、`o*` 指令与本地造词增强。

---

## 🚀 5 分钟快速上手

1. **安装与启用**：从 [GitHub Releases](https://github.com/dengxuezhao/TypeFree/releases/latest) 下载安装 APK，打开主应用在 **首页** 完成「启用输入法 → 切换输入法」（如需悬浮胶囊或军师读屏，再按需开启无障碍服务）。
2. **纯打字即刻可用**：离线打字、26 键手势、剪贴板、算式提示、内置开源方案与自定义 Rime 方案导入 **无需配置任何 API Key**，开箱即用。
3. **接入 AI 语音与创作（可选）**：进入 **语音与 AI → 模型与 API**，在凭证保险箱填入任一厂商 Key 并点选一键预设即可使用；支持 **⚡ 1-Token 实测测速** 自动按当前网络真实延迟排序。

### 推荐 API 组合对照

> 实际响应时间受用户网络环境、代理链路、语音时长与模型服务端排队波动影响，以下为常规短句（3–10 秒）体感参考，建议在应用内使用 **⚡ 测速** 实测。

| 使用场景 | 语音识别引擎（STT） | 润色 / 创作 / 军师（LLM） | 交互方式与体感延迟参考 | 特点 |
| :--- | :--- | :--- | :--- | :--- |
| **海外单 Key 流式** | Google `Gemini Live WebSocket`（`gemini-3.5-transcribe-live`） | `gemini-3.5-flash-lite` / `gemini-3.8-flash` | 流式边说边出字（首字通常 0.5–1.5s，受海外链路影响） | 单 Key 覆盖流式听音与多模态识图 |
| **国内双阶段流式** | 阿里云百炼 / 火山豆包 `SeedASR 2.0` | `doubao-seed-2-1-lite` / `deepseek-chat` | 流式实时出草稿，松手后润色定稿（定稿通常 0.8–2s） | 国内直连稳定、边说边看反馈快 |
| **DeepSeek 单 Key** | DeepSeek 官方 `/v1/tools/asr` | `deepseek-chat` | 整段音频上传识别 + 润色（通常 1.5–3.5s，视语音长度） | 1 把 Key 通吃语音与文本，配置最简 |
| **海外非流式备选** | Groq `whisper-large-v3-turbo` | `gpt-4o-mini` / `gemini-3.5-flash-lite` | 整段音频上传识别 + 润色（通常 1–2.5s，受海外链路影响） | 识别准确度高、组合成熟 |
| **局域网全离线** | 本地 `whisper.cpp` Server | 本地 `Ollama`（如 `qwen3:8b`） | 取决于本地主机 GPU / CPU 算力 | 数据不出内网、¥0 API 成本 |

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

项目采用多模块结构（`:core` 纯 JVM 引擎层 + `:rime` C++ NDK 层 + `:app` Android UI 与服务层）：

```bash
# 1. 克隆仓库
git clone https://github.com/dengxuezhao/TypeFree.git
cd TypeFree

# 2. 拉取并校验上游 librime 1.17.0 C++ 依赖源码（首次编译需要）
bash rime/src/main/cpp/fetch_librime_deps.sh

# 3. 运行核心层与 Android 单元测试
./gradlew :core:test :app:testStandardDebugUnitTest

# 4. 编译 Debug / Release APK
./gradlew assembleStandardDebug
./gradlew assembleStandardRelease
# 输出路径: app/build/outputs/apk/standard/release/
```

> **版权与合规说明**：本仓库与公开安装包严格遵循 GPL-3.0 协议，仅包含开源词表（雾凇拼音、星空键道 6、五笔 86）及复用开源词库的社区双拼键位映射，不含任何受限私有商业/EULA 码表资产，并通过 `.gitignore` 与 CI 门禁持续防误提交。用户可在应用内「键盘 → 词库管理」自由导入个人合法获取的第三方官方 Rime 方案包。

---

## 🔒 隐私声明

1. **零中心服务器**：不收集用户输入内容、无埋点统计、无广告 SDK、无第三方追踪器。
2. **直连个人端点**：语音音频与 AI 文本请求仅发往用户在本地填写的 API 服务地址或自建内网服务。
3. **按需读屏与端侧脱敏**：仅在用户主动点击 **✦ 军师** 时按需读取当前可见聊天区域，手机号、身份证号、微信号等先在端侧正则脱敏再发往模型，关闭面板立即清零内存；**✍️ 帮写** 与 **🔍 问AI** 全程零读屏、零截屏。
4. **无痕与敏感界面熔断**：密码框、支付及银行应用自动禁用读屏、悬浮球与词频学习；本地凭证加密保存在 `EncryptedSharedPreferences`。

---

## 📄 开源许可证与反馈

本项目基于 **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)** 开源。

- **GitHub 反馈与贡献**：欢迎通过 [GitHub Issues](https://github.com/dengxuezhao/TypeFree/issues) 提交缺陷报告与功能建议，或直接发起 Pull Request。
- **QQ 反馈交流群**：功能建议、日常使用交流或 Bug 反馈，欢迎加入 **TypeFree输入法反馈群**（QQ 群号：`801143863`）。
