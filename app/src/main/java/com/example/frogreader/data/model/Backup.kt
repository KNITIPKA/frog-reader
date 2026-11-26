package com.example.frogreader.data.model

import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.ReaderSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata and settings models for FrogReader SQLite backup archives.
 *
 * Archive layout (format v4):
 * - `manifest.json`        — written first so a reader can inspect metadata in O(1)
 * - `database/frogreader.db` — self-contained compacted SQLite snapshot (`VACUUM INTO`)
 * - `settings.json`        — reader and app settings (with portable `fonts/<name>` paths and avatar)
 * - `fonts/<name>`         — user-imported custom fonts (included in both DATA and FULL modes)
 * - `covers/<name>`        — Reading Log covers in every mode; book/queue covers in FULL mode
 * - `books/<id>.<ext>`     — ebook and PDF files in FULL mode only
 */

@Serializable
enum class BackupMode {
    /** User data, database, settings, custom fonts, and Reading Log photos. */
    DATA,

    /** Everything in [DATA], plus ebook/PDF files and library/queue covers. */
    FULL,
}

@Serializable
data class BackupManifest(
    /** Archive format version; v4 uses native SQLite (`database/frogreader.db`). */
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "",
    val createdAtMillis: Long = 0L,
    val mode: BackupMode = BackupMode.DATA,
    val bookCount: Int = 0,
    val quoteCount: Int = 0,
    @SerialName("readingListCount")
    val readingQueueCount: Int = 0,
    val readingLogCount: Int = 0,
) {
    companion object {
        // Version 4 switches the archive payload to a native SQLite snapshot (`database/frogreader.db`).
        const val FORMAT_VERSION = 4
    }
}

@Serializable
data class BackupSettings(
    val reader: ReaderSettings = ReaderSettings(),
    val app: AppSettings = AppSettings(),
)
