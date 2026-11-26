//! WOFF 1.0 and WOFF 2.0 font unpackers to sfnt (TTF/OTF).
//!
//! All directory bounds and output budgets are verified before large buffer
//! allocations so damaged or hostile embedded fonts degrade to `None` cleanly.

use flate2::{Decompress, FlushDecompress, Status};
use std::io::Read;

const WOFF_HEADER_SIZE: usize = 44;
const WOFF_DIR_ENTRY_SIZE: usize = 20;
const WOFF_MAX_TABLES: usize = 64;
const WOFF_MAX_TABLE_SIZE: usize = 10_000_000;

const WOFF2_HEADER_SIZE: usize = 48;
const WOFF2_MAX_TABLES: usize = 512;
const WOFF2_MAX_TABLE_SIZE: usize = 32 * 1024 * 1024;
const TTCF_FLAVOR: u32 = 0x7474_6366;

/// W3C WOFF 2.0 known-table-tags lookup array (indices 0..62; 63 means inline 4-byte tag).
const KNOWN_TAGS: [&[u8; 4]; 63] = [
    b"cmap", b"head", b"hhea", b"hmtx", b"maxp", b"name", b"OS/2", b"post", b"cvt ", b"fpgm",
    b"glyf", b"loca", b"prep", b"CFF ", b"VORG", b"EBDT", b"EBLC", b"gasp", b"hdmx", b"kern",
    b"LTSH", b"PCLT", b"VDMX", b"vhea", b"vmtx", b"BASE", b"GDEF", b"GPOS", b"GSUB", b"EBSC",
    b"JSTF", b"MATH", b"CBDT", b"CBLC", b"COLR", b"CPAL", b"SVG ", b"sbix", b"acnt", b"avar",
    b"bdat", b"bloc", b"bsln", b"cvar", b"fdsc", b"feat", b"fmtx", b"fvar", b"gvar", b"hsty",
    b"just", b"lcar", b"mort", b"morx", b"opbd", b"prop", b"trak", b"Zapf", b"Silf", b"Glat",
    b"Gloc", b"Feat", b"Sill",
];

struct SfntTable {
    tag: [u8; 4],
    checksum: u32,
    data: Vec<u8>,
}

fn try_zero_buf(size: usize) -> Option<Vec<u8>> {
    let mut buf = Vec::new();
    buf.try_reserve_exact(size).ok()?;
    buf.resize(size, 0);
    Some(buf)
}

fn padded4(size: usize) -> Option<usize> {
    size.checked_add(3).map(|n| (n / 4) * 4)
}

fn read_u16_be(bytes: &[u8], at: usize) -> Option<u16> {
    let slice = bytes.get(at..at + 2)?;
    Some(u16::from_be_bytes([slice[0], slice[1]]))
}

