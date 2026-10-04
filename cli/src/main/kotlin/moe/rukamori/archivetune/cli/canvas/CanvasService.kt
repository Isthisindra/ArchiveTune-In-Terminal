package moe.rukamori.archivetune.cli.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The pieces of the `canvas` command that are pure enough to unit test:
 * picking the save location, shaping the JSON output and streaming a canvas
 * video to disk.
 */
object CanvasService {
    private const val USER_AGENT = "ArchiveTuneCLI/1.0"

    /** Characters that are never legal in a Windows or POSIX file name. */
    private val BAD_NAME_CHARS = Regex("[<>:\"/\\\\|?*\u0000-\u001F]")

    /**
     * Resolves `--save <arg>` to a concrete file. When [arg] already has a
     * media extension it is treated as a file path; otherwise it is a
     * directory and the file is named `Artist - Title.mp4`.
     */
    fun saveTarget(arg: String, songName: String?, artist: String?): File {
        val trimmed = arg.trim()
        val raw = File(trimmed).absoluteFile
        if (trimmed.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS) {
            return raw
        }
        val base = listOfNotNull(artist, songName).joinToString(" - ").ifBlank { "canvas" }
        val safe = BAD_NAME_CHARS.replace(base, "_")
        return File(raw, "$safe.mp4")
    }

    /** Renders an artwork as the JSON object behind `-f json`. */
    fun json(query: String?, artwork: CanvasArtwork): String {
        val obj =
            buildJsonObject {
                query?.let { put("query", it) }
                put("source", artwork.source?.name ?: "ALL")
                artwork.name?.let { put("name", it) }
                artwork.artist?.let { put("artist", it) }
                artwork.albumName?.let { put("album", it) }
                artwork.albumId?.let { put("albumId", it) }
                artwork.static?.let { put("image", it) }
                artwork.preferredAnimationUrl?.let { put("video", it) }
                artwork.preferredVerticalAnimationUrl?.let { put("videoVertical", it) }
            }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Streams [url] into [dest] and returns the number of bytes written.
     * Throws [IOException] on transport problems and non-2xx responses.
     */
    fun download(url: String, dest: File): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "video/mp4,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code while downloading canvas video")
            }
            dest.parentFile?.mkdirs()
            val bytes =
                connection.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private val MEDIA_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv")
}