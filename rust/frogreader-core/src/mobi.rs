//! Native MOBI / AZW3 (KF8) container decoders and assemblers:
//! PDB container framing, PalmDOC/MOBI/EXTH headers,
//! PalmDOC LZ77, TrailingEntries, Huff/CDIC, FONT record deobfuscation/inflation,
//! INDX/TAGX/IDXT/CNCX parsing, and KF8 FDST/SKEL/FRAG reassembly.

use encoding_rs::{
    Encoding, BIG5, EUC_KR, GBK, ISO_8859_10, ISO_8859_13, ISO_8859_14, ISO_8859_15, ISO_8859_16,
    ISO_8859_2, ISO_8859_3, ISO_8859_4, ISO_8859_5, ISO_8859_6, ISO_8859_7, ISO_8859_8, MACINTOSH,
    SHIFT_JIS, UTF_8, WINDOWS_1250, WINDOWS_1251, WINDOWS_1252, WINDOWS_1253, WINDOWS_1254,
    WINDOWS_1255, WINDOWS_1256, WINDOWS_1257, WINDOWS_1258, WINDOWS_874,
};
use flate2::{Decompress, FlushDecompress, Status};

pub const PDB_HEADER_SIZE: usize = 78;
pub const TYPE_MOBI: &[u8] = b"BOOKMOBI";
pub const TYPE_PALMDOC: &[u8] = b"TEXtREAd";
const MAX_EXTH_ENTRIES: usize = 4096;
const MAX_FULL_NAME_LEN: usize = 2048;

const HUFF_MAX_DEPTH: usize = 32;
const FONT_HEADER: usize = 24;
const FONT_XOR_SPAN: usize = 1040;
const FONT_FLAG_ZLIB: u32 = 0x1;
const FONT_FLAG_OBFUSCATED: u32 = 0x2;

const MAX_INDX_RECORDS: usize = 4096;
const MAX_INDX_CNCX_RECORDS: usize = 64;
const MAX_INDX_ENTRIES_PER_RECORD: usize = 8192;
const MAX_FDST_FLOWS: usize = 4096;

/// Mirrors the `com.example.frogreader.data.parser.ResourceLimitKind` ordinals used by MOBI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum LimitKind {
    EntryCount = 0,
    ActualAggregate = 2,
    EntrySize = 3,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MobiError {
    Io(String),
    Limit(LimitKind, String),
}

// ---------------------------------------------------------------------------
// Big-endian primitives
// ---------------------------------------------------------------------------

#[inline]
fn u16_be(data: &[u8], off: usize) -> Option<u16> {
    let end = off.checked_add(2)?;
    let bytes: [u8; 2] = data.get(off..end)?.try_into().ok()?;
    Some(u16::from_be_bytes(bytes))
}

#[inline]
fn u32_be(data: &[u8], off: usize) -> Option<u32> {
    let end = off.checked_add(4)?;
    let bytes: [u8; 4] = data.get(off..end)?.try_into().ok()?;
    Some(u32::from_be_bytes(bytes))
}

/// Unsigned 32-bit as `usize`; `0x7FFF_FFFF` (`i32::MAX`) and above return `None`
/// to match `ByteArray.index32` (`value in 0 until Int.MAX_VALUE`).
#[inline]
fn index32_be(data: &[u8], off: usize) -> Option<usize> {
    let value = u32_be(data, off)?;
    if value < i32::MAX as u32 {
        Some(value as usize)
    } else {
        None
    }
}

#[inline]
fn has_magic(data: &[u8], off: usize, magic: &[u8]) -> bool {
    off.checked_add(magic.len())
        .and_then(|end| data.get(off..end))
        .is_some_and(|slice| slice == magic)
}

