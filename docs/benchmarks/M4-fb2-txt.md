# M4 Native TXT/MD and Streaming FB2 Parsers

On 2026-11-18 (Wave 2 completion), M4.1 text/markdown pre-processing
(`text_book.rs`) and M4.2 streaming FB2 encoding detection, sanitization,
structural scanning, and progressive preview publication (`fb2_stream.rs`,
`fb2_txt_jni.rs`) were integrated and wired via `RustFb2TxtNative.kt`,
`TextBookParser.kt`, `Fb2Parser.kt`, and `BookRepository.kt`:

- **M4.1 (`text_book.rs` + `TextBookParser.kt`):** removed the duplicate Kotlin
  fallback functions (`decodeTextKotlin`, `splitTxtParagraphsKotlin`,
  `headingSlugsKotlin`) so `RustFb2TxtNative` is the single production
  implementation for strict BOM + UTF-8/UTF-16LE/UTF-16BE decoding (with
  Windows-1251 fallback for un-BOMed legacy Cyrillic, explicit rejection of
  UTF-32/binary/blank documents, and `\r\n`/`\r`/`\u000c` normalization), TXT
  `\n\n` paragraph splitting, and Unicode-aware deduplicated Markdown heading
  slugs.
- **M4.2 (`fb2_stream.rs` + `Fb2Parser.kt` + `BookRepository.kt`):** XML
  prolog/BOM encoding detection via `encoding_rs`; streaming `Fb2Sanitizer`
  exposed through `RustSanitizedFb2InputStream`; streaming `Fb2StructureScanner`
  / `scan_fb2_structure` via `quick-xml` capturing `<body>`, nested `<section>`,
  `<title>` previews, `estimated_end_elements`, and `<binary>` byte ranges
  without decoding Base64; and progressive preview publication (`onPreview`,
  `previewElementIndex`) wired into `Fb2Parser.parseContent` and enabled for
  `BookFormat.FB2` in `BookRepository.loadContentProgressively` /
  `parseAndCachePinned` (with clean degradation to full parse on sectionless or
  malformed inputs).

## Host verification (Mac OS X aarch64, OpenJDK 21.0.7, Rust 1.90.0)

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | 11/11 M4 Rust tests passed (142/142 workspace total) | `text_book`: 4, `fb2_stream`: 6 (includes `estimated_end_elements`), `fb2_txt_jni`: 1 |
| `cargo clippy ... -- -D warnings` | 0 warnings | Workspace-wide |
| `RustFb2TxtTest` (JVM) | 11/11 passed (`1.932 s`) | Covers BOM/encoding parity, TXT/MD fixtures, legacy Cyrillic FB2 sanitization, structural scan/targeted section parity, `Fb2Parser.parseContent` progressive previews (including deep `previewElementIndex`), `BookRepository.loadContentProgressively` for FB2, and clean sectionless degradation |
| `FormatParityTest`, `Fb2BidiTest`, `RealBooksTest` | Passed (`FormatParityTest`: 1/1, `Fb2BidiTest`: 1/1, `RealBooksTest`: 9/12 passed, 3 skipped for absent private books) | Includes `FrogCompare.fb2`, `Tolkien_LOTR1_uk.fb2`, `King_-Tyomnaya-Bashnya-...fb2`, and `text-formats/` |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`49,341,627 B`) | All 12 `RustFb2TxtNative` JNI symbols exported across all 4 ABIs (`16384` `PT_LOAD` alignment); preserved via `@Keep` and `rules.keep` |

## Wave 3 advances (2026-11-19)

- **M4.1 Direct `.frb` v2 (revision 5) writer for TXT (`text_book.rs`, `fb2_txt_jni.rs`,
  `RustFb2TxtNative.kt`, `TextBookParser.kt`):** implemented `write_txt_frb`,
  `detect_language_from_paragraphs` (matching `LanguageTag.detectFromChapters`), and native
  revision-5 binary encoders (`FrbBlockEncoder`, `encode_txt_book_index_frb`,
  `encode_txt_book_globals_frb`, `encode_txt_chapter_frb` with 4096-entry short-string dictionary)
  writing complete CRC-checked `.frb` v2 containers directly via `crate::frb::Writer`, exposed via
  `@Keep external fun writeTxtFrb(...)` and `TextBookParser.writeTxtFrb(...)`.
