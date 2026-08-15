package com.example.frogreader.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private val FROGREADER_SNAPSHOT_NAME = Regex(
    "frogreader-\\d{4}-\\d{2}-\\d{2}(?:-\\d{6}-\\d{3})?(?: \\(\\d+\\))?\\.zip",
)

/** Names the app itself can produce for scheduled or explicit folder backups. */
internal fun String.isFrogReaderSnapshotFileName(): Boolean =
    FROGREADER_SNAPSHOT_NAME.matches(this)

/**
 * A folder the user picked once, written to again and again.
 *
 * This is where "back it up to the cloud" comes from without a line of network
 * code: Google Drive, Dropbox, OneDrive and Nextcloud all publish their folders
 * to the system picker, so a folder chosen inside one of them is written to
 * exactly like a local one. The app never sees an account, a token or a
 * network error — and the user is not tied to any one service.
 *
 * The permission is taken persistably at pick time, so it survives reboots and
 * the scheduled job can still write months later.
 */
class SafFolderTarget(
    private val context: Context,
    private val treeUri: Uri,
) : BackupTarget {

    private fun folder(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.canWrite() }
            ?: throw IOException("The backup folder is not available. Choose it again.")

    override suspend fun write(
        name: String,
        body: suspend (OutputStream) -> Unit,
    ): BackupRef = withContext(Dispatchers.IO) {
        val dir = folder()
        // Create the replacement first. Deleting today's last-good snapshot
        // before a provider/network write succeeds would turn a retry failure
        // into data loss. SAF providers may suffix a duplicate name; validated
        // rotation handles that only after this new file is complete.
        val file = dir.createFile("application/zip", name)
            ?: throw IOException("Could not create $name in the backup folder")
        var complete = false
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { body(it) }
                ?: throw IOException("Could not write $name")
            complete = true
        } finally {
            // This is the just-created document, never the previous last-good.
            // Do not leave a failed partial ZIP looking like a snapshot.
            if (!complete) runCatching { file.delete() }
        }
        BackupRef(
            id = file.uri.toString(),
            name = file.name ?: name,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
        )
    }

    override suspend fun list(): List<BackupRef> = withContext(Dispatchers.IO) {
        folder().listFiles()
            // A SAF tree belongs to the user, not to FrogReader. Never expose
            // unrelated ZIPs to retention or the restore history.
            .filter { it.isFile && it.name?.isFrogReaderSnapshotFileName() == true }
            .map {
                BackupRef(
                    id = it.uri.toString(),
                    name = it.name.orEmpty(),
                    sizeBytes = it.length(),
                    modifiedAtMillis = it.lastModified(),
                )
            }
            .sortedByDescending { it.modifiedAtMillis }
    }

    override suspend fun open(ref: BackupRef): InputStream =
        context.contentResolver.openInputStream(ref.id.toUri())
            ?: throw IOException("Could not open ${ref.name}")

    override suspend fun delete(ref: BackupRef) {
        withContext(Dispatchers.IO) {
            DocumentFile.fromSingleUri(context, ref.id.toUri())?.delete()
        }
    }
}
