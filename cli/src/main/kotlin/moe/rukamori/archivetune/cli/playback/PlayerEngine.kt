package moe.rukamori.archivetune.cli.playback

import moe.rukamori.archivetune.cli.music.ResolvedStream

/** Snapshot of playback position, used by the TUI and by scrobble timing. */
data class PlaybackStatus(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volumePercent: Int,
) {
    val progress: Double
        get() = if (durationMs <= 0) 0.0 else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
}

/**
 * Abstraction over the actual audio output. VLCJ is the default because it runs
 * in-process and reports position/duration, which the TUI progress bar and the
 * Last.fm scrobble thresholds both need. mpv is offered as an alternative for
 * real gapless/crossfade support.
 */
interface PlayerEngine {
    val name: String

    fun isAvailable(): Boolean

    fun play(stream: ResolvedStream, onEnd: () -> Unit = {})

    fun pause()

    fun resume()

    fun stop()

    fun seekTo(positionMs: Long)

    fun setVolume(percent: Int)

    fun status(): PlaybackStatus

    /** Blocks until the current track finishes or [stop] is called. */
    fun awaitCompletion()

    fun close()
}

class PlayerUnavailableException(message: String) : Exception(message)
