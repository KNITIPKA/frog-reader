//! Native book search over FRB v2 chapter payloads and text-search normalization.
//!
//! Matches `ReaderSearch.kt` (`searchBook` / `snippetFor`) and `SearchTextNormalizer.kt`
//! with exact UTF-16 code-unit offsets (`charStart`) and word-aligned snippet windows.

use crate::frb;
use memchr::{memchr, memchr2, memchr3};
use std::path::Path;

pub const SEARCH_RESULT_LIMIT: usize = 300;
const SNIPPET_BEFORE_CHARS: usize = 36;
const SNIPPET_AFTER_CHARS: usize = 110;

const FRB_MODEL_MAX_DEPTH: usize = 256;
const FRB_MODEL_DICTIONARY_LIMIT: usize = 4096;
const FRB_MODEL_SHORT_STRING_BYTES: usize = 128;
const FOOTNOTE_TAG: &str = "footnote";

const RESULT_MAGIC: u32 = 0x5253_5246; // "FRSR"
const ERROR_MAGIC: u32 = 0x4552_5246; // "FRRE"

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchMatch {
    pub item_index: i32,
    pub chapter_index: i32,
    pub char_start: i32,
    pub before_u16: Vec<u16>,
    pub matched_u16: Vec<u16>,
    pub after_u16: Vec<u16>,
}

#[cfg(test)]
impl SearchMatch {
    pub fn before(&self) -> String {
        String::from_utf16_lossy(&self.before_u16)
    }

    pub fn matched(&self) -> String {
        String::from_utf16_lossy(&self.matched_u16)
    }