// ---------------------------------------------------------------------------
// PDB container & MOBI / PalmDOC / EXTH headers
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PdbHeaderInfo {
    pub name: String,
    pub type_creator: String,
    pub record_count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PalmDocHeaderInfo {
    pub compression: i32,
    pub text_length: i32,
    pub text_record_count: i32,
    pub record_size: i32,
    pub encryption_type: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MobiHeaderInfo {
    pub header_length: i32,
    pub mobi_type: i32,
    pub text_encoding: i32,
    pub file_version: i32,
    pub full_name: Option<String>,
    pub charset_name: String,
    pub locale: i32,
    pub first_non_book_index: i32,
    pub first_image_index: i32,
    pub huffman_record_offset: i32,
    pub huffman_record_count: i32,
    pub exth_flags: i32,
    pub extra_record_data_flags: i32,
    pub indx_record_offset: i32,
    pub fdst_record: i32,
    pub fdst_count: i32,
    pub skel_index: i32,
    pub frag_index: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExthEntry {
    pub entry_type: i32,
    pub data: Vec<u8>,
}

/// Sniffs whether a file header identifies a Mobipocket or PalmDOC PDB container.
pub fn is_pdb_book(header: &[u8]) -> bool {
    header.len() >= 68 && (has_magic(header, 60, TYPE_MOBI) || has_magic(header, 60, TYPE_PALMDOC))
}

/// Validates and decodes the 78-byte Palm Database header.
pub fn parse_pdb_header(
    head: &[u8],
    file_size: i64,
    max_mobi_records: usize,
) -> Result<PdbHeaderInfo, MobiError> {
    if file_size < (PDB_HEADER_SIZE as i64) + 8 || head.len() < PDB_HEADER_SIZE {
        return Err(MobiError::Io("Damaged MOBI: file too small".into()));
    }
    if file_size > i64::from(i32::MAX) {
        return Err(MobiError::Io("Damaged MOBI: file too large".into()));
    }
    let name_bytes = &head[0..32];
    let nul_pos = name_bytes.iter().position(|&b| b == 0).unwrap_or(32);
    let raw_name: String = name_bytes[..nul_pos]
        .iter()
        .copied()
        .map(char::from)
        .collect();
    let name = raw_name.trim_matches(|c: char| c <= ' ').to_owned();
    let type_creator: String = head[60..68].iter().copied().map(char::from).collect();
    let record_count = usize::from(u16_be(head, 76).unwrap_or(0));
    if record_count > max_mobi_records {
        return Err(MobiError::Limit(
            LimitKind::EntryCount,
            format!("MOBI has {record_count} records; limit is {max_mobi_records}"),
        ));
    }
    if record_count == 0 || file_size < (PDB_HEADER_SIZE as i64) + 8 * (record_count as i64) {
        return Err(MobiError::Io(format!(
            "Damaged MOBI: bad record count {record_count}"
        )));
    }
    Ok(PdbHeaderInfo {
        name,
        type_creator,
        record_count,
    })
}

/// Parses and validates the `record_count * 8` PDB record offset table, returning `record_count + 1`
/// monotonic byte offsets ending at `file_size`.
pub fn parse_pdb_offsets(
    table: &[u8],
    record_count: usize,
    file_size: i64,
) -> Result<Vec<i32>, MobiError> {
    let table_bytes = record_count
        .checked_mul(8)
        .ok_or_else(|| MobiError::Io(format!("Damaged MOBI: bad record count {record_count}")))?;
    let table_end = PDB_HEADER_SIZE
        .checked_add(table_bytes)
        .ok_or_else(|| MobiError::Io(format!("Damaged MOBI: bad record count {record_count}")))?;
    if record_count == 0
        || file_size > i64::from(i32::MAX)
        || file_size < table_end as i64
        || table.len() < table_bytes
    {
        return Err(MobiError::Io(format!(
            "Damaged MOBI: bad record count {record_count}"
        )));
    }
    let mut offsets = Vec::with_capacity(record_count + 1);
    for i in 0..record_count {
        let offset = index32_be(table, 8 * i).map(|v| v as i32).unwrap_or(-1);
        offsets.push(offset);
    }
    offsets.push(file_size as i32);
    let min_offset = table_end as i32;
    for i in 0..record_count {
        if offsets[i] < min_offset || offsets[i] > offsets[i + 1] {
            return Err(MobiError::Io(format!(
                "Damaged MOBI: bad record table at {i}"
            )));
        }
    }
    Ok(offsets)
}

/// Parses the 16-byte PalmDOC header at the start of record 0.
pub fn parse_palmdoc_header(record0: &[u8]) -> Result<PalmDocHeaderInfo, MobiError> {
    if record0.len() < 16 {
        return Err(MobiError::Io("Damaged MOBI: record 0 too small".into()));
    }
    Ok(PalmDocHeaderInfo {
        compression: i32::from(u16_be(record0, 0).unwrap_or(0)),
        text_length: index32_be(record0, 4).map(|v| v as i32).unwrap_or(0),
        text_record_count: i32::from(u16_be(record0, 8).unwrap_or(0)),
        record_size: i32::from(u16_be(record0, 10).unwrap_or(0)),
        encryption_type: i32::from(u16_be(record0, 12).unwrap_or(0)),
    })
}

/// Maps a MOBI numeric Windows/ISO codepage to its JVM `Charset` name.
pub fn codepage_charset_name(encoding: i32) -> String {
    match encoding {
        65001 => "UTF-8".into(),
        1250..=1258 => format!("windows-{encoding}"),
        932 => "windows-31j".into(),
        936 => "GBK".into(),
        949 => "x-windows-949".into(),
        950 => "Big5".into(),
        10000 => "x-MacRoman".into(),
        28591..=28606 if encoding != 28602 => format!("ISO-8859-{}", encoding - 28590),
        _ => "UTF-8".into(),
    }
}

fn codepage_encoding(encoding: i32) -> Option<&'static Encoding> {
    match encoding {
        65001 => Some(UTF_8),
        1250 => Some(WINDOWS_1250),
        1251 => Some(WINDOWS_1251),
        1252 => Some(WINDOWS_1252),
        1253 => Some(WINDOWS_1253),
        1254 => Some(WINDOWS_1254),
        1255 => Some(WINDOWS_1255),
        1256 => Some(WINDOWS_1256),
        1257 => Some(WINDOWS_1257),
        1258 => Some(WINDOWS_1258),
        932 => Some(SHIFT_JIS),
        936 => Some(GBK),
        949 => Some(EUC_KR),
        950 => Some(BIG5),
        10000 => Some(MACINTOSH),
        28591 => None, // Handled as exact 1:1 ISO-8859-1 (preserving 0x80..0x9F).
        28592 => Some(ISO_8859_2),
        28593 => Some(ISO_8859_3),
        28594 => Some(ISO_8859_4),
        28595 => Some(ISO_8859_5),
        28596 => Some(ISO_8859_6),
        28597 => Some(ISO_8859_7),
        28598 => Some(ISO_8859_8),
        28599 => Some(WINDOWS_1254),
        28600 => Some(ISO_8859_10),
        28601 => Some(WINDOWS_874),
        28603 => Some(ISO_8859_13),
        28604 => Some(ISO_8859_14),
        28605 => Some(ISO_8859_15),
        28606 => Some(ISO_8859_16),
        _ => Some(UTF_8),
    }
}

fn decode_mobi_codepage(encoding: i32, bytes: &[u8]) -> String {
    if encoding == 28591 {
        return bytes.iter().copied().map(char::from).collect();
    }
    let enc = codepage_encoding(encoding).unwrap_or(UTF_8);
    enc.decode_without_bom_handling(bytes).0.into_owned()
}

/// Parses the MOBI header following the PalmDOC header inside record 0.
/// Returns `Ok(None)` when record 0 has no `"MOBI"` magic (plain `TEXtREAd` PalmDOC).
pub fn parse_mobi_header(record0: &[u8]) -> Result<Option<MobiHeaderInfo>, MobiError> {
    if !has_magic(record0, 16, b"MOBI") {
        return Ok(None);
    }
    let header_len_i32 = index32_be(record0, 20).map(|v| v as i32).unwrap_or(-1);
    if header_len_i32 < 24 || 16 + i64::from(header_len_i32) > (record0.len() as i64) + 4096 {
        return Err(MobiError::Io(format!(
            "Damaged MOBI: bad header length {header_len_i32}"
        )));
    }
    let header_len = header_len_i32 as usize;

    let field = |rel: usize| -> i32 {
        if rel + 4 <= header_len && 16 + rel + 4 <= record0.len() {
            index32_be(record0, 16 + rel)
                .map(|v| v as i32)
                .unwrap_or(-1)
        } else {
            -1
        }
    };

    let text_encoding = field(0x0C);
    let file_version = field(0x14);
    let full_name_offset = field(0x44);
    let full_name_length = field(0x48);
    let full_name =
        if full_name_offset > 0 && (1..=MAX_FULL_NAME_LEN as i32).contains(&full_name_length) {
            let start = full_name_offset as usize;
            let len = full_name_length as usize;
            start
                .checked_add(len)
                .filter(|&end| end <= record0.len())
                .and_then(|end| {
                    let decoded = decode_mobi_codepage(text_encoding, &record0[start..end]);
                    let cleaned = decoded
                        .split('\0')
                        .next()
                        .unwrap_or("")
                        .trim_matches(|c: char| c <= ' ');
                    if cleaned.is_empty() {
                        None
                    } else {
                        Some(cleaned.to_owned())
                    }
                })
        } else {
            None
        };

    let extra_record_data_flags = if header_len >= 0xE4 && 16 + 0xE2 + 2 <= record0.len() {
        i32::from(u16_be(record0, 16 + 0xE2).unwrap_or(0))
    } else {
        0
    };

    let is_kf8 = file_version >= 8;
    Ok(Some(MobiHeaderInfo {
        header_length: header_len_i32,
        mobi_type: field(0x08),
        text_encoding,
        file_version,
        full_name,
        charset_name: codepage_charset_name(text_encoding),
        locale: field(0x4C),
        first_non_book_index: field(0x40),
        first_image_index: field(0x5C),
        huffman_record_offset: field(0x60),
        huffman_record_count: field(0x64),
        exth_flags: field(0x70),
        extra_record_data_flags,
        indx_record_offset: field(0xE4),
        fdst_record: if is_kf8 { field(0xB0) } else { -1 },
        fdst_count: if is_kf8 { field(0xB4) } else { -1 },
        skel_index: if is_kf8 { field(0xEC) } else { -1 },
        frag_index: if is_kf8 { field(0xF0) } else { -1 },
    }))
}

/// Parses the `EXTH` metadata block at `16 + mobi_header_length`, tolerating up to 3 padding bytes.
/// Mid-list truncation preserves entries parsed so far.
pub fn parse_exth(record0: &[u8], mobi_header_length: i32) -> Vec<ExthEntry> {
    let Ok(header_len) = usize::try_from(mobi_header_length) else {
        return Vec::new();
    };
    let Some(base) = 16usize.checked_add(header_len) else {
        return Vec::new();
    };
    let Some(start) = (base..=base.saturating_add(3)).find(|&pos| has_magic(record0, pos, b"EXTH"))
    else {
        return Vec::new();
    };
    if start.checked_add(12).is_none_or(|end| end > record0.len()) {
        return Vec::new();
    }
    let Some(count) = index32_be(record0, start + 8) else {
        return Vec::new();
    };
    if count > MAX_EXTH_ENTRIES {
        return Vec::new();
    }
    let mut entries = Vec::with_capacity(count.min(64));
    let mut p = start + 12;
    for _ in 0..count {
        if p.checked_add(8).is_none_or(|end| end > record0.len()) {
            break;
        }
        let Some(entry_type) = index32_be(record0, p) else {
            break;
        };
        let Some(record_length) = index32_be(record0, p + 4) else {
            break;
        };
        if record_length < 8 {
            break;
        }
        let Some(next_p) = p.checked_add(record_length) else {
            break;
        };
        if next_p > record0.len() {
            break;
        }
        entries.push(ExthEntry {
            entry_type: entry_type as i32,
            data: record0[p + 8..next_p].to_vec(),
        });
        p = next_p;
    }
    entries
}

// ---------------------------------------------------------------------------
// PalmDOC & TrailingEntries
// ---------------------------------------------------------------------------

/// Decompresses a single PalmDOC LZ77 payload (`src`) into a fresh byte buffer
/// bounded by `max_out` bytes.
pub fn palmdoc_decompress(src: &[u8], max_out: usize) -> Result<Vec<u8>, MobiError> {
    let mut out = Vec::new();
    let initial = src.len().saturating_mul(2).min(max_out).min(64 * 1024);
    let _ = out.try_reserve(initial);

    let mut i = 0;
    while i < src.len() {
        let c = src[i];
        i += 1;
        match c {
            0x00 => {
                if out.len() >= max_out {
                    return Err(MobiError::Io("PalmDoc: output overflow".into()));
                }
                out.push(0);
            }
            0x01..=0x08 => {
                let count = usize::from(c);
                let end = i
                    .checked_add(count)
                    .ok_or_else(|| MobiError::Io("PalmDoc: truncated literal run".into()))?;
                let slice = src
                    .get(i..end)
                    .ok_or_else(|| MobiError::Io("PalmDoc: truncated literal run".into()))?;
                if count > max_out.saturating_sub(out.len()) {
                    return Err(MobiError::Io("PalmDoc: output overflow".into()));
                }
                out.extend_from_slice(slice);
                i = end;
            }
            0x09..=0x7F => {
                if out.len() >= max_out {
                    return Err(MobiError::Io("PalmDoc: output overflow".into()));
                }
                out.push(c);
            }
            0x80..=0xBF => {
                let Some(&next) = src.get(i) else {
                    return Err(MobiError::Io("PalmDoc: truncated back-reference".into()));
                };
                i += 1;
                let pair = (usize::from(c) << 8) | usize::from(next);
                let distance = (pair >> 3) & 0x7FF;
                let length = (pair & 7) + 3;
                if distance == 0 || distance > out.len() {
                    return Err(MobiError::Io("PalmDoc: bad back-reference".into()));
                }
                if length > max_out.saturating_sub(out.len()) {
                    return Err(MobiError::Io("PalmDoc: output overflow".into()));
                }
                for _ in 0..length {
                    let b = out[out.len() - distance];
                    out.push(b);
                }
            }
            0xC0..=0xFF => {
                if 2 > max_out.saturating_sub(out.len()) {
                    return Err(MobiError::Io("PalmDoc: output overflow".into()));
                }
                out.push(b' ');
                out.push(c ^ 0x80);
            }
        }
    }
    Ok(out)
}

/// Reads the backward-encoded varint at the end of `data` (up to 4 bytes).
pub fn backward_varint(data: &[u8]) -> i32 {
    let start = data.len().saturating_sub(4);
    let mut num: i32 = 0;
    for &v in &data[start..] {
        if v & 0x80 != 0 {
            num = 0;
        }
        num = (num << 7) | i32::from(v & 0x7F);
    }
    num
}

/// Computes the actual compressed payload length of a record after trimming trailing entries.
pub fn trailing_content_length(data: &[u8], flags: u32) -> usize {
    let mut n = data.len();
    let mut f = flags >> 1;
    while f != 0 {
        if f & 1 != 0 {
            let size = backward_varint(&data[..n]);
            if size <= 0 || (size as usize) > n {
                break;
            }
            n -= size as usize;
        }
        f >>= 1;
    }
    if flags & 1 != 0 && n > 0 {
        let overlap = usize::from(data[n - 1] & 0x03) + 1;
        n = n.saturating_sub(overlap);
    }
    n
}

// ---------------------------------------------------------------------------
// Huff/CDIC decompression
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct PhraseEntry {
    bytes: Option<Vec<u8>>,
    terminal: bool,
}

enum HuffSink<'a> {
    Overflow {
        out: &'a mut Vec<u8>,
        max_out: usize,
    },
    Limit {
        out: &'a mut Vec<u8>,
        max_bytes: i32,
    },
}

impl HuffSink<'_> {
    fn write(&mut self, slice: &[u8]) -> Result<(), MobiError> {
        match self {
            HuffSink::Overflow { out, max_out } => {
                if slice.len() > max_out.saturating_sub(out.len()) {
                    return Err(MobiError::Io("Damaged MOBI: HUFF output overflow".into()));
                }
                out.try_reserve(slice.len())
                    .map_err(|_| MobiError::Io("Damaged MOBI: HUFF output overflow".into()))?;
                out.extend_from_slice(slice);
                Ok(())
            }
            HuffSink::Limit { out, max_bytes } => {
                if (slice.len() as i64) > i64::from(*max_bytes) - (out.len() as i64) {
                    return Err(MobiError::Limit(
                        LimitKind::EntrySize,
                        format!("MOBI HUFF output exceeds {max_bytes} bytes"),
                    ));
                }
                out.try_reserve(slice.len()).map_err(|_| {
                    MobiError::Limit(
                        LimitKind::EntrySize,
                        format!("MOBI HUFF output exceeds {max_bytes} bytes"),
                    )
                })?;
                out.extend_from_slice(slice);
                Ok(())
            }
        }
    }
}

