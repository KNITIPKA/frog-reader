# Native chapter markup

Rust decodes chapter bytes and builds the document tree. The XHTML and
DTBook path uses `quick-xml` to retain authored namespaces, qualified
names, CDATA, and self-closing elements. `html5ever` handles HTML recovery
and markup decoded by the MOBI6 container. EPUB cold opening, targeted
windows, full parsing, and KF8 use the same chapter entry point.

The Kotlin mapper consumes the transferred tree. Mapping book blocks
directly from the Rust tree is the next part of M6.2a. The derived model
generation is 8, so an older book's content cache is rebuilt from its
source when used.

On the Mac, both normalized publication digests matched the preceding
parser. Chapters opened directly at 0% and 85% also matched the full parse.

| Book | Chapters | Elements | Model digest prefix | Full source parse |
| --- | ---: | ---: | --- | ---: |
| Hebrew Bible | 122 | 44,446 | `2b3ef7b8f6e4` | 1.440 s |
| Re:Zero | 677 | 190,851 | `0f2931a3dc57` | 1.736 s |

Each row is one host parse. The final benchmark also compared every EPUB
source range in the parsed result with the stored light index, including
677 ranges in Re:Zero and 1,028 in Hebrew Bible.

The Rust suite passed 87 tests and Clippy. EPUB, DTBook, MOBI6, KF8 and
author-fidelity JVM tests passed. The new chapter tests cover a UTF-16
XHTML chapter with inline CSS in CDATA, namespaced attributes, entities
and a self-closing SVG path. They also cover HTML recovery with raw CSS
and a base URL.

Native parser tests also cover entity ambiguity, malformed XML and HTML,
fostered table content, template reparenting, deep trees, and the byte
budget. They caught and corrected a BOM offset issue in the parser's
Unicode-text entry point.

The full app JVM suite recorded 2,248 passes and eight skips. Debug,
AndroidTest and signed Release packages built. The Release APK passed its
signature and 16 KiB alignment checks, and all four ABIs contain the
chapter parser JNI entry point.

## Pixel 9a cold opening

The isolated Debug APK passed the cold Pages-mode test on both private
EPUBs. Contents and the seek near 85% worked before exact book coordinates
were available. Both runs kept the same visible passage at the same screen
height across exact-index publication.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 1.385 s | 5.067 s | 36.093 s |
| Re:Zero | 0.981 s | 4.912 s | 38.787 s |

Each row is one run measured from launch. Hebrew Bible also passed an
earlier run whose timing log was empty. Its recorded row is a repeat.
The whole cold parse and exact-index budget remains above the 5–8 s
target. A matched trial series would establish the timing effect of this
parser change.

A generated EPUB passed the final native cache test on Pixel. The model
digest, content signature and page boundaries matched between parsing
and FRB restoration within that build. Cross-build pagination comparison
remains a gate for completing M6.

## Remaining mapping work

Two Mac parses of Re:Zero with Java execution sampling took 1.644 and
1.511 s. Of 90 samples containing `EpubParser`, 68 contained
`HtmlMapper`, six contained `RustChapterMarkup` and 11 contained
`CssResolver`. The sample counts overlap. They identify Kotlin block
mapping as a remaining candidate for migration. Native CPU attribution and
Android phase measurements require separate profiling.

The mapper still materializes Jsoup nodes and binds the reader model. The
next slice must produce block, style and UTF-16 range tables directly from
the native arena, including anchors, notes and resource references. EPUB
navigation still governs splitting and merging spine fragments into logical
chapters.

The native charset decoder handles web encodings and explicit UTF-16/32
BOMs. Unrecognized labels use UTF-8. Rare charset labels available only
through the JVM remain a compatibility gap to check against additional
books.

## Reusing the native tree for CSS matching

EPUB chapters keep their Rust arena alive through mapping. The Rust matcher
now builds its selector nodes from that arena without serializing the
materialized JVM tree back to Rust. It uses element-only preorder, with the
document root first, including when HTML recovery moves nodes. The chapter
owner releases the arena on completion or error. Documents that Kotlin
mutates for FB2 note conversion and selectors requiring Jsoup's absolute
URL attributes use the materialized tree when building selector nodes.

