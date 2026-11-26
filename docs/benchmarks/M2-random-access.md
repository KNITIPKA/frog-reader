# M2: indexed storage progress

On 2026-11-11 ReaderScreen started opening valid FRB books through the complete
index and an asynchronous chapter window. Exact global pagination, targeted
cold source preparation and the full readiness benchmark matrix remain open.

## Storage changes

One FRB v2 replaces v1, without a compatibility decoder. Block 0 contains the
complete chapter descriptors, exact global element coordinates, author TOC and
destinations, link targets, fonts, language, page progression, resource manifest
and precomputed publisher typography summary. It contains no chapter text,
notes or linked-document bodies. Block 1 contains the heavy global tables;
chapter K is addressed at block K+2. The manifest has an 8 MiB decoded limit;
the other container quotas remain unchanged.

`ChunkedContentCache.openIndexed()` validates the source SHA-256, framing,
native index, manifest coordinates and extracted resource paths. It does not
decode any sequential chapter or heavy global table. An explicit `readChapter`
performs native block IO and Kotlin decoding; callers must run it off Main.
The open native FD retains the same cache generation across atomic replacement.
Each request checks the verified source stat identity; a full-model load also
rechecks the source hash at the end. All native handles and registered scratch
trees have deterministic owners. Closed sessions reject further reads.

`BookRepository.openIndexedContent()` pins extracted resources before dispatching
IO and rechecks that the book still names the same source after opening.
`IndexedBookSession` exposes asynchronous chapter/global reads and a window of
at most the target chapter plus one neighbor on each side. It finds the target
by binary search over exact global coordinates, including empty chapters.
Cancellation stops remaining neighbor reads and closes an undelivered native
session even when cancellation arrives during the return from IO.

Cache misses and full-book consumers use the same format and session
implementation. A warm ReaderScreen uses global element coordinates for TOC,
progress, quotes and bookmarks. Scroll and paged rendering consume the loaded
window. Search walks chapters on request, and notes and linked documents load
when opened. Library startup no longer preloads a full text model for the
continue-reading card.

## Host results

Five new sessions per target, same warmed JVM and OS cache; times include index
open and complete target-chapter decoding. Source SHA validation and manifest
decoding are included. Source parse/save and canonical model checks are outside
those timings. Per-chapter SHA checks prove exact destination text rather than
merely a successful lookup. Nearest-rank p95 for five samples is the maximum.

| Corpus | Target | Index + chapter p50 (ms) | p95 (ms) |
| --- | ---: | ---: | ---: |
| Re Zero | 0% | 13.668 | 35.950 |
| Re Zero | 85% | 12.700 | 16.178 |
| Re Zero | last element | 12.818 | 13.247 |
| Hebrew Bible | 0% | 36.487 | 66.883 |
| Hebrew Bible | 85% | 61.588 | 101.113 |
| Hebrew Bible | last element | 45.035 | 61.406 |

Corpora retain the M1 counts: Re Zero 677 chapters / 190,851 elements; Hebrew
Bible 122 chapters / 44,446 elements. Source hashes are recorded in the M1
report. Raw phase samples, setup and environment are in
[Re Zero host](M2-rezero-indexed-host.md) and
[Hebrew Bible host](M2-bible-indexed-host.md).

The host does not measure Android text layout, page cuts, frame/input latency,
CPU or native heap. Host results cannot be compared directly with the earlier
Pixel full-model timings. The former full-model restore and the new selective
operation perform different amounts of work; they are not equivalent complete
reader benchmarks.

## Pixel results

Pixel 9a, Android 17 / API 37 (CP3A.261005.005), isolated `.rust` debug APK.
Both actual EPUBs passed full-index/navigation digests, every chapter descriptor
and exact hashes of the target chapters on all five runs. Each run opens one
new native session, then reads the first, 85%-target and last chapter. The 85%
target is source-element progress, not an exact page fraction. Setup parses the
full book once and releases that model before the timed indexed opens; a GC
request does not guarantee a particular collection.

| Corpus | Operation | p50 (ms) | p95 (ms) |
| --- | --- | ---: | ---: |
| Re Zero | Full index | 68.203 | 283.272 |
| Re Zero | Index + first chapter | 82.620 | 351.037 |
| Re Zero | Index + 85% chapter | 131.488 | 451.739 |
| Re Zero | Index + last chapter | 140.114 | 426.355 |
| Hebrew Bible | Full index | 301.198 | 804.950 |
| Hebrew Bible | Index + first chapter | 303.105 | 808.055 |
| Hebrew Bible | Index + 85% chapter | 1148.277 | 2338.199 |
| Hebrew Bible | Index + last chapter | 635.517 | 1201.742 |

