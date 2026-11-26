# Native EPUB package and navigation

FrogReader reads the ZIP container, `container.xml`, OPF, NCX and NAV
through Rust in the import, full content and targeted content paths. The
package reader returns ordered manifest, metadata and spine fields. The
navigation readers return ordered links, labels and depth. Kotlin resolves
these fields into the reader model and still maps chapter XHTML and CSS.

On the Mac, `IndexedCacheBenchmarkTest` ran before and after the migration
against the same two local EPUBs. Its publication digest covers every chapter
and the shared model, including navigation, notes, linked documents and
publisher layout metadata. It normalizes the test's temporary extraction path.
In five trials at each of 0% and 85%, a chapter read directly from the EPUB
also matched the corresponding chapter from the full parse.

| Book | Navigation | Chapters | Elements | Whole publication digest (before and after) | Full source parse before / after |
| --- | --- | ---: | ---: | --- | ---: |
| Re:Zero | NCX | 677 | 190,851 | `0f2931a3dc57…` | 1,554 / 1,509 ms |
| Hebrew Bible | NAV | 122 | 44,446 | `2b3ef7b8f6e4…` | 1,593 / 1,530 ms |

The source parse times are single host observations. They cover the full
parser and include XHTML mapping. A separate NAV comparison matched all 122
Hebrew Bible TOC rows against the former Jsoup path, including raw targets,
labels and depth.

The Rust parser accepts UTF-8 and Unicode BOMs and recovers common malformed
tags. NAV encoding declarations outside these encodings and HTML5 tree
recovery are compatibility gaps in this pass. Thirty-five Rust tests passed,
as did 2,239 JVM tests with eight skipped. The signed R8 APK has all four new
JNI methods and 16 KiB alignment on each ABI. The Pixel was absent from ADB
during this verification.

## First open without a content cache

The reader publishes the EPUB's reading-order navigation and a seekable
approximate percentage with the first mapped source document. The slider
chooses a spine occurrence by its share of the decoded ZIP size. During
preparation, progress is saved using the source occurrence, document element,
character and scroll offset. The exact index translates that anchor into
whole-book coordinates. Cached chapter counts and exact page numbers use the
normal reader paths once their data is available.

An isolated Pixel 9a debug build loaded each book with neither an FRB nor a
retained source index. The test selected an unopened chapter
from Contents, then moved the slider to 85%. Both actions showed text before
FRB publication. It checked that the same text had the same vertical screen
coordinate after the exact source index took over.

| Book | Mode | First source window and TOC | TOC rows | 85% text after both gestures | Exact source index and stable text |
| --- | --- | ---: | ---: | ---: | ---: |
| Hebrew Bible | Scroll | 1.129 s | 122 | 4.412 s | 23.736 s |
| Re:Zero | Scroll | 1.265 s | 677 | 3.734 s | 28.603 s |
| Hebrew Bible | Pages with chapter breaks | 1.210 s | 122 | 4.879 s | 29.940 s |
| Re:Zero | Pages with chapter breaks | 0.899 s | 677 | 4.492 s | 28.729 s |
| Re:Zero | Continuous pages | 0.947 s | 677 | 4.420 s | 29.467 s |

Times start at activity launch. The 85% column includes opening the panels,
the Contents jump and accessibility polling. Each row is one trial. The
continuous test also waited until the promoted local pagination was fully
presented. A separate JVM test checks exact recovery
of the source element, character and scroll offset and rejects position writes
from the previous viewport after publication.

## NAV compatibility (encoding and malformed HTML5)

On 2026-11-18, `rust/frogreader-core/src/epub_nav.rs` closed the remaining
compatibility gaps noted above:
- Non-UTF8 NAV byte payloads (including Windows-1251 and UTF-16) are decoded
  through `chapter_charset::decode`.
- When a NAV document contains HTML5 quirks, void tags, or malformed closures
  that `quick-xml` rejects, `epub_nav` falls back to iterative `html5ever` tree
  walking via `chapter_markup::parse(input, true)`.

Host verification: 4/4 `epub_nav` Rust tests pass under `--locked` with zero
Clippy warnings (`-D warnings`), and the full host JVM + signed release R8 APK
checks pass. Pixel 9a later passed all 12 core/fixture device suites plus the
cold `PAGES` and `IndexedCacheDeviceTest` verification runs on both *Hebrew Bible*
and *Re:Zero* (see [M6-chapter-markup.md](M6-chapter-markup.md#pixel-9a-verification-knitipkafrogreaderrust-2026-11-18)).

## Wave 4 native encryption.xml & direct ZIP-to-Markup (2026-11-22)

- **Native `encryption.xml` parser (`epub_package.rs`, `epub_package_jni.rs`,
  `RustEpubPackageNative.kt`, `EpubParser.kt`):** added `@Keep external fun parseEncryption(bytes: ByteArray): ByteArray?`
  to parse `META-INF/encryption.xml` font-obfuscation/encryption entries natively.
- **Direct ZIP-to-Markup chapter parsing (`epub_zip.rs`, `chapter_markup_jni.rs`,
  `RustChapterMarkup.kt`, `RustEpubArchive.kt`, `EpubParser.kt`):** added `parseFromZip` and
  `openFromZip` to decompress and parse EPUB spine entries directly in Rust native memory without
  copying raw XHTML bytes into JVM `ByteArray` buffers.
- **Host verification:**
  - `cargo test --workspace --manifest-path rust/Cargo.toml --locked`: `154/154` passed; `cargo clippy --all-targets --all-features --locked -- -D warnings`: `0` warnings.
  - Full `./gradlew test`: `2,338` passed, `8` skipped, `0` failures (`307` suites).
  - Signed R8 Release APK (`-PrustEngineApp=true`): built (`50,013,371 B`); all 4 ABIs export `98` JNI symbols (including `parseEncryption`, `parseFromZip`, `openFromZip`, `0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all new JNI methods.