All 103 Rust tests and the JVM suite passed. The Kotlin integration test
checks compound selectors, structural positions and sibling combinators
across interleaved text and comments. Both complete book models and their
source ranges matched the earlier build. One host parse took 1.926 s for
Re:Zero and 1.468 s for Hebrew Bible. These single samples don't establish
the effect on Pixel full loading.

Debug, AndroidTest and signed Release packages built. The Release APK
passed signature and 16 KiB alignment verification and includes the four
new JNI entry points in every ABI.

Both cold Pages-mode tests passed on Pixel 9a using the retained tree.
The first window included Contents. The user could jump via Contents and
the slider before the exact source index was ready, and the passage kept
its screen position after promotion to exact coordinates.

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 1.152 s | 4.612 s | 33.838 s |
| Re:Zero | 0.914 s | 4.657 s | 38.725 s |

These are single runs measured from launch. Full source readiness still
exceeds the cold-open budget. The generated EPUB also matched its full FRB
restore for the model digest, content signature and opening page cuts.

## Native pre-indexed chapter tree (MRK2) and host verification

On 2026-11-18, `chapter_markup.rs`, `chapter_markup_jni.rs`, `RustChapterMarkup.kt`,
`HtmlMapper.kt`, and `EpubParser.kt` upgraded the native markup snapshot to
`MRK2`. Rust precomputes per-element tag/attribute/subtree bitmasks (`TAG_*`,
`ATTR_*`, `SUBTREE_*`), text whitespace compaction flags (`TEXT_HTML_BLANK`,
`TEXT_HAS_CR`, visible whitespace rules matching Jsoup), document capability
flags (`DOC_*`), cached `id`/`src`/`href`/`xlink:href` strings, and summary
element indices (`body`, `book`, `headTitle`, `viewportMeta`, stylesheets).
`PreindexedElement` and `PreindexedTextNode` invalidate cached metadata on
post-parse DOM rewrites and isolate cloned subtrees.

### Host results (2026-11-18, Mac OS X aarch64, OpenJDK 21.0.7)

| Benchmark / Corpus | Metric | Observed Value |
| --- | --- | ---: |
| Deterministic generated EPUB (`ChunkedCacheBenchmarkTest`, 16 ch / 1,552 el, 23,847 B) | `parse` / `frb_save` | 225.668 ms / 104.305 ms |
| Deterministic generated BookContent (`IndexedCacheBenchmarkTest`, 32 ch / 4,096 el) | `index_and_target` 0% / 85% / 100% (p50 / p95) | 0.322 / 0.685, 0.345 / 0.374, 0.330 / 0.385 ms |
| Local EPUB (`Panchin_...epub`, 4,355,010 B, 17 ch / 2,783 el, `ChunkedCacheBenchmarkTest`) | `parse` / `frb_save` | 336.271 ms / 88.514 ms |
| Local EPUB (`Panchin_...epub`, `IndexedCacheBenchmarkTest`) | `source_parse` / `frb_save` | 108.310 ms / 55.307 ms |
| Local EPUB (`Panchin_...epub`, `IndexedCacheBenchmarkTest`) | `index_and_target` 0% / 85% / 100% (p50 / p95) | 4.194 / 4.963, 5.822 / 6.033, 5.388 / 5.798 ms |
| Local EPUB (`Panchin_...epub`, `IndexedCacheBenchmarkTest`, FRB deleted) | `source_index_and_target` 0% / 85% (p50 / p95) | 12.981 / 14.675, 59.280 / 64.378 ms |

`Panchin_...epub` verified 641 EPUB source ranges and normalized publication
SHA-256 `b2b2369e38e2581ee3241ce4257ef7046dc247443dba6a6525586b32785833c8`.
`RustChapterMarkupTest` passed 7/7 parity cases (EPUB XHTML, DTBook XML,
rewritten MOBI6 HTML, and rewritten KF8 XHTML), and `chapter_markup` +
`chapter_markup_jni` passed 8/8 Rust unit tests.

