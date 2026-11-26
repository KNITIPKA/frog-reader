//! Native Rust full-book EPUB -> `FrbBookContent` / `.frb` v2 pipeline (M6.2b).
//!
//! Opens the EPUB archive directly in Rust, parses package/navigation/encryption XML,
//! enforces all `ReaderResourceLimits` budgets, compiles shared CSS stylesheets once,
//! maps eligible spine documents in parallel across worker threads, and merges chapters,
//! TOC splits, anchors, notes, navigation, images, fonts, and `EpubSourceRange`s into
//! `FrbBookContent` (or writes a CRC-checked `.frb` v2 container directly with zero
//! JVM `BookContent` / `Chapter` allocation).

use crate::chapter_charset;
use crate::chapter_markup::mapper::{
    map_document, slice_publisher_box_spans, MapError, MapOptions, ResourceCallbacks,
};
use crate::chapter_markup::{self, Document, Kind};
use crate::css_engine::{
    ChapterCss, CompiledStylesheets, FontFaceSpec, SharedStyleCache, SheetInput,
};
use crate::epub_nav;
use crate::epub_ncx;
use crate::epub_package;
use crate::fb2_stream::body::sha256;
use crate::frb;
use crate::frb_model::{
    encode_book_content, encode_book_globals, encode_book_index, encode_chapter,
    FrbAnnotatedString, FrbBlockAlign, FrbBookContent, FrbBookFont, FrbBookIndex, FrbChapter,
    FrbChapterIndex, FrbContentElement, FrbEmbeddedFont, FrbEpubSourceRange, FrbFontTypography,
    FrbImageElement, FrbLinkedDocument, FrbLinkedDocumentTarget, FrbNavigationEntry,
    FrbNavigationTarget, FrbNoteDocument, FrbPageProgression, FrbParagraphStyle,
    FrbPublisherBoxSpan, FrbPublisherStyle, FrbTextAnnotation, INLINE_IMAGE_TAG,
};
use crate::woff;
use quick_xml::events::Event as XmlEvent;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fmt::Write as _;
use std::fs::{self, File, OpenOptions};
use std::hash::Hash;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use zip::ZipArchive;

/// Maximum number of spine items simultaneously in-flight or awaiting in-order commit.
const SLIDING_WINDOW_SIZE: usize = 8;

// --------------------------------------------------------------------------- Wire Protocol

pub(crate) const EPBC_MAGIC: u32 = 0x4342_5045; // "EPBC" LE
pub(crate) const STATUS_OK: u8 = 0;
pub(crate) const STATUS_FALLBACK: u8 = 1;
pub(crate) const STATUS_LIMIT: u8 = 2;
pub(crate) const STATUS_ERR: u8 = 3;

const OPF_MAGIC: u32 = 0x3146_504F; // OPF1
const NCX_MAGIC: u32 = 0x3158_434E; // NCX1
const NAV_MAGIC: u32 = 0x3156_414E; // NAV1

const MAX_NATIVE_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
const FONT_PIPELINE_VERSION: u32 = 1;
const DTBOOK_MEDIA_TYPE: &str = "application/x-dtbook+xml";
const IDPF_ALGORITHM: &str = "http://www.idpf.org/2008/embedding";
const ADOBE_ALGORITHM: &str = "http://ns.adobe.com/pdf/enc#RC";
const IDPF_PREFIX: usize = 1040;
const ADOBE_PREFIX: usize = 1024;

static TEMP_FILE_COUNTER: AtomicU64 = AtomicU64::new(1);

// --------------------------------------------------------------------------- Limits & Outcome

pub(crate) const LIMITS_LEN: usize = 15;

#[derive(Clone, Copy, Debug)]
pub(crate) struct EpubLimits {
    pub max_archive_entries: usize,
    pub max_archive_declared_bytes: u64,
    pub max_archive_read_bytes: u64,
    pub max_compression_ratio: u64,
    pub compression_ratio_check_from_bytes: u64,
    pub max_package_xml_bytes: u64,
    pub max_chapter_bytes: u64,
    pub max_stylesheet_bytes: u64,
    pub max_image_bytes: u64,
    pub max_font_bytes: u64,
    pub max_epub_css_expanded_sheets: usize,
    pub max_epub_css_expansion_operations: usize,
    pub max_epub_css_expanded_bytes: u64,
    pub max_html_generated_run_chars: usize,
    pub max_html_generated_total_chars: u64,
}

impl Default for EpubLimits {
    fn default() -> Self {
        Self {
            max_archive_entries: 20_000,
            max_archive_declared_bytes: 4 * 1024 * 1024 * 1024,
            max_archive_read_bytes: 768 * 1024 * 1024,
            max_compression_ratio: 2_000,
            compression_ratio_check_from_bytes: 8 * 1024 * 1024,
            max_package_xml_bytes: 16 * 1024 * 1024,
            max_chapter_bytes: 32 * 1024 * 1024,
            max_stylesheet_bytes: 8 * 1024 * 1024,
            max_image_bytes: 64 * 1024 * 1024,
            max_font_bytes: 32 * 1024 * 1024,
            max_epub_css_expanded_sheets: 4_096,
            max_epub_css_expansion_operations: 16_384,
            max_epub_css_expanded_bytes: 128 * 1024 * 1024,
            max_html_generated_run_chars: 4 * 1024,
            max_html_generated_total_chars: 1024 * 1024,
        }
    }
}

