package moe.rukamori.archivetune.cli.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cli.auth.BrowserLogin.readCookieResponse
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Opt-in end-to-end smoke test for the DevTools plumbing behind the built-in
 * browser login. Skips itself unless the `ARCHIVETUNE_CDP_SMOKE=true`
 * environment variable is set.
 *
 * It launches the real Edge/Chrome (fresh profile), waits for the DevTools
 * endpoint, connects over WebSocket and round-trips a `Network.getAllCookies`
 * CDP call - the same path `auth youtube` / `spotify auth` use.
 *
 * Run with:
 *   gradlew :cli:test --tests "*CdpSmokeTest"
 * (with ARCHIVETUNE_CDP_SMOKE=true exported)
 */
class CdpSmokeTest {
    @Test
    fun `browser launch and CDP cookie round trip`() {
        if (System.getenv("ARCHIVETUNE_CDP_SMOKE") != "true") return

        val browser = BrowserLogin.findBrowser()
        assumeTrue("A Chromium browser (Edge/Chrome) is required for this test", browser != null)
        println("[cdp-smoke] browser: $browser")

        val port = BrowserLogin.freePort()
        val profile = Files.createTempDirectory("archivetune-cdp-smoke")
        val process = BrowserLogin.launchBrowser(browser!!, port, profile, "about:blank")
        assumeTrue("Browser should start", process != null)
        try {
            var pagePath: String? = null
            var cookieCount: Int? = null
            runBlocking {
                HttpClient(CIO) { install(WebSockets) }.use { client ->
                    pagePath = BrowserLogin.waitForPagePath(client, port)
                    assertNotNull("DevTools /json/list should expose a page target", pagePath)
                    client.webSocket(host = "127.0.0.1", port = port, path = pagePath!!) {
                        outgoing.send(
                            Frame.Text("""{"id":1,"method":"Network.getAllCookies"}"""),
                        )
                        cookieCount = readCookieResponse(1)?.size
                    }
                }
            }
            assertNotNull("CDP should answer Network.getAllCookies", cookieCount)
            println("[cdp-smoke] got cookie array with $cookieCount entries")
        } finally {
            runCatching { process!!.destroy() }
            runCatching { process!!.destroyForcibly() }
            runCatching { profile.toFile().deleteRecursively() }
        }
    }
}