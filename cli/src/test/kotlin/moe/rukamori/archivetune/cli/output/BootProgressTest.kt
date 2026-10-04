package moe.rukamori.archivetune.cli.output

import com.github.ajalt.mordant.terminal.Terminal
import moe.rukamori.archivetune.cli.config.OutputFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class BootProgressTest {

    private val printer = Printer(Terminal(), OutputFormat.TABLE, colorEnabled = false)

    @Test
    fun `the bar and its logs share one in-place block`() {
        val out = StringWriter()
        val boot = BootProgress(printer, out, enabled = true)

        boot.status("Preparing session")
        assertTrue(out.toString().contains("Preparing session"))

        boot.log("[session] no visitorData configured; fetching one")
        val afterLog = out.toString()
        assertTrue(afterLog.contains("[session] no visitorData configured; fetching one"))
        // Redraw moved up past the log line + bar before rewriting them.
        assertTrue(afterLog.contains("\u001b[2A"))

        boot.close()
        assertTrue(out.toString().contains("\u001b[2A\r\u001b[2K"))
    }

    @Test
    fun `log lines are capped so the block height stays bounded`() {
        val out = StringWriter()
        val boot = BootProgress(printer, out, enabled = true)

        repeat(12) { boot.log("line$it") }

        // 8 log lines + the bar line, capped at 9 rows - never 13.
        assertTrue(out.toString().contains("\u001b[9A"))
        assertFalse(out.toString().contains("\u001b[13A"))
    }

    @Test
    fun `closing a status-only block clears just two rows`() {
        val out = StringWriter()
        val boot = BootProgress(printer, out, enabled = true)
        boot.status("Preparing player backend")
        boot.log("ready")
        boot.close()
        // The final cleanup moves up 2 rows (log + bar) and clears each.
        assertTrue(out.toString().contains("\u001b[2A\r\u001b[2K\n\r\u001b[2K\n\r\u001b[2K"))
    }

    @Test
    fun `disabled mode never writes ansi to the writer`() {
        val out = StringWriter()
        val boot = BootProgress(printer, out, enabled = false)

        boot.start()
        boot.status("Preparing session")
        boot.log("[session] fetching one")
        boot.close()

        assertFalse(out.toString().contains("\u001b["))
        assertFalse(out.toString().contains("Preparing"))
    }
}