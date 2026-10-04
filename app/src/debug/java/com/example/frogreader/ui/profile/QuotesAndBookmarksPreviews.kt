package com.example.frogreader.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.theme.FrogReaderTheme

private val sampleAnnotatedBooks = listOf(
    Book("a", "A Room of One’s Own", "Virginia Woolf", BookFormat.EPUB, fileName = "a.epub", addedAtMillis = 0,
        quotes = listOf(
            Quote("q1", "One cannot think well, love well, sleep well, if one has not dined well.", 2, 2,
                startItem = 20, startChar = 0, note = "The everyday things are not separate from the life of the mind. I want to remember that."),
            Quote("q2", "There is no gate, no lock, no bolt that you can set upon the freedom of my mind.", 3, 1,
                startItem = 40, startChar = 0),
        ),
        bookmarks = listOf(Bookmark("b1", 20, 2, "One cannot think well, love well, sleep well, if one has not dined well.", 5))),
    Book("b", "The Art of Noticing", "Rob Walker", BookFormat.EPUB, addedAtMillis = 0,
        quotes = listOf(Quote("q3", "A small detail can change the way we see an ordinary day.", 0, 1)),
        bookmarks = listOf(Bookmark("b2", 0, 0, "A small detail can change the way we see an ordinary day.", 2))),
)

@Preview(name = "Quotes & Bookmarks · books", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun SavedBooksPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(sampleAnnotatedBooks)
}

@Preview(name = "Quotes · book", widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
private fun SavedQuotesPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(sampleAnnotatedBooks, initialBookId = "a")
}

@Preview(name = "Bookmarks · midnight", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun SavedBookmarksPreview() = FrogReaderTheme(AppTheme.OLED) {
    QuotesAndBookmarksContent(sampleAnnotatedBooks, initialBookId = "a", initialTab = 1)
}

@Preview(name = "Thought editor · large text", widthDp = 360, heightDp = 800, fontScale = 1.5f, showSystemUi = true)
@Composable
private fun SavedThoughtPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(sampleAnnotatedBooks, initialBookId = "a",
        editor = QuoteThoughtEditor("a", "q1", "", "This is what I want to remember."))
}

@Preview(name = "Empty collection", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun EmptyQuotesAndBookmarksPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(emptyList())
}

// Same content shape as the owner's screenshots: absent author, long quote, two bookmarks.
private val screenshotBook = Book(
    id = "frankenstein", title = "Frankenstein", format = BookFormat.EPUB,
    fileName = "sample.epub", addedAtMillis = 0,
    quotes = listOf(
        Quote("q1", "Such was the history of my beloved cottagers. It impressed me deeply. I learned, from the views of social life which it developed, to admire their virtues, and to deprecate the vices of mankind.",
            25, 1, startItem = 20, startChar = 0),
        Quote("q2", "I can hardly describe to you the effect of these books. They produced in me an infinity of new images and feelings.",
            25, 2, startItem = 30, startChar = 0),
    ),
    bookmarks = listOf(
        Bookmark("b1", 33, 32, "“You well know, Victor, that our union had been the favourite plan of your parents ever since".take(90), 2),
        Bookmark("b2", 26, 25, "“I can hardly describe to you the effect of these books. They produced in me an infinity of".take(90), 1),
    ),
)

@Preview(name = "Screenshot comparison · quotes", widthDp = 393, heightDp = 882, showSystemUi = true)
@Composable
private fun ScreenshotQuotesPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(listOf(screenshotBook), initialBookId = screenshotBook.id)
}

@Preview(name = "Screenshot comparison · bookmarks", widthDp = 393, heightDp = 882, showSystemUi = true)
@Composable
private fun ScreenshotBookmarksPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(listOf(screenshotBook), initialBookId = screenshotBook.id, initialTab = 1)
}

@Preview(name = "Long quote · midnight", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun LongQuoteMidnightPreview() = FrogReaderTheme(AppTheme.OLED) {
    QuotesAndBookmarksContent(listOf(screenshotBook), initialBookId = screenshotBook.id)
}

@Preview(name = "Book with bookmarks only", widthDp = 360, heightDp = 800, showSystemUi = true)
@Composable
private fun BookmarkOnlyBookPreview() = FrogReaderTheme(AppTheme.WHITE) {
    QuotesAndBookmarksContent(listOf(screenshotBook.copy(quotes = emptyList())), initialBookId = screenshotBook.id)
}