Index + chapter is the sum of the corresponding phases within each run;
validation hashes between phases are excluded. The median of the sums is used,
not the sum of independently calculated medians. The first run includes fresh
runtime/JIT effects and remains in p95. No whole preceding prefix is decoded.

The full cold parse still takes 42.396 s for Re Zero and 34.403 s for Hebrew
Bible in these single setup runs. This stage has not accelerated cold parsing.
Heavy global-table decoding, measured separately once, takes 85.665 ms and
6344.063 ms respectively. These are not five-run cold or globals distributions.
Loading those tables at reader startup would retain a substantial Bible delay.

The Bible's 85% chapter has 2,960 elements; reading that one chapter alone has
848.161 ms p50 / 1533.249 ms p95. That still exceeds the warm-seek budget and
needs smaller addressable segments or a faster payload/model path, with layout
fidelity retained. The new primitive does not close this performance requirement.

JVM/native heaps are whole-process samples every 2 ms, not allocated bytes,
RSS or guaranteed maxima. Raw phase times, sampled memory and parity digests
are in [M2-device.md](M2-device.md). The final APK also passed the generated
indexed test and app-context smoke (two tests). Temporary test trees were
removed and benchmark wake locks released. The primary app was not replaced.

## ReaderScreen on Pixel

The 2026-11-11 `.rust` build exercised the production ReaderScreen through
`ReaderOpeningTestActivity`. `IndexedReaderUiTest` used a 20-chapter fixture with
100 identifiable paragraphs and a valid FRB. It restored saved text at 85%,
opened the last chapter via the actual contents button and row, and used the
progress control's accessibility action to seek to 10%. The test observed
destination text and the saved global percentage in both reading modes. A
fixture-specific parser hook fails the test if the reader requests a full model.

| Mode | Open to saved text (ms) | TOC tap to last chapter text (ms) | Slider to saved 10% (ms) |
| --- | ---: | ---: | ---: |
| Pages | 1221 | 488 | 366 |
| Scroll | 600 | 378 | 669 |

Each row is one passing instrumentation run. The open timer starts before the
activity launch. Slider timing includes the saved progress update and the test's
accessibility polling. A second test decoded and cached each private EPUB during
setup, then launched the paged reader at a paragraph near 85% and checked that
the expected source text was visible. Each corpus had one UI trial.

| Corpus | Book index (ms) | Initial window (ms) | Reader surface after VM load (ms) | Text confirmed after launch (ms) |
| --- | ---: | ---: | ---: | ---: |
| Re Zero | 333 | 315 | 2066 | 9854 |
| Hebrew Bible | 773 | 977 | 2555 | 3050 |

The timings include different boundaries. `surface_ready` records a presented
pager frame. The text check measures activity launch through detection of the
expected paragraph via accessibility. Re Zero still has a substantial gap
between those observations and needs profiling of the opening UI and test
observation latency. Neither corpus has enough UI samples for p95. These trials
preceded the exact page index for layouts with chapter breaks.
[M2F measurements](M2F-full-readiness.md) cover that index and the current limits.

Run `IndexedReaderUiTest#privateCorpusRestoresDistantTextFromIndexedCache` with
`-e engineProfileEpub engine-benchmark/re-zero.epub` or
`-e engineProfileEpub engine-benchmark/hebrew-bible.epub` in the isolated app.
The synthetic methods are `pagesNavigateOutsideTheOpeningWindow` and
`scrollNavigatesOutsideTheOpeningWindow`.

## M2.A in-memory FRB reads

The Rust reader validates and inflates each requested block before returning a
byte array over JNI. Content and page geometry decode from the array, and the
continuous layout reader passes a bounded slice of the group to each frame
decoder. The layout summary uses the same native RPI validator as the standalone
index. The FRB disk format and existing decoded block limits are unchanged.

Five reads of the 85% target chapter per EPUB on the same Pixel 9a model and
isolated debug package. The timer includes native block validation and read,
Kotlin decoding and chapter resource checks. It excludes preparation of the FRB,
index open, rendering and the parity digest. Each chapter matched the source
digest. The earlier measurements used the same test boundary on an earlier
build. These series do not control runtime and thermal variation across builds.