    pub fn after(&self) -> String {
        String::from_utf16_lossy(&self.after_u16)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchResults {
    pub matches: Vec<SearchMatch>,
    pub total_count: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrbSearchError {
    InvalidContainer,
    ChapterCorrupt { chapter_index: i32, reason: String },
    Overflow,
}

/// Encodes `SearchResults` into the `FRSR` wire format consumed by `RustSearchNative`.
pub fn encode_search_results(results: &SearchResults) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(12 + results.matches.len() * 64);
    out.extend_from_slice(&RESULT_MAGIC.to_le_bytes());
    out.extend_from_slice(&results.total_count.to_le_bytes());
    out.extend_from_slice(&i32::try_from(results.matches.len()).ok()?.to_le_bytes());
    for m in &results.matches {
        out.extend_from_slice(&m.item_index.to_le_bytes());
        out.extend_from_slice(&m.chapter_index.to_le_bytes());
        out.extend_from_slice(&m.char_start.to_le_bytes());
        write_utf16_field(&mut out, &m.before_u16)?;
        write_utf16_field(&mut out, &m.matched_u16)?;
        write_utf16_field(&mut out, &m.after_u16)?;
    }
    Some(out)
}

/// Encodes a chapter-level read/decode failure into the `FRRE` wire format.
pub fn encode_search_error(chapter_index: i32, message: &str) -> Vec<u8> {
    let msg_bytes = message.as_bytes();
    let mut out = Vec::with_capacity(12 + msg_bytes.len());
    out.extend_from_slice(&ERROR_MAGIC.to_le_bytes());
    out.extend_from_slice(&chapter_index.to_le_bytes());
    out.extend_from_slice(&(msg_bytes.len() as i32).to_le_bytes());
    out.extend_from_slice(msg_bytes);
    out
}

fn write_utf16_field(out: &mut Vec<u8>, units: &[u16]) -> Option<()> {
    out.extend_from_slice(&i32::try_from(units.len()).ok()?.to_le_bytes());
    for &u in units {
        out.extend_from_slice(&u.to_le_bytes());
    }
    Some(())
}

// --------------------------------------------------------------------------- Case Folding & Matcher

/// Matches Kotlin's `Char.equals(other, ignoreCase = true)` on JVM:
/// `Character.toLowerCase(Character.toUpperCase(ch))`.
#[inline]
pub fn fold_search_u16(c: u16) -> u16 {
    if c < 0x80 {
        return u16::from((c as u8).to_ascii_lowercase());
    }
    if (0x0400..=0x040F).contains(&c) {
        return c + 0x50;
    }
    if (0x0410..=0x042F).contains(&c) {
        return c + 0x20;
    }
    if (0x0430..=0x045F).contains(&c) || (0x0590..=0x06FF).contains(&c) {
        return c;
    }
    if (0xD800..=0xDFFF).contains(&c) {
        return c;
    }
    if c == 0x0130 {
        return 0x0069; // 'İ' -> 'i'
    }
    let Some(ch) = char::from_u32(u32::from(c)) else {
        return c;
    };
    let mut upper = ch.to_uppercase();
    if let (Some(u), None) = (upper.next(), upper.next()) {
        if u as u32 <= 0xFFFF {
            if u == '\u{0130}' {
                return 0x0069;
            }
            let mut lower = u.to_lowercase();
            if let (Some(l), None) = (lower.next(), lower.next()) {
                if l as u32 <= 0xFFFF {
                    return l as u16;
                }
            }
            return u as u16;
        }
    }
    // 1-to-many uppercase chars (e.g. 'ß', 'ᾈ', ligatures): Java's Character.toUpperCase keeps them
    // unchanged and Character.toLowerCase applies any 1-to-1 lowercase mapping.
    let mut lower = ch.to_lowercase();
    if let (Some(l), None) = (lower.next(), lower.next()) {
        if l as u32 <= 0xFFFF {
            return l as u16;
        }
    }
    c
}

#[inline]
fn utf16_len_of_utf8(bytes: &[u8]) -> usize {
    let mut count = 0usize;
    for &b in bytes {
        if (b & 0xC0) != 0x80 {
            count += 1;
            if b >= 0xF0 {
                count += 1;
            }
        }
    }
    count
}

enum FirstByteFilter {
    Single(u8),
    Double(u8, u8),
    Triple(u8, u8, u8),
}

pub struct SearchMatcher {
    query_folded: Vec<u16>,
    query_all_ascii_folded: bool,
    first_filter: Option<FirstByteFilter>,
    scratch_u16: Vec<u16>,
}

impl SearchMatcher {
    pub fn new(query: &str) -> Self {
        let query_folded: Vec<u16> = query.encode_utf16().map(fold_search_u16).collect();
        let query_all_ascii_folded = query_folded.iter().all(|&c| c < 128);
        let first_filter = if query_all_ascii_folded && !query_folded.is_empty() {
            let b0 = query_folded[0] as u8;
            Some(match b0 {
                b'i' => FirstByteFilter::Triple(b'i', b'I', 0xC4), // İ (C4 B0), ı (C4 B1)
                b's' => FirstByteFilter::Triple(b's', b'S', 0xC5), // ſ (C5 BF)
                b'k' => FirstByteFilter::Triple(b'k', b'K', 0xE2), // K (E2 84 AA)
                b'a'..=b'z' => FirstByteFilter::Double(b0, b0.to_ascii_uppercase()),
                _ => FirstByteFilter::Single(b0),
            })
        } else {
            None
        };
        Self {
            query_folded,
            query_all_ascii_folded,
            first_filter,
            scratch_u16: Vec::new(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.query_folded.is_empty()
    }

    #[inline]
    fn find_candidate(&self, haystack: &[u8]) -> Option<usize> {
        match self.first_filter.as_ref()? {
            FirstByteFilter::Single(a) => memchr(*a, haystack),
            FirstByteFilter::Double(a, b) => memchr2(*a, *b, haystack),
            FirstByteFilter::Triple(a, b, c) => memchr3(*a, *b, *c, haystack),
        }
    }

    pub fn search_element(
        &mut self,
        text: &str,
        chapter_index: i32,
        item_index: i32,
        limit: usize,
        matches: &mut Vec<SearchMatch>,
        total_count: &mut i32,
    ) -> Result<(), FrbSearchError> {
        let q_len = self.query_folded.len();
        if q_len == 0 || text.is_empty() {
            return Ok(());
        }

        if text.is_ascii() {
            if !self.query_all_ascii_folded {
                return Ok(());
            }
            return self.search_ascii(
                text.as_bytes(),
                chapter_index,
                item_index,
                limit,
                matches,
                total_count,
            );
        }

        if self.query_all_ascii_folded {
            return self.search_utf8_with_ascii_query(
                text,
                chapter_index,
                item_index,
                limit,
                matches,
                total_count,
            );
        }

        self.scratch_u16.clear();
        self.scratch_u16.extend(text.encode_utf16());
        let text_len = self.scratch_u16.len();
        if text_len < q_len {
            return Ok(());
        }
        let first = self.query_folded[0];
        let mut i = 0usize;
        while i + q_len <= text_len {
            if fold_search_u16(self.scratch_u16[i]) == first
                && (1..q_len)
                    .all(|j| fold_search_u16(self.scratch_u16[i + j]) == self.query_folded[j])
            {
                *total_count = total_count.checked_add(1).ok_or(FrbSearchError::Overflow)?;
                if matches.len() < limit {
                    matches.push(snippet_from_u16(
                        &self.scratch_u16,
                        chapter_index,
                        item_index,
                        i,
                        i + q_len,
                    ));
                }
                i += q_len;
            } else {
                i += 1;
            }
        }
        Ok(())
    }

    fn search_ascii(
        &self,
        bytes: &[u8],
        chapter_index: i32,
        item_index: i32,
        limit: usize,
        matches: &mut Vec<SearchMatch>,
        total_count: &mut i32,
    ) -> Result<(), FrbSearchError> {
        let q_len = self.query_folded.len();
        if bytes.len() < q_len {
            return Ok(());
        }
        let mut pos = 0usize;
        while pos + q_len <= bytes.len() {
            let Some(rel) = self.find_candidate(&bytes[pos..]) else {
                break;
            };
            let idx = pos + rel;
            if idx + q_len > bytes.len() {
                break;
            }
            if (0..q_len).all(|j| {
                usize::from(bytes[idx + j].to_ascii_lowercase())
                    == usize::from(self.query_folded[j])
            }) {
                *total_count = total_count.checked_add(1).ok_or(FrbSearchError::Overflow)?;
                if matches.len() < limit {
                    matches.push(snippet_from_ascii(
                        bytes,
                        chapter_index,
                        item_index,
                        idx,
                        idx + q_len,
                    ));
                }
                pos = idx + q_len;
            } else {
                pos = idx + 1;
            }
        }
        Ok(())
    }

    fn search_utf8_with_ascii_query(
        &mut self,
        text: &str,
        chapter_index: i32,
        item_index: i32,
        limit: usize,
        matches: &mut Vec<SearchMatch>,
        total_count: &mut i32,
    ) -> Result<(), FrbSearchError> {
        let bytes = text.as_bytes();
        let q_len = self.query_folded.len();
        let mut pos = 0usize;
        let mut last_byte = 0usize;
        let mut last_u16 = 0usize;
        let mut cached_u16 = false;

        while pos < bytes.len() {
            let Some(rel) = self.find_candidate(&bytes[pos..]) else {
                break;
            };
            let idx = pos + rel;
            // `idx` is guaranteed to be a valid char boundary (ASCII < 0x80 or UTF-8 lead >= 0xC2).
            let tail = &text[idx..];
            let mut chars = tail.chars();
            let mut matched = true;
            for &expected in &self.query_folded {
                let Some(ch) = chars.next() else {
                    matched = false;
                    break;
                };
                let cp = ch as u32;
                if cp > 0xFFFF || fold_search_u16(cp as u16) != expected {
                    matched = false;
                    break;
                }
            }
            if matched {
                last_u16 += utf16_len_of_utf8(&bytes[last_byte..idx]);
                let char_start = last_u16;
                let end_byte = bytes.len() - chars.as_str().len();
                *total_count = total_count.checked_add(1).ok_or(FrbSearchError::Overflow)?;
                if matches.len() < limit {
                    if !cached_u16 {
                        self.scratch_u16.clear();
                        self.scratch_u16.extend(text.encode_utf16());
                        cached_u16 = true;
                    }
                    matches.push(snippet_from_u16(
                        &self.scratch_u16,
                        chapter_index,
                        item_index,
                        char_start,
                        char_start + q_len,
                    ));
                }
                last_u16 += q_len;
                last_byte = end_byte;
                pos = end_byte;
            } else {
                pos = idx + 1;
            }
        }
        Ok(())
    }
}

fn snippet_from_ascii(
    bytes: &[u8],
    chapter_index: i32,
    item_index: i32,
    start: usize,
    end: usize,
) -> SearchMatch {
    let mut s = start.saturating_sub(SNIPPET_BEFORE_CHARS);
    if s > 0 {
        if let Some(rel) = memchr(b' ', &bytes[s..start]) {
            s += rel + 1;
        }
    }
    let mut e = (end + SNIPPET_AFTER_CHARS).min(bytes.len());
    if e < bytes.len() {
        // Matches Kotlin `text.lastIndexOf(' ', e)` inclusive at `e`.
        for pos in (end + 1..=e).rev() {
            if bytes[pos] == b' ' {
                e = pos;
                break;
            }
        }
    }

    let mut before_u16 = Vec::with_capacity((start - s) + usize::from(s > 0));
    if s > 0 {
        before_u16.push(0x2026); // '…'
    }
    for &b in &bytes[s..start] {
        before_u16.push(u16::from(if b == b'\n' { b' ' } else { b }));
    }

    let matched_u16: Vec<u16> = bytes[start..end].iter().map(|&b| u16::from(b)).collect();

    let mut after_u16 = Vec::with_capacity((e - end) + usize::from(e < bytes.len()));
    for &b in &bytes[end..e] {
        after_u16.push(u16::from(if b == b'\n' { b' ' } else { b }));
    }
    if e < bytes.len() {
        after_u16.push(0x2026);
    }

    SearchMatch {
        item_index,
        chapter_index,
        char_start: start as i32,
        before_u16,
        matched_u16,
        after_u16,
    }
}

fn snippet_from_u16(
    units: &[u16],
    chapter_index: i32,
    item_index: i32,
    start: usize,
    end: usize,
) -> SearchMatch {
    let mut s = start.saturating_sub(SNIPPET_BEFORE_CHARS);
    if s > 0 {
        if let Some(rel) = units[s..start].iter().position(|&u| u == 0x0020) {
            s += rel + 1;
        }
    }
    let mut e = (end + SNIPPET_AFTER_CHARS).min(units.len());
    if e < units.len() {
        for pos in (end + 1..=e).rev() {
            if units[pos] == 0x0020 {
                e = pos;
                break;
            }
        }
    }

    let mut before_u16 = Vec::with_capacity((start - s) + usize::from(s > 0));
    if s > 0 {
        before_u16.push(0x2026);
    }
    for &u in &units[s..start] {
        before_u16.push(if u == 0x000A { 0x0020 } else { u });
    }

    let matched_u16 = units[start..end].to_vec();

    let mut after_u16 = Vec::with_capacity((e - end) + usize::from(e < units.len()));
    for &u in &units[end..e] {
        after_u16.push(if u == 0x000A { 0x0020 } else { u });
    }
    if e < units.len() {
        after_u16.push(0x2026);
    }

    SearchMatch {
        item_index,
        chapter_index,
        char_start: start as i32,
        before_u16,
        matched_u16,
        after_u16,
    }
}

/// Searches a pre-extracted chapter text batch encoded by `RustSearchNative`:
/// `[u32 count]`, then `count` times `[i32 local_item_index, i32 utf8_len, utf8_bytes]`.
pub fn search_packed_chapter_texts(
    packed: &[u8],
    query: &str,
    chapter_index: i32,
    chapter_start_item: i32,
    limit: usize,
) -> Result<SearchResults, FrbSearchError> {
    if query.is_empty() {
        return Ok(SearchResults {
            matches: Vec::new(),
            total_count: 0,
        });
    }
    if packed.len() < 4 {
        return Err(FrbSearchError::InvalidContainer);
    }
    let count = u32::from_le_bytes(packed[0..4].try_into().unwrap()) as usize;
    if count > packed.len().saturating_sub(4) / 8 {
        return Err(FrbSearchError::InvalidContainer);
    }
    let mut pos = 4usize;
    let mut matcher = SearchMatcher::new(query);
    let mut matches = Vec::with_capacity(limit.min(64));
    let mut total_count = 0i32;

    for _ in 0..count {
        if pos + 8 > packed.len() {
            return Err(FrbSearchError::InvalidContainer);
        }
        let local_item = i32::from_le_bytes(packed[pos..pos + 4].try_into().unwrap());
        let len_i32 = i32::from_le_bytes(packed[pos + 4..pos + 8].try_into().unwrap());
        let Ok(len) = usize::try_from(len_i32) else {
            return Err(FrbSearchError::InvalidContainer);
        };
        pos += 8;
        let end = pos
            .checked_add(len)
            .ok_or(FrbSearchError::InvalidContainer)?;
        if end > packed.len() {
            return Err(FrbSearchError::InvalidContainer);
        }
        let text =
            std::str::from_utf8(&packed[pos..end]).map_err(|_| FrbSearchError::InvalidContainer)?;
        pos = end;
        let item_index = chapter_start_item
            .checked_add(local_item)
            .ok_or(FrbSearchError::Overflow)?;
        matcher.search_element(
            text,
            chapter_index,
            item_index,
            limit,
            &mut matches,
            &mut total_count,
        )?;
    }

    if pos != packed.len() {
        return Err(FrbSearchError::InvalidContainer);
    }

    Ok(SearchResults {
        matches,
        total_count,
    })
}

// --------------------------------------------------------------------------- FRB Revision-5 Chapter Search

#[derive(Clone, Copy)]
enum StyleTable {
    BlockStyle = 0,
    PublisherBoxStyle = 1,
    ParsedSpan = 2,
    ParsedParagraph = 3,
    ParsedLinkStyles = 4,
}

struct FrbChapterDecoder<'a> {
    bytes: &'a [u8],
    pos: usize,
    depth: usize,
    strings: Vec<&'a str>,
    style_counts: [usize; 5],
}

impl<'a> FrbChapterDecoder<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self {
            bytes,
            pos: 0,
            depth: 0,
            strings: Vec::new(),
            style_counts: [0; 5],
        }
    }

    fn err(&self, msg: &str) -> String {
        format!("{msg} at byte {}", self.pos)
    }

    fn read_byte(&mut self) -> Result<u8, String> {
        let b = *self
            .bytes
            .get(self.pos)
            .ok_or_else(|| self.err("Truncated model block"))?;
        self.pos += 1;
        Ok(b)
    }