- **M4.2 Native streaming FB2 metadata & cover parser (`fb2_stream.rs`, `fb2_txt_jni.rs`,
  `RustFb2TxtNative.kt`, `Fb2Parser.kt`):** implemented `Fb2MetadataParser`, `parse_fb2_metadata`,
  `decode_mime_base64`, and `FB2M` wire encoding running `quick-xml` over `Fb2Sanitizer` in one
  streaming pass to extract `<title-info>`, `<publish-info>`, and `<custom-info>` metadata and
  Base64-decode only the matching `<binary id="coverId">` (bounded by `maxCoverBytes`), fast-skipping
  `<body>` and unrelated `<binary>` blocks and stopping early once done. Wired `Fb2Parser.parseMetadata`
  to stream via `RustFb2TxtNative` (`openMetadataParse`/`feedMetadataParse`/`finishMetadataParse`/
  `closeMetadataParse`/`parseFb2Metadata`) and removed legacy Kotlin helpers (`parseMetadataWith`,
  `MetadataResult`, `collectAnnotationText`, `recoverCoverViaScan`, `WHITESPACE`).

### Wave 3 host verification (Mac OS X aarch64, OpenJDK 21.0.7, Rust 1.90.0)

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | 14/14 M4 Rust tests passed (146/146 workspace total) | `text_book`: 5 (includes `write_txt_frb_produces_valid_frb_v2_revision_5_container`), `fb2_stream`: 8 (includes 2 streaming metadata/cover tests), `fb2_txt_jni`: 1 |
| `cargo clippy ... -- -D warnings` | 0 warnings | Workspace-wide |
| `RustFb2TxtTest` (JVM) | 13/13 passed (`0.594 s`) | Added byte-for-byte `writeTxtFrb` Block 0/1/2 + `ChunkedContentCache`/`FrbModelCodec` parity and streaming FB2 metadata/cover + early-stop + `maxCoverBytes` tests |
| Full `./gradlew test` | 2,327 passed, 8 skipped, 0 failures | `:app`: 2,289 passed / 8 skipped (299 suites); `:pdf-viewer`: 38/38 (7 suites) |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`49,571,003 B`) | All **18** `RustFb2TxtNative` JNI symbols (93 total workspace JNI symbols, no `panicProbe`) exported across all 4 ABIs (`16384` `PT_LOAD` alignment) and retained in R8 `seeds.txt` |

### Wave 4 host verification (2026-11-22)

- **M4.1 Native Markdown parser & HTML renderer (`text_book.rs`, `fb2_txt_jni.rs`,
  `RustFb2TxtNative.kt`, `TextBookParser.kt`):** implemented `mod markdown`
  (`render_markdown_html`, `render_markdown_html_wire`) and `@Keep external fun renderMarkdownHtml(bytes: ByteArray): ByteArray?`,
  updated `TextBookParser.markdownBody` to render HTML natively and parse via
  `RustChapterMarkup.parse(..., htmlOnly = true)`, and removed `org.commonmark.*` imports/fields
  and post-render heading slug/image DOM mutations.

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | `154/154` passed | Includes `text_book::tests` Markdown, GFM table, URL/HTML sanitization, slug, and `MAX_MARKDOWN_DEPTH = 128` coverage |
| `cargo clippy ... -- -D warnings` | `0` warnings | Workspace-wide |
| Full `./gradlew test` | `2,338` passed, `8` skipped, `0` failures | `:app`: `2,300` passed / `8` skipped (`300` suites); `:pdf-viewer`: `38/38` (`7` suites) |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`50,013,371 B`) | `98` JNI symbols across all 4 ABIs (including `renderMarkdownHtml`, `0` `panicProbe`, `16384` `PT_LOAD` alignment) retained in R8 `seeds.txt` |

