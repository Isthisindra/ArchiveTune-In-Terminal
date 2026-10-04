package moe.rukamori.archivetune.cli.model

import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem

/**
 * Playable track. Lifted from the app's `MediaMetadata` with the two Android
 * dependencies removed (the `@Immutable` Compose annotation and the Room
 * `SongEntity` mapper), so it stays a plain serializable model.
 */
@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val duration: Int? = null,
    val thumbnailUrl: String? = null,
    val setVideoId: String? = null,
    val explicit: Boolean = false,
    val isMusicVideo: Boolean = false,
    val isPodcast: Boolean = false,
    val albumArtist: ArtistRef? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
) {
    val artistString: String get() = artists.joinToString(", ") { it.name }

    val durationText: String
        get() = duration?.let { formatDuration(it * 1000L) } ?: "--:--"
}

@Serializable
data class ArtistRef(
    val id: String? = null,
    val name: String,
)

@Serializable
data class AlbumRef(
    val id: String? = null,
    val title: String,
)

/** Format milliseconds as `m:ss` or `h:mm:ss`. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun SongItem.toTrack(trackNumber: Int? = null): Track =
    Track(
        // SongItem.id is already the video id, with setVideoId used for
        // playlist-entry set videos that map to a different track.
        id = setVideoId?.takeIf { it.isNotBlank() } ?: id,
        title = title,
        artists = artists.map { ArtistRef(it.id, it.name) },
        album = album?.let { AlbumRef(it.id, it.name) },
        duration = duration,
        thumbnailUrl = thumbnail.normalizedOrNull(),
        explicit = explicit,
        isPodcast = isPodcast,
        trackNumber = trackNumber,
    )

private fun String.normalizedOrNull(): String? =
    if (isBlank()) null else if (startsWith("//")) "https:$this" else this

fun AlbumItem.toAlbumRef(): AlbumRef = AlbumRef(browseId, title)

fun ArtistItem.toArtistRef(): ArtistRef = ArtistRef(id, title)

fun PlaylistItem.toPlaylistRef(): AlbumRef = AlbumRef(id, title)
