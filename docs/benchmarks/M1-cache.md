# M1 — complete indexed chapter cache

On 2026-11-10, the monolithic gzip cache was replaced by one FRB implementation.

This report records the M1 baseline with FRB v1. The current implementation uses
FRB v2 with a separate lightweight index; see [M2 storage progress](M2-random-access.md).
The old format is not kept as a compatibility reader.

Re:Zero and Hebrew Bible now save and restore completely. Their models match
the source parser by a streaming SHA-256 of every serialized field and by
`PaginationCache.contentSignature`; native page geometry also matches.

The previous `ParsedContentCache`, its rejection markers, retired benchmark
harnesses and unused `ReaderEngineFlags` class are removed. No old cache format
is read or migrated. This follows the user's explicit preference: they are the
only user at this stage and accept losing book-related app data, so obsolete
implementations and compatibility shims are unnecessary. The Kotlin source
parsers remain current implementations until their later migration stages.

## Implementation

- Rust FRB has a checked header and chapter index, independently LZ4-compressed
  global/chapter blocks and CRC32 checks. The index preserves exact element
  counts and flat starts, including empty chapters. Source path, size, timestamp,
  model revision and full SHA-256 are bound to each entry.
- JSON uses the existing lossless model serializers, streamed through bounded
  temporary files. Whole books are never passed through JNI byte arrays. The
  restored model includes every chapter, note, link, navigation entry, font
  descriptor, linked document and publisher-layout field.
- Native sessions own file descriptors across block reads, so replacement cannot
  mix generations. Only complete fsynced files are atomically published. If the
  filesystem cannot perform atomic replacement, the write fails as a cache miss.
- Interrupted scratch trees are swept before quota accounting. A shared active
  set protects live readers/writers; cleanup does not follow symbolic links.
- The original ReaderCacheBudget leases and LRU logic still enforce 64 MiB parsed
  content and 128 MiB combined storage. A book can use that parsed-content budget;
  the former separate 8 MiB cap was too small for these actual FRBs. Opening
  snapshots have an 8 MiB cap and share the per-book budget with the full entry.
- A previous full RAM model is released when another book begins loading,
  without releasing resources held by a live reader. This avoids retaining an
  obsolete whole book throughout the next large decode.
- Rust is required in every APK. `-PrustEngineApp=true` now controls only the
  separate application ID/name. Testing used `knitipka.frogreader.rust` beside
  the original installation.

The format bounds are 64 MiB per decoded block, 4 GiB decoded in total, 64 MiB
per compressed container, 1 MiB source metadata, 100,000 chapters and 64 live
native sessions. The total model is no longer limited to 64 MiB. See
[the binary format](../../rust/FRB_FORMAT.md) and [build guide](../../rust/README.md).

## Host results

Fresh before measurements were captured before changing the cache code. After
measurements used OpenJDK 21.0.7 on macOS aarch64, with a 3 GiB maximum test heap
for the private corpora. Each row is one run, not a performance distribution.
The Kotlin parser did not change; its timing variation is not a Rust parse gain.

| Corpus | Chapters / elements | Canonical model JSON bytes | Previous complete cache | FRB bytes | FRB save (ms) | Full restore at 0% (ms) | Full restore at 85% (ms) |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| Re:Zero | 677 / 190,851 | 109,781,201 | Rejected | 19,507,258 | 512.847 | 837.226 | 675.470 |
| Hebrew Bible | 122 / 44,446 | 95,924,082 | Rejected | 15,310,151 | 578.174 | 971.848 | 682.143 |

Both complete models exceed the previous 64 MiB monolithic bound; their FRBs
also exceed the former 8 MiB entry cap. Both fit within the retained 64 MiB
parsed-content budget. Successful restores match complete-model digests and
layout signatures. No `full-rejected.bin` is created.

Raw host phase/heap tables: [Re:Zero before](M1-rezero-before-host.md),
[Hebrew Bible before](M1-bible-before-host.md), [Re:Zero after](M1-rezero-host.md),
[Hebrew Bible after](M1-bible-host.md), [generated EPUB](M1-generated-host.md).
The source SHA-256 values are the same as [the M0 corpus](M0-baseline.md).

## Pixel 9a results

Real debug APK, fixed diagnostic layout: 360×600 px, density 1, 18 sp serif, chapter
breaks enabled. Publication font resolution/language and actual ReaderScreen
frames are outside this test. A bounded partial CPU wake lock prevented suspend;
the display and permanent power settings were unchanged.

### Complete model

| Corpus | Current cold full parse (ms) | FRB save (ms) | Full restore at 0% (ms) | Full restore plus pages at 0% (ms) | Full restore at 85% (ms) | Full restore plus pages at 85% (ms) | FRB bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Generated EPUB | 3906.084 | 737.491 | 3201.795 | 3222.626 | 2257.544 | 2471.662 | 98946 |
| Re:Zero | 41004.460 | 4907.314 | 21611.497 | 21626.114 | 20074.577 | 20338.235 | 19507265 |
| Hebrew Bible | 32131.747 | 5654.023 | 23286.720 | 23314.418 | 21675.021 | 21848.865 | 15310273 |

Each corpus passed a complete model digest, full pagination signature and exact
native page geometry at both positions. Page geometry comparisons include table
sizes/grids, side boxes and publisher boxes wherever present. Exhaustive checks
run outside opening timers. The source model leaves scope before restore; an
explicit GC request/finalization occurs outside each restore timer and is logged.

`cold_parse` includes its synchronous first-preview pagination callback. Warm
rows above restore the entire model, regardless of saved position; a returned
page list can include pages preceding the saved anchor in its current chapter.
These are not selective chapter reads or a displayed UI frame measurement.

