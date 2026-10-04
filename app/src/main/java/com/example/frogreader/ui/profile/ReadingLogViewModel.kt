package com.example.frogreader.ui.profile

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
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogEntry
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

internal data class ReadingLogEditor(
    val draft: ReadingLogEntry = ReadingLogEntry("", ""),
    val original: ReadingLogEntry = ReadingLogEntry("", ""),
    val failed: Boolean = false,
) {
    val isNew get() = original.id.isBlank()
    val dirty get() = draft != original
    val valid get() = draft.title.isNotBlank() && draft.title.length <= 200 && draft.author.length <= 160 &&
        draft.series.length <= 200 && draft.review.length <= 20000 &&
        (draft.ratingHalfStars == null || draft.ratingHalfStars in 2..10) &&
        (draft.startedOn?.isValid() != false) && (draft.finishedOn?.isValid() != false) && datesInOrder(draft.startedOn, draft.finishedOn)
}

internal fun datesInOrder(start: ReadingLogDate?, end: ReadingLogDate?): Boolean {
    if (start == null || end == null || !start.isValid() || !end.isValid()) return true
    // Partial dates describe intervals; reject only intervals that cannot overlap in order.
    val lastEnd = if (end.month == null) java.time.LocalDate.of(end.year, 12, 31)
        else java.time.LocalDate.of(end.year, end.month, end.day ?: YearMonth.of(end.year, end.month).lengthOfMonth())
    return !start.toLocalDate().isAfter(lastEnd)
}

internal fun sortedReadingLog(entries: List<ReadingLogEntry>, sort: ReadingLogSort): List<ReadingLogEntry> {
    if (sort == ReadingLogSort.MANUAL) return entries
    val byStart = sort == ReadingLogSort.STARTED_NEWEST || sort == ReadingLogSort.STARTED_OLDEST
    val descending = sort == ReadingLogSort.FINISHED_NEWEST || sort == ReadingLogSort.STARTED_NEWEST
    fun date(entry: ReadingLogEntry): java.time.LocalDate? = if (byStart) entry.startedOn?.takeIf { it.isValid() }?.toLocalDate()
        else if (entry.status == ReadingLogStatus.IN_PROGRESS) entry.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it).toLocalDate() }
            ?: entry.finishedOn?.takeIf { it.isValid() }?.toLocalDate()
        else entry.finishedOn?.takeIf { it.isValid() }?.toLocalDate()
            ?: if (entry.status == ReadingLogStatus.ABANDONED) entry.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it).toLocalDate() } else null
    return entries.sortedWith { a, b ->
        val first = date(a); val second = date(b)
        when {
            first == null && second == null -> 0
            first == null -> 1
            second == null -> -1
            descending -> second.compareTo(first)
            else -> first.compareTo(second)
        }
    }
}

internal sealed interface ReadingLogEvent {
    data object Failed : ReadingLogEvent
    data class Deleted(val entry: ReadingLogEntry, val index: Int) : ReadingLogEvent
}

internal class ReadingLogViewModel(private val repository: BookRepository, private val savedState: SavedStateHandle,
    private val settings: SettingsRepository? = null, private val cameraDirectory: File? = null,
) : ViewModel() {
    private val _entries = MutableStateFlow<List<ReadingLogEntry>>(emptyList())
    val entries = _entries.asStateFlow()
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
    private var pendingSort: String? = null
    private val pendingUndos = ArrayDeque<ReadingLogEvent.Deleted>()

    init {
        viewModelScope.launch {
            val flow = withContext(Dispatchers.IO) { repository.readingLog }
            flow.collect { if (!reordering) _entries.value = it; _ready.value = true }
        }
        settings?.let { preferences -> viewModelScope.launch { preferences.appSettings.collect { value ->
            val knownSort = ReadingLogSort.entries.firstOrNull { it.name == value.readingLogSort }?.name ?: ReadingLogSort.FINISHED_NEWEST.name
            if (pendingSort == knownSort) pendingSort = null
            if (pendingSort == null && !reordering) savedState["readingLogSort"] = knownSort
        } } }
        savedState.get<String>("readingLogPendingCover")?.let { attachCover(Uri.parse(it)) }
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
    fun change(entry: ReadingLogEntry) { if (!_busy.value) _editor.value?.let { setEditor(it.copy(draft = entry, failed = false)) } }
    fun dismissEditor() {
        if (_busy.value) return
        val old = _editor.value ?: return
        setEditor(null)
        old.draft.coverFileName?.takeIf { it != old.original.coverFileName }?.let(::cleanup)
    }
    fun attachCover(uri: Uri?) {
        if (uri == null || _editor.value == null || _busy.value) return
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
        if (_busy.value) return
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
                val draft = if (latest != null) current.draft.copy(readingSeconds = latest.readingSeconds,
                    lastOpenedAtMillis = latest.lastOpenedAtMillis) else current.draft
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
    fun delete(entry: ReadingLogEntry) {
        if (_busy.value || reordering || _editor.value != null) return
        val index = _entries.value.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        mutate { repository.deleteReadingLogEntry(entry.id); channel.send(ReadingLogEvent.Deleted(entry, index)) }
    }
    fun undo(event: ReadingLogEvent.Deleted) { pendingUndos.addLast(event); drainUndos() }
    fun finishDelete(event: ReadingLogEvent.Deleted) { event.entry.coverFileName?.let(::cleanup) }
    private fun drainUndos() {
        if (_busy.value || reordering || pendingUndos.isEmpty()) return
        val event = pendingUndos.removeFirst()
        _busy.value = true
        viewModelScope.launch {
            try { repository.restoreReadingLogEntry(event.entry, event.index) }
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
        if (_busy.value || reordering || _editor.value != null || !_ready.value || sort.value != ReadingLogSort.MANUAL.name) return false
        reordering = true; return true
    }
    fun move(id: String, target: Int) {
        if (!reordering) return
        val from = _entries.value.indexOfFirst { it.id == id }
        if (from >= 0 && target in _entries.value.indices && from != target)
            _entries.value = _entries.value.toMutableList().apply { add(target, removeAt(from)) }
    }
    fun finishReorder(cancel: Boolean = false) {
        if (!reordering) return
        if (cancel || _entries.value == repository.readingLog.value) {
            _entries.value = repository.readingLog.value; reordering = false; drainUndos(); return
        }
        val ids = _entries.value.map { it.id }
        _busy.value = true
        viewModelScope.launch {
            try { repository.reorderReadingLog(ids) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { channel.send(ReadingLogEvent.Failed) }
            finally { _entries.value = repository.readingLog.value; reordering = false; _busy.value = false; drainUndos() }
        }
    }
    fun moveAccessibly(id: String, target: Int): Boolean {
        if (!beginReorder()) return false
        move(id, target); finishReorder(); return true
    }
    private fun restoreEditor(): ReadingLogEditor? {
        val draft = savedState.get<String>("readingLogDraft") ?: return null
        return runCatching { ReadingLogEditor(Json.decodeFromString<ReadingLogEntry>(draft),
            Json.decodeFromString<ReadingLogEntry>(savedState.get<String>("readingLogOriginal") ?: draft)) }.getOrNull()
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
