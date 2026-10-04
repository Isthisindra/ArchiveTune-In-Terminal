package moe.rukamori.archivetune.cli.spotify

import moe.rukamori.archivetune.cli.model.AlbumRef
import moe.rukamori.archivetune.cli.model.Track
import moe.rukamori.archivetune.cli.model.toTrack
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.spotify.models.SpotifyUser

/**
 * Turns Spotify cookies into a working session and maps Spotify tracks onto
 * YouTube Music tracks, mirroring the app's `SpotifyPlaybackResolver`.
 *
 * The mapping is the interesting part: each Spotify track is searched on
 * YouTube Music (song filter) and the best candidate is picked by the same
 * title/artist/duration bigram scorer the app uses, with a 0.35 floor.
 */
object SpotifyImportService {
    /** Below this score a candidate is treated as "not found on YouTube". */
    const val MIN_MATCH_THRESHOLD = 0.35

    /** Page size used when paging Spotify lists. */
    const val PAGE_SIZE = 100

    /**
     * Fetches a fresh web-player access token for the given cookies and
     * verifies the account, so the caller knows the session is real before
     * importing anything.
     */
    suspend fun connect(spDc: String, spKey: String): Result<SpotifyUser> =
        runCatching {
            val token = SpotifyAuth.fetchAccessToken(spDc, spKey).getOrThrow()
            if (token.isAnonymous || token.accessToken.isBlank()) {
                throw Spotify.SpotifyException(401, "sp_dc cookie was rejected (anonymous token)")
            }
            Spotify.accessToken = token.accessToken
            Spotify.me().getOrThrow()
        }

    private val PLAYLIST_ID = Regex("^[A-Za-z0-9]{8,}$")
    private val PLAYLIST_REF = Regex("(?:spotify:playlist:|/playlist/)([A-Za-z0-9]+)")

    /**
     * Extracts a playlist id from a full URL, a spotify:playlist: URI or a
     * bare id. Returns null for anything that is not a playlist reference.
     */
    fun parsePlaylistId(target: String): String? {
        val trimmed = target.trim()
        PLAYLIST_REF.find(trimmed)?.let { return it.groupValues[1] }
        return trimmed.takeIf { it.matches(PLAYLIST_ID) }
    }

    /**
     * Finds the best YouTube Music match for one Spotify track. Returns null
     * when nothing is good enough (local files, odd titles, no match).
     */
    suspend fun resolveTrack(track: SpotifyTrack): Track? {
        if (track.isLocal || track.name.isBlank()) return null
        val searchResult =
            YouTube.search(
                query = SpotifyMapper.buildSearchQuery(track),
                filter = YouTube.SearchFilter.FILTER_SONG,
                useAccountContext = false,
            ).getOrNull() ?: return null
        val candidates = searchResult.items.filterIsInstance<SongItem>().distinctBy { it.id }
        if (candidates.isEmpty()) return null

        val precomputed =
            SpotifyMapper.precompute(
                title = track.name,
                artist = track.artists.joinToString(" ") { it.name },
                durationMs = track.durationMs,
            )
        val (best, score) =
            candidates
                .map { candidate ->
                    candidate to
                        SpotifyMapper.matchScorePrecomputed(
                            precomputed = precomputed,
                            candidateTitle = candidate.title,
                            candidateArtist = candidate.artists.joinToString(" ") { it.name },
                            candidateDurationSec = candidate.duration,
                        )
                }.maxByOrNull { it.second }
                ?: return null
        if (score < MIN_MATCH_THRESHOLD) return null

        val base = best.toTrack()
        return base.copy(
            // Spotify's duration and album art are the ground truth while the
            // video id comes from the YouTube match.
            duration = if (track.durationMs > 0) track.durationMs / 1000 else base.duration,
            album =
                track.album?.takeIf { it.name.isNotBlank() }
                    ?.let { AlbumRef(id = it.id.ifBlank { null }, title = it.name) }
                    ?: base.album,
            thumbnailUrl = SpotifyMapper.getTrackThumbnail(track) ?: base.thumbnailUrl,
        )
    }
}