package com.example.frogreader.data.search

import java.text.Normalizer
import java.text.BreakIterator
import java.util.Locale

/**
 * Normalized text paired with UTF-16 index bounds in the original string.
 *
 * Each character at index `i` in [normalized] corresponds to the half-open UTF-16 range
 * `[origStarts[i], origEnds[i])` in the source text, extended to absorb any stripped
 * combining/vocalization marks (such as Arabic harakat, tatweel, or Hebrew niqqud) that
 * belong to that character. Highlighting `[origStarts[start], origEnds[end - 1])` therefore
 * never splits a base letter from its vowel mark or breaks Arabic cursive shaping.
 */
data class MappedNormalizedText(
    val normalized: String,
    val origStarts: IntArray,
    val origEnds: IntArray,
)

/** How a query token met a word of the searched text; higher [strength] ranks first. */
enum class WordMatchKind(val strength: Int) {
    /** Token sits after an attached Arabic/Hebrew clitic (`كتاب` in `الكتاب`). */
    CLITIC(1),

    /** Token begins a longer word (`tim` in `time`). */
    PREFIX(2),

    /** Token is the whole word (`time` in `time`). */
    WHOLE_WORD(3),
}

/** A word-prefix match in normalized text, `[start, endExclusive)` covering only the token. */
data class WordMatch(
    val start: Int,
    val endExclusive: Int,
    val kind: WordMatchKind,
)

/**
 * Script-aware text normalizer for SQLite FTS5 indexing, word-prefix matching, and match highlighting.
 *
 * Designed for English/Latin, Ukrainian (and other Cyrillic scripts), Arabic, and Hebrew:
 * - Preserves distinct Ukrainian letters (`і`, `ї`, `є`, `ґ`, `й`) without NFD corruption,
 *   while folding Russian `ё -> е` and stripping dictionary stress marks (`U+0301`).
 * - Unifies intra-word apostrophes and Hebrew abbreviations (`'`, `’`, `ʼ`, `′`, `׳`, `״`)
 *   into `U+02BC` (`ʼ`, Unicode category `Lm`), which SQLite's `unicode61` tokenizer treats
 *   as part of a single word token out of the box.
 * - Strips Arabic vocalization (harakat/tashkeel) and tatweel (`ـ`), and unifies Alef/Yeh/Teh-Marbuta/Kaf variants.
 * - Strips Hebrew niqqud and cantillation marks (`U+0591..U+05C7`).
 * - Strips Latin accents (`é -> e`, `ü -> u`) and folds case in `Locale.ROOT`.
 */
object SearchTextNormalizer {

