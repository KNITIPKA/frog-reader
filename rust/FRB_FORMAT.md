# FRB version 2

`.frb` is rebuildable parsed content, not user-authored storage. Invalid files
are cache misses. Source identity and semantic model validation belong to the
Kotlin cache. The Rust layer treats metadata and payload bytes as opaque and
checks their framing, checksums, lengths, and coordinates.

Only version 2 is read or written. Version 1 caches are discarded and rebuilt
from the original ebook; there is no compatibility reader or migration.

Container integers are unsigned little endian. Every integer is read from checked byte
slices; no in-memory structs, pointers, or native ABI layout are serialized.

## Header (64 bytes)

| Offset | Type | Value |
| ---: | --- | --- |
| 0 | 8 bytes | ASCII `FRBCACHE` |
| 8 | u32 | Format version `2` |
| 12 | u32 | Header bytes `64` |
| 16 | u32 | Complete flag, exactly `1` |
| 20 | u32 | Number of chapters N |
| 24 | u32 | Source metadata length |
| 28 | u32 | Index entry bytes `48` |
| 32 | u64 | Exact complete file length |
| 40 | u64 | Sum of decoded block lengths |
| 48 | u32 | IEEE CRC32 of metadata |
| 52 | u32 | IEEE CRC32 of the whole index |
| 56 | u32 | IEEE CRC32 of the header with this field zeroed |
| 60 | u32 | Reserved, zero |

The header is followed by opaque source metadata, then N+2 index entries, then
N+2 independently compressed LZ4 blocks. Block 0 is the lightweight `BookIndex`
manifest: whole-book chapter/navigation coordinates and metadata, without the
sequential texts or heavy note/linked-document tables. Block 1 is the global
`BookContent` without sequential chapters. Chapter K (zero-based) is stored at
block K+2. The two prefix blocks are independently readable: opening the index
never requires decoding heavy global tables or sequential chapters. No images
or embedded font bytes are duplicated into this container.

## Content model payload, revision 5

The source metadata binds each content FRB to a model revision. Revision 5
uses the same binary codec for `BookIndex`, global `BookContent` and each
`Chapter`. A different revision is a cache miss. The outer container version
stays 2. Geometry FRBs have their own metadata tag and payload.

The generated serialization descriptors define record fields, defaults and
type names. Updating those definitions incompatibly requires a new model
revision. The encoding uses the following rules.

- A record contains strictly ascending unsigned LEB128 field ordinals
  (descriptor index plus one) followed by a zero terminator. Fields equal to
  their declared default can be omitted. A polymorphic record has its
  serialized type name in field 1 and its typed value in field 2.
- A list starts with an unsigned LEB128 item count. A map starts with an
  unsigned LEB128 entry count and stores alternating keys and values. Elements
  follow in order without field tags or a terminator.
- Signed short, int and long values use zigzag followed by unsigned LEB128.
  Bytes store their raw bits. Floats and doubles store their raw bits in
  four or eight little-endian bytes. Character codes and enum ordinals use
  unsigned LEB128. Inline values use the underlying primitive encoding.
- Booleans and nullable presence markers are one byte (`0` or `1`). A string
  starts with an unsigned LEB128 token. An even token is twice the byte length
  and precedes well-formed UTF-8. An odd token is twice a zero-based reference
  plus one. The dictionary stores the first 4,096 literals of 1–128 bytes in
  encounter order.
- A type marked `InternedFrbStyle` starts with an unsigned LEB128 reference.
  Zero precedes a normal record and registers it after decoding. A nonzero
  reference is one plus a previously registered index. Each type has a separate
  dictionary capped at 4,096 definitions. Nullable markers come before this
  style reference.

The reader bounds lengths by the checked block size, consumes each record and
collection fully and rejects trailing bytes. Both sides cap nesting at 256
active structures. Dictionaries start empty in each block.

