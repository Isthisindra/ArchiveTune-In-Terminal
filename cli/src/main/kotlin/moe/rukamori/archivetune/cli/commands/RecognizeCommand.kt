package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.recognize.AudioDecoder
import moe.rukamori.archivetune.shazamkit.Shazam
import moe.rukamori.archivetune.shazamkit.ShazamSignatureGenerator
import moe.rukamori.archivetune.shazamkit.models.RecognitionResult
import java.io.File

/**
 * Identifies a song through the Shazam API.
 *
 * Reads the first [seconds] of an audio file (decoded to the 16 kHz mono PCM16
 * Shazam wants, backed by ffmpeg when it is installed) or captures that long
 * from the microphone with [record]. Prints the match the same way the other
 * commands do: a table on the terminal, JSON behind `-f json`.
 */
class RecognizeCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "recognize",
        helpText = "Identify a song from an audio file or the microphone.",
        epilogText =
            """
            Examples:
              archivetune recognize song.mp3
              archivetune recognize --record --seconds 10
              archivetune recognize clip.wav -f json
            """.trimIndent(),
    ) {
        private val files by argument("FILE", help = "Audio file to identify").multiple()

        private val record by option("--record", help = "Capture from the microphone instead").flag()

        private val seconds by option("--seconds", metavar = "N", help = "Seconds of audio to feed (default: 8)").int().default(8)

        override fun run() {
            when {
                record -> {
                    if (files.isNotEmpty()) fail("Cannot mix --record with FILE arguments.")
                    val samples = AudioDecoder.captureMicrophone(seconds.coerceIn(1, 60))
                    if (samples.isEmpty()) fail("No microphone was available.")
                    identify(samples, "microphone")
                }

                files.isEmpty() -> fail("Nothing to identify. Pass a FILE or --record.")

                else ->
                    files.forEach { name ->
                        val file = File(name)
                        if (!file.isFile) fail("No such file: $name")
                        val samples =
                            runCatching { AudioDecoder.decodePcm16Mono(file) }
                                .getOrElse { fail(it.message ?: "Could not decode $name.") }
                        identify(samples, file.name)
                        if (files.size > 1) ctx.printer.echo()
                    }
            }
        }

        private fun identify(samples: ShortArray, source: String) {
            val printer = ctx.printer
            val limit = seconds.coerceIn(1, 60) * AudioDecoder.SAMPLE_RATE_HZ
            val window = if (samples.size > limit) samples.copyOf(limit) else samples
            printer.echo(printer.dim("listening to $source (${window.size / AudioDecoder.SAMPLE_RATE_HZ}s) ..."))

            val signature =
                ShazamSignatureGenerator().apply { feedPcm16Mono(window) }
                    .nextSignatureOrNull()
                    ?: fail("Could not build a fingerprint from \"$source\". The audio may be silent or too short.")

            val result =
                blocking { Shazam.recognize(signature.uri, signature.sampleDurationMs) }
                    .getOrElse { error ->
                        val message = error.message.orEmpty()
                        if (message.contains("no match", ignoreCase = true) || message.contains("404")) {
                            fail("No match found for \"$source\". Try a longer or clearer recording.")
                        }
                        fail("Recognition failed: $message")
                    }

            render(result)
        }

        private fun render(result: RecognitionResult) {
            val printer = ctx.printer
            if (ctx.outputFormat == moe.rukamori.archivetune.cli.config.OutputFormat.JSON) {
                printer.echo(encodeJson(result))
                return
            }

            printer.echo("${printer.accent(result.title)}  ${printer.bold(result.artist)}")
            result.album?.let { printer.echo("  album      $it") }
            result.genre?.let { printer.echo("  genre      $it") }
            result.releaseDate?.let { printer.echo("  released   $it") }
            result.label?.let { printer.echo("  label      $it") }
            result.isrc?.let { printer.echo("  isrc       $it") }
            val lyricLines = result.lyrics ?: emptyList()
            if (lyricLines.isNotEmpty()) {
                printer.echo("  lyrics     ${lyricLines.size} lines")
            }
            val links =
                buildList {
                    result.shazamUrl?.let { add("shazam" to it) }
                    result.appleMusicUrl?.let { add("apple music" to it) }
                    result.spotifyUrl?.let { add("spotify" to it) }
                    result.youtubeVideoId?.let { add("youtube" to "https://youtu.be/$it") }
                }
            if (links.isNotEmpty()) {
                printer.echo(
                    "  links      " + links.joinToString("\n             ") { "${it.first}: ${it.second}" },
                )
            }
        }

        private fun encodeJson(result: RecognitionResult): String =
            Json { prettyPrint = true }.encodeToString(
                buildJsonObject {
                    put("title", result.title)
                    put("artist", result.artist)
                    result.album?.let { put("album", it) }
                    result.genre?.let { put("genre", it) }
                    result.releaseDate?.let { put("released", it) }
                    result.label?.let { put("label", it) }
                    result.isrc?.let { put("isrc", it) }
                    result.youtubeVideoId?.let { put("youtube", "https://youtu.be/$it") }
                    result.appleMusicUrl?.let { put("appleMusic", it) }
                    result.spotifyUrl?.let { put("spotify", it) }
                    put("shazam", JsonPrimitive(result.shazamUrl ?: ""))
                },
            )
    }