    fn read_var_bits(&mut self) -> Result<u64, String> {
        let mut result = 0u64;
        for shift in (0..=63).step_by(7) {
            let byte = self.read_byte()?;
            if (shift == 63 && byte > 1) || (shift != 0 && byte == 0) {
                return Err(self.err("Invalid variable-width integer"));
            }
            result |= u64::from(byte & 0x7F) << shift;
            if byte < 0x80 {
                return Ok(result);
            }
        }
        Err(self.err("Variable-width integer overflow"))
    }

    fn read_size(&mut self) -> Result<usize, String> {
        let value = self.read_var_bits()?;
        if value > i32::MAX as u64 {
            return Err(self.err("Unsigned integer out of range"));
        }
        Ok(value as usize)
    }

    fn read_bool(&mut self) -> Result<bool, String> {
        match self.read_byte()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(self.err("Invalid boolean")),
        }
    }

    fn read_i32(&mut self) -> Result<i32, String> {
        let value = self.read_var_bits()?;
        if (value >> 32) != 0 {
            return Err(self.err("Int out of range"));
        }
        Ok(((value >> 1) as i64 ^ -((value & 1) as i64)) as i32)
    }

    fn skip_i64(&mut self) -> Result<(), String> {
        let _ = self.read_var_bits()?;
        Ok(())
    }

    fn skip_fixed(&mut self, len: usize) -> Result<(), String> {
        if len > self.bytes.len() - self.pos {
            return Err(self.err("Truncated fixed-width value"));
        }
        self.pos += len;
        Ok(())
    }

    fn skip_enum(&mut self, variants: usize) -> Result<(), String> {
        let v = self.read_size()?;
        if v >= variants {
            return Err(self.err("Enum ordinal out of range"));
        }
        Ok(())
    }

    fn read_string(&mut self) -> Result<&'a str, String> {
        let token = self.read_size()?;
        let size = token >> 1;
        if token & 1 != 0 {
            return self
                .strings
                .get(size)
                .copied()
                .ok_or_else(|| self.err("Unknown string reference"));
        }
        if size > self.bytes.len() - self.pos {
            return Err(self.err("String exceeds block bounds"));
        }
        let end = self.pos + size;
        let text = std::str::from_utf8(&self.bytes[self.pos..end])
            .map_err(|_| self.err("Malformed UTF-8 string"))?;
        self.pos = end;
        if (1..=FRB_MODEL_SHORT_STRING_BYTES).contains(&size)
            && self.strings.len() < FRB_MODEL_DICTIONARY_LIMIT
        {
            self.strings.push(text);
        }
        Ok(text)
    }

    fn enter(&mut self) -> Result<(), String> {
        if self.depth >= FRB_MODEL_MAX_DEPTH {
            return Err(self.err("Model nesting limit exceeded"));
        }
        self.depth += 1;
        Ok(())
    }

    fn leave(&mut self) {
        self.depth -= 1;
    }

    fn read_record<F>(&mut self, max_fields: usize, mut on_field: F) -> Result<(), String>
    where
        F: FnMut(&mut Self, usize) -> Result<(), String>,
    {
        self.enter()?;
        let mut prev_tag = 0usize;
        let res = (|| {
            loop {
                let tag = self.read_size()?;
                if tag == 0 {
                    break;
                }
                if tag <= prev_tag || tag > max_fields {
                    return Err(self.err("Invalid record field"));
                }
                prev_tag = tag;
                on_field(self, tag)?;
            }
            Ok(())
        })();
        self.leave();
        res
    }

    fn read_list<F>(&mut self, min_elem_bytes: usize, mut on_elem: F) -> Result<(), String>
    where
        F: FnMut(&mut Self, usize) -> Result<(), String>,
    {
        self.enter()?;
        let res = (|| {
            let count = self.read_size()?;
            if min_elem_bytes > 0 && count > (self.bytes.len() - self.pos) / min_elem_bytes {
                return Err(self.err("Collection exceeds block bounds"));
            }
            for i in 0..count {
                on_elem(self, i)?;
            }
            Ok(())
        })();
        self.leave();
        res
    }

    fn read_nullable<F>(&mut self, on_some: F) -> Result<(), String>
    where
        F: FnOnce(&mut Self) -> Result<(), String>,
    {
        if self.read_bool()? {
            on_some(self)
        } else {
            Ok(())
        }
    }

    fn read_interned<F>(&mut self, table: StyleTable, skip_record: F) -> Result<(), String>
    where
        F: FnOnce(&mut Self) -> Result<(), String>,
    {
        let reference = self.read_size()?;
        let idx = table as usize;
        if reference == 0 {
            skip_record(self)?;
            if self.style_counts[idx] < FRB_MODEL_DICTIONARY_LIMIT {
                self.style_counts[idx] += 1;
            }
            Ok(())
        } else if reference <= self.style_counts[idx] {
            Ok(())
        } else {
            Err(self.err("Unknown style reference"))
        }
    }

    fn skip_parsed_unit(&mut self) -> Result<(), String> {
        self.read_record(2, |r, tag| match tag {
            1 => r.read_i32().map(|_| ()),
            2 => r.read_i32().map(|_| ()),
            _ => unreachable!(),
        })
    }

    fn skip_parsed_shadow(&mut self) -> Result<(), String> {
        self.read_record(4, |r, tag| match tag {
            1 => r.skip_i64(),
            2..=4 => r.skip_fixed(4),
            _ => unreachable!(),
        })
    }

    fn skip_parsed_span(&mut self) -> Result<(), String> {
        self.read_interned(StyleTable::ParsedSpan, |r| {
            r.read_record(15, |r, tag| match tag {
                1 | 13 => r.skip_i64(),
                2 | 8 => r.skip_parsed_unit(),
                3 | 14 => r.read_nullable(|r| r.read_i32().map(|_| ())),
                4 => r.read_nullable(|r| r.read_bool().map(|_| ())),
                5..=7 => r.read_nullable(|r| r.read_string().map(|_| ())),
                9..=11 => r.read_nullable(|r| r.skip_fixed(4)),
                12 => r.read_nullable(|r| r.read_list(1, |r, _| r.read_string().map(|_| ()))),
                15 => r.read_nullable(|r| r.skip_parsed_shadow()),
                _ => unreachable!(),
            })
        })
    }

    fn skip_parsed_paragraph(&mut self) -> Result<(), String> {
        self.read_interned(StyleTable::ParsedParagraph, |r| {
            r.read_record(5, |r, tag| match tag {
                1 | 2 => r.read_i32().map(|_| ()),
                3 => r.skip_parsed_unit(),
                4 | 5 => r.read_nullable(|r| r.skip_parsed_unit()),
                _ => unreachable!(),
            })
        })
    }

    fn skip_parsed_link_styles(&mut self) -> Result<(), String> {
        self.read_interned(StyleTable::ParsedLinkStyles, |r| {
            r.read_record(4, |r, tag| match tag {
                1..=4 => r.read_nullable(|r| r.skip_parsed_span()),
                _ => unreachable!(),
            })
        })
    }

    fn skip_font_typography(&mut self) -> Result<(), String> {
        self.read_record(3, |r, tag| match tag {
            1 => r.read_nullable(|r| {
                r.enter()?;
                let res = (|| {
                    let count = r.read_size()?;
                    if count > (r.bytes.len() - r.pos) / 2 {
                        return Err(r.err("Map exceeds block bounds"));
                    }
                    for _ in 0..count {
                        let _ = r.read_string()?;
                        r.skip_fixed(4)?;
                    }
                    Ok(())
                })();
                r.leave();
                res
            }),
            2 => r.read_nullable(|r| r.skip_fixed(4)),
            3 => r.read_nullable(|r| r.read_bool().map(|_| ())),
            _ => unreachable!(),
        })
    }

    fn skip_first_letter_style(&mut self) -> Result<(), String> {
        self.read_record(14, |r, tag| match tag {
            1 => r.skip_fixed(4),
            2 | 3 => r.read_nullable(|r| r.read_bool().map(|_| ())),
            4 | 8 => r.read_bool().map(|_| ()),
            5 | 10 | 14 => r.read_nullable(|r| r.read_string().map(|_| ())),
            6 | 7 | 11 | 12 => r.read_nullable(|r| r.read_i32().map(|_| ())),
            9 => r.read_nullable(|r| r.skip_enum(3)),
            13 => r.read_nullable(|r| r.skip_font_typography()),
            _ => unreachable!(),
        })
    }

    fn skip_float_image(&mut self) -> Result<(), String> {
        self.read_record(4, |r, tag| match tag {
            1 => r.read_string().map(|_| ()),
            2 => r.skip_fixed(4),
            3 => r.read_bool().map(|_| ()),
            4 => r.read_nullable(|r| r.read_string().map(|_| ())),
            _ => unreachable!(),
        })
    }

    fn skip_block_style(&mut self) -> Result<(), String> {
        self.read_interned(StyleTable::BlockStyle, |r| {
            r.read_record(32, |r, tag| match tag {
                1 => r.read_nullable(|r| r.skip_enum(6)),
                2 | 3 | 13 | 22 => r.read_nullable(|r| r.read_bool().map(|_| ())),
                4 | 14 | 21 => r.read_nullable(|r| r.skip_fixed(4)),
                5..=12 | 16 | 17 => r.skip_fixed(4),
                15 | 18 | 19 | 23 => r.read_bool().map(|_| ()),
                20 | 26 | 30 => r.read_nullable(|r| r.read_string().map(|_| ())),
                24 => r.read_nullable(|r| r.skip_first_letter_style()),
                25 => r.read_nullable(|r| r.skip_float_image()),
                27 => r.read_nullable(|r| r.skip_enum(3)),
                28 | 29 | 31 => r.read_nullable(|r| r.read_i32().map(|_| ())),
                32 => r.read_nullable(|r| r.skip_font_typography()),
                _ => unreachable!(),
            })
        })
    }

    fn skip_border_side(&mut self) -> Result<(), String> {
        self.read_record(4, |r, tag| match tag {
            1 => r.skip_fixed(4),
            2 => r.read_i32().map(|_| ()),
            3 => r.skip_enum(5),
            4 => r.read_bool().map(|_| ()),
            _ => unreachable!(),
        })
    }

    fn skip_publisher_box_style(&mut self) -> Result<(), String> {
        self.read_interned(StyleTable::PublisherBoxStyle, |r| {
            r.read_record(33, |r, tag| match tag {
                1..=12 | 21 | 29 => r.skip_fixed(4),
                13 => r.read_nullable(|r| r.read_i32().map(|_| ())),
                14..=17 => r.read_nullable(|r| r.skip_border_side()),
                18 | 19 | 27 | 28 | 32 | 33 => r.read_nullable(|r| r.skip_fixed(4)),
                20 | 22 | 24 | 25 | 26 | 30 | 31 => r.read_bool().map(|_| ()),
                23 => r.skip_enum(3),
                _ => unreachable!(),
            })
        })
    }

    fn skip_publisher_box_span(&mut self) -> Result<(), String> {
        self.read_record(9, |r, tag| match tag {
            1 => r.read_string().map(|_| ()),
            2 => r.read_nullable(|r| r.read_string().map(|_| ())),
            3 | 4 => r.read_i32().map(|_| ()),
            5 => r.skip_publisher_box_style(),
            6 => r.read_nullable(|r| r.skip_enum(2)),
            7 => r.skip_enum(4),
            8 | 9 => r.read_bool().map(|_| ()),
            _ => unreachable!(),
        })
    }

    fn skip_table_cell_style(&mut self) -> Result<(), String> {
        self.read_record(5, |r, tag| match tag {
            1 => r.read_nullable(|r| r.skip_block_style()),
            2 => r.read_nullable(|r| r.skip_publisher_box_style()),
            3 => r.read_nullable(|r| r.skip_enum(6)),
            4 | 5 => r.read_bool().map(|_| ()),
            _ => unreachable!(),
        })
    }

    fn read_parsed_text(
        &mut self,
        hide_footnotes: bool,
        out_text: &mut String,
    ) -> Result<(), String> {
        out_text.clear();
        let mut raw_text: &'a str = "";
        let mut footnote_ranges: Vec<(usize, usize)> = Vec::new();
        let mut utf16_len_cache: Option<usize> = None;

        self.read_record(2, |r, tag| match tag {
            1 => {
                raw_text = r.read_string()?;
                Ok(())
            }
            2 => r.read_list(1, |r, _| {
                let text_u16_len =
                    *utf16_len_cache.get_or_insert_with(|| raw_text.encode_utf16().count());
                let mut r_type: &'a str = "";
                let mut r_start = 0i32;
                let mut r_end = 0i32;
                let mut r_tag: &'a str = "";
                r.read_record(8, |r, f| match f {
                    1 => {
                        r_type = r.read_string()?;
                        Ok(())
                    }
                    2 => {
                        r_start = r.read_i32()?;
                        Ok(())
                    }
                    3 => {
                        r_end = r.read_i32()?;
                        Ok(())
                    }
                    4 => {
                        r_tag = r.read_string()?;
                        Ok(())
                    }
                    5 => r.read_nullable(|r| r.read_string().map(|_| ())),
                    6 => r.read_nullable(|r| r.skip_parsed_span()),
                    7 => r.read_nullable(|r| r.skip_parsed_paragraph()),
                    8 => r.read_nullable(|r| r.skip_parsed_link_styles()),
                    _ => unreachable!(),
                })?;
                if r_start < 0 || r_end < r_start || (r_end as usize) > text_u16_len {
                    return Err(r.err("Invalid annotation range"));
                }
                if hide_footnotes && r_type == "string" && r_tag == FOOTNOTE_TAG && r_end > r_start
                {
                    footnote_ranges.push((r_start as usize, r_end as usize));
                }
                Ok(())
            }),
            _ => unreachable!(),
        })?;

        if footnote_ranges.is_empty() {
            out_text.push_str(raw_text);
        } else {
            strip_utf16_ranges(raw_text, &mut footnote_ranges, out_text);
        }
        Ok(())
    }

    fn read_table_flat_text(
        &mut self,
        hide_footnotes: bool,
        table_buf: &mut String,
        cell_buf: &mut String,
    ) -> Result<(), String> {
        table_buf.clear();
        self.read_record(6, |r, tag| match tag {
            1 => r.read_list(1, |r, row_idx| {
                if row_idx > 0 {
                    table_buf.push('\n');
                }
                r.read_record(2, |r, f| match f {
                    1 => r.read_list(1, |r, cell_idx| {
                        if cell_idx > 0 {
                            table_buf.push_str("  ");
                        }
                        r.read_record(10, |r, cf| match cf {
                            1 => {
                                r.read_parsed_text(hide_footnotes, cell_buf)?;
                                table_buf.push_str(cell_buf);
                                Ok(())
                            }
                            2 | 3 => r.read_i32().map(|_| ()),
                            4 => r.read_nullable(|r| r.skip_enum(6)),
                            5 | 8 | 9 => r.read_bool().map(|_| ()),
                            6 => r.read_nullable(|r| r.skip_block_style()),
                            7 => r.read_nullable(|r| r.skip_publisher_box_style()),
                            10 => r.read_nullable(|r| r.skip_table_cell_style()),
                            _ => unreachable!(),
                        })
                    }),
                    2 => r.read_bool().map(|_| ()),
                    _ => unreachable!(),
                })
            }),
            2 => r.read_nullable(|r| r.skip_block_style()),
            3 => r.read_nullable(|r| r.skip_enum(3)),
            4 => r.read_bool().map(|_| ()),
            5 => r.read_nullable(|r| r.skip_fixed(4)),
            6 => r.read_nullable(|r| r.skip_publisher_box_style()),
            _ => unreachable!(),
        })
    }

    fn skip_image(&mut self) -> Result<(), String> {
        self.read_record(14, |r, tag| match tag {
            1 => r.read_string().map(|_| ()),
            2..=4 | 8..=11 => r.read_nullable(|r| r.skip_fixed(4)),
            5 => r.read_nullable(|r| r.read_string().map(|_| ())),
            6 | 7 | 12 | 14 => r.read_bool().map(|_| ()),
            13 => r.read_nullable(|r| r.read_i32().map(|_| ())),
            _ => unreachable!(),
        })
    }
}

