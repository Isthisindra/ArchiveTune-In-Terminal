package moe.rukamori.archivetune.cli.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.innertube.utils.hasCompleteYouTubeLoginCookies
import moe.rukamori.archivetune.innertube.utils.parseCookieString
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** One cookie as reported by a CDP `Network.getAllCookies` response. */
data class CdpCookie(val name: String, val value: String, val domain: String)

/**
 * Built-in browser login: opens a real Edge/Chrome window (fresh profile),
 * waits for the user to sign in like they normally would, then captures the
 * session cookies through the DevTools (CDP) WebSocket. No DevTools, no cookie
 * copying, no pasting.
 *
 * `loginYouTube` captures every `youtube.com`/`google.com` cookie into one
 * header for `account.cookie`, and `loginSpotify` reads `sp_dc`/`sp_key` after
 * an open.spotify.com login.
 */
object BrowserLogin {
    private const val LOGIN_URL_YOUTUBE = "https://www.youtube.com"
    private const val LOGIN_URL_SPOTIFY = "https://open.spotify.com"
    private const val DEFAULT_TIMEOUT_MS = 10 * 60_000L
    private const val POLL_MS = 2_000L
    private const val BOOT_POLL_MS = 250L
    private const val BOOT_ATTEMPTS = 120
    private const val WAIT_LOG_EVERY = 6

    /**
     * How long to sit after the login cookies are first spotted before taking
     * the final snapshot. The sign-in redirect chain lands several Set-Cookie
     * responses over a second or two; grabbing them on the first sighting can
     * miss SID/HSID/SSID and the account check then refuses the session.
     */
    private const val SETTLE_MS = 4_000L

    /**
     * Cookies Google actually accepts for a signed-in youtube.com session. A
     * browser only ever sends cookies whose domain matches the request host, so
     * the header for music.youtube.com carries the `.youtube.com` set - SID,
     * HSID/SSID, the APISID family + SAPISID (same value on both domains),
     * LOGIN_INFO, SIDCC and the PSIDTS tokens. The parallel `.google.com`
     * copies carry *different* SIDCC/PSIDTS values that YouTube then reads as a
     * signed-out session, so they must stay out of the header.
     */
    val youtubeDomainFilter: (CdpCookie) -> Boolean = { cookie ->
        val domain = cookie.domain.removePrefix(".")
        domain.equals("youtube.com", ignoreCase = true) ||
            domain.endsWith(".youtube.com", ignoreCase = true)
    }

    val spotifyDomainFilter: (CdpCookie) -> Boolean = { cookie ->
        cookie.domain.contains("spotify.com")
    }

