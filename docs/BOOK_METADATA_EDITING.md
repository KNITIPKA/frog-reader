# Book metadata editing

The library’s **Info** action, available on long press in the grid or inside a shelf and in the continue-reading menu, opens a full book information screen. Its header shows the cover beside the complete title and author, followed by publication metadata, the full description, and technical details. The upper-right **Edit** and **Share** actions open the editor and Android’s file sharing chooser. Info observes the repository, so returning from a saved edit shows the current metadata and cover.

The **Edit** screen uses the same header. Tapping its cover opens change/remove/restore actions; the title and author alongside it follow the draft. Long titles wrap, fields grow with their contents, and contextual entry hints appear on focus. The save area remains accessible while scrolling or using the keyboard. Drafts survive configuration changes, Back confirms discarding unsaved edits, and failed saves retain the draft with a readable error card and separately accessible technical details. Export and an explanation of where edits are saved are available in the top-bar menu.

Editable fields: title, multiple authors, description, genres, series and number, publisher, publication year, ISBN, translators, language, and cover (replace/remove/restore). Names and genres use one entry per line, so commas within a name are not treated as separators.

## Files and search

FrogReader imports private copies. **Save to book** rewrites that copy’s actual file; it does not overwrite the external file originally selected during import. **Export a copy** uses Android’s document picker to write the current book bytes to a chosen destination. AZW3 exports retain the `.azw3` extension; zipped FB2 imports are already normalized to plain `.fb2`.

**Share** prepares an independent copy of the saved book in `cache/shared-books/`, with a readable title-based filename and the native extension/MIME type. It sends that file through `ACTION_SEND` with `EXTRA_STREAM`, `ClipData`, and a temporary read-only URI grant, following [Android’s file sharing flow](https://developer.android.com/develop/ui/compose/sharing/send). The non-exported `BookFileProvider` exposes only this cache subdirectory. Quotes, reading history and other application records are not included. A subsequent edit does not change an already prepared attachment. Snapshots older than seven days are reclaimed on the next share; opening the chooser does not claim a completed delivery.

A save writes a new generation, fsyncs it, then re-reads and checks all edited fields and the cover before committing the library index through `AtomicJsonFile`. An index failure leaves the original library and file usable. The previous book generation stays available for `library.json.bak` recovery. Concurrent stale editors are rejected by file-generation checks.

The library uses the metadata re-read from the saved book, including the description. Search sees the new values immediately; clearing the description removes its old matches. Content hashes and sizes change with the file. Covers and parsed content use generation-specific paths/keys. Quotes, bookmarks, progress, ratings, reviews and reading settings are retained; editing metadata does not re-anchor or reset them.

## Format handling

| Format | Storage |
| --- | --- |
| EPUB 2 / 3 | Native OPF Dublin Core fields, creator roles, series metadata/refinements, and cover manifest/resources. Other archive entries are streamed unchanged, except for intentionally replaced artwork or re-keyed obfuscated fonts. |
| FB2 / imported FB2 ZIP | Native `title-info`, `publish-info`, annotation and cover binary. The description is edited separately; the book body, notes and unrelated binaries are streamed through an XML serializer. Input encodings including Windows-1251 and UTF-16 are written as UTF-8. Valid decimal and hexadecimal XML character references are supported, including characters outside the original encoding; unresolved named entities and invalid XML characters are rejected. |
| MOBI6 / AZW / PRC / AZW3 / combined MOBI + KF8 | Standard EXTH metadata, cover resources, full-name header and rebuilt PDB byte offsets. Both headers of combined files are updated. Compressed text, indexes and unrelated resource records retain their bytes and record numbers. |
| Plain PalmDOC (`TEXtREAd`) | Native short title only: up to 31 Latin-1 characters. Other fields and covers require conversion to EPUB or MOBI. The screen explains this format limit. |

MOBI has no standard series/translator fields. These are stored **inside the book**, as UTF-8 JSON in private EXTH record `0x46524701`; FrogReader reads them on import. Other readers may ignore them. This record never substitutes for the standard title/author/description/cover fields. EXTH record meanings and PDB conventions can also be inspected in [Calibre’s metadata implementation](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/ebooks/metadata/mobi.py).

Legacy MOBI encodings are preserved because changing the text encoding would invalidate byte-based links and indexes. Characters the encoding cannot represent produce an explicit error rather than lossy replacement. The screen displays the actual legacy encoding.

EPUB ISBN edits preserve working embedded-font obfuscation by re-keying affected fonts when the package identity changes. Replacing a standalone SVG cover also updates its artwork while retaining the viewport. EPUB requires a language; a cleared language is stored as `und` (undetermined).

Copy-protected, digitally signed, malformed, multi-rendition EPUB, chained Palm database, or unsupported internal XML entity cases fail without replacing the stored book. The editor deliberately refuses a rewrite it cannot verify.

## Verification

Unit tests exercise EPUB 2/3, FB2, MOBI6, standalone KF8/AZW3 and combined files; setting and clearing fields/covers; unchanged content and unrelated records; UTF-16 FB2 with CDATA/namespaces; SVG cover replacement; EPUB font re-keying; native PalmDOC title edits; legacy encoding failures; export byte equality; search refresh; stale-editor rejection; and index-write failure recovery. Personal-data and progress documents are compared byte for byte across an edit.

`Fb2MetadataEntityTest` covers Windows-1251 numeric character references in metadata and body text, supplementary Unicode, literal escaped references, CDATA and invalid entities. Set `FROGREADER_METADATA_TEST_BOOK` to a local FB2 path to additionally verify a full book rename against its original metadata, cover, body text and embedded binaries. This opt-in test writes only temporary output and keeps the original unchanged; no full book is added to the repository.

Owner phone check:

1. Open **Edit** from a book menu, modify title/description and cover, then save.
2. Search for a word unique to the new description; clear the description and confirm that match disappears.
3. Reopen the book and check its cover, text, reading position, quotes and bookmarks.
4. Export a copy and open/import it independently to confirm embedded metadata.
5. Check keyboard, scrolling, Back/discard, and the app’s light/dark themes.
6. Long-press a book in the grid and inside a shelf, open **Info**, edit it, and return to confirm the refreshed title/description/cover. Check the same Info action in the continue-reading menu.
7. Tap the cover in Edit to change/remove/restore it. Check long titles/authors and missing covers.
8. From Info, tap **Share**, choose Telegram or another receiving app, and verify the attachment has the current title, extension and saved contents. Cancelling the chooser must leave the book untouched.

No phone installation or device-visible validation is implied by JVM tests or an APK build.