| Corpus | File-based read p50 / p95 | In-memory read p50 / p95 |
| --- | ---: | ---: |
| Re Zero | 63.285 / 168.467 ms | 60.628 / 178.005 ms |
| Hebrew Bible | 848.161 / 1,533.249 ms | 1,086.384 / 2,282.263 ms |

Re Zero in-memory samples were 178.005, 74.268, 60.628, 50.502 and 51.404 ms.
Hebrew Bible samples were 2,282.263, 1,086.384, 1,085.959, 1,079.876 and
1,115.316 ms. With five trials nearest-rank p95 is the maximum. The 2,960-leaf
Bible chapter still exceeds the 400 ms seek budget before a destination frame
can render. M2.B must reduce the cost of decoding or the amount decoded at that
destination.

The diagnostic rerun with `-e engineProfileFrbPhases true` again exceeded the
budget on the Bible. The five comparable complete reads gave 901.575 ms p50
and 1,641.314 ms p95. The same test then measured two separate phases on the
3,462,354-byte decoded block (411,449 compressed bytes). Every decoded chapter
matched the source digest.

| Phase on Pixel 9a | p50 | p95 |
| --- | ---: | ---: |
| Validated FRB read, LZ4 and JNI byte array | 6.259 ms | 6.346 ms |
| Chapter JSON decode and Kotlin model construction | 858.383 ms | 900.659 ms |

These diagnostic phases were run after the comparable reads, with an open
native handle and warmed serializer. Their medians are separate samples. The
JSON phase excludes resource checks and the digest. M2.B should start with
the chapter codec and measure the effect on the complete window before adding
transport segmentation.

## M2.B binary model and style sharing

