package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.model.YouTubeRefs
import moe.rukamori.archivetune.cli.playback.PlayOptions

class AlbumCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "album",
        helpText = "List or play an album.",
        epilogText = "Example:  archivetune album MPREb_abcdefghijkl --play",
    ) {
        private val reference by argument("ALBUM_ID_OR_URL", help = "Album browse id or a music.youtube.com URL")

        private val autoplay by option("--play", help = "Play the album instead of listing it").flag()
        private val startAt by option("--start", metavar = "N", help = "Start at track N (1-based)").int().default(1)

        override fun run() {
            val browseId =
                YouTubeRefs.browseId(reference)
                    ?: YouTubeRefs.playlistId(reference)?.takeIf { it.startsWith("OLAK5uy_") }
                    ?: fail("\"$reference\" is not an album browse id or a YouTube Music album URL.")

            ctx.requireNetwork()
            val album = blocking { catalog.album(browseId).orFail() }
            if (album.songs.isEmpty()) fail("No tracks found for $browseId.")

            if (autoplay) {
                play(album.songs, PlayOptions(startIndex = startAt - 1))
                return
            }

            val printer = ctx.printer
            printer.echo(
                printer.bold(album.album.title) + "  " +
                    printer.gray(
                        listOfNotNull(
                            album.album.subtitle.takeIf { it.isNotBlank() },
                            album.album.year?.toString(),
                        ).joinToString(" · "),
                    ),
            )
            printer.echo(printer.gray("  ${album.album.id}"))
            printer.echo()
            ctx.table.render(album.songs)
            printer.echo()
            printer.echo(printer.dim("Play it with:  archivetune album ${album.album.id} --play"))
            if (album.otherVersions.isNotEmpty()) {
                printer.echo()
                printer.echo(printer.dimBold("Other versions"))
                album.otherVersions.forEach { other ->
                    printer.echo("  ${printer.gray(other.id)}  ${other.title}  ${printer.gray(other.subtitle)}")
                }
            }
        }
    }

class ArtistCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "artist",
        helpText = "List or play an artist's music.",
        epilogText = "Example:  archivetune artist UCabc12345678 --play",
    ) {
        private val reference by argument("ARTIST_ID_OR_URL", help = "Channel id or a music.youtube.com URL")

        private val autoplay by option("--play", help = "Play the artist's songs instead of listing them").flag()

        override fun run() {
            val browseId =
                YouTubeRefs.browseId(reference)
                    ?: fail("\"$reference\" is not an artist channel id or a YouTube Music URL.")

            ctx.requireNetwork()
            val limit = ctx.settings.limit ?: ctx.config.output.pageSize * 3
            val artist = blocking { catalog.artist(browseId, limit).orFail() }
            val printer = ctx.printer

            printer.echo(printer.bold(artist.artist.title))
            if (artist.artist.subtitle.isNotBlank()) printer.echo(printer.gray(artist.artist.subtitle))
            printer.echo(printer.gray("  ${artist.artist.id}"))
            artist.description?.takeIf { it.isNotBlank() }?.let {
                printer.echo()
                printer.echo(printer.dim(it.lines().take(6).joinToString("\n")))
            }
            printer.echo()

            if (artist.songs.isEmpty() && artist.albums.isEmpty() && artist.playlists.isEmpty()) {
                printer.echo(printer.dim("Nothing found for this artist."))
                return
            }

            if (artist.songs.isNotEmpty()) {
                printer.echo(printer.dimBold("Songs"))
                ctx.table.render(artist.songs)
                printer.echo()
            }
            if (artist.albums.isNotEmpty()) {
                printer.echo(printer.dimBold("Albums"))
                renderRefs(artist.albums)
                printer.echo()
            }
            if (artist.playlists.isNotEmpty()) {
                printer.echo(printer.dimBold("Playlists"))
                renderRefs(artist.playlists)
                printer.echo()
            }
            if (artist.relatedArtists.isNotEmpty()) {
                printer.echo(printer.dimBold("Fans also like"))
                renderRefs(artist.relatedArtists)
                printer.echo()
            }

            if (autoplay && artist.songs.isNotEmpty()) {
                play(artist.songs)
            } else if (artist.songs.isNotEmpty()) {
                printer.echo(printer.dim("Play these with:  archivetune artist ${artist.artist.id} --play"))
            }
        }

        private fun renderRefs(items: List<moe.rukamori.archivetune.cli.music.CollectionRef>) {
            val printer = ctx.printer
            val idWidth = items.maxOfOrNull { it.id.length } ?: 4
            items.forEach { item ->
                printer.echo(
                    "  ${printer.gray(item.id.padEnd(idWidth))}  " +
                        printer.bold(item.title) + "  " + printer.gray(item.subtitle),
                )
            }
        }
    }

class PlaylistCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "playlist",
        helpText = "List or play a playlist.",
        epilogText = "Example:  archivetune playlist PLxxxxxxxxxxxx --play",
    ) {
        private val reference by argument("PLAYLIST_ID_OR_URL", help = "Playlist id or a YouTube URL")

        private val autoplay by option("--play", help = "Play the playlist instead of listing it").flag()
        private val startAt by option("--start", metavar = "N", help = "Start at track N (1-based)").int().default(1)

        override fun run() {
            val playlistId =
                YouTubeRefs.playlistId(reference)
                    ?: fail("\"$reference\" is not a playlist id or a YouTube URL.")

            ctx.requireNetwork()
            val playlist = blocking { catalog.playlist(playlistId).orFail() }
            if (playlist.songs.isEmpty()) fail("No tracks found for $playlistId.")

            if (autoplay) {
                play(playlist.songs, PlayOptions(startIndex = startAt - 1))
                return
            }

            val printer = ctx.printer
            printer.echo(printer.bold(playlist.playlist.title))
            listOfNotNull(playlist.author, playlist.playlist.subtitle.takeIf { it.isNotBlank() })
                .takeIf { it.isNotEmpty() }
                ?.let { printer.echo(printer.gray(it.joinToString(" · "))) }
            printer.echo(printer.gray("  ${playlist.playlist.id}  ·  ${playlist.songs.size} tracks"))
            printer.echo()
            ctx.table.render(playlist.songs)
            printer.echo()
            printer.echo(printer.dim("Play it with:  archivetune playlist ${playlist.playlist.id} --play"))
        }
    }
