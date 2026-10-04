package moe.rukamori.archivetune.cli.output

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queue is full of non-Latin titles by default, so every column measurement
 * has to be in terminal cells rather than UTF-16 units. These are the cases
 * that were actually wrong in `String.length`-based padding.
 */
class TextWidthTest {

    @Test
    fun `ascii is one column per character`() {
        assertEquals(0, TextWidth.width(""))
        assertEquals(5, TextWidth.width("Creep"))
    }

    @Test
    fun `cjk glyphs are two columns`() {
        // Han, Hangul and kana are all double-width in a terminal.
        assertEquals(4, TextWidth.width("夜光"))
        assertEquals(4, TextWidth.width("방랑"))
        // 5 kana/kanji glyphs, not 6.
        assertEquals(10, TextWidth.width("夜に駆ける"))
    }

    @Test
    fun `a supplementary ideograph is one code point but two units`() {
        // U+20000 needs a surrogate pair, so a naive take(1) would split it in
        // half and print half a glyph.
        val rare = "\uD840\uDC00"
        assertEquals(2, rare.length)
        assertEquals(1, rare.codePointCount(0, rare.length))
        assertEquals(2, TextWidth.width(rare))
        assertEquals(rare, TextWidth.truncate(rare, 2))
    }

    @Test
    fun `combining marks and zero-width characters take no columns`() {
        // "e" plus a combining acute accent is still one column.
        assertEquals(1, TextWidth.width("e\u0301"))
        assertEquals(0, TextWidth.width("\u200B"))
        assertEquals(0, TextWidth.width("\uFEFF"))
        assertEquals(2, TextWidth.width("a\u200Bb"))
    }

    @Test
    fun `ansi escapes are not measured`() {
        val styled = "\u001b[31mred\u001b[0m"
        assertEquals(3, TextWidth.width(styled))
        assertEquals(6, TextWidth.width(TextWidth.padEnd(styled, 6)))
    }

    @Test
    fun `truncate cuts on a code point boundary`() {
        val cut = TextWidth.truncate("夜に駆ける", 5)
        // 5 columns can hold two double-width glyphs plus the ellipsis.
        assertEquals(5, TextWidth.width(cut))
        assertEquals("\u591C\u306B\u2026", cut)
    }

    @Test
    fun `truncate leaves short enough text alone`() {
        assertEquals("Creep", TextWidth.truncate("Creep", 5))
        assertEquals("", TextWidth.truncate("Creep", 0))
    }

    @Test
    fun `truncate keeps ansi escapes`() {
        val styled = "\u001b[36m\u591C\u306B\u9A4A\u3051\u308B\u001b[0m"
        val cut = TextWidth.truncate(styled, 5)
        assertEquals(5, TextWidth.width(cut))
        assertEquals(true, cut.startsWith("\u001b[36m"))
    }

    @Test
    fun `padding aligns mixed script columns`() {
        val rows = listOf("Creep", "夜に駆ける", "방랑")
        val width = rows.maxOf(TextWidth::width)
        val padded = rows.map { TextWidth.padEnd(it, width) }
        padded.forEach { assertEquals(width, TextWidth.width(it)) }
    }
}
