package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.music.CollectionRef
import moe.rukamori.archivetune.cli.music.SearchPageResult
import moe.rukamori.archivetune.cli.music.SearchScope

class SearchCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "search",
        helpText = "Search YouTube Music.",
        epilogText = "Add --play to start playing the results straight away.",
    ) {
        private val query by argument("QUERY", help = "What to search for").multiple()

        private val scope by
            option(
                "-t",
                "--type",
                metavar = "TYPE",
                help = "songs, videos, albums, artists, podcasts or playlists",
            ).enum<SearchScope>()

        private val page by
            option("--page", metavar = "N", help = "Follow continuations N times before showing results")
                .int()
                .default(1)

        private val autoplay by option("--play", help = "Play the results instead of listing them").flag()

        override fun run() {
            val text = query.joinToString(" ").trim()
            if (text.isEmpty()) fail("A search query is required.")
            ctx.requireNetwork()

            val scope = scope ?: SearchScope.ALL
            // `--limit` is a global flag, so it is already in ctx.settings by now.
            val limit = ctx.settings.limit ?: ctx.config.output.pageSize
            val result = blocking { fetch(text, scope, limit) }

            if (autoplay) {
                play(result.songs)
                return
            }

            if (ctx.outputFormat == OutputFormat.JSON) {
                echoJson(text, scope, result)
                return
            }

            val printer = ctx.printer
            val albums = result.collections.filter { it.id.startsWith("MPREb_") }
            val playlists = result.collections.filterNot { it.id.startsWith("MPREb_") }

            if (result.songs.isNotEmpty()) {
                printer.echo(printer.dimBold("Songs"))
                ctx.table.render(result.songs)
                printer.echo()
            }
            if (albums.isNotEmpty()) {
                printer.echo(printer.dimBold("Albums"))
                renderCollections(albums)
                printer.echo()
            }
            if (playlists.isNotEmpty()) {
                printer.echo(printer.dimBold("Playlists"))
                renderCollections(playlists)
                printer.echo()
            }
            if (result.artists.isNotEmpty()) {
                printer.echo(printer.dimBold("Artists"))
                renderCollections(result.artists)
                printer.echo()
            }
            if (result.isEmpty()) {
                printer.echo(printer.dim("No results for \"$text\"."))
                return
            }
            printer.echo(printer.dim("Play these with:  archivetune search \"$text\" --play"))
        }

        /**
         * Continuations are opaque tokens tied to the original query, so paging
         * walks them in-process; each page therefore shows only the newly
         * fetched chunk rather than re-listing everything.
         */
        private suspend fun fetch(text: String, scope: SearchScope, limit: Int): SearchPageResult {
            var current = catalog.search(text, scope, limit).orFail()
            var fetched = 1
            while (fetched < page) {
                val continuation = current.continuation ?: break
                current = catalog.search(text, scope, limit, continuation).orFail()
                fetched++
            }
            return current
        }

        private fun renderCollections(items: List<CollectionRef>) {
            val printer = ctx.printer
            val idWidth = items.maxOfOrNull { it.id.length } ?: 4
            items.forEach { item ->
                val year = item.year?.let { " ($it)" }.orEmpty()
                printer.echo(
                    "  ${printer.gray(item.id.padEnd(idWidth))}  " +
                        printer.bold(item.title + year) + "  " + printer.gray(item.subtitle),
                )
            }
        }

        private fun echoJson(text: String, scope: SearchScope, result: SearchPageResult) {
            val json = Json { prettyPrint = true }
            ctx.printer.echo(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("query", text)
                        put("type", scope.label)
                        put("songs", JsonArray(result.songs.map(::trackJson)))
                        put(
                            "albums",
                            JsonArray(result.collections.filter { it.id.startsWith("MPREb_") }.map(::collectionJson)),
                        )
                        put(
                            "playlists",
                            JsonArray(result.collections.filterNot { it.id.startsWith("MPREb_") }.map(::collectionJson)),
                        )
                        put("artists", JsonArray(result.artists.map(::collectionJson)))
                    },
                ),
            )
        }

        private fun trackJson(track: Track): JsonObject =
            buildJsonObject {
                put("id", track.id)
                put("title", track.title)
                put("artists", JsonArray(track.artists.map { JsonPrimitive(it.name) }))
                track.album?.let { put("album", it.title) }
                put("durationSeconds", track.duration ?: 0)
                put("explicit", track.explicit)
            }

        private fun collectionJson(item: CollectionRef): JsonObject =
            buildJsonObject {
                put("id", item.id)
                put("title", item.title)
                put("subtitle", item.subtitle)
                item.year?.let { put("year", it) }
            }
    }

private fun SearchPageResult.isEmpty(): Boolean =
    songs.isEmpty() && collections.isEmpty() && artists.isEmpty()
