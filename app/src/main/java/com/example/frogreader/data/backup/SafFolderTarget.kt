package com.example.frogreader.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
        // Providers happily keep several files with the same name and hand back
        // "name (1)". Replacing the old one keeps the rotation predictable.
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/zip", name)
            ?: throw IOException("Could not create $name in the backup folder")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { body(it) }
            ?: throw IOException("Could not write $name")
        BackupRef(
            id = file.uri.toString(),
            name = file.name ?: name,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
        )
    }

    override suspend fun list(): List<BackupRef> = withContext(Dispatchers.IO) {
        folder().listFiles()
            .filter { it.isFile && it.name?.endsWith(".zip") == true }
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
        context.contentResolver.openInputStream(Uri.parse(ref.id))
            ?: throw IOException("Could not open ${ref.name}")

    override suspend fun delete(ref: BackupRef) {
        withContext(Dispatchers.IO) {
            DocumentFile.fromSingleUri(context, Uri.parse(ref.id))?.delete()
        }
    }
}
