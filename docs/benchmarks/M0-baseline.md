# M0 engine baseline and verification

Historical M0 snapshot: the old cache and benchmark harnesses were removed in
M1. The commands below record the procedure used at that time. Current code,
commands and results are in [M1-cache.md](M1-cache.md); no retired implementation
is retained for compatibility.

M0 adds the isolated Rust build and bridge. The Kotlin parsers and parsed-content
cache remain the implementation used for reading. This report must not be used
as evidence of a Rust parser/cache speedup or of completion of M1–M6.

## Reproduce the host measurements

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:testDebugUnitTest --tests '*EngineBenchmarkTest' \
  -PengineBenchmarkReport=/private/tmp/frogreader-M0-baseline.md --rerun-tasks
```

The report destination is optional; the test also writes its Markdown table and
`ENGINE_BENCHMARK` records into JUnit standard output, captured in
`app/build/test-results/testDebugUnitTest/TEST-com.example.frogreader.engine.EngineBenchmarkTest.xml`.

The default input is generated deterministically: 16 EPUB spine documents, 96
paragraphs per document, repeated inline emphasis/bold spans, headings, stylesheet
rules, and a navigation document. No private books are committed.

To measure a local EPUB, run the same command with
`FROGREADER_PROFILE_EPUB=/absolute/path/to/book.epub`. Use a separate report path
per corpus/run. Existing derived resources and parsed caches are never reused:
each invocation creates a fresh temporary resource/cache directory and cleans it
up afterwards. This does not reset OS page caches, JVM classloading or JIT state.

## Results

Before the Rust build/bridge changes: 2026-11-10 at 01:34 Kyiv time
(2026-11-09 23:34 UTC), OpenJDK 21.0.7, macOS aarch64. One host run;
not a statistical distribution.

Corpus: deterministic generated EPUB (16 spine documents, 96 paragraphs per document); 23847 source bytes; 16 parsed chapters; 1552 elements.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 297.869 | 31733760 | 67028560 | n/a | 132.378 | n/a |
| parse_resume | 85% | 135.172 | 67028560 | 118604120 | n/a | 118.612 | n/a |
| preview_save | 0% | 40.783 | 49812120 | 56103576 | true | n/a | 7917 |
| preview_restore | 0% | 39.227 | 57152152 | 70783640 | true | n/a | 7917 |
| preview_save | 85% | 139.772 | 74977944 | 110629528 | true | n/a | 161103 |
| preview_restore | 85% | 130.018 | 110629528 | 134084584 | true | n/a | 161103 |
| complete_save | all | 134.858 | 120671944 | 136400584 | true | n/a | 179871 |
| complete_restore | all | 69.863 | 74013720 | 140074008 | true | n/a | 179871 |

Total parsed-cache bytes across independent benchmark entries: 348891.


## After the M0 infrastructure changes

A second fresh host-test JVM on the same generated corpus, 2026-11-10 at 01:36
Kyiv time. Reading still uses exactly the same Kotlin parser and cache. These
single-run differences are run-to-run variation, not a Rust speedup.

| Phase | Position | Time (ms) | Heap before (bytes) | Sampled heap peak (bytes) | Success | Prefix ready (ms) | Cache bytes |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| parse_start | 0% | 301.437 | 31531008 | 66821544 | n/a | 132.864 | n/a |
| parse_resume | 85% | 139.862 | 66821544 | 118500496 | n/a | 119.230 | n/a |
| preview_save | 0% | 57.849 | 51342144 | 58682176 | true | n/a | 7918 |
| preview_restore | 0% | 53.019 | 58682176 | 72313664 | true | n/a | 7918 |
| preview_save | 85% | 138.748 | 77556544 | 113208128 | true | n/a | 161104 |
| preview_restore | 85% | 126.261 | 113208128 | 131250064 | true | n/a | 161104 |
| complete_save | all | 139.458 | 122174768 | 136854832 | true | n/a | 179872 |
| complete_restore | all | 79.931 | 76795672 | 138661656 | true | n/a | 179872 |

## Supplied large-book host baseline

Measured on 2026-11-10 at 01:38 Kyiv time with the same JDK/macOS. The profiling
JVM has a 3 GiB maximum heap; reported peaks are sampled used heap, not that
maximum. Private EPUB bytes are not added to the repository.

| Corpus | Source bytes | Chapters / elements | Full parse at 0% (ms) | Prefix ready at 0% (ms) | Full parse at 85% (ms) | Prefix ready at 85% (ms) | Preview restore at 0% (ms) | Preview at 85% | Complete cache |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Re:Zero | 9,648,164 | 677 / 190,851 | 2,023.069 | 173.536 | 1,271.799 | 1,091.820 | 25.264 | Rejected | Rejected |
| Hebrew Bible | 33,221,611 | 122 / 44,446 | 1,597.635 | 203.451 | 957.651 | 819.341 | 27.751 | Rejected | Rejected |

A rejected cache has no warm-restore timing. The near-zero miss times in the
raw harness output must not be interpreted as successful opens. Both sources
reproduce the complete-cache rejection and additionally expose the 85% opening
snapshot rejection. The saved beginning prefixes remain usable.

Complete phase tables, cache sizes and sampled heap peaks:
[Re:Zero](M0-rezero-host.md), [Hebrew Bible](M0-bible-host.md).

Source SHA-256 values, to identify repeated measurements without checking in books:

- Re:Zero: `143f9ecc487eee0be931928eed47dd3edd749be97dd8583228e6e2944fc23c53`.
- Hebrew Bible: `a8fbb21090c8bc89cb8fb529a217ed335397fc423e078c0ccc78a9c543e21d5f`.

## Measurement definitions

`parse_start` and `parse_resume` measure complete Kotlin EPUB parses with empty
derived-resource directories. Resume targets 85% of the first parse's exact
element count. The parses run sequentially in one JVM; only the first parse has
fresh JVM execution state for this test. Neither is a process-cold Android launch.

`prefix_ready_ms` measures the synchronous progressive parser callback, before
Android text measurement, pagination, or drawing. It is not time to a displayed
page. The complete parse duration includes producing that prefix.

At 0% and 85%, `preview_save` and `preview_restore` exercise the actual
`openingContentSnapshot` and `ParsedContentCache`. The current format retains the
prefix from source index zero, so the deep preview can be much larger. The full
cache rows record complete save/restore independently. Cache misses/rejections
are reported explicitly; a failed save is not timed as a successful restore.

Successful restore content is checked against the original with streaming
SHA-256 over the complete serialized model. Digest checks run outside the timed
regions. The generated fixture must save and restore successfully; an oversized
private EPUB may legitimately expose the existing rejection behavior.

Heap figures are process-wide used JVM heap sampled every 2 ms during each phase.
They include retained objects from earlier phases and are affected by GC. They
are not allocated bytes, native memory, or Android heap measurements. Disk bytes
include snapshot headers and rejection markers; the total includes independent
entries used to measure each position. Sampling can miss brief peaks.

## Pixel 9a baseline

Measured on 2026-11-10, approximately 01:50–01:54 Kyiv time, in the isolated
debug app `knitipka.frogreader.rust`. Each corpus ran in a fresh instrumentation
process, with fresh parser-resource and parsed-cache directories. A bounded
partial CPU wake lock prevented suspend during each test. The display remained
in Doze; no system display/power setting was changed. Earlier runs without this
lock were discarded from the table. No release UI timing is claimed.

| Corpus | Prepared pages at 0% (ms) | Full parse at 0% (ms) | Warm opening at 0% (ms) | Prepared pages at 85% (ms) | Full parse at 85% (ms) | Warm opening at 85% | Complete cache |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Generated EPUB | 882.292 | 3961.195 | 472.710 | 2082.996 | 2334.759 | 2866.970 ms | Saved |
| Re:Zero | 826.629 | 38770.559 | 121.758 | 28091.443 | 33353.843 | Unavailable: cache rejected | Rejected |
| Hebrew Bible | 1775.296 | 33032.651 | 160.540 | 21441.956 | 25605.445 | Unavailable: cache rejected | Rejected |

| Corpus | Maximum sampled used JVM heap across measured phases (MiB) | Maximum sampled native allocator heap across measured phases (MiB) | Beginning prefix (bytes) | Deep prefix (bytes) | Beginning cache plus complete entry/marker (bytes) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Generated EPUB | 244.66 | 60.66 | 7918 | 161104 | 187790 |
| Re:Zero | 255.95 | 46.69 | 6799 | 0 | 6954 |
| Hebrew Bible | 252.67 | 105.08 | 2009 | 0 | 2169 |

All three instrumentation runs passed. Each checked real Android JNI transport,
cold-prefix versus complete-model page cuts at both positions, and cached-prefix
page cuts wherever the cache saved successfully. The two private deep prefixes
were rejected, so no successful warm opening at 85% is reported for them.

The diagnostic layout is 360×600 px, density 1, 18sp serif, with chapter breaks;
it does not resolve publication fonts or pass the publication language as
ReaderScreen does. `first_three_pages_ms` ends once pagination has closed three
pages from the saved anchor. Returned page lists also include earlier pages in
the current chapter (69 for Re:Zero at 85%, 17 for Hebrew Bible, 38 for the
generated fixture). The full parse duration includes synchronous preview
pagination in the parser callback; it is not isolated background-parser CPU time.
These are engine preparation/pagination measurements, not displayed UI frames.

Heap figures are whole-process allocator/used-heap samples every 2 ms, including
retained objects, fonts, JNI and app initialization; they are not per-book
allocation totals, RSS/PSS, or guaranteed absolute peaks. CPU temperature, GC,
JIT and OS caches are uncontrolled. Single debug runs establish a reproducible
baseline and correctness checks, not a performance distribution or Rust speedup.
Host and Android generated EPUBs have the same 16×96 text/style structure, with
different ZIP/container whitespace (23,847 versus 23,843 source bytes).

Complete device records are retained in [M0-device.md](M0-device.md).

To reproduce after installing the experimental app and its test APK:

```sh
adb shell am instrument -w -r \
  -e class com.example.frogreader.engine.EngineDeviceBenchmarkTest \
  knitipka.frogreader.rust.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as knitipka.frogreader.rust cat files/engine-benchmark/latest.log
