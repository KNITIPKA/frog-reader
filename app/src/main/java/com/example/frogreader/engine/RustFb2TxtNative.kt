package com.example.frogreader.engine

import androidx.annotation.Keep

/**
 * Native M4.1 (TXT/MD decoding, paragraph splitting, heading slugs) and M4.2
 * (FB2 streaming encoding detection, XML sanitization, and structural scan).
 *
 * Names and signatures are part of the JNI ABI; keep them aligned with
 * `fb2_txt_jni.rs` and R8 keep rules.
 */
@Keep
internal object RustFb2TxtNative {
    @Keep
    external fun decodeText(bytes: ByteArray): ByteArray?

    @Keep
    external fun parseTxtParagraphs(bytes: ByteArray): ByteArray?

    @Keep
    external fun headingSlugs(encodedHeadings: ByteArray): ByteArray?

    @Keep
    external fun renderMarkdownHtml(bytes: ByteArray): ByteArray?

    @Keep
    external fun parseMarkdownTitle(bytes: ByteArray): ByteArray?

    @Keep
    external fun parseMarkdownMarkup(bytes: ByteArray): ByteArray?

    @Keep
    external fun parseMarkdownContent(bytes: ByteArray): ByteArray?

    @Keep
    external fun writeMdFrb(
        sourceBytes: ByteArray,
        destPath: String,
        sourceMetadata: ByteArray,
    ): Int

    @Keep
    external fun writeTxtFrb(
        sourceBytes: ByteArray,
        destPath: String,
        sourceMetadata: ByteArray,
        language: String?,
    ): Boolean

    @Keep
    external fun detectXmlEncoding(prefix: ByteArray): String?

    @Keep
    external fun openSanitizer(rewritePrologEncoding: Boolean): Long

    @Keep
    external fun feedSanitizer(handle: Long, chunk: ByteArray, length: Int, eof: Boolean): ByteArray?

    @Keep
    external fun closeSanitizer(handle: Long)

    @Keep
    external fun openStructureScan(): Long

    @Keep
    external fun feedStructureScan(handle: Long, chunk: ByteArray, length: Int, eof: Boolean): Boolean

    @Keep
    external fun finishStructureScan(handle: Long): ByteArray?

    @Keep
    external fun closeStructureScan(handle: Long)

    @Keep
    external fun scanStructure(bytes: ByteArray): ByteArray?

    @Keep
    external fun openMetadataParse(maxCoverBytes: Long): Long

    @Keep
    external fun feedMetadataParse(handle: Long, chunk: ByteArray, length: Int, eof: Boolean): Int

    @Keep
    external fun finishMetadataParse(handle: Long): ByteArray?

    @Keep
    external fun closeMetadataParse(handle: Long)

    @Keep
    external fun parseFb2Metadata(bytes: ByteArray, maxCoverBytes: Long): ByteArray?

    @Keep
    @JvmStatic
    external fun decodeMimeBase64(bytes: ByteArray, maxDecodedBytes: Long): ByteArray?

    @Keep
    external fun parseFb2Content(bytes: ByteArray, imagesDir: String, limits: LongArray): ByteArray?

    @Keep
    external fun parseFb2ContentFile(
        sourcePath: String,
        imagesDir: String,
        limits: LongArray,
    ): ByteArray?

    @Keep
    external fun writeFb2FrbFile(
        sourcePath: String,
        imagesDir: String,
        destFrbPath: String,
        sourceMetadata: ByteArray,
        limits: LongArray,
    ): ByteArray?

    @Keep
    external fun parseFb2TargetedSections(
        bytes: ByteArray,
        sectionIndices: IntArray,
        imagesDir: String,
        limits: LongArray,
    ): ByteArray?

    @Keep
    external fun startFb2ContentWithPreview(
        bytes: ByteArray,
        imagesDir: String,
        limits: LongArray,
        previewElementIndex: Int,
    ): ByteArray?

    @Keep
    external fun finishFb2ContentParse(handle: Long): ByteArray?

    @Keep
    external fun closeFb2ContentParse(handle: Long)
}
