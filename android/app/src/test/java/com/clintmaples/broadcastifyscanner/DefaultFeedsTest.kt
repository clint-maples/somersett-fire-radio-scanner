package com.clintmaples.broadcastifyscanner

import com.clintmaples.broadcastifyscanner.data.CallsPages
import com.clintmaples.broadcastifyscanner.data.DefaultFeeds
import com.clintmaples.broadcastifyscanner.data.Feed
import com.clintmaples.broadcastifyscanner.data.FeedKind
import com.clintmaples.broadcastifyscanner.data.FeedRegion
import com.clintmaples.broadcastifyscanner.data.FeedStatus
import com.clintmaples.broadcastifyscanner.data.FeedUiState
import com.clintmaples.broadcastifyscanner.data.ScannerListItem
import com.clintmaples.broadcastifyscanner.data.buildScannerList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFeedsTest {
    @Test
    fun defaults_putCallsThenRenoThenCaliforniaListenFeeds() {
        assertEquals(
            listOf(
                "calls-11341-30433",
                "calls-11341-command",
                "calls-11341-tac",
                "7364",
                "14826",
                "47365",
                "47367",
            ),
            DefaultFeeds.ALL.map { it.feedId },
        )
        assertEquals(FeedKind.CALLS, DefaultFeeds.ALL[0].kind)
        assertEquals(FeedKind.CALLS, DefaultFeeds.ALL[1].kind)
        assertEquals(FeedKind.CALLS, DefaultFeeds.ALL[2].kind)
        assertEquals(FeedKind.LISTEN, DefaultFeeds.ALL[3].kind)
        assertEquals("Reno and Sparks Police and Fire", DefaultFeeds.ALL[3].name)
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

        val dispatch = DefaultFeeds.ALL[0]
        assertEquals(listOf("30433"), dispatch.callsLinks.map { it.talkgroup })
        assertEquals(
            "https://www.broadcastify.com/calls/tg/11341/30433",
            dispatch.callsLinks.single().url,
        )
        assertEquals(
            listOf("30434", "30435"),
            DefaultFeeds.ALL[1].callsLinks.map { it.talkgroup },
        )
        assertEquals(
            listOf("30436", "30437", "30438"),
            DefaultFeeds.ALL[2].callsLinks.map { it.talkgroup },
        )
        DefaultFeeds.ALL.filter { it.kind == FeedKind.CALLS }.forEach { feed ->
            assertFalse(feed.feedId.all { ch -> ch.isDigit() })
            assertTrue(feed.callsLinks.isNotEmpty())
            feed.callsLinks.forEach { link ->
                assertTrue(CallsPages.isPublicCallsTalkgroupUrl(link.url))
                assertTrue(link.url.endsWith("/${link.talkgroup}"))
                assertFalse(link.talkgroup == feed.feedId)
            }
        }
    }

    @Test
    fun callsUrl_rejectsListenPopoutAndOtherSystems() {
        assertFalse(
            CallsPages.isPublicCallsTalkgroupUrl(
                "https://www.broadcastify.com/listen/feed/7364",
            ),
        )
        assertFalse(
            CallsPages.isPublicCallsTalkgroupUrl(
                "https://www.broadcastify.com/calls/tg/99999/30433",
            ),
        )
        assertFalse(CallsPages.isPublicCallsTalkgroupUrl("http://www.broadcastify.com/calls/tg/11341/30433"))
    }

    @Test
    fun sections_labelNevadaThenCaliforniaWithoutReordering() {
        val rows = buildScannerList(DefaultFeeds.ALL.map { it.toPreview() })
        assertEquals(
            listOf(
                "S:Nevada / Washoe",
                "C:calls-11341-30433",
                "C:calls-11341-command",
                "C:calls-11341-tac",
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
                "calls-11341-command",
                "calls-11341-tac",
                "7364",
                "14826",
                "47365",
                "47367",
            ),
            merged.map { it.feedId },
        )
        assertEquals(FeedKind.CALLS, merged[0].kind)
        assertEquals(FeedKind.LISTEN, merged[3].kind)
    }

    private fun Feed.toPreview(): FeedUiState {
        return FeedUiState(
            feedId = feedId,
            name = name,
            status = FeedStatus.IDLE,
            kind = kind,
            region = region,
            callsLinks = callsLinks,
        )
    }
}
