package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.auth.BrowserLogin
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.spotify.SpotifyImportService
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyUser

/**
 * Spotify integration: sign in with session cookies and import playlists (or
 * liked songs) as playable YouTube Music tracks.
 *
 * The playlists stay on Spotify; `import`/`liked` resolve every track through
 * YouTube Music search so they play in this player, and an optional `--create`
 * pushes the matched tracks into a fresh YouTube Music playlist instead.
 */
class SpotifyCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "spotify",
        helpText = "Import tracks and playlists from Spotify.",
    ) {
        init {
            subcommands(AuthCommand(ctx), ImportCommand(ctx), LikedCommand(ctx), PlaylistsCommand(ctx))
        }

        override fun run() = Unit

        private class AuthCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "auth",
                helpText = "Log in with a Spotify account and verify the session.",
                epilogText =
                    "By default a browser window opens and you sign in like you " +
                        "normally would - the sp_dc / sp_key cookies are captured " +
                        "automatically. To log in without the browser, pass --sp-dc.",
            ) {
            private val spDc by option("--sp-dc", help = "The sp_dc cookie from a logged-in open.spotify.com session (skips the browser)")
            private val spKey by option("--sp-key", help = "The sp_key cookie (optional)")

            override fun run() {
                val printer = ctx.printer
                val cookie = spDc
                val keyOption = spKey
                val pair: Pair<String, String?> =
                    if (cookie != null) {
                        cookie to (keyOption ?: ctx.config.services.spotifySpKey)
                    } else {
                        printer.echo("Opening a browser to link your Spotify account...")
                        val login =
                            BrowserLogin.loginSpotify(log = { line -> printer.echo(printer.dim(line)) })
                                ?: fail(
                                    "No login was completed. Make sure Edge/Chrome is installed, " +
                                        "or paste the sp_dc cookie manually with --sp-dc.",
                                )
                        login.first to
                            (login.second.takeIf(String::isNotBlank) ?: ctx.config.services.spotifySpKey)
                    }
                val (dc, key) = pair

                val user = blocking { connectSpotify(ctx, dc, key ?: "") }

                val services = ctx.config.services.copy(spotifySpDc = dc, spotifySpKey = key?.takeIf(String::isNotBlank))
                ctx.saveConfig(ctx.config.copy(services = services))
                printer.echo(
                    "${printer.successLabel("linked")} Spotify as ${printer.bold(user.displayName ?: user.id)}",
                )
                printer.echo(printer.dim("Now try:  archivetune spotify import https://open.spotify.com/playlist/..."))
            }
        }

        private class ImportCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "import",
                helpText = "Resolve a Spotify playlist into YouTube Music tracks.",
                epilogText =
                    "Examples:\n" +
                        "  archivetune spotify import https://open.spotify.com/playlist/37i9dQ...\n" +
                        "  archivetune spotify import 37i9dQZF1DXcBWIGoYBM5M --play\n" +
                        "  archivetune spotify import 37i9dQZF1DXcBWIGoYBM5M --create\n" +
                        "Add --create to build a YouTube Music playlist from the matched tracks\n" +
                        "(needs archivetune auth youtube first).",
            ) {
            private val target by argument("PLAYLIST", help = "A Spotify playlist URL, spotify:playlist:ID, or just the ID")
            private val create by option("--create", help = "Create a YouTube Music playlist from the matched tracks").flag()
            private val name by option("--name", metavar = "NAME", help = "Playlist name (defaults to the Spotify playlist's name)")
            private val playNow by option("--play", help = "Play the imported tracks instead of listing them").flag()

            override fun run() {
                val printer = ctx.printer
                val id = parsePlaylistId(target) ?: fail("Not a Spotify playlist: $target")

                val (playlist, fetched) =
                    blocking {
                        prepareSpotify(ctx)
                        val playlist = Spotify.playlist(id).orFail()
                        playlist to
                            fetchTracks(ctx) { offset ->
                                Spotify.playlistTracks(id, SpotifyImportService.PAGE_SIZE, offset)
                                    .orFail()
                                    .items
                                    .mapNotNull { it.track }
                            }
                    }
                if (fetched.isEmpty()) fail("That playlist has no playable tracks.")

                ctx.requireNetwork()
                val resolved = blocking { resolveAll(ctx, fetched, printer) }

                when {
                    create -> createYtPlaylist(printer, playlist.name, resolved)
                    playNow -> {
                        if (resolved.isEmpty()) fail("None of the tracks could be matched on YouTube Music.")
                        play(resolved)
                    }
                    else -> renderTracks(ctx, "spotify import $id", playlist.name, resolved)
                }
            }
        }

        private class LikedCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "liked",
                helpText = "Import your Spotify liked songs as YouTube Music tracks.",
                epilogText = "Shows a table by default; add --play or --create like `spotify import`.",
            ) {
            private val create by option("--create", help = "Create a YouTube Music playlist from the matched tracks").flag()
            private val name by option("--name", metavar = "NAME", help = "Playlist name (defaults to \"Liked from Spotify\")")
            private val playNow by option("--play", help = "Play the imported tracks instead of listing them").flag()

            override fun run() {
                val printer = ctx.printer
                val fetched =
                    blocking {
                        prepareSpotify(ctx)
                        fetchTracks(ctx) { offset ->
                            Spotify.likedSongs(SpotifyImportService.PAGE_SIZE, offset)
                                .orFail()
                                .items
                                .mapNotNull { it.track }
                        }
                    }
                if (fetched.isEmpty()) fail("No liked songs found on this account.")

                ctx.requireNetwork()
                val resolved = blocking { resolveAll(ctx, fetched, printer) }

                when {
                    create -> createYtPlaylist(printer, name ?: "Liked from Spotify", resolved)
                    playNow -> {
                        if (resolved.isEmpty()) fail("None of the liked songs could be matched on YouTube Music.")
                        play(resolved)
                    }
                    else -> renderTracks(ctx, "spotify liked", "Liked from Spotify", resolved)
                }
            }
        }

        private class PlaylistsCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "playlists",
                helpText = "List the playlists on your Spotify account.",
            ) {
            override fun run() {
                val printer = ctx.printer
                val want = ctx.settings.limit ?: 50
                val fetched =
                    blocking {
                        prepareSpotify(ctx)
                        fetchPages({ offset -> Spotify.myPlaylists(SpotifyImportService.PAGE_SIZE, offset).orFail().items }, want)
                    }
                if (fetched.isEmpty()) {
                    printer.echo(printer.dim("No playlists found. --limit raises the page size."))
                    return
                }
                val idWidth = (fetched.maxOfOrNull { it.id.length } ?: 0).coerceAtLeast(4)
                fetched.forEach { playlist ->
                    val tracks = playlist.tracks?.total?.let { " ($it tracks)" }.orEmpty()
                    val owner = playlist.owner?.displayName?.let { " by ${it}" }.orEmpty()
                    printer.echo(
                        "  ${printer.gray(playlist.id.padEnd(idWidth))}  " +
                            printer.bold(playlist.name) + printer.dim("$owner$tracks"),
                    )
                }
                val example = fetched.first().id
                printer.echo()
                printer.echo(printer.dim("Import one with:  archivetune spotify import $example"))
            }
        }
    }