For reflow EPUB, the manifest can include contiguous source ranges. Each
range stores the spine occurrence ordinal, canonical archive path, offset in
the mapped XHTML/SVG, logical chapter and its local range. The ranges cover
all logical elements in reading order and distinguish repeated occurrences.
A separate `reader-source-index/<id>/index.frb` can retain this manifest when
the complete FRB and extracted resources are evicted. Its source metadata has
a distinct entry kind and binds the map to the original file identity and
SHA-256. It contains the manifest and an empty reserved block, with no chapter
payloads. The cache excludes fixed-layout EPUBs from this targeted read path.

## Index entry (48 bytes)

| Offset | Type | Value |
| ---: | --- | --- |
| 0 | u64 | Absolute compressed block offset |
| 8 | u64 | Compressed length |
| 16 | u64 | Decoded length |
| 24 | u64 | Element count (zero for both prefix blocks) |
| 32 | u64 | Starting flat element index (zero for both prefixes and first chapter) |
| 40 | u32 | IEEE CRC32 of decoded payload |
| 44 | u32 | Reserved, zero |

Offsets must be contiguous, ordered, beyond the header/metadata/index, and end
at the exact file length. Flat starts equal the checked prefix sum of element
counts, with a whole-book total fitting signed 32-bit reader coordinates. Empty
chapters keep separate entries and may have the same flat start.

Compression uses LZ4 raw block encoding, with decoded length supplied by the
index rather than an embedded allocation hint. `lz4_flex` 0.14.0 is pinned with
`std`, `safe-encode`, `safe-decode`, and `checked-decode` enabled; frame support is
not needed. CRC32 uses pinned `crc32fast` 1.5.0. CRCs detect corruption and do not
serve as cryptographic authentication. Source metadata includes the source hash
in the owning Kotlin layer.

## Limits and publication

The Rust decoder accepts at most 100,000 chapters, 1 MiB metadata, 64 MiB per
decoded block (8 MiB for the lightweight manifest), 4 GiB decoded across all blocks, 64 MiB compressed file, and 64
live native sessions. The caller may set a smaller compressed-file write budget.
All bounds are checked before allocating the corresponding input buffer. Native
allocation failures when reserving explicit buffers become IO errors.

A writer creates a new temporary path, reserves header/index space, then appends
blocks in ascending order. After all blocks are present it writes the index,
syncs payload/index, writes the complete checked header, and syncs the file.
The Kotlin owner atomically replaces the public cache path only after success.
An interrupted, failed, or incomplete writer cannot open as a reader. A failed
commit cannot be retried on that writer. A writer creates the destination
exclusively and refuses existing paths.

Reads check the header, source metadata and index before a session is published.
A block is decoded into initialized bounded memory and its exact length and CRC
are validated before `readBlockBytes` copies the decoded bytes into a JVM array.
Payload CRCs are checked on block access. Kotlin decodes the array directly.

## JNI ownership

`RustChunkedCacheNative` returns positive monotonic IDs backed by safe Rust
`Arc<Mutex<Session>>` values. IDs are never pointers. A short global lock looks
up the session; IO and compression hold only that session's lock. Every caller
closes every positive ID in `finally`. A reader retains its original open file
descriptor, so an atomic replacement cannot mix generations between block calls.
Expected IO, invalid-cache, limits and invalid-handle failures return
`0`/`null`/`false`. Panics remain contained by the shared JNI panic guard.

`readIndex` omits both prefix entries and returns five signed 64-bit values per
chapter: offset, compressed length, decoded length, element count, flat start.
Its length is exactly N×5; all returned values fit signed 64-bit by format limits.

`readGeneration` returns the validated header CRC followed by the validated
index CRC as two little-endian `u32` values. These bytes come from the retained
reader and identify the checked block index across path replacements. Kotlin
derives the indexed session's SHA-256 content key from the verified source
metadata followed by these eight bytes. A derived layout can bind to that key
without reopening the path or decoding chapters. Block CRCs are verified when
their payloads are read.
