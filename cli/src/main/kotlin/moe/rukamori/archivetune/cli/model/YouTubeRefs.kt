package moe.rukamori.archivetune.cli.model

import java.net.URI

/**
 * Helpers for turning whatever the user typed - a bare id, a `youtu.be` link, a
 * watch URL with a `list=` parameter, or a browse URL - into the ids the
 * InnerTube client expects.
 */
object YouTubeRefs {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val PLAYLIST_ID = Regex("^(PL|OLAK5uy_|RDCLAK5uy_|VL)[A-Za-z0-9_-]{10,}$")
    private val BROWSE_ID = Regex("^(MPREb_|UC|UCF0u)[A-Za-z0-9_-]{10,}$")

    /** Extracts a video id from a watch/youtu.be URL, or accepts a bare video id. */
    fun videoId(input: String): String? {
        val trimmed = input.trim()
        if (VIDEO_ID.matches(trimmed)) return trimmed
        val query = queryOf(trimmed) ?: return null
        query["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { return it }
        query["video_id"]?.takeIf { VIDEO_ID.matches(it) }?.let { return it }
        val host = hostOf(trimmed) ?: return null
        if (host.endsWith("youtu.be")) {
            val path = pathOf(trimmed).trim('/')
            if (VIDEO_ID.matches(path)) return path
        }
        return null
    }

    /** Extracts a `list=` playlist id from any YouTube URL, or accepts a bare playlist id. */
    fun playlistId(input: String): String? {
        val trimmed = input.trim()
        val query = queryOf(trimmed)
        if (query != null) {
            query["list"]?.takeIf { it.isNotBlank() }?.let { return it }
            return null
        }
        return trimmed.takeIf { PLAYLIST_ID.matches(it) }
    }

    /** Extracts an album (`MPREb_`) or channel (`UC`) browse id. */
    fun browseId(input: String): String? {
        val trimmed = input.trim()
        val query = queryOf(trimmed)
        if (query != null) {
            // `playlist?list=OLAK5uy_...` addresses an album by its auto playlist id.
            query["list"]?.takeIf { it.startsWith("OLAK5uy_") }?.let { return it }
            val path = pathOf(trimmed).trim('/')
            return path.takeIf { BROWSE_ID.matches(it) }
        }
        return trimmed.takeIf { BROWSE_ID.matches(it) }
    }

    private fun uriOf(input: String): URI? =
        runCatching { URI(input.trim()) }.getOrNull()?.takeIf { it.scheme != null }

    private fun hostOf(input: String): String? = uriOf(input)?.host

    private fun pathOf(input: String): String = uriOf(input)?.path.orEmpty()

    private fun queryOf(input: String): Map<String, String>? {
        val uri = uriOf(input) ?: return null
        val raw = uri.rawQuery ?: return emptyMap()
        return raw.split('&')
            .mapNotNull { pair ->
                if (pair.isEmpty()) null
                else {
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    key to java.net.URLDecoder.decode(value, Charsets.UTF_8)
                }
            }.toMap()
    }
}

/** `artist - title` for display, with a fallback when there is no artist. */
fun Track.describe(): String = if (artists.isEmpty()) title else "$artistString - $title"