impl EpubLimits {
    pub(crate) fn from_slice(raw: &[i64]) -> Option<Self> {
        if raw.len() != LIMITS_LEN {
            return None;
        }
        let pos_usize = |v: i64| (v > 0).then(|| usize::try_from(v).ok()).flatten();
        let pos_u64 = |v: i64| (v > 0).then_some(v as u64);
        let nonneg_u64 = |v: i64| (v >= 0).then_some(v as u64);

        Some(Self {
            max_archive_entries: pos_usize(raw[0])?,
            max_archive_declared_bytes: pos_u64(raw[1])?,
            max_archive_read_bytes: pos_u64(raw[2])?,
            max_compression_ratio: pos_u64(raw[3])?,
            compression_ratio_check_from_bytes: nonneg_u64(raw[4])?,
            max_package_xml_bytes: pos_u64(raw[5])?,
            max_chapter_bytes: pos_u64(raw[6])?,
            max_stylesheet_bytes: pos_u64(raw[7])?,
            max_image_bytes: pos_u64(raw[8])?,
            max_font_bytes: pos_u64(raw[9])?,
            max_epub_css_expanded_sheets: pos_usize(raw[10])?,
            max_epub_css_expansion_operations: pos_usize(raw[11])?,
            max_epub_css_expanded_bytes: pos_u64(raw[12])?,
            max_html_generated_run_chars: pos_usize(raw[13])?,
            max_html_generated_total_chars: pos_u64(raw[14])?,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub(crate) enum LimitKind {
    EntryCount = 0,
    DeclaredAggregate = 1,
    ActualAggregate = 2,
    EntrySize = 3,
    CompressionRatio = 4,
    UnsafePath = 5,
}

#[derive(Debug)]
pub(crate) enum NativeEpubOutcome {
    Ready(Box<FrbBookContent>),
    Fallback(&'static str),
    Limit { kind: LimitKind, message: String },
    Error(String),
}

// --------------------------------------------------------------------------- EPBC Wire Helpers

pub(crate) fn encode_status_ok_payload(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + payload.len());
    out.extend_from_slice(&EPBC_MAGIC.to_le_bytes());
    out.push(STATUS_OK);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

pub(crate) fn encode_status_ok_empty() -> Vec<u8> {
    let mut out = Vec::with_capacity(5);
    out.extend_from_slice(&EPBC_MAGIC.to_le_bytes());
    out.push(STATUS_OK);
    out
}

pub(crate) fn encode_status_fallback(reason: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + reason.len());
    out.extend_from_slice(&EPBC_MAGIC.to_le_bytes());
    out.push(STATUS_FALLBACK);
    out.extend_from_slice(&(reason.len() as u32).to_le_bytes());
    out.extend_from_slice(reason.as_bytes());
    out
}

pub(crate) fn encode_status_limit(kind: LimitKind, message: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(10 + message.len());
    out.extend_from_slice(&EPBC_MAGIC.to_le_bytes());
    out.push(STATUS_LIMIT);
    out.push(kind as u8);
    out.extend_from_slice(&(message.len() as u32).to_le_bytes());
    out.extend_from_slice(message.as_bytes());
    out
}

pub(crate) fn encode_status_err(message: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + message.len());
    out.extend_from_slice(&EPBC_MAGIC.to_le_bytes());
    out.push(STATUS_ERR);
    out.extend_from_slice(&(message.len() as u32).to_le_bytes());
    out.extend_from_slice(message.as_bytes());
    out
}

pub(crate) fn parse_epub_content_wire(
    epub_path: &Path,
    images_dir: &Path,
    limits: &EpubLimits,
) -> Vec<u8> {
    match parse_epub_native(epub_path, images_dir, limits) {
        NativeEpubOutcome::Ready(content) => {
            let payload = encode_book_content(&content);
            encode_status_ok_payload(&payload)
        }
        NativeEpubOutcome::Fallback(reason) => encode_status_fallback(reason),
        NativeEpubOutcome::Limit { kind, message } => encode_status_limit(kind, &message),
        NativeEpubOutcome::Error(message) => encode_status_err(&message),
    }
}

pub(crate) fn write_epub_frb_wire(
    epub_path: &Path,
    images_dir: &Path,
    dest_frb_path: &Path,
    source_metadata: &[u8],
    limits: &EpubLimits,
) -> Vec<u8> {
    match write_epub_frb_streaming(
        epub_path,
        images_dir,
        dest_frb_path,
        source_metadata,
        limits,
    ) {
        Ok(()) => encode_status_ok_empty(),
        Err(NativeEpubOutcome::Fallback(reason)) => encode_status_fallback(reason),
        Err(NativeEpubOutcome::Limit { kind, message }) => encode_status_limit(kind, &message),
        Err(NativeEpubOutcome::Error(message)) => encode_status_err(&message),
        Err(NativeEpubOutcome::Ready(_)) => encode_status_ok_empty(),
    }
}

// --------------------------------------------------------------------------- ZIP Archive & Budgets

#[derive(Clone, Debug)]
struct ZipEntryMeta {
    index: usize,
    name: String,
    size: u64,
    compressed: u64,
    is_dir: bool,
}

#[derive(Debug)]
struct ArchiveDirectory {
    entries: Vec<ZipEntryMeta>,
    by_name: HashMap<String, usize>,
}

impl ArchiveDirectory {
    fn lookup(&self, path: &str) -> Option<&ZipEntryMeta> {
        if !is_safe_archive_path(path) {
            return None;
        }
        if let Some(&idx) = self.by_name.get(path) {
            return self.entries.get(idx);
        }
        if let Some(decoded) = decode_url_path(path) {
            if decoded != path && is_safe_archive_path(&decoded) {
                if let Some(&idx) = self.by_name.get(&decoded) {
                    return self.entries.get(idx);
                }
            }
        }
        None
    }
}

enum ArchiveError {
    Limit { kind: LimitKind, message: String },
    Fallback(&'static str),
    Io(String),
}

fn open_archive_with_directory(
    path: &Path,
    limits: &EpubLimits,
) -> Result<(ZipArchive<BufReader<File>>, Arc<ArchiveDirectory>), ArchiveError> {
    let file = File::open(path).map_err(|_| ArchiveError::Fallback("could not open epub file"))?;
    let archive = ZipArchive::new(BufReader::new(file))
        .map_err(|_| ArchiveError::Fallback("invalid epub zip"))?;
    if archive.len() > limits.max_archive_entries {
        return Err(ArchiveError::Limit {
            kind: LimitKind::EntryCount,
            message: format!(
                "Archive has more than {} entries",
                limits.max_archive_entries
            ),
        });
    }
    if archive.has_duplicate_names() {
        return Err(ArchiveError::Fallback("duplicate zip entry names"));
    }
    let mut entries = Vec::with_capacity(archive.len());
    let mut by_name = HashMap::with_capacity(archive.len());
    let mut declared = 0_u64;
    for index in 0..archive.len() {
        let entry = archive
            .by_index_data(index)
            .map_err(|_| ArchiveError::Fallback("invalid zip central directory"))?;
        let name = entry
            .name()
            .map_err(|_| ArchiveError::Fallback("invalid zip entry name"))?;
        let size = entry.size();
        let compressed = entry.compressed_size();
        declared = declared
            .checked_add(size)
            .ok_or_else(|| ArchiveError::Limit {
                kind: LimitKind::DeclaredAggregate,
                message: format!(
                    "Archive declares more than {} uncompressed bytes",
                    limits.max_archive_declared_bytes
                ),
            })?;
        if declared > limits.max_archive_declared_bytes {
            return Err(ArchiveError::Limit {
                kind: LimitKind::DeclaredAggregate,
                message: format!(
                    "Archive declares more than {} uncompressed bytes",
                    limits.max_archive_declared_bytes
                ),
            });
        }
        if name.is_empty() || compressed > i64::MAX as u64 {
            return Err(ArchiveError::Fallback("invalid zip entry metadata"));
        }
        let name_owned = name.into_owned();
        let is_dir = name_owned.ends_with('/');
        if by_name.insert(name_owned.clone(), index).is_some() {
            return Err(ArchiveError::Fallback("duplicate zip entry name"));
        }
        entries.push(ZipEntryMeta {
            index,
            name: name_owned,
            size,
            compressed,
            is_dir,
        });
    }
    Ok((archive, Arc::new(ArchiveDirectory { entries, by_name })))
}

fn open_worker_archive(path: &Path) -> Option<ZipArchive<BufReader<File>>> {
    let file = File::open(path).ok()?;
    ZipArchive::new(BufReader::new(file)).ok()
}

fn validate_archive_entry(
    entry: &ZipEntryMeta,
    max_bytes: u64,
    label: &str,
    limits: &EpubLimits,
) -> Result<(), ArchiveError> {
    if !is_safe_archive_path(&entry.name) {
        return Err(ArchiveError::Limit {
            kind: LimitKind::UnsafePath,
            message: format!("Unsafe archive path for {label}: {}", entry.name),
        });
    }
    let effective_max = max_bytes.min(MAX_NATIVE_ENTRY_BYTES);
    if entry.size > effective_max {
        return Err(ArchiveError::Limit {
            kind: LimitKind::EntrySize,
            message: format!(
                "{label} declares {} bytes, limit is {effective_max}",
                entry.size
            ),
        });
    }
    if entry.size >= limits.compression_ratio_check_from_bytes
        && entry.size
            > entry
                .compressed
                .max(1)
                .saturating_mul(limits.max_compression_ratio)
    {
        return Err(ArchiveError::Limit {
            kind: LimitKind::CompressionRatio,
            message: format!("{label} has an implausible compression ratio"),
        });
    }
    Ok(())
}

fn extract_entry_exact(
    archive: &mut ZipArchive<BufReader<File>>,
    entry: &ZipEntryMeta,
    output: &mut impl Write,
) -> Result<(), bool> {
    if entry.is_dir || entry.size > MAX_NATIVE_ENTRY_BYTES {
        return Err(false);
    }
    let mut reader = archive.by_index(entry.index).map_err(|_| false)?;
    let mut buffer = [0_u8; 16 * 1024];
    let mut written = 0_u64;
    loop {
        let count = reader.read(&mut buffer).map_err(|_| false)?;
        if count == 0 {
            break;
        }
        if (count as u64) > entry.size - written {
            return Err(false);
        }
        output.write_all(&buffer[..count]).map_err(|_| true)?;
        written += count as u64;
    }
    if written != entry.size {
        return Err(false);
    }
    Ok(())
}

fn read_entry_checked(
    archive: &mut ZipArchive<BufReader<File>>,
    entry: &ZipEntryMeta,
    max_bytes: u64,
    label: &str,
    optional: bool,
    limits: &EpubLimits,
    bytes_read: &mut u64,
) -> Result<Option<Vec<u8>>, ArchiveError> {
    if let Err(err) = validate_archive_entry(entry, max_bytes, label, limits) {
        if optional {
            return Ok(None);
        }
        return Err(err);
    }
    if entry.size > limits.max_archive_read_bytes.saturating_sub(*bytes_read) {
        return Err(ArchiveError::Limit {
            kind: LimitKind::ActualAggregate,
            message: format!(
                "Archive expands beyond {} bytes during this open",
                limits.max_archive_read_bytes
            ),
        });
    }
    *bytes_read += entry.size;
    let mut out = Vec::new();
    if out.try_reserve_exact(entry.size as usize).is_err() {
        return if optional {
            Ok(None)
        } else {
            Err(ArchiveError::Fallback("entry allocation failed"))
        };
    }
    match extract_entry_exact(archive, entry, &mut out) {
        Ok(()) => Ok(Some(out)),
        Err(_) => {
            if optional {
                Ok(None)
            } else {
                Err(ArchiveError::Fallback("damaged required epub entry"))
            }
        }
    }
}

fn write_atomic_file(
    target: &Path,
    write_fn: impl FnOnce(&mut File) -> Result<(), bool>,
) -> Result<(), bool> {
    let Some(parent) = target.parent() else {
        return Err(true);
    };
    fs::create_dir_all(parent).map_err(|_| true)?;
    let seq = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let partial = parent.join(format!(".reader-resource-{pid}-{seq}.tmp"));
    let result = (|| -> Result<(), bool> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&partial)
            .map_err(|_| true)?;
        write_fn(&mut file)?;
        file.flush().map_err(|_| true)?;
        drop(file);
        fs::rename(&partial, target).map_err(|_| true)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&partial);
    }
    result
}

#[allow(clippy::too_many_arguments)]
fn copy_entry_checked(
    archive: &mut ZipArchive<BufReader<File>>,
    entry: &ZipEntryMeta,
    target: &Path,
    max_bytes: u64,
    label: &str,
    optional: bool,
    limits: &EpubLimits,
    bytes_read: &mut u64,
) -> Result<bool, ArchiveError> {
    if let Err(err) = validate_archive_entry(entry, max_bytes, label, limits) {
        if optional {
            return Ok(false);
        }
        return Err(err);
    }
    if entry.size > limits.max_archive_read_bytes.saturating_sub(*bytes_read) {
        return Err(ArchiveError::Limit {
            kind: LimitKind::ActualAggregate,
            message: format!(
                "Archive expands beyond {} bytes during this open",
                limits.max_archive_read_bytes
            ),
        });
    }
    *bytes_read += entry.size;
    match write_atomic_file(target, |file| extract_entry_exact(archive, entry, file)) {
        Ok(()) => Ok(true),
        Err(is_output_err) => {
            if is_output_err {
                Err(ArchiveError::Io(format!("Could not write {label}")))
            } else if optional {
                Ok(false)
            } else {
                Err(ArchiveError::Fallback("damaged required epub entry"))
            }
        }
    }
}

// --------------------------------------------------------------------------- Paths, URLs & Digests

pub(crate) fn is_safe_archive_path(path: &str) -> bool {
    if path.is_empty() || path.contains('\0') || path.contains('\\') || path.starts_with('/') {
        return false;
    }
    let first_seg = path.split('/').next().unwrap_or("");
    let bytes = first_seg.as_bytes();
    if bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':' {
        return false;
    }
    !path.split('/').any(|seg| seg == "..")
}

fn hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Exact match for Kotlin `URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")`.
/// Literal `'+'` is preserved; invalid `%` escape returns `None`.
pub(crate) fn decode_url_path(path: &str) -> Option<String> {
    if !path.contains('%') {
        return Some(path.to_owned());
    }
    let bytes = path.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return None;
            }
            let hi = hex_nibble(bytes[i + 1])?;
            let lo = hex_nibble(bytes[i + 2])?;
            out.push((hi << 4) | lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    Some(String::from_utf8_lossy(&out).into_owned())
}

pub(crate) fn parent_dir(path: &str) -> &str {
    path.rsplit_once('/').map_or("", |(dir, _)| dir)
}

/// Resolves `href` relative to `base_dir`, handling `.` and `..` segments.
pub(crate) fn resolve_path(base_dir: &str, href: &str) -> String {
    let path = href
        .split('#')
        .next()
        .unwrap_or("")
        .split('?')
        .next()
        .unwrap_or("");
    let raw = decode_url_path(path).unwrap_or_else(|| path.to_owned());
    let combined = if base_dir.is_empty() {
        raw
    } else {
        format!("{base_dir}/{raw}")
    };
    let mut parts: Vec<&str> = Vec::new();
    for segment in combined.split('/') {
        match segment {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            other => parts.push(other),
        }
    }
    parts.join("/")
}

/// Exact match for `EpubParser.resolveLinkKey`.
pub(crate) fn resolve_link_key(
    href: &str,
    chapter_path: &str,
    chapter_dir: &str,
) -> Option<String> {
    let lower = href.to_lowercase();
    if lower.starts_with("http:")
        || lower.starts_with("https:")
        || lower.starts_with("mailto:")
        || lower.starts_with("tel:")
    {
        return None;
    }
    let (path, raw_fragment) = match href.split_once('#') {
        Some((p, f)) => (p, f),
        None => (href, ""),
    };
    let fragment = decode_url_path(raw_fragment).unwrap_or_else(|| raw_fragment.to_owned());
    if fragment.is_empty() {
        if path.is_empty() {
            return None;
        }
        let extension = path
            .rsplit_once('.')
            .map_or(path, |(_, e)| e)
            .to_lowercase();
        if !extension.starts_with("htm") && !extension.starts_with("xhtm") && extension != "svg" {
            return None;
        }
        return Some(resolve_path(chapter_dir, path));
    }
    let target = if path.is_empty() {
        chapter_path.to_owned()
    } else {
        resolve_path(chapter_dir, path)
    };
    Some(format!("{target}#{fragment}"))
}

pub(crate) fn resource_digest(canonical: &str) -> String {
    let digest = sha256(canonical.as_bytes());
    let mut hex = String::with_capacity(32);
    for &byte in &digest[..16] {
        let _ = write!(&mut hex, "{byte:02x}");
    }
    hex
}

pub(crate) fn resource_cache_file_name(
    prefix: &str,
    canonical: &str,
    display_name: &str,
) -> String {
    let tail = display_name
        .rsplit_once('/')
        .map_or(display_name, |(_, after)| after);
    let sanitized: String = tail
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = sanitized.trim_matches('_');
    let leaf = if trimmed.len() > 80 {
        &trimmed[trimmed.len() - 80..]
    } else {
        trimmed
    };
    let leaf = if leaf.trim().is_empty() {
        "resource"
    } else {
        leaf
    };
    format!("{prefix}_{}_{leaf}", resource_digest(canonical))
}

fn font_file_name(entry_path: &str) -> String {
    resource_cache_file_name(
        &format!("font_v{FONT_PIPELINE_VERSION}"),
        entry_path,
        entry_path,
    )
}

fn image_file_name(entry_path: &str) -> String {
    resource_cache_file_name("epub", entry_path, entry_path)
}

// --------------------------------------------------------------------------- Package / TOC / Rendition

struct WireCursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> WireCursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn u32(&mut self) -> Option<u32> {
        let slice = self.bytes.get(self.pos..self.pos + 4)?;
        self.pos += 4;
        Some(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
    }

    fn string(&mut self) -> Option<String> {
        let len = self.u32()? as usize;
        let slice = self.bytes.get(self.pos..self.pos + len)?;
        self.pos += len;
        std::str::from_utf8(slice).ok().map(str::to_owned)
    }

    fn finish(&self) -> Option<()> {
        (self.pos == self.bytes.len()).then_some(())
    }
}

#[derive(Debug)]
struct OpfMetadata {
    tag: String,
    text: String,
    id: String,
    _role: String,
    _scheme: String,
    name: String,
    property: String,
    _refines: String,
    content: String,
}

#[derive(Clone, Debug)]
struct OpfManifestItem {
    id: String,
    href: String,
    media_type: String,
    properties: String,
    fallback: String,
}

impl OpfManifestItem {
    fn has_property(&self, token: &str) -> bool {
        self.properties.split_whitespace().any(|t| t == token)
    }
}

#[derive(Debug)]
struct OpfItemRef {
    idref: String,
    linear: String,
    _id: String,
    properties: String,
}

#[derive(Debug)]
struct ParsedOpf {
    version: String,
    unique_identifier: String,
    spine_toc: String,
    page_progression_direction: String,
    metadata: Vec<OpfMetadata>,
    manifest: Vec<OpfManifestItem>,
    itemrefs: Vec<OpfItemRef>,
}

impl ParsedOpf {
    fn dc_fields<'a>(&'a self, local_name: &'a str) -> impl Iterator<Item = &'a OpfMetadata> + 'a {
        let target = format!("dc:{local_name}");
        self.metadata
            .iter()
            .filter(move |m| m.tag.eq_ignore_ascii_case(&target))
    }
}

fn decode_opf_wire(wire: &[u8]) -> Option<ParsedOpf> {
    let mut cur = WireCursor::new(wire);
    if cur.u32()? != OPF_MAGIC {
        return None;
    }
    let version = cur.string()?;
    let unique_identifier = cur.string()?;
    let spine_toc = cur.string()?;
    let page_progression_direction = cur.string()?;

    let meta_count = cur.u32()? as usize;
    let mut metadata = Vec::with_capacity(meta_count.min(4096));
    for _ in 0..meta_count {
        metadata.push(OpfMetadata {
            tag: cur.string()?,
            text: cur.string()?,
            id: cur.string()?,
            _role: cur.string()?,
            _scheme: cur.string()?,
            name: cur.string()?,
            property: cur.string()?,
            _refines: cur.string()?,
            content: cur.string()?,
        });
    }

    let manifest_count = cur.u32()? as usize;
    let mut manifest = Vec::with_capacity(manifest_count.min(4096));
    for _ in 0..manifest_count {
        manifest.push(OpfManifestItem {
            id: cur.string()?,
            href: cur.string()?,
            media_type: cur.string()?,
            properties: cur.string()?,
            fallback: cur.string()?,
        });
    }

    let ref_count = cur.u32()? as usize;
    let mut itemrefs = Vec::with_capacity(ref_count.min(4096));
    for _ in 0..ref_count {
        itemrefs.push(OpfItemRef {
            idref: cur.string()?,
            linear: cur.string()?,
            _id: cur.string()?,
            properties: cur.string()?,
        });
    }

    cur.finish()?;
    Some(ParsedOpf {
        version,
        unique_identifier,
        spine_toc,
        page_progression_direction,
        metadata,
        manifest,
        itemrefs,
    })
}

#[derive(Clone, Debug)]
struct SpineItem {
    item: OpfManifestItem,
    linear: bool,
    ordinal: usize,
    item_ref_properties: String,
}

fn contains_ascii_ignore_case(haystack: &str, needle: &str) -> bool {
    let h = haystack.as_bytes();
    let n = needle.as_bytes();
    if n.is_empty() {
        return true;
    }
    if n.len() > h.len() {
        return false;
    }
    h.windows(n.len()).any(|w| w.eq_ignore_ascii_case(n))
}

fn build_spine_items(opf: &ParsedOpf) -> Vec<SpineItem> {
    let mut items_by_id: HashMap<&str, &OpfManifestItem> =
        HashMap::with_capacity(opf.manifest.len());
    for item in &opf.manifest {
        // Match Kotlin `associateBy { it.id }` (last wins on duplicate id).
        items_by_id.insert(item.id.as_str(), item);
    }

    let renderable = |item: &OpfManifestItem| -> Option<OpfManifestItem> {
        let mut candidate = Some(item);
        let mut seen = HashSet::new();
        while let Some(curr) = candidate {
            if !seen.insert(curr.id.as_str()) {
                break;
            }
            if contains_ascii_ignore_case(&curr.media_type, "html")
                || curr.media_type.eq_ignore_ascii_case("image/svg+xml")
                || curr.media_type.eq_ignore_ascii_case(DTBOOK_MEDIA_TYPE)
            {
                return Some(curr.clone());
            }
            candidate = (!curr.fallback.is_empty())
                .then(|| items_by_id.get(curr.fallback.as_str()).copied())
                .flatten();
        }
        None
    };

    if !opf.itemrefs.is_empty() {
        let resolved: Vec<SpineItem> = opf
            .itemrefs
            .iter()
            .enumerate()
            .filter_map(|(ordinal, r)| {
                let item = items_by_id.get(r.idref.as_str()).copied()?;
                let rendered = renderable(item)?;
                Some(SpineItem {
                    item: rendered,
                    linear: !r.linear.eq_ignore_ascii_case("no"),
                    ordinal,
                    item_ref_properties: r.properties.clone(),
                })
            })
            .collect();
        if !resolved.is_empty() {
            return resolved;
        }
    }

    let mut seen_ids = HashSet::new();
    opf.manifest
        .iter()
        .filter_map(renderable)
        .filter(|item| seen_ids.insert(item.id.clone()))
        .enumerate()
        .map(|(ordinal, item)| SpineItem {
            item,
            linear: true,
            ordinal,
            item_ref_properties: String::new(),
        })
        .collect()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum LayoutKind {
    Reflowable,
    PrePaginated,
}

struct PackageRendition {
    default_layout: LayoutKind,
    page_progression: FrbPageProgression,
}

fn parse_package_rendition(opf: &ParsedOpf) -> PackageRendition {
    let version_major = opf
        .version
        .trim()
        .split('.')
        .next()
        .and_then(|s| s.parse::<i32>().ok());
    let is_epub3 = version_major.is_some_and(|v| v >= 3);

    let metas: Vec<&OpfMetadata> = opf
        .metadata
        .iter()
        .filter(|m| {
            let local = m.tag.rsplit_once(':').map_or(m.tag.as_str(), |(_, l)| l);
            local.eq_ignore_ascii_case("meta")
        })
        .collect();

    let standard_layout = metas
        .iter()
        .find(|m| m.property.eq_ignore_ascii_case("rendition:layout"))
        .and_then(|m| {
            let v = if m.text.trim().is_empty() {
                m.content.trim()
            } else {
                m.text.trim()
            };
            match v.to_ascii_lowercase().as_str() {
                "reflowable" => Some(LayoutKind::Reflowable),
                "pre-paginated" => Some(LayoutKind::PrePaginated),
                _ => None,
            }
        });

    let amazon_fixed = !is_epub3
        && metas
            .iter()
            .find(|m| m.name.eq_ignore_ascii_case("fixed-layout"))
            .map(|m| m.content.trim())
            .filter(|s| !s.is_empty())
            .is_some_and(|v| v.eq_ignore_ascii_case("true"));

    let default_layout = standard_layout.unwrap_or(if amazon_fixed {
        LayoutKind::PrePaginated
    } else {
        LayoutKind::Reflowable
    });

    let page_progression = match opf
        .page_progression_direction
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "ltr" => FrbPageProgression::Ltr,
        "rtl" => FrbPageProgression::Rtl,
        _ => FrbPageProgression::Default,
    };

    PackageRendition {
        default_layout,
        page_progression,
    }
}

fn is_fixed_layout(rendition: &PackageRendition, item: &SpineItem) -> bool {
    if item.item_ref_properties.is_empty() {
        return rendition.default_layout == LayoutKind::PrePaginated;
    }
    let mut found_reflow = false;
    let mut found_pre = false;
    for token in item.item_ref_properties.split_whitespace() {
        match token.to_ascii_lowercase().as_str() {
            "rendition:layout-reflowable" => found_reflow = true,
            "rendition:layout-pre-paginated" => found_pre = true,
            _ => {}
        }
    }
    let resolved = match (found_reflow, found_pre) {
        (true, false) => LayoutKind::Reflowable,
        (false, true) => LayoutKind::PrePaginated,
        _ => rendition.default_layout,
    };
    resolved == LayoutKind::PrePaginated
}

// --------------------------------------------------------------------------- TOC & Title Composition

#[derive(Clone, Debug)]
struct TocEntry {
    title: String,
    depth: i32,
    path: String,
    fragment: Option<String>,
}

impl TocEntry {
    fn target_key(&self) -> String {
        match &self.fragment {
            Some(frag) => format!("{}#{frag}", self.path),
            None => self.path.clone(),
        }
    }
}

struct TocIndex {
    by_path: HashMap<String, Vec<TocEntry>>,
    entries: Vec<TocEntry>,
}

fn is_part_numeral_char(ch: char) -> bool {
    if ch.is_ascii_digit() {
        return true;
    }
    for lc in ch.to_lowercase() {
        if matches!(
            lc,
            'i' | 'v' | 'x' | 'l' | 'c' | 'd' | 'm' | 'а'..='я' | 'і' | 'ї' | 'є' | 'ґ'
        ) {
            return true;
        }
    }
    false
}

/// Exact match for `TitleStructure.kt` `partDesignator`.
pub(crate) fn part_designator(title: &str) -> String {
    let line = title.split('\n').next().unwrap_or("").trim();
    const KEYWORDS: [&str; 7] = ["часть", "частина", "книга", "том", "part", "book", "volume"];
    let lower_line = line.to_lowercase();
    for kw in KEYWORDS {
        if lower_line.starts_with(kw) {
            // Find byte offset in `line` matching the same number of chars as `kw`.
            let kw_chars = kw.chars().count();
            if let Some((after_kw_byte, _)) = line.char_indices().nth(kw_chars) {
                let rest = &line[after_kw_byte..];
                let mut iter = rest.char_indices().peekable();
                // Require >= 1 whitespace char.
                let mut ws_count = 0;
                while let Some(&(_, c)) = iter.peek() {
                    if c.is_whitespace() {
                        ws_count += 1;
                        iter.next();
                    } else {
                        break;
                    }
                }
                if ws_count > 0 {
                    // Require >= 1 numeral/letter char in `[0-9ivxlcdmа-яіїєґ]`.
                    let mut num_count = 0;
                    let mut end_rel = 0;
                    while let Some(&(idx, c)) = iter.peek() {
                        if is_part_numeral_char(c) {
                            num_count += 1;
                            end_rel = idx + c.len_utf8();
                            iter.next();
                        } else {
                            break;
                        }
                    }
                    if num_count > 0 {
                        if let Some(&(idx, c)) = iter.peek() {
                            if matches!(c, '.' | ':' | ')') {
                                end_rel = idx + c.len_utf8();
                                iter.next();
                            }
                        }
                        let boundary_ok = match iter.peek() {
                            None => true,
                            Some(&(_, next_c)) => next_c.is_whitespace(),
                        };
                        if boundary_ok {
                            let matched = &line[..after_kw_byte + end_rel];
                            return matched.trim_end_matches(['.', ':', ')']).trim().to_owned();
                        }
                    }
                }
            }
        }
    }

    // `firstSentence = Regex("^(.+?[.!?…])\\s+\\S")`
    let mut seen_first = false;
    for (idx, c) in line.char_indices() {
        if seen_first && matches!(c, '.' | '!' | '?' | '…') {
            let after_punct = idx + c.len_utf8();
            let tail = &line[after_punct..];
            let mut tail_chars = tail.chars();
            if tail_chars.next().is_some_and(char::is_whitespace)
                && tail_chars.any(|ch| !ch.is_whitespace())
            {
                return line[..after_punct].trim_end_matches('.').trim().to_owned();
            }
        }
        seen_first = true;
    }

    line.to_owned()
}

/// Exact match for `TitleStructure.kt` `composeNestedChapterTitle`.
pub(crate) fn compose_nested_chapter_title(part_title: &str, chapter_title: &str) -> String {
    let head = part_designator(part_title);
    if head.trim().is_empty() {
        return chapter_title.to_owned();
    }
    let (first_line, rest_raw) = match chapter_title.split_once('\n') {
        Some((a, b)) => (a.trim(), b),
        None => (chapter_title.trim(), ""),
    };
    let rest_replaced = rest_raw.replace('\n', " ");
    let rest = rest_replaced.trim();
    if rest.is_empty() {
        format!("{head} {first_line}")
    } else {
        format!("{head} {first_line}\n{rest}")
    }
}

fn decode_nav_wire(wire: &[u8]) -> Option<Vec<(u32, String, String, String)>> {
    let mut cur = WireCursor::new(wire);
    if cur.u32()? != NAV_MAGIC {
        return None;
    }
    let count = cur.u32()? as usize;
    let mut rows = Vec::with_capacity(count.min(4096));
    for _ in 0..count {
        rows.push((cur.u32()?, cur.string()?, cur.string()?, cur.string()?));
    }
    cur.finish()?;
    Some(rows)
}

fn decode_ncx_wire(wire: &[u8]) -> Option<Vec<(u32, String, String)>> {
    let mut cur = WireCursor::new(wire);
    if cur.u32()? != NCX_MAGIC {
        return None;
    }
    let count = cur.u32()? as usize;
    let mut rows = Vec::with_capacity(count.min(4096));
    for _ in 0..count {
        rows.push((cur.u32()?, cur.string()?, cur.string()?));
    }
    cur.finish()?;
    Some(rows)
}

fn push_toc_entry(
    by_path: &mut HashMap<String, Vec<TocEntry>>,
    entries: &mut Vec<TocEntry>,
    target: String,
    title: String,
    depth: i32,
    fragment: Option<String>,
) {
    let entry = TocEntry {
        title,
        depth,
        path: target.clone(),
        fragment,
    };
    by_path.entry(target).or_default().push(entry.clone());
    entries.push(entry);
}

fn parse_toc_index(
    archive: &mut ZipArchive<BufReader<File>>,
    dir: &ArchiveDirectory,
    opf: &ParsedOpf,
    opf_dir: &str,
    limits: &EpubLimits,
    bytes_read: &mut u64,
) -> Result<TocIndex, ArchiveError> {
    let mut by_path: HashMap<String, Vec<TocEntry>> = HashMap::new();
    let mut entries: Vec<TocEntry> = Vec::new();

    if let Some(nav_item) = opf.manifest.iter().find(|i| i.has_property("nav")) {
        let nav_path = resolve_path(opf_dir, &nav_item.href);
        let nav_dir = parent_dir(&nav_path).to_owned();
        if let Some(zip_entry) = dir.lookup(&nav_path) {
            if let Some(bytes) = read_entry_checked(
                archive,
                zip_entry,
                limits.max_package_xml_bytes,
                "EPUB navigation document",
                true,
                limits,
                bytes_read,
            )? {
                if let Some(rows) = epub_nav::nav_bytes(&bytes).and_then(|w| decode_nav_wire(&w)) {
                    for (depth, source, title, parent_title) in rows {
                        let path_part = source.split('#').next().unwrap_or("");
                        let target = resolve_path(&nav_dir, path_part);
                        if title.is_empty() || target.is_empty() {
                            continue;
                        }
                        let fragment = source
                            .split_once('#')
                            .map(|(_, f)| f)
                            .filter(|f| !f.is_empty())
                            .map(|f| decode_url_path(f).unwrap_or_else(|| f.to_owned()));
                        let final_title = if parent_title.is_empty() {
                            title
                        } else {
                            compose_nested_chapter_title(&parent_title, &title)
                        };
                        push_toc_entry(
                            &mut by_path,
                            &mut entries,
                            target,
                            final_title,
                            depth as i32,
                            fragment,
                        );
                    }
                }
            }
        }
    }

    if entries.is_empty() {
        let declared_ncx = (!opf.spine_toc.is_empty())
            .then(|| opf.manifest.iter().find(|i| i.id == opf.spine_toc))
            .flatten()
            .filter(|i| i.media_type == "application/x-dtbncx+xml");
        let ncx_item = declared_ncx.or_else(|| {
            opf.manifest
                .iter()
                .find(|i| i.media_type == "application/x-dtbncx+xml")
        });
        if let Some(ncx_item) = ncx_item {
            let ncx_path = resolve_path(opf_dir, &ncx_item.href);
            let ncx_dir = parent_dir(&ncx_path).to_owned();
            if let Some(zip_entry) = dir.lookup(&ncx_path) {
                if let Some(bytes) = read_entry_checked(
                    archive,
                    zip_entry,
                    limits.max_package_xml_bytes,
                    "EPUB NCX navigation",
                    true,
                    limits,
                    bytes_read,
                )? {
                    if let Some(rows) =
                        epub_ncx::ncx_bytes(&bytes).and_then(|w| decode_ncx_wire(&w))
                    {
                        for (depth, source, title) in rows {
                            let path_part = source.split('#').next().unwrap_or("");
                            if title.is_empty() || path_part.is_empty() {
                                continue;
                            }
                            let target = resolve_path(&ncx_dir, path_part);
                            let fragment = source
                                .split_once('#')
                                .map(|(_, f)| f)
                                .filter(|f| !f.is_empty())
                                .map(|f| decode_url_path(f).unwrap_or_else(|| f.to_owned()));
                            push_toc_entry(
                                &mut by_path,
                                &mut entries,
                                target,
                                title,
                                depth as i32,
                                fragment,
                            );
                        }
                    }
                }
            }
        }
    }

    Ok(TocIndex { by_path, entries })
}

// --------------------------------------------------------------------------- SHA-1 & Font Obfuscation

pub(crate) fn sha1(data: &[u8]) -> [u8; 20] {
    let mut h: [u32; 5] = [
        0x6745_2301,
        0xEFCD_AB89,
        0x98BA_DCFE,
        0x1032_5476,
        0xC3D2_E1F0,
    ];
    let bit_len = (data.len() as u64).wrapping_mul(8);
    let mut padded_len = data.len() + 9;
    let rem = padded_len % 64;
    if rem != 0 {
        padded_len += 64 - rem;
    }
    let mut buf = Vec::with_capacity(padded_len);
    buf.extend_from_slice(data);
    buf.push(0x80);
    buf.resize(padded_len - 8, 0);
    buf.extend_from_slice(&bit_len.to_be_bytes());

    let mut w = [0_u32; 80];
    for chunk in buf.chunks_exact(64) {
        for (i, word) in chunk.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes([word[0], word[1], word[2], word[3]]);
        }
        for i in 16..80 {
            w[i] = (w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16]).rotate_left(1);
        }
        let [mut a, mut b, mut c, mut d, mut e] = h;
        for (i, &wi) in w.iter().enumerate() {
            let (f, k) = match i {
                0..=19 => ((b & c) | ((!b) & d), 0x5A82_7999_u32),
                20..=39 => (b ^ c ^ d, 0x6ED9_EBA1_u32),
                40..=59 => ((b & c) | (b & d) | (c & d), 0x8F1B_BCDC_u32),
                _ => (b ^ c ^ d, 0xCA62_C1D6_u32),
            };
            let temp = a
                .rotate_left(5)
                .wrapping_add(f)
                .wrapping_add(e)
                .wrapping_add(k)
                .wrapping_add(wi);
            e = d;
            d = c;
            c = b.rotate_left(30);
            b = a;
            a = temp;
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
    }

    let mut out = [0_u8; 20];
    for (i, word) in h.iter().enumerate() {
        out[i * 4..(i + 1) * 4].copy_from_slice(&word.to_be_bytes());
    }
    out
}

pub(crate) fn idpf_key(unique_identifier: &str) -> [u8; 20] {
    let cleaned: String = unique_identifier
        .chars()
        .filter(|&c| !matches!(c, ' ' | '\t' | '\r' | '\n'))
        .collect();
    sha1(cleaned.as_bytes())
}

pub(crate) fn adobe_key(identifier: &str) -> Option<[u8; 16]> {
    let trimmed = identifier.trim();
    let lower = trimmed.to_lowercase();
    let id = if lower.starts_with("urn:uuid:") {
        &trimmed[9..]
    } else if lower.starts_with("uuid:") {
        &trimmed[5..]
    } else {
        trimmed
    };
    let hex: String = id.chars().filter(|&c| c != '-').collect();
    if hex.len() != 32 {
        return None;
    }
    let bytes = hex.as_bytes();
    let mut out = [0_u8; 16];
    for i in 0..16 {
        let hi = hex_nibble(bytes[2 * i])?;
        let lo = hex_nibble(bytes[2 * i + 1])?;
        out[i] = (hi << 4) | lo;
    }
    Some(out)
}

fn deobfuscate_copy(bytes: &[u8], key: &[u8], count: usize) -> Vec<u8> {
    let mut out = bytes.to_vec();
    if key.is_empty() {
        return out;
    }
    let limit = count.min(out.len());
    for (i, byte) in out[..limit].iter_mut().enumerate() {
        *byte ^= key[i % key.len()];
    }
    out
}

