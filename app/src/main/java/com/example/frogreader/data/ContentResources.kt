package com.example.frogreader.data

import com.example.frogreader.data.model.BookContent
import com.example.frogreader.data.model.ContentElement
import com.example.frogreader.data.model.INLINE_IMAGE_TAG
import java.io.File

internal fun contentResourcesExist(content: BookContent): Boolean =
    contentResourcePaths(content).all { File(it).isFile }

/** Collected once when writing the cache, without decoding text on indexed opens. */
internal fun contentResourcePaths(content: BookContent): Set<String> {
    val paths = HashSet<String>()
    content.fonts.forEach { paths += it.path }
    fun text(value: androidx.compose.ui.text.AnnotatedString) {
        value.getStringAnnotations(INLINE_IMAGE_TAG, 0, value.length).forEach { paths += it.item }
    }
    fun elements(values: List<ContentElement>) {
        for (element in values) when (element) {
            is ContentElement.Image -> paths += element.path
            is ContentElement.Paragraph -> { text(element.text); element.block?.floatImage?.let { paths += it.path } }
            is ContentElement.Heading -> { text(element.styledText); element.block?.floatImage?.let { paths += it.path } }
            is ContentElement.Table -> {
                element.block?.floatImage?.let { paths += it.path }
                element.rows.forEach { row -> row.cells.forEach { cell ->
                    text(cell.text)
                    cell.block?.floatImage?.let { paths += it.path }
                    cell.narrowStyle?.block?.floatImage?.let { paths += it.path }
                } }
            }
            ContentElement.Divider, is ContentElement.Spacer -> Unit
        }
    }
    content.chapters.forEach { elements(it.elements) }
    content.notes.values.forEach { elements(it.elements) }
    content.linkedDocuments.values.forEach { elements(it.elements) }
    return paths
}
