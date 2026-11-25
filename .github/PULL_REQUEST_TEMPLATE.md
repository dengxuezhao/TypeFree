## 变更说明 / Summary

<!-- 简要说明本次 PR 解决的问题、实现的功能或优化点 / Briefly describe the problem solved, feature added, or improvement made -->

## 关联 Issue / Linked Issue

<!-- 若本 PR 修复或实现了某条 Issue，请使用 Closes #编号，合并与发版后将自动通知 / Use `Closes #<id>` so merging and releasing automatically notify the Issue -->
- Closes #

## 改动范围 / Scope of Changes

- [ ] `:core`（纯 Kotlin/JVM 引擎 / 智能英文 / 码表增强 / Pure JVM engine, Smart English, codetable logic）
- [ ] `:rime`（C++ JNI / `librime 1.17.0` 绑定 / Native `librime` JNI bindings）
- [ ] `:localai-native`（C++ JNI / `llama.cpp` 端侧推理绑定 / On-device `llama.cpp` JNI bindings）
- [ ] `:app`（Android IME / Compose UI / 语音与 AI / 设置 / Android IME, Compose UI, Voice & AI, Settings）
- [ ] `.github` / `scripts`（CI/CD、模板或脚本 / Workflows, templates, or build scripts）
- [ ] `docs` / `README*`（中英文文档 / Documentation）

## 验证清单 / Verification Checklist

- [ ] `./gradlew :core:test` 通过 / passed
- [ ] `./gradlew :app:testStandardDebugUnitTest` 通过 / passed
- [ ] 本地或真机实测通过（涉及界面或交互改动建议附截图）/ Verified on device or emulator (attach screenshots for UI/UX changes)
- [ ] 确认未提交 `app/src/xiaohe/assets/rime/`、`exports/`、`local_private/`、`*.table.bin`、`keystore.properties`、`*.jks` 或任何 API Key / Confirmed zero restricted codetables, compiled `.bin` dicts, keystores, or API keys are committed
- [ ] 确认未在核心源码中混入未经书面同意的第三方 Copyleft（GPL/LGPL/AGPL/CC BY-SA）或 CC BY-NC/ND 代码与数据 / Confirmed no unapproved third-party Copyleft or CC BY-NC/ND code/data was added
- [ ] 我已阅读并签署 [CLA.md](../CLA.md) / I have read the TypeFree CLA ([CLA.md](../CLA.md)) and hereby sign the CLA.
