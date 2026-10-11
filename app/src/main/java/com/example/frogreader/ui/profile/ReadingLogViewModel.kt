package com.example.frogreader.ui.profile

import androidx.core.net.toUri
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.ReadingLogDeletion
import com.example.frogreader.data.compareReadingLogDatePeers
import com.example.frogreader.data.orderingDate
import com.example.frogreader.data.withReadingLogDateOrder
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.YearMonth
import java.io.File

internal enum class ReadingLogSort { FINISHED_NEWEST, FINISHED_OLDEST, STARTED_NEWEST, STARTED_OLDEST, MANUAL }

private val ReadingLogSort.byStart get() = this == ReadingLogSort.STARTED_NEWEST || this == ReadingLogSort.STARTED_OLDEST
private val ReadingLogSort.descending get() = this == ReadingLogSort.FINISHED_NEWEST || this == ReadingLogSort.STARTED_NEWEST

internal fun readingLogReorderGroup(entry: ReadingLogEntry, sort: ReadingLogSort): ReadingLogDate? =
    if (sort == ReadingLogSort.MANUAL) null else entry.orderingDate(sort.byStart)?.takeIf { it.day == null }

internal fun readingLogCanReorder(entries: List<ReadingLogEntry>, sort: ReadingLogSort, id: String): Boolean {
    if (sort == ReadingLogSort.MANUAL) return entries.size > 1 && entries.any { it.id == id }
    val group = entries.firstOrNull { it.id == id }?.let { readingLogReorderGroup(it, sort) } ?: return false
    return entries.count { readingLogReorderGroup(it, sort) == group } > 1
}

/** Indices refer to the currently displayed date sort, never the stored manual list. */
internal fun readingLogCanMove(entries: List<ReadingLogEntry>, sort: ReadingLogSort, id: String, target: Int): Boolean {
    if (target !in entries.indices || entries[target].id == id || !readingLogCanReorder(entries, sort, id)) return false
    if (sort == ReadingLogSort.MANUAL) return true
    val entry = entries.first { it.id == id }
    return readingLogReorderGroup(entry, sort) == readingLogReorderGroup(entries[target], sort)
}

internal data class ReadingLogEditor(
    val draft: ReadingLogEntry = ReadingLogEntry("", ""),
    val original: ReadingLogEntry = ReadingLogEntry("", ""),
    val failed: Boolean = false,
) {
    val isNew get() = original.id.isBlank()
    val canEditIdentity get() = original.source != ReadingLogSource.FROGREADER
    val dirty get() = draft != original
    val valid get() = draft.title.isNotBlank() && (!canEditIdentity ||
        draft.title.length <= 200 && draft.author.length <= 160 && draft.series.length <= 200) && draft.review.length <= 20000 &&
        (draft.ratingHalfStars == null || draft.ratingHalfStars in 2..10) &&
        (draft.startedOn?.isValid() != false) && (draft.finishedOn?.isValid() != false) && datesInOrder(draft.startedOn, draft.finishedOn)

    fun withDraft(entry: ReadingLogEntry): ReadingLogEditor = copy(draft = if (canEditIdentity) entry else entry.copy(
        title = original.title, author = original.author, series = original.series, coverFileName = original.coverFileName,
        source = original.source, linkedBookId = original.linkedBookId, bookFormat = original.bookFormat,
    ), failed = false)
}

/** Choosing an ending for an ongoing reading completes it; abandoned readings retain their status. */
internal fun ReadingLogEntry.withEditedEndDate(date: ReadingLogDate?): ReadingLogEntry = copy(
    finishedOn = date,
    status = if (date != null && status == ReadingLogStatus.IN_PROGRESS) ReadingLogStatus.FINISHED else status,
    finishedAtMillis = finishedAtMillis.takeIf { date == finishedOn },
)

internal fun ReadingLogEntry.withEditedStatus(status: ReadingLogStatus): ReadingLogEntry = copy(
    status = status,
    finishedOn = if (status == ReadingLogStatus.IN_PROGRESS) null else finishedOn,
    finishedAtMillis = if (status == ReadingLogStatus.FINISHED) finishedAtMillis else null,
)

