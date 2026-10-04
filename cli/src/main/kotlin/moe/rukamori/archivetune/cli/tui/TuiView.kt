package moe.rukamori.archivetune.cli.tui

import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.formatDuration
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.output.TextWidth
import moe.rukamori.archivetune.cli.playback.PlaybackStatus

/**
 * Everything the TUI draws, as one immutable value.
 *
 * The render loop is a pure function of this: no hidden state, so a redraw after
 * a key press and a redraw after a playback tick produce the same frame for the
 * same [TuiState].
 */
data class TuiState(
    val queue: List<Track> = emptyList(),
    val cursor: Int = 0,
    val playingIndex: Int = -1,
    val status: PlaybackStatus = PlaybackStatus(playing = false, 0L, 0L, 80),
    val resolving: Boolean = false,
    val finished: Boolean = true,
    val message: String = "",
    val messageIsError: Boolean = false,
    val overlay: Overlay = Overlay.NONE,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val backend: String = "",
    val quality: String = "",
    val busy: String = "",
    val lyrics: List<String> = emptyList(),
    val lyricsTitle: String = "",
    val lyricsOffset: Int = 0,
) {
    enum class Overlay { NONE, HELP, LYRICS }

    val current: Track? get() = queue.getOrNull(playingIndex)

    val isEmpty: Boolean get() = queue.isEmpty()
}

/**
 * Lays a [TuiState] out into terminal lines.
 *
 * Kept free of I/O so the layout can be reasoned about (and tested) without a
 * terminal, and so the same state always yields the same frame. Every row is
 * measured with [TextWidth] rather than `String.length`, because a queue of
 * Japanese or Korean titles is the normal case and those glyphs are two columns
 * wide; the box-drawing characters used for the bar are single-width in every
 * terminal that ArchiveTune cares about.
 */
class TuiView(private val printer: Printer) {

    fun render(state: TuiState, width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()
        lines += header(state, width)
        lines += nowPlaying(state, width)
        lines += progressBar(state, width)
        lines += ""
        lines += queueHeader(state, width)

        when (state.overlay) {
            // Lyrics take over the whole queue area, paged by lyricsOffset.
            TuiState.Overlay.LYRICS -> {
                val areaHeight = (height - lines.size - FOOTER_ROWS).coerceAtLeast(1)
                lines += lyricsLines(state, width, areaHeight)
            }
            else -> {
                val overlay = if (state.overlay == TuiState.Overlay.HELP) helpLines() else emptyList()
                val listHeight = (height - lines.size - overlay.size - FOOTER_ROWS).coerceAtLeast(1)
                lines += queueRows(state, width, listHeight)
                lines += overlay
            }
        }
        // A short queue (or a tall terminal) leaves slack; pad it so the
        // separator and the key hints always sit on the last rows. Without this
        // the frame would be shorter than the terminal and the top of the queue
        // would drift up on every repaint.
        repeat((height - lines.size - FOOTER_ROWS).coerceAtLeast(0)) { lines += "" }
        lines += " " + printer.dim("─".repeat((width - 1).coerceAtLeast(0)))
        lines += footer(state, width)
        // Only reachable on a terminal too short to hold the fixed chrome.
        return if (lines.size > height) lines.subList(0, height).toList() else lines
    }

    private fun header(state: TuiState, width: Int): String {
        val left = printer.bold("ArchiveTune")
        val badges =
            buildList {
                state.backend.takeIf(String::isNotBlank)?.let { add(it) }
                state.quality.takeIf(String::isNotBlank)?.let { add(it) }
                add("vol ${state.status.volumePercent}")
                if (state.shuffle) add("shuffle")
                if (state.repeat) add("repeat")
            }.joinToString("  ")
        val right = printer.dim(badges)
        return spread(left, right, width)
    }

    private fun nowPlaying(state: TuiState, width: Int): String {
        val track = state.current
        val status = when {
            state.resolving -> printer.dim("resolving stream…")
            track == null -> printer.dim("nothing playing")
            else -> "${printer.accent(artistOf(track))}  ${printer.bold(clip(track.title, width / 2))}"
        }
        val position = if (state.finished) "" else formatDuration(state.status.positionMs)
        return spread(status, printer.dim(position), width)
    }

    private fun progressBar(state: TuiState, width: Int): String {
        val status = state.status
        val label = "${formatDuration(status.positionMs)} / " +
            if (status.durationMs > 0) formatDuration(status.durationMs) else "--:--"
        val marker = if (state.finished) "[]" else if (status.playing) " >" else "||"
        val barWidth = (width - label.length - marker.length - 4).coerceAtLeast(4)
        val filled = (barWidth * status.progress).toInt().coerceIn(0, barWidth)
        val bar = "█".repeat(filled) + "░".repeat(barWidth - filled)
        return " $marker " + printer.cyan(bar) + "  " + printer.dim(label)
    }

    private fun queueHeader(state: TuiState, width: Int): String {
        val count = if (state.isEmpty) "empty" else "${state.queue.size} tracks"
        val left = printer.dimBold("Queue  $count")
        val right = printer.dim(if (state.isEmpty) "" else "${state.cursor + 1}/${state.queue.size}")
        return spread(left, right, width)
    }

