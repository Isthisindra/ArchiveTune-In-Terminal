package moe.rukamori.archivetune.cli.output

import moe.rukamori.archivetune.cli.model.formatDuration
import moe.rukamori.archivetune.cli.playback.PlaybackStatus

/**
 * Draws the single-line "now playing" strip.
 *
 * On a terminal that supports ANSI the line is redrawn in place with a carriage
 * return and an erase-to-end-of-line; everywhere else (pipes, CI, `cmd.exe`
 * without VT) nothing is drawn at all, because a scrolling log full of progress
 * lines is worse than silence. Callers print one plain line per track in that
 * case instead.
 */
class ProgressRenderer(
    private val printer: Printer,
    private val supportsInPlace: Boolean,
    private val widthProvider: () -> Int = { 80 },
) {
    private var lastLineLength = 0
    private var active = false

    val enabled: Boolean get() = supportsInPlace

    fun start() {
        if (!enabled) return
        active = true
        lastLineLength = 0
        printer.print("")
    }

    /** Redraws the bar. Safe to call on every tick. */
    fun update(status: PlaybackStatus, title: String, artist: String, extra: String = "") {
        if (!enabled || !active) return
        val line = build(status, title, artist, extra)
        val padded = line.takeLast(maxOf(lastLineLength, line.length))
        printer.print("\r$printer.gray($padded)")
        lastLineLength = line.length
    }

    /** Clears the bar so normal output can continue below it. */
    fun stop() {
        if (!enabled || !active) return
        active = false
        printer.print("\r" + " ".repeat(maxOf(lastLineLength, 1)) + "\r")
        lastLineLength = 0
    }

    private fun build(status: PlaybackStatus, title: String, artist: String, extra: String): String {
        val width = maxOf(20, widthProvider())
        // "[1:23/3:45] title - artist            ▕████░░░░▏ 37%  vol 80"
        val clock = "${formatDuration(status.positionMs)}/${formatDuration(status.durationMs)}"
        val barWidth = (width * 0.22).toInt().coerceIn(8, 24)
        val percent = (status.progress * 100).toInt().coerceIn(0, 100)
        val filled = (barWidth * status.progress).toInt().coerceIn(0, barWidth)
        val bar = "▕" + "█".repeat(filled) + "░".repeat(barWidth - filled) + "▏"
        val volume = "vol ${status.volumePercent}"
        val tail = listOf(extra, "$percent%", volume).filter { it.isNotBlank() }.joinToString("  ")
        val fixed = clock.length + bar.length + tail.length + 6
        val label = "$title${if (artist.isBlank()) "" else " - $artist"}"
        val room = maxOf(4, width - fixed)
        val trimmed =
            if (label.length <= room) label
            else label.take(maxOf(0, room - 1)) + "…"
        return "$clock  ${printer.gray(bar)}  ${printer.gray(tail)}  $trimmed"
    }
}
