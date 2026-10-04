package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.Logging
import moe.rukamori.archivetune.cli.lyrics.LyricsProvider
import moe.rukamori.archivetune.cli.lyrics.LyricsService
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.music.CatalogService
import moe.rukamori.archivetune.cli.music.SearchPageResult
import moe.rukamori.archivetune.cli.music.SearchScope
import moe.rukamori.archivetune.cli.output.BootProgress
import moe.rukamori.archivetune.cli.output.ProgressRenderer
import moe.rukamori.archivetune.cli.playback.PlaybackController
import moe.rukamori.archivetune.cli.playback.PlayerEngine
import moe.rukamori.archivetune.cli.playback.ScrobbleGateway
import moe.rukamori.archivetune.cli.terminal.ConsoleTerminal
import moe.rukamori.archivetune.cli.tui.Frame
import moe.rukamori.archivetune.cli.tui.TuiApp
import moe.rukamori.archivetune.cli.tui.TuiView

/**
 * The interactive player.
 *
 * A query is optional, so `archivetune tui radiohead` is a one-word entry point
 * while `archivetune tui` opens on an empty queue and lets `/` do the searching.
 * The root command opens the same player when run bare (see [launchTui]), so
 * `archivetune radiohead` and `archivetune` behave like this command.
 */
class TuiCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "tui",
        helpText = "Open the full-screen player.",
        epilogText = "With no QUERY the queue starts empty; press / to search.",
    ) {

    private val query by argument("QUERY", help = "Search for this and queue the results").multiple()

    private val wait by
        option("--wait", help = "Queue the results without starting playback").flag()

    override fun run() {
        launchTui(ctx, query.joinToString(" ").trim(), wait = wait)
    }
}

/**
 * Opens the full-screen player.
 *
 * Shared by the `tui` subcommand and the root command, which runs bare
 * `archivetune` (and `archivetune <query>`) straight into the player.
 */
internal fun launchTui(ctx: CliContext, query: String, wait: Boolean = false) {
    val console =
        ConsoleTerminal.open("archivetune")
            ?: throw PrintMessage(
                "This terminal cannot be opened for interactive input. " +
                    "Run `archivetune play` for the non-interactive player.",
            )

    // Boot progress: an animated bar with the [session] logs scrolling above
    // it, drawn on the normal screen before the frame takes over. The frame
    // enters the alternate screen, which discards this block cleanly.
    val boot =
        BootProgress(
            printer = ctx.printer,
            out = console.terminal.writer(),
            enabled = ctx.supportsAnsi && !ctx.settings.noProgress,
        )
    boot.start()

    val engine: PlayerEngine
    val tracks: List<Track>
    try {
        boot.status("Preparing session")
        ctx.requireNetwork(onLog = { boot.log(it) })

        boot.status("Preparing player backend")
        engine = ctx.newPlayerEngine()
        if (!engine.isAvailable()) {
            throw PrintMessage(unavailableBackendMessage(engine.name))
        }

        // The frame owns the screen from here on; a stray log line would tear it.
        Logging.silence()

        boot.status(if (query.isBlank()) "Loading player" else "Searching \"$query\"")
        tracks = initialTuiQueue(ctx, query)
    } catch (t: Throwable) {
        console.close()
        throw t
    } finally {
        boot.close()
    }

    console.use { terminal ->
        val app =
            TuiApp(
                console = terminal,
                keys = terminal.keys(),
                frame = Frame(terminal.terminal),
                view = TuiView(ctx.printer),
                printer = ctx.printer,
                controller =
                    PlaybackController(
                        music = ctx.music,
                        engine = engine,
                        printer = ctx.printer,
                        // The TUI paints its own progress bar, so the one-shot
                        // renderer stays off.
                        progress = ProgressRenderer(ctx.printer, supportsInPlace = false),
                        clients = ctx.streamClients,
                        quality = ctx.audioQuality,
                        startVolume = ctx.volume,
                        verbose = ctx.settings.verbose,
                        scrobble =
                            ScrobbleGateway(ctx.config.scrobbling) { message ->
                                ctx.printer.warn(message)
                            },
                    ),
                catalog = CatalogService(),
                backend = engine.name,
                quality = ctx.audioQuality,
                lyrics =
                    LyricsService(
                        paxsenixApiKey = ctx.config.services.paxsenixApiKey,
                        log = { message -> if (ctx.settings.verbose) ctx.printer.dim("  $message") },
                    ),
                lyricsOrder = LyricsProvider.fromIds(ctx.config.lyrics.preferredProviders),
            )
        if (tracks.isNotEmpty()) app.start(tracks, autoplay = !wait)
        app.run()
    }
}

private fun initialTuiQueue(ctx: CliContext, query: String): List<Track> {
    if (query.isEmpty()) return emptyList()
    val limit = ctx.settings.limit ?: DEFAULT_TUI_LIMIT
    val page: SearchPageResult =
        runBlocking { CatalogService().search(query, SearchScope.SONGS, limit) }
            .getOrElse {
                ctx.printer.warn("Could not search \"$query\": ${it.message}")
                SearchPageResult(emptyList(), emptyList(), emptyList(), null)
            }
    return page.songs
}

private const val DEFAULT_TUI_LIMIT = 50