package com.example.frogreader.ui.reader

import com.example.frogreader.data.CustomFontGroup
import java.io.File
import java.security.MessageDigest

/** Saved copies remain deletable, while a book and its exact copies share one visible family. */
internal data class FontLibraryMerge(
    val fonts: List<CustomFontGroup> = emptyList(),
    val embedded: List<CustomFontGroup> = emptyList(),
    /** Saved file to byte-equivalent book file; also maps the active manual selection. */
    val savedBookPaths: Map<String, String> = emptyMap(),
) {
    fun savedCopies(group: CustomFontGroup): List<String> = savedBookPaths
        .filterValues(group::contains).keys.toList()

    fun selectedBookPath(selectedPath: String?): String? = selectedPath?.let(savedBookPaths::get)
}

/** Names alone cannot identify copies: another release of a family must remain separate. */
internal fun mergeFontLibrary(
    fonts: List<CustomFontGroup>,
    embedded: List<CustomFontGroup>,
    fingerprint: (String) -> String? = ::fontFileFingerprint,
): FontLibraryMerge {
    val identities = mutableMapOf<String, String?>()
    fun identity(path: String): String? = identities.getOrPut(path) { fingerprint(path) }
    val bookFiles = embedded.map { group ->
        group to group.faces.map { it.path }.distinct().mapNotNull { path -> identity(path)?.let { it to path } }.toMap()
    }
    val savedBookPaths = mutableMapOf<String, String>()
    val separate = fonts.filter { group ->
        val files = group.faces.map { it.path }.distinct()
        val matching = bookFiles.firstOrNull { (_, book) ->
            files.isNotEmpty() && files.all { path -> identity(path)?.let(book::containsKey) == true }
        }
        if (matching != null) {
            files.forEach { path -> savedBookPaths[path] = matching.second.getValue(identity(path)!!) }
            false
        } else true
    }
    return FontLibraryMerge(separate, embedded, savedBookPaths)
}

/** Called during dialog discovery on Dispatchers.IO; streamed hashing avoids loading whole fonts. */
private fun fontFileFingerprint(path: String): String? = runCatching {
    val file = File(path)
    require(file.isFile)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    file.length().toString() + ":" + digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
}.getOrNull()