### Wave 5 host verification (2026-11-22)

- **M4.1b Single-JNI native Markdown metadata & markup (`text_book.rs`, `chapter_markup_jni.rs`,
  `fb2_txt_jni.rs`, `RustFb2TxtNative.kt`, `TextBookParser.kt`):** exposed `chapter_markup_jni::chapter_bytes`,
  added `markdown::extract_first_heading_title`, `parse_markdown_title_wire`, and `parse_markdown_markup_wire`
  (returning `MRK2` bytes or empty 0-byte payload for blank HTML wrapped in `TXT1` + `STATUS_OK`), exported
  `@Keep external fun parseMarkdownTitle(bytes: ByteArray): ByteArray?` and
  `@Keep external fun parseMarkdownMarkup(bytes: ByteArray): ByteArray?`, and switched `TextBookParser`
  `BookFormat.MD` `parseMetadata` / `parseContent` to single-JNI `parseMarkdownTitle` and
  `parseMarkdownMarkup` + `RustChapterMarkup.decodeSnapshot(mrk2Bytes)` (removing `markdownBody` and
  unused `Jsoup` import).
- **M4.2 Direct TXT `.frb` cache fast path & legacy FB2 fallback cleanup (`ChunkedContentCache.kt`,
  `BookRepository.kt`, `Fb2Parser.kt`):** added `ChunkedContentCache.saveTxtDirectly` calling
  `TextBookParser.writeTxtFrb` under `writeLock` with `sourceIdentity` check, atomic commit to
  `content.frb`, and removal of stale `opening.frb`; wired `BookRepository.scheduleContentCacheWrite`
  to use `saveTxtDirectly` first for complete `BookFormat.TXT` books; and removed dead legacy Kotlin
  FB2 sanitizer fallback (`sanitizedText`, `invalidXmlChars`, `htmlEntities`, `bareAmpersand`) in `Fb2Parser.kt`.

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | `157/157` passed | `text_book`: `10/10` (includes `parse_markdown_title_wire` and `parse_markdown_markup_wire` parity & guardrail tests) |
| `cargo clippy ... -- -D warnings` | `0` warnings | Workspace-wide |
| Full `./gradlew test` | `2,341` passed, `8` skipped, `0` failures | `:app`: `2,303` passed / `8` skipped (`300` suites); `:pdf-viewer`: `38/38` (`7` suites) |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`50,029,755 B`) | `100` JNI symbols across all 4 ABIs (including `parseMarkdownTitle` and `parseMarkdownMarkup`, `0` `panicProbe`, `16384` `PT_LOAD` alignment) retained in R8 `seeds.txt` |

### Wave 6 host verification (2026-11-22)

- **M4.3 Native FB2 MIME Base64 resource decoding (`fb2_stream.rs`, `fb2_txt_jni.rs`,
  `RustFb2TxtNative.kt`, `Fb2Parser.kt`):** exposed `fb2_stream::decode_mime_base64` via
  `@Keep external fun decodeMimeBase64(bytes: ByteArray): ByteArray?`, wired
  `Fb2Parser.extractAndResolveTargetedImages` and `decodeBase64` directly from `ByteArray`
  without UTF-8 `String` conversion, and deleted `AsciiCharSequenceInputStream` and
  `java.util.Base64`.
- **M4.2b Cold-open indexed `.frb` fast path for TXT (`BookRepository.kt`):** updated
  `BookRepository.openIndexedContent` so cold `BookFormat.TXT` opens build `content.frb` on
  demand via `saveTxtDirectly` and open immediately via `openIndexed`.

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | `159/159` passed | Includes `fb2_stream` Base64 and `text_book` TXT/MD parity tests |
| `cargo clippy ... -- -D warnings` | `0` warnings | Workspace-wide |
| Full `./gradlew test` | `2,345` passed, `8` skipped, `0` failures | `:app`: `2,307` passed / `8` skipped (`300` suites); `:pdf-viewer`: `38/38` (`7` suites); includes `RustFb2TxtTest`, `Fb2ParserTest`, `IndexedContentCacheTest`, `BookRepositoryTest` |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`50,029,755 B`) | `101` JNI symbols across all 4 ABIs (including `decodeMimeBase64`, `0` `panicProbe`, `16384` `PT_LOAD` alignment) retained in R8 `seeds.txt` |

