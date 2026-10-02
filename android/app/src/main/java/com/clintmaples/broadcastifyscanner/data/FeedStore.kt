package com.clintmaples.broadcastifyscanner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class FeedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadFeeds(): List<Feed> {
        val current = prefs.getString(KEY_FEEDS, null)
        if (current != null) {
            return decode(current).ifEmpty { DefaultFeeds.ALL }
        }
        val legacy = prefs.getString(KEY_FEEDS_V1, null) ?: return DefaultFeeds.ALL
        val old = decodeLegacy(legacy)
        if (old.isEmpty()) return DefaultFeeds.ALL
        return DefaultFeeds.upgradeLegacy(old)
    }

    fun saveFeeds(feeds: List<Feed>) {
        prefs.edit().putString(KEY_FEEDS, encode(feeds)).apply()
    }

    fun loadMasterVolume(): Float = prefs.getFloat(KEY_MASTER, 0.8f).coerceIn(0f, 1f)

    fun saveMasterVolume(volume: Float) {
        prefs.edit().putFloat(KEY_MASTER, volume.coerceIn(0f, 1f)).apply()
    }

    fun loadKeepAwake(): Boolean = prefs.getBoolean(KEY_AWAKE, false)

    fun saveKeepAwake(value: Boolean) {
        prefs.edit().putBoolean(KEY_AWAKE, value).apply()
    }

    companion object {
        private const val PREFS = "broadcastify-scanner"
        private const val KEY_FEEDS_V1 = "feeds-v1"
        private const val KEY_FEEDS = "feeds-v2"
        private const val KEY_MASTER = "master-volume"
        private const val KEY_AWAKE = "keep-awake"

        fun encode(feeds: List<Feed>): String {
            val arr = JSONArray()
            feeds.forEach { feed ->
                val obj = JSONObject()
                    .put("feedId", feed.feedId)
                    .put("name", feed.name)
                    .put("kind", if (feed.kind == FeedKind.CALLS) "calls" else "listen")
                    .put("region", feed.region.name.lowercase())
                if (feed.kind == FeedKind.CALLS) {
                    val links = JSONArray()
                    feed.callsLinks.forEach { link ->
                        links.put(
                            JSONObject()
                                .put("talkgroup", link.talkgroup)
                                .put("label", link.label)
                                .put("url", link.url),
                        )
                    }
                    obj.put("callsLinks", links)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        fun decode(raw: String): List<Feed> {
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        decodeEntry(arr.getJSONObject(i))?.let { add(it) }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun decodeLegacy(raw: String): List<Feed> {
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("feedId").trim()
                        if (id.isEmpty() || !id.all { it.isDigit() }) continue
                        val name = obj.optString("name").ifBlank { "Feed $id" }
                        val known = DefaultFeeds.knownListen(id)
                        add(
                            Feed(
                                feedId = id,
                                name = name,
                                kind = FeedKind.LISTEN,
                                region = known?.region ?: FeedRegion.OTHER,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun decodeEntry(obj: JSONObject): Feed? {
            val kindRaw = obj.optString("kind")
            val name = obj.optString("name")
            val region = when (obj.optString("region")) {
                "nevada" -> FeedRegion.NEVADA
                "california" -> FeedRegion.CALIFORNIA
                else -> FeedRegion.OTHER
            }
            if (kindRaw == "calls") {
                val feedId = obj.optString("feedId").trim()
                // A Calls card id must not be a bare listen feed id / talkgroup number.
                if (feedId.isEmpty() || feedId.all { it.isDigit() }) return null
                val links = decodeLinks(obj.optJSONArray("callsLinks"))
                if (links.isEmpty()) return null
                return Feed(
                    feedId = feedId,
                    name = name.ifBlank { "Calls" },
                    kind = FeedKind.CALLS,
                    region = if (region == FeedRegion.OTHER) FeedRegion.NEVADA else region,
                    callsLinks = links,
                )
            }
            val id = obj.optString("feedId").trim()
            if (id.isEmpty() || !id.all { it.isDigit() }) return null
            val known = DefaultFeeds.knownListen(id)
            val resolvedRegion = when {
                region != FeedRegion.OTHER -> region
                known != null -> known.region
                else -> FeedRegion.OTHER
            }
            return Feed(
                feedId = id,
                name = name.ifBlank { known?.name ?: "Feed $id" },
                kind = FeedKind.LISTEN,
                region = resolvedRegion,
            )
        }

        private fun decodeLinks(arr: JSONArray?): List<CallsLink> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url")
                    if (!CallsPages.isPublicCallsTalkgroupUrl(url)) continue
                    val tg = url.trimEnd('/').substringAfterLast('/')
                    val label = obj.optString("label").ifBlank { "TG $tg" }
                    add(CallsLink(talkgroup = tg, label = label, url = url))
                }
            }
        }
    }
}
