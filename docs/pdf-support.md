# Native PDF support

FrogReader uses Google's AndroidX PDF **1.0.0-beta01**, released August 26, 2026.
The version was checked against the [official release notes](https://developer.android.com/jetpack/androidx/releases/pdf)
and the published source jars/AAR metadata in [Google Maven](https://maven.google.com/web/index.html#androidx.pdf).

## Application integration

PDF is a normal `BookFormat` in the library, import picker, folder scan, external-file intents,
sharing, collections, Reading Queue and backups. Import detects a PDF signature without parsing
untrusted document objects inside the app process. Before staging finishes, the isolated worker
uses the already-open PDFium document's public `getDocumentMeta()` API and renders a
bounded first-page cover. Supported fields are Title, Author, Subject (the book description),
Keywords (also listed as genres), Creator, Producer, CreationDate and ModDate. The native Author
string is kept intact; the API does not expose an ordered list of individual authors. These eight
fields remain readable as embedded metadata. Missing fields stay empty. Creation/modification
timestamps are retained as returned, never inferred to be a publication year. The original source
filename without its extension is the fallback title, including password-protected imports.
New imports do not extract XMP, custom Info keys, publisher, publication year, language, ISBN,
series or translators. Previously stored values remain supported and are never migrated away.
Library metadata and custom covers use the existing book-details editor and repository. PDF metadata edits update
the library index only. Exporting or sharing the document always preserves its original bytes.
Removing a cover persists a defaulted flag so a later open cannot undo that choice.

The import preview, folder inspection, Reading Queue attachments and committed library all use
the same extracted data. A reader Info button opens the shared book-details screen without
counting that time as reading. Other embedded properties expand on the import preview and
book-details screen; they persist in `library.json` and both backup modes through a defaulted
`additionalMetadata` field. Replacing a PDF respects removed/custom covers and gives newly
generated art a fresh cache key. Sharing/export still returns the original document bytes.

Metadata and covers reuse `PdfTextService` and its single PDFium document, opened through the
transferred read-only descriptor. There is no second PDF parser, path reopen, file scanning,
reflection, internal metadata API or whole-file copy inside the worker. The response carries only
the eight public fields, with at most 48,000 value characters (8,192 per value); JPEG covers are
at most 256 KiB and 360 × 540 pixels. The entire staging request has a cancellable 20-second
deadline; native requests retain the worker's ten-second cooperative work limit. A native call
finishes before cancellation can release its handles. Extraction failure leaves the PDF importable
with its filename. A failed cover render does not discard successfully extracted text metadata;
a failed metadata read can still return the cover. Password entry remains in the reader.

The on-disk models, backup format and defaulted fields are unchanged by this simplification.
Reimport/attach keeps nonempty library metadata and user corrections. Existing additional keys
are merged with the returned Info fields so a narrower extractor cannot erase old XMP/custom
properties. Chosen and removed covers remain authoritative; automatic first-page art refreshes
only for changed source bytes. AndroidX PDF continues to own page viewing and interaction.

`ReaderScreen` dispatches PDF books to a separate engine surface. It reuses `ReaderColors`,
reader bar surfaces/buttons, the selection toolbar's actions, external Search/Define/Translate
coordinator, reread dialog and completion receipt. PDF controls offer contents, page navigation, bookmarks,
saved quotes, notes, quote colours, search, brightness, keep-screen-on and the existing per-book
page counter. Inapplicable reflowable typography and page-turn settings are hidden. Native
pinch/scroll/selection gestures remain owned by AndroidX. Volume navigation follows the app
preference. Fixed PDF page artwork and colours remain authored content in every app theme.

PDF settings reuse the reflowable reader's compact brightness control, segmented choices and
grouped device switches. **Reader theme: Light / Beige / Midnight** changes the reader chrome and
the canvas around the PDF. The choice is saved for that book. A book follows the app theme until
one is chosen, and the PDF page artwork keeps its original colors. Brightness and reading mode
come next, followed by the **Panels** section. The panel also exposes the existing app-wide
volume-key navigation preference (down for next, up for previous) and keep-screen-on setting.

The PDF reader's Settings panel offers **Reading mode: Scroll / Pages**, saved independently for
each book and applied immediately. Continuous vertical scrolling remains the default for existing and new PDF books. In
single-page mode, horizontal swipes turn exactly one sheet (left for next, right for previous),
following the finger directly; releasing settles to the next sheet or returns to the current one
according to drag distance and velocity. Nearby button turns use a short horizontal slide;
distant page jumps go directly to the destination. Ordinary turns
fit the entire sheet. A bounded pager retains
native viewports for the current sheet and up to two neighbours in each direction, preparing their layout,
fit, tiles and saved/search highlights offscreen. Compose's additional speculative prefetch does
not create extra native renderers. Returning to a retained neighbour reuses its tiles; distant
sheets are released. Neighbours start immediately. Dragging and settling only expose a fitted
sheet whose native bitmaps have finished rendering; if rapid turns catch up with prefetch, the
current page stays visible until the destination is ready. The full-screen opening indicator is reserved for opening/recovery rather
than shown on every page turn. Zoomed pages retain native pan, pinch and selection;
turning by swipe resumes when the page fits horizontally. Volume keys and accessibility actions
also move one source page at a time. Switching modes retains the source page and viewport.
Each mode gets a fresh pager, and navigation callbacks read the current viewport/cache instead
of retaining the preceding mode's disposed sheets. Pager subcomposition retains the actual
viewport instance, including when its attachment follows parent cleanup.
The opening indicator stays visible until the restored destination has rendered, including when
AndroidX defers a distant Scroll restore while loading page dimensions. Search keeps page-turn
gestures available while its field remains open; reading time remains paused during search.

`SinglePagePdfDocument` borrows a single source page as local page 0 without reopening or closing
the session document. Metadata, rendering, selection and callbacks are remapped at this boundary;
progress, quotes, bookmarks, outline jumps and full-document search continue to use source page
indices. AndroidX beta01 has no paged layout or internal-link callback, so internal destinations
are routed through opaque per-adapter URLs and resolved only by that adapter. Upgrades must
recheck this adapter and the native gesture/viewport integration tests.

`CachedPdfDocument` shares immutable page dimensions across both modes and their nearby sheets.
Native page operations are serialized because the pinned renderer's metadata helpers can release
a page while another request is rendering it. Bitmap requests retry a transient page-release
failure at most twice, preserve cancellation, and recycle results whose delivery is cancelled.
Distant Scroll navigation warms only
the required dimension prefix on a worker; AndroidX still needs the exact sizes of preceding
pages for its layout, so the first jump in a very large document can still take time.

`PdfReaderViewModel` writes through the same `BookRepository`, `StatsRepository`, reading-session
clock and retryable ordered writes as the existing reader. Only resumed, visible reading counts
as reading time. Loading, reader sheets, selection, search, errors and undecided rereads do not.
PDF progress is debounced while moving and flushed on pause/disposal. Bookmarks, notes and
highlights remain in `userdata.json`; viewport position remains in `progress.json`. The library
and profile use physical PDF page numbers and typed destinations. Replacing different source bytes
detaches obsolete anchors while retaining passage text, notes and colours.

On the final PDF page the reader exposes **Finish reading**, which saves completion and opens
the shared rating/review receipt. Completed or abandoned books use the existing browse/resume/
reread choice, protecting previous history from an accidental open. Page completion is explicit;
opening the last page alone does not mark a book finished.

Backup archive structure stays unchanged. Its manifest version is now 3 because the new `PDF`
enum cannot be decoded by older readers. This reader continues to restore earlier supported
backup versions; new model fields have defaults. Personal PDF annotations and cover preferences
round-trip through both full and data-only backups.

## Engine and API choice

`PdfView` provides native tile rendering, zooming, scrolling, selection, links and highlight
overlays without requiring a fragment. Compose `PdfViewer` wraps that same `PdfView` in
`AndroidView` and exposes observable viewport/selection state, coordinate conversion,
navigation and selection menu filtering. Its viewer and state remain `@ExperimentalPdfApi`
in this beta. `PdfViewerFragment` adds its own search/password/chrome UI, which overlaps
FrogReader's existing reader controls. FrogReader owns its selection menu and Saved panels. Personal passages remain in its existing
user data and are also synchronized as portable annotations by the existing PDFium worker.

Documents open through [`SandboxedPdfLoader`](https://developer.android.com/reference/androidx/pdf/SandboxedPdfLoader).
The library's merged service is non-exported and runs in an isolated process. This follows
Android's recommendation to render untrusted PDFs in an isolated process with minimal
permissions; see [`PdfRenderer`](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer).
Loading and document access happen off the main thread. Each successful document must be
closed. `PdfDocumentSession` owns the document and disposes results from cancelled, superseded
or already-closed sessions. Native opening is allowed to return before abandoned results are
closed, so cancellation cannot lose ownership of a successfully opened document.

The pinned beta's loader does not unbind its service when opening fails before returning a
document, such as after an incorrect password. `PdfSandboxContext` tracks the loader's public
`Context.bindService(Intent, ServiceConnection, Int)` calls for each open and releases them on
failure. The returned document delegates its API and closes the original document before
releasing any remaining bindings. It stays active during normal runtime reconnects. A false
bind result becomes an `IOException`, preventing an indefinite callback wait. This uses only
public Android APIs; [Context's contract](https://developer.android.com/reference/android/content/Context#bindService(android.content.Intent,android.content.ServiceConnection,int))
requires unbinding even after a false result. Tests cover failed/throwing binds, password
failure, runtime reconnect and idempotent disposal. Recheck the loader's binding overload and
application-context usage when upgrading AndroidX PDF; this adapter is tied to the verified
beta01 behavior, without reflection or private API access.

## Compatibility

All four beta01 artifacts used by the reader (`pdf-core`, `pdf-document-service`, `pdf-viewer`
and `pdf-compose`) declare **minSdk 28** in their published Android manifests. Rendering uses
the library's `PdfRendererCompatAdapter` on older devices. AndroidX's system text and link
APIs require Android 15 or Android 12+ with S SDK extension 13. FrogReader supplies text-layer
search, selection and extraction on older supported devices through
[PdfiumAndroidKt 2.0.3](https://github.com/johngray1965/PdfiumAndroidKt), without raising the
minimum above Android 9. Links still follow the system engine's capabilities. The optional
image-selection API remains disabled. No OCR, Play Services or network service is required.

`CompatibilityPdfDocument` delegates rendering, tiles and links to the original AndroidX
document, and implements genuine text operations in `PdfTextService`. This non-exported,
isolated service receives only a read-only file descriptor and has no app permissions. It
does not initialize the library, preferences or backups. Parser work runs on Binder threads;
closing the reader releases the binding without blocking the UI on native work. Cancellation,
late open results, Binder death and failed connections retain explicit resource ownership.
The source PDF is never rewritten.

AndroidX continues to own selection handles, long-press and drag gestures, and highlight
painting. Search and selection availability comes from the opened document's capabilities,
rather than an OS-only UI check. The compatibility adapter implements beta01's JVM-public
`@RestrictTo` capability and selection-boundary methods. This is a small, version-pinned
integration point that must be rechecked on AndroidX upgrades; it uses no reflection or
SDK spoofing. Native character indices stay separate from UTF-16 word offsets. PDFium's own
page/device transforms map all rectangle corners, including crop boxes and page rotation.

The text worker caches at most eight opened text pages. Responses use bounded primitive
arrays below Binder's transaction limit: at most 120,000 text characters, 8,192 selection
rectangles, and 2,000 search matches per page. Pathological pages exceeding these limits
produce a recoverable search/selection failure, rather than truncated text with wrong anchors.
Search is case insensitive and still obeys the reader's 2,000-result document cap. Search input
is cleared when dismissed, and canceled requests cannot republish stale matches. A failed
selection leaves the document readable and offers a brief retry message.

The stable PDFium binding is pinned to 2.0.3, and its native JNI names are retained through R8.
The supplied 64-bit binaries support 16 KiB ELF alignment. The upstream artifact does not
identify an exact Chromium PDFium revision; dependency upgrades should include a native
security and compatibility review rather than assuming the binary tracks Chromium releases.

`pdf-compose` requires compileSdk 37 and AGP 9.1.0 according to its AAR metadata. The other
PDF artifacts require compileSdk 36 extension 19 or a later SDK, and AGP 8.9.1. FrogReader's
compileSdk 37 and AGP 9.4.1 already meet these requirements.

Encrypted files use the platform password APIs on supported systems. The older compatibility
renderer cannot accept a password. A password error can also indicate unsupported encryption;
it must not cause an endless automatic retry. Passwords are transient input, not book metadata.

## Locations and personal passages

PDF locations are typed `DocumentLocation.Pdf` records: a zero-based page index, viewport-centre
coordinates in PDF points, and zoom. PDF points have their origin at the page's top-left.
They are separate from reflowable chapter/paragraph/character coordinates. Saving the viewport
centre is deliberate: the public `scrollToPosition(PdfPoint)` restores its argument to the
viewport centre. `scrollToPage` instead resets zoom to fit width and is unsuitable for exact
position restoration. A centre point may lie outside a page when the viewport spans a page gap.

Text selections expose selected text and a list of `PdfRect` bounds, potentially over several
pages. PDF's selection menu has Search, Define, Translate, Highlight, Add note and Copy.
Highlight stores a passage; Add note opens a text editor with a detached selection snapshot.
Saved separates Highlights, Notes and Bookmarks. Tapping any marked line with a note opens
its note sheet; gaps between lines retain the ordinary reading gesture.

Quote colours, notes and page rectangles remain in existing user data and backups. They also
become standard Highlight annotations in the library's actual PDF: `/NM` identifies the owned
annotation, `/Contents` contains the note (or selected text), and quads and appearance streams
preserve the marked lines. Export and Share use the updated stored PDF. The original file
selected during import is not overwritten.

Annotation writes reuse the existing permissionless PDFium worker and its existing native binary.
The small `frog_pdf_annotations` JNI library binds the annotation functions exported by that same
`libpdfium.so`; it contains no PDF parser or renderer. There is no additional PDF dependency.
The existing factory's document handle is used only with the library's own native page loader;
only a page handle reaches the new bindings. All native work holds the existing PDFium lock and
worker lifetime monitor. PDFium's own device-to-page transform maps crop boxes and rotation.
Source, payload and output travel through descriptors. The worker saves to a separate fsynced
PDF generation, preserving encryption, before the repository publishes its name, hash, size and
annotation fingerprint. Foreign annotations are retained. Updates and deletion replace only
annotations with this book's ownership prefix. User data is committed immediately, and a debounced repository-owned queue synchronizes PDF
bytes in the background. Rapid changes share one generation; stale worker completions never
replace newer quotes or progress. File hashing uses a dedicated background-priority thread.
On API 29+, annotation writes bind a separate isolated instance of the existing PDFium service,
so native write locks do not block the text-selection worker. API 28 retains the compatible
binding path. Export, Share and full backups await pending changes; reader opening schedules
recovery after process death without delaying its first page. A failed background save retains
both the durable personal passage and the previous PDF, and offers an unobtrusive retry.

The reader still uses `setHighlights` for personal passages and temporary search matches.
The pinned AndroidX renderer's normal RenderParams do not paint embedded annotations; keeping
personal overlays provides immediate theme-aware highlights and stable hit testing without
painting the same passage twice. `setHighlights` replaces its entire list, so persistent passages,
new note drafts and temporary search matches are combined.
Search can be streamed by calling `PdfDocument.searchDocument(query, page..page)` for each page,
with cancellation between pages, rather than retaining an unbounded whole-document result set.
The integration caps results at 2,000 and indicates a capped count with `+`; overlay rendering
is limited to pages near the viewport. Scanned pages are still readable but have no searchable
text unless the document already contains a text layer.

## Verified library limitations

- AndroidX PDF beta01 exposes **no document outline/bookmark extraction API**. FrogReader
  obtains embedded outlines separately through its existing isolated PDFium worker, as described
  below. Page navigation remains available when the source contains no outline. Chapters are
  never inferred from physical page numbers or the printed contents page.
- Scanned/image-only documents have no selectable/searchable text without OCR. OCR is outside
  this integration's dependency and offline-processing scope.
- Source page typography, layout and colours are fixed PDF content. Reflowable font, margin,
  line-spacing and paragraph settings do not apply.
- `PdfView.pdfDocument = null` currently leaves its existing document attached. Dispose the
  old viewer before closing/replacing its session; assigning null is not a detach operation.
- The library filters native context menu items through
  `filterContextMenuComponents` / `SelectionMenuItemPreparer`; selected text and bounds can
  therefore drive FrogReader's existing selection actions and toolbar.

API references: [`PdfView`](https://developer.android.com/reference/androidx/pdf/view/PdfView),
[`PdfDocument`](https://developer.android.com/reference/androidx/pdf/PdfDocument),
[`PdfViewerState`](https://developer.android.com/reference/kotlin/androidx/pdf/compose/PdfViewerState).

Build and JVM tests verify integration logic, coordinate safety and lifecycle ownership. They
do not establish physical-device rendering, gesture smoothness, selection handles, TalkBack or
large-document performance; these require device validation.

## Embedded PDF contents — October 20, 2026

The navigation panel has Contents, Pages, Bookmarks and Quotes tabs. Contents preserves the
document's preorder hierarchy, including grouping entries without a valid page destination.
Only real in-range page targets are clickable. The current section is the closest preceding
target page (last authored entry wins ties); opening the panel scrolls near it. Empty outlines
have an explicit explanation, failures offer Retry, and bounded partial outlines show a notice.
This reads the embedded outline only; no OCR, text-heading inference or source-file edits occur.
Destinations currently use the target page, not the optional PDF destination coordinates/zoom.

Import requests the outline after metadata/cover from the **same** PDFium document and read-only
descriptor. Outline extraction gets a separate three-second cancellable wait inside the existing
twenty-second staging budget. Metadata already received survives outline failure or timeout.
The transient import result seeds a disposable cache against the final committed file. Existing,
restored and password-protected books request missing contents only when Contents is first shown,
after the viewer has opened successfully. A transient reader password is passed to the worker,
never to the cache. No outline work runs during app startup or gates the first rendered PDF page.

`PdfOutlineCache` stores successful results, including known-empty outlines, in `cacheDir/pdf-outlines`
through `AtomicJsonFile`. Cache schema, existing content hash (or path when absent), file size and
modification time are checked; no full-file hashing is added to reader startup. Changed source
fingerprints discard late extraction results. Records are capped at 1 MiB and the global LRU at
8 MiB / 128 records, counting backups and temporary files. Eviction or corruption causes lazy
re-extraction. The library/user/progress stores and backup archive schema remain unchanged.

PDFiumAndroidKt 2.0.3's convenience outline method recursively creates an unbounded full tree
and stops after sixteen levels. Instead, the worker uses an iterative preorder walker with cycle
detection, at most 4,096 entries / 64 levels, 2,048 characters per title and 131,072 title characters
in total. Structural limits return a marked partial result; native failures and cancellation are
not cached as an empty outline. Primitive-array Binder responses are validated on both sides.
The on-demand request has a twenty-second cancellable deadline and the worker retains its
cooperative ten-second work limit. A single native call, including allocating a native bookmark
title before it can be shortened, must return before its document can be safely released.

The bounded walker reuses the existing locked `PdfDocument` via version-pinned public constructor
injection (`PdfiumCoreU` / `NativeFactory` / `NativeCoreContract`). Only the descriptor-open return
value is captured inside the isolated process. Direct outline calls hold PDFium's same global
blocking lock and the engine's native lifetime monitor; native pointers never cross Binder or
enter stored models. This deliberately uses low-level library contracts intended for its own
implementation and must be rechecked on dependency upgrades. There is no reflection, new JNI
code, additional document within the worker session, new renderer or additional native binary. `pdfiumandroid-core:2.0.3`,
already present at runtime, is now an explicit compile dependency for these contracts.

Verification: `./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
:app:lintDebug -PlegacyReleaseSigning=true --max-workers=4` passed with Android Studio's JBR.
There were 2,062 JVM tests (2,054 passed, 8 skipped), no test failures and no lint errors/fatal
issues. Thirteen native Compose previews rendered cleanly and were visually inspected. Eleven
PDF worker/import/text instrumentation tests passed on a physical Pixel 9a, including Unicode,
groups, named/repeated targets, empty/deep/cyclic outlines, encrypted PDFs and unchanged source
bytes. A separate Java-only instrumentation APK then verified the obfuscated release worker's
outline through Binder (one passed test). The release is signed with the existing compatible
legacy certificate and installed without clearing data. No emulator was used. This does not
establish broad large-book performance, TalkBack or touch smoothness; those remain device QA
tasks. Build logs, tests and previews are in `.artifacts/pdf-outline-ui/`; the independent release
smoke harness is in `.artifacts/pdf-outline-release-smoke/`.

## Verification — October 18, 2026

The final command completed successfully with Android Studio's JBR:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

- 2,008 JVM tests: 2,000 passed, 8 skipped, no failures or errors.
- Lint: no errors or fatal issues; 76 warnings remain.
- Debug APK, instrumentation APK and optimized unsigned release APK built successfully.
- An installable optimized release also uses the project's existing compatible signing setup:
  `./gradlew :app:assembleRelease -PlegacyReleaseSigning=true` with the same JBR.
- Eight native Layoutlib/Compose previews rendered with no reported problems and were visually
  inspected: Light, Beige, Midnight, page navigation, bookmarks, quotes and settings, including
  320 dp and large text. This validates the new panels rather than PDF rendering itself.
- The merged manifest has minSdk 28, targetSdk 36 and compileSdk 37. Both PDF services are
  isolated and non-exported; there is no Internet permission.
- Device instrumentation tests cover sandbox rendering/reopening, platform and forced
  compatibility text search/selection, Unicode text, cropped/rotated coordinates, image-only
  pages, immutable source bytes, and long-press → FrogReader Copy → cleared selection. Their
  APK compiles; runtime device validation remains separate from JVM/build verification. No
  emulator was used.

Reproducible preview configuration, results and APK checksums are in the local
`.artifacts/pdf-support/` directory.

### PDFium-only import follow-up — October 18, 2026

Import now uses only the existing PDFium service. The former secondary parser, its positional
channel adapter, parser-specific tests, optional-decoder R8 suppression and dependency-only
licence assets were removed along with the secondary parser's transitive dependencies.

- `./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug -PlegacyReleaseSigning=true --max-workers=4`
  passed with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- 2,018 JVM tests: 2,010 passed, 8 skipped, no failures/errors.
- Lint: no errors/fatal issues, 83 warnings and 2 hints.
- Physical Pixel 9a: all eight import/native-backend tests passed. They cover Unicode Info,
  Subject mapping, both PDF timestamps without a publication year, filename fallback through
  commit/reload, red first-page art versus blue second-page art, bounded transport, source
  immutability, text search/selection and cancellation/close. No emulator was used.
- PDF metadata now travels in the existing service's bounded primitive response, then maps once
  into shared `BookMetadata`. Existing repository, preview, library/details and reader Info flows
  are retained. Existing library fields and old additional-property keys survive reimport/backup.
- Release classpath and R8 mapping contain no former parser/FontBox/Bouncy Castle artifacts.
  The APK contains none of their assets; all 24 native binaries match the baseline byte-for-byte.
- APK v2 signature and 16 KiB ZIP alignment checks passed. The optimized release was installed
  on the Pixel via `adb install -r` after testing, without clearing application data.
- Same signed, optimized release configuration: 39,679,524 → 33,618,779 bytes;
  reduced by 6,060,745 bytes (15.27%).

Logs, baseline APK, hashes and size comparison are in `.artifacts/pdfium-import/`.


### Single-page reading — October 21, 2026

- Full JVM suite: 2,084 tests, 2,076 passed, 8 skipped, no failures/errors; the new setting,
  page adapter and swipe recognizer contribute 21 focused JVM checks.
- Debug, instrumentation and signed optimized Release builds passed. Lint has no Error/Fatal
  issues. The final APK passes signature verification and 16 KiB ZIP alignment checks.
- Three native settings previews (Light, Beige at 320 dp/1.5x text, and Midnight) rendered
  without reported problems and were visually inspected.
- Five new physical Pixel 9a cases passed in separate instrumentation runs: real source-page
  rendering, default and compatibility search/selection, native swipe page replacement, and the
  production surface's page boundaries, whole-sheet fit, mode switching, stored source positions,
  restored position, page input and full-document text search. Fixtures use disposable PDF files
  and isolated library/progress/statistics stores; no emulator was used.
- TalkBack behavior, volume-button gestures, and animation/performance at scale were not
  specifically measured by these new device cases.


## Continuous-scroll rendering — October 27, 2026

The reader now builds `pdf-viewer/`, a source fork of AndroidX PDF viewer beta01.
Gradle dependency substitution replaces only `androidx.pdf:pdf-viewer`; the published
Compose wrapper, core API and isolated document service remain unchanged. Source/AAR
hashes, licensing, extraction details and upgrade checks are in `pdf-viewer/UPSTREAM.md`.
The sources and resources are checked in; builds do not patch binaries or download code.
There is no reflection, second rendering engine, screenshot overlay or book-specific path.

Upstream starts rendering a new page only when it becomes visible. Its "nearly visible"
state retains old data but does not pre-render an unseen neighbour. On image-heavy books,
this leaves an opaque white page in the viewport until native rendering completes.

The local viewer prepares real full-page base images before visibility. `RenderAheadPolicy`
uses scroll direction, measured velocity and recent preview rendering time to prioritize
nearby pages. Two neighbours in each direction are prepared while stationary; faster motion
requests farther ahead, keeping two reverse candidates. Visible pages have first priority.
The preview working set is capped at eight pages and each image at 524,288 ARGB pixels
(2 MiB), for at most 16 MiB of retained base-image pixels per viewer, independent of book
length or source image resolution. Aspect ratio and actual per-page dimensions are respected.
Very zoomed-out views prioritize the first eight visible pages; native detail rendering
continues for all visible pages. The budget describes retained previews, not total app/RSS:
visible detail tiles, native renderer allocations, an in-flight result and graphics references
pending collection use additional memory. Published bitmaps are released by dropping references,
not recycled while Android's render thread may still be using them.

`PdfRenderQueue` admits one render at a time and re-evaluates pending priorities at each
admission. Visible base images precede visible detail; nearby offscreen bases, predicted detail,
and farther previews follow. Obsolete queued
requests are removed on cancellation. The currently running native call must finish safely;
it cannot be interrupted at arbitrary points. Preview lifetime is independent of high-resolution
work, so offscreen retention, zoom, tile replacement and reversal keep a base image until
new detail arrives. Offscreen detail outside the bounded prediction window is discarded;
pages outside the working set release their
sources and previews. Content/form invalidation also invalidates the preview generation.

The viewport sends motion updates during dragging/flinging instead of waiting for a new page
or an idle gesture. Existing tile request de-duplication prevents duplicate rendering. The
manager visits only the rendered working set on a scroll frame. Late neighbour dimensions
trigger prefetch even when the visible range did not change. `hasVisiblePageContent()` is a
restricted diagnostic distinct from full-resolution completion; it requires a full-page base
behind every visible sheet, not merely one tile or a successful document open.

A bounded buffer cannot promise instant rendering after an arbitrary distant jump or through
an arbitrarily slow/corrupt document. Existing opening/restore handling remains, and native
errors retain the existing error path. No whole-book rasterization or unbounded cache is used.

Regression coverage includes pure planning/memory-bound and queue promotion/cancellation tests,
plus physical-device draw-frame sampling through the production reading surface with actual
finger events away from the fast-scroll thumb. The supplied textbook is an optional external
test fixture (`pdfScrollFixture` instrumentation argument), never committed to the repository.

## Zoom detail and pinch recognition — October 30, 2026

`BitmapFetcher` separates continuous display zoom from raster resolution. Quarter-octave
resolution levels provide 6–26% sampling headroom when selected; downward hysteresis keeps
small zoom movements and reversals on the same level. Full-page rasters are used while they fit
the viewer's texture dimensions and 8,388,608-pixel limit. Larger pages use a sparse 320 px grid
for request planning and bounded pan caching, rather than allocating metadata for the whole page.

### Coherent viewport replacement — November 10, 2026

The earlier implementation grouped only missing cells. Cached cells, separately completed
edge strips, and retained cells from older resolutions could still appear together. Drawing a
shared raster through per-cell fractional clips also needlessly exposed the grid during zoom.

Visible refinement renders the **entire requested viewport** when the visible area lacks detail.
It adds 20% coverage on each side when the tile-aligned result still fits one native allocation.
Panning inside cached detail avoids another render to refill that optional margin. Each clipped
native allocation remains at most 4,096 pixels per axis and 8,388,608 pixels. If multiple
allocations are required, the whole wave stays private
until all its batches finish. Cancellation or a failed batch recycles every unpublished result.
A useful in-flight wave finishes before catching up to the latest zoom **and viewport position**,
including after the final touch event. A queued wave for an obsolete resolution or a region
that no longer covers the viewport is replaced. Jumping away from a running wave skips its
remaining batches as soon as the current native call returns. Predicted work remains in small batches.

`PageBitmapPresentation` owns immutable display snapshots separately from the mutable request
cache. It adopts only a complete visible region and draws each shared native raster once, without
per-cell clipping. Rounded native raster dimensions are mapped back with their exact X/Y scales,
keeping text aligned between full-page and clipped images. Old detail remains visible while the
replacement is prepared. A completed replacement fades in as one layer over 48 ms. Later
completions cannot restart that transition indefinitely. `PdfView` schedules animation frames
without generating additional native renders or bitmap-ready events.

If a fast zoom-out/pan exposes the outer boundary of an older snapshot, that boundary blends
into its full-page base over a narrow band (4% of the visible page width/height), instead of
showing a hard rectangle of different sharpness. This mask applies only to exposed snapshot
edges, never the authored page edge or internal grid boundaries. Highlights and selection are
drawn separately and do not fade.

Presentation retains at most the displayed snapshot, one fading predecessor (limited to 32 MiB),
and the latest completed candidate; it does not retain a chain of boards or historical tile levels.
The old retained-tile list has been removed. Offscreen-only cache allocations remain bounded to
8 MiB per page, with shared bitmaps counted once. Visible/staged rasters, full-page bases, display
snapshots, native renderer memory and graphics references awaiting collection are additional to
that cache budget. Overscan is skipped for oversized viewports to avoid inflating their working
set. Published bitmaps are released by dropping references, never recycled underneath drawing.
Form/content changes and disposal clear presentation and pending work together.

For context, MJ PDF's [pinch handler](https://gitlab.com/mudlej_android/mj_pdf_reader/-/blob/286277c6a85a4068cbbfbd18c93a742f0b2087ed/AndroidPdfViewer/android-pdf-viewer/src/main/java/com/github/barteksc/pdfviewer/DragPinchManager.java) and
[page loader](https://gitlab.com/mudlej_android/mj_pdf_reader/-/blob/286277c6a85a4068cbbfbd18c93a742f0b2087ed/AndroidPdfViewer/android-pdf-viewer/src/main/java/com/github/barteksc/pdfviewer/PagesLoader.java) request a viewport snapshot during scaling, with a preload margin,
and its cache retains prior snapshots. These informed the viewport-level approach; FrogReader's
implementation stays within its AndroidX viewer and existing isolated document service.

`DetailRenderAheadPolicy` still predicts a window up to 1.5 viewport heights ahead during enlarged
scrolling, retaining a smaller reverse margin. The viewer maps this through actual page locations.
At most eight speculative tiles are requested across the window, no more than four per page;
they share the bounded pan cache. Pages outside that detail window keep budgeted base previews.

The two-finger recognizer uses touch slop and the actual span ratio. It avoids Android's large
minimum scaling span (27 mm on the test Pixel), which otherwise delays or prevents close-finger
pinches. Adding/removing a pointer rebases the span without jumping; coincident fingers cannot
produce invalid scale factors. Android's detector still owns the one-finger quick-scale shortcut.
The tracker receives the complete event stream and returns to idle on release. Pages-mode drag
observes horizontal movement without sending CANCEL to the native viewer. A second finger
immediately returns the touched sheet to its starting position and hands the stream to pinch.
Horizontal pan reaching an edge no longer relinquishes native ownership mid-stream: that caused
Compose to cancel the stream before the second finger arrived in continuous mode as well.

`BitmapFetcherZoomContract` runs on both Android and Robolectric, with controllable render
completion to check viewport overscan, position catch-up without another touch event, atomic
large-viewport publication, cancellation ownership, content invalidation and cache bounds.
`PageBitmapPresentationTest` uses native Skia through Robolectric to compare pixels at fractional
zoom, exercise whole-viewport fading, soften exposed snapshot edges, retain useful intermediate
zoom results, and clear stale pixels on invalidation. Pure tests cover resolution hysteresis, queue priority and pinch
math. `PdfZoomUiTest` exercises the production Compose/native surface in Scroll and Pages modes,
including a delayed second finger, repeated pinch/reversal, and a supplied textbook via the
`pdfZoomFixture` instrumentation argument. The local 151-page Focus textbook is an external test
input only; neither it nor its rendered pages are included in source control.

`PdfZoomRenderBenchmarkTest` separately times clipped native calls on the supplied file.
It is an optional diagnostic, not a device-speed-dependent CI assertion. The fast zoomed-scroll
test samples drawn frames, checks for missing base content across page boundaries, and records
the time to full detail after the final finger stop.

The October 30 2x2 visible-batch version was reported to sharpen in conspicuous blocks despite
passing the initial fast-scroll checks. The October 31 revision replaces those visible batches
with viewport publication and gives current detail precedence over offscreen base preparation.
`suppliedTextbookMeasuresPinchRefinementLatency` reports repeated physical pinches separately from
the fast-scroll scenario; timings are diagnostics, not a substitute for visual validation.

Retaining detail prevents a quality reset. It does not remove the finite cost of first rendering
previously unseen content or a substantially higher zoom level on a complex PDF.

November 10 verification used Android Studio's JBR:

```sh
./gradlew test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --max-workers=4
```

The final command passed. App JVM tests: 2,166 (8 skipped); viewer JVM tests: 38;
zero failures/errors. This includes 11 additional rendering regression tests. Debug, unsigned
optimized Release and the instrumentation APK built successfully. App lint has no errors/fatal
issues (101 warnings). An additional standalone `:pdf-viewer:lintDebug` run failed on the existing
untranslated `match_status_searching_description` in the imported viewer resources; that resource
was not changed. Logs are in `.artifacts/pdf-seamless-zoom/`. No emulator, ADB, device installation
or physical PDF/pinch validation was performed. Native Skia pixel tests establish software drawing
behavior; they do not establish GPU frame pacing or subjective smoothness on a phone.


## PDF annotation native adapter — October 28, 2026

The adapter is built from `app/src/main/cpp/` with NDK 30.0.16248370 and CMake 3.22.1.
It is plain C, uses no C++ runtime, and supports the same four ABIs as the existing PDFium
artifact. Its shared library has 16 KiB load alignment. Existing vendor libraries remain unchanged.
Recheck exported PDFium annotation symbols, native page-handle ownership, UTF-16 strings,
quads/appearance streams, encrypted saves, and crop/rotation integration whenever upgrading
PDFium. A missing symbol is a recoverable write failure; there is no reflection or SDK spoofing.

Unsaved note drafts include selected text, source-page rectangles, typed text and a stable save ID.
They live outside renderer identity keys and survive recreation. A new ViewModel clears a stale
in-flight marker after process death, and retrying the stable note ID cannot create duplicates.


October 28 validation: debug and signed optimized release builds and lint passed. The JVM suites
reported 2,120 tests, zero failures/errors and eight skipped. Fifteen native Compose previews
were rendered and visually inspected. Physical Pixel 9a checks covered native selection, six
actions, note editor keyboard, save/reopen/edit by passage tap, actual PDF generations, portable
Unicode notes, crop boxes, all four rotations, foreign annotation retention, edit/color/delete,
latest pending note in Share, and encrypted save/password preservation. A separate Python pypdf
inspection checked the actual files and mapped their quad coordinates back to the selected lines.
The background-queue tests block a writer while persisting newer note/color/progress updates,
reject stale completions, batch rapid changes and flush latest data before sharing. This proves
that annotation serialization is outside acknowledgement; it does not establish timing or frame
smoothness for every large document. No emulator was used.

The signed optimized release also passed a standalone Java/Binder smoke test on the Pixel.
An independent pypdf read verified its actual Highlight, Unicode `/Contents` and appearance
stream after R8. APK v2 signature and 16 KiB ZIP/ELF checks passed. Existing PDFium binaries
match their pinned dependency byte-for-byte; the additional native adapter is 9–12 KiB per ABI.
