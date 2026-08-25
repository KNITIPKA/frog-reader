package com.example.frogreader.data.parser

/**
 * Bounds content synthesized while mapping HTML/XML into the reader model.
 * A single instance is intended to be shared by every document in one book,
 * so a harmless-looking CSS literal cannot be duplicated into gigabytes of
 * generated text across thousands of matching elements or chapters.
 */
class HtmlExpansionBudget(
    private val maxGeneratedRunChars: Int = DEFAULT_MAX_GENERATED_RUN_CHARS,
    private val maxGeneratedTotalChars: Long = DEFAULT_MAX_GENERATED_TOTAL_CHARS,
) {
    private var generatedChars: Long = 0

    init {
        require(maxGeneratedRunChars > 0)
        require(maxGeneratedTotalChars > 0)
    }

    @Synchronized
    internal fun acceptGenerated(charCount: Int): Boolean {
        if (charCount <= 0 || charCount > maxGeneratedRunChars) return false
        val count = charCount.toLong()
        if (generatedChars > maxGeneratedTotalChars - count) return false
        generatedChars += count
        return true
    }

    companion object {
        const val DEFAULT_MAX_GENERATED_RUN_CHARS = 4 * 1024
        const val DEFAULT_MAX_GENERATED_TOTAL_CHARS = 1024L * 1024L
    }
}
