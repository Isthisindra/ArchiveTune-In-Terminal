package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.music.CatalogService
import moe.rukamori.archivetune.cli.output.ProgressRenderer
import moe.rukamori.archivetune.cli.playback.ConsoleControls
import moe.rukamori.archivetune.cli.playback.PlayOptions
import moe.rukamori.archivetune.cli.playback.PlaybackController
import moe.rukamori.archivetune.cli.playback.PlayerEngine
import moe.rukamori.archivetune.cli.playback.PlayerUnavailableException
import moe.rukamori.archivetune.cli.playback.ScrobbleGateway
import moe.rukamori.archivetune.cli.registerGlobalOptions

/**
 * Shared plumbing for the subcommands: coroutine bridging, consistent error
 * reporting, and one place that knows how to turn a queue into audio.
 */
abstract class BaseCommand(
    protected val ctx: CliContext,
    name: String? = null,
    private val helpText: String = "",
    private val epilogText: String = "",
) : CliktCommand(name = name) {

    init {
        // Hidden duplicates of the root flags, so `archivetune search x -f json`
        // works as well as `archivetune -f json search x`.
        registerGlobalOptions(ctx, visible = false)
    }

    override fun help(context: Context): String = helpText

    override fun helpEpilog(context: Context): String = epilogText

    protected val catalog: CatalogService by lazy { CatalogService() }

    protected fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    protected fun fail(message: String): Nothing = throw PrintMessage(message)

    /** Unwraps a [Result], turning a failure into a clean CLI error. */
    protected fun <T> Result<T>.orFail(): T =
        getOrElse { error ->
            fail(error.message ?: error::class.simpleName ?: "request failed")
        }

    /**
     * Plays a queue, checking the backend first so the user gets "install VLC"
     * instead of a stack trace.
     */
    protected fun play(queue: List<Track>, options: PlayOptions = PlayOptions()) {
        if (queue.isEmpty()) {
            fail("Nothing to play.")
        }
        ctx.requireNetwork()
        val engine = ctx.newPlayerEngine()
        if (!engine.isAvailable()) {
            fail(unavailableMessage(engine.name))
        }
        val controller = controllerFor(engine)
        if (ctx.interactive) ConsoleControls(controller, ctx.printer).start()
        val effective =
            options.copy(
                shuffle = ctx.settings.shuffle ?: false,
                repeat = ctx.settings.repeat ?: false,
                crossfadeSeconds = ctx.settings.crossfadeSeconds ?: 0,
                showProgress = !ctx.settings.noProgress,
                interactive = ctx.interactive,
            )
        blocking {
            try {
                controller.play(queue, effective)
            } finally {
                controller.stop()
            }
        }
    }

    private var controller: PlaybackController? = null

    /** The one controller for this command, so the controls and playback agree. */
    private fun controllerFor(engine: PlayerEngine): PlaybackController =
        controller ?: PlaybackController(
            music = ctx.music,
            engine = engine,
            printer = ctx.printer,
            progress = ProgressRenderer(
                printer = ctx.printer,
                supportsInPlace = ctx.supportsAnsi && !ctx.settings.noProgress,
                widthProvider = { runCatching { ctx.terminal.size.width }.getOrDefault(80) },
            ),
            clients = ctx.streamClients,
            quality = ctx.audioQuality,
            startVolume = ctx.volume,
            verbose = ctx.settings.verbose,
            scrobble =
                ScrobbleGateway(ctx.config.scrobbling) { message ->
                    ctx.printer.warn(message)
                },
        ).also { controller = it }

    /** The "you need VLC / mpv for this" message for a missing backend. */
    protected fun unavailableMessage(backend: String): String = unavailableBackendMessage(backend)
}

/** The "you need VLC / mpv for this" message for a missing backend. */
internal fun unavailableBackendMessage(backend: String): String =
    when (backend) {
        "vlc" ->
            "VLC was not found. Install it from https://www.videolan.org/vlc/ " +
                "or re-run with --player mpv."
        "mpv" ->
            "mpv was not found. Install it from https://mpv.io/ " +
                "or set playback.mpv_path in the config."
        else -> "The $backend backend is unavailable."
    }

/** Thrown when the user asked for something the player backend cannot do. */
internal fun playerUnavailable(error: Throwable): Nothing =
    if (error is PlayerUnavailableException) throw PrintMessage(error.message.orEmpty())
    else throw PrintMessage(error.message ?: "playback failed")
