package moe.rukamori.archivetune.cli.output

import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.model.Track

/**
 * Renders track lists as tightly aligned columns.
 *
 * The layout is computed here rather than with Mordant's table widget so the
 * output stays stable across Mordant versions and so ANSI styling is not counted
 * when measuring column widths. Column widths come from [TextWidth] rather than
 * `String.length`, because CJK titles are the norm here and each glyph is two
 * columns wide.
 *
 * Note: the Kotlin 2.4 stdlib no longer ships `String.padTo`/`Iterable
 * .joinIndexed`, so padding goes through `padStart`/`padEnd` and the header row
 * is built with a plain indexed loop.
 */
class TrackTableRenderer(private val printer: Printer) {
    fun render(tracks: List<Track>, showHeader: Boolean = true) {
        if (tracks.isEmpty()) {
            printer.echo(printer.dim("  (no results)"))
            return
        }
        if (printer.format != OutputFormat.TABLE) return

        val headers = listOf("#", "Title", "Artist", "Album", "Length")
        val rows =
            tracks.mapIndexed { index, track ->
                listOf(
                    (index + 1).toString(),
                    track.title,
                    track.artistString,
                    track.album?.title.orEmpty(),
                    track.durationText,
                )
            }

        val widths =
            headers.indices.map { column ->
                val content = rows.maxOf { TextWidth.width(it[column]) }
                maxOf(TextWidth.width(headers[column]), content)
            }

        if (showHeader) {
            val header =
                buildString {
                    headers.forEachIndexed { column, header ->
                        if (column > 0) append("  ")
                        append(TextWidth.padEnd(header, widths[column]))
                    }
                }
            printer.echo(printer.dimBold(header.trimEnd()))
        }
        rows.forEach { row ->
            val line =
                buildString {
                    row.forEachIndexed { column, value ->
                        if (column > 0) append("  ")
                        // The index and duration columns read better right-aligned.
                        val padded =
                            if (column == 0 || column == 4) {
                                TextWidth.padStart(value, widths[column])
                            } else {
                                TextWidth.padEnd(value, widths[column])
                            }
                        append(padded)
                    }
                }
            printer.echo(line.trimEnd())
        }
    }
}
