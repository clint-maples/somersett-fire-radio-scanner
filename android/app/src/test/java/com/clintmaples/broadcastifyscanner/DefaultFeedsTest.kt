package com.clintmaples.broadcastifyscanner

import com.clintmaples.broadcastifyscanner.data.CallsCatalog
import com.clintmaples.broadcastifyscanner.data.DefaultFeeds
import com.clintmaples.broadcastifyscanner.data.Feed
import com.clintmaples.broadcastifyscanner.data.FeedKind
import com.clintmaples.broadcastifyscanner.data.FeedRegion
import com.clintmaples.broadcastifyscanner.data.FeedStatus
import com.clintmaples.broadcastifyscanner.data.FeedStore
import com.clintmaples.broadcastifyscanner.data.FeedUiState
import com.clintmaples.broadcastifyscanner.data.ScannerListItem
import com.clintmaples.broadcastifyscanner.data.buildScannerList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFeedsTest {
    @Test
    fun defaults_putEachTalkgroupThenRenoThenCaliforniaListenFeeds() {
        assertEquals(
            listOf(
                "calls-11341-30433",
                "calls-11341-30434",
                "calls-11341-30435",
                "calls-11341-30436",
                "calls-11341-30437",
                "calls-11341-30438",
                "7364",
                "14826",
                "47365",
                "47367",
            ),
            DefaultFeeds.ALL.map { it.feedId },
        )
        val calls = DefaultFeeds.ALL.filter { it.kind == FeedKind.CALLS }
        assertEquals(
            listOf("30433", "30434", "30435", "30436", "30437", "30438"),
            calls.map { it.talkgroup },
        )
        calls.forEach { feed ->
            assertEquals(CallsCatalog.SYSTEM_SID, feed.systemSid)
            assertEquals(FeedRegion.NEVADA, feed.region)
            assertFalse(feed.feedId.all { ch -> ch.isDigit() })
            assertFalse(feed.talkgroup == feed.feedId)
        }
        assertEquals(FeedKind.LISTEN, DefaultFeeds.ALL[6].kind)
        assertEquals("Reno and Sparks Police and Fire", DefaultFeeds.ALL[6].name)
        assertEquals(
            listOf(
                "East Placer and Nevada Counties CAL FIRE NEU - Kings Beach Area",
                "CAL FIRE NEU West",
                "Tahoe National Forest West",
            ),
            DefaultFeeds.ALL.takeLast(3).map { it.name },
        )
    }

    @Test
    fun callsTalkgroups_areNotListenFeedIds() {
        val listen = DefaultFeeds.listenFeedIds()
        assertEquals(listOf("7364", "14826", "47365", "47367"), listen)
        val talkgroups = listOf("30433", "30434", "30435", "30436", "30437", "30438")
        assertTrue(talkgroups.none { it in listen })
    }

    @Test
    fun sections_labelNevadaThenCaliforniaWithoutReordering() {
        val rows = buildScannerList(DefaultFeeds.ALL.map { it.toPreview() })
        assertEquals(
            listOf(
                "S:Nevada / Washoe",
                "C:calls-11341-30433",
                "C:calls-11341-30434",
                "C:calls-11341-30435",
                "C:calls-11341-30436",
                "C:calls-11341-30437",
                "C:calls-11341-30438",
                "C:7364",
                "S:California / NEU–TNF",
                "C:14826",
                "C:47365",
                "C:47367",
            ),
            rows.map { row ->
                when (row) {
                    is ScannerListItem.Section -> "S:${row.title}"
                    is ScannerListItem.Card -> "C:${row.feed.feedId}"
                }
            },
        )
    }

    @Test
    fun upgradeLegacy_prependsNevadaAndKeepsCaliforniaOrder() {
        val legacy = listOf(
            Feed("14826", "East Placer", region = FeedRegion.CALIFORNIA),
            Feed("47365", "CAL FIRE NEU West", region = FeedRegion.CALIFORNIA),
            Feed("47367", "Tahoe National Forest West", region = FeedRegion.CALIFORNIA),
        )
        val merged = DefaultFeeds.upgradeLegacy(legacy)
        assertEquals(
            listOf(
                "calls-11341-30433",
                "calls-11341-30434",
                "calls-11341-30435",
                "calls-11341-30436",
                "calls-11341-30437",
                "calls-11341-30438",
                "7364",
                "14826",
                "47365",
                "47367",
            ),
            merged.map { it.feedId },
        )
        assertEquals(FeedKind.CALLS, merged[0].kind)
        assertEquals(FeedKind.LISTEN, merged[6].kind)
    }

    @Test
    fun decode_expandsOldGroupedDeepLinksIntoOneCardPerTalkgroup() {
        val raw = """
            [
              {"feedId":"calls-11341-30433","name":"NSRS Washoe TMFPD Red Dispatch","kind":"calls","region":"nevada","callsLinks":[{"talkgroup":"30433","label":"Red Dispatch","url":"https://www.broadcastify.com/calls/tg/11341/30433"}]},
              {"feedId":"calls-11341-command","name":"TMFPD Command 1 + Command 2","kind":"calls","region":"nevada","callsLinks":[{"talkgroup":"30434","label":"Command 1","url":"https://www.broadcastify.com/calls/tg/11341/30434"},{"talkgroup":"30435","label":"Command 2","url":"https://www.broadcastify.com/calls/tg/11341/30435"}]},
              {"feedId":"7364","name":"Reno and Sparks Police and Fire","kind":"listen","region":"nevada"}
            ]
        """.trimIndent()
        val feeds = DefaultFeeds.dedupe(FeedStore.decode(raw))
        assertEquals(
            listOf("calls-11341-30433", "calls-11341-30434", "calls-11341-30435", "7364"),
            feeds.map { it.feedId },
        )
        assertEquals("30434", feeds[1].talkgroup)
        assertEquals(CallsCatalog.SYSTEM_SID, feeds[1].systemSid)
        assertEquals("TMFPD Command 1", feeds[1].name)
        assertTrue(FeedStore.encode(feeds).contains(""""talkgroup":"30434""""))
        assertFalse(FeedStore.encode(feeds).contains("broadcastify.com"))
    }

    private fun Feed.toPreview(): FeedUiState {
        return FeedUiState(
            feedId = feedId,
            name = name,
            status = FeedStatus.IDLE,
            kind = kind,
            region = region,
            talkgroup = talkgroup,
            systemSid = systemSid,
        )
    }
}
