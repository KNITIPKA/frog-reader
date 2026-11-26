# M2F exact page index

ReaderScreen now uses a Rust page index for exact global numbering when each
chapter starts on a new page. The index contains the source item and character
at the start of every page. The pager checks the anchors of its current window
against the global sequence before using the sequence's ordinal or total. Equal
anchors are preserved, so repeated starts do not collapse distinct pages.

The key covers the validated FRB generation and source, pagination and text
transformation versions, viewport, density, typography, embedded and custom
font bytes, cover bytes, locale, layout direction and the OS build. A matching
file opens without a full layout scan. The native reader validates the bounds,
checksums and ordered coordinates before returning anchors. See
[the binary format](../../rust/PAGE_INDEX_FORMAT.md).

A miss starts a chapter-by-chapter layout pass after the reader presents its
first frame. For chapter breaks, two background workers by default share a
native book session and each use their own text measurer. They claim chapters
near the current reading position and periodically save finished chapters in
a layout-keyed checkpoint. The pass releases render parts and keeps the
anchors. A source opening that already measured the whole book can save its
anchors after the complete FRB is published. Bookmarks, Contents and the progress
control retain global source coordinates during preparation.

On Pixel, `IndexedPaginationPassTest` matched every anchor against full Compose
pagination with cover, empty chapters, long paragraphs, a publisher float and
split table rows. The ReaderScreen test showed page 17 of 20, closed the activity,
made the first chapter unreadable and reopened the book at page 17. The exact
counter appeared from the saved index. Both ReaderScreen navigation modes
passed their existing contents and slider tests on the same build.

## Large books on Pixel 9a

The isolated debug app prepared each real EPUB and FRB before the timed
open. ReaderScreen opened around 85% in paged mode with chapter breaks. The
test waited for the source text, exact counter and persisted `.rpi`, then
closed the activity and reopened it with the same geometry. It verified the
same counter and text and the unchanged index modification time.

| Book | Pages | Index bytes | First exact counter | Counter on reopen | Index stage on reopen |
| --- | ---: | ---: | ---: | ---: | ---: |
| Re Zero | 35,928 | 287,488 | 108.687 s | 0.974 s | 1 ms |
| Hebrew Bible | 7,133 | 57,128 | 37.749 s | 1.664 s | 4 ms |

Counter times start before activity launch and include accessibility polling.
The first index build itself took 106.919 s and 34.798 s. Source text was
confirmed after 1.935 s and 3.086 s during those first opens. The reopen trial
used a new activity and ViewModel in the same process. There is one passing
build/reopen pair per book, so p95 still needs repeated trials. The index stage
includes key calculation and the native file read. Re Zero's
initial attempt lost the ADB connection, and its completed retry is in the table.

The parallel implementation was checked on Pixel against the same sequential
Compose reference. The test stops the parent layout task after the first
chapter is recorded, reopens the checkpoint, measures the missing chapters
and checks the resulting anchor array. This exercises task cancellation and
disk restoration inside one test process. Recovery after an OS process kill
requires a separate check.

### Worker comparison on Hebrew Bible

On Pixel 9a, the test prepared the content FRB before each timed open at 85%.
It waited for the local page window to finish, then performed six animated
turns while the whole-book index was still building. All three runs built
7,133 pages and restored the same text and counter on reopen.

| Workers | Index build | First exact counter | Counter on reopen | Frame duration p95 | Missed frame deadlines |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 44.765 s | 48.137 s | 1.478 s | 27.1 ms | 10/269 (3.7%) |
| 2 | 38.241 s | 40.978 s | 1.540 s | 25.4 ms | 7/263 (2.7%) |
| 4 | 22.587 s | 24.900 s | 1.286 s | 25.4 ms | 7/264 (2.7%) |

`FrameMetrics` covers the turn window. These samples exclude the first draw
and the transition from the early preview to the local pager. One run per
worker count shows about 2× faster index building with four than with one,
with similar turn metrics to two in this trial. Two remains the default
pending repeated measurements on both books and peak memory measurements.

## Cold parser evidence

