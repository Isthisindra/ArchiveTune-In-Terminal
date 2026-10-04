package moe.rukamori.archivetune.cli

/**
 * Log level control for the libraries that log through slf4j.
 *
 * vlcj, Ktor and the InnerTube client all use slf4j. With no provider on the
 * classpath slf4j prints a "no providers were found" banner to stderr on every
 * single command, which is unacceptable for a tool whose output gets piped into
 * `jq`. `slf4j-simple` is the provider; this just sets its level.
 *
 * The level has to be a system property read once, when the first logger is
 * created, so [quiet] runs before anything else touches slf4j and [verbose]
 * runs while arguments are still being parsed.
 */
object Logging {

    private const val LEVEL = "org.slf4j.simpleLogger.defaultLogLevel"

    /** Warnings only. Anything louder and a piped run stops being parseable. */
    fun quiet() = set("warn")

    /** Everything the libraries have to say, for debugging a resolution failure. */
    fun verbose() = set("debug")

    /**
     * The TUI owns the whole screen, so a log line scrolling through the
     * alternate buffer would tear the frame. Nothing is logged while it runs.
     */
    fun silence() = set("off")

    private fun set(level: String) {
        if (System.getProperty(LEVEL) == level) return
        System.setProperty(LEVEL, level)
    }
}