pub(crate) fn looks_like_font(bytes: &[u8]) -> bool {
    if bytes.len() < 4 {
        return false;
    }
    matches!(
        &bytes[..4],
        [0x00, 0x01, 0x00, 0x00] | b"OTTO" | b"true" | b"ttcf"
    )
}

struct FontKeys {
    idpf: Option<[u8; 20]>,
    adobe: Vec<[u8; 16]>,
}

fn font_keys_from(opf: &ParsedOpf) -> FontKeys {
    let identifiers: Vec<&OpfMetadata> = opf.dc_fields("identifier").collect();
    let unique = identifiers
        .iter()
        .copied()
        .find(|m| !opf.unique_identifier.is_empty() && m.id == opf.unique_identifier)
        .or_else(|| identifiers.first().copied());
    let idpf = unique
        .map(|m| m.text.trim())
        .filter(|s| !s.is_empty())
        .map(idpf_key);
    let mut adobe = Vec::new();
    for m in &identifiers {
        if let Some(key) = adobe_key(&m.text) {
            if !adobe.contains(&key) {
                adobe.push(key);
            }
        }
    }
    FontKeys { idpf, adobe }
}

#[derive(PartialEq, Eq, Hash)]
struct FontDedupKey {
    entry_path: String,
    family: String,
    weight_min: i32,
    weight_max: i32,
    italic: bool,
    stretch_min_bits: u32,
    stretch_max_bits: u32,
    unicode_range: String,
}

impl FontDedupKey {
    fn from_face(face: &FontFaceSpec) -> (Self, String, String) {
        let entry_path = resolve_path(&face.base_dir, &face.src);
        let family = face.family.trim().to_lowercase();
        let key = Self {
            entry_path: entry_path.clone(),
            family: family.clone(),
            weight_min: face.weight_min,
            weight_max: face.weight_max,
            italic: face.italic,
            stretch_min_bits: face.stretch_min.to_bits(),
            stretch_max_bits: face.stretch_max.to_bits(),
            unicode_range: face.unicode_range.clone().unwrap_or_default(),
        };
        (key, entry_path, family)
    }
}

#[allow(clippy::too_many_arguments)]
fn extract_fonts(
    faces: &[FontFaceSpec],
    archive: &mut ZipArchive<BufReader<File>>,
    dir: &ArchiveDirectory,
    images_dir: &Path,
    encryption: &HashMap<String, String>,
    keys: &FontKeys,
    limits: &EpubLimits,
    bytes_read: &mut u64,
    seen: &mut HashSet<FontDedupKey>,
    out: &mut Vec<FrbBookFont>,
) -> Result<(), ArchiveError> {
    for face in faces {
        let (key, entry_path, family) = FontDedupKey::from_face(face);
        if !seen.insert(key) {
            continue;
        }
        let target = images_dir.join(font_file_name(&entry_path));
        if let Ok(meta) = fs::metadata(&target) {
            if meta.is_file() && meta.len() >= 1 && meta.len() <= limits.max_font_bytes {
                out.push(FrbBookFont {
                    family,
                    path: target.to_string_lossy().into_owned(),
                    bold: face.bold,
                    italic: face.italic,
                    weight: face.weight,
                    weight_min: face.weight_min,
                    weight_max: face.weight_max,
                    stretch_min: face.stretch_min,
                    stretch_max: face.stretch_max,
                    unicode_range: face.unicode_range.clone(),
                });
                continue;
            }
        }
        let Some(zip_entry) = dir.lookup(&entry_path) else {
            continue;
        };
        let Some(mut bytes) = read_entry_checked(
            archive,
            zip_entry,
            limits.max_font_bytes,
            &format!("EPUB font {entry_path}"),
            true,
            limits,
            bytes_read,
        )?
        else {
            continue;
        };
        match encryption.get(&entry_path).map(String::as_str) {
            None => {}
            Some(IDPF_ALGORITHM) => {
                let Some(idpf) = keys.idpf.as_ref() else {
                    continue;
                };
                bytes = deobfuscate_copy(&bytes, idpf, IDPF_PREFIX);
            }
            Some(ADOBE_ALGORITHM) => {
                let Some(decoded) = keys.adobe.iter().find_map(|key| {
                    let candidate = deobfuscate_copy(&bytes, key, ADOBE_PREFIX);
                    (looks_like_font(&candidate)
                        || woff::is_woff(&candidate)
                        || woff::is_woff2(&candidate))
                    .then_some(candidate)
                }) else {
                    continue;
                };
                bytes = decoded;
            }
            Some(_) => continue,
        }
        if woff::is_woff2(&bytes) {
            let max_decoded = usize::try_from(limits.max_font_bytes).unwrap_or(usize::MAX);
            let Some(decoded) = woff::decode_woff2(&bytes, max_decoded) else {
                continue;
            };
            bytes = decoded;
        }
        if woff::is_woff(&bytes) {
            let Some(decoded) = woff::decode_woff(&bytes, limits.max_font_bytes as usize) else {
                continue;
            };
            bytes = decoded;
        }
        if (bytes.len() as u64) > limits.max_font_bytes || !looks_like_font(&bytes) {
            continue;
        }
        if write_atomic_file(&target, |file| file.write_all(&bytes).map_err(|_| true)).is_err() {
            return Err(ArchiveError::Io(format!(
                "Could not write EPUB font {entry_path}"
            )));
        }
        out.push(FrbBookFont {
            family,
            path: target.to_string_lossy().into_owned(),
            bold: face.bold,
            italic: face.italic,
            weight: face.weight,
            weight_min: face.weight_min,
            weight_max: face.weight_max,
            stretch_min: face.stretch_min,
            stretch_max: face.stretch_max,
            unicode_range: face.unicode_range.clone(),
        });
    }
    Ok(())
}

// --------------------------------------------------------------------------- CSS Imports & Stylesheet Discovery

/// Exact match for `EpubParser.screenMediaApplies`.
pub(crate) fn screen_media_applies(media: &str) -> bool {
    media.split(',').any(|branch| {
        let mut query = branch.trim().to_lowercase();
        if query.is_empty() {
            return true;
        }
        let mut negated = false;
        if let Some(rest) = query.strip_prefix("only ") {
            query = rest.trim_start().to_owned();
        }
        if let Some(rest) = query.strip_prefix("not ") {
            negated = true;
            query = rest.trim_start().to_owned();
        }
        let head = split_before_and_clause(&query).trim();
        let media_type = if head.starts_with('(') {
            ""
        } else {
            head.split(' ').next().unwrap_or("")
        };
        let applies = matches!(media_type, "" | "all" | "screen");
        if negated {
            !applies
        } else {
            applies
        }
    })
}

fn split_before_and_clause(query: &str) -> &str {
    let bytes = query.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_whitespace() {
            let ws_start = i;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if bytes
                .get(i..i + 3)
                .is_some_and(|s| s.eq_ignore_ascii_case(b"and"))
            {
                let after = bytes.get(i + 3).copied();
                let boundary = match after {
                    None => true,
                    Some(b) => !(b.is_ascii_alphanumeric() || b == b'_'),
                };
                if boundary {
                    return &query[..ws_start];
                }
            }
        } else {
            i += 1;
        }
    }
    query
}

/// Exact match for `EpubParser.topLevelCssImports`.
pub(crate) fn top_level_css_imports(css: &str) -> Vec<(String, String)> {
    let mut result = Vec::new();
    let mut cursor = 0;
    while cursor < css.len() {
        while let Some(ch) = css[cursor..].chars().next() {
            if ch.is_whitespace() || ch == '\u{FEFF}' {
                cursor += ch.len_utf8();
            } else {
                break;
            }
        }
        if cursor >= css.len() {
            break;
        }
        let rest = &css[cursor..];
        if let Some(after_open) = rest.strip_prefix("/*") {
            let Some(end) = after_open.find("*/") else {
                break;
            };
            cursor += 2 + end + 2;
            continue;
        }
        if starts_with_ascii_ignore_case(rest, "@charset")
            || starts_with_ascii_ignore_case(rest, "@layer")
        {
            let Some(semi) = rest.find(';') else {
                break;
            };
            if rest[..semi].contains('{') {
                break;
            }
            cursor += semi + 1;
            continue;
        }
        if !starts_with_ascii_ignore_case(rest, "@import") {
            break;
        }
        let Some((href, media, consumed)) = parse_css_import_rule(rest) else {
            break;
        };
        result.push((href, media));
        cursor += consumed;
    }
    result
}

fn starts_with_ascii_ignore_case(s: &str, prefix: &str) -> bool {
    s.as_bytes()
        .get(..prefix.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(prefix.as_bytes()))
}

fn parse_css_import_rule(rest: &str) -> Option<(String, String, usize)> {
    // Matches: `@import\s+(?:url\(\s*['"]?([^'")\s]+)['"]?\s*\)|['"]([^'"]+)['"])\s*([^;]*);`
    if !starts_with_ascii_ignore_case(rest, "@import") {
        return None;
    }
    let mut pos = 7;
    let ws_start = pos;
    while let Some(ch) = rest[pos..].chars().next() {
        if ch.is_whitespace() {
            pos += ch.len_utf8();
        } else {
            break;
        }
    }
    if pos == ws_start {
        return None;
    }

    let href: &str;
    if starts_with_ascii_ignore_case(&rest[pos..], "url(") {
        pos += 4;
        while let Some(ch) = rest[pos..].chars().next() {
            if ch.is_whitespace() {
                pos += ch.len_utf8();
            } else {
                break;
            }
        }
        if matches!(rest.as_bytes().get(pos), Some(b'\'' | b'"')) {
            pos += 1;
        }
        let href_start = pos;
        while let Some(ch) = rest[pos..].chars().next() {
            if matches!(ch, '\'' | '"' | ')') || ch.is_whitespace() {
                break;
            }
            pos += ch.len_utf8();
        }
        if pos == href_start {
            return None;
        }
        href = &rest[href_start..pos];
        if matches!(rest.as_bytes().get(pos), Some(b'\'' | b'"')) {
            pos += 1;
        }
        while let Some(ch) = rest[pos..].chars().next() {
            if ch.is_whitespace() {
                pos += ch.len_utf8();
            } else {
                break;
            }
        }
        if rest.as_bytes().get(pos) != Some(&b')') {
            return None;
        }
        pos += 1;
    } else if matches!(rest.as_bytes().get(pos), Some(b'\'' | b'"')) {
        pos += 1;
        let href_start = pos;
        while let Some(ch) = rest[pos..].chars().next() {
            if matches!(ch, '\'' | '"') {
                break;
            }
            pos += ch.len_utf8();
        }
        if pos == href_start {
            return None;
        }
        href = &rest[href_start..pos];
        if !matches!(rest.as_bytes().get(pos), Some(b'\'' | b'"')) {
            return None;
        }
        pos += 1;
    } else {
        return None;
    }

    if href.trim().is_empty() {
        return None;
    }
    while let Some(ch) = rest[pos..].chars().next() {
        if ch.is_whitespace() {
            pos += ch.len_utf8();
        } else {
            break;
        }
    }
    let semi_rel = rest[pos..].find(';')?;
    let media = rest[pos..pos + semi_rel].trim().to_owned();
    let consumed = pos + semi_rel + 1;
    Some((href.to_owned(), media, consumed))
}

fn css_import_paths(css: &str, base_dir: &str) -> Vec<String> {
    top_level_css_imports(css)
        .into_iter()
        .filter_map(|(href, media)| {
            if !screen_media_applies(&media) {
                return None;
            }
            if starts_with_ascii_ignore_case(&href, "data:")
                || starts_with_ascii_ignore_case(&href, "http:")
                || starts_with_ascii_ignore_case(&href, "https:")
            {
                return None;
            }
            Some(resolve_path(base_dir, &href))
        })
        .collect()
}

/// Collects `<link[href]>` and `<style>` node indices in document pre-order DFS.
fn collect_stylesheet_nodes(doc: &Document) -> Vec<usize> {
    let mut out = Vec::new();
    let Some(root) = doc.nodes.first() else {
        return out;
    };
    let mut stack = Vec::new();
    if let Some(child) = root.first_child {
        stack.push(child);
    }
    while let Some(idx) = stack.pop() {
        let node = &doc.nodes[idx];
        if let Some(meta) = doc.preindex.nodes.get(idx) {
            if meta.is_stylesheet {
                out.push(idx);
            }
        }
        if let Some(sib) = node.next_sibling {
            stack.push(sib);
        }
        if let Some(child) = node.first_child {
            stack.push(child);
        }
    }
    out
}

fn attr_ignore_ascii_case<'a>(attrs: &'a [(String, String)], name: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(name))
        .map(|(_, v)| v.as_str())
}

/// Exact match for Jsoup `node.data().ifEmpty { node.text() }` on a `<style>` element.
fn style_element_text(doc: &Document, style_idx: usize) -> String {
    let mut data_out = String::new();
    let mut text_out = String::new();
    let mut stack = Vec::new();
    if let Some(child) = doc.nodes[style_idx].first_child {
        stack.push(child);
    }
    while let Some(idx) = stack.pop() {
        let node = &doc.nodes[idx];
        match &node.kind {
            Kind::Data(s) | Kind::Comment(s) => data_out.push_str(s),
            Kind::Cdata(s) => {
                data_out.push_str(s);
                append_normalized_jsoup_text(&mut text_out, s);
            }
            Kind::Text(s) => {
                append_normalized_jsoup_text(&mut text_out, s);
            }
            _ => {}
        }
        if let Some(sib) = node.next_sibling {
            stack.push(sib);
        }
        if let Some(child) = node.first_child {
            stack.push(child);
        }
    }
    if !data_out.is_empty() {
        data_out
    } else {
        text_out.trim().to_owned()
    }
}

fn append_normalized_jsoup_text(out: &mut String, input: &str) {
    let mut last_ws = out.ends_with(' ');
    for ch in input.chars() {
        if matches!(ch, ' ' | '\t' | '\n' | '\r' | '\u{000C}') {
            if !last_ws {
                out.push(' ');
                last_ws = true;
            }
        } else {
            out.push(ch);
            last_ws = false;
        }
    }
}

#[derive(Clone)]
struct CssSource {
    key: String,
    text: String,
    base_dir: String,
    imports: Vec<String>,
}

struct EpubCssBudget {
    limits: EpubLimits,
    expanded_bytes: u64,
    expanded_sheets: usize,
    expansion_operations: usize,
    accepted_resolvers: HashSet<String>,
    rejected_resolvers: HashSet<String>,
    was_limited: bool,
}

impl EpubCssBudget {
    fn new(limits: EpubLimits) -> Self {
        Self {
            limits,
            expanded_bytes: 0,
            expanded_sheets: 0,
            expansion_operations: 0,
            accepted_resolvers: HashSet::new(),
            rejected_resolvers: HashSet::new(),
            was_limited: false,
        }
    }

    fn enter_sheet(&mut self) -> bool {
        if self.expanded_sheets >= self.limits.max_epub_css_expanded_sheets
            || self.expansion_operations >= self.limits.max_epub_css_expansion_operations
        {
            self.was_limited = true;
            return false;
        }
        self.expanded_sheets += 1;
        self.expansion_operations += 1;
        true
    }

    fn traverse_import(&mut self) -> bool {
        if self.expansion_operations >= self.limits.max_epub_css_expansion_operations {
            self.was_limited = true;
            return false;
        }
        self.expansion_operations += 1;
        true
    }

    fn accept_resolver(&mut self, signature: &str, sheets: &[SheetInput]) -> bool {
        if self.accepted_resolvers.contains(signature) {
            return true;
        }
        if self.rejected_resolvers.contains(signature) {
            return false;
        }
        let mut bytes = 0_u64;
        for sheet in sheets {
            let next = (sheet.text.encode_utf16().count() as u64).saturating_mul(2);
            if bytes > self.limits.max_epub_css_expanded_bytes.saturating_sub(next) {
                self.was_limited = true;
                self.rejected_resolvers.insert(signature.to_owned());
                return false;
            }
            bytes += next;
        }
        if self.expanded_bytes
            > self
                .limits
                .max_epub_css_expanded_bytes
                .saturating_sub(bytes)
        {
            self.was_limited = true;
            self.rejected_resolvers.insert(signature.to_owned());
            return false;
        }
        self.expanded_bytes += bytes;
        self.accepted_resolvers.insert(signature.to_owned());
        true
    }
}

struct SharedCssState {
    raw_css: HashMap<String, Option<String>>,
    compiled_by_key: HashMap<String, Arc<CompiledStylesheets>>,
    empty_compiled: Arc<CompiledStylesheets>,
    budget: EpubCssBudget,
}

impl SharedCssState {
    fn new(limits: EpubLimits) -> Self {
        Self {
            raw_css: HashMap::new(),
            compiled_by_key: HashMap::new(),
            empty_compiled: CompiledStylesheets::empty(),
            budget: EpubCssBudget::new(limits),
        }
    }

    fn read_css_text(
        &mut self,
        path: &str,
        archive: &mut ZipArchive<BufReader<File>>,
        dir: &ArchiveDirectory,
        limits: &EpubLimits,
        bytes_read: &mut u64,
    ) -> Result<Option<String>, ArchiveError> {
        if let Some(cached) = self.raw_css.get(path) {
            return Ok(cached.clone());
        }
        let text = if let Some(entry) = dir.lookup(path) {
            read_entry_checked(
                archive,
                entry,
                limits.max_stylesheet_bytes,
                &format!("EPUB stylesheet {path}"),
                true,
                limits,
                bytes_read,
            )?
            .map(|b| String::from_utf8_lossy(&b).into_owned())
        } else {
            None
        };
        self.raw_css.insert(path.to_owned(), text.clone());
        Ok(text)
    }

    fn resolve_compiled_for_chapter(
        &mut self,
        doc: &Document,
        chapter_dir: &str,
        archive: &mut ZipArchive<BufReader<File>>,
        dir: &ArchiveDirectory,
        limits: &EpubLimits,
        bytes_read: &mut u64,
    ) -> Result<Arc<CompiledStylesheets>, ArchiveError> {
        if self.budget.was_limited {
            return Err(ArchiveError::Fallback("css expansion budget exceeded"));
        }
        let nodes = collect_stylesheet_nodes(doc);
        let mut roots: Vec<CssSource> = Vec::new();
        let mut source_cache: HashMap<String, CssSource> = HashMap::new();
        let mut missing_sources: HashSet<String> = HashSet::new();
        let mut inline_index = 0_usize;

        for node_idx in nodes {
            let Kind::Element { name, attrs, .. } = &doc.nodes[node_idx].kind else {
                continue;
            };
            let media = attr_ignore_ascii_case(attrs, "media").unwrap_or("");
            if !screen_media_applies(media) {
                continue;
            }
            let normal_name = name.to_ascii_lowercase();
            if normal_name == "link" {
                let rel = attr_ignore_ascii_case(attrs, "rel").unwrap_or("");
                let mime = attr_ignore_ascii_case(attrs, "type").unwrap_or("");
                let has_stylesheet = rel
                    .split_whitespace()
                    .any(|t| t.eq_ignore_ascii_case("stylesheet"));
                if !has_stylesheet && !contains_ascii_ignore_case(mime, "css") {
                    continue;
                }
                let href = attr_ignore_ascii_case(attrs, "href").unwrap_or("");
                let path = resolve_path(chapter_dir, href);
                if let Some(src) = source_cache.get(&path) {
                    roots.push(src.clone());
                } else if !missing_sources.contains(&path) {
                    if let Some(text) =
                        self.read_css_text(&path, archive, dir, limits, bytes_read)?
                    {
                        let base_dir = parent_dir(&path).to_owned();
                        let imports = css_import_paths(&text, &base_dir);
                        let src = CssSource {
                            key: path.clone(),
                            text,
                            base_dir,
                            imports,
                        };
                        source_cache.insert(path, src.clone());
                        roots.push(src);
                    } else {
                        missing_sources.insert(path);
                    }
                }
            } else {
                let idx = inline_index;
                inline_index += 1;
                let text = style_element_text(doc, node_idx);
                if !text.trim().is_empty()
                    && (text.encode_utf16().count() as u64) <= limits.max_stylesheet_bytes
                {
                    let imports = css_import_paths(&text, chapter_dir);
                    roots.push(CssSource {
                        key: format!("inline:{chapter_dir}:{idx}:{}", resource_digest(&text)),
                        text,
                        base_dir: chapter_dir.to_owned(),
                        imports,
                    });
                }
            }
        }

        if roots.is_empty() {
            return Ok(Arc::clone(&self.empty_compiled));
        }

        let mut root_sig = String::new();
        for root in &roots {
            let _ = write!(&mut root_sig, "{}:{}", root.key.len(), root.key);
        }
        let cache_key = resource_digest(&root_sig);
        if let Some(cached) = self.compiled_by_key.get(&cache_key) {
            return Ok(Arc::clone(cached));
        }

        let mut sheets: Vec<SheetInput> = Vec::new();
        for root in roots {
            struct Frame {
                source: CssSource,
                next_import: usize,
            }
            let mut active_path: HashSet<String> = HashSet::new();
            let mut stack: Vec<Frame> = Vec::new();
            if !active_path.contains(&root.key) && self.budget.enter_sheet() {
                active_path.insert(root.key.clone());
                stack.push(Frame {
                    source: root,
                    next_import: 0,
                });
            }
            while let Some(frame) = stack.last_mut() {
                if frame.next_import < frame.source.imports.len() && self.budget.traverse_import() {
                    let path = frame.source.imports[frame.next_import].clone();
                    frame.next_import += 1;
                    let next_src = if let Some(s) = source_cache.get(&path) {
                        Some(s.clone())
                    } else if missing_sources.contains(&path) {
                        None
                    } else if let Some(text) =
                        self.read_css_text(&path, archive, dir, limits, bytes_read)?
                    {
                        let base_dir = parent_dir(&path).to_owned();
                        let imports = css_import_paths(&text, &base_dir);
                        let src = CssSource {
                            key: path.clone(),
                            text,
                            base_dir,
                            imports,
                        };
                        source_cache.insert(path, src.clone());
                        Some(src)
                    } else {
                        missing_sources.insert(path);
                        None
                    };
                    if let Some(src) = next_src {
                        if !active_path.contains(&src.key) && self.budget.enter_sheet() {
                            active_path.insert(src.key.clone());
                            stack.push(Frame {
                                source: src,
                                next_import: 0,
                            });
                        }
                    }
                    continue;
                }
                let finished = stack.pop().unwrap();
                active_path.remove(&finished.source.key);
                sheets.push(SheetInput::new(
                    finished.source.text,
                    finished.source.base_dir,
                ));
            }
        }

        if self.budget.was_limited || !self.budget.accept_resolver(&cache_key, &sheets) {
            return Err(ArchiveError::Fallback("css expansion budget exceeded"));
        }

        let compiled = CompiledStylesheets::compile(&sheets, None);
        self.compiled_by_key
            .insert(cache_key, Arc::clone(&compiled));
        Ok(compiled)
    }
}

