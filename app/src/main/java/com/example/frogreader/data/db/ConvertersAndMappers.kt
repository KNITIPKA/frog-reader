package com.example.frogreader.data.db

import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.LibraryIndexCorruptedException
import com.example.frogreader.data.model.AchievementProgress
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.PrivateQueueAttachment
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogDateOrder
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingProgress
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.model.Shelf
import com.example.frogreader.data.model.sortTs
import com.example.frogreader.data.search.SearchTextNormalizer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal object DbJson {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val pdfBoundsListSerializer = ListSerializer(PdfTextBounds.serializer())

    private inline fun <T> decodeStoredJson(block: () -> T): T = try {
        block()
    } catch (error: IllegalArgumentException) {
        throw LibraryIndexCorruptedException("SQLite contains invalid stored JSON.", error)
    }

    fun encodeStringList(list: List<String>): String =
        if (list.isEmpty()) "[]" else codec.encodeToString(stringListSerializer, list)

    fun decodeStringList(raw: String): List<String> =
        decodeStoredJson { codec.decodeFromString(stringListSerializer, raw) }

    fun encodeStringMap(map: Map<String, String>): String =
        if (map.isEmpty()) "{}" else codec.encodeToString(stringMapSerializer, map)

    fun decodeStringMap(raw: String): Map<String, String> =
        decodeStoredJson { codec.decodeFromString(stringMapSerializer, raw) }

    fun encodeReaderSettings(settings: ReaderSettings?): String? =
        settings?.let { codec.encodeToString(ReaderSettings.serializer(), it) }

    fun decodeReaderSettings(raw: String?): ReaderSettings? =
        raw?.let { decodeStoredJson { codec.decodeFromString(ReaderSettings.serializer(), it) } }

    fun encodeDocumentLocation(location: DocumentLocation?): String? =
        location?.let { codec.encodeToString(DocumentLocation.serializer(), it) }

    fun decodeDocumentLocation(raw: String?): DocumentLocation? =
        raw?.let { decodeStoredJson { codec.decodeFromString(DocumentLocation.serializer(), it) } }

    fun encodePdfBounds(bounds: List<PdfTextBounds>): String =
        if (bounds.isEmpty()) "[]" else codec.encodeToString(pdfBoundsListSerializer, bounds)

    fun decodePdfBounds(raw: String): List<PdfTextBounds> =
        decodeStoredJson { codec.decodeFromString(pdfBoundsListSerializer, raw) }

    fun encodePrivateAttachment(attachment: PrivateQueueAttachment?): String? =
        attachment?.let { codec.encodeToString(PrivateQueueAttachment.serializer(), it) }

    fun decodePrivateAttachment(raw: String?): PrivateQueueAttachment? =
        raw?.let { decodeStoredJson { codec.decodeFromString(PrivateQueueAttachment.serializer(), it) } }
}

internal inline fun <reified T : Enum<T>> readDatabaseEnum(value: String): T = try {
    enumValueOf<T>(value)
} catch (error: IllegalArgumentException) {
    throw LibraryIndexCorruptedException("SQLite contains invalid ${T::class.java.simpleName}: $value", error)
}

internal fun Book.toBookEntity(
    docId: Long = 0L,
    shelfId: String? = null,
    shelfPosition: Int = 0,
    updatedAtMillis: Long = 0L,
): BookEntity = BookEntity(
    docId = docId,
    id = id,
    title = title,
    author = author,
    format = format.name,
    fileName = fileName,
    coverFileName = coverFileName,
    addedAtMillis = addedAtMillis,
    contentHash = contentHash,
    sizeBytes = sizeBytes,
    lastOpenedAtMillis = lastOpenedAtMillis,
    startedAtMillis = startedAtMillis,
    finishedAtMillis = finishedAtMillis,
    readingSeconds = readingSeconds,
    status = status.name,
    rating = rating,
    ratingHalfStars = ratingHalfStars,
    activeReadingLogId = activeReadingLogId,
    suppressReadingLog = suppressReadingLog,
    review = review,
    reviewUpdatedAtMillis = reviewUpdatedAtMillis,
    readingQueueNote = readingQueueNote,
    readerSettingsJson = DbJson.encodeReaderSettings(readerSettings),
    genresJson = DbJson.encodeStringList(genres),
    series = series,
    seriesNumber = seriesNumber,
    publisher = publisher,
    year = year,
    isbn = isbn,
    translatorsJson = DbJson.encodeStringList(translators),
    description = description,
    language = language,
    generatePdfCover = generatePdfCover,
    additionalMetadataJson = DbJson.encodeStringMap(additionalMetadata),
    pdfAnnotationFingerprint = pdfAnnotationFingerprint,
    shelfId = shelfId,
    shelfPosition = shelfPosition,
    updatedAtMillis = updatedAtMillis,
)