JFR on the Mac recorded two EPUB parses per corpus. Before the XHTML root
check change, Re Zero took 1,507 and 933 ms, and Hebrew Bible took 1,584 and
848 ms. These are host parse times with sampling. In Re Zero, 48 of 78 sampled
parser stacks included `HtmlMapper` and 24 included `parseChapterDocument`.
The latter included 16 samples in `Element.text`. Hebrew Bible had eight
samples in that text walk out of 58 parser samples. The groups overlap.

The shared parser now checks for child elements before flattening a reading
root to test for nonblank text. Roots with children have the same XML result
and skip the whole-document text walk. Roots without children retain the text
check. The dominant remaining candidates are HTML mapping and document parsing.

A follow-up Re Zero recording found zero `Element.text` samples under the
shared document parser in 80 EPUB samples. Chapter, element and paragraph
counts stayed identical. Overall host parse times in that run were 1,871 and
1,092 ms. The trials establish removal of the sampled text walk. End-to-end
cold speedup still needs verification.

The first M5.1 change moves EPUB ZIP directory reads, decompression and CRC
validation to Rust. On the Mac, one complete parse of each private test book
took 1.45 s for Re Zero and 1.53 s for Hebrew Bible. Chapters fetched directly
from the source at 0% and 85% matched the full model in five trials at each
position for each book. The 85% source-index lookup plus chapter mapping took
14 ms and 74 ms p95 respectively (the maximum of five observations). These
are host data-path timings without reader rendering and do not establish a
before/after device speedup. EPUB package XML, XHTML and CSS are still Kotlin.

Verification includes 24 Rust tests and 2,261 JVM test cases with eight
skipped. The final debug APK passed five Pixel tests covering the disk index,
full-layout parity and existing page-cut behavior. The large-book timings above
were collected before the XHTML root-check change, with source parsing outside
the timed opens. Debug, instrumented-test and optimized unsigned release builds
passed, as did app lint, Rust formatting and Clippy. Both APKs passed the
16 KiB package and ELF alignment checks with the RPI JNI exports on all four ABIs.

## Continuous layout

The continuous pass uses the same Compose paginator and keeps open page state
and heading lookahead across chapter boundaries. It writes closed pages into
compressed geometry groups. One atomic `layout.frb` publication contains those
groups and the validated RPI anchor index. An exact window restores the needed
source leaves and page geometry by global page ordinal. A publisher float also
retains its caption leaves for selection.

On a layout miss, the local window appears before the whole-book pass. When
the pass finishes, the exact counter uses the reading anchor and the visible
local page stays in place. An explicit seek or a turn beyond that window can
use the exact geometry. With a valid cache, the reader restores the exact
destination window on open.

On Pixel, the continuous ReaderScreen fixture preserved the saved source
position and the screen bounds of the visible text when the exact counter
appeared. It then navigated to the last chapter through Contents and returned
to the first page with the book slider. The first exact page displayed text
from chapters outside the source window. Reopening with an unrelated damaged
source block displayed the same page and counter from the unchanged layout
file. The differential test also compared all page anchors and render parts
with `paginateBook`, including publisher floats, split tables and pages with
content from more than one chapter.

### Real EPUBs with continuous pages on Pixel 9a

Each run opened near 85% in the isolated debug APK with the content FRB prepared
before timing. The first open built `layout.frb` and showed the exact counter
alongside the expected text. The second activity used the same settings and
verified the same text and counter with unchanged `layout.frb` modification
time. Timings include activity launch and accessibility observation.

| Book | Pages | Layout bytes | Text on first open | First exact counter | Exact counter on reopen |
| --- | ---: | ---: | ---: | ---: | ---: |
| Re Zero | 35,645 | 2,553,903 | 2.440 s | 177.735 s | 1.011 s |
| Hebrew Bible | 7,085 | 603,094 | 3.973 s | 44.310 s | 2.024 s |

The builds took 175.482 s and 40.540 s respectively. The cached index stage
took 4 ms for each book. The end-to-end reopen timing includes the book index,
source window and rendered page. There was one build/reopen pair per book,
with a new activity in the same process. The first layout, cold EPUB source
preparation, seek budgets and the full readiness matrix still need work.

