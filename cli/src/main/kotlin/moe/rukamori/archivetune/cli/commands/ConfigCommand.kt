package moe.rukamori.archivetune.cli.commands

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import moe.rukamori.archivetune.cli.CliContext
import moe.rukamori.archivetune.cli.config.AudioQuality
import moe.rukamori.archivetune.cli.config.CliConfig
import moe.rukamori.archivetune.cli.config.ConfigStore
import moe.rukamori.archivetune.cli.config.OutputFormat
import moe.rukamori.archivetune.cli.config.PlayerBackend

class ConfigCommand(ctx: CliContext) :
    BaseCommand(
        ctx = ctx,
        name = "config",
        helpText = "Read and write the CLI configuration file.",
    ) {
        init {
            subcommands(ShowCommand(ctx), GetCommand(ctx), SetCommand(ctx), PathCommand(ctx))
        }

        override fun run() = Unit

        private class ShowCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "show",
                helpText = "Print the effective configuration.",
            ) {
                private val raw by option("--raw", help = "Print the file contents verbatim").flag()

                override fun run() {
                    val printer = ctx.printer
                    if (raw) {
                        val file = ctx.resolvedConfigPath.toFile()
                        if (!file.isFile) fail("No config file at ${file.absolutePath}")
                        printer.echo(file.readText().trimEnd())
                        return
                    }
                    printer.echo(encode(ctx.config).trimEnd())
                }
            }

        private class GetCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "get",
                helpText = "Print one configuration value, e.g. `config get playback.volume`.",
            ) {
                private val key by argument("KEY", help = "Dotted key, for example playback.backend")

                override fun run() {
                    if (!known(key)) fail("Unknown key: $key")
                    ctx.printer.echo(lookup(ctx.config, key) ?: "null")
                }
            }

        private class SetCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "set",
                helpText = "Change one configuration value, e.g. `config set playback.volume 60`.",
            ) {
                private val key by argument("KEY", help = "Dotted key, for example playback.backend")
                private val value by argument("VALUE", help = "New value")

                override fun run() {
                    val updated = assign(ctx.config, key, value) ?: fail("Cannot set $key to \"$value\".")
                    ctx.saveConfig(updated)
                    ctx.printer.echo(
                        "${ctx.printer.successLabel("saved")} ${ctx.printer.gray(key)} = $value",
                    )
                }
            }

        private class PathCommand(ctx: CliContext) :
            BaseCommand(
                ctx = ctx,
                name = "path",
                helpText = "Print the paths the CLI uses.",
            ) {
                override fun run() {
                    val printer = ctx.printer
                    printer.echo("config    ${ctx.resolvedConfigPath}")
                    printer.echo("cache     ${CliContext.cacheDir}")
                    printer.echo("downloads ${CliContext.downloadDir}")
                }
            }

        private companion object {
            fun encode(config: CliConfig): String = ConfigStore.encode(config)

            fun known(key: String): Boolean =
                lookup(config = null, key = key, knownOnly = true) != null

            fun lookup(config: CliConfig, key: String): String? = lookup(config, key, knownOnly = false)

            /** Every key `set` accepts; `get` must accept the same set. */
            private val KEYS =
                setOf(
                    "account.cookie",
                    "account.visitordata",
                    "account.datasyncid",
                    "account.localehl",
                    "account.localegl",
                    "playback.backend",
                    "playback.audioquality",
                    "playback.streamclient",
                    "playback.volume",
                    "playback.shuffle",
                    "playback.repeat",
                    "playback.crossfadeseconds",
                    "playback.mpvpath",
                    "playback.vlcpath",
                    "network.proxy",
                    "network.dnsoverhttps",
                    "output.format",
                    "output.color",
                    "output.pagesize",
                    "lyrics.providers",
                    "lyrics.translate",
                    "lyrics.romanize",
                    "scrobbling.enabled",
                    "scrobbling.sessionkey",
                    "scrobbling.apikey",
                    "scrobbling.secret",
                    "scrobbling.endpoint",
                    "scrobbling.nowplaying",
                    "scrobbling.threshold",
                    "services.paxsenicapikey",
                    "services.spotifyspdc",
                    "services.spotifyspkey",
                )

            fun lookup(config: CliConfig?, key: String, knownOnly: Boolean): String? {
                val normalized = key.trim().replace('-', '_').lowercase()
                if (config == null) {
                    return if (normalized in KEYS) "<known>" else null
                }
                val value: Any? =
                    when (normalized) {
                        "account.cookie" -> config.account.cookie
                        "account.visitordata" -> config.account.visitorData
                        "account.datasyncid" -> config.account.dataSyncId
                        "account.localehl" -> config.account.localeHl
                        "account.localegl" -> config.account.localeGl
                        "playback.backend" -> config.playback.backend.name
                        "playback.audioquality" -> config.playback.audioQuality.name
                        "playback.streamclient" -> config.playback.streamClient
                        "playback.volume" -> config.playback.volume
                        "playback.shuffle" -> config.playback.shuffle
                        "playback.repeat" -> config.playback.repeat
                        "playback.crossfadeseconds" -> config.playback.crossfadeSeconds
                        "playback.mpvpath" -> config.playback.mpvPath
                        "playback.vlcpath" -> config.playback.vlcPath
                        "network.proxy" -> config.network.proxy
                        "network.dnsoverhttps" -> config.network.dnsOverHttps
                        "output.format" -> config.output.format.name
                        "output.color" -> config.output.color
                        "output.pagesize" -> config.output.pageSize
                        "lyrics.providers" -> config.lyrics.preferredProviders.joinToString(",")
                        "lyrics.translate" -> config.lyrics.translateTo
                        "lyrics.romanize" -> config.lyrics.romanize
                        "scrobbling.enabled" -> config.scrobbling.enabled
                        "scrobbling.sessionkey" -> config.scrobbling.sessionKey
                        "scrobbling.apikey" -> config.scrobbling.apiKey
                        "scrobbling.secret" -> config.scrobbling.secret
                        "scrobbling.endpoint" -> config.scrobbling.endpoint
                        "scrobbling.nowplaying" -> config.scrobbling.nowPlaying
                        "scrobbling.threshold" -> config.scrobbling.scrobbleThreshold
                        "services.paxsenicapikey" -> config.services.paxsenixApiKey
                        "services.spotifyspdc" -> config.services.spotifySpDc
                        "services.spotifyspkey" -> config.services.spotifySpKey
                        else -> return null
                    }
                return value?.toString()
            }

            fun assign(config: CliConfig, key: String, value: String): CliConfig? {
                val normalized = key.trim().replace('-', '_').lowercase()
                val playback = config.playback
                val account = config.account
                val network = config.network
                val output = config.output
                val lyrics = config.lyrics
                val scrobbling = config.scrobbling
                val services = config.services

                fun bool() = value.lowercase().toBooleanStrictOrNull()
                fun int() = value.toIntOrNull()

                return when (normalized) {
                    "account.cookie" -> config.copy(account = account.copy(cookie = value))
                    "account.visitordata" -> config.copy(account = account.copy(visitorData = value))
                    "account.datasyncid" -> config.copy(account = account.copy(dataSyncId = value))
                    "account.localehl" -> config.copy(account = account.copy(localeHl = value))
                    "account.localegl" -> config.copy(account = account.copy(localeGl = value))

                    "playback.backend" ->
                        PlayerBackend.entries
                            .firstOrNull { it.name.equals(value, ignoreCase = true) }
                            ?.let { config.copy(playback = playback.copy(backend = it)) }

                    "playback.audioquality" ->
                        AudioQuality.entries
                            .firstOrNull { it.name.equals(value, ignoreCase = true) }
                            ?.let { config.copy(playback = playback.copy(audioQuality = it)) }

                    "playback.streamclient" ->
                        config.copy(playback = playback.copy(streamClient = value.uppercase()))

                    "playback.volume" ->
                        int()?.let { config.copy(playback = playback.copy(volume = it.coerceIn(0, 100))) }

                    "playback.shuffle" -> bool()?.let { config.copy(playback = playback.copy(shuffle = it)) }
                    "playback.repeat" -> bool()?.let { config.copy(playback = playback.copy(repeat = it)) }

                    "playback.crossfadeseconds" ->
                        int()?.let {
                            config.copy(playback = playback.copy(crossfadeSeconds = it.coerceAtLeast(0)))
                        }

                    "playback.mpvpath" -> config.copy(playback = playback.copy(mpvPath = value))
                    "playback.vlcpath" -> config.copy(playback = playback.copy(vlcPath = value))
                    "network.proxy" -> config.copy(network = network.copy(proxy = value.ifBlank { null }))
                    "network.dnsoverhttps" ->
                        config.copy(network = network.copy(dnsOverHttps = value.ifBlank { null }))

                    "output.format" ->
                        OutputFormat.entries
                            .firstOrNull { it.name.equals(value, ignoreCase = true) }
                            ?.let { config.copy(output = output.copy(format = it)) }

                    "output.color" -> bool()?.let { config.copy(output = output.copy(color = it)) }
                    "output.pagesize" ->
                        int()?.let { config.copy(output = output.copy(pageSize = it.coerceIn(1, 200))) }

                    "lyrics.providers" ->
                        config.copy(
                            lyrics =
                                lyrics.copy(
                                    preferredProviders =
                                        value.split(',').map(String::trim).filter(String::isNotEmpty),
                                ),
                        )

                    "lyrics.translate" -> config.copy(lyrics = lyrics.copy(translateTo = value.ifBlank { null }))
                    "lyrics.romanize" -> bool()?.let { config.copy(lyrics = lyrics.copy(romanize = it)) }

                    "scrobbling.enabled" ->
                        bool()?.let { config.copy(scrobbling = scrobbling.copy(enabled = it)) }

                    "scrobbling.sessionkey" ->
                        config.copy(scrobbling = scrobbling.copy(sessionKey = value.ifBlank { null }))

                    "scrobbling.apikey" -> config.copy(scrobbling = scrobbling.copy(apiKey = value))
                    "scrobbling.secret" -> config.copy(scrobbling = scrobbling.copy(secret = value))
                    "scrobbling.endpoint" -> config.copy(scrobbling = scrobbling.copy(endpoint = value))
                    "scrobbling.nowplaying" ->
                        bool()?.let { config.copy(scrobbling = scrobbling.copy(nowPlaying = it)) }

                    "scrobbling.threshold" ->
                        value.toDoubleOrNull()
                            ?.takeIf { it > 0.0 && it <= 1.0 }
                            ?.let { config.copy(scrobbling = scrobbling.copy(scrobbleThreshold = it)) }

                    "services.paxsenicapikey" ->
                        config.copy(services = services.copy(paxsenixApiKey = value.ifBlank { null }))

                    "services.spotifyspdc" ->
                        config.copy(services = services.copy(spotifySpDc = value.ifBlank { null }))

                    "services.spotifyspkey" ->
                        config.copy(services = services.copy(spotifySpKey = value.ifBlank { null }))

                    else -> null
                }
            }
        }
    }