The content codec now writes the manifest, global tables and chapters in a
compact binary payload. Each block has independent bounded dictionaries for
short strings and immutable styles. A style definition can reuse the same
reconstructed Compose style across its references. The model revision key
invalidates earlier parsed caches. The existing canonical serializers remain
the parity oracle for publisher formatting, navigation, linked documents and
the sequential text model. [Payload format](../../rust/FRB_FORMAT.md#content-model-payload-revision-5).

The first binary prototype used no dictionaries. On the Pixel 9a it matched
the source chapter digests and reduced the decoded Bible 85% chapter block
from 3,462,354 to 1,868,758 bytes. Its five complete chapter reads were
1,092.785, 771.660, 771.851, 765.937 and 764.670 ms. The p50 was 771.660 ms
and the p95 was 1,092.785 ms. The isolated binary decode p50 was 769.691 ms,
and the native read p50 was 2.687 ms. This prototype still exceeded the 400 ms
chapter budget.

The dictionary build restored the same model on Pixel 9a. The 85% Bible chapter
contains 2,960 elements and its decoded block is now 988,220 bytes. Each column
below is a separate run of five samples on the same physical device with the
same measurement boundary. The JSON column uses the phase-split rerun from M2.A.

| 85% chapter phase | JSON p50 / p95 | Binary with dictionaries p50 / p95 |
| --- | ---: | ---: |
| Validated chapter read and model | 901.575 / 1,641.314 ms | 96.432 / 188.859 ms |
| FRB read, LZ4 and JNI array | 6.259 / 6.346 ms | 1.482 / 1.726 ms |
| Payload decode and model construction | 858.383 / 900.659 ms | 55.796 / 58.338 ms |

Final chapter reads were 188.859, 60.489, 96.432, 81.100 and 110.924 ms.
The chapter read p95 meets the 400 ms engine budget. Timing a reader seek
also includes the rest of the source window and the reader's presentation.
The opt-in `engineProfileSeek` test reports accessibility observation and
saved progress separately. Scroll-mode persistence includes the reader's
400 ms save debounce.

Re Zero also passed the Pixel source digests. Its five reads at 85% gave
10.670 ms p50 and 30.736 ms p95. The earlier in-memory JSON run gave
60.628 ms p50 and 178.005 ms p95 with the same measurement boundary.

On Mac the dictionary build passed canonical digests on both original EPUBs.
Five Bible reads at 85% gave 2.635 ms p50 and 9.888 ms p95, compared with
6.785 ms p50 and 14.175 ms p95 on the first binary prototype. Re Zero gave
0.600 ms p50 and 0.849 ms p95. These host values use the same JVM benchmark
boundary as the earlier host results.

### ReaderScreen slider on Hebrew Bible

The isolated debug app opened at 85% with a prepared FRB and checked the
restored paragraph. Each series reset to the beginning and used the real
book slider's accessibility action five times to seek to item 37,779. It
checked nearby source text visible under the open chrome and saved global
progress after every seek. Page mode used chapter breaks without a prepared
page index. The timer excludes locating the control and includes accessibility
traversal and polling. These times are observations of visible text, with
device frame timing still part of M2.D.

| Mode | Destination text p50 / p95 | Saved progress p50 / p95 |
| --- | ---: | ---: |
| Scroll | 465 / 535 ms | 817 / 888 ms |
| Pages | 501 / 545 ms | 502 / 546 ms |

The source window stage for these seeks was 135–178 ms in scroll mode and
102–123 ms in page mode. Both observed end-to-end p95 values exceed the 400 ms
goal. The chapter codec removes the original decode bottleneck, while the
reader still needs frame-level measurement and work after the source window
is ready. The two mode series start in separate processes and use five samples
each. The observed p95 in each case is the maximum sample.

The binary codec passed 2,273 JVM cases with eight skipped, 24 Rust tests and
Clippy. On Pixel both EPUB engine tests passed, along with the scroll series
and nine page/reader tests. The signed isolated R8 APK passed package alignment
and signature verification.

## M2.C targeted reopen with a retained EPUB source map

After one complete parse, the validated source map survives eviction of the
chapter FRB and extracted resources. The reader opens the saved logical
chapter from the corresponding EPUB spine occurrences. It has the full
navigation and global element coordinates from the retained map. Foreground
seeks can request other chapters while the full parse and cache write run in
the background. The session adopts the finished FRB for future reads in
place. This does not publish a different window when the write completes.

The Mac test removed the content FRB and extracted assets before the targeted
parse. Five samples at each destination include source identity validation,
map decode and a complete target logical chapter. Each chapter matched the
canonical digest from the same file's full parse. p95 is the maximum of five.

| Corpus | Beginning p50 / p95 | 85% p50 / p95 |
| --- | ---: | ---: |
| Re Zero | 13.788 / 18.655 ms | 13.183 / 14.214 ms |
| Hebrew Bible | 40.728 / 43.795 ms | 74.030 / 82.527 ms |

On Pixel 9a, the isolated debug APK ran
`IndexedReaderUiTest#privateCorpusRestoresDistantTextFromIndexedCache` with
`-e engineTargetedColdOpen true`. Each run started near 85% with the source
map retained and the content FRB and extracted resources removed. It observed
the saved text, moved the book slider to the beginning before the FRB was
published, and verified that publication preserved the new reading position.

| Corpus and mode | Source map + window stage | Saved text observed | Slider position confirmed | Complete FRB published |
| --- | ---: | ---: | ---: | ---: |
| Re Zero, pages | 0.327 s | 1.856 s | 2.745 s | 25.044 s |
| Hebrew Bible, scroll | 1.392 s | 2.547 s | 4.652 s | 19.991 s |

The three UI columns are elapsed from the activity launch, including test
observations. The slider column includes the time before issuing the action.
There is one sample per book and mode. A newly imported book without a retained
map still builds its first exact global index by parsing the source. EPUB
package and XHTML processing still use the current Kotlin parser.

## Verification

- 18 Rust tests; rustfmt and Clippy with warnings denied.
- Full `./gradlew test`: 2,261 tests across app/PDF modules, zero failures/errors,
  eight skipped. Includes real JNI corruption/selective access, format/source
  invalidation, generation replacement, cancellation and resource-pin tests.
- Debug, Android-test and optimized unsigned release APKs build; app lint has
  zero errors, 100 warnings and two hints.
- The final Pixel APK passed the four ReaderScreen trials and all three
  `LargeBookOpeningTest` page-cut checks.
- Debug/release APKs contain all four Rust ABIs, 16 KiB ELF LOAD alignment, all
  seven FRB JNI methods and no host panic-probe export; zipalign passes.
- The release APK is unsigned. Frame latency, source-position reanchoring and
  exact global page counts still require verification.

## Wave 3 verification (2026-11-19)

- **M2.C cold EPUB process-death recovery:** `ReaderEpubOpeningTest` (2/2 JVM) and
  `IndexedReaderUiTest#coldEpubSourceAnchorSurvivesProcessRecreationBeforeAndAfterExactPromotion`
  on Pixel 9a verified that a provisional `DocumentLocation.Reflowable` (`elementIndex == 0`,
  `epubSource` spine ordinal + path + local element + char/scroll offsets) persisted before
  `BookIndex`/`.frb` creation survives simulated process death / activity recreation from disk,
  reopens at the exact distant spine passage, promotes cleanly to exact global coordinates
  without shifting vertical screen position (`boundsBeforePromotion.top`), and survives subsequent
  recreation both with `content.frb` present and after `content.frb` eviction (`reader-source-index`).
- **M2.C *Hebrew Bible* continuous pages cold open on Pixel 9a:**
  `IndexedReaderUiTest#privateCorpusKeepsColdEpubSeekVisibleThroughExactPromotion`
  (`engineProfileEpub=engine-benchmark/hebrew-bible.epub`, `engineReadingMode=PAGES`,
  `engineContinuousPages=true`, isolated `knitipka.frogreader.rust` debug APK) passed in
  **22.021 s** (`epub_package_cold_exact source_bytes=33221611 ready_ms=21318 position_stable=true`).
  A follow-up logcat capture recorded `epub_package_cold_window mode=PAGES toc_rows=122 ready_ms=1126`
  and `epub_package_cold_seek ready_ms=4038` before display sleep.
- **M2.D multi-book LRU eviction with active pin & atomic FRB replacement:**
  - `ChunkedCacheBudgetTest` (4/4 JVM) verified that an oldest-timestamp active book pinned via
    `ReaderCacheBudget.pin` and its open `IndexedBookSession` (`readChapter`, `readChapterBlockBytes`,
    `loadWindow`) survive multi-book LRU eviction when 4 books exceed both the 64 MiB content budget
    (88 MiB) and 128 MiB total budget (186 MiB), and evict in LRU order once unpinned/closed.
  - `IndexedBookSessionTest` (8/8 JVM) verified that 4 concurrent reader coroutines calling
    `readChapter`, `readChapterBlockBytes`, and `loadWindow` on an open `IndexedBookSession`
    observe consistent generation-1 data without torn reads across 6 concurrent atomic `cache.save`
    replacements on disk and multi-book LRU trimming.
- **Pixel 9a device suite (`knitipka.frogreader.rust`):** all **15/15** generated/fixture
  instrumented tests passed (`ExampleInstrumentedTest` 1/1, `LargeBookOpeningTest` 3/3,
  `IndexedPaginationPassTest` 2/2 in `6.837 s`; `ChunkedCacheDeviceTest` 3/3 and
  `IndexedCacheDeviceTest` 1/1 in `21.409 s`; `IndexedReaderUiTest` 5/5 fixtures in `13.619 s`).

## Wave 4 host verification (2026-11-22)

- **In-memory FRB block writes (`writeBlockBytes`):** `RustChunkedCacheNative.writeBlockBytes`
  (annotated with `@Keep`) replaced temporary per-block files (`block.bin`, `index.bin`, `reserved`,
  `frame.json`, `group`) in `ChunkedContentCache.save`, `ChunkedContentCache.saveEpubSourceIndex`,
  and `ReaderPageLayoutCache.build` / `BatchWriter`. `ChunkedContentCacheParityDifferentialTest`
  and `ReaderPageLayoutCacheTest` verified byte-for-byte parity against file-based `writeBlock`,
  rejection of out-of-order/invalid/oversized block writes, empty and multi-group layout blocks,
  and absence of temporary block files.
- **Rust workspace:** `154/154` tests passed (`--locked`), `cargo clippy --all-targets --all-features --locked -- -D warnings` passed with `0` warnings.
- **JVM suite (`./gradlew test`):** `2,338` passed, `8` skipped, `0` failures across `307` suites (`:app`: `2,300` passed / `8` skipped across `300` suites; `:pdf-viewer`: `38/38` across `7` suites).
- **Host benchmarks:**
  - `ChunkedCacheBenchmarkTest` (deterministic generated EPUB, 16 ch / 1,552 el, 23,847 B source, 48,506 B FRB): `parse` `213.502 ms`, `frb_save` `90.212 ms`, `frb_full_restore` `42.528 ms` (0%) / `13.187 ms` (85%).
  - `IndexedCacheBenchmarkTest` (deterministic generated `BookContent`, 32 ch / 4,096 el, 40,538 B FRB): `frb_save` `16.018 ms`; `index_and_target` p50 / p95 `0.393 / 0.844 ms` (0%), `0.343 / 0.508 ms` (85%), `0.299 / 0.323 ms` (100%).
- **Signed R8 Release APK (`-PrustEngineApp=true`):** built (`50,013,371 B`); all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) export `98` `Java_com_example_frogreader_*` JNI symbols (including `writeBlockBytes`, `0` `panicProbe`) with `16384` ELF `PT_LOAD` alignment, and R8 `seeds.txt` retains all new JNI entry points.

