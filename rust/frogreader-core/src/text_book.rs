//! Native TXT/MD text decoding, control/empty validation, line normalization,
//! TXT paragraph splitting, and Markdown heading slug generation (M4.1).

use encoding_rs::WINDOWS_1251;
use std::collections::{HashMap, HashSet};
use std::io;
use std::path::Path;

pub(crate) const MAX_TEXT_BYTES: usize = 64 * 1024 * 1024;
pub(crate) const MAX_MARKDOWN_DEPTH: usize = 128;
const MAX_HEADING_COUNT: usize = 100_000;
const MAX_WIRE_BYTES: usize = 128 * 1024 * 1024;
const FRB_MODEL_DICTIONARY_LIMIT: usize = 4096;
const FRB_MODEL_SHORT_STRING_BYTES: usize = 128;
const PARAGRAPH_TYPE_NAME: &str = "com.example.frogreader.data.model.ContentElement.Paragraph";

pub(crate) const TEXT_MAGIC: u32 = 0x3154_5854; // TXT1
pub(crate) const SLUG_MAGIC: u32 = 0x3147_4C53; // SLG1
const STATUS_OK: u8 = 0;
const STATUS_ERR: u8 = 1;
const STATUS_FALLBACK: u8 = 2;

pub(crate) const MD_FRB_STATUS_OK: i32 = 0;
pub(crate) const MD_FRB_STATUS_ERR: i32 = 1;
pub(crate) const MD_FRB_STATUS_FALLBACK: i32 = 2;

const ERR_UTF32: &str = "UTF-32 text is not supported. Save the file as UTF-8.";
const ERR_BINARY: &str = "The file contains binary data, not readable text.";
const ERR_EMPTY: &str = "The text document is empty.";
const ERR_MD_NO_CONTENT: &str = "The Markdown document has no readable content.";

/// Decodes raw document bytes with strict BOM/UTF-8/UTF-16 validation and
/// un-BOMed Windows-1251 fallback, rejecting UTF-32, binary control bytes,
/// and blank text, then normalizes `\r\n`, `\r`, and `\u{000c}` to `\n`.
pub(crate) fn decode_text(bytes: &[u8]) -> Result<String, String> {
    if bytes.len() > MAX_TEXT_BYTES {
        return Err(format!("Text document exceeds {MAX_TEXT_BYTES} bytes"));
    }

    let decoded =
        if bytes.starts_with(b"\xff\xfe\x00\x00") || bytes.starts_with(b"\x00\x00\xfe\xff") {
            return Err(ERR_UTF32.into());
        } else if let Some(rest) = bytes.strip_prefix(b"\xef\xbb\xbf") {
            std::str::from_utf8(rest)
                .map(str::to_owned)
                .map_err(|_| "Invalid UTF-8 text".to_owned())?
        } else if let Some(rest) = bytes.strip_prefix(b"\xff\xfe") {
            decode_utf16_strict(rest, true).ok_or_else(|| "Invalid UTF-16LE text".to_owned())?
        } else if let Some(rest) = bytes.strip_prefix(b"\xfe\xff") {
            decode_utf16_strict(rest, false).ok_or_else(|| "Invalid UTF-16BE text".to_owned())?
        } else if let Ok(utf8) = std::str::from_utf8(bytes) {
            utf8.to_owned()
        } else {
            let (cow, had_errors) = WINDOWS_1251.decode_without_bom_handling(bytes);
            if had_errors {
                return Err("Invalid windows-1251 text".into());
            }
            cow.into_owned()
        };

    if decoded.chars().any(is_binary_control) {
        return Err(ERR_BINARY.into());
    }
    if decoded.chars().all(char::is_whitespace) {
        return Err(ERR_EMPTY.into());
    }

    Ok(normalize_line_endings(decoded))
}

/// Decodes raw TXT bytes and splits non-blank line groups into paragraph strings.
pub(crate) fn split_txt_paragraphs(bytes: &[u8]) -> Result<Vec<String>, String> {
    let text = decode_text(bytes)?;
    let mut paragraphs = Vec::new();
    let mut current = String::new();

    for line in text.split('\n') {
        if line.chars().all(char::is_whitespace) {
            if !current.is_empty() {
                paragraphs.push(std::mem::take(&mut current));
            }
        } else {
            if !current.is_empty() {
                current.push('\n');
            }
            current.push_str(line);
        }
    }
    if !current.is_empty() {
        paragraphs.push(current);
    }
    Ok(paragraphs)
}

/// Computes unique Markdown heading anchor slugs matching `TextBookParser`.
pub(crate) fn heading_slugs(headings: &[String]) -> Vec<String> {
    let mut used = HashSet::with_capacity(headings.len());
    let mut result = Vec::with_capacity(headings.len());
    for heading in headings {
        let base = heading_slug(heading);
        let mut id = base.clone();
        let mut suffix = 0_usize;
        while !used.insert(id.clone()) {
            suffix += 1;
            id = format!("{base}-{suffix}");
        }
        result.push(id);
    }
    result
}

pub(crate) fn heading_slug(raw: &str) -> String {
    let lower = raw.to_lowercase();
    let mut filtered = String::with_capacity(lower.len());
    for ch in lower.chars() {
        if is_slug_kept(ch) {
            filtered.push(ch);
        }
    }
    let trimmed = filtered.trim();
    let mut slug = String::with_capacity(trimmed.len());
    for ch in trimmed.chars() {
        if is_java_regex_whitespace(ch) {
            slug.push('-');
        } else {
            slug.push(ch);
        }
    }
    slug
}

pub(crate) fn decode_text_wire(bytes: &[u8]) -> Option<Vec<u8>> {
    match decode_text(bytes) {
        Ok(text) => {
            if text.len() > MAX_WIRE_BYTES - 9 {
                return None;
            }
            let mut out = Vec::with_capacity(9 + text.len());
            out.extend_from_slice(&TEXT_MAGIC.to_le_bytes());
            out.push(STATUS_OK);
            write_string(&mut out, &text);
            Some(out)
        }
        Err(msg) => Some(encode_error_wire(&msg)),
    }
}

pub(crate) fn parse_txt_paragraphs_wire(bytes: &[u8]) -> Option<Vec<u8>> {
    match split_txt_paragraphs(bytes) {
        Ok(paragraphs) => {
            let total_payload = paragraphs
                .iter()
                .try_fold(0_usize, |acc, p| acc.checked_add(4)?.checked_add(p.len()))?;
            if 9 + total_payload > MAX_WIRE_BYTES {
                return None;
            }
            let mut out = Vec::with_capacity(9 + total_payload);
            out.extend_from_slice(&TEXT_MAGIC.to_le_bytes());
            out.push(STATUS_OK);
            out.extend_from_slice(&(paragraphs.len() as u32).to_le_bytes());
            for paragraph in &paragraphs {
                write_string(&mut out, paragraph);
            }
            Some(out)
        }
        Err(msg) => Some(encode_error_wire(&msg)),
    }
}

pub(crate) fn heading_slugs_wire(input: &[u8]) -> Option<Vec<u8>> {
    let headings = decode_string_list(input)?;
    let slugs = heading_slugs(&headings);
    let total_payload = slugs
        .iter()
        .try_fold(0_usize, |acc, s| acc.checked_add(4)?.checked_add(s.len()))?;
    if 8 + total_payload > MAX_WIRE_BYTES {
        return None;
    }
    let mut out = Vec::with_capacity(8 + total_payload);
    out.extend_from_slice(&SLUG_MAGIC.to_le_bytes());
    out.extend_from_slice(&(slugs.len() as u32).to_le_bytes());
    for slug in &slugs {
        write_string(&mut out, slug);
    }
    Some(out)
}

pub(crate) fn render_markdown_html_wire(bytes: &[u8]) -> Option<Vec<u8>> {
    if bytes.len() > MAX_TEXT_BYTES {
        return None;
    }
    let Ok(markdown) = std::str::from_utf8(bytes) else {
        return Some(encode_error_wire("Invalid UTF-8 text"));
    };
    match render_markdown_html(markdown) {
        Ok(html) => encode_ok_bytes(html.as_bytes()),
        Err(msg) => Some(encode_error_wire(&msg)),
    }
}

/// Decodes raw Markdown file bytes, validates structure/depth, and returns the
/// first heading's plain text (or an empty string when no non-blank first heading
/// exists) wrapped in the `TXT1` wire envelope.
pub(crate) fn parse_markdown_title_wire(raw_bytes: &[u8]) -> Option<Vec<u8>> {
    let text = match decode_text(raw_bytes) {
        Ok(text) => text,
        Err(msg) => return Some(encode_error_wire(&msg)),
    };
    match markdown::extract_first_heading_title(&text) {
        Ok(Some(title)) => encode_ok_bytes(title.as_bytes()),
        Ok(None) => encode_ok_bytes(&[]),
        Err(msg) => Some(encode_error_wire(&msg)),
    }
}

/// Decodes raw Markdown file bytes, renders HTML, parses into `chapter_markup`
/// (`html_only = true`), and returns the `MRK2` snapshot wrapped in the `TXT1`
/// wire envelope (or an empty 0-byte payload when rendered HTML is blank).
pub(crate) fn parse_markdown_markup_wire(raw_bytes: &[u8]) -> Option<Vec<u8>> {
    let text = match decode_text(raw_bytes) {
        Ok(text) => text,
        Err(msg) => return Some(encode_error_wire(&msg)),
    };
    let html = match render_markdown_html(&text) {
        Ok(html) => html,
        Err(msg) => return Some(encode_error_wire(&msg)),
    };
    if html.chars().all(char::is_whitespace) {
        return encode_ok_bytes(&[]);
    }
    let doc = crate::chapter_markup::parse(&html, true)?;
    let mrk2 = crate::chapter_markup_jni::chapter_bytes(&doc)?;
    encode_ok_bytes(&mrk2)
}

/// Outcome of native Rust Markdown -> `html_mapper` -> `FrbBookContent` parsing.
pub(crate) enum MarkdownParseOutcome {
    Ok(Box<crate::frb_model::FrbBookContent>),
    Fallback,
    Err(String),
}

struct MarkdownCallbacks;

impl crate::chapter_markup::mapper::ResourceCallbacks for MarkdownCallbacks {
    fn resolve_image(&mut self, _src: &str) -> Option<String> {
        None
    }

    fn resolve_link(&mut self, href: &str) -> Option<String> {
        resolve_markdown_fragment_link(href)
    }
}

/// Matches `TextBookParser.kt`:
/// `if (href.startsWith('#')) runCatching { "#" + java.net.URI(href).fragment }.getOrDefault(href) else null`
fn resolve_markdown_fragment_link(href: &str) -> Option<String> {
    let raw_frag = href.strip_prefix('#')?;
    let bytes = raw_frag.as_bytes();
    let mut out = String::with_capacity(href.len());
    out.push('#');
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let mut pct_bytes = Vec::new();
            while i < bytes.len() && bytes[i] == b'%' {
                if i + 2 >= bytes.len() {
                    return Some(href.to_owned());
                }
                let Some(hi) = ascii_hex_nibble(bytes[i + 1]) else {
                    return Some(href.to_owned());
                };
                let Some(lo) = ascii_hex_nibble(bytes[i + 2]) else {
                    return Some(href.to_owned());
                };
                pct_bytes.push((hi << 4) | lo);
                i += 3;
            }
            out.push_str(&String::from_utf8_lossy(&pct_bytes));
        } else if bytes[i] < 0x80 {
            let b = bytes[i];
            if !is_java_uri_fragment_ascii(b) {
                return Some(href.to_owned());
            }
            out.push(b as char);
            i += 1;
        } else {
            let ch = raw_frag[i..].chars().next()?;
            if ch.is_control() || ch.is_whitespace() {
                return Some(href.to_owned());
            }
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    Some(out)
}

#[inline]
fn ascii_hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[inline]
fn is_java_uri_fragment_ascii(b: u8) -> bool {
    b.is_ascii_alphanumeric()
        || matches!(
            b,
            b'-' | b'_'
                | b'.'
                | b'!'
                | b'~'
                | b'*'
                | b'\''
                | b'('
                | b')'
                | b';'
                | b'/'
                | b'?'
                | b':'
                | b'@'
                | b'&'
                | b'='
                | b'+'
                | b'$'
                | b','
        )
}

/// Decodes raw Markdown bytes, renders HTML, parses into `chapter_markup`, and
/// maps directly into `FrbBookContent` via `html_mapper` without Jsoup.
/// Returns `Fallback` when the document uses complex markup (e.g. GFM `<table>`).
pub(crate) fn parse_markdown_book_content(raw_bytes: &[u8]) -> MarkdownParseOutcome {
    let text = match decode_text(raw_bytes) {
        Ok(text) => text,
        Err(msg) => return MarkdownParseOutcome::Err(msg),
    };
    let html = match render_markdown_html(&text) {
        Ok(html) => html,
        Err(msg) => return MarkdownParseOutcome::Err(msg),
    };
    if html
        .chars()
        .all(crate::chapter_markup::kotlin_is_whitespace)
    {
        return MarkdownParseOutcome::Err(ERR_MD_NO_CONTENT.to_owned());
    }
    let Some(doc) = crate::chapter_markup::parse(&html, true) else {
        return MarkdownParseOutcome::Fallback;
    };
    let Some(body_node) = doc.preindex.body_node else {
        return MarkdownParseOutcome::Err(ERR_MD_NO_CONTENT.to_owned());
    };
    let mut callbacks = MarkdownCallbacks;
    let options = crate::chapter_markup::mapper::MapOptions::default();
    let mapped = match crate::chapter_markup::mapper::map_document(
        &doc,
        body_node,
        None,
        &mut callbacks,
        &options,
    ) {
        Ok(mapped) => mapped,
        Err(crate::chapter_markup::mapper::MapError::ComplexMarkup(_))
        | Err(crate::chapter_markup::mapper::MapError::Internal(_)) => {
            return MarkdownParseOutcome::Fallback;
        }
    };
    if mapped.elements.is_empty() {
        return MarkdownParseOutcome::Err(ERR_MD_NO_CONTENT.to_owned());
    }
    let title = mapped.elements.iter().find_map(|el| match el {
        crate::frb_model::FrbContentElement::Heading { styled_text, .. } => {
            Some(styled_text.text.clone())
        }
        _ => None,
    });
    let mut navigation = Vec::new();
    for (index, element) in mapped.elements.iter().enumerate() {
        if let crate::frb_model::FrbContentElement::Heading {
            styled_text, level, ..
        } = element
        {
            navigation.push(crate::frb_model::FrbNavigationEntry {
                title: styled_text.text.clone(),
                depth: level - 1,
                target: crate::frb_model::FrbNavigationTarget::ReadingOrder {
                    chapter_index: 0,
                    element_index: index as i32,
                },
            });
        }
    }
    let elem_len = mapped.elements.len();
    let mut link_targets = std::collections::BTreeMap::new();
    for (id, index) in mapped.anchors {
        if index >= 0 && (index as usize) < elem_len {
            link_targets.insert(format!("#{id}"), (0, index));
        }
    }
    let language = detect_language_from_elements(&mapped.elements).map(str::to_owned);
    let chapter = crate::frb_model::FrbChapter {
        title,
        elements: mapped.elements,
        depth: 0,
        publisher_boxes: mapped.publisher_boxes,
    };
    MarkdownParseOutcome::Ok(Box::new(crate::frb_model::FrbBookContent {
        chapters: vec![chapter],
        navigation,
        link_targets,
        language,
        ..crate::frb_model::FrbBookContent::default()
    }))
}

/// Parses Markdown directly into `FrbModelCodec`-encoded `BookContent` bytes
/// wrapped in the `TXT1` wire envelope (`STATUS_OK`, `STATUS_ERR`, or
/// `STATUS_FALLBACK` when GFM tables or complex markup require Kotlin fallback).
pub(crate) fn parse_markdown_content_wire(raw_bytes: &[u8]) -> Option<Vec<u8>> {
    match parse_markdown_book_content(raw_bytes) {
        MarkdownParseOutcome::Ok(content) => {
            let payload = crate::frb_model::encode_book_content(&content);
            encode_ok_bytes(&payload)
        }
        MarkdownParseOutcome::Fallback => Some(encode_fallback_wire()),
        MarkdownParseOutcome::Err(msg) => Some(encode_error_wire(&msg)),
    }
}

/// Parses Markdown directly in Rust and writes a complete, CRC-checked `.frb` v2
/// container via `frb_model::write_book_frb` with zero JVM `BookContent` allocation.
/// Returns `MD_FRB_STATUS_OK` (0), `MD_FRB_STATUS_ERR` (1), or `MD_FRB_STATUS_FALLBACK` (2).
pub(crate) fn write_md_frb(source_bytes: &[u8], dest_path: &Path, source_metadata: &[u8]) -> i32 {
    if dest_path.as_os_str().is_empty() || dest_path.exists() {
        return MD_FRB_STATUS_ERR;
    }
    let content = match parse_markdown_book_content(source_bytes) {
        MarkdownParseOutcome::Ok(content) => content,
        MarkdownParseOutcome::Fallback => return MD_FRB_STATUS_FALLBACK,
        MarkdownParseOutcome::Err(_) => return MD_FRB_STATUS_ERR,
    };
    if let Some(parent) = dest_path.parent() {
        if !parent.as_os_str().is_empty() && std::fs::create_dir_all(parent).is_err() {
            return MD_FRB_STATUS_ERR;
        }
    }
    match crate::frb_model::write_book_frb(
        &content,
        dest_path,
        source_metadata,
        crate::frb::MAX_FILE_BYTES,
    ) {
        Ok(()) => MD_FRB_STATUS_OK,
        Err(_) => {
            let _ = std::fs::remove_file(dest_path);
            MD_FRB_STATUS_ERR
        }
    }
}

fn encode_fallback_wire() -> Vec<u8> {
    let mut out = Vec::with_capacity(5);
    out.extend_from_slice(&TEXT_MAGIC.to_le_bytes());
    out.push(STATUS_FALLBACK);
    out
}

fn detect_language_from_elements(
    elements: &[crate::frb_model::FrbContentElement],
) -> Option<&'static str> {
    let mut sample_u16_len = 0_usize;
    let mut letters = 0_usize;
    let mut cyrillic = 0_usize;
    let mut arabic = 0_usize;
    let mut hebrew = 0_usize;
    let mut ukrainian = 0_usize;

    for element in elements {
        let text = match element {
            crate::frb_model::FrbContentElement::Paragraph { text, .. } => text.text.as_str(),
            crate::frb_model::FrbContentElement::Heading { styled_text, .. } => {
                styled_text.text.as_str()
            }
            _ => continue,
        };
        for ch in text.chars() {
            sample_u16_len += ch.len_utf16();
            if (ch as u32) <= 0xFFFF && ch.is_alphabetic() {
                letters += 1;
                if ('\u{0400}'..='\u{04FF}').contains(&ch) {
                    cyrillic += 1;
                }
                if ('\u{0600}'..='\u{06FF}').contains(&ch)
                    || ('\u{0750}'..='\u{077F}').contains(&ch)
                    || ('\u{08A0}'..='\u{08FF}').contains(&ch)
                    || ('\u{FB50}'..='\u{FDFF}').contains(&ch)
                    || ('\u{FE70}'..='\u{FEFF}').contains(&ch)
                {
                    arabic += 1;
                }
                if ('\u{0590}'..='\u{05FF}').contains(&ch)
                    || ('\u{FB1D}'..='\u{FB4F}').contains(&ch)
                {
                    hebrew += 1;
                }
                if matches!(ch, 'і' | 'ї' | 'є' | 'ґ' | 'І' | 'Ї' | 'Є' | 'Ґ') {
                    ukrainian += 1;
                }
            }
        }
        sample_u16_len += 1;
        if sample_u16_len >= 2000 {
            break;
        }
    }

    if letters < 40 {
        return None;
    }
    let threshold = letters * 6;
    if arabic * 10 >= threshold && arabic >= hebrew && arabic >= cyrillic {
        Some("ar")
    } else if hebrew * 10 >= threshold && hebrew >= arabic && hebrew >= cyrillic {
        Some("he")
    } else if cyrillic * 10 >= threshold {
        Some(if ukrainian >= 3 { "uk" } else { "ru" })
    } else {
        None
    }
}

fn encode_ok_bytes(payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() > MAX_WIRE_BYTES - 9 {
        return None;
    }
    let mut out = Vec::with_capacity(9 + payload.len());
    out.extend_from_slice(&TEXT_MAGIC.to_le_bytes());
    out.push(STATUS_OK);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    Some(out)
}

/// Parses CommonMark + GFM tables + strikethrough in pure Rust and renders HTML
/// matching `TextBookParser`'s configuration (`MAX_MARKDOWN_DEPTH = 128`, HTML
/// escaping, URL scheme sanitization, image-to-alt rendering, and heading slugs).
pub(crate) fn render_markdown_html(markdown: &str) -> Result<String, String> {
    markdown::render_markdown_html_impl(markdown)
}

/// Decodes raw TXT bytes, splits paragraphs, and writes a complete, CRC-checked
/// `.frb` v2 (revision 5) container directly via `crate::frb::Writer` without
/// materializing `ContentElement.Paragraph` lists in JVM.
pub(crate) fn write_txt_frb(
    source_bytes: &[u8],
    dest_path: &Path,
    source_metadata: &[u8],
    language: Option<&str>,
) -> Result<(), String> {
    if dest_path.as_os_str().is_empty() {
        return Err("Destination path is empty".into());
    }
    if dest_path.exists() {
        return Err("Destination FRB path already exists".into());
    }
    let paragraphs = split_txt_paragraphs(source_bytes)?;
    let count = i32::try_from(paragraphs.len())
        .map_err(|_| "TXT paragraph count exceeds i32::MAX".to_owned())?;
    let detected_lang = if language.is_none() {
        detect_language_from_paragraphs(&paragraphs)
    } else {
        None
    };
    let effective_lang = language.filter(|s| !s.is_empty()).or(detected_lang);

    let block0 = encode_txt_book_index_frb(count, effective_lang);
    if block0.len() as u64 > crate::frb::MAX_INDEX_BYTES {
        return Err("TXT BookIndex block exceeds limit".into());
    }
    let block1 = encode_txt_book_globals_frb(effective_lang);
    let block2 = encode_txt_chapter_frb(&paragraphs)?;

    if let Some(parent) = dest_path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
    }

    let mut writer = crate::frb::Writer::begin(
        dest_path,
        source_metadata,
        &[count],
        crate::frb::MAX_FILE_BYTES,
    )
    .map_err(|e| e.to_string())?;

    let write_res = (|| -> io::Result<()> {
        for (idx, payload) in [&block0, &block1, &block2].into_iter().enumerate() {
            writer.write_block_bytes(idx, payload)?;
        }
        writer.finish()
    })();

    if let Err(err) = write_res {
        let _ = std::fs::remove_file(dest_path);
        return Err(err.to_string());
    }
    Ok(())
}

/// Matches `LanguageTag.detectFromChapters` for standalone TXT paragraphs.
pub(crate) fn detect_language_from_paragraphs(paragraphs: &[String]) -> Option<&'static str> {
    let mut sample_u16_len = 0_usize;
    let mut letters = 0_usize;
    let mut cyrillic = 0_usize;
    let mut arabic = 0_usize;
    let mut hebrew = 0_usize;
    let mut ukrainian = 0_usize;

    for paragraph in paragraphs {
        for ch in paragraph.chars() {
            sample_u16_len += ch.len_utf16();
            if (ch as u32) <= 0xFFFF && ch.is_alphabetic() {
                letters += 1;
                if ('\u{0400}'..='\u{04FF}').contains(&ch) {
                    cyrillic += 1;
                }
                if ('\u{0600}'..='\u{06FF}').contains(&ch)
                    || ('\u{0750}'..='\u{077F}').contains(&ch)
                    || ('\u{08A0}'..='\u{08FF}').contains(&ch)
                    || ('\u{FB50}'..='\u{FDFF}').contains(&ch)
                    || ('\u{FE70}'..='\u{FEFF}').contains(&ch)
                {
                    arabic += 1;
                }
                if ('\u{0590}'..='\u{05FF}').contains(&ch)
                    || ('\u{FB1D}'..='\u{FB4F}').contains(&ch)
                {
                    hebrew += 1;
                }
                if matches!(ch, 'і' | 'ї' | 'є' | 'ґ' | 'І' | 'Ї' | 'Є' | 'Ґ') {
                    ukrainian += 1;
                }
            }
        }
        sample_u16_len += 1; // space appended between paragraphs
        if sample_u16_len >= 2000 {
            break;
        }
    }

    if letters < 40 {
        return None;
    }
    let threshold = letters * 6;
    if arabic * 10 >= threshold && arabic >= hebrew && arabic >= cyrillic {
        Some("ar")
    } else if hebrew * 10 >= threshold && hebrew >= arabic && hebrew >= cyrillic {
        Some("he")
    } else if cyrillic * 10 >= threshold {
        Some(if ukrainian >= 3 { "uk" } else { "ru" })
    } else {
        None
    }
}

struct FrbBlockEncoder<'a> {
    out: Vec<u8>,
    strings: HashMap<&'a str, usize>,
}

impl<'a> FrbBlockEncoder<'a> {
    fn with_capacity(capacity: usize) -> Self {
        Self {
            out: Vec::with_capacity(capacity),
            strings: HashMap::new(),
        }
    }

    #[inline]
    fn write_unsigned(&mut self, mut bits: u64) {
        while (bits >> 7) != 0 {
            self.out.push(((bits as u8) & 0x7f) | 0x80);
            bits >>= 7;
        }
        self.out.push(bits as u8);
    }

    #[inline]
    fn write_i32(&mut self, value: i32) {
        let v = i64::from(value);
        let zigzag = ((v << 1) ^ (v >> 63)) as u64;
        self.write_unsigned(zigzag);
    }