### Wave 7 host & device verification (2026-11-22)

- **M4.1c Cold-open indexed `.frb` fast path for Markdown (`ChunkedContentCache.kt`, `BookRepository.kt`):**
  added `ChunkedContentCache.saveMdDirectly` & `hasValidCompleteEntry`, and wired cold-open `BookFormat.MD`
  books in `BookRepository.openIndexedContent` directly into `IndexedBookSession` via `content.frb`.
- **Google Pixel 9a (`58071JEBF07361`) on-device verification (`knitipka.frogreader.rust`):**
  `ExampleInstrumentedTest`, `LargeBookOpeningTest`, `ChunkedCacheDeviceTest`, `IndexedCacheDeviceTest`,
  and `IndexedPaginationPassTest` passed **10/10**.

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | `160/160` passed | Full Rust workspace suite |
| `cargo clippy ... -- -D warnings` | `0` warnings | Workspace-wide |
| Full `./gradlew test` | `2,350` passed, `8` skipped, `0` failures | `:app`: `2,312` passed / `8` skipped (`300` suites); `:pdf-viewer`: `38/38` (`7` suites) |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`50,029,755 B`) | `101` JNI symbols across all 4 ABIs (`0` `panicProbe`, `16384` `PT_LOAD` alignment); `app-debug.apk` `158,809,873 B`, `app-debug-androidTest.apk` `2,692,852 B` |
| Pixel 9a (`58071JEBF07361`) instrumented tests | `10/10` passed | `ExampleInstrumentedTest`, `LargeBookOpeningTest`, `ChunkedCacheDeviceTest`, `IndexedCacheDeviceTest`, `IndexedPaginationPassTest` |

### Wave 10 host & device verification (`BETA 2 (wave 10)`, `code=10`)

- **Universal native Rust FRB v2 binary model encoder (`rust/frogreader-core/src/frb_model.rs`):**
  matches Kotlin `FrbModelCodec.encodeToBytes` bit-for-bit across all 6 `ContentElement` variants and
  all 5 `@InternedFrbStyle` tables (`Chapter` `1574` B CRC-32 `0xd8c8065f`, `BookIndex` `550` B
  CRC-32 `0x80a08724`, `BookGlobals` `480` B CRC-32 `0x86ae2c17`, `BookContent` `1950` B CRC-32
  `0x8bcbb83e`).
- **Full native Rust FB2 body parser (`rust/frogreader-core/src/fb2_body.rs`, 5,648 lines) & direct
  `.frb` emission:** replaced 1,914 lines of legacy Kotlin `XmlPullParser` body parsing in
  `Fb2Parser.kt` (`0` `XmlPullParser` references remaining; `Fb2Parser.kt` reduced to 621 lines),
  and wired direct zero-JVM-allocation `.frb` emission (`Fb2Parser.writeFb2Frb` →
  `ChunkedContentCache.saveFb2Directly` → `BookRepository.openIndexedContent` cold open for
  `BookFormat.FB2`), alongside `parseFb2ContentFile` and single-pass Rust preview/finish
  (`startFb2ContentWithPreview` / `finishFb2ContentParse` / `closeFb2ContentParse`).
- **Production `org.commonmark` removal (`app/build.gradle.kts`):** moved `libs.commonmark`,
  `libs.commonmark.tables`, and `libs.commonmark.strikethrough` from `implementation` to
  `testImplementation` (`0` `commonmark` classes packaged in `app-release.apk`).