// --------------------------------------------------------------------------- Shared Parallel Image Extractor

struct SharedImageExtractor {
    images_dir: PathBuf,
    limits: EpubLimits,
    cache: Mutex<HashMap<String, Option<String>>>,
    in_flight: Mutex<HashMap<String, Arc<Mutex<()>>>>,
}

impl SharedImageExtractor {
    fn new(images_dir: PathBuf, limits: EpubLimits) -> Self {
        Self {
            images_dir,
            limits,
            cache: Mutex::new(HashMap::new()),
            in_flight: Mutex::new(HashMap::new()),
        }
    }

    fn extract(
        &self,
        archive: &mut ZipArchive<BufReader<File>>,
        dir: &ArchiveDirectory,
        entry_path: &str,
        bytes_read: &mut u64,
        required: bool,
    ) -> Result<Option<String>, ArchiveError> {
        {
            let guard = self.cache.lock().unwrap();
            if let Some(cached) = guard.get(entry_path) {
                return Ok(cached.clone());
            }
        }
        let key_lock = {
            let mut guard = self.in_flight.lock().unwrap();
            guard
                .entry(entry_path.to_owned())
                .or_insert_with(|| Arc::new(Mutex::new(())))
                .clone()
        };
        let _entry_guard = key_lock.lock().unwrap();
        {
            let guard = self.cache.lock().unwrap();
            if let Some(cached) = guard.get(entry_path) {
                return Ok(cached.clone());
            }
        }

        let Some(zip_entry) = dir.lookup(entry_path) else {
            return Ok(None);
        };
        let target = self.images_dir.join(image_file_name(entry_path));
        if let Ok(meta) = fs::metadata(&target) {
            if meta.is_file() && meta.len() >= 1 && meta.len() <= self.limits.max_image_bytes {
                let path_str = target.to_string_lossy().into_owned();
                self.cache
                    .lock()
                    .unwrap()
                    .insert(entry_path.to_owned(), Some(path_str.clone()));
                return Ok(Some(path_str));
            }
            if required {
                return Err(ArchiveError::Limit {
                    kind: LimitKind::EntrySize,
                    message: format!(
                        "Cached EPUB spine image exceeds {} bytes",
                        self.limits.max_image_bytes
                    ),
                });
            }
            return Ok(None);
        }

        let label = if required {
            format!("EPUB spine image {entry_path}")
        } else {
            format!("EPUB image {entry_path}")
        };
        let copied = copy_entry_checked(
            archive,
            zip_entry,
            &target,
            self.limits.max_image_bytes,
            &label,
            !required,
            &self.limits,
            bytes_read,
        )?;
        if !copied {
            return Ok(None);
        }
        let path_str = target.to_string_lossy().into_owned();
        self.cache
            .lock()
            .unwrap()
            .insert(entry_path.to_owned(), Some(path_str.clone()));
        Ok(Some(path_str))
    }
}

/// Thread-safe inline SVG writer matching `EpubParser.writeInlineSvg`
/// (`svg_inline_<sha256_prefix_32>_inline.svg`).
pub(crate) struct SharedInlineSvgWriter {
    images_dir: PathBuf,
    cache: Mutex<HashMap<String, String>>,
    in_flight: Mutex<HashMap<String, Arc<Mutex<()>>>>,
}

impl SharedInlineSvgWriter {
    pub(crate) fn new(images_dir: PathBuf) -> Self {
        Self {
            images_dir,
            cache: Mutex::new(HashMap::new()),
            in_flight: Mutex::new(HashMap::new()),
        }
    }

    pub(crate) fn write(&self, markup: &str) -> Option<String> {
        {
            let guard = self.cache.lock().unwrap();
            if let Some(cached) = guard.get(markup) {
                return Some(cached.clone());
            }
        }
        let file_name = resource_cache_file_name("svg_inline", markup, "inline.svg");
        let key_lock = {
            let mut guard = self.in_flight.lock().unwrap();
            guard
                .entry(file_name.clone())
                .or_insert_with(|| Arc::new(Mutex::new(())))
                .clone()
        };
        let _entry_guard = key_lock.lock().unwrap();
        {
            let guard = self.cache.lock().unwrap();
            if let Some(cached) = guard.get(markup) {
                return Some(cached.clone());
            }
        }
        let target = self.images_dir.join(&file_name);
        if write_atomic_file(&target, |file| {
            file.write_all(markup.as_bytes()).map_err(|_| true)
        })
        .is_err()
        {
            return None;
        }
        let path_str = target.to_string_lossy().into_owned();
        self.cache
            .lock()
            .unwrap()
            .insert(markup.to_owned(), path_str.clone());
        Some(path_str)
    }
}

/// Extracts accessible alt text from a standalone SVG document, matching
/// `EpubParser.kt`: `svg[aria-label]` if non-blank, else the first direct `<title>` child's text.
pub(crate) fn parse_svg_alt_text(bytes: &[u8]) -> Option<String> {
    let text = String::from_utf8_lossy(bytes);
    let input = text.strip_prefix('\u{FEFF}').unwrap_or(&text);
    let mut reader = quick_xml::Reader::from_str(input);
    let config = reader.config_mut();
    config.allow_dangling_amp = true;
    config.allow_unmatched_ends = true;
    config.check_end_names = false;

    let mut in_svg = false;
    let mut svg_depth = 0_usize;
    let mut in_title = false;
    let mut title_text = String::new();

    while let Ok(event) = reader.read_event() {
        match event {
            XmlEvent::Start(start) => {
                if !in_svg {
                    let local = start.local_name().into_inner();
                    if local.eq_ignore_ascii_case("svg") {
                        if let Some([aria]) = epub_package::xml_attributes(&start, [b"aria-label"])
                        {
                            let trimmed = aria.trim();
                            if !trimmed.is_empty() {
                                return Some(trimmed.to_owned());
                            }
                        }
                        in_svg = true;
                        svg_depth = 0;
                    }
                } else {
                    if svg_depth == 0 && start.name().into_inner().eq_ignore_ascii_case("title") {
                        in_title = true;
                    }
                    svg_depth += 1;
                }
            }
            XmlEvent::Empty(start) => {
                if !in_svg {
                    let local = start.local_name().into_inner();
                    if local.eq_ignore_ascii_case("svg") {
                        if let Some([aria]) = epub_package::xml_attributes(&start, [b"aria-label"])
                        {
                            let trimmed = aria.trim();
                            if !trimmed.is_empty() {
                                return Some(trimmed.to_owned());
                            }
                        }
                        return None;
                    }
                } else if svg_depth == 0 && start.name().into_inner().eq_ignore_ascii_case("title")
                {
                    return None;
                }
            }
            XmlEvent::End(_) => {
                if in_svg {
                    if svg_depth == 0 {
                        break;
                    }
                    svg_depth -= 1;
                    if in_title && svg_depth == 0 {
                        let trimmed = title_text.trim();
                        return (!trimmed.is_empty()).then(|| trimmed.to_owned());
                    }
                }
            }
            XmlEvent::Text(text) if in_title => {
                append_normalized_jsoup_text(&mut title_text, text.as_ref());
            }
            XmlEvent::CData(cdata) if in_title => {
                append_normalized_jsoup_text(&mut title_text, cdata.as_ref());
            }
            XmlEvent::GeneralRef(reference) if in_title => {
                let resolved = epub_package::xml_reference(&reference);
                append_normalized_jsoup_text(&mut title_text, &resolved);
            }
            XmlEvent::Eof => break,
            _ => {}
        }
    }
    if in_title {
        let trimmed = title_text.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    } else {
        None
    }
}

// --------------------------------------------------------------------------- Notes & Language

/// Exact match for `ParserShared.kt` `buildNotes`.
pub(crate) fn build_notes(
    chapters: &[FrbChapter],
    anchor_locations: &HashMap<String, (i32, i32)>,
    note_targets: &BTreeSet<String>,
    exact_documents: &HashMap<String, FrbNoteDocument>,
) -> BTreeMap<String, FrbNoteDocument> {
    let mut notes = BTreeMap::new();
    for (k, doc) in exact_documents {
        if note_targets.contains(k) && !doc.elements.is_empty() {
            notes.insert(k.clone(), doc.clone());
        }
    }

    let mut anchored_starts: HashMap<i32, Vec<i32>> = HashMap::new();
    for &(ch_idx, el_idx) in anchor_locations.values() {
        anchored_starts.entry(ch_idx).or_default().push(el_idx);
    }
    for starts in anchored_starts.values_mut() {
        starts.sort_unstable();
        starts.dedup();
    }

    for key in note_targets {
        if notes.contains_key(key) {
            continue;
        }
        let Some(&(ch_idx, el_idx)) = anchor_locations.get(key) else {
            continue;
        };
        let Some(chapter) = chapters.get(ch_idx as usize) else {
            continue;
        };
        if el_idx < 0 || (el_idx as usize) >= chapter.elements.len() {
            continue;
        }
        let starts = anchored_starts
            .get(&ch_idx)
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        let next_pos = match starts.binary_search(&el_idx) {
            Ok(pos) => pos + 1,
            Err(pos) => pos,
        };
        let end = starts
            .get(next_pos)
            .copied()
            .unwrap_or(chapter.elements.len() as i32);
        if end > el_idx {
            notes.insert(
                key.clone(),
                FrbNoteDocument {
                    elements: chapter.elements[el_idx as usize..end as usize].to_vec(),
                    publisher_boxes: slice_publisher_box_spans(
                        &chapter.publisher_boxes,
                        el_idx,
                        end,
                        0,
                    ),
                },
            );
        }
    }
    notes
}

pub(crate) fn normalize_language_tag(raw: Option<&str>) -> Option<String> {
    let tag = raw?.trim().to_lowercase().replace('_', "-");
    let mut parts = tag.split('-');
    let first = parts.next()?;
    if !(2..=3).contains(&first.len()) || !first.bytes().all(|b| b.is_ascii_lowercase()) {
        return None;
    }
    for sub in parts {
        if !(2..=8).contains(&sub.len())
            || !sub
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        {
            return None;
        }
    }
    if tag == "ua" {
        Some("uk".to_owned())
    } else {
        Some(tag)
    }
}

#[derive(Default)]
struct LanguageSampler {
    sample_u16_len: usize,
    letters: usize,
    cyrillic: usize,
    arabic: usize,
    hebrew: usize,
    ukrainian: usize,
}

impl LanguageSampler {
    fn observe_chapter(&mut self, chapter: &FrbChapter) {
        if self.sample_u16_len >= 2000 {
            return;
        }
        for element in &chapter.elements {
            let text = match element {
                FrbContentElement::Paragraph { text, .. } => text.text.as_str(),
                FrbContentElement::Heading { styled_text, .. } => styled_text.text.as_str(),
                _ => continue,
            };
            for ch in text.chars() {
                self.sample_u16_len += ch.len_utf16();
                if (ch as u32) <= 0xFFFF && ch.is_alphabetic() {
                    self.letters += 1;
                    if ('\u{0400}'..='\u{04FF}').contains(&ch) {
                        self.cyrillic += 1;
                    }
                    if ('\u{0600}'..='\u{06FF}').contains(&ch)
                        || ('\u{0750}'..='\u{077F}').contains(&ch)
                        || ('\u{08A0}'..='\u{08FF}').contains(&ch)
                        || ('\u{FB50}'..='\u{FDFF}').contains(&ch)
                        || ('\u{FE70}'..='\u{FEFF}').contains(&ch)
                    {
                        self.arabic += 1;
                    }
                    if ('\u{0590}'..='\u{05FF}').contains(&ch)
                        || ('\u{FB1D}'..='\u{FB4F}').contains(&ch)
                    {
                        self.hebrew += 1;
                    }
                    if matches!(ch, 'і' | 'ї' | 'є' | 'ґ' | 'І' | 'Ї' | 'Є' | 'Ґ') {
                        self.ukrainian += 1;
                    }
                }
            }
            self.sample_u16_len += 1;
            if self.sample_u16_len >= 2000 {
                break;
            }
        }
    }

    fn finish(&self) -> Option<String> {
        if self.letters < 40 {
            return None;
        }
        let threshold = self.letters * 6;
        if self.arabic * 10 >= threshold
            && self.arabic >= self.hebrew
            && self.arabic >= self.cyrillic
        {
            Some("ar".to_owned())
        } else if self.hebrew * 10 >= threshold
            && self.hebrew >= self.arabic
            && self.hebrew >= self.cyrillic
        {
            Some("he".to_owned())
        } else if self.cyrillic * 10 >= threshold {
            Some(if self.ukrainian >= 3 { "uk" } else { "ru" }.to_owned())
        } else {
            None
        }
    }
}

pub(crate) fn detect_language_from_chapters(chapters: &[FrbChapter]) -> Option<String> {
    let mut sampler = LanguageSampler::default();
    for chapter in chapters {
        sampler.observe_chapter(chapter);
        if sampler.sample_u16_len >= 2000 {
            break;
        }
    }
    sampler.finish()
}

// --------------------------------------------------------------------------- Streaming Resource & PublisherStyle Summary

fn collect_text_resources_into(text: &FrbAnnotatedString, paths: &mut HashSet<String>) {
    for range in &text.ranges {
        if range.tag == INLINE_IMAGE_TAG {
            if let FrbTextAnnotation::String(path) = &range.annotation {
                paths.insert(path.clone());
            }
        }
    }
}

