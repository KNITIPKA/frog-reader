package com.example.frogreader.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One backup file, wherever it happens to live. */
data class BackupRef(
    /** Opaque to callers; meaningful only to the target that produced it. */
    val id: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val modifiedAtMillis: Long = 0L,
)

/**
 * Where backups are kept.
 *
 * Exists so that [BackupRepository] never learns what a Uri is. Today there is
 * one implementation, a document the user picked. Later there can be a folder
 * watched by a scheduled job, or a Google Drive app-data folder reached over
 * the network — and neither requires touching the backup format, the restore
 * flow, or any of the screens.
 *
 * It also makes the round trip testable without Android: a target backed by a
 * ByteArrayOutputStream is a complete implementation.
 */
interface BackupTarget {

    /** Writes a new backup and returns a handle to it. */
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): BackupRef

    /** Backups already here, newest first. */
    suspend fun list(): List<BackupRef>

    suspend fun open(ref: BackupRef): InputStream

    suspend fun delete(ref: BackupRef)
}

/** How many backups a folder keeps before the oldest is dropped. */
const val DEFAULT_BACKUPS_KEPT = 5

/**
 * Keeps the [keep] newest backups here and deletes the rest.
 *
 * Without this a daily schedule fills the folder forever. With it, the folder
 * holds a short history rather than a single file — which matters more than it
 * sounds: the failure one rotating backup cannot survive is not a crash, it is
 * noticing a week later that something had been going wrong all along.
 *
 * Failures are swallowed on purpose. Tidying up is not worth failing a backup
 * that has already been written successfully.
 */
suspend fun BackupTarget.rotate(keep: Int = DEFAULT_BACKUPS_KEPT) {
    runCatching { list() }
        .getOrDefault(emptyList())
        .drop(keep.coerceAtLeast(1))
        .forEach { runCatching { delete(it) } }
}

/**
 * A single document the user picked through the system file picker.
 *
 * No permissions and no account: the picker grants access to exactly the one
 * file, and where that file lives — local storage, Google Drive, Dropbox — is
 * the user's business, not the app's.
 */
class SafDocumentTarget(
    private val context: Context,
    private val uri: Uri,
) : BackupTarget {

    override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): BackupRef {
        // The user already named the file in the picker, so `name` is ignored.
        context.contentResolver.openOutputStream(uri, "wt")?.use { body(it) }
            ?: throw IOException("Cannot write to the chosen file")
        return describe()
    }

    override suspend fun list(): List<BackupRef> = listOf(describe())

    override suspend fun open(ref: BackupRef): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the chosen file")

    override suspend fun delete(ref: BackupRef) {
        throw UnsupportedOperationException("A document the user picked is theirs to delete")
    }

    private fun describe(): BackupRef {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.zip"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    size = cursor.getLong(1)
                }
            }
        }
        return BackupRef(id = uri.toString(), name = name, sizeBytes = size)
    }
}
