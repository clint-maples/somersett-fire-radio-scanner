package com.clintmaples.broadcastifyscanner.data

enum class FeedKind {
    /** Classic Broadcastify listen feed. Plays in-app via the HLS popout scrape. */
    LISTEN,

    /** Broadcastify Calls talkgroup. Plays in-app as discrete call clips. */
    CALLS,
}

enum class FeedRegion {
    NEVADA,
    CALIFORNIA,
    OTHER,
}

data class Feed(
    val feedId: String,
    val name: String,
    val kind: FeedKind = FeedKind.LISTEN,
    val region: FeedRegion = FeedRegion.OTHER,
    /** Trunked system sid. Calls only. Never a listen feed id. */
    val systemSid: String = "",
    /** Decimal talkgroup. Calls only. Never sent to the listen popout scraper. */
    val talkgroup: String = "",
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
    val talkgroup: String = "",
    val systemSid: String = "",
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

/** Washoe NSRS / TMFPD talkgroups. These are Calls groups, not listen feed IDs. */
object CallsCatalog {
    const val SYSTEM_SID = "11341"

    fun groupKey(systemSid: String, talkgroup: String): String = "$systemSid-$talkgroup"

    fun cardId(systemSid: String, talkgroup: String): String = "calls-$systemSid-$talkgroup"

    fun isTalkgroup(value: String): Boolean = value.isNotEmpty() && value.all { it.isDigit() }
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
    private fun calls(talkgroup: String, name: String) = Feed(
        feedId = CallsCatalog.cardId(CallsCatalog.SYSTEM_SID, talkgroup),
        name = name,
        kind = FeedKind.CALLS,
        region = FeedRegion.NEVADA,
        systemSid = CallsCatalog.SYSTEM_SID,
        talkgroup = talkgroup,
    )

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
        calls("30433", "NSRS Washoe TMFPD Red Dispatch"),
        calls("30434", "TMFPD Command 1"),
        calls("30435", "TMFPD Command 2"),
        calls("30436", "TMFPD Tac 4"),
        calls("30437", "TMFPD Tac 5"),
        calls("30438", "TMFPD Tac 6"),
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

    fun knownCalls(talkgroup: String): Feed? {
        return ALL.firstOrNull { it.kind == FeedKind.CALLS && it.talkgroup == talkgroup }
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
        return dedupe(newcomers + existing)
    }

    fun dedupe(feeds: List<Feed>): List<Feed> {
        val seen = HashSet<String>()
        return feeds.filter { seen.add(it.feedId) }
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