fn collect_element_resources_into(elements: &[FrbContentElement], paths: &mut HashSet<String>) {
    for element in elements {
        match element {
            FrbContentElement::Image(img) => {
                paths.insert(img.path.clone());
            }
            FrbContentElement::Paragraph { text, block, .. } => {
                collect_text_resources_into(text, paths);
                if let Some(fi) = block.as_ref().and_then(|b| b.float_image.as_ref()) {
                    paths.insert(fi.path.clone());
                }
            }
            FrbContentElement::Heading {
                styled_text, block, ..
            } => {
                collect_text_resources_into(styled_text, paths);
                if let Some(fi) = block.as_ref().and_then(|b| b.float_image.as_ref()) {
                    paths.insert(fi.path.clone());
                }
            }
            FrbContentElement::Table(tbl) => {
                if let Some(fi) = tbl.block.as_ref().and_then(|b| b.float_image.as_ref()) {
                    paths.insert(fi.path.clone());
                }
                for row in &tbl.rows {
                    for cell in &row.cells {
                        collect_text_resources_into(&cell.text, paths);
                        if let Some(fi) = cell.block.as_ref().and_then(|b| b.float_image.as_ref()) {
                            paths.insert(fi.path.clone());
                        }
                        if let Some(fi) = cell
                            .narrow_style
                            .as_ref()
                            .and_then(|ns| ns.block.as_ref())
                            .and_then(|b| b.float_image.as_ref())
                        {
                            paths.insert(fi.path.clone());
                        }
                    }
                }
            }
            FrbContentElement::Divider | FrbContentElement::Spacer { .. } => {}
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct F32VoteKey(u32);

impl F32VoteKey {
    #[inline]
    fn from_f32(value: f32) -> Self {
        Self(if value.is_nan() {
            0x7FC0_0000
        } else {
            value.to_bits()
        })
    }

    #[inline]
    fn to_f32(self) -> f32 {
        f32::from_bits(self.0)
    }
}

struct VoteMap<K: Eq + Hash> {
    entries: Vec<(K, usize)>,
    index: HashMap<K, usize>,
}

impl<K: Eq + Hash + Clone> VoteMap<K> {
    fn new() -> Self {
        Self {
            entries: Vec::new(),
            index: HashMap::new(),
        }
    }

    fn increment(&mut self, key: K) {
        if let Some(&idx) = self.index.get(&key) {
            self.entries[idx].1 += 1;
        } else {
            let idx = self.entries.len();
            self.index.insert(key.clone(), idx);
            self.entries.push((key, 1));
        }
    }

    fn winner(&self) -> Option<&K> {
        let mut best: Option<(&K, usize)> = None;
        for (k, count) in &self.entries {
            if best.is_none_or(|(_, best_count)| *count > best_count) {
                best = Some((k, *count));
            }
        }
        best.map(|(k, _)| k)
    }
}

fn capitalize_first_char(s: &str) -> String {
    let mut chars = s.chars();
    let Some(first) = chars.next() else {
        return String::new();
    };
    let mut out = String::with_capacity(s.len());
    for upper in first.to_uppercase() {
        out.push(upper);
    }
    out.push_str(chars.as_str());
    out
}

fn representative_book_face(faces: &[FrbBookFont]) -> Option<&FrbBookFont> {
    faces.iter().min_by_key(|face| {
        let clamped = if face.weight_min <= face.weight_max {
            400.clamp(face.weight_min, face.weight_max)
        } else {
            face.weight_min
        };
        (face.italic, (400 - clamped).abs())
    })
}

/// Incremental `publisherStyleOf` accumulator matching `frb_model::compute_publisher_style` bit-for-bit.
struct StreamingPublisherStyle {
    justify_votes: VoteMap<bool>,
    line_height_votes: VoteMap<F32VoteKey>,
    hyphen_votes: VoteMap<bool>,
    font_votes: VoteMap<String>,
    typography_votes: VoteMap<FrbFontTypography>,
    drop_caps: bool,
}

impl StreamingPublisherStyle {
    fn new() -> Self {
        Self {
            justify_votes: VoteMap::new(),
            line_height_votes: VoteMap::new(),
            hyphen_votes: VoteMap::new(),
            font_votes: VoteMap::new(),
            typography_votes: VoteMap::new(),
            drop_caps: false,
        }
    }

    fn observe_chapter(&mut self, chapter: &FrbChapter) {
        for element in &chapter.elements {
            let FrbContentElement::Paragraph { style, block, .. } = element else {
                continue;
            };
            if *style != FrbParagraphStyle::Normal {
                continue;
            }
            let Some(block) = block else {
                continue;
            };
            if let Some(typo) = &block.font_typography {
                if !typo.is_empty() {
                    self.typography_votes.increment(typo.as_ref().clone());
                }
            }
            match block.align {
                Some(FrbBlockAlign::Justify) => self.justify_votes.increment(true),
                Some(FrbBlockAlign::Start) => self.justify_votes.increment(false),
                _ => {}
            }
            if let Some(lh) = block.line_height_mult {
                self.line_height_votes.increment(F32VoteKey::from_f32(lh));
            }
            if let Some(h) = block.hyphens {
                self.hyphen_votes.increment(h);
            }
            if let Some(f) = &block.font_family {
                self.font_votes.increment(f.clone());
            }
            if block.first_letter.as_ref().is_some_and(|fl| fl.is_drop_cap) {
                self.drop_caps = true;
            }
        }
    }

    fn finish(&self, fonts: &[FrbBookFont]) -> Option<FrbPublisherStyle> {
        let mut families: BTreeMap<&str, Vec<FrbBookFont>> = BTreeMap::new();
        for font in fonts {
            families
                .entry(font.family.as_str())
                .or_default()
                .push(font.clone());
        }

        let font = self.font_votes.winner();
        let embedded = font
            .and_then(|f| families.get(f.as_str()))
            .and_then(|faces| representative_book_face(faces));
        let generic_name = match font.map(String::as_str) {
            Some("serif") => Some("Serif"),
            Some("sans-serif") => Some("Sans"),
            Some("monospace") => Some("Monospace"),
            Some("cursive") => Some("Cursive"),
            Some("literata") => Some("Literata"),
            Some("ibm plex sans" | "ibm plex sans var") => Some("IBM Plex Sans"),
            _ => None,
        };

        let font_name = if embedded.is_some() {
            font.map(|f| capitalize_first_char(f))
        } else {
            generic_name.map(str::to_owned)
        };
        let font_path = embedded.map(|e| e.path.clone());
        let font_css = if embedded.is_some() || generic_name.is_some() {
            font.cloned()
        } else {
            None
        };

        let mut family_entries: Vec<(&str, &Vec<FrbBookFont>)> =
            families.iter().map(|(&k, v)| (k, v)).collect();
        family_entries.sort_by(|(a, _), (b, _)| a.encode_utf16().cmp(b.encode_utf16()));

        let mut embedded_fonts = Vec::new();
        for (family, faces) in family_entries {
            let mut seen_paths = HashSet::new();
            let display_name = capitalize_first_char(family);
            for face in faces {
                if seen_paths.insert(face.path.as_str()) {
                    embedded_fonts.push(FrbEmbeddedFont {
                        name: display_name.clone(),
                        path: face.path.clone(),
                    });
                }
            }
        }

        let font_faces = font
            .and_then(|f| families.get(f.as_str()))
            .cloned()
            .unwrap_or_default();

        let style = FrbPublisherStyle {
            justify: self.justify_votes.winner().copied(),
            line_height: self.line_height_votes.winner().map(|k| k.to_f32()),
            hyphenation: self.hyphen_votes.winner().copied(),
            drop_caps: self.drop_caps,
            font_name,
            font_path,
            font_css,
            embedded_fonts,
            font_faces,
            font_typography: self.typography_votes.winner().cloned(),
        };
        if style.is_empty() {
            None
        } else {
            Some(style)
        }
    }
}

// --------------------------------------------------------------------------- Chapter Spool File (RAII)

struct SpoolEntry {
    spool_offset: u64,
    compressed_len: usize,
    expanded_len: u64,
    crc: u32,
    element_count: i32,
}

struct SpoolFile {
    path: PathBuf,
    file: Option<File>,
    offset: u64,
}

impl SpoolFile {
    fn create_near(dest_frb_path: &Path) -> Result<Self, String> {
        let parent = dest_frb_path.parent().unwrap_or_else(|| Path::new("."));
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let seq = TEMP_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
        let pid = std::process::id();
        let path = parent.join(format!(".epub-frb-spool-{pid}-{seq}.tmp"));
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|e| e.to_string())?;
        Ok(Self {
            path,
            file: Some(file),
            offset: 0,
        })
    }

    fn append(&mut self, bytes: &[u8]) -> Result<u64, String> {
        let next = self
            .offset
            .checked_add(bytes.len() as u64)
            .ok_or_else(|| "compressed file limit".to_owned())?;
        if next > frb::MAX_FILE_BYTES {
            return Err("compressed file limit".to_owned());
        }
        let start = self.offset;
        self.file
            .as_mut()
            .expect("spool file open")
            .write_all(bytes)
            .map_err(|e| e.to_string())?;
        self.offset = next;
        Ok(start)
    }

    fn file_mut(&mut self) -> &mut File {
        self.file.as_mut().expect("spool file open")
    }
}

impl Drop for SpoolFile {
    fn drop(&mut self) {
        self.file.take();
        let _ = fs::remove_file(&self.path);
    }
}

// --------------------------------------------------------------------------- Mapped Spine Item & Merging

struct MappedSpineOutput {
    spine_ordinal: usize,
    linear: bool,
    chapter_path: String,
    has_body: bool,
    is_standalone_svg: bool,
    font_faces: Vec<FontFaceSpec>,
    elements: Vec<FrbContentElement>,
    publisher_boxes: Vec<FrbPublisherBoxSpan>,
    anchors: BTreeMap<String, i32>,
    link_targets: BTreeSet<String>,
    note_targets: BTreeSet<String>,
    note_documents: BTreeMap<String, FrbNoteDocument>,
    generated_chars_used: usize,
    was_aggregate_limited: bool,
    bytes_read: u64,
}

enum SpineTaskOutcome {
    Skipped { bytes_read: u64 },
    Mapped(MappedSpineOutput),
    Fallback(&'static str),
    Limit { kind: LimitKind, message: String },
    Error(String),
}

fn compute_toc_boundaries<'a>(
    chapter_path: &str,
    elements_len: usize,
    anchors: &BTreeMap<String, i32>,
    titles_by_path: &'a HashMap<String, Vec<TocEntry>>,
) -> Vec<(usize, Option<&'a TocEntry>)> {
    let toc_entries = titles_by_path
        .get(chapter_path)
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let mut points: BTreeMap<usize, &'a TocEntry> = BTreeMap::new();
    for toc_entry in toc_entries {
        let index = match &toc_entry.fragment {
            Some(frag) => anchors
                .get(frag)
                .copied()
                .and_then(|v| usize::try_from(v).ok()),
            None => Some(0),
        };
        if let Some(idx) = index {
            if idx < elements_len {
                points.entry(idx).or_insert(toc_entry);
            }
        }
    }

    let mut boundaries: Vec<(usize, Option<&'a TocEntry>)> = Vec::new();
    if points.keys().next() != Some(&0) {
        boundaries.push((0, None));
    }
    for (idx, entry) in points {
        boundaries.push((idx, Some(entry)));
    }
    if boundaries.is_empty() {
        boundaries.push((0, toc_entries.first()));
    }
    boundaries
}

fn record_linear_anchors_and_file(
    chapter_path: &str,
    added: &[FrbEpubSourceRange],
    anchors: &BTreeMap<String, i32>,
    anchor_locations: &mut HashMap<String, (i32, i32)>,
    file_locations: &mut HashMap<String, (i32, i32)>,
) {
    for (id, &index) in anchors {
        let Some(segment) = added.iter().rev().find(|s| index >= s.document_start) else {
            continue;
        };
        let local_index = (index - segment.document_start).clamp(0, segment.element_count - 1);
        anchor_locations
            .entry(format!("{chapter_path}#{id}"))
            .or_insert((segment.chapter_index, segment.chapter_start + local_index));
    }
    if let Some(first) = added.first() {
        file_locations
            .entry(chapter_path.to_owned())
            .or_insert((first.chapter_index, first.chapter_start));
    }
}

struct BookAccumulator {
    chapters: Vec<FrbChapter>,
    source_ranges: Vec<FrbEpubSourceRange>,
    linked_documents: BTreeMap<String, FrbLinkedDocument>,
    anchor_locations: HashMap<String, (i32, i32)>,
    file_locations: HashMap<String, (i32, i32)>,
    linked_locations: HashMap<String, FrbLinkedDocumentTarget>,
    link_targets: BTreeSet<String>,
    note_targets: BTreeSet<String>,
    exact_note_documents: HashMap<String, FrbNoteDocument>,
}

impl BookAccumulator {
    fn new() -> Self {
        Self {
            chapters: Vec::new(),
            source_ranges: Vec::new(),
            linked_documents: BTreeMap::new(),
            anchor_locations: HashMap::new(),
            file_locations: HashMap::new(),
            linked_locations: HashMap::new(),
            link_targets: BTreeSet::new(),
            note_targets: BTreeSet::new(),
            exact_note_documents: HashMap::new(),
        }
    }

    fn append_standalone_svg_linear(
        &mut self,
        spine_ordinal: usize,
        chapter_path: &str,
        elements: Vec<FrbContentElement>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) {
        let toc_entry = titles_by_path.get(chapter_path).and_then(|v| v.first());
        let chapter_index = self.chapters.len() as i32;
        let count = elements.len() as i32;
        self.chapters.push(FrbChapter {
            title: toc_entry.map(|e| e.title.clone()),
            elements,
            depth: toc_entry.map_or(0, |e| e.depth),
            publisher_boxes: Vec::new(),
        });
        self.source_ranges.push(FrbEpubSourceRange {
            spine_ordinal: spine_ordinal as i32,
            path: chapter_path.to_owned(),
            document_start: 0,
            chapter_index,
            chapter_start: 0,
            element_count: count,
        });
        self.file_locations
            .entry(chapter_path.to_owned())
            .or_insert((chapter_index, 0));
    }

    fn append_linear_document(
        &mut self,
        spine_ordinal: usize,
        chapter_path: &str,
        mut elements: Vec<FrbContentElement>,
        publisher_boxes: Vec<FrbPublisherBoxSpan>,
        anchors: &BTreeMap<String, i32>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) {
        let total_len = elements.len();
        if total_len == 0 {
            return;
        }
        let boundaries = compute_toc_boundaries(chapter_path, total_len, anchors, titles_by_path);

        let mut added: Vec<FrbEpubSourceRange> = Vec::with_capacity(boundaries.len());
        if boundaries.len() == 1 && boundaries[0].0 == 0 {
            let toc_entry = boundaries[0].1;
            let merge =
                toc_entry.is_none() && !titles_by_path.is_empty() && !self.chapters.is_empty();
            let taken = std::mem::take(&mut elements);
            let chapter_index: i32;
            let element_base: i32;
            if merge {
                let previous = self.chapters.last_mut().unwrap();
                element_base = previous.elements.len() as i32;
                previous.elements.extend(taken);
                previous.publisher_boxes.extend(slice_publisher_box_spans(
                    &publisher_boxes,
                    0,
                    total_len as i32,
                    element_base,
                ));
                chapter_index = (self.chapters.len() - 1) as i32;
            } else {
                let title = toc_entry
                    .map(|e| e.title.clone())
                    .or_else(|| first_heading_text(&taken));
                chapter_index = self.chapters.len() as i32;
                element_base = 0;
                self.chapters.push(FrbChapter {
                    title,
                    elements: taken,
                    depth: toc_entry.map_or(0, |e| e.depth),
                    publisher_boxes: slice_publisher_box_spans(
                        &publisher_boxes,
                        0,
                        total_len as i32,
                        0,
                    ),
                });
            }
            added.push(FrbEpubSourceRange {
                spine_ordinal: spine_ordinal as i32,
                path: chapter_path.to_owned(),
                document_start: 0,
                chapter_index,
                chapter_start: element_base,
                element_count: total_len as i32,
            });
        } else {
            let mut elem_iter = elements.into_iter();
            let mut cursor = 0_usize;
            for (pos, &(start, toc_entry)) in boundaries.iter().enumerate() {
                let end = boundaries.get(pos + 1).map_or(total_len, |b| b.0);
                if start > cursor {
                    for _ in 0..(start - cursor) {
                        let _ = elem_iter.next();
                    }
                    cursor = start;
                }
                if start >= end {
                    continue;
                }
                let seg_len = end - start;
                let segment: Vec<FrbContentElement> = elem_iter.by_ref().take(seg_len).collect();
                cursor = end;
                let merge =
                    toc_entry.is_none() && !titles_by_path.is_empty() && !self.chapters.is_empty();
                let chapter_index: i32;
                let element_base: i32;
                if merge {
                    let previous = self.chapters.last_mut().unwrap();
                    element_base = previous.elements.len() as i32;
                    previous.elements.extend(segment);
                    previous.publisher_boxes.extend(slice_publisher_box_spans(
                        &publisher_boxes,
                        start as i32,
                        end as i32,
                        element_base,
                    ));
                    chapter_index = (self.chapters.len() - 1) as i32;
                } else {
                    let title = toc_entry
                        .map(|e| e.title.clone())
                        .or_else(|| first_heading_text(&segment));
                    chapter_index = self.chapters.len() as i32;
                    element_base = 0;
                    self.chapters.push(FrbChapter {
                        title,
                        elements: segment,
                        depth: toc_entry.map_or(0, |e| e.depth),
                        publisher_boxes: slice_publisher_box_spans(
                            &publisher_boxes,
                            start as i32,
                            end as i32,
                            0,
                        ),
                    });
                }
                added.push(FrbEpubSourceRange {
                    spine_ordinal: spine_ordinal as i32,
                    path: chapter_path.to_owned(),
                    document_start: start as i32,
                    chapter_index,
                    chapter_start: element_base,
                    element_count: seg_len as i32,
                });
            }
        }
        self.source_ranges.extend(added.iter().cloned());
        record_linear_anchors_and_file(
            chapter_path,
            &added,
            anchors,
            &mut self.anchor_locations,
            &mut self.file_locations,
        );
    }

    fn append_linked_document(
        &mut self,
        chapter_path: &str,
        elements: Vec<FrbContentElement>,
        publisher_boxes: Vec<FrbPublisherBoxSpan>,
        anchors: &BTreeMap<String, i32>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) {
        let title = titles_by_path
            .get(chapter_path)
            .and_then(|v| v.first())
            .map(|e| e.title.clone())
            .or_else(|| first_heading_text(&elements));
        let last_index = (elements.len() as i32) - 1;
        self.linked_documents.insert(
            chapter_path.to_owned(),
            FrbLinkedDocument {
                id: chapter_path.to_owned(),
                title,
                elements,
                publisher_boxes,
            },
        );
        self.linked_locations
            .entry(chapter_path.to_owned())
            .or_insert_with(|| FrbLinkedDocumentTarget {
                document_id: chapter_path.to_owned(),
                element_index: 0,
            });
        for (id, &index) in anchors {
            self.linked_locations
                .entry(format!("{chapter_path}#{id}"))
                .or_insert_with(|| FrbLinkedDocumentTarget {
                    document_id: chapter_path.to_owned(),
                    element_index: index.clamp(0, last_index),
                });
        }
    }

    fn finish(
        self,
        toc: &TocIndex,
        fonts: Vec<FrbBookFont>,
        metadata_language: Option<String>,
        page_progression: FrbPageProgression,
    ) -> FrbBookContent {
        let mut nav_targets = self.file_locations;
        for (k, v) in &self.anchor_locations {
            nav_targets.insert(k.clone(), *v);
        }

        let mut notes = build_notes(
            &self.chapters,
            &self.anchor_locations,
            &self.note_targets,
            &self.exact_note_documents,
        );
        for (doc_id, doc) in &self.linked_documents {
            let mut local_anchors = HashMap::new();
            for (k, target) in &self.linked_locations {
                if target.document_id == *doc_id {
                    local_anchors.insert(k.clone(), (0_i32, target.element_index));
                }
            }
            let single = [FrbChapter {
                title: doc.title.clone(),
                elements: doc.elements.clone(),
                depth: 0,
                publisher_boxes: doc.publisher_boxes.clone(),
            }];
            let doc_notes = build_notes(
                &single,
                &local_anchors,
                &self.note_targets,
                &self.exact_note_documents,
            );
            notes.extend(doc_notes);
        }

        let mut navigation = Vec::new();
        for entry in &toc.entries {
            let key = entry.target_key();
            let target = if let Some(&(ch, el)) = nav_targets.get(&key) {
                FrbNavigationTarget::ReadingOrder {
                    chapter_index: ch,
                    element_index: el,
                }
            } else if let Some(linked) = self.linked_locations.get(&key) {
                FrbNavigationTarget::Linked {
                    document_id: linked.document_id.clone(),
                    element_index: linked.element_index,
                }
            } else {
                continue;
            };
            navigation.push(FrbNavigationEntry {
                title: entry.title.clone(),
                depth: entry.depth,
                target,
            });
        }

        let link_targets: BTreeMap<String, (i32, i32)> = nav_targets
            .into_iter()
            .filter(|(k, _)| self.link_targets.contains(k))
            .collect();
        let linked_document_targets: BTreeMap<String, FrbLinkedDocumentTarget> = self
            .linked_locations
            .into_iter()
            .filter(|(k, _)| self.link_targets.contains(k))
            .collect();

        let language = metadata_language.or_else(|| detect_language_from_chapters(&self.chapters));

        FrbBookContent {
            chapters: self.chapters,
            notes,
            link_targets,
            fonts,
            language,
            linked_documents: self.linked_documents,
            linked_document_targets,
            navigation,
            publisher_publication: None,
            page_progression,
            epub_source_ranges: self.source_ranges,
        }
    }
}

/// Streaming chapter spool accumulator for `write_epub_frb_wire`.
/// Retains only the single currently-open `FrbChapter` in RAM, flushing each completed
/// chapter to an LZ4-compressed temporary spool file next to `dest_frb_path`.
struct StreamingBookAccumulator {
    open_chapter: Option<FrbChapter>,
    chapter_indices: Vec<FrbChapterIndex>,
    total_elements: i64,
    spool: SpoolFile,
    spool_entries: Vec<SpoolEntry>,
    language_sampler: LanguageSampler,
    resource_paths: HashSet<String>,
    publisher_style: StreamingPublisherStyle,
    source_ranges: Vec<FrbEpubSourceRange>,
    linked_documents: BTreeMap<String, FrbLinkedDocument>,
    anchor_locations: HashMap<String, (i32, i32)>,
    file_locations: HashMap<String, (i32, i32)>,
    linked_locations: HashMap<String, FrbLinkedDocumentTarget>,
    link_targets: BTreeSet<String>,
    note_targets: BTreeSet<String>,
    exact_note_documents: HashMap<String, FrbNoteDocument>,
}

impl StreamingBookAccumulator {
    fn new(dest_frb_path: &Path) -> Result<Self, NativeEpubOutcome> {
        let spool = SpoolFile::create_near(dest_frb_path).map_err(NativeEpubOutcome::Error)?;
        Ok(Self {
            open_chapter: None,
            chapter_indices: Vec::new(),
            total_elements: 0,
            spool,
            spool_entries: Vec::new(),
            language_sampler: LanguageSampler::default(),
            resource_paths: HashSet::new(),
            publisher_style: StreamingPublisherStyle::new(),
            source_ranges: Vec::new(),
            linked_documents: BTreeMap::new(),
            anchor_locations: HashMap::new(),
            file_locations: HashMap::new(),
            linked_locations: HashMap::new(),
            link_targets: BTreeSet::new(),
            note_targets: BTreeSet::new(),
            exact_note_documents: HashMap::new(),
        })
    }

    fn flush_open_chapter(&mut self) -> Result<(), NativeEpubOutcome> {
        let Some(chapter) = self.open_chapter.take() else {
            return Ok(());
        };
        if self.chapter_indices.len() >= frb::MAX_CHAPTERS {
            return Err(NativeEpubOutcome::Error("container limit".to_owned()));
        }
        let count = i64::try_from(chapter.elements.len())
            .map_err(|_| NativeEpubOutcome::Error("Content coordinate overflow".to_owned()))?;
        if self.total_elements + count > i64::from(i32::MAX) {
            return Err(NativeEpubOutcome::Error(
                "Content coordinate overflow".to_owned(),
            ));
        }
        let element_count = count as i32;
        let start_element = self.total_elements as i32;

        self.language_sampler.observe_chapter(&chapter);
        collect_element_resources_into(&chapter.elements, &mut self.resource_paths);
        self.publisher_style.observe_chapter(&chapter);
        self.chapter_indices.push(FrbChapterIndex {
            title: chapter.title.clone(),
            depth: chapter.depth,
            element_count,
            start_element,
        });
        self.total_elements += count;

        let raw = encode_chapter(&chapter);
        drop(chapter);
        if raw.len() as u64 > frb::MAX_BLOCK_BYTES {
            return Err(NativeEpubOutcome::Error(
                "FRB chapter block exceeds limit".to_owned(),
            ));
        }
        let expanded_len = raw.len() as u64;
        let crc = crc32fast::hash(&raw);
        let compressed = lz4_flex::block::compress(&raw);
        drop(raw);

        let spool_offset = self
            .spool
            .append(&compressed)
            .map_err(NativeEpubOutcome::Error)?;
        self.spool_entries.push(SpoolEntry {
            spool_offset,
            compressed_len: compressed.len(),
            expanded_len,
            crc,
            element_count,
        });
        Ok(())
    }

    fn append_standalone_svg_linear(
        &mut self,
        spine_ordinal: usize,
        chapter_path: &str,
        elements: Vec<FrbContentElement>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) -> Result<(), NativeEpubOutcome> {
        self.flush_open_chapter()?;
        let toc_entry = titles_by_path.get(chapter_path).and_then(|v| v.first());
        let chapter_index = self.chapter_indices.len() as i32;
        let count = elements.len() as i32;
        self.open_chapter = Some(FrbChapter {
            title: toc_entry.map(|e| e.title.clone()),
            elements,
            depth: toc_entry.map_or(0, |e| e.depth),
            publisher_boxes: Vec::new(),
        });
        self.source_ranges.push(FrbEpubSourceRange {
            spine_ordinal: spine_ordinal as i32,
            path: chapter_path.to_owned(),
            document_start: 0,
            chapter_index,
            chapter_start: 0,
            element_count: count,
        });
        self.file_locations
            .entry(chapter_path.to_owned())
            .or_insert((chapter_index, 0));
        Ok(())
    }

    fn append_linear_document(
        &mut self,
        spine_ordinal: usize,
        chapter_path: &str,
        mut elements: Vec<FrbContentElement>,
        publisher_boxes: Vec<FrbPublisherBoxSpan>,
        anchors: &BTreeMap<String, i32>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) -> Result<(), NativeEpubOutcome> {
        let total_len = elements.len();
        if total_len == 0 {
            return Ok(());
        }
        let boundaries = compute_toc_boundaries(chapter_path, total_len, anchors, titles_by_path);

        let mut added: Vec<FrbEpubSourceRange> = Vec::with_capacity(boundaries.len());
        if boundaries.len() == 1 && boundaries[0].0 == 0 {
            let toc_entry = boundaries[0].1;
            let merge =
                toc_entry.is_none() && !titles_by_path.is_empty() && self.open_chapter.is_some();
            let taken = std::mem::take(&mut elements);
            let chapter_index: i32;
            let element_base: i32;
            if merge {
                let previous = self.open_chapter.as_mut().unwrap();
                element_base = previous.elements.len() as i32;
                previous.elements.extend(taken);
                previous.publisher_boxes.extend(slice_publisher_box_spans(
                    &publisher_boxes,
                    0,
                    total_len as i32,
                    element_base,
                ));
                chapter_index = self.chapter_indices.len() as i32;
            } else {
                self.flush_open_chapter()?;
                let title = toc_entry
                    .map(|e| e.title.clone())
                    .or_else(|| first_heading_text(&taken));
                chapter_index = self.chapter_indices.len() as i32;
                element_base = 0;
                self.open_chapter = Some(FrbChapter {
                    title,
                    elements: taken,
                    depth: toc_entry.map_or(0, |e| e.depth),
                    publisher_boxes: slice_publisher_box_spans(
                        &publisher_boxes,
                        0,
                        total_len as i32,
                        0,
                    ),
                });
            }
            added.push(FrbEpubSourceRange {
                spine_ordinal: spine_ordinal as i32,
                path: chapter_path.to_owned(),
                document_start: 0,
                chapter_index,
                chapter_start: element_base,
                element_count: total_len as i32,
            });
        } else {
            let mut elem_iter = elements.into_iter();
            let mut cursor = 0_usize;
            for (pos, &(start, toc_entry)) in boundaries.iter().enumerate() {
                let end = boundaries.get(pos + 1).map_or(total_len, |b| b.0);
                if start > cursor {
                    for _ in 0..(start - cursor) {
                        let _ = elem_iter.next();
                    }
                    cursor = start;
                }
                if start >= end {
                    continue;
                }
                let seg_len = end - start;
                let segment: Vec<FrbContentElement> = elem_iter.by_ref().take(seg_len).collect();
                cursor = end;
                let merge = toc_entry.is_none()
                    && !titles_by_path.is_empty()
                    && self.open_chapter.is_some();
                let chapter_index: i32;
                let element_base: i32;
                if merge {
                    let previous = self.open_chapter.as_mut().unwrap();
                    element_base = previous.elements.len() as i32;
                    previous.elements.extend(segment);
                    previous.publisher_boxes.extend(slice_publisher_box_spans(
                        &publisher_boxes,
                        start as i32,
                        end as i32,
                        element_base,
                    ));
                    chapter_index = self.chapter_indices.len() as i32;
                } else {
                    self.flush_open_chapter()?;
                    let title = toc_entry
                        .map(|e| e.title.clone())
                        .or_else(|| first_heading_text(&segment));
                    chapter_index = self.chapter_indices.len() as i32;
                    element_base = 0;
                    self.open_chapter = Some(FrbChapter {
                        title,
                        elements: segment,
                        depth: toc_entry.map_or(0, |e| e.depth),
                        publisher_boxes: slice_publisher_box_spans(
                            &publisher_boxes,
                            start as i32,
                            end as i32,
                            0,
                        ),
                    });
                }
                added.push(FrbEpubSourceRange {
                    spine_ordinal: spine_ordinal as i32,
                    path: chapter_path.to_owned(),
                    document_start: start as i32,
                    chapter_index,
                    chapter_start: element_base,
                    element_count: seg_len as i32,
                });
            }
        }
        self.source_ranges.extend(added.iter().cloned());
        record_linear_anchors_and_file(
            chapter_path,
            &added,
            anchors,
            &mut self.anchor_locations,
            &mut self.file_locations,
        );
        Ok(())
    }

    fn append_linked_document(
        &mut self,
        chapter_path: &str,
        elements: Vec<FrbContentElement>,
        publisher_boxes: Vec<FrbPublisherBoxSpan>,
        anchors: &BTreeMap<String, i32>,
        titles_by_path: &HashMap<String, Vec<TocEntry>>,
    ) {
        let title = titles_by_path
            .get(chapter_path)
            .and_then(|v| v.first())
            .map(|e| e.title.clone())
            .or_else(|| first_heading_text(&elements));
        let last_index = (elements.len() as i32) - 1;
        self.linked_documents.insert(
            chapter_path.to_owned(),
            FrbLinkedDocument {
                id: chapter_path.to_owned(),
                title,
                elements,
                publisher_boxes,
            },
        );
        self.linked_locations
            .entry(chapter_path.to_owned())
            .or_insert_with(|| FrbLinkedDocumentTarget {
                document_id: chapter_path.to_owned(),
                element_index: 0,
            });
        for (id, &index) in anchors {
            self.linked_locations
                .entry(format!("{chapter_path}#{id}"))
                .or_insert_with(|| FrbLinkedDocumentTarget {
                    document_id: chapter_path.to_owned(),
                    element_index: index.clamp(0, last_index),
                });
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn build_streaming_notes(
        &self,
        prep: &mut PreparedEpub,
        limits: &EpubLimits,
    ) -> Result<BTreeMap<String, FrbNoteDocument>, NativeEpubOutcome> {
        let mut notes = BTreeMap::new();
        for (k, doc) in &self.exact_note_documents {
            if self.note_targets.contains(k) && !doc.elements.is_empty() {
                notes.insert(k.clone(), doc.clone());
            }
        }

        // Identify only the linear chapters required for non-exact anchored footnotes.
        let mut targets_by_chapter: HashMap<i32, Vec<(String, i32)>> = HashMap::new();
        for key in &self.note_targets {
            if notes.contains_key(key) {
                continue;
            }
            let Some(&(ch_idx, el_idx)) = self.anchor_locations.get(key) else {
                continue;
            };
            if el_idx >= 0 && (ch_idx as usize) < self.chapter_indices.len() {
                targets_by_chapter
                    .entry(ch_idx)
                    .or_default()
                    .push((key.clone(), el_idx));
            }
        }

        if !targets_by_chapter.is_empty() {
            let mut anchored_starts: HashMap<i32, Vec<i32>> = HashMap::new();
            for &(ch_idx, el_idx) in self.anchor_locations.values() {
                if targets_by_chapter.contains_key(&ch_idx) {
                    anchored_starts.entry(ch_idx).or_default().push(el_idx);
                }
            }
            for starts in anchored_starts.values_mut() {
                starts.sort_unstable();
                starts.dedup();
            }

            let mut ranges_by_ordinal: BTreeMap<usize, Vec<&FrbEpubSourceRange>> = BTreeMap::new();
            for range in &self.source_ranges {
                if targets_by_chapter.contains_key(&range.chapter_index) {
                    ranges_by_ordinal
                        .entry(range.spine_ordinal as usize)
                        .or_default()
                        .push(range);
                }
            }

            let spine_by_ordinal: HashMap<usize, usize> = prep
                .active_spine
                .iter()
                .enumerate()
                .map(|(idx, (item, _))| (item.ordinal, idx))
                .collect();

            let slice_chapter_notes =
                |ch_idx: i32,
                 chapter: &FrbChapter,
                 notes: &mut BTreeMap<String, FrbNoteDocument>| {
                    let Some(targets) = targets_by_chapter.get(&ch_idx) else {
                        return;
                    };
                    let starts = anchored_starts
                        .get(&ch_idx)
                        .map(Vec::as_slice)
                        .unwrap_or(&[]);
                    for (key, el_idx) in targets {
                        if *el_idx < 0 || (*el_idx as usize) >= chapter.elements.len() {
                            continue;
                        }
                        let next_pos = match starts.binary_search(el_idx) {
                            Ok(pos) => pos + 1,
                            Err(pos) => pos,
                        };
                        let end = starts
                            .get(next_pos)
                            .copied()
                            .unwrap_or(chapter.elements.len() as i32);
                        if end > *el_idx {
                            notes.insert(
                                key.clone(),
                                FrbNoteDocument {
                                    elements: chapter.elements[*el_idx as usize..end as usize]
                                        .to_vec(),
                                    publisher_boxes: slice_publisher_box_spans(
                                        &chapter.publisher_boxes,
                                        *el_idx,
                                        end,
                                        0,
                                    ),
                                },
                            );
                        }
                    }
                };

            let mut in_progress: HashMap<i32, FrbChapter> = HashMap::new();
            let mut style_cache = SharedStyleCache::new();
            for (ordinal, ranges) in ranges_by_ordinal {
                let Some(&active_idx) = spine_by_ordinal.get(&ordinal) else {
                    continue;
                };
                let (spine_item, chapter_path) = &prep.active_spine[active_idx];
                let outcome = map_single_spine_item(
                    spine_item,
                    chapter_path,
                    &mut prep.archive,
                    &prep.dir,
                    &prep.css_state,
                    &prep.image_extractor,
                    &prep.inline_svg_writer,
                    &mut style_cache,
                    limits,
                    0,
                    prep.fb2_epub_conversion,
                );
                let mapped = match outcome {
                    SpineTaskOutcome::Mapped(m) => m,
                    SpineTaskOutcome::Skipped { .. } => continue,
                    SpineTaskOutcome::Fallback(reason) => {
                        return Err(NativeEpubOutcome::Fallback(reason));
                    }
                    SpineTaskOutcome::Limit { kind, message } => {
                        return Err(NativeEpubOutcome::Limit { kind, message });
                    }
                    SpineTaskOutcome::Error(message) => {
                        return Err(NativeEpubOutcome::Error(message));
                    }
                };
                for range in ranges {
                    let start = range.document_start as usize;
                    let end = start.saturating_add(range.element_count as usize);
                    if end > mapped.elements.len() {
                        continue;
                    }
                    let ch = in_progress.entry(range.chapter_index).or_default();
                    ch.elements.extend_from_slice(&mapped.elements[start..end]);
                    ch.publisher_boxes.extend(slice_publisher_box_spans(
                        &mapped.publisher_boxes,
                        range.document_start,
                        range.document_start + range.element_count,
                        range.chapter_start,
                    ));
                    let expected_len =
                        self.chapter_indices[range.chapter_index as usize].element_count as usize;
                    if ch.elements.len() >= expected_len {
                        let finished = in_progress.remove(&range.chapter_index).unwrap();
                        slice_chapter_notes(range.chapter_index, &finished, &mut notes);
                    }
                }
            }
            for (ch_idx, chapter) in in_progress {
                slice_chapter_notes(ch_idx, &chapter, &mut notes);
            }
        }

        for (doc_id, doc) in &self.linked_documents {
            let mut local_anchors = HashMap::new();
            for (k, target) in &self.linked_locations {
                if target.document_id == *doc_id {
                    local_anchors.insert(k.clone(), (0_i32, target.element_index));
                }
            }
            let single = [FrbChapter {
                title: doc.title.clone(),
                elements: doc.elements.clone(),
                depth: 0,
                publisher_boxes: doc.publisher_boxes.clone(),
            }];
            let doc_notes = build_notes(
                &single,
                &local_anchors,
                &self.note_targets,
                &self.exact_note_documents,
            );
            notes.extend(doc_notes);
        }

        Ok(notes)
    }

    #[allow(clippy::too_many_arguments)]
    fn finish_and_write(
        mut self,
        prep: &mut PreparedEpub,
        fonts: Vec<FrbBookFont>,
        dest_frb_path: &Path,
        source_metadata: &[u8],
        limits: &EpubLimits,
    ) -> Result<(), NativeEpubOutcome> {
        self.flush_open_chapter()?;

        let notes = self.build_streaming_notes(prep, limits)?;

        for font in &fonts {
            self.resource_paths.insert(font.path.clone());
        }
        for note in notes.values() {
            collect_element_resources_into(&note.elements, &mut self.resource_paths);
        }
        for doc in self.linked_documents.values() {
            collect_element_resources_into(&doc.elements, &mut self.resource_paths);
        }
        let mut resource_paths: Vec<String> = self.resource_paths.into_iter().collect();
        resource_paths.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));

        let publisher_style = self.publisher_style.finish(&fonts);

        let mut nav_targets = self.file_locations;
        for (k, v) in &self.anchor_locations {
            nav_targets.insert(k.clone(), *v);
        }

        let mut navigation = Vec::new();
        for entry in &prep.toc.entries {
            let key = entry.target_key();
            let target = if let Some(&(ch, el)) = nav_targets.get(&key) {
                FrbNavigationTarget::ReadingOrder {
                    chapter_index: ch,
                    element_index: el,
                }
            } else if let Some(linked) = self.linked_locations.get(&key) {
                FrbNavigationTarget::Linked {
                    document_id: linked.document_id.clone(),
                    element_index: linked.element_index,
                }
            } else {
                continue;
            };
            navigation.push(FrbNavigationEntry {
                title: entry.title.clone(),
                depth: entry.depth,
                target,
            });
        }

        let link_targets: BTreeMap<String, (i32, i32)> = nav_targets
            .into_iter()
            .filter(|(k, _)| self.link_targets.contains(k))
            .collect();
        let linked_document_targets: BTreeMap<String, FrbLinkedDocumentTarget> = self
            .linked_locations
            .into_iter()
            .filter(|(k, _)| self.link_targets.contains(k))
            .collect();

        let language = prep
            .metadata_language
            .clone()
            .or_else(|| self.language_sampler.finish());

        let counts: Vec<i32> = self.spool_entries.iter().map(|e| e.element_count).collect();
        let book_index = FrbBookIndex {
            chapters: self.chapter_indices,
            navigation: navigation.clone(),
            link_targets: link_targets.clone(),
            linked_document_targets: linked_document_targets.clone(),
            fonts: fonts.clone(),
            language: language.clone(),
            page_progression: prep.rendition.page_progression,
            total_elements: self.total_elements as i32,
            resource_paths,
            publisher_style,
            epub_source_ranges: self.source_ranges,
        };
        let index_bytes = encode_book_index(&book_index);
        drop(book_index);
        if index_bytes.len() as u64 > frb::MAX_INDEX_BYTES {
            return Err(NativeEpubOutcome::Error(
                "FRB index block exceeds limit".to_owned(),
            ));
        }

        let globals_content = FrbBookContent {
            chapters: Vec::new(),
            notes,
            link_targets,
            fonts,
            language,
            linked_documents: self.linked_documents,
            linked_document_targets,
            navigation,
            publisher_publication: None,
            page_progression: prep.rendition.page_progression,
            epub_source_ranges: Vec::new(),
        };
        let globals_bytes = encode_book_globals(&globals_content);
        drop(globals_content);
        if globals_bytes.len() as u64 > frb::MAX_BLOCK_BYTES {
            return Err(NativeEpubOutcome::Error(
                "FRB globals block exceeds limit".to_owned(),
            ));
        }

        let mut writer =
            frb::Writer::begin(dest_frb_path, source_metadata, &counts, frb::MAX_FILE_BYTES)
                .map_err(|e| NativeEpubOutcome::Error(e.to_string()))?;

        let write_result = (|| -> Result<(), String> {
            writer
                .write_block_bytes(0, &index_bytes)
                .map_err(|e| e.to_string())?;
            drop(index_bytes);
            writer
                .write_block_bytes(1, &globals_bytes)
                .map_err(|e| e.to_string())?;
            drop(globals_bytes);

            let spool_file = self.spool.file_mut();
            spool_file
                .seek(SeekFrom::Start(0))
                .map_err(|e| e.to_string())?;
            let mut copy_buf: Vec<u8> = Vec::new();
            for (idx, entry) in self.spool_entries.iter().enumerate() {
                debug_assert_eq!(
                    spool_file.stream_position().unwrap_or(u64::MAX),
                    entry.spool_offset
                );
                copy_buf.resize(entry.compressed_len, 0);
                spool_file
                    .read_exact(&mut copy_buf)
                    .map_err(|e| e.to_string())?;
                writer
                    .write_precompressed_block(idx + 2, &copy_buf, entry.expanded_len, entry.crc)
                    .map_err(|e| e.to_string())?;
            }
            writer.finish().map_err(|e| e.to_string())
        })();

        if let Err(msg) = write_result {
            drop(writer);
            let _ = fs::remove_file(dest_frb_path);
            return Err(NativeEpubOutcome::Error(msg));
        }
        Ok(())
    }
}

fn first_heading_text(elements: &[FrbContentElement]) -> Option<String> {
    elements.iter().find_map(|el| match el {
        FrbContentElement::Heading { styled_text, .. } => Some(styled_text.text.clone()),
        _ => None,
    })
}

// --------------------------------------------------------------------------- Top-Level Entry Point

fn find_opf_path(
    archive: &mut ZipArchive<BufReader<File>>,
    dir: &ArchiveDirectory,
    limits: &EpubLimits,
    bytes_read: &mut u64,
) -> Result<Option<String>, ArchiveError> {
    if let Some(container) = dir.lookup("META-INF/container.xml") {
        let bytes = read_entry_checked(
            archive,
            container,
            limits.max_package_xml_bytes,
            "EPUB container metadata",
            false,
            limits,
            bytes_read,
        )?
        .ok_or(ArchiveError::Fallback("missing container bytes"))?;
        if let Some(path) = epub_package::container_rootfile(&bytes) {
            if !path.is_empty() && is_safe_archive_path(&path) {
                return Ok(Some(path));
            }
        }
    }
    Ok(dir
        .entries
        .iter()
        .find(|e| {
            !e.is_dir
                && is_safe_archive_path(&e.name)
                && e.name.to_ascii_lowercase().ends_with(".opf")
        })
        .map(|e| e.name.clone()))
}

fn parse_encryption_map(
    archive: &mut ZipArchive<BufReader<File>>,
    dir: &ArchiveDirectory,
    limits: &EpubLimits,
    bytes_read: &mut u64,
) -> Result<HashMap<String, String>, ArchiveError> {
    let mut out = HashMap::new();
    let Some(entry) = dir.lookup("META-INF/encryption.xml") else {
        return Ok(out);
    };
    let Some(bytes) = read_entry_checked(
        archive,
        entry,
        limits.max_package_xml_bytes,
        "EPUB encryption metadata",
        true,
        limits,
        bytes_read,
    )?
    else {
        return Ok(out);
    };
    let Some(entries) = epub_package::parse_encryption(&bytes) else {
        return Ok(out);
    };
    for (uri, alg) in entries {
        if uri.is_empty() || alg.is_empty() {
            continue;
        }
        out.insert(resolve_path("", &uri), alg);
    }
    Ok(out)
}

fn archive_err_to_outcome(err: ArchiveError) -> NativeEpubOutcome {
    match err {
        ArchiveError::Limit { kind, message } => NativeEpubOutcome::Limit { kind, message },
        ArchiveError::Fallback(reason) => NativeEpubOutcome::Fallback(reason),
        ArchiveError::Io(message) => NativeEpubOutcome::Error(message),
    }
}

fn archive_err_to_task_outcome(err: ArchiveError) -> SpineTaskOutcome {
    match err {
        ArchiveError::Limit { kind, message } => SpineTaskOutcome::Limit { kind, message },
        ArchiveError::Fallback(reason) => SpineTaskOutcome::Fallback(reason),
        ArchiveError::Io(message) => SpineTaskOutcome::Error(message),
    }
}

struct EpubCallbacks<'a> {
    chapter_path: &'a str,
    chapter_dir: &'a str,
    archive: &'a mut ZipArchive<BufReader<File>>,
    dir: &'a ArchiveDirectory,
    image_extractor: &'a SharedImageExtractor,
    inline_svg_writer: &'a SharedInlineSvgWriter,
    bytes_read: &'a mut u64,
    error: Option<ArchiveError>,
}

impl ResourceCallbacks for EpubCallbacks<'_> {
    fn resolve_image(&mut self, src: &str) -> Option<String> {
        if self.error.is_some() {
            return None;
        }
        let entry_path = resolve_path(self.chapter_dir, src);
        match self.image_extractor.extract(
            self.archive,
            self.dir,
            &entry_path,
            self.bytes_read,
            false,
        ) {
            Ok(path) => path,
            Err(err) => {
                self.error = Some(err);
                None
            }
        }
    }

    fn resolve_link(&mut self, href: &str) -> Option<String> {
        resolve_link_key(href, self.chapter_path, self.chapter_dir)
    }

    fn resolve_inline_svg(&mut self, markup: &str) -> Option<String> {
        self.inline_svg_writer.write(markup)
    }
}

#[allow(clippy::too_many_arguments)]
fn map_single_spine_item(
    spine_item: &SpineItem,
    chapter_path: &str,
    archive: &mut ZipArchive<BufReader<File>>,
    dir: &ArchiveDirectory,
    css_state: &Mutex<SharedCssState>,
    image_extractor: &SharedImageExtractor,
    inline_svg_writer: &SharedInlineSvgWriter,
    style_cache: &mut SharedStyleCache,
    limits: &EpubLimits,
    base_bytes_read: u64,
    fb2_epub_conversion: bool,
) -> SpineTaskOutcome {
    let mut local_bytes = base_bytes_read;
    let Some(zip_entry) = dir.lookup(chapter_path) else {
        return SpineTaskOutcome::Skipped { bytes_read: 0 };
    };

    if spine_item
        .item
        .media_type
        .eq_ignore_ascii_case("image/svg+xml")
    {
        let image_path = match image_extractor.extract(
            archive,
            dir,
            chapter_path,
            &mut local_bytes,
            spine_item.linear,
        ) {
            Ok(Some(path)) => path,
            Ok(None) => {
                return SpineTaskOutcome::Skipped {
                    bytes_read: local_bytes.saturating_sub(base_bytes_read),
                };
            }
            Err(err) => return archive_err_to_task_outcome(err),
        };
        let svg_bytes = match read_entry_checked(
            archive,
            zip_entry,
            limits.max_image_bytes,
            "EPUB SVG description",
            true,
            limits,
            &mut local_bytes,
        ) {
            Ok(bytes) => bytes,
            Err(err) => return archive_err_to_task_outcome(err),
        };
        let alt_text = svg_bytes.as_deref().and_then(parse_svg_alt_text);
        let mut image = FrbImageElement::new(image_path);
        image.alt_text = alt_text;
        return SpineTaskOutcome::Mapped(MappedSpineOutput {
            spine_ordinal: spine_item.ordinal,
            linear: spine_item.linear,
            chapter_path: chapter_path.to_owned(),
            has_body: true,
            is_standalone_svg: true,
            font_faces: Vec::new(),
            elements: vec![FrbContentElement::Image(Box::new(image))],
            publisher_boxes: Vec::new(),
            anchors: BTreeMap::new(),
            link_targets: BTreeSet::new(),
            note_targets: BTreeSet::new(),
            note_documents: BTreeMap::new(),
            generated_chars_used: 0,
            was_aggregate_limited: false,
            bytes_read: local_bytes.saturating_sub(base_bytes_read),
        });
    }

    let is_dtbook = spine_item
        .item
        .media_type
        .eq_ignore_ascii_case(DTBOOK_MEDIA_TYPE);
    let label = if spine_item.linear {
        format!("EPUB spine document {}", zip_entry.name)
    } else {
        format!("EPUB linked document {}", zip_entry.name)
    };
    let raw_bytes = match read_entry_checked(
        archive,
        zip_entry,
        limits.max_chapter_bytes,
        &label,
        !spine_item.linear,
        limits,
        &mut local_bytes,
    ) {
        Ok(Some(bytes)) => bytes,
        Ok(None) => {
            return SpineTaskOutcome::Skipped {
                bytes_read: local_bytes.saturating_sub(base_bytes_read),
            };
        }
        Err(err) => return archive_err_to_task_outcome(err),
    };

    let Some(decoded) = chapter_charset::decode(&raw_bytes) else {
        return if spine_item.linear {
            SpineTaskOutcome::Fallback("chapter charset decode failed")
        } else {
            SpineTaskOutcome::Skipped {
                bytes_read: local_bytes.saturating_sub(base_bytes_read),
            }
        };
    };
    let Some(doc) = chapter_markup::parse(&decoded, false) else {
        return if spine_item.linear {
            SpineTaskOutcome::Fallback("chapter markup parse failed")
        } else {
            SpineTaskOutcome::Skipped {
                bytes_read: local_bytes.saturating_sub(base_bytes_read),
            }
        };
    };
    drop(decoded);
    drop(raw_bytes);

    let Some(body_node) = doc.body_or_book(is_dtbook) else {
        return SpineTaskOutcome::Mapped(MappedSpineOutput {
            spine_ordinal: spine_item.ordinal,
            linear: spine_item.linear,
            chapter_path: chapter_path.to_owned(),
            has_body: false,
            is_standalone_svg: false,
            font_faces: Vec::new(),
            elements: Vec::new(),
            publisher_boxes: Vec::new(),
            anchors: BTreeMap::new(),
            link_targets: BTreeSet::new(),
            note_targets: BTreeSet::new(),
            note_documents: BTreeMap::new(),
            generated_chars_used: 0,
            was_aggregate_limited: false,
            bytes_read: local_bytes.saturating_sub(base_bytes_read),
        });
    };

    let chapter_dir = parent_dir(chapter_path);
    let compiled = {
        let mut guard = css_state.lock().unwrap();
        match guard.resolve_compiled_for_chapter(
            &doc,
            chapter_dir,
            archive,
            dir,
            limits,
            &mut local_bytes,
        ) {
            Ok(compiled) => compiled,
            Err(err) => return archive_err_to_task_outcome(err),
        }
    };
    let font_faces = compiled.font_faces().to_vec();
    let mut chapter_css = ChapterCss::new(Arc::clone(&compiled), &doc, style_cache);

    let mut callbacks = EpubCallbacks {
        chapter_path,
        chapter_dir,
        archive,
        dir,
        image_extractor,
        inline_svg_writer,
        bytes_read: &mut local_bytes,
        error: None,
    };
    let max_gen_total =
        usize::try_from(limits.max_html_generated_total_chars).unwrap_or(usize::MAX);
    let options = MapOptions {
        publisher_box_id_prefix: format!("{chapter_path}@{}", spine_item.ordinal),
        max_structure_depth: 256,
        max_generated_run_chars: limits.max_html_generated_run_chars,
        max_generated_total_chars: max_gen_total,
        remaining_generated_total_chars: max_gen_total,
        fb2_epub_conversion,
    };

    let mapped = map_document(
        &doc,
        body_node,
        Some(&mut chapter_css),
        &mut callbacks,
        &options,
    );
    if let Some(err) = callbacks.error {
        return archive_err_to_task_outcome(err);
    }
    match mapped {
        Ok(body) => SpineTaskOutcome::Mapped(MappedSpineOutput {
            spine_ordinal: spine_item.ordinal,
            linear: spine_item.linear,
            chapter_path: chapter_path.to_owned(),
            has_body: true,
            is_standalone_svg: false,
            font_faces,
            elements: body.elements,
            publisher_boxes: body.publisher_boxes,
            anchors: body.anchors,
            link_targets: body.link_targets,
            note_targets: body.note_targets,
            note_documents: body.note_documents,
            generated_chars_used: body.generated_chars_used,
            was_aggregate_limited: body.was_aggregate_limited,
            bytes_read: local_bytes.saturating_sub(base_bytes_read),
        }),
        Err(MapError::ComplexMarkup(reason)) => SpineTaskOutcome::Fallback(reason),
        Err(MapError::Internal(_)) => SpineTaskOutcome::Fallback("html mapper internal error"),
    }
}

struct PreparedEpub {
    archive: ZipArchive<BufReader<File>>,
    dir: Arc<ArchiveDirectory>,
    toc: TocIndex,
    encryption: HashMap<String, String>,
    font_keys: FontKeys,
    metadata_language: Option<String>,
    rendition: PackageRendition,
    fb2_epub_conversion: bool,
    active_spine: Vec<(SpineItem, String)>,
    package_bytes_read: u64,
    css_state: Mutex<SharedCssState>,
    image_extractor: SharedImageExtractor,
    inline_svg_writer: SharedInlineSvgWriter,
}

fn prepare_epub(
    epub_path: &Path,
    images_dir: &Path,
    limits: &EpubLimits,
) -> Result<PreparedEpub, NativeEpubOutcome> {
    let (mut archive, dir) =
        open_archive_with_directory(epub_path, limits).map_err(archive_err_to_outcome)?;

    let mut bytes_read = 0_u64;
    let opf_path = find_opf_path(&mut archive, &dir, limits, &mut bytes_read)
        .map_err(archive_err_to_outcome)?
        .ok_or(NativeEpubOutcome::Fallback("missing opf"))?;
    let opf_dir = parent_dir(&opf_path).to_owned();
    let opf_entry = dir
        .lookup(&opf_path)
        .ok_or(NativeEpubOutcome::Fallback("missing opf entry"))?;
    let opf_bytes = read_entry_checked(
        &mut archive,
        opf_entry,
        limits.max_package_xml_bytes,
        &format!("EPUB package {opf_path}"),
        false,
        limits,
        &mut bytes_read,
    )
    .map_err(archive_err_to_outcome)?
    .ok_or(NativeEpubOutcome::Fallback("missing opf bytes"))?;
    let opf = epub_package::package_bytes(&opf_bytes)
        .and_then(|w| decode_opf_wire(&w))
        .ok_or(NativeEpubOutcome::Fallback("invalid opf xml"))?;

    let spine = build_spine_items(&opf);
    let rendition = parse_package_rendition(&opf);
    for item in &spine {
        if is_fixed_layout(&rendition, item) {
            return Err(NativeEpubOutcome::Fallback("fixed-layout epub"));
        }
    }

    let toc = parse_toc_index(&mut archive, &dir, &opf, &opf_dir, limits, &mut bytes_read)
        .map_err(archive_err_to_outcome)?;
    let encryption = parse_encryption_map(&mut archive, &dir, limits, &mut bytes_read)
        .map_err(archive_err_to_outcome)?;
    let font_keys = if encryption.is_empty() {
        FontKeys {
            idpf: None,
            adobe: Vec::new(),
        }
    } else {
        font_keys_from(&opf)
    };

    let metadata_language =
        normalize_language_tag(opf.dc_fields("language").next().map(|m| m.text.as_str()));
    let fb2_epub_conversion = opf.metadata.iter().any(|m| {
        m.tag.eq_ignore_ascii_case("meta") && m.name.eq_ignore_ascii_case("FB2EPUB.version")
    });

    // Deduplicate linear/non-linear spine items matching `EpubParser.kt` lines 593-597 & 792-794.
    let linear_paths: HashSet<String> = spine
        .iter()
        .filter(|s| s.linear)
        .map(|s| resolve_path(&opf_dir, &s.item.href))
        .collect();
    let mut processed_linked_paths: HashSet<String> = HashSet::new();
    let mut active_spine: Vec<(SpineItem, String)> = Vec::with_capacity(spine.len());
    for s in spine {
        let chapter_path = resolve_path(&opf_dir, &s.item.href);
        if !s.linear && linear_paths.contains(&chapter_path) {
            continue;
        }
        if !s.linear && !processed_linked_paths.insert(chapter_path.clone()) {
            continue;
        }
        let is_svg = s.item.media_type.eq_ignore_ascii_case("image/svg+xml");
        let is_dtbook = s.item.media_type.eq_ignore_ascii_case(DTBOOK_MEDIA_TYPE);
        if !contains_ascii_ignore_case(&s.item.media_type, "html") && !is_svg && !is_dtbook {
            continue;
        }
        if dir.lookup(&chapter_path).is_none() {
            continue;
        }
        active_spine.push((s, chapter_path));
    }

    Ok(PreparedEpub {
        archive,
        dir,
        toc,
        encryption,
        font_keys,
        metadata_language,
        rendition,
        fb2_epub_conversion,
        active_spine,
        package_bytes_read: bytes_read,
        css_state: Mutex::new(SharedCssState::new(*limits)),
        image_extractor: SharedImageExtractor::new(images_dir.to_path_buf(), *limits),
        inline_svg_writer: SharedInlineSvgWriter::new(images_dir.to_path_buf()),
    })
}

struct SlidingWindowState {
    next_dispatch: usize,
    next_commit: usize,
    ready: BTreeMap<usize, SpineTaskOutcome>,
    earliest_terminal: Option<usize>,
    stop_dispatch: bool,
    consumer_aborted: bool,
    active_workers: usize,
}

struct SlidingWindowShared {
    state: Mutex<SlidingWindowState>,
    cvar: Condvar,
}

struct WorkerDropGuard<'a> {
    shared: &'a SlidingWindowShared,
}