/// Canonical Huffman + CDIC phrase dictionary decompressor (MOBI compression 17480).
pub struct HuffCdic {
    code_lengths: [u8; 256],
    terminates: [bool; 256],
    max_code1: [u64; 256],
    min_code: [u64; 33],
    max_code: [u64; 33],
    dictionary: Vec<PhraseEntry>,
    max_decoded_bytes: i32,
    max_dictionary_bytes: i32,
    source_bytes: i64,
    phrase_bytes: i64,
    decoded_dictionary_bytes: i64,
}

impl HuffCdic {
    /// Initializes canonical Huffman tables from a `HUFF` record.
    pub fn new(
        huff_record: &[u8],
        max_decoded_bytes: i32,
        max_dictionary_bytes: i32,
    ) -> Result<Self, MobiError> {
        if !has_magic(huff_record, 0, b"HUFF") || huff_record.len() < 24 {
            return Err(MobiError::Io("Damaged MOBI: bad HUFF record".into()));
        }
        let Some(cache_offset) = index32_be(huff_record, 8) else {
            return Err(MobiError::Io(
                "Damaged MOBI: HUFF tables out of range".into(),
            ));
        };
        let Some(base_offset) = index32_be(huff_record, 12) else {
            return Err(MobiError::Io(
                "Damaged MOBI: HUFF tables out of range".into(),
            ));
        };
        if cache_offset
            .checked_add(256 * 4)
            .is_none_or(|end| end > huff_record.len())
            || base_offset
                .checked_add(64 * 4)
                .is_none_or(|end| end > huff_record.len())
        {
            return Err(MobiError::Io(
                "Damaged MOBI: HUFF tables out of range".into(),
            ));
        }

        let mut code_lengths = [0u8; 256];
        let mut terminates = [false; 256];
        let mut max_code1 = [0u64; 256];
        for b in 0..256 {
            let v = u32_be(huff_record, cache_offset + 4 * b)
                .ok_or_else(|| MobiError::Io("Damaged MOBI: HUFF tables out of range".into()))?;
            let len = (v & 0x1F) as usize;
            if !(1..=32).contains(&len) {
                return Err(MobiError::Io(format!(
                    "Damaged MOBI: HUFF code length {len}"
                )));
            }
            code_lengths[b] = len as u8;
            terminates[b] = (v & 0x80) != 0;
            max_code1[b] = (((u64::from(v) >> 8) + 1) << (32 - len)) - 1;
        }

        let mut min_code = [0u64; 33];
        let mut max_code = [0u64; 33];
        for len in 1..=32 {
            let min_val = u32_be(huff_record, base_offset + 8 * (len - 1))
                .ok_or_else(|| MobiError::Io("Damaged MOBI: HUFF tables out of range".into()))?;
            let max_val = u32_be(huff_record, base_offset + 8 * (len - 1) + 4)
                .ok_or_else(|| MobiError::Io("Damaged MOBI: HUFF tables out of range".into()))?;
            min_code[len] = u64::from(min_val) << (32 - len);
            max_code[len] = ((u64::from(max_val) + 1) << (32 - len)) - 1;
        }

        Ok(Self {
            code_lengths,
            terminates,
            max_code1,
            min_code,
            max_code,
            dictionary: Vec::new(),
            max_decoded_bytes,
            max_dictionary_bytes,
            source_bytes: 0,
            phrase_bytes: 0,
            decoded_dictionary_bytes: 0,
        })
    }

    /// Appends one `CDIC` phrase dictionary record sequentially.
    pub fn add_cdic(&mut self, cdic: &[u8]) -> Result<(), MobiError> {
        if (cdic.len() as i64) > i64::from(self.max_dictionary_bytes) - self.source_bytes {
            return Err(self.dictionary_limit());
        }
        self.source_bytes += cdic.len() as i64;
        if !has_magic(cdic, 0, b"CDIC") || cdic.len() < 16 {
            return Err(MobiError::Io("Damaged MOBI: bad CDIC record".into()));
        }
        let Some(total) = index32_be(cdic, 8) else {
            return Err(MobiError::Io("Damaged MOBI: CDIC header".into()));
        };
        let Some(bits) = index32_be(cdic, 12) else {
            return Err(MobiError::Io("Damaged MOBI: CDIC header".into()));
        };
        if bits > 16 {
            return Err(MobiError::Io("Damaged MOBI: CDIC header".into()));
        }
        let remaining_phrases = total.saturating_sub(self.dictionary.len());
        let here = (1usize << bits).min(remaining_phrases);

        for i in 0..here {
            let off_pos = 16 + 2 * i;
            let Some(offset) = u16_be(cdic, off_pos).map(usize::from) else {
                return Err(MobiError::Io("Damaged MOBI: CDIC offsets".into()));
            };
            let at = 16usize
                .checked_add(offset)
                .ok_or_else(|| MobiError::Io("Damaged MOBI: CDIC entry".into()))?;
            let Some(length_word) = u16_be(cdic, at) else {
                return Err(MobiError::Io("Damaged MOBI: CDIC entry".into()));
            };
            let length = usize::from(length_word & 0x7FFF);
            let phrase_start = at + 2;
            let phrase_end = phrase_start
                .checked_add(length)
                .ok_or_else(|| MobiError::Io("Damaged MOBI: CDIC phrase".into()))?;
            let Some(phrase) = cdic.get(phrase_start..phrase_end) else {
                return Err(MobiError::Io("Damaged MOBI: CDIC phrase".into()));
            };
            if (length as i64) > i64::from(self.max_dictionary_bytes) - self.phrase_bytes {
                return Err(self.dictionary_limit());
            }
            self.phrase_bytes += length as i64;
            self.dictionary.push(PhraseEntry {
                bytes: Some(phrase.to_vec()),
                terminal: (length_word & 0x8000) != 0,
            });
        }
        Ok(())
    }

    /// Decompresses a record into a fixed-capacity buffer (`IOException` on overflow).
    pub fn decompress(&mut self, src: &[u8], max_out: usize) -> Result<Vec<u8>, MobiError> {
        let mut out = Vec::new();
        let initial = src.len().saturating_mul(2).min(max_out).min(8192);
        let _ = out.try_reserve(initial);
        let mut sink = HuffSink::Overflow {
            out: &mut out,
            max_out,
        };
        self.decode_into(src, HUFF_MAX_DEPTH, &mut sink)?;
        Ok(out)
    }

    /// Decompresses a record enforcing `ResourceLimitException` when output exceeds `max_output_bytes`.
    pub fn decompress_bounded(
        &mut self,
        src: &[u8],
        max_output_bytes: i32,
    ) -> Result<Vec<u8>, MobiError> {
        let mut out = Vec::new();
        let initial = (max_output_bytes.clamp(0, 8192) as usize).min(src.len().saturating_mul(2));
        let _ = out.try_reserve(initial);
        let mut sink = HuffSink::Limit {
            out: &mut out,
            max_bytes: max_output_bytes,
        };
        self.decode_into(src, HUFF_MAX_DEPTH, &mut sink)?;
        Ok(out)
    }

