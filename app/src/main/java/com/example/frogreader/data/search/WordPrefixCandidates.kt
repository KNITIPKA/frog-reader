package com.example.frogreader.data.search

/**
 * Collects candidate entity ids for a word-prefix query, best FTS5 `bm25()` hits first.
 *
 * - [ftsRank] answers the query through the FTS5 prefix index (`"token"*`), which only ever
 *   matches at the start of a word.
 * - [substringFind] (normalized `instr()`) runs **only** for Arabic/Hebrew tokens that can sit
 *   behind an attached clitic (`ال`, `و`, `ה`, `ב`…), which the FTS5 index cannot see. It is never
 *   used for Latin/Cyrillic text, so typing `с` can no longer surface every word containing `с`.
 *
 * The result is a superset: callers must verify each candidate with
 * [SearchTextNormalizer.matchScore] / [SearchTextNormalizer.matchesAllTokens].
 */
internal suspend fun collectWordPrefixCandidates(
    ftsExpr: String?,
    tokens: List<String>,
    ftsRank: suspend (String) -> List<String>,
    substringFind: suspend (String) -> List<String>,
): List<String> {
    val ordered = LinkedHashSet<String>()
    if (ftsExpr != null) {
        ordered.addAll(ftsRank(ftsExpr))
    }
    // Any record that matches a clitic token behind a prefix necessarily contains that token as a
    // substring, so a single (longest, most selective) clitic token is enough for a superset.
    val cliticToken = tokens
        .filter { SearchTextNormalizer.needsCliticCandidates(it) }
        .maxByOrNull { it.length }
    if (cliticToken != null) {
        ordered.addAll(substringFind(cliticToken))
    }
    return ordered.toList()
}
