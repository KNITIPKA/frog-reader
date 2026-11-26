# M3 Native FRB Search and WOFF/WOFF2 Unpackers

On 2026-11-18 (Wave 2 completion), M3.1 true zero-`Chapter`-allocation FRB book
search + search-text normalization (`book_search.rs`, `book_search_jni.rs`,
`RustSearchNative.kt`, `ChunkedContentCache.kt`, `IndexedBookSession.kt`,
`IndexedReaderSearch.kt`) and M3.2 native WOFF/WOFF2 font unpackers
(`woff.rs`, `woff_jni.rs`, `RustWoffNative.kt`, `WoffDecoder.kt`,
`Woff2Decoder.kt`) were integrated and verified on host:

- **M3.1 (`book_search.rs` + `IndexedReaderSearch.kt`):** `ChunkedContentCache`
  exposes `readChapterBlockBytes(index)` (reading LZ4-decompressed FRB v2
  payloads with `sourceStatMatches()` without calling `FrbModelCodec.decode`),
  and `IndexedReaderSearch.searchIndexedBook` passes those raw bytes directly to
  `RustSearchNative.searchFrbChapter` when `!hasUnpairedSurrogate(query)`.
  Rust decodes FRB v2 / revision-5 payloads in native memory and scans with
  SIMD `memchr` without allocating `Chapter` or `ContentElement` objects in the
  JVM, applying `SEARCH_RESULT_LIMIT = 300`, word-aligned `-36`/`+110` UTF-16
  snippet windows with `"…"` ellipses and `\n` → `' '`, `hideFootnotes` over
  `FOOTNOTE_TAG` while preserving `HIDDEN_TEXT_TAG` and `Table` `flatText`, plus
  native `SearchTextNormalizer` helpers (`normalizeText`, `tokenizeQuery`,
  `toFts5PrefixQuery`). Only `null` block reads or unpaired-surrogate edge cases
  fall back to `readChapter`.
- **M3.2 (`woff.rs` + `WoffDecoder.kt` / `Woff2Decoder.kt`):** `WoffDecoder.kt`
  removed the duplicate `decodeKotlin` fallback so `RustWoffNative.decodeWoff`
  (`flate2` `zlib-rs`, `MAX_TABLES = 64`, `MAX_TABLE_SIZE = 10_000_000`) is the
  single implementation. `Woff2Decoder.kt` pairs `org.brotli:dec` stream
  decompression (retained under `--locked` constraints) with native Rust
  directory validation (`inspectWoff2`) and table reconstruction
  (`decodeWoff2FromStream`: `uintBase128`, known tags, `glyf`/`loca` triplet
  decoding, `hmtx` lsb reconstruction).

## Host verification and benchmarks (Mac OS X aarch64, OpenJDK 21.0.7, Rust 1.90.0)

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | 10/10 M3 Rust tests passed (142/142 workspace total) | `book_search`: 5, `woff`: 5 |
| `cargo clippy ... -- -D warnings` | 0 warnings | Workspace-wide |
| `RustSearchWoffTest` (JVM) | 6/6 passed (`0.208 s`) | Verifies zero-`readChapter` FRB block search, `SearchTextNormalizer`, native-only `WoffDecoder`, `Woff2Decoder`, and `M3_BENCHMARK` |
| `IndexedReaderSearchTest` (JVM) | 6/6 passed (`0.068 s`) | Reader search integration |
| `WoffDecoderTest` (JVM) | 3/3 passed (`0.001 s`) | Font unpacker boundary/error tests |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`49,341,627 B`) | All 6 `RustSearchNative` and 3 `RustWoffNative` JNI symbols exported across all 4 ABIs (`16384` `PT_LOAD` alignment); preserved via `@Keep` and `rules.keep` |

### `M3_BENCHMARK` host measurements (`RustSearchWoffTest`)

- **FRB search (`24` chapters, `1,583,250` raw payload bytes, `29,998` FRB file bytes, `192` matches):**
  - `RustSearchNative.searchFrbFile`: **1.10 ms** (**1,434.4 MB/s**)
  - `IndexedReaderSearch.searchIndexedBook` (zero-`Chapter`-allocation `readChapterBlockBytes` + `searchFrbChapter`): **2.18 ms** (**725.0 MB/s**)
  - Decoded-chapter Kotlin baseline (`FrbModelCodec.decode` + `searchDecodedChapter`): **4.10 ms** (**386.4 MB/s**)
