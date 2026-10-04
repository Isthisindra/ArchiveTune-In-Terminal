package moe.rukamori.archivetune.cli.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the app's `AudioQuality` preference so stream selection behaves the same. */
@Serializable
enum class AudioQuality {
    AUTO,
    HIGH,
    HIGHEST,
    LOW,
}

/** Player backends selectable with `--player`. */
@Serializable
enum class PlayerBackend {
    @SerialName("vlc")
    VLC,

    @SerialName("mpv")
    MPV,
}

/** Non-interactive output shape, for piping into `jq` and friends. */
@Serializable
enum class OutputFormat {
    @SerialName("table")
    TABLE,

    @SerialName("json")
    JSON,

    @SerialName("plain")
    PLAIN,
}

@Serializable
data class AccountConfig(
    val cookie: String? = null,
    val visitorData: String? = null,
    /** When [visitorData] was fetched, in epoch millis; used to refresh the cache. */
    val visitorDataFetchedAt: Long? = null,
    val dataSyncId: String? = null,
    val localeHl: String? = null,
    val localeGl: String? = null,
)

@Serializable
data class PlaybackConfig(
    val backend: PlayerBackend = PlayerBackend.VLC,
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val streamClient: String = "WEB_REMIX",
    val volume: Int = 80,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val crossfadeSeconds: Int = 0,
    /** Extra headers sent with every stream request; Some proxies need them. */
    val vlcOptions: List<String> = emptyList(),
    val mpvPath: String? = null,
    val vlcPath: String? = null,
)

@Serializable
data class NetworkConfig(
    val proxy: String? = null,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val dnsOverHttps: String? = null,
    val streamBypassProxy: Boolean = false,
    val streamProxy: String? = null,
)

@Serializable
data class OutputConfig(
    val format: OutputFormat = OutputFormat.TABLE,
    val color: Boolean = true,
    val pageSize: Int = 20,
)

@Serializable
data class LyricsConfig(
    val preferredProviders: List<String> =
        listOf("betterlyrics", "youlyplus", "unison", "simpmusic", "lrclib", "kugou", "paxsenix"),
    val translateTo: String? = null,
    val romanize: Boolean = false,
)

@Serializable
data class ScrobbleConfig(
    val enabled: Boolean = false,
    val endpoint: String = "https://ws.audioscrobbler.com/2.0/",
    val apiKey: String = "archivetune",
    val secret: String = "archivetune",
    val sessionKey: String? = null,
    val nowPlaying: Boolean = true,
    /** Fraction of a track that must play before it is submitted. */
    val scrobbleThreshold: Double = 0.5,
)

@Serializable
data class ShazamConfig(
    val paxsenixApiKey: String? = null,
    val spotifySpDc: String? = null,
    val spotifySpKey: String? = null,
)

@Serializable
data class CliConfig(
    val account: AccountConfig = AccountConfig(),
    val playback: PlaybackConfig = PlaybackConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val output: OutputConfig = OutputConfig(),
    val lyrics: LyricsConfig = LyricsConfig(),
    val scrobbling: ScrobbleConfig = ScrobbleConfig(),
    val services: ShazamConfig = ShazamConfig(),
)
