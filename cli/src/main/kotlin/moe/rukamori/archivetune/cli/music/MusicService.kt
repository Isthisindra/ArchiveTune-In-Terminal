package moe.rukamori.archivetune.cli.music

import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.innertube.NewPipeUtils
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse

/** A stream that is ready to hand to a player backend. */
data class ResolvedStream(
    val url: String,
    val requestHeaders: Map<String, String>,
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val sampleRate: Int?,
    val contentLength: Long,
    val loudnessDb: Double?,
    val title: String?,
    val durationSeconds: Int?,
    val expiresAtMs: Long,
)

/** Resolves tracks to playable stream URLs, mirroring the app's resolution order. */
class MusicService(
    private val authStateProvider: () -> PlaybackAuthState = { YouTube.currentPlaybackAuthState() },
) {
    /** Picks the best audio format, deobfuscates it, and returns a playable stream. */
    suspend fun resolve(
        track: Track,
        quality: AudioQuality = AudioQuality.AUTO,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ): Result<ResolvedStream> {
        val videoId = track.id
        val rawAuthState = authStateProvider()
        // Mobile clients reject the WEB-format visitor id; drop it for them.
        val authStateForPlayer =
            if (!client.useWebPoTokens && client.clientName.uppercase().let { it.startsWith("ANDROID") || it.startsWith("IOS") || it == "MOBILE" }) {
                rawAuthState.copy(visitorData = null)
            } else {
                rawAuthState
            }
        val playerResult =
            YouTube.player(
                videoId = videoId,
                client = client,
                authState = authStateForPlayer,
            )
        val playerResponse = playerResult.getOrElse { return Result.failure(it) }

        val status = playerResponse.playabilityStatus
        if (status.status != "OK") {
            return Result.failure(
                StreamUnavailableException(hintFor(status.reason ?: "Stream unavailable (${status.status})")),
            )
        }

        val streamingData = playerResponse.streamingData
            ?: return Result.failure(StreamUnavailableException("No streaming data returned"))

        val audioFormats = (streamingData.adaptiveFormats + streamingData.formats.orEmpty())
            .filter { it.isAudio }
        if (audioFormats.isEmpty()) {
            return Result.failure(StreamUnavailableException("No audio-only format available"))
        }

        val format = selectFormat(audioFormats, quality)
            ?: return Result.failure(StreamUnavailableException("No format matched quality $quality"))

        val authState = authStateProvider()
        val url =
            NewPipeUtils.getStreamUrl(
                format = format,
                videoId = videoId,
                client = client,
                authState = authState,
            ).getOrElse { return Result.failure(it) }

        val expiresInSeconds = streamingData.expiresInSeconds ?: 300
        val headers =
            buildMap {
                put("User-Agent", client.userAgent)
                // InnerTube stream URLs expect the same referer the web client sends.
                put("Referer", YouTubeClient.REFERER_YOUTUBE_MUSIC)
                put("Origin", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
            }

        return Result.success(
            ResolvedStream(
                url = url,
                requestHeaders = headers,
                itag = format.itag,
                mimeType = format.mimeType,
                bitrate = format.averageBitrate ?: format.bitrate,
                sampleRate = format.audioSampleRate,
                contentLength = format.contentLength ?: 0L,
                loudnessDb = format.loudnessDb ?: playerResponse.playerConfig?.audioConfig?.loudnessDb,
                title = playerResponse.videoDetails?.title ?: track.title,
                durationSeconds =
                    playerResponse.videoDetails?.lengthSeconds?.toIntOrNull() ?: track.duration,
                expiresAtMs = System.currentTimeMillis() + expiresInSeconds * 1000L,
            ),
        )
    }

    /** Rank audio formats by the requested quality, then by bitrate. */
    private fun selectFormat(
        formats: List<PlayerResponse.StreamingData.Format>,
        quality: AudioQuality,
    ): PlayerResponse.StreamingData.Format? =
        when (quality) {
            AudioQuality.AUTO -> formats.maxWithOrNull(compareBy({ it.audioQuality.orEmpty() }, { it.bitrate }))
            AudioQuality.LOW -> formats.filter { it.audioQuality == "AUDIO_QUALITY_LOW" }
                .maxByOrNull { it.bitrate } ?: formats.minByOrNull { it.bitrate }
            AudioQuality.HIGH -> formats.filter { it.audioQuality in setOf("AUDIO_QUALITY_MEDIUM", "AUDIO_QUALITY_HIGH") }
                .maxByOrNull { it.bitrate } ?: formats.maxByOrNull { it.bitrate }
            AudioQuality.HIGHEST -> formats.filter { it.audioQuality == "AUDIO_QUALITY_HIGH" }
                .maxByOrNull { it.bitrate } ?: formats.maxByOrNull { it.bitrate }
        }

    /** Injects headers a raw HTTP player (mpv/VLC) needs for the stream to load. */
    fun headersForHttp(headers: Map<String, String>): List<String> =
        headers.map { (k, v) -> "$k: $v" }

    /**
     * The reason YouTube gave gets annotated with what actually helps.
     *
     * "Please sign in" and "confirm you're not a bot" mean the session is not
     * accepted for this client, so a login is the fix - and every client is
     * already being tried before this message is reached, guest included. When
     * no client would take the track it is usually the upload itself that
     * YouTube has gated, in which case the queue moves on to the next result.
     */
    private fun hintFor(reason: String): String {
        if (reason.contains("sign in", ignoreCase = true) || reason.contains("bot", ignoreCase = true)) {
            return "$reason. Every client (guest included) was refused this upload. " +
                "Run auth youtube to link an account, or let the queue continue to the next result."
        }
        return reason
    }
}

class StreamUnavailableException(message: String) : Exception(message)
