package com.example.frogreader.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import com.example.frogreader.data.metadata.MetadataFileInfo
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.bookTransferFormat
import com.example.frogreader.data.bookTransferName
import com.example.frogreader.data.metadata.CoverEdit
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.EditableBookMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal enum class MetadataErrorAction { LOAD, SAVE, EXPORT }

internal data class MetadataEditorState(
    val book: Book? = null,
    val draft: EditableBookMetadata? = null,
    val original: EditableBookMetadata? = null,
    val cover: File? = null,
    val pickedCover: String? = null,
    val removeCover: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val exported: Boolean = false,
    val fileInfo: MetadataFileInfo? = null,
    val errorAction: MetadataErrorAction = MetadataErrorAction.LOAD,
    val ratingHalfStars: Int? = null,
    val originalRatingHalfStars: Int? = null,
    val review: String = "",
    val originalReview: String = "",
    val savedGeneration: Int = 0,
) {
    val metadataDirty: Boolean get() = draft != original || pickedCover != null || removeCover
    val reviewDirty: Boolean get() = ratingHalfStars != originalRatingHalfStars || review != originalReview
    val dirty: Boolean get() = metadataDirty || reviewDirty
    val canSave: Boolean get() = !loading && !busy && dirty && draft?.let { runCatching { it.normalized().validate() }.isSuccess } == true
        && review.length <= 20_000 && (ratingHalfStars == null || ratingHalfStars in 2..10)
}

