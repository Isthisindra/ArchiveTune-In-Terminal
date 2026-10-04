package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.YouTubeRefs
import moe.rukamori.archivetune.cli.music.SearchScope
import moe.rukamori.archivetune.cli.playback.PlayOptions

/**
 * The everyday command: give it words or a link and it plays something.
 *
 * A bare video id or URL skips search entirely. Otherwise the query is searched
 * and either the single best song, every song in the result, or the referenced
 * album/playlist is queued depending on what the input points at.
 *
 * A search plays the best match with the rest of the results queued behind it,
 * because YouTube gates some uploads for every client: the queue skips whatever
 * will not resolve and lands on a playable candidate instead of stopping.
 */
class PlayCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "play",
        helpText = "Search for something and play it.",
        epilogText =
            """
            Accepts free text, a YouTube URL, a video id, an album browse id or a playlist id.

            Examples:
              archivetune play radiohead creep
              archivetune play "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
              archivetune play PLxxxxxxxxxxxx
              archivetune play "bohemian rhapsody" --all
            """.trimIndent(),
    ) {
        private val query by argument("QUERY", help = "What to play").multiple()

        private val all by
            option("-a", "--all", help = "Play every matching track, not just the best one").flag()

        private val scope by
            option("-t", "--type", metavar = "TYPE", help = "Search tab to use: songs or videos")
                .enum<SearchScope>()
                .default(SearchScope.SONGS)

        private val startAt by
            option("--start", metavar = "N", help = "Start at track N (1-based) of the queue").int()

        override fun run() {
            val input = query.joinToString(" ").trim()
            if (input.isEmpty()) fail("Nothing to play - pass a search phrase or a URL.")
            ctx.requireNetwork()

            val queue = blocking { resolveQueue(input) }
            if (queue.isEmpty()) fail("No playable tracks for \"$input\".")
            play(queue, PlayOptions(startIndex = (startAt ?: 1).minus(1).coerceAtLeast(0)))
        }

        private suspend fun resolveQueue(input: String): List<Track> {
            YouTubeRefs.videoId(input)?.let { videoId ->
                val track = catalog.song(videoId, ctx.streamClient).orFail()
                val listId = YouTubeRefs.playlistId(input)
                if (listId != null) {
                    // A watch URL carrying `list=` means "play this in context".
                    val fromPlaylist = catalog.playlist(listId).orFail().songs
                    val index = fromPlaylist.indexOfFirst { it.id == track.id }
                    if (index > 0) return fromPlaylist.drop(index)
                }
                return listOf(track)
            }

            YouTubeRefs.playlistId(input)?.let { return catalog.playlist(it).orFail().songs }

            YouTubeRefs.browseId(input)?.let { return catalog.album(it).orFail().songs }

            val result = catalog.search(input, scope).orFail()
            if (all) return result.songs

            pickBest(input, result.songs)?.let { best ->
                // Best match first, the rest of the hits behind it. YouTube
                // bot-gates some uploads for every client, and the queue skips
                // whatever will not resolve, so a gated top hit falls through to
                // a playable candidate instead of ending playback immediately.
                return buildList {
                    add(best)
                    result.songs.filterNot { it.id == best.id }.forEach { add(it) }
                }
            }

            // Nothing matched as a song; fall back to an album so that
            // `archivetune play "pink floyd"` still does something useful.
            val album = result.collections.firstOrNull { it.id.startsWith("MPREb_") }
            return album?.let { catalog.album(it.id).orFail().songs }.orEmpty()
        }

        /**
         * Prefers a title that matches the query, so that `play "creep"` does not
         * start on a ten-minute cover. Falls back to the first result.
         */
        private fun pickBest(input: String, songs: List<Track>): Track? {
            if (songs.isEmpty()) return null
            val needle = input.trim()
            val exact = songs.firstOrNull { it.title.equals(needle, ignoreCase = true) }
            if (exact != null) return exact
            val startsWith = songs.firstOrNull { it.title.startsWith(needle, ignoreCase = true) }
            return startsWith ?: songs.first()
        }
    }
