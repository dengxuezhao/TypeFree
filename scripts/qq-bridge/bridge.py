#!/usr/bin/env python3
"""
TypeFree QQ Group (OneBot 11 / NapCat) <-> GitHub Bidirectional Bridge.

Zero third-party dependencies (Python 3.10+ standard library only).
Handles:
  1. OneBot 11 HTTP event webhooks (POST /onebot/event):
     - /bug, #bug         -> Create GitHub bug issue with area auto-labels
     - /建议, #建议, /feature -> Create GitHub enhancement issue
     - /最新, /版本        -> Query latest GitHub Release (with TTL cache)
     - /小鹤, /清空, /api, /faq -> Answer high-frequency TypeFree FAQs
     - /帮助, /help       -> Show usage commands
  2. GitHub Actions notifications (POST /github/notify):
     - Forward release & optional issue events to target QQ groups via OneBot 11.
  3. Health check (GET /healthz).
"""

from __future__ import annotations

import hashlib
import hmac
import html
import json
import logging
import os
import re
import signal
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("typefree-qq-bridge")

MAX_BODY_BYTES = 262_144
CQ_CODE_RE = re.compile(r"\[CQ:([a-zA-Z0-9_-]+)(?:,([^\]]*))?\]")


def _unescape_cq(text: str) -> str:
    return (
        text.replace("&#91;", "[")
        .replace("&#93;", "]")
        .replace("&#44;", ",")
        .replace("&amp;", "&")
    )


def parse_onebot_message(payload: dict[str, Any]) -> tuple[str, bool]:
    """Extract plain text and whether the bot (@self_id) was mentioned."""
    self_id = str(payload.get("self_id") or "")
    mentioned_bot = False
    raw_msg = payload.get("message")

    if isinstance(raw_msg, list):
        parts: list[str] = []
        for seg in raw_msg:
            if not isinstance(seg, dict):
                continue
            seg_type = seg.get("type")
            data = seg.get("data") or {}
            if seg_type == "text":
                parts.append(str(data.get("text") or ""))
            elif seg_type == "at":
                qq = str(data.get("qq") or "")
                if self_id and qq == self_id:
                    mentioned_bot = True
                elif qq and qq != "all":
                    parts.append(f"@{qq} ")
        text = "".join(parts)
    else:
        source = str(payload.get("raw_message") or raw_msg or "")

        def _replace_cq(match: re.Match[str]) -> str:
            nonlocal mentioned_bot
            cq_type = match.group(1)
            params = match.group(2) or ""
            if cq_type == "at":
                qq_match = re.search(r"(?:^|,)qq=([^,]+)", params)
                qq = qq_match.group(1).strip() if qq_match else ""
                if self_id and qq == self_id:
                    mentioned_bot = True
                    return ""
            return ""

        text = _unescape_cq(CQ_CODE_RE.sub(_replace_cq, source))

    text = html.unescape(text).replace("\r\n", "\n").strip()
    return text, mentioned_bot


def infer_area_labels(text: str) -> list[str]:
    """Infer TypeFree repository area labels matching .github/workflows/issue-triage.yml."""
    lower = text.lower()
    labels: list[str] = []
    if re.search(r"(九键|九宫格|\bt9\b|26键|键盘|布局|按键|删除键|退格|上滑|下滑|空格|换行|底排|候选栏|候选词|候选条)", lower):
        labels.append("area: keyboard")
    if re.search(r"(小鹤|音形|码表|词库|双拼|五笔|键道|简码|顶字|逆切|空码|快符|rime|flypy|\.dict|\.schema|xkjd|wubi)", lower):
        labels.append("area: codetable")
    if re.search(r"(语音|asr|麦克风|录音|转写|jev|军师|帮写|润色|翻译|deepseek|gemini|openai|\bai\b)", lower):
        labels.append("area: voice-jev")
    if re.search(r"(设置|界面|主题|深色|浅色|字体|更新|检查更新|隐私|权限|图标|\bui\b)", lower):
        labels.append("area: ui-settings")
    if re.search(r"(librime|jni|ndk|崩溃日志|sigsegv|native|\.so)", lower):
        labels.append("area: rime-native")
    return labels


