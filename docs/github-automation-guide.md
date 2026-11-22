# TypeFree GitHub 自动化流水线与运维指南

## 一、已配置自动化组件总览

| 文件 | 触发时机 | 核心作用 |
| --- | --- | --- |
| `.github/ISSUE_TEMPLATE/` (`bug_report.yml`, `feature_request.yml`, `question.yml`, `config.yml`) | 用户新建 Issue | 强制收集应用版本、版本类型（标准版/小鹤版）、输入方案、设备型号与复现步骤 |
| `.github/PULL_REQUEST_TEMPLATE.md` | 发起 Pull Request | 规范变更说明、关联 `Closes #编号`、验证清单及私有词库/签名隔离自检 |
| `.github/labels.json` + `.github/workflows/sync-labels.yml` | 推送至 `main` 修改标签定义或手动触发 | 自动创建并同步分诊状态与模块领域标签 |
| `.github/workflows/issue-triage.yml` | `issues` (`opened` / `reopened`) | 自动补充 `needs-triage` 与模块标签、发送防重欢迎提示、可选推送飞书机器人通知 |
| `.github/workflows/ci.yml` | `push` (`main`, `feat/**`) / `pull_request` (`main`) | 仓库隐私/EULA 合规静检 + `:core:test` + `testStandardDebugUnitTest` + `assembleStandardDebug` |
| `.github/workflows/release.yml` | 推送 `v*` Tag 或手动传入 Tag | 单测门禁 + 正式签名构建 `standardRelease` + 自动创建 GitHub Release + 自动在关联已关 Issue 下回帖通知 |
| `.github/workflows/build-xiaohe-apk.yml` | 手动 `workflow_dispatch` | 隔离注入私有小鹤词库 + `:core:test` + `testXiaoheDebugUnitTest` + 构建 `xiaohe` Debug/Release Artifact |
| `.github/dependabot.yml` | 每周一 09:00 (`Asia/Shanghai`) | 聚合检查 GitHub Actions 与 Gradle 依赖更新 |

---

## 二、GitHub Repository Secrets 配置说明

所有新增自动化工作流在未配置可选 Secrets 时均会平滑降级或跳过可选通知步骤，不会直接报错阻断。按需在仓库 **Settings → Secrets and variables → Actions** 中录入以下 Secret：

1. **正式 Release 签名密钥（用于 `release.yml` 与 `build-xiaohe-apk.yml` 的 `release` 构建）**
   - `TYPEFREE_KEYSTORE_BASE64`：将 `.jks` 密钥库转为单行 Base64：
     ```bash
     base64 -i release/typefree-release.jks | tr -d '\n'
     ```
   - `TYPEFREE_KEYSTORE_PASSWORD`：Keystore 密码
   - `TYPEFREE_KEY_ALIAS`：Key 别名（如 `typefree`）
   - `TYPEFREE_KEY_PASSWORD`：Key 密码
   - 未配置时 `release.yml` 会显式产出 `*-debugsigned.apk` 并在 Release 说明中提示。

2. **飞书群自定义机器人通知（用于新 Issue 与正式发版即时推送）**
   - `FEISHU_WEBHOOK_URL`：飞书群自定义机器人 Webhook 地址（`https://open.feishu.cn/open-apis/bot/v2/hook/...`）
   - 若你的飞书机器人开启了「签名校验」，建议后续改用独立中继或关闭仅内网测试，当前默认按无加签 Webhook JSON 卡片格式发送。

3. **QQ 反馈群双向联动（NapCat + OneBot 11，用于 `release.yml` 与 `issue-triage.yml`）**
   - 部署指南见 [scripts/qq-bridge/README.md](../scripts/qq-bridge/README.md)。
   - `QQ_BRIDGE_URL`：服务器对外反代的 Bridge 接口（如 `https://typefree.xuezhao.space/qq-bridge/notify`）
   - `QQ_BRIDGE_SECRET`：与服务器 `.env` 中 `BRIDGE_SECRET` 一致的 Bearer 鉴权密钥
   - `QQ_FEEDBACK_GROUP_ID`：目标 QQ 群号（如 `801143863`）
   - （可选 Variable）`QQ_NOTIFY_NEW_ISSUES=true`：开启后将 GitHub 网页端新增的非 QQ 来源 Issue 也同步播报到群内。

4. **小鹤专享版私有词库注入（用于 `build-xiaohe-apk.yml`）**
   - `XIAOHE_READY_ZIP_URL` 或 `XIAOHE_READY_ZIP_B64` 或 (`GDRIVE_ACCESS_TOKEN` + `XIAOHE_GDRIVE_FILE_ID`)

---

## 三、如何让自动化立即在 GitHub 生效

1. `main` 分支目前尚未包含 `.github/` 目录。将本分支的 `.github/` 合并或 cherry-pick 到 `main` 后：
   - Issue 表单与 PR 模板会立刻在 GitHub 网页端生效；
   - `sync-labels.yml` 会在合入 `main` 后自动同步 12 个新增标签；
   - `ci.yml` 会在后续每次 `main` 推送和 PR 时自动运行。
2. 如需立即在当前仓库把标签同步到远端，可在合并到 `main` 前先用 `gh` 执行一次本地同步预览。
3. 修复某个 Issue 时，在 Commit 或 PR 描述中写入 `Closes #1`（或 `Fixes #2`）；合并后 Issue 自动关闭，后续打 Tag（如 `git tag v3.6.1 && git push origin v3.6.1`）发布时，`release.yml` 会自动在该 Issue 下回复版本发布通知。

---

## 四、现有 Open Issue 处理建议与回复草稿（待人工确认后发出）

### Issue #1：[希望增加一个功能](https://github.com/dengxuezhao/TypeFree/issues/1)
- **现状核查**：`BuiltInQwertyKeyboard.kt` 中删除键上滑当前绑定 `onSwipeUpBackspaceUndo`（仅撤回上一笔或刚自动上屏的词汇/整句），尚不支持清空输入框内已确认的多句历史文本。
- **建议标签**：`enhancement`、`area: keyboard`、`under-consideration`
- **回复草稿**：
  > 感谢建议！你观察得很准确：目前删除键上滑绑定的是「撤回最近一次上屏/自动生成内容」，确实无法一键清空输入框内已经确认的历史长文。
  > 
  > 后续评估实现时主要会考虑防误触保护（例如：上滑清空整段前触发确认或支持再次上滑/快捷撤回恢复）。在评估前想确认下你更期望哪种触发范围：
  > 1. 仅清空当前光标前的一整句/当前段落；
  > 2. 清空整个输入框内容（配合可撤回机制）。

### Issue #2：[希望增加九键拼音键盘](https://github.com/dengxuezhao/TypeFree/issues/2)
- **现状核查**：`docs/plan-01-macos-companion-and-core-decoupling.md` 已在架构职责中预留九宫格 Compose 软键盘定位，当前实现仍以 26 键（及双拼/码表/语音面板）为主，尚未实现 T9 九键候选切分与键盘布局。
- **建议标签**：`enhancement`、`area: keyboard`、`under-consideration`
- **回复草稿**：
  > 感谢认可与建议！目前 TypeFree 优先打磨了 26 键、双拼/音形码表和语音输入体验，拼音九宫格（T9）目前尚未排期落地，架构上已留出扩展位置。
  > 
  > 已将该需求标记为 `under-consideration` 纳入候选评估，后续如有明确版本排期会在此帖同步更新进度。
