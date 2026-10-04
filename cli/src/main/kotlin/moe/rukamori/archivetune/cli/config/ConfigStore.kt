package moe.rukamori.archivetune.cli.config

import kotlinx.serialization.json.Json
import java.nio.file.Path

object ConfigStore {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun load(): CliConfig = loadFrom(ConfigPaths.configFile)

    /** Reads a config from an explicit path, falling back to defaults when unreadable. */
    fun loadFrom(path: Path): CliConfig {
        val file = path.toFile()
        if (!file.isFile) return CliConfig()
        return runCatching { json.decodeFromString(CliConfig.serializer(), file.readText()) }
            .getOrElse { CliConfig() }
    }

    fun save(config: CliConfig) {
        ConfigPaths.configFile.toFile().apply {
            parentFile?.mkdirs()
            writeText(encode(config))
        }
    }

    fun saveTo(path: Path, config: CliConfig) {
        path.toFile().apply {
            parentFile?.mkdirs()
            writeText(encode(config))
        }
    }

    fun encode(config: CliConfig): String = json.encodeToString(CliConfig.serializer(), config)
}
