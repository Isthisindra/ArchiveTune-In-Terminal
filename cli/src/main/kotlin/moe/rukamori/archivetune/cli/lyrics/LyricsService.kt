package moe.rukamori.archivetune.cli.lyrics

import moe.rukamori.archivetune.betterlyrics.BetterLyrics
import moe.rukamori.archivetune.kugou.KuGou
import moe.rukamori.archivetune.lrclib.LrcLib
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import moe.rukamori.archivetune.simpmusic.SimpMusicLyrics
import moe.rukamori.archivetune.unison.Unison
import moe.rukamori.archivetune.youlyplus.YouLyPlus

/**
 * A lyrics source.
 *
 * The seven providers are the same ones the Android app ships, wrapped so the
 * command can treat them uniformly. [needsVideoId] marks the providers that key
 * off the YouTube id instead of the title, which is also why the command
 * resolves a track before asking for lyrics.
 */
enum class LyricsProvider(
    val id: String,
    val label: String,
    val needsVideoId: Boolean = false,
    val source: String = "",
) {
    BETTERLYRICS("betterlyrics", "BetterLyrics"),
    YOURLYPLUS("youlyplus", "YouLyPlus"),
    UNISON("unison", "Unison", needsVideoId = true),
    SIMPMUSIC("simpmusic", "SimpMusic", needsVideoId = true),
    LRCLIB("lrclib", "LRCLIB"),
    KUGOU("kugou", "KuGou"),
    PAXSENIX("paxsenix", "Paxsenix"),
    ;

    /** A short note about what the provider is good at, for `--provider --help`. */
    val hint: String
        get() =
            when (this) {
                BETTERLYRICS -> "TTML with word timings and translations"
                YOURLYPLUS -> "YouTube timed lyrics, romanized"
                UNISON -> "Multi-language variants for a specific video"
                SIMPMUSIC -> "Synced lyrics for a specific video"
                LRCLIB -> "Open LRC database, best plain-text coverage"
                KUGOU -> "Strong on Chinese catalog"
                PAXSENIX -> "Aggregator: lrcget, Musixmatch, Apple Music, Spotify"
            }

    companion object {
        /** The order the app tries providers in when nothing is configured. */
        val defaultOrder: List<LyricsProvider> =
            listOf(BETTERLYRICS, YOURLYPLUS, UNISON, SIMPMUSIC, LRCLIB, KUGOU, PAXSENIX)

        fun byId(id: String): LyricsProvider? {
            val key = id.trim().lowercase()
            return entries.firstOrNull { it.id == key }
        }

        /** Resolves configured ids, silently dropping names this build lacks. */
        fun fromIds(ids: List<String>): List<LyricsProvider> =
            ids.mapNotNull(::byId).distinct().ifEmpty { defaultOrder }
    }
}

/** One provider's answer, plus enough context to explain it to the user. */
data class LyricsHit(
    val provider: LyricsProvider,
    val text: String,
) {
    val format: LyricsSourceFormat by lazy { LyricsText.detect(text) }
    val wordSynced: Boolean by lazy { LyricsText.isWordSynced(text) }

    /** The words only, timings removed. */
    val plain: String by lazy { LyricsText.toPlain(text) }
}

/**
 * Fetches lyrics from the configured providers.
 *
 * Every provider is a suspend function that returns a [Result], so failures are
 * values here too: the command tries the next provider instead of aborting the
 * whole run when one of them is down.
 */
class LyricsService(
    private val userAgent: String = "ArchiveTune",
    private val paxsenixApiKey: String? = null,
    private val log: (String) -> Unit = {},
) {
    init {
        // The providers expose a logger hook the app fills with its log file.
        BetterLyrics.logger = { message -> log("BetterLyrics: $message") }
        YouLyPlus.logger = { message -> log("YouLyPlus: $message") }
        Unison.logger = { message -> log("Unison: $message") }
        PaxsenixLyrics.setUserAgent(userAgent, VERSION)
        paxsenixApiKey?.takeIf { it.isNotBlank() }?.let(PaxsenixLyrics::setApiKey)
    }

    /** Asks one provider. Seconds of `0` means "unknown duration". */
    suspend fun fetch(
        provider: LyricsProvider,
        title: String,
        artist: String,
        album: String?,
        videoId: String?,
        durationSeconds: Int,
    ): Result<String> {
        if (title.isBlank() || artist.isBlank()) {
            return Result.failure(IllegalArgumentException("A title and an artist are required"))
        }
        if (provider.needsVideoId && videoId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("${provider.label} needs a YouTube video id"))
        }
        val duration = if (durationSeconds > 0) durationSeconds else -1
        return when (provider) {
            LyricsProvider.BETTERLYRICS ->
                BetterLyrics.getLyrics(title = title, artist = artist, album = album, durationSeconds = duration)

            LyricsProvider.YOURLYPLUS ->
                YouLyPlus.getLyrics(title = title, artist = artist, album = album, durationSeconds = duration)

            LyricsProvider.UNISON ->
                Unison.getLyrics(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                )

            LyricsProvider.SIMPMUSIC ->
                SimpMusicLyrics.getLyrics(videoId = videoId.orEmpty(), duration = duration.coerceAtLeast(0))

            LyricsProvider.LRCLIB -> LrcLib.getLyrics(title = title, artist = artist, duration = duration, album = album)

            LyricsProvider.KUGOU -> KuGou.getLyrics(title = title, artist = artist, duration = duration)

            LyricsProvider.PAXSENIX -> PaxsenixLyrics.getLyrics(title = title, artist = artist, durationSeconds = duration)
        }.mapCatching { raw ->
            raw.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Empty response")
        }
    }

    /**
     * The paxsenix aggregator exposes three more upstreams behind the same API
     * key. `--paxsenix-source` picks one; the default is the provider's own.
     */
    suspend fun fetchPaxsenixSource(
        source: PaxsenixSource,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> {
        val duration = if (durationSeconds > 0) durationSeconds else -1
        return when (source) {
            PaxsenixSource.LRCGET ->
                PaxsenixLyrics.getLyrics(title = title, artist = artist, durationSeconds = duration)

            PaxsenixSource.MUSIXMATCH ->
                PaxsenixLyrics.getMusixmatchLyrics(title = title, artist = artist, durationSeconds = duration)

            PaxsenixSource.APPLE_MUSIC ->
                PaxsenixLyrics.getAppleMusicLyrics(title = title, artist = artist, durationSeconds = duration)

            PaxsenixSource.SPOTIFY ->
                PaxsenixLyrics.getSpotifyLyrics(title = title, artist = artist, durationSeconds = duration)
        }
    }

    private companion object {
        const val VERSION = "1.0"
    }
}

/** The extra upstreams paxsenix can proxy. */
enum class PaxsenixSource(val id: String, val label: String) {
    LRCGET("lrcget", "lrcget"),
    MUSIXMATCH("musixmatch", "Musixmatch"),
    APPLE_MUSIC("apple", "Apple Music"),
    SPOTIFY("spotify", "Spotify"),
    ;

    companion object {
        fun byId(id: String): PaxsenixSource? {
            val key = id.trim().lowercase()
            return entries.firstOrNull { it.id == key }
        }
    }
}