    fn decode_into(
        &mut self,
        src: &[u8],
        depth: usize,
        sink: &mut HuffSink<'_>,
    ) -> Result<(), MobiError> {
        let mut buf = Vec::new();
        buf.try_reserve_exact(src.len() + 8)
            .map_err(|_| MobiError::Io("Damaged MOBI: HUFF output overflow".into()))?;
        buf.extend_from_slice(src);
        buf.resize(src.len() + 8, 0);

        let mut bits_left = (src.len() as i64) * 8;
        let mut pos = 0usize;
        let mut x = read_u64_be(&buf, pos);
        let mut n: i32 = 32;

        loop {
            if n <= 0 {
                pos += 4;
                x = read_u64_be(&buf, pos);
                n += 32;
            }
            let code = (x >> (n as u32)) & 0xFFFF_FFFF;
            let b = (code >> 24) as usize;
            let mut code_length = usize::from(self.code_lengths[b]);
            let mut max = self.max_code1[b];
            if !self.terminates[b] {
                while code < self.min_code[code_length] {
                    code_length += 1;
                    if code_length > 32 {
                        return Err(MobiError::Io("Damaged MOBI: HUFF code".into()));
                    }
                }
                max = self.max_code[code_length];
            }
            n -= code_length as i32;
            bits_left -= code_length as i64;
            if bits_left < 0 {
                break;
            }

            let index = (max.wrapping_sub(code) >> (32 - code_length)) as u32 as i32;
            if index < 0 || (index as usize) >= self.dictionary.len() {
                return Err(MobiError::Io(format!(
                    "Damaged MOBI: HUFF dictionary index {index}"
                )));
            }
            self.emit_resolved(index as usize, depth, sink)?;
        }
        Ok(())
    }

    fn emit_resolved(
        &mut self,
        index: usize,
        depth: usize,
        sink: &mut HuffSink<'_>,
    ) -> Result<(), MobiError> {
        if self.dictionary[index].terminal {
            let bytes = self.dictionary[index]
                .bytes
                .as_deref()
                .ok_or_else(|| MobiError::Io("Damaged MOBI: cyclic CDIC phrase".into()))?;
            return sink.write(bytes);
        }
        let phrase = self.dictionary[index]
            .bytes
            .take()
            .ok_or_else(|| MobiError::Io("Damaged MOBI: cyclic CDIC phrase".into()))?;
        if depth == 0 {
            return Err(MobiError::Io(
                "Damaged MOBI: CDIC recursion too deep".into(),
            ));
        }
        let remaining = i64::from(self.max_decoded_bytes) - self.decoded_dictionary_bytes;
        if remaining <= 0 {
            return Err(self.decoded_limit());
        }
        let max_phrase_bytes = remaining.min(i64::from(i32::MAX)) as i32;
        let mut decoded = Vec::new();
        let mut sub_sink = HuffSink::Limit {
            out: &mut decoded,
            max_bytes: max_phrase_bytes,
        };
        self.decode_into(&phrase, depth - 1, &mut sub_sink)?;
        self.decoded_dictionary_bytes += decoded.len() as i64;
        self.dictionary[index] = PhraseEntry {
            bytes: Some(decoded),
            terminal: true,
        };
        let bytes = self.dictionary[index].bytes.as_deref().unwrap_or(&[]);
        sink.write(bytes)
    }

    fn decoded_limit(&self) -> MobiError {
        MobiError::Limit(
            LimitKind::EntrySize,
            format!(
                "MOBI HUFF text expands beyond {} bytes",
                self.max_decoded_bytes
            ),
        )
    }

    fn dictionary_limit(&self) -> MobiError {
        MobiError::Limit(
            LimitKind::EntrySize,
            format!(
                "MOBI HUFF dictionary exceeds {} bytes",
                self.max_dictionary_bytes
            ),
        )
    }
}

#[inline]
fn read_u64_be(buf: &[u8], off: usize) -> u64 {
    let bytes: [u8; 8] = buf
        .get(off..off + 8)
        .and_then(|s| s.try_into().ok())
        .unwrap_or([0; 8]);
    u64::from_be_bytes(bytes)
}

// ---------------------------------------------------------------------------
// MobiFontRecord
// ---------------------------------------------------------------------------

/// Decodes a Kindle `"FONT"` resource record (optional XOR deobfuscation over the
/// first 1040 bytes and optional zlib inflation), returning `None` on any structural
/// damage or if output exceeds `max_decoded_bytes`.
pub fn decode_font_record(data: &[u8], max_decoded_bytes: i32) -> Option<Vec<u8>> {
    if max_decoded_bytes <= 0 || data.len() < FONT_HEADER || !has_magic(data, 0, b"FONT") {
        return None;
    }
    let max_bytes = max_decoded_bytes as usize;
    let usize_field = u32_be(data, 4)? as usize;
    let flags = u32_be(data, 8)?;
    let data_start = index32_be(data, 12)?;
    let xor_len = index32_be(data, 16)?;
    let xor_start = index32_be(data, 20)?;
    if data_start < FONT_HEADER || data_start > data.len() || usize_field > max_bytes {
        return None;
    }

    let mut font = data[data_start..].to_vec();
    if flags & FONT_FLAG_OBFUSCATED != 0 {
        if xor_len == 0
            || xor_start
                .checked_add(xor_len)
                .is_none_or(|end| end > data.len())
        {
            return None;
        }
        let xor_key = &data[xor_start..xor_start + xor_len];
        let limit = font.len().min(FONT_XOR_SPAN);
        for (i, byte) in font[..limit].iter_mut().enumerate() {
            *byte ^= xor_key[i % xor_len];
        }
    }

    if flags & FONT_FLAG_ZLIB != 0 {
        let mut decompress = Decompress::new(true);
        let mut out = Vec::new();
        let initial = usize_field.clamp(1024, 64 * 1024).min(max_bytes);
        out.try_reserve(initial).ok()?;
        let mut in_pos = 0usize;
        let mut chunk = [0u8; 8192];
        loop {
            let before_in = decompress.total_in();
            let before_out = decompress.total_out();
            let status = decompress
                .decompress(&font[in_pos..], &mut chunk, FlushDecompress::None)
                .ok()?;
            let consumed = (decompress.total_in() - before_in) as usize;
            let produced = (decompress.total_out() - before_out) as usize;
            in_pos += consumed;
            if produced > max_bytes - out.len() {
                return None;
            }
            if produced > 0 {
                out.try_reserve(produced).ok()?;
                out.extend_from_slice(&chunk[..produced]);
            }
            match status {
                Status::StreamEnd => return Some(out),
                Status::Ok => {
                    if consumed == 0 && produced == 0 {
                        return None;
                    }
                }
                Status::BufError => {
                    if in_pos >= font.len() || (consumed == 0 && produced == 0) {
                        return None;
                    }
                }
            }
        }
    }

    if font.len() <= max_bytes {
        Some(font)
    } else {
        None
    }
}

