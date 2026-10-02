#!/usr/bin/env python3
"""Stdlib tests for desktop/server.py (no network)."""

from __future__ import annotations

import unittest

import server


class RewriteTests(unittest.TestCase):
    def test_rewrite_playlist(self) -> None:
        body = (
            "#EXTM3U\n"
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
            "seg001.ts\n"
        )
        out = server.rewrite_m3u8(body, "https://hls-o2.broadcastify.com/feed/1/playlist.m3u8")
        text = out.decode("utf-8")
        self.assertIn("/proxy?url=", text)
        self.assertIn("seg001.ts", text)
        self.assertIn("key.bin", text)

    def test_proxy_host_allowlist(self) -> None:
        self.assertTrue(server.is_allowed_proxy_url("https://hls-o2.broadcastify.com/x"))
        self.assertTrue(server.is_allowed_proxy_url("https://www.broadcastify.com/x"))
        self.assertFalse(server.is_allowed_proxy_url("https://evil.example/x"))
        self.assertFalse(server.is_allowed_proxy_url("file:///etc/passwd"))

    def test_default_listen_feeds_are_not_calls_talkgroups(self) -> None:
        ids = [feed["feedId"] for feed in server.DEFAULT_FEEDS]
        self.assertEqual(ids, ["7364", "14826", "47365", "47367"])
        talkgroups = {"30433", "30434", "30435", "30436", "30437", "30438"}
        self.assertTrue(talkgroups.isdisjoint(ids))
        self.assertEqual(
            [call["talkgroups"] for call in server.DEFAULT_CALLS],
            [["30433"], ["30434", "30435"], ["30436", "30437", "30438"]],
        )
        for call in server.DEFAULT_CALLS:
            self.assertFalse(str(call["id"]).isdigit())
            for url in call["urls"]:
                self.assertIn("/calls/tg/11341/", url)

    def test_listen_id_rejects_calls_card_id(self) -> None:
        with self.assertRaises(ValueError):
            server.normalize_listen_feed_id("calls-11341-30433")
        self.assertEqual(server.normalize_listen_feed_id("7364"), "7364")

    def test_unescape(self) -> None:
        self.assertEqual(
            server.unescape_jsonish(r"https:\/\/hls-o2.broadcastify.com\/t\/x"),
            "https://hls-o2.broadcastify.com/t/x",
        )


if __name__ == "__main__":
    unittest.main()