internal fun datesInOrder(start: ReadingLogDate?, end: ReadingLogDate?): Boolean {
    if (start == null || end == null || !start.isValid() || !end.isValid()) return true
    // Partial dates describe intervals; reject only intervals that cannot overlap in order.
    val lastEnd = if (end.month == null) java.time.LocalDate.of(end.year, 12, 31)
        else java.time.LocalDate.of(end.year, end.month, end.day ?: YearMonth.of(end.year, end.month).lengthOfMonth())
    return !start.toLocalDate().isAfter(lastEnd)
}

internal fun sortedReadingLog(entries: List<ReadingLogEntry>, sort: ReadingLogSort): List<ReadingLogEntry> {
    if (sort == ReadingLogSort.MANUAL) return entries
    return entries.sortedWith { a, b ->
        val first = a.orderingDate(sort.byStart); val second = b.orderingDate(sort.byStart)
        when {
            first == null && second == null -> 0
            first == null -> 1
            second == null -> -1
            else -> {
                val days = first.toLocalDate().compareTo(second.toLocalDate())
                // A year, January and January 1 have different precision. Keep each
                // partial-date group together instead of interleaving it by creation time.
                val precision = (if (first.day != null) 2 else if (first.month != null) 1 else 0)
                    .compareTo(if (second.day != null) 2 else if (second.month != null) 1 else 0)
                // Dates stay primary, including partial/unknown dates. Same-day
                // readings use the event time, never the time a review was edited.
                val chronological = if (days != 0) days else if (precision != 0) precision
                    else compareReadingLogDatePeers(a, b, sort.byStart)
                if (sort.descending) -chronological else chronological
            }
        }
    }
}

internal sealed interface ReadingLogEvent {
    data object Failed : ReadingLogEvent
    data class Deleted(val entry: ReadingLogEntry, val index: Int, val previousReading: Book? = null) : ReadingLogEvent
}

