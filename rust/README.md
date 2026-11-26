# Reader engine build and cache

Parsed content uses one Rust FRB v2 format. The lightweight whole-book index,
heavy global model tables and each chapter are independently serialized,
LZ4-compressed and CRC-checked. EPUB metadata, complete parses and targeted
source windows read the ZIP directory and expand entries through Rust. The
EPUB container, OPF, NCX and NAV readers return typed package and navigation
fields to Kotlin, which maps chapter XHTML and CSS into the reader model.
A missing or invalid FRB is rebuilt from the original book. No old cache
format is read or migrated.

Install rustup and put Cargo on `PATH`. `rust-toolchain.toml` pins Rust 1.90.0,
Android targets, rustfmt and Clippy. Install the pinned Android builder:

```sh
rustup show
cargo install cargo-ndk --version 4.1.2 --locked
```

The Android SDK must contain NDK `30.0.16248370` and CMake `3.22.1`.
Rust is required for every APK and for host JNI tests. Gradle selects the exact
NDK, API 28, four ABIs and 16 KiB ELF alignment, independently of the PDF CMake build.

```sh
cargo test --workspace --manifest-path rust/Cargo.toml --locked
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PrustEngineApp=true
```

On Linux use the installed JDK instead of the macOS `JAVA_HOME` above.
`-PcargoExecutable=/absolute/path/to/cargo` selects a nonstandard Cargo install;
its toolchain and cargo-ndk must be available in the build environment.

The default application ID is `knitipka.frogreader`. `-PrustEngineApp=true`
changes it to `knitipka.frogreader.rust` and labels it **FrogReader (Rust)** so
it can be installed beside the existing app with separate books, SQLite and
caches. The property controls identity, not which cache implementation is used.
Use that separate identity for phone verification during development.

`cargoBuildHost` builds JNI into `app/build/rust/host`. Its `test-hooks` feature
adds a deliberate panic probe for host tests. `cargoNdkBuild` uses the separate
`app/build/rust/android` directory without that feature and publishes libraries
to `app/build/generated/rustJniLibs`. Derived cache operations can return a
miss after a JNI failure. The EPUB source reader requires the library to load
and pass the ABI handshake. It does not depend on the derived-cache session
remaining healthy. Cancellation and fatal JVM errors propagate.

The EPUB reader holds a native file session for one parse. The ZIP library
validates entry CRCs; the JNI reader also requires the decoded size to match
the directory. Kotlin checks safe paths, per-entry size and compression ratio,
and the open's aggregate expansion budget. Images are streamed into temporary
files and published only when extraction completes. The mixed ZIP import
probe and the metadata ZIP rewrite still use their separate Java I/O paths.

See [FRB_FORMAT.md](FRB_FORMAT.md) for the binary contract, native session
ownership and bounds. Readers retain an open file descriptor, so replacement
cannot mix cache generations. Each requested block returns as a bounded byte
array and Kotlin decodes it in memory. Writes publish the completed FRB
atomically. Interrupted write scratch is swept without touching live sessions.
The binary model codec covers the manifest, global tables and chapters.

The existing ReaderCacheBudget pin and LRU mechanism covers FRB, opening
previews, images/fonts and pagination. Combined and parsed-content quotas stay
128 MiB and 64 MiB. A book entry can use that 64 MiB parsed budget; the old 8 MiB
monolithic per-book cap is gone. Opening snapshots are independently capped at
8 MiB and share the per-book budget with the complete file.

`ChunkedContentCache.openIndexed` reads the complete navigation/chapter index
without decoding notes, linked documents or sequential text. The native reader
keeps one file generation open; explicit `readChapter(K)` accesses block K+2.
The manifest also stores resource paths and the publisher typography summary,
so those no longer require visiting every chapter during an indexed open.
The manifest is capped at 8 MiB decoded. FRB v1 is invalidated, without a legacy
reader; source books and user-authored storage are unaffected.

With a valid FRB, ReaderScreen opens the index and a window of up to three
chapters. The TOC, progress and destinations use global source coordinates.
Search reads one chapter at a time, and opening a note or linked document
requests the auxiliary tables. A cache miss prepares the book from its source.
Storage and reader measurements are in [M2-random-access.md](../docs/benchmarks/M2-random-access.md).
The Rust [page index](PAGE_INDEX_FORMAT.md) stores exact global page anchors
for a matching book generation and layout. Chapter-break layouts use a
standalone index. Continuous layouts bundle the index and exact page geometry
in `layout.frb` so the reader can restore pages across source chapters.
The M1 baseline is in [M1-cache.md](../docs/benchmarks/M1-cache.md); the historical
M0 baseline remains in [M0-baseline.md](../docs/benchmarks/M0-baseline.md).
