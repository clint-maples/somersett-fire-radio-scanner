package com.clintmaples.broadcastifyscanner

import android.app.Application
import com.clintmaples.broadcastifyscanner.data.BroadcastifyClient
import com.clintmaples.broadcastifyscanner.data.CallsAuth
import com.clintmaples.broadcastifyscanner.data.CallsCatalog
import com.clintmaples.broadcastifyscanner.data.CallsClient
import com.clintmaples.broadcastifyscanner.data.DefaultFeeds
import com.clintmaples.broadcastifyscanner.data.Feed
import com.clintmaples.broadcastifyscanner.data.FeedKind
import com.clintmaples.broadcastifyscanner.data.FeedRegion
import com.clintmaples.broadcastifyscanner.data.FeedStore
import com.clintmaples.broadcastifyscanner.data.ScannerUiState
import com.clintmaples.broadcastifyscanner.player.CallsSession
import com.clintmaples.broadcastifyscanner.player.FeedPlayer
import com.clintmaples.broadcastifyscanner.player.FeedSession
import com.clintmaples.broadcastifyscanner.player.PlaybackService
import com.clintmaples.broadcastifyscanner.player.SpectrumView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerController(private val app: Application) {
    private val store = FeedStore(app)
    private val client = BroadcastifyClient()
    private val callsClient = CallsClient()
    private val callsAuth = CallsAuth(
        username = BuildConfig.BROADCASTIFY_USERNAME,
        password = BuildConfig.BROADCASTIFY_PASSWORD,
        client = callsClient,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val order = mutableListOf<Feed>()
    private val sessions = linkedMapOf<String, FeedPlayer>()

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    init {
        store.loadFeeds().forEach { addEntry(it, persist = false) }
        _state.update {
            it.copy(
                masterVolume = store.loadMasterVolume(),
                keepAwake = store.loadKeepAwake(),
            )
        }
        publish()
    }

    fun playAll() {
        sessions.values.forEach { session ->
            if (!session.wantPlay) session.play()
            else session.reconnect()
        }
    }

    fun stopAll() {
        sessions.values.forEach { it.stop() }
    }

    fun play(feedId: String) {
        sessions[feedId]?.play()
    }

    fun stop(feedId: String) {
        sessions[feedId]?.stop()
    }

    fun reconnect(feedId: String) {
        sessions[feedId]?.reconnect()
    }

    fun toggleMute(feedId: String) {
        sessions[feedId]?.toggleMute()
    }

    fun setFeedVolume(feedId: String, volume: Float) {
        sessions[feedId]?.setVolume(volume)
    }

    fun setMasterVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        store.saveMasterVolume(v)
        _state.update { it.copy(masterVolume = v) }
        sessions.values.forEach { it.applyGain() }
        publish()
    }

    fun setKeepAwake(value: Boolean) {
        store.saveKeepAwake(value)
        _state.update { it.copy(keepAwake = value) }
    }

    fun setAddPanelOpen(open: Boolean) {
        _state.update { it.copy(addPanelOpen = open) }
    }

    fun addFeed(rawId: String, rawName: String?) {
        val id = rawId.trim()
        if (id.isEmpty() || !id.all { it.isDigit() } || order.any { it.feedId == id }) return
        val known = DefaultFeeds.knownListen(id)
        val typed = rawName?.trim().orEmpty()
        val name = typed.ifBlank { known?.name ?: "Feed $id" }
        addEntry(
            Feed(
                feedId = id,
                name = name,
                kind = FeedKind.LISTEN,
                region = known?.region ?: FeedRegion.OTHER,
            ),
            persist = true,
        )
        publish()
        if (typed.isEmpty()) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { client.fetchFeedMeta(id) } }
                    .onSuccess { meta -> (sessions[id] as? FeedSession)?.rename(meta.name) }
            }
        }
    }

    fun removeFeed(feedId: String) {
        order.removeAll { it.feedId == feedId }
        sessions.remove(feedId)?.dispose()
        syncService()
        publish()
    }

    fun attachSpectrum(feedId: String, view: SpectrumView?) {
        sessions[feedId]?.attachSpectrum(view)
    }

    fun playingCount(): Int = sessions.values.count { it.wantPlay }

    private fun addEntry(feed: Feed, persist: Boolean) {
        if (order.any { it.feedId == feed.feedId }) return
        if (feed.kind == FeedKind.CALLS) {
            val tg = feed.talkgroup.trim()
            val sid = feed.systemSid.trim().ifBlank { CallsCatalog.SYSTEM_SID }
            if (!CallsCatalog.isTalkgroup(tg) || !CallsCatalog.isTalkgroup(sid)) return
            if (feed.feedId.all { it.isDigit() } || sessions.containsKey(feed.feedId)) return
            val calls = feed.copy(systemSid = sid, talkgroup = tg)
            order += calls
            sessions[calls.feedId] = CallsSession(
                initial = calls,
                appContext = app,
                scope = scope,
                auth = callsAuth,
                client = callsClient,
                masterVolume = { _state.value.masterVolume },
                onChanged = { publish() },
                onPlayingChanged = { syncService() },
            )
        } else {
            val id = feed.feedId.trim()
            if (id.isEmpty() || !id.all { it.isDigit() } || sessions.containsKey(id)) return
            val listen = feed.copy(feedId = id, kind = FeedKind.LISTEN, systemSid = "", talkgroup = "")
            order += listen
            sessions[id] = FeedSession(
                initial = listen,
                appContext = app,
                scope = scope,
                client = client,
                masterVolume = { _state.value.masterVolume },
                onChanged = { publish() },
                onPlayingChanged = { syncService() },
            )
        }
        if (persist) persistFeeds()
    }

    private fun persistFeeds() {
        val snapshot = order.map { feed ->
            val session = sessions[feed.feedId]
            if (session != null) feed.copy(name = session.name) else feed
        }
        order.clear()
        order.addAll(snapshot)
        store.saveFeeds(snapshot)
    }

    private fun publish() {
        persistFeeds()
        _state.update { current ->
            current.copy(
                feeds = order.map { feed ->
                    val session = sessions[feed.feedId]
                    session?.uiState(feed.region) ?: feed.toIdle()
                },
            )
        }
    }

    private fun Feed.toIdle() = com.clintmaples.broadcastifyscanner.data.FeedUiState(
        feedId = feedId,
        name = name,
        status = com.clintmaples.broadcastifyscanner.data.FeedStatus.IDLE,
        kind = kind,
        region = region,
        talkgroup = talkgroup,
        systemSid = systemSid,
    )

    private fun syncService() {
        val n = playingCount()
        if (n > 0) {
            PlaybackService.start(app, n)
        } else {
            PlaybackService.stop(app)
        }
    }
}
