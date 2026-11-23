#!/usr/bin/env python3
"""Unit tests for TypeFree QQ Bridge (no external network required)."""

from __future__ import annotations

import hashlib
import hmac
import json
import sys
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

import bridge  # noqa: E402


class FakeGitHubClient:
    def __init__(self) -> None:
        self.created_issues: list[dict[str, Any]] = []
        self.latest_release_calls = 0

    def create_issue(self, title: str, body: str, labels: list[str]) -> dict[str, Any]:
        num = len(self.created_issues) + 10
        item = {"title": title, "body": body, "labels": labels, "number": num}
        self.created_issues.append(item)
        return {"number": num, "html_url": f"https://github.com/dengxuezhao/TypeFree/issues/{num}"}

    def get_latest_release(self) -> dict[str, Any]:
        self.latest_release_calls += 1
        return {
            "tag_name": "v3.7.1",
            "html_url": "https://github.com/dengxuezhao/TypeFree/releases/tag/v3.7.1",
            "body": "### 更新亮点\n\n- **端侧离线语音**：支持断网识别\n- **小鹤原包导入**：免解压直接导入\n\n---\n\n### 安装包说明\n- 忽略此行",
            "assets": [
                {
                    "name": "TypeFree-v3.7.1-standard-release.apk",
                    "browser_download_url": "https://example.com/TypeFree-v3.7.1.apk",
                }
            ],
        }


class FakeOneBotClient:
    def __init__(self) -> None:
        self.sent: list[dict[str, Any]] = []

    def send_group_msg(self, group_id: int, text: str, reply_to_message_id: int | None = None) -> None:
        self.sent.append({
            "group_id": group_id,
            "text": text,
            "reply_to_message_id": reply_to_message_id,
        })


