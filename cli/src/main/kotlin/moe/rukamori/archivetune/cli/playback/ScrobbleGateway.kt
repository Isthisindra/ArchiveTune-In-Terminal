package moe.rukamori.archivetune.cli.playback

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cli.config.ScrobbleConfig
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.lastfm.LastFM
import kotlin.math.min

/**
 * Turns a played track into Last.fm events without ever blocking the queue.
 *
 * [updateNowPlaying] and [scrobble] run on a daemon thread each, so the player
 * keeps ticking even when the network is slow. The rule set mirrors the app's
 * `ScrobbleManager`: very short tracks never scrobble, and a track must be
 * listened to for `scrobbleThreshold` (capped at three minutes) before it is
 * submitted. Skipping a track early simply means its timer never fires.
 */
class ScrobbleGateway(
    private val config: ScrobbleConfig,
    private val warn: (String) -> Unit,
) {
    /** Whether this gateway will do anything at all. */
    fun isActive(): Boolean = config.enabled && !config.sessionKey.isNullOrBlank()

    /**
     * How many milliseconds the track must be listened to before it counts as
     * scrobbled. Returns -1 when it never should be (disabled, too short).
     */
    fun listenGoalMs(track: Track): Long {
        if (!isActive()) return -1L
        val seconds = track.duration ?: return -1L
        if (seconds <= MIN_SONG_SECONDS) return -1L
        val fraction = config.scrobbleThreshold.coerceIn(0.0, 1.0)
        return min((seconds * 1000L * fraction).toLong(), MAX_SCROBBLE_DELAY_MS)
    }

    /** Reports the track as currently playing on Last.fm. */
    fun updateNowPlaying(track: Track) {
        if (!config.nowPlaying || !isReady()) return
        fire {
            LastFM.updateNowPlaying(
                artist = track.artistString,
                track = track.title,
                album = track.album?.title,
                duration = track.duration,
            )
        }
    }

    /** Submits the track to Last.fm, stamped with when play actually started. */
    fun scrobble(track: Track, startedAtEpochSeconds: Long) {
        if (!isReady()) return
        fire {
            LastFM.scrobble(
                artist = track.artistString,
                track = track.title,
                timestamp = startedAtEpochSeconds,
                album = track.album?.title,
                trackNumber = track.trackNumber,
                duration = track.duration,
            )
        }
    }

    private fun isReady(): Boolean = isActive() && LastFM.isInitialized()

    private fun fire(call: suspend () -> Result<String>) {
        Thread(
            {
                runBlocking {
                    call().onFailure { error ->
                        warn("Last.fm: ${error.message ?: "request failed"}")
                    }
                }
            },
            "archivetune-scrobble",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        /** Tracks shorter than this never scrobble, matching the Last.fm rules. */
        const val MIN_SONG_SECONDS = 30
        /** No matter how long the song, wait at most this long to submit. */
        const val MAX_SCROBBLE_DELAY_MS = 180_000L
    }
}