    #[inline]
    fn write_string(&mut self, value: &'a str) {
        let bytes = value.as_bytes();
        if (1..=FRB_MODEL_SHORT_STRING_BYTES).contains(&bytes.len()) {
            if let Some(&existing) = self.strings.get(value) {
                self.write_unsigned(((existing as u64) << 1) | 1);
                return;
            }
        }
        self.write_unsigned((bytes.len() as u64) << 1);
        self.out.extend_from_slice(bytes);
        if (1..=FRB_MODEL_SHORT_STRING_BYTES).contains(&bytes.len())
            && self.strings.len() < FRB_MODEL_DICTIONARY_LIMIT
        {
            let next_idx = self.strings.len();
            self.strings.insert(value, next_idx);
        }
    }
}

fn encode_txt_book_index_frb(element_count: i32, language: Option<&str>) -> Vec<u8> {
    let mut enc = FrbBlockEncoder::with_capacity(64);
    // field 1: chapters: List<BookChapterIndex> (size = 1)
    enc.write_unsigned(1);
    enc.write_unsigned(1);
    // BookChapterIndex(title = null, depth = 0, elementCount = element_count, startElement = 0)
    enc.write_unsigned(1); // title
    enc.out.push(0); // null
    enc.write_unsigned(2); // depth
    enc.write_i32(0);
    enc.write_unsigned(3); // elementCount
    enc.write_i32(element_count);
    enc.write_unsigned(4); // startElement
    enc.write_i32(0);
    enc.out.push(0); // end BookChapterIndex
                     // field 2: navigation = emptyList()
    enc.write_unsigned(2);
    enc.write_unsigned(0);
    // field 3: linkTargets = emptyMap()
    enc.write_unsigned(3);
    enc.write_unsigned(0);
    // field 4: linkedDocumentTargets = emptyMap()
    enc.write_unsigned(4);
    enc.write_unsigned(0);
    // field 5: fonts = emptyList()
    enc.write_unsigned(5);
    enc.write_unsigned(0);
    // field 6: language: String?
    enc.write_unsigned(6);
    match language {
        Some(lang) => {
            enc.out.push(1);
            enc.write_string(lang);
        }
        None => enc.out.push(0),
    }
    // field 7: pageProgression = PageProgression.DEFAULT (ordinal 0)
    enc.write_unsigned(7);
    enc.write_unsigned(0);
    // field 8: totalElements = element_count
    enc.write_unsigned(8);
    enc.write_i32(element_count);
    // field 9: resourcePaths = emptyList()
    enc.write_unsigned(9);
    enc.write_unsigned(0);
    // field 10 (publisherStyle = null) & field 11 (epubSourceRanges = emptyList()) omitted
    enc.out.push(0); // end BookIndex
    enc.out
}

fn encode_txt_book_globals_frb(language: Option<&str>) -> Vec<u8> {
    let mut enc = FrbBlockEncoder::with_capacity(32);
    // field 1: chapters: List<Chapter> (emptyList())
    enc.write_unsigned(1);
    enc.write_unsigned(0);
    // field 5: language: String? = null (only encoded when Some)
    if let Some(lang) = language {
        enc.write_unsigned(5);
        enc.out.push(1);
        enc.write_string(lang);
    }
    enc.out.push(0); // end BookContent
    enc.out
}

fn encode_txt_chapter_frb(paragraphs: &[String]) -> Result<Vec<u8>, String> {
    let estimated = paragraphs.iter().map(|p| p.len() + 12).sum::<usize>() + 96;
    let mut enc = FrbBlockEncoder::with_capacity(estimated.min(16 * 1024 * 1024));
    // field 1: title: String? (null)
    enc.write_unsigned(1);
    enc.out.push(0);
    // field 2: elements: List<ContentElement>
    enc.write_unsigned(2);
    enc.write_unsigned(paragraphs.len() as u64);
    for paragraph in paragraphs {
        // Polymorphic ContentElement record
        enc.write_unsigned(1); // type name tag
        enc.write_string(PARAGRAPH_TYPE_NAME);
        enc.write_unsigned(2); // typed value tag (ContentElement.Paragraph)
        enc.write_unsigned(1); // Paragraph.text tag (ParsedText)
        enc.write_unsigned(1); // ParsedText.text tag
        enc.write_string(paragraph);
        enc.out.push(0); // end ParsedText
        enc.out.push(0); // end ContentElement.Paragraph
        enc.out.push(0); // end Polymorphic ContentElement
        if enc.out.len() as u64 > crate::frb::MAX_BLOCK_BYTES {
            return Err("TXT chapter block exceeds FRB block size limit".into());
        }
    }
    enc.out.push(0); // end Chapter
    if enc.out.len() as u64 > crate::frb::MAX_BLOCK_BYTES {
        return Err("TXT chapter block exceeds FRB block size limit".into());
    }
    Ok(enc.out)
}

fn encode_error_wire(message: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + message.len());
    out.extend_from_slice(&TEXT_MAGIC.to_le_bytes());
    out.push(STATUS_ERR);
    write_string(&mut out, message);
    out
}

fn write_string(out: &mut Vec<u8>, value: &str) {
    out.extend_from_slice(&(value.len() as u32).to_le_bytes());
    out.extend_from_slice(value.as_bytes());
}

fn decode_string_list(mut input: &[u8]) -> Option<Vec<String>> {
    if input.len() < 4 {
        return None;
    }
    let count = u32::from_le_bytes(input[..4].try_into().ok()?) as usize;
    input = &input[4..];
    if count > MAX_HEADING_COUNT || count > input.len() / 4 {
        return None;
    }
    let mut list = Vec::with_capacity(count);
    for _ in 0..count {
        if input.len() < 4 {
            return None;
        }
        let len = u32::from_le_bytes(input[..4].try_into().ok()?) as usize;
        input = &input[4..];
        if len > input.len() {
            return None;
        }
        let s = std::str::from_utf8(&input[..len]).ok()?.to_owned();
        input = &input[len..];
        list.push(s);
    }
    if !input.is_empty() {
        return None;
    }
    Some(list)
}

fn decode_utf16_strict(bytes: &[u8], little_endian: bool) -> Option<String> {
    let chunks = bytes.chunks_exact(2);
    if !chunks.remainder().is_empty() {
        return None;
    }
    let code_units = chunks.map(|pair| {
        let pair = [pair[0], pair[1]];
        if little_endian {
            u16::from_le_bytes(pair)
        } else {
            u16::from_be_bytes(pair)
        }
    });
    let mut out = String::with_capacity(bytes.len() / 2);
    for ch in char::decode_utf16(code_units) {
        out.push(ch.ok()?);
    }
    Some(out)
}

#[inline]
fn is_binary_control(ch: char) -> bool {
    (ch < ' ' && !matches!(ch, '\n' | '\r' | '\t' | '\u{000c}'))
        || ('\u{007f}'..='\u{009f}').contains(&ch)
}

fn normalize_line_endings(input: String) -> String {
    let bytes = input.as_bytes();
    let Some(first) = memchr::memchr2(b'\r', 0x0c, bytes) else {
        return input;
    };
    let mut out = Vec::with_capacity(bytes.len());
    out.extend_from_slice(&bytes[..first]);
    let mut pos = first;
    while pos < bytes.len() {
        let slice = &bytes[pos..];
        if let Some(rel) = memchr::memchr2(b'\r', 0x0c, slice) {
            out.extend_from_slice(&slice[..rel]);
            let idx = pos + rel;
            if bytes[idx] == b'\r' {
                out.push(b'\n');
                pos = if bytes.get(idx + 1) == Some(&b'\n') {
                    idx + 2
                } else {
                    idx + 1
                };
            } else {
                out.push(b'\n');
                pos = idx + 1;
            }
        } else {
            out.extend_from_slice(slice);
            break;
        }
    }
    // Replacing ASCII `\r` and `\x0c` with ASCII `\n` preserves UTF-8 validity.
    String::from_utf8(out).unwrap_or(input)
}

#[inline]
fn is_java_regex_whitespace(ch: char) -> bool {
    matches!(ch, ' ' | '\t' | '\n' | '\x0b' | '\x0c' | '\r')
}