### Fast beginning snapshot

`opening.frb` uses the same codec and restores only the bounded opening model.
These tests stop the source parser at its first prefix, save a 65-element
snapshot, and compare restored model/signature plus native page cuts against
the original larger prefix. They do not wait for a complete-book parse.

| Corpus | Parser prefix (ms) | Source prefix / cached elements | Opening FRB bytes | Restore only (ms) | Restore plus first three pages (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Generated EPUB | 703.636 | 97 / 65 | 5443 | 456.380 | 489.020 |
| Re:Zero | 831.642 | 67 / 65 | 9099 | 111.934 | 135.290 |
| Hebrew Bible | 1536.728 | 1027 / 65 | 3114 | 149.047 | 178.365 |

The fast opening path remains distinct from the 20–23 s full restore. Comparing
M1 full-model restore with an M0 prefix-only restore would be misleading.
M0 had no successful complete cache or 85% disk prefix for the private books.
Its deep cold first-page preparation was 28,091 ms for Re:Zero and 21,442 ms for
Hebrew Bible; M1's full-cache-plus-page result is 20,338 ms and 21,849 ms respectively.
This establishes successful persistence and avoids repeat source parsing; it
shows no universal deep first-page speedup. Selective decoding is M2.

### Sampled memory

| Corpus | Maximum sampled JVM used heap across full-model phases (MiB) | Maximum sampled native allocator heap across full-model phases (MiB) | Maximum sampled JVM heap across opening-only phases (MiB) |
| --- | ---: | ---: | ---: |
| Generated EPUB | 209.41 | 46.80 | 45.82 |
| Re:Zero | 255.97 | 45.46 | 19.16 |
| Hebrew Bible | 256.00 | 94.94 | 34.00 |

Samples are whole-process used JVM/native allocator heap every 2 ms, not per-book
allocations, RSS/PSS or guaranteed absolute peaks. Timings also depend on GC,
JIT, OS caches and device temperature. Full-model memory remains substantial;
M2 is needed to avoid decoding/retaining unrelated chapters on resume.

Complete device records are in [M1-device.md](M1-device.md).

## Verification

- Rust workspace: 16 tests passed; rustfmt and Clippy with warnings denied passed.
- Full JVM suite: 2,221 tests, zero failures/errors, 8 skipped (2,183 app and 38
  PDF-viewer tests). This includes rich-model/style parity, a model above 64 MiB,
  corrupt/truncated cache misses, source identity, resources, atomic replacement,
  interrupted scratch recovery, safe symlink cleanup, global quotas, pins and
  previous-model RAM release while the next decode is blocked.
- Six Pixel corpus tests passed: complete and opening-only checks for generated
  EPUB, Re:Zero and Hebrew Bible. Native cache operations remain available after
  each check. The CPU wake locks were confirmed released afterward.
- Standard debug build and isolated debug/device-test APKs assembled; Rust is
  packaged for all four ABIs. The isolated debug APK was installed separately.
- R8 release assembly and debug lint passed; lint reported 0 errors, 100 warnings
  and 2 hints. Unsigned release was not installed.
- Debug/release Rust libraries have 16 KiB ELF LOAD alignment, all FRB JNI exports
  and no host panic-probe symbol. Both APK ZIP alignment checks passed.
- Retired cache/flag class descriptors are absent from the unshrunk debug APK.
- GitHub CI execution, actual ReaderScreen first-frame/gesture checks and a
  release installation are not claimed by these local/device-engine checks.
- The six corpus runs preceded the final removal of the non-atomic filesystem
  replacement fallback. The atomic path exercised by those runs is unchanged;
  the final fail-closed policy passed the full JVM suite, builds and lint. The
  Pixel disconnected before the optional final APK reinstall/smoke, so that
  additional run was not performed. The latest APK is available locally.

Changes remain on `rust/m1-chunked-cache`, preserving preexisting user edits.
No commit/PR mixes those unrelated changes. M2, M2F and M3–M6 remain
unimplemented. The revised plan prioritizes full-reader
interactivity, complete source preparation and exact pagination immediately after
M1; these goals are not satisfied by this cache-only milestone.

## Reproduce

With the pinned toolchain installed as described in the build guide:

```sh
cargo test --workspace --manifest-path rust/Cargo.toml --locked
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test
FROGREADER_PROFILE_EPUB='/absolute/path/to/book.epub' \
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
./gradlew :app:testDebugUnitTest --tests '*ChunkedCacheBenchmarkTest' \
  -PengineBenchmarkReport=/private/tmp/frogreader-M1-host.md
```

After building/installing only the separate Rust APK and its test APK, run one
method per process; use the relative private source argument when appropriate:

```sh
adb shell am instrument -w -r \
  -e class 'com.example.frogreader.engine.ChunkedCacheDeviceTest#completeChunkedCachePreservesModelSignatureAndNativePageCuts' \
  -e engineProfileEpub engine-benchmark/re-zero.epub \
  knitipka.frogreader.rust.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as knitipka.frogreader.rust cat files/engine-benchmark/m1-latest.log

adb shell am instrument -w -r \
  -e class 'com.example.frogreader.engine.ChunkedCacheDeviceTest#openingSnapshotRestoresNativePageCuts' \
  -e engineProfileEpub engine-benchmark/hebrew-bible.epub \
  knitipka.frogreader.rust.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as knitipka.frogreader.rust cat files/engine-benchmark/m1-opening-latest.log
```

Omit `engineProfileEpub` for the generated fixture. Supplied sources must be
inside the experimental app's files directory; the argument is validated.
Test-derived trees are removed in `finally`; the small records and source copies
remain for repeat runs. No private book bytes are checked into this repository.
