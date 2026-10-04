package com.example.frogreader.data

import com.example.frogreader.data.model.BackupDocument
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.UserDataStore
import com.example.frogreader.ui.nav.QuotesAndBookmarksRoute
import com.example.frogreader.ui.nav.ReadingLogRoute
import com.example.frogreader.ui.nav.ReadingQueueRoute
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNamingCompatibilityTest {
    @Test fun `existing queue entries timestamps and book notes keep their storage keys`() {
        val legacy = """{
            "userData":{"book":{"readingListNote":"Read with a friend"}},
            "readingList":[{"id":"queued","title":"Next book","note":"Keep me","updatedAtMillis":9}],
            "readingListUpdatedAtMillis":23
        }"""
        val store = Json.decodeFromString(UserDataStore.serializer(), legacy)
        assertEquals("Keep me", store.readingQueue.single().note)
        assertEquals(9L, store.readingQueue.single().updatedAtMillis)
        assertEquals(23L, store.readingQueueUpdatedAtMillis)
        assertEquals("Read with a friend", store.userData.getValue("book").readingQueueNote)

        val encoded = Json.encodeToJsonElement(UserDataStore.serializer(), store).jsonObject
        assertEquals(Json.parseToJsonElement(legacy), encoded)
        assertFalse(encoded.containsKey("readingQueue"))
        assertFalse(encoded.containsKey("readingQueueUpdatedAtMillis"))
    }

    @Test fun `existing backup queue attachments notes and counts keep their archive keys`() {
        val legacy = """{
            "books":[{"id":"book","title":"Book","format":"EPUB","addedAtMillis":1,"readingListNote":"A reason"}],
            "readingList":[{"id":"queued","title":"Next book","attachment":{
                "id":"reading-list-attached","title":"Next book","format":"EPUB","addedAtMillis":2,"fileName":"private.epub"
            }}]
        }"""
        val document = Json.decodeFromString(BackupDocument.serializer(), legacy)
        assertEquals("A reason", document.books.single().readingQueueNote)
        assertEquals("private.epub", document.readingQueue.single().attachment?.fileName)
        assertEquals(2, document.assetBooks.size)
        val encoded = Json.encodeToJsonElement(BackupDocument.serializer(), document).jsonObject
        assertEquals(Json.parseToJsonElement(legacy), encoded)
        assertTrue(encoded["books"]!!.jsonArray.single().jsonObject.containsKey("readingListNote"))
        assertFalse(encoded.containsKey("readingQueue"))

        val manifest = Json.decodeFromString(BackupManifest.serializer(), """{"readingListCount":1}""")
        assertEquals(1, manifest.readingQueueCount)
        assertEquals(2, manifest.formatVersion)
        assertEquals(Json.parseToJsonElement("""{"readingListCount":1}"""),
            Json.encodeToJsonElement(BackupManifest.serializer(), manifest))
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun `renamed destinations retain their existing navigation identities`() {
        assertEquals("com.example.frogreader.ui.nav.QuotesBookmarksRoute",
            QuotesAndBookmarksRoute.serializer().descriptor.serialName)
        assertEquals("com.example.frogreader.ui.nav.ReadingListRoute",
            ReadingQueueRoute.serializer().descriptor.serialName)
        assertEquals("com.example.frogreader.ui.nav.ReadingHistoryRoute",
            ReadingLogRoute.serializer().descriptor.serialName)
    }
}
