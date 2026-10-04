package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import moe.rukamori.archivetune.canvas.ArchiveTuneCanvas
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.canvas.CanvasService
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.YouTubeRefs
import moe.rukamori.archivetune.cli.music.SearchScope
import moe.rukamori.archivetune.cli.output.Printer
import java.io.File

/**
 * Animated album artwork ("canvas") lookup. Given a song query (or an Apple
 * Music album url) it asks the artwork service for the canvas video, prints
 * the URLs and optionally saves the clip with `--save`.
 */
class CanvasCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "canvas",
        helpText = "Find animated album artwork (canvas) for a song.",
        epilogText =
            "Examples:\n" +
                "  archivetune canvas \"Nightcall - Kavinsky\"\n" +
                "  archivetune canvas 4uLU6hMCjMI75M1A2tKUQC --source BETTER_LYRICS\n" +
                "  archivetune canvas \"Nightcall - Kavinsky\" --save .   (download the clip)\n" +
                "  archivetune canvas --album https://music.apple.com/us/album/outrun/1027078480\n" +
                "Canvas art comes from the artwork.boidu.dev service and Apple Music.",
    ) {
    private val text by argument("QUERY", help = "Song name, video id, or \"Artist - Title\"")
    private val album by option("--album", help = "Fetch by an Apple Music album url instead of a song query")
    private val storefront by option("--storefront", help = "Apple Music storefront code (default us)").default("us")
    private val vertical by option("--vertical", help = "Require a vertical (portrait) canvas video").flag()
    private val source by
        option("--source", help = "Canvas source: ALL, BETTER_LYRICS or APPLE_MUSIC")
            .enum<CanvasSource>()
            .default(CanvasSource.ALL)
    private val save by
        option(
            "--save",
            metavar = "DIR|FILE",
            help = "Download the canvas video to a directory or exact file path",
        )

    override fun run() {
        ctx.requireNetwork()
        val printer = ctx.printer
        val albumUrl = album
        val saveArg = save

        val artwork: CanvasArtwork =
            if (albumUrl != null) {
                blocking { ArchiveTuneCanvas.getByAlbumUrl(albumUrl, source) }
                    ?: fail("No canvas artwork found for album ${albumUrl}.")
            } else {
                val track = blocking { resolveTrack(text) } ?: fail("No track found for \"${text}\". Try \"Artist - Title\".")
                if (ctx.outputFormat != OutputFormat.JSON) {
                    printer.echo(printer.bold(track.title) + printer.dim("  " + track.artistString))
                }
                val artist = track.artists.firstOrNull()?.name ?: track.artistString
                blocking {
                    ArchiveTuneCanvas.getBySongArtist(
                        song = track.title,
                        artist = artist,
                        storefront = storefront,
                        source = source,
                        requireVertical = vertical,
                    )
                } ?: fail(noArtworkHint(track))
            }

        if (saveArg != null) {
            val videoUrl =
                if (vertical) artwork.preferredVerticalAnimationUrl
                else artwork.preferredAnimationUrl
            if (videoUrl.isNullOrBlank()) {
                fail(
                    "No ${if (vertical) "vertical canvas video" else "canvas video"} available " +
                        "for \"${artwork.name ?: text}\". Try again without --save, or use --vertical " +
                        "on a track that actually has one.",
                )
            }
            val dest = CanvasService.saveTarget(saveArg, artwork.name ?: text, artwork.artist)
            saveCanvas(videoUrl, dest, printer)
            return
        }

        if (ctx.outputFormat == OutputFormat.JSON) {
            printer.echo(CanvasService.json(text.takeIf { albumUrl == null }, artwork))
            return
        }
        printArtwork(printer, artwork)
    }

    private fun noArtworkHint(track: Track): String {
        val hint =
            when {
                vertical -> "no vertical canvas found; drop --vertical"
                else -> "try --source BETTER_LYRICS or an --album url"
            }
        return "No canvas artwork found for \"${track.title}\" - ${hint}."
    }

    private fun saveCanvas(url: String, dest: File, printer: Printer) {
        printer.echo(printer.dim("saving ${url}"))
        val bytes =
            runCatching { CanvasService.download(url, dest) }
                .getOrElse { error -> fail("Could not save ${dest.path}: ${error.message}") }
        printer.success("Saved ${dest.path} (${formatBytes(bytes)})")
        if (url.endsWith(".m3u8", ignoreCase = true)) {
            printer.echo(
                printer.dim("That's an HLS playlist, not a full video. Re-encode it with:"),
            )
            printer.echo(printer.dim("  ffmpeg -i \"$url\" -c copy \"out.mp4\""))
        }
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

    private fun printArtwork(printer: Printer, artwork: CanvasArtwork) {
        printer.echo(printer.dimBold(artwork.source?.name ?: "ALL"))
        artRow(printer, "name", artwork.name)
        artRow(printer, "artist", artwork.artist)
        artRow(printer, "album", artwork.albumName)
        artRow(printer, "albumId", artwork.albumId)
        artRow(printer, "image", artwork.static)
        artRow(printer, "video", artwork.preferredAnimationUrl)
        artRow(printer, "videoVertical", artwork.preferredVerticalAnimationUrl)
        artwork.preferredAnimationUrl?.let {
            printer.echo()
            val name = artwork.name.orEmpty()
            printer.echo(printer.dim("Save the clip with:  archivetune canvas \"$name\" --save ."))
        }
    }

    private fun artRow(
        printer: Printer,
        label: String,
        value: String?,
    ) {
        if (value != null) {
            printer.echo("  ${printer.gray(label)}  $value")
        }
    }

    /**
     * A video id resolves exactly; anything else is a song search. Like lyrics,
     * `Artist - Title` falls back to searching the title alone when the full
     * string finds nothing.
     */
    private suspend fun resolveTrack(text: String): Track? {
        YouTubeRefs.videoId(text)?.let { id ->
            catalog.song(id, ctx.streamClient).getOrNull()?.let { return it }
        }
        catalog.search(text, SearchScope.SONGS, limit = 5).getOrNull()?.songs?.firstOrNull()?.let {
            return it
        }
        val split = text.split(" - ", limit = 2)
        if (split.size == 2) {
            val candidates = catalog.search(split[1].trim(), SearchScope.SONGS, limit = 5).getOrNull()?.songs
            if (!candidates.isNullOrEmpty()) {
                val artist = split[0].trim()
                return candidates.firstOrNull { it.artistString.contains(artist, ignoreCase = true) }
                    ?: candidates.first()
            }
        }
        return null
    }
}