## Remaining M2 work

- Full cold-parse speedup is already delivered via the Rust EPUB/CSS/HTML pipeline (`M5 + M6`, `M6.2b`).

M2.C and M2.D are closed on host and Pixel 9a.

## Wave 20 Pixel 9a final matrix (BETA 2 (wave 20), code=20)

Measured on Google Pixel 9a (`58071JEBF07361`, Android 16 / API 36, `tegu`, Debug build `knitipka.frogreader.rust`, default 6 workers):

- **Strict direct Rust vs Kotlin EPUB parity (`IndexedCacheDeviceTest#compareDirectRustVsKotlinEpubParity`) on *Hebrew Bible* (`33,221,611 B`) — 1/1 PASSED (`53.394 s`):**
  - Field-by-field `assertBookIndexEquals` + canonical SHA-256 `index_digest=5c92c2f813cf9d91c716137d0d659fd1eac77e3451d986635c1ee9f433af6215` (`MATCH=true`; `122/122` chapters, `122/122` navigation, `40,558/40,558` `linkTargets`, `31/31` `resourcePaths`, `1,028/1,028` `epubSourceRanges`, `publisherStyle fieldsMatch=true`).
  - Canonical SHA-256 `globals_digest=b0304defa72393cec5ac500ce9f3d8647e86a81645acda610b47a3fb53165142` (`MATCH=true`; `11,972/11,972` notes, `differingNotes=0`).
  - `chapters`: `122/122`, `differing_chapters=0`.
