package moe.rukamori.archivetune.cli.tui

import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp.Capability

/**
 * A full-screen line buffer drawn straight to the terminal.
 *
 * JLine's `Display` is built for readline redraws, where the buffer must stay in
 * sync with a text line. A TUI redraws an arbitrary grid, so this keeps its own
 * previous frame and only rewrites the lines that actually changed - which is
 * what stops the queue view from flickering at four frames a second.
 */
class Frame(private val terminal: Terminal) {

    private var previous: List<String> = emptyList()
    private var started = false

    val width: Int get() = runCatching { terminal.size.columns }.getOrDefault(80).coerceAtLeast(20)

    val height: Int get() = runCatching { terminal.size.rows }.getOrDefault(24).coerceAtLeast(8)

    /** Swaps to the alternate screen and hides the cursor. */
    fun start() {
        if (started) return
        started = true
        terminal.puts(Capability.enter_ca_mode)
        terminal.puts(Capability.cursor_invisible)
        terminal.writer().flush()
    }

    fun stop() {
        if (!started) return
        started = false
        terminal.puts(Capability.cursor_normal)
        terminal.puts(Capability.exit_ca_mode)
        terminal.writer().flush()
        previous = emptyList()
    }

    /**
     * Forgets the previous frame so the next [draw] repaints every line. Needed
     * after something else has written to the screen - handing the console to a
     * line editor, for instance.
     */
    fun invalidate() {
        previous = emptyList()
    }

    /**
     * Paints [lines] over the previous frame. Lines that are identical are left
     * alone; the cursor is parked at the bottom so the top of the screen stays
     * still while the queue scrolls underneath it.
     */
    fun draw(lines: List<String>) {
        val out = terminal.writer()
        val rows = lines.take(height)

        // A resize can make the old frame taller than the new one.
        val stale = previous.size - rows.size
        for (index in rows.indices) {
            val line = rows[index]
            if (index < previous.size && previous[index] == line) continue
            out.print("\u001b[${index + 1};1H")
            // Padding to the full width erases whatever the old line left there.
            out.print(line)
            out.print(" ".repeat((width - visibleWidth(line)).coerceAtLeast(0)))
            out.print("\u001b[K")
        }
        if (stale > 0) {
            for (index in rows.size until previous.size) {
                out.print("\u001b[${index + 1};1H")
                out.print("\u001b[2K")
            }
        }
        out.flush()
        previous = rows
    }

    /**
     * Width of [text] as the terminal counts it: escape sequences are free and
     * the box-drawing characters used for the progress bar are one column each.
     */
    fun visibleWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\u001b') {
                // Skip CSI (ESC [ ... final-byte) and OSC (ESC ] ... BEL/ST).
                when (text.getOrNull(index + 1)) {
                    '[' -> {
                        var cursor = index + 2
                        while (cursor < text.length && text[cursor] !in '@'..'~') cursor++
                        index = cursor + 1
                        continue
                    }

                    ']' -> {
                        var cursor = index + 2
                        while (cursor < text.length && text[cursor] != '\u0007') {
                            if (text[cursor] == '\u001b' && text.getOrNull(cursor + 1) == '\\') {
                                cursor++
                                break
                            }
                            cursor++
                        }
                        index = cursor + 1
                        continue
                    }

                    else -> {
                        index += 2
                        continue
                    }
                }
            }
            if (ch.code in 0x0300..0x036F) {
                // Combining mark: rides along with the previous glyph.
                index++
                continue
            }
            width++
            index++
        }
        return width
    }

    /** Cuts [text] to [max] visible columns, keeping escape sequences intact. */
    fun clip(text: String, max: Int): String {
        if (max <= 0) return ""
        var width = 0
        var index = 0
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\u001b') {
                val start = index
                when (text.getOrNull(index + 1)) {
                    '[' -> {
                        var cursor = index + 2
                        while (cursor < text.length && text[cursor] !in '@'..'~') cursor++
                        index = cursor + 1
                    }

                    else -> index += 2
                }
                out.append(text, start, index)
                continue
            }
            if (width >= max) break
            out.append(ch)
            width++
            index++
        }
        return out.toString()
    }
}