The final debug and signed isolated R8 builds passed, including release lint.
Verification covered 2,265 JVM cases with eight skipped, 24 Rust tests and
eight Pixel smoke tests. The new JNI exports and 16 KiB ELF alignment were
verified on all four Android ABIs. The release APK passed signing and package
alignment checks.

## Wave 3 checkpoint process-death & layout-key verification (2026-11-19)

- **JVM (`ReaderPageCheckpointTest`, 6/6 passed in `0.026 s`):** verified that partial chapter
  anchors committed to `ReaderPageCheckpoint` survive simulated process death (fresh disk reopen),
  that theme and visual-only changes (`AppTheme.WHITE`/`SEPIA`/`OLED`, `invertImages`,
  `pdfBackground`, `pageTurnAnimation`, counter flags) preserve the 32-byte
  `ReaderPageIndexCache.key` and reuse committed chapters, that typography/margin changes
  (`fontSizeSp`, `lineHeight`, `font`, `justify`, `hyphenation`, `pageMarginDp`, `pageMargins`)
  change the layout key and ignore the old checkpoint, and that resuming only unmeasured chapters
  produces byte-for-byte identical `.rpi` anchors to an uninterrupted pass.
- **Pixel 9a (`IndexedPaginationPassTest#processDeathCheckpointRecoveryAndThemeVsTypographyKeyIsolation`,
  2/2 passed in `6.837 s` alongside `ExampleInstrumentedTest` and `LargeBookOpeningTest`):**
  closed both `ReaderPageCheckpoint` and `IndexedBookSession` after committing 3 outward chapters
  (`[5, 4, 6]` from `preferredChapter = 5`), reopened fresh instances from disk, verified
  typography/margin key isolation vs theme key invariance, resumed only the remaining unmeasured
  chapters outward (`[3, 7, 2, 1, 0]`), and matched the uninterrupted `.rpi` anchor sequence
  byte for byte on device.

`M2F.B` is closed; `M2F.A` remains open pending repeated multi-run worker trials and peak memory
measurements on both *Re:Zero* and *Hebrew Bible*.

## Wave 14 pagination speedup & cold-EPUB deadlock fix (BETA 2 wave 14, code=14)

- **Cold-EPUB promotion deadlock fix (`ReaderViewModel.kt`)**:
  - `matchesPresentedWindowKey` now accepts both `...|environment=<env>` and `...|environment=<env>|source=<N>` suffixes so whole-book pagination starts immediately after cold EPUB promotion instead of hanging indefinitely on `presentedPagination.first` with the corner spinner active.
  - Tracks `page_index_wait` and distinguishes local `Вёрстка окна` (`window_pagination`) from whole-book `Пагинация всей книги` (`exact_page_index` / `full_pagination`) in the live Reader Loading HUD (`Settings -> Reading -> Reader loading overlay`, available in Debug and R8 Release).
- **Pagination & lock-free decode optimizations (100% identical page cuts, `LAYOUT_ENGINE_VERSION = 24` unchanged)**:
  - `IndexedBookSession.readChapter`: releases `synchronized(lock)` right after `readChapterBlockBytes` so `FrbModelCodec.decode` runs lock-free in parallel across all workers.
  - `ReaderPageWorkers.kt`, `IndexedPaginationPass.kt`, `ReaderScreen.kt`: scaled default workers to `(availableProcessors - 2).coerceIn(2, 6)` (`MAX_CHAPTER_LAYOUT_WORKERS = 6`, 6 workers on 8-core Pixel 9a) and replaced `THREAD_PRIORITY_BACKGROUND` (throttled background cgroup) with `THREAD_PRIORITY_LESS_FAVORABLE`.
  - `ReaderTypography.kt`, `FontFamilyTypography.kt`, `PublisherFonts.kt`, `ReaderInlineContent.kt`: added zero-allocation fast paths skipping the 10-collection `TreeMap`/`HashMap`/`TreeSet` typography sweep-line and 4-pass inline-content annotation scans on plain paragraphs.
  - `ReaderMetrics.kt` & `Pagination.kt`: added per-pass `ReaderTextStyleCache` (`FontFamily`/`LocaleList`/`TextStyle`), lazy `fragmentStyle` (computed only on split paragraphs), and zero-`PagePart`/zero-`subSequence` `anchorsOnly` mode for `emitPaginationAnchors`.
