package com.example.frogreader.ui.reader

import java.util.Locale
import kotlin.math.roundToInt

/** Count destinations, not their enclosing volumes/parts or out-of-flow notes. */
internal fun readerBookChapterStarts(
    navigation: List<ReaderNavigationEntry>,
    starts: List<Int>,
    depths: List<Int>,
    itemCount: Int,
): List<Int> {
    val entries = navigation.filter { (it.target as? ReaderNavigationTarget.ReadingOrder)?.flatItemIndex in 0 until itemCount }
    val candidates = if (entries.isNotEmpty()) {
        val tree = ReaderContentsTree(entries.map { it.depth })
        entries.mapIndexedNotNull { index, entry ->
            (entry.target as ReaderNavigationTarget.ReadingOrder).flatItemIndex.takeUnless { tree.isGroup[index] }
        }
    } else {
        val tree = ReaderContentsTree(starts.indices.map { depths.getOrElse(it) { 0 } })
        starts.filterIndexed { index, _ -> !tree.isGroup[index] }
    }
    return candidates.filter { it in 0 until itemCount }.distinct().sorted()
}

internal fun readerChaptersRemaining(stops: List<Float>, fraction: Float): Int =
    stops.count { it > fraction.coerceIn(0f, 1f) + 0.000001f }

/** Discrete pages left in the current chapter according to the given whole-book fraction. */
internal fun readerChapterPagesLeft(
    chapterStartPages: List<Int>?,
    bookPageCount: Int?,
    fraction: Float,
): Int? {
    if (chapterStartPages.isNullOrEmpty() || bookPageCount == null || bookPageCount <= 0) return null
    val targetPage = ReaderNavigationPolicy.progressTargetIndex(fraction, 0, bookPageCount - 1)
    val chapterIndex = chapterStartPages.indexOfLast { it <= targetPage }.coerceAtLeast(0)
    val nextStart = chapterStartPages.drop(chapterIndex + 1).firstOrNull { it > chapterStartPages[chapterIndex] }
    val chapterEndPage = if (nextStart != null) (nextStart - 1).coerceAtLeast(chapterStartPages[chapterIndex]) else (bookPageCount - 1)
    return (chapterEndPage - targetPage).coerceAtLeast(0)
}

/** Percentage left in the current chapter when continuous scrolling without discrete pages. */
internal fun readerChapterPercentRemaining(
    chapterStarts: List<Int>?,
    itemCount: Int?,
    fraction: Float,
): Int? {
    if (chapterStarts.isNullOrEmpty() || itemCount == null || itemCount <= 0) return null
    val currentElement = ReaderNavigationPolicy.progressTargetIndex(fraction, 0, itemCount - 1)
    val chapterIndex = chapterStarts.indexOfLast { it <= currentElement }.coerceAtLeast(0)
    val nextStart = chapterStarts.drop(chapterIndex + 1).firstOrNull { it > chapterStarts[chapterIndex] } ?: itemCount
    val chapterStart = chapterStarts[chapterIndex]
    val span = (nextStart - chapterStart).coerceAtLeast(1)
    val chapterFraction = ((currentElement - chapterStart).toFloat() / span).coerceIn(0f, 1f)
    return ((1f - chapterFraction) * 100).roundToInt()
}

internal data class ReaderChapterHeading(val label: String?, val title: String)

private val numberedChapter = Regex(
    "^(chapter|section|part|book|глава|раздел|часть|книга|розділ|підрозділ|частина)\\s+" +
        "(\\d+(?:\\.\\d+)*|[ivxlcdm]+)(?=\\s|[.:：—–-]|$)",
    RegexOption.IGNORE_CASE,
)
private val bareChapterNumber = Regex("^(?:[IVXLCDM]+|\\d+(?:\\.\\d+)*)(?=[.:：](?!\\d))")
private val chapterTitleSeparator = Regex("^\\s*[.:：—–-]?\\s*")

/** Split only recognizable numbering; an ordinary title must never lose its first word. */
internal fun readerChapterHeading(text: String): ReaderChapterHeading {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    val prefix = numberedChapter.find(normalized) ?: bareChapterNumber.find(normalized)
        ?: return ReaderChapterHeading(null, normalized)
    val title = normalized.substring(prefix.range.last + 1).replaceFirst(chapterTitleSeparator, "")
    return if (title.isBlank()) ReaderChapterHeading(null, normalized)
        else ReaderChapterHeading(prefix.value, title)
}

