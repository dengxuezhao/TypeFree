English | [简体中文](github-automation-guide.md)

# TypeFree GitHub Automation & Operations Guide

## 1. Configured Automation Components Overview

| File / Path | Trigger | Core Responsibility |
| --- | --- | --- |
| `.github/ISSUE_TEMPLATE/` (`bug_report.yml`, `feature_request.yml`, `question.yml`, `config.yml`) | User opens a new Issue | Structured bilingual forms collecting app version, APK build type, input schema/scenario, device info, and reproduction steps |
| `.github/PULL_REQUEST_TEMPLATE.md` | Pull Request opened | Enforces summary, `Closes #<id>` issue linking, verification checklist, CLA signature, and private-asset/key hygiene checks |
| `.github/labels.json` + `.github/workflows/sync-labels.yml` | Push to `main` touching labels or manual dispatch | Creates and synchronizes triage status labels and module area labels idempotently |
| `.github/workflows/issue-triage.yml` | `issues` (`opened` / `reopened`) | Auto-applies `needs-triage` + `area:*` labels, posts a non-duplicate welcome note, and optionally notifies Feishu / QQ webhooks |
| `.github/workflows/ci.yml` | `push` (`main`, `feat/**`) / `pull_request` (`main`) | Repo EULA/secret hygiene guard + `:core:test` + `testStandardDebugUnitTest` + `assembleStandardDebug` |
| `.github/workflows/release.yml` | Push `v*` tag or manual workflow dispatch | Unit-test gate + signed `standardRelease` APK build + automated GitHub Release creation + release notifications on linked closed Issues |
| `.github/workflows/build-xiaohe-apk.yml` | Manual `workflow_dispatch` | Isolated private Flypy lexicon injection + `:core:test` + `testXiaoheDebugUnitTest` + `xiaohe` Debug/Release artifact build |
| `.github/dependabot.yml` | Mondays at 09:00 (`Asia/Shanghai`) | Grouped weekly dependency update checks for GitHub Actions and Gradle |

---

## 2. GitHub Repository Secrets Configuration

All workflows degrade gracefully when optional Secrets are absent — missing notification webhooks or signing keys never break CI or block workflow execution. Configure Secrets as needed under **Settings → Secrets and variables → Actions**:

1. **Official Release Signing Keys (used by `release.yml` and `release` builds in `build-xiaohe-apk.yml`)**
   - `TYPEFREE_KEYSTORE_BASE64`: Single-line Base64 of your `.jks` keystore:
     ```bash
     base64 -i release/typefree-release.jks | tr -d '\n'
     ```
   - `TYPEFREE_KEYSTORE_PASSWORD`: Keystore password
   - `TYPEFREE_KEY_ALIAS`: Key alias (e.g., `typefree`)
   - `TYPEFREE_KEY_PASSWORD`: Key password
   - If omitted, `release.yml` falls back cleanly to producing `*-debugsigned.apk` and notes it explicitly in the Release description.

2. **Feishu Custom Bot Webhook (optional real-time notifications for new Issues and Releases)**
   - `FEISHU_WEBHOOK_URL`: Feishu custom group bot webhook URL (`https://open.feishu.cn/open-apis/bot/v2/hook/...`)
   - Sends un-signed interactive JSON cards by default; if signature verification is enabled on your Feishu bot, route through a signing relay or disable signature enforcement for that webhook.

3. **QQ Feedback Group Two-Way Bridge (NapCat + OneBot 11, used by `release.yml` and `issue-triage.yml`)**
   - Deployment instructions: [scripts/qq-bridge/README.md](../scripts/qq-bridge/README.md).
   - `QQ_BRIDGE_URL`: Reverse-proxied public bridge endpoint (e.g., `https://typefree.xuezhao.space/qq-bridge/notify`)
   - `QQ_BRIDGE_SECRET`: Bearer token matching `BRIDGE_SECRET` in the server `.env`
   - `QQ_FEEDBACK_GROUP_ID`: Target QQ group number (e.g., `801143863`)
   - (Optional Repository Variable) `QQ_NOTIFY_NEW_ISSUES=true`: Broadcasts newly opened GitHub web Issues (excluding `[QQ反馈]`-prefixed mirrored issues) to the QQ group.

4. **Isolated Flypy Flavor Lexicon Injection (used only by manual `build-xiaohe-apk.yml`)**
   - `XIAOHE_READY_ZIP_URL` or `XIAOHE_READY_ZIP_B64` or (`GDRIVE_ACCESS_TOKEN` + `XIAOHE_GDRIVE_FILE_ID`)

---

## 3. Activating & Operating Workflows on GitHub

1. Once `.github/` is merged into the default `main` branch:
   - Structured Issue forms and the PR template take effect immediately in the GitHub web UI.
   - `sync-labels.yml` automatically syncs the repository label set upon landing on `main`.
   - `ci.yml` runs automatically on subsequent pushes and pull requests targeting `main`.
2. When fixing an Issue, include `Closes #1` (or `Fixes #2`) in the commit message or PR body. Merging the PR closes the Issue automatically, and pushing the next version tag (e.g., `git tag v3.9.0 && git push origin v3.9.0`) triggers `release.yml` to comment on that Issue once the release APK is published.

---

## 4. Historical Open Issue Triage Archive

### Issue #1: [Feature Request — Swipe-Up Backspace Clear](https://github.com/dengxuezhao/TypeFree/issues/1)
- **Context & Follow-Up**: Originally `BuiltInQwertyKeyboard.kt` bound backspace swipe-up exclusively to `onSwipeUpBackspaceUndo` (reverting the most recent commit). An optional **"Swipe Up Backspace to Clear Input"** toggle with undo protection was subsequently added in v3.8.3+.
- **Recommended Labels**: `enhancement`, `area: keyboard`

### Issue #2: [Feature Request — 9-Key T9 Pinyin Keyboard](https://github.com/dengxuezhao/TypeFree/issues/2)
- **Context & Resolution**: Shipped natively in v3.8.0+ as **9-Key Pinyin (`t9`)** sharing the open-source `rime_ice` lexicon, complete with a left-hand live syllable filter strip and a 3×4 large-key number pad.
- **Recommended Labels**: `enhancement`, `area: keyboard`