// ---------------------------------------------------------------------------
// MobiIndex (INDX / TAGX / IDXT / CNCX)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct IndxLimits {
    pub max_tagx_entries: usize,
    pub max_entries: usize,
    pub max_values: usize,
    pub max_cncx_entries: usize,
    pub max_cncx_bytes: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IndexEntry {
    pub label: String,
    pub tags: Vec<(u8, Vec<i64>)>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedIndex {
    pub entries: Vec<IndexEntry>,
    pub cncx: Vec<(i32, String)>,
}

#[derive(Debug, Clone, Copy)]
struct TagxEntry {
    tag: u8,
    values_per_entry: usize,
    bitmask: u8,
    end_flag: bool,
}

struct PendingTag {
    tag: u8,
    value_count: Option<usize>,
    value_bytes: Option<i64>,
    values_per_entry: usize,
}

/// Forward varint: 7 bits per byte, big-endian, terminating on high bit `0x80`.
pub fn forward_varint(data: &[u8], offset: usize) -> Option<(i64, usize)> {
    let mut value: i64 = 0;
    let mut consumed: usize = 0;
    loop {
        if consumed >= 8 {
            return None;
        }
        let &byte = data.get(offset.checked_add(consumed)?)?;
        consumed += 1;
        value = (value << 7) | i64::from(byte & 0x7F);
        if byte & 0x80 != 0 {
            return Some((value, consumed));
        }
    }
}

/// Stateful incremental parser for a MOBI `INDX` cluster.
pub struct IndxParser {
    tagx: Vec<TagxEntry>,
    control_byte_count: usize,
    pub entry_record_count: usize,
    pub cncx_count: usize,
    limits: IndxLimits,
    entries: Vec<IndexEntry>,
    value_count: usize,
    cncx: Vec<(i32, String)>,
    cncx_base: i64,
    cncx_bytes: i64,
}

impl IndxParser {
    /// Parses the header `INDX` record and its `TAGX` table.
    /// Returns `Ok(None)` on structural damage so non-required callers degrade cleanly.
    pub fn begin(header: &[u8], limits: IndxLimits) -> Result<Option<Self>, MobiError> {
        if !has_magic(header, 0, b"INDX") {
            return Ok(None);
        }
        let Some(header_len) = index32_be(header, 4) else {
            return Ok(None);
        };
        if header_len < 56 || header_len > header.len() {
            return Ok(None);
        }
        let Some(entry_record_count) = index32_be(header, 24) else {
            return Ok(None);
        };
        let Some(cncx_count) = index32_be(header, 52) else {
            return Ok(None);
        };
        if entry_record_count > MAX_INDX_RECORDS || cncx_count > MAX_INDX_CNCX_RECORDS {
            return Ok(None);
        }

        if !has_magic(header, header_len, b"TAGX") {
            return Ok(None);
        }
        let Some(tagx_len) = index32_be(header, header_len + 4) else {
            return Ok(None);
        };
        let Some(control_byte_count) = index32_be(header, header_len + 8) else {
            return Ok(None);
        };
        if tagx_len < 12
            || header_len
                .checked_add(tagx_len)
                .is_none_or(|end| end > header.len())
        {
            return Ok(None);
        }
        if !(1..=8).contains(&control_byte_count) {
            return Ok(None);
        }
        let tagx_entry_count = (tagx_len - 12) / 4;
        if tagx_entry_count > limits.max_tagx_entries {
            return Err(count_limit("MOBI TAGX", limits.max_tagx_entries));
        }

        let mut tagx = Vec::with_capacity(tagx_entry_count);
        let tagx_end = header_len + tagx_len;
        let mut p = header_len + 12;
        while p + 4 <= tagx_end {
            tagx.push(TagxEntry {
                tag: header[p],
                values_per_entry: usize::from(header[p + 1]),
                bitmask: header[p + 2],
                end_flag: (header[p + 3] & 0x01) != 0,
            });
            p += 4;
        }
        if tagx.is_empty() {
            return Ok(None);
        }

        Ok(Some(Self {
            tagx,
            control_byte_count,
            entry_record_count,
            cncx_count,
            limits,
            entries: Vec::new(),
            value_count: 0,
            cncx: Vec::new(),
            cncx_base: 0,
            cncx_bytes: 0,
        }))
    }

    /// Parses one `INDX` entry record. Returns `Ok(false)` on structural damage.
    pub fn add_entry_record(&mut self, record: &[u8]) -> Result<bool, MobiError> {
        if !has_magic(record, 0, b"INDX") {
            return Ok(false);
        }
        let Some(idxt_offset) = index32_be(record, 20) else {
            return Ok(false);
        };
        let Some(count) = index32_be(record, 24) else {
            return Ok(false);
        };
        if count > MAX_INDX_ENTRIES_PER_RECORD {
            return Ok(false);
        }
        if count > self.limits.max_entries.saturating_sub(self.entries.len()) {
            return Err(count_limit("MOBI index", self.limits.max_entries));
        }
        if !has_magic(record, idxt_offset, b"IDXT") {
            return Ok(false);
        }
        for e in 0..count {
            let Some(off_at) = idxt_offset.checked_add(4 + 2 * e) else {
                return Ok(false);
            };
            let Some(entry_offset) = u16_be(record, off_at).map(usize::from) else {
                return Ok(false);
            };
            let Some(entry) = parse_indx_entry(
                record,
                entry_offset,
                &self.tagx,
                self.control_byte_count,
                &mut self.value_count,
                self.limits.max_values,
            )?
            else {
                return Ok(false);
            };
            self.entries.push(entry);
        }
        Ok(true)
    }

    /// Parses one `CNCX` string pool record.
    pub fn add_cncx_record(&mut self, record: &[u8]) -> Result<(), MobiError> {
        let mut q = 0usize;
        while q < record.len() {
            let start = q;
            let Some((length, consumed)) = forward_varint(record, q) else {
                break;
            };
            if length <= 0 || (q + consumed) as i64 + length > record.len() as i64 {
                break;
            }
            if self.cncx.len() >= self.limits.max_cncx_entries {
                return Err(count_limit("MOBI CNCX", self.limits.max_cncx_entries));
            }
            let decoded_bytes = length.saturating_mul(2);
            if self.cncx_bytes > self.limits.max_cncx_bytes - decoded_bytes
                || self.cncx_base + (start as i64) > i64::from(i32::MAX)
            {
                return Err(MobiError::Limit(
                    LimitKind::ActualAggregate,
                    format!(
                        "MOBI CNCX exceeds {} retained bytes",
                        self.limits.max_cncx_bytes
                    ),
                ));
            }
            self.cncx_bytes += decoded_bytes;
            let len_usize = length as usize;
            let text = String::from_utf8_lossy(&record[q + consumed..q + consumed + len_usize])
                .into_owned();
            self.cncx
                .push(((self.cncx_base + start as i64) as i32, text));
            q += consumed + len_usize;
        }
        self.cncx_base += record.len() as i64;
        Ok(())
    }

    pub fn finish(self) -> ParsedIndex {
        ParsedIndex {
            entries: self.entries,
            cncx: self.cncx,
        }
    }
}

fn parse_indx_entry(
    record: &[u8],
    entry_offset: usize,
    tagx: &[TagxEntry],
    control_byte_count: usize,
    value_count: &mut usize,
    max_values: usize,
) -> Result<Option<IndexEntry>, MobiError> {
    let Some(&len_byte) = record.get(entry_offset) else {
        return Ok(None);
    };
    let label_len = usize::from(len_byte);
    let label_start = entry_offset + 1;
    let Some(control_start) = label_start.checked_add(label_len) else {
        return Ok(None);
    };
    let Some(mut data_start) = control_start.checked_add(control_byte_count) else {
        return Ok(None);
    };
    if data_start > record.len() {
        return Ok(None);
    }
    let label: String = record[label_start..control_start]
        .iter()
        .map(|&b| char::from(b))
        .collect();

    let mut pending = Vec::new();
    let mut control_index = 0usize;
    for entry in tagx {
        if entry.end_flag {
            control_index += 1;
            continue;
        }
        if control_index >= control_byte_count {
            return Ok(None);
        }
        let value = record[control_start + control_index] & entry.bitmask;
        if value == 0 {
            continue;
        }
        if value == entry.bitmask {
            if entry.bitmask.count_ones() > 1 {
                let Some((total_bytes, consumed)) = forward_varint(record, data_start) else {
                    return Ok(None);
                };
                data_start += consumed;
                pending.push(PendingTag {
                    tag: entry.tag,
                    value_count: None,
                    value_bytes: Some(total_bytes),
                    values_per_entry: entry.values_per_entry,
                });
            } else {
                pending.push(PendingTag {
                    tag: entry.tag,
                    value_count: Some(1),
                    value_bytes: None,
                    values_per_entry: entry.values_per_entry,
                });
            }
        } else {
            let mut mask = entry.bitmask;
            let mut shifted = value;
            while (mask & 0x01) == 0 {
                mask >>= 1;
                shifted >>= 1;
            }
            pending.push(PendingTag {
                tag: entry.tag,
                value_count: Some(usize::from(shifted)),
                value_bytes: None,
                values_per_entry: entry.values_per_entry,
            });
        }
    }

    let mut tags = Vec::with_capacity(pending.len());
    for tag in pending {
        let mut values = Vec::new();
        if let Some(count) = tag.value_count {
            let total = count.saturating_mul(tag.values_per_entry);
            for _ in 0..total {
                let Some((val, consumed)) = forward_varint(record, data_start) else {
                    return Ok(None);
                };
                data_start += consumed;
                if *value_count >= max_values {
                    return Err(count_limit("MOBI index values", max_values));
                }
                *value_count += 1;
                values.push(val);
            }
        } else {
            let target_bytes = tag.value_bytes.unwrap_or(0);
            let mut consumed_total: i64 = 0;
            while consumed_total < target_bytes {
                let Some((val, consumed)) = forward_varint(record, data_start) else {
                    return Ok(None);
                };
                data_start += consumed;
                consumed_total += consumed as i64;
                if *value_count >= max_values {
                    return Err(count_limit("MOBI index values", max_values));
                }
                *value_count += 1;
                values.push(val);
            }
        }
        tags.push((tag.tag, values));
    }

    Ok(Some(IndexEntry { label, tags }))
}

fn count_limit(label: &str, max: usize) -> MobiError {
    MobiError::Limit(
        LimitKind::EntryCount,
        format!("{label} exceeds {max} retained entries"),
    )
}

// ---------------------------------------------------------------------------
// Kf8Assembler (FDST flow splitting + SKEL/FRAG reassembly)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct Kf8Limits {
    pub max_parts: usize,
    pub max_fragments: usize,
    pub max_part_bytes: i64,
    pub max_assembled_bytes: i64,
}

#[derive(Debug, Clone)]
pub struct SkelInput {
    pub num_frags: Option<i64>,
    pub geometry: Option<Vec<i64>>,
}