#[inline]
fn is_slug_kept(ch: char) -> bool {
    if ch.is_ascii() {
        return ch.is_ascii_alphanumeric()
            || matches!(ch, '_' | '-')
            || is_java_regex_whitespace(ch);
    }
    let cp = ch as u32;
    NON_ASCII_LNM_RANGES
        .binary_search_by(|&(start, end)| {
            if cp < start {
                std::cmp::Ordering::Greater
            } else if cp > end {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

// Unicode General Categories L (Letter), N (Number), M (Mark) for U+0080..=U+10FFFF.
// Matches Java regex `\p{L}\p{N}\p{M}` across all Unicode scalar values.
const NON_ASCII_LNM_RANGES: &[(u32, u32)] = &[
    (0x00AA, 0x00AA),
    (0x00B2, 0x00B3),
    (0x00B5, 0x00B5),
    (0x00B9, 0x00BA),
    (0x00BC, 0x00BE),
    (0x00C0, 0x00D6),
    (0x00D8, 0x00F6),
    (0x00F8, 0x02C1),
    (0x02C6, 0x02D1),
    (0x02E0, 0x02E4),
    (0x02EC, 0x02EC),
    (0x02EE, 0x02EE),
    (0x0300, 0x0374),
    (0x0376, 0x0377),
    (0x037A, 0x037D),
    (0x037F, 0x037F),
    (0x0386, 0x0386),
    (0x0388, 0x038A),
    (0x038C, 0x038C),
    (0x038E, 0x03A1),
    (0x03A3, 0x03F5),
    (0x03F7, 0x0481),
    (0x0483, 0x052F),
    (0x0531, 0x0556),
    (0x0559, 0x0559),
    (0x0560, 0x0588),
    (0x0591, 0x05BD),
    (0x05BF, 0x05BF),
    (0x05C1, 0x05C2),
    (0x05C4, 0x05C5),
    (0x05C7, 0x05C7),
    (0x05D0, 0x05EA),
    (0x05EF, 0x05F2),
    (0x0610, 0x061A),
    (0x0620, 0x0669),
    (0x066E, 0x06D3),
    (0x06D5, 0x06DC),
    (0x06DF, 0x06E8),
    (0x06EA, 0x06FC),
    (0x06FF, 0x06FF),
    (0x0710, 0x074A),
    (0x074D, 0x07B1),
    (0x07C0, 0x07F5),
    (0x07FA, 0x07FA),
    (0x07FD, 0x07FD),
    (0x0800, 0x082D),
    (0x0840, 0x085B),
    (0x0860, 0x086A),
    (0x0870, 0x0887),
    (0x0889, 0x088E),
    (0x0897, 0x08E1),
    (0x08E3, 0x0963),
    (0x0966, 0x096F),
    (0x0971, 0x0983),
    (0x0985, 0x098C),
    (0x098F, 0x0990),
    (0x0993, 0x09A8),
    (0x09AA, 0x09B0),
    (0x09B2, 0x09B2),
    (0x09B6, 0x09B9),
    (0x09BC, 0x09C4),
    (0x09C7, 0x09C8),
    (0x09CB, 0x09CE),
    (0x09D7, 0x09D7),
    (0x09DC, 0x09DD),
    (0x09DF, 0x09E3),
    (0x09E6, 0x09F1),
    (0x09F4, 0x09F9),
    (0x09FC, 0x09FC),
    (0x09FE, 0x09FE),
    (0x0A01, 0x0A03),
    (0x0A05, 0x0A0A),
    (0x0A0F, 0x0A10),
    (0x0A13, 0x0A28),
    (0x0A2A, 0x0A30),
    (0x0A32, 0x0A33),
    (0x0A35, 0x0A36),
    (0x0A38, 0x0A39),
    (0x0A3C, 0x0A3C),
    (0x0A3E, 0x0A42),
    (0x0A47, 0x0A48),
    (0x0A4B, 0x0A4D),
    (0x0A51, 0x0A51),
    (0x0A59, 0x0A5C),
    (0x0A5E, 0x0A5E),
    (0x0A66, 0x0A75),
    (0x0A81, 0x0A83),
    (0x0A85, 0x0A8D),
    (0x0A8F, 0x0A91),
    (0x0A93, 0x0AA8),
    (0x0AAA, 0x0AB0),
    (0x0AB2, 0x0AB3),
    (0x0AB5, 0x0AB9),
    (0x0ABC, 0x0AC5),
    (0x0AC7, 0x0AC9),
    (0x0ACB, 0x0ACD),
    (0x0AD0, 0x0AD0),
    (0x0AE0, 0x0AE3),
    (0x0AE6, 0x0AEF),
    (0x0AF9, 0x0AFF),
    (0x0B01, 0x0B03),
    (0x0B05, 0x0B0C),
    (0x0B0F, 0x0B10),
    (0x0B13, 0x0B28),
    (0x0B2A, 0x0B30),
    (0x0B32, 0x0B33),
    (0x0B35, 0x0B39),
    (0x0B3C, 0x0B44),
    (0x0B47, 0x0B48),
    (0x0B4B, 0x0B4D),
    (0x0B55, 0x0B57),
    (0x0B5C, 0x0B5D),
    (0x0B5F, 0x0B63),
    (0x0B66, 0x0B6F),
    (0x0B71, 0x0B77),
    (0x0B82, 0x0B83),
    (0x0B85, 0x0B8A),
    (0x0B8E, 0x0B90),
    (0x0B92, 0x0B95),
    (0x0B99, 0x0B9A),
    (0x0B9C, 0x0B9C),
    (0x0B9E, 0x0B9F),
    (0x0BA3, 0x0BA4),
    (0x0BA8, 0x0BAA),
    (0x0BAE, 0x0BB9),
    (0x0BBE, 0x0BC2),
    (0x0BC6, 0x0BC8),
    (0x0BCA, 0x0BCD),
    (0x0BD0, 0x0BD0),
    (0x0BD7, 0x0BD7),
    (0x0BE6, 0x0BF2),
    (0x0C00, 0x0C0C),
    (0x0C0E, 0x0C10),
    (0x0C12, 0x0C28),
    (0x0C2A, 0x0C39),
    (0x0C3C, 0x0C44),
    (0x0C46, 0x0C48),
    (0x0C4A, 0x0C4D),
    (0x0C55, 0x0C56),
    (0x0C58, 0x0C5A),
    (0x0C5D, 0x0C5D),
    (0x0C60, 0x0C63),
    (0x0C66, 0x0C6F),
    (0x0C78, 0x0C7E),
    (0x0C80, 0x0C83),
    (0x0C85, 0x0C8C),
    (0x0C8E, 0x0C90),
    (0x0C92, 0x0CA8),
    (0x0CAA, 0x0CB3),
    (0x0CB5, 0x0CB9),
    (0x0CBC, 0x0CC4),
    (0x0CC6, 0x0CC8),
    (0x0CCA, 0x0CCD),
    (0x0CD5, 0x0CD6),
    (0x0CDD, 0x0CDE),
    (0x0CE0, 0x0CE3),
    (0x0CE6, 0x0CEF),
    (0x0CF1, 0x0CF3),
    (0x0D00, 0x0D0C),
    (0x0D0E, 0x0D10),
    (0x0D12, 0x0D44),
    (0x0D46, 0x0D48),
    (0x0D4A, 0x0D4E),
    (0x0D54, 0x0D63),
    (0x0D66, 0x0D78),
    (0x0D7A, 0x0D7F),
    (0x0D81, 0x0D83),
    (0x0D85, 0x0D96),
    (0x0D9A, 0x0DB1),
    (0x0DB3, 0x0DBB),
    (0x0DBD, 0x0DBD),
    (0x0DC0, 0x0DC6),
    (0x0DCA, 0x0DCA),
    (0x0DCF, 0x0DD4),
    (0x0DD6, 0x0DD6),
    (0x0DD8, 0x0DDF),
    (0x0DE6, 0x0DEF),
    (0x0DF2, 0x0DF3),
    (0x0E01, 0x0E3A),
    (0x0E40, 0x0E4E),
    (0x0E50, 0x0E59),
    (0x0E81, 0x0E82),
    (0x0E84, 0x0E84),
    (0x0E86, 0x0E8A),
    (0x0E8C, 0x0EA3),
    (0x0EA5, 0x0EA5),
    (0x0EA7, 0x0EBD),
    (0x0EC0, 0x0EC4),
    (0x0EC6, 0x0EC6),
    (0x0EC8, 0x0ECE),
    (0x0ED0, 0x0ED9),
    (0x0EDC, 0x0EDF),
    (0x0F00, 0x0F00),
    (0x0F18, 0x0F19),
    (0x0F20, 0x0F33),
    (0x0F35, 0x0F35),
    (0x0F37, 0x0F37),
    (0x0F39, 0x0F39),
    (0x0F3E, 0x0F47),
    (0x0F49, 0x0F6C),
    (0x0F71, 0x0F84),
    (0x0F86, 0x0F97),
    (0x0F99, 0x0FBC),
    (0x0FC6, 0x0FC6),
    (0x1000, 0x1049),
    (0x1050, 0x109D),
    (0x10A0, 0x10C5),
    (0x10C7, 0x10C7),
    (0x10CD, 0x10CD),
    (0x10D0, 0x10FA),
    (0x10FC, 0x1248),
    (0x124A, 0x124D),
    (0x1250, 0x1256),
    (0x1258, 0x1258),
    (0x125A, 0x125D),
    (0x1260, 0x1288),
    (0x128A, 0x128D),
    (0x1290, 0x12B0),
    (0x12B2, 0x12B5),
    (0x12B8, 0x12BE),
    (0x12C0, 0x12C0),
    (0x12C2, 0x12C5),
    (0x12C8, 0x12D6),
    (0x12D8, 0x1310),
    (0x1312, 0x1315),
    (0x1318, 0x135A),
    (0x135D, 0x135F),
    (0x1369, 0x137C),
    (0x1380, 0x138F),
    (0x13A0, 0x13F5),
    (0x13F8, 0x13FD),
    (0x1401, 0x166C),
    (0x166F, 0x167F),
    (0x1681, 0x169A),
    (0x16A0, 0x16EA),
    (0x16EE, 0x16F8),
    (0x1700, 0x1715),
    (0x171F, 0x1734),
    (0x1740, 0x1753),
    (0x1760, 0x176C),
    (0x176E, 0x1770),
    (0x1772, 0x1773),
    (0x1780, 0x17D3),
    (0x17D7, 0x17D7),
    (0x17DC, 0x17DD),
    (0x17E0, 0x17E9),
    (0x17F0, 0x17F9),
    (0x180B, 0x180D),
    (0x180F, 0x1819),
    (0x1820, 0x1878),
    (0x1880, 0x18AA),
    (0x18B0, 0x18F5),
    (0x1900, 0x191E),
    (0x1920, 0x192B),
    (0x1930, 0x193B),
    (0x1946, 0x196D),
    (0x1970, 0x1974),
    (0x1980, 0x19AB),
    (0x19B0, 0x19C9),
    (0x19D0, 0x19DA),
    (0x1A00, 0x1A1B),
    (0x1A20, 0x1A5E),
    (0x1A60, 0x1A7C),
    (0x1A7F, 0x1A89),
    (0x1A90, 0x1A99),
    (0x1AA7, 0x1AA7),
    (0x1AB0, 0x1ACE),
    (0x1B00, 0x1B4C),
    (0x1B50, 0x1B59),
    (0x1B6B, 0x1B73),
    (0x1B80, 0x1BF3),
    (0x1C00, 0x1C37),
    (0x1C40, 0x1C49),
    (0x1C4D, 0x1C7D),
    (0x1C80, 0x1C8A),
    (0x1C90, 0x1CBA),
    (0x1CBD, 0x1CBF),
    (0x1CD0, 0x1CD2),
    (0x1CD4, 0x1CFA),
    (0x1D00, 0x1F15),
    (0x1F18, 0x1F1D),
    (0x1F20, 0x1F45),
    (0x1F48, 0x1F4D),
    (0x1F50, 0x1F57),
    (0x1F59, 0x1F59),
    (0x1F5B, 0x1F5B),
    (0x1F5D, 0x1F5D),
    (0x1F5F, 0x1F7D),
    (0x1F80, 0x1FB4),
    (0x1FB6, 0x1FBC),
    (0x1FBE, 0x1FBE),
    (0x1FC2, 0x1FC4),
    (0x1FC6, 0x1FCC),
    (0x1FD0, 0x1FD3),
    (0x1FD6, 0x1FDB),
    (0x1FE0, 0x1FEC),
    (0x1FF2, 0x1FF4),
    (0x1FF6, 0x1FFC),
    (0x2070, 0x2071),
    (0x2074, 0x2079),
    (0x207F, 0x2089),
    (0x2090, 0x209C),
    (0x20D0, 0x20F0),
    (0x2102, 0x2102),
    (0x2107, 0x2107),
    (0x210A, 0x2113),
    (0x2115, 0x2115),
    (0x2119, 0x211D),
    (0x2124, 0x2124),
    (0x2126, 0x2126),
    (0x2128, 0x2128),
    (0x212A, 0x212D),
    (0x212F, 0x2139),
    (0x213C, 0x213F),
    (0x2145, 0x2149),
    (0x214E, 0x214E),
    (0x2150, 0x2189),
    (0x2460, 0x249B),
    (0x24EA, 0x24FF),
    (0x2776, 0x2793),
    (0x2C00, 0x2CE4),
    (0x2CEB, 0x2CF3),
    (0x2CFD, 0x2CFD),
    (0x2D00, 0x2D25),
    (0x2D27, 0x2D27),
    (0x2D2D, 0x2D2D),
    (0x2D30, 0x2D67),
    (0x2D6F, 0x2D6F),
    (0x2D7F, 0x2D96),
    (0x2DA0, 0x2DA6),
    (0x2DA8, 0x2DAE),
    (0x2DB0, 0x2DB6),
    (0x2DB8, 0x2DBE),
    (0x2DC0, 0x2DC6),
    (0x2DC8, 0x2DCE),
    (0x2DD0, 0x2DD6),
    (0x2DD8, 0x2DDE),
    (0x2DE0, 0x2DFF),
    (0x2E2F, 0x2E2F),
    (0x3005, 0x3007),
    (0x3021, 0x302F),
    (0x3031, 0x3035),
    (0x3038, 0x303C),
    (0x3041, 0x3096),
    (0x3099, 0x309A),
    (0x309D, 0x309F),
    (0x30A1, 0x30FA),
    (0x30FC, 0x30FF),
    (0x3105, 0x312F),
    (0x3131, 0x318E),
    (0x3192, 0x3195),
    (0x31A0, 0x31BF),
    (0x31F0, 0x31FF),
    (0x3220, 0x3229),
    (0x3248, 0x324F),
    (0x3251, 0x325F),
    (0x3280, 0x3289),
    (0x32B1, 0x32BF),
    (0x3400, 0x4DBF),
    (0x4E00, 0xA48C),
    (0xA4D0, 0xA4FD),
    (0xA500, 0xA60C),
    (0xA610, 0xA62B),
    (0xA640, 0xA672),
    (0xA674, 0xA67D),
    (0xA67F, 0xA6F1),
    (0xA717, 0xA71F),
    (0xA722, 0xA788),
    (0xA78B, 0xA7CD),
    (0xA7D0, 0xA7D1),
    (0xA7D3, 0xA7D3),
    (0xA7D5, 0xA7DC),
    (0xA7F2, 0xA827),
    (0xA82C, 0xA82C),
    (0xA830, 0xA835),
    (0xA840, 0xA873),
    (0xA880, 0xA8C5),
    (0xA8D0, 0xA8D9),
    (0xA8E0, 0xA8F7),
    (0xA8FB, 0xA8FB),
    (0xA8FD, 0xA92D),
    (0xA930, 0xA953),
    (0xA960, 0xA97C),
    (0xA980, 0xA9C0),
    (0xA9CF, 0xA9D9),
    (0xA9E0, 0xA9FE),
    (0xAA00, 0xAA36),
    (0xAA40, 0xAA4D),
    (0xAA50, 0xAA59),
    (0xAA60, 0xAA76),
    (0xAA7A, 0xAAC2),
    (0xAADB, 0xAADD),
    (0xAAE0, 0xAAEF),
    (0xAAF2, 0xAAF6),
    (0xAB01, 0xAB06),
    (0xAB09, 0xAB0E),
    (0xAB11, 0xAB16),
    (0xAB20, 0xAB26),
    (0xAB28, 0xAB2E),
    (0xAB30, 0xAB5A),
    (0xAB5C, 0xAB69),
    (0xAB70, 0xABEA),
    (0xABEC, 0xABED),
    (0xABF0, 0xABF9),
    (0xAC00, 0xD7A3),
    (0xD7B0, 0xD7C6),
    (0xD7CB, 0xD7FB),
    (0xF900, 0xFA6D),
    (0xFA70, 0xFAD9),
    (0xFB00, 0xFB06),
    (0xFB13, 0xFB17),
    (0xFB1D, 0xFB28),
    (0xFB2A, 0xFB36),
    (0xFB38, 0xFB3C),
    (0xFB3E, 0xFB3E),
    (0xFB40, 0xFB41),
    (0xFB43, 0xFB44),
    (0xFB46, 0xFBB1),
    (0xFBD3, 0xFD3D),
    (0xFD50, 0xFD8F),
    (0xFD92, 0xFDC7),
    (0xFDF0, 0xFDFB),
    (0xFE00, 0xFE0F),
    (0xFE20, 0xFE2F),
    (0xFE70, 0xFE74),
    (0xFE76, 0xFEFC),
    (0xFF10, 0xFF19),
    (0xFF21, 0xFF3A),
    (0xFF41, 0xFF5A),
    (0xFF66, 0xFFBE),
    (0xFFC2, 0xFFC7),
    (0xFFCA, 0xFFCF),
    (0xFFD2, 0xFFD7),
    (0xFFDA, 0xFFDC),
    (0x10000, 0x1000B),
    (0x1000D, 0x10026),
    (0x10028, 0x1003A),
    (0x1003C, 0x1003D),
    (0x1003F, 0x1004D),
    (0x10050, 0x1005D),
    (0x10080, 0x100FA),
    (0x10107, 0x10133),
    (0x10140, 0x10178),
    (0x1018A, 0x1018B),
    (0x101FD, 0x101FD),
    (0x10280, 0x1029C),
    (0x102A0, 0x102D0),
    (0x102E0, 0x102FB),
    (0x10300, 0x10323),
    (0x1032D, 0x1034A),
    (0x10350, 0x1037A),
    (0x10380, 0x1039D),
    (0x103A0, 0x103C3),
    (0x103C8, 0x103CF),
    (0x103D1, 0x103D5),
    (0x10400, 0x1049D),
    (0x104A0, 0x104A9),
    (0x104B0, 0x104D3),
    (0x104D8, 0x104FB),
    (0x10500, 0x10527),
    (0x10530, 0x10563),
    (0x10570, 0x1057A),
    (0x1057C, 0x1058A),
    (0x1058C, 0x10592),
    (0x10594, 0x10595),
    (0x10597, 0x105A1),
    (0x105A3, 0x105B1),
    (0x105B3, 0x105B9),
    (0x105BB, 0x105BC),
    (0x105C0, 0x105F3),
    (0x10600, 0x10736),
    (0x10740, 0x10755),
    (0x10760, 0x10767),
    (0x10780, 0x10785),
    (0x10787, 0x107B0),
    (0x107B2, 0x107BA),
    (0x10800, 0x10805),
    (0x10808, 0x10808),
    (0x1080A, 0x10835),
    (0x10837, 0x10838),
    (0x1083C, 0x1083C),
    (0x1083F, 0x10855),
    (0x10858, 0x10876),
    (0x10879, 0x1089E),
    (0x108A7, 0x108AF),
    (0x108E0, 0x108F2),
    (0x108F4, 0x108F5),
    (0x108FB, 0x1091B),
    (0x10920, 0x10939),
    (0x10980, 0x109B7),
    (0x109BC, 0x109CF),
    (0x109D2, 0x10A03),
    (0x10A05, 0x10A06),
    (0x10A0C, 0x10A13),
    (0x10A15, 0x10A17),
    (0x10A19, 0x10A35),
    (0x10A38, 0x10A3A),
    (0x10A3F, 0x10A48),
    (0x10A60, 0x10A7E),
    (0x10A80, 0x10A9F),
    (0x10AC0, 0x10AC7),
    (0x10AC9, 0x10AE6),
    (0x10AEB, 0x10AEF),
    (0x10B00, 0x10B35),
    (0x10B40, 0x10B55),
    (0x10B58, 0x10B72),
    (0x10B78, 0x10B91),
    (0x10BA9, 0x10BAF),
    (0x10C00, 0x10C48),
    (0x10C80, 0x10CB2),
    (0x10CC0, 0x10CF2),
    (0x10CFA, 0x10D27),
    (0x10D30, 0x10D39),
    (0x10D40, 0x10D65),
    (0x10D69, 0x10D6D),
    (0x10D6F, 0x10D85),
    (0x10E60, 0x10E7E),
    (0x10E80, 0x10EA9),
    (0x10EAB, 0x10EAC),
    (0x10EB0, 0x10EB1),
    (0x10EC2, 0x10EC4),
    (0x10EFC, 0x10F27),
    (0x10F30, 0x10F54),
    (0x10F70, 0x10F85),
    (0x10FB0, 0x10FCB),
    (0x10FE0, 0x10FF6),
    (0x11000, 0x11046),
    (0x11052, 0x11075),
    (0x1107F, 0x110BA),
    (0x110C2, 0x110C2),
    (0x110D0, 0x110E8),
    (0x110F0, 0x110F9),
    (0x11100, 0x11134),
    (0x11136, 0x1113F),
    (0x11144, 0x11147),
    (0x11150, 0x11173),
    (0x11176, 0x11176),
    (0x11180, 0x111C4),
    (0x111C9, 0x111CC),
    (0x111CE, 0x111DA),
    (0x111DC, 0x111DC),
    (0x111E1, 0x111F4),
    (0x11200, 0x11211),
    (0x11213, 0x11237),
    (0x1123E, 0x11241),
    (0x11280, 0x11286),
    (0x11288, 0x11288),
    (0x1128A, 0x1128D),
    (0x1128F, 0x1129D),
    (0x1129F, 0x112A8),
    (0x112B0, 0x112EA),
    (0x112F0, 0x112F9),
    (0x11300, 0x11303),
    (0x11305, 0x1130C),
    (0x1130F, 0x11310),
    (0x11313, 0x11328),
    (0x1132A, 0x11330),
    (0x11332, 0x11333),
    (0x11335, 0x11339),
    (0x1133B, 0x11344),
    (0x11347, 0x11348),
    (0x1134B, 0x1134D),
    (0x11350, 0x11350),
    (0x11357, 0x11357),
    (0x1135D, 0x11363),
    (0x11366, 0x1136C),
    (0x11370, 0x11374),
    (0x11380, 0x11389),
    (0x1138B, 0x1138B),
    (0x1138E, 0x1138E),
    (0x11390, 0x113B5),
    (0x113B7, 0x113C0),
    (0x113C2, 0x113C2),
    (0x113C5, 0x113C5),
    (0x113C7, 0x113CA),
    (0x113CC, 0x113D3),
    (0x113E1, 0x113E2),
    (0x11400, 0x1144A),
    (0x11450, 0x11459),
    (0x1145E, 0x11461),
    (0x11480, 0x114C5),
    (0x114C7, 0x114C7),
    (0x114D0, 0x114D9),
    (0x11580, 0x115B5),
    (0x115B8, 0x115C0),
    (0x115D8, 0x115DD),
    (0x11600, 0x11640),
    (0x11644, 0x11644),
    (0x11650, 0x11659),
    (0x11680, 0x116B8),
    (0x116C0, 0x116C9),
    (0x116D0, 0x116E3),
    (0x11700, 0x1171A),
    (0x1171D, 0x1172B),
    (0x11730, 0x1173B),
    (0x11740, 0x11746),
    (0x11800, 0x1183A),
    (0x118A0, 0x118F2),
    (0x118FF, 0x11906),
    (0x11909, 0x11909),
    (0x1190C, 0x11913),
    (0x11915, 0x11916),
    (0x11918, 0x11935),
    (0x11937, 0x11938),
    (0x1193B, 0x11943),
    (0x11950, 0x11959),
    (0x119A0, 0x119A7),
    (0x119AA, 0x119D7),
    (0x119DA, 0x119E1),
    (0x119E3, 0x119E4),
    (0x11A00, 0x11A3E),
    (0x11A47, 0x11A47),
    (0x11A50, 0x11A99),
    (0x11A9D, 0x11A9D),
    (0x11AB0, 0x11AF8),
    (0x11BC0, 0x11BE0),
    (0x11BF0, 0x11BF9),
    (0x11C00, 0x11C08),
    (0x11C0A, 0x11C36),
    (0x11C38, 0x11C40),
    (0x11C50, 0x11C6C),
    (0x11C72, 0x11C8F),
    (0x11C92, 0x11CA7),
    (0x11CA9, 0x11CB6),
    (0x11D00, 0x11D06),
    (0x11D08, 0x11D09),
    (0x11D0B, 0x11D36),
    (0x11D3A, 0x11D3A),
    (0x11D3C, 0x11D3D),
    (0x11D3F, 0x11D47),
    (0x11D50, 0x11D59),
    (0x11D60, 0x11D65),
    (0x11D67, 0x11D68),
    (0x11D6A, 0x11D8E),
    (0x11D90, 0x11D91),
    (0x11D93, 0x11D98),
    (0x11DA0, 0x11DA9),
    (0x11EE0, 0x11EF6),
    (0x11F00, 0x11F10),
    (0x11F12, 0x11F3A),
    (0x11F3E, 0x11F42),
    (0x11F50, 0x11F5A),
    (0x11FB0, 0x11FB0),
    (0x11FC0, 0x11FD4),
    (0x12000, 0x12399),
    (0x12400, 0x1246E),
    (0x12480, 0x12543),
    (0x12F90, 0x12FF0),
    (0x13000, 0x1342F),
    (0x13440, 0x13455),
    (0x13460, 0x143FA),
    (0x14400, 0x14646),
    (0x16100, 0x16139),
    (0x16800, 0x16A38),
    (0x16A40, 0x16A5E),
    (0x16A60, 0x16A69),
    (0x16A70, 0x16ABE),
    (0x16AC0, 0x16AC9),
    (0x16AD0, 0x16AED),
    (0x16AF0, 0x16AF4),
    (0x16B00, 0x16B36),
    (0x16B40, 0x16B43),
    (0x16B50, 0x16B59),
    (0x16B5B, 0x16B61),
    (0x16B63, 0x16B77),
    (0x16B7D, 0x16B8F),
    (0x16D40, 0x16D6C),
    (0x16D70, 0x16D79),
    (0x16E40, 0x16E96),
    (0x16F00, 0x16F4A),
    (0x16F4F, 0x16F87),
    (0x16F8F, 0x16F9F),
    (0x16FE0, 0x16FE1),
    (0x16FE3, 0x16FE4),
    (0x16FF0, 0x16FF1),
    (0x17000, 0x187F7),
    (0x18800, 0x18CD5),
    (0x18CFF, 0x18D08),
    (0x1AFF0, 0x1AFF3),
    (0x1AFF5, 0x1AFFB),
    (0x1AFFD, 0x1AFFE),
    (0x1B000, 0x1B122),
    (0x1B132, 0x1B132),
    (0x1B150, 0x1B152),
    (0x1B155, 0x1B155),
    (0x1B164, 0x1B167),
    (0x1B170, 0x1B2FB),
    (0x1BC00, 0x1BC6A),
    (0x1BC70, 0x1BC7C),
    (0x1BC80, 0x1BC88),
    (0x1BC90, 0x1BC99),
    (0x1BC9D, 0x1BC9E),
    (0x1CCF0, 0x1CCF9),
    (0x1CF00, 0x1CF2D),
    (0x1CF30, 0x1CF46),
    (0x1D165, 0x1D169),
    (0x1D16D, 0x1D172),
    (0x1D17B, 0x1D182),
    (0x1D185, 0x1D18B),
    (0x1D1AA, 0x1D1AD),
    (0x1D242, 0x1D244),
    (0x1D2C0, 0x1D2D3),
    (0x1D2E0, 0x1D2F3),
    (0x1D360, 0x1D378),
    (0x1D400, 0x1D454),
    (0x1D456, 0x1D49C),
    (0x1D49E, 0x1D49F),
    (0x1D4A2, 0x1D4A2),
    (0x1D4A5, 0x1D4A6),
    (0x1D4A9, 0x1D4AC),
    (0x1D4AE, 0x1D4B9),
    (0x1D4BB, 0x1D4BB),
    (0x1D4BD, 0x1D4C3),
    (0x1D4C5, 0x1D505),
    (0x1D507, 0x1D50A),
    (0x1D50D, 0x1D514),
    (0x1D516, 0x1D51C),
    (0x1D51E, 0x1D539),
    (0x1D53B, 0x1D53E),
    (0x1D540, 0x1D544),
    (0x1D546, 0x1D546),
    (0x1D54A, 0x1D550),
    (0x1D552, 0x1D6A5),
    (0x1D6A8, 0x1D6C0),
    (0x1D6C2, 0x1D6DA),
    (0x1D6DC, 0x1D6FA),
    (0x1D6FC, 0x1D714),
    (0x1D716, 0x1D734),
    (0x1D736, 0x1D74E),
    (0x1D750, 0x1D76E),
    (0x1D770, 0x1D788),
    (0x1D78A, 0x1D7A8),
    (0x1D7AA, 0x1D7C2),
    (0x1D7C4, 0x1D7CB),
    (0x1D7CE, 0x1D7FF),
    (0x1DA00, 0x1DA36),
    (0x1DA3B, 0x1DA6C),
    (0x1DA75, 0x1DA75),
    (0x1DA84, 0x1DA84),
    (0x1DA9B, 0x1DA9F),
    (0x1DAA1, 0x1DAAF),
    (0x1DF00, 0x1DF1E),
    (0x1DF25, 0x1DF2A),
    (0x1E000, 0x1E006),
    (0x1E008, 0x1E018),
    (0x1E01B, 0x1E021),
    (0x1E023, 0x1E024),
    (0x1E026, 0x1E02A),
    (0x1E030, 0x1E06D),
    (0x1E08F, 0x1E08F),
    (0x1E100, 0x1E12C),
    (0x1E130, 0x1E13D),
    (0x1E140, 0x1E149),
    (0x1E14E, 0x1E14E),
    (0x1E290, 0x1E2AE),
    (0x1E2C0, 0x1E2F9),
    (0x1E4D0, 0x1E4F9),
    (0x1E5D0, 0x1E5FA),
    (0x1E7E0, 0x1E7E6),
    (0x1E7E8, 0x1E7EB),
    (0x1E7ED, 0x1E7EE),
    (0x1E7F0, 0x1E7FE),
    (0x1E800, 0x1E8C4),
    (0x1E8C7, 0x1E8D6),
    (0x1E900, 0x1E94B),
    (0x1E950, 0x1E959),
    (0x1EC71, 0x1ECAB),
    (0x1ECAD, 0x1ECAF),
    (0x1ECB1, 0x1ECB4),
    (0x1ED01, 0x1ED2D),
    (0x1ED2F, 0x1ED3D),
    (0x1EE00, 0x1EE03),
    (0x1EE05, 0x1EE1F),
    (0x1EE21, 0x1EE22),
    (0x1EE24, 0x1EE24),
    (0x1EE27, 0x1EE27),
    (0x1EE29, 0x1EE32),
    (0x1EE34, 0x1EE37),
    (0x1EE39, 0x1EE39),
    (0x1EE3B, 0x1EE3B),
    (0x1EE42, 0x1EE42),
    (0x1EE47, 0x1EE47),
    (0x1EE49, 0x1EE49),
    (0x1EE4B, 0x1EE4B),
    (0x1EE4D, 0x1EE4F),
    (0x1EE51, 0x1EE52),
    (0x1EE54, 0x1EE54),
    (0x1EE57, 0x1EE57),
    (0x1EE59, 0x1EE59),
    (0x1EE5B, 0x1EE5B),
    (0x1EE5D, 0x1EE5D),
    (0x1EE5F, 0x1EE5F),
    (0x1EE61, 0x1EE62),
    (0x1EE64, 0x1EE64),
    (0x1EE67, 0x1EE6A),
    (0x1EE6C, 0x1EE72),
    (0x1EE74, 0x1EE77),
    (0x1EE79, 0x1EE7C),
    (0x1EE7E, 0x1EE7E),
    (0x1EE80, 0x1EE89),
    (0x1EE8B, 0x1EE9B),
    (0x1EEA1, 0x1EEA3),
    (0x1EEA5, 0x1EEA9),
    (0x1EEAB, 0x1EEBB),
    (0x1F100, 0x1F10C),
    (0x1FBF0, 0x1FBF9),
    (0x20000, 0x2A6DF),
    (0x2A700, 0x2B739),
    (0x2B740, 0x2B81D),
    (0x2B820, 0x2CEA1),
    (0x2CEB0, 0x2EBE0),
    (0x2EBF0, 0x2EE5D),
    (0x2F800, 0x2FA1D),
    (0x30000, 0x3134A),
    (0x31350, 0x323AF),
    (0xE0100, 0xE01EF),
];

// Unicode General Categories P (Punctuation) and S (Symbol) for U+0080..=U+10FFFF.
// Matches CommonMark / `org.commonmark.text.Characters.isPunctuationCodePoint`.
const NON_ASCII_PS_RANGES: &[(u32, u32)] = &[
    (0x00A1, 0x00A9),
    (0x00AB, 0x00AC),
    (0x00AE, 0x00B1),
    (0x00B4, 0x00B4),
    (0x00B6, 0x00B8),
    (0x00BB, 0x00BB),
    (0x00BF, 0x00BF),
    (0x00D7, 0x00D7),
    (0x00F7, 0x00F7),
    (0x02C2, 0x02C5),
    (0x02D2, 0x02DF),
    (0x02E5, 0x02EB),
    (0x02ED, 0x02ED),
    (0x02EF, 0x02FF),
    (0x0375, 0x0375),
    (0x037E, 0x037E),
    (0x0384, 0x0385),
    (0x0387, 0x0387),
    (0x03F6, 0x03F6),
    (0x0482, 0x0482),
    (0x055A, 0x055F),
    (0x0589, 0x058A),
    (0x058D, 0x058F),
    (0x05BE, 0x05BE),
    (0x05C0, 0x05C0),
    (0x05C3, 0x05C3),
    (0x05C6, 0x05C6),
    (0x05F3, 0x05F4),
    (0x0606, 0x060F),
    (0x061B, 0x061B),
    (0x061D, 0x061F),
    (0x066A, 0x066D),
    (0x06D4, 0x06D4),
    (0x06DE, 0x06DE),
    (0x06E9, 0x06E9),
    (0x06FD, 0x06FE),
    (0x0700, 0x070D),
    (0x07F6, 0x07F9),
    (0x07FE, 0x07FF),
    (0x0830, 0x083E),
    (0x085E, 0x085E),
    (0x0888, 0x0888),
    (0x0964, 0x0965),
    (0x0970, 0x0970),
    (0x09F2, 0x09F3),
    (0x09FA, 0x09FB),
    (0x09FD, 0x09FD),
    (0x0A76, 0x0A76),
    (0x0AF0, 0x0AF1),
    (0x0B70, 0x0B70),
    (0x0BF3, 0x0BFA),
    (0x0C77, 0x0C77),
    (0x0C7F, 0x0C7F),
    (0x0C84, 0x0C84),
    (0x0D4F, 0x0D4F),
    (0x0D79, 0x0D79),
    (0x0DF4, 0x0DF4),
    (0x0E3F, 0x0E3F),
    (0x0E4F, 0x0E4F),
    (0x0E5A, 0x0E5B),
    (0x0F01, 0x0F17),
    (0x0F1A, 0x0F1F),
    (0x0F34, 0x0F34),
    (0x0F36, 0x0F36),
    (0x0F38, 0x0F38),
    (0x0F3A, 0x0F3D),
    (0x0F85, 0x0F85),
    (0x0FBE, 0x0FC5),
    (0x0FC7, 0x0FCC),
    (0x0FCE, 0x0FDA),
    (0x104A, 0x104F),
    (0x109E, 0x109F),
    (0x10FB, 0x10FB),
    (0x1360, 0x1368),
    (0x1390, 0x1399),
    (0x1400, 0x1400),
    (0x166D, 0x166E),
    (0x169B, 0x169C),
    (0x16EB, 0x16ED),
    (0x1735, 0x1736),
    (0x17D4, 0x17D6),
    (0x17D8, 0x17DB),
    (0x1800, 0x180A),
    (0x1940, 0x1940),
    (0x1944, 0x1945),
    (0x19DE, 0x19FF),
    (0x1A1E, 0x1A1F),
    (0x1AA0, 0x1AA6),
    (0x1AA8, 0x1AAD),
    (0x1B4E, 0x1B4F),
    (0x1B5A, 0x1B6A),
    (0x1B74, 0x1B7F),
    (0x1BFC, 0x1BFF),
    (0x1C3B, 0x1C3F),
    (0x1C7E, 0x1C7F),
    (0x1CC0, 0x1CC7),
    (0x1CD3, 0x1CD3),
    (0x1FBD, 0x1FBD),
    (0x1FBF, 0x1FC1),
    (0x1FCD, 0x1FCF),
    (0x1FDD, 0x1FDF),
    (0x1FED, 0x1FEF),
    (0x1FFD, 0x1FFE),
    (0x2010, 0x2027),
    (0x2030, 0x205E),
    (0x207A, 0x207E),
    (0x208A, 0x208E),
    (0x20A0, 0x20C1),
    (0x2100, 0x2101),
    (0x2103, 0x2106),
    (0x2108, 0x2109),
    (0x2114, 0x2114),
    (0x2116, 0x2118),
    (0x211E, 0x2123),
    (0x2125, 0x2125),
    (0x2127, 0x2127),
    (0x2129, 0x2129),
    (0x212E, 0x212E),
    (0x213A, 0x213B),
    (0x2140, 0x2144),
    (0x214A, 0x214D),
    (0x214F, 0x214F),
    (0x218A, 0x218B),
    (0x2190, 0x2429),
    (0x2440, 0x244A),
    (0x249C, 0x24E9),
    (0x2500, 0x2775),
    (0x2794, 0x2B73),
    (0x2B76, 0x2BFF),
    (0x2CE5, 0x2CEA),
    (0x2CF9, 0x2CFC),
    (0x2CFE, 0x2CFF),
    (0x2D70, 0x2D70),
    (0x2E00, 0x2E2E),
    (0x2E30, 0x2E5D),
    (0x2E80, 0x2E99),
    (0x2E9B, 0x2EF3),
    (0x2F00, 0x2FD5),
    (0x2FF0, 0x2FFF),
    (0x3001, 0x3004),
    (0x3008, 0x3020),
    (0x3030, 0x3030),
    (0x3036, 0x3037),
    (0x303D, 0x303F),
    (0x309B, 0x309C),
    (0x30A0, 0x30A0),
    (0x30FB, 0x30FB),
    (0x3190, 0x3191),
    (0x3196, 0x319F),
    (0x31C0, 0x31E5),
    (0x31EF, 0x31EF),
    (0x3200, 0x321E),
    (0x322A, 0x3247),
    (0x3250, 0x3250),
    (0x3260, 0x327F),
    (0x328A, 0x32B0),
    (0x32C0, 0x33FF),
    (0x4DC0, 0x4DFF),
    (0xA490, 0xA4C6),
    (0xA4FE, 0xA4FF),
    (0xA60D, 0xA60F),
    (0xA673, 0xA673),
    (0xA67E, 0xA67E),
    (0xA6F2, 0xA6F7),
    (0xA700, 0xA716),
    (0xA720, 0xA721),
    (0xA789, 0xA78A),
    (0xA828, 0xA82B),
    (0xA836, 0xA839),
    (0xA874, 0xA877),
    (0xA8CE, 0xA8CF),
    (0xA8F8, 0xA8FA),
    (0xA8FC, 0xA8FC),
    (0xA92E, 0xA92F),
    (0xA95F, 0xA95F),
    (0xA9C1, 0xA9CD),
    (0xA9DE, 0xA9DF),
    (0xAA5C, 0xAA5F),
    (0xAA77, 0xAA79),
    (0xAADE, 0xAADF),
    (0xAAF0, 0xAAF1),
    (0xAB5B, 0xAB5B),
    (0xAB6A, 0xAB6B),
    (0xABEB, 0xABEB),
    (0xFB29, 0xFB29),
    (0xFBB2, 0xFBD2),
    (0xFD3E, 0xFD4F),
    (0xFD90, 0xFD91),
    (0xFDC8, 0xFDCF),
    (0xFDFC, 0xFDFF),
    (0xFE10, 0xFE19),
    (0xFE30, 0xFE52),
    (0xFE54, 0xFE66),
    (0xFE68, 0xFE6B),
    (0xFF01, 0xFF0F),
    (0xFF1A, 0xFF20),
    (0xFF3B, 0xFF40),
    (0xFF5B, 0xFF65),
    (0xFFE0, 0xFFE6),
    (0xFFE8, 0xFFEE),
    (0xFFFC, 0xFFFD),
    (0x10100, 0x10102),
    (0x10137, 0x1013F),
    (0x10179, 0x10189),
    (0x1018C, 0x1018E),
    (0x10190, 0x1019C),
    (0x101A0, 0x101A0),
    (0x101D0, 0x101FC),
    (0x1039F, 0x1039F),
    (0x103D0, 0x103D0),
    (0x1056F, 0x1056F),
    (0x10857, 0x10857),
    (0x10877, 0x10878),
    (0x1091F, 0x1091F),
    (0x1093F, 0x1093F),
    (0x10A50, 0x10A58),
    (0x10A7F, 0x10A7F),
    (0x10AC8, 0x10AC8),
    (0x10AF0, 0x10AF6),
    (0x10B39, 0x10B3F),
    (0x10B99, 0x10B9C),
    (0x10D6E, 0x10D6E),
    (0x10D8E, 0x10D8F),
    (0x10EAD, 0x10EAD),
    (0x10ED0, 0x10ED8),
    (0x10F55, 0x10F59),
    (0x10F86, 0x10F89),
    (0x11047, 0x1104D),
    (0x110BB, 0x110BC),
    (0x110BE, 0x110C1),
    (0x11140, 0x11143),
    (0x11174, 0x11175),
    (0x111C5, 0x111C8),
    (0x111CD, 0x111CD),
    (0x111DB, 0x111DB),
    (0x111DD, 0x111DF),
    (0x11238, 0x1123D),
    (0x112A9, 0x112A9),
    (0x113D4, 0x113D5),
    (0x113D7, 0x113D8),
    (0x1144B, 0x1144F),
    (0x1145A, 0x1145B),
    (0x1145D, 0x1145D),
    (0x114C6, 0x114C6),
    (0x115C1, 0x115D7),
    (0x11641, 0x11643),
    (0x11660, 0x1166C),
    (0x116B9, 0x116B9),
    (0x1173C, 0x1173F),
    (0x1183B, 0x1183B),
    (0x11944, 0x11946),
    (0x119E2, 0x119E2),
    (0x11A3F, 0x11A46),
    (0x11A9A, 0x11A9C),
    (0x11A9E, 0x11AA2),
    (0x11B00, 0x11B09),
    (0x11BE1, 0x11BE1),
    (0x11C41, 0x11C45),
    (0x11C70, 0x11C71),
    (0x11EF7, 0x11EF8),
    (0x11F43, 0x11F4F),
    (0x11FD5, 0x11FF1),
    (0x11FFF, 0x11FFF),
    (0x12470, 0x12474),
    (0x12FF1, 0x12FF2),
    (0x16A6E, 0x16A6F),
    (0x16AF5, 0x16AF5),
    (0x16B37, 0x16B3F),
    (0x16B44, 0x16B45),
    (0x16D6D, 0x16D6F),
    (0x16E97, 0x16E9A),
    (0x16FE2, 0x16FE2),
    (0x1BC9C, 0x1BC9C),
    (0x1BC9F, 0x1BC9F),
    (0x1CC00, 0x1CCEF),
    (0x1CCFA, 0x1CCFC),
    (0x1CD00, 0x1CEB3),
    (0x1CEBA, 0x1CED0),
    (0x1CEE0, 0x1CEF0),
    (0x1CF50, 0x1CFC3),
    (0x1D000, 0x1D0F5),
    (0x1D100, 0x1D126),
    (0x1D129, 0x1D164),
    (0x1D16A, 0x1D16C),
    (0x1D183, 0x1D184),
    (0x1D18C, 0x1D1A9),
    (0x1D1AE, 0x1D1EA),
    (0x1D200, 0x1D241),
    (0x1D245, 0x1D245),
    (0x1D300, 0x1D356),
    (0x1D6C1, 0x1D6C1),
    (0x1D6DB, 0x1D6DB),
    (0x1D6FB, 0x1D6FB),
    (0x1D715, 0x1D715),
    (0x1D735, 0x1D735),
    (0x1D74F, 0x1D74F),
    (0x1D76F, 0x1D76F),
    (0x1D789, 0x1D789),
    (0x1D7A9, 0x1D7A9),
    (0x1D7C3, 0x1D7C3),
    (0x1D800, 0x1D9FF),
    (0x1DA37, 0x1DA3A),
    (0x1DA6D, 0x1DA74),
    (0x1DA76, 0x1DA83),
    (0x1DA85, 0x1DA8B),
    (0x1E14F, 0x1E14F),
    (0x1E2FF, 0x1E2FF),
    (0x1E5FF, 0x1E5FF),
    (0x1E95E, 0x1E95F),
    (0x1ECAC, 0x1ECAC),
    (0x1ECB0, 0x1ECB0),
    (0x1ED2E, 0x1ED2E),
    (0x1EEF0, 0x1EEF1),
    (0x1F000, 0x1F02B),
    (0x1F030, 0x1F093),
    (0x1F0A0, 0x1F0AE),
    (0x1F0B1, 0x1F0BF),
    (0x1F0C1, 0x1F0CF),
    (0x1F0D1, 0x1F0F5),
    (0x1F10D, 0x1F1AD),
    (0x1F1E6, 0x1F202),
    (0x1F210, 0x1F23B),
    (0x1F240, 0x1F248),
    (0x1F250, 0x1F251),
    (0x1F260, 0x1F265),
    (0x1F300, 0x1F6D8),
    (0x1F6DC, 0x1F6EC),
    (0x1F6F0, 0x1F6FC),
    (0x1F700, 0x1F7D9),
    (0x1F7E0, 0x1F7EB),
    (0x1F7F0, 0x1F7F0),
    (0x1F800, 0x1F80B),
    (0x1F810, 0x1F847),
    (0x1F850, 0x1F859),
    (0x1F860, 0x1F887),
    (0x1F890, 0x1F8AD),
    (0x1F8B0, 0x1F8BB),
    (0x1F8C0, 0x1F8C1),
    (0x1F8D0, 0x1F8D8),
    (0x1F900, 0x1FA57),
    (0x1FA60, 0x1FA6D),
    (0x1FA70, 0x1FA7C),
    (0x1FA80, 0x1FA8A),
    (0x1FA8E, 0x1FAC6),
    (0x1FAC8, 0x1FAC8),
    (0x1FACD, 0x1FADC),
    (0x1FADF, 0x1FAEA),
    (0x1FAEF, 0x1FAF8),
    (0x1FB00, 0x1FB92),
    (0x1FB94, 0x1FBEF),
    (0x1FBFA, 0x1FBFA),
];

mod markdown {
    use super::{heading_slugs, MAX_MARKDOWN_DEPTH, NON_ASCII_PS_RANGES};
    use quick_xml::escape::resolve_html5_entity;
    use std::collections::HashMap;

    const MAX_TABLE_CELLS: usize = 500_000;

    #[derive(Clone, Debug)]
    struct LinkDef {
        dest: String,
        title: Option<String>,
    }

    #[derive(Clone, Copy, Debug, PartialEq, Eq)]
    enum TableAlign {
        Left,
        Center,
        Right,
    }

    #[derive(Clone, Debug)]
    enum MdBlock {
        BlockQuote(Vec<MdBlock>),
        List {
            ordered: bool,
            start: u32,
            tight: bool,
            items: Vec<Vec<MdBlock>>,
        },
        Heading {
            level: u8,
            raw: String,
            inlines: Vec<MdInline>,
        },
        Paragraph {
            raw: String,
            inlines: Vec<MdInline>,
        },
        FencedCode {
            info: String,
            literal: String,
        },
        IndentedCode {
            literal: String,
        },
        HtmlBlock {
            literal: String,
        },
        ThematicBreak,
        Table {
            alignments: Vec<Option<TableAlign>>,
            head: Vec<MdTableCell>,
            body: Vec<Vec<MdTableCell>>,
        },
    }

    #[derive(Clone, Debug)]
    struct MdTableCell {
        raw: String,
        inlines: Vec<MdInline>,
    }

    #[derive(Clone, Debug)]
    enum MdInline {
        Text(String),
        Code(String),
        SoftBreak,
        HardBreak,
        Emph(Vec<MdInline>),
        Strong(Vec<MdInline>),
        Strike(Vec<MdInline>),
        Link {
            dest: String,
            title: Option<String>,
            children: Vec<MdInline>,
        },
        Image {
            alt_text: String,
            max_child_depth: usize,
        },
        HtmlInline(String),
    }

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum ListKind {
        Bullet(u8),
        Ordered(u8),
    }

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum HtmlBlockType {
        ScriptPreStyleTextarea, // 1
        Comment,                // 2
        ProcessingInstr,        // 3
        Declaration,            // 4
        Cdata,                  // 5
        StandardTag,            // 6
        CompleteTag,            // 7
    }

    struct ParagraphLines {
        lines: Vec<String>,
        defs_prefix_len: usize,
    }

    impl ParagraphLines {
        fn new(first_line: String) -> Self {
            Self {
                lines: vec![first_line],
                defs_prefix_len: 0,
            }
        }

        fn active_lines(&self) -> &[String] {
            &self.lines[self.defs_prefix_len..]
        }
    }

    enum OpenBlock {
        Document(Vec<MdBlock>),
        BlockQuote(Vec<MdBlock>),
        List {
            kind: ListKind,
            start: u32,
            tight: bool,
            had_blank_line: bool,
            lines_after_blank: usize,
            items: Vec<Vec<MdBlock>>,
        },
        ListItem {
            content_indent: usize,
            had_blank_line: bool,
            blocks: Vec<MdBlock>,
        },
        Paragraph(ParagraphLines),
        FencedCode {
            fence_char: u8,
            fence_len: usize,
            fence_indent: usize,
            info: String,
            lines: Vec<String>,
        },
        IndentedCode {
            lines: Vec<String>,
        },
        HtmlBlock {
            kind: HtmlBlockType,
            lines: Vec<String>,
        },
        Table {
            alignments: Vec<Option<TableAlign>>,
            row_lines: Vec<String>,
            allow_lazy: bool,
        },
    }

    struct LineCursor<'a> {
        line: &'a str,
        pos: usize,
        col: usize,
        partial_spaces: usize,
    }

    impl<'a> LineCursor<'a> {
        fn new(line: &'a str) -> Self {
            Self {
                line,
                pos: 0,
                col: 0,
                partial_spaces: 0,
            }
        }

        fn measure_indent(&self) -> (usize, usize, usize, bool) {
            let bytes = self.line.as_bytes();
            let mut p = self.pos;
            let mut c = self.col;
            let mut spaces = self.partial_spaces;
            c += self.partial_spaces;
            while p < bytes.len() {
                match bytes[p] {
                    b' ' => {
                        p += 1;
                        c += 1;
                        spaces += 1;
                    }
                    b'\t' => {
                        let tab_w = 4 - (c % 4);
                        p += 1;
                        c += tab_w;
                        spaces += tab_w;
                    }
                    _ => break,
                }
            }
            (spaces, p, c, p == bytes.len())
        }

        fn advance_columns(&mut self, mut count: usize) {
            if self.partial_spaces > 0 {
                let take = count.min(self.partial_spaces);
                self.partial_spaces -= take;
                self.col += take;
                count -= take;
            }
            let bytes = self.line.as_bytes();
            while count > 0 && self.pos < bytes.len() {
                match bytes[self.pos] {
                    b' ' => {
                        self.pos += 1;
                        self.col += 1;
                        count -= 1;
                    }
                    b'\t' => {
                        let tab_w = 4 - (self.col % 4);
                        self.pos += 1;
                        if count >= tab_w {
                            self.col += tab_w;
                            count -= tab_w;
                        } else {
                            self.col += count;
                            self.partial_spaces = tab_w - count;
                            count = 0;
                        }
                    }
                    _ => break,
                }
            }
        }

        fn consume_optional_space(&mut self) {
            if self.partial_spaces > 0 {
                self.partial_spaces -= 1;
                self.col += 1;
                return;
            }
            let bytes = self.line.as_bytes();
            if self.pos < bytes.len() {
                match bytes[self.pos] {
                    b' ' => {
                        self.pos += 1;
                        self.col += 1;
                    }
                    b'\t' => {
                        let tab_w = 4 - (self.col % 4);
                        self.pos += 1;
                        self.col += 1;
                        self.partial_spaces = tab_w - 1;
                    }
                    _ => {}
                }
            }
        }

        fn rest_string(&self) -> String {
            if self.partial_spaces == 0 {
                self.line[self.pos..].to_owned()
            } else {
                let mut out =
                    String::with_capacity(self.partial_spaces + self.line.len() - self.pos);
                for _ in 0..self.partial_spaces {
                    out.push(' ');
                }
                out.push_str(&self.line[self.pos..]);
                out
            }
        }

        fn is_blank(&self) -> bool {
            let (_, _, _, blank) = self.measure_indent();
            blank
        }
    }

    pub(super) fn render_markdown_html_impl(markdown: &str) -> Result<String, String> {
        let normalized = preprocess_markdown(markdown);
        let (mut blocks, defs) = parse_blocks(&normalized)?;
        populate_inlines(&mut blocks, &defs)?;
        check_ast_depth(&blocks)?;

        let mut heading_texts = Vec::new();
        collect_heading_texts(&blocks, &mut heading_texts);
        let slugs = heading_slugs(&heading_texts);
        let mut slug_idx = 0;

        let mut out = String::with_capacity(normalized.len() + normalized.len() / 4 + 32);
        render_blocks(&blocks, false, &slugs, &mut slug_idx, &mut out);
        Ok(out)
    }

    pub(super) fn extract_first_heading_title(markdown: &str) -> Result<Option<String>, String> {
        let normalized = preprocess_markdown(markdown);
        let (mut blocks, defs) = parse_blocks(&normalized)?;
        populate_inlines(&mut blocks, &defs)?;
        check_ast_depth(&blocks)?;

        let mut stack: Vec<&MdBlock> = Vec::with_capacity(blocks.len());
        for b in blocks.iter().rev() {
            stack.push(b);
        }
        while let Some(b) = stack.pop() {
            match b {
                MdBlock::BlockQuote(children) => {
                    for c in children.iter().rev() {
                        stack.push(c);
                    }
                }
                MdBlock::List { items, .. } => {
                    for item in items.iter().rev() {
                        for child in item.iter().rev() {
                            stack.push(child);
                        }
                    }
                }
                MdBlock::Heading { inlines, .. } => {
                    let text = extract_heading_plain_text(inlines);
                    return Ok(if text.chars().all(char::is_whitespace) {
                        None
                    } else {
                        Some(text)
                    });
                }
                _ => {}
            }
        }
        Ok(None)
    }

    fn preprocess_markdown(raw: &str) -> String {
        let has_cr = raw.as_bytes().contains(&b'\r');
        let has_nul = raw.as_bytes().contains(&b'\0');
        if !has_cr && !has_nul {
            return raw.to_owned();
        }
        let mut out = String::with_capacity(raw.len());
        let mut chars = raw.chars().peekable();
        while let Some(ch) = chars.next() {
            match ch {
                '\0' => out.push('\u{FFFD}'),
                '\r' => {
                    if chars.peek() == Some(&'\n') {
                        chars.next();
                    }
                    out.push('\n');
                }
                _ => out.push(ch),
            }
        }
        out
    }

    fn split_lines(input: &str) -> Vec<&str> {
        if input.is_empty() {
            return Vec::new();
        }
        let trimmed = input.strip_suffix('\n').unwrap_or(input);
        if trimmed.is_empty() && input == "\n" {
            return vec![""];
        }
        trimmed.split('\n').collect()
    }

    fn parse_blocks(input: &str) -> Result<(Vec<MdBlock>, HashMap<String, LinkDef>), String> {
        let mut stack: Vec<OpenBlock> = vec![OpenBlock::Document(Vec::new())];
        let mut defs: HashMap<String, LinkDef> = HashMap::new();

        for raw_line in split_lines(input) {
            let mut cursor = LineCursor::new(raw_line);
            let mut matched = 0usize;
            let mut line_consumed = false;

            // Phase 1a: continue open blocks from root downward.
            for idx in 1..stack.len() {
                let is_leaf_para = matches!(stack.last(), Some(OpenBlock::Paragraph(_)));
                let is_leaf_item = matches!(stack.last(), Some(OpenBlock::ListItem { .. }));
                let is_deepest = idx + 1 == stack.len();
                match &mut stack[idx] {
                    OpenBlock::BlockQuote(_) => {
                        let (indent, next_pos, next_col, blank) = cursor.measure_indent();
                        if !blank
                            && indent <= 3
                            && cursor.line.as_bytes().get(next_pos) == Some(&b'>')
                        {
                            cursor.pos = next_pos + 1;
                            cursor.col = next_col + 1;
                            cursor.partial_spaces = 0;
                            cursor.consume_optional_space();
                            matched = idx;
                        } else {
                            break;
                        }
                    }
                    OpenBlock::List {
                        had_blank_line,
                        lines_after_blank,
                        ..
                    } => {
                        if cursor.is_blank() {
                            *had_blank_line = true;
                            *lines_after_blank = 0;
                        } else if *had_blank_line {
                            *lines_after_blank += 1;
                        }
                        matched = idx;
                    }
                    OpenBlock::ListItem {
                        content_indent,
                        had_blank_line,
                        blocks,
                    } => {
                        let (indent, next_pos, next_col, blank) = cursor.measure_indent();
                        if blank {
                            // Empty list item cannot continue past a blank line if it still has no blocks
                            // AND is the deepest open block.
                            if blocks.is_empty() && is_deepest {
                                break;
                            }
                            *had_blank_line = is_leaf_para || is_leaf_item;
                            cursor.pos = next_pos;
                            cursor.col = next_col;
                            cursor.partial_spaces = 0;
                            matched = idx;
                        } else if indent >= *content_indent {
                            cursor.advance_columns(*content_indent);
                            matched = idx;
                        } else {
                            break;
                        }
                    }
                    OpenBlock::Paragraph(_) => {
                        if cursor.is_blank() {
                            break;
                        }
                        matched = idx;
                    }
                    OpenBlock::FencedCode {
                        fence_char,
                        fence_len,
                        fence_indent,
                        lines,
                        ..
                    } => {
                        let (indent, next_pos, _, blank) = cursor.measure_indent();
                        if !blank
                            && indent <= 3
                            && is_closing_fence(&cursor.line[next_pos..], *fence_char, *fence_len)
                        {
                            matched = idx;
                            close_blocks_from(&mut stack, idx, &mut defs)?;
                            line_consumed = true;
                            break;
                        }
                        let mut skip = *fence_indent;
                        while skip > 0
                            && cursor.pos < cursor.line.len()
                            && cursor.line.as_bytes()[cursor.pos] == b' '
                        {
                            cursor.pos += 1;
                            cursor.col += 1;
                            skip -= 1;
                        }
                        lines.push(cursor.rest_string());
                        matched = idx;
                        line_consumed = true;
                        break;
                    }
                    OpenBlock::IndentedCode { lines } => {
                        let (indent, next_pos, next_col, blank) = cursor.measure_indent();
                        if blank {
                            cursor.advance_columns(indent.min(4));
                            lines.push(cursor.rest_string());
                            matched = idx;
                            line_consumed = true;
                            break;
                        } else if indent >= 4 {
                            cursor.advance_columns(4);
                            lines.push(cursor.rest_string());
                            matched = idx;
                            line_consumed = true;
                            break;
                        } else {
                            let _ = (next_pos, next_col);
                            break;
                        }
                    }
                    OpenBlock::HtmlBlock { kind, lines } => {
                        let blank = cursor.is_blank();
                        if blank
                            && matches!(
                                kind,
                                HtmlBlockType::StandardTag | HtmlBlockType::CompleteTag
                            )
                        {
                            break;
                        }
                        let rest = cursor.rest_string();
                        let should_close = html_block_closes(*kind, &rest);
                        lines.push(rest);
                        matched = idx;
                        line_consumed = true;
                        if should_close {
                            close_blocks_from(&mut stack, idx, &mut defs)?;
                        }
                        break;
                    }
                    OpenBlock::Table {
                        row_lines,
                        allow_lazy,
                        ..
                    } => {
                        let (_, next_pos, _, blank) = cursor.measure_indent();
                        if blank {
                            break;
                        }
                        let slice = &cursor.line[next_pos..];
                        if let Some(rel_pipe) = slice.find('|') {
                            if rel_pipe == 0
                                && slice[1..]
                                    .trim_matches(|c| c == ' ' || c == '\t')
                                    .is_empty()
                            {
                                *allow_lazy = false;
                                break;
                            }
                            row_lines.push(cursor.rest_string());
                            matched = idx;
                            line_consumed = true;
                            break;
                        } else {
                            break;
                        }
                    }
                    OpenBlock::Document(_) => unreachable!(),
                }
            }

            if line_consumed {
                continue;
            }

            // Phase 1b: try opening new blocks.
            let mut all_matched = matched == stack.len() - 1;
            let mut can_lazy_para =
                !cursor.is_blank() && matches!(stack.last(), Some(OpenBlock::Paragraph(_)));
            let mut can_lazy_table = !cursor.is_blank()
                && matches!(
                    stack.last(),
                    Some(OpenBlock::Table {
                        allow_lazy: true,
                        ..
                    })
                );

            loop {
                if stack.len() > MAX_MARKDOWN_DEPTH + 1 {
                    return Err("Markdown nesting is too deep.".into());
                }

                let (indent, next_pos, next_col, blank) = cursor.measure_indent();
                if blank {
                    close_blocks_from(&mut stack, matched + 1, &mut defs)?;
                    line_consumed = true;
                    break;
                }

                let in_matched_paragraph =
                    all_matched && matches!(stack.last(), Some(OpenBlock::Paragraph(_)));
                let rem = &cursor.line[next_pos..];

                if indent >= 4 {
                    if !in_matched_paragraph && !can_lazy_para && !can_lazy_table {
                        close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                        cursor.advance_columns(4);
                        stack.push(OpenBlock::IndentedCode {
                            lines: vec![cursor.rest_string()],
                        });
                        line_consumed = true;
                        break;
                    }
                    break;
                }

                // 1. BlockQuote (`>`)
                if rem.starts_with('>') {
                    close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                    cursor.pos = next_pos + 1;
                    cursor.col = next_col + 1;
                    cursor.partial_spaces = 0;
                    cursor.consume_optional_space();
                    ensure_list_tight_before_child(&mut stack);
                    stack.push(OpenBlock::BlockQuote(Vec::new()));
                    matched = stack.len() - 1;
                    all_matched = true;
                    can_lazy_para = false;
                    can_lazy_table = false;
                    continue;
                }

                // 2. ATX Heading
                if rem.starts_with('#') {
                    if let Some((level, text)) = parse_atx_heading(rem) {
                        close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                        ensure_list_tight_before_child(&mut stack);
                        push_finished_block(
                            &mut stack,
                            MdBlock::Heading {
                                level,
                                raw: text,
                                inlines: Vec::new(),
                            },
                        );
                        line_consumed = true;
                        break;
                    }
                }

                // 3. Fenced Code Block
                if rem.starts_with("```") || rem.starts_with("~~~") {
                    if let Some((fence_char, fence_len, info)) = parse_fence_open(rem) {
                        close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                        ensure_list_tight_before_child(&mut stack);
                        stack.push(OpenBlock::FencedCode {
                            fence_char,
                            fence_len,
                            fence_indent: indent,
                            info,
                            lines: Vec::new(),
                        });
                        line_consumed = true;
                        break;
                    }
                }

                // 4. HTML Block
                if rem.starts_with('<') {
                    if let Some(kind) = detect_html_block_start(rem) {
                        let can_interrupt = kind != HtmlBlockType::CompleteTag;
                        if can_interrupt
                            || (!in_matched_paragraph && !can_lazy_para && !can_lazy_table)
                        {
                            close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                            ensure_list_tight_before_child(&mut stack);
                            cursor.pos = next_pos;
                            cursor.col = next_col;
                            cursor.partial_spaces = 0;
                            let rest = cursor.rest_string();
                            let closes_immediately = html_block_closes(kind, &rest);
                            stack.push(OpenBlock::HtmlBlock {
                                kind,
                                lines: vec![rest],
                            });
                            if closes_immediately {
                                let top_idx = stack.len() - 1;
                                close_blocks_from(&mut stack, top_idx, &mut defs)?;
                            }
                            line_consumed = true;
                            break;
                        }
                    }
                }

                // 5. Setext Heading (must have an active matched paragraph with non-def lines)
                if in_matched_paragraph {
                    if let Some(level) = parse_setext_underline(rem) {
                        if let Some(OpenBlock::Paragraph(para)) = stack.last_mut() {
                            flush_paragraph_defs(para, &mut defs);
                            if !para.active_lines().is_empty() {
                                let raw = para.active_lines().join("\n");
                                stack.pop();
                                push_finished_block(
                                    &mut stack,
                                    MdBlock::Heading {
                                        level,
                                        raw,
                                        inlines: Vec::new(),
                                    },
                                );
                                line_consumed = true;
                                break;
                            }
                        }
                    }
                }

                // 6. GFM Table (must have an active matched paragraph whose last line has `|`)
                if in_matched_paragraph {
                    let sep_slice = &cursor.line[cursor.pos..];
                    if let Some(alignments) = parse_table_separator(sep_slice) {
                        let mut table_header: Option<String> = None;
                        if let Some(OpenBlock::Paragraph(para)) = stack.last_mut() {
                            flush_paragraph_defs(para, &mut defs);
                            let active = para.active_lines();
                            if let Some(last_line) = active.last() {
                                if last_line.contains('|') {
                                    let header_cells = split_table_row(last_line);
                                    if alignments.len() >= header_cells.len() {
                                        table_header = para.lines.pop();
                                    }
                                }
                            }
                        }
                        if let Some(header_line) = table_header {
                            // If paragraph now has no remaining active lines, remove it; otherwise close it!
                            let remove_para = matches!(
                                stack.last(),
                                Some(OpenBlock::Paragraph(p)) if p.active_lines().is_empty()
                            );
                            if remove_para {
                                stack.pop();
                            } else {
                                let top_idx = stack.len() - 1;
                                close_blocks_from(&mut stack, top_idx, &mut defs)?;
                            }
                            ensure_list_tight_before_child(&mut stack);
                            stack.push(OpenBlock::Table {
                                alignments,
                                row_lines: vec![header_line],
                                allow_lazy: true,
                            });
                            line_consumed = true;
                            break;
                        }
                    }
                }

                // 7. Thematic Break
                if is_thematic_break(rem) {
                    close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                    ensure_list_tight_before_child(&mut stack);
                    push_finished_block(&mut stack, MdBlock::ThematicBreak);
                    line_consumed = true;
                    break;
                }

                // 8. List Item
                if let Some((kind, start, marker_bytes, is_empty_after)) =
                    parse_list_marker(rem, in_matched_paragraph)
                {
                    if in_matched_paragraph && is_empty_after {
                        // Empty list items cannot interrupt a paragraph.
                    } else if in_matched_paragraph
                        && matches!(kind, ListKind::Ordered(_))
                        && start != 1
                    {
                        // Ordered lists can only interrupt a paragraph if starting at 1.
                    } else {
                        let marker_col = next_col + marker_bytes;
                        let content_col = compute_list_content_col(
                            cursor.line,
                            next_pos + marker_bytes,
                            marker_col,
                        );
                        let item_content_indent = content_col - cursor.col;

                        // Close blocks above `matched`, except keep `List` at `matched` if it matches!
                        let can_reuse_list = matches!(
                            stack.get(matched),
                            Some(OpenBlock::List { kind: existing, .. }) if *existing == kind
                        );
                        if can_reuse_list {
                            close_blocks_from(&mut stack, matched + 1, &mut defs)?;
                            if let Some(OpenBlock::List {
                                tight,
                                had_blank_line,
                                lines_after_blank,
                                ..
                            }) = stack.get_mut(matched)
                            {
                                if *had_blank_line && *lines_after_blank == 1 {
                                    *tight = false;
                                    *had_blank_line = false;
                                }
                            }
                        } else {
                            close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
                            ensure_list_tight_before_child(&mut stack);
                            stack.push(OpenBlock::List {
                                kind,
                                start,
                                tight: true,
                                had_blank_line: false,
                                lines_after_blank: 0,
                                items: Vec::new(),
                            });
                        }

                        cursor.pos = next_pos + marker_bytes;
                        cursor.col = marker_col;
                        cursor.partial_spaces = 0;
                        cursor.advance_columns(content_col - marker_col);
                        stack.push(OpenBlock::ListItem {
                            content_indent: item_content_indent,
                            had_blank_line: false,
                            blocks: Vec::new(),
                        });
                        matched = stack.len() - 1;
                        all_matched = true;
                        can_lazy_para = false;
                        can_lazy_table = false;
                        continue;
                    }
                }

                break;
            }

            if line_consumed {
                continue;
            }

            // Phase 1c: add text to active/lazy paragraph, lazy table, or start a new paragraph.
            let (_, next_pos, next_col, blank) = cursor.measure_indent();
            if blank {
                close_blocks_from(&mut stack, matched + 1, &mut defs)?;
                continue;
            }
            cursor.pos = next_pos;
            cursor.col = next_col;
            cursor.partial_spaces = 0;

            if can_lazy_table && !all_matched {
                if let Some(OpenBlock::Table { row_lines, .. }) = stack.last_mut() {
                    row_lines.push(cursor.rest_string());
                    continue;
                }
            }

            if all_matched || can_lazy_para {
                if let Some(OpenBlock::Paragraph(para)) = stack.last_mut() {
                    para.lines.push(cursor.rest_string());
                    flush_paragraph_defs(para, &mut defs);
                    continue;
                }
            }

            close_unmatched_blocks(&mut stack, &mut matched, &mut defs)?;
            ensure_list_tight_before_child(&mut stack);
            let mut para = ParagraphLines::new(cursor.rest_string());
            flush_paragraph_defs(&mut para, &mut defs);
            stack.push(OpenBlock::Paragraph(para));
        }

        close_blocks_from(&mut stack, 1, &mut defs)?;
        let Some(OpenBlock::Document(root_blocks)) = stack.pop() else {
            return Err("Markdown block stack corrupted".into());
        };
        Ok((root_blocks, defs))
    }

    fn ensure_list_tight_before_child(stack: &mut [OpenBlock]) {
        let len = stack.len();
        if len >= 2 {
            if let OpenBlock::ListItem {
                had_blank_line: true,
                ..
            } = &stack[len - 1]
            {
                if let OpenBlock::List { tight, .. } = &mut stack[len - 2] {
                    *tight = false;
                }
            }
        }
    }

    fn push_finished_block(stack: &mut [OpenBlock], block: MdBlock) {
        match stack.last_mut() {
            Some(OpenBlock::Document(blocks)) | Some(OpenBlock::BlockQuote(blocks)) => {
                blocks.push(block);
            }
            Some(OpenBlock::ListItem { blocks, .. }) => {
                blocks.push(block);
            }
            _ => {}
        }
    }

    fn close_unmatched_blocks(
        stack: &mut Vec<OpenBlock>,
        matched: &mut usize,
        defs: &mut HashMap<String, LinkDef>,
    ) -> Result<(), String> {
        while matches!(
            stack.get(*matched),
            Some(OpenBlock::List { .. } | OpenBlock::Paragraph(_) | OpenBlock::Table { .. })
        ) {
            *matched -= 1;
        }
        close_blocks_from(stack, *matched + 1, defs)
    }

    fn close_blocks_from(
        stack: &mut Vec<OpenBlock>,
        from_idx: usize,
        defs: &mut HashMap<String, LinkDef>,
    ) -> Result<(), String> {
        while stack.len() > from_idx {
            let block = stack.pop().unwrap();
            let finished = match block {
                OpenBlock::Document(_) => return Err("Cannot close document root".into()),
                OpenBlock::BlockQuote(children) => Some(MdBlock::BlockQuote(children)),
                OpenBlock::List {
                    kind,
                    start,
                    tight,
                    items,
                    ..
                } => Some(MdBlock::List {
                    ordered: matches!(kind, ListKind::Ordered(_)),
                    start,
                    tight,
                    items,
                }),
                OpenBlock::ListItem { blocks, .. } => {
                    if let Some(OpenBlock::List { items, .. }) = stack.last_mut() {
                        items.push(blocks);
                    }
                    None
                }
                OpenBlock::Paragraph(mut para) => {
                    flush_paragraph_defs(&mut para, defs);
                    let active = para.active_lines();
                    if active.is_empty() {
                        None
                    } else {
                        Some(MdBlock::Paragraph {
                            raw: active.join("\n"),
                            inlines: Vec::new(),
                        })
                    }
                }
                OpenBlock::FencedCode { info, lines, .. } => {
                    let mut literal = String::new();
                    for line in lines {
                        literal.push_str(&line);
                        literal.push('\n');
                    }
                    Some(MdBlock::FencedCode {
                        info: unescape_md_string(info.trim()),
                        literal,
                    })
                }
                OpenBlock::IndentedCode { mut lines } => {
                    while lines
                        .last()
                        .is_some_and(|l| l.chars().all(|c| c == ' ' || c == '\t'))
                    {
                        lines.pop();
                    }
                    let mut literal = String::new();
                    for line in lines {
                        literal.push_str(&line);
                        literal.push('\n');
                    }
                    Some(MdBlock::IndentedCode { literal })
                }
                OpenBlock::HtmlBlock { lines, .. } => {
                    let literal = lines.join("\n");
                    Some(MdBlock::HtmlBlock { literal })
                }
                OpenBlock::Table {
                    alignments,
                    row_lines,
                    ..
                } => {
                    let mut cell_count = 0usize;
                    let header_raw =
                        split_table_row(row_lines.first().map(String::as_str).unwrap_or(""));
                    let header_cols = header_raw.len();
                    let mut head = Vec::with_capacity(header_cols);
                    for (i, cell) in header_raw.into_iter().enumerate() {
                        cell_count += 1;
                        if cell_count > MAX_TABLE_CELLS {
                            return Err("Markdown table cell count exceeds limit.".into());
                        }
                        let _ = i;
                        head.push(MdTableCell {
                            raw: trim_space_tab(&cell).to_owned(),
                            inlines: Vec::new(),
                        });
                    }
                    let mut body = Vec::new();
                    for row_line in row_lines.iter().skip(1) {
                        let raw_cells = split_table_row(row_line);
                        let mut row = Vec::with_capacity(header_cols);
                        for i in 0..header_cols {
                            cell_count += 1;
                            if cell_count > MAX_TABLE_CELLS {
                                return Err("Markdown table cell count exceeds limit.".into());
                            }
                            let cell_str = raw_cells.get(i).map(String::as_str).unwrap_or("");
                            row.push(MdTableCell {
                                raw: trim_space_tab(cell_str).to_owned(),
                                inlines: Vec::new(),
                            });
                        }
                        body.push(row);
                    }
                    Some(MdBlock::Table {
                        alignments,
                        head,
                        body,
                    })
                }
            };
            if let Some(item) = finished {
                push_finished_block(stack, item);
            }
        }
        Ok(())
    }

    fn trim_space_tab(s: &str) -> &str {
        s.trim_matches(|c| c == ' ' || c == '\t')
    }

    fn flush_paragraph_defs(para: &mut ParagraphLines, defs: &mut HashMap<String, LinkDef>) {
        // Parse any complete link reference definitions starting at `defs_prefix_len`.
        loop {
            let remaining_lines = &para.lines[para.defs_prefix_len..];
            if remaining_lines.is_empty() {
                break;
            }
            let joined = remaining_lines.join("\n");
            let Some((key, def, lines_consumed)) = try_parse_link_def_prefix(&joined) else {
                break;
            };
            defs.entry(key).or_insert(def);
            para.defs_prefix_len += lines_consumed;
        }
    }

    fn try_parse_link_def_prefix(s: &str) -> Option<(String, LinkDef, usize)> {
        let bytes = s.as_bytes();
        let mut i = 0usize;
        while i < 3 && i < bytes.len() && bytes[i] == b' ' {
            i += 1;
        }
        if bytes.get(i) != Some(&b'[') {
            return None;
        }
        i += 1;
        let label_start = i;
        let mut label_chars = 0usize;
        let mut has_non_ws = false;
        while i < bytes.len() {
            let b = bytes[i];
            if b == b'\\' {
                i += 1;
                if i < bytes.len() {
                    let ch = s[i..].chars().next()?;
                    i += ch.len_utf8();
                    label_chars += 1;
                    if !is_md_whitespace(ch) {
                        has_non_ws = true;
                    }
                    continue;
                }
                return None;
            }
            if b == b'[' {
                return None;
            }
            if b == b']' {
                break;
            }
            let ch = s[i..].chars().next()?;
            i += ch.len_utf8();
            label_chars += 1;
            if label_chars > 999 {
                return None;
            }
            if !is_md_whitespace(ch) {
                has_non_ws = true;
            }
        }
        if bytes.get(i) != Some(&b']') || !has_non_ws {
            return None;
        }
        let raw_label = &s[label_start..i];
        i += 1; // skip ']'
        if bytes.get(i) != Some(&b':') {
            return None;
        }
        i += 1; // skip ':'

        // Optional spaces/tabs and at most one newline.
        while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
            i += 1;
        }
        if bytes.get(i) == Some(&b'\n') {
            i += 1;
            while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
                i += 1;
            }
        }

        // Destination
        let (dest, after_dest) = parse_link_destination(s, i)?;
        i = after_dest;

        // Check after destination: spaces/tabs + optional newline + optional title
        let after_dest_ws_start = i;
        let mut ws_count = 0usize;
        while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
            i += 1;
            ws_count += 1;
        }
        let mut saw_newline = false;
        if bytes.get(i) == Some(&b'\n') {
            saw_newline = true;
            i += 1;
            while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
                i += 1;
                ws_count += 1;
            }
        }

        let mut title: Option<String> = None;
        let mut end_offset = None;

        if ws_count > 0 && i < bytes.len() && matches!(bytes[i], b'"' | b'\'' | b'(') {
            if let Some((parsed_title, after_title)) = parse_link_title(s, i) {
                let mut t_end = after_title;
                while t_end < bytes.len() && (bytes[t_end] == b' ' || bytes[t_end] == b'\t') {
                    t_end += 1;
                }
                if t_end == bytes.len() || bytes[t_end] == b'\n' {
                    title = Some(parsed_title);
                    end_offset = Some(if t_end < bytes.len() {
                        t_end + 1
                    } else {
                        t_end
                    });
                }
            }
        }

        if end_offset.is_none() {
            // Backtrack before newline if there wasn't a valid title on the next line.
            if saw_newline {
                let nl_pos = after_dest_ws_start + s[after_dest_ws_start..].find('\n').unwrap_or(0);
                end_offset = Some(nl_pos + 1);
            } else if i == bytes.len() {
                end_offset = Some(bytes.len());
            } else {
                return None;
            }
        }

        let consumed_end = end_offset?;
        let lines_consumed = s[..consumed_end].bytes().filter(|&b| b == b'\n').count()
            + usize::from(consumed_end == bytes.len() && !s.ends_with('\n'));
        let normalized_key = normalize_label_content(raw_label);
        if normalized_key.is_empty() {
            return None;
        }
        Some((
            normalized_key,
            LinkDef {
                dest: unescape_md_string(&dest),
                title: title.map(|t| unescape_md_string(&t)),
            },
            lines_consumed.max(1),
        ))
    }

    fn parse_atx_heading(rem: &str) -> Option<(u8, String)> {
        let bytes = rem.as_bytes();
        let mut level = 0usize;
        while level < bytes.len() && bytes[level] == b'#' {
            level += 1;
            if level > 6 {
                return None;
            }
        }
        if level == 0 {
            return None;
        }
        if level < bytes.len() && !matches!(bytes[level], b' ' | b'\t') {
            return None;
        }
        let content = rem[level..].trim_matches(|c| c == ' ' || c == '\t');
        if content.is_empty() {
            return Some((level as u8, String::new()));
        }
        let no_trailing_hashes = content.trim_end_matches('#');
        let cleaned = if no_trailing_hashes.is_empty() {
            ""
        } else if no_trailing_hashes.len() < content.len()
            && no_trailing_hashes.ends_with([' ', '\t'])
        {
            no_trailing_hashes.trim_end_matches([' ', '\t'])
        } else {
            content
        };
        Some((level as u8, cleaned.to_owned()))
    }

    fn parse_setext_underline(rem: &str) -> Option<u8> {
        let bytes = rem.as_bytes();
        let marker = *bytes.first()?;
        if marker != b'=' && marker != b'-' {
            return None;
        }
        let mut i = 1usize;
        while i < bytes.len() && bytes[i] == marker {
            i += 1;
        }
        while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\t') {
            i += 1;
        }
        if i == bytes.len() {
            Some(if marker == b'=' { 1 } else { 2 })
        } else {
            None
        }
    }

    fn is_thematic_break(rem: &str) -> bool {
        let bytes = rem.as_bytes();
        let Some(&marker) = bytes.first() else {
            return false;
        };
        if !matches!(marker, b'*' | b'-' | b'_') {
            return false;
        }
        let mut count = 0usize;
        for &b in bytes {
            if b == marker {
                count += 1;
            } else if b != b' ' && b != b'\t' {
                return false;
            }
        }
        count >= 3
    }

    fn parse_fence_open(rem: &str) -> Option<(u8, usize, String)> {
        let bytes = rem.as_bytes();
        let marker = *bytes.first()?;
        if marker != b'`' && marker != b'~' {
            return None;
        }
        let mut len = 0usize;
        while len < bytes.len() && bytes[len] == marker {
            len += 1;
        }
        if len < 3 {
            return None;
        }
        let info = &rem[len..];
        if marker == b'`' && info.as_bytes().contains(&b'`') {
            return None;
        }
        Some((marker, len, info.to_owned()))
    }

    fn is_closing_fence(rem: &str, fence_char: u8, min_len: usize) -> bool {
        let bytes = rem.as_bytes();
        let mut count = 0usize;
        while count < bytes.len() && bytes[count] == fence_char {
            count += 1;
        }
        if count < min_len {
            return false;
        }
        bytes[count..].iter().all(|&b| b == b' ' || b == b'\t')
    }

    fn parse_list_marker(rem: &str, in_paragraph: bool) -> Option<(ListKind, u32, usize, bool)> {
        let bytes = rem.as_bytes();
        let first = *bytes.first()?;
        if matches!(first, b'-' | b'*' | b'+') {
            if bytes.len() > 1 && !matches!(bytes[1], b' ' | b'\t') {
                return None;
            }
            // Note: thematic break has priority over bullet list!
            if first != b'+' && is_thematic_break(rem) {
                return None;
            }
            let empty_after = bytes[1..].iter().all(|&b| b == b' ' || b == b'\t');
            let _ = in_paragraph;
            return Some((ListKind::Bullet(first), 1, 1, empty_after));
        }

        if first.is_ascii_digit() {
            let mut digits = 0usize;
            while digits < bytes.len() && bytes[digits].is_ascii_digit() {
                digits += 1;
                if digits > 9 {
                    return None;
                }
            }
            let delim = *bytes.get(digits)?;
            if delim != b'.' && delim != b')' {
                return None;
            }
            if digits + 1 < bytes.len() && !matches!(bytes[digits + 1], b' ' | b'\t') {
                return None;
            }
            let start = rem[..digits].parse::<u32>().ok()?;
            let empty_after = bytes[digits + 1..].iter().all(|&b| b == b' ' || b == b'\t');
            return Some((ListKind::Ordered(delim), start, digits + 1, empty_after));
        }
        None
    }

    fn compute_list_content_col(
        line: &str,
        after_marker_pos: usize,
        marker_end_col: usize,
    ) -> usize {
        let bytes = line.as_bytes();
        let mut p = after_marker_pos;
        let mut c = marker_end_col;
        let mut spaces_after = 0usize;
        while p < bytes.len() {
            match bytes[p] {
                b' ' => {
                    p += 1;
                    c += 1;
                    spaces_after += 1;
                }
                b'\t' => {
                    let tab_w = 4 - (c % 4);
                    p += 1;
                    c += tab_w;
                    spaces_after += tab_w;
                }
                _ => break,
            }
        }
        let is_blank_after = p == bytes.len();
        if is_blank_after || spaces_after >= 5 {
            marker_end_col + 1
        } else {
            marker_end_col + spaces_after.max(1)
        }
    }

    fn parse_table_separator(s: &str) -> Option<Vec<Option<TableAlign>>> {
        let bytes = s.as_bytes();
        let mut columns = Vec::new();
        let mut pipes = 0usize;
        let mut valid = false;
        let mut i = 0usize;
        while i < bytes.len() {
            match bytes[i] {
                b'|' => {
                    i += 1;
                    pipes += 1;
                    if pipes > 1 {
                        return None;
                    }
                    valid = true;
                }
                b'-' | b':' => {
                    if pipes == 0 && !columns.is_empty() {
                        return None;
                    }
                    let mut left = false;
                    let mut right = false;
                    if bytes[i] == b':' {
                        left = true;
                        i += 1;
                    }
                    let mut have_dash = false;
                    while i < bytes.len() && bytes[i] == b'-' {
                        i += 1;
                        have_dash = true;
                    }
                    if !have_dash {
                        return None;
                    }
                    if i < bytes.len() && bytes[i] == b':' {
                        right = true;
                        i += 1;
                    }
                    let align = match (left, right) {
                        (true, true) => Some(TableAlign::Center),
                        (true, false) => Some(TableAlign::Left),
                        (false, true) => Some(TableAlign::Right),
                        (false, false) => None,
                    };
                    columns.push(align);
                    pipes = 0;
                }
                b' ' | b'\t' => i += 1,
                _ => return None,
            }
        }
        if !valid || columns.is_empty() {
            None
        } else {
            Some(columns)
        }
    }

    fn split_table_row(line: &str) -> Vec<String> {
        let bytes = line.as_bytes();
        let mut non_space = 0usize;
        while non_space < bytes.len() && (bytes[non_space] == b' ' || bytes[non_space] == b'\t') {
            non_space += 1;
        }
        let mut cell_start = non_space;
        let mut cell_end = bytes.len();
        if bytes.get(non_space) == Some(&b'|') {
            cell_start = non_space + 1;
            while cell_end > cell_start
                && (bytes[cell_end - 1] == b' ' || bytes[cell_end - 1] == b'\t')
            {
                cell_end -= 1;
            }
        }
        let mut cells = Vec::new();
        let mut sb = String::new();
        let mut i = cell_start;
        while i < cell_end {
            match bytes[i] {
                b'\\' => {
                    if i + 1 < cell_end && bytes[i + 1] == b'|' {
                        sb.push('|');
                        i += 2;
                    } else {
                        sb.push('\\');
                        i += 1;
                    }
                }
                b'|' => {
                    cells.push(std::mem::take(&mut sb));
                    i += 1;
                }
                _ => {
                    let ch = line[i..].chars().next().unwrap();
                    sb.push(ch);
                    i += ch.len_utf8();
                }
            }
        }
        if !sb.is_empty() {
            cells.push(sb);
        }
        cells
    }

    fn detect_html_block_start(rem: &str) -> Option<HtmlBlockType> {
        if rem.starts_with("<!--") {
            return Some(HtmlBlockType::Comment);
        }
        if rem.starts_with("<?") {
            return Some(HtmlBlockType::ProcessingInstr);
        }
        if rem.starts_with("<![CDATA[") {
            return Some(HtmlBlockType::Cdata);
        }
        let bytes = rem.as_bytes();
        if bytes.len() >= 3 && bytes[0] == b'<' && bytes[1] == b'!' && bytes[2].is_ascii_uppercase()
        {
            return Some(HtmlBlockType::Declaration);
        }
        // Check Type 1 & Type 6 tag name.
        let after_lt = rem.strip_prefix('<')?;
        let (is_close, name_slice) = if let Some(rest) = after_lt.strip_prefix('/') {
            (true, rest)
        } else {
            (false, after_lt)
        };
        let name_len = name_slice
            .bytes()
            .take_while(|b| b.is_ascii_alphanumeric() || *b == b'-')
            .count();
        if name_len > 0 && name_slice.as_bytes()[0].is_ascii_alphabetic() {
            let tag_lower = name_slice[..name_len].to_ascii_lowercase();
            let after_name = &name_slice.as_bytes()[name_len..];
            let boundary_1 = after_name.is_empty() || matches!(after_name[0], b' ' | b'\t' | b'>');
            if !is_close
                && boundary_1
                && matches!(tag_lower.as_str(), "script" | "pre" | "style" | "textarea")
            {
                return Some(HtmlBlockType::ScriptPreStyleTextarea);
            }
            let boundary_6 = after_name.is_empty()
                || matches!(after_name[0], b' ' | b'\t' | b'>')
                || after_name.starts_with(b"/>");
            if boundary_6 && is_html_block_type6_tag(&tag_lower) {
                return Some(HtmlBlockType::StandardTag);
            }
        }
        // Type 7: single complete open or close tag followed only by whitespace.
        if let Some(tag_len) = parse_html_inline_tag(rem) {
            if rem[tag_len..].bytes().all(|b| b == b' ' || b == b'\t') {
                return Some(HtmlBlockType::CompleteTag);
            }
        }
        None
    }

    fn is_html_block_type6_tag(tag: &str) -> bool {
        matches!(
            tag,
            "address"
                | "article"
                | "aside"
                | "base"
                | "basefont"
                | "blockquote"
                | "body"
                | "caption"
                | "center"
                | "col"
                | "colgroup"
                | "dd"
                | "details"
                | "dialog"
                | "dir"
                | "div"
                | "dl"
                | "dt"
                | "fieldset"
                | "figcaption"
                | "figure"
                | "footer"
                | "form"
                | "frame"
                | "frameset"
                | "h1"
                | "h2"
                | "h3"
                | "h4"
                | "h5"
                | "h6"
                | "head"
                | "header"
                | "hr"
                | "html"
                | "iframe"
                | "legend"
                | "li"
                | "link"
                | "main"
                | "menu"
                | "menuitem"
                | "nav"
                | "noframes"
                | "ol"
                | "optgroup"
                | "option"
                | "p"
                | "param"
                | "search"
                | "section"
                | "summary"
                | "table"
                | "tbody"
                | "td"
                | "tfoot"
                | "th"
                | "thead"
                | "title"
                | "tr"
                | "track"
                | "ul"
        )
    }

    fn html_block_closes(kind: HtmlBlockType, line: &str) -> bool {
        match kind {
            HtmlBlockType::ScriptPreStyleTextarea => {
                let lower = line.to_ascii_lowercase();
                lower.contains("</script>")
                    || lower.contains("</pre>")
                    || lower.contains("</style>")
                    || lower.contains("</textarea>")
            }
            HtmlBlockType::Comment => line.contains("-->"),
            HtmlBlockType::ProcessingInstr => line.contains("?>"),
            HtmlBlockType::Declaration => line.contains('>'),
            HtmlBlockType::Cdata => line.contains("]]>"),
            HtmlBlockType::StandardTag | HtmlBlockType::CompleteTag => false,
        }
    }

    fn populate_inlines(
        blocks: &mut [MdBlock],
        defs: &HashMap<String, LinkDef>,
    ) -> Result<(), String> {
        let mut stack: Vec<*mut MdBlock> = Vec::with_capacity(blocks.len());
        for b in blocks.iter_mut().rev() {
            stack.push(b as *mut MdBlock);
        }
        while let Some(ptr) = stack.pop() {
            // SAFETY: every pointer in `stack` comes from a disjoint mutable borrow inside `blocks`.
            let block = unsafe { &mut *ptr };
            match block {
                MdBlock::BlockQuote(children) => {
                    for c in children.iter_mut().rev() {
                        stack.push(c as *mut MdBlock);
                    }
                }
                MdBlock::List { items, .. } => {
                    for item in items.iter_mut().rev() {
                        for b in item.iter_mut().rev() {
                            stack.push(b as *mut MdBlock);
                        }
                    }
                }
                MdBlock::Heading { raw, inlines, .. } | MdBlock::Paragraph { raw, inlines } => {
                    *inlines = parse_inlines(raw, defs)?;
                }
                MdBlock::Table { head, body, .. } => {
                    for cell in head.iter_mut() {
                        cell.inlines = parse_inlines(&cell.raw, defs)?;
                    }
                    for row in body.iter_mut() {
                        for cell in row.iter_mut() {
                            cell.inlines = parse_inlines(&cell.raw, defs)?;
                        }
                    }
                }
                MdBlock::FencedCode { .. }
                | MdBlock::IndentedCode { .. }
                | MdBlock::HtmlBlock { .. }
                | MdBlock::ThematicBreak => {}
            }
        }
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Inline Parser (arena-based, iterative depth enforcement)
    // -------------------------------------------------------------------------

    #[derive(Clone)]
    enum NodeKind {
        Root,
        Text(String),
        Code(String),
        SoftBreak,
        HardBreak,
        HtmlInline(String),
        Emph,
        Strong,
        Strike,
        Link {
            dest: String,
            title: Option<String>,
        },
        Image {
            alt_text: String,
            max_child_depth: usize,
        },
    }

    struct ArenaNode {
        kind: NodeKind,
        prev: Option<usize>,
        next: Option<usize>,
        parent: Option<usize>,
        first_child: Option<usize>,
        last_child: Option<usize>,
        nesting_depth: usize,
        mergeable_text: bool,
    }

    struct InlineArena {
        nodes: Vec<ArenaNode>,
    }

    impl InlineArena {
        fn new() -> Self {
            Self {
                nodes: vec![ArenaNode {
                    kind: NodeKind::Root,
                    prev: None,
                    next: None,
                    parent: None,
                    first_child: None,
                    last_child: None,
                    nesting_depth: 0,
                    mergeable_text: false,
                }],
            }
        }

        fn alloc(&mut self, kind: NodeKind, nesting_depth: usize) -> usize {
            let id = self.nodes.len();
            self.nodes.push(ArenaNode {
                kind,
                prev: None,
                next: None,
                parent: None,
                first_child: None,
                last_child: None,
                nesting_depth,
                mergeable_text: false,
            });
            id
        }

        fn append_child(&mut self, parent: usize, child: usize) {
            let old_last = self.nodes[parent].last_child;
            self.nodes[child].parent = Some(parent);
            self.nodes[child].prev = old_last;
            self.nodes[child].next = None;
            if let Some(last) = old_last {
                self.nodes[last].next = Some(child);
            } else {
                self.nodes[parent].first_child = Some(child);
            }
            self.nodes[parent].last_child = Some(child);
        }

        fn insert_after(&mut self, anchor: usize, new_node: usize) {
            let parent = self.nodes[anchor].parent;
            let next = self.nodes[anchor].next;
            self.nodes[new_node].parent = parent;
            self.nodes[new_node].prev = Some(anchor);
            self.nodes[new_node].next = next;
            self.nodes[anchor].next = Some(new_node);
            if let Some(nxt) = next {
                self.nodes[nxt].prev = Some(new_node);
            } else if let Some(p) = parent {
                self.nodes[p].last_child = Some(new_node);
            }
        }

        fn unlink(&mut self, node: usize) {
            let prev = self.nodes[node].prev;
            let next = self.nodes[node].next;
            let parent = self.nodes[node].parent;
            if let Some(p) = prev {
                self.nodes[p].next = next;
            } else if let Some(par) = parent {
                self.nodes[par].first_child = next;
            }
            if let Some(n) = next {
                self.nodes[n].prev = prev;
            } else if let Some(par) = parent {
                self.nodes[par].last_child = prev;
            }
            self.nodes[node].prev = None;
            self.nodes[node].next = None;
            self.nodes[node].parent = None;
        }

        fn wrap_between(&mut self, start_excl: usize, end_excl: usize, wrapper: usize) {
            // Collect nodes strictly between start_excl and end_excl (must share same parent).
            let mut cur = self.nodes[start_excl].next;
            let mut to_move = Vec::new();
            while let Some(id) = cur {
                if id == end_excl {
                    break;
                }
                to_move.push(id);
                cur = self.nodes[id].next;
            }
            for id in &to_move {
                self.unlink(*id);
            }
            self.insert_after(start_excl, wrapper);
            for id in to_move {
                self.append_child(wrapper, id);
            }
        }

        fn max_depth_between(&self, start_excl: usize, end_excl: usize) -> usize {
            let mut max_d = 0usize;
            let mut cur = self.nodes[start_excl].next;
            while let Some(id) = cur {
                if id == end_excl {
                    break;
                }
                max_d = max_d.max(self.nodes[id].nesting_depth);
                cur = self.nodes[id].next;
            }
            max_d
        }
    }

    struct DelimEntry {
        ch: u8,
        len: usize,
        orig_len: usize,
        can_open: bool,
        can_close: bool,
        node: usize,
        active: bool,
    }

    struct BracketEntry {
        node: usize,
        is_image: bool,
        active: bool,
        prev_delim_idx: Option<usize>,
        content_start_byte: usize,
        content_depth_floor: usize,
    }

    fn parse_inlines(
        input: &str,
        defs: &HashMap<String, LinkDef>,
    ) -> Result<Vec<MdInline>, String> {
        if input.is_empty() {
            return Ok(Vec::new());
        }
        let mut arena = InlineArena::new();
        let mut delims: Vec<DelimEntry> = Vec::new();
        let mut brackets: Vec<BracketEntry> = Vec::new();

        let bytes = input.as_bytes();
        let mut pos = 0usize;

        while pos < bytes.len() {
            match bytes[pos] {
                b'\n' => {
                    // Check preceding text node for trailing spaces -> HardBreak vs SoftBreak.
                    let mut is_hard = false;
                    if let Some(last_id) = arena.nodes[0].last_child {
                        if arena.nodes[last_id].mergeable_text {
                            if let NodeKind::Text(ref mut text) = arena.nodes[last_id].kind {
                                let trimmed_len = text.trim_end_matches(' ').len();
                                let trailing_spaces = text.len() - trimmed_len;
                                if trailing_spaces >= 2 {
                                    is_hard = true;
                                }
                                text.truncate(trimmed_len);
                                if text.is_empty() {
                                    arena.unlink(last_id);
                                }
                            }
                        }
                    }
                    pos += 1;
                    while pos < bytes.len() && (bytes[pos] == b' ' || bytes[pos] == b'\t') {
                        pos += 1;
                    }
                    let id = arena.alloc(
                        if is_hard {
                            NodeKind::HardBreak
                        } else {
                            NodeKind::SoftBreak
                        },
                        0,
                    );
                    arena.append_child(0, id);
                }
                b'\\' => {
                    if pos + 1 < bytes.len() && bytes[pos + 1] == b'\n' {
                        pos += 2;
                        while pos < bytes.len() && (bytes[pos] == b' ' || bytes[pos] == b'\t') {
                            pos += 1;
                        }
                        let id = arena.alloc(NodeKind::HardBreak, 0);
                        arena.append_child(0, id);
                    } else if pos + 1 < bytes.len() && is_ascii_escapable(bytes[pos + 1]) {
                        append_text(
                            &mut arena,
                            std::str::from_utf8(&bytes[pos + 1..pos + 2]).unwrap(),
                        );
                        pos += 2;
                    } else {
                        append_text(&mut arena, "\\");
                        pos += 1;
                    }
                }
                b'`' => {
                    let mut open_len = 0usize;
                    while pos + open_len < bytes.len() && bytes[pos + open_len] == b'`' {
                        open_len += 1;
                    }
                    let search_start = pos + open_len;
                    if let Some((close_start, close_end)) =
                        find_matching_backticks(bytes, search_start, open_len)
                    {
                        let raw_code = &input[search_start..close_start];
                        let mut code = raw_code.replace('\n', " ");
                        if code.len() >= 2
                            && code.starts_with(' ')
                            && code.ends_with(' ')
                            && code.bytes().any(|b| b != b' ')
                        {
                            code = code[1..code.len() - 1].to_owned();
                        }
                        let id = arena.alloc(NodeKind::Code(code), 0);
                        arena.append_child(0, id);
                        pos = close_end;
                    } else {
                        append_text(&mut arena, &input[pos..pos + open_len]);
                        pos += open_len;
                    }
                }
                b'&' => {
                    if let Some((resolved, end)) = try_parse_entity(input, pos) {
                        append_text(&mut arena, &resolved);
                        pos = end;
                    } else {
                        append_text(&mut arena, "&");
                        pos += 1;
                    }
                }
                b'<' => {
                    if let Some((dest, label, end)) = try_parse_autolink(input, pos) {
                        let link_id = arena.alloc(NodeKind::Link { dest, title: None }, 1);
                        let text_id = arena.alloc(NodeKind::Text(label), 0);
                        arena.append_child(link_id, text_id);
                        arena.append_child(0, link_id);
                        pos = end;
                    } else if let Some(tag_len) = parse_html_inline_token(&input[pos..]) {
                        let id = arena.alloc(
                            NodeKind::HtmlInline(input[pos..pos + tag_len].to_owned()),
                            0,
                        );
                        arena.append_child(0, id);
                        pos += tag_len;
                    } else {
                        append_text(&mut arena, "<");
                        pos += 1;
                    }
                }
                b'*' | b'_' | b'~' => {
                    let ch = bytes[pos];
                    let mut len = 0usize;
                    while pos + len < bytes.len() && bytes[pos + len] == ch {
                        len += 1;
                    }
                    let before = input[..pos].chars().next_back().unwrap_or('\n');
                    let after = input[pos + len..].chars().next().unwrap_or('\n');
                    let (can_open, can_close) = classify_delimiter_run(ch, len, before, after);
                    let run_node = arena.alloc(NodeKind::Text(input[pos..pos + len].to_owned()), 0);
                    arena.append_child(0, run_node);
                    if can_open || can_close {
                        delims.push(DelimEntry {
                            ch,
                            len,
                            orig_len: len,
                            can_open,
                            can_close,
                            node: run_node,
                            active: true,
                        });
                    }
                    pos += len;
                }
                b'[' => {
                    let node = arena.alloc(NodeKind::Text("[".to_owned()), 0);
                    arena.append_child(0, node);
                    brackets.push(BracketEntry {
                        node,
                        is_image: false,
                        active: true,
                        prev_delim_idx: delims.iter().rposition(|d| d.active),
                        content_start_byte: pos + 1,
                        content_depth_floor: 0,
                    });
                    pos += 1;
                }
                b'!' if bytes.get(pos + 1) == Some(&b'[') => {
                    let node = arena.alloc(NodeKind::Text("![".to_owned()), 0);
                    arena.append_child(0, node);
                    brackets.push(BracketEntry {
                        node,
                        is_image: true,
                        active: true,
                        prev_delim_idx: delims.iter().rposition(|d| d.active),
                        content_start_byte: pos + 2,
                        content_depth_floor: 0,
                    });
                    pos += 2;
                }
                b']' => {
                    if let Some(bracket) = brackets.pop() {
                        if !bracket.active {
                            append_text(&mut arena, "]");
                            pos += 1;
                            continue;
                        }
                        let inner_raw = &input[bracket.content_start_byte..pos];
                        let matched_link = try_match_link_or_ref(input, pos + 1, inner_raw, defs);
                        if let Some((dest, title, end_pos)) = matched_link {
                            let sentinel = arena.alloc(NodeKind::Text(String::new()), 0);
                            arena.append_child(0, sentinel);

                            let bottom_delim = bracket.prev_delim_idx.map_or(0, |i| i + 1);
                            process_delimiters(&mut arena, &mut delims, bottom_delim, sentinel)?;
                            let inner_depth = arena
                                .max_depth_between(bracket.node, sentinel)
                                .max(bracket.content_depth_floor);
                            if inner_depth > MAX_MARKDOWN_DEPTH {
                                return Err("Markdown nesting is too deep.".into());
                            }

                            if bracket.is_image {
                                // Build temporary image subtree to extract plain alt text + child depth.
                                let wrapper = arena.alloc(
                                    NodeKind::Image {
                                        alt_text: String::new(),
                                        max_child_depth: inner_depth,
                                    },
                                    inner_depth + 1,
                                );
                                arena.wrap_between(bracket.node, sentinel, wrapper);
                                let alt_text = extract_arena_alt_text(&arena, wrapper);
                                arena.nodes[wrapper].kind = NodeKind::Image {
                                    alt_text,
                                    max_child_depth: inner_depth,
                                };
                                arena.nodes[wrapper].first_child = None;
                                arena.nodes[wrapper].last_child = None;
                            } else {
                                let wrapper =
                                    arena.alloc(NodeKind::Link { dest, title }, inner_depth + 1);
                                arena.wrap_between(bracket.node, sentinel, wrapper);
                                for prev_b in &mut brackets {
                                    if !prev_b.is_image {
                                        prev_b.active = false;
                                    }
                                }
                            }
                            arena.unlink(bracket.node);
                            arena.unlink(sentinel);
                            // Propagate depth floor to enclosing bracket if needed.
                            if let Some(parent_b) = brackets.last_mut() {
                                parent_b.content_depth_floor =
                                    parent_b.content_depth_floor.max(inner_depth + 1);
                            }
                            pos = end_pos;
                        } else {
                            append_text(&mut arena, "]");
                            pos += 1;
                        }
                    } else {
                        append_text(&mut arena, "]");
                        pos += 1;
                    }
                }
                _ => {
                    let start = pos;
                    pos += 1;
                    while pos < bytes.len()
                        && !matches!(
                            bytes[pos],
                            b'\n'
                                | b'\\'
                                | b'`'
                                | b'&'
                                | b'<'
                                | b'*'
                                | b'_'
                                | b'~'
                                | b'['
                                | b']'
                                | b'!'
                        )
                    {
                        pos += 1;
                    }
                    // Include '!' if not followed by '['.
                    if pos < bytes.len() && bytes[pos] == b'!' && bytes.get(pos + 1) != Some(&b'[')
                    {
                        pos += 1;
                    }
                    append_text(&mut arena, &input[start..pos]);
                }
            }
        }

        let end_sentinel = arena.alloc(NodeKind::Text(String::new()), 0);
        arena.append_child(0, end_sentinel);
        process_delimiters(&mut arena, &mut delims, 0, end_sentinel)?;
        arena.unlink(end_sentinel);

        arena_children_to_vec(&arena, 0)
    }

    fn append_text(arena: &mut InlineArena, s: &str) {
        if s.is_empty() {
            return;
        }
        if let Some(last_id) = arena.nodes[0].last_child {
            if arena.nodes[last_id].mergeable_text {
                if let NodeKind::Text(ref mut existing) = arena.nodes[last_id].kind {
                    existing.push_str(s);
                    return;
                }
            }
        }
        let id = arena.alloc(NodeKind::Text(s.to_owned()), 0);
        arena.nodes[id].mergeable_text = true;
        arena.append_child(0, id);
    }

    fn find_matching_backticks(
        bytes: &[u8],
        mut pos: usize,
        target_len: usize,
    ) -> Option<(usize, usize)> {
        while pos < bytes.len() {
            if bytes[pos] == b'`' {
                let start = pos;
                while pos < bytes.len() && bytes[pos] == b'`' {
                    pos += 1;
                }
                if pos - start == target_len {
                    return Some((start, pos));
                }
            } else {
                pos += 1;
            }
        }
        None
    }

    fn classify_delimiter_run(ch: u8, len: usize, before: char, after: char) -> (bool, bool) {
        let after_ws = is_md_whitespace(after);
        let after_punct = is_md_punctuation(after);
        let before_ws = is_md_whitespace(before);
        let before_punct = is_md_punctuation(before);

        let left_flanking = !after_ws && (!after_punct || before_ws || before_punct);
        let right_flanking = !before_ws && (!before_punct || after_ws || after_punct);

        match ch {
            b'*' => (left_flanking, right_flanking),
            b'_' => (
                left_flanking && (!right_flanking || before_punct),
                right_flanking && (!left_flanking || after_punct),
            ),
            b'~' => {
                if len <= 2 {
                    (left_flanking, right_flanking)
                } else {
                    (false, false)
                }
            }
            _ => (false, false),
        }
    }

    fn process_delimiters(
        arena: &mut InlineArena,
        delims: &mut [DelimEntry],
        stack_bottom: usize,
        _end_sentinel: usize,
    ) -> Result<(), String> {
        let mut openers_bottom: [[usize; 3]; 3] = [[stack_bottom; 3]; 3];

        let mut closer_idx = stack_bottom;
        while closer_idx < delims.len() {
            if !delims[closer_idx].active || !delims[closer_idx].can_close {
                closer_idx += 1;
                continue;
            }
            let delim_ch = delims[closer_idx].ch;
            let ch_slot = match delim_ch {
                b'*' => 0,
                b'_' => 1,
                b'~' => 2,
                _ => {
                    closer_idx += 1;
                    continue;
                }
            };
            let len_slot = delims[closer_idx].orig_len % 3;
            let bottom = openers_bottom[ch_slot][len_slot];

            let mut opener_idx_opt = None;
            if closer_idx > bottom {
                let mut i = closer_idx - 1;
                loop {
                    let o = &delims[i];
                    if o.active && o.can_open && o.ch == delim_ch {
                        let compatible = if delim_ch == b'~' {
                            o.len == delims[closer_idx].len && o.len <= 2
                        } else {
                            let odd_rule = (o.can_close || delims[closer_idx].can_open)
                                && (o.orig_len + delims[closer_idx].orig_len).is_multiple_of(3)
                                && (!o.orig_len.is_multiple_of(3)
                                    || !delims[closer_idx].orig_len.is_multiple_of(3));
                            !odd_rule
                        };
                        if compatible {
                            opener_idx_opt = Some(i);
                            break;
                        }
                    }
                    if i == bottom {
                        break;
                    }
                    i -= 1;
                }
            }

            let Some(opener_idx) = opener_idx_opt else {
                openers_bottom[ch_slot][len_slot] = closer_idx;
                if !delims[closer_idx].can_open {
                    delims[closer_idx].active = false;
                }
                closer_idx += 1;
                continue;
            };

            let opener_node = delims[opener_idx].node;
            let closer_node = delims[closer_idx].node;
            let use_len = if delim_ch == b'~' {
                delims[opener_idx].len
            } else if delims[opener_idx].len >= 2 && delims[closer_idx].len >= 2 {
                2
            } else {
                1
            };

            let inner_depth = arena.max_depth_between(opener_node, closer_node);
            if inner_depth > MAX_MARKDOWN_DEPTH {
                return Err("Markdown nesting is too deep.".into());
            }

            // Deactivate any delimiters strictly between opener_idx and closer_idx.
            for d in &mut delims[opener_idx + 1..closer_idx] {
                d.active = false;
            }

            delims[opener_idx].len -= use_len;
            delims[closer_idx].len -= use_len;

            if let NodeKind::Text(ref mut s) = arena.nodes[opener_node].kind {
                s.truncate(s.len() - use_len);
            }
            if let NodeKind::Text(ref mut s) = arena.nodes[closer_node].kind {
                s.truncate(s.len() - use_len);
            }

            let kind = match (delim_ch, use_len) {
                (b'~', _) => NodeKind::Strike,
                (_, 2) => NodeKind::Strong,
                _ => NodeKind::Emph,
            };
            let wrapper = arena.alloc(kind, inner_depth + 1);
            arena.wrap_between(opener_node, closer_node, wrapper);

            if delims[opener_idx].len == 0 {
                delims[opener_idx].active = false;
                arena.unlink(opener_node);
            }
            if delims[closer_idx].len == 0 {
                delims[closer_idx].active = false;
                arena.unlink(closer_node);
                closer_idx += 1;
            }
        }

        for d in &mut delims[stack_bottom..] {
            d.active = false;
        }
        Ok(())
    }

    fn try_match_link_or_ref(
        input: &str,
        after_bracket: usize,
        inner_raw: &str,
        defs: &HashMap<String, LinkDef>,
    ) -> Option<(String, Option<String>, usize)> {
        let bytes = input.as_bytes();
        // 1. Inline link `(... )`
        if bytes.get(after_bracket) == Some(&b'(') {
            let mut i = after_bracket + 1;
            i = skip_md_ws_bytes(bytes, i);
            let mut dest = String::new();
            let mut title = None;
            if bytes.get(i) != Some(&b')') {
                let (d, next_i) = parse_link_destination(input, i)?;
                dest = unescape_md_string(&d);
                i = next_i;
                let ws_start = i;
                i = skip_md_ws_bytes(bytes, i);
                if i > ws_start
                    && bytes
                        .get(i)
                        .is_some_and(|b| matches!(b, b'"' | b'\'' | b'('))
                {
                    if let Some((t, after_t)) = parse_link_title(input, i) {
                        title = Some(unescape_md_string(&t));
                        i = skip_md_ws_bytes(bytes, after_t);
                    }
                }
            }
            if bytes.get(i) == Some(&b')') {
                return Some((dest, title, i + 1));
            }
        }

        // 2. Full or collapsed reference link `[label]` / `[]`
        if bytes.get(after_bracket) == Some(&b'[') {
            if let Some((label, end_pos)) = parse_bracket_label(input, after_bracket) {
                let key_source = if label.is_empty() { inner_raw } else { label };
                let key = normalize_label_content(key_source);
                if !key.is_empty() {
                    if let Some(def) = defs.get(&key) {
                        return Some((def.dest.clone(), def.title.clone(), end_pos));
                    }
                }
            }
            return None;
        }

        // 3. Shortcut reference link `[label]` (must NOT be followed by `[`)
        let key = normalize_label_content(inner_raw);
        if !key.is_empty() {
            if let Some(def) = defs.get(&key) {
                return Some((def.dest.clone(), def.title.clone(), after_bracket));
            }
        }
        None
    }

    fn skip_md_ws_bytes(bytes: &[u8], mut i: usize) -> usize {
        while i < bytes.len() && matches!(bytes[i], b' ' | b'\t' | b'\n' | b'\x0c') {
            i += 1;
        }
        i
    }

    fn parse_bracket_label(input: &str, open_pos: usize) -> Option<(&str, usize)> {
        let bytes = input.as_bytes();
        if bytes.get(open_pos) != Some(&b'[') {
            return None;
        }
        let mut i = open_pos + 1;
        let mut char_count = 0usize;
        while i < bytes.len() {
            let b = bytes[i];
            if b == b'\\' && i + 1 < bytes.len() {
                let ch = input[i + 1..].chars().next()?;
                i += 1 + ch.len_utf8();
                char_count += 1;
                continue;
            }
            if b == b'[' {
                return None;
            }
            if b == b']' {
                return Some((&input[open_pos + 1..i], i + 1));
            }
            let ch = input[i..].chars().next()?;
            i += ch.len_utf8();
            char_count += 1;
            if char_count > 999 {
                return None;
            }
        }
        None
    }

    fn parse_link_destination(input: &str, start: usize) -> Option<(String, usize)> {
        let bytes = input.as_bytes();
        if start >= bytes.len() {
            return None;
        }
        if bytes[start] == b'<' {
            let mut i = start + 1;
            let mut out = String::new();
            while i < bytes.len() {
                match bytes[i] {
                    b'>' => return Some((out, i + 1)),
                    b'<' | b'\n' => return None,
                    b'\\' if i + 1 < bytes.len() => {
                        out.push('\\');
                        let ch = input[i + 1..].chars().next()?;
                        out.push(ch);
                        i += 1 + ch.len_utf8();
                    }
                    _ => {
                        let ch = input[i..].chars().next()?;
                        out.push(ch);
                        i += ch.len_utf8();
                    }
                }
            }
            return None;
        }

        let mut i = start;
        let mut parens = 0usize;
        while i < bytes.len() {
            let b = bytes[i];
            if b <= 0x20 || b == 0x7f {
                break;
            }
            match b {
                b'\\' if i + 1 < bytes.len() && is_ascii_escapable(bytes[i + 1]) => {
                    i += 2;
                }
                b'(' => {
                    parens += 1;
                    if parens > 32 {
                        return None;
                    }
                    i += 1;
                }
                b')' => {
                    if parens == 0 {
                        break;
                    }
                    parens -= 1;
                    i += 1;
                }
                _ => {
                    let ch = input[i..].chars().next()?;
                    i += ch.len_utf8();
                }
            }
        }
        if parens != 0 || i == start {
            return None;
        }
        Some((input[start..i].to_owned(), i))
    }

    fn parse_link_title(input: &str, start: usize) -> Option<(String, usize)> {
        let bytes = input.as_bytes();
        let open = *bytes.get(start)?;
        let close = match open {
            b'"' => b'"',
            b'\'' => b'\'',
            b'(' => b')',
            _ => return None,
        };
        let mut i = start + 1;
        while i < bytes.len() {
            let b = bytes[i];
            if b == b'\\' && i + 1 < bytes.len() {
                let ch = input[i + 1..].chars().next()?;
                i += 1 + ch.len_utf8();
                continue;
            }
            if b == close {
                return Some((input[start + 1..i].to_owned(), i + 1));
            }
            if open == b'(' && b == b'(' {
                return None;
            }
            let ch = input[i..].chars().next()?;
            i += ch.len_utf8();
        }
        None
    }

    fn try_parse_entity(input: &str, amp_pos: usize) -> Option<(String, usize)> {
        let rest = &input[amp_pos + 1..];
        let semi = rest.find(';')?;
        if semi == 0 || semi > 32 {
            return None;
        }
        let body = &rest[..semi];
        if let Some(num) = body.strip_prefix('#') {
            let cp = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
                if hex.is_empty() || hex.len() > 6 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return None;
                }
                u32::from_str_radix(hex, 16).ok()?
            } else {
                if num.is_empty() || num.len() > 7 || !num.bytes().all(|b| b.is_ascii_digit()) {
                    return None;
                }
                num.parse::<u32>().ok()?
            };
            let ch = if cp == 0 {
                '\u{FFFD}'
            } else {
                char::from_u32(cp).unwrap_or('\u{FFFD}')
            };
            return Some((ch.to_string(), amp_pos + 2 + semi));
        }
        if !body.as_bytes()[0].is_ascii_alphabetic()
            || !body.bytes().all(|b| b.is_ascii_alphanumeric())
        {
            return None;
        }
        let resolved = resolve_html5_entity(body)?;
        Some((resolved.to_owned(), amp_pos + 2 + semi))
    }

    fn try_parse_autolink(input: &str, lt_pos: usize) -> Option<(String, String, usize)> {
        let rest = &input[lt_pos + 1..];
        let gt_rel = rest.find('>')?;
        let inner = &rest[..gt_rel];
        if inner.is_empty() || inner.bytes().any(|b| b <= 0x20 || b == b'<') {
            return None;
        }
        let end = lt_pos + 1 + gt_rel + 1;
        // URI autolink: scheme `2..=32` chars [A-Za-z][A-Za-z0-9+.-]* ':'
        if let Some(colon) = inner.find(':') {
            let scheme = &inner[..colon];
            if (2..=32).contains(&scheme.len())
                && scheme.as_bytes()[0].is_ascii_alphabetic()
                && scheme
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'-' | b'.'))
            {
                return Some((inner.to_owned(), inner.to_owned(), end));
            }
        }
        // Email autolink
        if is_commonmark_email(inner) {
            return Some((format!("mailto:{inner}"), inner.to_owned(), end));
        }
        None
    }

    fn is_commonmark_email(s: &str) -> bool {
        let Some((local, domain)) = s.split_once('@') else {
            return false;
        };
        if local.is_empty()
            || !local.bytes().all(|b| {
                b.is_ascii_alphanumeric()
                    || matches!(
                        b,
                        b'.' | b'!'
                            | b'#'
                            | b'$'
                            | b'%'
                            | b'&'
                            | b'\''
                            | b'*'
                            | b'+'
                            | b'/'
                            | b'='
                            | b'?'
                            | b'^'
                            | b'_'
                            | b'`'
                            | b'{'
                            | b'|'
                            | b'}'
                            | b'~'
                            | b'-'
                    )
            })
        {
            return false;
        }
        if domain.is_empty() {
            return false;
        }
        for label in domain.split('.') {
            if label.is_empty() || label.len() > 63 {
                return false;
            }
            let bytes = label.as_bytes();
            if !bytes[0].is_ascii_alphanumeric() || !bytes[bytes.len() - 1].is_ascii_alphanumeric()
            {
                return false;
            }
            if !bytes
                .iter()
                .all(|b| b.is_ascii_alphanumeric() || *b == b'-')
            {
                return false;
            }
        }
        true
    }

    fn parse_html_inline_token(s: &str) -> Option<usize> {
        if let Some(end) = s.strip_prefix("<!--").and_then(|r| {
            if r.starts_with('>') || r.starts_with("->") {
                return None;
            }
            r.find("-->").map(|p| 4 + p + 3)
        }) {
            return Some(end);
        }
        if let Some(end) = s
            .strip_prefix("<?")
            .and_then(|r| r.find("?>").map(|p| 2 + p + 2))
        {
            return Some(end);
        }
        if let Some(end) = s
            .strip_prefix("<![CDATA[")
            .and_then(|r| r.find("]]>").map(|p| 9 + p + 3))
        {
            return Some(end);
        }
        if s.len() >= 4 && s.as_bytes()[1] == b'!' && s.as_bytes()[2].is_ascii_alphabetic() {
            if let Some(gt) = s[3..].find('>') {
                return Some(3 + gt + 1);
            }
        }
        parse_html_inline_tag(s)
    }

    fn parse_html_inline_tag(s: &str) -> Option<usize> {
        let bytes = s.as_bytes();
        if bytes.first() != Some(&b'<') {
            return None;
        }
        let mut i = 1usize;
        if bytes.get(i) == Some(&b'/') {
            i += 1;
            if !bytes.get(i).is_some_and(u8::is_ascii_alphabetic) {
                return None;
            }
            while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'-') {
                i += 1;
            }
            while i < bytes.len() && matches!(bytes[i], b' ' | b'\t' | b'\n') {
                i += 1;
            }
            return (bytes.get(i) == Some(&b'>')).then_some(i + 1);
        }
        if !bytes.get(i).is_some_and(u8::is_ascii_alphabetic) {
            return None;
        }
        while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'-') {
            i += 1;
        }
        loop {
            let ws_start = i;
            while i < bytes.len() && matches!(bytes[i], b' ' | b'\t' | b'\n') {
                i += 1;
            }
            if bytes.get(i) == Some(&b'>') {
                return Some(i + 1);
            }
            if bytes.get(i) == Some(&b'/') && bytes.get(i + 1) == Some(&b'>') {
                return Some(i + 2);
            }
            if i == ws_start {
                return None;
            }
            // Attribute name
            if !bytes
                .get(i)
                .is_some_and(|&b| b.is_ascii_alphabetic() || b == b'_' || b == b':')
            {
                return None;
            }
            i += 1;
            while i < bytes.len()
                && (bytes[i].is_ascii_alphanumeric()
                    || matches!(bytes[i], b'_' | b'.' | b':' | b'-'))
            {
                i += 1;
            }
            let after_name = i;
            while i < bytes.len() && matches!(bytes[i], b' ' | b'\t' | b'\n') {
                i += 1;
            }
            if bytes.get(i) == Some(&b'=') {
                i += 1;
                while i < bytes.len() && matches!(bytes[i], b' ' | b'\t' | b'\n') {
                    i += 1;
                }
                match bytes.get(i) {
                    Some(&b'\'') => {
                        i += 1;
                        let rel = s[i..].find('\'')?;
                        i += rel + 1;
                    }
                    Some(&b'"') => {
                        i += 1;
                        let rel = s[i..].find('"')?;
                        i += rel + 1;
                    }
                    Some(_) => {
                        let val_start = i;
                        while i < bytes.len()
                            && bytes[i] > 0x20
                            && !matches!(bytes[i], b'"' | b'\'' | b'=' | b'<' | b'>' | b'`')
                        {
                            i += 1;
                        }
                        if i == val_start {
                            return None;
                        }
                    }
                    None => return None,
                }
            } else {
                i = after_name;
            }
        }
    }

    fn extract_arena_alt_text(arena: &InlineArena, root: usize) -> String {
        let mut out = String::new();
        let mut stack = Vec::new();
        if let Some(first) = arena.nodes[root].first_child {
            let mut cur = Some(first);
            let mut children = Vec::new();
            while let Some(id) = cur {
                children.push(id);
                cur = arena.nodes[id].next;
            }
            for id in children.into_iter().rev() {
                stack.push(id);
            }
        }
        while let Some(id) = stack.pop() {
            match &arena.nodes[id].kind {
                NodeKind::Text(s) | NodeKind::Code(s) => out.push_str(s),
                NodeKind::SoftBreak | NodeKind::HardBreak => out.push('\n'),
                NodeKind::Image { alt_text, .. } => out.push_str(alt_text),
                NodeKind::Emph | NodeKind::Strong | NodeKind::Strike | NodeKind::Link { .. } => {
                    let mut cur = arena.nodes[id].first_child;
                    let mut children = Vec::new();
                    while let Some(cid) = cur {
                        children.push(cid);
                        cur = arena.nodes[cid].next;
                    }
                    for cid in children.into_iter().rev() {
                        stack.push(cid);
                    }
                }
                NodeKind::Root | NodeKind::HtmlInline(_) => {}
            }
        }
        out
    }

    fn arena_children_to_vec(arena: &InlineArena, parent: usize) -> Result<Vec<MdInline>, String> {
        enum Frame {
            Enter(usize),
            Exit(usize),
        }
        let mut stack = Vec::new();
        let mut children = Vec::new();
        let mut cur = arena.nodes[parent].first_child;
        while let Some(id) = cur {
            children.push(id);
            cur = arena.nodes[id].next;
        }
        for id in children.into_iter().rev() {
            stack.push(Frame::Enter(id));
        }

        let mut list_stack: Vec<Vec<MdInline>> = vec![Vec::new()];
        while let Some(frame) = stack.pop() {
            match frame {
                Frame::Enter(id) => match &arena.nodes[id].kind {
                    NodeKind::Text(s) => {
                        list_stack
                            .last_mut()
                            .unwrap()
                            .push(MdInline::Text(s.clone()));
                    }
                    NodeKind::Code(s) => {
                        list_stack
                            .last_mut()
                            .unwrap()
                            .push(MdInline::Code(s.clone()));
                    }
                    NodeKind::SoftBreak => {
                        list_stack.last_mut().unwrap().push(MdInline::SoftBreak);
                    }
                    NodeKind::HardBreak => {
                        list_stack.last_mut().unwrap().push(MdInline::HardBreak);
                    }
                    NodeKind::HtmlInline(s) => {
                        list_stack
                            .last_mut()
                            .unwrap()
                            .push(MdInline::HtmlInline(s.clone()));
                    }
                    NodeKind::Image {
                        alt_text,
                        max_child_depth,
                    } => {
                        list_stack.last_mut().unwrap().push(MdInline::Image {
                            alt_text: alt_text.clone(),
                            max_child_depth: *max_child_depth,
                        });
                    }
                    NodeKind::Emph
                    | NodeKind::Strong
                    | NodeKind::Strike
                    | NodeKind::Link { .. } => {
                        let depth_now = list_stack.len();
                        if depth_now > MAX_MARKDOWN_DEPTH + 1 {
                            return Err("Markdown nesting is too deep.".into());
                        }
                        list_stack.push(Vec::new());
                        stack.push(Frame::Exit(id));
                        let mut sub = Vec::new();
                        let mut c = arena.nodes[id].first_child;
                        while let Some(cid) = c {
                            sub.push(cid);
                            c = arena.nodes[cid].next;
                        }
                        for cid in sub.into_iter().rev() {
                            stack.push(Frame::Enter(cid));
                        }
                    }
                    NodeKind::Root => {}
                },
                Frame::Exit(id) => {
                    let inner = list_stack.pop().unwrap();
                    let node = match &arena.nodes[id].kind {
                        NodeKind::Emph => MdInline::Emph(inner),
                        NodeKind::Strong => MdInline::Strong(inner),
                        NodeKind::Strike => MdInline::Strike(inner),
                        NodeKind::Link { dest, title } => MdInline::Link {
                            dest: dest.clone(),
                            title: title.clone(),
                            children: inner,
                        },
                        _ => unreachable!(),
                    };
                    list_stack.last_mut().unwrap().push(node);
                }
            }
        }
        Ok(list_stack.pop().unwrap())
    }

    // -------------------------------------------------------------------------
    // Depth Enforcement (matching TextBookParser AST walk: Document = 0, limit 128)
    // -------------------------------------------------------------------------

    fn check_ast_depth(blocks: &[MdBlock]) -> Result<(), String> {
        enum Item<'a> {
            Block(&'a MdBlock, usize),
            Inline(&'a MdInline, usize),
        }
        let mut stack = Vec::with_capacity(blocks.len());
        for b in blocks {
            stack.push(Item::Block(b, 1));
        }
        while let Some(item) = stack.pop() {
            match item {
                Item::Block(b, depth) => {
                    if depth > MAX_MARKDOWN_DEPTH {
                        return Err("Markdown nesting is too deep.".into());
                    }
                    match b {
                        MdBlock::BlockQuote(children) => {
                            for c in children {
                                stack.push(Item::Block(c, depth + 1));
                            }
                        }
                        MdBlock::List { items, .. } => {
                            // List (+0 from b=depth), ListItem (+1), child block (+2).
                            if depth + 1 > MAX_MARKDOWN_DEPTH && !items.is_empty() {
                                return Err("Markdown nesting is too deep.".into());
                            }
                            for item_blocks in items {
                                if depth + 1 > MAX_MARKDOWN_DEPTH {
                                    return Err("Markdown nesting is too deep.".into());
                                }
                                for child in item_blocks {
                                    stack.push(Item::Block(child, depth + 2));
                                }
                            }
                        }
                        MdBlock::Heading { inlines, .. } | MdBlock::Paragraph { inlines, .. } => {
                            for inl in inlines {
                                stack.push(Item::Inline(inl, depth + 1));
                            }
                        }
                        MdBlock::FencedCode { .. }
                        | MdBlock::IndentedCode { .. }
                        | MdBlock::HtmlBlock { .. }
                        | MdBlock::ThematicBreak => {}
                        MdBlock::Table { head, body, .. } => {
                            // Table (+0=depth) -> TableHead/Body (+1) -> TableRow (+2) -> TableCell (+3) -> inlines (+4)
                            if depth + 3 > MAX_MARKDOWN_DEPTH {
                                return Err("Markdown nesting is too deep.".into());
                            }
                            for cell in head {
                                for inl in &cell.inlines {
                                    stack.push(Item::Inline(inl, depth + 4));
                                }
                            }
                            for row in body {
                                for cell in row {
                                    for inl in &cell.inlines {
                                        stack.push(Item::Inline(inl, depth + 4));
                                    }
                                }
                            }
                        }
                    }
                }
                Item::Inline(inl, depth) => {
                    if depth > MAX_MARKDOWN_DEPTH {
                        return Err("Markdown nesting is too deep.".into());
                    }
                    match inl {
                        MdInline::Emph(c)
                        | MdInline::Strong(c)
                        | MdInline::Strike(c)
                        | MdInline::Link { children: c, .. } => {
                            for child in c {
                                stack.push(Item::Inline(child, depth + 1));
                            }
                        }
                        MdInline::Image {
                            max_child_depth, ..
                        } => {
                            if depth + max_child_depth > MAX_MARKDOWN_DEPTH {
                                return Err("Markdown nesting is too deep.".into());
                            }
                        }
                        MdInline::Text(_)
                        | MdInline::Code(_)
                        | MdInline::SoftBreak
                        | MdInline::HardBreak
                        | MdInline::HtmlInline(_) => {}
                    }
                }
            }
        }
        Ok(())
    }

    // -------------------------------------------------------------------------
    // Heading Extraction + HTML Rendering
    // -------------------------------------------------------------------------

    fn collect_heading_texts(blocks: &[MdBlock], out: &mut Vec<String>) {
        let mut stack: Vec<&MdBlock> = Vec::with_capacity(blocks.len());
        for b in blocks.iter().rev() {
            stack.push(b);
        }
        while let Some(b) = stack.pop() {
            match b {
                MdBlock::BlockQuote(children) => {
                    for c in children.iter().rev() {
                        stack.push(c);
                    }
                }
                MdBlock::List { items, .. } => {
                    for item in items.iter().rev() {
                        for child in item.iter().rev() {
                            stack.push(child);
                        }
                    }
                }
                MdBlock::Heading { inlines, .. } => {
                    out.push(extract_heading_plain_text(inlines));
                }
                _ => {}
            }
        }
    }

    fn extract_heading_plain_text(inlines: &[MdInline]) -> String {
        let mut raw = String::new();
        let mut stack: Vec<&MdInline> = Vec::with_capacity(inlines.len());
        for i in inlines.iter().rev() {
            stack.push(i);
        }
        while let Some(inl) = stack.pop() {
            match inl {
                MdInline::Text(s) | MdInline::Code(s) | MdInline::HtmlInline(s) => {
                    raw.push_str(s);
                }
                MdInline::SoftBreak | MdInline::HardBreak => raw.push(' '),
                MdInline::Image { alt_text, .. } => raw.push_str(alt_text),
                MdInline::Emph(c)
                | MdInline::Strong(c)
                | MdInline::Strike(c)
                | MdInline::Link { children: c, .. } => {
                    for child in c.iter().rev() {
                        stack.push(child);
                    }
                }
            }
        }
        // Match Jsoup's `Element.text()` normalization on HTML whitespace ([ \t\n\r\f]+ -> ' ', trimmed).
        let mut normalized = String::with_capacity(raw.len());
        let mut in_ws = false;
        for ch in raw.chars() {
            if matches!(ch, ' ' | '\t' | '\n' | '\r' | '\x0c') {
                if !in_ws && !normalized.is_empty() {
                    normalized.push(' ');
                }
                in_ws = true;
            } else {
                normalized.push(ch);
                in_ws = false;
            }
        }
        normalized.trim_end_matches(' ').to_owned()
    }

    enum RenderOp<'a> {
        Block(&'a MdBlock, bool),
        RawStr(&'static str),
        Inline(&'a MdInline),
    }

    fn render_blocks(
        blocks: &[MdBlock],
        in_tight_list: bool,
        slugs: &[String],
        slug_idx: &mut usize,
        out: &mut String,
    ) {
        let mut stack: Vec<RenderOp<'_>> = Vec::with_capacity(blocks.len());
        for b in blocks.iter().rev() {
            stack.push(RenderOp::Block(b, in_tight_list));
        }
        while let Some(op) = stack.pop() {
            match op {
                RenderOp::RawStr(s) => out.push_str(s),
                RenderOp::Block(block, tight_para) => match block {
                    MdBlock::BlockQuote(children) => {
                        push_newline_if_needed(out);
                        out.push_str("<blockquote>\n");
                        stack.push(RenderOp::RawStr("</blockquote>\n"));
                        for c in children.iter().rev() {
                            stack.push(RenderOp::Block(c, false));
                        }
                    }
                    MdBlock::List {
                        ordered,
                        start,
                        tight,
                        items,
                    } => {
                        push_newline_if_needed(out);
                        if *ordered {
                            if *start != 1 {
                                out.push_str("<ol start=\"");
                                out.push_str(&start.to_string());
                                out.push_str("\">\n");
                            } else {
                                out.push_str("<ol>\n");
                            }
                            stack.push(RenderOp::RawStr("</ol>\n"));
                        } else {
                            out.push_str("<ul>\n");
                            stack.push(RenderOp::RawStr("</ul>\n"));
                        }
                        for item in items.iter().rev() {
                            stack.push(RenderOp::RawStr("</li>\n"));
                            for c in item.iter().rev() {
                                stack.push(RenderOp::Block(c, *tight));
                            }
                            stack.push(RenderOp::RawStr("<li>"));
                        }
                    }
                    MdBlock::Heading { level, inlines, .. } => {
                        push_newline_if_needed(out);
                        let slug = slugs.get(*slug_idx).map(String::as_str).unwrap_or("");
                        *slug_idx += 1;
                        let tag_ch = (b'0' + *level) as char;
                        out.push('<');
                        out.push('h');
                        out.push(tag_ch);
                        out.push_str(" id=\"");
                        append_escaped_html(out, slug);
                        out.push_str("\">");
                        let close_tag = match *level {
                            1 => "</h1>\n",
                            2 => "</h2>\n",
                            3 => "</h3>\n",
                            4 => "</h4>\n",
                            5 => "</h5>\n",
                            _ => "</h6>\n",
                        };
                        stack.push(RenderOp::RawStr(close_tag));
                        for inl in inlines.iter().rev() {
                            stack.push(RenderOp::Inline(inl));
                        }
                    }
                    MdBlock::Paragraph { inlines, .. } => {
                        if !tight_para {
                            push_newline_if_needed(out);
                            out.push_str("<p>");
                            stack.push(RenderOp::RawStr("</p>\n"));
                        } else if !out.ends_with("<li>") {
                            push_newline_if_needed(out);
                        }
                        for inl in inlines.iter().rev() {
                            stack.push(RenderOp::Inline(inl));
                        }
                    }
                    MdBlock::FencedCode { info, literal } => {
                        push_newline_if_needed(out);
                        out.push_str("<pre><code");
                        let lang = info.split(' ').next().unwrap_or("");
                        if !lang.is_empty() {
                            out.push_str(" class=\"language-");
                            append_escaped_html(out, lang);
                            out.push('"');
                        }
                        out.push('>');
                        append_escaped_html(out, literal);
                        out.push_str("</code></pre>\n");
                    }
                    MdBlock::IndentedCode { literal } => {
                        push_newline_if_needed(out);
                        out.push_str("<pre><code>");
                        append_escaped_html(out, literal);
                        out.push_str("</code></pre>\n");
                    }
                    MdBlock::HtmlBlock { literal } => {
                        push_newline_if_needed(out);
                        out.push_str("<p>");
                        append_escaped_html(out, literal);
                        out.push_str("</p>\n");
                    }
                    MdBlock::ThematicBreak => {
                        push_newline_if_needed(out);
                        out.push_str("<hr />\n");
                    }
                    MdBlock::Table {
                        alignments,
                        head,
                        body,
                    } => {
                        push_newline_if_needed(out);
                        out.push_str("<table>\n<thead>\n<tr>\n");
                        for (i, cell) in head.iter().enumerate() {
                            out.push_str("<th");
                            if let Some(align) = alignments.get(i).copied().flatten() {
                                out.push_str(" align=\"");
                                out.push_str(match align {
                                    TableAlign::Left => "left",
                                    TableAlign::Center => "center",
                                    TableAlign::Right => "right",
                                });
                                out.push('"');
                            }
                            out.push('>');
                            render_inlines_iterative(&cell.inlines, out);
                            out.push_str("</th>\n");
                        }
                        out.push_str("</tr>\n</thead>\n");
                        if !body.is_empty() {
                            out.push_str("<tbody>\n");
                            for row in body {
                                out.push_str("<tr>\n");
                                for (i, cell) in row.iter().enumerate() {
                                    out.push_str("<td");
                                    if let Some(align) = alignments.get(i).copied().flatten() {
                                        out.push_str(" align=\"");
                                        out.push_str(match align {
                                            TableAlign::Left => "left",
                                            TableAlign::Center => "center",
                                            TableAlign::Right => "right",
                                        });
                                        out.push('"');
                                    }
                                    out.push('>');
                                    render_inlines_iterative(&cell.inlines, out);
                                    out.push_str("</td>\n");
                                }
                                out.push_str("</tr>\n");
                            }
                            out.push_str("</tbody>\n");
                        }
                        out.push_str("</table>\n");
                    }
                },
                RenderOp::Inline(inl) => match inl {
                    MdInline::Text(s) | MdInline::HtmlInline(s) => append_escaped_html(out, s),
                    MdInline::Code(s) => {
                        out.push_str("<code>");
                        append_escaped_html(out, s);
                        out.push_str("</code>");
                    }
                    MdInline::SoftBreak => out.push('\n'),
                    MdInline::HardBreak => out.push_str("<br />\n"),
                    MdInline::Emph(children) => {
                        out.push_str("<em>");
                        stack.push(RenderOp::RawStr("</em>"));
                        for c in children.iter().rev() {
                            stack.push(RenderOp::Inline(c));
                        }
                    }
                    MdInline::Strong(children) => {
                        out.push_str("<strong>");
                        stack.push(RenderOp::RawStr("</strong>"));
                        for c in children.iter().rev() {
                            stack.push(RenderOp::Inline(c));
                        }
                    }
                    MdInline::Strike(children) => {
                        out.push_str("<del>");
                        stack.push(RenderOp::RawStr("</del>"));
                        for c in children.iter().rev() {
                            stack.push(RenderOp::Inline(c));
                        }
                    }
                    MdInline::Link {
                        dest,
                        title,
                        children,
                    } => {
                        let safe_url = sanitize_markdown_url(dest);
                        out.push_str("<a rel=\"nofollow\" href=\"");
                        append_escaped_html(out, safe_url);
                        out.push('"');
                        if let Some(title) = title {
                            out.push_str(" title=\"");
                            append_escaped_html(out, title);
                            out.push('"');
                        }
                        out.push('>');
                        stack.push(RenderOp::RawStr("</a>"));
                        for c in children.iter().rev() {
                            stack.push(RenderOp::Inline(c));
                        }
                    }
                    MdInline::Image { alt_text, .. } => {
                        append_escaped_html(out, alt_text);
                    }
                },
            }
        }
    }

    fn render_inlines_iterative(inlines: &[MdInline], out: &mut String) {
        enum Op<'a> {
            Inline(&'a MdInline),
            Raw(&'static str),
        }
        let mut stack: Vec<Op<'_>> = inlines.iter().rev().map(Op::Inline).collect();
        while let Some(op) = stack.pop() {
            match op {
                Op::Raw(s) => out.push_str(s),
                Op::Inline(inl) => match inl {
                    MdInline::Text(s) | MdInline::HtmlInline(s) => append_escaped_html(out, s),
                    MdInline::Code(s) => {
                        out.push_str("<code>");
                        append_escaped_html(out, s);
                        out.push_str("</code>");
                    }
                    MdInline::SoftBreak => out.push('\n'),
                    MdInline::HardBreak => out.push_str("<br />\n"),
                    MdInline::Emph(c) => {
                        out.push_str("<em>");
                        stack.push(Op::Raw("</em>"));
                        for child in c.iter().rev() {
                            stack.push(Op::Inline(child));
                        }
                    }
                    MdInline::Strong(c) => {
                        out.push_str("<strong>");
                        stack.push(Op::Raw("</strong>"));
                        for child in c.iter().rev() {
                            stack.push(Op::Inline(child));
                        }
                    }
                    MdInline::Strike(c) => {
                        out.push_str("<del>");
                        stack.push(Op::Raw("</del>"));
                        for child in c.iter().rev() {
                            stack.push(Op::Inline(child));
                        }
                    }
                    MdInline::Link {
                        dest,
                        title,
                        children,
                    } => {
                        let safe_url = sanitize_markdown_url(dest);
                        out.push_str("<a rel=\"nofollow\" href=\"");
                        append_escaped_html(out, safe_url);
                        out.push('"');
                        if let Some(title) = title {
                            out.push_str(" title=\"");
                            append_escaped_html(out, title);
                            out.push('"');
                        }
                        out.push('>');
                        stack.push(Op::Raw("</a>"));
                        for child in children.iter().rev() {
                            stack.push(Op::Inline(child));
                        }
                    }
                    MdInline::Image { alt_text, .. } => append_escaped_html(out, alt_text),
                },
            }
        }
    }

    #[inline]
    fn push_newline_if_needed(out: &mut String) {
        if !out.is_empty() && !out.ends_with('\n') {
            out.push('\n');
        }
    }

    fn append_escaped_html(out: &mut String, input: &str) {
        let bytes = input.as_bytes();
        let mut last = 0usize;
        for (idx, &b) in bytes.iter().enumerate() {
            let repl = match b {
                b'&' => "&amp;",
                b'<' => "&lt;",
                b'>' => "&gt;",
                b'"' => "&quot;",
                _ => continue,
            };
            out.push_str(&input[last..idx]);
            out.push_str(repl);
            last = idx + 1;
        }
        out.push_str(&input[last..]);
    }

    /// Matches `org.commonmark.renderer.html.DefaultUrlSanitizer`.
    fn sanitize_markdown_url(raw: &str) -> &str {
        let stripped = raw.trim_matches(|c| matches!(c, ' ' | '\t' | '\n' | '\x0c' | '\r'));
        for (idx, b) in stripped.bytes().enumerate() {
            match b {
                b'/' | b'#' | b'?' => return stripped,
                b':' => {
                    let protocol = &stripped[..idx];
                    if protocol.eq_ignore_ascii_case("http")
                        || protocol.eq_ignore_ascii_case("https")
                        || protocol.eq_ignore_ascii_case("mailto")
                        || protocol.eq_ignore_ascii_case("data")
                    {
                        return stripped;
                    }
                    return "";
                }
                _ => {}
            }
        }
        stripped
    }

    fn normalize_label_content(raw: &str) -> String {
        let trimmed = raw.trim();
        let case_folded = trimmed.to_lowercase().to_uppercase();
        let mut out = String::with_capacity(case_folded.len());
        let mut in_ws = false;
        for ch in case_folded.chars() {
            if matches!(ch, ' ' | '\t' | '\r' | '\n') {
                if !in_ws {
                    out.push(' ');
                    in_ws = true;
                }
            } else {
                out.push(ch);
                in_ws = false;
            }
        }
        out
    }

    fn unescape_md_string(s: &str) -> String {
        let bytes = s.as_bytes();
        if !bytes.contains(&b'\\') && !bytes.contains(&b'&') {
            return s.to_owned();
        }
        let mut out = String::with_capacity(s.len());
        let mut i = 0usize;
        while i < bytes.len() {
            if bytes[i] == b'\\' && i + 1 < bytes.len() && is_ascii_escapable(bytes[i + 1]) {
                out.push(bytes[i + 1] as char);
                i += 2;
            } else if bytes[i] == b'&' {
                if let Some((resolved, end)) = try_parse_entity(s, i) {
                    out.push_str(&resolved);
                    i = end;
                } else {
                    out.push('&');
                    i += 1;
                }
            } else {
                let ch = s[i..].chars().next().unwrap();
                out.push(ch);
                i += ch.len_utf8();
            }
        }
        out
    }

    #[inline]
    fn is_ascii_escapable(b: u8) -> bool {
        b.is_ascii_punctuation()
    }

    #[inline]
    fn is_md_whitespace(ch: char) -> bool {
        if matches!(ch, ' ' | '\t' | '\n' | '\x0c' | '\r') {
            return true;
        }
        matches!(
            ch,
            '\u{00A0}' | '\u{1680}' | '\u{2000}'
                ..='\u{200A}' | '\u{202F}' | '\u{205F}' | '\u{3000}'
        )
    }

    #[inline]
    fn is_md_punctuation(ch: char) -> bool {
        if ch.is_ascii() {
            return ch.is_ascii_punctuation();
        }
        let cp = ch as u32;
        NON_ASCII_PS_RANGES
            .binary_search_by(|&(start, end)| {
                if cp < start {
                    std::cmp::Ordering::Greater
                } else if cp > end {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    #[test]
    fn decodes_boms_and_windows_1251_and_normalizes_line_endings() {
        let sample = "Українська книжка — їжак і ґанок";

        assert_eq!(decode_text(sample.as_bytes()).as_deref(), Ok(sample));

        let mut utf8_bom = b"\xef\xbb\xbf".to_vec();
        utf8_bom.extend_from_slice(sample.as_bytes());
        assert_eq!(decode_text(&utf8_bom).as_deref(), Ok(sample));

        let mut utf16le = b"\xff\xfe".to_vec();
        utf16le.extend(sample.encode_utf16().flat_map(u16::to_le_bytes));
        assert_eq!(decode_text(&utf16le).as_deref(), Ok(sample));

        let mut utf16be = b"\xfe\xff".to_vec();
        utf16be.extend(sample.encode_utf16().flat_map(u16::to_be_bytes));
        assert_eq!(decode_text(&utf16be).as_deref(), Ok(sample));

        // "Привет" in Windows-1251 (not valid UTF-8).
        let cp1251 = b"\xcf\xf0\xe8\xe2\xe5\xf2";
        assert_eq!(decode_text(cp1251).as_deref(), Ok("Привет"));

        assert_eq!(decode_text(b"a\r\nb\rc\x0cd").as_deref(), Ok("a\nb\nc\nd"));
    }

    #[test]
    fn rejects_utf32_binary_blank_and_malformed_bom_inputs() {
        assert_eq!(
            decode_text(b"\xff\xfe\x00\x00A\x00\x00\x00"),
            Err(ERR_UTF32.into())
        );
        assert_eq!(
            decode_text(b"\x00\x00\xfe\xff\x00\x00\x00A"),
            Err(ERR_UTF32.into())
        );
        assert_eq!(decode_text(b"hello\x00world"), Err(ERR_BINARY.into()));
        assert_eq!(decode_text(b"hello\x7fworld"), Err(ERR_BINARY.into()));
        assert_eq!(decode_text(b" \n\t\x0c\r "), Err(ERR_EMPTY.into()));
        assert_eq!(decode_text(b""), Err(ERR_EMPTY.into()));
        assert!(decode_text(b"\xff\xfeA").is_err());
        assert!(decode_text(b"\xef\xbb\xbf\xff").is_err());
        // Unmapped byte 0x98 in Windows-1251 (with invalid UTF-8 lead to trigger CP1251 fallback).
        assert!(decode_text(b"\xcf\xff\x98").is_err());
    }

    #[test]
    fn splits_txt_paragraphs_preserving_indentation_and_single_linebreaks() {
        let src =
            b"  <b>Literal & text</b>\r\n# Not a heading\r\n   \r\n**Not bold**\rLast line\x0cNext";
        let paragraphs = split_txt_paragraphs(src).unwrap();
        assert_eq!(
            paragraphs,
            vec![
                "  <b>Literal & text</b>\n# Not a heading",
                "**Not bold**\nLast line\nNext",
            ]
        );
    }

    #[test]
    fn generates_unicode_and_deduplicated_heading_slugs() {
        let headings = vec![
            "Книжка!".into(),
            "MD-40. Финиш".into(),
            "Repeat".into(),
            "Repeat".into(),
            "Repeat".into(),
            "A  B\tC".into(),
            "Їжак\u{0301} & Ґанок".into(),
            "ΣΟΦΟΣ".into(),
            "İstanbul".into(),
        ];
        assert_eq!(
            heading_slugs(&headings),
            vec![
                "книжка",
                "md-40-финиш",
                "repeat",
                "repeat-1",
                "repeat-2",
                "a--b-c",
                "їжак\u{0301}--ґанок",
                "σοφος",
                "i\u{0307}stanbul",
            ]
        );
    }

    #[test]
    fn write_txt_frb_produces_valid_frb_v2_revision_5_container() {
        static NEXT_DIR: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "txt-frb-test-{}-{}",
            std::process::id(),
            NEXT_DIR.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("book.frb");
        let meta = b"test-txt-source-identity";
        let raw_txt = concat!(
            "Перший абзац українською мовою про їжака, ґанок та єнота в лісі.\r\n",
            "Другий рядок першого абзацу з додатковим українським текстом про їжаків.\r\n",
            "\r\n",
            "Повторюваний короткий абзац.\n\n",
            "Повторюваний короткий абзац.\n\n",
            "Довгий абзац українською мовою понад сто двадцять вісім байтів для перевірки прямого запису рядка без словника: 01234567890123456789"
        );

        write_txt_frb(raw_txt.as_bytes(), &dest, meta, None).unwrap();
        // Existing path must be refused without truncating it.
        assert!(write_txt_frb(b"other", &dest, meta, None).is_err());

        let mut reader = crate::frb::Reader::open(&dest, meta).unwrap();
        let idx = reader.index();
        assert_eq!(idx.len(), 5);
        assert_eq!(idx[3], 4); // 4 paragraphs
        assert_eq!(idx[4], 0); // startElement = 0

        let block0 = reader.read_block_bytes(0).unwrap();
        let block1 = reader.read_block_bytes(1).unwrap();
        let block2 = reader.read_block_bytes(2).unwrap();
        assert_eq!(block0, encode_txt_book_index_frb(4, Some("uk")));
        assert_eq!(block1, encode_txt_book_globals_frb(Some("uk")));

        // Verify Block 2 decodes cleanly with FrbChapterDecoder in book_search.
        let search = crate::book_search::search_frb_chapter_payload(
            &block2,
            0,
            0,
            Some(4),
            "Повторюваний",
            false,
            10,
        )
        .unwrap();
        assert_eq!(search.total_count, 2);
        assert_eq!(search.matches[0].item_index, 1);
        assert_eq!(search.matches[1].item_index, 2);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn renders_markdown_headings_emphasis_strikethrough_code_and_blocks() {
        let md = concat!(
            "# Книжка! #\n",
            "## Repeat\n",
            "Repeat\n",
            "------\n\n",
            "Setext H1\n",
            "=========\n\n",
            "Paragraph with *em*, **strong**, _under_, __strong2__, ~~strike~~, and `code <b>`.\n",
            "Line with hard break  \n",
            "and backslash\\\n",
            "next line.\n\n",
            "```kotlin info\n",
            "val x = 1 < 2 && 3 > 1\n",
            "```\n\n",
            "    indented <code & block>\n",
            "    line 2\n\n",
            "> Quoted *line*\n",
            "> lazy continuation\n\n",
            "---\n\n",
            "- tight 1\n",
            "  - nested 1\n",
            "    - nested 2\n",
            "  - nested back\n",
            "- tight 2\n\n",
            "3. loose 1\n\n",
            "4. loose 2\n",
        );

        let html = render_markdown_html(md).unwrap();
        assert!(html.contains("<h1 id=\"книжка\">Книжка!</h1>\n"));
        assert!(html.contains("<h2 id=\"repeat\">Repeat</h2>\n"));
        assert!(html.contains("<h2 id=\"repeat-1\">Repeat</h2>\n"));
        assert!(html.contains("<h1 id=\"setext-h1\">Setext H1</h1>\n"));
        assert!(html.contains("<em>em</em>, <strong>strong</strong>, <em>under</em>, <strong>strong2</strong>, <del>strike</del>, and <code>code &lt;b&gt;</code>."));
        assert!(html.contains("Line with hard break<br />\nand backslash<br />\nnext line."));
        assert!(html.contains("<pre><code class=\"language-kotlin\">val x = 1 &lt; 2 &amp;&amp; 3 &gt; 1\n</code></pre>\n"));
        assert!(
            html.contains("<pre><code>indented &lt;code &amp; block&gt;\nline 2\n</code></pre>\n")
        );
        assert!(html.contains(
            "<blockquote>\n<p>Quoted <em>line</em>\nlazy continuation</p>\n</blockquote>\n"
        ));
        assert!(html.contains("<hr />\n"));
        assert!(html.contains(
            "<ul>\n<li>tight 1\n<ul>\n<li>nested 1\n<ul>\n<li>nested 2</li>\n</ul>\n</li>\n<li>nested back</li>\n</ul>\n</li>\n<li>tight 2</li>\n</ul>\n"
        ));
        assert!(html.contains(
            "<ol start=\"3\">\n<li>\n<p>loose 1</p>\n</li>\n<li>\n<p>loose 2</p>\n</li>\n</ol>\n"
        ));
    }

    #[test]
    fn renders_gfm_tables_links_autolinks_images_entities_and_sanitizes_html_and_urls() {
        let md = concat!(
            "| Left | Center | Right | Default |\n",
            "| :--- | :---: | ---: | --- |\n",
            "| a \\| b | *c* | `d` | e |\n\n",
            "[inline](https://example.com \"Title & <tag>\") and [bad](javascript:alert(1)) and [frag](#sec-1) and [mail](mailto:a@b.c)\n\n",
            "[ref][id1], [Collapsed][], [shortcut]\n\n",
            "[id1]: https://frog.example/path (Ref Title)\n",
            "[collapsed]: /local/path\n",
            "[shortcut]: #anchor\n\n",
            "Autolinks: <https://example.org/test?a=1&b=2> and <reader@example.org>\n\n",
            "Image: ![Alt *text* & a < b](https://example.com/pic.png \"ignored\")\n\n",
            "Entities: &mdash; &laquo;Frog&raquo; &#160; &#x2014;\n\n",
            "<script>alert('xss')</script>\n\n",
            "Inline <b>raw</b> html.\n",
        );

        let html = render_markdown_html(md).unwrap();
        assert!(
            html.contains(concat!(
                "<table>\n<thead>\n<tr>\n",
                "<th align=\"left\">Left</th>\n",
                "<th align=\"center\">Center</th>\n",
                "<th align=\"right\">Right</th>\n",
                "<th>Default</th>\n",
                "</tr>\n</thead>\n<tbody>\n<tr>\n",
                "<td align=\"left\">a | b</td>\n",
                "<td align=\"center\"><em>c</em></td>\n",
                "<td align=\"right\"><code>d</code></td>\n",
                "<td>e</td>\n",
                "</tr>\n</tbody>\n</table>\n"
            )),
            "Actual HTML:\n{html}"
        );
        assert!(html.contains("<a rel=\"nofollow\" href=\"https://example.com\" title=\"Title &amp; &lt;tag&gt;\">inline</a>"));
        assert!(html.contains("<a rel=\"nofollow\" href=\"\">bad</a>"));
        assert!(html.contains("<a rel=\"nofollow\" href=\"#sec-1\">frag</a>"));
        assert!(html.contains("<a rel=\"nofollow\" href=\"mailto:a@b.c\">mail</a>"));
        assert!(html.contains(
            "<a rel=\"nofollow\" href=\"https://frog.example/path\" title=\"Ref Title\">ref</a>"
        ));
        assert!(html.contains("<a rel=\"nofollow\" href=\"/local/path\">Collapsed</a>"));
        assert!(html.contains("<a rel=\"nofollow\" href=\"#anchor\">shortcut</a>"));
        assert!(html.contains("<a rel=\"nofollow\" href=\"https://example.org/test?a=1&amp;b=2\">https://example.org/test?a=1&amp;b=2</a>"));
        assert!(html.contains(
            "<a rel=\"nofollow\" href=\"mailto:reader@example.org\">reader@example.org</a>"
        ));
        assert!(html.contains("Image: Alt text &amp; a &lt; b"));
        assert!(!html.contains("<img"));
        assert!(html.contains("Entities: — «Frog» \u{00a0} —"));
        assert!(html.contains("<p>&lt;script&gt;alert('xss')&lt;/script&gt;</p>\n"));
        assert!(html.contains("Inline &lt;b&gt;raw&lt;/b&gt; html."));
    }

    #[test]
    fn enforces_max_markdown_depth_and_encodes_wire_format() {
        let ok_quotes = format!("{}ok", "> ".repeat(110));
        let ok_html = render_markdown_html(&ok_quotes).unwrap();
        assert!(ok_html.contains("<p>ok</p>"));

        let deep_quotes = format!("{}deep", "> ".repeat(140));
        assert_eq!(
            render_markdown_html(&deep_quotes),
            Err("Markdown nesting is too deep.".to_owned())
        );

        let deep_inlines = format!("{}x{}", "*".repeat(280), "*".repeat(280));
        assert_eq!(
            render_markdown_html(&deep_inlines),
            Err("Markdown nesting is too deep.".to_owned())
        );

        let wire_ok = render_markdown_html_wire(b"# Hello").unwrap();
        assert_eq!(&wire_ok[0..4], &TEXT_MAGIC.to_le_bytes());
        assert_eq!(wire_ok[4], STATUS_OK);

        let wire_err = render_markdown_html_wire(deep_quotes.as_bytes()).unwrap();
        assert_eq!(&wire_err[0..4], &TEXT_MAGIC.to_le_bytes());
        assert_eq!(wire_err[4], STATUS_ERR);
    }

    fn decode_wire_result(wire: &[u8]) -> Result<&[u8], &str> {
        assert!(wire.len() >= 9);
        assert_eq!(&wire[0..4], &TEXT_MAGIC.to_le_bytes());
        let len = u32::from_le_bytes(wire[5..9].try_into().unwrap()) as usize;
        assert_eq!(wire.len(), 9 + len);
        match wire[4] {
            STATUS_OK => Ok(&wire[9..]),
            STATUS_ERR => Err(std::str::from_utf8(&wire[9..]).unwrap()),
            other => panic!("unexpected wire status {other}"),
        }
    }

    #[test]
    fn parse_markdown_title_wire_handles_encodings_formatting_entities_and_edge_cases() {
        // Formatting, inline code, HTML entities, images, and raw HTML in heading.
        let md = "Intro paragraph.\n\n## *Tom* &amp; **Jerry** `code` &#x2605; ![alt](x.png) <b>tag</b>\n\n### Later";
        let wire = parse_markdown_title_wire(md.as_bytes()).unwrap();
        let title = std::str::from_utf8(decode_wire_result(&wire).unwrap()).unwrap();
        assert_eq!(title, "Tom & Jerry code ★ alt <b>tag</b>");

        // UTF-16LE with BOM.
        let utf16_src = "# Привіт, *світ*!\r\n\r\nТекст.";
        let mut utf16_le = vec![0xff, 0xfe];
        for unit in utf16_src.encode_utf16() {
            utf16_le.extend_from_slice(&unit.to_le_bytes());
        }
        let wire_u16 = parse_markdown_title_wire(&utf16_le).unwrap();
        assert_eq!(
            std::str::from_utf8(decode_wire_result(&wire_u16).unwrap()).unwrap(),
            "Привіт, світ!"
        );

        // Windows-1251 encoded Markdown.
        let (cp1251_bytes, _, had_errors) = WINDOWS_1251.encode("# Заголовок — *тест*\n\nАбзац.");
        assert!(!had_errors);
        let wire_cp1251 = parse_markdown_title_wire(&cp1251_bytes).unwrap();
        assert_eq!(
            std::str::from_utf8(decode_wire_result(&wire_cp1251).unwrap()).unwrap(),
            "Заголовок — тест"
        );

        // Setext multiline heading collapses soft break to single space.
        let setext = "Line one\n  Line   two  \n=========\n";
        let wire_setext = parse_markdown_title_wire(setext.as_bytes()).unwrap();
        assert_eq!(
            std::str::from_utf8(decode_wire_result(&wire_setext).unwrap()).unwrap(),
            "Line one Line two"
        );

        // Heading nested inside blockquote is found in document order.
        let quoted = "> ### Quoted Heading\n\n# Later Heading";
        let wire_quoted = parse_markdown_title_wire(quoted.as_bytes()).unwrap();
        assert_eq!(
            std::str::from_utf8(decode_wire_result(&wire_quoted).unwrap()).unwrap(),
            "Quoted Heading"
        );

        // Empty first heading returns empty string (matching selectFirst("h1..h6").text().takeIf { isNotBlank() }).
        let empty_first = "# \n\n## Second";
        let wire_empty_first = parse_markdown_title_wire(empty_first.as_bytes()).unwrap();
        assert_eq!(decode_wire_result(&wire_empty_first).unwrap(), b"");

        // No headings returns empty string.
        let no_heading = "Just a plain paragraph.";
        let wire_none = parse_markdown_title_wire(no_heading.as_bytes()).unwrap();
        assert_eq!(decode_wire_result(&wire_none).unwrap(), b"");

        // Blank document returns empty error.
        let wire_blank = parse_markdown_title_wire(b"   \n\t  ").unwrap();
        assert_eq!(decode_wire_result(&wire_blank), Err(ERR_EMPTY));

        // Deep nesting returns depth error.
        let deep = format!("{}x", "> ".repeat(140));
        let wire_deep = parse_markdown_title_wire(deep.as_bytes()).unwrap();
        assert_eq!(
            decode_wire_result(&wire_deep),
            Err("Markdown nesting is too deep.")
        );
    }

    #[test]
    fn parse_markdown_markup_wire_matches_multi_step_mrk2_and_handles_blank_and_errors() {
        let (cp1251_bytes, _, had_errors) =
            WINDOWS_1251.encode("# Глава 1\r\n\r\nТекст с *курсивом* и `кодом`.\r\n");
        assert!(!had_errors);

        let wire = parse_markdown_markup_wire(&cp1251_bytes).unwrap();
        let mrk2 = decode_wire_result(&wire).unwrap();
        assert_eq!(&mrk2[0..4], &0x324b_524d_i32.to_le_bytes()); // MRK2

        // Must match the 3-step pipeline byte-for-byte.
        let decoded = decode_text(&cp1251_bytes).unwrap();
        let html = render_markdown_html(&decoded).unwrap();
        let doc = crate::chapter_markup::parse(&html, true).unwrap();
        let expected_mrk2 = crate::chapter_markup_jni::chapter_bytes(&doc).unwrap();
        assert_eq!(mrk2, expected_mrk2.as_slice());

        // Link-definition-only Markdown renders to blank HTML -> 0-byte STATUS_OK payload.
        let link_def_only = b"[ref]: https://example.com\n";
        let wire_empty_html = parse_markdown_markup_wire(link_def_only).unwrap();
        assert_eq!(decode_wire_result(&wire_empty_html).unwrap(), b"");

        // Blank Markdown returns STATUS_ERR.
        let wire_blank = parse_markdown_markup_wire(b" \r\n\t ").unwrap();
        assert_eq!(decode_wire_result(&wire_blank), Err(ERR_EMPTY));

        // Deep nesting returns STATUS_ERR.
        let deep = format!("{}x{}", "*".repeat(280), "*".repeat(280));
        let wire_deep = parse_markdown_markup_wire(deep.as_bytes()).unwrap();
        assert_eq!(
            decode_wire_result(&wire_deep),
            Err("Markdown nesting is too deep.")
        );
    }

    #[test]
    fn parse_markdown_content_wire_and_write_md_frb_handle_standard_and_table_fallback() {
        let md = "# Книга 🐸\n\nТекст з **жирним**, *курсивом*, ~~закресленим~~, `кодом`, та [посиланням](#%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0-).\n\n> Цитата\n\n- Пункт 1\n- Пункт 2\n\n---\n";
        let wire = parse_markdown_content_wire(md.as_bytes()).unwrap();
        assert_eq!(&wire[0..4], &TEXT_MAGIC.to_le_bytes());
        assert_eq!(wire[4], STATUS_OK);
        let payload = decode_wire_result(&wire).unwrap();
        assert!(!payload.is_empty());

        // Fragment link resolution matches java.net.URI(href).fragment
        assert_eq!(
            resolve_markdown_fragment_link("#%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0").as_deref(),
            Some("#книга")
        );
        assert_eq!(resolve_markdown_fragment_link("#").as_deref(), Some("#"));
        assert_eq!(
            resolve_markdown_fragment_link("#bad space").as_deref(),
            Some("#bad space")
        );
        assert_eq!(
            resolve_markdown_fragment_link("#bad%ZZ").as_deref(),
            Some("#bad%ZZ")
        );
        assert_eq!(resolve_markdown_fragment_link("https://example.com"), None);

        // GFM table now runs natively via html_mapper with STATUS_OK (0)
        let with_table = "# Table\n\n| A | B |\n| :--- | ---: |\n| **1** | 2 |\n";
        let tbl_wire = parse_markdown_content_wire(with_table.as_bytes()).unwrap();
        assert_eq!(&tbl_wire[0..4], &TEXT_MAGIC.to_le_bytes());
        assert_eq!(tbl_wire[4], STATUS_OK);
        let tbl_payload = decode_wire_result(&tbl_wire).unwrap();
        assert!(!tbl_payload.is_empty());
        let MarkdownParseOutcome::Ok(tbl_book) = parse_markdown_book_content(with_table.as_bytes())
        else {
            panic!("expected native MarkdownParseOutcome::Ok for GFM table");
        };
        assert_eq!(tbl_book.chapters.len(), 1);
        assert_eq!(tbl_book.chapters[0].elements.len(), 2);
        let crate::frb_model::FrbContentElement::Table(ref tbl) = tbl_book.chapters[0].elements[1]
        else {
            panic!("expected Table element");
        };
        assert_eq!(tbl.rows.len(), 2);
        assert!(tbl.rows[0].is_header);
        assert_eq!(tbl.rows[0].cells[0].text.text, "A");
        assert_eq!(tbl.rows[0].cells[1].text.text, "B");
        assert_eq!(tbl.rows[1].cells[0].text.text, "1");
        assert_eq!(tbl.rows[1].cells[1].text.text, "2");
        assert_eq!(
            tbl.rows[1].cells[1].align,
            Some(crate::frb_model::FrbBlockAlign::Right)
        );

        // Link-definition-only Markdown returns ERR_MD_NO_CONTENT
        let empty_html_wire = parse_markdown_content_wire(b"[ref]: https://example.com\n").unwrap();
        assert_eq!(decode_wire_result(&empty_html_wire), Err(ERR_MD_NO_CONTENT));

        // Direct write_md_frb writes valid .frb for both standard docs and GFM tables, and refuses overwrite
        let tmp_dir =
            std::env::temp_dir().join(format!("frogreader-md-frb-test-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&tmp_dir);
        let ok_path = tmp_dir.join("ok.frb");
        let _ = std::fs::remove_file(&ok_path);
        let meta = b"test-md-meta";
        assert_eq!(
            write_md_frb(md.as_bytes(), &ok_path, meta),
            MD_FRB_STATUS_OK
        );
        assert!(ok_path.is_file());
        // Must refuse existing file
        assert_eq!(
            write_md_frb(md.as_bytes(), &ok_path, meta),
            MD_FRB_STATUS_ERR
        );
        // Verify FRB container opens cleanly
        let mut reader = crate::frb::Reader::open(&ok_path, meta).unwrap();
        assert_eq!(reader.index().len(), 5);
        assert!(!reader.read_block_bytes(0).unwrap().is_empty());
        assert!(!reader.read_block_bytes(1).unwrap().is_empty());
        assert!(!reader.read_block_bytes(2).unwrap().is_empty());

        let tbl_path = tmp_dir.join("table.frb");
        let _ = std::fs::remove_file(&tbl_path);
        assert_eq!(
            write_md_frb(with_table.as_bytes(), &tbl_path, meta),
            MD_FRB_STATUS_OK
        );
        assert!(tbl_path.is_file());
        let mut tbl_reader = crate::frb::Reader::open(&tbl_path, meta).unwrap();
        assert_eq!(tbl_reader.index().len(), 5);
        assert!(!tbl_reader.read_block_bytes(2).unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&tmp_dir);
    }
}