impl Drop for WorkerDropGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.shared.state.lock() {
            state.active_workers = state.active_workers.saturating_sub(1);
            if std::thread::panicking() {
                state.stop_dispatch = true;
            }
            self.shared.cvar.notify_all();
        }
    }
}

/// Runs all `prep.active_spine` items through a bounded sliding-window parallel pipeline
/// (`workers = 2..=4`, max in-flight + buffered spine items `<= SLIDING_WINDOW_SIZE`)
/// and commits outcomes strictly in order `0..total`, dropping each outcome before
/// admitting the next window slot.
fn run_sliding_window_spine<F>(
    epub_path: &Path,
    prep: &mut PreparedEpub,
    limits: &EpubLimits,
    mut consume: F,
) -> Result<(), NativeEpubOutcome>
where
    F: FnMut(SpineTaskOutcome, &HashMap<String, Vec<TocEntry>>) -> Result<(), NativeEpubOutcome>,
{
    let total = prep.active_spine.len();
    if total == 0 {
        return Ok(());
    }

    let desired_workers = if total < 4 {
        1
    } else {
        std::thread::available_parallelism()
            .map_or(2, |n| n.get())
            .clamp(2, 4)
            .min(total)
    };

    // Open lightweight worker archives up front; fall back cleanly to sequential if none open.
    let mut worker_archives = Vec::with_capacity(desired_workers.saturating_sub(1));
    if desired_workers >= 2 {
        for _ in 1..desired_workers {
            if let Some(w) = open_worker_archive(epub_path) {
                worker_archives.push(w);
            } else {
                break;
            }
        }
    }

    if worker_archives.is_empty() {
        let mut style_cache = SharedStyleCache::new();
        for (spine_item, chapter_path) in &prep.active_spine {
            let outcome = map_single_spine_item(
                spine_item,
                chapter_path,
                &mut prep.archive,
                &prep.dir,
                &prep.css_state,
                &prep.image_extractor,
                &prep.inline_svg_writer,
                &mut style_cache,
                limits,
                prep.package_bytes_read,
                prep.fb2_epub_conversion,
            );
            consume(outcome, &prep.toc.by_path)?;
        }
    } else {
        let num_workers = worker_archives.len() + 1;
        let shared = SlidingWindowShared {
            state: Mutex::new(SlidingWindowState {
                next_dispatch: 0,
                next_commit: 0,
                ready: BTreeMap::new(),
                earliest_terminal: None,
                stop_dispatch: false,
                consumer_aborted: false,
                active_workers: num_workers,
            }),
            cvar: Condvar::new(),
        };

        let dir_ref = &prep.dir;
        let spine_ref = &prep.active_spine;
        let css_ref = &prep.css_state;
        let img_ref = &prep.image_extractor;
        let svg_ref = &prep.inline_svg_writer;
        let toc_by_path_ref = &prep.toc.by_path;
        let package_bytes_read = prep.package_bytes_read;
        let fb2_epub_conversion = prep.fb2_epub_conversion;
        let shared_ref = &shared;
        let main_archive_ref = &mut prep.archive;

        let consumer_err = std::thread::scope(|scope| {
            let run_worker = |w_archive: &mut ZipArchive<BufReader<File>>| {
                let _guard = WorkerDropGuard { shared: shared_ref };
                let mut style_cache = SharedStyleCache::new();
                loop {
                    let idx = {
                        let mut state = shared_ref.state.lock().unwrap();
                        while !state.stop_dispatch
                            && !state.consumer_aborted
                            && state.next_dispatch < total
                            && (state.next_dispatch - state.next_commit) >= SLIDING_WINDOW_SIZE
                        {
                            state = shared_ref.cvar.wait(state).unwrap();
                        }
                        if state.stop_dispatch
                            || state.consumer_aborted
                            || state.next_dispatch >= total
                        {
                            break;
                        }
                        let claimed = state.next_dispatch;
                        state.next_dispatch += 1;
                        claimed
                    };

                    let (spine_item, chapter_path) = &spine_ref[idx];
                    let outcome = map_single_spine_item(
                        spine_item,
                        chapter_path,
                        w_archive,
                        dir_ref,
                        css_ref,
                        img_ref,
                        svg_ref,
                        &mut style_cache,
                        limits,
                        package_bytes_read,
                        fb2_epub_conversion,
                    );

                    let is_terminal = matches!(
                        outcome,
                        SpineTaskOutcome::Fallback(_)
                            | SpineTaskOutcome::Limit { .. }
                            | SpineTaskOutcome::Error(_)
                    );

                    let mut state = shared_ref.state.lock().unwrap();
                    if state.consumer_aborted {
                        drop(state);
                        drop(outcome);
                        break;
                    }
                    if let Some(earliest) = state.earliest_terminal {
                        if idx > earliest {
                            drop(state);
                            drop(outcome);
                            break;
                        }
                    }
                    if is_terminal {
                        state.earliest_terminal =
                            Some(state.earliest_terminal.map_or(idx, |prev| prev.min(idx)));
                        state.stop_dispatch = true;
                        let prune_from = idx + 1;
                        let _ = state.ready.split_off(&prune_from);
                    }
                    state.ready.insert(idx, outcome);
                    shared_ref.cvar.notify_all();
                }
            };

            let mut handles = Vec::with_capacity(num_workers);
            handles.push(scope.spawn(move || {
                run_worker(main_archive_ref);
            }));
            for mut w_archive in worker_archives {
                handles.push(scope.spawn(move || {
                    run_worker(&mut w_archive);
                }));
            }

            // Calling thread commits strictly in order 0..total as soon as each item is ready.
            let mut consumer_err: Option<NativeEpubOutcome> = None;
            let mut next_commit = 0_usize;
            while next_commit < total {
                let maybe_outcome = {
                    let mut state = shared_ref.state.lock().unwrap();
                    loop {
                        if let Some(outcome) = state.ready.remove(&next_commit) {
                            break Some(outcome);
                        }
                        if state.active_workers == 0 {
                            break None;
                        }
                        state = shared_ref.cvar.wait(state).unwrap();
                    }
                };
                let Some(outcome) = maybe_outcome else {
                    consumer_err =
                        Some(NativeEpubOutcome::Fallback("parallel spine worker aborted"));
                    let mut state = shared_ref.state.lock().unwrap();
                    state.consumer_aborted = true;
                    state.stop_dispatch = true;
                    state.ready.clear();
                    shared_ref.cvar.notify_all();
                    break;
                };
                if let Err(err) = consume(outcome, toc_by_path_ref) {
                    consumer_err = Some(err);
                    let mut state = shared_ref.state.lock().unwrap();
                    state.consumer_aborted = true;
                    state.stop_dispatch = true;
                    state.ready.clear();
                    shared_ref.cvar.notify_all();
                    break;
                }
                next_commit += 1;
                let mut state = shared_ref.state.lock().unwrap();
                state.next_commit = next_commit;
                shared_ref.cvar.notify_all();
            }

            for handle in handles {
                let _ = handle.join();
            }
            consumer_err
        });

        if let Some(err) = consumer_err {
            return Err(err);
        }
    }

    if prep.css_state.lock().unwrap().budget.was_limited {
        return Err(NativeEpubOutcome::Fallback("css expansion budget exceeded"));
    }
    Ok(())
}

