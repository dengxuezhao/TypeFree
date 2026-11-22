# TypeFree QQ 群 ↔ GitHub 双向自动化桥接部署指南（NapCat + OneBot 11）

本目录提供一套零第三方 Python 依赖的轻量双向桥接服务，基于多架构容器 `lmq8267/napcat:v4.18.38`（原生支持 `linux/arm64` 与 `linux/amd64`，内置官方 QQNT `3.2.34` + WebUI，无需外部签名站或在线 `auth_token`），将 **TypeFree输入法反馈群（`801143863`）** 与 GitHub 仓库打通：

- **QQ 群 → GitHub**：
  - `/bug <内容>`（或 `#bug`）：自动校验长度、附带 `area:*` 标签并在 GitHub 登记 Issue，回贴链接到群内。
  - `/建议 <内容>`（或 `#建议` / `/feature`）：自动登记功能建议 Issue。
  - `/最新`（或 `/版本`）：查询最新公开 Release 版本与 APK 下载链接（内置 60s 缓存）。
  - `/小鹤`、`/清空`、`/api`、`/faq` 或 `@机器人 <问题>`：自动答复高频使用问题。
  - `/帮助`（或 `/help`）：展示群助手指令列表。
- **GitHub → QQ 群**：
  - `.github/workflows/release.yml` 发布新版本后自动向 QQ 群推送更新通知、下载直链与本次已修复的关联 Issue。
  - `.github/workflows/issue-triage.yml` 支持按需将 GitHub 网页端新增的非 QQ 来源 Issue 推送至群内（默认需配置仓库 Variable `QQ_NOTIFY_NEW_ISSUES=true` 才开启，避免打扰群聊）。

---

## 1. 安全与防风控前置建议

1. **务必使用独立的备用 QQ 小号**登录 NapCat，不要使用群主主力账号，且建议将小号设为群管理员。
2. **仅通过 SSH 隧道访问 NapCat WebUI**：`docker-compose.yml` 将 WebUI（`6099`）与 Bridge（`8090`）仅绑定在 `127.0.0.1`，不直接裸奔暴露公网。
3. **仅反代通知端点到公网 HTTPS**：对外暴露时只把 `/qq-bridge/notify`（转发到 `http://127.0.0.1:8090/github/notify`）交给宿主机 Nginx + HTTPS（配合 `BRIDGE_SECRET` Bearer 鉴权），不要把 `/onebot/event` 或 OneBot `3000` 端口暴露到公网。

---

## 2. 服务器部署与扫码步骤

### 第一步：准备 `.env` 并启动容器

```bash
cd /opt/typefree-qq-bridge
cp .env.example .env
# 在 .env 中生成随机 BRIDGE_SECRET / ONEBOT_ACCESS_TOKEN，并填入 GITHUB_TOKEN（Fine-grained PAT，仅授权 Issues 读写）
docker compose up -d
docker logs -f typefree-napcat
```

### 第二步：用 QQ 小号扫码登录并接入 OneBot 11

- 可直接扫描终端输出的二维码（或通过 `ssh -N -L 6099:127.0.0.1:6099 baota` 访问 `http://127.0.0.1:6099/webui` 使用日志中的 WebUI Token 登录并扫码）。
- 登录成功后，在 `data/napcat/config/onebot11_<QQ号>.json`（或 WebUI 网络配置）中启用：
  1. **HTTP 服务器**：`host: "0.0.0.0"`、`port: 3000`、`token: <ONEBOT_ACCESS_TOKEN>`；
  2. **HTTP 客户端（上报）**：`url: "http://typefree-qq-bridge:8090/onebot/event"`、`messagePostFormat: "array"`。

### 第三步：宿主机 Nginx 反代（`typefree.xuezhao.space`）

在站点的 HTTPS `server` 块中配置精确匹配：

```nginx
location = /qq-bridge/notify {
    proxy_pass http://127.0.0.1:8090/github/notify;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

### 第四步：配置 GitHub Repository Secrets

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