@dataclass
class BridgeConfig:
    listen_host: str = "0.0.0.0"
    listen_port: int = 8090
    github_repo: str = "dengxuezhao/TypeFree"
    github_token: str = ""
    onebot_http_url: str = "http://127.0.0.1:3000"
    onebot_access_token: str = ""
    onebot_event_secret: str = ""
    bridge_secret: str = ""
    allowed_group_ids: set[int] = field(default_factory=lambda: {801143863})
    default_group_id: int = 801143863
    user_cooldown_seconds: float = 15.0
    group_max_replies_per_min: int = 10
    max_issues_per_user_per_day: int = 5
    release_cache_ttl_seconds: float = 60.0
    include_qq_id_in_issue: bool = False
    official_site_origin: str = "https://typefree.xuezhao.space"

    @classmethod
    def from_env(cls) -> BridgeConfig:
        raw_groups = os.environ.get("QQ_GROUP_IDS", "801143863").strip()
        group_ids = {
            int(item.strip())
            for item in raw_groups.split(",")
            if item.strip().isdigit()
        }
        default_group = int(os.environ.get("DEFAULT_QQ_GROUP_ID", "0") or 0)
        if not default_group and group_ids:
            default_group = sorted(group_ids)[0]
        return cls(
            listen_host=os.environ.get("BRIDGE_HOST", "0.0.0.0"),
            listen_port=int(os.environ.get("BRIDGE_PORT", "8090")),
            github_repo=os.environ.get("GITHUB_REPO", "dengxuezhao/TypeFree").strip(),
            github_token=os.environ.get("GITHUB_TOKEN", "").strip(),
            onebot_http_url=os.environ.get("ONEBOT_HTTP_URL", "http://127.0.0.1:3000").rstrip("/"),
            onebot_access_token=os.environ.get("ONEBOT_ACCESS_TOKEN", "").strip(),
            onebot_event_secret=os.environ.get("ONEBOT_EVENT_SECRET", "").strip(),
            bridge_secret=os.environ.get("BRIDGE_SECRET", "").strip(),
            allowed_group_ids=group_ids,
            default_group_id=default_group,
            user_cooldown_seconds=float(os.environ.get("USER_COOLDOWN_SECONDS", "15")),
            group_max_replies_per_min=int(os.environ.get("GROUP_MAX_REPLIES_PER_MIN", "10")),
            max_issues_per_user_per_day=int(os.environ.get("MAX_ISSUES_PER_USER_PER_DAY", "5")),
            release_cache_ttl_seconds=float(os.environ.get("RELEASE_CACHE_TTL_SECONDS", "60")),
            include_qq_id_in_issue=os.environ.get("INCLUDE_QQ_ID_IN_ISSUE", "false").lower() == "true",
            official_site_origin=os.environ.get("OFFICIAL_SITE_ORIGIN", "https://typefree.xuezhao.space").strip().rstrip("/"),
        )


FAQ_ENTRIES: list[tuple[re.Pattern[str], str]] = [
    (
        re.compile(r"(小鹤|音形|码表.*导入|词库.*导入|怎么导入|hang|flypy)", re.IGNORECASE),
        "📘 小鹤音形 / 第三方码表导入说明：\n"
        "1. 公开开源安装包遵循协议，不内置小鹤官方私有码表；\n"
        "2. 请自行准备合法的官方 Rime 挂接包（.zip，无需手动解压或改写）；\n"
        "3. 在 App 内进入「键盘 → 词库管理 → 导入 Rime 方案包 (.zip)」直接选择压缩包，导入后即可启用四码顶功、; 快捷符号与 o* 指令。",
    ),
    (
        re.compile(r"(清空|全选|删.*全部|一键删|删除键)", re.IGNORECASE),
        "⌨️ 快速清空已上屏内容技巧：\n"
        "• 可在「键盘 → 方案与布局 → 通用键盘设置」开启「上滑回退键清空输入框」（默认关闭），开启后上滑删除键即可一键清空并自动入撤回栈；\n"
        "• 未开启时，删除键上滑对应「撤回最近一次上屏」，也可长按「A」全选后点删除键（或长按「X」剪切）。",
    ),
    (
        re.compile(r"(api|key|语音|deepseek|gemini|百炼|豆包|ollama|离线)", re.IGNORECASE),
        "🎙️ 语音与 AI 配置说明（支持纯端侧离线 + BYOK）：\n"
        "• 零 Key 离线可用：在「语音与 AI → 离线模型」按需下载本地语音与端侧文本模型，断网也能听写、净写与基础改写；\n"
        "• 如需云端大模型或 Jev 军师，可在「语音与 AI → 模型与 API」填入自己的 DeepSeek / 豆包 / 百炼 / Gemini / 本地 Ollama 地址与 Key；\n"
        "• 详细教程见仓库文档：https://github.com/dengxuezhao/TypeFree/blob/main/docs/domestic-api-setup-guide.md",
    ),
]