internal fun Book.toProgressEntity(updatedAtMillis: Long = 0L): BookProgressEntity =
    progress.toEntity(bookId = id, updatedAtMillis = updatedAtMillis)

internal fun ReadingProgress.toEntity(bookId: String, updatedAtMillis: Long = 0L): BookProgressEntity =
    BookProgressEntity(
        bookId = bookId,
        chapterIndex = chapterIndex,
        elementIndex = elementIndex,
        scrollOffset = scrollOffset,
        fraction = fraction,
        pagesLeftInChapter = pagesLeftInChapter,
        totalPages = totalPages,
        documentLocationJson = DbJson.encodeDocumentLocation(documentLocation),
        updatedAtMillis = updatedAtMillis,
    )

internal fun BookProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    chapterIndex = chapterIndex,
    elementIndex = elementIndex,
    scrollOffset = scrollOffset,
    fraction = fraction,
    pagesLeftInChapter = pagesLeftInChapter,
    totalPages = totalPages,
    documentLocation = DbJson.decodeDocumentLocation(documentLocationJson),
)

internal fun BookEntity.toDomain(
    progress: ReadingProgress?,
    quotes: List<Quote>,
    bookmarks: List<Bookmark>,
): Book = Book(
    id = id,
    title = title,
    author = author,
    format = readDatabaseEnum<BookFormat>(format),
    fileName = fileName,
    coverFileName = coverFileName,
    addedAtMillis = addedAtMillis,
    contentHash = contentHash,
    sizeBytes = sizeBytes,
    lastOpenedAtMillis = lastOpenedAtMillis,
    progress = progress ?: ReadingProgress(),
    bookmarks = bookmarks,
    quotes = quotes,
    startedAtMillis = startedAtMillis,
    finishedAtMillis = finishedAtMillis,
    readingSeconds = readingSeconds,
    status = readDatabaseEnum<ReadingStatus>(status),
    rating = rating,
    ratingHalfStars = ratingHalfStars,
    activeReadingLogId = activeReadingLogId,
    suppressReadingLog = suppressReadingLog,
    review = review,
    reviewUpdatedAtMillis = reviewUpdatedAtMillis,
    readingQueueNote = readingQueueNote,
    readerSettings = DbJson.decodeReaderSettings(readerSettingsJson),
    genres = DbJson.decodeStringList(genresJson),
    series = series,
    seriesNumber = seriesNumber,
    publisher = publisher,
    year = year,
    isbn = isbn,
    translators = DbJson.decodeStringList(translatorsJson),
    description = description,
    language = language,
    generatePdfCover = generatePdfCover,
    additionalMetadata = DbJson.decodeStringMap(additionalMetadataJson),
    pdfAnnotationFingerprint = pdfAnnotationFingerprint,
)

