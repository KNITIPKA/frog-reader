# M5.2 Native MOBI / AZW3 (KF8) Container, Decoders, and Index Assembler

On 2026-11-18 (Wave 2 completion), the entire MOBI/AZW3 PDB container framing,
header parsing, decompression, font record unpacking, index parsing, and KF8
reassembly stack was ported to Rust (`rust/frogreader-core/src/mobi.rs` and
`mobi_jni.rs`), wired via `RustMobiNative.kt`, and all duplicate Kotlin decoder
fallbacks were removed:

- `PdbFile` header sniffing (`is_pdb_book`), header validation
  (`parse_pdb_header`), and record offset table validation (`parse_pdb_offsets`)
  in Rust, preserving `PdbSource` lazy reads, `ReaderResourceLimits` accounting,
  and exact `ResourceLimitException`/`IOException` semantics.
- `MobiHeaders` (`parse_palmdoc_header`, `parse_mobi_header` with `encoding_rs`
  codepage decoding and KF8 fields gated on `file_version >= 8`, and
  `parse_exth` with 0..3 padding bytes and mid-list corruption recovery).
- `PalmDoc` LZ77 decompression + `TrailingEntries` backward-varint record
  trimming (`PalmDocDecoder.kt` duplicate Kotlin fallback deleted).
- `HuffCdic` canonical Huffman + CDIC phrase dictionary decompression enforcing
  `MAX_DEPTH = 32`, dictionary byte budgets, and output byte limits
  (`HuffCdicDecoder.kt` duplicate Kotlin fallback deleted).
- `MobiFontRecord` (`FONT` header, 1040-byte XOR deobfuscation, and zlib
  inflation via `flate2` + `zlib-rs`; `MobiFontRecord.kt` Kotlin fallback
  deleted).
- `MobiIndex` (`INDX`/`TAGX`/`IDXT`/`CNCX` parsing, forward varints, tag maps)
  and `Kf8Assembler` (`FDST` flow splitting and `SKEL`/`FRAG` XHTML part
  reassembly with resource-limit checks; duplicate Kotlin fallbacks deleted).

## Host verification (Mac OS X aarch64, OpenJDK 21.0.7, Rust 1.90.0)

| Suite / Check | Result | Notes |
| --- | --- | --- |
| `cargo test --workspace --manifest-path rust/Cargo.toml --locked` | 14/14 MOBI Rust tests passed (142/142 workspace total) | `mobi`: 10 (includes PDB + header tests), `mobi_jni`: 4 |
| `cargo clippy ... -- -D warnings` | 0 warnings | Workspace-wide |
| `RustMobiTest` (JVM) | 8/8 passed (`0.004 s`) | Covers native `PdbFile`, `isPdbBook`, `PalmDocHeader`, `MobiHeader`, `Exth`, PalmDOC, TrailingEntries, HuffCdic + cyclic/budget limits, FONT, MobiIndex, and KF8 assembly |
| `MobiIndexTest`, `FormatParityTest`, `RealBooksTest` | All passed | `MobiIndexTest`: 6/6 (`0.004 s`), `FormatParityTest`: 1/1 (`0.072 s`), `RealBooksTest` MOBI case passed (`9/12`, 3 absent private-book skips) |
| Signed R8 Release APK (`-PrustEngineApp=true`) | Built and verified (`49,341,627 B`) | All 23 `RustMobiNative` JNI symbols exported across all 4 ABIs (`16384` `PT_LOAD` alignment); preserved via `@Keep` and `rules.keep` |

## Wave 20 Pixel 9a device benchmark (BETA 2 (wave 20), code=20)

Measured on Google Pixel 9a (`58071JEBF07361`, Android 16 / API 36, `tegu`) via `RustEngineDeviceBenchmarkTest` (**3/3 PASSED**, `8.388 s`):

| Suite | Metric | Pixel 9a Result |
| --- | --- | ---: |
| `m5_mobi` | `mobi_meta_ms` | **`0.712 ms`** |
| `m5_mobi` | `mobi_parse_ms` | **`407.718 ms`** |

## Remaining scope

- `MobiParser.kt` orchestration (feeding records into the M6 native markup/CSS
  pipeline and emitting `.frb` blocks directly from Rust) remains in Kotlin.
- Real-device UI/pagination checks specifically on MOBI/KF8 corpora on Pixel 9a
  remain open for future profiling if needed.