fn strip_utf16_ranges(raw: &str, ranges: &mut [(usize, usize)], out: &mut String) {
    ranges.sort_by_key(|r| r.0);
    // Fast path: map UTF-16 positions directly to UTF-8 byte offsets.
    // All valid XML/HTML/FRB ranges align to UTF-16 code point boundaries.
    let mut u16_pos = 0usize;
    let mut range_idx = 0usize;
    let mut skip_until_u16 = 0usize;
    for ch in raw.chars() {
        let ch_u16 = ch.len_utf16();
        while range_idx < ranges.len() && ranges[range_idx].0 <= u16_pos {
            skip_until_u16 = skip_until_u16.max(ranges[range_idx].1);
            range_idx += 1;
        }
        if u16_pos >= skip_until_u16 {
            out.push(ch);
        }
        u16_pos += ch_u16;
    }
}

/// Decodes and searches a single FRB revision-5 `Chapter` binary block.
pub fn search_frb_chapter_payload(
    block_bytes: &[u8],
    chapter_index: i32,
    start_item: i32,
    expected_elements: Option<usize>,
    query: &str,
    hide_footnotes: bool,
    limit: usize,
) -> Result<SearchResults, FrbSearchError> {
    let mut matcher = SearchMatcher::new(query);
    let mut matches = Vec::with_capacity(limit.min(64));
    let mut total_count = 0i32;

    search_frb_chapter_into(
        block_bytes,
        chapter_index,
        start_item,
        expected_elements,
        hide_footnotes,
        limit,
        &mut matcher,
        &mut matches,
        &mut total_count,
    )?;

    Ok(SearchResults {
        matches,
        total_count,
    })
}

