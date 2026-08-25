package com.example.frogreader.testbooks

/** Bits every writer needs: escaping and "which pictures does this file use". */

fun xmlEscape(text: String): String = buildString(text.length) {
    for (char in text) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}

/**
 * Image names actually reachable in the expanded content.
 *
 * A picture referenced only by a check that this format stubs out must not be
 * packed: an unused resource in an EPUB is harmless, but an unreferenced FB2
 * `<binary>` is decoded and thrown away, and an unused MOBI image record
 * shifts every `recindex` after it.
 */
fun collectImages(blocks: List<Block>): LinkedHashSet<String> {
    val found = LinkedHashSet<String>()

    fun scanRuns(runs: List<Run>) {
        for (run in runs) {
            when (run) {
                is Run.InlineImage -> found += run.image
                is Run.Styled -> scanRuns(run.runs)
                else -> Unit
            }
        }
    }

    fun scan(list: List<Block>) {
        for (block in list) {
            when (block) {
                is Block.Img -> found += block.image
                is Block.Figure -> found += block.image
                is Block.FloatPara -> {
                    found += block.image
                    scanRuns(block.runs)
                }

                is Block.P -> scanRuns(block.runs)
                is Block.Aside -> scanRuns(block.runs)
                is Block.Quote -> scan(block.body)
                is Block.Epigraph -> scan(block.body)
                is Block.Test -> scan(block.body)
                is Block.Lst -> for (item in block.items) {
                    scanRuns(item.runs)
                    item.nested?.let { scan(listOf(it)) }
                }

                else -> Unit
            }
        }
    }

    scan(blocks)
    return found
}

/** A chapter with the chapters that nest under it. */
class ChapterNode(val chapter: Ch, val children: MutableList<ChapterNode> = mutableListOf())

/**
 * Rebuilds the depth column into real nesting. Chapters are authored flat
 * with a depth each, but every table of contents — EPUB `nav`, NCX, FB2
 * `<section>` — wants a tree.
 */
fun List<Ch>.tree(): List<ChapterNode> {
    val roots = mutableListOf<ChapterNode>()
    val openPath = mutableListOf<ChapterNode>()
    for (chapter in this) {
        while (openPath.size > chapter.depth) openPath.removeAt(openPath.lastIndex)
        val node = ChapterNode(chapter)
        if (openPath.isEmpty()) roots += node else openPath.last().children += node
        openPath += node
    }
    return roots
}

/** Assets this format has to pack, cover first so it can claim record 1. */
fun Doc.imagesFor(format: Fmt): List<String> {
    val used = LinkedHashSet<String>()
    used += "cover"
    for (chapter in chapters) used += collectImages(chapter.blocks.expand(format))
    for (note in notes) used += collectImages(note.blocks.expand(format))
    // A name with no asset behind it is the "missing image" check.
    return used.filter { it in TestAssets.images }
}
