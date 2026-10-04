package moe.rukamori.archivetune.cli.tui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.lyrics.LyricsHit
import moe.rukamori.archivetune.cli.lyrics.LyricsProvider
import moe.rukamori.archivetune.cli.lyrics.LyricsService
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.music.CatalogService
import moe.rukamori.archivetune.cli.music.SearchScope
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.playback.PlayOptions
import moe.rukamori.archivetune.cli.playback.PlaybackController
import moe.rukamori.archivetune.cli.terminal.ConsoleTerminal
import moe.rukamori.archivetune.cli.terminal.KeySource
import moe.rukamori.archivetune.cli.terminal.TerminalKey
import moe.rukamori.archivetune.cli.terminal.isEndOfFile
import moe.rukamori.archivetune.cli.terminal.isUserInterrupt
import org.jline.utils.InfoCmp.Capability

/**
 * The full-screen player.
 *
 * One thread drives everything: it waits up to [POLL_MS] for a key, then
 * repaints from the latest [PlaybackController] state. Playback runs on its own
 * coroutine, so a slow stream resolution never freezes the interface - the queue
 * just shows "resolving stream…" until the controller catches up.
 */
class TuiApp(
    private val console: ConsoleTerminal,
    private val keys: KeySource,
    private val frame: Frame,
    private val view: TuiView,
    private val printer: Printer,
    private val controller: PlaybackController,
    private val catalog: CatalogService,
    private val backend: String,
    private val quality: AudioQuality,
    private val lyrics: LyricsService,
    private val lyricsOrder: List<LyricsProvider>,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var playback: Job? = null

    @Volatile
    private var queue: List<Track> = emptyList()

    @Volatile
    private var cursor = 0

    @Volatile
    private var running = true

    @Volatile
    private var overlay = TuiState.Overlay.NONE

    // Message and busy are also written by the background lyrics fetch, so they
    // need their own visibility; the queue fields above are only ever touched
    // on the render thread but keep the annotation for consistency.
    @Volatile
    private var message = ""

    @Volatile
    private var messageIsError = false

    @Volatile
    private var busy = ""

    @Volatile
    private var lyricsLines: List<String> = emptyList()

    @Volatile
    private var lyricsTitle = ""

    @Volatile
    private var lyricsOffset = 0

    private var shuffle = false
    private var repeat = false
    private var lyricsJob: Job? = null

    /** Bumped on every `/l` request; stale fetches drop their result. */
    @Volatile
    private var lyricsRequest = 0

    fun run(): Int {
        frame.start()
        console.startRawMode()
        console.terminal.puts(Capability.clear_screen)
        try {
            while (running) {
                keys.read(POLL_MS)?.let(::onKey)
                draw()
            }
        } finally {
            playback?.cancel()
            controller.stop()
            console.stopRawMode()
            frame.stop()
            scope.cancel()
        }
        return 0
    }

    /** Seeds the queue from a startup search, optionally starting playback. */
    fun start(tracks: List<Track>, autoplay: Boolean = true) {
        queue = tracks
        cursor = 0
        if (autoplay) playFrom(0)
    }

    private fun draw() {
        val now = controller.nowPlaying
        frame.draw(
            view.render(
                TuiState(
                    queue = queue,
                    cursor = cursor,
                    playingIndex = now.index,
                    status = now.status,
                    resolving = now.resolving,
                    finished = now.finished,
                    message = message,
                    messageIsError = messageIsError,
                    overlay = overlay,
                    shuffle = shuffle,
                    repeat = repeat,
                    backend = backend,
                    quality = quality.name.lowercase(),
                    busy = busy,
                    lyrics = lyricsLines,
                    lyricsTitle = lyricsTitle,
                    lyricsOffset = lyricsOffset,
                ),
                frame.width,
                frame.height,
            ),
        )
    }

    // --- input --------------------------------------------------------------

    private fun onKey(key: TerminalKey) {
        when (key) {
            is TerminalKey.CtrlC, TerminalKey.Eof -> running = false
            else -> if (!dispatch(key)) beep()
        }
    }

    /** Returns false when the key has no binding, so the caller can beep. */
    private fun dispatch(key: TerminalKey): Boolean {
        when (overlay) {
            // Any key dismisses help; the lyrics view answers for itself so it
            // can keep j/k for scrolling instead of firing queue shortcuts.
            TuiState.Overlay.HELP -> {
                overlay = TuiState.Overlay.NONE
                return true
            }
            TuiState.Overlay.LYRICS -> return onLyricsKey(key)
            TuiState.Overlay.NONE -> Unit
        }
        when (key) {
            is TerminalKey.Char -> return onChar(key.value)
            TerminalKey.Enter -> {
                playFrom(cursor)
                return true
            }
            TerminalKey.Up -> return moveCursor(-1)
            TerminalKey.Down -> return moveCursor(1)
            TerminalKey.PageUp -> return moveCursor(-PAGE)
            TerminalKey.PageDown -> return moveCursor(PAGE)
            TerminalKey.Home -> return setCursor(0)
            TerminalKey.End -> return setCursor(Int.MAX_VALUE)
            TerminalKey.Left, TerminalKey.Backspace -> {
                controller.seekBy(-SEEK_STEP_MS)
                return true
            }

            TerminalKey.Right, TerminalKey.Delete -> {
                controller.seekBy(SEEK_STEP_MS)
                return true
            }

            else -> return false
        }
    }

    private fun onChar(value: kotlin.Char): Boolean {
        when (value.lowercaseChar()) {
            'q', 'x' -> running = false
            ' ' -> if (queue.isEmpty()) warn("Nothing queued. Press / to search first.") else controller.togglePause()
            'n' -> controller.next()
            'p' -> controller.previous()
            'j' -> moveCursor(1)
            'k' -> moveCursor(-1)
            'r' -> controller.restart()
            'a' -> playFrom(cursor)
            'c' -> clearQueue()
            's' -> {
                shuffle = !shuffle
                message = "shuffle ${if (shuffle) "on" else "off"}"
            }

            't' -> {
                repeat = !repeat
                message = "repeat ${if (repeat) "on" else "off"}"
            }

            '/' -> search(replace = true)
            'A' -> search(replace = false)
            'd' -> removeSelected()
            'e' -> jumpToPlaying()
            'l' -> if (overlay == TuiState.Overlay.LYRICS) closeLyrics() else requestLyrics()
            'o' -> openAlbum(replace = true)
            'O' -> openAlbum(replace = false)
            '+', '=' -> controller.volumeBy(VOLUME_STEP)
            '-' -> controller.volumeBy(-VOLUME_STEP)
            '?' -> overlay = TuiState.Overlay.HELP
            else -> return false
        }
        return true
    }

    private fun moveCursor(delta: Int): Boolean {
        if (queue.isEmpty()) return false
        cursor = (cursor + delta).coerceIn(0, queue.lastIndex)
        return true
    }

    private fun setCursor(index: Int): Boolean {
        if (queue.isEmpty()) return false
        cursor = index.coerceIn(0, queue.lastIndex)
        return true
    }

    // --- queue actions ------------------------------------------------------

    private fun playFrom(index: Int) {
        if (queue.isEmpty()) {
            warn("Nothing queued. Press / to search first.")
            return
        }
        clearMessage()
        playback?.cancel()
        controller.stop()
        val snapshot = queue
        playback = scope.launch {
            controller.play(
                snapshot,
                PlayOptions(
                    startIndex = index.coerceIn(0, snapshot.lastIndex),
                    shuffle = shuffle,
                    repeat = repeat,
                    showProgress = false,
                ),
            )
        }
    }

    private fun clearQueue() {
        playback?.cancel()
        controller.stop()
        queue = emptyList()
        cursor = 0
        message = "queue cleared"
    }

    /**
     * Removes the selected track and restarts playback from the adjusted
     * position, because the controller holds its own queue snapshot: leaving it
     * stale would silently keep playing a track that is no longer queued.
     */
    private fun removeSelected() {
        if (queue.isEmpty() || cursor !in queue.indices) return
        val index = cursor
        val now = controller.nowPlaying
        val playingIndex = if (now.track != null) now.index else -1
        queue = queue.filterIndexed { i, _ -> i != index }
        if (queue.isEmpty()) {
            playback?.cancel()
            controller.stop()
            cursor = 0
            message = "queue cleared"
            return
        }
        cursor = cursor.coerceIn(0, queue.lastIndex)
        if (playingIndex >= 0) {
            val start = if (index < playingIndex) playingIndex - 1 else playingIndex
            playFrom(start.coerceIn(0, queue.lastIndex))
        } else {
            message = "removed"
        }
    }

    /**
     * Extends the queue without hijacking playback: the current track keeps
     * playing (restarted from its position in the new queue, so the controller
     * and the view stay in sync) and the cursor lands on the first new track.
     */
    private fun appendTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val now = controller.nowPlaying
        val playingIndex = if (now.track != null) now.index else -1
        queue = queue + tracks
        cursor = (queue.size - tracks.size).coerceAtLeast(0)
        if (playingIndex >= 0) {
            playFrom(playingIndex.coerceIn(0, queue.lastIndex))
        } else {
            message = "${tracks.size} track(s) added"
        }
    }

    /** Puts the selection back on the track the audio is actually on. */
    private fun jumpToPlaying() {
        val now = controller.nowPlaying
        if (now.track == null || now.index !in queue.indices) {
            warn("Nothing playing.")
            return
        }
        cursor = now.index
    }

    /** Replaces (o) or extends (O) the queue with the selected track's album. */
    private fun openAlbum(replace: Boolean) {
        val track = queue.getOrNull(cursor)
        if (track == null) {
            warn("Nothing selected.")
            return
        }
        val albumId = track.album?.id
        if (albumId.isNullOrBlank()) {
            warn("“${track.title}” has no album page id.")
            return
        }
        writeBottomLine("opening album…")
        val result = runBlocking { catalog.album(albumId) }
        if (result.isFailure) {
            warn(result.exceptionOrNull()?.message ?: "album fetch failed")
            return
        }
        val songs = result.getOrNull()?.songs ?: emptyList()
        if (songs.isEmpty()) {
            warn("The album has no playable songs.")
            return
        }
        if (replace) {
            queue = songs
            cursor = 0
            playFrom(0)
        } else {
            appendTracks(songs)
        }
    }

    /**
     * Runs a search. The console is handed to a real line editor for the query:
     * JLine already does editing, history and UTF-8, and re-implementing that
     * here would only produce something worse.
     */
    private fun search(replace: Boolean) {
        val query = prompt("search YouTube Music — Enter to run, Esc to cancel", "> ") ?: return
        if (query.isBlank()) {
            clearMessage()
            return
        }
        clearMessage()
        writeBottomLine("searching “$query”…")
        val result = runBlocking { catalog.search(query, SearchScope.SONGS, SEARCH_LIMIT) }
        if (result.isFailure) {
            warn(result.exceptionOrNull()?.message ?: "search failed")
            return
        }
        val tracks: List<Track> = result.getOrNull()?.songs ?: emptyList()
        if (tracks.isEmpty()) {
            warn("No songs matched “$query”.")
            return
        }
        if (replace) {
            queue = tracks
            cursor = 0
            playFrom(0)
        } else {
            appendTracks(tracks)
        }
    }

    // --- console helpers ----------------------------------------------------

    /** Draws one line on the bottom row and returns after a full repaint. */
    private fun prompt(hint: String, prefix: String): String? {
        console.stopRawMode()
        val out = console.terminal.writer()
        out.print("\u001b[${frame.height};1H")
        out.print(printer.dim(hint))
        out.print("\r\n" + prefix)
        out.flush()
        val answer =
            try {
                console.lineReader().readLine("")
            } catch (error: Throwable) {
                if (error.isUserInterrupt() || error.isEndOfFile()) null else throw error
            }
        returnScreen()
        return answer?.trim()
    }

    /** Prints a transient line and repaints over it. */
    private fun writeBottomLine(text: String) {
        val out = console.terminal.writer()
        out.print("\u001b[${frame.height};1H")
        out.print(printer.dim(text))
        out.print("\u001b[2K")
        out.flush()
        returnScreen()
    }

    private fun returnScreen() {
        console.startRawMode()
        // The line editor moved the cursor and left its own text behind, so the
        // next paint has to rewrite the frame instead of diffing against it.
        frame.invalidate()
        draw()
    }

    private fun beep() {
        val out = console.terminal.writer()
        out.print("\u0007")
        out.flush()
    }

    private fun warn(text: String) {
        message = text
        messageIsError = true
    }

    private fun clearMessage() {
        message = ""
        messageIsError = false
    }

    // --- lyrics -------------------------------------------------------------

    /** What `/l` targets: the playing track, or the selection while idle. */
    private fun selectedForLyrics(): Track? {
        val now = controller.nowPlaying
        if (now.track != null) return queue.getOrNull(now.index)
        return queue.getOrNull(cursor)
    }

    private fun requestLyrics() {
        val track = selectedForLyrics()
        if (track == null) {
            warn("Nothing to show lyrics for.")
            return
        }
        lyricsJob?.cancel()
        busy = "fetching lyrics for “${track.title}”…"
        message = ""
        messageIsError = false
        val request = ++lyricsRequest
        lyricsJob =
            scope.launch {
                val hit = fetchLyrics(track)
                // A later request replaced this one, or the user closed the
                // view while it was in flight: drop the result either way.
                if (!isActive || request != lyricsRequest) return@launch
                busy = ""
                if (hit == null) {
                    message = "No lyrics found for “${track.title}”."
                    messageIsError = true
                } else {
                    lyricsTitle = "${track.artistString} — ${track.title}"
                    lyricsLines = hit
                    lyricsOffset = 0
                    overlay = TuiState.Overlay.LYRICS
                }
            }
    }

    /**
     * Walks the configured providers and returns the first usable plain-text
     * answer. Providers are suspend calls, so the TUI stays responsive while
     * this runs on the background coroutine.
     */
    private suspend fun fetchLyrics(track: Track): List<String>? {
        for (provider in lyricsOrder) {
            val raw =
                lyrics.fetch(
                    provider = provider,
                    title = track.title,
                    artist = track.artistString,
                    album = track.album?.title,
                    videoId = track.id,
                    durationSeconds = track.duration ?: -1,
                ).getOrNull() ?: continue
            val lines = LyricsHit(provider, raw).plain.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    // --- lyrics overlay keys ------------------------------------------------

    private fun onLyricsKey(key: TerminalKey): Boolean {
        when (key) {
            is TerminalKey.Char ->
                when (key.value.lowercaseChar()) {
                    'q', 'x', 'l', ' ' -> return closeLyrics()
                    'j' -> return scrollLyrics(1)
                    'k' -> return scrollLyrics(-1)
                    else -> return true
                }

            TerminalKey.Escape, TerminalKey.Enter -> return closeLyrics()
            TerminalKey.Up -> return scrollLyrics(-1)
            TerminalKey.Down -> return scrollLyrics(1)
            TerminalKey.PageUp -> return scrollLyrics(-PAGE)
            TerminalKey.PageDown -> return scrollLyrics(PAGE)
            else -> return true
        }
    }

    private fun closeLyrics(): Boolean {
        lyricsJob?.cancel()
        lyricsJob = null
        overlay = TuiState.Overlay.NONE
        return true
    }

    private fun scrollLyrics(delta: Int): Boolean {
        if (lyricsLines.isEmpty()) return true
        lyricsOffset = (lyricsOffset + delta).coerceIn(0, maxOf(0, lyricsLines.lastIndex))
        return true
    }

    private companion object {
        const val POLL_MS = 250L
        const val SEEK_STEP_MS = 10_000L
        const val VOLUME_STEP = 5
        const val PAGE = 10
        const val SEARCH_LIMIT = 50
    }
}
