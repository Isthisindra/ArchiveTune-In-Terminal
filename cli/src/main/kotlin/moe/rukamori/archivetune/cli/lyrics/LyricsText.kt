package moe.rukamori.archivetune.cli.lyrics

import moe.rukamori.archivetune.betterlyrics.QRCParser
import moe.rukamori.archivetune.betterlyrics.TTMLParser
import kotlin.math.roundToLong

/** The wire format a provider answered in, after normalisation. */
enum class LyricsSourceFormat {
    PLAIN,
    LRC,
    QRC,
    TTML,
    ;

    val isSynced: Boolean get() = this != PLAIN
}

/**
 * Turns whatever a lyrics provider returned into something a terminal (or
 * another program) can use.
 *
 * The app renders lyrics on a Compose canvas, so it keeps the raw document and
 * pulls the text out at paint time. A CLI has to make the choice up front, so
 * this object does the same detection the app does - QRC, TTML, line-synced LRC
 * with optional word timings, or plain text - and can render it as clean text,
 * as an LRC file, or hand back the provider's bytes untouched.
 */
object LyricsText {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val METADATA_LINE = Regex("""^\[[A-Za-z]+:.*]$""")
    private val YRC_LINE = Regex("""^\[(\d{1,8}),\d{1,8}](.*)$""")
    private val YRC_WORD = Regex("""\(\d{1,8},\d{1,8}(?:,\d{1,8})?\)""")
    private val INLINE_MILLIS = Regex("""<\d{1,8}(?:,\d{1,8})?>""")
    private val TTML_ROOT = Regex("""<(?:[A-Za-z_][\w.-]*:)?tt(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val INVISIBLE = Regex("""[\u200B\u200C\u200D\u2060\u00AD]""")

    private const val NBSP = '\u00A0'

    /**
     * Strips the wrappers providers like to add: a byte-order mark, zero-width
     * characters, a Markdown code fence, and escaped TTML entities. Providers
     * return "```xml\n<tt/>\n```" surprisingly often.
     */
    fun normalize(raw: String): String {
        val cleaned = raw.replace("\uFEFF", "").replace(INVISIBLE, "").trim { it.isWhitespace() || it == NBSP }
        val unfenced = stripCodeFence(cleaned)
        val unescaped =
            if (unescapedTtml(unfenced)) {
                unfenced
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&#39;", "'")
                    .replace("&amp;", "&")
            } else {
                unfenced
            }
        return unescaped.trim { it.isWhitespace() || it == NBSP }
    }

    /** What kind of document this is. Cheap: only the head of the text is read. */
    fun detect(raw: String): LyricsSourceFormat {
        val text = normalize(raw)
        if (text.isEmpty()) return LyricsSourceFormat.PLAIN
        if (TTML_ROOT.containsMatchIn(text.take(4096)) || looksTtml(text)) return LyricsSourceFormat.TTML
        if (QRCParser.isQrc(text)) return LyricsSourceFormat.QRC
        if (text.lineSequence().any { line -> isSyncedLine(line) }) return LyricsSourceFormat.LRC
        return LyricsSourceFormat.PLAIN
    }

    /** True when the document has per-word timings, not just per-line. */
    fun isWordSynced(raw: String): Boolean =
        when (detect(raw)) {
            LyricsSourceFormat.TTML -> runCatching { ttmlLines(raw).any { line -> line.words.isNotEmpty() } }
                .getOrDefault(false)

            LyricsSourceFormat.QRC ->
                runCatching { QRCParser.parseQrc(normalize(raw)).any { line -> line.words.isNotEmpty() } }
                    .getOrDefault(false)

            LyricsSourceFormat.LRC ->
                normalize(raw).lineSequence().any { line ->
                    WORD_TIMESTAMP.containsMatchIn(line.substringAfter(']', ""))
                }

            LyricsSourceFormat.PLAIN -> false
        }

    /**
     * The words only, one line per lyric line. This is what `archivetune lyrics`
     * prints, because a terminal is not a karaoke player.
     */
    fun toPlain(raw: String): String = plainLines(raw).joinToString("\n")

    /** The words only, one entry per lyric line. */
    private fun plainLines(raw: String): List<String> =
        when (detect(raw)) {
            LyricsSourceFormat.TTML ->
                runCatching { ttmlLines(raw).map { line -> line.text } }
                    .getOrElse { fallbackPlain(raw) }

            LyricsSourceFormat.QRC ->
                runCatching { QRCParser.parseQrc(normalize(raw)).map { line -> line.text } }
                    .getOrElse { fallbackPlain(raw) }

            else -> fallbackPlain(raw)
        }.filter { it.isNotBlank() }

    /**
     * An LRC document. TTML and QRC are converted, keeping word timings when the
     * source had them; plain text has no timings to invent, so it passes through.
     */
    fun toLrc(raw: String): String {
        val converted =
            when (detect(raw)) {
                LyricsSourceFormat.TTML, LyricsSourceFormat.QRC ->
                    entries(raw).map(::lrcEntry).ifEmpty { listOf(normalize(raw)) }

                else -> listOf(normalize(raw))
            }
        return converted.joinToString("\n") + "\n"
    }

    private fun lrcEntry(line: LyricsLine): String {
        val text =
            if (line.words.isEmpty()) {
                line.text
            } else {
                line.words.joinToString("") { word ->
                    "<${timestamp(word.startMs / 1000.0)}>${word.text}"
                }
            }
        return "[${timestamp(line.startMs / 1000.0)}]" + text
    }

    /**
     * Every line with its start time, for `--format json` and for the TUI. Word
     * timings are reported separately so a caller can render them as it likes.
     */
    fun entries(raw: String): List<LyricsLine> =
        when (detect(raw)) {
            LyricsSourceFormat.TTML ->
                runCatching {
                    ttmlLines(raw).map { line ->
                        LyricsLine(
                            startMs = (line.startTime * 1000.0).roundToLong().coerceAtLeast(0L),
                            endMs = (line.endTime * 1000.0).roundToLong().coerceAtLeast(0L),
                            text = line.text,
                            words =
                                line.words.map { word ->
                                    LyricsWord(
                                        text = word.text,
                                        startMs = (word.startTime * 1000.0).roundToLong().coerceAtLeast(0L),
                                        endMs = (word.endTime * 1000.0).roundToLong().coerceAtLeast(0L),
                                    )
                                },
                        )
                    }
                }.getOrElse { emptyList() }

            LyricsSourceFormat.QRC ->
                runCatching {
                    QRCParser.parseQrc(normalize(raw)).map { line ->
                        LyricsLine(
                            startMs = (line.startTime * 1000.0).roundToLong().coerceAtLeast(0L),
                            endMs = (line.endTime * 1000.0).roundToLong().coerceAtLeast(0L),
                            text = line.text,
                            words =
                                line.words.map { word ->
                                    LyricsWord(
                                        text = word.text,
                                        startMs = (word.startTime * 1000.0).roundToLong().coerceAtLeast(0L),
                                        endMs = (word.endTime * 1000.0).roundToLong().coerceAtLeast(0L),
                                    )
                                },
                        )
                    }
                }.getOrElse { emptyList() }

            else -> lrcEntries(raw)
        }

    /** One line of lyrics, with the timings a renderer would need. */
    data class LyricsLine(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val words: List<LyricsWord> = emptyList(),
    )

    data class LyricsWord(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    // --- internals ---------------------------------------------------------

    private fun ttmlLines(raw: String) = TTMLParser.parseTTML(normalize(raw))

    private fun lrcEntries(raw: String): List<LyricsLine> {
        val lines = normalize(raw).lines()
        val result = mutableListOf<LyricsLine>()
        lines.forEachIndexed { index, line ->
            val yrc = YRC_LINE.matchEntire(line.trim())
            if (yrc != null) {
                val start = yrc.groupValues[1].toLongOrNull() ?: return@forEachIndexed
                result += LyricsLine(start, start, clean(yrc.groupValues[2]))
                return@forEachIndexed
            }
            val match = TIMESTAMP.find(line) ?: return@forEachIndexed
            val start = match.toMillis() ?: return@forEachIndexed
            val text = clean(line.substring(match.range.last + 1))
            if (text.isEmpty() && METADATA_LINE.matches(line.trim())) return@forEachIndexed
            val end =
                lines.drop(index + 1)
                    .firstNotNullOfOrNull { next -> TIMESTAMP.find(next)?.toMillis() }
                    ?: start
            val words =
                WORD_TIMESTAMP
                    .findAll(line)
                    .mapNotNull { wordMatch ->
                        val wordStart = wordMatch.toMillis() ?: return@mapNotNull null
                        val textStart = wordMatch.range.last + 1
                        val textEnd = WORD_TIMESTAMP.find(line, textStart)?.range?.first ?: line.length
                        val word = clean(line.substring(textStart, textEnd))
                        if (word.isEmpty()) {
                            null
                        } else {
                            LyricsWord(word, wordStart, wordStart)
                        }
                    }.toList()
            result += LyricsLine(start, end, text, words)
        }
        return result
    }

    private fun isSyncedLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (YRC_LINE.matches(trimmed)) return true
        if (METADATA_LINE.matches(trimmed)) return false
        val firstTag = TIMESTAMP.find(trimmed) ?: return false
        // "[ar:Artist]" has a colon but no digits before it, so require that the
        // tag really is a timestamp.
        return firstTag.range.first == 0 && trimmed.length > firstTag.range.last
    }

    /** Line-synced text with the timing markers removed, used when parsing failed. */
    private fun fallbackPlain(raw: String): List<String> =
        normalize(raw)
            .lines()
            .map { line -> clean(line) }
            .filter { it.isNotBlank() }

    /** Removes every timing marker, leaving the words. */
    private fun clean(text: String): String =
        text.replace(WORD_TIMESTAMP, "")
            .replace(INLINE_MILLIS, "")
            .replace(YRC_WORD, "")
            .replace(TIMESTAMP, "")
            .replace(Regex("\\s+"), " ")
            .trim { it.isWhitespace() || it == NBSP }

    private fun stripCodeFence(text: String): String {
        if (!text.startsWith("```")) return text
        val lines = text.lines()
        if (lines.size <= 1) return text
        val body = lines.drop(1)
        val trimmed = if (body.lastOrNull()?.trim() == "```") body.dropLast(1) else body
        return trimmed.joinToString("\n").trim { it.isWhitespace() || it == NBSP }
    }

    private fun looksTtml(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("<")) return false
        return trimmed.contains("<tt", ignoreCase = true) ||
            trimmed.contains("http://www.w3.org/ns/ttml", ignoreCase = true)
    }

    private fun unescapedTtml(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("&lt;tt", ignoreCase = true) || trimmed.contains("&lt;tt", ignoreCase = true)
    }

    /** `12.34` seconds as the `mm:ss.xx` an LRC player expects. */
    private fun timestamp(seconds: Double): String {
        val totalMs = (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
        val minutes = totalMs / 60_000
        val rest = (totalMs % 60_000).toInt()
        return "%d:%02d.%02d".format(minutes, rest / 1000, rest % 1000 / 10)
    }

    private fun MatchResult.toMillis(): Long? {
        val minutes = groupValues[1].toLongOrNull() ?: return null
        val seconds = groupValues[2].toLongOrNull()?.takeIf { it in 0L..59L } ?: return null
        val fraction = groupValues[3]
        val millis =
            when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLongOrNull()?.times(100L)
                2 -> fraction.toLongOrNull()?.times(10L)
                else -> fraction.toLongOrNull()
            } ?: return null
        return minutes * 60_000 + seconds * 1000 + millis
    }
}
