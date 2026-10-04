package moe.rukamori.archivetune.cli.playback

import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.describe
import moe.rukamori.archivetune.cli.music.MusicService
import moe.rukamori.archivetune.cli.music.ResolvedStream
import moe.rukamori.archivetune.cli.music.StreamUnavailableException
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.output.ProgressRenderer
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

data class PlayOptions(
    val startIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val crossfadeSeconds: Int = 0,
    val showProgress: Boolean = true,
    val interactive: Boolean = false,
)

/** Live snapshot of what the player is doing, shared with the TUI. */
data class NowPlaying(
    val track: Track? = null,
    val index: Int = 0,
    val queueSize: Int = 0,
    val status: PlaybackStatus = PlaybackStatus(false, 0L, 0L, 80),
    val resolving: Boolean = false,
    val finished: Boolean = false,
)

/** What a user gesture asks the queue loop to do. */
enum class PlaybackCommand {
    NEXT,
    PREVIOUS,
    RESTART,
    STOP,
}

/**
 * Drives a queue through a [PlayerEngine].
 *
 * [play] owns the queue; callers steer it from the outside with [next],
 * [previous], [restart], [stop], [togglePause], [seekBy] and [volumeBy]. The
 * console loop in `ConsoleControls` and the full-screen TUI both work that way,
 * so neither has to fight the controller for the terminal. Everything is
 * observable through [nowPlaying], which is what the TUI redraws from.
 *
 * Inside [play] three threads cooperate: the coroutine runs the queue and
 * repaints the progress bar, a short-lived watcher thread blocks on the engine
 * until the track ends, and the engine itself reports position. Skipping works
 * by calling [PlayerEngine.stop], which releases the watcher.
 */