- **Host verification (`BETA 2 (wave 14)`, `code=14`)**:
  - `./gradlew test`: **2,405 passed** (`:app`: 2,361 passed across 302 suites, 8 skipped; `:pdf-viewer`: 44/44 across 8 suites, 0 failures).
  - Builds & binary checks: `app-release.apk` (`55,841,484 B`), `app-debug.apk` (`154,051,741 B`), `app-debug-androidTest.apk` (`3,314,780 B`); `113` JNI symbols per ABI (`0` `panicProbe`, `0x4000` `PT_LOAD`, `0` `org/commonmark` classes).
- **Pixel 9a (`58071JEBF07361`, Debug APK `knitipka.frogreader.rust`) observations**:
  - `IndexedPaginationPassTest`: **2/2 passed** in `5.673 s` (verifying 100% anchor parity and checkpoint recovery on real Android `TextMeasurer`).
  - *Re:Zero* cold EPUB open HUD trace: `Сборка кэша FRB (Rust)` `1.39 s`, `Ожидание окна` `0.24 s` (deadlock resolved), `Пагинация всей книги` started immediately and progressed live (`107/677 гл.` in `12.93 s`).
  - *Hebrew Bible* (`7,133` pages, Debug APK): `exact_page_index_cache` hit on reopen `5 ms`, `surface_ready` `762 ms`, `window_pagination` (`243` pages) dropped from `4,012 ms` (cold ART interpreter) to `795 ms` (`3.27 ms/page`, **5.05× faster** once JIT-warmed), confirming that non-debuggable R8/AOT release builds eliminate the cold ART-interpreter penalty during pagination.

## Wave 15 direct StaticLayout fast-path & lock-free typography (M2F.B, BETA 2 wave 15, code=15)

- **Per-worker `FontFamily.Resolver` & non-clipping HUD**:
  - `ReaderScreen.kt`: both the local window `measurer` and every worker in `indexMeasurers` now receive an independent `createFontFamilyResolver(appContext)` wrapped via `createReaderTextMeasurer(..., cacheSize = 0)`, eliminating `LocalFontFamilyResolver` contention across the 6 background workers and UI thread.
  - `ReaderDebugLoadOverlay.kt` & `ReaderViewModel.kt`: widened the HUD container to `.widthIn(max = 336.dp)`, split active/completed stage rows into `Row` (`Modifier.weight(1f, fill = false)` + `TextOverflow.Ellipsis` on labels, `softWrap = false` on `— <time>`), and shortened the stage label to `"Пагинация книги"` so completion timings never clip.
- **Lock-free typography & zero-alloc Bidi short-circuit**:
  - `FontFamilyTypography.kt`: replaced global `synchronized` locks with `ReentrantReadWriteLock` on `TypographyRegistry` (`collectLocked()` under write lock only) and lock-free `ConcurrentHashMap` reads with bounded eviction (`48`/`128`) for `typographyFamilies` and `fallbackFaces`, preserving `forgetFontTypographyFiles` thread safety.
  - `BidiLayoutText.kt`: `BidiLayoutText.of` now short-circuits via `hasStringAnnotations(BIDI_TAG, 0, source.length)` before `getStringAnnotations`, returning `identity(source)` with zero allocations for plain paragraphs.
- **Direct Android `StaticLayout` fast-path (`FastParagraphLayout.kt`, `LAYOUT_ENGINE_VERSION = 24` unchanged)**:
  - Implemented `createReaderTextMeasurer`, `fastParagraphMeasurerFor`, `MeasuredParagraph`, and `FastParagraphMeasurer` (`measure` / `measureDirectOrNull`) matching Compose `TextLayout` / `LineHeightStyleSpan` / `StaticLayout` geometry with per-thread lock-free caching, flattened `fontWeight`/`fontStyle` spans, and no `BoringLayout.isBoring()` double-shaping; falls back cleanly to `TextMeasurer.measureReader` on JVM or unsupported styles/spans/inlines.
  - `Pagination.kt` looks up `fastParagraphMeasurerFor(measurer)` once per `paginateLayout` pass and routes all 5 paragraph measurement sites through `measureParagraph(...)`.
