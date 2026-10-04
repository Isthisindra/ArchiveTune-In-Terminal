package moe.rukamori.archivetune.cli.playback

import moe.rukamori.archivetune.cli.music.ResolvedStream
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process VLC playback via vlcj.
 *
 * Runs headless (`:no-video`) and reports position/duration, which powers the
 * TUI progress bar and the Last.fm scrobble thresholds. Requires a local VLC
 * installation; vlcj locates libvlc on Windows via its native discovery
 * strategies (registry and Program Files).
 *
 * End-of-track is detected by polling [StatusApi.state] rather than by
 * subscribing to vlcj's event listener. The CLI already polls for a progress bar
 * on the same thread, and polling sidesteps the fact that vlcj's Java
 * `MediaPlayerEventListener` hierarchy is awkward to implement anonymously from
 * Kotlin.
 */
class VlcPlayerEngine : PlayerEngine {
    override val name: String = "vlc"

    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null

    @Volatile private var onEndCallback: (() -> Unit)? = null

    /** Incremented on every [play]/[stop] so waiters can tell a stale track. */
    private val session = AtomicInteger(0)

    @Volatile private var volume: Int = 80

    override fun isAvailable(): Boolean = discover() != null

    /**
     * Creates the VLC media player factory if it does not exist yet.
     *
     * `NativeDiscovery.discover()` returns a boolean; a factory is only built
     * once the native library has actually been located, so a missing VLC
     * install surfaces as `null` instead of an `UnsatisfiedLinkError`.
     */
    private fun discover(): MediaPlayerFactory? {
        factory?.let { return it }
        val created =
            runCatching {
                val discovery = NativeDiscovery()
                if (discovery.discover()) MediaPlayerFactory(discovery) else null
            }.getOrNull()
        factory = created
        return created
    }

    override fun play(stream: ResolvedStream, onEnd: () -> Unit) {
        val f =
            discover()
                ?: throw PlayerUnavailableException(
                    "VLC not found. Install VLC (https://www.videolan.org/vlc/) or run with --player mpv.",
                )

        stop()
        onEndCallback = onEnd

        val p = player ?: f.mediaPlayers().newMediaPlayer().also { player = it }
        p.audio().isMute = false
        p.audio().setVolume(vlcVolume(volume))

        // Pass the stream URL plus VLC options. `:http-referrer` and a custom
        // user-agent reproduce the web request the app makes so signed URLs load.
        val options =
            buildList {
                add(":no-video")
                stream.requestHeaders["Referer"]?.let { add(":http-referrer=$it") }
                stream.requestHeaders["User-Agent"]?.let { add(":http-user-agent=$it") }
            }

        if (!p.media().play(stream.url, *options.toTypedArray())) {
            throw PlayerUnavailableException("VLC refused to play the resolved stream URL")
        }
    }

    override fun pause() {
        player?.controls()?.setPause(true)
    }

    override fun resume() {
        player?.controls()?.setPause(false)
    }

    override fun stop() {
        runCatching {
            player?.controls()?.stop()
            player?.release()
        }
        player = null
        onEndCallback = null
        // Wakes up anything blocked in awaitCompletion().
        session.incrementAndGet()
    }

    override fun seekTo(positionMs: Long) {
        player?.controls()?.setTime(positionMs.coerceAtLeast(0L))
    }

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        player?.audio()?.setVolume(vlcVolume(volume))
    }

    override fun status(): PlaybackStatus {
        val p = player ?: return PlaybackStatus(false, 0L, 0L, volume)
        val pos = runCatching { p.status().time() }.getOrDefault(0L)
        val dur = runCatching { p.status().length() }.getOrDefault(0L)
        val playing =
            when (currentState()) {
                State.PLAYING, State.BUFFERING -> true
                else -> false
            }
        return PlaybackStatus(
            playing = playing,
            positionMs = if (pos < 0) 0L else pos,
            durationMs = if (dur > 0) dur else 0L,
            volumePercent = volume,
        )
    }

    /**
 * Blocks until the current track ends, errors out, or is replaced.
 *
     * The state alone is not enough to call a track done. VLC settles on
     * [State.STOPPED] while it is still opening a stream, and at the end of a
     * track it can sit in [State.PLAYING] waiting for data that never comes -
     * trusting the state either ends every track before a sample is played, or
     * hangs on the last one forever. So the wait also ends when the position
     * reaches the duration, when the position stops moving while the track is
     * not paused, or when nothing ever becomes active.
     */
    override fun awaitCompletion() {
        val mySession = session.get()
        val startedAt = System.currentTimeMillis()
        var seenActive = false
        var lastPosition = -1L
        var lastAdvanceAt = startedAt

        while (true) {
            if (session.get() != mySession || player == null) return
            val state = currentState()
            if (state == State.PLAYING || state == State.PAUSED || state == State.BUFFERING) {
                seenActive = true
            }

            val status = status()
            if (status.positionMs > lastPosition) {
                lastPosition = status.positionMs
                lastAdvanceAt = System.currentTimeMillis()
            }

            val terminal = state == State.ENDED || state == State.ERROR
            val reachedEnd = status.durationMs > 0L && status.positionMs >= status.durationMs - END_TOLERANCE_MS
            val neverStarted = !seenActive && System.currentTimeMillis() - startedAt > STARTUP_TIMEOUT_MS
            val stalled =
                seenActive &&
                    state != State.PAUSED &&
                    System.currentTimeMillis() - lastAdvanceAt > STALL_TIMEOUT_MS
            if (terminal || reachedEnd || neverStarted || stalled) break
            Thread.sleep(POLL_INTERVAL_MS)
        }

        if (session.get() != mySession) return
        val callback = onEndCallback
        onEndCallback = null
        runCatching { callback?.invoke() }
    }

    override fun close() {
        stop()
        runCatching { factory?.release() }
        factory = null
    }

    private fun currentState(): State? = runCatching { player?.status()?.state() }.getOrNull()

    /**
     * VLC treats a volume of 256 as 100%, so scale 0-100 onto 0-256 to match the
     * percentage the CLI exposes in config.
     */
    private fun vlcVolume(percent: Int): Int = percent.coerceIn(0, 100) * 256 / 100

    private companion object {
        const val POLL_INTERVAL_MS = 120L
        /** How long a stream may sit without becoming active before it is a dud. */
        const val STARTUP_TIMEOUT_MS = 20_000L

        /** Positions this close to the duration count as "played to the end". */
        const val END_TOLERANCE_MS = 1_500L

        /** A position that stops moving this long means the stream is done or dead. */
        const val STALL_TIMEOUT_MS = 30_000L
    }
}
