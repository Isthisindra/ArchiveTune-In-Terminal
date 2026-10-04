package moe.rukamori.archivetune.cli.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves where the CLI keeps its state. Windows conventions take priority so the
 * files land next to the rest of the user's app data instead of in a Unix-style
 * dotfile directory.
 */
object ConfigPaths {
    const val APP_DIR_NAME = "archivetune"

    val configDir: Path by lazy {
        val base: Path =
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: System.getProperty("user.home")?.let { Paths.get(it, ".config") }
                ?: Paths.get("").toAbsolutePath()
        base.resolve(APP_DIR_NAME).also { it.toFile().mkdirs() }
    }

    val configFile: Path get() = configDir.resolve("config.json")

    val queueFile: Path get() = configDir.resolve("queue.json")

    val accountsFile: Path get() = configDir.resolve("accounts.json")

    val cacheDir: Path by lazy {
        val base: Path =
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("java.io.tmpdir") ?: ".")
        base.resolve(APP_DIR_NAME).resolve("cache").also { it.toFile().mkdirs() }
    }

    val downloadDir: Path by lazy {
        val home: Path = Paths.get(System.getProperty("user.home") ?: ".")
        home.resolve("Music").resolve("ArchiveTune").also { it.toFile().mkdirs() }
    }
}
