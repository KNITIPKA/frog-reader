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
import com.example.frogreader.R
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.ReadingQueueEntry
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

internal data class ReadingQueueEditor(
    val id: String? = null,
    val title: String = "",
    val author: String = "",
    val note: String = "",
    val attachment: Book? = null,
    val originalTitle: String = "",
    val originalAuthor: String = "",
    val originalNote: String = "",
    val originalAttachment: Book? = null,
    val failed: Boolean = false,
) {
    val dirty get() = title != originalTitle || author != originalAuthor || note != originalNote || attachment != originalAttachment
    val noteValid get() = ReadingQueueEntry.acceptsNote(note, originalNote.takeIf { id != null })
    val valid get() = title.isNotBlank() && title.length <= ReadingQueueEntry.TITLE_LIMIT &&
        author.length <= ReadingQueueEntry.AUTHOR_LIMIT && noteValid
}

internal sealed interface ReadingQueueEvent {
    data class Failed(val message: Int = R.string.reading_queue_save_failed) : ReadingQueueEvent
    data class Deleted(val entry: ReadingQueueEntry, val index: Int) : ReadingQueueEvent
    data class OpenBook(val id: String) : ReadingQueueEvent
}

internal class ReadingQueueViewModel(
    private val repository: BookRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _entries = MutableStateFlow<List<ReadingQueueEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _editor = MutableStateFlow(restoreEditor())
    val editor = _editor.asStateFlow()
    // Keep legacy saved-state keys so an existing draft survives the feature rename.
    val addedEntryId = savedState.getStateFlow<String?>("readingListScrollTarget", null)
    private val eventsChannel = Channel<ReadingQueueEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private var reordering = false
    private val pendingUndos = ArrayDeque<ReadingQueueEvent.Deleted>()

    init {
        viewModelScope.launch {
            val flow = withContext(Dispatchers.IO) { repository.readingQueue }
            flow.collect {
                if (!reordering) _entries.value = it
                _ready.value = true
            }
        }
    }

    fun coverFor(book: Book) = repository.coverFileFor(book)

    fun edit(entry: ReadingQueueEntry? = null) {
        if (_busy.value || reordering || _editor.value != null) return
        setEditor(ReadingQueueEditor(id = entry?.id, title = entry?.title.orEmpty(),
            author = entry?.author.orEmpty(), note = entry?.note.orEmpty(), attachment = entry?.attachment,
            originalTitle = entry?.title.orEmpty(), originalAuthor = entry?.author.orEmpty(),
            originalNote = entry?.note.orEmpty(), originalAttachment = entry?.attachment))
    }

    fun change(title: String? = null, author: String? = null, note: String? = null) {
        if (_busy.value) return
        _editor.value?.let { setEditor(it.copy(title = title ?: it.title, author = author ?: it.author,
            note = note ?: it.note, failed = false)) }
    }

    fun dismissEditor() {
        if (_busy.value) return
        val current = _editor.value
        setEditor(null)
        current?.attachment?.takeIf { it != current.originalAttachment }?.let { cleanup(it) }
    }

    fun selectFileTarget(entryId: String?) { savedState["readingListFileTarget"] = entryId ?: DRAFT }

    fun attachFile(uri: Uri?) {
        val target = savedState.remove<String>("readingListFileTarget") ?: return
        if (uri == null || _busy.value || reordering) return
        _busy.value = true
        viewModelScope.launch {
            var prepared: Book? = null
            try {
                prepared = repository.prepareReadingQueueAttachment(uri)
                if (target == DRAFT) {
                    val draft = _editor.value ?: error("The draft was closed.")
                    setEditor(draft.copy(attachment = prepared,
                        title = draft.title.ifBlank { prepared.title.take(ReadingQueueEntry.TITLE_LIMIT) },
                        author = draft.author.ifBlank { prepared.author.orEmpty().take(ReadingQueueEntry.AUTHOR_LIMIT) },
                        failed = false))
                    draft.attachment?.takeIf { it != draft.originalAttachment }?.let { cleanup(it) }
                } else {
                    repository.attachReadingQueueFile(target, prepared)
                }
                prepared = null // Owned by the saved draft or the durable entry now.
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { eventsChannel.send(ReadingQueueEvent.Failed(R.string.reading_queue_file_failed)) }
            finally {
                prepared?.let { withContext(NonCancellable) { repository.discardReadingQueueAttachment(it) } }
                _busy.value = false
                drainUndos()
            }
        }
    }

    fun removeDraftFile() {
        if (_busy.value) return
        val current = _editor.value ?: return
        // An existing saved attachment is replaced through Add a file; this action only cancels a draft file.
        val attachment = current.attachment?.takeIf { it != current.originalAttachment } ?: return
        setEditor(current.copy(attachment = current.originalAttachment))
        cleanup(attachment)
    }

    fun save() {
        val current = _editor.value?.takeIf { it.valid } ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val id = repository.saveReadingQueueEntry(current.id, current.title, current.author, current.note, current.attachment)
                setEditor(null)
                if (current.id == null) savedState["readingListScrollTarget"] = id
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _editor.value?.let { setEditor(it.copy(failed = true)) } }
            finally { _busy.value = false; drainUndos() }
        }
    }

    fun acknowledgeAddedEntry(id: String) {
        if (addedEntryId.value == id) savedState["readingListScrollTarget"] = null
    }

    fun delete(entry: ReadingQueueEntry) {
        if (_busy.value || reordering || _editor.value != null) return
        val index = _entries.value.indexOfFirst { it.id == entry.id }
        if (index < 0) return
        mutate {
            repository.deleteReadingQueueEntry(entry.id)
            eventsChannel.send(ReadingQueueEvent.Deleted(entry, index))
        }
    }

    // Snackbar actions stay available while a draft or another save is open.
    // Queue them instead of silently dropping Undo behind the normal mutation guards.
    fun undo(event: ReadingQueueEvent.Deleted) {
        pendingUndos.addLast(event)
        drainUndos()
    }

    private fun drainUndos() {
        if (_busy.value || reordering || pendingUndos.isEmpty()) return
        val event = pendingUndos.removeFirst()
        _busy.value = true
        viewModelScope.launch {
            try { repository.restoreReadingQueueEntry(event.entry, event.index) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { eventsChannel.send(ReadingQueueEvent.Failed()) }
            finally { _busy.value = false; drainUndos() }
        }
    }
    fun finishDelete(event: ReadingQueueEvent.Deleted) { event.entry.attachment?.let { cleanup(it) } }

    fun startReading(entry: ReadingQueueEntry) = mutate {
        val book = repository.startReadingQueueEntry(entry.id)
        eventsChannel.send(ReadingQueueEvent.OpenBook(book.id))
    }

    private fun cleanup(book: Book) { viewModelScope.launch { repository.discardReadingQueueAttachment(book) } }

    private fun mutate(action: suspend () -> Unit) {
        if (_busy.value || reordering || _editor.value != null) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { eventsChannel.send(ReadingQueueEvent.Failed()) }
            finally { _busy.value = false; drainUndos() }
        }
    }

    fun beginReorder(): Boolean {
        if (_busy.value || reordering || _editor.value != null || !_ready.value) return false
        reordering = true
        return true
    }

    fun move(id: String, target: Int) {
        if (reordering) _entries.value = moveReadingQueueEntry(_entries.value, id, target)
    }

    fun finishReorder(cancel: Boolean = false) {
        if (!reordering) return
        if (cancel || _entries.value == repository.readingQueue.value) {
            _entries.value = repository.readingQueue.value
            reordering = false
            drainUndos()
            return
        }
        val ids = _entries.value.map { it.id }
        _busy.value = true
        viewModelScope.launch {
            try { repository.reorderReadingQueue(ids) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { eventsChannel.send(ReadingQueueEvent.Failed()) }
            finally {
                _entries.value = repository.readingQueue.value
                reordering = false
                _busy.value = false
                drainUndos()
            }
        }
    }

    fun moveAccessibly(id: String, target: Int): Boolean {
        if (!beginReorder()) return false
        move(id, target)
        finishReorder()
        return true
    }

    private fun restoreEditor(): ReadingQueueEditor? {
        if (savedState.get<Boolean>("readingListEditing") != true) return null
        fun book(key: String): Book? = savedState.get<String>(key)?.let {
            runCatching { Json.decodeFromString<Book>(it) }.getOrNull()
        }
        return ReadingQueueEditor(id = savedState["readingListId"], title = savedState["readingListTitle"] ?: "",
            author = savedState["readingListAuthor"] ?: "", note = savedState["readingListNote"] ?: "",
            attachment = book("readingListAttachment"), originalAttachment = book("readingListOriginalAttachment"),
            originalTitle = savedState["readingListOriginalTitle"] ?: "",
            originalAuthor = savedState["readingListOriginalAuthor"] ?: "",
            originalNote = savedState["readingListOriginalNote"] ?: "")
    }

    private fun setEditor(value: ReadingQueueEditor?) {
        savedState["readingListEditing"] = value != null
        savedState["readingListId"] = value?.id
        savedState["readingListTitle"] = value?.title
        savedState["readingListAuthor"] = value?.author
        savedState["readingListNote"] = value?.note
        savedState["readingListOriginalTitle"] = value?.originalTitle
        savedState["readingListOriginalAuthor"] = value?.originalAuthor
        savedState["readingListOriginalNote"] = value?.originalNote
        if (value?.attachment != _editor.value?.attachment) {
            savedState["readingListAttachment"] = value?.attachment?.let { Json.encodeToString(Book.serializer(), it) }
        }
        if (value?.originalAttachment != _editor.value?.originalAttachment) {
            savedState["readingListOriginalAttachment"] = value?.originalAttachment?.let { Json.encodeToString(Book.serializer(), it) }
        }
        _editor.value = value
    }

    companion object {
        private const val DRAFT = "draft"
        val Factory = viewModelFactory {
            initializer {
                ReadingQueueViewModel((this[APPLICATION_KEY] as FrogReaderApp).bookRepository, createSavedStateHandle())
            }
        }
    }
}

internal fun moveReadingQueueEntry(entries: List<ReadingQueueEntry>, id: String, target: Int): List<ReadingQueueEntry> {
    val from = entries.indexOfFirst { it.id == id }
    if (from < 0 || target !in entries.indices || from == target) return entries
    return entries.toMutableList().apply { add(target, removeAt(from)) }
}
