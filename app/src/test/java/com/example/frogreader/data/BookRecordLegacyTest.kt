package com.example.frogreader.data

import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Every field added to [com.example.frogreader.data.model.BookRecord] has to be
 * defaulted, or kotlinx.serialization throws MissingFieldException on every
 * library.json written before it existed — which BookRepository reads as
 * "corrupted", i.e. an empty library. The user would open the app to find every
 * book gone.
 *
 * This pins the contract for `contentHash` and `sizeBytes` against a literal
 * legacy document, not against a re-encode of the current model, because a
 * re-encode would carry the new keys and prove nothing.
 */
class BookRecordLegacyTest {

    private val testDir = File("build/tmp/test_files")
    private val indexFile = File(testDir, "library.json")

    @Before
    fun setUp() {
        testDir.mkdirs()
        StoreFixture.clear(testDir)
    }

    @After
    fun tearDown() {
        StoreFixture.clear(testDir)
    }

    @Test
    fun `a library written before hashes existed still loads`() = runTest {
        indexFile.writeText(
            """
            {
              "books": [
                {
                  "id": "legacy-1",
                  "title": "War and Peace",
                  "author": "Leo Tolstoy",
                  "format": "EPUB",
                  "fileName": "legacy-1.epub",
                  "addedAtMillis": 1000
                }
              ]
            }
            """.trimIndent(),
        )

        val books = BookRepository(context = null).books.value

        assertEquals(1, books.size)
        assertEquals("War and Peace", books[0].title)
        assertNull("no hash was ever computed for this record", books[0].contentHash)
        assertEquals("unknown size reads as zero, not as an empty file", 0L, books[0].sizeBytes)
    }

    @Test
    fun `a hash survives a write and a reopen`() = runTest {
        val seeded = Book(
            id = "b1",
            title = "Anna Karenina",
            format = BookFormat.FB2,
            fileName = "b1.fb2",
            addedAtMillis = 5,
            contentHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 4096,
        )
        StoreFixture.seed(testDir, listOf(seeded))

        val reopened = BookRepository(context = null).books.value.single()
        assertEquals(seeded.contentHash, reopened.contentHash)
        assertEquals(4096L, reopened.sizeBytes)
    }
}