#[allow(clippy::too_many_arguments)]
fn search_frb_chapter_into(
    block_bytes: &[u8],
    chapter_index: i32,
    start_item: i32,
    expected_elements: Option<usize>,
    hide_footnotes: bool,
    limit: usize,
    matcher: &mut SearchMatcher,
    matches: &mut Vec<SearchMatch>,
    total_count: &mut i32,
) -> Result<(), FrbSearchError> {
    let mut dec = FrbChapterDecoder::new(block_bytes);
    let mut actual_elements = 0usize;
    let mut elem_text = String::new();
    let mut cell_text = String::new();
    let mut search_err: Option<FrbSearchError> = None;

    let res = dec.read_record(4, |r, tag| match tag {
        1 => r.read_nullable(|r| r.read_string().map(|_| ())),
        2 => r.read_list(1, |r, elem_idx| {
            actual_elements += 1;
            let mut variant: Option<&str> = None;
            let mut has_value = false;
            r.read_record(2, |r, pf| match pf {
                1 => {
                    variant = Some(r.read_string()?);
                    Ok(())
                }
                2 => {
                    let type_name =
                        variant.ok_or_else(|| r.err("Polymorphic value before type"))?;
                    has_value = true;
                    let item_index = start_item
                        .checked_add(elem_idx as i32)
                        .ok_or_else(|| "Element index overflow".to_string())?;
                    let should_search = match type_name {
                        "com.example.frogreader.data.model.ContentElement.Paragraph" => {
                            elem_text.clear();
                            r.read_record(3, |r, f| match f {
                                1 => r.read_parsed_text(hide_footnotes, &mut elem_text),
                                2 => r.skip_enum(3),
                                3 => r.read_nullable(|r| r.skip_block_style()),
                                _ => unreachable!(),
                            })?;
                            true
                        }
                        "com.example.frogreader.data.model.ContentElement.Heading" => {
                            elem_text.clear();
                            r.read_record(3, |r, f| match f {
                                1 => r.read_parsed_text(hide_footnotes, &mut elem_text),
                                2 => r.read_i32().map(|_| ()),
                                3 => r.read_nullable(|r| r.skip_block_style()),
                                _ => unreachable!(),
                            })?;
                            true
                        }
                        "com.example.frogreader.data.model.ContentElement.Table" => {
                            r.read_table_flat_text(hide_footnotes, &mut elem_text, &mut cell_text)?;
                            true
                        }
                        "com.example.frogreader.data.model.ContentElement.Image" => {
                            r.skip_image()?;
                            false
                        }
                        "com.example.frogreader.data.model.ContentElement.Divider" => {
                            r.read_record(0, |_, _| unreachable!())?;
                            false
                        }
                        "com.example.frogreader.data.model.ContentElement.Spacer" => {
                            r.read_record(1, |r, _| r.skip_fixed(4))?;
                            false
                        }
                        other => return Err(r.err(&format!("Unsupported ContentElement: {other}"))),
                    };
                    if should_search && !matcher.is_empty() {
                        if let Err(e) = matcher.search_element(
                            &elem_text,
                            chapter_index,
                            item_index,
                            limit,
                            matches,
                            total_count,
                        ) {
                            search_err = Some(e);
                            return Err("Search overflow".into());
                        }
                    }
                    Ok(())
                }
                _ => unreachable!(),
            })?;
            if variant.is_none() || !has_value {
                return Err(r.err("Incomplete polymorphic ContentElement"));
            }
            Ok(())
        }),
        3 => r.read_i32().map(|_| ()),
        4 => r.read_list(1, |r, _| r.skip_publisher_box_span()),
        _ => unreachable!(),
    });

    if let Some(e) = search_err {
        return Err(e);
    }
    res.map_err(|reason| FrbSearchError::ChapterCorrupt {
        chapter_index,
        reason,
    })?;

    if dec.pos != dec.bytes.len() || dec.depth != 0 {
        return Err(FrbSearchError::ChapterCorrupt {
            chapter_index,
            reason: "Trailing or unfinished chapter data".into(),
        });
    }
    if let Some(expected) = expected_elements {
        if actual_elements != expected {
            return Err(FrbSearchError::ChapterCorrupt {
                chapter_index,
                reason: "Chapter element count mismatch".into(),
            });
        }
    }
    Ok(())
}

/// Searches chapters `[start_chapter, start_chapter + chapter_count)` directly from an
/// already-open `frb::Reader`, pre-normalizing the query once for the entire range and
/// avoiding any per-chapter JVM buffer copies.
pub(crate) fn search_frb_reader_range(
    reader: &mut frb::Reader,
    start_chapter: usize,
    chapter_count: usize,
    cover_count: i32,
    query: &str,
    hide_footnotes: bool,
    limit: usize,
) -> Result<SearchResults, FrbSearchError> {
    if !(0..=1).contains(&cover_count) {
        return Err(FrbSearchError::InvalidContainer);
    }
    if query.is_empty() || chapter_count == 0 {
        return Ok(SearchResults {
            matches: Vec::new(),
            total_count: 0,
        });
    }
    let index = reader.index();
    let total_chapters = index.len() / 5;
    let end_chapter = start_chapter
        .saturating_add(chapter_count)
        .min(total_chapters);
    if start_chapter >= end_chapter {
        return Ok(SearchResults {
            matches: Vec::new(),
            total_count: 0,
        });
    }

    let capped_limit = limit.min(SEARCH_RESULT_LIMIT);
    let mut matcher = SearchMatcher::new(query);
    let mut matches = Vec::with_capacity(capped_limit.min(64));
    let mut total_count = 0i32;

    for ch in start_chapter..end_chapter {
        let base = ch * 5;
        let expected_elements =
            usize::try_from(index[base + 3]).map_err(|_| FrbSearchError::InvalidContainer)?;
        let start_element =
            i32::try_from(index[base + 4]).map_err(|_| FrbSearchError::InvalidContainer)?;
        let start_item = start_element
            .checked_add(cover_count)
            .ok_or(FrbSearchError::Overflow)?;
        let block =
            reader
                .read_block_bytes(ch + 2)
                .map_err(|e| FrbSearchError::ChapterCorrupt {
                    chapter_index: ch as i32,
                    reason: e.to_string(),
                })?;
        search_frb_chapter_into(
            &block,
            ch as i32,
            start_item,
            Some(expected_elements),
            hide_footnotes,
            capped_limit,
            &mut matcher,
            &mut matches,
            &mut total_count,
        )?;
    }

    Ok(SearchResults {
        matches,
        total_count,
    })
}

/// Opens an `.frb` v2 container directly via `frb::Reader` and searches all chapters
/// in Rust without decoding Block 1 globals or materializing `Chapter` models in JVM.
pub fn search_frb_file(
    path: &Path,
    metadata: &[u8],
    query: &str,
    cover_count: i32,
    hide_footnotes: bool,
    limit: usize,
) -> Result<SearchResults, FrbSearchError> {
    if !(0..=1).contains(&cover_count) {
        return Err(FrbSearchError::InvalidContainer);
    }
    let mut reader =
        frb::Reader::open(path, metadata).map_err(|_| FrbSearchError::InvalidContainer)?;
    let chapter_count = reader.index().len() / 5;
    search_frb_reader_range(
        &mut reader,
        0,
        chapter_count,
        cover_count,
        query,
        hide_footnotes,
        limit,
    )
}

// --------------------------------------------------------------------------- SearchTextNormalizer

pub const CANONICAL_APOSTROPHE: char = '\u{02BC}';

fn is_ignorable_format_or_mark(cp: u32) -> bool {
    cp == 0x00AD
        || cp == 0x0640
        || (0x200B..=0x200F).contains(&cp)
        || (0x202A..=0x202E).contains(&cp)
        || (0x2066..=0x2069).contains(&cp)
        || cp == 0xFEFF
        || (0x0610..=0x061A).contains(&cp)
        || (0x064B..=0x065F).contains(&cp)
        || cp == 0x0670
        || (0x06D6..=0x06ED).contains(&cp)
        || (0x0591..=0x05BD).contains(&cp)
        || cp == 0x05BF
        || (0x05C1..=0x05C2).contains(&cp)
        || (0x05C4..=0x05C5).contains(&cp)
        || cp == 0x05C7
        || cp == 0x0300
        || cp == 0x0301
}

fn is_java_private_use(cp: u32) -> bool {
    (0xE000..=0xF8FF).contains(&cp)
        || (0xF0000..=0xFFFFD).contains(&cp)
        || (0x10_0000..=0x10_FFFD).contains(&cp)
}

fn is_java_whitespace(ch: char) -> bool {
    let cp = ch as u32;
    if matches!(cp, 0x00A0 | 0x2007 | 0x202F) {
        return false;
    }
    ch.is_whitespace() || (0x001C..=0x001F).contains(&cp)
}

fn is_combining_mark(cp: u32) -> bool {
    (0x0300..=0x036F).contains(&cp)
        || (0x0483..=0x0489).contains(&cp)
        || (0x0591..=0x05BD).contains(&cp)
        || (0x0610..=0x061A).contains(&cp)
        || (0x064B..=0x065F).contains(&cp)
        || cp == 0x0670
        || (0x06D6..=0x06ED).contains(&cp)
        || (0x1AB0..=0x1AFF).contains(&cp)
        || (0x1DC0..=0x1DFF).contains(&cp)
        || (0x20D0..=0x20FF).contains(&cp)
        || (0xFE20..=0xFE2F).contains(&cp)
}

fn is_word_internal_connector_candidate(cp: u32) -> bool {
    matches!(
        cp,
        0x0027 | 0x2019 | 0x2018 | 0x02BC | 0x2032 | 0x05F3 | 0x05F4 | 0x0022
    )
}

