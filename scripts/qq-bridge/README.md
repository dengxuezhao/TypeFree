# TypeFree QQ 群 ↔ GitHub 双向自动化桥接部署指南（QQ 官方机器人 Webhook + 备用 OneBot 11）

本目录提供一套零第三方 Python 依赖的轻量双向桥接服务，默认采用 **QQ 开放平台官方机器人 Webhook v2（Ed25519 验签 + 官方 API 回复）**，不会因海外服务器 IP 触发个人账号风控掉线；同时保留旧版 NapCat OneBot 11（`--profile onebot`）作为兼容开关，将 **TypeFree输入法反馈群（`801143863`）** 与 GitHub 仓库打通：

- **QQ 群 / 私聊 → GitHub（`@机器人` 触发，私聊直接发送）**：
  - `/bug <内容>`（或 `#bug`）：自动校验长度、附带 `area:*` 标签并在 GitHub 登记 Issue，回贴编号与查询引导（官方机器人模式下自动去除外链 URL，避免被腾讯安全规则拦截）。
  - `/建议 <内容>`（或 `#建议` / `/feature`）：自动登记功能建议 Issue。
  - `/最新`（或 `/版本`）：查询最新公开 Release 版本、更新亮点与官网下载引导（内置 60s 缓存）。
  - `/小鹤`、`/清空`、`/api`、`/faq` 或 `@机器人 <问题>`：自动答复高频使用问题。
  - `/帮助`（或 `/help`）：展示群助手指令列表。
- **GitHub → QQ 群**：
  - `.github/workflows/release.yml` 与 `.github/workflows/issue-triage.yml` 支持经 `/qq-bridge/notify` 推送；纯官方机器人被动回复模式下若未配置 `ONEBOT_HTTP_URL` 与 `QQ_OFFICIAL_GROUP_OPENID`，该接口会安全返回 `skipped: true`（HTTP 200），不会导致 Actions 报错。
  - 若未来在官方平台开通群主动消息并配置 `QQ_OFFICIAL_GROUP_OPENID`，即可自动恢复发版主动推送。

---

## 1. QQ 官方机器人 Webhook 配置与部署（推荐）

### 第一步：准备 `.env` 并启动容器

```bash
cd /opt/typefree-qq-bridge
cp .env.example .env
# 在 .env 中填入 QQ_OFFICIAL_APP_ID、QQ_OFFICIAL_APP_SECRET、GITHUB_TOKEN、BRIDGE_SECRET，
# 并将 ONEBOT_HTTP_URL 留空
docker compose up -d
```

### 第二步：宿主机 Nginx 反代（`typefree.xuezhao.space`）

在站点的 HTTPS `server` 块中配置精确匹配：

```nginx
location = /qq-official/webhook {
    proxy_pass http://127.0.0.1:8090/qq-official/webhook;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}

location = /qq-bridge/notify {
    proxy_pass http://127.0.0.1:8090/github/notify;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

### 第三步：在 QQ 开放平台后台配置事件回调与权限

1. **沙箱 / 现网事件配置**：请求方式选择 **Webhook**，回调地址填写 `https://typefree.xuezhao.space/qq-official/webhook`，点击验证（桥接服务自动用 `AppSecret` 签名响应 `op=13`）。
2. **订阅事件**：勾选 `GROUP_AT_MESSAGE_CREATE`（群聊 `@机器人`）及按需勾选 `C2C_MESSAGE_CREATE`（私聊消息）。
3. **IP 白名单与群权限**：将服务器公网 IP 加入 QQ 开放平台调用白名单，确认测试群（`801143863`）在机器人「开发设置 / 沙箱配置」或已过审上架范围内，再将机器人拉入群内即可 `@机器人 /帮助` 测试。

---

## 2. 备用：旧版 NapCat 个人小号模式（可选）

仅在需要保留个人号主动发消息时使用：在 `.env` 填入 `ONEBOT_HTTP_URL=http://napcat:3000`，使用 `docker compose --profile onebot up -d` 启动，并通过 SSH 隧道 `ssh -N -L 6099:127.0.0.1:6099 baota` 扫码登录；不要将 `/onebot/event` 或 OneBot `3000` 端口暴露到公网。

### 配置 GitHub Repository Secrets

在 GitHub 仓库 **Settings → Secrets and variables → Actions** 中新增：

- `QQ_BRIDGE_URL`：`https://typefree.xuezhao.space/qq-bridge/notify`
- `QQ_BRIDGE_SECRET`：与服务器 `.env` 中的 `BRIDGE_SECRET` 一致
- `QQ_FEEDBACK_GROUP_ID`：`801143863`

（可选）如果希望 GitHub 网页端直接新建的 Issue 也自动转发到 QQ 群，在 **Variables** 页签新增 `QQ_NOTIFY_NEW_ISSUES = true`。

---

## 3. 本地测试与服务器自检

```bash
# 本地单元测试
python3 -m unittest scripts/qq-bridge/test_bridge.py -v

# 服务器健康检查与推送联调
curl -fsS http://127.0.0.1:8090/healthz
curl -fsS -X POST https://typefree.xuezhao.space/qq-bridge/notify \
  -H "Authorization: Bearer 你的BRIDGE_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"event":"text","message":"TypeFree QQ 群自动化联动测试成功"}'
```
