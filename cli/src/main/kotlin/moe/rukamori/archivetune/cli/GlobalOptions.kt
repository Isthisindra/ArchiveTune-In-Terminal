package moe.rukamori.archivetune.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.config.PlayerBackend
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The flags that apply to every command.
 *
 * They are registered on the root command, where they show up in
 * `archivetune --help`, and again on every subcommand, where they are hidden
 * from the help output but still accepted. Clikt stops handing tokens to the
 * root as soon as it sees a subcommand name, so without the second
 * registration `archivetune search x -f json` would fail with "no such
 * option" while `archivetune -f json search x` worked.
 *
 * Every one of them is `eager`. Clikt finalizes the eager options of the whole
 * command tree before running anything, so the settings are final before the
 * first `run()` looks at them.
 *
 * @param visible false keeps them out of this command's help output.
 */
fun CliktCommand.registerGlobalOptions(ctx: CliContext, visible: Boolean = false) {
    val settings = ctx.settings
    val hidden = !visible

    // --- options that carry a value ----------------------------------------

    eagerValue(
        names = listOf("--config"),
        help = "Use an alternative config file or directory",
        metavar = "PATH",
        hidden = hidden,
        typed = { path() },
        assign = { settings.configFile = it },
    )

    eagerValue(
        names = listOf("-f", "--format"),
        help = "Output format: table, json or plain",
        metavar = "FORMAT",
        hidden = hidden,
        typed = { enum<OutputFormat>() },
        assign = { settings.format = it },
    )

    eagerValue(
        names = listOf("--player"),
        help = "Audio backend: vlc or mpv",
        metavar = "BACKEND",
        hidden = hidden,
        typed = { enum<PlayerBackend>() },
        assign = { settings.player = it },
    )

    eagerValue(
        names = listOf("-q", "--quality"),
        help = "Stream quality: auto, low, high or highest",
        metavar = "QUALITY",
        hidden = hidden,
        typed = { enum<AudioQuality>() },
        assign = { settings.quality = it },
    )

    eagerValue(
        names = listOf("--limit"),
        help = "How many results to show",
        metavar = "N",
        hidden = hidden,
        typed = { int() },
        assign = { settings.limit = it },
    )

    eagerValue(
        names = listOf("--volume"),
        help = "Start playback at this volume (0-100)",
        metavar = "N",
        hidden = hidden,
        typed = { int() },
        assign = { settings.volume = it },
    )

    eagerValue(
        names = listOf("--crossfade"),
        help = "Crossfade length in seconds",
        metavar = "SECONDS",
        hidden = hidden,
        typed = { int() },
        assign = { settings.crossfadeSeconds = it },
    )

    // --- flags -------------------------------------------------------------

    eagerOption(names = listOf("--color"), help = "Force ANSI colour on", hidden = hidden) {
        settings.color = true
    }

    eagerOption(names = listOf("--no-color"), help = "Disable ANSI colour", hidden = hidden) {
        settings.color = false
    }

    eagerOption(names = listOf("--shuffle"), help = "Shuffle the queue", hidden = hidden) {
        settings.shuffle = true
    }

    eagerOption(names = listOf("--repeat"), help = "Repeat the queue", hidden = hidden) {
        settings.repeat = true
    }

    eagerOption(
        names = listOf("--no-progress"),
        help = "Never draw the in-place progress bar",
        hidden = hidden,
    ) { settings.noProgress = true }

    eagerOption(
        names = listOf("-y", "--yes", "--non-interactive"),
        help = "Never read control keys from the console",
        hidden = hidden,
    ) { settings.interactive = false }

    eagerOption(names = listOf("-v", "--verbose"), help = "Log stream resolution details", hidden = hidden) {
        settings.verbose = true
        Logging.verbose()
    }
}

/**
 * Declares an eager option whose parsed value goes straight into the shared
 * settings instead of being exposed as a command property.
 *
 * Clikt has no "write into my object" hook for regular options - the closest
 * thing is [validate] on an `eager` option, whose callback only runs when the
 * option was actually given. [typed] is where the raw string becomes a typed
 * value (`int()`, `enum<OutputFormat>()`, ...); [assign] is what stores it.
 */
private fun <T : Any> CliktCommand.eagerValue(
    names: List<String>,
    help: String,
    metavar: String,
    hidden: Boolean,
    typed: NullableOption<String, String>.() -> NullableOption<T, T>,
    assign: (T) -> Unit,
) {
    registerOption(
        option(
            *names.toTypedArray(),
            help = help,
            metavar = metavar,
            hidden = hidden,
            eager = true,
        ).typed().validate { assign(it) },
    )
}

/** Clikt has no built-in filesystem path type, so here is one. */
private fun RawOption.path(): NullableOption<Path, Path> = convert { Paths.get(it) }
