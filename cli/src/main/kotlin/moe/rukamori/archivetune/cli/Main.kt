package moe.rukamori.archivetune.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import moe.rukamori.archivetune.cli.commands.AlbumCommand
import moe.rukamori.archivetune.cli.commands.ArtistCommand
import moe.rukamori.archivetune.cli.commands.AuthCommand
import moe.rukamori.archivetune.cli.commands.CanvasCommand
import moe.rukamori.archivetune.cli.commands.ConfigCommand
import moe.rukamori.archivetune.cli.commands.LyricsCommand
import moe.rukamori.archivetune.cli.commands.PlayCommand
import moe.rukamori.archivetune.cli.commands.PlayerCommand
import moe.rukamori.archivetune.cli.commands.PlaylistCommand
import moe.rukamori.archivetune.cli.commands.RecognizeCommand
import moe.rukamori.archivetune.cli.commands.SearchCommand
import moe.rukamori.archivetune.cli.commands.SongCommand
import moe.rukamori.archivetune.cli.commands.SpotifyCommand
import moe.rukamori.archivetune.cli.commands.TuiCommand
import moe.rukamori.archivetune.cli.commands.launchTui
import moe.rukamori.archivetune.innertube.NetworkGatekeeper

fun main(args: Array<String>) {
    // Before the first logger is created: slf4j reads its level once, and the
    // default has to win over any value baked into the jar.
    Logging.quiet()
    // The gatekeeper ships in a "blocked" state for unofficial Android builds and
    // makes every InnerTube request throw. Nothing does that on the CLI, so clear
    // it before the first request goes out.
    NetworkGatekeeper.setConnectionBlocked(false)
    val ctx = CliContext()
    try {
        // The JVM mangles non-ANSI arguments before main() sees them, so on
        // Windows the command line is read back from the OS instead.
        ArchiveTuneCommand(ctx).main(WindowsArgs.recover(args))
    } finally {
        ctx.shutdown()
    }
}

/**
 * The root command. It owns the flags that every subcommand understands.
 *
 * Clikt finalizes a command's own parameters before it parses a subcommand's,
 * so the global flags are declared here and applied to [GlobalSettings] right
 * away. They are registered on each subcommand too (hidden) so that they work
 * on either side of the subcommand name: `archivetune -f json search x` and
 * `archivetune search x -f json` behave the same.
 */
class ArchiveTuneCommand(private val ctx: CliContext) : CliktCommand(name = "archivetune") {

    // `archivetune` alone (or `archivetune <query>`) opens the full-screen
    // player; only an explicit subcommand name skips run().
    override val invokeWithoutSubcommand = true

    /** A bare, non-command phrase starts the player pre-searched with it. */
    private val query by argument("QUERY", help = "Open the player and search for this").multiple()

    override fun help(context: Context): String =
        """
        ArchiveTune for the terminal - a YouTube Music client that plays audio.

        Run it with no arguments (or just type a search) to open the full-screen
        player:

        Examples:
          archivetune                                open the full-screen player
          archivetune radiohead                      open the player, searched
          archivetune search "radiohead creep"       list matching tracks
          archivetune search "kay hanada" -f json    machine-readable output
          archivetune play "radiohead creep"         play the top search hit
          archivetune play "https://youtu.be/ID"     play a link directly
          archivetune album MPREb_xxxxxxxxxxxx       list an album
          archivetune playlist PLxxxxxxxx --play     play a whole playlist
          archivetune play "daft punk" --player mpv  use the mpv backend
          archivetune lyrics "radiohead creep"       print the lyrics
          archivetune recognize clip.wav             identify a song
          archivetune auth youtube                   one-tap browser login (playback)
          archivetune auth lastfm                    link Last.fm for scrobbling
          archivetune spotify import <url>           import a Spotify playlist
          archivetune canvas "song name"             find animated album art
          archivetune player --probe                 which backends work
          archivetune config path                    show the paths in use
        """.trimIndent()

    init {
        context { helpFormatter = { ArchiveTuneHelpFormatter(it) } }

        registerGlobalOptions(ctx, visible = true)

        subcommands(
            SearchCommand(ctx),
            PlayCommand(ctx),
            SongCommand(ctx),
            AlbumCommand(ctx),
            ArtistCommand(ctx),
            PlaylistCommand(ctx),
            LyricsCommand(ctx),
            RecognizeCommand(ctx),
            CanvasCommand(ctx),
            SpotifyCommand(ctx),
            TuiCommand(ctx),
            PlayerCommand(ctx),
            ConfigCommand(ctx),
            AuthCommand(ctx),
        )
    }

    /**
     * Only reached for a bare `archivetune` or `archivetune <query>`; an
     * explicit subcommand takes over before `run` is useful, and is detected
     * through [currentContext].
     */
    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        launchTui(ctx, query.joinToString(" ").trim())
    }
}
