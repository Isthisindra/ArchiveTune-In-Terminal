package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.lyrics.LyricsHit
import moe.rukamori.archivetune.cli.lyrics.LyricsProvider
import moe.rukamori.archivetune.cli.lyrics.LyricsService
import moe.rukamori.archivetune.cli.lyrics.LyricsText
import moe.rukamori.archivetune.cli.lyrics.PaxsenixSource
import moe.rukamori.archivetune.cli.lyrics.Romanizer
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.YouTubeRefs
import moe.rukamori.archivetune.cli.music.SearchScope
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText

/** How the lyrics are printed. */
enum class LyricsRender {
    /** Just the words, one line per lyric line. */
    TEXT,

    /** A line-synced `.lrc` document, converted from TTML/QRC if needed. */
    LRC,

    /** Everything the provider sent, byte for byte. */
    RAW,
}

/**
 * Prints lyrics for a track.
 *
 * The query is resolved the way `play` resolves it - a link or video id is
 * looked up directly, anything else is searched - because two of the seven
 * providers key off the YouTube id rather than the title.
 */
class LyricsCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "lyrics",
        helpText = "Print lyrics for a track.",
        epilogText =
            """
            Providers are tried in the order given by lyrics.preferredProviders
            and the first usable answer wins. --all asks every one of them.

            Examples:
              archivetune lyrics "radiohead creep"
              archivetune lyrics "dank druk" --provider lrclib --as lrc
              archivetune lyrics "ヨルシカ 夜に駆ける" --romanize
              archivetune lyrics "https://youtu.be/ID" --all --save creep.lrc
            """.trimIndent(),
    ) {
        private val query by argument("QUERY", help = "Search text, a video id or a YouTube link").multiple()

        private val providerIds by
            option("-p", "--provider", metavar = "NAME", help = "Provider to use; repeatable")
                .multiple()

        private val askAll by option("--all", help = "Ask every provider instead of stopping at the first hit").flag()

        private val render by
            option("--as", metavar = "KIND", help = "Print as text, lrc or raw")
                .enum<LyricsRender>(ignoreCase = true)
                .default(LyricsRender.TEXT)

        private val romanize by option("--romanize", help = "Add a romanized line under each lyric line").flag()

        private val showProviders by option("--list-providers", help = "List the providers and exit").flag()

        private val saveTo by option("-o", "--save", metavar = "PATH", help = "Also write the lyrics to a file").asPath()

        private val paxsenixSource by
            option(
                "--paxsenix-source",
                metavar = "SOURCE",
                help = "Which paxsenix upstream to use: lrcget, musixmatch, apple or spotify",
            ).enum<PaxsenixSource>(ignoreCase = true)

        override fun run() {
            if (showProviders) {
                printProviders()
                return
            }

            val text = query.joinToString(" ").trim()
            if (text.isEmpty()) fail("A search query, video id or link is required.")

            val order =
                if (providerIds.isEmpty()) {
                    LyricsProvider.fromIds(ctx.config.lyrics.preferredProviders)
                } else {
                    providerIds.map { name ->
                        LyricsProvider.byId(name)
                            ?: fail("Unknown provider \"$name\". Run --list-providers to see the names.")
                    }
                }

            ctx.requireNetwork()
            val track = blocking { resolveTrack(text) } ?: fail("Could not find a track for \"$text\".")

            val service =
                LyricsService(
                    paxsenixApiKey = ctx.config.services.paxsenixApiKey,
                    log = { message -> if (ctx.settings.verbose) ctx.printer.dim("  $message") },
                )

            val (hits, failures) = blocking { fetchAll(service, order, track, paxsenixSource) }

            if (hits.isEmpty()) {
                if (ctx.outputFormat == OutputFormat.JSON) {
                    echoJson(track, emptyList(), failures)
                    return
                }
                failures.forEach { ctx.printer.dim("  $it") }
                fail("No lyrics found for \"${track.title}\".")
            }

            saveTo?.let { writeFile(it, hits.first()) }

            if (ctx.outputFormat == OutputFormat.JSON) {
                echoJson(track, hits, failures)
                return
            }
            printHits(track, hits, failures)
        }

        /**
         * Walks the provider list, stopping at the first usable answer unless
         * `--all` was given. Failures are collected instead of thrown so the
         * user can see every provider that was tried.
         */
        private suspend fun fetchAll(
            service: LyricsService,
            order: List<LyricsProvider>,
            track: Track,
            paxsenixSource: PaxsenixSource?,
        ): Pair<List<LyricsHit>, List<String>> {
            val hits = mutableListOf<LyricsHit>()
            val failures = mutableListOf<String>()
            for (provider in order) {
                if (ctx.settings.verbose) ctx.printer.dim("  asking ${provider.id}")
                val result =
                    if (provider == LyricsProvider.PAXSENIX && paxsenixSource != null) {
                        service.fetchPaxsenixSource(
                            source = paxsenixSource,
                            title = track.title,
                            artist = track.artistString,
                            durationSeconds = track.duration ?: -1,
                        )
                    } else {
                        service.fetch(
                            provider = provider,
                            title = track.title,
                            artist = track.artistString,
                            album = track.album?.title,
                            videoId = track.id,
                            durationSeconds = track.duration ?: -1,
                        )
                    }
                result.fold(
                    onSuccess = { raw ->
                        val hit = LyricsHit(provider, raw)
                        if (hit.plain.isNotBlank()) hits += hit else failures += "${provider.label}: empty response"
                    },
                    onFailure = { error ->
                        failures += "${provider.label}: ${error.message ?: error::class.simpleName ?: "failed"}"
                    },
                )
                if (hits.isNotEmpty() && !askAll) break
            }
            return hits to failures
        }

        private fun printProviders() {
            val printer = ctx.printer
            for (provider in LyricsProvider.entries) {
                val needsId = if (provider.needsVideoId) printer.dim("  (needs a video id)") else ""
                printer.echo("  ${printer.cyan(provider.id.padEnd(13))}${provider.hint}$needsId")
            }
            printer.echo()
            printer.echo(printer.dim("Configured order: " + ctx.config.lyrics.preferredProviders.joinToString(" > ")))
        }

        /**
         * A link or a bare video id resolves exactly; anything else is a search.
         * `Artist - Title` is retried on the title alone when the full string
         * finds nothing, which is what pasting a filename-style tag expects.
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
                    return candidates.firstOrNull { candidate ->
                        candidate.artistString.contains(artist, ignoreCase = true)
                    } ?: candidates.first()
                }
            }
            return null
        }

        private fun writeFile(path: Path, hit: LyricsHit) {
            val body =
                when (render) {
                    LyricsRender.RAW -> hit.text
                    LyricsRender.LRC -> LyricsText.toLrc(hit.text)
                    LyricsRender.TEXT -> hit.plain
                }
            runCatching { path.writeText(body) }
                .onSuccess { ctx.printer.success("Wrote $path") }
                .onFailure { error -> fail("Could not write $path: ${error.message}") }
        }

        private fun printHits(
            track: Track,
            hits: List<LyricsHit>,
            failures: List<String>,
        ) {
            val printer = ctx.printer
            printer.echo(printer.bold(track.title) + printer.dim("  " + track.artistString))
            hits.forEach { hit ->
                printer.echo()
                if (hits.size > 1) {
                    val tags =
                        buildList {
                            add(hit.provider.label)
                            add(hit.format.name.lowercase())
                            if (hit.wordSynced) add("word-synced")
                        }
                    printer.echo(printer.dimBold(tags.joinToString(" · ")))
                    printer.echo()
                }
                when (render) {
                    LyricsRender.RAW -> printer.echo(hit.text.trimEnd())
                    LyricsRender.LRC -> printer.echo(LyricsText.toLrc(hit.text).trimEnd())
                    LyricsRender.TEXT -> printPlain(hit)
                }
            }
            if (failures.isNotEmpty()) {
                printer.echo()
                printer.echo(printer.dim("also tried: " + failures.joinToString("; ")))
            }
        }

        private fun printPlain(hit: LyricsHit) {
            val printer = ctx.printer
            hit.plain.lines().forEach { line ->
                printer.echo("  " + line)
                if (!romanize) return@forEach
                val romanized = Romanizer.romanize(line) ?: return@forEach
                if (romanized.equals(line, ignoreCase = true)) return@forEach
                printer.echo(printer.dim("  " + romanized))
            }
        }

        private fun echoJson(
            track: Track,
            hits: List<LyricsHit>,
            failures: List<String>,
        ) {
            val json = Json { prettyPrint = true }
            ctx.printer.echo(
                json.encodeToString(
                    buildJsonObject {
                        put("id", track.id)
                        put("title", track.title)
                        put("artists", JsonArray(track.artists.map { JsonPrimitive(it.name) }))
                        put(
                            "lyrics",
                            JsonArray(
                                hits.map { hit ->
                                    buildJsonObject {
                                        put("provider", hit.provider.id)
                                        put("format", hit.format.name.lowercase())
                                        put("wordSynced", hit.wordSynced)
                                        put("text", hit.plain)
                                    }
                                },
                            ),
                        )
                        put("errors", JsonArray(failures.map { JsonPrimitive(it) }))
                    },
                ),
            )
        }
    }

/** Clikt has no filesystem path type, so `Paths.get` is wrapped once here. */
private fun NullableOption<String, String>.asPath(): NullableOption<Path, Path> = convert { Paths.get(it) }