    /**
     * The queue window. It follows [TuiState.cursor] but also tries to keep the
     * playing row visible, so the selection and the audio do not drift apart
     * while a long queue scrolls past.
     */
    private fun queueRows(state: TuiState, width: Int, height: Int): List<String> {
        if (state.isEmpty) {
            return listOf("   " + printer.dim("press / to search, a to play the queue, ? for help"))
        }
        val numberWidth = state.queue.size.toString().length
        val durationWidth = 5
        val artistWidth = (width / 3).coerceIn(10, 34)
        // " " + 2 marker columns + " " + number + " " + title + 2 + artist + 2 + duration
        val titleWidth = (width - 6 - numberWidth - 2 - artistWidth - 2 - durationWidth)
            .coerceAtLeast(12)

        val top = windowTop(state, height)
        return (top until minOf(top + height, state.queue.size)).map { index ->
            val track = state.queue[index]
            val selected = index == state.cursor
            val playing = index == state.playingIndex
            // Two marker columns: the playhead on the left, the cursor on the
            // right, so `> >` reads as "playing and selected".
            val marker = "${if (playing) ">" else " "} ${if (selected) ">" else " "}"
            val number = "${index + 1}".padStart(numberWidth)
            val line =
                " $marker $number " +
                    TextWidth.padEnd(clip(track.title, titleWidth), titleWidth) +
                    "  " +
                    TextWidth.padEnd(clip(track.artistString, artistWidth), artistWidth) +
                    "  " +
                    track.durationText.padStart(durationWidth)
            when {
                selected && playing -> printer.accent(clip(line, width))
                selected -> printer.bold(clip(line, width))
                playing -> printer.cyan(clip(line, width))
                else -> clip(line, width)
            }
        }
    }

    private fun windowTop(state: TuiState, height: Int): Int {
        val maxTop = (state.queue.size - height).coerceAtLeast(0)
        val cursorTop = (state.cursor - height + 1).coerceAtLeast(0)
        val playingTop = state.playingIndex
            .takeIf { it >= 0 }
            ?.let { it - height / 2 }
            ?: cursorTop
        return maxOf(cursorTop, playingTop).coerceIn(0, maxTop)
    }

    private fun footer(state: TuiState, width: Int): String =
        when {
            state.busy.isNotBlank() -> " " + printer.dim(clip(state.busy, width - 1))
            state.message.isNotBlank() ->
                " " + clip(
                    if (state.messageIsError) printer.red(state.message) else printer.dim(state.message),
                    width - 1,
                )

            else -> " " + printer.dim(clip(KEY_HINTS, width - 2))
        }

    private fun helpLines(): List<String> =
        listOf(
            "",
            " Keys",
        ) + KEY_HELP.map { "   $it" }

    /**
     * The lyrics view: a heading of artist — title, then the words paged by
     * [TuiState.lyricsOffset], so j/k (or PageUp/PageDown) scrolls through a
     * long song without the queue underneath changing.
     */
    private fun lyricsLines(state: TuiState, width: Int, height: Int): List<String> {
        val heading = state.lyricsTitle.ifBlank { "Lyrics" }
        val out = mutableListOf(" " + printer.bold(clip(heading, width - 1)))
        if (state.lyrics.isEmpty()) {
            out += "   " + printer.dim("no lyrics available")
            while (out.size < height) out += ""
            return out
        }
        val first = state.lyricsOffset.coerceIn(0, state.lyrics.lastIndex)
        var index = first
        while (out.size < height && index < state.lyrics.size) {
            out += "  " + clip(state.lyrics[index], width - 2)
            index++
        }
        while (out.size < height) out += ""
        return out
    }

    /** Left text, right text, and enough spaces to push the right one to the edge. */
    private fun spread(left: String, right: String, width: Int): String {
        val gap = (width - visible(left) - visible(right) - 1).coerceAtLeast(1)
        return " $left" + " ".repeat(gap) + right
    }

    private fun artistOf(track: Track): String =
        track.artistString.ifBlank { track.album?.title.orEmpty() }.ifBlank { "unknown artist" }

    private fun visible(text: String): Int = TextWidth.width(text)

    private fun clip(text: String, max: Int): String = TextWidth.truncate(text, max)

    private companion object {
        /** The separator and the key hints, which always close the frame. */
        const val FOOTER_ROWS = 2

        const val KEY_HINTS =
            "space pause · enter play · / search · d remove · o album · l lyrics · ? help · q quit"

        val KEY_HELP =
            listOf(
                "space      play / pause",
                "enter      play the queue from the selection",
                "n  p       next / previous track",
                "j  k       move the queue selection",
                "d          remove the selected track",
                "e          jump to the playing track",
                "a          play the whole queue",
                "/          search and replace the queue",
                "A          search and append to the queue",
                "o  O       open / append the selected track's album",
                "l          lyrics for the playing (or selected) track",
                "c          clear the queue",
                "+  -       volume",
                "<  >       seek 10 seconds",
                "r          restart the current track",
                "s  t       toggle shuffle / repeat",
                "esc        close the current view",
                "?          close this help",
                "q          quit",
            )
    }
}
