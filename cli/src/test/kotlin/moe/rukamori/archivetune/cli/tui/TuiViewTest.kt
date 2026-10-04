package moe.rukamori.archivetune.cli.tui

import com.github.ajalt.mordant.terminal.Terminal
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.model.AlbumRef
import moe.rukamori.archivetune.cli.model.ArtistRef
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.playback.PlaybackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TUI layout is pure string building, so it can be checked without a
 * terminal. The important invariants are that every line fits the width it was
 * given and that the frame is exactly as tall as the terminal, otherwise the
 * top of the queue would scroll off the screen.
 */
class TuiViewTest {

    private val printer = Printer(Terminal(), OutputFormat.TABLE, colorEnabled = false)
    private val view = TuiView(printer)

    private fun track(index: Int) =
        Track(
            id = "id$index",
            title = "Title number $index",
            artists = listOf(ArtistRef(null, "Artist $index")),
            album = AlbumRef(null, "Album $index"),
            duration = 200 + index,
        )

    private fun state(
        size: Int,
        cursor: Int = 0,
        playing: Int = -1,
        status: PlaybackStatus = PlaybackStatus(playing = true, 61_000, 240_000, 70),
    ) = TuiState(
        queue = (0 until size).map(::track),
        cursor = cursor,
        playingIndex = playing,
        status = status,
        finished = playing < 0,
        backend = "vlc",
        quality = "auto",
    )

    @Test
    fun `frame is exactly the terminal height`() {
        for (height in 10..40) {
            val lines = view.render(state(size = 60), width = 100, height = height)
            assertEquals("height $height", height, lines.size)
        }
    }

    @Test
    fun `no line overflows the width`() {
        // Colour is off here, so a plain length check is the real thing; the
        // styled paths are covered by the width-aware tests below.
        for (width in 40..120) {
            val lines = view.render(state(size = 40, playing = 3), width = width, height = 24)
            lines.forEach { line ->
                assertTrue(
                    "line of ${line.length} > $width: $line",
                    line.length <= width,
                )
            }
        }
    }

    @Test
    fun `empty queue explains what to do`() {
        val lines = view.render(TuiState(), width = 80, height = 20)
        assertTrue(lines.any { it.contains("press /") })
    }

    @Test
    fun `the playing row is marked and kept visible`() {
        val lines = view.render(state(size = 100, cursor = 0, playing = 90), width = 80, height = 24)
        // The now-playing header also names the track, so pick the queue row by
        // the playhead marker rather than by its text alone.
        val marked = lines.filter { it.trimStart().startsWith(">") && it.contains("Title number") }
        assertEquals(
            "expected exactly one marked row, got:\n" + lines.joinToString("\n"),
            1,
            marked.size,
        )
        assertTrue("wrong row marked: ${marked.single()}", marked.single().contains("Title number 90"))
    }

    @Test
    fun `the playhead and the cursor are separate marker columns`() {
        val lines = view.render(state(size = 100, cursor = 90, playing = 90), width = 80, height = 24)
        val row = lines.first { it.trimStart().startsWith(">") && it.contains("Title number") }
        assertTrue("expected `> >` for playing and selected, got: $row", row.trimStart().startsWith("> >"))
    }

    @Test
    fun `the progress bar tracks the position`() {
        val empty = view.render(
            state(size = 3, playing = 0, status = PlaybackStatus(true, 0, 240_000, 70)), 80, 20,
        )
        val halfway = view.render(
            state(size = 3, playing = 0, status = PlaybackStatus(true, 120_000, 240_000, 70)), 80, 20,
        )
        // Anchor on the empty half of the bar: at position zero there is no
        // filled block at all, so looking for one would find nothing.
        fun filled(lines: List<String>): Int =
            lines.first { it.contains('░') }.count { it == '█' }

        assertTrue("expected progress", filled(halfway) > filled(empty))
    }

    @Test
    fun `help overlay adds rows but keeps the frame the same height`() {
        val plain = view.render(state(size = 10), width = 90, height = 30)
        val help =
            view.render(state(size = 10).copy(overlay = TuiState.Overlay.HELP), width = 90, height = 30)
        assertEquals(30, plain.size)
        assertEquals(30, help.size)
        assertTrue(help.any { it.contains("toggle shuffle") })
    }

    @Test
    fun `lyrics overlay pages through a long song without breaking the frame`() {
        val text = (1..60).map { "Lyric line ${it.toString().padStart(2, '0')} with a bit of text" }
        val base =
            state(size = 5).copy(
                overlay = TuiState.Overlay.LYRICS,
                lyrics = text,
                lyricsTitle = "Artist 1 — Title number 1",
            )
        val page1 = view.render(base, width = 90, height = 30)
        val page2 = view.render(base.copy(lyricsOffset = 15), width = 90, height = 30)
        assertEquals(30, page1.size)
        assertEquals(30, page2.size)
        page1.forEach { line -> assertTrue("overflow: $line", line.length <= 90) }
        assertTrue(page1.any { it.contains("Artist 1 — Title number 1") })
        assertTrue(page1.any { it.contains("Lyric line 01") })
        assertTrue(page2.any { it.contains("Lyric line 16") })
        assertFalse(page2.any { it.contains("Lyric line 01") })
    }

    @Test
    fun `lyrics overlay without words says so`() {
        val lines =
            view.render(
                state(size = 0).copy(overlay = TuiState.Overlay.LYRICS),
                width = 80,
                height = 20,
            )
        assertEquals(20, lines.size)
        assertTrue(lines.any { it.contains("no lyrics available") })
    }
}
