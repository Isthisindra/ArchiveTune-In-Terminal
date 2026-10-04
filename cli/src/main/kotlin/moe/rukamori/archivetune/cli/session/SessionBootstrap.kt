package moe.rukamori.archivetune.cli.session

import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.cli.config.AccountConfig
import moe.rukamori.archivetune.cli.config.CliConfig
import moe.rukamori.archivetune.innertube.NetworkGatekeeper
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.lastfm.LastFM
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Applies CLI configuration onto the shared singletons ([YouTube] for playback,
 * [LastFM] for scrobbling). Must be called once before any request, and before
 * [moe.rukamori.archivetune.innertube.NewPipeUtils] is first touched (its
 * `init` snapshots the stream proxy once).
 *
 * [onLog] receives the `[session] ...` progress lines the interactive startup
 * strip shows under its bar; the default client forwards them to stderr only
 * under `--verbose`. [save] persists the updated config (currently: the fetched
 * visitor data) so the next launch does not pay for another `sw.js_data`
 * download.
 */
object SessionBootstrap {
    /**
     * How long a cached [AccountConfig.visitorData] is trusted before the CLI
     * re-downloads `sw.js_data` to refresh it. Visitor data rotates slowly and
     * stale values are still accepted by InnerTube, so a week keeps repeated
     * startups off the network without risking staleness.
     */
    const val VISITOR_DATA_TTL_MS: Long = 7 * 24 * 60 * 60 * 1000L

    fun apply(
        config: CliConfig,
        onLog: (String) -> Unit = {},
        save: (CliConfig) -> Unit = {},
    ): PlaybackAuthState {
        // Shipped "blocked" for unofficial Android builds; a CLI is not affected.
        NetworkGatekeeper.setConnectionBlocked(false)

        // The canvas module's Apple Music lookups print a wall of debug lines
        // to stdout; keep CLI output pipeable (specially with -f json).
        AppleMusicProvider.isDebugLoggingEnabled = false

        val network = config.network
        network.proxy?.let { YouTube.proxy = parseProxy(it) }
        network.proxyUsername?.let { YouTube.proxyUsername = it }
        network.proxyPassword?.let { YouTube.proxyPassword = it }
        network.dnsOverHttps?.let { YouTube.dns = YouTube.createDnsOverHttps(it) }
        YouTube.streamBypassProxy = network.streamBypassProxy

        val account = config.account
        YouTube.locale =
            YouTubeLocale(
                hl = account.localeHl ?: System.getProperty("user.language")?.take(2) ?: "en",
                gl = account.localeGl ?: System.getProperty("user.country")?.take(2) ?: "US",
            )

        // Setting these on the facade auto-normalizes and pushes into InnerTube.
        YouTube.cookie = account.cookie
        YouTube.dataSyncId = account.dataSyncId
        resolveDataSyncId(config, account, onLog, save)
        resolveVisitorData(config, account, onLog, save)?.let { YouTube.visitorData = it }

        resolveScrobbling(config)

        return YouTube.currentPlaybackAuthState()
    }

    /**
     * Heals logins saved before DataSyncId capture existed (or pasted manually
     * with just a cookie): playback rides on the DataSyncId onBehalfOfUser
     * context, so a cookie alone is not enough. Fetches it once and persists it
     * so the next launch skips the network call.
     */
    private fun resolveDataSyncId(
        config: CliConfig,
        account: AccountConfig,
        onLog: (String) -> Unit,
        save: (CliConfig) -> Unit,
    ) {
        if (account.cookie.isNullOrBlank() || !account.dataSyncId.isNullOrBlank()) return
        onLog("[session] cookie present but DataSyncId missing; fetching one")
        val fetched =
            runCatching { kotlinx.coroutines.runBlocking { YouTube.accountDataSyncId() } }
                .getOrNull()
                ?.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.also { onLog("[session] fetched DataSyncId of length ${it.length}") }
        if (fetched != null) {
            YouTube.dataSyncId = fetched
            save(config.copy(account = account.copy(dataSyncId = fetched)))
        } else {
            onLog("[session] could not fetch DataSyncId; playback may ask you to sign in")
        }
    }

    /**
     * Picks the visitor data for this run and returns it (or null to stay
     * anonymous). A fresh cached value is reused without a network call; a
     * missing or stale one triggers the `sw.js_data` fetch and persists the
     * result so the next launch skips the download entirely.
     */
    private fun resolveVisitorData(
        config: CliConfig,
        account: AccountConfig,
        onLog: (String) -> Unit,
        save: (CliConfig) -> Unit,
    ): String? {
        val cached = account.visitorData?.takeIf { it.isNotBlank() }
        val stale =
            account.visitorDataFetchedAt
                ?.let { fetchedAt -> System.currentTimeMillis() - fetchedAt >= VISITOR_DATA_TTL_MS }
                ?: true

        if (cached != null && !stale) {
            onLog("[session] using cached visitorData of length ${cached.length}")
            return cached
        }

        // Every player call without a visitor id reads as an unauthenticated
        // scraper. The app fetches one lazily before the first playback; do the
        // same, but only when the config did not already pin one. This is a
        // cheap config fetch, not the BotGuard PoToken dance, so a bot-checked
        // response may still need one of the login flows instead.
        onLog("[session] no visitorData configured; fetching one")
        val fetched =
            runCatching { kotlinx.coroutines.runBlocking { YouTube.visitorData() } }
                .getOrNull()
                ?.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.also { onLog("[session] fetched visitorData of length ${it.length}") }

        if (fetched != null) {
            save(
                config.copy(
                    account =
                        account.copy(
                            visitorData = fetched,
                            visitorDataFetchedAt = System.currentTimeMillis(),
                        ),
                ),
            )
            return fetched
        }

        // Refresh failed: reuse whatever was cached (even if stale) before
        // falling back to anonymous requests.
        onLog("[session] visitorData fetch failed; requests go out anonymous")
        return cached
    }

    private fun resolveScrobbling(config: CliConfig) {
        // Last.fm carries its credentials in the same config file; hand them to
        // the shared singleton so `auth lastfm` and the scrobbler agree on the
        // account. The compat keys (apiKey/secret = "archivetune") are what the
        // app ships as a fallback; real keys override them from the config.
        val scrobbling = config.scrobbling
        LastFM.sessionKey = scrobbling.sessionKey
        LastFM.initialize(
            apiKey = scrobbling.apiKey.ifBlank { LastFM.FALLBACK_COMPAT_API_KEY },
            secret = scrobbling.secret.ifBlank { LastFM.FALLBACK_COMPAT_SECRET },
        )
    }

    /** Accepts `host:port`, `socks5://host:port`, or `http://user:pass@host:port`. */
    private fun parseProxy(raw: String): Proxy {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        val schemePart =
            when (scheme) {
                "", "http", "https" -> Proxy.Type.HTTP
                "socks", "socks5" -> Proxy.Type.SOCKS
                else -> throw IllegalArgumentException("Unsupported proxy scheme: $scheme")
            }
        val authority = trimmed.substringAfter("://", trimmed).substringAfterLast("@")
        val (host, port) =
            authority.split(":").let { parts ->
                val h = parts.firstOrNull().orEmpty()
                val p = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                h to p
            }
        require(host.isNotBlank()) { "Proxy host missing in '$raw'" }
        return Proxy(schemePart, InetSocketAddress(host, port))
    }
}