    /** Modifier letter apostrophe (`Lm`): kept inside words by SQLite `unicode61`. */
    const val CANONICAL_APOSTROPHE: Char = '\u02BC'

    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return normalizeInternal(text, trackMapping = false).normalized
    }

    fun normalizeWithMapping(text: String): MappedNormalizedText {
        if (text.isEmpty()) {
            return MappedNormalizedText("", IntArray(0), IntArray(0))
        }
        return normalizeInternal(text, trackMapping = true)
    }

    /** Extracts normalized word tokens suitable for building FTS5 queries. */
    fun tokenize(query: String?): List<String> {
        val norm = normalize(query)
        if (norm.isBlank()) return emptyList()
        val tokens = ArrayList<String>(4)
        val current = StringBuilder()
        var i = 0
        while (i < norm.length) {
            val cp = norm.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (isTokenCodePoint(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
            i += charCount
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    /**
     * Builds a safe FTS5 `MATCH` expression where every token matches as a prefix (`"token"*`),
     * combined with implicit `AND`. Returns `null` when [query] has no searchable tokens.
     */
    fun toFts5PrefixQuery(query: String?): String? {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }

    // ------------------------------------------------------------------ word-prefix matching

    /**
     * Normalized-text ranges `[start, endExclusive)` where [token] begins a word of [norm].
     *
     * This is the single matching rule shared by SQLite FTS5 (`"token"*`), in-memory verification
     * and highlighting: a query never matches in the middle of a word (`с` finds `стілець`, not
     * `вмостилася`). The only exception is clitic-aware matching for Arabic (`و`, `ف`, `ب`, `ك`,
     * `ل`, `ال`, `لل`) and Hebrew (`ו`, `ה`, `ב`, `כ`, `ל`, `מ`, `ש`) attached prefixes, so `كتاب`
     * finds `الكتاب` and `ספר` finds `והספר` — exactly as a native speaker expects.
     *
     * Both arguments must already be normalized by [normalize] / [tokenize].
     */
    fun findWordPrefixMatches(norm: String, token: String): List<WordMatch> {
        if (token.isEmpty() || norm.length < token.length) return emptyList()
        val cliticScript = cliticScriptOf(token)
        var result: ArrayList<WordMatch>? = null
        var from = 0
        while (from <= norm.length - token.length) {
            val idx = norm.indexOf(token, startIndex = from)
            if (idx < 0) break
            val kind = matchKindAt(norm, idx, token, cliticScript)
            if (kind != null) {
                if (result == null) result = ArrayList(2)
                result += WordMatch(idx, idx + token.length, kind)
            }
            from = idx + 1
        }
        return result ?: emptyList()
    }

    /** True when [token] begins a word of the normalized text [norm] (see [findWordPrefixMatches]). */
    fun containsWordPrefix(norm: String, token: String): Boolean = bestMatchKind(norm, token) != null

    /** Strongest [WordMatchKind] of [token] inside [norm], or `null` when it does not match. */
    fun bestMatchKind(norm: String, token: String): WordMatchKind? {
        if (token.isEmpty() || norm.length < token.length) return null
        val cliticScript = cliticScriptOf(token)
        var best: WordMatchKind? = null
        var from = 0
        while (from <= norm.length - token.length) {
            val idx = norm.indexOf(token, startIndex = from)
            if (idx < 0) break
            val kind = matchKindAt(norm, idx, token, cliticScript)
            if (kind != null && (best == null || kind.strength > best.strength)) {
                best = kind
                if (kind == WordMatchKind.WHOLE_WORD) return kind
            }
            from = idx + 1
        }
        return best
    }

    /**
     * Relevance of a multi-field record: sums, per query token, the strongest match across
     * [normalizedFields]. Returns `0` when any token is missing (implicit `AND`).
     */
    fun matchScore(normalizedFields: List<String>, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        var score = 0
        for (token in tokens) {
            var best = 0
            for (field in normalizedFields) {
                val kind = bestMatchKind(field, token) ?: continue
                if (kind.strength > best) best = kind.strength
                if (best == WordMatchKind.WHOLE_WORD.strength) break
            }
            if (best == 0) return 0
            score += best
        }
        return score
    }

    /** True when every query token begins a word in at least one of [normalizedFields]. */
    fun matchesAllTokens(normalizedFields: List<String>, tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all { token -> normalizedFields.any { containsWordPrefix(it, token) } }

    /**
     * True when [token] can match after an attached Arabic/Hebrew clitic. Such tokens cannot be
     * fully answered by the FTS5 prefix index, so repositories add a normalized `instr()`
     * candidate pass for them and verify every candidate with [findWordPrefixMatches].
     */
    fun needsCliticCandidates(token: String): Boolean = cliticScriptOf(token) != CliticScript.NONE

    private enum class CliticScript { NONE, ARABIC, HEBREW }

    private fun cliticScriptOf(token: String): CliticScript {
        if (token.length < MIN_CLITIC_STEM_LENGTH) return CliticScript.NONE
        val first = token.codePointAt(0)
        return when (first) {
            in 0x0620..0x064A, in 0x0671..0x06D3 -> CliticScript.ARABIC
            in 0x05D0..0x05EA -> CliticScript.HEBREW
            else -> CliticScript.NONE
        }
    }

    private fun matchKindAt(norm: String, idx: Int, token: String, cliticScript: CliticScript): WordMatchKind? {
        val end = idx + token.length
        val endsWord = end >= norm.length || !isTokenCodePoint(norm.codePointAt(end))
        if (idx == 0 || !isTokenCodePoint(norm.codePointBefore(idx))) {
            return if (endsWord) WordMatchKind.WHOLE_WORD else WordMatchKind.PREFIX
        }
        if (cliticScript == CliticScript.NONE) return null
        var wordStart = idx
        while (wordStart > 0 && isTokenCodePoint(norm.codePointBefore(wordStart))) {
            wordStart -= Character.charCount(norm.codePointBefore(wordStart))
        }
        val clitic = norm.substring(wordStart, idx)
        val valid = when (cliticScript) {
            CliticScript.ARABIC -> ARABIC_CLITICS.matches(clitic)
            CliticScript.HEBREW -> clitic.length <= 3 && clitic.all { it in HEBREW_CLITIC_LETTERS }
            CliticScript.NONE -> false
        }
        return if (valid) WordMatchKind.CLITIC else null
    }

    /** Minimum stem length before clitic matching kicks in, so `ب` alone never matches mid-word. */
    private const val MIN_CLITIC_STEM_LENGTH = 2

    /** Conjunction `و`/`ف`, preposition `ب`/`ك`/`ل`, article `ال`, and the contracted `لل`. */
    private val ARABIC_CLITICS = Regex("^[\\u0648\\u0641]?(?:[\\u0628\\u0643\\u0644]?\\u0627\\u0644|\\u0644\\u0644|[\\u0628\\u0643\\u0644])\$|^[\\u0648\\u0641]\$")

    /** Hebrew one-letter prefixes (`ו ה ב כ ל מ ש`), which can stack up to three (`ושב…`). */
    private const val HEBREW_CLITIC_LETTERS = "\u05D5\u05D4\u05D1\u05DB\u05DC\u05DE\u05E9"

    private fun isTokenCodePoint(cp: Int): Boolean {
        if (cp == CANONICAL_APOSTROPHE.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER,
            Character.LOWERCASE_LETTER,
            Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER,
            Character.OTHER_LETTER,
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    private fun normalizeInternal(text: String, trackMapping: Boolean): MappedNormalizedText {
        if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            return normalizeCanonicalText(text, trackMapping)
        }
        // Compose before stripping marks: decomposed Ukrainian і + diaeresis and и + breve
        // must remain ї and й, just like their precomposed spellings in the FTS index.
        if (!trackMapping) {
            return normalizeCanonicalText(Normalizer.normalize(text, Normalizer.Form.NFC), false)
        }
        val canonical = StringBuilder(text.length)
        val sourceStarts = ArrayList<Int>(text.length)
        val sourceEnds = ArrayList<Int>(text.length)
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            val cluster = Normalizer.normalize(text.substring(start, end), Normalizer.Form.NFC)
            canonical.append(cluster)
            repeat(cluster.length) {
                sourceStarts += start
                sourceEnds += end
            }
            start = end
            end = boundaries.next()
        }
        val mapped = normalizeCanonicalText(canonical.toString(), true)
        return MappedNormalizedText(
            mapped.normalized,
            IntArray(mapped.origStarts.size) { sourceStarts[mapped.origStarts[it]] },
            IntArray(mapped.origEnds.size) { sourceEnds[mapped.origEnds[it] - 1] },
        )
    }

    private fun normalizeCanonicalText(text: String, trackMapping: Boolean): MappedNormalizedText {
        val len = text.length
        val out = StringBuilder(len)
        var starts = if (trackMapping) IntArray(len + 8) else EMPTY_INTS
        var ends = if (trackMapping) IntArray(len + 8) else EMPTY_INTS
        var outLen = 0

        fun ensureCapacity(needed: Int) {
            if (!trackMapping || needed <= starts.size) return
            val nextSize = maxOf(needed, starts.size * 2)
            starts = starts.copyOf(nextSize)
            ends = ends.copyOf(nextSize)
        }

        fun appendMappedChar(ch: Char, origStart: Int, origEnd: Int) {
            out.append(ch)
            if (trackMapping) {
                ensureCapacity(outLen + 1)
                starts[outLen] = origStart
                ends[outLen] = origEnd
            }
            outLen++
        }

        fun appendMappedString(str: String, origStart: Int, origEnd: Int) {
            for (k in 0 until str.length) {
                appendMappedChar(str[k], origStart, origEnd)
            }
        }

        fun extendPreviousEnd(origStart: Int, origEnd: Int) {
            // A standalone mark after whitespace belongs to that whitespace, not the last word.
            if (trackMapping && outLen > 0 && ends[outLen - 1] == origStart) {
                ends[outLen - 1] = origEnd
            }
        }

        var pendingSpaceStart = -1
        var pendingSpaceEnd = -1

        fun flushPendingSpaceIfNeeded() {
            if (pendingSpaceStart >= 0 && outLen > 0) {
                appendMappedChar(' ', pendingSpaceStart, pendingSpaceEnd)
            }
            pendingSpaceStart = -1
            pendingSpaceEnd = -1
        }

        var i = 0
        while (i < len) {
            val cp = text.codePointAt(i)
            val cpLen = Character.charCount(cp)
            val nextIdx = i + cpLen

            when {
                isIgnorableFormatOrMark(cp) -> {
                    extendPreviousEnd(i, nextIdx)
                }

                // unicode61 considers private-use glyphs word characters, while the Kotlin
                // matcher deliberately does not. Make their boundary explicit before indexing.
                Character.isWhitespace(cp) || Character.getType(cp) == Character.PRIVATE_USE.toInt() -> {
                    if (outLen > 0) {
                        if (pendingSpaceStart < 0) pendingSpaceStart = i
                        pendingSpaceEnd = nextIdx
                    }
                }

                isWordInternalConnectorCandidate(cp) &&
                    hasAdjacentLetter(text, i, forward = false) &&
                    hasAdjacentLetter(text, nextIdx, forward = true) -> {
                    flushPendingSpaceIfNeeded()
                    appendMappedChar(CANONICAL_APOSTROPHE, i, nextIdx)
                }

                cp in 0x0400..0x052F -> {
                    flushPendingSpaceIfNeeded()
                    val lower = Character.toLowerCase(cp)
                    val folded = if (lower == 0x0451) 0x0435 else lower // ё -> е; і, ї, є, ґ, й stay intact
                    if (Character.charCount(folded) == 1) {
                        appendMappedChar(folded.toChar(), i, nextIdx)
                    } else {
                        appendMappedString(String(Character.toChars(folded)), i, nextIdx)
                    }
                }

                cp in 0x0600..0x06FF -> {
                    flushPendingSpaceIfNeeded()
                    val folded = foldArabicCodePoint(cp)
                    if (folded != 0) {
                        appendMappedChar(folded.toChar(), i, nextIdx)
                    } else {
                        extendPreviousEnd(i, nextIdx)
                    }
                }

                cp in 0x0590..0x05FF -> {
                    flushPendingSpaceIfNeeded()
                    appendMappedChar(cp.toChar(), i, nextIdx)
                }

                isLatinPrecomposedOrExtended(cp) -> {
                    flushPendingSpaceIfNeeded()
                    val folded = foldLatinCodePoint(cp)
                    if (folded.isNotEmpty()) {
                        appendMappedString(folded, i, nextIdx)
                    }
                }

                Character.getType(cp).let {
                    it == Character.NON_SPACING_MARK.toInt() ||
                        it == Character.COMBINING_SPACING_MARK.toInt() ||
                        it == Character.ENCLOSING_MARK.toInt()
                } -> {
                    // Combining acute stress marks on Cyrillic or stray Latin combining marks.
                    extendPreviousEnd(i, nextIdx)
                }

                else -> {
                    flushPendingSpaceIfNeeded()
                    val lower = Character.toLowerCase(cp)
                    if (Character.charCount(lower) == 1) {
                        appendMappedChar(lower.toChar(), i, nextIdx)
                    } else {
                        appendMappedString(String(Character.toChars(lower)), i, nextIdx)
                    }
                }
            }

            i = nextIdx
        }

        return if (trackMapping) {
            MappedNormalizedText(
                normalized = out.toString(),
                origStarts = starts.copyOf(outLen),
                origEnds = ends.copyOf(outLen),
            )
        } else {
            MappedNormalizedText(out.toString(), EMPTY_INTS, EMPTY_INTS)
        }
    }

    private fun isIgnorableFormatOrMark(cp: Int): Boolean = when {
        cp == 0x00AD -> true // Soft hyphen
        cp == 0x0640 -> true // Arabic Tatweel / Kashida
        cp in 0x200B..0x200F -> true // Zero-width spaces & LTR/RTL marks
        cp in 0x202A..0x202E -> true // BiDi embedding/override
        cp in 0x2066..0x2069 -> true // BiDi isolates
        cp == 0xFEFF -> true // BOM
        // Arabic vocalization (Tashkeel / Harakat) and Quranic annotations:
        cp in 0x0610..0x061A -> true
        cp in 0x064B..0x065F -> true
        cp == 0x0670 -> true
        cp in 0x06D6..0x06ED -> true
        // Hebrew Niqqud and cantillation marks:
        cp in 0x0591..0x05BD -> true
        cp == 0x05BF -> true
        cp in 0x05C1..0x05C2 -> true
        cp in 0x05C4..0x05C5 -> true
        cp == 0x05C7 -> true
        // Acute/grave stress marks commonly placed on Cyrillic or Latin vowels:
        cp == 0x0300 || cp == 0x0301 -> true
        else -> false
    }

    private fun isWordInternalConnectorCandidate(cp: Int): Boolean = when (cp) {
        '\''.code,
        '\u2019'.code, // ’ right single quotation mark
        '\u2018'.code, // ‘ left single quotation mark
        '\u02BC'.code, // ʼ modifier letter apostrophe
        '\u2032'.code, // ′ prime
        '\u05F3'.code, // ׳ Hebrew geresh
        '\u05F4'.code, // ״ Hebrew gershayim
        '"'.code -> true
        else -> false
    }

    /** Skips ignorable marks when checking whether an apostrophe/gershayim sits inside a word. */
    private fun hasAdjacentLetter(text: String, boundaryIndex: Int, forward: Boolean): Boolean {
        var idx = boundaryIndex
        while (if (forward) idx < text.length else idx > 0) {
            val cp = if (forward) text.codePointAt(idx) else text.codePointBefore(idx)
            val step = Character.charCount(cp)
            if (isIgnorableFormatOrMark(cp)) {
                idx += if (forward) step else -step
                continue
            }
            return Character.isLetter(cp)
        }
        return false
    }

    private fun foldArabicCodePoint(cp: Int): Int = when (cp) {
        0x0622, 0x0623, 0x0625, 0x0671 -> 0x0627 // آ أ إ ٱ -> ا
        0x0649, 0x06CC -> 0x064A // ى ی -> ي
        0x0629, 0x06C0 -> 0x0647 // ة ۀ -> ه
        0x06A9 -> 0x0643 // ک -> ك
        0x0621 -> 0x0621 // ء preserved
        else -> cp
    }

    private fun isLatinPrecomposedOrExtended(cp: Int): Boolean =
        cp in 0x00C0..0x024F || cp in 0x1E00..0x1EFF

    private fun foldLatinCodePoint(cp: Int): String = when (val lower = Character.toLowerCase(cp)) {
        0x00DF -> "ss" // ß
        0x00E6 -> "ae" // æ
        0x0153 -> "oe" // œ
        0x00F8 -> "o"  // ø
        0x0142 -> "l"  // ł
        0x00F0 -> "d"  // ð
        0x00FE -> "th" // þ
        else -> {
            val decomposed = Normalizer.normalize(String(Character.toChars(lower)), Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            var idx = 0
            while (idx < decomposed.length) {
                val sub = decomposed.codePointAt(idx)
                val type = Character.getType(sub)
                if (type != Character.NON_SPACING_MARK.toInt() &&
                    type != Character.COMBINING_SPACING_MARK.toInt() &&
                    type != Character.ENCLOSING_MARK.toInt()
                ) {
                    sb.appendCodePoint(Character.toLowerCase(sub))
                }
                idx += Character.charCount(sub)
            }
            sb.toString()
        }
    }

    private val EMPTY_INTS = IntArray(0)
}