- **`IndexedReaderUiTest` core fixture suite — 5/5 PASSED (`14.409 s`)** (with `showReaderLoadHud = true` isolated from accessibility via `.clearAndSetSemantics {}`):
  - `scrollNavigatesOutsideTheOpeningWindow`, `pagesNavigateOutsideTheOpeningWindow`, `globalPageCounterReopensFromDiskWithAnUnreadableDistantChapter`, `continuousExactPagesCrossTheSourceWindowAndReopenFromDisk`, `coldEpubSourceAnchorSurvivesProcessRecreationBeforeAndAfterExactPromotion`.
- **Real-corpus seek & frame matrix (`IndexedReaderUiTest`):**

| Corpus | Mode | Target | Initial render (`rendered_ms`) | 5-trial seek `observed_ms` | Seek p50 / p95 (Debug) | Notes |
| --- | --- | --- | ---: | --- | ---: | --- |
| *Re:Zero* (`9,648,164 B`, `677` ch, `190,851` el, `35,928` pg) | `PAGES` | 85% `item=162223` | `1,439 ms` | `561 / 595 / 623 / 653 / 717 ms` | **`623 / 717 ms`** | Warm `.rpi` reopen `ready_ms=969 ms` (`stage=cache`) |
| *Re:Zero* | `SCROLL` | 85% `item=162223` | `1,033 ms` | `330 / 349 / 369 / 404 / 439 ms` | **`369 / 439 ms`** | — |
| *Hebrew Bible* (`33,221,611 B`, `122` ch, `44,446` el, `7,133` pg) | `PAGES` | — | — | PASSED (`19.212 s`) | — | Full 6-worker `.rpi` + 6 turns: `frames=264`, `p50=4.66 ms`, `p95=22.90 ms`, `max=127.17 ms`, `deadline_misses=9/264`, `dropped_reports=0`; warm `.rpi` reopen `ready_ms=1,279 ms` |
| *Hebrew Bible* | `SCROLL` | 85% `item=37779` | `1,459 ms` | `430 / 448 / 483 / 501 / 508 ms` | **`483 / 508 ms`** | — |

- **Waves 16–19 UX & correctness improvements verified alongside M2.D:** zero wrong-page intermediate flash on slider/TOC seek (`ReaderSeekResolution.kt`), slider draft held through slow chapter loads (`ReaderChrome.kt`), anchor-sliced instant jump from `.rpi` (`resolveExactQuickPaginationStart`), and redesigned English developer HUD (`ReaderDebugLoadOverlay.kt`, `ReaderLoadTrace.kt`).
