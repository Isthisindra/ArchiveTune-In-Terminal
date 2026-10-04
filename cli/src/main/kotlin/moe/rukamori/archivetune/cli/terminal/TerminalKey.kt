package moe.rukamori.archivetune.cli.terminal

/**
 * A single key press, decoded from whatever the terminal sent.
 *
 * Escape sequences are resolved here so the rest of the CLI only ever deals
 * with "up" or "enter" and never with `\u001b[A`.
 */
sealed interface TerminalKey {
    /** `kotlin.Char` spelled out: the nested `Char` class shadows the builtin. */
    data class Char(val value: kotlin.Char) : TerminalKey

    data object Enter : TerminalKey

    data object Tab : TerminalKey

    data object ShiftTab : TerminalKey

    data object Escape : TerminalKey

    data object Backspace : TerminalKey

    data object Delete : TerminalKey

    data object Up : TerminalKey

    data object Down : TerminalKey

    data object Left : TerminalKey

    data object Right : TerminalKey

    data object Home : TerminalKey

    data object End : TerminalKey

    data object PageUp : TerminalKey

    data object PageDown : TerminalKey

    data object CtrlC : TerminalKey

    data object CtrlD : TerminalKey

    /** End of input: the console was closed, not just idle. */
    data object Eof : TerminalKey

    /** A sequence we recognise as a key but have no binding for. */
    data object Unknown : TerminalKey
}

/** Where key presses come from. */
interface KeySource {
    /**
     * The next key press, or null when [timeoutMs] elapses with nothing
     * available. Never blocks for longer than that, which is what lets the
     * player keep repainting while nobody is touching the keyboard.
     */
    fun read(timeoutMs: Long): TerminalKey?

    fun close() {}
}
