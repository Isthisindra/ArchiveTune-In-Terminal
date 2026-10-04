package moe.rukamori.archivetune.cli.music

import moe.rukamori.archivetune.cli.model.AlbumRef
import moe.rukamori.archivetune.cli.model.ArtistRef
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.toTrack
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.pages.SearchResult

/** What a search can be narrowed to, mirroring the app's search tabs. */
enum class SearchScope(val label: String) {
    ALL("all"),
    SONGS("songs"),
    VIDEOS("videos"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    PODCASTS("podcasts"),
    PLAYLISTS("playlists"),
    ;

    val filter: SearchFilter
        get() =
            when (this) {
                // An empty params value is what InnerTube expects for "no filter".
                ALL -> SearchFilter("")
                SONGS -> SearchFilter.FILTER_SONG
                VIDEOS -> SearchFilter.FILTER_VIDEO
                ALBUMS -> SearchFilter.FILTER_ALBUM
                ARTISTS -> SearchFilter.FILTER_ARTIST
                PODCASTS -> SearchFilter.FILTER_PODCAST
                PLAYLISTS -> SearchFilter.FILTER_COMMUNITY_PLAYLIST
            }
}

/** Albums/playlists/artist rows rendered by the list commands. */
data class CollectionRef(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val year: Int? = null,
)

data class SearchPageResult(
    val songs: List<Track>,
    val collections: List<CollectionRef>,
    val artists: List<CollectionRef>,
    val continuation: String?,
)

data class AlbumResult(
    val album: CollectionRef,
    val songs: List<Track>,
    val otherVersions: List<CollectionRef>,
)

data class ArtistResult(
    val artist: CollectionRef,
    val description: String?,
    val songs: List<Track>,
    val albums: List<CollectionRef>,
    val playlists: List<CollectionRef>,
    val relatedArtists: List<CollectionRef>,
)

data class PlaylistResult(
    val playlist: CollectionRef,
    val author: String?,
    val songs: List<Track>,
    val continuation: String?,
)

/**
 * Thin, CLI-shaped wrappers over the shared [YouTube] facade.
 *
 * The facade returns the app's `YTItem` hierarchy; this layer flattens it into
 * [Track]/[CollectionRef] and splits results by kind so commands can render
 * mixed search results without re-inspecting sealed-class types.
 */
class CatalogService {
    suspend fun search(
        query: String,
        scope: SearchScope = SearchScope.ALL,
        limit: Int = 20,
        continuation: String? = null,
    ): Result<SearchPageResult> {
        if (continuation != null) {
            return YouTube.searchContinuation(continuation).map { it.toPage(limit) }
        }
        return YouTube.search(query, scope.filter).map { it.toPage(limit) }
    }

    suspend fun album(browseId: String, limit: Int = 200): Result<AlbumResult> =
        YouTube.album(browseId).map { page ->
            AlbumResult(
                album = page.album.toCollectionRef(),
                songs = page.songs.take(limit).mapIndexed { index, song -> song.toTrack(index + 1) },
                otherVersions = page.otherVersions.map { it.toCollectionRef() },
            )
        }

    suspend fun artist(browseId: String, limit: Int = 200): Result<ArtistResult> =
        YouTube.artist(browseId).map { page ->
            val songs = mutableListOf<Track>()
            val albums = mutableListOf<CollectionRef>()
            val playlists = mutableListOf<CollectionRef>()
            val related = mutableListOf<CollectionRef>()
            page.sections.forEach { section ->
                section.items.forEach { item ->
                    when (item) {
                        is SongItem -> if (songs.size < limit) songs += item.toTrack(songs.size + 1)
                        is AlbumItem -> albums += item.toCollectionRef()
                        is PlaylistItem -> playlists += item.toCollectionRef()
                        is ArtistItem -> related += item.toCollectionRef()
                        else -> Unit
                    }
                }
            }
            ArtistResult(
                artist = page.artist.toCollectionRef(),
                description = page.description,
                songs = songs,
                albums = albums,
                playlists = playlists,
                relatedArtists = related,
            )
        }

    suspend fun playlist(playlistId: String, limit: Int = 500): Result<PlaylistResult> =
        YouTube.playlist(playlistId).map { page ->
            PlaylistResult(
                playlist = page.playlist.toCollectionRef(),
                author = page.playlist.author?.name,
                songs = page.songs.take(limit).mapIndexed { index, song -> song.toTrack(index + 1) },
                continuation = page.songsContinuation ?: page.continuation,
            )
        }

    suspend fun playlistMore(continuation: String, limit: Int = 500): Result<List<Track>> =
        YouTube.playlistContinuation(continuation).map { page ->
            page.songs.mapIndexed { index, song -> song.toTrack(index + 1) }.take(limit)
        }

    suspend fun song(videoId: String, client: YouTubeClient): Result<Track> {
        // `player` carries the authoritative title/duration for a video id;
        // `getMediaInfo` adds the channel name. Either one may fail, so treat
        // both as best-effort enrichment around a minimal track.
        val player = YouTube.player(videoId = videoId, client = client).getOrNull()
        val info = YouTube.getMediaInfo(videoId).getOrNull()
        val details = player?.videoDetails
        val author = details?.author ?: info?.author
        return Result.success(
            Track(
                id = videoId,
                title = details?.title ?: info?.title ?: videoId,
                artists = author?.let { listOf(ArtistRef(info?.authorId, it)) }.orEmpty(),
                duration = details?.lengthSeconds?.toIntOrNull(),
                thumbnailUrl = details?.thumbnail?.thumbnails?.lastOrNull()?.url,
            ),
        )
    }

    private fun SearchResult.toPage(limit: Int): SearchPageResult {
        val songs = mutableListOf<Track>()
        val collections = mutableListOf<CollectionRef>()
        val artists = mutableListOf<CollectionRef>()
        items.forEach { item ->
            when (item) {
                is SongItem -> if (songs.size < limit) songs += item.toTrack()
                is AlbumItem -> collections += item.toCollectionRef()
                is PlaylistItem -> collections += item.toCollectionRef()
                is ArtistItem -> artists += item.toCollectionRef()
                else -> Unit
            }
        }
        return SearchPageResult(songs, collections, artists, continuation)
    }
}

private fun AlbumItem.toCollectionRef() =
    CollectionRef(
        id = browseId,
        title = title,
        subtitle = artists?.joinToString(", ") { it.name }.orEmpty(),
        thumbnailUrl = thumbnail,
        year = year,
    )

private fun PlaylistItem.toCollectionRef() =
    CollectionRef(
        id = id,
        title = title,
        subtitle = author?.name.orEmpty(),
        thumbnailUrl = thumbnail,
    )

private fun ArtistItem.toCollectionRef() =
    CollectionRef(
        id = id,
        title = title,
        subtitle =
            listOfNotNull(
                subscriberCountText?.let { "$it subscribers" },
                monthlyListenerCountText?.let { "$it monthly listeners" },
            ).joinToString(" · "),
        thumbnailUrl = thumbnail,
    )

/** Convenience for the `song` command's album display. */
fun Track.withAlbum(title: String?, id: String?): Track =
    if (album != null) this else copy(album = title?.let { AlbumRef(id, it) })