/** A clean CLI error, same shape as BaseCommand.fail but from shared helpers. */
private fun spotifyFail(message: String): Nothing = throw PrintMessage(message)

/** Configures the Spotify session from the saved cookies, with a sign-in hint. */
private suspend fun connectSpotify(ctx: CliContext, spDc: String, spKey: String): SpotifyUser {
    if (spDc.isBlank()) {
        spotifyFail(
            "No Spotify cookies saved. Run archivetune spotify auth or " +
                "`config set services.spotifyspdc <sp_dc cookie>` first.",
        )
    }
    return SpotifyImportService.connect(spDc, spKey).getOrElse { error ->
        spotifyFail(
            "Spotify session failed: ${error.message ?: "request failed"}. " +
                "The sp_dc cookie usually expires; re-run archivetune spotify auth.",
        )
    }
}

/** Ensures the saved cookies make a working session before importing. */
private suspend fun prepareSpotify(ctx: CliContext) {
    connectSpotify(ctx, ctx.config.services.spotifySpDc.orEmpty(), ctx.config.services.spotifySpKey.orEmpty())
}

/** Pages through a track endpoint until enough tracks, or all of them, are fetched. */
private suspend fun <T> fetchPages(page: suspend (offset: Int) -> List<T>, want: Int): List<T> {
    val fetched = mutableListOf<T>()
    var offset = 0
    while (fetched.size < want) {
        val items = page(offset)
        if (items.isEmpty()) break
        fetched += items
        offset += items.size
    }
    return fetched
}