internal fun Quote.toEntity(bookId: String, docId: Long = 0L): QuoteEntity = QuoteEntity(
    docId = docId,
    id = id,
    bookId = bookId,
    text = text,
    note = note,
    chapterIndex = chapterIndex,
    startItem = startItem,
    startChar = startChar,
    endItem = endItem,
    endChar = endChar,
    color = color.name,
    documentLocationJson = DbJson.encodeDocumentLocation(documentLocation),
    pdfSelectionJson = DbJson.encodePdfBounds(pdfSelection),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun QuoteEntity.toDomain(): Quote = Quote(
    id = id,
    text = text,
    chapterIndex = chapterIndex,
    createdAtMillis = createdAtMillis,
    startItem = startItem,
    startChar = startChar,
    endItem = endItem,
    endChar = endChar,
    note = note,
    bookId = bookId,
    updatedAtMillis = updatedAtMillis,
    color = readDatabaseEnum<QuoteColor>(color),
    documentLocation = DbJson.decodeDocumentLocation(documentLocationJson),
    pdfSelection = DbJson.decodePdfBounds(pdfSelectionJson),
)

internal fun Bookmark.toEntity(bookId: String, docId: Long = 0L): BookmarkEntity = BookmarkEntity(
    docId = docId,
    id = id,
    bookId = bookId,
    flatIndex = flatIndex,
    chapterIndex = chapterIndex,
    preview = preview,
    orphaned = orphaned,
    documentLocationJson = DbJson.encodeDocumentLocation(documentLocation),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    flatIndex = flatIndex,
    chapterIndex = chapterIndex,
    preview = preview,
    createdAtMillis = createdAtMillis,
    orphaned = orphaned,
    bookId = bookId,
    updatedAtMillis = updatedAtMillis,
    documentLocation = DbJson.decodeDocumentLocation(documentLocationJson),
)

internal fun Shelf.toEntity(): ShelfEntity = ShelfEntity(
    id = id,
    name = name,
    createdAtMillis = createdAtMillis,
    sortKey = sortKey,
    updatedAtMillis = updatedAtMillis,
)

internal fun ShelfEntity.toDomain(bookIds: List<String>): Shelf = Shelf(
    id = id,
    name = name,
    bookIds = bookIds,
    createdAtMillis = createdAtMillis,
    sortKey = sortKey,
    updatedAtMillis = updatedAtMillis,
)

internal fun ReadingQueueEntry.toEntity(
    priorityOrder: Int,
    libraryBookIds: Set<String>,
    docId: Long = 0L,
): ReadingQueueEntity {
    val attached = attachment
    val isLinkedLibraryBook = attached != null && attached.id in libraryBookIds
    return ReadingQueueEntity(
        docId = docId,
        id = id,
        title = title,
        author = author,
        note = note,
        priorityOrder = priorityOrder,
        linkedBookId = if (isLinkedLibraryBook) attached.id else null,
        privateAttachmentJson = if (attached != null && !isLinkedLibraryBook) {
            DbJson.encodePrivateAttachment(PrivateQueueAttachment.fromBook(attached))
        } else {
            null
        },
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

internal fun ReadingQueueEntity.toDomain(booksById: Map<String, Book>): ReadingQueueEntry {
    val resolvedAttachment = linkedBookId?.let { booksById[it] }
        ?: DbJson.decodePrivateAttachment(privateAttachmentJson)?.toBook()
    return ReadingQueueEntry(
        id = id,
        title = title,
        author = author,
        note = note,
        createdAtMillis = createdAtMillis,
        attachment = resolvedAttachment,
        updatedAtMillis = updatedAtMillis,
    )
}

internal fun ReadingLogEntry.toEntity(manualOrder: Int, docId: Long = 0L): ReadingLogEntity =
    ReadingLogEntity(
        docId = docId,
        id = id,
        manualOrder = manualOrder,
        title = title,
        author = author,
        series = series,
        source = source.name,
        status = status.name,
        startedYear = startedOn?.year,
        startedMonth = startedOn?.month,
        startedDay = startedOn?.day,
        finishedYear = finishedOn?.year,
        finishedMonth = finishedOn?.month,
        finishedDay = finishedOn?.day,
        linkedBookId = linkedBookId,
        coverFileName = coverFileName,
        readingSeconds = readingSeconds,
        lastOpenedAtMillis = lastOpenedAtMillis,
        ratingHalfStars = ratingHalfStars,
        review = review,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        finishedAtMillis = finishedAtMillis,
        bookFormat = bookFormat?.name,
        startedOrderYear = startedDateOrder?.date?.year,
        startedOrderMonth = startedDateOrder?.date?.month,
        startedOrderDay = startedDateOrder?.date?.day,
        startedOrderRank = startedDateOrder?.rank,
        finishedOrderYear = finishedDateOrder?.date?.year,
        finishedOrderMonth = finishedDateOrder?.date?.month,
        finishedOrderDay = finishedDateOrder?.date?.day,
        finishedOrderRank = finishedDateOrder?.rank,
        progressFraction = progressFraction,
    )

internal fun ReadingLogEntity.toDomain(): ReadingLogEntry = ReadingLogEntry(
    id = id,
    title = title,
    author = author,
    series = series,
    source = readDatabaseEnum<ReadingLogSource>(source),
    status = readDatabaseEnum<ReadingLogStatus>(status),
    startedOn = startedYear?.let { ReadingLogDate(it, startedMonth, startedDay) }?.takeIf { it.isValid() },
    finishedOn = finishedYear?.let { ReadingLogDate(it, finishedMonth, finishedDay) }?.takeIf { it.isValid() },
    linkedBookId = linkedBookId,
    coverFileName = coverFileName,
    readingSeconds = readingSeconds,
    lastOpenedAtMillis = lastOpenedAtMillis,
    ratingHalfStars = ratingHalfStars,
    review = review,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    finishedAtMillis = finishedAtMillis,
    bookFormat = bookFormat?.let { readDatabaseEnum<BookFormat>(it) },
    startedDateOrder = if (startedOrderYear != null && startedOrderRank != null) {
        val d = ReadingLogDate(startedOrderYear, startedOrderMonth, startedOrderDay)
        if (d.isValid()) ReadingLogDateOrder(d, startedOrderRank) else null
    } else null,
    finishedDateOrder = if (finishedOrderYear != null && finishedOrderRank != null) {
        val d = ReadingLogDate(finishedOrderYear, finishedOrderMonth, finishedOrderDay)
        if (d.isValid()) ReadingLogDateOrder(d, finishedOrderRank) else null
    } else null,
    progressFraction = progressFraction,
)

internal fun ReadingStats.toEntities(): Pair<List<DailyReadingStatEntity>, ReadingStatsMetaEntity> =
    dailySeconds.entries
        .filter { it.value > 0L }
        .map { (date, secs) -> DailyReadingStatEntity(isoDate = date, seconds = secs) } to
        ReadingStatsMetaEntity(singletonId = 1, lastReadAtMillis = lastReadAtMillis)

internal fun buildReadingStats(
    daily: List<DailyReadingStatEntity>,
    meta: ReadingStatsMetaEntity?,
): ReadingStats = ReadingStats(
    dailySeconds = daily.associate { it.isoDate to it.seconds },
    lastReadAtMillis = meta?.lastReadAtMillis,
)

internal fun AchievementProgressEntity.toDomain(): AchievementProgress = AchievementProgress(
    achievementId = achievementId,
    currentValue = currentValue,
    unlocked = unlocked,
    unlockedAtMillis = unlockedAtMillis,
)

internal fun AchievementProgress.toEntity(): AchievementProgressEntity = AchievementProgressEntity(
    achievementId = achievementId,
    currentValue = currentValue,
    unlocked = unlocked,
    unlockedAtMillis = unlockedAtMillis,
)

// ---------------------------------------------------------------------------
// FTS5 row builders (using SearchTextNormalizer)
// ---------------------------------------------------------------------------

internal fun BookEntity.toFts(rowId: Long = docId): BookFtsEntity = BookFtsEntity(
    rowId = rowId,
    entityId = id,
    title = SearchTextNormalizer.normalize(title),
    author = SearchTextNormalizer.normalize(author),
    series = SearchTextNormalizer.normalize(series),
    description = SearchTextNormalizer.normalize(description),
)

internal fun QuoteEntity.toFts(rowId: Long = docId): QuoteFtsEntity = QuoteFtsEntity(
    rowId = rowId,
    entityId = id,
    text = SearchTextNormalizer.normalize(text),
    note = SearchTextNormalizer.normalize(note),
)

internal fun BookmarkEntity.toFts(rowId: Long = docId): BookmarkFtsEntity = BookmarkFtsEntity(
    rowId = rowId,
    entityId = id,
    preview = SearchTextNormalizer.normalize(preview),
)

internal fun ReadingQueueEntity.toFts(rowId: Long = docId): ReadingQueueFtsEntity = ReadingQueueFtsEntity(
    rowId = rowId,
    entityId = id,
    title = SearchTextNormalizer.normalize(title),
    author = SearchTextNormalizer.normalize(author),
    note = SearchTextNormalizer.normalize(note),
)

internal fun ReadingLogEntity.toFts(rowId: Long = docId): ReadingLogFtsEntity = ReadingLogFtsEntity(
    rowId = rowId,
    entityId = id,
    title = SearchTextNormalizer.normalize(title),
    author = SearchTextNormalizer.normalize(author),
    series = SearchTextNormalizer.normalize(series),
    review = SearchTextNormalizer.normalize(review),
)

internal fun assembleBooksAndShelves(
    bookEntities: List<BookEntity>,
    progressEntities: List<BookProgressEntity>,
    quoteEntities: List<QuoteEntity>,
    bookmarkEntities: List<BookmarkEntity>,
    shelfEntities: List<ShelfEntity>,
): Pair<List<Book>, List<Shelf>> {
    val progressByBook = progressEntities.associateBy { it.bookId }
    val quotesByBook = quoteEntities.groupBy { it.bookId }
    val bookmarksByBook = bookmarkEntities.groupBy { it.bookId }

    val books = bookEntities
        .map { entity ->
            entity.toDomain(
                progress = progressByBook[entity.id]?.toDomain(),
                quotes = quotesByBook[entity.id]
                    ?.sortedWith(compareBy<QuoteEntity> { it.createdAtMillis }.thenBy { it.docId })
                    ?.map { it.toDomain() }
                    ?: emptyList(),
                bookmarks = bookmarksByBook[entity.id]
                    ?.sortedWith(compareBy<BookmarkEntity> { it.flatIndex }.thenBy { it.docId })
                    ?.map { it.toDomain() }
                    ?: emptyList(),
            )
        }
        .sortedByDescending { it.sortTs }

    val shelfMembers = bookEntities
        .filter { it.shelfId != null }
        .sortedWith(compareBy<BookEntity> { it.shelfPosition }.thenBy { it.docId })
        .groupBy({ it.shelfId!! }, { it.id })

    val shelves = shelfEntities
        .map { entity -> entity.toDomain(bookIds = shelfMembers[entity.id] ?: emptyList()) }
        .sortedWith(compareByDescending<Shelf> { it.sortTs }.thenBy { it.id })

    return books to shelves
}
