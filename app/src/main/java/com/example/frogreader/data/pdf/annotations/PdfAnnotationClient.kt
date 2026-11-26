package com.example.frogreader.data.pdf.annotations

import android.content.Context
import android.net.Uri
import com.example.frogreader.data.model.PdfAnnotationPayload
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.pdf.compat.MAX_ANNOTATION_PAYLOAD_BYTES
import com.example.frogreader.data.pdf.compat.PdfTextClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Reuses the existing permissionless PDFium service; no additional parser or PDF engine. */
internal object PdfAnnotationClient {
    suspend fun write(context: Context, source: File, target: File, bookId: String, quotes: List<Quote>, password: String?) =
        withContext(Dispatchers.IO) {
            val owner = context.applicationContext ?: context
            val payloadFile = File(owner.cacheDir, "pdf-annotations-${UUID.randomUUID()}.json")
            try {
                val bytes = Json.encodeToString(PdfAnnotationPayload.serializer(), PdfAnnotationPayload(bookId, quotes))
                    .toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_ANNOTATION_PAYLOAD_BYTES) throw IOException("Too many PDF annotations")
                FileOutputStream(payloadFile).use { it.write(bytes) }
                PdfTextClient.connect(owner, Uri.fromFile(source), password, separateProcess = true).use { worker ->
                    worker.writeAnnotations(target, payloadFile)
                }
                if (target.length() < 8) throw IOException("PDF annotation output is incomplete")
                FileOutputStream(target, true).use { it.fd.sync() }
            } finally { payloadFile.delete() }
        }
}
