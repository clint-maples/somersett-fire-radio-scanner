package com.clintmaples.broadcastifyscanner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class FeedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadFeeds(): List<Feed> {
        val current = prefs.getString(KEY_FEEDS, null)
        if (current != null) {
            val decoded = DefaultFeeds.dedupe(decode(current))
            return decoded.ifEmpty { DefaultFeeds.ALL }
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
                    obj.put("systemSid", feed.systemSid)
                        .put("talkgroup", feed.talkgroup)
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
                        addAll(decodeEntry(arr.getJSONObject(i)))
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

        private fun decodeEntry(obj: JSONObject): List<Feed> {
            val kindRaw = obj.optString("kind")
            val name = obj.optString("name")
            val region = when (obj.optString("region")) {
                "nevada" -> FeedRegion.NEVADA
                "california" -> FeedRegion.CALIFORNIA
                else -> FeedRegion.OTHER
            }
            if (kindRaw == "calls") {
                return decodeCalls(obj, name, region)
            }
            val id = obj.optString("feedId").trim()
            if (id.isEmpty() || !id.all { it.isDigit() }) return emptyList()
            val known = DefaultFeeds.knownListen(id)
            val resolvedRegion = when {
                region != FeedRegion.OTHER -> region
                known != null -> known.region
                else -> FeedRegion.OTHER
            }
            return listOf(
                Feed(
                    feedId = id,
                    name = name.ifBlank { known?.name ?: "Feed $id" },
                    kind = FeedKind.LISTEN,
                    region = resolvedRegion,
                ),
            )
        }

        private fun decodeCalls(obj: JSONObject, name: String, region: FeedRegion): List<Feed> {
            val explicit = obj.optString("talkgroup").trim()
            if (CallsCatalog.isTalkgroup(explicit)) {
                val sid = obj.optString("systemSid").trim().ifBlank { CallsCatalog.SYSTEM_SID }
                if (!CallsCatalog.isTalkgroup(sid)) return emptyList()
                val feedId = obj.optString("feedId").trim().ifBlank {
                    CallsCatalog.cardId(sid, explicit)
                }
                if (feedId.isEmpty() || feedId.all { it.isDigit() }) return emptyList()
                val known = DefaultFeeds.knownCalls(explicit)
                return listOf(
                    Feed(
                        feedId = feedId,
                        name = name.ifBlank { known?.name ?: "TG $explicit" },
                        kind = FeedKind.CALLS,
                        region = if (region == FeedRegion.OTHER) FeedRegion.NEVADA else region,
                        systemSid = sid,
                        talkgroup = explicit,
                    ),
                )
            }
            // 0.4.0 stored one card with several deep links. Expand to one card per TG.
            val links = obj.optJSONArray("callsLinks") ?: return emptyList()
            return buildList {
                for (i in 0 until links.length()) {
                    val link = links.optJSONObject(i) ?: continue
                    val tg = link.optString("talkgroup").trim().ifBlank {
                        talkgroupFromLegacyUrl(link.optString("url"))
                    }
                    if (!CallsCatalog.isTalkgroup(tg)) continue
                    val known = DefaultFeeds.knownCalls(tg)
                    add(
                        Feed(
                            feedId = CallsCatalog.cardId(CallsCatalog.SYSTEM_SID, tg),
                            name = known?.name ?: link.optString("label").ifBlank { "TG $tg" },
                            kind = FeedKind.CALLS,
                            region = FeedRegion.NEVADA,
                            systemSid = CallsCatalog.SYSTEM_SID,
                            talkgroup = tg,
                        ),
                    )
                }
            }
        }

        /** Reads a stored 0.4.0 page URL. Does not open it. */
        private fun talkgroupFromLegacyUrl(url: String): String {
            val marker = "/calls/tg/${CallsCatalog.SYSTEM_SID}/"
            val idx = url.indexOf(marker)
            if (idx < 0) return ""
            val tg = url.substring(idx + marker.length).substringBefore('?').trimEnd('/')
            return if (CallsCatalog.isTalkgroup(tg)) tg else ""
        }
    }
}