internal class BookMetadataViewModel(
    private val app: FrogReaderApp,
    private val bookId: String,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val repository = app.bookRepository
    private val _state: MutableStateFlow<MetadataEditorState> = run {
        val book = repository.books.value.firstOrNull { it.id == bookId }
        val cover = book?.let(repository::coverFileFor)
        val oldFile: String? = savedState["file"]
        val restoredDraft = if (oldFile == book?.fileName) restore(savedState, "draft") else null
        val restoredOriginal = if (oldFile == book?.fileName) restore(savedState, "original") else null
        val fallbackDraft = book?.let(EditableBookMetadata::from)
        val draft = restoredDraft ?: fallbackDraft
        val original = restoredOriginal ?: fallbackDraft
        MutableStateFlow(
            MetadataEditorState(
                book = book,
                draft = draft,
                original = original,
                cover = cover,
                pickedCover = if (oldFile == book?.fileName) savedState["cover"] else null,
                removeCover = oldFile == book?.fileName && savedState.get<Boolean>("remove") == true,
                loading = true,
                ratingHalfStars = book?.ratingHalfStars ?: book?.rating?.times(2),
                originalRatingHalfStars = book?.ratingHalfStars ?: book?.rating?.times(2),
                review = book?.review.orEmpty(), originalReview = book?.review.orEmpty(),
            )
        )
    }
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, saved = false, exported = false)
            try {
                val (book, initial) = repository.editableMetadata(bookId)
                val oldFile: String? = savedState["file"]
                val restoredDraft = if (oldFile == book.fileName) restore(savedState, "draft") else null
                val restoredOriginal = if (oldFile == book.fileName) restore(savedState, "original") else null
                val restoreMetadata = restoredDraft != restoredOriginal || savedState.get<String>("cover") != null || savedState.get<Boolean>("remove") == true
                val draft = if (restoreMetadata) restoredDraft ?: initial else initial
                val original = if (restoreMetadata) restoredOriginal ?: initial else initial
                val originalRating = book.ratingHalfStars ?: book.rating?.times(2)
                val restoreReview = oldFile == book.fileName && savedState.contains("originalReview") &&
                    (savedState.get<String>("review") != savedState.get<String>("originalReview") ||
                        savedState.get<Int>("ratingHalfStars") != savedState.get<Int>("originalRatingHalfStars"))
                _state.value = MetadataEditorState(
                    fileInfo = repository.metadataFileInfo(book),
                    book = book, draft = draft, original = original, cover = repository.coverFileFor(book), loading = false,
                    pickedCover = if (oldFile == book.fileName) savedState["cover"] else null,
                    removeCover = oldFile == book.fileName && savedState.get<Boolean>("remove") == true,
                    ratingHalfStars = if (restoreReview) savedState["ratingHalfStars"] else originalRating,
                    originalRatingHalfStars = if (restoreReview) savedState["originalRatingHalfStars"] else originalRating,
                    review = if (restoreReview) savedState["review"] ?: book.review.orEmpty() else book.review.orEmpty(),
                    originalReview = if (restoreReview) savedState["originalReview"] ?: book.review.orEmpty() else book.review.orEmpty(),
                    savedGeneration = _state.value.savedGeneration,
                )
                persist()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(loading = false, draft = null, error = e.message ?: "Could not read the book file.", errorAction = MetadataErrorAction.LOAD)
            }
        }
    }

    fun change(edit: EditableBookMetadata) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(draft = edit, saved = false, exported = false, error = null)
        persist()
    }

    fun startEditing() { if (!_state.value.loading && !_state.value.busy) load() }

    fun discardChanges() {
        if (_state.value.busy) return
        _state.value = _state.value.let { it.copy(draft = it.original, pickedCover = null, removeCover = false,
            ratingHalfStars = it.originalRatingHalfStars, review = it.originalReview, error = null) }
        persist()
    }

    fun changeReview(ratingHalfStars: Int?, review: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(ratingHalfStars = ratingHalfStars, review = review.take(20_000), saved = false, error = null)
        persist()
    }

    fun pickCover(uri: Uri) {
        if (_state.value.busy) return
        runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        _state.value = _state.value.copy(pickedCover = uri.toString(), removeCover = false, saved = false, exported = false, error = null)
        persist()
    }

    fun removeCover() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(pickedCover = null, removeCover = _state.value.cover != null, saved = false, exported = false, error = null)
        persist()
    }

    fun resetCover() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(pickedCover = null, removeCover = false, saved = false, exported = false, error = null)
        persist()
    }

    fun save() {
        val before = _state.value
        if (!before.canSave) return
        _state.value = before.copy(busy = true, error = null)
        viewModelScope.launch {
            var working = before.copy(error = null)
            try {
                if (before.metadataDirty) {
                    val cover = when {
                        before.removeCover -> CoverEdit.Remove
                        before.pickedCover != null -> readCover(before.pickedCover.toUri())
                        else -> CoverEdit.Keep
                    }
                    repository.saveBookMetadata(bookId, before.book!!.fileName, before.draft!!, cover)
                    val (book, actual) = repository.editableMetadata(bookId)
                    // If the personal review fails afterward, retry only the remaining changes.
                    working = working.copy(book = book, draft = actual, original = actual,
                        cover = repository.coverFileFor(book), pickedCover = null, removeCover = false)
                }
                if (before.reviewDirty) repository.saveBookReview(bookId, before.ratingHalfStars, before.review)
                _state.value = working.copy(busy = false, saved = true,
                    review = before.review.trim(), originalReview = before.review.trim(),
                    originalRatingHalfStars = before.ratingHalfStars, savedGeneration = before.savedGeneration + 1)
                persist()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = working.copy(busy = false, error = e.message ?: "Could not save the book.", errorAction = MetadataErrorAction.SAVE)
                persist()
            }
        }
    }

    fun export(uri: Uri) {
        if (_state.value.busy || _state.value.dirty) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                repository.exportBook(bookId, uri)
                _state.value = _state.value.copy(busy = false, exported = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(busy = false, error = e.message ?: "Could not export the book.", errorAction = MetadataErrorAction.EXPORT)
            }
        }
    }

    fun exportName(): String {
        val book = _state.value.book ?: return "book.epub"
        return bookTransferName(book.title, bookTransferFormat(book.format, _state.value.fileInfo?.label))
    }

    private fun persist() {
        val s = _state.value
        savedState["file"] = s.book?.fileName
        savedState["draft"] = s.draft?.let { Json.encodeToString(EditableBookMetadata.serializer(), it) }
        savedState["original"] = s.original?.let { Json.encodeToString(EditableBookMetadata.serializer(), it) }
        savedState["cover"] = s.pickedCover
        savedState["remove"] = s.removeCover
        savedState["ratingHalfStars"] = s.ratingHalfStars
        savedState["originalRatingHalfStars"] = s.originalRatingHalfStars
        savedState["review"] = s.review
        savedState["originalReview"] = s.originalReview
    }

    private suspend fun readCover(uri: Uri): CoverEdit.Replace = withContext(Dispatchers.IO) {
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > 32 * 1024 * 1024) throw IOException("Choose an image smaller than 32 MB.")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: throw IOException("Could not open the selected image.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported image, such as JPEG or PNG." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Could not decode the selected image.")
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        try {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                val opaque = createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(oriented, 0f, 0f, null) }
                    val out = ByteArrayOutputStream()
                    if (!opaque.compress(Bitmap.CompressFormat.JPEG, 93, out)) throw IOException("Could not prepare the cover.")
                    CoverEdit.Replace(out.toByteArray())
                } finally { opaque.recycle() }
            } finally { if (oriented !== bitmap) oriented.recycle() }
        } finally { bitmap.recycle() }
    }

    companion object {
        fun factory(bookId: String) = viewModelFactory {
            initializer { BookMetadataViewModel(this[APPLICATION_KEY] as FrogReaderApp, bookId, createSavedStateHandle()) }
        }

        private fun restore(savedState: SavedStateHandle, key: String): EditableBookMetadata? = savedState.get<String>(key)?.let {
            runCatching { Json.decodeFromString(EditableBookMetadata.serializer(), it) }.getOrNull()
        }
    }
}