```

For a private corpus, first copy the EPUB into the experimental app's
`files/engine-benchmark/` folder (for example via `adb push` to `/data/local/tmp/`
and `adb shell run-as knitipka.frogreader.rust cp … files/engine-benchmark/…`).
Add `-e engineProfileEpub engine-benchmark/re-zero.epub` or the corresponding
relative filename to the instrumentation command. The argument is checked to
remain inside the experimental app's files directory. Test-derived caches are
removed in `finally`; the small report and supplied source copies remain for
repeat runs. The timed CPU wake lock is released in `finally`.

## Verification gates

- Rust workspace tests: 3 passed; rustfmt and Clippy with warnings denied passed.
- Full JVM suite with real host JNI tests: 2,218 tests, zero failures/errors,
  8 skipped (2,180 app tests and 38 PDF-viewer tests).
- Default `./gradlew test assembleDebug`: passed. Standard APK identity remains
  `knitipka.frogreader`, with no `libfrogreader_core.so` packaged.
- Separate `knitipka.frogreader.rust` debug APK and Android test APK: built and
  installed alongside the original app. Generated, Re:Zero and Hebrew Bible
  device benchmarks passed. The variant-aware app-context smoke test also passed;
  the benchmark CPU lock was confirmed released after completion.
- Rust experiment R8 release assembly and debug lint: passed. Lint reported
  0 errors, 100 warnings and 2 hints. Unsigned release was not installed.
- All four Android Rust ABIs packaged; ELF LOAD alignment is at least 16 KiB;
  Android artifacts contain protocol/echo JNI exports and no panic-probe export.
  Debug and release APK ZIP alignment checks passed.
- Configuration-cache reuse verified; CI YAML parsed. GitHub Actions execution
  and server-side branch protection are not verified by these local checks.
- Actual ReaderScreen first displayed frame and full visual/gesture/release-install
  checks remain outside this M0 baseline.

M0 has no parser or cache algorithm change. M1–M6 require their own
implementation, differential, layout, memory and device measurements. Existing
working-tree changes were preserved; the M0 work remains on `rust/m0-foundation`
without a commit/PR that would mix those unrelated changes.
