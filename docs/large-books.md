# Opening very large books

The reported Re:Zero EPUB is a single collected publication with 677 spine
documents. Its 9.2 MiB ZIP expands to about 24.4 MiB. The native parser produces
190,851 content elements, including 190,314 paragraphs. A small compressed file
can therefore require hundreds of thousands of separate native text layouts.

The second publication, Robert Alter's Hebrew Bible, has 1,011 linear XHTML
documents which merge into 122 logical chapters and 44,446 native elements.

## Opening behavior

EPUB parsing can publish one immutable prefix after reaching the saved source
element and another 32 elements, at a complete spine-document boundary. The
repository shares this prefix and the eventual complete parse across library
preloading and reader subscribers. Cancelling one reader does not cancel or
duplicate the underlying parse; only the complete publication enters its RAM
cache. Smaller books can finish without publishing an intermediate prefix.

The reader prepares the current chapter on a background dispatcher. Remaining
chapters and transformed notes are lazy, with exact whole-book source indices
and thread-safe, once-only preparation. The prefix's last logical chapter may
grow when later unlisted spine documents merge into it; published collections
remain immutable.

Pagination stops after three closed pages from the saved character. It does not
wait for an entire chapter or hundreds of blocks. Publisher float planning is
also local. With forced chapter breaks it starts at the current chapter;
continuous and single-chapter books start from zero to preserve exact cuts.
The page-cache decode, validation and complete-book pagination wait until the
preview has reached a frame. An incomplete publication then expands to all
stable pages in its already parsed prefix. The trailing open page is withheld
when possible, because a later spine fragment can still extend it. Incomplete
content never writes a pagination cache.

The persistent opening indicator can hand over once the requested preview is
laid out and the opening destination has been restored. Preview holders retain
their exact source bounds, including partially covered paragraph characters and
table rows. Seeks outside them, whole-book percentage seeks and initial quote or
bookmark restoration wait for sufficient content. Preview pages never supply a
whole-book page total or a completion page. While parsing, progress retains the
previous known book fraction and writes the actual source position. Scroll mode
also suppresses completion and uses a loading tail until the whole book arrives.

When full content arrives, the reader keeps its latest live item/character
position and navigation history. A generation check prevents an older prefix or
settings rebuild from replacing the complete state. The final pagination is the
only layout saved to disk.

## Bounded repeat-opening cache

`ParsedContentCache` stores separate gzip prefix and complete snapshots. A
cached prefix can reach the screen while the full snapshot decodes. Ordinary
books can restore their parsed text after process death, without rerunning the
HTML/CSS parser. EPUB parser checkpoints refresh the prefix when resuming deeper
in a book. The disk prefix normally retains the saved source element and 64
following elements; paragraphs/tables stay whole, publisher float and bounded
inline-layout groups extend the boundary when necessary. Note/link closure and
clipped publisher boxes preserve rendering and original coordinates. The parser's
larger on-screen prefix remains available while the rest of the book loads.
Unsupported or oversized complete snapshots leave that disk prefix intact.
There is no file-size threshold restricting this path to large books.

The format preserves annotation ordering, paragraph/span styles, notes, tables,
navigation, publisher boxes, embedded-font descriptors and publication metadata.
Unsupported Compose values cause a cache miss rather than lossy rendering.
Image/font bytes are referenced, not duplicated. Each load validates source
path, size, timestamp, full SHA-256, format revision, gzip CRC and resource
existence. A stale/damaged/missing cache falls back to parsing. Writes use bounded
streams, fsync and atomic replacement, off the first-page path.
A tiny source/version/limit-specific rejection marker avoids repeating expensive
full serialization after a deterministic size/unsupported-style failure. IO and
missing-resource failures remain retryable. A dedicated background-priority
writer yields CPU time to reading; LRU recency IO does not delay opening text.

`ReaderCacheBudget` applies LRU limits across existing extracted images/fonts,
pagination and parsed content: 128 MiB combined, 64 MiB parsed content, 8 MiB
compressed snapshots per book. Expanded snapshot streams are capped at 64 MiB;
pagination files at 16 MiB. Recent-book markers survive restarts. Originals,
covers, custom font imports, reading progress, quotes and backups are outside
these roots. The backup format is unchanged.

Readers, parsers and cache writers hold reference-counted resource leases.
Eviction atomically detaches inactive cache paths into a private trash directory
before deleting them; acquiring a lease never waits for a full file-tree walk.
Interrupted trash is swept. A currently open large book can temporarily exceed
the combined budget because its rendered resources cannot be removed safely.
The RAM warm cache releases its lease if it prevents trimming; after the live
reader/work finishes the ordinary limits apply again.

## Loading feedback

The opening loader is delayed 180 ms to avoid flashing on fast opens. A ready
page and loader hand over concurrently in 160 ms, replacing the former mandatory
240 + 620 ms sequence. No source position is changed by the animation.

Both modes expose a clear loading tail instead of a silent navigation boundary.
A single pending page turn resumes at the exact first unread item/character or
table row when new pages become available, including partial-to-partial updates.
Repeated taps do not queue a series of unwanted turns. Pending destinations get
a delayed status indicator; incomplete Contents explicitly says chapters are
loading. Whole-book progress seeks retain their requested fraction until full
coordinates exist. Selection remains stable across prefix/full publication.

## Other scaling improvements

