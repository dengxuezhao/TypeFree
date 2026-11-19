## 变更说明

<!-- 简要说明本次 PR 解决的问题、实现的功能或优化点 -->

## 关联 Issue

<!-- 若本 PR 修复或实现了某条 Issue，请使用 Closes #编号，合并与发版后将自动联动闭环 -->
- Closes #

## 改动范围

- [ ] `:core`（纯 Kotlin 引擎 / 码表增强 / 规则逻辑）
- [ ] `:rime`（C++ JNI / librime 绑定）
- [ ] `:app`（Android IME / Compose UI / 设置 / 网络）
- [ ] `.github` / `scripts`（CI/CD、模板或构建脚本）
- [ ] `docs` / `README.md`

## 验证情况

- [ ] `./gradlew :core:test` 通过
- [ ] `./gradlew testStandardDebugUnitTest` 通过
- [ ] 本地或真机实测通过（如有界面或交互改动，建议附截图）

## 合规与隐私自检

- [ ] 确认未提交 `app/src/xiaohe/assets/rime/`、`local_private/`、`*.table.bin`、`keystore.properties`、`*.jks` 或任何 API Key
