//! Native streaming FB2 XML encoding detection, on-the-fly XML sanitization,
//! and fast `quick-xml` structural scanning (M4.2).

#[path = "fb2_body.rs"]
pub(crate) mod body;

use encoding_rs::{
    Decoder, Encoding, ISO_8859_5, KOI8_R, KOI8_U, REPLACEMENT, UTF_16BE, UTF_16LE, UTF_8,
    WINDOWS_1251,
};
use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesRef, BytesStart, Event};
use quick_xml::Reader;
use std::borrow::Cow;

pub(crate) const MAX_FB2_BYTES: usize = 256 * 1024 * 1024;
const HEADER_SNIFF_BYTES: usize = 1024;
const MAX_ENTITY_LOOKAHEAD: usize = 12;
const MAX_TAG_CARRY_BYTES: usize = 16 * 1024 * 1024;
const MAX_XML_DEPTH: usize = 2048;
const MAX_BODIES: usize = 1_024;
const MAX_SECTIONS: usize = 100_000;
const MAX_BINARIES: usize = 100_000;
const MAX_STYLESHEETS: usize = 1_024;
const MAX_TITLE_PREVIEW_BYTES: usize = 1024;
const MAX_WIRE_BYTES: usize = 64 * 1024 * 1024;
const MAX_METADATA_ITEMS: usize = 4_096;
const MAX_METADATA_FIELD_BYTES: usize = 512 * 1024;
#[cfg(test)]
const DEFAULT_MAX_COVER_BYTES: usize = 32 * 1024 * 1024;

pub(crate) const FB2_SCAN_MAGIC: u32 = 0x3132_4246; // FB21
pub(crate) const FB2_META_MAGIC: u32 = 0x4D32_4246; // FB2M

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DetectedCharset {
    Web(&'static Encoding),
    Iso88591,
    Ascii,
    Utf32 { little_endian: bool },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct EncodingDetection {
    pub(crate) charset: DetectedCharset,
    pub(crate) bom_len: usize,
    pub(crate) label: &'static str,
}

/// Detects the XML document encoding from BOM, un-BOMed UTF-16 heuristics,
/// or the leading `<?xml ... encoding="..." ?>` declaration.
pub(crate) fn detect_xml_encoding(prefix: &[u8]) -> EncodingDetection {
    if prefix.starts_with(b"\x00\x00\xfe\xff") {
        return EncodingDetection {
            charset: DetectedCharset::Utf32 {
                little_endian: false,
            },
            bom_len: 4,
            label: "UTF-32BE",
        };
    }
    if prefix.starts_with(b"\xff\xfe\x00\x00") {
        return EncodingDetection {
            charset: DetectedCharset::Utf32 {
                little_endian: true,
            },
            bom_len: 4,
            label: "UTF-32LE",
        };
    }
    if prefix.starts_with(b"\xef\xbb\xbf") {
        return EncodingDetection {
            charset: DetectedCharset::Web(UTF_8),
            bom_len: 3,
            label: "UTF-8",
        };
    }
    if prefix.starts_with(b"\xff\xfe") {
        return EncodingDetection {
            charset: DetectedCharset::Web(UTF_16LE),
            bom_len: 2,
            label: "UTF-16LE",
        };
    }
    if prefix.starts_with(b"\xfe\xff") {
        return EncodingDetection {
            charset: DetectedCharset::Web(UTF_16BE),
            bom_len: 2,
            label: "UTF-16BE",
        };
    }

    // Un-BOMed UTF-16 heuristic per XML 1.0 Appendix F.
    if prefix.len() >= 4 {
        if prefix[0] == 0x00
            && matches!(prefix[1], b'<' | b' ' | b'\t' | b'\r' | b'\n')
            && prefix[2] == 0x00
        {
            return EncodingDetection {
                charset: DetectedCharset::Web(UTF_16BE),
                bom_len: 0,
                label: "UTF-16BE",
            };
        }
        if matches!(prefix[0], b'<' | b' ' | b'\t' | b'\r' | b'\n')
            && prefix[1] == 0x00
            && prefix[3] == 0x00
        {
            return EncodingDetection {
                charset: DetectedCharset::Web(UTF_16LE),
                bom_len: 0,
                label: "UTF-16LE",
            };
        }
    }

    if let Some((charset, label)) = xml_prolog_encoding(prefix) {
        return EncodingDetection {
            charset,
            bom_len: 0,
            label,
        };
    }

    EncodingDetection {
        charset: DetectedCharset::Web(UTF_8),
        bom_len: 0,
        label: "UTF-8",
    }
}

fn xml_prolog_encoding(prefix: &[u8]) -> Option<(DetectedCharset, &'static str)> {
    let trimmed = prefix.trim_ascii_start();
    let rest = trimmed.strip_prefix(b"<?xml")?;
    if !rest.first().is_some_and(u8::is_ascii_whitespace) {
        return None;
    }
    let end = rest.windows(2).position(|pair| pair == b"?>")?;
    let decl = &rest[..end];
    let raw_label = find_prolog_attribute(decl, b"encoding")?;
    let label = std::str::from_utf8(raw_label)
        .ok()?
        .trim()
        .to_ascii_lowercase();
    resolve_encoding_label(&label)
}

fn find_prolog_attribute<'a>(mut decl: &'a [u8], wanted: &[u8]) -> Option<&'a [u8]> {
    while !decl.is_empty() {
        decl = decl.trim_ascii_start();
        if decl.is_empty() {
            break;
        }
        let key_end = decl
            .iter()
            .position(|b| b.is_ascii_whitespace() || *b == b'=')
            .unwrap_or(decl.len());
        let key = &decl[..key_end];
        decl = decl[key_end..].trim_ascii_start();
        let Some(after_eq) = decl.strip_prefix(b"=") else {
            continue;
        };
        let after_eq = after_eq.trim_ascii_start();
        let Some(&quote @ (b'"' | b'\'')) = after_eq.first() else {
            break;
        };
        let value_rest = &after_eq[1..];
        let close = value_rest.iter().position(|&b| b == quote)?;
        let value = &value_rest[..close];
        decl = &value_rest[close + 1..];
        if key.eq_ignore_ascii_case(wanted) {
            return Some(value);
        }
    }
    None
}

fn resolve_encoding_label(label: &str) -> Option<(DetectedCharset, &'static str)> {
    match label {
        "utf-8" | "utf8" => Some((DetectedCharset::Web(UTF_8), "UTF-8")),
        "utf-16" | "utf16" | "utf-16be" | "utf16be" | "unicodebig" | "unicodebigunmarked" => {
            Some((DetectedCharset::Web(UTF_16BE), "UTF-16BE"))
        }
        "utf-16le" | "utf16le" | "unicodelittle" | "unicodelittleunmarked" => {
            Some((DetectedCharset::Web(UTF_16LE), "UTF-16LE"))
        }
        "windows-1251" | "cp1251" | "win-1251" | "x-cp1251" | "ansi-1251" => {
            Some((DetectedCharset::Web(WINDOWS_1251), "windows-1251"))
        }
        "koi8-r" | "koi8r" | "cskoi8r" => Some((DetectedCharset::Web(KOI8_R), "KOI8-R")),
        "koi8-u" | "koi8u" => Some((DetectedCharset::Web(KOI8_U), "KOI8-U")),
        "iso-8859-5" | "iso8859-5" | "iso_8859-5" | "cyrillic" => {
            Some((DetectedCharset::Web(ISO_8859_5), "ISO-8859-5"))
        }
        "iso-8859-1" | "iso8859-1" | "iso_8859-1" | "latin1" | "latin-1" | "l1" => {
            Some((DetectedCharset::Iso88591, "ISO-8859-1"))
        }
        "us-ascii" | "ascii" | "ansi_x3.4-1968" => Some((DetectedCharset::Ascii, "US-ASCII")),
        "utf-32" | "utf32" | "utf-32be" | "utf32be" => Some((
            DetectedCharset::Utf32 {
                little_endian: false,
            },
            "UTF-32BE",
        )),
        "utf-32le" | "utf32le" => Some((
            DetectedCharset::Utf32 {
                little_endian: true,
            },
            "UTF-32LE",
        )),
        _ => Encoding::for_label(label.as_bytes())
            .filter(|&enc| enc != REPLACEMENT)
            .map(|enc| (DetectedCharset::Web(enc), enc.name())),
    }
}

enum StreamDecoder {
    Web(Decoder),
    Iso88591,
    Ascii,
    Utf32 {
        little_endian: bool,
        rem: [u8; 4],
        rem_len: usize,
    },
}

impl StreamDecoder {
    fn from_charset(charset: DetectedCharset) -> Self {
        match charset {
            DetectedCharset::Web(enc) => Self::Web(enc.new_decoder_without_bom_handling()),
            DetectedCharset::Iso88591 => Self::Iso88591,
            DetectedCharset::Ascii => Self::Ascii,
            DetectedCharset::Utf32 { little_endian } => Self::Utf32 {
                little_endian,
                rem: [0; 4],
                rem_len: 0,
            },
        }
    }