- CSS resolution shares immutable computed values for equivalent cascades and
  inherited styles. Selectors still match each element. The bounded cache clears
  between documents and avoids text-dependent automatic direction resolution.
- Page and chapter lookups use upper-bound binary searches, preserving duplicate
  anchors and split-paragraph positions. Footer preparation changes from
  O(pages × chapters) to O(pages × log chapters).
- Ordinary text uses identity offset arithmetic in `BidiLayoutText`, avoiding
  per-character mapping arrays. Real bidi scopes keep their mapped path.
- EPUB spine fragments append to mutable chapter builders, then freeze once,
  avoiding repeated copies of every preceding fragment.
- Fallback note boundaries use binary search; semantic-note aliases use an
  inverse anchor index, and token separator regexes are compiled once.

These changes preserve layout decisions, the existing pagination-cache version,
stored reading coordinates, and the backup format.

## Local measurements and verification

On 2026-10-16, two parses in a fresh Mac JVM with 10 ms JFR CPU sampling measured
2,499 / 1,793 ms before CSS sharing and 2,176 / 1,283 ms afterwards. A repeat of the
optimized version measured 1,706 / 988 ms. Element/chapter/paragraph counts were
unchanged. These measurements cover parsing only, not Android text measurement
or end-to-end opening on the reporter's phone.

On 2026-10-17, an isolated debug instrumentation package on the user's Pixel 9a
measured the engine through the first three prepared pages:

| EPUB | Previous eager parse before pages | Progressive first pages | Published prefix |
| --- | ---: | ---: | ---: |
| Re:Zero | 40,450 ms | 891 ms | 67 elements |
| Hebrew Bible | 30,548 ms | 1,733 ms | 1,027 elements |

These are native engine measurements at the beginning of each supplied book,
not end-to-end release UI timings or universal speed guarantees. They exclude
the reader entrance animation. Resources extracted during earlier runs could
remain cached. The remaining parse still runs: Re:Zero's complete pass took
38,477 ms in the progressive debug test, including preparation of its preview.
This changes when reading can begin, rather than eliminating the total work.

Later Pixel 9a debug checks of the final bounded opening snapshot measured:

| EPUB | Source parser prefix | Cached opening elements | Compressed snapshot | Restore time |
| --- | ---: | ---: | ---: | ---: |
| Re:Zero | 67 | 65 | 6,789 bytes | 113 ms |
| Hebrew Bible | 1,027 | 65 | 1,962 bytes | 157 ms |

These restore times include source SHA-256 validation and decoding, but exclude
page measurement and UI presentation. Android page-cut assertions passed against
the original parser prefixes. The larger Bible prefix had taken 776 ms to restore
before cache-only slicing. Full snapshots of both private books exceeded the
64 MiB expanded bound; their readable prefixes survive and deterministic failure
markers prevent repeating that failed full-save work on subsequent opens.

For a generated ordinary styled EPUB (972 elements), one cold debug run measured
1,271 ms for parsing, 152 ms for its opening-prefix restore and 547 ms for full
restore. A later warmed run parsed in 384 ms, with 188/535 ms prefix/full restore;
whole-book cache decoding is not universally faster than an already warmed
parser. The smaller prefix and the RAM cache keep the first readable surface off
that whole-book path. Do not use these engine numbers as end-to-end guarantees.

Final logic verification: `./gradlew test` passed 1,912 tests, zero failures/errors,
six skipped. Signed R8 assembly and release vital lint passed. Generated native
checks cover closed-page equivalence, split paragraphs, lazy preparation and
ordinary snapshot/full pagination equivalence. Private-book complete-parse and
bounded-snapshot checks passed independently on Pixel. No emulator was used.
The real ReaderScreen also passed a native UI check: expanded partial pagination
(53 pages) and complete pagination (108 pages) both kept position zero without
input. This uses a debug-only host, isolated from launcher navigation, and keeps
the physical screen active while waiting for frames. It is absent from release.

`./gradlew test` passed: 1,864 tests, zero failures/errors, six skipped (including
the opt-in local profiling test). `:app:assembleDebug` passed. Regressions cover
CSS inheritance/selectors/direction, 50,000-entry logarithmic lookups, bidi
offsets, cover/empty chapter ranges, partial-to-full publication, cache hits,
empty previews and cancellation of obsolete calculations. Progressive tests also
cover immutable merged prefixes, known notes/navigation, late resource errors,
shared preload/cancellation/source identity, prefix-to-full position retention,
fraction/completion guards and incomplete-cache suppression. Two generated
Android tests passed on Pixel: closed preview/full page-cut equivalence (also
inside a long paragraph) and avoiding preparation of distant chapters.

Full release UI handoff and page-turn smoothness during the remaining parse
still need visual verification. Reading can reach a loading tail while more
content is prepared. Distant notes/navigation can remain unavailable until
parsing completes. An uncached deep resume must parse preceding spine documents;
one giant source document still has to be mapped before its prefix is available.
The bounded snapshot cache is not constant-time random access to an arbitrary
uncached chapter.

The private EPUB is not included in fixtures. To profile another local EPUB:

```sh
FROGREADER_PROFILE_EPUB='/absolute/path/to/book.epub' \
FROGREADER_PROFILE_JFR='/tmp/frogreader-large-book.jfr' \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :app:testDebugUnitTest --tests '*LargeEpubProfileTest'
```

The JFR path is optional. Parse timings, content counts and preview item counts
are written to the unit-test report's standard output; no timing threshold is
used in CI.
