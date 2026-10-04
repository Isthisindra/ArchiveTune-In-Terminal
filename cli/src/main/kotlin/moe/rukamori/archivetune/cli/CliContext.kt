package moe.rukamori.archivetune.cli

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.config.CliConfig
import moe.rukamori.archivetune.cli.config.ConfigPaths
import moe.rukamori.archivetune.cli.config.ConfigStore
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.config.PlayerBackend
import moe.rukamori.archivetune.cli.music.MusicService
import moe.rukamori.archivetune.cli.output.Printer
import moe.rukamori.archivetune.cli.output.TrackTableRenderer
import moe.rukamori.archivetune.cli.playback.MpvPlayerEngine
import moe.rukamori.archivetune.cli.playback.PlayerEngine
import moe.rukamori.archivetune.cli.playback.VlcPlayerEngine
import moe.rukamori.archivetune.cli.session.SessionBootstrap
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import java.nio.file.Path

/**
 * Values the global flags can override. Every field starts as `null` so that an
 * unset flag keeps whatever the config file asked for; [CliContext] resolves
 * them once, after parsing and before any command runs.
 */
class GlobalSettings {
    var configFile: Path? = null
    var format: OutputFormat? = null
    var color: Boolean? = null
    var player: PlayerBackend? = null
    var quality: AudioQuality? = null
    var volume: Int? = null
    var shuffle: Boolean? = null
    var repeat: Boolean? = null
    var crossfadeSeconds: Int? = null
    var limit: Int? = null
    var verbose: Boolean = false
    var noProgress: Boolean = false
    var interactive: Boolean? = null
}

/**
 * Everything a subcommand needs, created once per invocation.
 *
 * Subcommands receive this instance directly instead of using Clikt's context
 * objects: the global flags are declared on the root command and write into
 * [settings] through `eager {}` blocks, so by the time any `run()` executes the
 * settings are final and the lazily built services below are already correct.
 */
class CliContext(val settings: GlobalSettings = GlobalSettings()) {
    val terminal: Terminal = Terminal()

    val config: CliConfig by lazy { loadConfig() }

    /**
     * The file `--config` resolved to, honouring the "a directory means
     * `config.json` inside it" rule. Falls back to the default location.
     */
    val resolvedConfigPath: Path by lazy {
        val override = settings.configFile
        when {
            override == null -> ConfigPaths.configFile
            override.toFile().isDirectory -> override.resolve("config.json")
            else -> override
        }
    }

    fun saveConfig(config: CliConfig) = ConfigStore.saveTo(resolvedConfigPath, config)

    /** Effective output format after applying flags on top of the config. */
    val outputFormat: OutputFormat get() = settings.format ?: config.output.format

    val colorEnabled: Boolean
        get() = (settings.color ?: config.output.color) && supportsAnsi

    /** True when we can draw in place and read control keys from the console. */
    val interactive: Boolean
        get() = settings.interactive ?: (terminal.terminalInfo.interactive && System.console() != null)

    val supportsAnsi: Boolean get() = terminal.terminalInfo.ansiLevel != AnsiLevel.NONE

    val printer: Printer by lazy { Printer(terminal, outputFormat, colorEnabled) }

    val table: TrackTableRenderer by lazy { TrackTableRenderer(printer) }

    val music: MusicService by lazy { MusicService() }

    val audioQuality: AudioQuality get() = settings.quality ?: config.playback.audioQuality

    val volume: Int get() = (settings.volume ?: config.playback.volume).coerceIn(0, 100)

    val streamClient: YouTubeClient by lazy { resolveClient(config.playback.streamClient) }

    /**
     * The clients a stream is resolved with, in order.
     *
     * `playback.streamClient` is only the first choice. YouTube bot-gates some
     * uploads and rejects whole client families outright for some accounts, so
     * guest VISIONOS is tried next: it needs no login at all and still serves
     * everything that is not gated. Without this, one account-specific refusal
     * ("Please sign in") ends playback for every track in the queue.
     */
    val streamClients: List<YouTubeClient> by lazy {
        buildList {
            add(streamClient)
            val guest = YouTubeClient.VISIONOS
            if (guest.clientName != streamClient.clientName) add(guest)
        }
    }

