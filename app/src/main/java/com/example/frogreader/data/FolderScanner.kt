package com.example.frogreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** A book file found in a picked folder, before anything has been parsed. */
data class ScanCandidate(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val format: BookFormat,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * Walks a folder the user picked, looking for books.
 *
 * Only a folder the user picked. The previous version also queried MediaStore
 * and walked the public Downloads and Documents directories, which needed
 * READ_EXTERNAL_STORAGE — a permission the app declared and never once
 * requested. On Android 8 to 12 those paths returned nothing and swallowed the
 * failure; on 13 and up scoped storage made them meaningless. It looked like a
 * feature and was dead code.
 *
 * The walk is deliberately split in two. Finding files is a directory listing
 * and takes milliseconds; working out a book's title, author and cover means
 * copying it out of the provider and parsing it, which takes about a second
 * each. Emitting the first as it goes lets the list appear at once and fill
 * itself in, instead of showing a spinner until every book on the device has
 * been parsed — which is what the old scan did, every time it was opened.
 */
object FolderScanner {

    /**
     * How deep to look. Book folders nest by author and series, rarely past
     * three or four; the cap is what stops a pathological tree (or a provider
     * that reports a cycle) from walking forever.
     */
    const val MAX_DEPTH = 8

    /** The folder's own name, or null when the provider will not say. */
    fun folderName(treeUri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Every supported book under [treeUri], emitted as it is found.
     *
     * Breadth-first, so the books sitting directly in the folder the user
     * picked appear before anything buried in subfolders — which is the order
     * they are most likely to want.
     */
    fun enumerate(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = MAX_DEPTH,
    ): Flow<ScanCandidate> = flow {
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Pair<String, Int>>()
        // A tree can contain a shortcut back to a folder already visited, and
        // some providers report one. Without this the walk never ends.
        val seen = HashSet<String>()
        queue += root to 0
        seen += root

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (documentId, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

            // Each directory's cursor is drained and closed before descending,
            // so a deep tree never holds more than one open at a time.
            val found = ArrayList<ScanCandidate>()
            val subdirectories = ArrayList<String>()
            context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val childId = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: continue
                    if (name.startsWith('.')) continue

                    if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // The missing piece in the old scan: without asking for
                        // the MIME type there was no way to tell a folder from a
                        // file, so it never descended at all.
                        if (depth < maxDepth && seen.add(childId)) subdirectories += childId
                        continue
                    }

                    val format = inferFormat(name) ?: continue
                    found += ScanCandidate(
                        documentId = childId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        name = name,
                        format = format,
                        sizeBytes = cursor.getLong(sizeColumn),
                        lastModifiedMillis = cursor.getLong(dateColumn),
                    )
                }
            }

            found.forEach { emit(it) }
            subdirectories.forEach { queue += it to depth + 1 }
        }
    }.flowOn(Dispatchers.IO)

    /** What a file name claims to be. Content is checked later, at import. */
    fun inferFormat(fileName: String): BookFormat? {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".epub") -> BookFormat.EPUB
            lower.endsWith(".fb2") || lower.endsWith(".fb2.zip") -> BookFormat.FB2
            MOBI_EXTENSIONS.any { lower.endsWith(it) } -> BookFormat.MOBI
            else -> null
        }
    }

    /**
     * Hands back the folder permissions the previous version took and never
     * released.
     *
     * It called takePersistableUriPermission on every folder added and had no
     * matching release — removing a folder forgot the URI and kept the grant.
     * Those grants count against a per-app cap and appear in no interface the
     * user can reach, so anyone who used the old scan is holding some now.
     * Scanning no longer persists anything, so all of them can go.
     */
    fun releaseLegacyGrants(context: Context) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(LEGACY_KEY, emptySet()).orEmpty()
        if (stored.isEmpty()) return
        stored.forEach { value ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(LEGACY_KEY).apply()
    }

    private val MOBI_EXTENSIONS = listOf(".mobi", ".prc", ".azw", ".azw3")

    private const val LEGACY_PREFS = "frog_scanned_folders"
    private const val LEGACY_KEY = "folder_uris"
}
