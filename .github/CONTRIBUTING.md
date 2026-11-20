# 参与 TypeFree 贡献 / Contributing to TypeFree

感谢你愿意参与 **TypeFree** 的改进！在提交 Issue 或 Pull Request 之前，请先花 1 分钟了解以下约定。

---

## 1. 贡献者许可协议（CLA）

所有代码、脚本、文档与资源类 Pull Request 在合入前，必须签署项目根目录的 [CLA.md](../CLA.md)。
请在提交 PR 时勾选模板中的 CLA 确认框，或在 PR 评论中写明：

```text
I have read the TypeFree CLA (CLA.md) and hereby sign the CLA.
```

---

## 2. 仓库模块结构

- `core/`：纯 Kotlin/JVM 核心算法层（码表增强协调器、空码逆切分、直通车指令、自动造词查重、方案导入校验器）。
- `rime/`：`librime 1.17.0` C++17 原生桥接模块及 JNI 封装。
- `app/`：Android 输入法客户端（Jetpack Compose + Material 3 + 语音/AI 面板 + 设置页）。
- `scripts/`：词库与资源构建工具脚本。

---

## 3. 开源合规红线（严禁提交）

请勿向公开开源仓库提交以下内容：

- **第三方 Copyleft 代码或数据**：未经维护者事先书面同意，不得将第三方 GPL / LGPL / AGPL / CC BY-SA 代码或数据混入 `core/`、`rime/`、`app/` 核心源码（以免妨碍本项目的跨平台双重许可）。
- **专有/受限第三方码表与词库**：例如小鹤音形官方专有码表、`exports/`、`local_private/`、`*.table.bin`，以及采用 `CC BY-NC` / `CC BY-ND` 等与 GPL-3.0 冲突许可的数据集。
- **敏感凭据与私有文档**：任何真实 API Key、签名证书（`keystore.properties`、`*.jks`）、用户私有数据或未公开的私有商业规划文档。

---

## 4. 本地构建与测试

请使用 **JDK 17** 运行测试：

```bash
# 运行核心算法层单元测试
./gradlew :core:test

# 运行 Android 端单元测试（标准开源变体）
./gradlew :app:testStandardDebugUnitTest
```

提交 PR 前请确保：

1. 改动范围聚焦，不混入与本次目标无关的格式重排或大面积重构。
2. 核心状态机改动附带对应单元测试，且不突破架构守护约束（如 `KeyboardScreen.kt <= 650` 行）。
3. Commit 信息清晰描述改动原因与影响范围（推荐使用 Conventional Commits，如 `feat(ime): ...`、`fix(core): ...`）。
