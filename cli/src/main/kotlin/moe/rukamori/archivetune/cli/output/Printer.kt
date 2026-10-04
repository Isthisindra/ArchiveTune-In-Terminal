package moe.rukamori.archivetune.cli.output

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.Terminal
import moe.rukamori.archivetune.cli.config.OutputFormat

/**
 * Central place for writing styled output.
 *
 * Mordant 3 splits styling in two: [TextColors] are [TextStyle] values, while
 * attributes such as bold/dim live in the `TextStyles` enum and are combined
 * with `+`. Building the styles once here keeps the rest of the CLI free of
 * styling details, and when colour is disabled every helper degrades to the raw
 * text so piped output stays clean.
 */
class Printer(
    private val terminal: Terminal,
    val format: OutputFormat,
    private val colorEnabled: Boolean = true,
) {
    private fun apply(style: TextStyle, text: String): String =
        if (colorEnabled) style.invoke(text) else text

    fun bold(text: String): String = apply(BOLD, text)

    fun dim(text: String): String = apply(DIM, text)

    fun red(text: String): String = apply(TextColors.red, text)

    fun green(text: String): String = apply(TextColors.green, text)

    fun yellow(text: String): String = apply(TextColors.yellow, text)

    fun blue(text: String): String = apply(TextColors.blue, text)

    fun cyan(text: String): String = apply(TextColors.cyan, text)

    fun magenta(text: String): String = apply(TextColors.magenta, text)

    fun gray(text: String): String = apply(TextColors.gray, text)

    fun dimBold(text: String): String = apply(DIM_BOLD, text)

    fun errorLabel(text: String): String = apply(TextColors.red + TextStyle(bold = true), text)

    fun successLabel(text: String): String = apply(TextColors.green + TextStyle(bold = true), text)

    fun warnLabel(text: String): String = apply(TextColors.yellow + TextStyle(bold = true), text)

    fun infoLabel(text: String): String = apply(TextColors.blue + TextStyle(bold = true), text)

    /** Accent used for the currently playing track in lists. */
    fun accent(text: String): String = apply(TextColors.cyan + TextStyle(bold = true), text)

    fun echo(message: String = "") = terminal.println(message)

    fun info(message: String) = terminal.println(infoLabel(">") + " " + message)

    fun success(message: String) = terminal.println(successLabel("OK") + " " + message)

    fun warn(message: String) = terminal.println(warnLabel("!") + " " + message)

    fun error(message: String) = terminal.println(errorLabel("error: ") + message)

    fun print(message: String) = terminal.print(message)

    /** Renders a value as JSON only in JSON mode, otherwise returns null. */
    fun <T> json(value: T, encoder: (T) -> String): String? =
        if (format == OutputFormat.JSON) encoder(value) else null

    private companion object {
        val BOLD: TextStyle = TextStyle(bold = true)
        val DIM: TextStyle = TextStyle(dim = true)
        val DIM_BOLD: TextStyle = TextStyle(bold = true, dim = true)
    }
}
