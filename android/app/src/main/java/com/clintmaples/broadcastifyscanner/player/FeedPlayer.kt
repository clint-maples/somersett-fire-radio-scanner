package com.clintmaples.broadcastifyscanner.player

import com.clintmaples.broadcastifyscanner.data.FeedRegion
import com.clintmaples.broadcastifyscanner.data.FeedUiState

/** Listen HLS session or Calls clip session. Both use the same card controls. */
interface FeedPlayer {
    val name: String
    val wantPlay: Boolean
    fun play()
    fun stop()
    fun reconnect()
    fun toggleMute()
    fun setVolume(value: Float)
    fun applyGain()
    fun attachSpectrum(view: SpectrumView?)
    fun uiState(region: FeedRegion): FeedUiState
    fun dispose()
}