class PlaybackController(
    private val music: MusicService,
    private val engine: PlayerEngine,
    private val printer: Printer,
    private val progress: ProgressRenderer,
    private val clients: List<YouTubeClient>,
    private val quality: AudioQuality,
    private val startVolume: Int,
    private val verbose: Boolean = false,
    private val scrobble: ScrobbleGateway? = null,
) {
    private val command = AtomicReference<PlaybackCommand?>(null)

    @Volatile
    private var state = NowPlaying()

    @Volatile
    private var stopped = false

    /** Observable playback state, for the TUI and for `status` style output. */
    val nowPlaying: NowPlaying get() = state

    /** True once [play] has finished and the engine is idle again. */
    val isFinished: Boolean get() = state.finished

    // --- external control ---------------------------------------------------

    fun next() = steer(PlaybackCommand.NEXT)

    fun previous() = steer(PlaybackCommand.PREVIOUS)

    fun restart() = steer(PlaybackCommand.RESTART)

    /** Ends playback and unwinds [play]. */
    fun stop() = steer(PlaybackCommand.STOP)

    fun togglePause() {
        if (engine.status().playing) engine.pause() else engine.resume()
    }

    fun seekBy(deltaMs: Long) {
        val status = engine.status()
        engine.seekTo((status.positionMs + deltaMs).coerceIn(0L, status.durationMs))
    }

    fun volumeBy(delta: Int) {
        engine.setVolume(engine.status().volumePercent + delta)
    }

    private fun steer(next: PlaybackCommand) {
        // Stopping the engine releases the watcher, which is what wakes the
        // queue loop so it can act on the command.
        engine.stop()
        command.set(next)
    }

    // --- queue loop ---------------------------------------------------------

    suspend fun play(queue: List<Track>, options: PlayOptions) {
        if (queue.isEmpty()) {
            printer.warn("Nothing to play.")
            return
        }

        val order = buildOrder(queue, options)
        engine.setVolume(startVolume)
        stopped = false
        command.set(null)

        state = NowPlaying(index = options.startIndex, queueSize = order.size)
        if (options.showProgress && !progress.enabled) {
            printer.echo(printer.dim("Playing ${order.size} track(s) with ${engine.name}. Press Ctrl+C to stop."))
        }

        var index = options.startIndex.coerceIn(0, order.lastIndex)
        var failed = 0
        try {
            while (index in order.indices && !stopped) {
                val track = order[index]
                state = state.copy(track = track, index = index, resolving = true, finished = false)

                val outcome = playOne(track, options)
                val next = command.getAndSet(null)

                when (next) {
                    PlaybackCommand.STOP -> return
                    PlaybackCommand.PREVIOUS -> {
                        index = (index - 1).mod(order.size)
                        continue
                    }
                    PlaybackCommand.RESTART -> {
                        playOne(track, options)
                        continue
                    }
                    // NEXT falls through to the advance logic below.
                    else -> Unit
                }

                if (!outcome) {
                    failed++
                    if (failed >= MAX_CONSECUTIVE_FAILURES) {
                        printer.error("Giving up after $failed failed tracks.")
                        return
                    }
                } else {
                    failed = 0
                }

                if (command.getAndSet(null) == PlaybackCommand.STOP) return
                if (index + 1 >= order.size) {
                    if (options.repeat) index = 0 else break
                } else {
                    index++
                }
            }
        } finally {
            stopped = true
            progress.stop()
            engine.stop()
            state = state.copy(track = null, resolving = false, finished = true)
        }
    }

    /**
     * Resolves one track, trying every client in [clients] before giving up.
     *
     * A refusal is usually about the client rather than the track - an account
     * that no longer signs in to the mobile clients, or an upload that is gated
     * for one of them - so the next client is tried before the track is skipped.
     * The last failure is the one reported, since that is the most specific.
     */
    private suspend fun resolveStream(track: Track): Result<ResolvedStream> {
        var last = Result.failure<ResolvedStream>(StreamUnavailableException("No stream client available"))
        for (candidate in clients) {
            val attempt = music.resolve(track, quality, candidate)
            if (attempt.isSuccess) return attempt
            if (verbose) {
                val why = attempt.exceptionOrNull()?.message?.take(70) ?: "failed"
                printer.echo(printer.dim("${candidate.clientName}: $why"))
            }
            last = attempt
        }
        return last
    }

    /**
     * Resolves and plays a single track. Returns false when the track could not
     * be played, so the caller can skip it without tearing the queue down.
     */
    private suspend fun playOne(track: Track, options: PlayOptions): Boolean {
        if (verbose) printer.echo(printer.dim("resolving ${track.id} ..."))
        state = state.copy(resolving = true)
        val stream =
            resolveStream(track).getOrElse { error ->
                printer.warn("${track.title}: ${error.message ?: "unavailable"}")
                state = state.copy(resolving = false)
                return false
            }
        state = state.copy(resolving = false)

        if (verbose) {
            printer.echo(printer.dim("itag ${stream.itag} ${stream.mimeType} ${stream.bitrate / 1000}kbps"))
        }

        if (!progress.enabled) {
            printer.echo("${printer.accent("now playing")}  ${track.describe()}")
        }

        progress.start()

        val finished = CountDownLatch(1)
        // The watcher is started only once the engine holds the track. Starting it
        // first races the pre-play state: the engine reports nothing playing yet,
        // the watcher calls the track done, and the queue moves on mid-stream.
        val started =
            runCatching {
                engine.play(stream)
                Thread({
                    runCatching { engine.awaitCompletion() }
                    finished.countDown()
                }, "archivetune-playback-watcher").apply {
                    isDaemon = true
                    start()
                }
                true
            }.getOrElse { error ->
                printer.error(error.message ?: "Playback failed")
                false
            }

        if (!started) {
            finished.countDown()
            progress.stop()
            return false
        }

        val startedAtEpochSeconds = System.currentTimeMillis() / 1000
        val listenGoalMs = scrobble?.listenGoalMs(track) ?: -1L
        var reported = false
        var scrobbled = false
        var listenedMs = 0L
        var lastTick = System.currentTimeMillis()

        try {
            while (finished.count > 0L) {
                val status = engine.status()
                val now = System.currentTimeMillis()
                if (status.playing) {
                    listenedMs += now - lastTick
                    if (!reported) {
                        scrobble?.updateNowPlaying(track)
                        reported = true
                    }
                    // The scrobble fires once, the moment the listen goal is
                    // crossed. Skipping before that cancels the track silently,
                    // which is how Last.fm expects it to work.
                    if (!scrobbled && listenGoalMs >= 0L && listenedMs >= listenGoalMs) {
                        scrobble?.scrobble(track, startedAtEpochSeconds)
                        scrobbled = true
                    }
                }
                lastTick = now
                state = state.copy(status = status)
                if (progress.enabled) {
                    progress.update(
                        status = status,
                        title = track.title,
                        artist = track.artistString,
                        extra = if (status.playing) "" else "paused",
                    )
                }
                Thread.sleep(TICK_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            progress.stop()
            if (verbose) printer.echo(printer.dim("finished ${track.id}"))
        }
        return true
    }

    private fun buildOrder(queue: List<Track>, options: PlayOptions): List<Track> {
        if (!options.shuffle) return queue
        val start = options.startIndex.coerceIn(0, queue.lastIndex)
        val first = queue[start]
        val rest = queue.filterIndexed { index, _ -> index != start }.shuffled()
        return listOf(first) + rest
    }

    private companion object {
        const val TICK_MS = 200L
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}

/** `mod` that always returns a non-negative index. */
internal fun Int.mod(divisor: Int): Int = ((this % divisor) + divisor) % divisor
