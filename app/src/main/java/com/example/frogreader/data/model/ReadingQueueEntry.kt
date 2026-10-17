package com.example.frogreader.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A reading intention, independent of downloaded books. List position is its priority. */
@Serializable
@SerialName("com.example.frogreader.data.model.ReadingListEntry")
data class ReadingQueueEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val note: String = "",
    val createdAtMillis: Long = 0L,
    /** Attached file and metadata, either private to the queue or shared with a library book. */
    val attachment: Book? = null,
    override val updatedAtMillis: Long = 0L,
) : Timestamped<ReadingQueueEntry> {
    override fun withUpdatedAt(millis: Long) = copy(updatedAtMillis = millis)

    companion object {
        const val TITLE_LIMIT = 200
        const val AUTHOR_LIMIT = 160
        const val NOTE_LIMIT = 100

        /** Existing notes remain editable as metadata without silently losing their original text. */
        fun acceptsNote(note: String, previousNote: String? = null): Boolean =
            note.trim().length <= NOTE_LIMIT || (previousNote != null && note == previousNote)
    }
}
