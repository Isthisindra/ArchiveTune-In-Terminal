package moe.rukamori.archivetune.cli

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString

/**
 * Recovers the arguments Windows actually typed.
 *
 * The JVM decodes its command line with the *system ANSI code page* before
 * `main` is called, and every character that code page cannot represent has
 * already been replaced by `?` by the time argv exists. On a machine with a
 * Western code page that is most of the ArchiveTune catalog:
 *
 * ```
 * PS> java -jar archivetune.jar "夜に駆ける"
 *        ->  search query  "?????",  five literal question marks
 * ```
 *
 * Nothing inside the JVM can undo that, and `-Dsun.jnu.encoding=UTF-8` does
 * not help either: `sun.jnu.encoding` is fixed during VM init, before
 * command-line `-D` flags are applied, so the property still reads `Cp1252` and
 * the argument is still `?`. `chcp 65001` in a wrapper script does not help
 * either, because Java reads the ANSI code page rather than the console one.
 *
 * The information is not lost, though: the OS still holds the original UTF-16
 * command line, and `CommandLineToArgvW` is the very function Windows used to
 * build the mangled argv. Asking the OS to parse it again yields the real
 * strings, so the fix is to ignore `args` on Windows and use this instead.
 *
 * Every step is best-effort. If JNA cannot load, or the call fails, the
 * already-decoded `args` are returned unchanged - a non-Latin query then fails
 * exactly as it did before, and an ASCII one is unaffected either way.
 */
object WindowsArgs {

    /** [args] as typed, with nothing dropped, on Windows. */
    fun recover(args: Array<String>): Array<String> {
        val windows = fromCommandLine(args) ?: return args
        // The recovered list always starts with the executable, so it is one
        // longer than what Clikt wants to parse.
        return windows.drop(1).toTypedArray()
    }

    /**
     * The arguments the OS would hand a program, or null when this is not
     * Windows or the call did not work out.
     *
     * Uses [Kernel32.CommandLineToArgvW] rather than a hand-rolled parser: the
     * backslash/quote rules for Windows arguments are fiddly enough that
     * reimplementing them is how you get a subtly wrong path.
     */
    private fun fromCommandLine(decoded: Array<String>): List<String>? =
        runCatching {
            if (!isWindows()) return null
            val kernel = Kernel32.INSTANCE

            val commandLine = kernel.GetCommandLineW() ?: return null
            val argv = kernel.CommandLineToArgvW(WString(commandLine), null) ?: return null
            val recovered =
                try {
                    readArgv(argv)
                } finally {
                    // The block came from LocalAlloc, so LocalFree is the only
                    // way to give it back. Leaking one per invocation is not
                    // fatal, but the TUI is a long-lived process.
                    kernel.LocalFree(argv)
                }
            // If the counts disagree, something is very wrong - most likely
            // JNA resolved a different entry point than the launcher used, and
            // the recovered list is not argv at all. The mangled list is at
            // least the same shape the user typed.
            recovered.takeIf { it.size == decoded.size + 1 }
        }.getOrNull()

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * The two entry points this needs. `jna-platform`'s [Kernel32] does not
     * bind them, and extending it would drag its whole surface in behind it.
     */
    private interface Kernel32 : Library {
        /** The command line exactly as the process was started, in UTF-16. */
        fun GetCommandLineW(): String?

        /**
         * Splits [lpCmdLine] the way Windows built argv. Pass a null
         * [lpNumberOfArgs] to skip the count; the array ends at a null entry.
         *
         * The result is a `LPWSTR*` the caller must release with [LocalFree].
         */
        fun CommandLineToArgvW(lpCmdLine: WString, lpNumberOfArgs: Pointer?): Pointer?

        /** Frees a block handed out by [CommandLineToArgvW]. */
        fun LocalFree(ptr: Pointer?): Pointer?

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }

    /**
     * Walks a `LPWSTR*` array until the NULL terminator.
     *
     * `CommandLineToArgvW` can be asked for the count through its second
     * argument, but JNA's binding types it as a nullable pointer, so the
     * terminator is the cheaper and more portable stop condition.
     */
    private fun readArgv(argv: Pointer): List<String> {
        val step = Native.POINTER_SIZE.toLong()
        val out = mutableListOf<String>()
        var index = 0L
        while (true) {
            val entry = argv.share(index * step).getPointer(0) ?: break
            out += entry.getWideString(0)
            index++
        }
        return out
    }
}