internal class ReadingLogViewModel(private val repository: BookRepository, private val savedState: SavedStateHandle,
    private val settings: SettingsRepository? = null, private val cameraDirectory: File? = null,
) : ViewModel() {
    private val _entries = MutableStateFlow<List<ReadingLogEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _activeProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeProgress = _activeProgress.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _editor = MutableStateFlow(restoreEditor())
    val editor = _editor.asStateFlow()
    val sort = savedState.getStateFlow("readingLogSort", ReadingLogSort.FINISHED_NEWEST.name)
    val addedEntryId = savedState.getStateFlow<String?>("readingLogScrollTarget", null)
    private val channel = Channel<ReadingLogEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var reordering = false
    private var reorderSort = ReadingLogSort.MANUAL
    private var reorderDate: ReadingLogDate? = null
    private var pendingSort: String? = null
    private val pendingUndos = ArrayDeque<ReadingLogEvent.Deleted>()

    init {
        viewModelScope.launch {
            val flow = withContext(Dispatchers.IO) { repository.readingLog }
            flow.collect { if (!reordering) _entries.value = it; _ready.value = true }
        }
        viewModelScope.launch {
            val flow = withContext(Dispatchers.IO) { repository.books }
            flow.collect { _activeProgress.value = readingLogActiveProgress(it) }
        }
        settings?.let { preferences -> viewModelScope.launch { preferences.appSettings.collect { value ->
            val knownSort = ReadingLogSort.entries.firstOrNull { it.name == value.readingLogSort }?.name ?: ReadingLogSort.FINISHED_NEWEST.name
            if (pendingSort == knownSort) pendingSort = null
            if (pendingSort == null && !reordering) savedState["readingLogSort"] = knownSort
        } } }
        savedState.get<String>("readingLogPendingCover")?.let { attachCover(it.toUri()) }
    }

    fun coverFor(entry: ReadingLogEntry) = repository.coverFileFor(entry)
    fun setSort(value: ReadingLogSort) {
        if (_busy.value || reordering) return
        savedState["readingLogSort"] = value.name
        val preferences = settings ?: return
        pendingSort = value.name
        viewModelScope.launch {
            try { preferences.updateApp { it.copy(readingLogSort = value.name) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (pendingSort == value.name) pendingSort = null; channel.send(ReadingLogEvent.Failed) }
        }
    }
    fun edit(entry: ReadingLogEntry? = null) {
        if (_busy.value || reordering || _editor.value != null) return
        val original = entry ?: ReadingLogEntry("", "")
        setEditor(ReadingLogEditor(original, original))
    }
    fun change(entry: ReadingLogEntry) {
        if (!_busy.value) _editor.value?.let { editor ->
            setEditor(editor.withDraft(entry))
        }
    }
    fun dismissEditor() {
        if (_busy.value) return
        val old = _editor.value ?: return
        setEditor(null)
        old.draft.coverFileName?.takeIf { it != old.original.coverFileName }?.let(::cleanup)
    }
    fun attachCover(uri: Uri?) {
        if (uri == null || _editor.value?.canEditIdentity != true || _busy.value) return
        savedState["readingLogPendingCover"] = uri.toString()
        _busy.value = true
        viewModelScope.launch {
            var prepared: String? = null
            try {
                prepared = repository.prepareReadingLogCover(uri)
                val old = _editor.value ?: error("Draft closed")
                setEditor(old.copy(draft = old.draft.copy(coverFileName = prepared), failed = false))
                old.draft.coverFileName?.takeIf { it != old.original.coverFileName }?.let(::cleanup)
                prepared = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { channel.send(ReadingLogEvent.Failed) }
            finally {
                prepared?.let { withContext(NonCancellable) { repository.discardReadingLogCover(it) } }
                withContext(NonCancellable + Dispatchers.IO) {
                    if (uri.scheme == "file" && cameraDirectory != null) runCatching {
                        val photo = uri.path?.let(::File)?.canonicalFile
                        if (photo?.parentFile == cameraDirectory.canonicalFile && photo.name.startsWith("cover-") && photo.name.endsWith(".jpg")) photo.delete()
                    }
                }
                savedState["readingLogPendingCover"] = null
                _busy.value = false; drainUndos()
            }
        }
    }
    fun removeCover() {
        val old = _editor.value ?: return
        if (_busy.value || !old.canEditIdentity) return
        setEditor(old.copy(draft = old.draft.copy(coverFileName = null)))
        old.draft.coverFileName?.takeIf { it != old.original.coverFileName }?.let(::cleanup)
    }
    fun save() {
        val current = _editor.value?.takeIf { it.valid } ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val latest = repository.readingLog.value.firstOrNull { it.id == current.draft.id }
                val withIdentity = if (current.canEditIdentity) current.draft else (latest ?: current.original).let { identity ->
                    current.draft.copy(title = identity.title, author = identity.author, series = identity.series,
                        coverFileName = identity.coverFileName, source = identity.source, linkedBookId = identity.linkedBookId,
                        bookFormat = identity.bookFormat)
                }
                val draft = if (latest != null) withIdentity.copy(readingSeconds = latest.readingSeconds,
                    lastOpenedAtMillis = latest.lastOpenedAtMillis) else withIdentity
                val id = repository.saveReadingLogEntry(draft)
                setEditor(null)
                if (current.isNew) savedState["readingLogScrollTarget"] = id
                current.original.coverFileName?.takeIf { it != current.draft.coverFileName }?.let(::cleanup)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _editor.value?.let { setEditor(it.copy(failed = true)) } }
            finally { _busy.value = false; drainUndos() }
        }
    }
    fun acknowledgeAddedEntry(id: String) { if (addedEntryId.value == id) savedState["readingLogScrollTarget"] = null }
    /** A details screen already removed it durably; expose the same Undo as a list removal. */
    suspend fun announceDeletedReading(deletion: ReadingLogDeletion) {
        channel.send(ReadingLogEvent.Deleted(deletion.entry, deletion.index, deletion.previousReading))
    }
    fun delete(entry: ReadingLogEntry) {
        if (_busy.value || reordering || _editor.value != null) return
        val index = _entries.value.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        mutate {
            val deleted = repository.deleteReadingLogEntry(entry.id) ?: return@mutate
            channel.send(ReadingLogEvent.Deleted(deleted.entry, deleted.index, deleted.previousReading))
        }
    }
    fun undo(event: ReadingLogEvent.Deleted) { pendingUndos.addLast(event); drainUndos() }
    fun finishDelete(event: ReadingLogEvent.Deleted) { event.entry.coverFileName?.let(::cleanup) }
    private fun drainUndos() {
        if (_busy.value || reordering || pendingUndos.isEmpty()) return
        val event = pendingUndos.removeFirst()
        _busy.value = true
        viewModelScope.launch {
            try { repository.restoreReadingLogEntry(event.entry, event.index, event.previousReading) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { channel.send(ReadingLogEvent.Failed) }
            finally { _busy.value = false; drainUndos() }
        }
    }
    private fun cleanup(name: String) { viewModelScope.launch { repository.discardReadingLogCover(name) } }
    private fun mutate(block: suspend () -> Unit) {
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { channel.send(ReadingLogEvent.Failed) }
            finally { _busy.value = false; drainUndos() }
        }
    }
    fun beginReorder(): Boolean {
        if (sort.value != ReadingLogSort.MANUAL.name) return false
        return _entries.value.firstOrNull()?.let { beginReorder(it.id) } ?: false
    }
    fun beginReorder(id: String): Boolean {
        if (_busy.value || reordering || _editor.value != null || !_ready.value) return false
        val selectedSort = ReadingLogSort.entries.firstOrNull { it.name == sort.value } ?: return false
        if (!readingLogCanReorder(_entries.value, selectedSort, id)) return false
        reorderSort = selectedSort
        reorderDate = _entries.value.firstOrNull { it.id == id }?.let { readingLogReorderGroup(it, selectedSort) }
        reordering = true; return true
    }
    fun move(id: String, target: Int) {
        if (!reordering) return
        val displayed = sortedReadingLog(_entries.value, reorderSort)
        if (!readingLogCanMove(displayed, reorderSort, id, target)) return
        if (reorderSort == ReadingLogSort.MANUAL) {
            val from = _entries.value.indexOfFirst { it.id == id }
            _entries.value = _entries.value.toMutableList().apply { add(target, removeAt(from)) }
        } else {
            val group = reorderDate ?: return
            if (readingLogReorderGroup(displayed.first { it.id == id }, reorderSort) != group) return
            val from = displayed.indexOfFirst { it.id == id }
            val moved = displayed.toMutableList().apply { add(target, removeAt(from)) }
                .filter { readingLogReorderGroup(it, reorderSort) == group }.map { it.id }
            val chronologicalIds = if (reorderSort.descending) moved.reversed() else moved
            _entries.value = withReadingLogDateOrder(_entries.value, chronologicalIds, reorderSort.byStart, group)
        }
    }
    fun finishReorder(cancel: Boolean = false) {
        if (!reordering) return
        if (cancel || _entries.value == repository.readingLog.value) {
            _entries.value = repository.readingLog.value; reordering = false; drainUndos(); return
        }
        val date = reorderDate
        val selectedSort = reorderSort
        val ids = if (selectedSort == ReadingLogSort.MANUAL) _entries.value.map { it.id } else {
            val displayed = sortedReadingLog(_entries.value, selectedSort)
                .filter { readingLogReorderGroup(it, selectedSort) == date }.map { it.id }
            if (selectedSort.descending) displayed.reversed() else displayed
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                if (selectedSort == ReadingLogSort.MANUAL) repository.reorderReadingLog(ids)
                else if (date != null) repository.reorderReadingLogDateGroup(ids, selectedSort.byStart, date)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { channel.send(ReadingLogEvent.Failed) }
            finally { _entries.value = repository.readingLog.value; reordering = false; _busy.value = false; drainUndos() }
        }
    }
    fun moveAccessibly(id: String, target: Int): Boolean {
        val selectedSort = ReadingLogSort.entries.firstOrNull { it.name == sort.value } ?: return false
        if (!readingLogCanMove(sortedReadingLog(_entries.value, selectedSort), selectedSort, id, target) || !beginReorder(id)) return false
        move(id, target); finishReorder(); return true
    }
    private fun restoreEditor(): ReadingLogEditor? {
        val draft = savedState.get<String>("readingLogDraft") ?: return null
        return runCatching {
            val restored = ReadingLogEditor(Json.decodeFromString<ReadingLogEntry>(draft),
                Json.decodeFromString<ReadingLogEntry>(savedState.get<String>("readingLogOriginal") ?: draft))
            restored.withDraft(restored.draft)
        }.getOrNull()
    }
    private fun setEditor(editor: ReadingLogEditor?) {
        savedState["readingLogDraft"] = editor?.draft?.let { Json.encodeToString(ReadingLogEntry.serializer(), it) }
        savedState["readingLogOriginal"] = editor?.original?.let { Json.encodeToString(ReadingLogEntry.serializer(), it) }
        _editor.value = editor
    }
    companion object {
        val Factory = viewModelFactory { initializer {
            val app = this[APPLICATION_KEY] as FrogReaderApp
            ReadingLogViewModel(app.bookRepository, createSavedStateHandle(), app.settingsRepository, File(app.cacheDir, "reading-log-camera"))
        } }
    }
}