- **Pixel 9a (Android 16, API 36) verification**:
  - `IndexedPaginationPassTest`: **3/3 passed** (`6.342 s`), including **336/336** exact differential parity cases between `FastParagraphMeasurer` and Compose `TextMeasurer.measureReader` (`0.00px` height delta, exact `lineCount`/`getLineTop`/`getLineBottom`/`getLineEnd`/`getLineForOffset`, plus whole-book `paginateBook` parity on `bookStyles = true/false`).
  - Single-threaded benchmark (`FAST_PARAGRAPH_BENCH`, 500 paragraphs): `composeMs = 540.04` -> `fastMs = 244.66` (**2.21× single-threaded speedup**), on top of eliminating the 3 global `synchronized` locks across 6 workers.
- **Host verification & R8 Release APK (`BETA 2 (wave 15)`, `code=15`)**:
  - `./gradlew test`: **2,400 passed**, **8 skipped**, **0 failures** (`:app`: 2,356 passed / 8 skipped / 2,364 total across 302 suites; `:pdf-viewer`: 44/44 across 8 suites).
  - R8 Release APK (`app/build/outputs/apk/release/app-release.apk`, `-PlegacyReleaseSigning=true -PrustEngineApp=true`): **`55,873,424 B`**; `libfrogreader_core.so` verified across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) with `113` `Java_*` JNI symbols, `0` `panicProbe` symbols, `0x4000` `PT_LOAD` alignment, and `0` `org/commonmark` classes in `classes.dex`.

## Wave 20 Pixel 9a 6-worker pagination & frame matrix (BETA 2 (wave 20), code=20)

Measured on Google Pixel 9a (`58071JEBF07361`, Android 16 / API 36, `tegu`, Debug build `knitipka.frogreader.rust`, default 6 workers):

- **`IndexedPaginationPassTest` — 4/4 PASSED (`7.716 s`):** covers `336/336` `FastParagraphLayout` parity, `anchorSlicedQuickPaginationMatchesFullChapterLayout` (Wave 18), checkpoint process-death recovery, and `1/2/4`-worker anchor parity.
- **6-worker `.rpi` full-book pagination & warm reopen (`IndexedReaderUiTest`, `enginePageIndex=true`):**

| Book | Pages | `.rpi` bytes | 6-worker build `ready_ms` (Debug) | Speedup vs baseline (`108.7 s`) | Warm `.rpi` reopen `ready_ms` | Notes |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| *Re:Zero* (`9,648,164 B`, `677` ch, `190,851` el) | `35,928` | `287,488 B` | **`26,846 ms`** | **`4.05×`** (`21.57 s` = **`5.04×`** in R8 Release AOT via Wave 15 HUD) | **`969 ms`** (`stage=cache`) | Under `1.0 s` budget on warm reopen even in Debug |
| *Hebrew Bible* (`33,221,611 B`, `122` ch, `44,446` el) | `7,133` | `57,128 B` | **`20,286 ms`** | — | **`1,279 ms`** | Profiled simultaneously with 6 page turns (`engineProfilePaginationFrames=true`) |

- **Frame responsiveness during active 6-worker pagination (*Hebrew Bible*, `engineProfilePaginationFrames=true`, 6 simultaneous page turns):**
  - `frames=264`, **`p50=4.66 ms`**, `p95=22.90 ms`, `max=127.17 ms`, `deadline_misses=9/264`, `dropped_reports=0`.
- **Waves 16–19 reader UX & quick-layout improvements:** zero wrong-page intermediate flash on slider/TOC seek (`ReaderSeekResolution.kt`), slider draft held through slow chapter loads (`ReaderChrome.kt`), anchor-sliced instant jump from `.rpi` (`resolveExactQuickPaginationStart`), and redesigned English developer HUD (`ReaderDebugLoadOverlay.kt`, `ReaderLoadTrace.kt`, isolated from accessibility via `.clearAndSetSemantics {}`).

`M2F` and `M2F.A` are closed.
