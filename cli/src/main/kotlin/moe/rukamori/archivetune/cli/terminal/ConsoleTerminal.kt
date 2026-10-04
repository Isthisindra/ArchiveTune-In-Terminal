package moe.rukamori.archivetune.cli.terminal

import org.jline.reader.EndOfFileException
import org.jline.reader.History
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.history.DefaultHistory
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.IOException

/**
 * A [JLine] terminal opened for the current console.
 *
 * On Windows this is what makes raw mode work at all: `System.in` gives you
 * buffered, line-oriented input no matter what the console is in, while JLine
 * talks to the console through its JNI/Jansi provider and can switch the tty
 * into character-at-a-time mode.
 */
class ConsoleTerminal private constructor(
    val terminal: Terminal,
    private val ownsTerminal: Boolean,
) : AutoCloseable {

    private var rawAttributes: Attributes? = null

    /** True when [startRawMode] currently holds the console. */
    val isRaw: Boolean get() = rawAttributes != null

    fun startRawMode() {
        if (rawAttributes == null) rawAttributes = terminal.enterRawMode()
    }

    fun stopRawMode() {
        val attributes = rawAttributes ?: return
        rawAttributes = null
        runCatching { terminal.setAttributes(attributes) }
    }

    /** Single key presses, for the player controls and the TUI. */
    fun keys(): KeySource = JLineKeySource(terminal)

    /**
     * A readline editor on this terminal. Used for the one-off prompts the TUI
     * hands back mid-session, where re-implementing editing and history would
     * be pure waste.
     */
    fun lineReader(): LineReader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .history(history)
            .build()

    override fun close() {
        stopRawMode()
        if (ownsTerminal) runCatching { terminal.close() }
    }

    companion object {
        /** Shared across prompts so the arrow-up history works within a session. */
        val history: History by lazy { DefaultHistory() }

        /**
         * Opens the console, or returns null when there is no usable one -
         * piped input, a CI runner, or a Windows console JLine cannot attach to.
         */
        fun open(name: String = "archivetune"): ConsoleTerminal? =
            runCatching {
                ConsoleTerminal(TerminalBuilder.builder().name(name).build(), ownsTerminal = true)
            }.getOrNull()

        fun of(terminal: Terminal): ConsoleTerminal =
            ConsoleTerminal(terminal, ownsTerminal = false)
    }
}

/**
 * Reads single key presses from a raw-mode JLine terminal.
 *
 * The decoding is deliberately hand-rolled: JLine 4 has no `BindingReader`, and
 * a small decoder is easier to reason about than dragging in a whole keymap
 * just to recognise `\u001b[5~`.
 */
internal class JLineKeySource(private val terminal: Terminal) : KeySource {

    private val reader = terminal.reader()

    override fun read(timeoutMs: Long): TerminalKey? {
        val code = readRaw(timeoutMs) ?: return null
        if (code == EndOfInput) return TerminalKey.Eof
        val ch = code.toChar()
        return when {
            code == ESC -> readEscape()
            ch == '\r' || ch == '\n' -> TerminalKey.Enter
            ch == '\t' -> TerminalKey.Tab
            ch.code == 127 || ch.code == 8 -> TerminalKey.Backspace
            code == 3 -> TerminalKey.CtrlC
            code == 4 -> TerminalKey.CtrlD
            // ESC + key is Alt+key. Reporting it as a bare Escape keeps a
            // stray modifier press from firing an unrelated command.
            else -> TerminalKey.Char(ch)
        }
    }

    /** Reads one code unit, or null on timeout / end of input. */
    private fun readRaw(timeoutMs: Long): Int? =
        try {
            when (val value = reader.read(timeoutMs)) {
                TimedOut, EndOfInput -> null
                else -> value
            }
        } catch (_: IOException) {
            null
        }

    private fun readEscape(): TerminalKey {
        val next = readRaw(ESC_TIMEOUT_MS) ?: return TerminalKey.Escape
        return when (next.toChar()) {
            '[' -> readCsi()
            // SS3, sent by the numeric keypad and by some Windows consoles.
            'O' ->
                when (readRaw(ESC_TIMEOUT_MS)?.toChar()) {
                    'A' -> TerminalKey.Up
                    'B' -> TerminalKey.Down
                    'C' -> TerminalKey.Right
                    'D' -> TerminalKey.Left
                    'H' -> TerminalKey.Home
                    'F' -> TerminalKey.End
                    else -> TerminalKey.Unknown
                }

            else -> TerminalKey.Escape
        }
    }

    /** Reads the body of a `CSI` sequence; the leading `[` is already gone. */
    private fun readCsi(): TerminalKey {
        val parameters = StringBuilder()
        while (true) {
            val code = readRaw(ESC_TIMEOUT_MS) ?: return TerminalKey.Escape
            val ch = code.toChar()
            if (ch in '0'..'9' || ch == ';' || ch == '<') {
                parameters.append(ch)
                continue
            }
            return when (ch) {
                'A' -> TerminalKey.Up
                'B' -> TerminalKey.Down
                'C' -> TerminalKey.Right
                'D' -> TerminalKey.Left
                'H' -> TerminalKey.Home
                'F' -> TerminalKey.End
                'Z' -> TerminalKey.ShiftTab
                '~' ->
                    when (parameters.toString()) {
                        "1", "7" -> TerminalKey.Home
                        "3" -> TerminalKey.Delete
                        "4", "8" -> TerminalKey.End
                        "5" -> TerminalKey.PageUp
                        "6" -> TerminalKey.PageDown
                        // 2 = Insert, 200/201 = bracketed paste. Nothing to do.
                        else -> TerminalKey.Unknown
                    }

                else -> TerminalKey.Unknown
            }
        }
    }

    private companion object {
        const val ESC = 27
        const val EndOfInput = -1
        const val TimedOut = -2

        /** Long enough for a local terminal, short enough to feel instant on Esc. */
        const val ESC_TIMEOUT_MS = 40L
    }
}

/**
 * Reads lines of text the portable way. Used when the console is not a terminal
 * (a pipe, a CI log) and single-key control is impossible anyway.
 */
class StdinKeySource(
    private val reader: java.io.BufferedReader = System.`in`.bufferedReader(),
) : KeySource {

    /** Always blocks; [timeoutMs] is ignored because a plain stream cannot. */
    override fun read(timeoutMs: Long): TerminalKey? {
        val line = reader.readLine() ?: return TerminalKey.Eof
        return line.firstOrNull()?.let { TerminalKey.Char(it) } ?: TerminalKey.Enter
    }
}

/** True when a line reader ended because the user hit Ctrl+C. */
fun Throwable.isUserInterrupt(): Boolean = this is UserInterruptException

/** True when a line reader ended because stdin was closed. */
fun Throwable.isEndOfFile(): Boolean = this is EndOfFileException