- **WOFF / WOFF2 unpack (`woff`: `1,315 B -> 66,604 B`; `woff2`: `33,027 B -> 33,060 B`):**
  - `WoffDecoder.decode` (native Rust `flate2`): **0.0198 ms**
  - `Woff2Decoder.decode` (hybrid Brotli + Rust table reconstruction): **0.0454 ms** (vs `Woff2Decoder.decodeKotlin` **0.2597 ms**; native `decodeWoff2FromStream` reconstruction alone **0.0079 ms**)

## Wave 19 — Native Rust WOFF2 Brotli & Release APK Verification (`BETA 2 (wave 19)`, `code=19`)

Wave 19 completed **M3.2** by moving WOFF2 Brotli decompression into pure safe Rust (`brotli-decompressor = 6.4.0`, locked with `alloc-stdlib 0.3.0` and `alloc-no-stdlib 3.0.0`), removing the `RustEpubPackage.decodeWoff2` JNI callback (`epub_frb.rs` calls `crate::woff::decode_woff2` directly), deleting ~610 lines of Kotlin WOFF2/Brotli fallback in `Woff2Decoder.kt`, and moving `org.brotli:dec` from `implementation` to `testImplementation`.

| Check / Benchmark | Wave 19 Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | **232/232 passed** (`0` failed) | Includes end-to-end Brotli + `glyf`/`loca`/`hmtx` WOFF2 tests and direct `epub_frb` WOFF2 decoding |
| `cargo clippy ... -- -D warnings` | **0 warnings** | Workspace-wide (`--all-targets --all-features --locked`) |
| `WoffDecoderTest` / `Woff2DecoderTest` / `RustSearchWoffTest` | **3/3**, **7/7**, **7/7** passed | `woff2_rust_ms = 0.0093 ms` on 33 KB WOFF2 (**4.9×** faster than Wave 2 hybrid `0.0454 ms`) |
| Full JVM `./gradlew test` | **2,418 passed, 8 skipped, 0 failed** | `:app` (`2,374` passed, `8` skipped, `0` failed, incl. `ReaderLoadTraceTest` **10/10**); `:pdf-viewer` (`44` passed, `0` failed) |
| Signed R8 Release APK (`knitipka.frogreader.rust`, `code=19`) | **`56,397,996 B`** | `libfrogreader_core.so` in all 4 ABIs (`arm64-v8a` `4,223,992 B`, `armeabi-v7a` `3,090,520 B`, `x86` `5,146,900 B`, `x86_64` `4,738,408 B`): **`112`** `Java_*` JNI symbols per ABI, **`0`** `panicProbe`, **`0x4000`** `PT_LOAD` alignment; **`0` `org/commonmark`** and **`0` `org/brotli`** classes in `classes.dex` |

## Wave 20 Pixel 9a device benchmark (BETA 2 (wave 20), code=20)

Measured on Google Pixel 9a (`58071JEBF07361`, Android 16 / API 36, `tegu`) via `RustEngineDeviceBenchmarkTest` (**3/3 PASSED**, `8.388 s`):

| Benchmark | Measured Pixel 9a Result | Notes |
| --- | --- | --- |
| `m3_search` | `median_ms = 48.111 ms`, **`throughput_mb_s = 49.46 MB/s`** | `corpus_bytes = 2,379,641`, `matches = 160` |
| `m3_woff` (WOFF) | **`woff_median_ms = 0.4137 ms`** | Native Rust `flate2` (`zlib-rs`) |
| `m3_woff` (WOFF2) | **`woff2_median_ms = 0.2962 ms`** | Pure native Rust Brotli (`brotli-decompressor 6.4.0`) |

## Remaining scope

- `org.brotli:dec` is eliminated from the runtime APK (`0` `org/brotli` classes in `classes.dex`).
- Both M3.1 on-device search throughput and M3.2 pure Rust WOFF/WOFF2 unpacking are verified on Pixel 9a.
