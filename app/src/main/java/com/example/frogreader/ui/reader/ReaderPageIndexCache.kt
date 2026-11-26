package com.example.frogreader.ui.reader

import com.example.frogreader.engine.RustBridge
import com.example.frogreader.engine.RustPageIndexNative
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

internal object ReaderPageIndexCache {
    fun fileFor(dir: File, bookId: String): File = File(dir, "$bookId.rpi")

    fun load(file: File, key: ByteArray, itemCount: Int): ReaderPageIndex? =
        RustBridge.shared.withFallback(true, { null }) {
            RustPageIndexNative.read(file.path, key, itemCount)?.let(::ReaderPageIndex)
        }

    fun load(bytes: ByteArray, key: ByteArray, itemCount: Int): ReaderPageIndex? =
        RustBridge.shared.withFallback(true, { null }) {
            RustPageIndexNative.readBytes(bytes, key, itemCount)?.let(::ReaderPageIndex)
        }

    @Synchronized
    fun save(file: File, key: ByteArray, itemCount: Int, anchors: IntArray, active: () -> Boolean = { true }): Boolean {
        if (!active()) return false
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val pending = File(parent, "${file.name}.tmp")
        if (pending.exists() && !pending.delete()) return false
        return try {
            if (!RustBridge.shared.withFallback(true, { false }) {
                    RustPageIndexNative.write(pending.path, key, itemCount, anchors)
                }) return false
            if (!active()) return false
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            false
        } finally {
            pending.delete()
        }
    }

    /** The source, renderer, viewport and bytes of files that can change layout. */
    fun key(contentKey: String, spec: PaginationSpec, coverPath: String?, environment: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateFramed(contentKey)
        digest.updateFramed(spec.key)
        digest.updateFramed(environment)
        digest.updateFramed(coverPath?.let { layoutFileState(File(it)) } ?: "no cover")
        (listOfNotNull(spec.settings.customFontPath) + spec.settings.customFontFamilyPaths)
            .distinct().sorted().forEach { path ->
                digest.updateFramed(path)
                digest.updateFramed(layoutFileState(File(path)))
            }
        return digest.digest()
    }
}
