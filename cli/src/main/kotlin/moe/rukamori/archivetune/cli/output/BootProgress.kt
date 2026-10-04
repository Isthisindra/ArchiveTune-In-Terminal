package moe.rukamori.archivetune.cli.output

import java.io.Writer

/**
 * The pre-TUI startup strip: an animated progress bar on the last line with the
 * log lines scrolling above it, so a slow `sw.js_data` fetch looks busy instead
 * of frozen.
 *
 * The whole block (logs + bar) is redrawn in place on every update; lines are
 * kept short and capped so the block height stays stable and ANSI terminals do
 * not tear. When [enabled] is false (no ANSI, or `--no-progress`) the bar is
 * skipped entirely and the log lines fall through as plain output, keeping
 * piped/CI runs clean.
 */
class BootProgress(
    private val printer: Printer,
    private val out: Writer,
    private val enabled: Boolean,
) {
    private val logs = ArrayDeque<String>()
    private var label = ""
    private var spinnerIndex = 0
    private var ticker: Thread? = null
    private var closed = false

    /** Sets the bar label ("Preparing session", "Searching..."). */
    @Synchronized
    fun status(label: String) {
        this.label = label
        if (enabled && !closed) redraw()
    }

    /** Appends one line below the bar. */
    @Synchronized
    fun log(message: String) {
        logs.addLast(message)
        while (logs.size > MAX_LOG_LINES) logs.removeFirst()
        if (enabled && !closed) {
            redraw()
        } else {
            printer.echo(printer.dim(message))
        }
    }

    /** Starts the spinner animation. Must be paired with [close]. */
    @Synchronized
    fun start() {
        if (!enabled || closed || ticker != null) return
        ticker =
            Thread {
                while (!closed) {
                    try {
                        Thread.sleep(TICK_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    synchronized(this@BootProgress) {
                        if (!closed) redraw()
                    }
                }
            }.apply {
                isDaemon = true
                name = "archivetune-boot"
                start()
            }
    }

    /** Stops the spinner and removes the block, leaving a clean terminal. */
    @Synchronized
    fun close() {
        closed = true
        ticker?.interrupt()
        ticker = null
        if (enabled && (logs.isNotEmpty() || label.isNotBlank())) {
            val rows = blockHeight()
            out.write("\u001b[${rows}A")
            repeat(rows) { out.write("\r\u001b[2K\n") }
            out.write("\r\u001b[2K")
            out.flush()
        }
        logs.clear()
        label = ""
    }

    private fun blockHeight(): Int = if (enabled) logs.size + 1 else 0

    /** Moves to the top of the block, clears it and rewrites logs + bar line. */
    private fun redraw() {
        if (!enabled || closed) return
        val rows = blockHeight()
        out.write("\u001b[${rows}A")
        for (line in logs) {
            out.write("\r\u001b[2K")
            out.write(printer.dim(clip(line)))
            out.write("\n")
        }
        out.write("\r\u001b[2K")
        out.write(buildBar())
        out.flush()
    }

    private fun buildBar(): String {
        if (label.isBlank()) return ""
        val spinner = SPINNER[spinnerIndex % SPINNER.size]
        spinnerIndex++
        val width = 16
        val sweep = (spinnerIndex / SPINNER.size) % (width + 1)
        val bar = (0 until width).joinToString("") { index -> if (index < sweep) "█" else "░" }
        return "$spinner $bar $label"
    }

    private fun clip(line: String): String =
        if (line.length <= MAX_LINE_WIDTH) line
        else line.take(MAX_LINE_WIDTH - 1) + "…"

    private companion object {
        const val MAX_LOG_LINES = 8
        const val MAX_LINE_WIDTH = 78
        const val TICK_MS = 120L
        val SPINNER = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
    }
}