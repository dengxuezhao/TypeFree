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


class FakeQQOfficialClient:
    def __init__(self) -> None:
        self.group_sent: list[dict[str, Any]] = []
        self.c2c_sent: list[dict[str, Any]] = []
        self.replied_event = threading.Event()

    def send_group_msg(self, group_openid: str, text: str, msg_id: str | None = None) -> dict[str, Any]:
        self.group_sent.append({"group_openid": group_openid, "text": text, "msg_id": msg_id})
        self.replied_event.set()
        return {"id": "reply_group_1"}

    def send_c2c_msg(self, user_openid: str, text: str, msg_id: str | None = None) -> dict[str, Any]:
        self.c2c_sent.append({"user_openid": user_openid, "text": text, "msg_id": msg_id})
        self.replied_event.set()
        return {"id": "reply_c2c_1"}


class BridgeTests(unittest.TestCase):
    def _make_service(
        self,
        now: list[float] | None = None,
        *,
        qq_official_secret: str = "test_official_secret_32bytes_ok!",
        onebot_http_url: str = "http://127.0.0.1:3000",
    ) -> tuple[bridge.BridgeService, FakeGitHubClient, FakeOneBotClient, FakeQQOfficialClient]:
        clock = (lambda: now[0]) if now is not None else bridge.time.time
        cfg = bridge.BridgeConfig(
            github_token="ghp_test",
            bridge_secret="bridge-secret",
            onebot_http_url=onebot_http_url,
            onebot_event_secret="event-secret",
            allowed_group_ids={801143863},
            default_group_id=801143863,
            user_cooldown_seconds=10.0,
            group_max_replies_per_min=5,
            max_issues_per_user_per_day=2,
            qq_official_app_id="1906541210",
            qq_official_app_secret=qq_official_secret,
            qq_official_verify_sig=True,
        )
        gh = FakeGitHubClient()
        ob = FakeOneBotClient()
        qqo = FakeQQOfficialClient()
        limiter = bridge.RateLimiter(10.0, 5, 2, clock=clock)
        return bridge.BridgeService(cfg, gh, ob, limiter, qq_official_client=qqo), gh, ob, qqo

    def _unpack(self, *args: Any, **kwargs: Any) -> tuple[bridge.BridgeService, FakeGitHubClient, FakeOneBotClient]:
        svc, gh, ob, _ = self._make_service(*args, **kwargs)
        return svc, gh, ob

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
        svc, gh, ob, _ = self._make_service()
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
        svc, gh, ob, _ = self._make_service()
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
        svc, _, ob, _ = self._make_service()
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
        svc, gh, _, _ = self._make_service(now=now)
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
        svc, _, ob, _ = self._make_service()
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
                    "release_notes": "### 更新亮点\n\n- **端侧离线模型**：断网也能语音听写与净写（独立 :local_ai 进程 + llama.cpp）\n- **26 键优化**：大写键帽与上滑清空\n\n---\n\n### 安装包说明\n- 不应出现",
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
            self.assertIn("• 端侧离线模型：断网也能语音听写与净写", ob.sent[0]["text"])
            self.assertNotIn(":local_ai", ob.sent[0]["text"])
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

    def test_ed25519_rfc8032_vectors_and_secret_seed(self) -> None:
        # RFC 8032 Section 7.1 TEST 1 (empty message)
        seed1 = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        pub1 = bridge.ed25519_public_key_from_seed(seed1)
        self.assertEqual(pub1.hex(), "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        sig1 = bridge.ed25519_sign(seed1, b"")
        self.assertEqual(
            sig1.hex(),
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        self.assertTrue(bridge.ed25519_verify(pub1, b"", sig1))
        self.assertFalse(bridge.ed25519_verify(pub1, b"x", sig1))

        # RFC 8032 Section 7.1 TEST 2 (1-byte 0x72)
        seed2 = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        pub2 = bridge.ed25519_public_key_from_seed(seed2)
        self.assertEqual(pub2.hex(), "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        sig2 = bridge.ed25519_sign(seed2, b"\x72")
        self.assertEqual(
            sig2.hex(),
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        )
        self.assertTrue(bridge.ed25519_verify(pub2, b"\x72", sig2))

        # Secret shorter than 32 bytes repeats until 32 bytes
        self.assertEqual(bridge.ed25519_seed_from_secret("abc"), (b"abc" * 11)[:32])

    def test_qq_official_webhook_validation_and_dispatch(self) -> None:
        secret = "test_official_secret_32bytes_ok!"
        svc, gh, _, qqo = self._make_service(qq_official_secret=secret)
        seed = bridge.ed25519_seed_from_secret(secret)
        pub = bridge.ed25519_public_key_from_seed(seed)

        server = ThreadingHTTPServer(("127.0.0.1", 0), bridge.create_http_handler(svc))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]

            # 1. op=13 callback validation
            challenge_req = urllib.request.Request(
                f"http://127.0.0.1:{port}/qq-official/webhook",
                data=json.dumps({
                    "op": 13,
                    "d": {"plain_token": "plain_abc", "event_ts": "1732590000"},
                }).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(challenge_req) as resp:
                self.assertEqual(resp.status, 200)
                challenge_resp = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(challenge_resp["plain_token"], "plain_abc")
            self.assertTrue(
                bridge.ed25519_verify(
                    pub,
                    b"1732590000plain_abc",
                    bytes.fromhex(challenge_resp["signature"]),
                )
            )

            # 2. Invalid signature on op=0 rejected with 401
            bad_req = urllib.request.Request(
                f"http://127.0.0.1:{port}/qq-official/webhook",
                data=b'{"op":0,"t":"GROUP_AT_MESSAGE_CREATE","d":{}}',
                headers={
                    "Content-Type": "application/json",
                    "X-Signature-Timestamp": "1732590001",
                    "X-Signature-Ed25519": "00" * 64,
                },
                method="POST",
            )
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(bad_req)
            self.assertEqual(ctx.exception.code, 401)

            # 3. Signed GROUP_AT_MESSAGE_CREATE /bug creates issue & replies without URLs
            ev_bytes = json.dumps({
                "op": 0,
                "t": "GROUP_AT_MESSAGE_CREATE",
                "d": {
                    "id": "msg_group_1",
                    "group_openid": "GROUP_OPENID_123456",
                    "content": "<@!1906541210> /bug v3.9.0 小米14 九键候选栏遮挡输入法面板",
                    "author": {"member_openid": "MEMBER_OPENID_999"},
                },
            }).encode("utf-8")
            ts = "1732590002"
            sig_hex = bridge.ed25519_sign(seed, ts.encode("utf-8") + ev_bytes).hex()
            qqo.replied_event.clear()
            ok_req = urllib.request.Request(
                f"http://127.0.0.1:{port}/qq-official/webhook",
                data=ev_bytes,
                headers={
                    "Content-Type": "application/json",
                    "X-Signature-Timestamp": ts,
                    "X-Signature-Ed25519": sig_hex,
                },
                method="POST",
            )
            with urllib.request.urlopen(ok_req) as resp:
                self.assertEqual(resp.status, 200)
                self.assertEqual(json.loads(resp.read().decode("utf-8")), {"op": 12})

            self.assertTrue(qqo.replied_event.wait(timeout=3.0))
            self.assertEqual(len(gh.created_issues), 1)
            self.assertEqual(qqo.group_sent[0]["group_openid"], "GROUP_OPENID_123456")
            self.assertEqual(qqo.group_sent[0]["msg_id"], "msg_group_1")
            self.assertIn("#10", qqo.group_sent[0]["text"])
            self.assertNotIn("http://", qqo.group_sent[0]["text"])
            self.assertNotIn("https://", qqo.group_sent[0]["text"])

            # 4. /最新 and /api sanitize URLs for QQ Official filter
            latest_ev = {
                "op": 0,
                "t": "GROUP_AT_MESSAGE_CREATE",
                "d": {
                    "id": "msg_group_2",
                    "group_openid": "GROUP_OPENID_123456",
                    "content": "/最新",
                    "author": {"member_openid": "MEMBER_OPENID_OTHER"},
                },
            }
            svc.process_qq_official_dispatch(latest_ev)
            self.assertIn("v3.7.1", qqo.group_sent[-1]["text"])
            self.assertNotIn("https://", qqo.group_sent[-1]["text"])

            # 5. C2C_MESSAGE_CREATE /api replies via send_c2c_msg without URLs
            c2c_ev = {
                "op": 0,
                "t": "C2C_MESSAGE_CREATE",
                "d": {
                    "id": "msg_c2c_1",
                    "content": "/api",
                    "author": {"user_openid": "USER_OPENID_777"},
                },
            }
            svc.process_qq_official_dispatch(c2c_ev)
            self.assertEqual(len(qqo.c2c_sent), 1)
            self.assertEqual(qqo.c2c_sent[0]["user_openid"], "USER_OPENID_777")
            self.assertIn("DeepSeek", qqo.c2c_sent[0]["text"])
            self.assertNotIn("https://", qqo.c2c_sent[0]["text"])
        finally:
            server.shutdown()
            server.server_close()

    def test_github_notify_skips_cleanly_without_push_channel(self) -> None:
        svc, _, ob, _ = self._make_service(onebot_http_url="")
        res = svc.handle_github_notify({"event": "text", "message": "hi"})
        self.assertTrue(res["ok"])
        self.assertIn("skipped", res)
        self.assertEqual(len(ob.sent), 0)


if __name__ == "__main__":
    unittest.main()