    /**
     * Signs a browser into Google/YouTube and returns the merged cookie header,
     * or null when no browser was found, the login was cancelled or timed out.
     */
    fun loginYouTube(
        log: (String) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? =
        runBlocking {
            val cookies =
                capture(
                    log = log,
                    loginUrl = LOGIN_URL_YOUTUBE,
                    keep = youtubeDomainFilter,
                    // LOGIN_INFO + an APISID-family cookie are the guest-proof
                    // baseline; SID only exists on a genuinely signed-in session
                    // (a logged-out profile never has it), so require it too.
                    isDone = { kept ->
                        val header = buildCookieHeader(kept, youtubeDomainFilter)
                        youTubeLoggedIn(header) && hasSid(kept)
                    },
                    timeoutMs = timeoutMs,
                ) ?: return@runBlocking null
            buildCookieHeader(cookies, youtubeDomainFilter).takeIf(::youTubeLoggedIn)
        }

    /** True when a signed-in session's `SID` cookie is present. */
    internal fun hasSid(cookies: List<CdpCookie>): Boolean =
        cookies.any { it.name == "SID" && it.value.isNotBlank() }

    /**
     * Signs a browser into Spotify and returns `sp_dc`/`sp_key`, or null when
     * no browser was found, the login was cancelled or timed out.
     */
    fun loginSpotify(
        log: (String) -> Unit,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Pair<String, String>? =
        runBlocking {
            val cookies =
                capture(
                    log = log,
                    loginUrl = LOGIN_URL_SPOTIFY,
                    keep = spotifyDomainFilter,
                    isDone = { kept -> kept.any { it.name == "sp_dc" && it.value.isNotBlank() } },
                    timeoutMs = timeoutMs,
                ) ?: return@runBlocking null
            val map = parseCookieString(buildCookieHeader(cookies, spotifyDomainFilter))
            val dc = map["sp_dc"] ?: return@runBlocking null
            dc to map["sp_key"].orEmpty()
        }

    /** Joins the kept cookies into a `name=value; name=value` header. */
    fun buildCookieHeader(cookies: List<CdpCookie>, keep: (CdpCookie) -> Boolean): String =
        cookies
            .asSequence()
            .filter(keep)
            .distinctBy { it.name }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }

    /** True when the header has the login trio YouTube's SAPISIDHASH needs. */
    fun youTubeLoggedIn(cookie: String?): Boolean = hasCompleteYouTubeLoginCookies(cookie)

    private suspend fun capture(
        log: (String) -> Unit,
        loginUrl: String,
        keep: (CdpCookie) -> Boolean,
        isDone: (List<CdpCookie>) -> Boolean,
        timeoutMs: Long,
    ): List<CdpCookie>? {
        val executable = findBrowser() ?: run {
            log(
                "No Edge or Chrome was found. Install Edge/Chrome, or paste the cookie " +
                    "manually with --cookie.",
            )
            return null
        }
        val port = freePort()
        val profile = Files.createTempDirectory("archivetune-auth")
        val process = launchBrowser(executable, port, profile, loginUrl) ?: run {
            log("Could not start the browser window.")
            return null
        }
        try {
            return HttpClient(CIO) { install(WebSockets) }.use { client ->
                withTimeoutOrNull(timeoutMs) {
                    log("A browser window opened - log in there with any account. (Ctrl+C to cancel)")
                    log(
                        "No window visible? It may be grouped under the existing Edge/Chrome icon " +
                            "in the taskbar - it can take a few seconds to appear.",
                    )
                    val path =
                        waitForPagePath(client, port)
                            ?: run {
                                log(
                                    "The browser window did not become ready. Look for it under the " +
                                        "Edge/Chrome icon in the taskbar; if it is not there, close " +
                                        "other browser windows and run `auth youtube` again.",
                                )
                                return@withTimeoutOrNull null
                            }
                    var result: List<CdpCookie>? = null
                    var polls = 0
                    client.webSocket(host = "127.0.0.1", port = port, path = path) {
                        navigate(loginUrl)
                        var id = 0
                        while (isActive && result == null) {
                            val requestId = ++id
                            outgoing.send(
                                Frame.Text("""{"id":$requestId,"method":"Network.getAllCookies"}"""),
                            )
                            val cookies = readCookieResponse(requestId)
                            if (cookies != null) {
                                val kept = cookies.filter(keep)
                                if (kept.isNotEmpty() && isDone(kept)) {
                                    // First sighting can be mid-redirect; let the
                                    // rest of the Set-Cookie chain land before
                                    // taking the final, complete snapshot.
                                    delay(SETTLE_MS)
                                    val settleId = ++id
                                    outgoing.send(
                                        Frame.Text("""{"id":$settleId,"method":"Network.getAllCookies"}"""),
                                    )
                                    result = readCookieResponse(settleId)?.filter(keep)
                                    if (result != null) {
                                        log("Session cookies captured - verifying your account...")
                                    }
                                } else if (++polls % WAIT_LOG_EVERY == 0) {
                                    log("Still waiting for you to log in... (Ctrl+C to cancel)")
                                }
                            }
                            if (result == null) delay(POLL_MS)
                        }
                    }
                    result
                }
            }
        } finally {
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
            runCatching { profile.toFile().deleteRecursively() }
        }
    }

    private suspend fun DefaultWebSocketSession.navigate(url: String) {
        runCatching {
            outgoing.send(Frame.Text("""{"id":0,"method":"Page.navigate","params":{"url":"$url"}}"""))
        }
    }

    /** Reads the JSON frames until the response with [requestId] arrives. */
    internal suspend fun DefaultWebSocketSession.readCookieResponse(
        requestId: Int,
    ): List<CdpCookie>? {
        while (isActive) {
            val frame =
                try {
                    withTimeoutOrNull(15_000) { incoming.receive() } ?: return null
                } catch (_: Exception) {
                    return null
                }
            if (frame !is Frame.Text) continue
            val json = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull()
                ?: continue
            if (json["id"]?.jsonPrimitive?.intOrNull != requestId) continue
            val cookies = json["result"]?.jsonObject?.get("cookies")?.jsonArray ?: continue
            return cookies.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val domain = obj["domain"]?.jsonPrimitive?.contentOrNull.orEmpty()
                CdpCookie(name, value, domain)
            }
        }
        return null
    }

    internal suspend fun waitForPagePath(client: HttpClient, port: Int): String? {
        var attempts = 0
        while (attempts < BOOT_ATTEMPTS) {
            val body =
                runCatching { client.get("http://127.0.0.1:$port/json/list").bodyAsText() }
                    .getOrNull()
            pageWsPath(body, port)?.let { return it }
            attempts++
            delay(BOOT_POLL_MS)
        }
        return null
    }

    /** The WS path of the first page target, e.g. `/devtools/page/0123...`. */
    private fun pageWsPath(body: String?, port: Int): String? =
        runCatching {
            val wsUrl =
                Json.parseToJsonElement(body.orEmpty()).jsonArray
                    .firstNotNullOfOrNull { element ->
                        val obj = element.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "page") {
                            obj["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    }
                    ?: return null
            wsUrl.substringAfter("ws://127.0.0.1:$port").takeIf { it.isNotBlank() }
        }.getOrNull()

    internal fun findBrowser(): String? {
        System.getenv("ARCHIVETUNE_BROWSER")?.takeIf { File(it).isFile }?.let { return it }
        val candidates =
            listOf(
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
                "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            )
        return candidates.firstOrNull { File(it).isFile } ?: whereIs("chrome") ?: whereIs("msedge")
    }

    private fun whereIs(name: String): String? =
        runCatching {
            val process = ProcessBuilder("where", name).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readLine()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && File(it).isFile }
        }.getOrNull()

    internal fun launchBrowser(executable: String, port: Int, profile: Path, url: String): Process? =
        runCatching {
            ProcessBuilder(
                executable,
                "--remote-debugging-port=$port",
                // Chrome/Edge >= 111 rejects CDP WebSocket connections that do
                // not come from an allowed origin unless this is set.
                "--remote-allow-origins=*",
                "--user-data-dir=$profile",
                "--no-first-run",
                "--no-default-browser-check",
                "--no-restore-session-state",
                "--disable-features=msEdgeFirstRunExperience",
                // Windows tends to open the fresh window behind the ones that are
                // already up, where it is easy to miss; maximize it instead.
                "--start-maximized",
                url,
            ).start()
        }.getOrNull()

    internal fun freePort(): Int = ServerSocket(0).use { it.localPort }
}