/** A nav root sometimes repeats the publication title rather than naming a part. */
internal fun readerVisibleChapterParents(bookTitle: String, parents: List<String>): List<String> {
    fun identity(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    val book = identity(bookTitle)
    return parents.dropWhile { book.isNotEmpty() && identity(it) == book }
}

/** The current TOC section, including its enclosing books/parts/chapters. */
internal data class ReaderChapterContext(
    val title: String?,
    val parents: List<String>,
    val firstItem: Int,
    val endItemExclusive: Int,
    val navigationIndex: Int? = null,
)

/** Resolves navigation anchors, including several subchapters in one spine chapter. */
internal class ReaderChapterContexts(
    private val navigation: List<ReaderNavigationEntry>,
    private val chapterStarts: List<Int>,
    private val chapterTitles: List<String?>,
    chapterDepths: List<Int>,
    private val itemCount: Int,
) {
    private val navigationTree = ReaderContentsTree(navigation.map { it.depth })
    private val chapterTree = ReaderContentsTree(chapterDepths)
    private val readingEntries = navigation.mapIndexedNotNull { index, entry ->
        (entry.target as? ReaderNavigationTarget.ReadingOrder)?.let { index to it }
    }.groupBy { it.second.chapterIndex }

    fun at(flatIndex: Int, chapter: Int): ReaderChapterContext {
        val chapterStart = chapterStarts.getOrElse(chapter) { 0 }
        val chapterEnd = chapterStarts.getOrNull(chapter + 1) ?: itemCount
        val entries = readingEntries[chapter].orEmpty().filter {
            it.second.flatItemIndex in chapterStart until chapterEnd
        }
        // Parent and child often share an anchor. Prefer the deepest entry;
        // later sibling aliases win only when their depth also matches.
        val current = entries.filter { it.second.flatItemIndex <= flatIndex }
            .maxWithOrNull(
                compareBy<Pair<Int, ReaderNavigationTarget.ReadingOrder>> { it.second.flatItemIndex }
                    .thenBy { navigation[it.first].depth }
                    .thenBy { it.first },
            )
        if (current == null) {
            return ReaderChapterContext(
                title = chapterTitles.getOrNull(chapter),
                parents = chapterTree.ancestorsOf(chapter).asReversed().mapNotNull {
                    chapterTitles.getOrNull(it)?.takeIf(String::isNotBlank)
                },
                firstItem = chapterStart,
                endItemExclusive = entries.minOfOrNull { it.second.flatItemIndex } ?: chapterEnd,
            )
        }
        val (entryIndex, target) = current
        return ReaderChapterContext(
            title = navigation[entryIndex].title?.takeIf(String::isNotBlank)
                ?: chapterTitles.getOrNull(chapter),
            parents = navigationTree.ancestorsOf(entryIndex).asReversed().mapNotNull {
                navigation[it].title?.takeIf(String::isNotBlank)
            },
            firstItem = target.flatItemIndex,
            endItemExclusive = entries.asSequence().map { it.second.flatItemIndex }
                .filter { it > target.flatItemIndex }.minOrNull() ?: chapterEnd,
            navigationIndex = entryIndex,
        )
    }
}

/** Preview metadata for chapter display and haptic tick identification while scrubbing. */
internal data class ReaderChapterPreview(
    val chapterId: Any,
    val label: String,
    val parents: List<String> = emptyList(),
)

/** Pure function resolving the chapter preview for any given track fraction. */
internal fun resolveReaderChapterPreview(
    fraction: Float,
    usePages: Boolean,
    pageCount: Int?,
    fullPages: List<BookPage>?,
    chapterStartPages: List<Int>?,
    chapterStarts: List<Int>,
    itemCount: Int,
    chapterAtItem: (Int) -> Int,
    chapterContexts: ReaderChapterContexts,
    bookTitle: String,
    defaultChapterName: (Int) -> String,
): ReaderChapterPreview {
    val safeF = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val item = if (usePages && pageCount != null && pageCount > 0) {
        val targetPage = ReaderNavigationPolicy.progressTargetIndex(
            fraction = safeF,
            firstIndex = 0,
            lastIndexInclusive = pageCount - 1,
        )
        fullPages?.getOrNull(targetPage)?.firstItemIndex
            ?: run {
                val chap = chapterStartPages?.indexOfLast { it <= targetPage }?.coerceAtLeast(0) ?: 0
                chapterStarts.getOrElse(chap) { 0 }
            }
    } else {
        ReaderNavigationPolicy.progressTargetIndex(
            fraction = safeF,
            firstIndex = 0,
            lastIndexInclusive = (itemCount - 1).coerceAtLeast(0),
        )
    }
    val safeItem = item.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    val chapter = chapterAtItem(safeItem)
    val ctx = chapterContexts.at(safeItem, chapter)
    val label = ctx.title?.takeIf(String::isNotBlank) ?: defaultChapterName(chapter)
    val parents = readerVisibleChapterParents(bookTitle, ctx.parents)
    return ReaderChapterPreview(
        chapterId = ctx.navigationIndex?.let { "nav_$it" } ?: "chap_$chapter",
        label = label,
        parents = parents,
    )
}