class RateLimiter:
    """Thread-safe cooldown, group reply cap, and daily issue quota guard."""

    def __init__(
        self,
        user_cooldown_seconds: float,
        group_max_replies_per_min: int,
        max_issues_per_user_per_day: int,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._lock = threading.Lock()
        self._user_cooldown = user_cooldown_seconds
        self._group_cap = group_max_replies_per_min
        self._daily_issue_cap = max_issues_per_user_per_day
        self._clock = clock
        self._last_user_reply: dict[tuple[int, int], float] = {}
        self._group_reply_times: dict[int, list[float]] = {}
        self._user_issue_times: dict[int, list[float]] = {}

    def allow_reply(self, group_id: int, user_id: int) -> bool:
        now = self._clock()
        with self._lock:
            last = self._last_user_reply.get((group_id, user_id), 0.0)
            if now - last < self._user_cooldown:
                return False
            history = [t for t in self._group_reply_times.get(group_id, []) if now - t < 60.0]
            if len(history) >= self._group_cap:
                self._group_reply_times[group_id] = history
                return False
            history.append(now)
            self._group_reply_times[group_id] = history
            self._last_user_reply[(group_id, user_id)] = now
            return True

    def allow_issue_creation(self, user_id: int) -> bool:
        now = self._clock()
        with self._lock:
            history = [t for t in self._user_issue_times.get(user_id, []) if now - t < 86_400.0]
            if len(history) >= self._daily_issue_cap:
                self._user_issue_times[user_id] = history
                return False
            history.append(now)
            self._user_issue_times[user_id] = history
            return True


class GitHubClient:
    """Minimal GitHub REST API v3 client with TTL caching for latest release."""

    def __init__(
        self,
        repo: str,
        token: str,
        release_cache_ttl: float = 60.0,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.repo = repo
        self.token = token
        self.release_cache_ttl = release_cache_ttl
        self._clock = clock
        self._cache_lock = threading.Lock()
        self._cached_release: tuple[float, dict[str, Any]] | None = None

    def _request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        url = f"https://api.github.com/repos/{self.repo}{path}"
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "TypeFree-QQ-Bridge/1.0",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}

    def get_latest_release(self) -> dict[str, Any]:
        now = self._clock()
        with self._cache_lock:
            if self._cached_release is not None:
                ts, cached = self._cached_release
                if now - ts < self.release_cache_ttl:
                    return cached

        release = self._request("GET", "/releases/latest")
        with self._cache_lock:
            self._cached_release = (now, release)
        return release

    def create_issue(self, title: str, body: str, labels: list[str]) -> dict[str, Any]:
        if not self.token:
            raise RuntimeError("GITHUB_TOKEN is not configured on bridge server")
        return self._request(
            "POST",
            "/issues",
            {"title": title, "body": body, "labels": labels},
        )


class OneBotClient:
    """Minimal OneBot 11 HTTP sender."""

    def __init__(self, base_url: str, access_token: str = "") -> None:
        self.base_url = base_url.rstrip("/")
        self.access_token = access_token

    def send_group_msg(self, group_id: int, text: str, reply_to_message_id: int | None = None) -> None:
        segments: list[dict[str, Any]] = []
        if reply_to_message_id is not None:
            segments.append({"type": "reply", "data": {"id": str(reply_to_message_id)}})
        segments.append({"type": "text", "data": {"text": text}})

        headers = {"Content-Type": "application/json; charset=utf-8"}
        if self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"

        payload = json.dumps({"group_id": group_id, "message": segments}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/send_group_msg",
            data=payload,
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8")
            if raw:
                parsed = json.loads(raw)
                if parsed.get("status") == "failed" or parsed.get("retcode", 0) not in (0, 1):
                    raise RuntimeError(f"OneBot send_group_msg error: {parsed}")


class BridgeService:
    """Core command router and GitHub notification formatter."""

    def __init__(
        self,
        config: BridgeConfig,
        github_client: GitHubClient | None = None,
        onebot_client: OneBotClient | None = None,
        rate_limiter: RateLimiter | None = None,
    ) -> None:
        self.config = config
        self.github = github_client or GitHubClient(
            config.github_repo,
            config.github_token,
            config.release_cache_ttl_seconds,
        )
        self.onebot = onebot_client or OneBotClient(
            config.onebot_http_url,
            config.onebot_access_token,
        )
        self.limiter = rate_limiter or RateLimiter(
            config.user_cooldown_seconds,
            config.group_max_replies_per_min,
            config.max_issues_per_user_per_day,
        )

    def handle_onebot_event(self, payload: dict[str, Any]) -> str | None:
        if payload.get("post_type") != "message" or payload.get("message_type") != "group":
            return None

        group_id = int(payload.get("group_id") or 0)
        user_id = int(payload.get("user_id") or 0)
        self_id = int(payload.get("self_id") or 0)
        message_id = payload.get("message_id")
        if not group_id or not user_id or user_id == self_id:
            return None
        if self.config.allowed_group_ids and group_id not in self.config.allowed_group_ids:
            return None

        text, mentioned_bot = parse_onebot_message(payload)
        if not text:
            return None

        intent = self._parse_intent(text, mentioned_bot)
        if intent is None:
            return None

        if not self.limiter.allow_reply(group_id, user_id):
            logger.info("Rate-limited reply for group=%s user=%s", group_id, user_id)
            return None

        reply_text = self._execute_intent(payload, intent)
        if not reply_text:
            return None

        self.onebot.send_group_msg(
            group_id=group_id,
            text=reply_text,
            reply_to_message_id=int(message_id) if message_id is not None else None,
        )
        return reply_text

    @staticmethod
    def _parse_intent(text: str, mentioned_bot: bool) -> tuple[str, str] | None:
        cmd_match = re.match(
            r"^[/#]?(bug|建议|feature|最新|版本|帮助|help|faq|小鹤|清空|全选|api)\b(?:\s+|[:：]\s*)?(.*)$",
            text,
            re.IGNORECASE | re.DOTALL,
        )
        has_explicit_prefix = bool(re.match(r"^[/#]", text))

        if cmd_match:
            cmd = cmd_match.group(1).lower()
            rest = (cmd_match.group(2) or "").strip()
            if cmd in ("bug", "建议", "feature", "最新", "版本", "帮助", "help", "faq", "小鹤", "清空", "全选", "api") and (
                has_explicit_prefix or mentioned_bot
            ):
                return cmd, rest

        if mentioned_bot:
            return "mention", text

        return None

    def _execute_intent(self, payload: dict[str, Any], intent: tuple[str, str]) -> str:
        cmd, rest = intent
        if cmd in ("bug", "建议", "feature"):
            return self._handle_issue_command(payload, cmd, rest)
        if cmd in ("最新", "版本"):
            return self._handle_latest_release()
        if cmd == "faq":
            return self._format_faq_index()
        if cmd in ("帮助", "help"):
            return self._format_help()
        if cmd in ("小鹤", "清空", "全选", "api"):
            return self._match_faq(f"{cmd} {rest}".strip()) or self._format_faq_index()
        return self._match_faq(rest) or self._format_help()

    def _handle_issue_command(self, payload: dict[str, Any], cmd: str, content: str) -> str:
        is_bug = cmd == "bug"
        tag_label = "Bug" if is_bug else "建议"
        cmd_hint = "/bug" if is_bug else "/建议"

        if len(content) < 8:
            return (
                f"请补充更具体的描述（至少 8 个字），格式示例：\n"
                f"{cmd_hint} v3.7.1 小米14 小鹤音形 候选栏在横屏下被遮挡..."
            )

        user_id = int(payload.get("user_id") or 0)
        if not self.limiter.allow_issue_creation(user_id):
            return f"你今天提交的反馈已达上限（{self.config.max_issues_per_user_per_day} 条/天），请稍后再试或直接前往 GitHub Issues 补充。"

        sender = payload.get("sender") or {}
        nickname = str(sender.get("card") or sender.get("nickname") or "QQ群友").strip()
        nickname = re.sub(r"[\r\n@`]", "", nickname)[:32] or "QQ群友"
        group_id = int(payload.get("group_id") or 0)

        first_line = next((line.strip() for line in content.splitlines() if line.strip()), content)
        short_title = first_line[:60] + ("..." if len(first_line) > 60 else "")
        issue_title = f"[QQ反馈][{tag_label}] {short_title}"

        reporter_str = f"{nickname} (QQ: {user_id})" if self.config.include_qq_id_in_issue else f"{nickname} (QQ群 {group_id})"
        issue_body = (
            f"### 来源\n"
            f"- 反馈渠道：QQ 群（`{group_id}`）\n"
            f"- 提交人：{reporter_str}\n\n"
            f"### 反馈内容\n"
            f"{content}\n"
        )

        labels = ["bug" if is_bug else "enhancement", "needs-triage"]
        for area in infer_area_labels(content):
            if area not in labels:
                labels.append(area)

        try:
            created = self.github.create_issue(issue_title, issue_body, labels)
            number = created.get("number")
            html_url = created.get("html_url")
            return f"✅ 已为你登记到 GitHub Issue #{number}：\n{html_url}\n后续修复并随新版本发布后，群里也会同步通知。"
        except Exception as exc:
            logger.exception("Failed to create GitHub issue from QQ")
            return f"⚠️ 自动提交 Issue 暂时失败（{type(exc).__name__}），可先在 GitHub 手动提交：https://github.com/{self.config.github_repo}/issues/new/choose"

    def _derive_official_apk_url(self, tag: str) -> str:
        origin = (self.config.official_site_origin or "").strip().rstrip("/")
        if not origin or not re.match(r"^v\d+\.\d+\.\d+$", tag.strip()):
            return ""
        return f"{origin}/downloads/TypeFree-{tag.strip()}-release.apk"

    @staticmethod
    def _extract_release_highlights(
        raw_highlights: Any,
        max_items: int = 5,
        max_item_len: int = 72,
    ) -> list[str]:
        if isinstance(raw_highlights, list):
            raw_lines = [str(item) for item in raw_highlights]
        elif isinstance(raw_highlights, str):
            raw_lines = raw_highlights.splitlines()
        else:
            return []

        items: list[str] = []
        in_code_fence = False
        stop_heading_re = re.compile(r"^#{1,6}\s*(?:安装包说明|下载说明|校验|checksum|assets)\b", re.IGNORECASE)
        skip_heading_re = re.compile(r"^#{1,6}\s*")
        skip_meta_re = re.compile(r"^(?:full changelog|https?://|sha-?256)", re.IGNORECASE)

        for raw in raw_lines:
            line = raw.strip()
            if line.startswith("```"):
                in_code_fence = not in_code_fence
                continue
            if in_code_fence or not line:
                continue
            if set(line) <= {"-", "=", "_", "*"} and len(line) >= 3:
                if items:
                    break
                continue
            if stop_heading_re.match(line):
                break
            if skip_heading_re.match(line):
                continue
            if skip_meta_re.match(line):
                continue

            cleaned = re.sub(r"^(?:[-*•]+|\d+[.)、])\s*", "", line)
            cleaned = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", cleaned)
            cleaned = cleaned.replace("**", "").replace("__", "").replace("`", "")
            cleaned = re.sub(r"\s+", " ", cleaned).strip()
            if len(cleaned) < 3:
                continue
            if len(cleaned) > max_item_len:
                cleaned = cleaned[: max_item_len - 1].rstrip(" ，,、：:·") + "…"
            items.append(cleaned)
            if len(items) >= max_items:
                break
        return items

    def _handle_latest_release(self) -> str:
        try:
            release = self.github.get_latest_release()
        except Exception as exc:
            logger.exception("Failed to fetch latest release")
            return f"暂时无法查询 GitHub 最新版本（{type(exc).__name__}），请直接查看：https://github.com/{self.config.github_repo}/releases"

        tag = release.get("tag_name") or "未知版本"
        html_url = release.get("html_url") or f"https://github.com/{self.config.github_repo}/releases"
        assets = release.get("assets") or []
        apk_asset = next((a for a in assets if str(a.get("name", "")).endswith(".apk")), None)
        official_apk_url = self._derive_official_apk_url(str(tag))
        highlights = self._extract_release_highlights(release.get("body") or "", max_items=4, max_item_len=64)
        lines = [f"📦 TypeFree 最新公开版本：{tag}"]
        if highlights:
            lines.append("✨ 更新亮点：")
            for item in highlights:
                lines.append(f"• {item}")
        if official_apk_url:
            lines.append(f"🌐 官网下载直链：{official_apk_url}")
        elif self.config.official_site_origin:
            lines.append(f"🌐 官网下载页：{self.config.official_site_origin}")
        if apk_asset:
            lines.append(f"⬇️ GitHub 直链：{apk_asset.get('browser_download_url')}")
        lines.append(f"📝 更新日志：{html_url}")
        return "\n".join(lines)

    @staticmethod
    def _match_faq(text: str) -> str | None:
        for pattern, answer in FAQ_ENTRIES:
            if pattern.search(text):
                return answer
        return None

    @staticmethod
    def _format_faq_index() -> str:
        return (
            "📚 常见高频问题（发送对应指令或 @我 提问）：\n"
            "• /小鹤 —— 小鹤音形 / 第三方 Rime 码表怎么导入\n"
            "• /清空 —— 如何一键清空输入框已确认文字\n"
            "• /api  —— DeepSeek / 豆包 / 百炼 / Gemini 语音配置"
        )

    @staticmethod
    def _format_help() -> str:
        return (
            "🤖 TypeFree 群助手指令：\n"
            "• /bug 版本+设备+问题 —— 一键登记 Bug 到 GitHub\n"
            "• /建议 场景与期望 —— 提交功能建议到 GitHub\n"
            "• /最新 —— 获取最新公开版 APK 与更新日志\n"
            "• /faq  —— 查看小鹤导入、快捷键、API 等高频问答"
        )

    def handle_github_notify(self, payload: dict[str, Any]) -> dict[str, Any]:
        event_type = str(payload.get("event") or "").strip().lower()
        raw_group = payload.get("group_id") or self.config.default_group_id
        group_id = int(raw_group or 0)
        if not group_id:
            raise ValueError("Missing target group_id")

        if event_type == "release":
            tag = str(payload.get("tag") or "").strip()
            release_url = str(payload.get("release_url") or "").strip()
            apk_url = str(payload.get("apk_url") or "").strip()
            official_apk_url = str(payload.get("official_apk_url") or "").strip() or self._derive_official_apk_url(tag)
            official_site_url = str(payload.get("official_site_url") or "").strip() or self.config.official_site_origin
            apk_name = str(payload.get("apk_file") or "").strip()
            notified_issues = str(payload.get("notified_issues") or "").strip()
            is_signed = str(payload.get("is_signed") or "").lower() == "true"
            highlights = self._extract_release_highlights(
                payload.get("highlights") if payload.get("highlights") is not None else payload.get("release_notes"),
                max_items=6,
                max_item_len=72,
            )

            lines = [f"🚀 TypeFree {tag} 已正式发布！"]
            if highlights:
                lines.append("✨ 本版更新亮点：")
                for item in highlights:
                    lines.append(f"• {item}")
            if apk_name:
                sign_hint = "正式签名" if is_signed else "Debug 签名"
                lines.append(f"📦 安装包：{apk_name}（{sign_hint}）")
            if official_apk_url:
                lines.append(f"🌐 官网下载直链：{official_apk_url}")
            elif official_site_url:
                lines.append(f"🌐 官网下载页：{official_site_url}")
            if apk_url:
                lines.append(f"⬇️ GitHub 直链：{apk_url}")
            if notified_issues and notified_issues.lower() != "none":
                lines.append(f"🔧 本版已修复关联 Issue：{notified_issues}")
            if release_url:
                lines.append(f"📝 完整更新日志：{release_url}")
            lines.append("💡 小鹤音形专享版用户请注意勿直接用公开版覆盖安装。")
            message = "\n".join(lines)
        elif event_type == "issue":
            number = payload.get("number")
            title = str(payload.get("title") or "").strip()
            author = str(payload.get("author") or "").strip()
            labels = str(payload.get("labels") or "").strip()
            url = str(payload.get("url") or "").strip()
            message = (
                f"📌 GitHub 新反馈 Issue #{number}\n"
                f"标题：{title}\n"
                f"作者：@{author} | 标签：{labels or 'needs-triage'}\n"
                f"链接：{url}"
            )
        elif event_type == "text":
            message = str(payload.get("message") or "").strip()
            if not message:
                raise ValueError("Empty text message")
        else:
            raise ValueError(f"Unsupported event type: {event_type!r}")

        self.onebot.send_group_msg(group_id=group_id, text=message)
        return {"ok": True, "group_id": group_id, "event": event_type}


def verify_onebot_signature(secret: str, header_sig: str, body: bytes) -> bool:
    if not secret:
        return True
    if not header_sig.startswith("sha1="):
        return False
    expected = "sha1=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha1).hexdigest()
    return hmac.compare_digest(expected, header_sig.strip())


def verify_bearer_token(expected_secret: str, auth_header: str) -> bool:
    if not expected_secret:
        return False
    if not auth_header.startswith("Bearer "):
        return False
    token = auth_header[len("Bearer "):].strip()
    return hmac.compare_digest(expected_secret, token)


def create_http_handler(service: BridgeService) -> type[BaseHTTPRequestHandler]:
    class BridgeRequestHandler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: Any) -> None:
            logger.info("%s - %s", self.address_string(), format % args)

        def _send_json(self, status: int, body: dict[str, Any]) -> None:
            raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _read_chunked_body(self) -> bytes | None:
            chunks: list[bytes] = []
            total = 0
            while True:
                size_line = self.rfile.readline(64)
                if not size_line:
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "incomplete chunked body"})
                    return None
                size_str = size_line.split(b";", 1)[0].strip()
                if not size_str:
                    continue
                try:
                    chunk_len = int(size_str, 16)
                except ValueError:
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid chunk size"})
                    return None
                if chunk_len < 0:
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid chunk size"})
                    return None
                if chunk_len == 0:
                    while True:
                        trailer = self.rfile.readline(1024)
                        if not trailer or trailer in (b"\r\n", b"\n"):
                            break
                    break
                total += chunk_len
                if total > MAX_BODY_BYTES:
                    self.close_connection = True
                    self._send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "payload too large"})
                    return None
                data = self.rfile.read(chunk_len)
                if len(data) != chunk_len:
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "truncated chunk"})
                    return None
                chunks.append(data)
                crlf = self.rfile.readline(4)
                if crlf not in (b"\r\n", b"\n"):
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "malformed chunk delimiter"})
                    return None
            return b"".join(chunks)

        def _read_json_body(self) -> tuple[bytes, dict[str, Any]] | None:
            te = (self.headers.get("Transfer-Encoding") or "").lower()
            if "chunked" in te:
                raw = self._read_chunked_body()
                if raw is None:
                    return None
            else:
                length = int(self.headers.get("Content-Length") or "0")
                if length <= 0 or length > MAX_BODY_BYTES:
                    self.close_connection = True
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid content-length"})
                    return None
                raw = self.rfile.read(length)
            try:
                parsed = json.loads(raw.decode("utf-8"))
            except Exception:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid json"})
                return None
            if not isinstance(parsed, dict):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "json object required"})
                return None
            return raw, parsed

        def do_GET(self) -> None:
            if self.path == "/healthz":
                self._send_json(HTTPStatus.OK, {"status": "ok"})
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

        def do_POST(self) -> None:
            if self.path == "/onebot/event":
                read_res = self._read_json_body()
                if read_res is None:
                    return
                raw, payload = read_res
                sig = self.headers.get("X-Signature", "")
                if not verify_onebot_signature(service.config.onebot_event_secret, sig, raw):
                    self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "invalid signature"})
                    return
                try:
                    service.handle_onebot_event(payload)
                    self._send_json(HTTPStatus.OK, {"ok": True})
                except Exception as exc:
                    logger.exception("Error processing OneBot event")
                    self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})
                return

            if self.path == "/github/notify":
                if not service.config.bridge_secret:
                    self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "BRIDGE_SECRET not configured"})
                    return
                auth = self.headers.get("Authorization", "")
                if not verify_bearer_token(service.config.bridge_secret, auth):
                    self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                    return
                read_res = self._read_json_body()
                if read_res is None:
                    return
                _, payload = read_res
                try:
                    result = service.handle_github_notify(payload)
                    self._send_json(HTTPStatus.OK, result)
                except ValueError as exc:
                    self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                except Exception as exc:
                    logger.exception("Error processing GitHub notify")
                    self._send_json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
                return

            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    return BridgeRequestHandler


def main() -> None:
    config = BridgeConfig.from_env()
    service = BridgeService(config)
    server = ThreadingHTTPServer((config.listen_host, config.listen_port), create_http_handler(service))

    def _shutdown(_signum: int, _frame: Any) -> None:
        logger.info("Shutting down TypeFree QQ bridge...")
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    logger.info(
        "TypeFree QQ bridge listening on %s:%s (repo=%s, groups=%s)",
        config.listen_host,
        config.listen_port,
        config.github_repo,
        sorted(config.allowed_group_ids),
    )
    server.serve_forever()
    server.server_close()


if __name__ == "__main__":
    main()