/** Pages a track endpoint, honouring the global `--limit` (default 100). */
private suspend fun fetchTracks(ctx: CliContext, page: suspend (offset: Int) -> List<SpotifyTrack>): List<SpotifyTrack> =
    fetchPages(page, ctx.settings.limit ?: 100)

private fun parsePlaylistId(target: String): String? = SpotifyImportService.parsePlaylistId(target)

/** Resolves Spotify tracks against YouTube Music, with a live progress line. */
private suspend fun resolveAll(ctx: CliContext, tracks: List<SpotifyTrack>, printer: Printer): List<Track> {
    val progress = tracks.size > 1
    if (progress) System.err.print("resolving ${tracks.size} tracks ...")
    val resolved = mutableListOf<Track>()
    var missed = 0
    tracks.forEachIndexed { index, track ->
        if (progress) {
            System.err.print("\rresolved $index/${tracks.size}  ${track.name.take(24)}")
        }
        val match = SpotifyImportService.resolveTrack(track)
        if (match != null) resolved += match else missed++
    }
    if (progress) System.err.println()
    printer.echo(
        "${printer.successLabel("matched")} ${resolved.size} of ${tracks.size} tracks on YouTube Music" +
            if (missed > 0) " ${printer.dim("($missed skipped)")}" else "",
    )
    return resolved
}

/** Pushes the matched tracks into a fresh YouTube Music playlist. */
private fun createYtPlaylist(printer: Printer, title: String, resolved: List<Track>) {
    if (resolved.isEmpty()) spotifyFail("Nothing to add: no tracks were matched on YouTube Music.")
    printer.echo(printer.dim("creating \"$title\" ..."))
    val playlistId =
        blocking {
            YouTube.createPlaylist(title).getOrElse { error ->
                spotifyFail(
                    "Could not create the playlist: ${error.message ?: "request failed"}. " +
                        "This needs a logged-in account: run archivetune auth youtube first.",
                )
            }
        }
    val added =
        blocking { YouTube.addSongsToPlaylist(playlistId, resolved.map { it.id }) }
            .getOrElse { error -> spotifyFail("Could not add the tracks: ${error.message ?: "request failed"}") }
    printer.echo(
        "${printer.successLabel("created")} \"$title\" with ${added.count { it != null }} of ${resolved.size} tracks",
    )
    printer.echo("  https://music.youtube.com/playlist?list=$playlistId")
}

private fun renderTracks(ctx: CliContext, replayHint: String, playlistTitle: String?, resolved: List<Track>) {
    val printer = ctx.printer
    if (ctx.outputFormat == OutputFormat.JSON) {
        val json = Json { prettyPrint = true }
        ctx.printer.echo(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    playlistTitle?.let { put("playlist", it) }
                    put("matched", resolved.size)
                    put("tracks", JsonArray(resolved.map(::spotifyTrackJson)))
                },
            ),
        )
        return
    }
    if (resolved.isEmpty()) {
        printer.echo(printer.dim("No tracks could be matched on YouTube Music."))
        return
    }
    ctx.table.render(resolved)
    printer.echo(printer.dim("Play these with:  archivetune $replayHint --play"))
}

private fun spotifyTrackJson(track: Track): JsonObject =
    buildJsonObject {
        put("id", track.id)
        put("title", track.title)
        put("artists", JsonArray(track.artists.map { JsonPrimitive(it.name) }))
        track.album?.let { put("album", it.title) }
        put("durationSeconds", track.duration ?: 0)
        put("explicit", track.explicit)
    }

/** Routing bookmark so `blocking` (a BaseCommand member) is callable top-level. */
private fun <T> blocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }