package com.clintmaples.broadcastifyscanner

import com.clintmaples.broadcastifyscanner.data.CallsParser
import com.clintmaples.broadcastifyscanner.data.LiveCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallsParserTest {
    @Test
    fun parse_readsCallMetadataAndIgnoresAuthErrorShape() {
        val body = """
            {
              "calls": [{
                "id": "11341-30433",
                "ts": 1790973000,
                "systemId": 4777,
                "call_tg": 30433,
                "filename": "1790973000-30433",
                "enc": "m4a",
                "hash": "abc123",
                "display": "TMFPD Dispatch"
              }],
              "serverTime": 1790973010,
              "lastPos": 1790973005
            }
        """.trimIndent()
        val poll = CallsParser.parse(body)
        assertNull(poll.error)
        assertEquals(1790973005.0, poll.lastPos, 0.0)
        assertEquals(1790973010L, poll.serverTime)
        val call = poll.calls.single()
        assertEquals("30433", call.talkgroup)
        assertEquals("4777", call.systemId)
        assertEquals("abc123", call.hash)
        assertEquals(
            "https://calls.broadcastify.com/abc123/4777/1790973000-30433.m4a",
            CallsParser.audioUrl(call),
        )
    }

    @Test
    fun audioUrl_omitsEmptyHashAndRejectsTraversal() {
        val plain = LiveCall(
            key = "k",
            talkgroup = "30434",
            systemId = "4777",
            filename = "1790973000-30434",
            enc = "mp3",
            hash = null,
            ts = 1,
            display = "",
        )
        assertEquals(
            "https://calls.broadcastify.com/4777/1790973000-30434.mp3",
            CallsParser.audioUrl(plain),
        )
        val bad = plain.copy(filename = "../etc/passwd")
        try {
            CallsParser.audioUrl(bad)
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
            // path segments stay on the calls CDN
        }
    }

    @Test
    fun parse_surfacesAuthError() {
        val poll = CallsParser.parse("""{"error":"auth_required"}""")
        assertEquals("auth_required", poll.error)
        assertEquals(emptyList<LiveCall>(), poll.calls)
    }
}