#### Host & Release APK verification

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | `179/179` passed | `0` failed, `0` ignored (`16` `fb2` tests, `9` `frb_model` tests) |
| `cargo clippy ... -- -D warnings` | `0` warnings | Workspace-wide |
| Full `./gradlew test` | `2,376` passed, `8` skipped, `0` failed (`308` suites) | `:app`: `2,332` passed / `8` skipped (`300` suites); `:pdf-viewer`: `44/44` (`8` suites) |
| Signed R8 Release APK (`-PrustEngineApp=true`, `BETA 2 (wave 10)`, `code=10`) | Built and verified (`51,989,936 B`) | `109` JNI symbols across all 4 ABIs (`0` `panicProbe`, `16384` `0x4000` `PT_LOAD` alignment); `0` `commonmark` classes in `classes.dex` |

#### Real Google Pixel 9a (`tegu`, ARM64, `58071JEBF07361`) Wave 10 FB2 benchmark (`RustEngineDeviceBenchmarkTest`, ~512 KB FB2)

| Metric | Wave 8 (Kotlin `XmlPullParser`) | Wave 10 (Rust `fb2_body.rs`) | Speedup / Notes |
| --- | --- | --- | --- |
| `fb2_parse_ms` | `203.47 ms` | **`43.08 ms`** | **`4.72x` faster** |
| `fb2_direct_frb_ms` | — | **`32.87 ms`** | Full Rust parse + PNG extraction + `.frb` write + `openIndexed` (zero JVM `BookContent` allocation; **`6.19x` faster** than Wave 8 Kotlin parse alone) |
| `fb2_meta_ms` | — | `4.87 ms` | Streaming native metadata & cover parse |
| `fb2_scan_ms` | — | `5.47 ms` | Structural `<section>` / `<binary>` scan |
| `fb2_base64_ms` | — | `0.211 ms` | Native MIME Base64 decode |

### Wave 12 Pixel 9a Markdown GFM-table & FB2 direct FRB verification

Measured on Google Pixel 9a (`tegu`, ARM64, `58071JEBF07361`) via `RustEngineDeviceBenchmarkTest`:

| Metric | Wave 11 (Fallback) | Wave 12 (Direct Rust) | Speedup / Notes |
| --- | ---: | ---: | --- |
| `md_parse_ms` | `498.77 ms` | **`99.30 ms`** | **`5.02×` faster** |
| `md_direct_frb_ms` | `635.04 ms` | **`85.96 ms`** | **`7.39×` faster** (direct Rust `.frb` including GFM tables) |
| `txt_direct_frb_ms` | — | `19.78 ms` | Direct Rust TXT `.frb` |
| `fb2_direct_frb_ms` | — | `33.52 ms` | Direct Rust FB2 `.frb` |
| `fb2_parse_ms` | — | `55.24 ms` | Native FB2 body parse |
| `md_meta_ms` | — | `15.74 ms` | Native Markdown metadata parse |

## Wave 20 Pixel 9a device benchmark (BETA 2 (wave 20), code=20)

Measured on Google Pixel 9a (`58071JEBF07361`, Android 16 / API 36, `tegu`) via `RustEngineDeviceBenchmarkTest` (**3/3 PASSED**, `8.388 s`):

| Suite | Metric | Pixel 9a Result |
| --- | --- | ---: |
| `m4_txt_md` | `txt_direct_frb_ms` | **`22.878 ms`** |
| `m4_txt_md` | `md_meta_ms` | **`18.615 ms`** |
| `m4_txt_md` | `md_parse_ms` | **`105.685 ms`** |
| `m4_txt_md` | `md_direct_frb_ms` | **`82.085 ms`** |
| `m4_fb2` | `fb2_meta_ms` | **`6.273 ms`** |
| `m4_fb2` | `fb2_scan_ms` | **`7.392 ms`** |
| `m4_fb2` | `fb2_base64_ms` | **`0.264 ms`** |
| `m4_fb2` | `fb2_parse_ms` | **`40.177 ms`** |
| `m4_fb2` | `fb2_direct_frb_ms` | **`28.672 ms`** |

## Remaining scope

- Direct native Rust `.frb` emission is now complete for **TXT**, **MD** (including GFM tables), and **FB2**, with on-device Pixel 9a verification completed across all three formats.
