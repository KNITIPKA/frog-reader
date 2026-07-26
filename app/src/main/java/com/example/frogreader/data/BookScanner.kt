package com.example.frogreader.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.parser.BookParsers
import com.example.frogreader.data.parser.mobi.PdbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class ScannedFolder(
    val uri: Uri,
    val name: String,
)

data class ScannedBookFile(
    val uri: Uri,
    val file: File? = null,
    val title: String,
    val author: String? = null,
    val coverBytes: ByteArray? = null,
    val format: BookFormat,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ScannedBookFile
        return uri == other.uri
    }

    override fun hashCode(): Int {
        return uri.hashCode()
    }
}

object BookScanner {

    private const val PREFS_NAME = "frog_scanned_folders"
    private const val KEY_FOLDERS = "folder_uris"

    fun getSavedFolders(context: Context): List<ScannedFolder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStrings = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        return uriStrings.mapNotNull { uriStr ->
            runCatching {
                val uri = Uri.parse(uriStr)
                val folderName = parseFolderName(uri)
                ScannedFolder(uri, folderName)
            }.getOrNull()
        }
    }

    fun addFolder(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    fun removeFolder(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_FOLDERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    private fun parseFolderName(treeUri: Uri): String {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val name = docId.substringAfterLast(':').substringAfterLast('/').trim()
            if (name.isNotBlank()) name else "Папка"
        }.getOrDefault("Папка")
    }

    suspend fun scanDirectories(context: Context): List<ScannedBookFile> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<ScannedBookFile>()
        val seenKeys = mutableSetOf<String>()

        val userFolders = getSavedFolders(context)

        if (userFolders.isNotEmpty()) {
            userFolders.forEach { folder ->
                scanFolderTree(context, folder.uri, resultList, seenKeys)
            }
        } else {
            // Default scan: MediaStore & Downloads/Documents folders
            val collectionsToQuery = mutableListOf<Uri>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collectionsToQuery.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                collectionsToQuery.add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            } else {
                collectionsToQuery.add(MediaStore.Files.getContentUri("external"))
            }

            collectionsToQuery.forEach { collection ->
                runCatching {
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.DATE_MODIFIED,
                    )

                    val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.epub' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.fb2' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.fb2.zip' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.mobi' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.prc' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.azw' OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.azw3'"

                    context.contentResolver.query(
                        collection,
                        projection,
                        selection,
                        null,
                        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = cursor.getString(nameCol) ?: continue
                            val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                            val size = cursor.getLong(sizeCol)
                            val date = cursor.getLong(dateCol) * 1000L
                            val contentUri = ContentUris.withAppendedId(collection, id)

                            val key = name.lowercase()
                            if (key in seenKeys) continue

                            val format = inferFormat(name) ?: continue
                            val file = path?.let { File(it) }?.takeIf { it.exists() }

                            val (title, author, coverBytes) = parseMetadataForFile(context, contentUri, file, name, format)

                            val item = ScannedBookFile(
                                uri = contentUri,
                                file = file,
                                title = title,
                                author = author,
                                coverBytes = coverBytes,
                                format = format,
                                sizeBytes = size,
                                lastModifiedMillis = date,
                            )
                            seenKeys.add(key)
                            path?.let { seenKeys.add(it.lowercase()) }
                            resultList.add(item)
                        }
                    }
                }
            }

            runCatching {
                val directFiles = mutableListOf<File>()
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists() && downloadsDir.isDirectory) {
                    scanDirectory(downloadsDir, directFiles, maxDepth = 3)
                }
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                if (documentsDir.exists() && documentsDir.isDirectory) {
                    scanDirectory(documentsDir, directFiles, maxDepth = 3)
                }

                directFiles.forEach { file ->
                    if (file.name.lowercase() !in seenKeys && file.absolutePath.lowercase() !in seenKeys) {
                        inspectBook(context, file)?.let {
                            seenKeys.add(file.name.lowercase())
                            resultList.add(it)
                        }
                    }
                }
            }
        }

        resultList.sortedByDescending { it.lastModifiedMillis }
    }

    private fun scanFolderTree(
        context: Context,
        treeUri: Uri,
        resultList: MutableList<ScannedBookFile>,
        seenKeys: MutableSet<String>,
    ) {
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val format = inferFormat(name) ?: continue

                    val key = name.lowercase()
                    if (key in seenKeys) continue

                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)

                    val (title, author, coverBytes) = parseMetadataForFile(context, childUri, null, name, format)

                    val item = ScannedBookFile(
                        uri = childUri,
                        file = null,
                        title = title,
                        author = author,
                        coverBytes = coverBytes,
                        format = format,
                        sizeBytes = size,
                        lastModifiedMillis = date,
                    )
                    seenKeys.add(key)
                    resultList.add(item)
                }
            }
        }
    }

    private fun parseMetadataForFile(
        context: Context,
        uri: Uri,
        file: File?,
        fallbackName: String,
        format: BookFormat,
    ): Triple<String, String?, ByteArray?> {
        val targetFile = file ?: run {
            val temp = File.createTempFile("scan-", null, context.cacheDir)
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { input.copyTo(it) }
                }
            }
            temp
        }

        val isTemp = (file == null)
        return try {
            val metadata = runCatching { BookParsers.parseMetadata(targetFile, format) }.getOrNull()
            val title = metadata?.title?.takeIf { it.isNotBlank() }
                ?: fallbackName.substringBeforeLast('.').removeSuffix(".fb2")
            val author = metadata?.authors?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: metadata?.author?.takeIf { it.isNotBlank() }
            val cover = metadata?.coverBytes

            Triple(title, author, cover)
        } finally {
            if (isTemp) targetFile.delete()
        }
    }

    private fun scanDirectory(dir: File, result: MutableList<File>, maxDepth: Int, currentDepth: Int = 0) {
        if (currentDepth > maxDepth) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val nameLower = file.name.lowercase()
                val ext = file.extension.lowercase()
                if (ext in BOOK_EXTENSIONS || nameLower.endsWith(".fb2.zip")) {
                    result.add(file)
                }
            } else if (file.isDirectory && !file.name.startsWith(".")) {
                scanDirectory(file, result, maxDepth, currentDepth + 1)
            }
        }
    }

    private val BOOK_EXTENSIONS = setOf("epub", "fb2", "mobi", "prc", "azw", "azw3")

    private fun inferFormat(fileName: String): BookFormat? {
        val nameLower = fileName.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "epub" -> BookFormat.EPUB
            ext == "fb2" || nameLower.endsWith(".fb2.zip") -> BookFormat.FB2
            ext in setOf("mobi", "prc", "azw", "azw3") -> BookFormat.MOBI
            else -> null
        }
    }

    private fun inspectBook(context: Context, file: File): ScannedBookFile? = runCatching {
        if (!file.exists() || file.length() < 100) return@runCatching null
        val format = inferFormat(file.name) ?: run {
            val header = file.inputStream().use { it.readNBytes(68) }
            if (PdbFile.isPdbBook(header)) {
                BookFormat.MOBI
            } else if (header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                runCatching {
                    ZipFile(file).use { zip ->
                        when {
                            zip.getEntry("META-INF/container.xml") != null -> BookFormat.EPUB
                            zip.entries().asSequence().any { it.name.endsWith(".fb2", ignoreCase = true) } -> BookFormat.FB2
                            else -> null
                        }
                    }
                }.getOrNull()
            } else {
                null
            }
        } ?: return@runCatching null

        val (title, author, coverBytes) = parseMetadataForFile(context, Uri.fromFile(file), file, file.name, format)

        ScannedBookFile(
            uri = Uri.fromFile(file),
            file = file,
            title = title,
            author = author,
            coverBytes = coverBytes,
            format = format,
            sizeBytes = file.length(),
            lastModifiedMillis = file.lastModified(),
        )
    }.getOrNull()
}
