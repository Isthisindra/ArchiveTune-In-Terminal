package moe.rukamori.archivetune.cli.playback

import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.terminal.ConsoleTerminal
import moe.rukamori.archivetune.cli.terminal.KeySource
import moe.rukamori.archivetune.cli.terminal.StdinKeySource
import moe.rukamori.archivetune.cli.terminal.TerminalKey

/**
 * Single-key transport controls for the one-shot player (`play`, `album --play`,
 * ...), the same set the TUI offers.
 *
 * With a JLine console the keys are read as they are pressed; otherwise we fall
 * back to stdin lines, which still works when output is piped but needs Enter.
 */
class ConsoleControls(
    private val controller: PlaybackController,
    private val printer: Printer,
) {
    /** Starts the reader loop on a daemon thread. Returns false if it cannot. */
    fun start(): Boolean {
        val console = ConsoleTerminal.open("archivetune-controls")
        if (console != null) {
            console.startRawMode()
            return startRawLoop(controller, printer, console.keys(), console)
        }
        if (System.`in`.available() == 0 && System.console() == null) return false
        return startLineLoop(controller, printer)
    }

    private fun startRawLoop(
        controller: PlaybackController,
        printer: Printer,
        keys: KeySource,
        console: ConsoleTerminal,
    ): Boolean {
        Thread({
            try {
                printer.echo(printer.dim(KEY_HINTS))
                while (!Thread.currentThread().isInterrupted) {
                    when (val key = keys.read(POLL_MS)) {
                        null, TerminalKey.Unknown -> Unit
                        TerminalKey.CtrlC, TerminalKey.Eof -> {
                            controller.stop()
                            return@Thread
                        }

                        is TerminalKey.Char -> handle(controller, key.value)
                        TerminalKey.Up -> controller.previous()
                        TerminalKey.Down -> controller.next()
                        TerminalKey.Left, TerminalKey.Backspace -> controller.seekBy(-SEEK_STEP_MS)
                        TerminalKey.Right, TerminalKey.Delete -> controller.seekBy(SEEK_STEP_MS)
                        else -> Unit
                    }
                }
            } finally {
                console.close()
            }
        }, "archivetune-controls").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun startLineLoop(controller: PlaybackController, printer: Printer): Boolean {
        Thread({
            try {
                printer.echo(printer.dim(LINE_HINTS))
                val keys = StdinKeySource()
                while (!Thread.currentThread().isInterrupted) {
                    when (val key = keys.read(POLL_MS)) {
                        null, TerminalKey.Unknown -> Unit
                        TerminalKey.Eof -> {
                            controller.stop()
                            return@Thread
                        }

                        is TerminalKey.Char -> handle(controller, key.value)
                        else -> Unit
                    }
                }
            } catch (_: java.io.IOException) {
                controller.stop()
            }
        }, "archivetune-controls").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun handle(controller: PlaybackController, value: kotlin.Char) {
        when (value.lowercaseChar()) {
            'q', 'x' -> controller.stop()
            's' -> controller.stop()
            'n', 'j' -> controller.next()
            'p', 'k' -> controller.previous()
            'r' -> controller.restart()
            ' ' -> controller.togglePause()
            '+', '=' -> controller.volumeBy(VOLUME_STEP)
            '-' -> controller.volumeBy(-VOLUME_STEP)
            '<' -> controller.seekBy(-SEEK_STEP_MS)
            '>' -> controller.seekBy(SEEK_STEP_MS)
            else -> Unit
        }
    }

    private companion object {
        const val POLL_MS = 250L
        const val SEEK_STEP_MS = 10_000L
        const val VOLUME_STEP = 5
        const val KEY_HINTS =
            "keys: [space]pause [n]ext [p]prev [</>]seek [+/-]volume [r]restart [q]quit"
        const val LINE_HINTS =
            "keys (press Enter): [space]pause [n]ext [p]prev [</>]seek [+/-]volume [r]restart [q]quit"
    }
}