struct CommitBudgets {
    committed_bytes: u64,
    total_generated_chars: u64,
    pending_font_faces: Vec<FontFaceSpec>,
    seen_font_specs: HashSet<FontDedupKey>,
}

impl CommitBudgets {
    fn new(package_bytes_read: u64) -> Self {
        Self {
            committed_bytes: package_bytes_read,
            total_generated_chars: 0,
            pending_font_faces: Vec::new(),
            seen_font_specs: HashSet::new(),
        }
    }

    fn charge_bytes(&mut self, delta: u64, limits: &EpubLimits) -> Result<(), NativeEpubOutcome> {
        if delta
            > limits
                .max_archive_read_bytes
                .saturating_sub(self.committed_bytes)
        {
            return Err(NativeEpubOutcome::Limit {
                kind: LimitKind::ActualAggregate,
                message: format!(
                    "Archive expands beyond {} bytes during this open",
                    limits.max_archive_read_bytes
                ),
            });
        }
        self.committed_bytes += delta;
        Ok(())
    }

    fn charge_generated(
        &mut self,
        used: usize,
        was_limited: bool,
        limits: &EpubLimits,
    ) -> Result<(), NativeEpubOutcome> {
        if was_limited
            || (used as u64)
                > limits
                    .max_html_generated_total_chars
                    .saturating_sub(self.total_generated_chars)
        {
            return Err(NativeEpubOutcome::Fallback(
                "html expansion budget exceeded",
            ));
        }
        self.total_generated_chars += used as u64;
        Ok(())
    }

    fn record_font_faces(&mut self, faces: Vec<FontFaceSpec>) {
        for face in faces {
            let (key, _, _) = FontDedupKey::from_face(&face);
            if self.seen_font_specs.insert(key) {
                self.pending_font_faces.push(face);
            }
        }
    }
}

pub(crate) fn parse_epub_native(
    epub_path: &Path,
    images_dir: &Path,
    limits: &EpubLimits,
) -> NativeEpubOutcome {
    let mut prep = match prepare_epub(epub_path, images_dir, limits) {
        Ok(p) => p,
        Err(err) => return err,
    };

    let mut acc = BookAccumulator::new();
    let mut budgets = CommitBudgets::new(prep.package_bytes_read);

    if let Err(err) = run_sliding_window_spine(
        epub_path,
        &mut prep,
        limits,
        |outcome, toc_by_path| match outcome {
            SpineTaskOutcome::Limit { kind, message } => {
                Err(NativeEpubOutcome::Limit { kind, message })
            }
            SpineTaskOutcome::Fallback(reason) => Err(NativeEpubOutcome::Fallback(reason)),
            SpineTaskOutcome::Error(message) => Err(NativeEpubOutcome::Error(message)),
            SpineTaskOutcome::Skipped { bytes_read: delta } => budgets.charge_bytes(delta, limits),
            SpineTaskOutcome::Mapped(mapped) => {
                budgets.charge_bytes(mapped.bytes_read, limits)?;
                budgets.charge_generated(
                    mapped.generated_chars_used,
                    mapped.was_aggregate_limited,
                    limits,
                )?;
                if mapped.has_body {
                    if !mapped.font_faces.is_empty() {
                        budgets.record_font_faces(mapped.font_faces);
                    }
                    for (id, note) in mapped.note_documents {
                        acc.exact_note_documents
                            .entry(format!("{}#{id}", mapped.chapter_path))
                            .or_insert(note);
                    }
                    if !mapped.elements.is_empty() {
                        acc.link_targets.extend(mapped.link_targets);
                        acc.note_targets.extend(mapped.note_targets);
                        if mapped.linear {
                            if mapped.is_standalone_svg {
                                acc.append_standalone_svg_linear(
                                    mapped.spine_ordinal,
                                    &mapped.chapter_path,
                                    mapped.elements,
                                    toc_by_path,
                                );
                            } else {
                                acc.append_linear_document(
                                    mapped.spine_ordinal,
                                    &mapped.chapter_path,
                                    mapped.elements,
                                    mapped.publisher_boxes,
                                    &mapped.anchors,
                                    toc_by_path,
                                );
                            }
                        } else {
                            acc.append_linked_document(
                                &mapped.chapter_path,
                                mapped.elements,
                                mapped.publisher_boxes,
                                &mapped.anchors,
                                toc_by_path,
                            );
                        }
                    }
                }
                Ok(())
            }
        },
    ) {
        return err;
    }

    let mut seen_fonts = HashSet::new();
    let mut fonts = Vec::new();
    if !budgets.pending_font_faces.is_empty() {
        if let Err(err) = extract_fonts(
            &budgets.pending_font_faces,
            &mut prep.archive,
            &prep.dir,
            images_dir,
            &prep.encryption,
            &prep.font_keys,
            limits,
            &mut budgets.committed_bytes,
            &mut seen_fonts,
            &mut fonts,
        ) {
            return archive_err_to_outcome(err);
        }
    }

    NativeEpubOutcome::Ready(Box::new(acc.finish(
        &prep.toc,
        fonts,
        prep.metadata_language,
        prep.rendition.page_progression,
    )))
}

fn write_epub_frb_streaming(
    epub_path: &Path,
    images_dir: &Path,
    dest_frb_path: &Path,
    source_metadata: &[u8],
    limits: &EpubLimits,
) -> Result<(), NativeEpubOutcome> {
    if source_metadata.len() > frb::MAX_METADATA_BYTES {
        return Err(NativeEpubOutcome::Error("container limit".to_owned()));
    }
    let mut prep = prepare_epub(epub_path, images_dir, limits)?;
    let mut acc = StreamingBookAccumulator::new(dest_frb_path)?;
    let mut budgets = CommitBudgets::new(prep.package_bytes_read);

    run_sliding_window_spine(
        epub_path,
        &mut prep,
        limits,
        |outcome, toc_by_path| match outcome {
            SpineTaskOutcome::Limit { kind, message } => {
                Err(NativeEpubOutcome::Limit { kind, message })
            }
            SpineTaskOutcome::Fallback(reason) => Err(NativeEpubOutcome::Fallback(reason)),
            SpineTaskOutcome::Error(message) => Err(NativeEpubOutcome::Error(message)),
            SpineTaskOutcome::Skipped { bytes_read: delta } => budgets.charge_bytes(delta, limits),
            SpineTaskOutcome::Mapped(mapped) => {
                budgets.charge_bytes(mapped.bytes_read, limits)?;
                budgets.charge_generated(
                    mapped.generated_chars_used,
                    mapped.was_aggregate_limited,
                    limits,
                )?;
                if mapped.has_body {
                    if !mapped.font_faces.is_empty() {
                        budgets.record_font_faces(mapped.font_faces);
                    }
                    for (id, note) in mapped.note_documents {
                        acc.exact_note_documents
                            .entry(format!("{}#{id}", mapped.chapter_path))
                            .or_insert(note);
                    }
                    if !mapped.elements.is_empty() {
                        acc.link_targets.extend(mapped.link_targets);
                        acc.note_targets.extend(mapped.note_targets);
                        if mapped.linear {
                            if mapped.is_standalone_svg {
                                acc.append_standalone_svg_linear(
                                    mapped.spine_ordinal,
                                    &mapped.chapter_path,
                                    mapped.elements,
                                    toc_by_path,
                                )?;
                            } else {
                                acc.append_linear_document(
                                    mapped.spine_ordinal,
                                    &mapped.chapter_path,
                                    mapped.elements,
                                    mapped.publisher_boxes,
                                    &mapped.anchors,
                                    toc_by_path,
                                )?;
                            }
                        } else {
                            acc.append_linked_document(
                                &mapped.chapter_path,
                                mapped.elements,
                                mapped.publisher_boxes,
                                &mapped.anchors,
                                toc_by_path,
                            );
                        }
                    }
                }
                Ok(())
            }
        },
    )?;

    let mut seen_fonts = HashSet::new();
    let mut fonts = Vec::new();
    if !budgets.pending_font_faces.is_empty() {
        extract_fonts(
            &budgets.pending_font_faces,
            &mut prep.archive,
            &prep.dir,
            images_dir,
            &prep.encryption,
            &prep.font_keys,
            limits,
            &mut budgets.committed_bytes,
            &mut seen_fonts,
            &mut fonts,
        )
        .map_err(archive_err_to_outcome)?;
    }

    acc.finish_and_write(&mut prep, fonts, dest_frb_path, source_metadata, limits)
}