Combined host verification across all M3–M6 modules:
- Rust: 137/137 tests passed (`--locked`), `cargo clippy --all-targets --all-features --locked -- -D warnings` passed with 0 warnings.
- JVM (`./gradlew test`): 2,310/2,318 passed, 8 skipped (optional private book fixtures absent from workspace), 0 failures (`:app`: 2,272 passed / 8 skipped; `:pdf-viewer`: 38 passed).
- Signed R8 Release APK (`:app:assembleRelease -PlegacyReleaseSigning=true -PrustEngineApp=true`): built after updating `app/src/main/keepRules/rules.keep` with explicit keep rules alongside `@Keep`; all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) export all 81 JNI symbols (without `panicProbe`) with 16 KiB ELF `PT_LOAD` alignment and 4096-byte APK `.so` page alignment.

### Pixel 9a verification (`knitipka.frogreader.rust`, 2026-11-18)

All 12 generated/fixture instrumented tests passed on Pixel 9a (`ExampleInstrumentedTest` 1/1, `LargeBookOpeningTest` 3/3, `IndexedPaginationPassTest` 1/1 in 6.306 s; `ChunkedCacheDeviceTest` 2/2 and `IndexedCacheDeviceTest` 1/1 in 15.517 s; `IndexedReaderUiTest` 4/4 in 11.654 s).

`IndexedCacheDeviceTest#fullIndexAndDistantChaptersPreserveSourceModel` (`engineProfileFrbPhases=true`, 5 iterations) passed on both private EPUBs with exact index, navigation, global-table, and target-chapter digests:

| Book | `cold_parse` | `frb_save` | `index_open` p50 / p95 | `target=85` chapter p50 / p95 | `chapter_jni_read` p50 / p95 | `chapter_binary_decode` p50 / p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Hebrew Bible | 25.171 s | 4.318 s | 190.7 / 581.6 ms | 74.0 / 187.1 ms | 1.44 / 1.63 ms | 55.0 / 119.7 ms |
| Re:Zero | 21.135 s | 4.396 s | 81.8 / 119.7 ms | 13.6 / 37.1 ms | 0.15 / 0.16 ms | 8.6 / 8.7 ms |

Cold `PAGES`-mode UI runs (`IndexedReaderUiTest#privateCorpusKeepsColdEpubSeekVisibleThroughExactPromotion`) passed on both private EPUBs with `position_stable=true`:

| Book | First window and TOC | Text near 85% after both gestures | Exact source index and stable text |
| --- | ---: | ---: | ---: |
| Hebrew Bible | 1.020 s | 4.058 s | 23.936 s |
| Re:Zero | 0.849 s | 4.083 s | 21.024 s |

Each row is one run measured from launch. Direct `.frb` emission from Rust and dedicated device benchmarks for MOBI/KF8, FB2/TXT/MD, FRB search throughput, and WOFF/WOFF2 unpacking remain open.

## Pre-indexed inline whitespace compaction (`MRK2` + `HtmlInlineBuilder`)

On 2026-11-18 (Wave 2 completion), `chapter_markup.rs`, `RustChapterMarkup.kt`,
`HtmlMapper.kt`, and `RustChapterMarkupTest.kt` extended `MRK2` `textFlags` with
`TEXT_ALREADY_COLLAPSED_ASCII` (`= TEXT_COMPACT`) and `TEXT_HAS_KOTLIN_NON_WS`
(matching Kotlin `Char.isWhitespace()` / `Character.isSpaceChar`, including
`\u00A0` / `\u202F` and `\u0085` differences from `Character.isWhitespace`).
`HtmlMapper.kt` uses `HtmlInlineBuilder` to skip character-by-character scanning
and redundant substring allocations on pre-collapsed text nodes while preserving
exact block-boundary trimming and `<pre>` whitespace parity.

### Wave 2 host verification (`2026-11-18`, Mac OS X aarch64, OpenJDK 21.0.7)

