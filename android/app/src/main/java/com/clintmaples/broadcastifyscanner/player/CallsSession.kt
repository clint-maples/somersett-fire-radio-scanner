package com.clintmaples.broadcastifyscanner.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.clintmaples.broadcastifyscanner.data.BroadcastifyHttp
import com.clintmaples.broadcastifyscanner.data.CallsAuth
import com.clintmaples.broadcastifyscanner.data.CallsCatalog
import com.clintmaples.broadcastifyscanner.data.CallsClient
import com.clintmaples.broadcastifyscanner.data.CallsParser
import com.clintmaples.broadcastifyscanner.data.Feed
import com.clintmaples.broadcastifyscanner.data.FeedKind
import com.clintmaples.broadcastifyscanner.data.FeedRegion
import com.clintmaples.broadcastifyscanner.data.FeedStatus
import com.clintmaples.broadcastifyscanner.data.FeedUiState
import com.clintmaples.broadcastifyscanner.data.LiveCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.security.SecureRandom

/**
 * Plays one Calls talkgroup as a scanner: poll for new clips, queue them, and
 * stay quiet between transmissions. Not an HLS listen feed.
 */
class CallsSession(
    initial: Feed,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val auth: CallsAuth,
    private val client: CallsClient,
    private val masterVolume: () -> Float,
    private val onChanged: () -> Unit,
    private val onPlayingChanged: () -> Unit,
) : FeedPlayer {
    val feedId: String = initial.feedId
    val talkgroup: String = initial.talkgroup
    val systemSid: String = initial.systemSid.ifBlank { CallsCatalog.SYSTEM_SID }
    override var name: String = initial.name
        private set

    private var muted: Boolean = false
    private var volume: Float = 1f
    override var wantPlay: Boolean = false
        private set
    private var status: FeedStatus = FeedStatus.IDLE
    private var statusDetail: String = ""

    private val spectrum = SpectrumAudioProcessor()
    private var player: ExoPlayer? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var loopJob: Job? = null
    private var playGeneration = 0
    private var spectrumView: WeakReference<SpectrumView> = WeakReference(null)
    private val queue = ArrayDeque<LiveCall>()
    private val seen = LinkedHashSet<String>()
    private var playingClip = false
    private var sessionKey: String = newSessionKey()
    private var cursor = 0.0
    private var initialized = false
    @Volatile private var playbackCookie: String = ""

    init {
        spectrum.listener = SpectrumAudioProcessor.Listener { levels ->
            spectrumView.get()?.setLevels(levels)
        }
    }

    override fun uiState(region: FeedRegion): FeedUiState = FeedUiState(
        feedId = feedId,
        name = name,
        status = status,
        statusDetail = statusDetail,
        muted = muted,
        volume = volume,
        wantPlay = wantPlay,
        kind = FeedKind.CALLS,
        region = region,
        talkgroup = talkgroup,
        systemSid = systemSid,
    )

    override fun attachSpectrum(view: SpectrumView?) {
        spectrumView = WeakReference(view)
        if (view == null) return
        if (!wantPlay || status == FeedStatus.IDLE) view.clear()
    }

    override fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyGain()
        onChanged()
    }

    override fun toggleMute() {
        muted = !muted
        applyGain()
        if (wantPlay && status == FeedStatus.PLAYING && muted) {
            setStatus(FeedStatus.MUTED, statusDetail)
        } else if (wantPlay && status == FeedStatus.MUTED && !muted) {
            setStatus(FeedStatus.PLAYING, statusDetail)
        }
        onChanged()
    }

    override fun applyGain() {
        val gain = if (muted) 0f else volume * masterVolume()
        spectrum.outputGain = gain.coerceIn(0f, 1f)
    }

    override fun play() {
        wantPlay = true
        val gen = ++playGeneration
        setStatus(FeedStatus.LOADING)
        onChanged()
        onPlayingChanged()
        loopJob?.cancel()
        loopJob = scope.launch {
            runLoop(gen)
        }
    }

    override fun stop() {
        wantPlay = false
        playGeneration++
        loopJob?.cancel()
        loopJob = null
        queue.clear()
        playingClip = false
        releasePlayer()
        spectrumView.get()?.clear()
        setStatus(FeedStatus.IDLE)
        onPlayingChanged()
        onChanged()
    }

    override fun reconnect() {
        initialized = false
        cursor = 0.0
        sessionKey = newSessionKey()
        queue.clear()
        if (!wantPlay) {
            play()
            return
        }
        val gen = ++playGeneration
        setStatus(FeedStatus.RECONNECTING)
        onChanged()
        loopJob?.cancel()
        loopJob = scope.launch {
            runLoop(gen)
        }
    }

    override fun dispose() {
        stop()
        spectrum.listener = null
    }

    private suspend fun runLoop(gen: Int) {
        while (scope.isActive && wantPlay && gen == playGeneration) {
            try {
                val poll = withContext(Dispatchers.IO) { pollOnce() }
                if (gen != playGeneration || !wantPlay) return
                ingest(poll.calls, poll.serverTime, fresh = !initialized)
                if (poll.lastPos > 0.0) cursor = poll.lastPos + 1.0
                initialized = true
                if (status == FeedStatus.LOADING || status == FeedStatus.RECONNECTING || status == FeedStatus.ERROR) {
                    setStatus(if (muted) FeedStatus.MUTED else FeedStatus.PLAYING)
                    onChanged()
                }
                drainQueue()
                delay(POLL_MS)
            } catch (e: Exception) {
                if (gen != playGeneration || !wantPlay) return
                setStatus(FeedStatus.ERROR, (e.message ?: "failed").take(48))
                onChanged()
                delay(RETRY_MS)
            }
        }
    }

    private fun pollOnce(): com.clintmaples.broadcastifyscanner.data.LivePoll {
        val group = CallsCatalog.groupKey(systemSid, talkgroup)
        var cookie = auth.cookie()
        var result = client.poll(
            cookie = cookie,
            groupKeys = listOf(group),
            pos = cursor,
            doInit = !initialized,
            sessionKey = sessionKey,
            systemId = systemSid,
            sid = "0",
        )
        if (isAuthError(result.error)) {
            auth.invalidate()
            cookie = auth.cookie()
            result = client.poll(
                cookie = cookie,
                groupKeys = listOf(group),
                pos = cursor,
                doInit = !initialized,
                sessionKey = sessionKey,
                systemId = systemSid,
                sid = "0",
            )
        }
        if (isAuthError(result.error)) {
            throw java.io.IOException("Calls session rejected")
        }
        if (!result.error.isNullOrBlank()) {
            throw java.io.IOException(result.error)
        }
        result.sessionKey?.let { sessionKey = it }
        playbackCookie = cookie
        return result
    }

    private fun ingest(calls: List<LiveCall>, serverTime: Long?, fresh: Boolean) {
        val now = serverTime ?: (System.currentTimeMillis() / 1000L)
        val ordered = calls
            .asSequence()
            .filter { it.talkgroup == talkgroup }
            .filter { !fresh || it.ts >= now - RECENT_WINDOW_SEC }
            .sortedBy { it.ts }
        for (call in ordered) {
            if (!seen.add(call.key)) continue
            queue.addLast(call)
        }
        while (seen.size > SEEN_CAP) {
            val oldest = seen.firstOrNull() ?: break
            seen.remove(oldest)
        }
        while (queue.size > QUEUE_CAP) {
            queue.removeFirst()
        }
    }

    private fun drainQueue() {
        while (wantPlay && !playingClip && queue.isNotEmpty()) {
            val call = queue.removeFirst()
            val url = try {
                CallsParser.audioUrl(call)
            } catch (_: Exception) {
                continue
            }
            val cookie = playbackCookie
            if (cookie.isBlank()) return
            playingClip = true
            val detail = call.display.ifBlank { "TG $talkgroup" }.take(42)
            setStatus(if (muted) FeedStatus.MUTED else FeedStatus.PLAYING, detail)
            onChanged()
            try {
                val exo = ensurePlayer(cookie)
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMimeType(mimeFor(call.enc))
                    .build()
                exo.setMediaItem(item)
                exo.prepare()
                exo.playWhenReady = true
                applyGain()
            } catch (_: Exception) {
                playingClip = false
            }
        }
    }

    private fun ensurePlayer(cookie: String): ExoPlayer {
        val factory = httpFactory ?: OkHttpDataSource.Factory(BroadcastifyHttp.client)
            .setUserAgent(BroadcastifyHttp.USER_AGENT)
            .also { httpFactory = it }
        factory.setDefaultRequestProperties(CallsClient.playbackHeaders(cookie))
        player?.let { return it }
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(spectrum))
                    .build()
            }
        }
        val exo = ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setHandleAudioBecomingNoisy(false)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus= */ false,
        )
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!wantPlay) return
                if (playbackState == Player.STATE_ENDED) {
                    playingClip = false
                    if (queue.isEmpty()) {
                        setStatus(if (muted) FeedStatus.MUTED else FeedStatus.PLAYING)
                        spectrumView.get()?.clear()
                        onChanged()
                    }
                    drainQueue()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!wantPlay) return
                playingClip = false
                drainQueue()
            }
        })
        player = exo
        return exo
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        httpFactory = null
    }

    private fun setStatus(next: FeedStatus, detail: String = "") {
        status = next
        statusDetail = detail
    }

    private fun mimeFor(enc: String): String {
        return if (enc.equals("mp3", ignoreCase = true)) {
            MimeTypes.AUDIO_MPEG
        } else {
            MimeTypes.AUDIO_MP4
        }
    }

    private fun isAuthError(error: String?): Boolean {
        val value = error?.lowercase().orEmpty()
        return value == "auth_required" || value == "bad_session" || value == "unauthorized"
    }

    companion object {
        private const val POLL_MS = 3_000L
        private const val RETRY_MS = 4_000L
        private const val RECENT_WINDOW_SEC = 20L
        private const val QUEUE_CAP = 8
        private const val SEEN_CAP = 240

        private fun newSessionKey(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
