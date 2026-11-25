# 参与 TypeFree 贡献 / Contributing to TypeFree

感谢你参与 **TypeFree** 的改进！在提交 Issue 或 Pull Request 之前，请花 1 分钟阅读以下中英双语约定。  
Thank you for helping improve **TypeFree**! Before opening an Issue or Pull Request, please take a minute to review the guidelines below.

---

## 1. 贡献者许可协议 / Contributor License Agreement (CLA)

所有代码、脚本、文档与资源类 Pull Request 在合入前，必须签署项目根目录的 [CLA.md](../CLA.md)。请在提交 PR 时勾选模板中的 CLA 确认框，或在 PR 评论中写明：  
All code, script, documentation, and asset Pull Requests require signing [CLA.md](../CLA.md) before merge. Please check the CLA box in the PR template or post the exact statement below in a PR comment:

```text
I have read the TypeFree CLA (CLA.md) and hereby sign the CLA.
```

---

## 2. 仓库模块结构 / Repository Module Structure

- `core/`：纯 Kotlin/JVM 核心算法层（智能英文引擎、码表增强协调器、空码逆切分、直通车指令、自动造词查重、方案导入校验器）。  
  Pure Kotlin/JVM core engine (Smart English engine, codetable enhancer, empty-code reverse segmentation, quick commands, word-learning deduplicator, and schema inspector).
- `rime/`：`librime 1.17.0` + `librime-lua` C++17 原生桥接模块及 JNI 封装。  
  Native C++17 `librime 1.17.0` + `librime-lua` bridge and JNI bindings.
- `localai-native/`：`llama.cpp` 端侧小模型 C++ JNI 推理桥接模块（运行于独立 `:local_ai` 进程）。  
  Native `llama.cpp` JNI bridge for on-device SLM inference inside the isolated `:local_ai` process.
- `app/`：Android 输入法客户端（Jetpack Compose + Material 3 + `sherpa-onnx` 离线语音 + BYOK 语音/AI 面板 + 设置与统计洞察页）。  
  Android IME & companion app (Jetpack Compose, Material 3, `sherpa-onnx` offline ASR, BYOK voice/AI studio, settings, and Expression Insights).
- `scripts/`：依赖拉取、词库校验与自动化辅助脚本。  
  Dependency fetchers, lexicon verification, and automation helper scripts.

---

## 3. 开源合规红线（严禁提交）/ Open-Source Compliance Red Lines (Strictly Forbidden)

请勿向公开开源仓库提交以下内容 / Never commit any of the following to the public repository:

- **第三方 Copyleft 代码或数据 / Unapproved Third-Party Copyleft Code or Data**  
  未经维护者事先书面同意，不得将第三方 GPL / LGPL / AGPL / CC BY-SA 代码或数据混入 `core/`、`rime/`、`localai-native/`、`app/` 源码（以免妨碍本项目的跨平台双重许可）。  
  Do not introduce third-party GPL / LGPL / AGPL / CC BY-SA code or datasets into `core/`, `rime/`, `localai-native/`, or `app/` without prior written maintainer approval (to preserve dual-licensing compatibility).
- **受限第三方码表与二进制构建产物 / Restricted Third-Party Codetables & Compiled Binaries**  
  严禁提交小鹤音形官方私有码表、`app/src/xiaohe/assets/rime/`、`exports/`、`local_private/`、`*.table.bin`、`*.prism.bin`、`*.reverse.bin`，以及采用 `CC BY-NC` / `CC BY-ND` 等与 GPL-3.0 冲突许可的数据集。  
  Never commit restricted third-party codetables (e.g., proprietary Xiaohe Flypy shape tables), `app/src/xiaohe/assets/rime/`, `exports/`, `local_private/`, `*.table.bin`, `*.prism.bin`, `*.reverse.bin`, or `CC BY-NC` / `CC BY-ND` datasets incompatible with GPL-3.0.
- **敏感凭据与内部文件 / Secrets, Signing Keys & Internal Files**  
  严禁提交任何真实 API Key、签名证书（`keystore.properties`、`*.jks`、`*.keystore`）、用户私人数据或内部未公开文档。  
  Never commit real API keys, signing keystores (`keystore.properties`, `*.jks`, `*.keystore`), personal user data, or internal unpublished documents.

---

## 4. 本地构建与测试 / Local Build & Testing

请使用 **JDK 17** 运行依赖拉取与单元测试 / Use **JDK 17** to fetch native dependencies (first run) and execute unit tests:

```bash
# 首次构建：拉取并校验 librime 1.17.0 与端侧离线依赖 / First build only: fetch verified native & offline AI deps
bash rime/src/main/cpp/fetch_librime_deps.sh
bash scripts/fetch_offline_deps.sh

# 运行纯 JVM 核心层单元测试 / Run pure JVM :core unit tests
./gradlew :core:test

# 运行 Android 标准开源变体单元测试 / Run Android Standard flavor unit tests
./gradlew :app:testStandardDebugUnitTest
```

提交 PR 前请确保 / Before opening a PR, please ensure:

1. **改动聚焦 / Focused Scope**：不混入与本次目标无关的格式重排或大面积重构。 / Keep changes focused without unrelated formatting churn or sweeping refactors.
2. **测试与守护约束 / Tests & Guardrails**：核心状态机改动附带对应单元测试，且遵守架构行数约束（如 `KeyboardScreen.kt <= 650` 行）。 / Cover state-machine changes with unit tests and respect architectural size guards (e.g., `KeyboardScreen.kt <= 650` lines).
3. **清晰的提交信息 / Clear Commit Messages**：说明改动原因与影响范围（推荐使用 Conventional Commits，如 `feat(ime): ...`、`fix(core): ...`、`docs: ...`）。 / Explain the *why* and impact clearly (Conventional Commits such as `feat(ime): ...`, `fix(core): ...`, `docs: ...` recommended).