class BridgeTests(unittest.TestCase):
    def _make_service(self, now: list[float] | None = None) -> tuple[bridge.BridgeService, FakeGitHubClient, FakeOneBotClient]:
        clock = (lambda: now[0]) if now is not None else bridge.time.time
        cfg = bridge.BridgeConfig(
            github_token="ghp_test",
            bridge_secret="bridge-secret",
            onebot_event_secret="event-secret",
            allowed_group_ids={801143863},
            default_group_id=801143863,
            user_cooldown_seconds=10.0,
            group_max_replies_per_min=5,
            max_issues_per_user_per_day=2,
        )
        gh = FakeGitHubClient()
        ob = FakeOneBotClient()
        limiter = bridge.RateLimiter(10.0, 5, 2, clock=clock)
        return bridge.BridgeService(cfg, gh, ob, limiter), gh, ob

    def test_parse_segment_and_cq_mentions(self) -> None:
        seg_payload = {
            "self_id": 99999,
            "message": [
                {"type": "at", "data": {"qq": "99999"}},
                {"type": "text", "data": {"text": "  /小鹤 怎么导入 "}},
            ],
        }
        text, mentioned = bridge.parse_onebot_message(seg_payload)
        self.assertTrue(mentioned)
        self.assertEqual(text, "/小鹤 怎么导入")

        cq_payload = {
            "self_id": 99999,
            "raw_message": "[CQ:at,qq=99999] /bug &#91;v3.7.1&#93; 候选栏异常",
        }
        text2, mentioned2 = bridge.parse_onebot_message(cq_payload)
        self.assertTrue(mentioned2)
        self.assertEqual(text2, "/bug [v3.7.1] 候选栏异常")

    def test_bug_command_creates_github_issue_with_area_labels(self) -> None:
        svc, gh, ob = self._make_service()
        reply = svc.handle_onebot_event({
            "post_type": "message",
            "message_type": "group",
            "self_id": 99999,
            "group_id": 801143863,
            "user_id": 123456,
            "message_id": 77,
            "sender": {"nickname": "测试用户"},
            "raw_message": "/bug v3.7.1 小鹤音形 九键候选栏重叠",
        })
        self.assertIsNotNone(reply)
        self.assertEqual(len(gh.created_issues), 1)
        issue = gh.created_issues[0]
        self.assertTrue(issue["title"].startswith("[QQ反馈][Bug]"))
        self.assertIn("bug", issue["labels"])
        self.assertIn("needs-triage", issue["labels"])
        self.assertIn("area: codetable", issue["labels"])
        self.assertIn("area: keyboard", issue["labels"])
        self.assertNotIn("123456", issue["body"])
        self.assertEqual(ob.sent[0]["reply_to_message_id"], 77)

    def test_short_bug_prompts_for_more_detail(self) -> None:
        svc, gh, ob = self._make_service()
        reply = svc.handle_onebot_event({
            "post_type": "message",
            "message_type": "group",
            "self_id": 99999,
            "group_id": 801143863,
            "user_id": 123456,
            "message_id": 78,
            "raw_message": "/bug 闪退",
        })
        self.assertIn("至少 8 个字", reply or "")
        self.assertEqual(len(gh.created_issues), 0)
        self.assertEqual(len(ob.sent), 1)

    def test_ignores_untriggered_normal_chat_and_other_groups(self) -> None:
        svc, _, ob = self._make_service()
        self.assertIsNone(svc.handle_onebot_event({
            "post_type": "message",
            "message_type": "group",
            "self_id": 99999,
            "group_id": 801143863,
            "user_id": 123456,
            "raw_message": "今天小鹤打字很顺畅啊",
        }))
        self.assertIsNone(svc.handle_onebot_event({
            "post_type": "message",
            "message_type": "group",
            "self_id": 99999,
            "group_id": 111111,
            "user_id": 123456,
            "raw_message": "/最新",
        }))
        self.assertEqual(len(ob.sent), 0)

    def test_cooldown_and_daily_issue_quota(self) -> None:
        now = [1000.0]
        svc, gh, _ = self._make_service(now=now)
        base_ev = {
            "post_type": "message",
            "message_type": "group",
            "self_id": 99999,
            "group_id": 801143863,
            "user_id": 555,
            "raw_message": "/建议 增加九键布局支持和防误触开关",
        }
        self.assertIn("#10", svc.handle_onebot_event(base_ev) or "")
        # Within cooldown window -> dropped
        self.assertIsNone(svc.handle_onebot_event(base_ev))

        now[0] += 11.0
        self.assertIn("#11", svc.handle_onebot_event(base_ev) or "")

        now[0] += 11.0
        # 3rd issue on same day hits daily quota of 2
        reply = svc.handle_onebot_event(base_ev)
        self.assertIn("已达上限", reply or "")
        self.assertEqual(len(gh.created_issues), 2)

    def test_github_notify_release_and_http_auth(self) -> None:
        svc, _, ob = self._make_service()
        server = ThreadingHTTPServer(("127.0.0.1", 0), bridge.create_http_handler(svc))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]
            # Unauthorized rejected
            req_unauth = urllib.request.Request(
                f"http://127.0.0.1:{port}/github/notify",
                data=b'{"event":"release","tag":"v3.7.2"}',
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req_unauth)
            self.assertEqual(ctx.exception.code, 401)

            # Authorized release notification forwarded
            req_ok = urllib.request.Request(
                f"http://127.0.0.1:{port}/github/notify",
                data=json.dumps({
                    "event": "release",
                    "tag": "v3.7.2",
                    "apk_file": "TypeFree-v3.7.2-standard-release.apk",
                    "apk_url": "https://example.com/v3.7.2.apk",
                    "release_url": "https://github.com/dengxuezhao/TypeFree/releases/tag/v3.7.2",
                    "is_signed": "true",
                    "notified_issues": "#1, #2",
                    "release_notes": "### 更新亮点\n\n- **端侧离线模型**：断网可用\n- **26 键优化**：大写键帽与上滑清空\n\n---\n\n### 安装包说明\n- 不应出现",
                }).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": "Bearer bridge-secret",
                },
                method="POST",
            )
            with urllib.request.urlopen(req_ok) as resp:
                self.assertEqual(resp.status, 200)
            self.assertEqual(len(ob.sent), 1)
            self.assertIn("v3.7.2", ob.sent[0]["text"])
            self.assertIn("端侧离线模型", ob.sent[0]["text"])
            self.assertNotIn("不应出现", ob.sent[0]["text"])
            self.assertIn("https://typefree.xuezhao.space/downloads/TypeFree-v3.7.2-release.apk", ob.sent[0]["text"])
            self.assertIn("#1, #2", ob.sent[0]["text"])

            # HMAC verified OneBot event
            ev_bytes = json.dumps({
                "post_type": "message",
                "message_type": "group",
                "self_id": 99999,
                "group_id": 801143863,
                "user_id": 888,
                "raw_message": "/最新",
            }).encode("utf-8")
            sig = "sha1=" + hmac.new(b"event-secret", ev_bytes, hashlib.sha1).hexdigest()
            req_ev = urllib.request.Request(
                f"http://127.0.0.1:{port}/onebot/event",
                data=ev_bytes,
                headers={"Content-Type": "application/json", "X-Signature": sig},
                method="POST",
            )
            with urllib.request.urlopen(req_ev) as resp:
                self.assertEqual(resp.status, 200)
            self.assertIn("v3.7.1", ob.sent[-1]["text"])
            self.assertIn("端侧离线语音", ob.sent[-1]["text"])
            self.assertNotIn("忽略此行", ob.sent[-1]["text"])
            self.assertIn("https://typefree.xuezhao.space/downloads/TypeFree-v3.7.1-release.apk", ob.sent[-1]["text"])

            # Chunked Transfer-Encoding (as used by Node/NapCat HTTP clients)
            import http.client
            chunked_payload = json.dumps({
                "post_type": "message",
                "message_type": "group",
                "self_id": 99999,
                "group_id": 801143863,
                "user_id": 889,
                "message": [
                    {"type": "at", "data": {"qq": "99999"}},
                    {"type": "text", "data": {"text": " /帮助"}},
                ],
            }).encode("utf-8")
            chunk_sig = "sha1=" + hmac.new(b"event-secret", chunked_payload, hashlib.sha1).hexdigest()
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            conn.putrequest("POST", "/onebot/event")
            conn.putheader("Content-Type", "application/json")
            conn.putheader("Transfer-Encoding", "chunked")
            conn.putheader("X-Signature", chunk_sig)
            conn.endheaders()
            mid = len(chunked_payload) // 2
            for part in (chunked_payload[:mid], chunked_payload[mid:]):
                conn.send(f"{len(part):x}\r\n".encode("ascii") + part + b"\r\n")
            conn.send(b"0\r\n\r\n")
            c_resp = conn.getresponse()
            self.assertEqual(c_resp.status, 200)
            c_resp.read()
            conn.close()
            self.assertIn("TypeFree 群助手指令", ob.sent[-1]["text"])
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