    fn decode_chunk(&mut self, src: &[u8], is_eof: bool, out: &mut Vec<u8>) {
        match self {
            Self::Web(decoder) => {
                let mut offset = 0_usize;
                let mut scratch = [0_u8; 32 * 1024];
                loop {
                    let slice = &src[offset..];
                    let (result, read, written, _) =
                        decoder.decode_to_utf8(slice, &mut scratch, is_eof);
                    if written > 0 {
                        out.extend_from_slice(&scratch[..written]);
                    }
                    offset += read;
                    match result {
                        encoding_rs::CoderResult::InputEmpty => break,
                        encoding_rs::CoderResult::OutputFull => continue,
                    }
                }
            }
            Self::Iso88591 => {
                out.reserve(src.len());
                let mut utf8_buf = [0_u8; 4];
                for &byte in src {
                    if byte.is_ascii() {
                        out.push(byte);
                    } else {
                        let ch = char::from(byte);
                        out.extend_from_slice(ch.encode_utf8(&mut utf8_buf).as_bytes());
                    }
                }
            }
            Self::Ascii => {
                out.reserve(src.len());
                for &byte in src {
                    if byte.is_ascii() {
                        out.push(byte);
                    } else {
                        out.extend_from_slice("\u{fffd}".as_bytes());
                    }
                }
            }
            Self::Utf32 {
                little_endian,
                rem,
                rem_len,
            } => {
                let mut idx = 0_usize;
                let mut utf8_buf = [0_u8; 4];
                if *rem_len > 0 {
                    while *rem_len < 4 && idx < src.len() {
                        rem[*rem_len] = src[idx];
                        *rem_len += 1;
                        idx += 1;
                    }
                    if *rem_len == 4 {
                        let scalar = if *little_endian {
                            u32::from_le_bytes(*rem)
                        } else {
                            u32::from_be_bytes(*rem)
                        };
                        let ch = char::from_u32(scalar).unwrap_or(char::REPLACEMENT_CHARACTER);
                        out.extend_from_slice(ch.encode_utf8(&mut utf8_buf).as_bytes());
                        *rem_len = 0;
                    }
                }
                let chunks = src[idx..].chunks_exact(4);
                let tail = chunks.remainder();
                for chunk in chunks {
                    let word = [chunk[0], chunk[1], chunk[2], chunk[3]];
                    let scalar = if *little_endian {
                        u32::from_le_bytes(word)
                    } else {
                        u32::from_be_bytes(word)
                    };
                    let ch = char::from_u32(scalar).unwrap_or(char::REPLACEMENT_CHARACTER);
                    out.extend_from_slice(ch.encode_utf8(&mut utf8_buf).as_bytes());
                }
                if !tail.is_empty() {
                    rem[..tail.len()].copy_from_slice(tail);
                    *rem_len = tail.len();
                }
                if is_eof && *rem_len > 0 {
                    out.extend_from_slice("\u{fffd}".as_bytes());
                    *rem_len = 0;
                }
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SanitizeMode {
    Normal,
    CData,
    Comment,
}

/// Streaming decoder + XML sanitizer for FB2 byte streams.
///
/// Sniffs BOM/XML prolog encoding from the prefix, transcodes to UTF-8 via
/// `encoding_rs`, strips invalid XML 1.0 control characters, resolves common
/// named HTML entities (`&nbsp;`, `&mdash;`, `&ndash;`, `&laquo;`, `&raquo;`,
/// `&hellip;`, etc.), and escapes bare `&` while preserving `<![CDATA[...]]>`.
pub(crate) struct Fb2Sanitizer {
    rewrite_prolog_encoding: bool,
    max_input_bytes: usize,
    header_buf: Vec<u8>,
    decoder: Option<StreamDecoder>,
    detected_label: &'static str,
    prolog_handled: bool,
    mode: SanitizeMode,
    carry: Vec<u8>,
    utf8_scratch: Vec<u8>,
    total_input_bytes: usize,
}

impl Fb2Sanitizer {
    pub(crate) fn new(rewrite_prolog_encoding: bool) -> Self {
        Self::with_max_input_bytes(rewrite_prolog_encoding, MAX_FB2_BYTES)
    }

    pub(crate) fn with_max_input_bytes(
        rewrite_prolog_encoding: bool,
        max_input_bytes: usize,
    ) -> Self {
        Self {
            rewrite_prolog_encoding,
            max_input_bytes,
            header_buf: Vec::new(),
            decoder: None,
            detected_label: "UTF-8",
            prolog_handled: false,
            mode: SanitizeMode::Normal,
            carry: Vec::new(),
            utf8_scratch: Vec::new(),
            total_input_bytes: 0,
        }
    }

    pub(crate) fn detected_encoding(&self) -> &'static str {
        self.detected_label
    }

    /// Feeds an input chunk (or EOF when `is_eof == true`), appending sanitized
    /// UTF-8 XML bytes into `out`.
    pub(crate) fn feed(
        &mut self,
        chunk: &[u8],
        is_eof: bool,
        out: &mut Vec<u8>,
    ) -> Result<(), String> {
        self.total_input_bytes = self
            .total_input_bytes
            .checked_add(chunk.len())
            .ok_or_else(|| "FB2 input byte count overflow".to_owned())?;
        if self.total_input_bytes > self.max_input_bytes {
            return Err(format!(
                "FB2 document exceeds {} bytes",
                self.max_input_bytes
            ));
        }

        if self.decoder.is_none() {
            self.header_buf.extend_from_slice(chunk);
            if self.header_buf.len() < HEADER_SNIFF_BYTES && !is_eof {
                return Ok(());
            }
            let detection = detect_xml_encoding(&self.header_buf);
            self.detected_label = detection.label;
            let mut dec = StreamDecoder::from_charset(detection.charset);
            self.utf8_scratch.clear();
            dec.decode_chunk(
                &self.header_buf[detection.bom_len..],
                is_eof,
                &mut self.utf8_scratch,
            );
            self.header_buf.clear();
            self.decoder = Some(dec);

            if self.rewrite_prolog_encoding && !self.prolog_handled {
                self.prolog_handled = true;
                let rewritten = rewrite_xml_prolog_encoding(&self.utf8_scratch);
                if let Some(updated) = rewritten {
                    self.utf8_scratch = updated;
                }
            } else {
                self.prolog_handled = true;
            }

            let utf8 = std::mem::take(&mut self.utf8_scratch);
            self.sanitize_utf8_bytes(&utf8, is_eof, out);
            self.utf8_scratch = utf8;
            self.utf8_scratch.clear();
            return Ok(());
        }

        self.utf8_scratch.clear();
        if let Some(decoder) = self.decoder.as_mut() {
            decoder.decode_chunk(chunk, is_eof, &mut self.utf8_scratch);
        }
        let utf8 = std::mem::take(&mut self.utf8_scratch);
        self.sanitize_utf8_bytes(&utf8, is_eof, out);
        self.utf8_scratch = utf8;
        self.utf8_scratch.clear();
        Ok(())
    }

    fn sanitize_utf8_bytes(&mut self, input: &[u8], is_eof: bool, out: &mut Vec<u8>) {
        if self.carry.is_empty() {
            let consumed = sanitize_slice(input, is_eof, &mut self.mode, out);
            if consumed < input.len() {
                self.carry.extend_from_slice(&input[consumed..]);
            }
            return;
        }

        // Resolve the small split token at the carry/input boundary without
        // copying the entire input chunk.
        let bridge_len = input.len().min(MAX_ENTITY_LOOKAHEAD + 16);
        self.carry.extend_from_slice(&input[..bridge_len]);
        let carry_is_eof = is_eof && bridge_len == input.len();
        let consumed = sanitize_slice(&self.carry, carry_is_eof, &mut self.mode, out);
        let remaining_in_carry = self.carry.len() - consumed;

        if remaining_in_carry <= bridge_len {
            // Boundary resolved inside `input[..bridge_len]`: resume directly in `input`.
            self.carry.clear();
            let resume_at = bridge_len - remaining_in_carry;
            let tail = &input[resume_at..];
            let tail_consumed = sanitize_slice(tail, is_eof, &mut self.mode, out);
            if tail_consumed < tail.len() {
                self.carry.extend_from_slice(&tail[tail_consumed..]);
            }
        } else {
            self.carry.drain(..consumed);
            if is_eof && !self.carry.is_empty() {
                let tail = std::mem::take(&mut self.carry);
                let _ = sanitize_slice(&tail, true, &mut self.mode, out);
            }
        }
    }
}

/// Sanitizes an in-memory FB2 byte slice to UTF-8 XML.
#[cfg(test)]
pub(crate) fn sanitize_fb2_bytes(
    bytes: &[u8],
    rewrite_prolog_encoding: bool,
) -> Result<Vec<u8>, String> {
    let mut sanitizer = Fb2Sanitizer::new(rewrite_prolog_encoding);
    let mut out = Vec::with_capacity(bytes.len());
    sanitizer.feed(bytes, true, &mut out)?;
    Ok(out)
}

fn rewrite_xml_prolog_encoding(utf8: &[u8]) -> Option<Vec<u8>> {
    let leading_ws = utf8
        .iter()
        .position(|b| !b.is_ascii_whitespace())
        .unwrap_or(utf8.len());
    let after_ws = &utf8[leading_ws..];
    let after_xml = after_ws.strip_prefix(b"<?xml")?;
    if !after_xml.first().is_some_and(u8::is_ascii_whitespace) {
        return None;
    }
    let decl_end_rel = after_xml.windows(2).position(|pair| pair == b"?>")?;
    let decl_start = leading_ws + 5;
    let decl_end = decl_start + decl_end_rel;
    let mut cursor = decl_start;
    while cursor < decl_end {
        while cursor < decl_end && utf8[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        let key_start = cursor;
        while cursor < decl_end && !utf8[cursor].is_ascii_whitespace() && utf8[cursor] != b'=' {
            cursor += 1;
        }
        let key = &utf8[key_start..cursor];
        while cursor < decl_end && utf8[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        if cursor >= decl_end || utf8[cursor] != b'=' {
            continue;
        }
        cursor += 1;
        while cursor < decl_end && utf8[cursor].is_ascii_whitespace() {
            cursor += 1;
        }
        if cursor >= decl_end {
            break;
        }
        let quote = utf8[cursor];
        if quote != b'"' && quote != b'\'' {
            break;
        }
        cursor += 1;
        let val_start = cursor;
        while cursor < decl_end && utf8[cursor] != quote {
            cursor += 1;
        }
        if cursor >= decl_end {
            break;
        }
        let val_end = cursor;
        cursor += 1;
        if key.eq_ignore_ascii_case(b"encoding") {
            let mut out = Vec::with_capacity(utf8.len() + 8);
            out.extend_from_slice(&utf8[..val_start]);
            out.extend_from_slice(b"utf-8");
            out.extend_from_slice(&utf8[val_end..]);
            return Some(out);
        }
    }
    None
}

fn sanitize_slice(buf: &[u8], is_eof: bool, mode: &mut SanitizeMode, out: &mut Vec<u8>) -> usize {
    let mut pos = 0_usize;
    while pos < buf.len() {
        match *mode {
            SanitizeMode::CData => {
                let rel = find_cdata_special(&buf[pos..]);
                out.extend_from_slice(&buf[pos..pos + rel]);
                pos += rel;
                if pos == buf.len() {
                    break;
                }
                let b = buf[pos];
                if b < 0x20 {
                    pos += 1;
                } else {
                    // b == b']'
                    if buf.len() - pos < 3 && !is_eof {
                        return pos;
                    }
                    if buf[pos..].starts_with(b"]]>") {
                        out.extend_from_slice(b"]]>");
                        *mode = SanitizeMode::Normal;
                        pos += 3;
                    } else {
                        out.push(b']');
                        pos += 1;
                    }
                }
            }
            SanitizeMode::Comment => {
                let rel = find_comment_special(&buf[pos..]);
                out.extend_from_slice(&buf[pos..pos + rel]);
                pos += rel;
                if pos == buf.len() {
                    break;
                }
                let b = buf[pos];
                if b < 0x20 {
                    pos += 1;
                } else {
                    // b == b'-'
                    if buf.len() - pos < 3 && !is_eof {
                        return pos;
                    }
                    if buf[pos..].starts_with(b"-->") {
                        out.extend_from_slice(b"-->");
                        *mode = SanitizeMode::Normal;
                        pos += 3;
                    } else {
                        out.push(b'-');
                        pos += 1;
                    }
                }
            }
            SanitizeMode::Normal => {
                let rel = find_normal_special(&buf[pos..]);
                out.extend_from_slice(&buf[pos..pos + rel]);
                pos += rel;
                if pos == buf.len() {
                    break;
                }
                let b = buf[pos];
                if b < 0x20 {
                    pos += 1;
                } else if b == b'<' {
                    let avail = &buf[pos..];
                    if !is_eof
                        && avail.len() < 9
                        && (b"<![CDATA[".starts_with(avail) || b"<!--".starts_with(avail))
                    {
                        return pos;
                    }
                    if avail.starts_with(b"<![CDATA[") {
                        out.extend_from_slice(b"<![CDATA[");
                        *mode = SanitizeMode::CData;
                        pos += 9;
                    } else if avail.starts_with(b"<!--") {
                        out.extend_from_slice(b"<!--");
                        *mode = SanitizeMode::Comment;
                        pos += 4;
                    } else {
                        out.push(b'<');
                        pos += 1;
                    }
                } else {
                    // b == b'&'
                    let avail = &buf[pos..];
                    let search_len = avail.len().min(MAX_ENTITY_LOOKAHEAD);
                    let window = &avail[1..search_len];
                    if let Some(semi_rel) = window.iter().position(|&c| c == b';') {
                        let name = &window[..semi_rel];
                        if let Some(replacement) = classify_entity(name) {
                            out.extend_from_slice(replacement.as_bytes());
                            pos += 2 + semi_rel;
                        } else if is_valid_xml_char_ref(name) || is_xml_predefined_entity(name) {
                            out.extend_from_slice(&avail[..2 + semi_rel]);
                            pos += 2 + semi_rel;
                        } else {
                            out.extend_from_slice(b"&amp;");
                            pos += 1;
                        }
                    } else {
                        if !is_eof
                            && avail.len() < MAX_ENTITY_LOOKAHEAD
                            && could_still_be_entity(window)
                        {
                            return pos;
                        }
                        out.extend_from_slice(b"&amp;");
                        pos += 1;
                    }
                }
            }
        }
    }
    pos
}

#[inline]
fn find_normal_special(slice: &[u8]) -> usize {
    let mut idx = 0;
    while idx < slice.len() {
        let next = memchr::memchr2(b'<', b'&', &slice[idx..]).unwrap_or(slice.len() - idx);
        if let Some(ctrl) = slice[idx..idx + next]
            .iter()
            .position(|&b| b < 0x20 && !matches!(b, b'\t' | b'\n' | b'\r'))
        {
            return idx + ctrl;
        }
        idx += next;
        if idx < slice.len() {
            return idx;
        }
    }
    slice.len()
}

#[inline]
fn find_cdata_special(slice: &[u8]) -> usize {
    let mut idx = 0;
    while idx < slice.len() {
        let next = memchr::memchr(b']', &slice[idx..]).unwrap_or(slice.len() - idx);
        if let Some(ctrl) = slice[idx..idx + next]
            .iter()
            .position(|&b| b < 0x20 && !matches!(b, b'\t' | b'\n' | b'\r'))
        {
            return idx + ctrl;
        }
        idx += next;
        if idx < slice.len() {
            return idx;
        }
    }
    slice.len()
}

#[inline]
fn find_comment_special(slice: &[u8]) -> usize {
    let mut idx = 0;
    while idx < slice.len() {
        let next = memchr::memchr(b'-', &slice[idx..]).unwrap_or(slice.len() - idx);
        if let Some(ctrl) = slice[idx..idx + next]
            .iter()
            .position(|&b| b < 0x20 && !matches!(b, b'\t' | b'\n' | b'\r'))
        {
            return idx + ctrl;
        }
        idx += next;
        if idx < slice.len() {
            return idx;
        }
    }
    slice.len()
}

#[inline]
fn could_still_be_entity(after_amp: &[u8]) -> bool {
    if after_amp.is_empty() {
        return true;
    }
    if after_amp[0] == b'#' {
        let rest = &after_amp[1..];
        if rest.is_empty() {
            return true;
        }
        if rest[0] == b'x' || rest[0] == b'X' {
            return rest[1..].iter().all(u8::is_ascii_hexdigit);
        }
        return rest.iter().all(u8::is_ascii_digit);
    }
    after_amp[0].is_ascii_alphabetic() && after_amp[1..].iter().all(u8::is_ascii_alphanumeric)
}

#[inline]
fn is_xml_predefined_entity(name: &[u8]) -> bool {
    matches!(name, b"amp" | b"lt" | b"gt" | b"quot" | b"apos")
}

fn is_valid_xml_char_ref(name: &[u8]) -> bool {
    let Some(rest) = name.strip_prefix(b"#") else {
        return false;
    };
    let scalar = if let Some(hex) = rest.strip_prefix(b"x").or_else(|| rest.strip_prefix(b"X")) {
        if hex.is_empty() || hex.len() > 6 || !hex.iter().all(u8::is_ascii_hexdigit) {
            return false;
        }
        let Ok(s) = std::str::from_utf8(hex) else {
            return false;
        };
        let Ok(v) = u32::from_str_radix(s, 16) else {
            return false;
        };
        v
    } else {
        if rest.is_empty() || rest.len() > 7 || !rest.iter().all(u8::is_ascii_digit) {
            return false;
        }
        let Ok(s) = std::str::from_utf8(rest) else {
            return false;
        };
        let Ok(v) = s.parse::<u32>() else {
            return false;
        };
        v
    };
    matches!(
        scalar,
        0x09 | 0x0A | 0x0D | 0x20..=0xD7FF | 0xE000..=0xFFFD | 0x10000..=0x10FFFF
    )
}

fn classify_entity(name: &[u8]) -> Option<&'static str> {
    if name.len() < 2 || name.len() > 9 || !name[0].is_ascii_alphabetic() {
        return None;
    }
    if !name[1..].iter().all(u8::is_ascii_alphanumeric) {
        return None;
    }
    match name {
        b"nbsp" => Some("\u{00a0}"),
        b"shy" => Some("\u{00ad}"),
        b"ensp" => Some("\u{2002}"),
        b"emsp" => Some("\u{2003}"),
        b"thinsp" => Some("\u{2009}"),
        b"mdash" => Some("\u{2014}"),
        b"ndash" => Some("\u{2013}"),
        b"minus" => Some("\u{2212}"),
        b"hellip" => Some("\u{2026}"),
        b"laquo" => Some("\u{00ab}"),
        b"raquo" => Some("\u{00bb}"),
        b"ldquo" => Some("\u{201c}"),
        b"rdquo" => Some("\u{201d}"),
        b"lsquo" => Some("\u{2018}"),
        b"rsquo" => Some("\u{2019}"),
        b"bdquo" => Some("\u{201e}"),
        b"sbquo" => Some("\u{201a}"),
        b"prime" => Some("\u{2032}"),
        b"copy" => Some("\u{00a9}"),
        b"reg" => Some("\u{00ae}"),
        b"trade" => Some("\u{2122}"),
        b"sect" => Some("\u{00a7}"),
        b"para" => Some("\u{00b6}"),
        b"deg" => Some("\u{00b0}"),
        b"plusmn" => Some("\u{00b1}"),
        b"times" => Some("\u{00d7}"),
        b"divide" => Some("\u{00f7}"),
        b"frac12" => Some("\u{00bd}"),
        b"frac14" => Some("\u{00bc}"),
        b"middot" => Some("\u{00b7}"),
        b"bull" => Some("\u{2022}"),
        b"dagger" => Some("\u{2020}"),
        b"euro" => Some("\u{20ac}"),
        b"pound" => Some("\u{00a3}"),
        b"numero" => Some("\u{2116}"),
        _ => None,
    }
}

// -----------------------------------------------------------------------------
// Fast Structural Scan (`scan_fb2_structure`)
// -----------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Fb2ByteRange {
    pub(crate) start: u64,
    pub(crate) end: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Fb2BodyInfo {
    pub(crate) index: u32,
    pub(crate) name: String,
    pub(crate) lang: String,
    pub(crate) title_preview: String,
    pub(crate) start_offset: u64,
    pub(crate) end_offset: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Fb2SectionInfo {
    pub(crate) index: u32,
    pub(crate) body_index: u32,
    pub(crate) parent_index: i32,
    pub(crate) depth: u32,
    pub(crate) id: String,
    pub(crate) lang: String,
    pub(crate) title_preview: String,
    pub(crate) start_offset: u64,
    pub(crate) end_offset: u64,
    pub(crate) estimated_end_elements: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Fb2BinaryInfo {
    pub(crate) id: String,
    pub(crate) content_type: String,
    pub(crate) start_offset: u64,
    pub(crate) payload_start_offset: u64,
    pub(crate) payload_end_offset: u64,
    pub(crate) end_offset: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Fb2Structure {
    pub(crate) detected_encoding: String,
    pub(crate) sanitized_byte_len: u64,
    pub(crate) book_lang: String,
    pub(crate) cover_id: String,
    pub(crate) root_tag_range: Option<Fb2ByteRange>,
    pub(crate) description_range: Option<Fb2ByteRange>,
    pub(crate) stylesheets: Vec<Fb2ByteRange>,
    pub(crate) bodies: Vec<Fb2BodyInfo>,
    pub(crate) sections: Vec<Fb2SectionInfo>,
    pub(crate) binaries: Vec<Fb2BinaryInfo>,
    pub(crate) max_section_depth_seen: u32,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum XmlSplitState {
    Text,
    Tag { quote: Option<u8> },
    Comment,
    CData,
    PiOrDecl,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum BlockScopeKind {
    Table,
    InlineBlock,
    Poem,
    Stanza,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum TagKind {
    Description,
    TitleInfo,
    SrcTitleInfo,
    DocumentInfo,
    Coverpage,
    Lang,
    Stylesheet(Option<u64>),
    Body(u32),
    Section(u32),
    Title(bool, bool),
    TitleParagraph,
    Binary,
    BlockScope(u64, BlockScopeKind),
    Other(u64),
}

impl TagKind {
    fn matches_close(self, other: Self) -> bool {
        match (self, other) {
            (Self::Description, Self::Description)
            | (Self::TitleInfo, Self::TitleInfo)
            | (Self::SrcTitleInfo, Self::SrcTitleInfo)
            | (Self::DocumentInfo, Self::DocumentInfo)
            | (Self::Coverpage, Self::Coverpage)
            | (Self::Lang, Self::Lang)
            | (Self::Stylesheet(_), Self::Stylesheet(_))
            | (Self::Body(_), Self::Body(_))
            | (Self::Section(_), Self::Section(_))
            | (Self::Title(_, _), Self::Title(_, _))
            | (Self::TitleParagraph, Self::TitleParagraph)
            | (Self::Binary, Self::Binary) => true,
            (Self::Other(a), Self::Other(b)) | (Self::BlockScope(a, _), Self::Other(b)) => a == b,
            _ => false,
        }
    }
}

#[inline]
fn tag_hash(local: &str) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    for &b in local.as_bytes() {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0100_0000_01b3);
    }
    hash
}

#[derive(Clone, Copy)]
enum TitleTarget {
    Body(usize),
    Section(usize),
}

struct TitleCapture {
    target: TitleTarget,
    parts: Vec<String>,
    current_p: Option<String>,
    last_was_space: bool,
    bare_text: String,
}

impl TitleCapture {
    fn start_p(&mut self) {
        self.current_p = Some(String::new());
        self.last_was_space = true;
    }

    fn end_p(&mut self) {
        if let Some(p) = self.current_p.take() {
            if !p.trim().is_empty() {
                self.parts.push(p);
            }
        }
        self.last_was_space = true;
    }

    fn add_empty_line(&mut self) {
        self.parts.push(String::new());
    }

    fn push_text(&mut self, raw: &str) {
        if let Some(p) = &mut self.current_p {
            append_folded_inline_text(p, raw, &mut self.last_was_space, MAX_TITLE_PREVIEW_BYTES);
        } else if self.parts.is_empty() {
            append_folded_inline_text(
                &mut self.bare_text,
                raw,
                &mut self.last_was_space,
                MAX_TITLE_PREVIEW_BYTES,
            );
        }
    }

    fn finish(self) -> String {
        let joined = if !self.parts.is_empty() {
            self.parts.join("\n")
        } else {
            self.bare_text
        };
        let trimmed = joined.trim();
        truncate_utf8(trimmed, MAX_TITLE_PREVIEW_BYTES).to_owned()
    }
}

struct ActiveBinary {
    id: String,
    content_type: String,
    start_offset: u64,
    payload_start_offset: u64,
}

/// Streaming structural scanner for FB2 books.
///
/// Uses `Fb2Sanitizer` + `quick-xml` to scan `<body>`, nested `<section>`
/// byte offsets, `<title>` previews, and `<binary id="...">` byte ranges in a
/// single bounded pass without decoding `<binary>` Base64 payloads.
pub(crate) struct Fb2StructureScanner {
    sanitizer: Fb2Sanitizer,
    sanitized_chunk: Vec<u8>,
    pending_xml: Vec<u8>,
    split_state: XmlSplitState,
    stream_offset: u64,
    tag_stack: Vec<TagKind>,
    body_stack: Vec<u32>,
    section_stack: Vec<u32>,
    in_title_info_depth: u32,
    in_ignored_info_depth: u32,
    in_coverpage_depth: u32,
    root_tag_range: Option<Fb2ByteRange>,
    description_start: Option<u64>,
    description_range: Option<Fb2ByteRange>,
    book_lang: String,
    in_book_lang: bool,
    cover_id: String,
    stylesheets: Vec<Fb2ByteRange>,
    bodies: Vec<Fb2BodyInfo>,
    sections: Vec<Fb2SectionInfo>,
    binaries: Vec<Fb2BinaryInfo>,
    active_title: Option<TitleCapture>,
    active_binary: Option<ActiveBinary>,
    max_section_depth_seen: u32,
    main_body_elements: u32,
    body0_title_depth: u32,
    body0_table_depth: u32,
    body0_inline_depth: u32,
    body0_poem_depth: u32,
    body0_stanza_depth: u32,
    aborted_tail: bool,
}

impl Fb2StructureScanner {
    pub(crate) fn new() -> Self {
        Self {
            sanitizer: Fb2Sanitizer::new(false),
            sanitized_chunk: Vec::new(),
            pending_xml: Vec::new(),
            split_state: XmlSplitState::Text,
            stream_offset: 0,
            tag_stack: Vec::with_capacity(32),
            body_stack: Vec::with_capacity(4),
            section_stack: Vec::with_capacity(16),
            in_title_info_depth: 0,
            in_ignored_info_depth: 0,
            in_coverpage_depth: 0,
            root_tag_range: None,
            description_start: None,
            description_range: None,
            book_lang: String::new(),
            in_book_lang: false,
            cover_id: String::new(),
            stylesheets: Vec::new(),
            bodies: Vec::new(),
            sections: Vec::new(),
            binaries: Vec::new(),
            active_title: None,
            active_binary: None,
            max_section_depth_seen: 0,
            main_body_elements: 0,
            body0_title_depth: 0,
            body0_table_depth: 0,
            body0_inline_depth: 0,
            body0_poem_depth: 0,
            body0_stanza_depth: 0,
            aborted_tail: false,
        }
    }

    pub(crate) fn feed(&mut self, chunk: &[u8], is_eof: bool) -> Result<(), String> {
        self.sanitized_chunk.clear();
        self.sanitizer
            .feed(chunk, is_eof, &mut self.sanitized_chunk)?;
        if self.sanitized_chunk.is_empty() && (!is_eof || self.pending_xml.is_empty()) {
            return Ok(());
        }

        let input = std::mem::take(&mut self.sanitized_chunk);
        let result = self.process_sanitized(&input, is_eof);
        self.sanitized_chunk = input;
        self.sanitized_chunk.clear();
        result
    }

    pub(crate) fn finish(mut self) -> Result<Fb2Structure, String> {
        if !self.pending_xml.is_empty() {
            let tail = std::mem::take(&mut self.pending_xml);
            let tail_str = std::str::from_utf8(&tail).unwrap_or("");
            if !self.aborted_tail {
                self.scan_slice(tail_str)?;
            }
            self.stream_offset += tail.len() as u64;
        }
        self.close_unterminated_at(self.stream_offset);
        Ok(Fb2Structure {
            detected_encoding: self.sanitizer.detected_encoding().to_owned(),
            sanitized_byte_len: self.stream_offset,
            book_lang: self.book_lang.trim().to_owned(),
            cover_id: self.cover_id,
            root_tag_range: self.root_tag_range,
            description_range: self.description_range,
            stylesheets: self.stylesheets,
            bodies: self.bodies,
            sections: self.sections,
            binaries: self.binaries,
            max_section_depth_seen: self.max_section_depth_seen,
        })
    }

    fn process_sanitized(&mut self, input: &[u8], is_eof: bool) -> Result<(), String> {
        if self.aborted_tail {
            self.stream_offset += input.len() as u64;
            return Ok(());
        }

        if self.pending_xml.is_empty() {
            if self.can_fast_skip(input) {
                self.stream_offset += input.len() as u64;
                return Ok(());
            }
            let cut = find_safe_xml_cut(input, &mut self.split_state, is_eof);
            if cut > 0 {
                let slice = std::str::from_utf8(&input[..cut])
                    .map_err(|_| "Sanitized FB2 chunk is not UTF-8".to_owned())?;
                self.scan_slice(slice)?;
                self.stream_offset += cut as u64;
            }
            if cut < input.len() {
                if input.len() - cut > MAX_TAG_CARRY_BYTES {
                    return Err("FB2 XML token exceeds size limit".into());
                }
                self.pending_xml.extend_from_slice(&input[cut..]);
            }
            return Ok(());
        }

        if self.pending_xml.len().saturating_add(input.len()) > MAX_TAG_CARRY_BYTES {
            return Err("FB2 XML token exceeds size limit".into());
        }
        let prev_len = self.pending_xml.len();
        self.pending_xml.extend_from_slice(input);
        let mut combined = std::mem::take(&mut self.pending_xml);
        let resume_from = match self.split_state {
            XmlSplitState::Text => 0,
            XmlSplitState::Tag { .. } => prev_len,
            XmlSplitState::Comment | XmlSplitState::CData => prev_len.saturating_sub(2),
            XmlSplitState::PiOrDecl => prev_len.saturating_sub(1),
        };
        let cut = find_safe_xml_cut_from(&combined, resume_from, &mut self.split_state, is_eof);
        if cut > 0 {
            let slice = std::str::from_utf8(&combined[..cut])
                .map_err(|_| "Sanitized FB2 chunk is not UTF-8".to_owned())?;
            self.scan_slice(slice)?;
            self.stream_offset += cut as u64;
        }
        if cut < combined.len() {
            combined.drain(..cut);
            self.pending_xml = combined;
        }
        Ok(())
    }

    #[inline]
    fn can_fast_skip(&self, input: &[u8]) -> bool {
        self.split_state == XmlSplitState::Text
            && self.active_title.is_none()
            && !self.in_book_lang
            && memchr::memchr(b'<', input).is_none()
    }

    fn scan_slice(&mut self, slice: &str) -> Result<(), String> {
        let base = self.stream_offset;
        let mut reader = Reader::from_str(slice);
        let cfg = reader.config_mut();
        cfg.allow_dangling_amp = true;
        cfg.allow_unmatched_ends = true;
        cfg.check_end_names = false;

        loop {
            let pos_before = base + reader.buffer_position();
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    let pos_after = base + reader.buffer_position();
                    self.handle_start(&tag, pos_before, pos_after, false)?;
                }
                Ok(Event::Empty(tag)) => {
                    let pos_after = base + reader.buffer_position();
                    self.handle_start(&tag, pos_before, pos_after, true)?;
                }
                Ok(Event::End(tag)) => {
                    let pos_after = base + reader.buffer_position();
                    let local = tag.local_name().into_inner();
                    self.handle_end(local, pos_before, pos_after);
                }
                Ok(Event::Text(text)) => {
                    if self.active_binary.is_none() {
                        if self.in_book_lang {
                            if self.book_lang.len() < 64 {
                                self.book_lang.push_str(&text);
                            }
                        } else if let Some(title) = &mut self.active_title {
                            title.push_text(&text);
                        }
                    }
                }
                Ok(Event::CData(text)) => {
                    if self.active_binary.is_none() {
                        if let Some(title) = &mut self.active_title {
                            title.push_text(&text);
                        }
                    }
                }
                Ok(Event::GeneralRef(reference)) => {
                    if self.active_binary.is_none() {
                        if let Some(title) = &mut self.active_title {
                            title.push_text(&resolve_ref(&reference));
                        } else if self.in_book_lang && self.book_lang.len() < 64 {
                            self.book_lang.push_str(&resolve_ref(&reference));
                        }
                    }
                }
                Ok(Event::Eof) => break,
                Err(_) => {
                    self.aborted_tail = true;
                    break;
                }
                _ => {}
            }
        }
        Ok(())
    }

    fn handle_start(
        &mut self,
        tag: &BytesStart<'_>,
        pos_before: u64,
        pos_after: u64,
        empty: bool,
    ) -> Result<(), String> {
        if !empty && self.tag_stack.len() >= MAX_XML_DEPTH {
            return Err(format!("FB2 XML depth exceeds {MAX_XML_DEPTH}"));
        }
        let local = tag.local_name().into_inner();

        // Inside `<binary>`, skip child structure and Base64 payload entirely.
        if self.active_binary.is_some() {
            if !empty {
                self.tag_stack.push(TagKind::Other(tag_hash(local)));
            }
            return Ok(());
        }

        let in_main_body = self.body_stack.as_slice() == [0];
        let kind = match local {
            "FictionBook" => {
                if !empty && self.root_tag_range.is_none() && self.tag_stack.is_empty() {
                    self.root_tag_range = Some(Fb2ByteRange {
                        start: pos_before,
                        end: pos_after,
                    });
                }
                TagKind::Other(tag_hash(local))
            }
            "description" => {
                if !empty && self.description_start.is_none() {
                    self.description_start = Some(pos_before);
                }
                TagKind::Description
            }
            "title-info" => {
                if !empty && self.in_ignored_info_depth == 0 {
                    self.in_title_info_depth += 1;
                }
                TagKind::TitleInfo
            }
            "src-title-info" | "document-info" => {
                if !empty {
                    self.in_ignored_info_depth += 1;
                }
                if local == "src-title-info" {
                    TagKind::SrcTitleInfo
                } else {
                    TagKind::DocumentInfo
                }
            }
            "coverpage" => {
                if !empty && self.in_title_info_depth > 0 && self.in_ignored_info_depth == 0 {
                    self.in_coverpage_depth += 1;
                }
                TagKind::Coverpage
            }
            "image" => {
                if self.in_coverpage_depth > 0 && self.cover_id.is_empty() {
                    if let Some(href) = find_attr_local(tag, "href") {
                        self.cover_id = href.strip_prefix('#').unwrap_or(&href).to_owned();
                    }
                }
                if in_main_body
                    && self.body0_title_depth == 0
                    && self.body0_table_depth == 0
                    && self.body0_inline_depth == 0
                    && self.body0_stanza_depth == 0
                {
                    self.main_body_elements = self.main_body_elements.saturating_add(1);
                }
                TagKind::Other(tag_hash(local))
            }
            "lang" => {
                if !empty
                    && self.in_title_info_depth > 0
                    && self.in_ignored_info_depth == 0
                    && self.book_lang.is_empty()
                {
                    self.in_book_lang = true;
                    TagKind::Lang
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "stylesheet" => {
                if !empty && self.body_stack.is_empty() && self.stylesheets.len() < MAX_STYLESHEETS
                {
                    let mime = find_attr_local(tag, "type").unwrap_or_default();
                    let main_type = mime.split(';').next().unwrap_or("").trim();
                    if main_type.eq_ignore_ascii_case("text/css") {
                        TagKind::Stylesheet(Some(pos_before))
                    } else {
                        TagKind::Stylesheet(None)
                    }
                } else {
                    TagKind::Stylesheet(None)
                }
            }
            "body" => {
                if self.bodies.len() >= MAX_BODIES {
                    return Err(format!("FB2 body count exceeds {MAX_BODIES}"));
                }
                let body_idx = self.bodies.len() as u32;
                let name = find_attr_local(tag, "name").unwrap_or_default();
                let lang = find_attr_local(tag, "lang").unwrap_or_default();
                self.bodies.push(Fb2BodyInfo {
                    index: body_idx,
                    name,
                    lang,
                    title_preview: String::new(),
                    start_offset: pos_before,
                    end_offset: pos_after,
                });
                if !empty {
                    self.body_stack.push(body_idx);
                }
                TagKind::Body(body_idx)
            }
            "section" => {
                if let Some(&body_index) = self.body_stack.last() {
                    if self.sections.len() >= MAX_SECTIONS {
                        return Err(format!("FB2 section count exceeds {MAX_SECTIONS}"));
                    }
                    let section_idx = self.sections.len() as u32;
                    let depth = self.section_stack.len() as u32;
                    self.max_section_depth_seen = self.max_section_depth_seen.max(depth + 1);
                    let parent_index = self.section_stack.last().map_or(-1, |&p| p as i32);
                    let id = find_attr_local(tag, "id").unwrap_or_default();
                    let lang = find_attr_local(tag, "lang").unwrap_or_default();
                    let initial_elements = if body_index == 0 {
                        self.main_body_elements
                    } else {
                        0
                    };
                    self.sections.push(Fb2SectionInfo {
                        index: section_idx,
                        body_index,
                        parent_index,
                        depth,
                        id,
                        lang,
                        title_preview: String::new(),
                        start_offset: pos_before,
                        end_offset: pos_after,
                        estimated_end_elements: initial_elements,
                    });
                    if !empty {
                        self.section_stack.push(section_idx);
                    }
                    TagKind::Section(section_idx)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "title" => {
                let target = match self.tag_stack.last().copied() {
                    Some(TagKind::Section(sec_idx))
                        if self.sections[sec_idx as usize].title_preview.is_empty() =>
                    {
                        Some(TitleTarget::Section(sec_idx as usize))
                    }
                    Some(TagKind::Body(body_idx))
                        if self.bodies[body_idx as usize].title_preview.is_empty() =>
                    {
                        Some(TitleTarget::Body(body_idx as usize))
                    }
                    _ => None,
                };
                let track_body0 = in_main_body && !empty;
                if track_body0 && self.body0_table_depth == 0 {
                    self.main_body_elements = self.main_body_elements.saturating_add(1);
                }
                if track_body0 {
                    self.body0_title_depth += 1;
                }
                if !empty && self.active_title.is_none() {
                    if let Some(target) = target {
                        self.active_title = Some(TitleCapture {
                            target,
                            parts: Vec::new(),
                            current_p: None,
                            last_was_space: true,
                            bare_text: String::new(),
                        });
                        TagKind::Title(true, track_body0)
                    } else {
                        TagKind::Title(false, track_body0)
                    }
                } else {
                    TagKind::Title(false, track_body0)
                }
            }
            "p" => {
                if let Some(title) = &mut self.active_title {
                    if empty {
                        TagKind::Other(tag_hash(local))
                    } else {
                        title.start_p();
                        TagKind::TitleParagraph
                    }
                } else if in_main_body && !empty {
                    if self.body0_title_depth == 0
                        && self.body0_table_depth == 0
                        && self.body0_inline_depth == 0
                    {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    self.body0_inline_depth += 1;
                    TagKind::BlockScope(tag_hash(local), BlockScopeKind::InlineBlock)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "subtitle" | "text-author" => {
                if in_main_body && !empty {
                    if self.body0_title_depth == 0
                        && self.body0_table_depth == 0
                        && self.body0_inline_depth == 0
                    {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    self.body0_inline_depth += 1;
                    TagKind::BlockScope(tag_hash(local), BlockScopeKind::InlineBlock)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "empty-line" => {
                if let Some(title) = &mut self.active_title {
                    title.add_empty_line();
                } else if in_main_body
                    && self.body0_title_depth == 0
                    && self.body0_table_depth == 0
                    && self.body0_inline_depth == 0
                {
                    self.main_body_elements = self.main_body_elements.saturating_add(1);
                }
                TagKind::Other(tag_hash(local))
            }
            "table" => {
                if in_main_body {
                    if self.body0_title_depth == 0 && self.body0_table_depth == 0 {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    if !empty {
                        self.body0_table_depth += 1;
                        TagKind::BlockScope(tag_hash(local), BlockScopeKind::Table)
                    } else {
                        TagKind::Other(tag_hash(local))
                    }
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "poem" => {
                if in_main_body && !empty {
                    self.body0_poem_depth += 1;
                    TagKind::BlockScope(tag_hash(local), BlockScopeKind::Poem)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "stanza" => {
                if in_main_body {
                    if self.body0_title_depth == 0 && self.body0_table_depth == 0 {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    if !empty {
                        self.body0_stanza_depth += 1;
                        TagKind::BlockScope(tag_hash(local), BlockScopeKind::Stanza)
                    } else {
                        TagKind::Other(tag_hash(local))
                    }
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "date" => {
                if in_main_body && !empty {
                    if self.body0_title_depth == 0
                        && self.body0_table_depth == 0
                        && self.body0_inline_depth == 0
                        && self.body0_poem_depth > 0
                        && self.body0_stanza_depth == 0
                    {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    self.body0_inline_depth += 1;
                    TagKind::BlockScope(tag_hash(local), BlockScopeKind::InlineBlock)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "style" => {
                if in_main_body && !empty {
                    if self.body0_title_depth == 0
                        && self.body0_table_depth == 0
                        && self.body0_inline_depth == 0
                        && self.body0_poem_depth == 0
                    {
                        self.main_body_elements = self.main_body_elements.saturating_add(1);
                    }
                    self.body0_inline_depth += 1;
                    TagKind::BlockScope(tag_hash(local), BlockScopeKind::InlineBlock)
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            "binary" => {
                if self.binaries.len() < MAX_BINARIES {
                    let id = find_attr_local(tag, "id").unwrap_or_default();
                    let content_type = find_attr_local(tag, "content-type").unwrap_or_default();
                    if empty {
                        self.binaries.push(Fb2BinaryInfo {
                            id,
                            content_type,
                            start_offset: pos_before,
                            payload_start_offset: pos_after,
                            payload_end_offset: pos_after,
                            end_offset: pos_after,
                        });
                        TagKind::Other(tag_hash(local))
                    } else {
                        self.active_binary = Some(ActiveBinary {
                            id,
                            content_type,
                            start_offset: pos_before,
                            payload_start_offset: pos_after,
                        });
                        TagKind::Binary
                    }
                } else {
                    TagKind::Other(tag_hash(local))
                }
            }
            _ => TagKind::Other(tag_hash(local)),
        };

        if !empty {
            self.tag_stack.push(kind);
        }
        Ok(())
    }

    fn handle_end(&mut self, local: &str, pos_before: u64, pos_after: u64) {
        if self.active_binary.is_some() {
            if local == "binary" {
                if let Some(pos) = self
                    .tag_stack
                    .iter()
                    .rposition(|k| matches!(k, TagKind::Binary))
                {
                    while self.tag_stack.len() > pos {
                        let popped = self.tag_stack.pop().unwrap_or(TagKind::Other(0));
                        if matches!(popped, TagKind::Binary) {
                            if let Some(bin) = self.active_binary.take() {
                                self.binaries.push(Fb2BinaryInfo {
                                    id: bin.id,
                                    content_type: bin.content_type,
                                    start_offset: bin.start_offset,
                                    payload_start_offset: bin.payload_start_offset,
                                    payload_end_offset: pos_before,
                                    end_offset: pos_after,
                                });
                            }
                        }
                    }
                }
            }
            return;
        }

        let target_kind = match local {
            "description" => TagKind::Description,
            "title-info" => TagKind::TitleInfo,
            "src-title-info" => TagKind::SrcTitleInfo,
            "document-info" => TagKind::DocumentInfo,
            "coverpage" => TagKind::Coverpage,
            "lang" if self.in_book_lang => TagKind::Lang,
            "stylesheet" => TagKind::Stylesheet(None),
            "body" => TagKind::Body(0),
            "section" if !self.section_stack.is_empty() => TagKind::Section(0),
            "title" => TagKind::Title(false, false),
            "p" if self.active_title.is_some() => TagKind::TitleParagraph,
            _ => TagKind::Other(tag_hash(local)),
        };

        let Some(pos) = self
            .tag_stack
            .iter()
            .rposition(|kind| kind.matches_close(target_kind))
        else {
            return;
        };

        while self.tag_stack.len() > pos {
            let is_direct = self.tag_stack.len() - 1 == pos;
            let close_end = if is_direct { pos_after } else { pos_before };
            let popped = self.tag_stack.pop().unwrap_or(TagKind::Other(0));
            self.close_tag(popped, close_end);
        }
    }

    fn close_tag(&mut self, kind: TagKind, end_offset: u64) {
        match kind {
            TagKind::Description => {
                if let Some(start) = self.description_start.take() {
                    if self.description_range.is_none() {
                        self.description_range = Some(Fb2ByteRange {
                            start,
                            end: end_offset,
                        });
                    }
                }
            }
            TagKind::TitleInfo => {
                self.in_title_info_depth = self.in_title_info_depth.saturating_sub(1);
            }
            TagKind::SrcTitleInfo | TagKind::DocumentInfo => {
                self.in_ignored_info_depth = self.in_ignored_info_depth.saturating_sub(1);
            }
            TagKind::Coverpage => {
                self.in_coverpage_depth = self.in_coverpage_depth.saturating_sub(1);
            }
            TagKind::Lang => {
                self.in_book_lang = false;
            }
            TagKind::Stylesheet(Some(start)) => {
                self.stylesheets.push(Fb2ByteRange {
                    start,
                    end: end_offset,
                });
            }
            TagKind::Body(body_idx) => {
                if self.body_stack.last().copied() == Some(body_idx) {
                    self.body_stack.pop();
                }
                if body_idx == 0 {
                    self.body0_title_depth = 0;
                    self.body0_table_depth = 0;
                    self.body0_inline_depth = 0;
                    self.body0_poem_depth = 0;
                    self.body0_stanza_depth = 0;
                }
                self.bodies[body_idx as usize].end_offset = end_offset;
            }
            TagKind::Section(sec_idx) => {
                if self.section_stack.last().copied() == Some(sec_idx) {
                    self.section_stack.pop();
                }
                let sec = &mut self.sections[sec_idx as usize];
                sec.end_offset = end_offset;
                if sec.body_index == 0 {
                    sec.estimated_end_elements = self.main_body_elements;
                }
            }
            TagKind::Title(capturing, track_body0) => {
                if track_body0 {
                    self.body0_title_depth = self.body0_title_depth.saturating_sub(1);
                }
                if capturing {
                    if let Some(capture) = self.active_title.take() {
                        let target = capture.target;
                        let text = capture.finish();
                        if !text.is_empty() {
                            match target {
                                TitleTarget::Body(idx) => self.bodies[idx].title_preview = text,
                                TitleTarget::Section(idx) => {
                                    self.sections[idx].title_preview = text
                                }
                            }
                        }
                    }
                }
            }
            TagKind::TitleParagraph => {
                if let Some(title) = &mut self.active_title {
                    title.end_p();
                }
            }
            TagKind::Binary => {
                if let Some(bin) = self.active_binary.take() {
                    self.binaries.push(Fb2BinaryInfo {
                        id: bin.id,
                        content_type: bin.content_type,
                        start_offset: bin.start_offset,
                        payload_start_offset: bin.payload_start_offset,
                        payload_end_offset: end_offset,
                        end_offset,
                    });
                }
            }
            TagKind::BlockScope(_, scope) => match scope {
                BlockScopeKind::Table => {
                    self.body0_table_depth = self.body0_table_depth.saturating_sub(1);
                }
                BlockScopeKind::InlineBlock => {
                    self.body0_inline_depth = self.body0_inline_depth.saturating_sub(1);
                }
                BlockScopeKind::Poem => {
                    self.body0_poem_depth = self.body0_poem_depth.saturating_sub(1);
                }
                BlockScopeKind::Stanza => {
                    self.body0_stanza_depth = self.body0_stanza_depth.saturating_sub(1);
                }
            },
            TagKind::Stylesheet(None) | TagKind::Other(_) => {}
        }
    }

    fn close_unterminated_at(&mut self, end_offset: u64) {
        while let Some(kind) = self.tag_stack.pop() {
            self.close_tag(kind, end_offset);
        }
    }
}

/// Convenience helper for scanning an in-memory FB2 buffer in one call.
pub(crate) fn scan_fb2_structure(bytes: &[u8]) -> Result<Fb2Structure, String> {
    let mut scanner = Fb2StructureScanner::new();
    scanner.feed(bytes, true)?;
    scanner.finish()
}

pub(crate) fn encode_structure_wire(structure: &Fb2Structure) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(256 + structure.sections.len() * 40);
    out.extend_from_slice(&FB2_SCAN_MAGIC.to_le_bytes());
    write_str(&mut out, &structure.detected_encoding)?;
    out.extend_from_slice(&structure.sanitized_byte_len.to_le_bytes());
    write_str(&mut out, &structure.book_lang)?;
    write_str(&mut out, &structure.cover_id)?;
    out.extend_from_slice(&structure.max_section_depth_seen.to_le_bytes());
    for range in [structure.root_tag_range, structure.description_range] {
        match range {
            Some(range) => {
                out.push(1);
                out.extend_from_slice(&range.start.to_le_bytes());
                out.extend_from_slice(&range.end.to_le_bytes());
            }
            None => out.push(0),
        }
    }

    out.extend_from_slice(&(structure.stylesheets.len() as u32).to_le_bytes());
    for range in &structure.stylesheets {
        out.extend_from_slice(&range.start.to_le_bytes());
        out.extend_from_slice(&range.end.to_le_bytes());
    }

    out.extend_from_slice(&(structure.bodies.len() as u32).to_le_bytes());
    for body in &structure.bodies {
        out.extend_from_slice(&body.index.to_le_bytes());
        write_str(&mut out, &body.name)?;
        write_str(&mut out, &body.lang)?;
        write_str(&mut out, &body.title_preview)?;
        out.extend_from_slice(&body.start_offset.to_le_bytes());
        out.extend_from_slice(&body.end_offset.to_le_bytes());
        if out.len() > MAX_WIRE_BYTES {
            return None;
        }
    }

    out.extend_from_slice(&(structure.sections.len() as u32).to_le_bytes());
    for sec in &structure.sections {
        out.extend_from_slice(&sec.index.to_le_bytes());
        out.extend_from_slice(&sec.body_index.to_le_bytes());
        out.extend_from_slice(&sec.parent_index.to_le_bytes());
        out.extend_from_slice(&sec.depth.to_le_bytes());
        write_str(&mut out, &sec.id)?;
        write_str(&mut out, &sec.lang)?;
        write_str(&mut out, &sec.title_preview)?;
        out.extend_from_slice(&sec.start_offset.to_le_bytes());
        out.extend_from_slice(&sec.end_offset.to_le_bytes());
        out.extend_from_slice(&sec.estimated_end_elements.to_le_bytes());
        if out.len() > MAX_WIRE_BYTES {
            return None;
        }
    }

    out.extend_from_slice(&(structure.binaries.len() as u32).to_le_bytes());
    for bin in &structure.binaries {
        write_str(&mut out, &bin.id)?;
        write_str(&mut out, &bin.content_type)?;
        out.extend_from_slice(&bin.start_offset.to_le_bytes());
        out.extend_from_slice(&bin.payload_start_offset.to_le_bytes());
        out.extend_from_slice(&bin.payload_end_offset.to_le_bytes());
        out.extend_from_slice(&bin.end_offset.to_le_bytes());
        if out.len() > MAX_WIRE_BYTES {
            return None;
        }
    }

    Some(out)
}

fn write_str(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let len = u32::try_from(value.len()).ok()?;
    if out.len() + 4 + value.len() > MAX_WIRE_BYTES {
        return None;
    }
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(value.as_bytes());
    Some(())
}

/// Finds the largest prefix ending at a complete XML token boundary so `quick-xml`
/// never observes a tag, comment, CDATA, or PI split across input chunks.
fn find_safe_xml_cut(input: &[u8], state: &mut XmlSplitState, is_eof: bool) -> usize {
    find_safe_xml_cut_from(input, 0, state, is_eof)
}

fn find_safe_xml_cut_from(
    input: &[u8],
    start_idx: usize,
    state: &mut XmlSplitState,
    is_eof: bool,
) -> usize {
    if is_eof {
        *state = XmlSplitState::Text;
        return input.len();
    }
    let mut last_safe = 0_usize;
    let mut idx = start_idx.min(input.len());
    while idx < input.len() {
        match *state {
            XmlSplitState::Text => {
                let Some(rel) = memchr::memchr(b'<', &input[idx..]) else {
                    last_safe = input.len();
                    break;
                };
                let lt = idx + rel;
                let avail = &input[lt..];
                if avail.len() < 9
                    && (b"<![CDATA[".starts_with(avail)
                        || b"<!--".starts_with(avail)
                        || b"<?".starts_with(avail))
                {
                    last_safe = lt;
                    break;
                }
                last_safe = lt;
                if avail.starts_with(b"<!--") {
                    *state = XmlSplitState::Comment;
                    idx = lt + 4;
                } else if avail.starts_with(b"<![CDATA[") {
                    *state = XmlSplitState::CData;
                    idx = lt + 9;
                } else if avail.starts_with(b"<?") {
                    *state = XmlSplitState::PiOrDecl;
                    idx = lt + 2;
                } else {
                    *state = XmlSplitState::Tag { quote: None };
                    idx = lt + 1;
                }
            }
            XmlSplitState::Tag { quote } => {
                if let Some(q) = quote {
                    if let Some(rel) = memchr::memchr(q, &input[idx..]) {
                        *state = XmlSplitState::Tag { quote: None };
                        idx += rel + 1;
                    } else {
                        break;
                    }
                } else if let Some(rel) = memchr::memchr3(b'"', b'\'', b'>', &input[idx..]) {
                    let b = input[idx + rel];
                    if b == b'>' {
                        *state = XmlSplitState::Text;
                        idx += rel + 1;
                        last_safe = idx;
                    } else {
                        *state = XmlSplitState::Tag { quote: Some(b) };
                        idx += rel + 1;
                    }
                } else {
                    break;
                }
            }
            XmlSplitState::Comment => {
                if let Some(rel) = input[idx..].windows(3).position(|w| w == b"-->") {
                    *state = XmlSplitState::Text;
                    idx += rel + 3;
                    last_safe = idx;
                } else {
                    break;
                }
            }
            XmlSplitState::CData => {
                if let Some(rel) = input[idx..].windows(3).position(|w| w == b"]]>") {
                    *state = XmlSplitState::Text;
                    idx += rel + 3;
                    last_safe = idx;
                } else {
                    break;
                }
            }
            XmlSplitState::PiOrDecl => {
                if let Some(rel) = input[idx..].windows(2).position(|w| w == b"?>") {
                    *state = XmlSplitState::Text;
                    idx += rel + 2;
                    last_safe = idx;
                } else {
                    break;
                }
            }
        }
    }
    last_safe
}

fn find_attr_local(tag: &BytesStart<'_>, wanted_local: &str) -> Option<String> {
    for attr in tag.html_attributes().with_checks(false).flatten() {
        let key = attr.key.local_name().into_inner();
        if key == wanted_local {
            return Some(decode_attr_value(&attr.value));
        }
    }
    None
}

fn decode_attr_value(raw: &str) -> String {
    if !raw.contains('&') {
        return raw.to_owned();
    }
    let mut parts = raw.split('&');
    let mut out = parts.next().unwrap_or("").to_owned();
    for part in parts {
        if let Some((name, rest)) = part.split_once(';') {
            out.push_str(&resolve_ref(&BytesRef::new(name)));
            out.push_str(rest);
        } else {
            out.push('&');
            out.push_str(part);
        }
    }
    out
}

fn resolve_ref(reference: &BytesRef<'_>) -> Cow<'static, str> {
    match reference.resolve_char_ref() {
        Ok(Some(ch)) => Cow::Owned(ch.to_string()),
        Err(_) => Cow::Borrowed("\u{fffd}"),
        Ok(None) => match resolve_predefined_entity(reference) {
            Some(val) => Cow::Borrowed(val),
            None => Cow::Owned(format!("&{reference};", reference = &**reference)),
        },
    }
}

fn append_folded_inline_text(
    out: &mut String,
    raw: &str,
    last_was_space: &mut bool,
    max_bytes: usize,
) {
    if out.len() >= max_bytes || raw.is_empty() {
        return;
    }
    for ch in raw.chars() {
        if matches!(ch, ' ' | '\t' | '\r' | '\n' | '\x0c') {
            if !*last_was_space {
                if out.len() >= max_bytes {
                    break;
                }
                out.push(' ');
                *last_was_space = true;
            }
        } else {
            if out.len() + ch.len_utf8() > max_bytes {
                break;
            }
            out.push(ch);
            *last_was_space = false;
        }
    }
}

fn truncate_utf8(s: &str, max_bytes: usize) -> &str {
    if s.len() <= max_bytes {
        return s;
    }
    let mut end = max_bytes;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

// -----------------------------------------------------------------------------
// Native Streaming FB2 Metadata & Cover Parser (`parse_fb2_metadata`)
// -----------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Fb2Metadata {
    pub(crate) title: Option<String>,
    pub(crate) authors: Vec<String>,
    pub(crate) genres: Vec<String>,
    pub(crate) series: Option<String>,
    pub(crate) series_number: Option<f32>,
    pub(crate) translators: Vec<String>,
    pub(crate) publisher: Option<String>,
    pub(crate) year: Option<String>,
    pub(crate) isbn: Option<String>,
    pub(crate) description: Option<String>,
    pub(crate) language: Option<String>,
    pub(crate) cover_id: Option<String>,
    pub(crate) cover_bytes: Option<Vec<u8>>,
    pub(crate) custom_info: Vec<(String, String)>,
}

#[derive(Clone, PartialEq, Eq)]
enum LeafField {
    BookTitle,
    Genre,
    FirstName,
    MiddleName,
    LastName,
    Nickname,
    TitleDate,
    Lang,
    Publisher,
    Year,
    Isbn,
    CustomInfo(String),
}

#[derive(Clone, PartialEq, Eq)]
enum MetaTagKind {
    Description,
    TitleInfo,
    PublishInfo,
    IgnoredInfo(u64),
    Coverpage,
    Person { is_translator: bool },
    Annotation,
    Leaf(u64, LeafField),
    CoverBinary,
    SkippedBinary,
    Body,
    Other(u64),
}

impl MetaTagKind {
    fn matches_close(&self, local: &str, local_hash: u64) -> bool {
        match self {
            Self::Description => local == "description",
            Self::TitleInfo => local == "title-info",
            Self::PublishInfo => local == "publish-info",
            Self::IgnoredInfo(h) => *h == local_hash,
            Self::Coverpage => local == "coverpage",
            Self::Person {
                is_translator: false,
            } => local == "author",
            Self::Person {
                is_translator: true,
            } => local == "translator",
            Self::Annotation => local == "annotation",
            Self::Leaf(h, _) => *h == local_hash,
            Self::CoverBinary | Self::SkippedBinary => local == "binary",
            Self::Body => local == "body",
            Self::Other(h) => *h == local_hash,
        }
    }
}

#[derive(Default)]
struct PersonCapture {
    first_name: Option<String>,
    middle_name: Option<String>,
    last_name: Option<String>,
    nickname: Option<String>,
}

impl PersonCapture {
    fn finish(self) -> Option<String> {
        let joined: String = [
            self.first_name.as_deref(),
            self.middle_name.as_deref(),
            self.last_name.as_deref(),
        ]
        .into_iter()
        .flatten()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
        let resolved = if !joined.is_empty() {
            joined
        } else {
            self.nickname
                .as_deref()
                .map(str::trim)
                .unwrap_or("")
                .to_owned()
        };
        if resolved.trim().is_empty() {
            None
        } else {
            Some(resolved)
        }
    }
}

struct AnnotationCapture {
    paragraphs: Vec<String>,
    current: String,
}

impl AnnotationCapture {
    fn new() -> Self {
        Self {
            paragraphs: Vec::new(),
            current: String::new(),
        }
    }

    fn push_text(&mut self, raw: &str) {
        if self.current.len() < MAX_METADATA_FIELD_BYTES {
            let remaining = MAX_METADATA_FIELD_BYTES - self.current.len();
            self.current.push_str(truncate_utf8(raw, remaining));
        }
    }

    fn flush_paragraph(&mut self) {
        if self.current.is_empty() {
            return;
        }
        let collapsed = collapse_and_trim_whitespace(&self.current);
        if !collapsed.is_empty() && self.paragraphs.len() < MAX_METADATA_ITEMS {
            self.paragraphs.push(collapsed);
        }
        self.current.clear();
    }

    fn finish(mut self) -> Option<String> {
        self.flush_paragraph();
        let joined = self.paragraphs.join("\n");
        if joined.trim().is_empty() {
            None
        } else {
            Some(joined)
        }
    }
}

struct CoverBinaryCapture {
    encoded: String,
    max_encoded_bytes: usize,
    oversized: bool,
    nested_depth: u32,
}

impl CoverBinaryCapture {
    fn new(max_cover_bytes: usize) -> Self {
        let max_encoded_bytes = max_encoded_base64_bytes(max_cover_bytes);
        Self {
            encoded: String::with_capacity(max_encoded_bytes.min(32 * 1024)),
            max_encoded_bytes,
            oversized: false,
            nested_depth: 0,
        }
    }

    fn push_text(&mut self, raw: &str) {
        if self.oversized || self.nested_depth > 0 {
            return;
        }
        if raw.len() > self.max_encoded_bytes.saturating_sub(self.encoded.len()) {
            self.oversized = true;
            self.encoded.clear();
        } else {
            self.encoded.push_str(raw);
        }
    }
}

/// Streaming `quick-xml` FB2 metadata & cover extractor running over `Fb2Sanitizer`.
///
/// Extracts `<title-info>`, `<publish-info>`, and `<custom-info>` metadata and
/// Base64-decodes only the matching `<binary id="coverId">` payload (bounded by
/// `max_cover_bytes`), skipping all `<body>` content and non-cover `<binary>` blocks.
pub(crate) struct Fb2MetadataParser {
    sanitizer: Fb2Sanitizer,
    sanitized_chunk: Vec<u8>,
    pending_xml: Vec<u8>,
    split_state: XmlSplitState,
    max_cover_bytes: usize,
    meta_stack: Vec<MetaTagKind>,
    description_depth: u32,
    title_info_depth: u32,
    publish_info_depth: u32,
    ignored_info_depth: u32,
    coverpage_depth: u32,
    body_depth: u32,
    skipped_binary_depth: u32,
    active_person: Option<PersonCapture>,
    active_annotation: Option<AnnotationCapture>,
    active_leaf_text: Option<String>,
    active_cover_binary: Option<CoverBinaryCapture>,
    title: Option<String>,
    authors: Vec<String>,
    genres: Vec<String>,
    series: Option<String>,
    series_number: Option<f32>,
    translators: Vec<String>,
    publisher: Option<String>,
    year: Option<String>,
    title_date: Option<String>,
    isbn: Option<String>,
    description: Option<String>,
    language: Option<String>,
    cover_id: Option<String>,
    cover_bytes: Option<Vec<u8>>,
    custom_info: Vec<(String, String)>,
    done: bool,
}

impl Fb2MetadataParser {
    pub(crate) fn new(max_cover_bytes: usize) -> Self {
        Self {
            sanitizer: Fb2Sanitizer::new(false),
            sanitized_chunk: Vec::new(),
            pending_xml: Vec::new(),
            split_state: XmlSplitState::Text,
            max_cover_bytes,
            meta_stack: Vec::with_capacity(32),
            description_depth: 0,
            title_info_depth: 0,
            publish_info_depth: 0,
            ignored_info_depth: 0,
            coverpage_depth: 0,
            body_depth: 0,
            skipped_binary_depth: 0,
            active_person: None,
            active_annotation: None,
            active_leaf_text: None,
            active_cover_binary: None,
            title: None,
            authors: Vec::new(),
            genres: Vec::new(),
            series: None,
            series_number: None,
            translators: Vec::new(),
            publisher: None,
            year: None,
            title_date: None,
            isbn: None,
            description: None,
            language: None,
            cover_id: None,
            cover_bytes: None,
            custom_info: Vec::new(),
            done: false,
        }
    }

    pub(crate) fn feed(&mut self, chunk: &[u8], is_eof: bool) -> Result<bool, String> {
        if self.done {
            return Ok(true);
        }
        self.sanitized_chunk.clear();
        self.sanitizer
            .feed(chunk, is_eof, &mut self.sanitized_chunk)?;
        if self.sanitized_chunk.is_empty() && (!is_eof || self.pending_xml.is_empty()) {
            return Ok(self.done);
        }

        let input = std::mem::take(&mut self.sanitized_chunk);
        let result = self.process_sanitized(&input, is_eof);
        self.sanitized_chunk = input;
        self.sanitized_chunk.clear();
        result?;
        Ok(self.done)
    }

    pub(crate) fn finish(mut self) -> Result<Fb2Metadata, String> {
        if !self.done && !self.pending_xml.is_empty() {
            let tail = std::mem::take(&mut self.pending_xml);
            if let Ok(tail_str) = std::str::from_utf8(&tail) {
                let _ = self.scan_slice(tail_str);
            }
        }
        if !self.done {
            self.close_unterminated();
        }
        let year = self.year.or_else(|| {
            self.title_date
                .as_deref()
                .and_then(extract_four_digit_year)
                .map(str::to_owned)
        });
        Ok(Fb2Metadata {
            title: self.title,
            authors: self.authors,
            genres: self.genres,
            series: self.series,
            series_number: self.series_number,
            translators: self.translators,
            publisher: self.publisher,
            year,
            isbn: self.isbn,
            description: self.description,
            language: self.language,
            cover_id: self.cover_id,
            cover_bytes: self.cover_bytes,
            custom_info: self.custom_info,
        })
    }

    fn process_sanitized(&mut self, mut input: &[u8], is_eof: bool) -> Result<(), String> {
        if self.done {
            return Ok(());
        }

        if self.pending_xml.is_empty() {
            // Fast-skip plain text / Base64 prefixes when inside `<body>` or a non-cover `<binary>`.
            if self.split_state == XmlSplitState::Text
                && (self.body_depth > 0 || self.skipped_binary_depth > 0)
                && self.active_cover_binary.is_none()
            {
                let Some(lt_pos) = memchr::memchr(b'<', input) else {
                    return Ok(());
                };
                input = &input[lt_pos..];
            }
            let cut = find_safe_xml_cut(input, &mut self.split_state, is_eof);
            if cut > 0 {
                let slice = std::str::from_utf8(&input[..cut])
                    .map_err(|_| "Sanitized FB2 metadata chunk is not UTF-8".to_owned())?;
                self.scan_slice(slice)?;
            }
            if !self.done && cut < input.len() {
                if input.len() - cut > MAX_TAG_CARRY_BYTES {
                    return Err("FB2 XML token exceeds size limit".into());
                }
                self.pending_xml.extend_from_slice(&input[cut..]);
            }
            return Ok(());
        }

        if self.pending_xml.len().saturating_add(input.len()) > MAX_TAG_CARRY_BYTES {
            return Err("FB2 XML token exceeds size limit".into());
        }
        let prev_len = self.pending_xml.len();
        self.pending_xml.extend_from_slice(input);
        let mut combined = std::mem::take(&mut self.pending_xml);
        let resume_from = match self.split_state {
            XmlSplitState::Text => 0,
            XmlSplitState::Tag { .. } => prev_len,
            XmlSplitState::Comment | XmlSplitState::CData => prev_len.saturating_sub(2),
            XmlSplitState::PiOrDecl => prev_len.saturating_sub(1),
        };
        let cut = find_safe_xml_cut_from(&combined, resume_from, &mut self.split_state, is_eof);
        if cut > 0 {
            let slice = std::str::from_utf8(&combined[..cut])
                .map_err(|_| "Sanitized FB2 metadata chunk is not UTF-8".to_owned())?;
            self.scan_slice(slice)?;
        }
        if !self.done && cut < combined.len() {
            combined.drain(..cut);
            self.pending_xml = combined;
        }
        Ok(())
    }

    fn scan_slice(&mut self, slice: &str) -> Result<(), String> {
        let mut reader = Reader::from_str(slice);
        let cfg = reader.config_mut();
        cfg.allow_dangling_amp = true;
        cfg.allow_unmatched_ends = true;
        cfg.check_end_names = false;

        while !self.done {
            let prev_pos = reader.buffer_position();
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    self.handle_start(&tag, false)?;
                }
                Ok(Event::Empty(tag)) => {
                    self.handle_start(&tag, true)?;
                }
                Ok(Event::End(tag)) => {
                    let local = tag.local_name().into_inner();
                    self.handle_end(local);
                }
                Ok(Event::Text(text)) => {
                    self.handle_text(&text);
                }
                Ok(Event::CData(text)) => {
                    self.handle_text(&text);
                }
                Ok(Event::GeneralRef(reference)) => {
                    let resolved = resolve_ref(&reference);
                    self.handle_text(&resolved);
                }
                Ok(Event::Eof) => break,
                Err(_) => {
                    if reader.buffer_position() <= prev_pos {
                        break;
                    }
                }
                _ => {}
            }
        }
        Ok(())
    }

    #[inline]
    fn in_title_info(&self) -> bool {
        self.title_info_depth > 0
            && self.ignored_info_depth == 0
            && self.body_depth == 0
            && self.skipped_binary_depth == 0
    }

    #[inline]
    fn in_publish_info(&self) -> bool {
        self.publish_info_depth > 0
            && self.ignored_info_depth == 0
            && self.body_depth == 0
            && self.skipped_binary_depth == 0
    }

    fn handle_text(&mut self, text: &str) {
        if let Some(cover) = &mut self.active_cover_binary {
            cover.push_text(text);
        } else if self.skipped_binary_depth > 0
            || self.body_depth > 0
            || self.ignored_info_depth > 0
        {
        } else if let Some(leaf) = &mut self.active_leaf_text {
            if leaf.len() < MAX_METADATA_FIELD_BYTES {
                let remaining = MAX_METADATA_FIELD_BYTES - leaf.len();
                leaf.push_str(truncate_utf8(text, remaining));
            }
        } else if let Some(ann) = &mut self.active_annotation {
            ann.push_text(text);
        }
    }

    fn handle_start(&mut self, tag: &BytesStart<'_>, empty: bool) -> Result<(), String> {
        if !empty && self.meta_stack.len() >= MAX_XML_DEPTH {
            return Err(format!("FB2 XML depth exceeds {MAX_XML_DEPTH}"));
        }
        let local = tag.local_name().into_inner();
        let local_hash = tag_hash(local);

        if let Some(cover) = &mut self.active_cover_binary {
            if !empty {
                cover.nested_depth += 1;
                self.meta_stack.push(MetaTagKind::Other(local_hash));
            }
            return Ok(());
        }

        if self.skipped_binary_depth > 0 {
            if !empty {
                self.meta_stack.push(MetaTagKind::Other(local_hash));
            }
            return Ok(());
        }

        // Top-level `<binary>` can follow an unclosed `<body>` in broken files.
        if local == "binary" {
            let id = find_attr_local(tag, "id");
            let matches_cover = self
                .cover_id
                .as_deref()
                .is_some_and(|cid| Some(cid) == id.as_deref());
            if matches_cover {
                if empty {
                    self.cover_bytes = Some(Vec::new());
                    self.done = true;
                } else {
                    self.active_cover_binary = Some(CoverBinaryCapture::new(self.max_cover_bytes));
                    self.meta_stack.push(MetaTagKind::CoverBinary);
                }
            } else if !empty {
                self.skipped_binary_depth += 1;
                self.meta_stack.push(MetaTagKind::SkippedBinary);
            }
            return Ok(());
        }

        if self.body_depth > 0 {
            if !empty {
                if local == "body" {
                    self.body_depth += 1;
                    self.meta_stack.push(MetaTagKind::Body);
                } else {
                    self.meta_stack.push(MetaTagKind::Other(local_hash));
                }
            }
            return Ok(());
        }

        if self.ignored_info_depth > 0 {
            if !empty {
                if matches!(local, "src-title-info" | "document-info") {
                    self.ignored_info_depth += 1;
                    self.meta_stack.push(MetaTagKind::IgnoredInfo(local_hash));
                } else {
                    self.meta_stack.push(MetaTagKind::Other(local_hash));
                }
            }
            return Ok(());
        }

        if let Some(ann) = &mut self.active_annotation {
            if empty {
                if local == "p" || local == "empty-line" {
                    ann.flush_paragraph();
                }
            } else {
                self.meta_stack.push(MetaTagKind::Other(local_hash));
            }
            return Ok(());
        }

        if self.active_leaf_text.is_some() {
            if !empty {
                self.meta_stack.push(MetaTagKind::Other(local_hash));
            }
            return Ok(());
        }

        let kind = match local {
            "description" => {
                if !empty {
                    self.description_depth += 1;
                }
                MetaTagKind::Description
            }
            "title-info" => {
                if !empty {
                    self.title_info_depth += 1;
                }
                MetaTagKind::TitleInfo
            }
            "publish-info" => {
                if !empty {
                    self.publish_info_depth += 1;
                }
                MetaTagKind::PublishInfo
            }
            "src-title-info" | "document-info" => {
                if !empty {
                    self.ignored_info_depth += 1;
                }
                MetaTagKind::IgnoredInfo(local_hash)
            }
            "body" => {
                if !empty {
                    self.body_depth += 1;
                }
                MetaTagKind::Body
            }
            "coverpage" if self.in_title_info() => {
                if !empty {
                    self.coverpage_depth += 1;
                }
                MetaTagKind::Coverpage
            }
            "image" if self.in_title_info() && self.coverpage_depth > 0 => {
                if self.cover_id.is_none() {
                    if let Some(href) = find_attr_local(tag, "href") {
                        self.cover_id = Some(href.strip_prefix('#').unwrap_or(&href).to_owned());
                    }
                }
                MetaTagKind::Other(local_hash)
            }
            "sequence"
                if (self.in_title_info() || self.in_publish_info()) && self.series.is_none() =>
            {
                if let Some(raw_name) = find_attr_local(tag, "name") {
                    let name = raw_name.trim();
                    if !name.is_empty() {
                        self.series = Some(name.to_owned());
                        self.series_number = find_attr_local(tag, "number")
                            .and_then(|s| s.trim().parse::<f32>().ok());
                    }
                }
                MetaTagKind::Other(local_hash)
            }
            "author" | "translator" if self.in_title_info() => {
                let is_translator = local == "translator";
                if !empty {
                    self.active_person = Some(PersonCapture::default());
                }
                MetaTagKind::Person { is_translator }
            }
            "first-name" if self.active_person.is_some() => {
                self.start_leaf(local_hash, LeafField::FirstName, empty)
            }
            "middle-name" if self.active_person.is_some() => {
                self.start_leaf(local_hash, LeafField::MiddleName, empty)
            }
            "last-name" if self.active_person.is_some() => {
                self.start_leaf(local_hash, LeafField::LastName, empty)
            }
            "nickname" if self.active_person.is_some() => {
                self.start_leaf(local_hash, LeafField::Nickname, empty)
            }
            "book-title" if self.in_title_info() => {
                self.start_leaf(local_hash, LeafField::BookTitle, empty)
            }
            "genre" if self.in_title_info() => self.start_leaf(local_hash, LeafField::Genre, empty),
            "annotation" if self.in_title_info() && self.description.is_none() => {
                if !empty {
                    self.active_annotation = Some(AnnotationCapture::new());
                }
                MetaTagKind::Annotation
            }
            "date" if self.in_title_info() && self.title_date.is_none() => {
                let attr_val = find_attr_local(tag, "value")
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty());
                if let Some(v) = attr_val {
                    self.title_date = Some(v);
                    MetaTagKind::Other(local_hash)
                } else {
                    self.start_leaf(local_hash, LeafField::TitleDate, empty)
                }
            }
            "lang" if self.in_title_info() && self.language.is_none() => {
                self.start_leaf(local_hash, LeafField::Lang, empty)
            }
            "publisher" if self.in_publish_info() && self.publisher.is_none() => {
                self.start_leaf(local_hash, LeafField::Publisher, empty)
            }
            "year" if self.in_publish_info() && self.year.is_none() => {
                self.start_leaf(local_hash, LeafField::Year, empty)
            }
            "isbn" if self.in_publish_info() && self.isbn.is_none() => {
                self.start_leaf(local_hash, LeafField::Isbn, empty)
            }
            "custom-info"
                if self.description_depth > 0
                    && !self.in_title_info()
                    && !self.in_publish_info() =>
            {
                let info_type = find_attr_local(tag, "info-type")
                    .map(|s| s.trim().to_owned())
                    .unwrap_or_default();
                self.start_leaf(local_hash, LeafField::CustomInfo(info_type), empty)
            }
            _ => MetaTagKind::Other(local_hash),
        };

        if !empty {
            self.meta_stack.push(kind);
        }
        Ok(())
    }

    fn start_leaf(&mut self, local_hash: u64, field: LeafField, empty: bool) -> MetaTagKind {
        if empty {
            self.commit_leaf(field, "");
            MetaTagKind::Other(local_hash)
        } else {
            self.active_leaf_text = Some(String::new());
            MetaTagKind::Leaf(local_hash, field)
        }
    }

    fn commit_leaf(&mut self, field: LeafField, raw_text: &str) {
        let trimmed = raw_text.trim();
        match field {
            LeafField::BookTitle => {
                self.title = Some(trimmed.to_owned());
            }
            LeafField::Genre => {
                if !trimmed.is_empty() && self.genres.len() < MAX_METADATA_ITEMS {
                    self.genres.push(trimmed.to_owned());
                }
            }
            LeafField::FirstName => {
                if let Some(p) = &mut self.active_person {
                    p.first_name = Some(trimmed.to_owned());
                }
            }
            LeafField::MiddleName => {
                if let Some(p) = &mut self.active_person {
                    p.middle_name = Some(trimmed.to_owned());
                }
            }
            LeafField::LastName => {
                if let Some(p) = &mut self.active_person {
                    p.last_name = Some(trimmed.to_owned());
                }
            }
            LeafField::Nickname => {
                if let Some(p) = &mut self.active_person {
                    p.nickname = Some(trimmed.to_owned());
                }
            }
            LeafField::TitleDate => {
                if self.title_date.is_none() && !trimmed.is_empty() {
                    self.title_date = Some(trimmed.to_owned());
                }
            }
            LeafField::Lang => {
                if self.language.is_none() && !trimmed.is_empty() {
                    self.language = Some(trimmed.to_owned());
                }
            }
            LeafField::Publisher => {
                if self.publisher.is_none() && !trimmed.is_empty() {
                    self.publisher = Some(trimmed.to_owned());
                }
            }
            LeafField::Year => {
                if self.year.is_none() && !trimmed.is_empty() {
                    self.year = Some(trimmed.to_owned());
                }
            }
            LeafField::Isbn => {
                if self.isbn.is_none() && !trimmed.is_empty() {
                    self.isbn = Some(trimmed.to_owned());
                }
            }
            LeafField::CustomInfo(info_type) => {
                if !trimmed.is_empty() && self.custom_info.len() < MAX_METADATA_ITEMS {
                    self.custom_info.push((info_type, trimmed.to_owned()));
                }
            }
        }
    }

    fn handle_end(&mut self, local: &str) {
        let local_hash = tag_hash(local);

        if self.active_annotation.is_some() && (local == "p" || local == "empty-line") {
            if let Some(ann) = &mut self.active_annotation {
                ann.flush_paragraph();
            }
        }

        let Some(pos) = self
            .meta_stack
            .iter()
            .rposition(|kind| kind.matches_close(local, local_hash))
        else {
            return;
        };

        while self.meta_stack.len() > pos {
            let popped = self.meta_stack.pop().unwrap();
            self.close_meta_tag(popped);
        }
    }

    fn close_meta_tag(&mut self, kind: MetaTagKind) {
        if let Some(cover) = &mut self.active_cover_binary {
            if !matches!(kind, MetaTagKind::CoverBinary) {
                cover.nested_depth = cover.nested_depth.saturating_sub(1);
                return;
            }
        }
        match kind {
            MetaTagKind::Description => {
                self.description_depth = self.description_depth.saturating_sub(1);
                if self.description_depth == 0 && self.cover_id.is_none() {
                    self.done = true;
                }
            }
            MetaTagKind::TitleInfo => {
                self.title_info_depth = self.title_info_depth.saturating_sub(1);
            }
            MetaTagKind::PublishInfo => {
                self.publish_info_depth = self.publish_info_depth.saturating_sub(1);
            }
            MetaTagKind::IgnoredInfo(_) => {
                self.ignored_info_depth = self.ignored_info_depth.saturating_sub(1);
            }
            MetaTagKind::Coverpage => {
                self.coverpage_depth = self.coverpage_depth.saturating_sub(1);
            }
            MetaTagKind::Person { is_translator } => {
                if let Some(person) = self.active_person.take() {
                    if let Some(name) = person.finish() {
                        if is_translator {
                            if self.translators.len() < MAX_METADATA_ITEMS {
                                self.translators.push(name);
                            }
                        } else if self.authors.len() < MAX_METADATA_ITEMS {
                            self.authors.push(name);
                        }
                    }
                }
            }
            MetaTagKind::Annotation => {
                if let Some(ann) = self.active_annotation.take() {
                    if self.description.is_none() {
                        self.description = ann.finish();
                    }
                }
            }
            MetaTagKind::Leaf(_, field) => {
                let raw = self.active_leaf_text.take().unwrap_or_default();
                self.commit_leaf(field, &raw);
            }
            MetaTagKind::CoverBinary => {
                if let Some(capture) = self.active_cover_binary.take() {
                    if !capture.oversized {
                        self.cover_bytes =
                            decode_mime_base64(capture.encoded.as_bytes(), self.max_cover_bytes);
                    }
                    self.done = true;
                }
            }
            MetaTagKind::SkippedBinary => {
                self.skipped_binary_depth = self.skipped_binary_depth.saturating_sub(1);
            }
            MetaTagKind::Body => {
                self.body_depth = self.body_depth.saturating_sub(1);
            }
            MetaTagKind::Other(_) => {}
        }
    }

    fn close_unterminated(&mut self) {
        while let Some(kind) = self.meta_stack.pop() {
            self.close_meta_tag(kind);
        }
    }
}

/// Convenience one-shot helper for extracting FB2 metadata and cover bytes.
pub(crate) fn parse_fb2_metadata(
    bytes: &[u8],
    max_cover_bytes: usize,
) -> Result<Fb2Metadata, String> {
    let mut parser = Fb2MetadataParser::new(max_cover_bytes);
    parser.feed(bytes, true)?;
    parser.finish()
}

pub(crate) fn encode_metadata_wire(meta: &Fb2Metadata) -> Option<Vec<u8>> {
    let cover_len = meta.cover_bytes.as_ref().map_or(0, Vec::len);
    let mut out = Vec::with_capacity(128 + cover_len);
    out.extend_from_slice(&FB2_META_MAGIC.to_le_bytes());
    write_opt_str(&mut out, meta.title.as_deref())?;
    write_str_list(&mut out, &meta.authors)?;
    write_str_list(&mut out, &meta.genres)?;
    write_opt_str(&mut out, meta.series.as_deref())?;
    match meta.series_number {
        Some(num) => {
            out.push(1);
            out.extend_from_slice(&num.to_le_bytes());
        }
        None => out.push(0),
    }
    write_str_list(&mut out, &meta.translators)?;
    write_opt_str(&mut out, meta.publisher.as_deref())?;
    write_opt_str(&mut out, meta.year.as_deref())?;
    write_opt_str(&mut out, meta.isbn.as_deref())?;
    write_opt_str(&mut out, meta.description.as_deref())?;
    write_opt_str(&mut out, meta.language.as_deref())?;
    write_opt_str(&mut out, meta.cover_id.as_deref())?;
    match &meta.cover_bytes {
        Some(bytes) => {
            let len = u32::try_from(bytes.len()).ok()?;
            if out.len() + 5 + bytes.len() > MAX_WIRE_BYTES {
                return None;
            }
            out.push(1);
            out.extend_from_slice(&len.to_le_bytes());
            out.extend_from_slice(bytes);
        }
        None => out.push(0),
    }
    let custom_count = u32::try_from(meta.custom_info.len()).ok()?;
    out.extend_from_slice(&custom_count.to_le_bytes());
    for (k, v) in &meta.custom_info {
        write_str(&mut out, k)?;
        write_str(&mut out, v)?;
    }
    Some(out)
}

fn write_opt_str(out: &mut Vec<u8>, value: Option<&str>) -> Option<()> {
    match value {
        Some(s) => {
            out.push(1);
            write_str(out, s)
        }
        None => {
            out.push(0);
            Some(())
        }
    }
}

fn write_str_list(out: &mut Vec<u8>, items: &[String]) -> Option<()> {
    let count = u32::try_from(items.len()).ok()?;
    out.extend_from_slice(&count.to_le_bytes());
    for item in items {
        write_str(out, item)?;
    }
    Some(())
}

pub(crate) fn max_encoded_base64_bytes(max_decoded_bytes: usize) -> usize {
    let whitespace = (1024 * 1024).min(max_decoded_bytes / 16 + 1024);
    max_decoded_bytes
        .saturating_add(max_decoded_bytes / 2)
        .saturating_add(whitespace)
        .min(i32::MAX as usize)
}

pub(crate) fn decode_mime_base64(raw: &[u8], max_decoded_bytes: usize) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity((raw.len() / 4 * 3).min(max_decoded_bytes).min(64 * 1024));
    let mut quantum = [0_u8; 4];
    let mut q_len = 0_usize;
    let mut pad_count = 0_usize;
    let mut eof_after_pad = false;

    for &b in raw {
        let val = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => {
                if eof_after_pad || q_len < 2 {
                    return None;
                }
                pad_count += 1;
                if q_len == 2 {
                    if pad_count == 2 {
                        if out.len() + 1 > max_decoded_bytes {
                            return None;
                        }
                        out.push((quantum[0] << 2) | (quantum[1] >> 4));
                        q_len = 0;
                        pad_count = 0;
                        eof_after_pad = true;
                    }
                } else if q_len == 3 {
                    if pad_count == 1 {
                        if out.len() + 2 > max_decoded_bytes {
                            return None;
                        }
                        out.push((quantum[0] << 2) | (quantum[1] >> 4));
                        out.push((quantum[1] << 4) | (quantum[2] >> 2));
                        q_len = 0;
                        pad_count = 0;
                        eof_after_pad = true;
                    } else {
                        return None;
                    }
                }
                continue;
            }
            b' ' | b'\t' | b'\r' | b'\n' => continue,
            _ => return None,
        };

        if eof_after_pad || pad_count > 0 {
            return None;
        }
        quantum[q_len] = val;
        q_len += 1;
        if q_len == 4 {
            if out.len() + 3 > max_decoded_bytes {
                return None;
            }
            out.push((quantum[0] << 2) | (quantum[1] >> 4));
            out.push((quantum[1] << 4) | (quantum[2] >> 2));
            out.push((quantum[2] << 6) | quantum[3]);
            q_len = 0;
        }
    }

    if pad_count != 0 {
        return None;
    }
    match q_len {
        0 => {}
        1 => return None,
        2 => {
            if out.len() + 1 > max_decoded_bytes {
                return None;
            }
            out.push((quantum[0] << 2) | (quantum[1] >> 4));
        }
        3 => {
            if out.len() + 2 > max_decoded_bytes {
                return None;
            }
            out.push((quantum[0] << 2) | (quantum[1] >> 4));
            out.push((quantum[1] << 4) | (quantum[2] >> 2));
        }
        _ => unreachable!(),
    }
    Some(out)
}

fn collapse_and_trim_whitespace(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_ws = false;
    for ch in raw.chars() {
        if matches!(ch, ' ' | '\t' | '\n' | '\x0b' | '\x0c' | '\r') {
            if !in_ws {
                out.push(' ');
                in_ws = true;
            }
        } else {
            out.push(ch);
            in_ws = false;
        }
    }
    out.trim().to_owned()
}

fn extract_four_digit_year(s: &str) -> Option<&str> {
    s.as_bytes()
        .windows(4)
        .position(|w| w.iter().all(u8::is_ascii_digit))
        .map(|i| &s[i..i + 4])
}

#[cfg(test)]
mod tests {
    use super::*;
    use encoding_rs::{ISO_8859_5, KOI8_R, WINDOWS_1251};

    #[test]
    fn detects_encodings_from_prolog_and_boms() {
        assert_eq!(
            detect_xml_encoding(b"<?xml version=\"1.0\" encoding=\"windows-1251\"?><FictionBook/>")
                .label,
            "windows-1251"
        );
        assert_eq!(
            detect_xml_encoding(b"<?xml version='1.0' encoding='koi8-r'?><FictionBook/>").label,
            "KOI8-R"
        );
        assert_eq!(
            detect_xml_encoding(b"<?xml version='1.0' encoding='iso-8859-5'?><FictionBook/>").label,
            "ISO-8859-5"
        );
        assert_eq!(
            detect_xml_encoding(b"\xff\xfe<\x00?\x00x\x00").label,
            "UTF-16LE"
        );
        assert_eq!(
            detect_xml_encoding(b"\xfe\xff\x00<\x00?\x00x").label,
            "UTF-16BE"
        );
        assert_eq!(
            detect_xml_encoding(b"<\x00?\x00x\x00m\x00").label,
            "UTF-16LE"
        );
        assert_eq!(
            detect_xml_encoding(b"\xef\xbb\xbf<?xml encoding='windows-1251'?>").label,
            "UTF-8"
        );
    }

    #[test]
    fn sanitizes_legacy_encodings_entities_control_chars_and_bare_ampersands_across_chunks() {
        let xml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\
            <FictionBook><stylesheet><![CDATA[a & b\x00]]></stylesheet>\
            <body><section><p>Тире&nbsp;&mdash; и &laquo;ёлочки&raquo;&hellip; \
            Тор & Локи\x01\x0b&ndash;&#160;&amp;&unknown;</p></section></body></FictionBook>";
        let (encoded, _, had_errors) = WINDOWS_1251.encode(xml);
        assert!(!had_errors);

        // Feed 7 bytes at a time to stress chunk-boundary carry handling.
        let mut sanitizer = Fb2Sanitizer::new(true);
        let mut out = Vec::new();
        for chunk in encoded.chunks(7) {
            sanitizer.feed(chunk, false, &mut out).unwrap();
        }
        sanitizer.feed(&[], true, &mut out).unwrap();

        let sanitized = std::str::from_utf8(&out).unwrap();
        assert!(sanitized.starts_with("<?xml version=\"1.0\" encoding=\"utf-8\"?>"));
        assert!(sanitized.contains("<![CDATA[a & b]]>"));
        assert!(
            sanitized.contains("Тире\u{00a0}— и «ёлочки»… Тор &amp; Локи–&#160;&amp;&amp;unknown;")
        );
    }

    #[test]
    fn decodes_koi8r_iso8859_5_and_utf16_via_sanitizer() {
        let koi_body = "<?xml version=\"1.0\" encoding=\"koi8-r\"?><FictionBook><body><section><p>Привет мир & тест</p></section></body></FictionBook>";
        let (koi_bytes, _, _) = KOI8_R.encode(koi_body);
        let koi_out = String::from_utf8(sanitize_fb2_bytes(&koi_bytes, true).unwrap()).unwrap();
        assert!(koi_out.contains("Привет мир &amp; тест"));

        let iso_body = "<?xml version=\"1.0\" encoding=\"iso-8859-5\"?><FictionBook><body><section><p>Здравствуй</p></section></body></FictionBook>";
        let (iso_bytes, _, _) = ISO_8859_5.encode(iso_body);
        let iso_out = String::from_utf8(sanitize_fb2_bytes(&iso_bytes, true).unwrap()).unwrap();
        assert!(iso_out.contains("Здравствуй"));

        let utf16_body = "<?xml version=\"1.0\" encoding=\"utf-16\"?><FictionBook><body><section><p>Юникод &amp; книга</p></section></body></FictionBook>";
        let mut utf16_bytes = b"\xff\xfe".to_vec();
        utf16_bytes.extend(utf16_body.encode_utf16().flat_map(u16::to_le_bytes));
        let utf16_out = String::from_utf8(sanitize_fb2_bytes(&utf16_bytes, true).unwrap()).unwrap();
        assert!(utf16_out.contains("Юникод &amp; книга"));
    }

    #[test]
    fn scan_fb2_structure_records_exact_offsets_nested_titles_and_binary_ranges() {
        let xml = concat!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">",
            "<stylesheet type=\"text/css\">p { color: #112233; }</stylesheet>",
            "<description><title-info><book-title>Test</book-title><coverpage><image l:href=\"#cov.png\"/></coverpage><lang>uk</lang></title-info></description>",
            "<body xml:lang=\"uk\">",
            "<title><p>Том первый</p></title>",
            "<section id=\"part1\">",
            "<title><p>Часть I</p><p>Подзаголовок</p></title>",
            "<section id=\"ch1\"><title><p>Глава&nbsp;1</p></title><poem><title><p>Not Section Title</p></title><stanza><v>Line</v></stanza></poem></section>",
            "<section id=\"ch2\"><p>Untitled scene</p></section>",
            "</section>",
            "</body>",
            "<body name=\"notes\"><section id=\"n1\"><p>Note</p></section></body>",
            "<binary id=\"cov.png\" content-type=\"image/png\">AAEC\nAw==</binary>",
            "<binary id=\"pic.jpg\" content-type=\"image/jpeg\">INVALID_BASE64_NOT_DECODED_AT_SCAN</binary>",
            "</FictionBook>",
        );

        let bytes = xml.as_bytes();
        // Feed in small arbitrary chunks to verify streaming scanner parity with one-shot scan.
        let mut scanner = Fb2StructureScanner::new();
        for chunk in bytes.chunks(13) {
            scanner.feed(chunk, false).unwrap();
        }
        scanner.feed(&[], true).unwrap();
        let streamed = scanner.finish().unwrap();
        let direct = scan_fb2_structure(bytes).unwrap();
        assert_eq!(streamed, direct);

        let sanitized_bytes = sanitize_fb2_bytes(bytes, false).unwrap();
        let sanitized_xml = std::str::from_utf8(&sanitized_bytes).unwrap();

        assert_eq!(direct.book_lang, "uk");
        assert_eq!(direct.cover_id, "cov.png");
        assert_eq!(direct.stylesheets.len(), 1);
        assert_eq!(
            &sanitized_xml
                [direct.stylesheets[0].start as usize..direct.stylesheets[0].end as usize],
            "<stylesheet type=\"text/css\">p { color: #112233; }</stylesheet>"
        );

        assert_eq!(direct.bodies.len(), 2);
        assert_eq!(direct.bodies[0].lang, "uk");
        assert_eq!(direct.bodies[0].title_preview, "Том первый");
        assert_eq!(direct.bodies[1].name, "notes");
        assert!(sanitized_xml
            [direct.bodies[0].start_offset as usize..direct.bodies[0].end_offset as usize]
            .starts_with("<body xml:lang=\"uk\">"));
        assert!(sanitized_xml
            [direct.bodies[0].start_offset as usize..direct.bodies[0].end_offset as usize]
            .ends_with("</body>"));

        assert_eq!(direct.sections.len(), 4);
        assert_eq!(direct.sections[0].depth, 0);
        assert_eq!(direct.sections[0].parent_index, -1);
        assert_eq!(direct.sections[0].title_preview, "Часть I\nПодзаголовок");
        assert_eq!(direct.sections[1].depth, 1);
        assert_eq!(direct.sections[1].parent_index, 0);
        // &nbsp; was sanitized to U+00A0 in the scanned UTF-8 stream, and <poem><title> did not override.
        assert_eq!(direct.sections[1].title_preview, "Глава\u{00a0}1");
        assert_eq!(direct.sections[2].title_preview, "");
        assert_eq!(direct.sections[3].body_index, 1);
        assert_eq!(direct.max_section_depth_seen, 2);

        let sec2 = &direct.sections[2];
        assert_eq!(
            &sanitized_xml[sec2.start_offset as usize..sec2.end_offset as usize],
            "<section id=\"ch2\"><p>Untitled scene</p></section>"
        );

        assert_eq!(direct.sections[1].estimated_end_elements, 5);
        assert_eq!(direct.sections[2].estimated_end_elements, 6);
        assert_eq!(direct.sections[0].estimated_end_elements, 6);
        assert_eq!(direct.sections[3].estimated_end_elements, 0);

        // <binary> payload was NOT Base64-decoded (the invalid payload succeeds cleanly) and offsets match.
        assert_eq!(direct.binaries.len(), 2);
        assert_eq!(direct.binaries[0].id, "cov.png");
        assert_eq!(direct.binaries[0].content_type, "image/png");
        assert_eq!(
            &sanitized_xml[direct.binaries[0].payload_start_offset as usize
                ..direct.binaries[0].payload_end_offset as usize],
            "AAEC\nAw=="
        );
        assert_eq!(
            &sanitized_xml
                [direct.binaries[1].start_offset as usize..direct.binaries[1].end_offset as usize],
            "<binary id=\"pic.jpg\" content-type=\"image/jpeg\">INVALID_BASE64_NOT_DECODED_AT_SCAN</binary>"
        );
    }

    #[test]
    fn scan_fb2_structure_closes_truncated_sections_cleanly() {
        let truncated =
            b"<?xml version=\"1.0\"?><FictionBook><body><section><title><p>Ch 1</p></title><p>Text";
        let structure = scan_fb2_structure(truncated).unwrap();
        assert_eq!(structure.bodies.len(), 1);
        assert_eq!(structure.sections.len(), 1);
        assert_eq!(structure.sections[0].title_preview, "Ch 1");
        assert_eq!(structure.sections[0].end_offset, truncated.len() as u64);
    }

    #[test]
    fn scan_fb2_structure_tracks_cumulative_element_estimates() {
        let xml = concat!(
            "<FictionBook>",
            "<body>",
            "<title><p>Book</p></title>",
            "<epigraph><p>Epi</p><text-author>Author</text-author></epigraph>",
            "<section id=\"s1\">",
            "<title><p>One</p></title>",
            "<p>Para <image l:href=\"#inline\"/></p>",
            "<empty-line/>",
            "<image l:href=\"#block\"/>",
            "<table><tr><td><p>Cell 1</p></td><td><p>Cell 2</p></td></tr></table>",
            "</section>",
            "<section id=\"s2\">",
            "<title><p>Two</p></title>",
            "<poem><stanza><v>L1</v><v>L2</v></stanza><date>2026</date></poem>",
            "</section>",
            "</body>",
            "<body name=\"notes\"><section id=\"n1\"><p>Note</p></section></body>",
            "</FictionBook>",
        );
        let structure = scan_fb2_structure(xml.as_bytes()).unwrap();
        assert_eq!(structure.sections.len(), 3);
        // body preamble: 1 (title) + 2 (epigraph p + text-author) = 3
        // s1: 1 (title) + 1 (p with inline image) + 1 (empty-line) + 1 (image) + 1 (table) = 5 -> cumulative 8
        assert_eq!(structure.sections[0].estimated_end_elements, 8);
        // s2: 1 (title) + 1 (stanza) + 1 (date) = 3 -> cumulative 11
        assert_eq!(structure.sections[1].estimated_end_elements, 11);
        // notes body does not advance main body element estimates
        assert_eq!(structure.sections[2].estimated_end_elements, 0);
    }

    #[test]
    fn parse_fb2_metadata_streams_all_fields_and_matching_cover_across_broken_legacy_xml() {
        let xml = concat!(
            "<?xml version=\"1.0\" encoding=\"windows-1251\"?>",
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">",
            "<description>",
            "<title-info>",
            "<genre>sf_fantasy</genre><genre>prose_classic</genre>",
            "<author><first-name>Стивен</first-name><middle-name>Эдвин</middle-name><last-name>Кинг</last-name></author>",
            "<author><nickname>Соавтор &amp; Друг</nickname></author>",
            "<book-title>Тёмная&nbsp;башня &mdash; &lt;Том 7&gt;</book-title>",
            "<annotation><p>Первый   абзац\n\tаннотации &amp; &lt;B&gt;.</p><empty-line/><p>Второй &laquo;абзац&raquo;.</p></annotation>",
            "<date value=\"2004\">2004</date>",
            "<coverpage><image l:href=\"#cover.png\"/></coverpage>",
            "<lang>ru</lang>",
            "<src-lang>en</src-lang>",
            "<translator><first-name>Виктор</first-name><last-name>Вебер</last-name></translator>",
            "<sequence name=\"Тёмная башня\" number=\"7.5\"/>",
            "</title-info>",
            "<src-title-info>",
            "<genre>foreign_sf</genre>",
            "<author><first-name>Stephen</first-name><last-name>King</last-name></author>",
            "<book-title>The Dark Tower</book-title>",
            "<annotation><p>Must not override.</p></annotation>",
            "<lang>en</lang>",
            "</src-title-info>",
            "<document-info>",
            "<author><nickname>scan-group</nickname></author>",
            "<date value=\"2026-08-22\">2026-08-22</date>",
            "</document-info>",
            "<publish-info>",
            "<publisher>АСТ</publisher>",
            "<year>2005</year>",
            "<isbn>5-17-030411-1</isbn>",
            "<sequence name=\"Издательская серия\" number=\"3\"/>",
            "</publish-info>",
            "<custom-info info-type=\"archive-source\">Lib.ru &amp; Flibusta</custom-info>",
            "</description>",
            "<body><section><p>Тор & Локи\x01 — сломанный текст в теле книги.</p></section></body>",
            "<binary id=\"other.bin\" content-type=\"image/png\">NOT_VALID_BASE64_!@#</binary>",
            "<binary id=\"cover.png\" content-type=\"image/png\">AAEC\nAw==</binary>",
            "<binary id=\"after.bin\" content-type=\"image/png\">IGNORED_AFTER_COVER_!@#</binary>",
            "</FictionBook>",
        );
        let (encoded, _, had_errors) = WINDOWS_1251.encode(xml);
        assert!(!had_errors);

        // Stream in 11-byte chunks to exercise boundary splitting and early stop after cover.
        let mut parser = Fb2MetadataParser::new(DEFAULT_MAX_COVER_BYTES);
        let mut stopped_early = false;
        for chunk in encoded.chunks(11) {
            if parser.feed(chunk, false).unwrap() {
                stopped_early = true;
                break;
            }
        }
        if !stopped_early {
            parser.feed(&[], true).unwrap();
        }
        assert!(
            stopped_early,
            "parser should finish right after </binary> of cover.png"
        );
        let streamed = parser.finish().unwrap();
        let direct = parse_fb2_metadata(&encoded, DEFAULT_MAX_COVER_BYTES).unwrap();
        assert_eq!(streamed, direct);

        assert_eq!(
            direct.title.as_deref(),
            Some("Тёмная\u{00a0}башня — <Том 7>")
        );
        assert_eq!(
            direct.authors,
            vec!["Стивен Эдвин Кинг".to_owned(), "Соавтор & Друг".to_owned()]
        );
        assert_eq!(
            direct.genres,
            vec!["sf_fantasy".to_owned(), "prose_classic".to_owned()]
        );
        assert_eq!(direct.series.as_deref(), Some("Тёмная башня"));
        assert_eq!(direct.series_number, Some(7.5));
        assert_eq!(direct.translators, vec!["Виктор Вебер".to_owned()]);
        assert_eq!(direct.publisher.as_deref(), Some("АСТ"));
        assert_eq!(direct.year.as_deref(), Some("2005"));
        assert_eq!(direct.isbn.as_deref(), Some("5-17-030411-1"));
        assert_eq!(
            direct.description.as_deref(),
            Some("Первый абзац аннотации & <B>.\nВторой «абзац».")
        );
        assert_eq!(direct.language.as_deref(), Some("ru"));
        assert_eq!(direct.cover_id.as_deref(), Some("cover.png"));
        assert_eq!(direct.cover_bytes.as_deref(), Some(&[0, 1, 2, 3][..]));
        assert_eq!(
            direct.custom_info,
            vec![("archive-source".to_owned(), "Lib.ru & Flibusta".to_owned())]
        );
    }

    #[test]
    fn parse_fb2_metadata_enforces_max_cover_bytes_and_falls_back_to_title_date_year() {
        let xml = concat!(
            "<FictionBook xmlns:l=\"http://www.w3.org/1999/xlink\">",
            "<description>",
            "<title-info>",
            "<book-title>1984</book-title>",
            "<date>написано в 1984 году</date>",
            "<coverpage><image l:href=\"#cov\"/></coverpage>",
            "</title-info>",
            "<publish-info><sequence name=\"Классика\" number=\"12\"/></publish-info>",
            "</description>",
            "<body><section><p>Body</p></section></body>",
            "<binary id=\"cov\" content-type=\"image/png\">AAECAwQF</binary>",
            "</FictionBook>",
        );
        let meta_ok = parse_fb2_metadata(xml.as_bytes(), 6).unwrap();
        assert_eq!(meta_ok.year.as_deref(), Some("1984"));
        assert_eq!(meta_ok.series.as_deref(), Some("Классика"));
        assert_eq!(meta_ok.series_number, Some(12.0));
        assert_eq!(
            meta_ok.cover_bytes.as_deref(),
            Some(&[0, 1, 2, 3, 4, 5][..])
        );

        // Cover payload (6 decoded bytes) exceeds max_cover_bytes = 3 -> cover_bytes must be None.
        let meta_capped = parse_fb2_metadata(xml.as_bytes(), 3).unwrap();
        assert_eq!(meta_capped.title.as_deref(), Some("1984"));
        assert_eq!(meta_capped.cover_id.as_deref(), Some("cov"));
        assert_eq!(meta_capped.cover_bytes, None);
    }

    #[test]
    fn decode_mime_base64_handles_valid_multiline_padding_limits_and_malformed_inputs() {
        // Empty and whitespace-only payloads decode to empty Vec even at limit 0.
        assert_eq!(decode_mime_base64(b"", 0), Some(Vec::new()));
        assert_eq!(decode_mime_base64(b" \t\r\n ", 0), Some(Vec::new()));
        assert_eq!(decode_mime_base64(b"AA==", 0), None);

        // Single-line and full-alphabet decoding.
        assert_eq!(decode_mime_base64(b"Zg==", 16), Some(b"f".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm8=", 16), Some(b"fo".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm9v", 16), Some(b"foo".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm9vYg==", 16), Some(b"foob".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm9vYmE=", 16), Some(b"fooba".to_vec()));
        assert_eq!(
            decode_mime_base64(b"Zm9vYmFy", 16),
            Some(b"foobar".to_vec())
        );
        assert_eq!(decode_mime_base64(b"+/8=", 16), Some(vec![0xfb, 0xff]));

        // Multi-line MIME Base64 with mixed whitespace and trailing whitespace.
        assert_eq!(
            decode_mime_base64(b"  Zm9v\r\n\tYmFy \n ", 16),
            Some(b"foobar".to_vec())
        );
        assert_eq!(
            decode_mime_base64(b"Zm\r\n9v\nYg=\n= \r\n", 16),
            Some(b"foob".to_vec())
        );

        // Unpadded trailing 2-char and 3-char quanta.
        assert_eq!(decode_mime_base64(b"Zg", 16), Some(b"f".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm8", 16), Some(b"fo".to_vec()));

        // Exact max_decoded_bytes boundary vs oversized.
        assert_eq!(decode_mime_base64(b"Zm9vYmFy", 6), Some(b"foobar".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm9vYmFy", 5), None);
        assert_eq!(decode_mime_base64(b"Zm8=", 2), Some(b"fo".to_vec()));
        assert_eq!(decode_mime_base64(b"Zm8=", 1), None);
        assert_eq!(decode_mime_base64(b"Zg==", 1), Some(b"f".to_vec()));

        // Malformed inputs: non-Base64 chars, trailing single quantum, invalid padding.
        for bad in [
            b"!".as_slice(),
            b"Zm9v!YmFy".as_slice(),
            b"NOT_VALID_BASE64_!@#".as_slice(),
            b"A".as_slice(),
            b"Zm9vA".as_slice(),
            b"=".as_slice(),
            b"A=".as_slice(),
            b"Zg=".as_slice(),
            b"Zg===".as_slice(),
            b"Zm8==".as_slice(),
            b"Zg==Zg==".as_slice(),
            b"Z=g=".as_slice(),
        ] {
            assert_eq!(
                decode_mime_base64(bad, 64),
                None,
                "expected None for malformed input {:?}",
                std::str::from_utf8(bad)
            );
        }
    }
}
