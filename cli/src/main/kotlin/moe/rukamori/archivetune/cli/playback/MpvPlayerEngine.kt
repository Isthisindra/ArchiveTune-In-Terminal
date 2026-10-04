package moe.rukamori.archivetune.cli.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.cli.music.ResolvedStream
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * Drives an external `mpv` process. Chosen as the alternative backend because
 * mpv handles gapless playback and crossfade better than VLC. Progress is read
 * back over mpv's JSON IPC socket.
 */
class MpvPlayerEngine(
    private val mpvPath: String = "mpv",
) : PlayerEngine {
    override val name: String = "mpv"

    private val json = Json { ignoreUnknownKeys = true }

    private var process: Process? = null
    private var onEndCallback: (() -> Unit)? = null
    @Volatile private var completion = CountDownLatch(1)
    private var ipcPort: Int = 0
    @Volatile private var volume: Int = 80

    override fun isAvailable(): Boolean = locateMpv() != null

    private fun locateMpv(): String? {
        if (File(mpvPath).canExecute()) return mpvPath
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val candidates =
            listOfNotNull(
                "C:\\Program Files\\mpv\\mpv.exe",
                "C:\\Program Files (x86)\\mpv\\mpv.exe",
                if (localAppData.isNotBlank()) "$localAppData\\Programs\\mpv\\mpv.exe" else null,
            )
        return candidates.firstOrNull { File(it).canExecute() }
    }

    override fun play(stream: ResolvedStream, onEnd: () -> Unit) {
        val exe = locateMpv() ?: throw PlayerUnavailableException("mpv not found. Install it or set playback.mpv_path in the config.")
        stop()
        completion = CountDownLatch(1)
        onEndCallback = onEnd
        ipcPort = (17500..17600).random()

        val cmd =
            buildList {
                add(exe)
                add("--no-video")
                add("--no-terminal")
                add("--idle=yes")
                add("--keep-open=no")
                add("--input-ipc-server=127.0.0.1:$ipcPort")
                add("--volume=$volume")
                stream.requestHeaders.forEach { (k, v) -> add("--http-header-fields=$k: $v") }
                add(stream.url)
            }

        val pb = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process = pb

        Thread({ runCatching { pb.inputStream.bufferedReader().forEachLine { } } }, "mpv-stdout")
            .apply { isDaemon = true }
            .start()

        Thread({
            runCatching { pb.waitFor() }
            completion.countDown()
            onEndCallback?.invoke()
        }, "mpv-wait").apply { isDaemon = true }.start()
    }

    override fun pause() {
        ipc("[\"set\",\"pause\",true]")
    }

    override fun resume() {
        ipc("[\"set\",\"pause\",false]")
    }

    override fun stop() {
        ipc("[\"quit\"]")
        process?.destroy()
        process = null
    }

    override fun seekTo(positionMs: Long) {
        ipc("[\"seek\",${positionMs / 1000.0},\"absolute\"]")
    }

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        ipc("[\"set\",\"volume\",$volume]")
    }

    override fun status(): PlaybackStatus {
        val pos = (ipcGet("playback-pos") as? Number)?.toDouble() ?: 0.0
        val dur = (ipcGet("duration") as? Number)?.toDouble() ?: 0.0
        val paused = (ipcGet("pause") as? Boolean) ?: false
        val alive = process?.isAlive == true
        return PlaybackStatus(
            playing = alive && !paused,
            positionMs = (pos * 1000).toLong(),
            durationMs = (dur * 1000).toLong(),
            volumePercent = volume,
        )
    }

    override fun awaitCompletion() {
        completion.await()
    }

    override fun close() {
        stop()
    }

    /** Sends a raw JSON command array over the IPC socket. */
    private fun ipc(commandJson: String) {
        runCatching {
            Socket("127.0.0.1", ipcPort).use { socket ->
                socket.soTimeout = 500
                socket.getOutputStream().bufferedWriter().apply {
                    write("{\"command\":$commandJson}\n")
                    flush()
                }
                socket.getInputStream().bufferedReader().readLine()
            }
        }
    }

    /** Sends a property read and returns the decoded `data` field. */
    private fun ipcGet(property: String): Any? =
        runCatching {
            Socket("127.0.0.1", ipcPort).use { socket ->
                socket.soTimeout = 500
                socket.getOutputStream().bufferedWriter().apply {
                    write("{\"command\":[\"get_property\",\"$property\"],\"request_id\":1}\n")
                    flush()
                }
                val line = socket.getInputStream().bufferedReader().readLine() ?: return@runCatching null
                val data = json.parseToJsonElement(line).jsonObject["data"] ?: return@runCatching null
                when (data) {
                    is JsonPrimitive -> if (data.isString) data.content else data.content.toDoubleOrNull()
                    else -> null
                }
            }
        }.getOrNull()
}
