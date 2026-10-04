package moe.rukamori.archivetune.cli.lyrics

import com.ibm.icu.text.Transliterator
import java.lang.Character.UnicodeScript

/**
 * Romanizes Japanese, Korean, Chinese, Hindi and "anything else" lyrics.
 *
 * The app romanizes Japanese with Kuromoji, which gives word readings and
 * handles sokuon (`ッ`) properly. Kuromoji is an Android-side dependency here,
 * so the CLI does the two things that actually matter for a terminal: it walks
 * the Hangul syllable blocks arithmetically (no dictionary needed) and it
 * transliterates kana with a table. Chinese, Hindi and other scripts go through
 * the same ICU transliterator the app uses, which `icu4j` provides on the JVM.
 */
object Romanizer {

    private const val HANGUL_FIRST = '가'
    private const val HANGUL_LAST = '힣'
    private const val JAMO_CHO_BASE = 0x1100
    private const val JAMO_JUNG_BASE = 0x1161
    private const val JAMO_JONG_BASE = 0x11A8

    /** The 19 initials, by Unicode jamo index. */
    private val CHO = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")

    /** The 21 vowels, by Unicode jamo index. */
    private val JUNG = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "eui", "i")

    /** The 27 finals, by Unicode jamo index; the empty entry is "no final". */
    private val JONG = listOf("", "k", "k", "n", "n", "t", "t", "l", "k", "m", "p", "l", "l", "l", "p", "l", "b", "p", "p", "s", "s", "ng", "t", "t", "p", "p", "p", "t")

    /**
     * Consonant clusters. A final consonant is usually pronounced before the next
     * syllable's initial, and that changes both (`실` + `제` = `silje`), so the
     * naive per-syllable answer sounds wrong for the most common Korean words.
     * The keys are `final jamo + initial jamo`.
     */
    private val CLUSTERS =
        mapOf(
            "ᆨᄂ" to "d", "ᆪᄒ" to "ch", "ᆨᄋ" to "g", "ᆨᄊ" to "gg", "ᆨᄐ" to "gg",
            "ᆯᄀ" to "g", "ᆯᄂ" to "m", "ᆯᄆ" to "ngm", "ᆯᄒ" to "kh", "ᆮᄀ" to "g",
            "ᆮᄂ" to "m", "ᆮᄋ" to "ngm", "ᆮᄒ" to "kh", "ᆱᄀ" to "g", "ᆱᄂ" to "n",
            "ᆱᄆ" to "ngm", "ᆱᄒ" to "kh", "ᆲᄀ" to "g", "ᆲᄂ" to "n", "ᆲᄆ" to "ngm",
            "ᆲᄒ" to "kh", "ᆼᄀ" to "g", "ᆼᄂ" to "n", "ᆽᄂ" to "n", "ᆿᄂ" to "m",
            "ᆷᄂ" to "n", "ᆮᄋ" to "m", "ᆯᄋ" to "ls", "ᆱᄐ" to "gs",
        )

    private val KANA = buildMap {
        fun row(chars: String, readings: String) {
            chars.forEachIndexed { index, ch -> put(ch, readings.split(' ')[index]) }
        }
        // Basic katakana. `シ` is `shi` and `ツ` is `tsu`, the two readings that
        // are never guessed right from the consonant alone.
        row("アイウエオ", "a i u e o")
        row("カキクケコ", "ka ki ku ke ko")
        row("サシスセソ", "sa shi su se so")
        row("タチツテト", "ta chi tsu te to")
        row("ナニヌネノ", "na ni nu ne no")
        row("ハヒフヘホ", "ha hi fu he ho")
        row("マミムメモ", "ma mi mu me mo")
        row("ヤ", "ya")
        row("ユ", "yu")
        row("ヨ", "yo")
        row("ラリルレロ", "ra ri ru re ro")
        row("ワ", "wa")
        row("ヲ", "o")
        row("ン", "n")
        // Voiced and semi-voiced.
        put("ガ", "ga"); put("ギ", "gi"); put("グ", "gu"); put("ゲ", "ge"); put("ゴ", "go")
        put("ザ", "za"); put("ジ", "ji"); put("ズ", "zu"); put("ゼ", "ze"); put("ゾ", "zo")
        put("ダ", "da"); put("ヂ", "ji"); put("ヅ", "zu"); put("デ", "de"); put("ド", "do")
        put("バ", "ba"); put("ビ", "bi"); put("ブ", "bu"); put("ベ", "be"); put("ボ", "bo")
        put("パ", "pa"); put("ピ", "pi"); put("プ", "pu"); put("ペ", "pe"); put("ポ", "po")
        put("ヴ", "vu")
        // Small kana, which fold into the preceding syllable.
        put("ァ", "a"); put("ィ", "i"); put("ゥ", "u"); put("ェ", "e"); put("ォ", "o")
        put("ャ", "ya"); put("ュ", "yu"); put("ョ", "yo"); put("ヰ", "i"); put("ヱ", "e")
        // Punctuation and the long-vowel mark, folded away.
        put("ー", ""); put("・", " "); put("、", ", "); put("。", ". ")
        put("！", "! "); put("？", "? "); put("「", "\""); put("」", "\"")
        put("『", "\""); put("』", "\""); put("…", "...")
    }

    /** Two-kana sequences must be tried before single kana (`キャ` before `キ`). */
    private val KANA_PAIRS =
        listOf(
            "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
            "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
            "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che",
            "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
            "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
            "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
            "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
            "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
            "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ジェ" to "je",
            "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
            "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
            "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
            "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo",
            "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
            "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ツァ" to "tsa",
            "ツェ" to "tse", "ツォ" to "tso", "ファ" to "fa", "フィ" to "fi",
            "フェ" to "fe", "フォ" to "fo", "フュ" to "fyu",
        )

    private val generic by lazy {
        ThreadLocal.withInitial { Transliterator.getInstance("Any-Latin; Latin-ASCII") }
    }

    /** The scripts this object knows how to handle. */
    data class Scripts(
        val japanese: Boolean = false,
        val korean: Boolean = false,
        val chinese: Boolean = false,
        val hindi: Boolean = false,
        val other: Boolean = false,
    ) {
        val any: Boolean get() = japanese || korean || chinese || hindi || other
    }

    fun scriptsOf(text: String): Scripts {
        var kana = 0
        var hangul = 0
        var han = 0
        var devanagari = 0
        var other = 0
        text.forEach { ch ->
            val code = ch.code
            when {
                code in 0x3040..0x30FF -> kana++
                code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF -> hangul++
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> han++
                code in 0x0900..0x097F -> devanagari++
                ch.isLetter() && scriptOf(ch) !in IGNORED_SCRIPTS -> other++
            }
        }
        return Scripts(
            // Kana decides: Japanese and Chinese share Han characters, and a
            // single kana character is enough to call the line Japanese.
            japanese = kana > 0,
            korean = hangul > 0,
            chinese = han > 0 && kana == 0 && hangul == 0,
            hindi = devanagari > 0,
            other = other > 0,
        )
    }

    /**
     * The romanized form of [text], or null when there is nothing to romanize -
     * a Latin-script line is returned as-is by the caller instead of being
     * mangled through the transliterator.
     */
    fun romanize(text: String): String? {
        if (text.isBlank()) return null
        val scripts = scriptsOf(text)
        if (!scripts.any) return null

        val out = StringBuilder(text.length * 2)
        when {
            scripts.korean -> return romanizeKorean(text)
            scripts.japanese -> romanizeKana(text, out)
            scripts.chinese || scripts.hindi || scripts.other -> {
                val transliterated = generic.get().transliterate(text)
                return transliterated.replace(Regex("\\s+"), " ").trim().takeIf(String::isNotEmpty)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /** Katakana and kanji text. Kana runs are converted, everything else kept. */
    private fun romanizeKana(text: String, out: StringBuilder) {
        var index = 0
        while (index < text.length) {
            val pair = KANA_PAIRS.firstOrNull { text.startsWith(it.first, index) }
            if (pair != null) {
                out.append(pair.second)
                index += pair.first.length
                continue
            }
            val ch = text[index]
            val mapped = KANA[ch]
            if (mapped != null) {
                // Sokuon doubles the next consonant: ratto, not rato.
                if (ch == 'ッ') {
                    val nextChar = text.getOrNull(index + 1)
                    val next = nextChar?.let(KANA::get) ?: KANA_PAIRS
                        .firstOrNull { text.startsWith(it.first, index + 1) }?.second
                    if (next != null && next.isNotEmpty()) out.append(next.first())
                } else {
                    out.append(mapped)
                }
            } else {
                out.append(ch)
            }
            index++
        }
    }

    /**
     * Hangul syllables decomposed arithmetically, with the final consonant of one
     * syllable carried into the next one the way Korean actually pronounces it.
     */
    fun romanizeKorean(text: String): String {
        val out = StringBuilder(text.length * 3)
        var previousFinal: Int? = null
        var previousInitialJamo: Int? = null

        text.forEach { ch ->
            if (ch.code in HANGUL_FIRST.code..HANGUL_LAST.code) {
                val index = ch.code - HANGUL_FIRST.code
                val initial = index / (21 * 28)
                val medial = (index % (21 * 28)) / 28
                val finalIndex = index % 28

                if (previousFinal != null) {
                    out.append(cluster(previousFinal, previousInitialJamo))
                    previousFinal = null
                }

                out.append(CHO[initial])
                out.append(JUNG[medial])
                if (finalIndex != 0) previousFinal = finalIndex
                previousInitialJamo = initial
            } else {
                if (previousFinal != null) {
                    out.append(cluster(previousFinal, previousInitialJamo))
                    previousFinal = null
                    previousInitialJamo = null
                }
                out.append(ch)
            }
        }
        if (previousFinal != null) {
            out.append(cluster(previousFinal, previousInitialJamo))
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** The pronunciation of a final consonant in front of the next initial. */
    private fun cluster(finalIndex: Int, nextInitial: Int?): String {
        val jongChar = (JAMO_JONG_BASE + finalIndex).toChar()
        val choChar = nextInitial?.let { (JAMO_CHO_BASE + it).toChar() }
        if (choChar != null) {
            CLUSTERS["$jongChar$choChar"]?.let { return it }
        }
        return JONG[finalIndex]
    }

    private fun scriptOf(ch: Char): UnicodeScript = runCatching { UnicodeScript.of(ch.code) }.getOrElse { UnicodeScript.UNKNOWN }

    private val IGNORED_SCRIPTS =
        setOf(
            UnicodeScript.LATIN,
            UnicodeScript.COMMON,
            UnicodeScript.INHERITED,
            UnicodeScript.HAN,
            UnicodeScript.HIRAGANA,
            UnicodeScript.KATAKANA,
            UnicodeScript.HANGUL,
            UnicodeScript.DEVANAGARI,
        )
}
