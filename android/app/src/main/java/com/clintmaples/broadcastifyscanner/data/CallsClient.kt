package com.clintmaples.broadcastifyscanner.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LiveCall(
    val key: String,
    val talkgroup: String,
    val systemId: String,
    val filename: String,
    val enc: String,
    val hash: String?,
    val ts: Long,
    val display: String,
)

data class LivePoll(
    val calls: List<LiveCall>,
    val lastPos: Double,
    val sessionKey: String?,
    val serverTime: Long?,
    val error: String?,
)

/**
 * Parses Broadcastify Calls live-poll JSON and builds the public CDN audio URL.
 * Audio path uses the call's `systemId` (capture system), not the talkgroup sid.
 */
object CallsParser {
    private val PATH_TOKEN = Regex("[A-Za-z0-9_-]+")
    private val ENC_TOKEN = Regex("[A-Za-z0-9]+")

    fun parse(body: String): LivePoll {
        val json = JSONObject(body)
        val error = json.optString("error").trim().ifBlank { null }
        val callsJson = json.optJSONArray("calls")
        val calls = buildList {
            if (callsJson != null) {
                for (i in 0 until callsJson.length()) {
                    val obj = callsJson.optJSONObject(i) ?: continue
                    parseCall(obj)?.let { add(it) }
                }
            }
        }
        return LivePoll(
            calls = calls,
            lastPos = json.optDouble("lastPos", 0.0),
            sessionKey = json.optString("sessionKey").trim().ifBlank { null },
            serverTime = if (json.has("serverTime")) json.optLong("serverTime") else null,
            error = error,
        )
    }

    fun audioUrl(call: LiveCall): String {
        require(call.systemId.isNotEmpty() && call.systemId.all { it.isDigit() }) {
            "Rejected call systemId"
        }
        require(PATH_TOKEN.matches(call.filename)) { "Rejected call filename" }
        require(ENC_TOKEN.matches(call.enc)) { "Rejected call enc" }
        val hash = call.hash
        if (!hash.isNullOrEmpty()) {
            require(PATH_TOKEN.matches(hash)) { "Rejected call hash" }
        }
        val path = if (hash.isNullOrEmpty()) {
            "${call.systemId}/${call.filename}.${call.enc}"
        } else {
            "$hash/${call.systemId}/${call.filename}.${call.enc}"
        }
        val url = "https://calls.broadcastify.com/$path"
        if (!BroadcastifyAllowlist.isAllowedUrl(url)) {
            throw IllegalStateException("Rejected non-allowlisted call audio URL")
        }
        return url
    }

    private fun parseCall(obj: JSONObject): LiveCall? {
        val filename = text(obj, "filename")
        val systemId = text(obj, "systemId")
        val talkgroup = text(obj, "call_tg")
        if (filename.isEmpty() || systemId.isEmpty() || talkgroup.isEmpty()) return null
        if (!systemId.all { it.isDigit() } || !talkgroup.all { it.isDigit() }) return null
        val enc = text(obj, "enc").ifBlank { "m4a" }
        val hash = text(obj, "hash").ifBlank { null }
        val ts = obj.optLong("ts")
        val id = text(obj, "id").ifBlank { "$systemId-$talkgroup" }
        val display = text(obj, "display").ifBlank { text(obj, "descr") }
        return LiveCall(
            key = "$id:$ts:$filename",
            talkgroup = talkgroup,
            systemId = systemId,
            filename = filename,
            enc = enc,
            hash = hash,
            ts = ts,
            display = display,
        )
    }

    private fun text(obj: JSONObject, name: String): String {
        if (!obj.has(name) || obj.isNull(name)) return ""
        return obj.get(name).toString().trim()
    }
}

/**
 * Listener-session Calls client. Login yields cookie `bcfyuser1`.
 * Talkgroup polls use `groups[]={sid}-{tg}`, `systemId={sid}`, and `sid=0`
 * (the web client's talkgroup form). Audio is fetched separately with that cookie.
 */
class CallsClient(
    private val http: OkHttpClient = defaultClient(),
) {
    fun login(username: String, password: String): String {
        val user = username.trim()
        val pass = password
        if (user.isEmpty() || pass.isEmpty()) {
            throw IOException("listener login not configured")
        }
        val body = FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .add("action", "auth")
            .add("redirect", "https://www.broadcastify.com")
            .build()
        val request = Request.Builder()
            .url("https://www.broadcastify.com/login/")
            .header("User-Agent", BroadcastifyHttp.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "https://www.broadcastify.com/")
            .post(body)
            .build()
        val noRedirect = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        noRedirect.newCall(request).execute().use { response ->
            if (!BroadcastifyAllowlist.isAllowedUrl(response.request.url)) {
                throw IOException("Rejected off-origin login response")
            }
            val cookie = sessionCookie(response)
            if (cookie.isNullOrBlank()) {
                throw IOException("Broadcastify login failed")
            }
            return cookie
        }
    }

    fun poll(
        cookie: String,
        groupKeys: List<String>,
        pos: Double,
        doInit: Boolean,
        sessionKey: String,
        systemId: String,
        sid: String,
    ): LivePoll {
        val form = FormBody.Builder()
            .add("pos", String.format(Locale.US, "%.3f", pos))
            .add("doInit", if (doInit) "1" else "0")
            .add("sessionKey", sessionKey)
            .add("systemId", systemId)
            .add("sid", sid)
        groupKeys.forEach { form.add("groups[]", it) }
        val request = Request.Builder()
            .url("https://www.broadcastify.com/calls/apis/live-calls")
            .header("User-Agent", BroadcastifyHttp.USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", CALLS_REFERER)
            .header("Origin", BroadcastifyHttp.ORIGIN)
            .header("Cookie", cookieHeader(cookie))
            .post(form.build())
            .build()
        http.newCall(request).execute().use { response ->
            if (!BroadcastifyAllowlist.isAllowedUrl(response.request.url)) {
                throw IOException("Rejected off-origin calls response")
            }
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { CallsParser.parse(raw) }.getOrNull()
            if (!response.isSuccessful) {
                if (parsed?.error != null) return parsed
                throw IOException("Calls poll HTTP ${response.code}")
            }
            return parsed ?: throw IOException("Calls poll was not JSON")
        }
    }

    companion object {
        const val CALLS_REFERER = "https://www.broadcastify.com/calls/"

        fun cookieHeader(cookie: String): String = "bcfyuser1=$cookie"

        fun playbackHeaders(cookie: String): Map<String, String> = mapOf(
            "Referer" to CALLS_REFERER,
            "Origin" to BroadcastifyHttp.ORIGIN,
            "Accept" to "*/*",
            "Cookie" to cookieHeader(cookie),
        )

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(BroadcastifyAllowlist.RedirectGuard)
                .build()
        }

        private fun sessionCookie(response: okhttp3.Response): String? {
            val headers = response.headers("Set-Cookie")
            for (header in headers) {
                val pair = header.substringBefore(';').trim()
                val name = pair.substringBefore('=')
                if (name == "bcfyuser1") {
                    val value = pair.substringAfter('=', "")
                    if (value.isNotBlank()) return value
                }
            }
            return null
        }
    }
}
