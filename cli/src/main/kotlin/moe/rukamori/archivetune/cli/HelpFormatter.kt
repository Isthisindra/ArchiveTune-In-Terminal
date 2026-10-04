package moe.rukamori.archivetune.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.widgets.Text

/**
 * Clikt's default help formatter word-wraps every description into a single
 * paragraph, which turns an aligned block of examples into one unreadable
 * line. This keeps the newlines the author wrote and only wraps lines that are
 * genuinely too long for the terminal.
 */
class ArchiveTuneHelpFormatter(context: Context) : MordantHelpFormatter(context) {
    override fun renderWrappedText(text: String): Widget =
        Text(text, whitespace = Whitespace.PRE_WRAP)
}