// --------------------------------------------------------------------------- Unit Tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frb_model::write_book_frb;
    use zip::write::SimpleFileOptions;
    use zip::CompressionMethod;

    struct TestDir(PathBuf);
    impl TestDir {
        fn new(label: &str) -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "epub-frb-test-{label}-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
        fn path(&self, name: &str) -> PathBuf {
            self.0.join(name)
        }
    }
    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn write_test_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let file = File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
        for &(name, data) in entries {
            zip.start_file(name, opts).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    fn assert_no_spool_files(dir: &Path) {
        for entry in fs::read_dir(dir).unwrap() {
            let name = entry.unwrap().file_name().to_string_lossy().into_owned();
            assert!(
                !name.starts_with(".epub-frb-spool-"),
                "leaked temporary spool file: {name}"
            );
        }
    }

    fn decode_wire_status(wire: &[u8]) -> (u8, String) {
        assert!(wire.len() >= 5);
        assert_eq!(
            u32::from_le_bytes([wire[0], wire[1], wire[2], wire[3]]),
            EPBC_MAGIC
        );
        let status = wire[4];
        if status == STATUS_OK && wire.len() == 5 {
            return (status, String::new());
        }
        if status == STATUS_FALLBACK || status == STATUS_ERR {
            let len = u32::from_le_bytes([wire[5], wire[6], wire[7], wire[8]]) as usize;
            let msg = String::from_utf8_lossy(&wire[9..9 + len]).into_owned();
            return (status, msg);
        }
        (status, String::new())
    }

    #[test]
    fn streaming_write_epub_frb_matches_full_in_memory_bit_for_bit() {
        let dir = TestDir::new("streaming-parity");
        let epub = dir.path("corpus.epub");
        let fake_otf: Vec<u8> = b"OTTO".iter().copied().chain(0..120u8).collect();
        let fake_png: Vec<u8> = vec![137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4];

        let mut owned_entries: Vec<(String, Vec<u8>)> = Vec::new();
        owned_entries.push(("mimetype".into(), b"application/epub+zip".to_vec()));
        owned_entries.push((
            "META-INF/container.xml".into(),
            br#"<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"#
                .to_vec(),
        ));

        let chapter_count = 18;
        let mut manifest = String::from(
            r#"<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="css" href="styles/main.css" media-type="text/css"/>
<item id="font1" href="fonts/book.otf" media-type="font/otf"/>
<item id="pic" href="images/pic.png" media-type="image/png"/>
<item id="svg1" href="images/plate.svg" media-type="image/svg+xml"/>
<item id="svglink" href="images/extra.svg" media-type="image/svg+xml"/>
<item id="dtb" href="text/dtbook.xml" media-type="application/x-dtbook+xml"/>
<item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
<item id="linked" href="text/endnotes.xhtml" media-type="application/xhtml+xml"/>"#,
        );
        let mut spine = String::new();
        let mut nav = String::new();
        let mut nav_id = 1;

        for i in 1..=chapter_count {
            let _ = write!(
                &mut manifest,
                r#"<item id="ch{i}" href="text/ch{i}.xhtml" media-type="application/xhtml+xml"/>"#
            );
            let _ = write!(&mut spine, r#"<itemref idref="ch{i}"/>"#);
            if i == 4 {
                // Inject standalone SVG right after chapter 4.
                spine.push_str(r#"<itemref idref="svg1"/>"#);
                let _ = write!(
                    &mut nav,
                    r#"<navPoint id="n{nav_id}"><navLabel><text>Plate SVG</text></navLabel><content src="images/plate.svg"/></navPoint>"#
                );
                nav_id += 1;
            }
            if i == 7 {
                // Inject DTBook spine item after chapter 7.
                spine.push_str(r#"<itemref idref="dtb"/>"#);
                let _ = write!(
                    &mut nav,
                    r#"<navPoint id="n{nav_id}"><navLabel><text>DTBook Section</text></navLabel><content src="text/dtbook.xml#dtb1"/></navPoint>"#
                );
                nav_id += 1;
            }
            if i % 6 == 0 {
                // Unlisted in TOC: merges into previous chapter!
                continue;
            }
            if i % 5 == 0 {
                // Multi-TOC split inside a single spine XHTML file (with unlisted preamble).
                let _ = write!(
                    &mut nav,
                    r#"<navPoint id="n{nav_id}"><navLabel><text>Split {i} A</text></navLabel><content src="text/ch{i}.xhtml#secA"/></navPoint>"#
                );
                nav_id += 1;
                let _ = write!(
                    &mut nav,
                    r#"<navPoint id="n{nav_id}"><navLabel><text>Split {i} B</text></navLabel><content src="text/ch{i}.xhtml#secB"/></navPoint>"#
                );
                nav_id += 1;
            } else {
                let _ = write!(
                    &mut nav,
                    r#"<navPoint id="n{nav_id}"><navLabel><text>Chapter {i}</text></navLabel><content src="text/ch{i}.xhtml"/></navPoint>"#
                );
                nav_id += 1;
            }
        }
        // Linear notes chapter (unlisted in TOC -> merges into chapter 18!) + non-linear endnotes & SVG.
        spine.push_str(r#"<itemref idref="notes"/>"#);
        spine.push_str(r#"<itemref idref="linked" linear="no"/>"#);
        spine.push_str(r#"<itemref idref="svglink" linear="no"/>"#);

        // Omit dc:language to exercise streaming LanguageSampler parity too!
        let opf = format!(
            r#"<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0" unique-identifier="uid">
  <metadata><dc:title>Streaming Parity</dc:title></metadata>
  <manifest>{manifest}</manifest>
  <spine toc="ncx">{spine}</spine>
</package>"#
        );
        let ncx = format!(
            r#"<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>{nav}</navMap></ncx>"#
        );
        let css = r#"
@font-face { font-family: "BookFace"; src: url(../fonts/book.otf); }
p { text-align: justify; line-height: 1.4; hyphens: auto; font-family: "BookFace", serif; }
.drop::first-letter { float: left; font-size: 2.2em; }
.box { margin-left: 1em; border-left: 2px solid #224466; padding-left: 0.5em; }
"#;
        owned_entries.push(("OEBPS/content.opf".into(), opf.into_bytes()));
        owned_entries.push(("OEBPS/toc.ncx".into(), ncx.into_bytes()));
        owned_entries.push(("OEBPS/styles/main.css".into(), css.as_bytes().to_vec()));
        owned_entries.push(("OEBPS/fonts/book.otf".into(), fake_otf));
        owned_entries.push(("OEBPS/images/pic.png".into(), fake_png));
        owned_entries.push((
            "OEBPS/images/plate.svg".into(),
            br#"<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><title>  Plate   One </title><circle cx="50" cy="50" r="40"/></svg>"#.to_vec(),
        ));
        owned_entries.push((
            "OEBPS/images/extra.svg".into(),
            br#"<svg xmlns="http://www.w3.org/2000/svg" aria-label="Linked Diagram"><rect width="10" height="10"/></svg>"#.to_vec(),
        ));
        owned_entries.push((
            "OEBPS/text/dtbook.xml".into(),
            r#"<?xml version="1.0" encoding="UTF-8"?>
<dtbook xmlns="http://www.daisy.org/z3986/2005/dtbook/" version="2005-2">
  <head><link rel="stylesheet" type="text/css" href="../styles/main.css"/></head>
  <book><bodymatter><level1 id="dtb1">
    <levelhd>DTBook Title</levelhd>
    <p>Привет мир из DTBook раздела с русским текстом.</p>
  </level1></bodymatter></book>
</dtbook>"#
                .as_bytes()
                .to_vec(),
        ));

        for i in 1..=chapter_count {
            let body = if i % 5 == 0 {
                format!(
                    r##"<p>Преамбула перед разделом {i} на русском языке для определения языка.</p>
<h2 id="secA">Раздел {i} A</h2>
<p class="drop">Текст раздела A со ссылкой на обычную сноску <a href="notes.xhtml#n1" epub:type="noteref">[1]</a> и <a href="endnotes.xhtml#en1" epub:type="noteref">[e1]</a>.</p>
<h2 id="secB">Раздел {i} B</h2>
<div class="box"><p id="anchor{i}">Второй сегмент главы {i} и <a href="ch1.xhtml">назад</a>.</p></div>"##
                )
            } else {
                format!(
                    r##"<h1>Глава {i}</h1>
<p class="drop">Привет мир, это достаточно длинный текст главы {i} на русском языке с <a href="notes.xhtml#n2" epub:type="noteref">[2]</a> и <a href="#exact{i}" epub:type="noteref">[* {i}]</a>.</p>
<p><img src="../images/pic.png" alt="Рис {i}"/></p>
<aside id="exact{i}" epub:type="footnote"><p>Семантическая сноска {i}.</p></aside>"##
                )
            };
            let html = format!(
                r#"<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><link rel="stylesheet" type="text/css" href="../styles/main.css"/></head>
<body>{body}</body></html>"#
            );
            owned_entries.push((format!("OEBPS/text/ch{i}.xhtml"), html.into_bytes()));
        }

        // Non-exact footnotes inside a linear spine item (which merges into chapter 18!).
        owned_entries.push((
            "OEBPS/text/notes.xhtml".into(),
            br#"<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><link rel="stylesheet" type="text/css" href="../styles/main.css"/></head>
<body>
  <div class="box"><p id="n1">[1] Non-exact footnote one inside merged linear chapter.</p><p>Second paragraph of note 1.</p></div>
  <p id="n2">[2] Non-exact footnote two.</p>
  <p id="n3">[3] Trailing unreferenced note.</p>
</body></html>"#
                .to_vec(),
        ));
        owned_entries.push((
            "OEBPS/text/endnotes.xhtml".into(),
            br#"<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<body><p id="en1">[e1] Linked non-exact endnote.</p><p id="en2">[e2] Other endnote.</p></body></html>"#
                .to_vec(),
        ));

        let entry_refs: Vec<(&str, &[u8])> = owned_entries
            .iter()
            .map(|(k, v)| (k.as_str(), v.as_slice()))
            .collect();
        write_test_zip(&epub, &entry_refs);

        let limits = EpubLimits::default();
        let meta = b"parity-source-metadata-v8";
        let shared_images = dir.path("images");
        fs::create_dir_all(&shared_images).unwrap();

        let reference_frb = dir.path("reference.frb");
        let parsed = match parse_epub_native(&epub, &shared_images, &limits) {
            NativeEpubOutcome::Ready(content) => content,
            other => panic!("expected Ready from parse_epub_native, got {other:?}"),
        };
        assert_eq!(parsed.language.as_deref(), Some("ru"));
        assert!(parsed.notes.contains_key("OEBPS/text/notes.xhtml#n1"));
        assert!(parsed.notes.contains_key("OEBPS/text/notes.xhtml#n2"));
        assert!(parsed.notes.contains_key("OEBPS/text/endnotes.xhtml#en1"));
        assert!(parsed
            .linked_documents
            .contains_key("OEBPS/images/extra.svg"));
        assert!(parsed.chapters.iter().any(|c| {
            c.title.as_deref() == Some("Plate SVG")
                && matches!(
                    c.elements.first(),
                    Some(FrbContentElement::Image(img)) if img.alt_text.as_deref() == Some("Plate One")
                )
        }));
        assert!(parsed.chapters.iter().any(|c| {
            c.title.as_deref() == Some("DTBook Section")
                && c.elements.iter().any(|el| matches!(
                    el,
                    FrbContentElement::Heading { styled_text, .. } if styled_text.text == "DTBook Title"
                ))
        }));
        write_book_frb(&parsed, &reference_frb, meta, frb::MAX_FILE_BYTES).unwrap();

        let streaming_dir = dir.path("stream-out");
        fs::create_dir_all(&streaming_dir).unwrap();
        let streaming_frb = streaming_dir.join("streaming.frb");
        let wire = write_epub_frb_wire(&epub, &shared_images, &streaming_frb, meta, &limits);
        assert_eq!(decode_wire_status(&wire), (STATUS_OK, String::new()));
        assert_no_spool_files(&streaming_dir);

        // Entire FRB v2 container must match 100% bit-for-bit (header, index, globals, chapters).
        let ref_bytes = fs::read(&reference_frb).unwrap();
        let stream_bytes = fs::read(&streaming_frb).unwrap();
        assert_eq!(
            ref_bytes, stream_bytes,
            "streaming FRB must match non-streaming write_book_frb bit-for-bit"
        );
    }

    #[test]
    fn woff2_decodes_natively_in_rust_and_skips_corrupt_face() {
        let dir = TestDir::new("woff2-native");
        let epub = dir.path("woff2.epub");
        let valid_woff2 = woff::test_minimal_woff2(b"head", &[0u8; 54]);
        let corrupt_woff2 = b"wOF2corrupt-brotli-font-payload";

        write_test_zip(
            &epub,
            &[
                ("mimetype", b"application/epub+zip"),
                (
                    "META-INF/container.xml",
                    br#"<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"#,
                ),
                (
                    "content.opf",
                    br#"<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0"><metadata><dc:title>Woff2</dc:title></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="css" href="s.css" media-type="text/css"/><item id="f" href="f.woff2" media-type="font/woff2"/><item id="bad" href="bad.woff2" media-type="font/woff2"/></manifest><spine><itemref idref="c1"/></spine></package>"#,
                ),
                (
                    "s.css",
                    br#"@font-face { font-family: "TestWoff2"; src: url(f.woff2); } @font-face { font-family: "BrokenWoff2"; src: url(bad.woff2); } p { font-family: "TestWoff2", "BrokenWoff2"; }"#,
                ),
                (
                    "c1.xhtml",
                    br#"<html xmlns="http://www.w3.org/1999/xhtml"><head><link rel="stylesheet" href="s.css"/></head><body><p>Hello WOFF2</p></body></html>"#,
                ),
                ("f.woff2", &valid_woff2),
                ("bad.woff2", corrupt_woff2),
            ],
        );

        let limits = EpubLimits::default();
        let img_dir = dir.path("img-native");
        fs::create_dir_all(&img_dir).unwrap();
        let out_dir = dir.path("out");
        fs::create_dir_all(&out_dir).unwrap();

        let parsed = match parse_epub_native(&epub, &img_dir, &limits) {
            NativeEpubOutcome::Ready(content) => content,
            other => panic!("expected Ready from parse_epub_native, got {other:?}"),
        };
        assert_eq!(parsed.fonts.len(), 1);
        assert_eq!(parsed.fonts[0].family, "testwoff2");
        let extracted = fs::read(&parsed.fonts[0].path).unwrap();
        assert!(looks_like_font(&extracted));
        assert_eq!(&extracted[..4], b"\x00\x01\x00\x00");

        let dest = out_dir.join("woff2.frb");
        let wire_ok = write_epub_frb_wire(&epub, &img_dir, &dest, b"meta", &limits);
        assert_eq!(decode_wire_status(&wire_ok), (STATUS_OK, String::new()));
        assert!(dest.is_file());
        assert_no_spool_files(&out_dir);
        let reader = frb::Reader::open(&dest, b"meta").unwrap();
        assert_eq!(reader.index().len(), 5);
    }

    #[test]
    fn shared_inline_svg_writer_names_deduplicates_and_writes_atomically() {
        let dir = TestDir::new("inline-svg");
        let writer = SharedInlineSvgWriter::new(dir.0.clone());
        let svg = r#"<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><circle cx="5" cy="5" r="4"/></svg>"#;
        let p1 = writer.write(svg).unwrap();
        let p2 = writer.write(svg).unwrap();
        assert_eq!(p1, p2);
        let file_name = Path::new(&p1).file_name().unwrap().to_str().unwrap();
        assert!(file_name.starts_with("svg_inline_"));
        assert!(file_name.ends_with("_inline.svg"));
        assert_eq!(
            file_name.len(),
            "svg_inline_".len() + 32 + "_inline.svg".len()
        );
        assert_eq!(fs::read_to_string(&p1).unwrap(), svg);
    }

    #[test]
    fn parse_svg_alt_text_matches_epub_parser_semantics() {
        assert_eq!(
            parse_svg_alt_text(
                br#"<svg xmlns="http://www.w3.org/2000/svg" aria-label="  Map &amp; Legend "><title>Ignored</title></svg>"#
            ),
            Some("Map & Legend".to_owned())
        );
        assert_eq!(
            parse_svg_alt_text(
                br#"<svg xmlns="http://www.w3.org/2000/svg" aria-label="   "><title>  Line 1
  Line 2 </title></svg>"#
            ),
            Some("Line 1 Line 2".to_owned())
        );
        assert_eq!(
            parse_svg_alt_text(
                br#"<svg xmlns="http://www.w3.org/2000/svg"><title/><title>Second</title></svg>"#
            ),
            None
        );
    }

    #[test]
    fn sha1_matches_fips_vectors_and_idpf_key() {
        let hex = |bytes: &[u8]| -> String {
            let mut s = String::with_capacity(bytes.len() * 2);
            for b in bytes {
                let _ = write!(&mut s, "{b:02x}");
            }
            s
        };
        assert_eq!(hex(&sha1(b"")), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(
            hex(&sha1(b"abc")),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            hex(&sha1(
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
            )),
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1"
        );
        // IDPF strips all ASCII whitespace before SHA-1.
        assert_eq!(idpf_key(" a b\tc\r\n "), sha1(b"abc"));
    }

    #[test]
    fn adobe_key_and_deobfuscation_roundtrip() {
        let key = adobe_key("urn:uuid:00112233-4455-6677-8899-aabbccddeeff").unwrap();
        assert_eq!(
            key,
            [
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
                0xee, 0xff
            ]
        );
        assert!(adobe_key("not-a-uuid").is_none());
        let plain = b"OTTOpayload1234567890";
        let mangled = deobfuscate_copy(plain, &key, ADOBE_PREFIX);
        assert_ne!(mangled, plain);
        assert_eq!(deobfuscate_copy(&mangled, &key, ADOBE_PREFIX), plain);
    }

    #[test]
    fn path_url_and_link_key_resolution_matches_kotlin() {
        assert_eq!(
            resolve_path("OEBPS/text", "../images/a+b%20c.png"),
            "OEBPS/images/a+b c.png"
        );
        assert_eq!(
            resolve_link_key("https://example.com", "OEBPS/ch1.xhtml", "OEBPS"),
            None
        );
        assert_eq!(
            resolve_link_key("#note%201", "OEBPS/ch1.xhtml", "OEBPS"),
            Some("OEBPS/ch1.xhtml#note 1".to_owned())
        );
        assert_eq!(
            resolve_link_key("ch2.xhtml", "OEBPS/ch1.xhtml", "OEBPS"),
            Some("OEBPS/ch2.xhtml".to_owned())
        );
        assert_eq!(
            resolve_link_key("pic.png", "OEBPS/ch1.xhtml", "OEBPS"),
            None
        );
        assert!(is_safe_archive_path("OEBPS/text/ch1.xhtml"));
        assert!(!is_safe_archive_path("../evil.xhtml"));
        assert!(!is_safe_archive_path("C:/evil.xhtml"));
        assert!(!is_safe_archive_path("/abs.xhtml"));
    }

    #[test]
    fn css_imports_and_media_queries_match_epub_parser() {
        assert!(screen_media_applies(""));
        assert!(screen_media_applies("screen"));
        assert!(screen_media_applies("all and (min-width: 100px)"));
        assert!(!screen_media_applies("print"));
        assert!(screen_media_applies("not print"));
        assert!(!screen_media_applies("not screen"));
        assert!(screen_media_applies("print, only screen"));

        let css = r#"
            @charset "UTF-8";
            /* @import "commented.css"; */
            @import url("base.css");
            @import 'theme.css' screen;
            @import "print.css" print;
            body { color: black; }
            @import "after-rule.css";
        "#;
        assert_eq!(
            top_level_css_imports(css),
            vec![
                ("base.css".to_owned(), "".to_owned()),
                ("theme.css".to_owned(), "screen".to_owned()),
                ("print.css".to_owned(), "print".to_owned()),
            ]
        );
        assert_eq!(
            css_import_paths(css, "OEBPS/styles"),
            vec![
                "OEBPS/styles/base.css".to_owned(),
                "OEBPS/styles/theme.css".to_owned(),
            ]
        );
    }

    #[test]
    fn nested_chapter_title_composition_matches_kotlin() {
        assert_eq!(
            compose_nested_chapter_title("Часть 2. Синие небеса", "Глава 3"),
            "Часть 2 Глава 3"
        );
        assert_eq!(
            compose_nested_chapter_title("Part IV: The Return", "Chapter 1\nSub"),
            "Part IV Chapter 1\nSub"
        );
        assert_eq!(
            compose_nested_chapter_title("Prologue. Early days", "Scene 1"),
            "Prologue Scene 1"
        );
    }

    #[test]
    fn resource_cache_file_name_matches_kotlin() {
        let img = image_file_name("OEBPS/images/pic.png");
        assert!(img.starts_with("epub_"));
        assert!(img.ends_with("_pic.png"));
        assert_eq!(img.len(), "epub_".len() + 32 + "_pic.png".len());

        let font = font_file_name("OEBPS/fonts/Book Regular.otf");
        assert!(font.starts_with("font_v1_"));
        assert!(font.ends_with("_Book_Regular.otf"));
    }

    #[test]
    fn language_normalization_and_detection_matches_kotlin() {
        assert_eq!(normalize_language_tag(Some("ru_RU")), Some("ru-ru".into()));
        assert_eq!(normalize_language_tag(Some("ua")), Some("uk".into()));
        assert_eq!(normalize_language_tag(Some("bad tag!")), None);

        let ru = FrbChapter {
            title: None,
            elements: vec![FrbContentElement::Paragraph {
                text: crate::frb_model::FrbAnnotatedString::plain(
                    "Привет мир, это достаточно длинный текст на русском языке для определения.",
                ),
                style: crate::frb_model::FrbParagraphStyle::Normal,
                block: None,
            }],
            depth: 0,
            publisher_boxes: Vec::new(),
        };
        assert_eq!(detect_language_from_chapters(&[ru]), Some("ru".to_owned()));
    }
}
