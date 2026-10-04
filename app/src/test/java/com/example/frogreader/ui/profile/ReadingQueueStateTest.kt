package com.example.frogreader.ui.profile

import androidx.lifecycle.SavedStateHandle
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import com.example.frogreader.data.model.ReadingQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingQueueStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `editor draft survives recreation and retains original values for discard`() {
        val handle = SavedStateHandle()
        val vm = ReadingQueueViewModel(BookRepository(), handle)
        vm.edit(ReadingQueueEntry("id", "Original", "Author", "Note"))
        vm.change(title = "New title", note = "Unfinished 🐸")
        val restored = ReadingQueueViewModel(BookRepository(), SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("id", restored.editor.value!!.id)
        assertEquals("New title", restored.editor.value!!.title)
        assertEquals("Author", restored.editor.value!!.author)
        assertEquals("Unfinished 🐸", restored.editor.value!!.note)
        assertEquals("Original", restored.editor.value!!.originalTitle)
        assertTrue(restored.editor.value!!.dirty)
        restored.dismissEditor()
        assertNull(restored.editor.value)
    }

    @Test fun `drag first to last and back preserves identity and notes`() {
        val entries = (1..20).map { ReadingQueueEntry("$it", "Book $it", note = "Note $it") }
        val moved = moveReadingQueueEntry(entries, "1", 19)
        assertEquals(entries.first(), moved.last())
        assertEquals(entries.drop(1), moved.dropLast(1))
        assertEquals(entries, moveReadingQueueEntry(moved, "1", 0))
        assertSame(entries, moveReadingQueueEntry(entries, "missing", 2))
        assertSame(entries, moveReadingQueueEntry(entries, "1", -1))
    }
    @Test fun `undo works while draft is open and preserves unsaved text`() = runTest(dispatcher) {
        val directory = File("build/tmp/test_files")
        directory.mkdirs()
        StoreFixture.clear(directory)
        try {
            val repository = BookRepository()
            repository.saveReadingQueueEntry(null, "Deleted book", "Author", "Keep my note")
            val deleted = repository.readingQueue.value.single()
            repository.deleteReadingQueueEntry(deleted.id)
            val vm = ReadingQueueViewModel(repository, SavedStateHandle())
            vm.edit()
            vm.change(title = "Unfinished new book", note = "Draft text")
            vm.undo(ReadingQueueEvent.Deleted(deleted, 0))
            vm.busy.first { !it }
            assertEquals(deleted.id, repository.readingQueue.value.single().id)
            assertEquals("Unfinished new book", vm.editor.value!!.title)
            assertEquals("Draft text", vm.editor.value!!.note)
        } finally { StoreFixture.clear(directory) }
    }

    @Test fun `attached file metadata survives draft recreation`() {
        val handle = SavedStateHandle()
        val file = Book("attachment", "File title", "Author", BookFormat.EPUB, "private.epub", addedAtMillis = 1)
        val vm = ReadingQueueViewModel(BookRepository(), handle)
        vm.edit(ReadingQueueEntry("entry", "Chosen title", "Author", "Note", attachment = file))
        vm.change(note = "Edited draft")
        val restored = ReadingQueueViewModel(BookRepository(), SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(file, restored.editor.value!!.attachment)
        assertEquals(file, restored.editor.value!!.originalAttachment)
        assertEquals("Edited draft", restored.editor.value!!.note)
        assertTrue(restored.editor.value!!.dirty)
    }

    @Test fun `successful addition scroll target survives recreation and edits never trigger another scroll`() = runTest(dispatcher) {
        val directory = File("build/tmp/test_files")
        directory.mkdirs()
        StoreFixture.clear(directory)
        try {
            val repository = BookRepository()
            repository.saveReadingQueueEntry(null, "Existing", "", "")
            val handle = SavedStateHandle()
            val vm = ReadingQueueViewModel(repository, handle)
            vm.ready.first { it }
            vm.edit()
            vm.change(title = "New book", note = "Read next month")
            vm.save()
            vm.busy.first { !it }
            val added = repository.readingQueue.value.last()
            assertEquals("New book", added.title)
            assertEquals(added.id, vm.addedEntryId.value)
            assertNull(vm.editor.value)
            val restored = ReadingQueueViewModel(repository, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
            assertEquals(added.id, restored.addedEntryId.value)
            vm.acknowledgeAddedEntry("wrong")
            assertEquals(added.id, vm.addedEntryId.value)
            vm.acknowledgeAddedEntry(added.id)
            assertNull(vm.addedEntryId.value)
            vm.edit(added)
            vm.change(title = "Updated title")
            vm.save()
            vm.busy.first { !it }
            assertNull(vm.addedEntryId.value)
        } finally { StoreFixture.clear(directory) }
    }

    @Test fun `failed addition keeps draft and does not request a scroll`() = runTest(dispatcher) {
        val directory = File("build/tmp/test_files")
        directory.mkdirs()
        StoreFixture.clear(directory)
        val temp = File(directory, "userdata.json.tmp")
        try {
            val vm = ReadingQueueViewModel(BookRepository(), SavedStateHandle())
            vm.ready.first { it }
            vm.edit()
            vm.change(title = "Keep this draft", note = "Unsaved note")
            temp.mkdirs()
            File(temp, "block").writeText("prevent replacement")
            vm.save()
            vm.busy.first { !it }
            assertTrue(vm.editor.value!!.failed)
            assertEquals("Unsaved note", vm.editor.value!!.note)
            assertNull(vm.addedEntryId.value)
        } finally { temp.deleteRecursively(); StoreFixture.clear(directory) }
    }

    @Test fun `old note is valid unchanged and shorter new note is valid`() {
        val editor = ReadingQueueEditor(id = "legacy", title = "Book", note = "a".repeat(500), originalNote = "a".repeat(500))
        assertTrue(editor.copy(title = "Renamed").valid)
        assertFalse(editor.copy(note = "a".repeat(499)).valid)
        assertTrue(editor.copy(note = "a".repeat(100)).valid)
        assertFalse(ReadingQueueEditor(title = "New book", note = "a".repeat(101)).valid)
    }

}
