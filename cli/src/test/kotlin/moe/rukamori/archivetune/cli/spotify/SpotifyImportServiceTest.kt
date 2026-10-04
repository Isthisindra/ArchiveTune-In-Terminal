package moe.rukamori.archivetune.cli.spotify

import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun <T> blocking(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

class SpotifyImportServiceTest {

    // ── playlist id parsing ──────────────────────────────────────────────

    @Test
    fun `parses a full open spotify playlist url`() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyImportService.parsePlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `parses a playlist url with query parameters`() {
        assertEquals(
            "37i9dQZF1DXcBWIGoYBM5M",
            SpotifyImportService.parsePlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123&pt=abc"),
        )
    }

    @Test
    fun `parses a spotify URI`() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyImportService.parsePlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `accepts a bare playlist id`() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyImportService.parsePlaylistId("37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun `rejects track urls and album urls`() {
        assertNull(SpotifyImportService.parsePlaylistId("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(SpotifyImportService.parsePlaylistId("https://open.spotify.com/album/1kTMgvdK0pmi0CIQ7Joj7Q?si=x"))
    }

    @Test
    fun `rejects garbage and blanks`() {
        assertNull(SpotifyImportService.parsePlaylistId(""))
        assertNull(SpotifyImportService.parsePlaylistId("   "))
        assertNull(SpotifyImportService.parsePlaylistId("abc"))
    }

    // ── track resolution guards (offline, no network) ────────────────────

    @Test
    fun `local files are never matched`() {
        val track = SpotifyTrack(id = "local1", name = "Some Song", isLocal = true)
        assertNull(blocking { SpotifyImportService.resolveTrack(track) })
    }

    @Test
    fun `tracks with blank names are never matched`() {
        val track = SpotifyTrack(id = "abc123", name = "   ")
        assertNull(blocking { SpotifyImportService.resolveTrack(track) })
    }
}