fn is_java_letter(ch: char) -> bool {
    let cp = ch as u32;
    if is_java_private_use(cp) || is_combining_mark(cp) {
        return false;
    }
    ch.is_alphabetic()
}

fn has_adjacent_letter(chars: &[char], idx: usize, forward: bool) -> bool {
    let mut i = idx;
    while if forward { i < chars.len() } else { i > 0 } {
        let pos = if forward { i } else { i - 1 };
        let ch = chars[pos];
        if is_ignorable_format_or_mark(ch as u32) {
            if forward {
                i += 1;
            } else {
                i -= 1;
            }
            continue;
        }
        return is_java_letter(ch);
    }
    false
}

fn compose_nfc_pair(base: char, mark: char) -> Option<char> {
    match (base, mark) {
        ('І', '\u{0308}') => Some('Ї'),
        ('і', '\u{0308}') => Some('ї'),
        ('И', '\u{0306}') => Some('Й'),
        ('и', '\u{0306}') => Some('й'),
        ('Е', '\u{0308}') => Some('Ё'),
        ('е', '\u{0308}') => Some('ё'),
        ('Г', '\u{0301}') => Some('Ѓ'),
        ('г', '\u{0301}') => Some('ѓ'),
        ('К', '\u{0301}') => Some('Ќ'),
        ('к', '\u{0301}') => Some('ќ'),
        ('У', '\u{0306}') => Some('Ў'),
        ('у', '\u{0306}') => Some('ў'),
        ('Ж', '\u{0306}') => Some('Ӂ'),
        ('ж', '\u{0306}') => Some('ӂ'),
        ('Ø', '\u{0301}') => Some('Ǿ'),
        ('ø', '\u{0301}') => Some('ǿ'),
        ('Æ', '\u{0301}') => Some('Ǽ'),
        ('æ', '\u{0301}') => Some('ǽ'),
        ('Æ', '\u{0304}') => Some('Ǣ'),
        ('æ', '\u{0304}') => Some('ǣ'),
        _ => None,
    }
}

fn fold_arabic_code_point(cp: u32) -> u32 {
    match cp {
        0x0622 | 0x0623 | 0x0625 | 0x0671 => 0x0627,
        0x0649 | 0x06CC => 0x064A,
        0x0629 | 0x06C0 => 0x0647,
        0x06A9 => 0x0643,
        _ => cp,
    }
}

fn fold_latin_code_point(cp: u32) -> &'static str {
    match cp {
        0x00C0..=0x00C5
        | 0x00E0..=0x00E5
        | 0x0100..=0x0105
        | 0x01CD
        | 0x01CE
        | 0x01DE..=0x01E1
        | 0x01FA
        | 0x01FB
        | 0x0200..=0x0203
        | 0x0226
        | 0x0227
        | 0x1E00
        | 0x1E01
        | 0x1EA0..=0x1EB7 => "a",
        0x00C6 | 0x00E6 => "ae",
        0x01E2 | 0x01E3 | 0x01FC | 0x01FD => "æ",
        0x1E02..=0x1E07 => "b",
        0x00C7 | 0x00E7 | 0x0106..=0x010D | 0x1E08 | 0x1E09 => "c",
        0x00D0 | 0x00F0 | 0x010E | 0x010F | 0x1E0A..=0x1E13 => "d",
        0x00C8..=0x00CB
        | 0x00E8..=0x00EB
        | 0x0112..=0x011B
        | 0x0204..=0x0207
        | 0x0228
        | 0x0229
        | 0x1E14..=0x1E1D
        | 0x1EB8..=0x1EC7 => "e",
        0x1E1E | 0x1E1F => "f",
        0x011C..=0x0123 | 0x01E6 | 0x01E7 | 0x01F4 | 0x01F5 | 0x1E20 | 0x1E21 => "g",
        0x0124 | 0x0125 | 0x021E | 0x021F | 0x1E22..=0x1E2B | 0x1E96 => "h",
        0x00CC..=0x00CF
        | 0x00EC..=0x00EF
        | 0x0128..=0x012F
        | 0x01CF
        | 0x01D0
        | 0x0208..=0x020B
        | 0x1E2C..=0x1E2F
        | 0x1EC8..=0x1ECB => "i",
        0x0134 | 0x0135 | 0x01F0 => "j",
        0x0136 | 0x0137 | 0x01E8 | 0x01E9 | 0x1E30..=0x1E35 => "k",
        0x0139..=0x013E | 0x0141 | 0x0142 | 0x1E36..=0x1E3D => "l",
        0x1E3E..=0x1E43 => "m",
        0x00D1 | 0x00F1 | 0x0143..=0x0148 | 0x01F8 | 0x01F9 | 0x1E44..=0x1E4B => "n",
        0x00D2..=0x00D6
        | 0x00D8
        | 0x00F2..=0x00F6
        | 0x00F8
        | 0x014C..=0x0151
        | 0x01A0
        | 0x01A1
        | 0x01D1
        | 0x01D2
        | 0x01EA..=0x01ED
        | 0x020C..=0x020F
        | 0x022A..=0x0231
        | 0x1E4C..=0x1E53
        | 0x1ECC..=0x1EE3 => "o",
        0x01FE | 0x01FF => "ø",
        0x0152 | 0x0153 => "oe",
        0x1E54..=0x1E57 => "p",
        0x0154..=0x0159 | 0x0210..=0x0213 | 0x1E58..=0x1E5F => "r",
        0x015A..=0x0161 | 0x0218 | 0x0219 | 0x1E60..=0x1E69 => "s",
        0x00DF | 0x1E9E => "ss",
        0x1E9B => "ſ",
        0x0162..=0x0165 | 0x021A | 0x021B | 0x1E6A..=0x1E71 | 0x1E97 => "t",
        0x00DE | 0x00FE => "th",
        0x00D9..=0x00DC
        | 0x00F9..=0x00FC
        | 0x0168..=0x0173
        | 0x01AF
        | 0x01B0
        | 0x01D3..=0x01DC
        | 0x0214..=0x0217
        | 0x1E72..=0x1E7B
        | 0x1EE4..=0x1EF1 => "u",
        0x1E7C..=0x1E7F => "v",
        0x0174 | 0x0175 | 0x1E80..=0x1E89 | 0x1E98 => "w",
        0x1E8A..=0x1E8D => "x",
        0x00DD
        | 0x00FD
        | 0x00FF
        | 0x0176..=0x0178
        | 0x0232
        | 0x0233
        | 0x1E8E
        | 0x1E8F
        | 0x1E99
        | 0x1EF2..=0x1EF9 => "y",
        0x0179..=0x017E | 0x1E90..=0x1E95 => "z",
        0x01EE | 0x01EF => "ʒ",
        _ => "",
    }
}

fn push_lowercase(ch: char, out: &mut String) {
    for c in ch.to_lowercase() {
        out.push(c);
    }
}

/// Script-aware text normalization matching `SearchTextNormalizer.normalize(text)`.
pub fn normalize_search_text(text: &str) -> String {
    if text.is_empty() {
        return String::new();
    }
    let raw_chars: Vec<char> = text.chars().collect();
    let mut canonical = Vec::with_capacity(raw_chars.len());
    let mut idx = 0usize;
    while idx < raw_chars.len() {
        let ch = raw_chars[idx];
        if idx + 1 < raw_chars.len() {
            if let Some(composed) = compose_nfc_pair(ch, raw_chars[idx + 1]) {
                canonical.push(composed);
                idx += 2;
                continue;
            }
        }
        canonical.push(ch);
        idx += 1;
    }

    let mut out = String::with_capacity(text.len());
    let mut pending_space = false;

    for (i, &ch) in canonical.iter().enumerate() {
        let cp = ch as u32;
        if is_ignorable_format_or_mark(cp) {
            continue;
        }
        if is_java_whitespace(ch) || is_java_private_use(cp) {
            if !out.is_empty() {
                pending_space = true;
            }
            continue;
        }
        if is_word_internal_connector_candidate(cp)
            && has_adjacent_letter(&canonical, i, false)
            && has_adjacent_letter(&canonical, i + 1, true)
        {
            if pending_space && !out.is_empty() {
                out.push(' ');
                pending_space = false;
            }
            out.push(CANONICAL_APOSTROPHE);
            continue;
        }
        if (0x0400..=0x052F).contains(&cp) {
            if pending_space && !out.is_empty() {
                out.push(' ');
                pending_space = false;
            }
            let lower = if (0x0410..=0x042F).contains(&cp) {
                char::from_u32(cp + 0x20).unwrap_or(ch)
            } else if (0x0400..=0x040F).contains(&cp) {
                char::from_u32(cp + 0x50).unwrap_or(ch)
            } else {
                ch.to_lowercase().next().unwrap_or(ch)
            };
            if lower == 'ё' {
                out.push('е');
            } else {
                out.push(lower);
            }
            continue;
        }
        if (0x0600..=0x06FF).contains(&cp) {
            if pending_space && !out.is_empty() {
                out.push(' ');
                pending_space = false;
            }
            let folded = fold_arabic_code_point(cp);
            if let Some(fch) = char::from_u32(folded) {
                out.push(fch);
            }
            continue;
        }
        if (0x0590..=0x05FF).contains(&cp) {
            if pending_space && !out.is_empty() {
                out.push(' ');
                pending_space = false;
            }
            out.push(ch);
            continue;
        }
        if (0x00C0..=0x024F).contains(&cp) || (0x1E00..=0x1EFF).contains(&cp) {
            if pending_space && !out.is_empty() {
                out.push(' ');
                pending_space = false;
            }
            let mapped = fold_latin_code_point(cp);
            if !mapped.is_empty() {
                out.push_str(mapped);
            } else {
                push_lowercase(ch, &mut out);
            }
            continue;
        }
        if is_combining_mark(cp) {
            continue;
        }
        if pending_space && !out.is_empty() {
            out.push(' ');
            pending_space = false;
        }
        push_lowercase(ch, &mut out);
    }
    out
}