#[derive(Debug, Clone)]
pub struct FragInput {
    pub label: String,
    pub length: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Kf8Assembled {
    pub parts: Vec<Vec<u8>>,
    pub frag_locations: Vec<(i32, i32)>,
}

/// Parses an optional `FDST` flow table into byte ranges `(start, end)` within
/// a text buffer of `raw_text_len` bytes, enforcing `max_flow_aggregate_bytes`.
pub fn kf8_parse_fdst(
    fdst: Option<&[u8]>,
    raw_text_len: usize,
    max_flow_aggregate_bytes: i64,
) -> Result<Vec<(usize, usize)>, MobiError> {
    let Some(fdst) = fdst else {
        if (raw_text_len as i64) > max_flow_aggregate_bytes {
            return Err(MobiError::Limit(
                LimitKind::ActualAggregate,
                format!("KF8 flow exceeds {max_flow_aggregate_bytes} bytes"),
            ));
        }
        return Ok(vec![(0, raw_text_len)]);
    };
    if fdst.len() < 12 {
        return Err(MobiError::Io("Damaged KF8: FDST header".into()));
    }
    let Some(count) = index32_be(fdst, 8) else {
        return Ok(vec![(0, raw_text_len)]);
    };
    if !(1..=MAX_FDST_FLOWS).contains(&count)
        || 12usize
            .checked_add(count * 8)
            .is_none_or(|end| end > fdst.len())
    {
        return Ok(vec![(0, raw_text_len)]);
    }

    let mut ranges = Vec::with_capacity(count);
    let mut copied_bytes: i64 = 0;
    for i in 0..count {
        let start = index32_be(fdst, 12 + i * 8)
            .unwrap_or(0)
            .clamp(0, raw_text_len);
        let end = index32_be(fdst, 16 + i * 8)
            .unwrap_or(0)
            .clamp(start, raw_text_len);
        let length = (end - start) as i64;
        if copied_bytes > max_flow_aggregate_bytes - length {
            return Err(MobiError::Limit(
                LimitKind::ActualAggregate,
                format!("KF8 flows exceed {max_flow_aggregate_bytes} bytes"),
            ));
        }
        copied_bytes += length;
        ranges.push((start, end));
    }
    if ranges.is_empty() {
        Ok(vec![(0, raw_text_len)])
    } else {
        Ok(ranges)
    }
}

/// Reassembles KF8 XHTML parts from flow 0 (`flow0`) and the SKEL/FRAG index rows.
pub fn kf8_assemble(
    flow0: &[u8],
    skel: &[SkelInput],
    frag: &[FragInput],
    limits: &Kf8Limits,
) -> Result<Kf8Assembled, MobiError> {
    if skel.len() > limits.max_parts {
        return Err(MobiError::Limit(
            LimitKind::EntryCount,
            format!("KF8 has more than {} reassembled parts", limits.max_parts),
        ));
    }

    struct FragPiece {
        insert_pos: i32,
        start: usize,
        length: usize,
    }

    let mut parts = Vec::with_capacity(skel.len());
    let mut frag_locations = Vec::new();
    let mut frag_ptr = 0usize;
    let mut assembled_bytes: i64 = 0;
    let mut fragment_count = 0usize;

    for (part_index, entry) in skel.iter().enumerate() {
        let num_frags_long = entry.num_frags.unwrap_or(0);
        if !(0..=limits.max_fragments as i64).contains(&num_frags_long)
            || num_frags_long > (limits.max_fragments - fragment_count) as i64
        {
            return Err(MobiError::Limit(
                LimitKind::EntryCount,
                format!("KF8 has more than {} fragments", limits.max_fragments),
            ));
        }
        let num_frags = num_frags_long as usize;
        fragment_count += num_frags;

        let geometry = entry
            .geometry
            .as_deref()
            .ok_or_else(|| MobiError::Io("Damaged KF8: skeleton geometry".into()))?;
        let skel_pos_long = geometry.first().copied().unwrap_or(-1);
        let skel_len_long = geometry.get(1).copied().unwrap_or(-1);
        if skel_pos_long < 0
            || skel_len_long < 0
            || skel_pos_long > (flow0.len() as i64) - skel_len_long
        {
            return Err(MobiError::Io("Damaged KF8: skeleton range".into()));
        }
        let skel_pos = skel_pos_long as usize;
        let skel_len = skel_len_long as usize;
        let mut base_ptr = skel_pos_long + skel_len_long;

        let mut frags = Vec::with_capacity(num_frags);
        let mut total_frag_len: i64 = 0;
        for _ in 0..num_frags {
            let frag_entry = frag
                .get(frag_ptr)
                .ok_or_else(|| MobiError::Io("Damaged KF8: fragment table short".into()))?;
            frag_ptr += 1;

            let insert_pos = frag_entry
                .label
                .trim_matches(|c: char| c <= ' ')
                .parse::<i64>()
                .map_err(|_| MobiError::Io("Damaged KF8: fragment insert position".into()))?;
            let length_long = frag_entry
                .length
                .ok_or_else(|| MobiError::Io("Damaged KF8: fragment length".into()))?;
            if !(0..=i64::from(i32::MAX)).contains(&insert_pos)
                || length_long < 0
                || base_ptr > (flow0.len() as i64) - length_long
            {
                return Err(MobiError::Io("Damaged KF8: fragment range".into()));
            }
            if total_frag_len > limits.max_part_bytes - length_long {
                return Err(kf8_part_limit(limits.max_part_bytes));
            }
            let length = length_long as usize;
            frags.push(FragPiece {
                insert_pos: insert_pos as i32,
                start: base_ptr as usize,
                length,
            });
            base_ptr += length_long;
            total_frag_len += length_long;
        }

        let part_bytes = skel_len_long + total_frag_len;
        if part_bytes > limits.max_part_bytes
            || assembled_bytes > limits.max_assembled_bytes - part_bytes
        {
            return Err(kf8_part_limit(
                limits.max_part_bytes.min(limits.max_assembled_bytes),
            ));
        }
        assembled_bytes += part_bytes;

        let mut out = Vec::new();
        out.try_reserve_exact(part_bytes as usize)
            .map_err(|_| kf8_part_limit(limits.max_part_bytes))?;
        let mut skel_cursor = 0usize;
        let mut consumed: i64 = 0;
        for piece in &frags {
            let assembled_at = i64::from(piece.insert_pos) - (skel_pos as i64);
            let split_at = (assembled_at - consumed)
                .max(skel_cursor as i64)
                .min(skel_len as i64) as usize;
            out.extend_from_slice(&flow0[skel_pos + skel_cursor..skel_pos + split_at]);
            skel_cursor = split_at;
            frag_locations.push((part_index as i32, out.len() as i32));
            out.extend_from_slice(&flow0[piece.start..piece.start + piece.length]);
            consumed += piece.length as i64;
        }
        out.extend_from_slice(&flow0[skel_pos + skel_cursor..skel_pos + skel_len]);
        parts.push(out);
    }

    if parts.is_empty() {
        return Err(MobiError::Io("Damaged KF8: no parts".into()));
    }
    Ok(Kf8Assembled {
        parts,
        frag_locations,
    })
}

fn kf8_part_limit(max_bytes: i64) -> MobiError {
    MobiError::Limit(
        LimitKind::EntrySize,
        format!("KF8 reassembled content exceeds {max_bytes} bytes"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::ZlibEncoder, Compression};
    use std::io::Write as _;

    fn palmdoc_compress(text: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut i = 0;
        while i < text.len() {
            let mut best_len = 0;
            let mut best_dist = 0;
            let max_dist = i.min(2047);
            for dist in 1..=max_dist {
                let mut l = 0;
                while l < 10 && i + l < text.len() && text[i + l] == text[i - dist + l] {
                    l += 1;
                }
                if l > best_len {
                    best_len = l;
                    best_dist = dist;
                }
            }
            if best_len >= 3 {
                let pair = 0x8000 | (best_dist << 3) | (best_len - 3);
                out.push(((pair >> 8) & 0xFF) as u8);
                out.push((pair & 0xFF) as u8);
                i += best_len;
                continue;
            }
            let b = text[i];
            if b == b' ' && i + 1 < text.len() && (0x40..=0x7F).contains(&text[i + 1]) {
                out.push(text[i + 1] ^ 0x80);
                i += 2;
                continue;
            }
            if b == 0x00 || (0x09..=0x7F).contains(&b) {
                out.push(b);
                i += 1;
                continue;
            }
            let start = i;
            let mut run = 0;
            while i < text.len() && run < 8 {
                let c = text[i];
                if c == 0x00 || (0x09..=0x7F).contains(&c) {
                    break;
                }
                run += 1;
                i += 1;
            }
            out.push(run as u8);
            out.extend_from_slice(&text[start..start + run]);
        }
        out
    }

    fn backward_varint_bytes(value: usize) -> Vec<u8> {
        if value < 0x80 {
            vec![(0x80 | value) as u8]
        } else {
            vec![(0x80 | (value >> 7)) as u8, (value & 0x7F) as u8]
        }
    }

    fn forward_varint_bytes(value: usize) -> Vec<u8> {
        if value < 0x80 {
            vec![(value | 0x80) as u8]
        } else if value < 0x4000 {
            vec![((value >> 7) & 0x7F) as u8, ((value & 0x7F) | 0x80) as u8]
        } else {
            vec![
                ((value >> 14) & 0x7F) as u8,
                ((value >> 7) & 0x7F) as u8,
                ((value & 0x7F) | 0x80) as u8,
            ]
        }
    }

    #[test]
    fn palmdoc_round_trips_every_token_class() {
        let text = concat!(
            "Simple ASCII with spaces and words. ",
            "aaaaaaaaaaaaaaaaaaaaaa overlapping backref ",
            "Русский текст в UTF-8 идёт литеральными сериями. ",
            "repeat repeat repeat repeat done."
        )
        .as_bytes();
        let compressed = palmdoc_compress(text);
        let decoded = palmdoc_decompress(&compressed, text.len() + 16).unwrap();
        assert_eq!(decoded, text);
    }

    #[test]
    fn palmdoc_reports_exact_errors() {
        assert_eq!(
            palmdoc_decompress(&[0x03, 0x41], 16),
            Err(MobiError::Io("PalmDoc: truncated literal run".into()))
        );
        assert_eq!(
            palmdoc_decompress(&[0x80], 16),
            Err(MobiError::Io("PalmDoc: truncated back-reference".into()))
        );
        assert_eq!(
            palmdoc_decompress(&[0x80, 0x00], 16),
            Err(MobiError::Io("PalmDoc: bad back-reference".into()))
        );
        assert_eq!(
            palmdoc_decompress(&[0x41, 0x42, 0x43], 2),
            Err(MobiError::Io("PalmDoc: output overflow".into()))
        );
        assert_eq!(
            palmdoc_decompress(&[0xC1], 1),
            Err(MobiError::Io("PalmDoc: output overflow".into()))
        );
    }

    #[test]
    fn trailing_entries_trim_in_reverse_order() {
        let base = b"content!";
        assert_eq!(trailing_content_length(base, 0), 8);

        let mut one = base.to_vec();
        one.extend_from_slice(&[9, 9, 9]);
        one.extend_from_slice(&backward_varint_bytes(4));
        assert_eq!(trailing_content_length(&one, 0b10), 8);

        let mut two = base.to_vec();
        two.extend_from_slice(&[1]);
        two.extend_from_slice(&backward_varint_bytes(2));
        two.extend_from_slice(&[2, 2]);
        two.extend_from_slice(&backward_varint_bytes(3));
        assert_eq!(trailing_content_length(&two, 0b110), 8);

        let mut multi = base.to_vec();
        multi.extend_from_slice(b"xx");
        multi.push(2);
        assert_eq!(trailing_content_length(&multi, 0b1), 8);

        let corrupt = b"abc\xff";
        assert_eq!(trailing_content_length(corrupt, 0b10), 4);
    }

    fn build_test_huff() -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"HUFF");
        out.extend_from_slice(&24u32.to_be_bytes());
        out.extend_from_slice(&24u32.to_be_bytes());
        out.extend_from_slice(&((24 + 256 * 4) as u32).to_be_bytes());
        out.extend_from_slice(&0u32.to_be_bytes());
        out.extend_from_slice(&0u32.to_be_bytes());
        for _ in 0..256 {
            out.extend_from_slice(&0x282u32.to_be_bytes());
        }
        for len in 1..=32 {
            if len == 2 {
                out.extend_from_slice(&0u32.to_be_bytes());
                out.extend_from_slice(&2u32.to_be_bytes());
            } else {
                out.extend_from_slice(&0u32.to_be_bytes());
                out.extend_from_slice(&0u32.to_be_bytes());
            }
        }
        out
    }

