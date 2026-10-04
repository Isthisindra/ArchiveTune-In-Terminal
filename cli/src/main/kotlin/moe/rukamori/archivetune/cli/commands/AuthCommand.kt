package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.auth.BrowserLogin
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lastfm.LastFM.LastFmException
import moe.rukamori.archivetune.lastfm.models.Authentication
import java.awt.Desktop
import java.net.URI

/**
 * Attaches the accounts the CLI uses.
 *
 * `auth youtube` signs a browser into Google/YouTube and stores the session
 * cookies automatically, so the stream resolution stops being treated as an
 * unauthenticated scraper (today's YouTube demands a login or a PoToken for
 * most player calls). `auth lastfm` stores the session key the scrobbler sends
 * updates with.
 */
class AuthCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "auth",
        helpText = "Link the accounts the CLI uses.",
    ) {
        init {
            subcommands(YouTubeCommand(ctx), LastFmCommand(ctx))
        }

        override fun run() = Unit

        /**
         * Links the YouTube account used for playback.
         *
         * By default a browser window opens and you sign in like you normally
         * would - the session cookies are captured automatically, so no
         * DevTools or cookie copying is needed.
         */
        private class YouTubeCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "youtube",
                helpText = "Log in with a Google account for playback.",
                epilogText =
                    "By default a browser window opens and you sign in like you " +
                        "normally would - the session cookies are captured " +
                        "automatically. To log in without the browser, pass --cookie.",
            ) {
            private val cookie by option("--cookie", help = "A cookie header from a logged-in youtube.com session (skips the browser)")

            override fun run() {
                val printer = ctx.printer
                val value = cookie ?: browserLogin() ?: fail(noLoginMessage())

                ctx.requireNetwork()
                YouTube.cookie = value

                // The session YouTube just minted can take a couple of seconds
                // to be usable; retry before declaring the login invalid.
                val account =
                    retry(attempts = 4, delayMs = 4_000) { blocking { YouTube.accountInfo() } }
                        .getOrElse { error ->
                            fail(
                                "That login was not accepted: ${error.message ?: "request failed"}. " +
                                    "Make sure you signed in and YouTube showed the home page " +
                                    "(your avatar, top right), then run `auth youtube` again.",
                            )
                        }

                // Playback also rides on the DataSyncId (the onBehalfOfUser
                // delegation id), which lives in accounts_list rather than the
                // account menu. Fetch and persist it so the player has the full
                // login context.
                val dataSyncId =
                    retry(attempts = 3, delayMs = 2_000) { blocking { YouTube.accountDataSyncId() } }
                        .getOrNull()

                val updated =
                    ctx.config.copy(
                        account =
                            ctx.config.account.copy(
                                cookie = value,
                                dataSyncId = dataSyncId ?: ctx.config.account.dataSyncId,
                            ),
                    )
                ctx.saveConfig(updated)
                val handle = account.channelHandle?.let { " ($it)" }.orEmpty()
                printer.echo(
                    "${printer.successLabel("saved")} cookie for ${printer.bold(account.name)}$handle" +
                        if (account.email != null) " (${account.email})" else "",
                )
                if (dataSyncId != null) {
                    printer.echo(printer.dim("Playback should work now. Try: archivetune play \"radiohead creep\""))
                } else {
                    printer.echo(
                        "${printer.warnLabel("warning")} could not fetch the session's DataSyncId, " +
                            "so playback may still ask you to sign in. Run `auth youtube` again.",
                    )
                }
            }

            private fun browserLogin(): String? {
                val printer = ctx.printer
                printer.echo("Opening a browser to link your Google account...")
                return BrowserLogin.loginYouTube(log = { line -> printer.echo(printer.dim(line)) })
            }

            /** Runs [block] up to [attempts] times, sleeping [delayMs] between retries. */
            private fun <T> retry(attempts: Int, delayMs: Long, block: () -> Result<T>): Result<T> {
                var result: Result<T> = block()
                repeat(attempts - 1) {
                    if (result.isSuccess) return result
                    Thread.sleep(delayMs)
                    result = block()
                }
                return result
            }

            private fun noLoginMessage(): String =
                "No login was completed. Make sure Edge/Chrome is installed, " +
                    "or paste the cookie manually with --cookie."
        }

        private class LastFmCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "lastfm",
                helpText = "Link a Last.fm account for scrobbling.",
                epilogText =
                    "By default the browser flow is used: a URL opens, you approve, and the\n" +
                        "session key is saved. With --username/--password the desktop session\n" +
                        "flow is used instead (password is read from the console when omitted).",
            ) {
            private val username by option("--username", help = "Last.fm user name (uses the mobile session flow)")
            private val password by option("--password", help = "Last.fm password (asks when omitted)")

            override fun run() {
                val printer = ctx.printer
                val scrobbling = ctx.config.scrobbling

                // Configure the shared singleton from the config, no network needed.
                LastFM.sessionKey = scrobbling.sessionKey
                LastFM.initialize(
                    apiKey = scrobbling.apiKey.ifBlank { LastFM.FALLBACK_COMPAT_API_KEY },
                    secret = scrobbling.secret.ifBlank { LastFM.FALLBACK_COMPAT_SECRET },
                )

                val session =
                    if (username != null) {
                        mobileSession()
                    } else {
                        browserSession()
                    }

                val updated =
                    ctx.config.copy(
                        scrobbling = scrobbling.copy(sessionKey = session.key, enabled = true),
                    )
                ctx.saveConfig(updated)
                printer.echo(
                    "${printer.successLabel("linked")} Last.fm as ${printer.bold(session.name)} " +
                        "(scrobbling.enabled = true)",
                )
                printer.echo(printer.dim("Listen to something and it will show up on Last.fm."))
            }

            private fun mobileSession(): Authentication.Session {
                val user = username!!
                val pass =
                    password
                        ?: if (System.console() != null) {
                            System.console().readPassword("Last.fm password for $user: ")
                                ?.let { chars -> String(chars) }
                        } else {
                            System.`in`.bufferedReader().readLine()
                        }
                    ?: fail("No password given for $user.")
                return blocking { LastFM.getMobileSession(user, pass) }
                    .getOrElse { fail(lastfmFailure("Login failed", it)) }
                    .session
            }

            private fun browserSession(): Authentication.Session {
                val printer = ctx.printer
                val token =
                    blocking { LastFM.getToken() }
                        .getOrElse { fail(lastfmFailure("Could not get a Last.fm auth token", it)) }
                        .token

                val url = LastFM.getAuthUrl(token)
                printer.echo("Open this URL in a browser and click \u201cGet API Account\u201d:")
                printer.echo("  ${printer.cyan(url)}")
                runCatching { Desktop.getDesktop().browse(URI(url)) }
                    .onSuccess { printer.echo(printer.dim("(a browser window should have opened)")) }
                printer.echo("Press Enter here once you have authorised.")
                if (System.console() != null) {
                    System.console().readLine()
                } else {
                    System.`in`.bufferedReader().readLine()
                }
                return blocking { LastFM.getSession(token) }
                    .getOrElse { fail(lastfmFailure("Could not complete the login", it)) }
                    .session
            }

            /** Formats a Last.fm failure with the code and, for code 6, the fix. */
            private fun lastfmFailure(prefix: String, error: Throwable): String {
                val detail =
                    if (error is LastFmException) {
                        val code = error.code
                        val apiKeyHint =
                            if (code == 6 || code == 401 || code == 403) {
                                " Create an API key at https://www.last.fm/api/account/create, then " +
                                    "`config set scrobbling.apikey <key>` and `config set scrobbling.secret <secret>`."
                            } else {
                                ""
                            }
                        " (error $code)${error.message?.let { " $it" }.orEmpty()}$apiKeyHint"
                    } else {
                        error.message ?: "request failed"
                    }
                return "$prefix$detail"
            }
        }
    }