fn is_token_char(ch: char) -> bool {
    ch == CANONICAL_APOSTROPHE
        || (!is_java_private_use(ch as u32)
            && !is_combining_mark(ch as u32)
            && ch.is_alphanumeric())
}

/// Extracts normalized search tokens matching `SearchTextNormalizer.tokenize`.
pub fn tokenize_search_query(query: &str) -> Vec<String> {
    let norm = normalize_search_text(query);
    if norm.trim().is_empty() {
        return Vec::new();
    }
    let mut tokens = Vec::new();
    let mut current = String::new();
    for ch in norm.chars() {
        if is_token_char(ch) {
            current.push(ch);
        } else if !current.is_empty() {
            tokens.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        tokens.push(current);
    }
    tokens
}

/// Builds an FTS5 prefix `MATCH` query matching `SearchTextNormalizer.toFts5PrefixQuery`.
pub fn to_fts5_prefix_query(query: &str) -> Option<String> {
    let tokens = tokenize_search_query(query);
    if tokens.is_empty() {
        return None;
    }
    let joined: Vec<String> = tokens
        .into_iter()
        .map(|t| {
            let escaped = t.replace('"', "\"\"");
            format!("\"{escaped}\"*")
        })
        .collect();
    Some(joined.join(" "))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frb;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    // Minimal FRB revision-5 test encoder for self-contained Rust unit tests.
    struct Enc(Vec<u8>);
    impl Enc {
        fn new() -> Self {
            Self(Vec::new())
        }
        fn u_leb(&mut self, mut v: u64) {
            while v >> 7 != 0 {
                self.0.push(((v & 0x7F) as u8) | 0x80);
                v >>= 7;
            }
            self.0.push(v as u8);
        }
        fn i32(&mut self, v: i32) {
            let z = ((v as i64) << 1) ^ ((v as i64) >> 63);
            self.u_leb(z as u64);
        }
        fn str(&mut self, s: &str) {
            self.u_leb((s.len() as u64) << 1);
            self.0.extend_from_slice(s.as_bytes());
        }
        fn bool(&mut self, v: bool) {
            self.0.push(u8::from(v));
        }
        fn parsed_text(&mut self, text: &str, ranges: &[(i32, i32, &str)]) {
            self.0.push(1); // field 1: text
            self.str(text);
            if !ranges.is_empty() {
                self.0.push(2); // field 2: ranges
                self.u_leb(ranges.len() as u64);
                for &(start, end, tag) in ranges {
                    self.0.push(1);
                    self.str("string");
                    self.0.push(2);
                    self.i32(start);
                    self.0.push(3);
                    self.i32(end);
                    self.0.push(4);
                    self.str(tag);
                    self.0.push(5);
                    self.bool(true);
                    self.str("note-key");
                    self.0.push(0);
                }
            }
            self.0.push(0);
        }
        fn paragraph(&mut self, text: &str, ranges: &[(i32, i32, &str)]) {
            self.0.push(1);
            self.str("com.example.frogreader.data.model.ContentElement.Paragraph");
            self.0.push(2);
            self.0.push(1);
            self.parsed_text(text, ranges);
            self.0.push(0); // Paragraph end
            self.0.push(0); // Poly end
        }
        fn heading(&mut self, text: &str, level: i32) {
            self.0.push(1);
            self.str("com.example.frogreader.data.model.ContentElement.Heading");
            self.0.push(2);
            self.0.push(1);
            self.parsed_text(text, &[]);
            self.0.push(2);
            self.i32(level);
            self.0.push(0);
            self.0.push(0);
        }
        fn divider(&mut self) {
            self.0.push(1);
            self.str("com.example.frogreader.data.model.ContentElement.Divider");
            self.0.push(2);
            self.0.push(0);
            self.0.push(0);
        }
        fn table(&mut self, rows: &[&[&str]]) {
            self.0.push(1);
            self.str("com.example.frogreader.data.model.ContentElement.Table");
            self.0.push(2);
            self.0.push(1);
            self.u_leb(rows.len() as u64);
            for row in rows {
                self.0.push(1);
                self.u_leb(row.len() as u64);
                for cell in *row {
                    self.0.push(1);
                    self.parsed_text(cell, &[]);
                    self.0.push(0);
                }
                self.0.push(2);
                self.bool(false);
                self.0.push(0);
            }
            self.0.push(0);
            self.0.push(0);
        }
        fn chapter(title: Option<&str>, build_elems: impl FnOnce(&mut Enc) -> usize) -> Vec<u8> {
            let mut e = Enc::new();
            if let Some(t) = title {
                e.0.push(1);
                e.bool(true);
                e.str(t);
            }
            let mut body = Enc::new();
            let count = build_elems(&mut body);
            e.0.push(2);
            e.u_leb(count as u64);
            e.0.extend_from_slice(&body.0);
            e.0.push(0);
            e.0
        }
    }

    struct TempDir(PathBuf);
    impl TempDir {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let p = std::env::temp_dir().join(format!(
                "frb-search-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&p).unwrap();
            Self(p)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn searches_frb_file_directly_without_reading_globals() {
        let dir = TempDir::new();
        let ch0 = Enc::chapter(Some("Empty"), |_| 0);
        let ch1 = Enc::chapter(Some("Ch1"), |e| {
            e.paragraph("One needle here and NEEDLE there", &[]);
            e.divider();
            e.heading("Needle heading", 2);
            3
        });
        let ch2 = Enc::chapter(Some("Ch2"), |e| {
            e.table(&[&["Col1", "A needle in a table"], &["Row2", "Needle end"]]);
            1
        });

        let path = dir.0.join("book.frb");
        let meta = b"test-meta";
        let mut writer = frb::Writer::begin(&path, meta, &[0, 3, 1], frb::MAX_FILE_BYTES).unwrap();
        for (idx, bytes) in [
            b"index".as_slice(),
            b"corrupt-globals-never-read".as_slice(),
            &ch0,
            &ch1,
            &ch2,
        ]
        .iter()
        .enumerate()
        {
            let p = dir.0.join(format!("b{idx}"));
            fs::write(&p, bytes).unwrap();
            writer.write_block(idx, &p).unwrap();
        }
        writer.finish().unwrap();

        let res = search_frb_file(&path, meta, "needle", 1, false, SEARCH_RESULT_LIMIT).unwrap();
        assert_eq!(res.total_count, 5);
        assert_eq!(
            res.matches
                .iter()
                .map(|m| (m.item_index, m.chapter_index, m.char_start))
                .collect::<Vec<_>>(),
            vec![(1, 1, 4), (1, 1, 20), (3, 1, 0), (4, 2, 8), (4, 2, 32)]
        );
        assert_eq!(res.matches[1].matched(), "NEEDLE");
        assert_eq!(res.matches[3].before(), "Col1  A ");
        assert_eq!(res.matches[3].after(), " in a table Row2  Needle end");
    }

    #[test]
    fn footnote_hiding_strips_only_footnote_tag_and_preserves_utf16_offsets() {
        let text = "Prefix needle then body needle 🎉 tail needle";
        // "Prefix " = 7, "needle" = 6 -> [7, 13)
        let ch = Enc::chapter(Some("Notes"), |e| {
            e.paragraph(text, &[(7, 13, FOOTNOTE_TAG), (0, 6, "frogreader:hidden")]);
            1
        });
        let shown = search_frb_chapter_payload(&ch, 0, 0, Some(1), "needle", false, 300).unwrap();
        assert_eq!(shown.total_count, 3);
        let hidden = search_frb_chapter_payload(&ch, 0, 0, Some(1), "needle", true, 300).unwrap();
        assert_eq!(hidden.total_count, 2);
        // "Prefix  then body " = 18; "needle" = 6 -> 24; " 🎉 tail " = 9 -> 33.
        assert_eq!(hidden.matches[0].char_start, 18);
        assert_eq!(hidden.matches[1].char_start, 33);
    }

    #[test]
    fn word_aligned_snippet_window_matches_kotlin_bounds() {
        let prefix = "alpha beta gamma delta epsilon zeta eta theta ";
        let suffix = " iota kappa lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega alpha beta gamma delta epsilon zeta eta";
        let text = format!("{prefix}needle{suffix}");
        let ch = Enc::chapter(None, |e| {
            e.paragraph(&text, &[]);
            1
        });
        let res = search_frb_chapter_payload(&ch, 0, 0, Some(1), "needle", false, 300).unwrap();
        let m = &res.matches[0];
        assert!(m.before().starts_with('…'));
        assert!(m.after().ends_with('…'));
        assert!(!m.before().starts_with("…beta")); // "beta" was cut mid-word -> snaps to "gamma"
        assert!(m.before().starts_with("…gamma"));
    }

    #[test]
    fn normalizer_matches_kotlin_rules() {
        assert_eq!(
            normalize_search_text("  Café   Naïve\nRésumé "),
            "cafe naive resume"
        );
        assert_eq!(
            normalize_search_text("Straße Søren Łódź"),
            "strasse soren lodz"
        );
        assert_eq!(
            normalize_search_text("Encyclopædie Œuvre"),
            "encyclopaedie oeuvre"
        );
        assert_eq!(
            normalize_search_text("Украї\u{0301}на й Ки\u{0301}їв"),
            "україна й київ"
        );
        assert_eq!(
            normalize_search_text("І\u{0308}жак и\u{0306}орж Киі\u{0308}в"),
            "їжак йорж київ"
        );
        assert_eq!(
            normalize_search_text("أَحْمَدُ إِبْرَاهِيم آفاق مَكْتَبَة مُوسَى كِتَابٌ كـتـاب"),
            "احمد ابراهيم افاق مكتبه موسي كتاب كتاب"
        );
        assert_eq!(
            normalize_search_text("שָׁלוֹם בְּרֵאשִׁית הַזְּמַן"),
            "שלום בראשית הזמן"
        );
        assert_eq!(
            tokenize_search_query("солов'їний пам’ять"),
            tokenize_search_query("соловʼїний пам′ять")
        );
        assert_eq!(
            to_fts5_prefix_query("time \"AND\" OR* (test)").as_deref(),
            Some("\"time\"* \"and\"* \"or\"* \"test\"*")
        );
        assert_eq!(to_fts5_prefix_query("   ... — ?!  "), None);
    }

    #[test]
    fn non_ascii_and_isk_case_folding_parity() {
        let text = "İstanbul ſample Kelvin Привіт ПРИВІТ";
        let ch = Enc::chapter(None, |e| {
            e.paragraph(text, &[]);
            1
        });
        assert_eq!(
            search_frb_chapter_payload(&ch, 0, 0, Some(1), "istanbul", false, 10)
                .unwrap()
                .total_count,
            1
        );
        assert_eq!(
            search_frb_chapter_payload(&ch, 0, 0, Some(1), "sample", false, 10)
                .unwrap()
                .total_count,
            1
        );
        assert_eq!(
            search_frb_chapter_payload(&ch, 0, 0, Some(1), "kelvin", false, 10)
                .unwrap()
                .total_count,
            1
        );
        assert_eq!(
            search_frb_chapter_payload(&ch, 0, 0, Some(1), "привіт", false, 10)
                .unwrap()
                .total_count,
            2
        );
    }

    #[test]
    fn search_frb_reader_range_batches_match_full_file_search() {
        let dir = TempDir::new();
        let mut counts = Vec::new();
        let mut payloads = Vec::new();
        for ch in 0..7 {
            if ch == 2 {
                counts.push(0);
                payloads.push(Enc::chapter(Some("Empty"), |_| 0));
            } else {
                counts.push(2);
                let p0 = format!("Chapter {ch} alpha needle and NEEDLE");
                let p1 = format!("Footnote [needle] body needle in ch {ch}");
                // "[needle]" starts at byte/utf16 9..17
                payloads.push(Enc::chapter(Some(&format!("Ch{ch}")), |e| {
                    e.paragraph(&p0, &[]);
                    e.paragraph(&p1, &[(9, 17, FOOTNOTE_TAG)]);
                    2
                }));
            }
        }

        let path = dir.0.join("batched.frb");
        let meta = b"batch-meta";
        let mut writer = frb::Writer::begin(&path, meta, &counts, frb::MAX_FILE_BYTES).unwrap();
        writer.write_block_bytes(0, b"index").unwrap();
        writer.write_block_bytes(1, b"corrupt-globals").unwrap();
        for (i, payload) in payloads.iter().enumerate() {
            writer.write_block_bytes(i + 2, payload).unwrap();
        }
        writer.finish().unwrap();

        let mut reader = frb::Reader::open(&path, meta).unwrap();

        for hide in [false, true] {
            for cover_count in [0, 1] {
                for limit in [0, 3, 5, SEARCH_RESULT_LIMIT] {
                    let expected =
                        search_frb_file(&path, meta, "needle", cover_count, hide, limit).unwrap();

                    // Stitch batches of 2 chapters at a time, mirroring IndexedReaderSearch.
                    let mut acc_matches = Vec::new();
                    let mut acc_total = 0i32;
                    let mut start = 0usize;
                    while start < counts.len() {
                        let remaining = limit
                            .min(SEARCH_RESULT_LIMIT)
                            .saturating_sub(acc_matches.len());
                        let batch = search_frb_reader_range(
                            &mut reader,
                            start,
                            2,
                            cover_count,
                            "needle",
                            hide,
                            remaining,
                        )
                        .unwrap();
                        acc_total += batch.total_count;
                        acc_matches.extend(batch.matches);
                        start += 2;
                    }
                    assert_eq!(
                        acc_total, expected.total_count,
                        "total mismatch hide={hide} cover={cover_count} limit={limit}"
                    );
                    assert_eq!(
                        acc_matches, expected.matches,
                        "matches mismatch hide={hide} cover={cover_count} limit={limit}"
                    );
                }
            }
        }

        // Empty query, zero count, and out-of-range start return empty results.
        let empty_q = search_frb_reader_range(&mut reader, 0, 7, 0, "", false, 10).unwrap();
        assert_eq!(empty_q.total_count, 0);
        assert!(empty_q.matches.is_empty());

        let zero_cnt = search_frb_reader_range(&mut reader, 0, 0, 0, "needle", false, 10).unwrap();
        assert_eq!(zero_cnt.total_count, 0);
        assert!(zero_cnt.matches.is_empty());

        let past_end =
            search_frb_reader_range(&mut reader, 100, usize::MAX, 0, "needle", false, 10).unwrap();
        assert_eq!(past_end.total_count, 0);
        assert!(past_end.matches.is_empty());

        // Invalid cover_count returns InvalidContainer.
        assert_eq!(
            search_frb_reader_range(&mut reader, 0, 7, 2, "needle", false, 10),
            Err(FrbSearchError::InvalidContainer)
        );
        assert_eq!(
            search_frb_reader_range(&mut reader, 0, 7, -1, "needle", false, 10),
            Err(FrbSearchError::InvalidContainer)
        );
    }

    #[test]
    fn search_frb_reader_range_reports_corrupt_chapter_and_element_mismatch() {
        let dir = TempDir::new();
        let ch0 = Enc::chapter(Some("Good"), |e| {
            e.paragraph("needle ok", &[]);
            1
        });
        let ch1_bad_payload = vec![0xFF, 0xFF, 0xFF]; // malformed FRB model block
        let ch2_wrong_count = Enc::chapter(Some("CountMismatch"), |e| {
            e.paragraph("needle one", &[]);
            1
        });

        let path = dir.0.join("corrupt.frb");
        let meta = b"corrupt-meta";
        // Declare 2 elements for ch2 when payload only has 1.
        let mut writer = frb::Writer::begin(&path, meta, &[1, 1, 2], frb::MAX_FILE_BYTES).unwrap();
        writer.write_block_bytes(0, b"index").unwrap();
        writer.write_block_bytes(1, b"globals").unwrap();
        writer.write_block_bytes(2, &ch0).unwrap();
        writer.write_block_bytes(3, &ch1_bad_payload).unwrap();
        writer.write_block_bytes(4, &ch2_wrong_count).unwrap();
        writer.finish().unwrap();

        let mut reader = frb::Reader::open(&path, meta).unwrap();

        // Range [0, 1) succeeds even though chapter 1 is corrupt.
        let ok = search_frb_reader_range(&mut reader, 0, 1, 0, "needle", false, 10).unwrap();
        assert_eq!(ok.total_count, 1);

        // Range [0, 2) fails with ChapterCorrupt for chapter 1.
        match search_frb_reader_range(&mut reader, 0, 2, 0, "needle", false, 10) {
            Err(FrbSearchError::ChapterCorrupt { chapter_index, .. }) => {
                assert_eq!(chapter_index, 1);
            }
            other => panic!("expected ChapterCorrupt(1), got {other:?}"),
        }

        // Range [2, 1) fails with ChapterCorrupt for chapter 2 due to element count mismatch.
        match search_frb_reader_range(&mut reader, 2, 1, 0, "needle", false, 10) {
            Err(FrbSearchError::ChapterCorrupt {
                chapter_index,
                reason,
            }) => {
                assert_eq!(chapter_index, 2);
                assert!(reason.contains("element count mismatch"), "reason={reason}");
            }
            other => panic!("expected ChapterCorrupt(2), got {other:?}"),
        }
    }

    #[test]
    fn search_frb_chapter_reports_total_count_overflow() {
        let ch = Enc::chapter(Some("Overflow"), |e| {
            e.paragraph("needle", &[]);
            1
        });
        let mut matcher = SearchMatcher::new("needle");
        let mut matches = Vec::new();
        let mut total_count = i32::MAX;
        let err = search_frb_chapter_into(
            &ch,
            0,
            0,
            Some(1),
            false,
            10,
            &mut matcher,
            &mut matches,
            &mut total_count,
        )
        .unwrap_err();
        assert_eq!(err, FrbSearchError::Overflow);
    }
}
