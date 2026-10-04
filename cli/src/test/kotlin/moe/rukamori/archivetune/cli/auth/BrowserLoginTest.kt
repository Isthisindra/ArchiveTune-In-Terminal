package moe.rukamori.archivetune.cli.auth

import moe.rukamori.archivetune.innertube.utils.parseCookieString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLoginTest {

    // ── cookie header construction ────────────────────────────────────────

    @Test
    fun `youtube header keeps only youtube-domain cookies and dedupes by name`() {
        val cookies =
            listOf(
                CdpCookie("SID", "sid", ".youtube.com"),
                CdpCookie("LOGIN_INFO", "login", ".youtube.com"),
                CdpCookie("SID", "second", ".google.com"), // parallel google copy, dropped
                CdpCookie("__Secure-3PAPISID", "apisid", ".youtube.com"),
                CdpCookie("SSID", "ssid", ".youtube.com"),
                CdpCookie("SAPISID", "google-value", ".google.com"), // dropped
                CdpCookie("NID", "123", ".google.co.id"), // different TLD, dropped
                CdpCookie("sp_dc", "dc", ".spotify.com"), // other service, dropped
                CdpCookie("_ga", "x", ".example.com"), // unrelated, dropped
            )
        val header = BrowserLogin.buildCookieHeader(cookies, BrowserLogin.youtubeDomainFilter)
        assertEquals("SID=sid; LOGIN_INFO=login; __Secure-3PAPISID=apisid; SSID=ssid", header)
    }

    @Test
    fun `youtube header prefers the youtube-domain copy of a duplicated cookie`() {
        // getAllCookies lists every host copy; the youtube.com one must win over
        // the .google.com one because SIDCC/PSIDTS values differ per domain.
        val cookies =
            listOf(
                CdpCookie("SIDCC", "wrong-google", ".google.com"),
                CdpCookie("__Secure-3PSIDTS", "wrong-google", ".google.com"),
                CdpCookie("SIDCC", "right-youtube", ".youtube.com"),
                CdpCookie("__Secure-3PSIDTS", "right-youtube", ".youtube.com"),
                CdpCookie("SID", "g.a000", ".youtube.com"),
                CdpCookie("LOGIN_INFO", "l", ".youtube.com"),
                CdpCookie("SAPISID", "s", ".youtube.com"),
            )
        val header = BrowserLogin.buildCookieHeader(cookies, BrowserLogin.youtubeDomainFilter)
        assertEquals(
            "SIDCC=right-youtube; __Secure-3PSIDTS=right-youtube; SID=g.a000; LOGIN_INFO=l; SAPISID=s",
            header,
        )
    }

    @Test
    fun `spotify header keeps every spotify cookie`() {
        val cookies =
            listOf(
                CdpCookie("sp_dc", "AQ123", ".spotify.com"),
                CdpCookie("sp_key", "KEY456", ".spotify.com"),
                CdpCookie("opt", "out", ".spotify.com"),
                CdpCookie("NID", "nope", ".google.co.id"),
            )
        val header = BrowserLogin.buildCookieHeader(cookies, BrowserLogin.spotifyDomainFilter)
        val map = parseCookieString(header)
        assertEquals("AQ123", map["sp_dc"])
        assertEquals("KEY456", map["sp_key"])
        assertEquals("out", map["opt"])
        assertEquals(3, map.size)
    }

    // ── login readiness ───────────────────────────────────────────────────

    @Test
    fun `youtube login is ready once LOGIN_INFO and an APISID family cookie are present`() {
        assertTrue(BrowserLogin.youTubeLoggedIn("LOGIN_INFO=x; SID=s; __Secure-3PAPISID=p"))
        assertTrue(BrowserLogin.youTubeLoggedIn("LOGIN_INFO=x; SAPISID=p; HSID=h"))
        assertTrue(BrowserLogin.youTubeLoggedIn("LOGIN_INFO=x; __Secure-1PAPISID=p"))
    }

    @Test
    fun `youtube login is not ready without a login cookie`() {
        assertFalse(BrowserLogin.youTubeLoggedIn("SID=s; SAPISID=p"))
        assertFalse(BrowserLogin.youTubeLoggedIn("__Secure-3PAPISID=p"))
        assertFalse(BrowserLogin.youTubeLoggedIn(null))
        assertFalse(BrowserLogin.youTubeLoggedIn(""))
    }

    @Test
    fun `sid is only present on a genuinely signed-in session`() {
        val loggedOut =
            listOf(
                CdpCookie("VISITOR_INFO1_LIVE", "v", ".youtube.com"),
                CdpCookie("PREF", "p", ".youtube.com"),
                CdpCookie("LOGIN_INFO", "l", ".youtube.com"),
                CdpCookie("SAPISID", "s", ".google.com"),
            )
        assertFalse(BrowserLogin.hasSid(loggedOut))

        val signedIn = loggedOut + CdpCookie("SID", "g.a000", ".youtube.com")
        assertTrue(BrowserLogin.hasSid(signedIn))

        // Blank SID values are not a session.
        assertFalse(BrowserLogin.hasSid(loggedOut + CdpCookie("SID", " ", ".youtube.com")))
        assertFalse(BrowserLogin.hasSid(emptyList()))
    }

    @Test
    fun `the full capture shape round-trips into a parseable header`() {
        val cookies =
            listOf(
                CdpCookie("SID", "aaa", ".youtube.com"),
                CdpCookie("__Secure-3PAPISID", "bbb", ".youtube.com"),
                CdpCookie("LOGIN_INFO", "ccc", ".youtube.com"),
                CdpCookie("SAPISID", "ddd", ".youtube.com"),
                CdpCookie("HSID", "eee", ".youtube.com"),
                CdpCookie("SSID", "fff", ".youtube.com"),
                CdpCookie("APISID", "ggg", ".youtube.com"),
                CdpCookie("SIDCC", "hhh", ".youtube.com"),
                CdpCookie("__Secure-3PSIDTS", "iii", ".youtube.com"),
                CdpCookie("PREF", "jjj", ".youtube.com"),
                CdpCookie("SIDCC", "conflicting", ".google.com"),
                CdpCookie("__Secure-3PSIDTS", "conflicting", ".google.com"),
            )
        val header = BrowserLogin.buildCookieHeader(cookies, BrowserLogin.youtubeDomainFilter)
        assertTrue(BrowserLogin.youTubeLoggedIn(header))
        val map = parseCookieString(header)
        assertEquals("aaa", map["SID"])
        assertEquals("bbb", map["__Secure-3PAPISID"])
        assertEquals("hhh", map["SIDCC"]) // youtube.com copy beats google.com
        assertEquals("iii", map["__Secure-3PSIDTS"])
        assertFalse(map.containsKey("conflicting"))
    }
}