package moe.rukamori.archivetune.cli.canvas

import com.sun.net.httpserver.HttpServer
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class CanvasServiceTest {

    // ── save target resolution ───────────────────────────────────────────

    @Test
    fun `a directory arg becomes Artist - Title mp4`() {
        val target = CanvasService.saveTarget("clips", "Nightcall", "Kavinsky")
        assertEquals("clips", target.parentFile?.name)
        assertEquals("Kavinsky - Nightcall.mp4", target.name)
    }

    @Test
    fun `file names are sanitized for Windows`() {
        val target = CanvasService.saveTarget("clips", "A/B*C:Test?", "Artist")
        assertEquals("Artist - A_B_C_Test_.mp4", target.name)
    }

    @Test
    fun `an exact file path wins`() {
        val target = CanvasService.saveTarget("out/track.mp4", "Nightcall", "Kavinsky")
        assertEquals("track.mp4", target.name)
        assertEquals("out", target.parentFile?.name)
    }

    @Test
    fun `missing names fall back to canvas mp4`() {
        val target = CanvasService.saveTarget("clips", null, null)
        assertEquals("canvas.mp4", target.name)
    }

    // ── json output ──────────────────────────────────────────────────────

    @Test
    fun `json carries the readable fields only when present`() {
        val artwork =
            CanvasArtwork(
                source = moe.rukamori.archivetune.canvas.CanvasSource.BETTER_LYRICS,
                name = "Nightcall",
                artist = "Kavinsky",
                albumId = "abc123",
                albumName = "OutRun",
                static = "https://img/cover.jpg",
                videoUrl = "https://cdn/canvas.mp4",
            )
        val json = CanvasService.json("Nightcall - Kavinsky", artwork)
        assertTrue(json.contains("\"source\": \"BETTER_LYRICS\""))
        assertTrue(json.contains("\"video\": \"https://cdn/canvas.mp4\""))
        assertTrue(json.contains("\"album\": \"OutRun\""))
        assertTrue(json.contains("\"query\": \"Nightcall - Kavinsky\""))
        // No vertical url present, so the vertical key must be absent.
        assertTrue(!json.contains("videoVertical"))
    }

    // ── download ─────────────────────────────────────────────────────────

    @Test
    fun `download streams the body and returns the byte count`() {
        val payload = ByteArray(8192) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/clip.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dest = Files.createTempFile("canvas-", ".mp4").toFile()
            val bytes = CanvasService.download("http://127.0.0.1:${server.address.port}/clip.mp4", dest)
            assertEquals(payload.size.toLong(), bytes)
            assertTrue(payload.contentEquals(Files.readAllBytes(dest.toPath())))
        } finally {
            server.stop(0)
        }
    }
}