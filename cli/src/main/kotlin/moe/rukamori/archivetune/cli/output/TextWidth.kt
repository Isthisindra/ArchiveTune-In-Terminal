package moe.rukamori.archivetune.cli.output

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty

/**
 * Terminal column arithmetic for text that may not be ASCII.
 *
 * `String.length` counts UTF-16 units, which is wrong in two ways for a music
 * player that routinely prints Japanese, Korean and Chinese titles: a CJK glyph
 * occupies two terminal columns, and a combining mark or a zero-width space
 * occupies none. Padding a table by `length` therefore shunts every column to
 * the right of a CJK one. [width] measures what the terminal will actually draw.
 *
 * East Asian width comes from ICU, which is already on the classpath for lyric
 * romanization, so no new dependency is needed.
 */
object TextWidth {

    private const val ESC = '\u001b'

    /** Columns [text] occupies, ignoring ANSI escape sequences. */
    fun width(text: String): Int {
        var total = 0
        var index = 0
        while (index < text.length) {
            val skip = escapeLength(text, index)
            if (skip > 0) {
                index += skip
                continue
            }
            val codePoint = text.codePointAt(index)
            total += charWidth(codePoint)
            index += Character.charCount(codePoint)
        }
        return total
    }

    /**
     * Truncates to at most [max] columns, cutting on a code point boundary and
     * appending an ellipsis when anything was dropped. ANSI sequences survive
     * the cut, so styled input stays styled.
     */
    fun truncate(text: String, max: Int): String {
        if (max <= 0) return ""
        if (width(text) <= max) return text
        val ellipsis = "\u2026"
        val budget = (max - 1).coerceAtLeast(0)
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val skip = escapeLength(text, index)
            if (skip > 0) {
                out.append(text, index, index + skip)
                index += skip
                continue
            }
            val codePoint = text.codePointAt(index)
            val next = charWidth(codePoint)
            if (used + next > budget) break
            out.appendCodePoint(codePoint)
            used += next
            index += Character.charCount(codePoint)
        }
        return out.toString().trimEnd() + ellipsis
    }

    /** Pads on the right with spaces until the text occupies [width] columns. */
    fun padEnd(text: String, width: Int): String {
        val gap = width - this.width(text)
        return if (gap <= 0) text else text + " ".repeat(gap)
    }

    /** Pads on the left with spaces until the text occupies [width] columns. */
    fun padStart(text: String, width: Int): String {
        val gap = width - this.width(text)
        return if (gap <= 0) text else " ".repeat(gap) + text
    }

    /**
     * The length of the ANSI escape sequence starting at [index], or 0 when
     * there is none. Only CSI sequences are recognised, which is all Mordant
     * emits.
     */
    private fun escapeLength(text: String, index: Int): Int {
        if (text[index] != ESC) return 0
        if (text.getOrNull(index + 1) != '[') return 0
        var cursor = index + 2
        while (cursor < text.length && text[cursor] !in '@'..'~') cursor++
        return if (cursor < text.length) cursor - index + 1 else 0
    }

    private fun charWidth(codePoint: Int): Int {
        if (codePoint == '\n'.code || codePoint == '\r'.code) return 0
        // `Character`'s category constants are Java bytes; getType returns int.
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            -> return 0
        }
        if (codePoint in 0x200B..0x200F || codePoint == 0xFEFF) return 0
        val eastAsian =
            UCharacter.getIntPropertyValue(codePoint, UProperty.EAST_ASIAN_WIDTH)
        return if (eastAsian == UCharacter.EastAsianWidth.WIDE ||
            eastAsian == UCharacter.EastAsianWidth.FULLWIDTH
        ) {
            2
        } else {
            1
        }
    }
}