    private var engine: PlayerEngine? = null

    private var bootstrapped = false

    /**
     * Applies the config to the shared [YouTube] singleton. Called lazily so
     * commands that never touch the network (such as `config path`) do not pay
     * for it. [onLog] receives the progress lines; the default keeps printing
     * them to stderr under `--verbose` exactly like before.
     */
    fun requireNetwork(onLog: (String) -> Unit = defaultStatus) {
        if (bootstrapped) return
        SessionBootstrap.apply(
            config = config,
            onLog = onLog,
            save = { saveConfig(it) },
        )
        bootstrapped = true
    }

    /** The non-interactive status sink: stderr, only when `--verbose` asks. */
    private val defaultStatus: (String) -> Unit = { message ->
        if (settings.verbose) System.err.println(message)
    }

    fun newPlayerEngine(): PlayerEngine {
        engine?.let { return it }
        val created = newPlayerEngineFor(settings.player ?: config.playback.backend)
        engine = created
        return created
    }

    /** Builds an engine for an explicit backend, ignoring the cached one. */
    fun newPlayerEngineFor(backend: PlayerBackend): PlayerEngine =
        when (backend) {
            PlayerBackend.VLC -> VlcPlayerEngine()
            PlayerBackend.MPV -> MpvPlayerEngine(config.playback.mpvPath ?: "mpv")
        }

    fun shutdown() {
        runCatching { engine?.close() }
        engine = null
    }

    private fun loadConfig(): CliConfig = ConfigStore.loadFrom(resolvedConfigPath)

    private fun resolveClient(name: String): YouTubeClient =
        when (name.trim().uppercase()) {
            "WEB" -> YouTubeClient.WEB
            "WEB_PRIMARY" -> YouTubeClient.WEB_PRIMARY
            "WEB_REMIX" -> YouTubeClient.WEB_REMIX
            "WEB_CREATOR" -> YouTubeClient.WEB_CREATOR
            "WEB_MUSIC" -> YouTubeClient.WEB_MUSIC
            "WEB_SAFARI" -> YouTubeClient.WEB_SAFARI
            "WEB_EMBEDDED" -> YouTubeClient.WEB_EMBEDDED
            "MWEB" -> YouTubeClient.MWEB
            "TVHTML5" -> YouTubeClient.TVHTML5
            "TVHTML5_DOWNGRADED" -> YouTubeClient.TVHTML5_DOWNGRADED
            "TVHTML5_SIMPLY" -> YouTubeClient.TVHTML5_SIMPLY
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER
            "ANDROID_MUSIC" -> YouTubeClient.ANDROID_MUSIC.copy(
                loginSupported = true,
                supportsCookieAuthentication = true,
                useSignatureTimestamp = false,
            )
            "ANDROID_TESTSUITE" -> YouTubeClient.ANDROID_TESTSUITE
            "ANDROID_UNPLUGGED" -> YouTubeClient.ANDROID_UNPLUGGED.copy(
                loginSupported = true,
                supportsCookieAuthentication = true,
                useSignatureTimestamp = false,
            )
            "ANDROID_CREATOR" -> YouTubeClient.ANDROID_CREATOR
            "ANDROID_VR_NO_AUTH" -> YouTubeClient.ANDROID_VR_NO_AUTH
            "IOS_MUSIC" -> YouTubeClient.IOS_MUSIC.copy(
                loginSupported = true,
                supportsCookieAuthentication = true,
                useSignatureTimestamp = false,
            )
            "IOS" -> YouTubeClient.IOS
            "MOBILE" -> YouTubeClient.MOBILE
            "IPADOS" -> YouTubeClient.IPADOS
            "VISIONOS" -> YouTubeClient.VISIONOS
            else -> YouTubeClient.WEB_REMIX
        }

    companion object {
        val defaultConfigPath: Path = ConfigPaths.configFile
        val cacheDir: Path = ConfigPaths.cacheDir
        val downloadDir: Path = ConfigPaths.downloadDir
    }
}
