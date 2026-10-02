package com.clintmaples.broadcastifyscanner.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class FeedKind {
    /** Classic Broadcastify listen feed. Plays in-app via the HLS popout scrape. */
    LISTEN,

    /** Broadcastify Calls talkgroup. Opens the public Calls page. Not an HLS feed ID. */
    CALLS,
}

enum class FeedRegion {
    NEVADA,
    CALIFORNIA,
    OTHER,
}

data class CallsLink(
    val talkgroup: String,
    val label: String,
    val url: String,
)

data class Feed(
    val feedId: String,
    val name: String,
    val kind: FeedKind = FeedKind.LISTEN,
    val region: FeedRegion = FeedRegion.OTHER,
    val callsLinks: List<CallsLink> = emptyList(),
)

data class FeedMeta(
    val feedId: String,
    val name: String,
    val hlsUrl: String,
)

enum class FeedStatus {
    IDLE,
    LOADING,
    PLAYING,
    MUTED,
    RECONNECTING,
    ERROR,
}

data class FeedUiState(
    val feedId: String,
    val name: String,
    val status: FeedStatus,
    val statusDetail: String = "",
    val muted: Boolean = false,
    val volume: Float = 1f,
    val wantPlay: Boolean = false,
    val kind: FeedKind = FeedKind.LISTEN,
    val region: FeedRegion = FeedRegion.OTHER,
    val callsLinks: List<CallsLink> = emptyList(),
)

data class ScannerUiState(
    val feeds: List<FeedUiState> = emptyList(),
    val masterVolume: Float = 0.8f,
    val keepAwake: Boolean = false,
    val addPanelOpen: Boolean = false,
)

sealed class ScannerListItem {
    abstract val key: String

    data class Section(val title: String, override val key: String) : ScannerListItem()

    data class Card(val feed: FeedUiState) : ScannerListItem() {
        override val key: String get() = "card-${feed.feedId}"
    }
}

/**
 * Public Broadcastify Calls pages for Washoe NSRS / TMFPD.
 * System sid 11341. These talkgroup numbers are not listen feed IDs.
 */
object CallsPages {
    const val SYSTEM_SID = "11341"
    const val BADGE = "Calls (opens Broadcastify)"

    fun talkgroupUrl(talkgroup: String): String {
        return "https://www.broadcastify.com/calls/tg/$SYSTEM_SID/$talkgroup"
    }

    fun link(talkgroup: String, label: String): CallsLink {
        return CallsLink(
            talkgroup = talkgroup,
            label = label,
            url = talkgroupUrl(talkgroup),
        )
    }

    /**
     * True only for a public https Calls talkgroup page on this system.
     * Rejects listen popout URLs and anything that is not sid 11341.
     */
    fun isPublicCallsTalkgroupUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!BroadcastifyAllowlist.isAllowedUrl(parsed)) return false
        if (parsed.querySize != 0) return false
        val segments = parsed.pathSegments
        if (segments.size != 4) return false
        if (segments[0] != "calls" || segments[1] != "tg") return false
        if (segments[2] != SYSTEM_SID) return false
        val tg = segments[3]
        return tg.isNotEmpty() && tg.all { it.isDigit() }
    }
}

object FeedSections {
    const val NEVADA = "Nevada / Washoe"
    const val CALIFORNIA = "California / NEU–TNF"

    fun title(region: FeedRegion): String? = when (region) {
        FeedRegion.NEVADA -> NEVADA
        FeedRegion.CALIFORNIA -> CALIFORNIA
        FeedRegion.OTHER -> null
    }
}

object DefaultFeeds {
    private val renoSparks = Feed(
        feedId = "7364",
        name = "Reno and Sparks Police and Fire",
        kind = FeedKind.LISTEN,
        region = FeedRegion.NEVADA,
    )
    private val eastPlacer = Feed(
        feedId = "14826",
        name = "East Placer and Nevada Counties CAL FIRE NEU - Kings Beach Area",
        kind = FeedKind.LISTEN,
        region = FeedRegion.CALIFORNIA,
    )
    private val neuWest = Feed(
        feedId = "47365",
        name = "CAL FIRE NEU West",
        kind = FeedKind.LISTEN,
        region = FeedRegion.CALIFORNIA,
    )
    private val tnfWest = Feed(
        feedId = "47367",
        name = "Tahoe National Forest West",
        kind = FeedKind.LISTEN,
        region = FeedRegion.CALIFORNIA,
    )

    val ALL: List<Feed> = listOf(
        Feed(
            feedId = "calls-11341-30433",
            name = "NSRS Washoe TMFPD Red Dispatch",
            kind = FeedKind.CALLS,
            region = FeedRegion.NEVADA,
            callsLinks = listOf(CallsPages.link("30433", "Red Dispatch")),
        ),
        Feed(
            feedId = "calls-11341-command",
            name = "TMFPD Command 1 + Command 2",
            kind = FeedKind.CALLS,
            region = FeedRegion.NEVADA,
            callsLinks = listOf(
                CallsPages.link("30434", "Command 1"),
                CallsPages.link("30435", "Command 2"),
            ),
        ),
        Feed(
            feedId = "calls-11341-tac",
            name = "TMFPD Tac 4–6",
            kind = FeedKind.CALLS,
            region = FeedRegion.NEVADA,
            callsLinks = listOf(
                CallsPages.link("30436", "Tac 4"),
                CallsPages.link("30437", "Tac 5"),
                CallsPages.link("30438", "Tac 6"),
            ),
        ),
        renoSparks,
        eastPlacer,
        neuWest,
        tnfWest,
    )

    /** Numeric listen feed IDs only. Never includes Calls talkgroups. */
    fun listenFeedIds(): List<String> {
        return ALL.filter { it.kind == FeedKind.LISTEN }.map { it.feedId }
    }

    fun knownListen(feedId: String): Feed? {
        return ALL.firstOrNull { it.kind == FeedKind.LISTEN && it.feedId == feedId }
    }

    /**
     * Prepend Nevada defaults that an older install does not have yet.
     * Leaves the saved order of everything else alone, including the three
     * California listen feeds.
     */
    fun upgradeLegacy(existing: List<Feed>): List<Feed> {
        val have = existing.map { it.feedId }.toSet()
        val newcomers = ALL.filter { feed ->
            feed.region == FeedRegion.NEVADA && feed.feedId !in have
        }
        return newcomers + existing
    }
}

/**
 * Insert section labels where the region changes. Does not reorder cards.
 */
fun buildScannerList(feeds: List<FeedUiState>): List<ScannerListItem> {
    val out = ArrayList<ScannerListItem>(feeds.size + 2)
    var previous: FeedRegion? = null
    for (feed in feeds) {
        val title = FeedSections.title(feed.region)
        if (title != null && feed.region != previous) {
            out += ScannerListItem.Section(
                title = title,
                key = "section-${feed.region.name}-${feed.feedId}",
            )
        }
        previous = feed.region
        out += ScannerListItem.Card(feed)
    }
    return out
}