fn read_u32_be(bytes: &[u8], at: usize) -> Option<u32> {
    let slice = bytes.get(at..at + 4)?;
    Some(u32::from_be_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

fn write_u16_be(bytes: &mut [u8], at: usize, value: u16) {
    bytes[at..at + 2].copy_from_slice(&value.to_be_bytes());
}

fn write_u32_be(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_be_bytes());
}

fn push_u16_be(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn push_i16_be(out: &mut Vec<u8>, value: i32) {
    push_u16_be(out, (value as i16) as u16);
}

fn push_u32_be(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn sfnt_header_params(num_tables: usize) -> Option<(u16, u16, u16)> {
    if num_tables == 0 || num_tables > u16::MAX as usize {
        return None;
    }
    let entry_selector = (num_tables as u32).ilog2() as u16;
    let search_range = 16u16.checked_shl(u32::from(entry_selector))?;
    let total_dir = u16::try_from(num_tables).ok()?.checked_mul(16)?;
    let range_shift = total_dir.checked_sub(search_range)?;
    Some((search_range, entry_selector, range_shift))
}

fn assemble_sfnt(flavor: u32, tables: Vec<SfntTable>, max_decoded_bytes: usize) -> Option<Vec<u8>> {
    let num_tables = tables.len();
    let (search_range, entry_selector, range_shift) = sfnt_header_params(num_tables)?;
    let mut total = 12usize.checked_add(num_tables.checked_mul(16)?)?;
    if total > max_decoded_bytes {
        return None;
    }
    for table in &tables {
        let padded = padded4(table.data.len())?;
        if padded > max_decoded_bytes - total {
            return None;
        }
        total += padded;
    }

    let mut out = try_zero_buf(total)?;
    write_u32_be(&mut out, 0, flavor);
    write_u16_be(&mut out, 4, num_tables as u16);
    write_u16_be(&mut out, 6, search_range);
    write_u16_be(&mut out, 8, entry_selector);
    write_u16_be(&mut out, 10, range_shift);

    let mut record = 12;
    let mut data_offset = 12 + num_tables * 16;
    for table in tables {
        let len = table.data.len();
        out[data_offset..data_offset + len].copy_from_slice(&table.data);
        let padded = padded4(len)?;
        out[record..record + 4].copy_from_slice(&table.tag);
        write_u32_be(&mut out, record + 4, table.checksum);
        write_u32_be(&mut out, record + 8, u32::try_from(data_offset).ok()?);
        write_u32_be(&mut out, record + 12, u32::try_from(len).ok()?);
        data_offset += padded;
        record += 16;
    }
    Some(out)
}

fn table_checksum(padded_slice: &[u8]) -> u32 {
    let mut sum = 0u32;
    for chunk in padded_slice.chunks_exact(4) {
        sum = sum.wrapping_add(u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    sum
}

// --------------------------------------------------------------------------- WOFF 1.0

pub fn is_woff(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && &bytes[..4] == b"wOFF"
}

fn inflate_zlib_exact(compressed: &[u8], orig_len: usize) -> Option<Vec<u8>> {
    let mut out = try_zero_buf(orig_len)?;
    let mut decomp = Decompress::new(true);
    let mut in_pos = 0usize;
    let mut out_pos = 0usize;
    let mut overflow_probe = [0u8; 1];

    loop {
        let prev_in = in_pos;
        let prev_out = out_pos;
        let status = if out_pos < orig_len {
            decomp
                .decompress(
                    &compressed[in_pos..],
                    &mut out[out_pos..],
                    FlushDecompress::None,
                )
                .ok()?
        } else {
            decomp
                .decompress(
                    &compressed[in_pos..],
                    &mut overflow_probe,
                    FlushDecompress::None,
                )
                .ok()?
        };
        in_pos = usize::try_from(decomp.total_in()).ok()?;
        out_pos = usize::try_from(decomp.total_out()).ok()?;
        if out_pos > orig_len {
            return None;
        }
        match status {
            Status::StreamEnd => break,
            Status::Ok | Status::BufError => {
                if in_pos == prev_in && out_pos == prev_out {
                    return None;
                }
            }
        }
    }

    if out_pos != orig_len {
        return None;
    }
    Some(out)
}

/// Decodes a WOFF 1.0 payload into an sfnt (TTF/OTF) font, returning `None` on
/// invalid headers, checksums/limits, or corrupt zlib streams.
pub fn decode_woff(woff: &[u8], max_decoded_bytes: usize) -> Option<Vec<u8>> {
    if max_decoded_bytes == 0 || !is_woff(woff) || woff.len() < WOFF_HEADER_SIZE {
        return None;
    }
    let flavor = read_u32_be(woff, 4)?;
    let num_tables = read_u16_be(woff, 12)? as usize;
    if num_tables == 0 || num_tables > WOFF_MAX_TABLES {
        return None;
    }
    let dir_end = WOFF_HEADER_SIZE.checked_add(num_tables * WOFF_DIR_ENTRY_SIZE)?;
    if woff.len() < dir_end {
        return None;
    }

    let mut decoded_total = 12usize.checked_add(num_tables * 16)?;
    if decoded_total > max_decoded_bytes {
        return None;
    }

    struct RawDirEntry {
        tag: [u8; 4],
        offset: usize,
        comp_len: usize,
        orig_len: usize,
        checksum: u32,
    }

    let mut dir = Vec::with_capacity(num_tables);
    for i in 0..num_tables {
        let at = WOFF_HEADER_SIZE + i * WOFF_DIR_ENTRY_SIZE;
        let tag = [woff[at], woff[at + 1], woff[at + 2], woff[at + 3]];
        let offset = read_u32_be(woff, at + 4)? as usize;
        let comp_len = read_u32_be(woff, at + 8)? as usize;
        let orig_len = read_u32_be(woff, at + 12)? as usize;
        let checksum = read_u32_be(woff, at + 16)?;
        if orig_len > WOFF_MAX_TABLE_SIZE || comp_len > orig_len {
            return None;
        }
        let end = offset.checked_add(comp_len)?;
        if end > woff.len() {
            return None;
        }
        let padded = padded4(orig_len)?;
        if padded > max_decoded_bytes - decoded_total {
            return None;
        }
        decoded_total += padded;
        dir.push(RawDirEntry {
            tag,
            offset,
            comp_len,
            orig_len,
            checksum,
        });
    }

    let mut tables = Vec::with_capacity(num_tables);
    for entry in dir {
        let data = if entry.comp_len < entry.orig_len {
            inflate_zlib_exact(
                &woff[entry.offset..entry.offset + entry.comp_len],
                entry.orig_len,
            )?
        } else {
            let mut buf = try_zero_buf(entry.comp_len)?;
            buf.copy_from_slice(&woff[entry.offset..entry.offset + entry.comp_len]);
            buf
        };
        tables.push(SfntTable {
            tag: entry.tag,
            checksum: entry.checksum,
            data,
        });
    }

    assemble_sfnt(flavor, tables, max_decoded_bytes)
}

// --------------------------------------------------------------------------- WOFF 2.0

pub fn is_woff2(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && &bytes[..4] == b"wOF2"
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
    end: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8], pos: usize) -> Option<Self> {
        if pos > bytes.len() {
            return None;
        }
        Some(Self {
            bytes,
            pos,
            end: bytes.len(),
        })
    }

    fn slice(bytes: &'a [u8], start: usize, end: usize) -> Option<Self> {
        if start > end || end > bytes.len() {
            return None;
        }
        Some(Self {
            bytes,
            pos: start,
            end,
        })
    }

    fn remaining(&self) -> usize {
        self.end - self.pos
    }

    fn u8(&mut self) -> Option<u8> {
        if self.pos >= self.end {
            return None;
        }
        let b = self.bytes[self.pos];
        self.pos += 1;
        Some(b)
    }

    fn u16(&mut self) -> Option<u16> {
        let hi = self.u8()?;
        let lo = self.u8()?;
        Some(u16::from_be_bytes([hi, lo]))
    }

    fn i16(&mut self) -> Option<i16> {
        self.u16().map(|v| v as i16)
    }

    fn u32(&mut self) -> Option<u32> {
        let hi = self.u16()?;
        let lo = self.u16()?;
        Some((u32::from(hi) << 16) | u32::from(lo))
    }

    fn bytes(&mut self, n: usize) -> Option<&'a [u8]> {
        let next = self.pos.checked_add(n)?;
        if next > self.end {
            return None;
        }
        let slice = &self.bytes[self.pos..next];
        self.pos = next;
        Some(slice)
    }

    fn tag4(&mut self) -> Option<[u8; 4]> {
        let b = self.bytes(4)?;
        Some([b[0], b[1], b[2], b[3]])
    }

    /// WOFF2 §5.2: UIntBase128 unsigned 32-bit integer, no leading zeros.
    fn uint_base128(&mut self) -> Option<u32> {
        let mut value = 0u32;
        for i in 0..5 {
            let byte = self.u8()?;
            if i == 0 && byte == 0x80 {
                return None;
            }
            if value & 0xFE00_0000 != 0 {
                return None;
            }
            value = (value << 7) | u32::from(byte & 0x7F);
            if byte & 0x80 == 0 {
                return Some(value);
            }
        }
        None
    }

    /// WOFF2 §5.1: 255UInt16 variable-length unsigned 16-bit integer.
    fn read_255_ushort(&mut self) -> Option<usize> {
        let code = self.u8()?;
        match code {
            253 => self.u16().map(usize::from),
            254 => self.u8().map(|b| usize::from(b) + 506),
            255 => self.u8().map(|b| usize::from(b) + 253),
            _ => Some(usize::from(code)),
        }
    }
}

struct Woff2DirEntry {
    tag: [u8; 4],
    transformed: bool,
    data_length: usize,
    data: Vec<u8>,
}

struct Woff2Directory {
    flavor: u32,
    compressed_offset: usize,
    compressed_size: usize,
    total_uncompressed: usize,
    entries: Vec<Woff2DirEntry>,
}

fn parse_woff2_directory(woff2: &[u8], max_decoded_bytes: usize) -> Option<Woff2Directory> {
    if max_decoded_bytes == 0 || !is_woff2(woff2) || woff2.len() < WOFF2_HEADER_SIZE {
        return None;
    }
    let mut header = Reader::new(woff2, 4)?;
    let flavor = header.u32()?;
    if flavor == TTCF_FLAVOR {
        return None;
    }
    let _length = header.u32()?;
    let num_tables = header.u16()? as usize;
    let _reserved = header.u16()?;
    let _total_sfnt_size = header.u32()?;
    let total_compressed_size = header.u32()? as usize;
    if total_compressed_size > i32::MAX as usize {
        return None;
    }
    if num_tables == 0 || num_tables > WOFF2_MAX_TABLES {
        return None;
    }

    let mut reader = Reader::new(woff2, WOFF2_HEADER_SIZE)?;
    let mut entries = Vec::with_capacity(num_tables);
    let mut total_uncompressed = 0usize;
    let mut estimated_sfnt = 12usize.checked_add(num_tables * 16)?;
    if estimated_sfnt > max_decoded_bytes {
        return None;
    }

    for _ in 0..num_tables {
        let flags = reader.u8()?;
        let tag_index = usize::from(flags & 0x3F);
        let tag = if tag_index == 63 {
            reader.tag4()?
        } else {
            *KNOWN_TAGS[tag_index]
        };
        let version = (flags >> 6) & 0x3;
        let glyf_loca = &tag == b"glyf" || &tag == b"loca";
        let transformed = if glyf_loca {
            version == 0
        } else {
            version != 0
        };
        if transformed && !glyf_loca && !(&tag == b"hmtx" && version == 1) {
            return None;
        }
        if glyf_loca && !transformed && version != 3 {
            return None;
        }
        let orig_length = reader.uint_base128()? as usize;
        let data_length = if transformed {
            reader.uint_base128()? as usize
        } else {
            orig_length
        };
        if orig_length > WOFF2_MAX_TABLE_SIZE || data_length > WOFF2_MAX_TABLE_SIZE {
            return None;
        }
        total_uncompressed = total_uncompressed.checked_add(data_length)?;
        if total_uncompressed > max_decoded_bytes {
            return None;
        }
        let padded_orig = padded4(orig_length)?;
        if padded_orig > max_decoded_bytes - estimated_sfnt {
            return None;
        }
        estimated_sfnt += padded_orig;
        entries.push(Woff2DirEntry {
            tag,
            transformed,
            data_length,
            data: Vec::new(),
        });
    }

    let compressed_offset = reader.pos;
    let compressed_end = compressed_offset.checked_add(total_compressed_size)?;
    if compressed_end > woff2.len() {
        return None;
    }

    Some(Woff2Directory {
        flavor,
        compressed_offset,
        compressed_size: total_compressed_size,
        total_uncompressed,
        entries,
    })
}

fn decompress_brotli(compressed: &[u8], expected_len: usize) -> Option<Vec<u8>> {
    let mut out = try_zero_buf(expected_len)?;
    let mut decompressor = brotli_decompressor::Decompressor::new(compressed, 4096);
    let mut off = 0usize;
    while off < expected_len {
        match decompressor.read(&mut out[off..]) {
            Ok(0) => return None,
            Ok(n) => off += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    }
    // The directory's transformed lengths must describe the complete Brotli
    // stream, not merely a prefix of it (and the stream must reach a valid EOF).
    let mut probe = [0u8; 1];
    loop {
        match decompressor.read(&mut probe) {
            Ok(0) => return Some(out),
            Ok(_) => return None,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return None,
        }
    }
}

/// Decodes a WOFF 2.0 payload into an sfnt (TTF/OTF) font using pure-Rust
/// Brotli decompression and table reconstruction, returning `None` on
/// unsupported collections (`ttcf`), invalid headers/limits, or corrupt streams.
pub fn decode_woff2(woff2: &[u8], max_decoded_bytes: usize) -> Option<Vec<u8>> {
    let dir = parse_woff2_directory(woff2, max_decoded_bytes)?;
    let compressed_end = dir.compressed_offset.checked_add(dir.compressed_size)?;
    let compressed = woff2.get(dir.compressed_offset..compressed_end)?;
    let uncompressed = decompress_brotli(compressed, dir.total_uncompressed)?;
    decode_woff2_with_dir(dir, &uncompressed, max_decoded_bytes)
}

/// Validates a WOFF2 header and directory against `max_decoded_bytes`, returning
/// `[compressed_offset, compressed_size, total_uncompressed]` for the Brotli stream.
#[cfg(test)]
pub(crate) fn inspect_woff2(woff2: &[u8], max_decoded_bytes: usize) -> Option<[i32; 3]> {
    let dir = parse_woff2_directory(woff2, max_decoded_bytes)?;
    Some([
        i32::try_from(dir.compressed_offset).ok()?,
        i32::try_from(dir.compressed_size).ok()?,
        i32::try_from(dir.total_uncompressed).ok()?,
    ])
}

/// Reconstructs an sfnt font from the WOFF2 header/directory and its exact
/// decompressed Brotli stream (`uncompressed.len() == total_uncompressed`).
#[cfg(test)]
pub(crate) fn decode_woff2_from_stream(
    woff2: &[u8],
    uncompressed: &[u8],
    max_decoded_bytes: usize,
) -> Option<Vec<u8>> {
    let dir = parse_woff2_directory(woff2, max_decoded_bytes)?;
    decode_woff2_with_dir(dir, uncompressed, max_decoded_bytes)
}

fn decode_woff2_with_dir(
    mut dir: Woff2Directory,
    uncompressed: &[u8],
    max_decoded_bytes: usize,
) -> Option<Vec<u8>> {
    if uncompressed.len() != dir.total_uncompressed {
        return None;
    }

    let mut slice_pos = 0usize;
    for entry in &mut dir.entries {
        let next = slice_pos.checked_add(entry.data_length)?;
        let raw = uncompressed.get(slice_pos..next)?;
        let mut buf = try_zero_buf(raw.len())?;
        buf.copy_from_slice(raw);
        entry.data = buf;
        slice_pos = next;
    }

    let glyf_idx = dir.entries.iter().position(|e| &e.tag == b"glyf");
    let loca_idx = dir.entries.iter().position(|e| &e.tag == b"loca");
    let glyf_transformed = glyf_idx.is_some_and(|i| dir.entries[i].transformed);
    let loca_transformed = loca_idx.is_some_and(|i| dir.entries[i].transformed);

    let mut x_mins: Option<Vec<i32>> = None;
    let mut num_glyphs = 0usize;

    if glyf_transformed || loca_transformed {
        if !glyf_transformed || !loca_transformed {
            return None;
        }
        let gi = glyf_idx?;
        let li = loca_idx?;
        if dir.entries[li].data_length != 0 {
            return None;
        }
        let reconstructed = reconstruct_glyf(&dir.entries[gi].data, max_decoded_bytes)?;
        dir.entries[gi].data = reconstructed.glyf;
        dir.entries[li].data = reconstructed.loca;
        x_mins = Some(reconstructed.x_mins);
        num_glyphs = reconstructed.num_glyphs;
        if let Some(head) = dir.entries.iter_mut().find(|e| &e.tag == b"head") {
            if head.data.len() >= 52 {
                head.data[50] = 0;
                head.data[51] = reconstructed.index_format as u8;
            }
        }
    }

    if let Some(hmtx_idx) = dir.entries.iter().position(|e| &e.tag == b"hmtx") {
        if dir.entries[hmtx_idx].transformed {
            let mins = x_mins.as_ref()?;
            let hhea = dir
                .entries
                .iter()
                .find(|e| &e.tag == b"hhea" && !e.transformed)?;
            if hhea.data.len() < 36 {
                return None;
            }
            let num_h_metrics = usize::from(read_u16_be(&hhea.data, 34)?);
            let rebuilt =
                reconstruct_hmtx(&dir.entries[hmtx_idx].data, num_glyphs, num_h_metrics, mins)?;
            dir.entries[hmtx_idx].data = rebuilt;
        }
    }

    // sfnt directories are sorted by 4-byte tag.
    dir.entries.sort_by_key(|e| e.tag);

    // Zero checkSumAdjustment in `head` before computing table checksums.
    if let Some(head) = dir.entries.iter_mut().find(|e| &e.tag == b"head") {
        if head.data.len() >= 12 {
            head.data[8..12].fill(0);
        }
    }

    let num_tables = dir.entries.len();
    let (search_range, entry_selector, range_shift) = sfnt_header_params(num_tables)?;
    let mut total = 12usize.checked_add(num_tables * 16)?;
    if total > max_decoded_bytes {
        return None;
    }
    for entry in &dir.entries {
        let padded = padded4(entry.data.len())?;
        if padded > max_decoded_bytes - total {
            return None;
        }
        total += padded;
    }

    let mut out = try_zero_buf(total)?;
    write_u32_be(&mut out, 0, dir.flavor);
    write_u16_be(&mut out, 4, num_tables as u16);
    write_u16_be(&mut out, 6, search_range);
    write_u16_be(&mut out, 8, entry_selector);
    write_u16_be(&mut out, 10, range_shift);

    let mut record = 12;
    let mut data_offset = 12 + num_tables * 16;
    for entry in &dir.entries {
        let len = entry.data.len();
        let padded = padded4(len)?;
        out[data_offset..data_offset + len].copy_from_slice(&entry.data);
        let checksum = table_checksum(&out[data_offset..data_offset + padded]);
        out[record..record + 4].copy_from_slice(&entry.tag);
        write_u32_be(&mut out, record + 4, checksum);
        write_u32_be(&mut out, record + 8, u32::try_from(data_offset).ok()?);
        write_u32_be(&mut out, record + 12, u32::try_from(len).ok()?);
        data_offset += padded;
        record += 16;
    }
    Some(out)
}

struct GlyfResult {
    glyf: Vec<u8>,
    loca: Vec<u8>,
    x_mins: Vec<i32>,
    num_glyphs: usize,
    index_format: u16,
}

fn reconstruct_glyf(data: &[u8], max_decoded_bytes: usize) -> Option<GlyfResult> {
    if data.len() < 36 {
        return None;
    }
    let mut header = Reader::new(data, 0)?;
    let _version = header.u32()?;
    let num_glyphs = usize::from(header.u16()?);
    let index_format = header.u16()?;
    let mut sizes = [0usize; 7];
    for slot in &mut sizes {
        let size = header.u32()? as usize;
        if size > data.len() {
            return None;
        }
        *slot = size;
    }

    let mut off = header.pos;
    let mut substream = |size: usize| -> Option<Reader<'_>> {
        let end = off.checked_add(size)?;
        let sub = Reader::slice(data, off, end)?;
        off = end;
        Some(sub)
    };
    let mut n_contour_stream = substream(sizes[0])?;
    let mut n_points_stream = substream(sizes[1])?;
    let mut flag_stream = substream(sizes[2])?;
    let mut glyph_stream = substream(sizes[3])?;
    let mut composite_stream = substream(sizes[4])?;
    let mut bbox_stream = substream(sizes[5])?;
    let mut instruction_stream = substream(sizes[6])?;

    let bitmap_size = num_glyphs.checked_add(31)? / 32 * 4;
    if bbox_stream.remaining() < bitmap_size {
        return None;
    }
    let bbox_bitmap = bbox_stream.bytes(bitmap_size)?;
    let has_bbox = |i: usize| -> bool { (bbox_bitmap[i / 8] & (0x80 >> (i % 8))) != 0 };

    let mut out = Vec::new();
    let mut loca_offsets = Vec::new();
    loca_offsets.try_reserve_exact(num_glyphs + 1).ok()?;
    let mut x_mins = Vec::new();
    x_mins.try_reserve_exact(num_glyphs).ok()?;

    // Reusable buffers for simple glyphs to avoid per-glyph allocations.
    let mut points_per_contour = Vec::new();
    let mut xs = Vec::new();
    let mut ys = Vec::new();
    let mut on_curve = Vec::new();
    let mut flags_buf = Vec::new();
    let mut x_buf = Vec::new();
    let mut y_buf = Vec::new();
    let mut comp_buf = Vec::new();

    for glyph in 0..num_glyphs {
        loca_offsets.push(out.len());
        let n_contours = n_contour_stream.i16()?;
        match n_contours {
            0 => {
                if has_bbox(glyph) {
                    return None;
                }
                x_mins.push(0);
            }
            -1 => {
                comp_buf.clear();
                let mut have_instructions = false;
                loop {
                    let flags = composite_stream.u16()?;
                    push_u16_be(&mut comp_buf, flags);
                    push_u16_be(&mut comp_buf, composite_stream.u16()?);
                    let mut extra = if flags & 0x0001 != 0 { 4 } else { 2 };
                    extra += if flags & 0x0008 != 0 {
                        2
                    } else if flags & 0x0040 != 0 {
                        4
                    } else if flags & 0x0080 != 0 {
                        8
                    } else {
                        0
                    };
                    comp_buf.extend_from_slice(composite_stream.bytes(extra)?);
                    if flags & 0x0100 != 0 {
                        have_instructions = true;
                    }
                    if flags & 0x0020 == 0 {
                        break;
                    }
                }
                if !has_bbox(glyph) {
                    return None;
                }
                let bbox = [
                    i32::from(bbox_stream.i16()?),
                    i32::from(bbox_stream.i16()?),
                    i32::from(bbox_stream.i16()?),
                    i32::from(bbox_stream.i16()?),
                ];
                x_mins.push(bbox[0]);
                let mut needed = 10 + comp_buf.len();
                let instr = if have_instructions {
                    let instr_len = glyph_stream.read_255_ushort()?;
                    if instr_len > u16::MAX as usize {
                        return None;
                    }
                    let bytes = instruction_stream.bytes(instr_len)?;
                    needed = needed.checked_add(2 + bytes.len())?;
                    Some(bytes)
                } else {
                    None
                };
                if needed > max_decoded_bytes.saturating_sub(out.len()) {
                    return None;
                }
                push_i16_be(&mut out, -1);
                for v in bbox {
                    push_i16_be(&mut out, v);
                }
                out.extend_from_slice(&comp_buf);
                if let Some(bytes) = instr {
                    push_u16_be(&mut out, bytes.len() as u16);
                    out.extend_from_slice(bytes);
                }
            }
            n if n > 0 => {
                let n_contours_usize = n as usize;
                points_per_contour.clear();
                let mut total_points = 0usize;
                for _ in 0..n_contours_usize {
                    let count = n_points_stream.read_255_ushort()?;
                    total_points = total_points.checked_add(count)?;
                    points_per_contour.push(count);
                }
                if total_points == 0 || total_points > 0xFFFF {
                    return None;
                }
                xs.clear();
                ys.clear();
                on_curve.clear();
                let mut x = 0i32;
                let mut y = 0i32;
                for _ in 0..total_points {
                    let flag = flag_stream.u8()?;
                    on_curve.push((flag & 0x80) == 0);
                    let (dx, dy) = triplet_decode(flag & 0x7F, &mut glyph_stream)?;
                    x += dx;
                    y += dy;
                    xs.push(x);
                    ys.push(y);
                }
                let instr_len = glyph_stream.read_255_ushort()?;
                if instr_len > u16::MAX as usize {
                    return None;
                }
                let instructions = instruction_stream.bytes(instr_len)?;
                let bbox = if has_bbox(glyph) {
                    [
                        i32::from(bbox_stream.i16()?),
                        i32::from(bbox_stream.i16()?),
                        i32::from(bbox_stream.i16()?),
                        i32::from(bbox_stream.i16()?),
                    ]
                } else {
                    [
                        *xs.iter().min()?,
                        *ys.iter().min()?,
                        *xs.iter().max()?,
                        *ys.iter().max()?,
                    ]
                };
                x_mins.push(bbox[0]);
                write_simple_glyph(
                    &mut out,
                    max_decoded_bytes,
                    n_contours_usize,
                    &points_per_contour,
                    &xs,
                    &ys,
                    &on_curve,
                    bbox,
                    instructions,
                    &mut flags_buf,
                    &mut x_buf,
                    &mut y_buf,
                )?;
            }
            _ => return None,
        }
        if out.len() % 2 != 0 {
            if out.len() >= max_decoded_bytes {
                return None;
            }
            out.push(0);
        }
    }
    loca_offsets.push(out.len());

    let mut loca = Vec::new();
    if index_format == 0 {
        loca.try_reserve_exact(loca_offsets.len() * 2).ok()?;
        for offset in loca_offsets {
            let scaled = offset / 2;
            if scaled > 0xFFFF {
                return None;
            }
            push_u16_be(&mut loca, scaled as u16);
        }
    } else {
        loca.try_reserve_exact(loca_offsets.len() * 4).ok()?;
        for offset in loca_offsets {
            push_u32_be(&mut loca, u32::try_from(offset).ok()?);
        }
    }

    Some(GlyfResult {
        glyf: out,
        loca,
        x_mins,
        num_glyphs,
        index_format,
    })
}

/// WOFF2 §5.1 triplet coordinate decoding (128-row lookup, computed algorithmically).
fn triplet_decode(flag: u8, data: &mut Reader<'_>) -> Option<(i32, i32)> {
    let with_sign = |sign_flag: u8, value: i32| -> i32 {
        if sign_flag & 1 != 0 {
            value
        } else {
            -value
        }
    };
    let f = i32::from(flag);
    if flag < 10 {
        let b0 = i32::from(data.u8()?);
        let dy = ((f & 14) << 7) + b0;
        Some((0, with_sign(flag, dy)))
    } else if flag < 20 {
        let b0 = i32::from(data.u8()?);
        let dx = (((f - 10) & 14) << 7) + b0;
        Some((with_sign(flag, dx), 0))
    } else if flag < 84 {
        let base = f - 20;
        let b1 = i32::from(data.u8()?);
        let dx = 1 + (base & 0x30) + (b1 >> 4);
        let dy = 1 + ((base & 0x0C) << 2) + (b1 & 0x0F);
        Some((with_sign(flag, dx), with_sign(flag >> 1, dy)))
    } else if flag < 120 {
        let base = f - 84;
        let b1 = i32::from(data.u8()?);
        let b2 = i32::from(data.u8()?);
        let dx = 1 + ((base / 12) << 8) + b1;
        let dy = 1 + (((base % 12) >> 2) << 8) + b2;
        Some((with_sign(flag, dx), with_sign(flag >> 1, dy)))
    } else if flag < 124 {
        let b1 = i32::from(data.u8()?);
        let b2 = i32::from(data.u8()?);
        let b3 = i32::from(data.u8()?);
        let dx = (b1 << 4) + (b2 >> 4);
        let dy = ((b2 & 0x0F) << 8) + b3;
        Some((with_sign(flag, dx), with_sign(flag >> 1, dy)))
    } else {
        let b1 = i32::from(data.u8()?);
        let b2 = i32::from(data.u8()?);
        let b3 = i32::from(data.u8()?);
        let b4 = i32::from(data.u8()?);
        let dx = (b1 << 8) + b2;
        let dy = (b3 << 8) + b4;
        Some((with_sign(flag, dx), with_sign(flag >> 1, dy)))
    }
}

#[allow(clippy::too_many_arguments)]
fn write_simple_glyph(
    out: &mut Vec<u8>,
    max_decoded_bytes: usize,
    n_contours: usize,
    points_per_contour: &[usize],
    xs: &[i32],
    ys: &[i32],
    on_curve: &[bool],
    bbox: [i32; 4],
    instructions: &[u8],
    flags_buf: &mut Vec<u8>,
    x_buf: &mut Vec<u8>,
    y_buf: &mut Vec<u8>,
) -> Option<()> {
    flags_buf.clear();
    x_buf.clear();
    y_buf.clear();
    let mut px = 0i32;
    let mut py = 0i32;
    for ((&x, &y), &is_on) in xs.iter().zip(ys).zip(on_curve) {
        let dx = x - px;
        let dy = y - py;
        px = x;
        py = y;
        let mut flag = if is_on { 0x01u8 } else { 0x00u8 };
        if dx == 0 {
            flag |= 0x10;
        } else if (-255..=255).contains(&dx) {
            flag |= 0x02 | if dx > 0 { 0x10 } else { 0 };
            x_buf.push(dx.unsigned_abs() as u8);
        } else {
            push_i16_be(x_buf, dx);
        }
        if dy == 0 {
            flag |= 0x20;
        } else if (-255..=255).contains(&dy) {
            flag |= 0x04 | if dy > 0 { 0x20 } else { 0 };
            y_buf.push(dy.unsigned_abs() as u8);
        } else {
            push_i16_be(y_buf, dy);
        }
        flags_buf.push(flag);
    }

    let header_and_contours = 10usize
        .checked_add(n_contours * 2)?
        .checked_add(2)?
        .checked_add(instructions.len())?;
    let total_glyph_bytes = header_and_contours
        .checked_add(flags_buf.len())?
        .checked_add(x_buf.len())?
        .checked_add(y_buf.len())?;
    if total_glyph_bytes > max_decoded_bytes.saturating_sub(out.len()) {
        return None;
    }

    push_i16_be(out, n_contours as i32);
    for v in bbox {
        push_i16_be(out, v);
    }
    let mut end: i32 = -1;
    for &count in points_per_contour {
        end += count as i32;
        push_u16_be(out, end as u16);
    }
    push_u16_be(out, instructions.len() as u16);
    out.extend_from_slice(instructions);
    out.extend_from_slice(flags_buf);
    out.extend_from_slice(x_buf);
    out.extend_from_slice(y_buf);
    Some(())
}

fn reconstruct_hmtx(
    data: &[u8],
    num_glyphs: usize,
    num_h_metrics: usize,
    x_mins: &[i32],
) -> Option<Vec<u8>> {
    if num_h_metrics < 1 || num_h_metrics > num_glyphs || num_glyphs > x_mins.len() {
        return None;
    }
    let mut reader = Reader::new(data, 0)?;
    let flags = reader.u8()?;
    let proportional_omitted = flags & 0x1 != 0;
    let monospace_omitted = flags & 0x2 != 0;

    let mut advances = Vec::with_capacity(num_h_metrics);
    for _ in 0..num_h_metrics {
        advances.push(reader.u16()?);
    }
    let mut lsbs = Vec::with_capacity(num_h_metrics);
    for &xmin in x_mins.iter().take(num_h_metrics) {
        if proportional_omitted {
            lsbs.push(xmin);
        } else {
            lsbs.push(i32::from(reader.i16()?));
        }
    }
    let extra_count = num_glyphs - num_h_metrics;
    let mut extra = Vec::with_capacity(extra_count);
    for &xmin in x_mins.iter().skip(num_h_metrics).take(extra_count) {
        if monospace_omitted {
            extra.push(xmin);
        } else {
            extra.push(i32::from(reader.i16()?));
        }
    }

    let out_len = num_h_metrics * 4 + extra_count * 2;
    let mut out = Vec::new();
    out.try_reserve_exact(out_len).ok()?;
    for (&adv, &lsb) in advances.iter().zip(&lsbs) {
        push_u16_be(&mut out, adv);
        push_i16_be(&mut out, lsb);
    }
    for v in extra {
        push_i16_be(&mut out, v);
    }
    Some(out)
}

#[cfg(test)]
pub(crate) fn test_brotli_uncompressed(data: &[u8]) -> Vec<u8> {
    struct BitWriter {
        out: Vec<u8>,
        current: u8,
        bit_count: u8,
    }
    impl BitWriter {
        fn new() -> Self {
            Self {
                out: Vec::new(),
                current: 0,
                bit_count: 0,
            }
        }
        fn write_bits(&mut self, value: u32, bits: u8) {
            for i in 0..bits {
                self.current |= (((value >> i) & 1) as u8) << self.bit_count;
                self.bit_count += 1;
                if self.bit_count == 8 {
                    self.out.push(self.current);
                    self.current = 0;
                    self.bit_count = 0;
                }
            }
        }
        fn align_byte(&mut self) {
            if self.bit_count > 0 {
                self.out.push(self.current);
                self.current = 0;
                self.bit_count = 0;
            }
        }
        fn write_bytes(&mut self, bytes: &[u8]) {
            debug_assert_eq!(self.bit_count, 0);
            self.out.extend_from_slice(bytes);
        }
        fn finish(mut self) -> Vec<u8> {
            self.align_byte();
            self.out
        }
    }

    let mut writer = BitWriter::new();
    writer.write_bits(0, 1); // WBITS = '0' -> window 16
    let mut off = 0usize;
    while off < data.len() {
        let chunk = (data.len() - off).min(65536);
        writer.write_bits(0, 1); // ISLAST = 0
        writer.write_bits(0, 2); // MNIBBLES code '00' -> 4 nibbles
        writer.write_bits((chunk - 1) as u32, 16); // MLEN - 1
        writer.write_bits(1, 1); // ISUNCOMPRESSED = 1
        writer.align_byte();
        writer.write_bytes(&data[off..off + chunk]);
        off += chunk;
    }
    writer.write_bits(1, 1); // ISLAST = 1
    writer.write_bits(1, 1); // ISLASTEMPTY = 1
    writer.finish()
}

#[cfg(test)]
pub(crate) fn test_minimal_woff2(table_tag: &[u8; 4], table_data: &[u8]) -> Vec<u8> {
    let compressed = test_brotli_uncompressed(table_data);
    let mut dir = Vec::new();
    let idx = KNOWN_TAGS.iter().position(|t| *t == table_tag);
    dir.push(idx.unwrap_or(63) as u8);
    if idx.is_none() {
        dir.extend_from_slice(table_tag);
    }
    // UIntBase128
    let mut value = table_data.len() as u32;
    let mut groups = Vec::new();
    loop {
        groups.push((value & 0x7F) as u8);
        value >>= 7;
        if value == 0 {
            break;
        }
    }
    groups.reverse();
    let last = groups.len() - 1;
    for (i, b) in groups.iter_mut().enumerate() {
        if i < last {
            *b |= 0x80;
        }
    }
    dir.extend_from_slice(&groups);

    let mut out = Vec::new();
    out.extend_from_slice(b"wOF2");
    push_u32_be(&mut out, 0x0001_0000);
    push_u32_be(&mut out, (48 + dir.len() + compressed.len()) as u32);
    push_u16_be(&mut out, 1);
    push_u16_be(&mut out, 0);
    push_u32_be(&mut out, 0);
    push_u32_be(&mut out, compressed.len() as u32);
    push_u16_be(&mut out, 1);
    push_u16_be(&mut out, 0);
    for _ in 0..5 {
        push_u32_be(&mut out, 0);
    }
    out.extend_from_slice(&dir);
    out.extend_from_slice(&compressed);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::ZlibEncoder, Compression};
    use std::io::Write;

    const DEFAULT_MAX_DECODED_BYTES: usize = 32 * 1024 * 1024;

    fn zlib_compress(data: &[u8]) -> Vec<u8> {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(data).unwrap();
        encoder.finish().unwrap()
    }

    fn build_woff(stored: &[u8], compressed: &[u8]) -> Vec<u8> {
        let deflated = zlib_compress(compressed);
        let mut out = Vec::new();
        out.extend_from_slice(b"wOFF");
        push_u32_be(&mut out, 0x0001_0000);
        let data_start = 44 + 2 * 20;
        push_u32_be(
            &mut out,
            (data_start + stored.len() + deflated.len()) as u32,
        );
        push_u16_be(&mut out, 2);
        push_u16_be(&mut out, 0);
        push_u32_be(
            &mut out,
            (12 + 2 * 16 + stored.len() + compressed.len()) as u32,
        );
        push_u16_be(&mut out, 1);
        push_u16_be(&mut out, 0);
        for _ in 0..5 {
            push_u32_be(&mut out, 0);
        }
        out.extend_from_slice(b"name");
        push_u32_be(&mut out, data_start as u32);
        push_u32_be(&mut out, stored.len() as u32);
        push_u32_be(&mut out, stored.len() as u32);
        push_u32_be(&mut out, 0x1122_3344);

        out.extend_from_slice(b"glyf");
        push_u32_be(&mut out, (data_start + stored.len()) as u32);
        push_u32_be(&mut out, deflated.len() as u32);
        push_u32_be(&mut out, compressed.len() as u32);
        push_u32_be(&mut out, 0x5566_7788);

        out.extend_from_slice(stored);
        out.extend_from_slice(&deflated);
        out
    }

    fn encode_uint_base128(mut value: u32) -> Vec<u8> {
        let mut groups = Vec::new();
        loop {
            groups.push((value & 0x7F) as u8);
            value >>= 7;
            if value == 0 {
                break;
            }
        }
        groups.reverse();
        let last = groups.len() - 1;
        for (i, b) in groups.iter_mut().enumerate() {
            if i < last {
                *b |= 0x80;
            }
        }
        groups
    }

    fn build_woff2_header_dir(
        flavor: u32,
        tables: &[(&[u8; 4], usize, Option<usize>, u8)],
        compressed_len: usize,
    ) -> Vec<u8> {
        let mut dir = Vec::new();
        for &(tag, orig_len, trans_len, ver) in tables {
            let idx = KNOWN_TAGS.iter().position(|t| *t == tag);
            let flags = (idx.unwrap_or(63) as u8) | (ver << 6);
            dir.push(flags);
            if idx.is_none() {
                dir.extend_from_slice(tag);
            }
            dir.extend_from_slice(&encode_uint_base128(orig_len as u32));
            if let Some(tl) = trans_len {
                dir.extend_from_slice(&encode_uint_base128(tl as u32));
            }
        }
        let mut out = Vec::new();
        out.extend_from_slice(b"wOF2");
        push_u32_be(&mut out, flavor);
        push_u32_be(&mut out, (48 + dir.len() + compressed_len) as u32);
        push_u16_be(&mut out, tables.len() as u16);
        push_u16_be(&mut out, 0);
        push_u32_be(&mut out, 0);
        push_u32_be(&mut out, compressed_len as u32);
        push_u16_be(&mut out, 1);
        push_u16_be(&mut out, 0);
        for _ in 0..5 {
            push_u32_be(&mut out, 0);
        }
        out.extend_from_slice(&dir);
        out.resize(out.len() + compressed_len, 0);
        out
    }

    #[test]
    fn decodes_minimal_woff_to_sfnt() {
        let stored = b"ABCDEF";
        let compressed: Vec<u8> = (0..400).map(|i| (i % 4) as u8).collect();
        let woff = build_woff(stored, &compressed);
        assert!(is_woff(&woff));
        let sfnt = decode_woff(&woff, DEFAULT_MAX_DECODED_BYTES).unwrap();
        assert_eq!(read_u32_be(&sfnt, 0), Some(0x0001_0000));
        assert_eq!(read_u16_be(&sfnt, 4), Some(2));
        assert_eq!(read_u16_be(&sfnt, 6), Some(32));
        assert_eq!(read_u16_be(&sfnt, 8), Some(1));
        assert_eq!(read_u16_be(&sfnt, 10), Some(0));
        assert_eq!(&sfnt[12..16], b"name");
        assert_eq!(read_u32_be(&sfnt, 20), Some(44));
        assert_eq!(read_u32_be(&sfnt, 24), Some(6));
        assert_eq!(&sfnt[28..32], b"glyf");
        assert_eq!(read_u32_be(&sfnt, 36), Some(52));
        assert_eq!(read_u32_be(&sfnt, 40), Some(400));
        assert_eq!(&sfnt[44..50], stored);
        assert_eq!(&sfnt[52..452], &compressed[..]);
        assert_eq!(sfnt.len(), 452);
    }

    #[test]
    fn woff_rejects_truncation_corruption_and_budget_overflow() {
        let stored = b"AB";
        let compressed = vec![1u8; 4_000];
        let woff = build_woff(stored, &compressed);
        assert!(decode_woff(&woff[..woff.len() - 5], DEFAULT_MAX_DECODED_BYTES).is_none());
        assert!(decode_woff(b"wOFF", DEFAULT_MAX_DECODED_BYTES).is_none());
        assert!(decode_woff(&woff, 1_024).is_none());
        assert!(decode_woff(&woff, 8_192).is_some());

        // Overwriting the orig_len to less than real zlib expanded size must fail.
        let mut lying = woff.clone();
        write_u32_be(&mut lying, WOFF_HEADER_SIZE + WOFF_DIR_ENTRY_SIZE + 12, 100);
        assert!(decode_woff(&lying, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Overwriting the orig_len to more than real zlib expanded size must fail.
        let mut inflated = woff;
        write_u32_be(
            &mut inflated,
            WOFF_HEADER_SIZE + WOFF_DIR_ENTRY_SIZE + 12,
            4_500,
        );
        assert!(decode_woff(&inflated, DEFAULT_MAX_DECODED_BYTES).is_none());
    }

    #[test]
    fn woff2_uint_base128_and_directory_validation() {
        // Leading zero 0x80 rejected.
        let mut r = Reader::new(&[0x80, 0x01], 0).unwrap();
        assert!(r.uint_base128().is_none());
        // Multi-byte valid: 16384 = 0x81 0x80 0x00.
        let mut r = Reader::new(&[0x81, 0x80, 0x00], 0).unwrap();
        assert_eq!(r.uint_base128(), Some(16384));
        // Overflow rejected.
        let mut r = Reader::new(&[0xFF, 0xFF, 0xFF, 0xFF, 0x7F], 0).unwrap();
        assert!(r.uint_base128().is_none());

        // TTCF flavor rejected.
        let ttc = build_woff2_header_dir(TTCF_FLAVOR, &[(b"name", 4, None, 0)], 4);
        assert!(inspect_woff2(&ttc, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Custom unknown tag uses index 63 + 4 inline bytes.
        let custom = build_woff2_header_dir(0x0001_0000, &[(b"FROG", 6, None, 0)], 4);
        let inspected = inspect_woff2(&custom, DEFAULT_MAX_DECODED_BYTES).unwrap();
        assert_eq!(inspected[2], 6);
        let sfnt = decode_woff2_from_stream(&custom, b"123456", DEFAULT_MAX_DECODED_BYTES).unwrap();
        assert_eq!(&sfnt[12..16], b"FROG");
        assert_eq!(&sfnt[28..34], b"123456");
    }

    #[test]
    fn woff2_reconstructs_transformed_glyf_loca_and_hmtx() {
        // Build transformed glyf: glyph 0 empty, glyph 1 triangle (50,0 on), (550,900 on), (1050,0 off).
        let mut glyf = Vec::new();
        push_u32_be(&mut glyf, 0); // version
        push_u16_be(&mut glyf, 2); // numGlyphs
        push_u16_be(&mut glyf, 0); // indexFormat
        let n_contour = [0u8, 0, 0, 1];
        let n_points = [3u8];
        let flags = [124u8 | 1 | 2, 124 | 1 | 2, 124 | 1 | 0x80]; // dx>=0, dy>=0; dx>=0, dy>=0; dx>=0, dy<0, off-curve
        let mut glyph_stream = Vec::new();
        for (ax, ay) in [(50u16, 0u16), (500, 900), (500, 900)] {
            push_u16_be(&mut glyph_stream, ax);
            push_u16_be(&mut glyph_stream, ay);
        }
        glyph_stream.push(0); // instructionLength
        let bbox = [0u8; 4];

        push_u32_be(&mut glyf, n_contour.len() as u32);
        push_u32_be(&mut glyf, n_points.len() as u32);
        push_u32_be(&mut glyf, flags.len() as u32);
        push_u32_be(&mut glyf, glyph_stream.len() as u32);
        push_u32_be(&mut glyf, 0);
        push_u32_be(&mut glyf, bbox.len() as u32);
        push_u32_be(&mut glyf, 0);
        glyf.extend_from_slice(&n_contour);
        glyf.extend_from_slice(&n_points);
        glyf.extend_from_slice(&flags);
        glyf.extend_from_slice(&glyph_stream);
        glyf.extend_from_slice(&bbox);

        let mut head = vec![0u8; 54];
        write_u32_be(&mut head, 0, 0x0001_0000);
        write_u16_be(&mut head, 50, 1); // originally 1; patched back to 0

        let mut hhea = vec![0u8; 36];
        write_u16_be(&mut hhea, 34, 2); // numHMetrics = 2

        let mut hmtx = vec![0x03u8]; // flags: both proportional and monospace lsbs omitted
        push_u16_be(&mut hmtx, 500);
        push_u16_be(&mut hmtx, 600);

        let mut stream = Vec::new();
        stream.extend_from_slice(&glyf);
        stream.extend_from_slice(&head);
        stream.extend_from_slice(&hhea);
        stream.extend_from_slice(&hmtx);

        let woff2 = build_woff2_header_dir(
            0x0001_0000,
            &[
                (b"glyf", 64, Some(glyf.len()), 0),
                (b"loca", 6, Some(0), 0),
                (b"head", head.len(), None, 0),
                (b"hhea", hhea.len(), None, 0),
                (b"hmtx", 8, Some(hmtx.len()), 1),
            ],
            4,
        );
        let sfnt = decode_woff2_from_stream(&woff2, &stream, DEFAULT_MAX_DECODED_BYTES).unwrap();

        // Parse sfnt directory to verify tables.
        let num_tables = read_u16_be(&sfnt, 4).unwrap() as usize;
        assert_eq!(num_tables, 5);
        let mut found_loca = None;
        let mut found_hmtx = None;
        let mut found_head = None;
        let mut found_glyf = None;
        let mut prev_tag = [0u8; 4];
        for i in 0..num_tables {
            let at = 12 + i * 16;
            let tag = [sfnt[at], sfnt[at + 1], sfnt[at + 2], sfnt[at + 3]];
            assert!(tag > prev_tag);
            prev_tag = tag;
            let off = read_u32_be(&sfnt, at + 8).unwrap() as usize;
            let len = read_u32_be(&sfnt, at + 12).unwrap() as usize;
            let data = &sfnt[off..off + len];
            match &tag {
                b"loca" => found_loca = Some(data),
                b"hmtx" => found_hmtx = Some(data),
                b"head" => found_head = Some(data),
                b"glyf" => found_glyf = Some(data),
                _ => {}
            }
        }
        let head_out = found_head.unwrap();
        assert_eq!(read_u16_be(head_out, 50), Some(0));
        let glyf_out = found_glyf.unwrap();
        assert_eq!(read_u16_be(glyf_out, 0), Some(1));
        assert_eq!(read_u16_be(glyf_out, 2).map(|v| v as i16), Some(50));
        assert_eq!(read_u16_be(glyf_out, 4).map(|v| v as i16), Some(0));
        assert_eq!(read_u16_be(glyf_out, 6).map(|v| v as i16), Some(1050));
        assert_eq!(read_u16_be(glyf_out, 8).map(|v| v as i16), Some(900));
        let loca_out = found_loca.unwrap();
        assert_eq!(loca_out.len(), 6);
        assert_eq!(read_u16_be(loca_out, 0), Some(0));
        assert_eq!(read_u16_be(loca_out, 2), Some(0));
        assert_eq!(read_u16_be(loca_out, 4), Some((glyf_out.len() / 2) as u16));
        let hmtx_out = found_hmtx.unwrap();
        assert_eq!(read_u16_be(hmtx_out, 0), Some(500));
        assert_eq!(read_u16_be(hmtx_out, 2).map(|v| v as i16), Some(0));
        assert_eq!(read_u16_be(hmtx_out, 4), Some(600));
        assert_eq!(read_u16_be(hmtx_out, 6).map(|v| v as i16), Some(50));
    }

    #[test]
    fn triplet_decode_covers_all_six_bands() {
        // Band 0 (<10): dx=0, dy=±(...)
        let mut r = Reader::new(&[0x10], 0).unwrap();
        assert_eq!(triplet_decode(1, &mut r), Some((0, 0x10)));
        let mut r = Reader::new(&[0x10], 0).unwrap();
        assert_eq!(triplet_decode(0, &mut r), Some((0, -0x10)));
        // Band 1 (10..20): dy=0, dx=±(...)
        let mut r = Reader::new(&[0x20], 0).unwrap();
        assert_eq!(triplet_decode(11, &mut r), Some((0x20, 0)));
        // Band 2 (20..84): 1 byte
        let mut r = Reader::new(&[0x12], 0).unwrap();
        assert_eq!(triplet_decode(20, &mut r), Some((-2, -3)));
        // Band 3 (84..120): 2 bytes
        let mut r = Reader::new(&[0x05, 0x06], 0).unwrap();
        assert_eq!(triplet_decode(87, &mut r), Some((6, 7)));
        // Band 4 (120..124): 3 bytes
        let mut r = Reader::new(&[0x01, 0x23, 0x45], 0).unwrap();
        assert_eq!(triplet_decode(123, &mut r), Some((0x012, 0x345)));
        // Band 5 (124..128): 4 bytes
        let mut r = Reader::new(&[0x01, 0x00, 0x02, 0x00], 0).unwrap();
        assert_eq!(triplet_decode(127, &mut r), Some((256, 512)));
    }

    fn build_woff2_with_brotli(
        flavor: u32,
        tables: &[(&[u8; 4], usize, Option<usize>, u8)],
        compressed: &[u8],
    ) -> Vec<u8> {
        let mut woff2 = build_woff2_header_dir(flavor, tables, compressed.len());
        let start = woff2.len() - compressed.len();
        woff2[start..].copy_from_slice(compressed);
        woff2
    }

    #[test]
    fn decode_woff2_end_to_end_with_transformed_glyf_loca_hmtx_and_brotli() {
        // Build transformed glyf: glyph 0 empty, glyph 1 triangle (50,0 on), (550,900 on), (1050,0 off).
        let mut glyf = Vec::new();
        push_u32_be(&mut glyf, 0); // version
        push_u16_be(&mut glyf, 2); // numGlyphs
        push_u16_be(&mut glyf, 0); // indexFormat
        let n_contour = [0u8, 0, 0, 1];
        let n_points = [3u8];
        let flags = [124u8 | 1 | 2, 124 | 1 | 2, 124 | 1 | 0x80];
        let mut glyph_stream = Vec::new();
        for (ax, ay) in [(50u16, 0u16), (500, 900), (500, 900)] {
            push_u16_be(&mut glyph_stream, ax);
            push_u16_be(&mut glyph_stream, ay);
        }
        glyph_stream.push(0); // instructionLength
        let bbox = [0u8; 4];

        push_u32_be(&mut glyf, n_contour.len() as u32);
        push_u32_be(&mut glyf, n_points.len() as u32);
        push_u32_be(&mut glyf, flags.len() as u32);
        push_u32_be(&mut glyf, glyph_stream.len() as u32);
        push_u32_be(&mut glyf, 0);
        push_u32_be(&mut glyf, bbox.len() as u32);
        push_u32_be(&mut glyf, 0);
        glyf.extend_from_slice(&n_contour);
        glyf.extend_from_slice(&n_points);
        glyf.extend_from_slice(&flags);
        glyf.extend_from_slice(&glyph_stream);
        glyf.extend_from_slice(&bbox);

        let mut head = vec![0u8; 54];
        write_u32_be(&mut head, 0, 0x0001_0000);
        write_u16_be(&mut head, 50, 1);

        let mut hhea = vec![0u8; 36];
        write_u16_be(&mut hhea, 34, 2);

        let mut hmtx = vec![0x03u8];
        push_u16_be(&mut hmtx, 500);
        push_u16_be(&mut hmtx, 600);

        let custom = b"FROG-CUSTOM";

        let mut raw_stream = Vec::new();
        raw_stream.extend_from_slice(&glyf);
        raw_stream.extend_from_slice(&head);
        raw_stream.extend_from_slice(&hhea);
        raw_stream.extend_from_slice(&hmtx);
        raw_stream.extend_from_slice(custom);

        let compressed = test_brotli_uncompressed(&raw_stream);
        let woff2 = build_woff2_with_brotli(
            0x0001_0000,
            &[
                (b"glyf", 64, Some(glyf.len()), 0),
                (b"loca", 6, Some(0), 0),
                (b"head", head.len(), None, 0),
                (b"hhea", hhea.len(), None, 0),
                (b"hmtx", 8, Some(hmtx.len()), 1),
                (b"FROG", custom.len(), None, 0),
            ],
            &compressed,
        );

        let sfnt = decode_woff2(&woff2, DEFAULT_MAX_DECODED_BYTES).unwrap();
        let expected =
            decode_woff2_from_stream(&woff2, &raw_stream, DEFAULT_MAX_DECODED_BYTES).unwrap();
        assert_eq!(sfnt, expected);
        assert_eq!(read_u32_be(&sfnt, 0), Some(0x0001_0000));
        assert_eq!(read_u16_be(&sfnt, 4), Some(6));
        // FROG sorts first in ASCII.
        assert_eq!(&sfnt[12..16], b"FROG");
        let frog_off = read_u32_be(&sfnt, 20).unwrap() as usize;
        let frog_len = read_u32_be(&sfnt, 24).unwrap() as usize;
        assert_eq!(&sfnt[frog_off..frog_off + frog_len], custom);
    }

    #[test]
    fn decode_woff2_handles_multi_metablock_brotli_stream() {
        // > 65536 bytes forces multiple RFC 7932 uncompressed meta-blocks.
        let payload: Vec<u8> = (0..150_000usize).map(|i| ((i * 31) & 0xFF) as u8).collect();
        let compressed = test_brotli_uncompressed(&payload);
        let woff2 = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"cmap", payload.len(), None, 0)],
            &compressed,
        );
        let sfnt = decode_woff2(&woff2, DEFAULT_MAX_DECODED_BYTES).unwrap();
        let off = read_u32_be(&sfnt, 20).unwrap() as usize;
        let len = read_u32_be(&sfnt, 24).unwrap() as usize;
        assert_eq!(&sfnt[off..off + len], &payload[..]);
    }

    #[test]
    fn decode_woff2_rejects_corrupt_truncated_trailing_budget_and_ttcf() {
        let payload: Vec<u8> = (0..64u8).collect();
        let compressed = test_brotli_uncompressed(&payload);

        // Valid baseline.
        let valid = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"name", payload.len(), None, 0)],
            &compressed,
        );
        assert!(decode_woff2(&valid, DEFAULT_MAX_DECODED_BYTES).is_some());

        // Corrupt Brotli stream.
        let mut corrupt_stream = compressed.clone();
        corrupt_stream[0] ^= 0xFF;
        let corrupt = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"name", payload.len(), None, 0)],
            &corrupt_stream,
        );
        assert!(decode_woff2(&corrupt, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Truncated Brotli stream (missing final ISLAST/ISLASTEMPTY marker).
        let truncated_stream = &compressed[..compressed.len() - 1];
        let truncated = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"name", payload.len(), None, 0)],
            truncated_stream,
        );
        assert!(decode_woff2(&truncated, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Truncated WOFF2 file overall.
        assert!(decode_woff2(&valid[..valid.len() - 4], DEFAULT_MAX_DECODED_BYTES).is_none());

        // Trailing extra Brotli data: directory declares 63 bytes while stream decodes 64.
        let trailing = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"name", payload.len() - 1, None, 0)],
            &compressed,
        );
        assert!(decode_woff2(&trailing, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Overstated directory length: directory declares 65 bytes while stream decodes 64.
        let overstated = build_woff2_with_brotli(
            0x0001_0000,
            &[(b"name", payload.len() + 1, None, 0)],
            &compressed,
        );
        assert!(decode_woff2(&overstated, DEFAULT_MAX_DECODED_BYTES).is_none());

        // Budget exceeded.
        assert!(decode_woff2(&valid, 50).is_none());
        assert!(decode_woff2(&valid, 0).is_none());

        // TTCF collection rejected.
        let ttcf = build_woff2_with_brotli(
            TTCF_FLAVOR,
            &[(b"name", payload.len(), None, 0)],
            &compressed,
        );
        assert!(decode_woff2(&ttcf, DEFAULT_MAX_DECODED_BYTES).is_none());
    }
}
