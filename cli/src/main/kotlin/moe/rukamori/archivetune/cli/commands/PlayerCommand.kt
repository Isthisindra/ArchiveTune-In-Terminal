package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.config.PlayerBackend

/**
 * Diagnostics for the audio side of things: which backend is configured, which
 * one actually loaded, and where the CLI keeps its state.
 */
class PlayerCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "player",
        helpText = "Show which audio backends are available.",
    ) {
        private val probe by
            option("--probe", help = "Actually load each backend to confirm it works").flag()

        override fun run() {
            val printer = ctx.printer
            val selected = ctx.settings.player ?: ctx.config.playback.backend

            printer.echo(printer.bold("ArchiveTune CLI"))
            printer.echo("  config        ${ctx.resolvedConfigPath}")
            printer.echo("  cache         ${CliContext.cacheDir}")
            printer.echo("  downloads     ${CliContext.downloadDir}")
            printer.echo("  terminal      ${ctx.terminal.terminalInfo.ansiLevel} (interactive: ${ctx.interactive})")
            printer.echo()
            printer.echo(printer.dimBold("Backends"))
            PlayerBackend.entries.forEach { backend ->
                val marked = if (backend == selected) "*" else " "
                val engine = ctx.newPlayerEngineFor(backend)
                val state =
                    if (probe) {
                        runCatching { engine.isAvailable() }
                            .fold(
                                onSuccess = { if (it) "loaded" else "not installed" },
                                onFailure = { "error: ${it.message}" },
                            )
                    } else {
                        "not probed"
                    }
                printer.echo("  $marked ${backend.name.lowercase().padEnd(4)} $state")
            }
            printer.echo()
            printer.echo(printer.dim("Run with --probe to load the backends and check for VLC/mpv."))
        }
    }