    fn build_test_cdic(entries: &[(&[u8], bool)]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"CDIC");
        out.extend_from_slice(&16u32.to_be_bytes());
        out.extend_from_slice(&(entries.len() as u32).to_be_bytes());
        out.extend_from_slice(&2u32.to_be_bytes());
        let mut data_offset = 2 * entries.len();
        for (bytes, _) in entries {
            out.extend_from_slice(&(data_offset as u16).to_be_bytes());
            data_offset += 2 + bytes.len();
        }
        for (bytes, terminal) in entries {
            let word = (bytes.len() as u16) | if *terminal { 0x8000 } else { 0 };
            out.extend_from_slice(&word.to_be_bytes());
            out.extend_from_slice(bytes);
        }
        out
    }

    #[test]
    fn huff_cdic_decodes_recursive_phrases_and_limits() {
        let cdic = build_test_cdic(&[(b"ab", true), (b"cd", true), (&[0x55], false)]);
        let mut decoder = HuffCdic::new(&build_test_huff(), 1024, 1024).unwrap();
        decoder.add_cdic(&cdic).unwrap();
        let decoded = decoder.decompress(&[0x84], 64).unwrap();
        assert_eq!(decoded, b"abcdcdcdcdcdcdcdcdcd");

        // Overflow returns Io, decompress_bounded returns Limit(EntrySize)
        assert_eq!(
            decoder.decompress(&[0x84], 5),
            Err(MobiError::Io("Damaged MOBI: HUFF output overflow".into()))
        );
        assert!(matches!(
            decoder.decompress_bounded(&[0x84], 5),
            Err(MobiError::Limit(LimitKind::EntrySize, _))
        ));
    }

    #[test]
    fn huff_cdic_guards_cycles_and_dictionary_budget() {
        let cyclic = build_test_cdic(&[(b"ab", true), (b"cd", true), (&[0x00], false)]);
        let mut decoder = HuffCdic::new(&build_test_huff(), 1024, 1024).unwrap();
        decoder.add_cdic(&cyclic).unwrap();
        assert_eq!(
            decoder.decompress(&[0x00], 64),
            Err(MobiError::Io("Damaged MOBI: cyclic CDIC phrase".into()))
        );

        let mut limited =
            HuffCdic::new(&build_test_huff(), 1024, (cyclic.len() + 1) as i32).unwrap();
        limited.add_cdic(&cyclic).unwrap();
        assert!(matches!(
            limited.add_cdic(&cyclic),
            Err(MobiError::Limit(LimitKind::EntrySize, _))
        ));
    }

    #[test]
    fn font_record_round_trips_xor_and_zlib() {
        let mut sfnt = vec![0x00, 0x01, 0x00, 0x00];
        for i in 0..2000 {
            sfnt.push(((i * 7) & 0xFF) as u8);
        }
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&sfnt).unwrap();
        let mut payload = encoder.finish().unwrap();

        let xor_key = [0x5Au8, 0x33, 0x77, 0x0F, 0xEE];
        let limit = payload.len().min(FONT_XOR_SPAN);
        for (i, b) in payload[..limit].iter_mut().enumerate() {
            *b ^= xor_key[i % xor_key.len()];
        }

        let mut rec = Vec::new();
        rec.extend_from_slice(b"FONT");
        rec.extend_from_slice(&(sfnt.len() as u32).to_be_bytes());
        rec.extend_from_slice(&(FONT_FLAG_ZLIB | FONT_FLAG_OBFUSCATED).to_be_bytes());
        rec.extend_from_slice(&((24 + xor_key.len()) as u32).to_be_bytes());
        rec.extend_from_slice(&(xor_key.len() as u32).to_be_bytes());
        rec.extend_from_slice(&24u32.to_be_bytes());
        rec.extend_from_slice(&xor_key);
        rec.extend_from_slice(&payload);

        assert_eq!(
            decode_font_record(&rec, 32 * 1024 * 1024),
            Some(sfnt.clone())
        );
        assert_eq!(
            decode_font_record(&rec, sfnt.len() as i32),
            Some(sfnt.clone())
        );
        assert_eq!(decode_font_record(&rec, 512), None);

        // Truncated zlib fails cleanly
        let truncated = &rec[..rec.len() - 4];
        assert_eq!(decode_font_record(truncated, 32 * 1024 * 1024), None);
    }

    #[test]
    fn mobi_index_parses_tags_and_cncx_with_limits() {
        let mut cncx = Vec::new();
        let s1 = "Часть 1".as_bytes();
        cncx.extend_from_slice(&forward_varint_bytes(s1.len()));
        cncx.extend_from_slice(s1);

        let mut entry_data = Vec::new();
        let entry_offset = 192usize;
        entry_data.push(2);
        entry_data.extend_from_slice(b"e1");
        entry_data.push(0x21); // bit 0 (tag 1) + 2 in 0x30 (tag 6, 2 groups of 2 values)
        for v in [100usize, 11, 22, 3000, 44] {
            entry_data.extend_from_slice(&forward_varint_bytes(v));
        }
        let idxt_off = 192 + entry_data.len();
        let mut entry_rec = vec![0u8; 192];
        entry_rec[0..4].copy_from_slice(b"INDX");
        entry_rec[4..8].copy_from_slice(&192u32.to_be_bytes());
        entry_rec[20..24].copy_from_slice(&(idxt_off as u32).to_be_bytes());
        entry_rec[24..28].copy_from_slice(&1u32.to_be_bytes());
        entry_rec.extend_from_slice(&entry_data);
        entry_rec.extend_from_slice(b"IDXT");
        entry_rec.extend_from_slice(&(entry_offset as u16).to_be_bytes());

        let mut header = vec![0u8; 192];
        header[0..4].copy_from_slice(b"INDX");
        header[4..8].copy_from_slice(&192u32.to_be_bytes());
        header[24..28].copy_from_slice(&1u32.to_be_bytes());
        header[52..56].copy_from_slice(&1u32.to_be_bytes());
        header.extend_from_slice(b"TAGX");
        header.extend_from_slice(&24u32.to_be_bytes());
        header.extend_from_slice(&1u32.to_be_bytes());
        header.extend_from_slice(&[1, 1, 0x01, 0]);
        header.extend_from_slice(&[6, 2, 0x30, 0]);
        header.extend_from_slice(&[0, 0, 0, 1]);

        let limits = IndxLimits {
            max_tagx_entries: 16,
            max_entries: 16,
            max_values: 16,
            max_cncx_entries: 16,
            max_cncx_bytes: 1024,
        };
        let mut parser = IndxParser::begin(&header, limits.clone()).unwrap().unwrap();
        assert!(parser.add_entry_record(&entry_rec).unwrap());
        parser.add_cncx_record(&cncx).unwrap();
        let parsed = parser.finish();
        assert_eq!(parsed.entries.len(), 1);
        assert_eq!(parsed.entries[0].label, "e1");
        assert_eq!(parsed.entries[0].tags[0], (1, vec![100]));
        assert_eq!(parsed.entries[0].tags[1], (6, vec![11, 22, 3000, 44]));
        assert_eq!(parsed.cncx, vec![(0, "Часть 1".into())]);

        // Value limit enforced
        let mut tight_val = limits;
        tight_val.max_values = 2;
        let mut parser2 = IndxParser::begin(&header, tight_val).unwrap().unwrap();
        assert!(matches!(
            parser2.add_entry_record(&entry_rec),
            Err(MobiError::Limit(LimitKind::EntryCount, _))
        ));
    }

    #[test]
    fn kf8_assembles_parts_and_enforces_limits() {
        let flow0 = b"<html><body></body></html><p>Hello</p><p>World</p>";
        let skel = vec![SkelInput {
            num_frags: Some(2),
            geometry: Some(vec![0, 26]),
        }];
        let frag = vec![
            FragInput {
                label: "12".into(), // insert before </body> (at 12)
                length: Some(12),
            },
            FragInput {
                label: "24".into(), // insert after first fragment (12 + 12 = 24)
                length: Some(12),
            },
        ];
        let limits = Kf8Limits {
            max_parts: 10,
            max_fragments: 10,
            max_part_bytes: 1024,
            max_assembled_bytes: 1024,
        };
        let assembled = kf8_assemble(flow0, &skel, &frag, &limits).unwrap();
        assert_eq!(
            assembled.parts[0],
            b"<html><body><p>Hello</p><p>World</p></body></html>"
        );
        assert_eq!(assembled.frag_locations, vec![(0, 12), (0, 24)]);

        let tight = Kf8Limits {
            max_part_bytes: 30,
            ..limits
        };
        assert!(matches!(
            kf8_assemble(flow0, &skel, &frag, &tight),
            Err(MobiError::Limit(LimitKind::EntrySize, _))
        ));
    }

    fn build_pdb(type_creator: &[u8; 8], name: &[u8], records: &[&[u8]]) -> Vec<u8> {
        let mut out = vec![0u8; PDB_HEADER_SIZE];
        let copy_len = name.len().min(32);
        out[..copy_len].copy_from_slice(&name[..copy_len]);
        out[60..68].copy_from_slice(type_creator);
        out[76..78].copy_from_slice(&(records.len() as u16).to_be_bytes());
        let mut offset = PDB_HEADER_SIZE + 8 * records.len();
        for (i, rec) in records.iter().enumerate() {
            out.extend_from_slice(&(offset as u32).to_be_bytes());
            out.push(0);
            out.push(((i >> 16) & 0xFF) as u8);
            out.push(((i >> 8) & 0xFF) as u8);
            out.push((i & 0xFF) as u8);
            offset += rec.len();
        }
        for rec in records {
            out.extend_from_slice(rec);
        }
        out
    }

    #[test]
    fn pdb_header_and_offsets_validate_magic_limits_and_corruption() {
        let mobi = build_pdb(
            b"BOOKMOBI",
            b" \xd2\xe5\xf1\xf2 \0ignored",
            &[b"abc", b"de"],
        );
        let palm = build_pdb(b"TEXtREAd", b"Plain", &[b"x"]);
        assert!(is_pdb_book(&mobi[..68]));
        assert!(is_pdb_book(&palm[..68]));
        assert!(!is_pdb_book(b"PK\x03\x04"));
        let mut bogus = mobi[..68].to_vec();
        bogus[60..68].copy_from_slice(b"OTHERAPP");
        assert!(!is_pdb_book(&bogus));

        let head = parse_pdb_header(&mobi[..78], mobi.len() as i64, 100).unwrap();
        assert_eq!(head.name, "\u{d2}\u{e5}\u{f1}\u{f2}");
        assert_eq!(head.type_creator, "BOOKMOBI");
        assert_eq!(head.record_count, 2);

        let table_start = PDB_HEADER_SIZE;
        let table_end = table_start + 8 * head.record_count;
        let offsets = parse_pdb_offsets(
            &mobi[table_start..table_end],
            head.record_count,
            mobi.len() as i64,
        )
        .unwrap();
        assert_eq!(offsets, vec![94, 97, 99]);

        assert!(matches!(
            parse_pdb_header(&mobi[..78], mobi.len() as i64, 1),
            Err(MobiError::Limit(LimitKind::EntryCount, _))
        ));
        assert_eq!(
            parse_pdb_header(&mobi[..78], 80, 100),
            Err(MobiError::Io("Damaged MOBI: file too small".into()))
        );

        let mut bad_table = mobi[table_start..table_end].to_vec();
        bad_table[8..12].copy_from_slice(&1u32.to_be_bytes());
        assert_eq!(
            parse_pdb_offsets(&bad_table, 2, mobi.len() as i64),
            Err(MobiError::Io("Damaged MOBI: bad record table at 0".into()))
        );
        let mut past_eof = mobi[table_start..table_end].to_vec();
        past_eof[8..12].copy_from_slice(&(mobi.len() as u32 + 10).to_be_bytes());
        assert_eq!(
            parse_pdb_offsets(&past_eof, 2, mobi.len() as i64),
            Err(MobiError::Io("Damaged MOBI: bad record table at 1".into()))
        );
    }

    #[test]
    fn palmdoc_mobi_and_exth_headers_parse_fields_codepages_and_kf8() {
        let mut record0 = vec![0u8; 256];
        record0[0..2].copy_from_slice(&2u16.to_be_bytes()); // compression
        record0[4..8].copy_from_slice(&0xFFFF_FFFFu32.to_be_bytes()); // u32 max -> 0
        record0[8..10].copy_from_slice(&4u16.to_be_bytes()); // textRecordCount
        record0[10..12].copy_from_slice(&4096u16.to_be_bytes()); // recordSize
        record0[12..14].copy_from_slice(&2u16.to_be_bytes()); // encryption

        let palm = parse_palmdoc_header(&record0).unwrap();
        assert_eq!(palm.compression, 2);
        assert_eq!(palm.text_length, 0);
        assert_eq!(palm.text_record_count, 4);
        assert_eq!(palm.record_size, 4096);
        assert_eq!(palm.encryption_type, 2);
        assert!(parse_palmdoc_header(&record0[..15]).is_err());

        // Plain TEXtREAd without MOBI magic returns None
        assert_eq!(parse_mobi_header(&record0[..16]).unwrap(), None);

        let header_len = 0x108usize - 16;
        let full_name = b"\xcf\xf0\xe8\xe2\xe5\xf2\0tail"; // Windows-1251 "Привет"
        let mut exth = Vec::new();
        exth.extend_from_slice(b"EXTH");
        exth.extend_from_slice(&0u32.to_be_bytes());
        exth.extend_from_slice(&2u32.to_be_bytes());
        exth.extend_from_slice(&100u32.to_be_bytes());
        exth.extend_from_slice(&12u32.to_be_bytes());
        exth.extend_from_slice(b"auth");
        exth.extend_from_slice(&201u32.to_be_bytes());
        exth.extend_from_slice(&12u32.to_be_bytes());
        exth.extend_from_slice(&5u32.to_be_bytes());

        let mobi_end = 16 + header_len;
        let name_off = mobi_end + 2 + exth.len(); // +2 padding bytes before EXTH
        record0.resize(name_off + full_name.len(), 0);
        record0[16..20].copy_from_slice(b"MOBI");
        record0[20..24].copy_from_slice(&(header_len as u32).to_be_bytes());
        record0[24..28].copy_from_slice(&2u32.to_be_bytes());
        record0[28..32].copy_from_slice(&1251u32.to_be_bytes());
        record0[36..40].copy_from_slice(&8u32.to_be_bytes()); // fileVersion 8 (KF8)
        record0[16 + 0x44..16 + 0x48].copy_from_slice(&(name_off as u32).to_be_bytes());
        record0[16 + 0x48..16 + 0x4C].copy_from_slice(&(full_name.len() as u32).to_be_bytes());
        record0[16 + 0x5C..16 + 0x60].copy_from_slice(&7u32.to_be_bytes());
        record0[16 + 0xE2..16 + 0xE4].copy_from_slice(&3u16.to_be_bytes());
        record0[16 + 0xB0..16 + 0xB4].copy_from_slice(&11u32.to_be_bytes());
        record0[16 + 0xB4..16 + 0xB8].copy_from_slice(&2u32.to_be_bytes());
        record0[16 + 0xEC..16 + 0xF0].copy_from_slice(&12u32.to_be_bytes());
        record0[16 + 0xF0..16 + 0xF4].copy_from_slice(&13u32.to_be_bytes());
        record0[mobi_end + 2..mobi_end + 2 + exth.len()].copy_from_slice(&exth);
        record0[name_off..name_off + full_name.len()].copy_from_slice(full_name);

        let mobi = parse_mobi_header(&record0).unwrap().unwrap();
        assert_eq!(mobi.text_encoding, 1251);
        assert_eq!(mobi.charset_name, "windows-1251");
        assert_eq!(mobi.full_name.as_deref(), Some("Привет"));
        assert_eq!(mobi.first_image_index, 7);
        assert_eq!(mobi.extra_record_data_flags, 3);
        assert_eq!(mobi.fdst_record, 11);
        assert_eq!(mobi.fdst_count, 2);
        assert_eq!(mobi.skel_index, 12);
        assert_eq!(mobi.frag_index, 13);

        let parsed_exth = parse_exth(&record0, mobi.header_length);
        assert_eq!(parsed_exth.len(), 2);
        assert_eq!(parsed_exth[0].entry_type, 100);
        assert_eq!(parsed_exth[0].data, b"auth");
        assert_eq!(parsed_exth[1].entry_type, 201);
        assert_eq!(parsed_exth[1].data, 5u32.to_be_bytes());

        // Truncated second EXTH entry keeps the first entry
        let truncated_end = mobi_end + 2 + exth.len() - 2;
        assert_eq!(
            parse_exth(&record0[..truncated_end], mobi.header_length).len(),
            1
        );
    }
}
