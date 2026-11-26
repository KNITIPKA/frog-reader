# Exact page index, version 1

The page index is a rebuildable cache for one publication and one layout
specification. It contains the total page count and the global source anchor
of each page. Kotlin supplies the layout key and the number of reader items
from the active publication. Rust checks the framing, identity, checksums and
anchor ordering before it returns a result.

All integers are little endian. The header occupies 64 bytes.

| Offset | Type | Value |
| ---: | --- | --- |
| 0 | 8 bytes | ASCII `FROGPAGE` |
| 8 | u32 | Version `1` |
| 12 | u32 | Header size `64` |
| 16 | u32 | Positive reader item count, at most `Int.MAX_VALUE` |
| 20 | u32 | Page count, from `1` through `1,000,000` |
| 24 | 32 bytes | SHA-256 layout key |
| 56 | u32 | IEEE CRC32 of the payload |
| 60 | u32 | IEEE CRC32 of header bytes 0 through 59 |

The payload contains exactly one 8-byte record for every page. Each record
holds the global item index and the character or table-row offset, both signed
32-bit integers. Each item index is in `[0, itemCount)` and each offset is
nonnegative. Records are sorted by item index, then offset. Equal anchors are
valid. The page count gives an exact file size of `64 + 8 × pageCount` bytes.

`RustPageIndexNative.read` validates the complete file before returning a flat
array of alternating item indices and offsets. `readBytes` applies the same
validation to the final FRB block of a continuous `layout.frb`. A missing
file, a mismatched key or invalid data returns `null`. `write` validates the
array before opening an exclusive temporary file, writes the checked header and payload, and syncs
the file. Kotlin publishes that file with an atomic rename. Failed writes
return `false` and the caller removes the temporary file. The reader and writer
bound both the array and file size before allocating the anchor buffer. CRC32
detects corruption. The SHA-256 key identifies the source and layout supplied
by the caller.