- `cargo test --workspace --manifest-path rust/Cargo.toml --locked`: **142/142 passed** (`chapter_markup`: 8, `chapter_markup_jni`: 1).
- `cargo clippy --manifest-path rust/Cargo.toml --workspace --all-targets --all-features --locked -- -D warnings`: **0 warnings**.
- `./gradlew test`: **2,320 passed, 8 skipped, 0 failures** (`:app`: 2,282 passed / 8 skipped across 299 suites; `:pdf-viewer`: 38/38 across 7 suites), including `RustChapterMarkupTest` **9/9** (`0.039 s`), `StandardEbooksCorpusTest` **6/6**, `EpubAuthorFidelityTest` **18/18**, `PublisherLayoutMapperTest` **11/11**, `BookStylingTest` **8/8**, `ChunkedCacheBenchmarkTest` (`parse` `80.060 ms`, `frb_save` `39.242 ms`, `frb_full_restore` `20.673 ms` at 0% / `10.832 ms` at 85%), and `IndexedCacheBenchmarkTest` (`index_and_target` p50/p95: `0.236 / 0.496 ms` at 0%, `0.218 / 0.225 ms` at 85%, `0.216 / 0.231 ms` at 100%).
- Signed R8 Release APK (`:app:assembleRelease -PlegacyReleaseSigning=true -PrustEngineApp=true`): built (`49,341,627 B`, `APK Sig Block 42` present), all 4 ABIs export **87** `Java_com_example_frogreader_*` JNI symbols (without `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all 15 `Rust*Native` classes.
- Debug & AndroidTest APKs (`:app:assembleDebug :app:assembleDebugAndroidTest -PrustEngineApp=true`): built cleanly (`app-debug.apk` `158,375,953 B`, `app-debug-androidTest.apk` `2,692,852 B`).
- Wave 2 Pixel 9a verification (`knitipka.frogreader.rust`): all **12/12** core/fixture instrumented tests passed in **30.562 s** (`ExampleInstrumentedTest` 1/1, `LargeBookOpeningTest` 3/3, `IndexedPaginationPassTest` 1/1, `ChunkedCacheDeviceTest` 2/2, `IndexedCacheDeviceTest` 1/1, `IndexedReaderUiTest` 4/4) on the updated `HtmlInlineBuilder` / `MRK2` build. Re-running the private *Re:Zero*/*Hebrew Bible* `PAGES` checks and direct `.frb` block emission from Rust remain open.

## Fast leaf-block direct mapping (`SUBTREE_SIMPLE_LEAF_TEXT`) & Wave 3 verification (2026-11-19)

`chapter_markup.rs`, `RustChapterMarkup.kt`, `HtmlMapper.kt`, `CssResolver.kt`, and
`RustChapterMarkupTest.kt` extended `MRK2` with `SUBTREE_SIMPLE_LEAF_TEXT = 1 << 11` for
single-text-child elements with non-whitespace content (masked out of parent subtree propagation so
ancestors cannot falsely inherit it, with automatic invalidation on DOM/text mutations and `empty()`).
`HtmlMapper.kt` uses fast leaf-block direct mapping in `emitParagraph` (`<p>`) and `emitHeading`
(`<h1..h6>` / promoted title headings) whenever no CSS `::before`/`::after`/`::first-letter`
applies, extracting collapsed/trimmed text directly from the single `PreindexedTextNode` without
inline-builder traversal while preserving 100% identical styles, `text-transform`, anchors, and
publisher box spans. Unused M6.1 private helpers (`percentFrac`, `leadingNumber`, `numberRegex`)
were removed from `CssResolver.kt`.

### Wave 3 host verification (`2026-11-19`, Mac OS X aarch64, OpenJDK 21.0.7, Rust 1.90.0)

- `cargo test --workspace --manifest-path rust/Cargo.toml --locked`: **146/146 passed** (`chapter_markup`: 9, `chapter_markup_jni`: 1).
- `cargo clippy --manifest-path rust/Cargo.toml --workspace --all-targets --all-features --locked -- -D warnings`: **0 warnings**.
- `./gradlew test`: **2,327 passed, 8 skipped, 0 failures** (`:app`: 2,289 passed / 8 skipped across 299 suites; `:pdf-viewer`: 38/38 across 7 suites), including `RustChapterMarkupTest` **10/10** (`0.12 s`), `StandardEbooksCorpusTest` **6/6**, `EpubAuthorFidelityTest` **18/18**, `PublisherLayoutMapperTest` **11/11**, and `BookStylingTest` **8/8**.
- Signed R8 Release APK (`:app:assembleRelease -PlegacyReleaseSigning=true -PrustEngineApp=true`): built (`49,571,003 B`, `APK Sig Block 42` present), all 4 ABIs export **93** `Java_com_example_frogreader_*` JNI symbols (without `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all 15 `Rust*Native` classes.
- Debug & AndroidTest APKs (`:app:assembleDebug :app:assembleDebugAndroidTest -PrustEngineApp=true`): `app-debug.apk` (`158,519,849 B`), `app-debug-androidTest.apk` (`2,692,852 B`).

### Wave 3 Pixel 9a verification (`knitipka.frogreader.rust`, 2026-11-19)

- All **15/15** generated/fixture instrumented tests passed (`ExampleInstrumentedTest` 1/1, `LargeBookOpeningTest` 3/3, `IndexedPaginationPassTest` 2/2 in `6.837 s`; `ChunkedCacheDeviceTest` 3/3 and `IndexedCacheDeviceTest` 1/1 in `21.409 s`; `IndexedReaderUiTest` 5/5 fixtures in `13.619 s`).
- `ChunkedCacheDeviceTest#exportCrossBuildPaginationGeometry` (`enginePaginationSignaturesOnly=true`) passed on both private EPUBs with exact content signatures:

| Book | Runtime | `source_sha256` (prefix) | `content_signature` | `contentDigest` (prefix) | `pages.bin` SHA-256 (prefix) |
| --- | ---: | --- | --- | --- | --- |
| Hebrew Bible | 40.922 s | `a8fbb21090c8…` | `e1119692a0b3a4d5d78b972c8e7e6720a840a6bd9f4a3bc3afe5dde41c21f516` | `ddd499caebca…` | `632a56801eee…` (514 B) |
| Re:Zero | 47.548 s | `143f9ecc487e…` | `c46cecb31fb864cb894d977ae06c4636b7d302c93ae81265a880c824d4b7a629` | `f989b45015e7…` | `771a4b03a498…` (514 B) |

  *Re:Zero*'s `content_signature` (`c46cecb31fb864cb894d977ae06c4636b7d302c93ae81265a880c824d4b7a629`) matches the original M1 Kotlin/Jsoup baseline in [M1-device.md](M1-device.md) bit for bit across all 677 chapters / 190,851 elements. Direct `.frb` emission from Rust without Kotlin `HtmlMapper` remains open under `M6.2b`.

## Wave 4 host verification (2026-11-22)

- **Cross-chapter CSS retention & `MRK2` flow/leaf fast paths:** `CssResolver.kt` retains
  cross-chapter `sharedComputed` / `RustCssComputed` state; `chapter_markup.rs`,
  `RustChapterMarkup.kt`, and `HtmlMapper.kt` added `ATTR_ANC_HAS_FLOW_SCOPE` (`1 << 20`) for
  O(1) ancestor-walk skipping in `flowAnnotations` and expanded `SUBTREE_SIMPLE_LEAF_TEXT` to
  cover anonymous CSS text-line blocks in `emitParagraph`. `EpubParser.kt` also switched EPUB
  spine chapter parsing to direct `openFromZip` / `parseFromZip` in native memory.
- **Rust workspace:** `154/154` passed (`--locked`), `cargo clippy --all-targets --all-features --locked -- -D warnings`: `0` warnings.
- **JVM suite (`./gradlew test`):** `2,338` passed, `8` skipped, `0` failures across `307` suites (`:app`: `2,300` passed / `8` skipped across `300` suites; `:pdf-viewer`: `38/38` across `7` suites).
- **Host benchmarks:**
  - `ChunkedCacheBenchmarkTest` (generated EPUB, 16 ch / 1,552 el, 48,506 B FRB): `parse` `213.502 ms`, `frb_save` `90.212 ms`, `frb_full_restore` `42.528 ms` (0%) / `13.187 ms` (85%).
  - `IndexedCacheBenchmarkTest` (generated `BookContent`, 32 ch / 4,096 el, 40,538 B FRB): `frb_save` `16.018 ms`; `index_and_target` p50 / p95 `0.393 / 0.844 ms` (0%), `0.343 / 0.508 ms` (85%), `0.299 / 0.323 ms` (100%).
- **Signed R8 Release APK (`-PrustEngineApp=true`):** built (`50,013,371 B`); all 4 ABIs export `98` JNI symbols (including `writeBlockBytes`, `renderMarkdownHtml`, `parseEncryption`, `parseFromZip`, `openFromZip`; `0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all new JNI entry points.

## Wave 5 host verification (2026-11-22)

- **M6.2c Pre-indexed CSS flow block & explicit floated initial fast paths (`chapter_markup.rs`,
  `RustChapterMarkup.kt`, `CssResolver.kt`, `HtmlMapper.kt`):**
  - Added `SUBTREE_HAS_INLINE_FLOW_CANDIDATE` (`1 << 12`) in `chapter_markup.rs` (`Preindex::build`,
    set when `(tag_flags & (TAG_PUBLISHER_BOX | TAG_NON_CONTENT | TAG_ROOT)) == 0` and propagated
    up `subtree_flags`) and `RustChapterMarkup.kt`, plus `RustChapterMarkup.decodeSnapshot(wire)`.
  - Added `CssResolver.hasDisplayRules` (checking `display` / `--*` rules) and O(1) / subtree-pruned
    fast paths in `HtmlMapper.indexCssBlockContainers` (early exit on `!SUBTREE_HAS_INLINE_FLOW_CANDIDATE`
    or `!css.hasDisplayRules && attrValid(MUTATED_STYLE) && !SUBTREE_HAS_STYLE`, plus per-child stack
    pruning) and `HtmlMapper.explicitFloatedInitial` (O(1) `null` return on `!SUBTREE_HAS_INLINE_FLOW_CANDIDATE`).
- **Rust workspace:** `157/157` passed (`--locked`, `chapter_markup`: `13/13` including `preindex_classifies_subtree_inline_flow_candidate`), `cargo clippy --all-targets --all-features --locked -- -D warnings`: `0` warnings.
- **JVM suite (`./gradlew test`):** `2,341` passed, `8` skipped, `0` failures across `307` suites (`:app`: `2,303` passed / `8` skipped across `300` suites; `:pdf-viewer`: `38/38` across `7` suites).
- **Signed R8 Release APK (`-PrustEngineApp=true`):** built (`50,029,755 B`); all 4 ABIs export `100` JNI symbols (including `parseMarkdownTitle`, `parseMarkdownMarkup`; `0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all new JNI entry points.

## Wave 6 host verification (2026-11-22)

- **M6.1b / M6.2d Pre-indexed presentational attributes, zero-allocation CSS cascade winner cache & horizontal-row fast exit (`chapter_markup.rs`, `RustChapterMarkup.kt`, `CssResolver.kt`, `HtmlMapper.kt`):**
  - Added `ATTR_HAS_PRESENTATIONAL` (`1 << 24`, covering `align`, `color`, `text`, `bgcolor`) and `MUTATED_PRESENTATIONAL` in `chapter_markup.rs` and `RustChapterMarkup.kt`.
  - Updated `CssResolver` with a preindexed attribute fast path skipping Jsoup `attr(...)` scans for `style`/`dir`/`align`/`color`/`text`/`bgcolor` when the corresponding flags are unset, and added a bounded `cascadeWinnerCache` eliminating per-element `LinkedHashMap` and `ArrayList<Pair>` allocations on `rules` cascades.
  - Added an O(1) fast exit in `HtmlMapper.emitCssHorizontalRow` when `!hasDisplayRules` and `!ATTR_HAS_STYLE`.
- **Rust workspace:** `159/159` passed (`--locked`, includes `preindex_classifies_presentational_attributes`), `cargo clippy --all-targets --all-features --locked -- -D warnings`: `0` warnings.
- **JVM suite (`./gradlew test`):** `2,345` passed, `8` skipped, `0` failures across `307` suites (`:app`: `2,307` passed / `8` skipped across `300` suites; `:pdf-viewer`: `38/38` across `7` suites).
- **Signed R8 Release APK (`-PrustEngineApp=true`):** built (`50,029,755 B`); all 4 ABIs export `101` JNI symbols (including `decodeMimeBase64`; `0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all JNI entry points.

## Wave 7 host & device verification (2026-11-22)

- **M6.1c / M6.2e `Computed` Box/Typography Caching, `DOC_HAS_ANY_PRESENTATIONAL` & `blockStyleFor` Fast Paths (`chapter_markup.rs`, `RustChapterMarkup.kt`, `CssResolver.kt`, `HtmlMapper.kt`):**
  - Added `DOC_HAS_ANY_PRESENTATIONAL = 1 << 9` in `chapter_markup.rs` and `RustChapterMarkup.kt` for O(1) presentational-attribute short-circuits in `HtmlMapper` (`attributeAlign`, `legacyForeground`, `legacyVisualBackground`, `legacyInlineStyle`).
  - Cached `encodedFontTypography` and `standardBoxStyle` on `CssResolver.Computed`, eliminating per-paragraph `PublisherBoxStyle` and `fontTypography.encode()` allocations across all 190,851 `<p>` elements.
  - Added `CssResolver.hasPageBreakBeforeRules` & `hasFlexCenteringRules`, and fast paths in `HtmlMapper.blockStyleFor` and empty-container `minimumHeight` resolution.
- **Rust workspace:** `160/160` passed (`--locked`), `cargo clippy --all-targets --all-features --locked -- -D warnings`: `0` warnings.
- **JVM suite (`./gradlew test`):** `2,350` passed, `8` skipped, `0` failures across `307` suites (`:app`: `2,312` passed / `8` skipped across `300` suites; `:pdf-viewer`: `38/38` across `7` suites).
- **APKs (`-PlegacyReleaseSigning=true -PrustEngineApp=true`):** `app-release.apk` (`50,029,755 B`), `app-debug.apk` (`158,809,873 B`), `app-debug-androidTest.apk` (`2,692,852 B`); all 4 ABIs export `101` JNI symbols (`0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment.
- **Google Pixel 9a (`58071JEBF07361`) on-device verification (`knitipka.frogreader.rust`):** `ExampleInstrumentedTest`, `LargeBookOpeningTest`, `ChunkedCacheDeviceTest`, `IndexedCacheDeviceTest`, and `IndexedPaginationPassTest` passed **10/10**.

## Waves 11–13: Native Rust CSS/HTML/EPUB -> FRB Breakthrough (M6.2b, BETA 2 wave 13)

### Architecture delivered
- **`css_engine.rs`, `html_mapper.rs` + `mathml.rs`:** complete native CSS cascade & HTML/XHTML mapper covering all 6 complex HTML/CSS emitters (`<table>` + responsive `@media(max-width)`, CSS horizontal rows, inline text boxes, `<ruby>`, block/inline `<svg>` with base64 `<image>` embedding & `SharedInlineSvgWriter`, and block/inline `<math>` + `MathMlNativeParser`).
- **`epub_frb.rs`:** bounded sliding-window direct EPUB-to-FRB v2 writer (`SLIDING_WINDOW_SIZE=8`) with RAII chapter spool and `frb::Writer::write_precompressed_block`, WOFF2 decoding via `@Keep @JvmStatic RustEpubPackage.decodeWoff2`, standalone SVG spine items, and DTBook support.
- **Boxed `frb_model.rs`:** boxed heap-allocated sub-structs reducing in-memory sizes across `FrbContentElement` (`728 B -> 72 B`), `FrbTextRange` (`896 B -> 72 B`), `FrbTextAnnotation` (`864 B -> 40 B`), and `FrbTableCell` (`1448 B -> 96 B`).

### Host verification (`BETA 2 (wave 13)`, `code=13`)
- **Rust (`cargo test` / `clippy`):** `229/229` passed (`0` clippy warnings).
- **Gradle (`./gradlew test`):** `2,389` passed, `8` skipped, `0` failed (`:app`: `2,345` passed / `8` skipped; `:pdf-viewer`: `44/44`).
- **APKs (`-PlegacyReleaseSigning=true -PrustEngineApp=true`):** `app-release.apk` (`55,841,484 B`), `app-debug.apk` (`154,035,357 B`), `app-debug-androidTest.apk` (`3,314,780 B`).
- **JNI & R8 checks:** `113` JNI symbols per ABI (`0` `panicProbe`, `0x4000` `PT_LOAD`, `decodeWoff2` / `parseEpubContentNative` / `writeEpubFrbNative` retained in `seeds.txt`, `0` `org/commonmark` classes in `app-release.apk`).

### Pixel 9a (`tegu`, ARM64, `58071JEBF07361`, Debug APK `knitipka.frogreader.rust`) Before / After

| Book | Stage / Build | Time | JVM Peak | Native Peak | Notes |
| --- | --- | ---: | ---: | ---: | --- |
| **Re:Zero** (`677` ch, `190,851` el, `9.65 MB`) | Wave 7 Kotlin (`cold_parse + frb_save`) | `12,942 + 4,362 = 17,304 ms` | `241 MB` | — | Baseline Kotlin mapper + FRB save |
| **Re:Zero** | Wave 9 Kotlin (`cold_parse + frb_save`) | `8,000 + 1,824 = 9,824 ms` | `268 MB` | — | Pre-indexed `MRK2` Kotlin optimizations |
| **Re:Zero** | Wave 12 Direct Rust FRB | `1,904.98 ms` | `5.8 MB` | `35.6 MB` | `entry_bytes = 18,397,444 B` (byte-identical) |
| **Re:Zero** | **Wave 13 Direct Rust FRB (boxed `frb_model`)** | **`1,993.94 ms`** | **`5.8 MB`** | **`19.3 MB`** | **8.7–9.1× faster than Wave 7**; **41.5× less** JVM peak; **43.6× down** from Wave 11's `841.4 MB` native peak; `entry_bytes = 18,397,444 B` (**100% byte-identical**, exact `index_digest`, `navigation_digest`, `globals_digest`) |
| **Hebrew Bible** (`122` ch, `44,446` el, `11,972` notes, `40,558` linkTargets, `33.2 MB`) | Wave 7 Kotlin (`cold_parse + frb_save`) | `17,814 + 3,712 = 21,526 ms` | — | — | Baseline Kotlin mapper + FRB save |
| **Hebrew Bible** | Wave 9 Kotlin (`cold_parse + frb_save`) | `8,457 + 3,415 = 11,872 ms` | — | — | Pre-indexed `MRK2` Kotlin optimizations |
| **Hebrew Bible** | Wave 12 Direct Rust FRB | `6,470.71 ms` | `14.4 MB` | `517.2 MB` | Direct Rust `.frb` emission |
| **Hebrew Bible** | **Wave 13 Direct Rust FRB (boxed `frb_model`)** | **`5,454.32 ms`** | **`14.4 MB`** | **`155.7 MB`** | **3.95× faster than Wave 7**; **3.32× reduction** in native peak vs Wave 12 |

- **Rust vs Kotlin full parity check (`compareDirectRustVsKotlinEpubParity` on `hebrew-bible.epub`):** `122/122` chapters (`0` differing), `11,972/11,972` notes (`0` differing), `40,558/40,558` linkTargets (`0` diffs), `1,028/1,028` epubSourceRanges (`0` diffs), `31/31` resourcePaths (`0` diffs), `navigation`/`language`/`pageProgression`/`totalElements`/`publisherStyle` 100% match (only JSON map key order `BTreeMap` vs `LinkedHashMap` differed).
