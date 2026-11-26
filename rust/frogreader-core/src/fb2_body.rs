//! Native Rust FB2 body parser, bounded CSS compatibility engine, image extractor,
//! and direct `.frb` emission (M4.2 / Wave 10).
//!
//! Mirrors the exact semantics of FrogReader's FB2 parser and `Fb2Stylesheet`
//! compatibility profile while running natively over `Fb2Sanitizer` + `quick-xml`.

use crate::frb_model::{
    self, color_argb_to_compose_ulong, FrbAnnotatedString, FrbBlockAlign, FrbBlockStyle,
    FrbBookContent, FrbBookTextDirection, FrbChapter, FrbContentElement, FrbImageElement,
    FrbNoteDocument, FrbPageProgression, FrbParagraphAnnotation, FrbParagraphStyle,
    FrbPublisherBorderSide, FrbPublisherBorderStyle, FrbPublisherBoxStyle, FrbSpanStyle,
    FrbTableCell, FrbTableElement, FrbTableRow, FrbTextRange, FrbTextUnit,
    COMPOSE_COLOR_UNSPECIFIED,
};
use crate::{css_color, css_computed, css_computed_model, css_shorthand};
use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};

pub(crate) const FB2_CONTENT_MAGIC: u32 = 0x4332_4246; // FB2C
pub(crate) const FB2_STATUS_OK_FULL: u8 = 0;
pub(crate) const FB2_STATUS_OK_PREVIEW: u8 = 1;
pub(crate) const FB2_STATUS_ERR_LIMIT: u8 = 2;
pub(crate) const FB2_STATUS_ERR_IO: u8 = 3;

pub(crate) const LIMIT_KIND_ENTRY_SIZE: u8 = 3; // ResourceLimitKind.ENTRY_SIZE
pub(crate) const LIMIT_KIND_STRUCTURAL_DEPTH: u8 = 6; // ResourceLimitKind.STRUCTURAL_DEPTH

const INLINE_IMAGE_TAG: &str = "inlineImage";
const INLINE_IMAGE_ALT_TAG: &str = "inlineImageAlt";
const INLINE_CONTENT_TAG: &str = "androidx.compose.foundation.text.inlineContent";
const INLINE_IMAGE_CHAR: char = '\u{FFFC}';
const FOOTNOTE_TAG: &str = "footnote";
const LINK_TAG: &str = "internalLink";
const EXTERNAL_LINK_TAG: &str = "externalLink";
const BIDI_TAG: &str = "frogreader:bidi";

const MAX_CONTAINER_DEPTH: usize = 256;
const FILE_READ_CHUNK_BYTES: usize = 64 * 1024;
const PREVIEW_BUFFER_ELEMENTS: u64 = 32;

// -----------------------------------------------------------------------------
// Limits & Errors
// -----------------------------------------------------------------------------

#[derive(Clone, Copy, Debug)]
pub(crate) struct Fb2Limits {
    pub(crate) max_fb2_bytes: u64,
    pub(crate) max_fb2_structural_depth: usize,
    pub(crate) max_fb2_stylesheet_count: usize,
    pub(crate) max_stylesheet_bytes: usize,
    pub(crate) max_fb2_stylesheet_aggregate_bytes: u64,
    pub(crate) max_fb2_binary_count: usize,
    pub(crate) max_fb2_binary_bytes: usize,
    pub(crate) max_fb2_binary_aggregate_bytes: u64,
}

impl Default for Fb2Limits {
    fn default() -> Self {
        Self {
            max_fb2_bytes: 256 * 1024 * 1024,
            max_fb2_structural_depth: 256,
            max_fb2_stylesheet_count: 256,
            max_stylesheet_bytes: 8 * 1024 * 1024,
            max_fb2_stylesheet_aggregate_bytes: 32 * 1024 * 1024,
            max_fb2_binary_count: 4_096,
            max_fb2_binary_bytes: 64 * 1024 * 1024,
            max_fb2_binary_aggregate_bytes: 256 * 1024 * 1024,
        }
    }
}

impl Fb2Limits {
    pub(crate) fn from_slice(raw: &[i64]) -> Option<Self> {
        if raw.len() != 8 || raw.iter().any(|&v| v <= 0) {
            return None;
        }
        Some(Self {
            max_fb2_bytes: raw[0] as u64,
            max_fb2_structural_depth: usize::try_from(raw[1]).ok()?,
            max_fb2_stylesheet_count: usize::try_from(raw[2]).ok()?,
            max_stylesheet_bytes: usize::try_from(raw[3]).ok()?,
            max_fb2_stylesheet_aggregate_bytes: raw[4] as u64,
            max_fb2_binary_count: usize::try_from(raw[5]).ok()?,
            max_fb2_binary_bytes: usize::try_from(raw[6]).ok()?,
            max_fb2_binary_aggregate_bytes: raw[7] as u64,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Fb2ParseError {
    Limit { kind: u8, message: String },
    Io(String),
}

// -----------------------------------------------------------------------------
// Internal Semantic Model (mirrors BookContent / ContentElement / BlockStyle)
// -----------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Align {
    Start,
    Center,
    End,
    Justify,
    Left,
    Right,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TextDir {
    Ltr,
    Rtl,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ParaStyle {
    Normal,
    Quote,
    Poem,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BorderStyleKind {
    Solid,
    Dashed,
    Dotted,
    Double,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct BorderSide {
    pub(crate) width_em: f32,
    pub(crate) color_argb: i32,
    pub(crate) style: BorderStyleKind,
    pub(crate) uses_current_color: bool,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct BoxBorders {
    pub(crate) border_top: Option<BorderSide>,
    pub(crate) border_right: Option<BorderSide>,
    pub(crate) border_bottom: Option<BorderSide>,
    pub(crate) border_left: Option<BorderSide>,
}

impl BoxBorders {
    fn is_empty(&self) -> bool {
        self.border_top.is_none()
            && self.border_right.is_none()
            && self.border_bottom.is_none()
            && self.border_left.is_none()
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct BlockStyle {
    pub(crate) align: Option<Align>,
    pub(crate) italic: Option<bool>,
    pub(crate) bold: Option<bool>,
    pub(crate) font_scale: Option<f32>,
    pub(crate) indent_start_frac: f32,
    pub(crate) indent_start_em: f32,
    pub(crate) indent_end_frac: f32,
    pub(crate) indent_end_em: f32,
    pub(crate) indent_left_frac: f32,
    pub(crate) indent_left_em: f32,
    pub(crate) indent_right_frac: f32,
    pub(crate) indent_right_em: f32,
    pub(crate) first_line_indent: Option<bool>,
    pub(crate) first_line_indent_em: Option<f32>,
    pub(crate) space_before_em: f32,
    pub(crate) space_after_em: f32,
    pub(crate) space_before_specified: bool,
    pub(crate) space_after_specified: bool,
    pub(crate) font_family: Option<String>,
    pub(crate) line_height_mult: Option<f32>,
    pub(crate) page_break_before: bool,
    pub(crate) language: Option<String>,
    pub(crate) direction: Option<TextDir>,
    pub(crate) foreground_color_argb: Option<i32>,
    pub(crate) background_color_argb: Option<i32>,
}

impl BlockStyle {
    fn is_default(&self) -> bool {
        self == &Self::default()
    }

    fn epigraph() -> Self {
        Self {
            italic: Some(true),
            indent_start_frac: 0.25,
            first_line_indent: Some(false),
            ..Self::default()
        }
    }

    fn text_author() -> Self {
        Self {
            italic: Some(true),
            align: Some(Align::End),
            first_line_indent: Some(false),
            ..Self::default()
        }
    }

    fn centered() -> Self {
        Self {
            align: Some(Align::Center),
            ..Self::default()
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct SpanStyle {
    pub(crate) color_argb: Option<i32>,
    pub(crate) font_size_em: Option<f32>,
    pub(crate) weight: Option<i32>,
    pub(crate) italic: Option<bool>,
    pub(crate) family: Option<&'static str>, // "serif", "sans", "mono", "cursive"
    pub(crate) baseline_shift: Option<f32>,
    pub(crate) locales: Option<Vec<String>>,
    pub(crate) background_argb: Option<i32>,
    pub(crate) decoration: Option<i32>, // 0=None, 1=Underline, 2=LineThrough, 3=Both
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum TextAnnotation {
    String { tag: String, value: String },
    Span(SpanStyle),
    ParagraphAlign(i32), // Compose TextAlign code: 0..=6
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct TextRange {
    pub(crate) start: i32,
    pub(crate) end: i32,
    pub(crate) item: TextAnnotation,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(crate) struct RichText {
    pub(crate) text: String,
    pub(crate) utf16_len: i32,
    pub(crate) ranges: Vec<TextRange>,
}

impl RichText {
    fn plain(text: String) -> Self {
        let utf16_len = utf16_length(&text);
        Self {
            text,
            utf16_len,
            ranges: Vec::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct TableCell {
    pub(crate) text: RichText,
    pub(crate) col_span: i32,
    pub(crate) row_span: i32,
    pub(crate) align: Option<Align>,
    pub(crate) header: bool,
    pub(crate) block: Option<BlockStyle>,
    pub(crate) publisher_box: Option<BoxBorders>,
    pub(crate) publisher_border_specified: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct TableRow {
    pub(crate) cells: Vec<TableCell>,
    pub(crate) is_header: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Element {
    Paragraph {
        text: RichText,
        style: ParaStyle,
        block: Option<BlockStyle>,
    },
    Heading {
        styled_text: RichText,
        level: i32,
        block: Option<BlockStyle>,
    },
    Image {
        path: String,
        alt_text: Option<String>,
    },
    Spacer {
        height_em: f32,
    },
    Table {
        rows: Vec<TableRow>,
        block: Option<BlockStyle>,
        publisher_box: Option<BoxBorders>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ParsedChapter {
    pub(crate) title: Option<String>,
    pub(crate) elements: Vec<Element>,
    pub(crate) depth: i32,
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ParsedNote {
    pub(crate) elements: Vec<Element>,
}

#[derive(Clone, Debug)]
pub(crate) struct BinaryAsset {
    pub(crate) bytes: Vec<u8>,
    pub(crate) content_type: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct ParsedBook {
    pub(crate) chapters: Vec<ParsedChapter>,
    pub(crate) notes: Vec<(String, ParsedNote)>,
    pub(crate) link_targets: Vec<(String, (i32, i32))>,
    pub(crate) language: Option<String>,
}

/// Intermediate result before image extraction so progressive preview can extract
/// only opening images before `onPreview` and extract the remaining images afterward.
#[derive(Debug)]
pub(crate) struct UnresolvedFb2Book {
    pub(crate) chapters: Vec<ParsedChapter>,
    pub(crate) notes: Vec<(String, ParsedNote)>,
    pub(crate) link_targets: Vec<(String, (i32, i32))>,
    pub(crate) declared_language: Option<String>,
    pub(crate) binaries: Vec<(String, BinaryAsset)>,
    pub(crate) has_main_sections: bool,
}

impl UnresolvedFb2Book {
    pub(crate) fn find_preview_end_chapter(&self, preview_threshold: u64) -> Option<usize> {
        if !self.has_main_sections || self.chapters.len() < 2 {
            return None;
        }
        let total: u64 = self.chapters.iter().map(|c| c.elements.len() as u64).sum();
        if preview_threshold >= total {
            return None;
        }
        let mut cumulative = 0_u64;
        for (idx, chapter) in self.chapters.iter().enumerate() {
            cumulative = cumulative.saturating_add(chapter.elements.len() as u64);
            if cumulative >= preview_threshold {
                return (cumulative < total && idx + 1 < self.chapters.len()).then_some(idx);
            }
        }
        None
    }

    pub(crate) fn resolve_prefix(
        &self,
        end_chapter_inclusive: usize,
        images_dir: &Path,
        written_paths: &mut HashMap<String, String>,
    ) -> Result<ParsedBook, Fb2ParseError> {
        let prefix_chapters = &self.chapters[..=end_chapter_inclusive];
        let mut needed_ids = HashSet::new();
        for ch in prefix_chapters {
            collect_referenced_images_in_elements(&ch.elements, &mut needed_ids);
        }
        extract_selected_binaries(&self.binaries, Some(&needed_ids), images_dir, written_paths)?;
        let resolved_chapters: Vec<ParsedChapter> = prefix_chapters
            .iter()
            .map(|ch| ParsedChapter {
                title: ch.title.clone(),
                elements: resolve_elements(&ch.elements, written_paths),
                depth: ch.depth,
            })
            .collect();
        let preview_links: Vec<(String, (i32, i32))> = self
            .link_targets
            .iter()
            .filter(|(_, (ch_idx, _))| (*ch_idx as usize) <= end_chapter_inclusive)
            .cloned()
            .collect();
        let language = normalize_language_tag(self.declared_language.as_deref())
            .or_else(|| detect_language_from_chapters(&self.chapters).map(str::to_owned));
        Ok(ParsedBook {
            chapters: resolved_chapters,
            notes: Vec::new(),
            link_targets: preview_links,
            language,
        })
    }

    pub(crate) fn resolve_all(
        self,
        images_dir: &Path,
        written_paths: &mut HashMap<String, String>,
    ) -> Result<ParsedBook, Fb2ParseError> {
        extract_selected_binaries(&self.binaries, None, images_dir, written_paths)?;
        let resolved_chapters: Vec<ParsedChapter> = self
            .chapters
            .into_iter()
            .map(|ch| ParsedChapter {
                title: ch.title,
                elements: resolve_elements(&ch.elements, written_paths),
                depth: ch.depth,
            })
            .collect();
        let resolved_notes: Vec<(String, ParsedNote)> = self
            .notes
            .into_iter()
            .filter_map(|(k, note)| {
                let elements = resolve_elements(&note.elements, written_paths);
                if elements.is_empty() {
                    None
                } else {
                    Some((k, ParsedNote { elements }))
                }
            })
            .collect();
        let language = normalize_language_tag(self.declared_language.as_deref())
            .or_else(|| detect_language_from_chapters(&resolved_chapters).map(str::to_owned));
        Ok(ParsedBook {
            chapters: resolved_chapters,
            notes: resolved_notes,
            link_targets: self.link_targets,
            language,
        })
    }
}

// -----------------------------------------------------------------------------
// InlineTextBuilder (100% parity with InlineTextBuilder.kt)
// -----------------------------------------------------------------------------

struct OpenRange {
    range_index: usize,
}

struct InlineBuilder {
    text: String,
    utf16_len: i32,
    ranges: Vec<TextRange>,
    open_stack: Vec<OpenRange>,
    last_was_space: bool,
    has_visible_content: bool,
    image_refs: Vec<String>,
}

impl InlineBuilder {
    fn new() -> Self {
        Self {
            text: String::new(),
            utf16_len: 0,
            ranges: Vec::new(),
            open_stack: Vec::new(),
            last_was_space: true,
            has_visible_content: false,
            image_refs: Vec::new(),
        }
    }

    fn text(&mut self, raw: &str) {
        if raw.is_empty() {
            return;
        }
        let mut last_char: Option<char> = None;
        let mut in_ws = false;
        let mut wrote_any = false;
        for ch in raw.chars() {
            if matches!(ch, ' ' | '\t' | '\r' | '\n' | '\x0C') {
                if !in_ws {
                    in_ws = true;
                    if !self.last_was_space || wrote_any {
                        self.text.push(' ');
                        self.utf16_len += 1;
                        last_char = Some(' ');
                        wrote_any = true;
                    }
                }
            } else {
                in_ws = false;
                self.text.push(ch);
                self.utf16_len += ch.len_utf16() as i32;
                last_char = Some(ch);
                wrote_any = true;
                if !is_kotlin_whitespace(ch) {
                    self.has_visible_content = true;
                }
            }
        }
        if let Some(last) = last_char {
            self.last_was_space = last == ' ';
        }
    }

    fn line_break(&mut self) {
        self.text.push('\n');
        self.utf16_len += 1;
        self.last_was_space = true;
    }

    fn inline_image(&mut self, ref_str: String, alt_text: Option<&str>) {
        let alt = alt_text.map(str::trim).filter(|s| !s.is_empty());
        let start = self.utf16_len;
        let end = start + 1; // '\u{FFFC}'.len_utf16() == 1
        if let Some(alt_val) = alt {
            self.ranges.push(TextRange {
                start,
                end,
                item: TextAnnotation::String {
                    tag: INLINE_IMAGE_ALT_TAG.to_owned(),
                    value: alt_val.to_owned(),
                },
            });
        }
        self.ranges.push(TextRange {
            start,
            end,
            item: TextAnnotation::String {
                tag: INLINE_IMAGE_TAG.to_owned(),
                value: ref_str.clone(),
            },
        });
        self.ranges.push(TextRange {
            start,
            end,
            item: TextAnnotation::String {
                tag: INLINE_CONTENT_TAG.to_owned(),
                value: ref_str.clone(),
            },
        });
        self.text.push(INLINE_IMAGE_CHAR);
        self.utf16_len = end;
        self.image_refs.push(ref_str);
        self.last_was_space = false;
    }

    fn push_style(&mut self, style: SpanStyle) {
        let idx = self.ranges.len();
        self.ranges.push(TextRange {
            start: self.utf16_len,
            end: self.utf16_len,
            item: TextAnnotation::Span(style),
        });
        self.open_stack.push(OpenRange { range_index: idx });
    }

    fn push_annotation(&mut self, tag: &str, value: String) {
        let idx = self.ranges.len();
        self.ranges.push(TextRange {
            start: self.utf16_len,
            end: self.utf16_len,
            item: TextAnnotation::String {
                tag: tag.to_owned(),
                value,
            },
        });
        self.open_stack.push(OpenRange { range_index: idx });
    }

    fn pop(&mut self) {
        if let Some(open) = self.open_stack.pop() {
            self.ranges[open.range_index].end = self.utf16_len;
        }
    }

    fn is_blank(&self) -> bool {
        !self.has_visible_content
    }

    fn build(mut self) -> RichText {
        while let Some(open) = self.open_stack.pop() {
            self.ranges[open.range_index].end = self.utf16_len;
        }
        RichText {
            text: self.text,
            utf16_len: self.utf16_len,
            ranges: self.ranges,
        }
    }
}

#[inline]
fn is_kotlin_whitespace(ch: char) -> bool {
    ch.is_whitespace() || matches!(ch, '\u{00A0}' | '\u{2007}' | '\u{202F}')
}

#[inline]
fn utf16_length(s: &str) -> i32 {
    s.chars().map(|c| c.len_utf16() as i32).sum()
}

// -----------------------------------------------------------------------------
// Language Tag Normalization, RTL Check & Script Detection (LanguageTag.kt)
// -----------------------------------------------------------------------------

pub(crate) fn normalize_language_tag(raw: Option<&str>) -> Option<String> {
    let tag = raw?.trim().to_ascii_lowercase().replace('_', "-");
    if !is_valid_language_tag(&tag) {
        return None;
    }
    if tag == "ua" {
        Some("uk".to_owned())
    } else {
        Some(tag)
    }
}

fn is_valid_language_tag(tag: &str) -> bool {
    let mut parts = tag.split('-');
    let Some(primary) = parts.next() else {
        return false;
    };
    if !(2..=3).contains(&primary.len()) || !primary.bytes().all(|b| b.is_ascii_lowercase()) {
        return false;
    }
    for subtag in parts {
        if !(2..=8).contains(&subtag.len())
            || !subtag
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        {
            return false;
        }
    }
    true
}

pub(crate) fn is_rtl_language(raw: &str) -> bool {
    let Some(normalized) = normalize_language_tag(Some(raw)) else {
        return false;
    };
    let mut parts = normalized.split('-');
    let Some(primary) = parts.next() else {
        return false;
    };
    if matches!(
        primary,
        "ar" | "arc"
            | "ckb"
            | "dv"
            | "fa"
            | "he"
            | "iw"
            | "ks"
            | "lrc"
            | "mzn"
            | "nqo"
            | "pnb"
            | "ps"
            | "rhg"
            | "sd"
            | "syr"
            | "ug"
            | "ur"
            | "yi"
    ) {
        return true;
    }
    parts.any(|sub| {
        matches!(
            sub,
            "adlm" | "arab" | "hebr" | "mand" | "nkoo" | "rohg" | "samr" | "syrc" | "thaa"
        )
    })
}

pub(crate) fn detect_language_from_chapters(chapters: &[ParsedChapter]) -> Option<&'static str> {
    let mut sample_u16_len = 0_usize;
    let mut letters = 0_usize;
    let mut cyrillic = 0_usize;
    let mut arabic = 0_usize;
    let mut hebrew = 0_usize;
    let mut ukrainian = 0_usize;

    'outer: for chapter in chapters {
        for element in &chapter.elements {
            let text = match element {
                Element::Paragraph { text, .. } => text.text.as_str(),
                Element::Heading { styled_text, .. } => styled_text.text.as_str(),
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
            sample_u16_len += 1; // space appended between elements
            if sample_u16_len >= 2000 {
                break 'outer;
            }
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

// -----------------------------------------------------------------------------
// SHA-256 & Deterministic Binary Filename (ResourceIdentity.kt + Fb2Parser.kt)
// -----------------------------------------------------------------------------

pub(crate) fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
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

    let mut w = [0u32; 64];
    for chunk in buf.chunks_exact(64) {
        for (i, word) in chunk.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes([word[0], word[1], word[2], word[3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    let mut out = [0u8; 32];
    for (i, word) in h.iter().enumerate() {
        out[i * 4..(i + 1) * 4].copy_from_slice(&word.to_be_bytes());
    }
    out
}

fn sanitize_file_name(name: &str) -> String {
    name.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

pub(crate) fn binary_file_name(id: &str, content_type: Option<&str>) -> String {
    let raw_safe = sanitize_file_name(id);
    let safe = if raw_safe.trim().is_empty() {
        "image".to_owned()
    } else {
        raw_safe
    };
    let mime = content_type
        .and_then(|ct| ct.split(';').next())
        .map(|s| s.trim().to_ascii_lowercase());
    let suffix = match mime.as_deref() {
        Some("image/svg+xml") => Some("svg"),
        Some("image/gif") => Some("gif"),
        Some("image/png") => Some("png"),
        Some("image/jpeg" | "image/jpg") => Some("jpg"),
        Some("image/webp") => Some("webp"),
        Some("image/bmp" | "image/x-ms-bmp") => Some("bmp"),
        Some("image/avif") => Some("avif"),
        _ => None,
    };
    let display_name = if let Some(wanted) = suffix {
        let current = safe.rsplit_once('.').map_or("", |(_, ext)| ext);
        if current.eq_ignore_ascii_case(wanted)
            || (wanted == "jpg" && current.eq_ignore_ascii_case("jpeg"))
        {
            safe
        } else {
            format!("{safe}.{wanted}")
        }
    } else {
        safe
    };
    resource_cache_file_name("fb2", id, &display_name)
}

fn resource_cache_file_name(prefix: &str, canonical: &str, display_name: &str) -> String {
    let digest = sha256(canonical.as_bytes());
    let mut hex = String::with_capacity(32);
    for &byte in &digest[..16] {
        use std::fmt::Write;
        let _ = write!(&mut hex, "{byte:02x}");
    }
    let tail = display_name
        .rsplit_once('/')
        .map_or(display_name, |(_, after)| after);
    let sanitized = sanitize_file_name(tail);
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
    format!("{prefix}_{hex}_{leaf}")
}

// -----------------------------------------------------------------------------
// Bounded FB2 CSS Compatibility Engine (100% parity with Fb2Stylesheet.kt)
// -----------------------------------------------------------------------------

const INLINE_SPECIFICITY: i32 = 1_000;
const MAX_STYLESHEETS: usize = 64;
const MAX_RULE_BLOCKS: usize = 8_192;
const MAX_RULES: usize = 8_192;
const MAX_STYLESHEET_DECLARATIONS: usize = 65_536;
const MAX_DECLARATIONS_PER_BLOCK: usize = 256;
const MAX_EXPANDED_DECLARATIONS_PER_BLOCK: usize = 512;
const MAX_SELECTORS_PER_RULE: usize = 128;
const MAX_SELECTOR_CHARS: usize = 4_096;

#[inline]
fn is_element_class_alias(name: &str) -> bool {
    matches!(
        name,
        "p" | "title" | "subtitle" | "v" | "text-author" | "table" | "tr" | "td" | "th"
    )
}

#[inline]
fn is_table_element(name: &str) -> bool {
    matches!(name, "table" | "tr" | "td" | "th")
}

#[inline]
fn is_border_property(name: &str) -> bool {
    matches!(
        name,
        "border"
            | "border-width"
            | "border-style"
            | "border-color"
            | "border-top"
            | "border-right"
            | "border-bottom"
            | "border-left"
            | "border-top-width"
            | "border-top-style"
            | "border-top-color"
            | "border-right-width"
            | "border-right-style"
            | "border-right-color"
            | "border-bottom-width"
            | "border-bottom-style"
            | "border-bottom-color"
            | "border-left-width"
            | "border-left-style"
            | "border-left-color"
    )
}

#[inline]
fn is_supported_property(name: &str) -> bool {
    matches!(
        name,
        "font-style"
            | "font-weight"
            | "font-size"
            | "font-family"
            | "text-align"
            | "text-indent"
            | "line-height"
            | "text-decoration"
            | "margin-top"
            | "margin-right"
            | "margin-bottom"
            | "margin-left"
            | "margin-inline-start"
            | "margin-inline-end"
            | "page-break-before"
            | "break-before"
            | "color"
            | "background-color"
    ) || is_border_property(name)
}

#[derive(Clone, Debug)]
enum CssSelector {
    Type(String),
    Class(String),
    Named { name: String, with_type: bool },
    Universal,
}

impl CssSelector {
    fn specificity(&self) -> i32 {
        match self {
            Self::Type(_) => 1,
            Self::Class(_) => 10,
            Self::Named { with_type, .. } => {
                if *with_type {
                    11
                } else {
                    10
                }
            }
            Self::Universal => 0,
        }
    }

    fn matches_body(&self) -> bool {
        match self {
            Self::Type(name) | Self::Class(name) => name == "body",
            _ => false,
        }
    }

    fn matches_element(&self, element: &str) -> bool {
        match self {
            Self::Type(name) => name == element,
            Self::Class(name) => name == element && is_element_class_alias(element),
            Self::Universal => is_element_class_alias(element),
            Self::Named { .. } => false,
        }
    }
}

#[derive(Clone, Debug)]
struct CssDeclaration {
    property: String,
    value: String,
    important: bool,
    source_order: usize,
}

#[derive(Clone, Debug)]
struct CssRule {
    selector: CssSelector,
    declarations: Vec<CssDeclaration>,
    order: usize,
}

#[derive(Clone, Debug)]
struct CssWinner {
    value: String,
    important: bool,
    specificity: i32,
    cascade_order: usize,
    declaration_order: usize,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct CssComputed {
    pub(crate) italic: Option<bool>,
    pub(crate) bold: Option<bool>,
    pub(crate) font_scale: Option<f32>,
    pub(crate) font_family: Option<String>,
    pub(crate) align: Option<Align>,
    pub(crate) first_line_indent_em: Option<f32>,
    pub(crate) indent_start_em: Option<f32>,
    pub(crate) indent_start_frac: Option<f32>,
    pub(crate) indent_end_em: Option<f32>,
    pub(crate) indent_end_frac: Option<f32>,
    pub(crate) indent_left_em: Option<f32>,
    pub(crate) indent_left_frac: Option<f32>,
    pub(crate) indent_right_em: Option<f32>,
    pub(crate) indent_right_frac: Option<f32>,
    pub(crate) space_before_em: Option<f32>,
    pub(crate) space_after_em: Option<f32>,
    pub(crate) line_height_mult: Option<f32>,
    pub(crate) decoration: Option<i32>,
    pub(crate) page_break_before: Option<bool>,
    pub(crate) foreground_color_argb: Option<i32>,
    pub(crate) background_color_argb: Option<i32>,
    pub(crate) visual_background_color_argb: Option<i32>,
    pub(crate) publisher_box: Option<BoxBorders>,
    pub(crate) border_specified: bool,
    pub(crate) borderless: bool,
    absolute_line_height_em: Option<f32>,
    unitless_line_height: Option<f32>,
}

impl CssComputed {
    fn from_values(values: &HashMap<&str, &str>, inherited: Option<&Self>) -> Self {
        let parent_scale = inherited.and_then(|i| i.font_scale).unwrap_or(1.0);
        let font_scale = values
            .get("font-size")
            .and_then(|v| parse_font_scale(v, parent_scale))
            .or_else(|| inherited.and_then(|i| i.font_scale));
        let effective_scale = font_scale.unwrap_or(parent_scale);
        let (lh_mult, lh_abs, lh_unitless) = resolve_line_height(
            values.get("line-height").copied(),
            effective_scale,
            inherited,
        );
        let margins = parse_margin_values(values);
        let foreground = resolve_foreground_color(
            values.get("color").copied(),
            inherited.and_then(|i| i.foreground_color_argb),
        );
        let own_background = resolve_background_color(
            values.get("background-color").copied(),
            inherited.and_then(|i| i.background_color_argb),
            foreground,
        );
        let italic = match values
            .get("font-style")
            .map(|v| v.trim().to_ascii_lowercase())
            .as_deref()
        {
            Some("italic" | "oblique") => Some(true),
            Some("normal") => Some(false),
            _ => inherited.and_then(|i| i.italic),
        };
        let bold = values
            .get("font-weight")
            .and_then(|v| parse_font_weight(v))
            .or_else(|| inherited.and_then(|i| i.bold));
        let font_family = values
            .get("font-family")
            .and_then(|v| parse_font_family(v))
            .or_else(|| inherited.and_then(|i| i.font_family.clone()));
        let align = values
            .get("text-align")
            .and_then(|v| parse_alignment(v))
            .or_else(|| inherited.and_then(|i| i.align));
        let first_line_indent_em = values
            .get("text-indent")
            .and_then(|v| parse_length(v))
            .and_then(|l| l.em);
        let decoration = values
            .get("text-decoration")
            .and_then(|v| parse_decoration(v))
            .or_else(|| inherited.and_then(|i| i.decoration));
        let page_break_before = values
            .get("break-before")
            .or_else(|| values.get("page-break-before"))
            .and_then(|v| parse_page_break(v));
        let visual_background_color_argb = composite_nullable(
            own_background,
            inherited.and_then(|i| i.visual_background_color_argb),
        );
        Self {
            italic,
            bold,
            font_scale,
            font_family,
            align,
            first_line_indent_em,
            indent_start_em: None,
            indent_start_frac: None,
            indent_end_em: None,
            indent_end_frac: None,
            indent_left_em: margins.left.and_then(|l| l.em),
            indent_left_frac: margins.left.and_then(|l| l.fraction),
            indent_right_em: margins.right.and_then(|l| l.em),
            indent_right_frac: margins.right.and_then(|l| l.fraction),
            space_before_em: margins.before.and_then(|l| l.em),
            space_after_em: margins.after.and_then(|l| l.em),
            line_height_mult: lh_mult,
            decoration,
            page_break_before,
            foreground_color_argb: foreground,
            background_color_argb: own_background,
            visual_background_color_argb,
            publisher_box: None,
            border_specified: false,
            borderless: false,
            absolute_line_height_em: lh_abs,
            unitless_line_height: lh_unitless,
        }
    }

    fn apply_to(&self, base: Option<&BlockStyle>) -> Option<BlockStyle> {
        let original = base.cloned().unwrap_or_default();
        let (indent_start_em, indent_start_frac) =
            match (self.indent_start_em, self.indent_start_frac) {
                (Some(em), _) => (em, 0.0),
                (None, Some(frac)) => (0.0, frac),
                (None, None) => (original.indent_start_em, original.indent_start_frac),
            };
        let (indent_end_em, indent_end_frac) = match (self.indent_end_em, self.indent_end_frac) {
            (Some(em), _) => (em, 0.0),
            (None, Some(frac)) => (0.0, frac),
            (None, None) => (original.indent_end_em, original.indent_end_frac),
        };
        let (indent_left_em, indent_left_frac) = match (self.indent_left_em, self.indent_left_frac)
        {
            (Some(em), _) => (em, 0.0),
            (None, Some(frac)) => (0.0, frac),
            (None, None) => (original.indent_left_em, original.indent_left_frac),
        };
        let (indent_right_em, indent_right_frac) =
            match (self.indent_right_em, self.indent_right_frac) {
                (Some(em), _) => (em, 0.0),
                (None, Some(frac)) => (0.0, frac),
                (None, None) => (original.indent_right_em, original.indent_right_frac),
            };
        let first_line_indent = match self.first_line_indent_em {
            None => original.first_line_indent,
            Some(v) if v.abs() < 0.001 => Some(false),
            Some(v) if v > 0.0 => Some(true),
            Some(_) => original.first_line_indent,
        };
        let first_line_indent_em = self
            .first_line_indent_em
            .filter(|&v| v > 0.0)
            .or(original.first_line_indent_em);

        let result = BlockStyle {
            align: self.align.or(original.align),
            italic: self.italic.or(original.italic),
            bold: self.bold.or(original.bold),
            font_scale: self.font_scale.or(original.font_scale),
            indent_start_frac,
            indent_start_em,
            indent_end_frac,
            indent_end_em,
            indent_left_frac,
            indent_left_em,
            indent_right_frac,
            indent_right_em,
            first_line_indent,
            first_line_indent_em,
            space_before_em: self.space_before_em.unwrap_or(original.space_before_em),
            space_after_em: self.space_after_em.unwrap_or(original.space_after_em),
            space_before_specified: original.space_before_specified,
            space_after_specified: original.space_after_specified,
            font_family: self.font_family.clone().or(original.font_family),
            line_height_mult: self.line_height_mult.or(original.line_height_mult),
            page_break_before: self.page_break_before.unwrap_or(original.page_break_before),
            language: original.language,
            direction: original.direction,
            foreground_color_argb: self
                .foreground_color_argb
                .or(original.foreground_color_argb),
            background_color_argb: self
                .visual_background_color_argb
                .or(original.background_color_argb),
        };
        if base.is_none() && result.is_default() {
            None
        } else {
            Some(result)
        }
    }

    fn span_style(&self, relative_to: Option<&Self>) -> Option<SpanStyle> {
        let generic = generic_span_family(self.font_family.as_deref());
        let relative_generic =
            generic_span_family(relative_to.and_then(|r| r.font_family.as_deref()));
        let relative_scale = relative_to.and_then(|r| r.font_scale).unwrap_or(1.0);
        let size_ratio = self
            .font_scale
            .map(|s| s / relative_scale)
            .filter(|&r| r.is_finite() && !(0.999..=1.001).contains(&r));
        let own_italic = self
            .italic
            .filter(|&v| Some(v) != relative_to.and_then(|r| r.italic));
        let own_bold = self
            .bold
            .filter(|&v| Some(v) != relative_to.and_then(|r| r.bold));
        let own_family = generic.filter(|&f| Some(f) != relative_generic);
        let own_decoration = self
            .decoration
            .filter(|&d| Some(d) != relative_to.and_then(|r| r.decoration));
        let own_foreground = self
            .foreground_color_argb
            .filter(|&c| Some(c) != relative_to.and_then(|r| r.foreground_color_argb));
        let own_background = self
            .background_color_argb
            .filter(|&c| ((c as u32) >> 24) != 0);

        if own_italic.is_none()
            && own_bold.is_none()
            && size_ratio.is_none()
            && own_family.is_none()
            && own_decoration.is_none()
            && own_foreground.is_none()
            && own_background.is_none()
        {
            return None;
        }
        Some(SpanStyle {
            color_argb: own_foreground,
            font_size_em: size_ratio,
            weight: own_bold.map(|b| if b { 700 } else { 400 }),
            italic: own_italic,
            family: own_family,
            baseline_shift: None,
            locales: None,
            background_argb: own_background,
            decoration: own_decoration,
        })
    }

    fn color_block_style(
        &self,
        relative_to: Option<&Self>,
        inherited_background_argb: Option<i32>,
    ) -> Option<BlockStyle> {
        let style = BlockStyle {
            foreground_color_argb: self
                .foreground_color_argb
                .filter(|&c| Some(c) != relative_to.and_then(|r| r.foreground_color_argb)),
            background_color_argb: self.background_color_argb.or(inherited_background_argb),
            ..BlockStyle::default()
        };
        if style.is_default() {
            None
        } else {
            Some(style)
        }
    }

    fn decoration_span_style(&self) -> Option<SpanStyle> {
        self.decoration.map(|decoration| SpanStyle {
            decoration: Some(decoration),
            ..SpanStyle::default()
        })
    }
}

fn generic_span_family(family: Option<&str>) -> Option<&'static str> {
    match family? {
        "serif" => Some("serif"),
        "sans-serif" => Some("sans"),
        "monospace" => Some("mono"),
        "cursive" => Some("cursive"),
        _ => None,
    }
}

pub(crate) struct Fb2Stylesheet {
    rules_count: usize,
    element_rules: HashMap<&'static str, Vec<CssRule>>,
    universal_named_rules: Vec<CssRule>,
    named_rules: HashMap<String, Vec<CssRule>>,
    body_style: CssComputed,
    is_empty: bool,
}

impl Fb2Stylesheet {
    pub(crate) fn empty() -> Self {
        Self {
            rules_count: 0,
            element_rules: HashMap::new(),
            universal_named_rules: Vec::new(),
            named_rules: HashMap::new(),
            body_style: CssComputed::default(),
            is_empty: true,
        }
    }

    pub(crate) fn parse(sheets: &[String]) -> Self {
        let mut order = 0_usize;
        let mut block_count = 0_usize;
        let mut rule_count = 0_usize;
        let mut declaration_count = 0_usize;
        let mut rules: Vec<CssRule> = Vec::new();

        'sheet_loop: for css in sheets.iter().take(MAX_STYLESHEETS) {
            let remaining_blocks = MAX_RULE_BLOCKS.saturating_sub(block_count);
            if remaining_blocks == 0
                || rule_count >= MAX_RULES
                || declaration_count >= MAX_STYLESHEET_DECLARATIONS
            {
                break;
            }
            let (parsed_rules, scanned_blocks) = parse_css_rules(css, remaining_blocks);
            block_count += scanned_blocks;
            for (prelude, body) in parsed_rules {
                let decl_limit = MAX_DECLARATIONS_PER_BLOCK
                    .min(MAX_STYLESHEET_DECLARATIONS.saturating_sub(declaration_count));
                if decl_limit == 0 {
                    break 'sheet_loop;
                }
                let (parsed_decls, source_count) = parse_declarations(&body, decl_limit);
                declaration_count += source_count;
                if parsed_decls.is_empty() {
                    continue;
                }
                for raw_sel in split_top_level(&prelude, ',', MAX_SELECTORS_PER_RULE) {
                    if rule_count >= MAX_RULES {
                        break 'sheet_loop;
                    }
                    let Some(selector) = parse_selector(&raw_sel) else {
                        continue;
                    };
                    rules.push(CssRule {
                        selector,
                        declarations: parsed_decls.clone(),
                        order,
                    });
                    order += 1;
                    rule_count += 1;
                }
            }
        }

        if rules.is_empty() {
            return Self::empty();
        }

        let mut body_winners: HashMap<String, CssWinner> = HashMap::new();
        for rule in &rules {
            if rule.selector.matches_body() {
                apply_rule(&mut body_winners, rule);
            }
        }
        let body_values: HashMap<&str, &str> = body_winners
            .iter()
            .map(|(k, v)| (k.as_str(), v.value.as_str()))
            .collect();
        let body_style = CssComputed::from_values(&body_values, None);

        const ALIASES: [&str; 9] = [
            "p",
            "title",
            "subtitle",
            "v",
            "text-author",
            "table",
            "tr",
            "td",
            "th",
        ];
        let mut element_rules: HashMap<&'static str, Vec<CssRule>> = HashMap::new();
        for &elem in &ALIASES {
            let matching: Vec<CssRule> = rules
                .iter()
                .filter(|r| r.selector.matches_element(elem))
                .cloned()
                .collect();
            if !matching.is_empty() {
                element_rules.insert(elem, matching);
            }
        }

        let universal_named_rules: Vec<CssRule> = rules
            .iter()
            .filter(|r| {
                matches!(r.selector, CssSelector::Universal)
                    || matches!(&r.selector, CssSelector::Type(name) if name == "style")
            })
            .cloned()
            .collect();

        let mut named_rules: HashMap<String, Vec<CssRule>> = HashMap::new();
        for rule in &rules {
            let name = match &rule.selector {
                CssSelector::Class(n) | CssSelector::Named { name: n, .. } => Some(n.clone()),
                _ => None,
            };
            if let Some(name) = name {
                named_rules.entry(name).or_default().push(rule.clone());
            }
        }

        Self {
            rules_count: rules.len(),
            element_rules,
            universal_named_rules,
            named_rules,
            body_style,
            is_empty: false,
        }
    }

    pub(crate) fn computed(
        &self,
        element: &str,
        inline_style: Option<&str>,
        inherited: Option<&CssComputed>,
    ) -> CssComputed {
        if self.is_empty && inline_style.is_none_or(|s| s.trim().is_empty()) {
            return match inherited {
                Some(inh) => {
                    let empty: HashMap<&str, &str> = HashMap::new();
                    CssComputed::from_values(&empty, Some(inh))
                }
                None => CssComputed::default(),
            };
        }

        let mut winners: HashMap<String, CssWinner> = HashMap::new();
        if let Some(rules) = self.element_rules.get(element) {
            for rule in rules {
                apply_rule(&mut winners, rule);
            }
        }
        if let Some(inline) = inline_style {
            if !inline.trim().is_empty() {
                let (decls, _) = parse_declarations(inline, MAX_DECLARATIONS_PER_BLOCK);
                for decl in decls {
                    put_winner(&mut winners, &decl, INLINE_SPECIFICITY, self.rules_count);
                }
            }
        }

        let values: HashMap<&str, &str> = winners
            .iter()
            .map(|(k, v)| (k.as_str(), v.value.as_str()))
            .collect();
        let mut computed =
            CssComputed::from_values(&values, Some(inherited.unwrap_or(&self.body_style)));

        if !is_table_element(element) {
            return computed;
        }
        let mut border_entries: Vec<(&String, &CssWinner)> = winners
            .iter()
            .filter(|(k, _)| is_border_property(k))
            .collect();
        if border_entries.is_empty() {
            return computed;
        }
        border_entries.sort_by(|(_, a), (_, b)| {
            a.specificity
                .cmp(&b.specificity)
                .then_with(|| a.cascade_order.cmp(&b.cascade_order))
                .then_with(|| a.declaration_order.cmp(&b.declaration_order))
        });

        let mut raw_decls = Vec::with_capacity(border_entries.len() + 1);
        raw_decls.push(css_shorthand::RawDeclaration {
            property: "font-size".to_owned(),
            value: format!("{}em", computed.font_scale.unwrap_or(1.0)),
            important: false,
        });
        for (prop, winner) in border_entries {
            raw_decls.push(css_shorthand::RawDeclaration {
                property: prop.clone(),
                value: winner.value.clone(),
                important: winner.important,
            });
        }
        let (_, compiled) = css_shorthand::compile(&raw_decls);
        let resolved_decls: Vec<css_computed_model::Declaration> = compiled
            .into_iter()
            .map(|d| css_computed_model::Declaration {
                property: d.property,
                value: d.value.into_owned(),
                expansion: d.expansion,
            })
            .collect();
        let elem = css_computed_model::Element {
            tag: element.to_owned(),
            ..css_computed_model::Element::default()
        };
        let resolved = css_computed::resolve(&elem, None, &resolved_decls);
        let borders = BoxBorders {
            border_top: resolved.border_top.as_ref().and_then(convert_border),
            border_right: resolved.border_right.as_ref().and_then(convert_border),
            border_bottom: resolved.border_bottom.as_ref().and_then(convert_border),
            border_left: resolved.border_left.as_ref().and_then(convert_border),
        };
        let has_visible_border = [
            resolved.border_top.as_ref(),
            resolved.border_right.as_ref(),
            resolved.border_bottom.as_ref(),
            resolved.border_left.as_ref(),
        ]
        .into_iter()
        .flatten()
        .any(|b| b.uses_current_color || ((b.color_argb as u32) >> 24) != 0);

        computed.publisher_box = (!borders.is_empty()).then_some(borders);
        computed.border_specified = resolved.border_specified;
        computed.borderless = resolved.border_specified && !has_visible_border;
        computed
    }

    pub(crate) fn computed_named(&self, name: Option<&str>) -> CssComputed {
        if self.is_empty {
            return CssComputed::default();
        }
        let normalized = name.map(str::trim).unwrap_or("");
        if normalized.is_empty() {
            return CssComputed::default();
        }
        let mut winners: HashMap<String, CssWinner> = HashMap::new();
        for rule in &self.universal_named_rules {
            apply_rule(&mut winners, rule);
        }
        if let Some(rules) = self.named_rules.get(normalized) {
            for rule in rules {
                apply_rule(&mut winners, rule);
            }
        }
        let values: HashMap<&str, &str> = winners
            .iter()
            .map(|(k, v)| (k.as_str(), v.value.as_str()))
            .collect();
        CssComputed::from_values(&values, None)
    }
}

fn convert_border(border: &css_computed_model::Border) -> Option<BorderSide> {
    let style = match border.style {
        1 => BorderStyleKind::Solid,
        2 => BorderStyleKind::Dashed,
        3 => BorderStyleKind::Dotted,
        4 => BorderStyleKind::Double,
        _ => return None,
    };
    Some(BorderSide {
        width_em: border.width_em,
        color_argb: border.color_argb,
        style,
        uses_current_color: border.uses_current_color,
    })
}

fn apply_rule(winners: &mut HashMap<String, CssWinner>, rule: &CssRule) {
    let spec = rule.selector.specificity();
    for decl in &rule.declarations {
        put_winner(winners, decl, spec, rule.order);
    }
}

fn put_winner(
    winners: &mut HashMap<String, CssWinner>,
    decl: &CssDeclaration,
    specificity: i32,
    cascade_order: usize,
) {
    if !is_supported_property(&decl.property) {
        return;
    }
    let replaces = match winners.get(&decl.property) {
        None => true,
        Some(old) => {
            (decl.important && !old.important)
                || (decl.important == old.important
                    && (specificity > old.specificity
                        || (specificity == old.specificity
                            && (cascade_order > old.cascade_order
                                || (cascade_order == old.cascade_order
                                    && decl.source_order >= old.declaration_order)))))
        }
    };
    if replaces {
        winners.insert(
            decl.property.clone(),
            CssWinner {
                value: decl.value.clone(),
                important: decl.important,
                specificity,
                cascade_order,
                declaration_order: decl.source_order,
            },
        );
    }
}

fn parse_selector(raw: &str) -> Option<CssSelector> {
    let value = raw.trim();
    if value.is_empty() || value.chars().count() > MAX_SELECTOR_CHARS {
        return None;
    }
    if value == "*" {
        return Some(CssSelector::Universal);
    }
    // style[name="foo"] or style[name='foo']
    if value.len() >= 12 && value[..5].eq_ignore_ascii_case("style") {
        let rest = value[5..].trim_start();
        if let Some(inner) = rest.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
            let inner = inner.trim();
            if inner.len() >= 4 && inner[..4].eq_ignore_ascii_case("name") {
                let after_name = inner[4..].trim_start();
                if let Some(after_eq) = after_name.strip_prefix('=') {
                    let val = after_eq.trim();
                    let bytes = val.as_bytes();
                    if bytes.len() >= 3
                        && (bytes[0] == b'"' || bytes[0] == b'\'')
                        && bytes[bytes.len() - 1] == bytes[0]
                    {
                        let inner_val = &val[1..val.len() - 1];
                        if !inner_val.is_empty()
                            && !inner_val.contains('\'')
                            && !inner_val.contains('"')
                        {
                            return Some(CssSelector::Named {
                                name: inner_val.to_owned(),
                                with_type: true,
                            });
                        }
                    }
                }
            }
        }
    }
    // style.foo
    if value.len() > 6 && value[..5].eq_ignore_ascii_case("style") && value.as_bytes()[5] == b'.' {
        let name = &value[6..];
        if is_css_class_ident(name) {
            return Some(CssSelector::Named {
                name: name.to_owned(),
                with_type: true,
            });
        }
    }
    // .foo
    if let Some(name) = value.strip_prefix('.') {
        if is_css_class_ident(name) {
            return Some(CssSelector::Class(name.to_owned()));
        }
        return None;
    }
    // element type
    let bytes = value.as_bytes();
    if !bytes.is_empty()
        && bytes[0].is_ascii_alphabetic()
        && bytes[1..]
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || *b == b'-')
    {
        let lower = value.to_ascii_lowercase();
        if lower == "body" || lower == "style" || is_element_class_alias(&lower) {
            return Some(CssSelector::Type(lower));
        }
    }
    None
}

fn is_css_class_ident(s: &str) -> bool {
    let bytes = s.as_bytes();
    !bytes.is_empty()
        && (bytes[0].is_ascii_alphabetic() || bytes[0] == b'_')
        && bytes[1..]
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || *b == b'_' || *b == b'-')
}

fn parse_css_rules(raw_css: &str, limit: usize) -> (Vec<(String, String)>, usize) {
    let css = strip_css_comments(raw_css);
    let bytes = css.as_bytes();
    let mut result = Vec::new();
    let mut pos = 0_usize;
    let mut scanned_blocks = 0_usize;

    while pos < bytes.len() && scanned_blocks < limit {
        while pos < bytes.len() {
            let ch = css[pos..].chars().next().unwrap();
            if ch.is_whitespace() || ch == ';' {
                pos += ch.len_utf8();
            } else {
                break;
            }
        }
        if pos >= bytes.len() {
            break;
        }
        if bytes[pos] == b'@' {
            let Some(boundary) = find_top_level(&css, pos + 1, b";{") else {
                break;
            };
            if bytes[boundary] == b';' {
                pos = boundary + 1;
            } else {
                scanned_blocks += 1;
                let Some(close) = matching_brace(&css, boundary) else {
                    break;
                };
                pos = close + 1;
            }
            continue;
        }
        let Some(open) = find_top_level(&css, pos, b"{") else {
            break;
        };
        let prelude = css[pos..open].trim();
        let Some(close) = matching_brace(&css, open) else {
            break;
        };
        scanned_blocks += 1;
        if !prelude.is_empty() {
            result.push((prelude.to_owned(), css[open + 1..close].to_owned()));
        }
        pos = close + 1;
    }
    (result, scanned_blocks)
}

fn parse_declarations(raw: &str, source_limit: usize) -> (Vec<CssDeclaration>, usize) {
    let stripped = strip_css_comments(raw);
    let pieces = split_top_level(&stripped, ';', source_limit);
    let source_count = pieces.len();
    let mut values = Vec::new();
    for (source_order, piece) in pieces.into_iter().enumerate() {
        let Some(colon) = find_top_level(&piece, 0, b":") else {
            continue;
        };
        let property = piece[..colon].trim().to_ascii_lowercase();
        let raw_val = piece[colon + 1..].trim();
        if property.is_empty() || raw_val.is_empty() {
            continue;
        }
        let (val, important) = strip_important(raw_val);
        let normalized = normalize_declaration(&property, val, important, source_order);
        if values.len() + normalized.len() > MAX_EXPANDED_DECLARATIONS_PER_BLOCK {
            continue;
        }
        values.extend(normalized);
    }
    (values, source_count)
}

fn strip_important(raw: &str) -> (&str, bool) {
    let trimmed = raw.trim_end();
    if trimmed.len() >= 10 && trimmed[trimmed.len() - 10..].eq_ignore_ascii_case("!important") {
        (trimmed[..trimmed.len() - 10].trim_end(), true)
    } else {
        (trimmed, false)
    }
}

fn normalize_declaration(
    property: &str,
    value: &str,
    important: bool,
    source_order: usize,
) -> Vec<CssDeclaration> {
    if property == "font" {
        let Some((style, weight, size, line_height, family)) = parse_font_shorthand(value) else {
            return Vec::new();
        };
        return vec![
            CssDeclaration {
                property: "font-style".to_owned(),
                value: style,
                important,
                source_order,
            },
            CssDeclaration {
                property: "font-weight".to_owned(),
                value: weight,
                important,
                source_order,
            },
            CssDeclaration {
                property: "font-size".to_owned(),
                value: size,
                important,
                source_order,
            },
            CssDeclaration {
                property: "line-height".to_owned(),
                value: line_height,
                important,
                source_order,
            },
            CssDeclaration {
                property: "font-family".to_owned(),
                value: family,
                important,
                source_order,
            },
        ];
    }
    if property == "margin" {
        let tokens: Vec<&str> = value.split_whitespace().collect();
        if tokens.is_empty()
            || tokens.len() > 4
            || !tokens.iter().all(|t| parse_length(t).is_some())
        {
            return Vec::new();
        }
        let top = tokens[0];
        let right = tokens.get(1).copied().unwrap_or(top);
        let bottom = tokens.get(2).copied().unwrap_or(top);
        let left = tokens.get(3).copied().unwrap_or(right);
        return vec![
            CssDeclaration {
                property: "margin-top".to_owned(),
                value: top.to_owned(),
                important,
                source_order,
            },
            CssDeclaration {
                property: "margin-right".to_owned(),
                value: right.to_owned(),
                important,
                source_order,
            },
            CssDeclaration {
                property: "margin-bottom".to_owned(),
                value: bottom.to_owned(),
                important,
                source_order,
            },
            CssDeclaration {
                property: "margin-left".to_owned(),
                value: left.to_owned(),
                important,
                source_order,
            },
        ];
    }
    if property == "margin-inline" {
        let tokens: Vec<&str> = value.split_whitespace().collect();
        if tokens.is_empty()
            || tokens.len() > 2
            || !tokens.iter().all(|t| parse_length(t).is_some())
        {
            return Vec::new();
        }
        let first = tokens[0];
        let second = tokens.get(1).copied().unwrap_or(first);
        return vec![
            CssDeclaration {
                property: "margin-left".to_owned(),
                value: first.to_owned(),
                important,
                source_order,
            },
            CssDeclaration {
                property: "margin-right".to_owned(),
                value: second.to_owned(),
                important,
                source_order,
            },
        ];
    }

    let normalized_prop = match property {
        "margin-block-start" => "margin-top",
        "margin-block-end" => "margin-bottom",
        "margin-inline-start" => "margin-left",
        "margin-inline-end" => "margin-right",
        other => other,
    };
    if normalized_prop.starts_with("margin-") && parse_length(value).is_none() {
        return Vec::new();
    }
    vec![CssDeclaration {
        property: normalized_prop.to_owned(),
        value: value.to_owned(),
        important,
        source_order,
    }]
}

fn parse_font_shorthand(raw: &str) -> Option<(String, String, String, String, String)> {
    let value = raw.trim();
    match value.to_ascii_lowercase().as_str() {
        "inherit" | "unset" => {
            return Some((
                "inherit".into(),
                "inherit".into(),
                "inherit".into(),
                "inherit".into(),
                "inherit".into(),
            ));
        }
        "initial" | "revert" | "revert-layer" => {
            return Some((
                "normal".into(),
                "normal".into(),
                "medium".into(),
                "normal".into(),
                "serif".into(),
            ));
        }
        "caption" | "icon" | "menu" | "message-box" | "small-caption" | "status-bar" => {
            return Some((
                "normal".into(),
                "normal".into(),
                "medium".into(),
                "normal".into(),
                "sans-serif".into(),
            ));
        }
        _ => {}
    }

    let tokens = split_font_tokens(value);
    if tokens.is_empty() {
        return None;
    }
    let mut style = "normal".to_owned();
    let mut weight = "normal".to_owned();
    let mut size: Option<String> = None;
    let mut index = 0_usize;

    while index < tokens.len() {
        let token = &tokens[index];
        let lower = token.to_ascii_lowercase();
        if parse_font_scale(&lower, 1.0).is_some() {
            size = Some(token.clone());
            index += 1;
            break;
        }
        if lower == "italic" || lower == "oblique" {
            style = lower.clone();
            if lower == "oblique" && tokens.get(index + 1).is_some_and(|t| is_font_angle(t)) {
                index += 1;
            }
        } else if lower == "normal" || lower == "small-caps" {
            // ignored
        } else if matches!(lower.as_str(), "bold" | "bolder" | "lighter")
            || lower
                .parse::<i32>()
                .ok()
                .is_some_and(|w| (1..=1_000).contains(&w))
        {
            weight = lower;
        } else if matches!(
            lower.as_str(),
            "ultra-condensed"
                | "extra-condensed"
                | "condensed"
                | "semi-condensed"
                | "semi-expanded"
                | "expanded"
                | "extra-expanded"
                | "ultra-expanded"
        ) {
            // ignored
        } else {
            return None;
        }
        index += 1;
    }

    let resolved_size = size?;
    let mut line_height = "normal".to_owned();
    if tokens.get(index).map(String::as_str) == Some("/") {
        let lh = tokens.get(index + 1).filter(|t| t.as_str() != "/")?;
        line_height = lh.clone();
        index += 2;
    }
    let family = tokens[index..].join(" ").trim().to_owned();
    if family.is_empty() || family == "/" {
        return None;
    }
    Some((style, weight, resolved_size, line_height, family))
}

fn is_font_angle(s: &str) -> bool {
    let lower = s.to_ascii_lowercase();
    let suffix = ["deg", "grad", "rad", "turn"]
        .into_iter()
        .find(|suf| lower.ends_with(suf));
    let Some(suf) = suffix else {
        return false;
    };
    let num = &lower[..lower.len() - suf.len()];
    num.parse::<f32>().ok().is_some_and(f32::is_finite)
}

fn split_font_tokens(value: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut start: Option<usize> = None;
    let mut quote: Option<char> = None;
    let mut parens = 0_usize;
    let flush = |result: &mut Vec<String>, start: &mut Option<usize>, end: usize| {
        if let Some(s) = start.take() {
            result.push(value[s..end].to_owned());
        }
    };
    for (idx, ch) in value.char_indices() {
        if let Some(q) = quote {
            if ch == q && !is_escaped_at(value, idx) {
                quote = None;
            }
            if start.is_none() {
                start = Some(idx);
            }
        } else if ch == '\'' || ch == '"' {
            quote = Some(ch);
            if start.is_none() {
                start = Some(idx);
            }
        } else if ch == '(' {
            parens += 1;
            if start.is_none() {
                start = Some(idx);
            }
        } else if ch == ')' {
            parens = parens.saturating_sub(1);
            if start.is_none() {
                start = Some(idx);
            }
        } else if parens == 0 && ch == '/' {
            flush(&mut result, &mut start, idx);
            result.push("/".to_owned());
        } else if parens == 0 && ch.is_whitespace() {
            flush(&mut result, &mut start, idx);
        } else if start.is_none() {
            start = Some(idx);
        }
    }
    flush(&mut result, &mut start, value.len());
    result
}

fn split_top_level(raw: &str, separator: char, limit: usize) -> Vec<String> {
    if raw.is_empty() || limit == 0 {
        return Vec::new();
    }
    let mut result = Vec::new();
    let mut start = 0_usize;
    let mut quote: Option<char> = None;
    let mut parens = 0_usize;

    for (idx, ch) in raw.char_indices() {
        if let Some(q) = quote {
            if ch == q && !is_escaped_at(raw, idx) {
                quote = None;
            }
        } else if ch == '\'' || ch == '"' {
            quote = Some(ch);
        } else if ch == '(' {
            parens += 1;
        } else if ch == ')' {
            parens = parens.saturating_sub(1);
        } else if ch == separator && parens == 0 {
            result.push(raw[start..idx].to_owned());
            if result.len() >= limit {
                return result;
            }
            start = idx + ch.len_utf8();
        }
    }
    if result.len() < limit {
        result.push(raw[start..].to_owned());
    }
    result
}

fn find_top_level(raw: &str, start: usize, targets: &[u8]) -> Option<usize> {
    let mut quote: Option<u8> = None;
    let mut parens = 0_usize;
    let mut brackets = 0_usize;
    for (idx, &b) in raw.as_bytes().iter().enumerate().skip(start) {
        if let Some(q) = quote {
            if b == q && !is_escaped_at(raw, idx) {
                quote = None;
            }
        } else if b == b'\'' || b == b'"' {
            quote = Some(b);
        } else if b == b'(' {
            parens += 1;
        } else if b == b')' {
            parens = parens.saturating_sub(1);
        } else if b == b'[' {
            brackets += 1;
        } else if b == b']' {
            brackets = brackets.saturating_sub(1);
        } else if parens == 0 && brackets == 0 && targets.contains(&b) {
            return Some(idx);
        }
    }
    None
}

fn matching_brace(raw: &str, open: usize) -> Option<usize> {
    let mut depth = 1_usize;
    let mut quote: Option<u8> = None;
    for (idx, &b) in raw.as_bytes().iter().enumerate().skip(open + 1) {
        if let Some(q) = quote {
            if b == q && !is_escaped_at(raw, idx) {
                quote = None;
            }
        } else if b == b'\'' || b == b'"' {
            quote = Some(b);
        } else if b == b'{' {
            depth += 1;
        } else if b == b'}' {
            depth -= 1;
            if depth == 0 {
                return Some(idx);
            }
        }
    }
    None
}

fn strip_css_comments(raw: &str) -> String {
    if !raw.contains("/*") {
        return raw.to_owned();
    }
    let mut out = String::with_capacity(raw.len());
    let bytes = raw.as_bytes();
    let mut idx = 0_usize;
    let mut quote: Option<u8> = None;
    while idx < bytes.len() {
        let b = bytes[idx];
        if let Some(q) = quote {
            let ch = raw[idx..].chars().next().unwrap();
            out.push(ch);
            if b == q && !is_escaped_at(raw, idx) {
                quote = None;
            }
            idx += ch.len_utf8();
        } else if b == b'\'' || b == b'"' {
            quote = Some(b);
            out.push(b as char);
            idx += 1;
        } else if b == b'/' && bytes.get(idx + 1) == Some(&b'*') {
            out.push(' ');
            if let Some(rel) = raw[idx + 2..].find("*/") {
                idx = idx + 2 + rel + 2;
            } else {
                break;
            }
        } else {
            let ch = raw[idx..].chars().next().unwrap();
            out.push(ch);
            idx += ch.len_utf8();
        }
    }
    out
}

#[inline]
fn is_escaped_at(raw: &str, byte_idx: usize) -> bool {
    let bytes = raw.as_bytes();
    let mut backslashes = 0_usize;
    let mut cursor = byte_idx;
    while cursor > 0 && bytes[cursor - 1] == b'\\' {
        backslashes += 1;
        cursor -= 1;
    }
    backslashes % 2 == 1
}

fn resolve_foreground_color(value: Option<&str>, inherited: Option<i32>) -> Option<i32> {
    let Some(raw) = value else {
        return inherited;
    };
    match raw.trim().to_ascii_lowercase().as_str() {
        "inherit" | "unset" | "currentcolor" => inherited,
        "initial" | "revert" | "revert-layer" => Some(0xFF00_0000_u32 as i32),
        _ => css_color::parse_color(raw).map(|c| c as i32).or(inherited),
    }
}

fn resolve_background_color(
    value: Option<&str>,
    inherited: Option<i32>,
    current: Option<i32>,
) -> Option<i32> {
    let raw = value?;
    match raw.trim().to_ascii_lowercase().as_str() {
        "inherit" => inherited,
        "currentcolor" => current,
        "initial" | "unset" | "revert" | "revert-layer" => None,
        _ => css_color::parse_color(raw).map(|c| c as i32),
    }
}

fn composite_nullable(foreground: Option<i32>, background: Option<i32>) -> Option<i32> {
    let fg = match foreground {
        Some(c) if ((c as u32) >> 24) != 0 => c as u32,
        _ => return background,
    };
    let bg = match background {
        Some(c) if ((c as u32) >> 24) != 0 => c as u32,
        _ => return foreground,
    };
    let fa = (fg >> 24) & 0xFF;
    if fa == 0xFF {
        return Some(fg as i32);
    }
    let ba = (bg >> 24) & 0xFF;
    let out_a = fa + (ba * (255 - fa) + 127) / 255;
    let channel = |shift: u32| -> u32 {
        let fc = (fg >> shift) & 0xFF;
        let bc = (bg >> shift) & 0xFF;
        let premul = fc * fa + (bc * ba * (255 - fa) + 127) / 255;
        (premul + out_a / 2) / out_a
    };
    let out = (out_a << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0);
    Some(out as i32)
}

fn parse_font_weight(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "bold" | "bolder" | "600" | "700" | "800" | "900" => Some(true),
        "normal" | "lighter" | "100" | "200" | "300" | "400" | "500" => Some(false),
        _ => None,
    }
}

fn parse_font_scale(value: &str, parent_scale: f32) -> Option<f32> {
    let raw = value.trim().to_ascii_lowercase();
    let scale = match raw.as_str() {
        "xx-small" => Some(0.6),
        "x-small" => Some(0.75),
        "small" => Some(0.875),
        "medium" => Some(1.0),
        "large" => Some(1.125),
        "x-large" => Some(1.5),
        "xx-large" => Some(2.0),
        "smaller" => Some(parent_scale * 0.875),
        "larger" => Some(parent_scale * 1.125),
        "inherit" | "unset" => Some(parent_scale),
        _ => {
            if let Some(num) = raw.strip_suffix('%') {
                parse_f32_finite(num).map(|n| (n / 100.0) * parent_scale)
            } else if let Some(num) = raw.strip_suffix("rem") {
                parse_f32_finite(num)
            } else if let Some(num) = raw.strip_suffix("em") {
                parse_f32_finite(num).map(|n| n * parent_scale)
            } else if let Some(num) = raw.strip_suffix("px") {
                parse_f32_finite(num).map(|n| n / 16.0)
            } else if let Some(num) = raw.strip_suffix("pt") {
                parse_f32_finite(num).map(|n| n / 12.0)
            } else {
                None
            }
        }
    };
    scale.filter(|s| s.is_finite()).map(|s| s.clamp(0.6, 2.6))
}

fn parse_font_family(value: &str) -> Option<String> {
    let parts = split_top_level(value, ',', 1);
    let first = parts.first()?;
    let trimmed = first
        .trim()
        .trim_matches(|c| c == '\'' || c == '"')
        .to_lowercase();
    if trimmed.is_empty() {
        return None;
    }
    match trimmed.as_str() {
        "inherit" | "unset" => None,
        "initial" | "revert" | "revert-layer" => Some("serif".to_owned()),
        "serif" | "times" | "times new roman" | "georgia" => Some("serif".to_owned()),
        "sans-serif" | "arial" | "helvetica" | "verdana" | "tahoma" => {
            Some("sans-serif".to_owned())
        }
        "monospace" | "courier" | "courier new" | "consolas" => Some("monospace".to_owned()),
        "cursive" | "comic sans ms" => Some("cursive".to_owned()),
        _ => Some(trimmed),
    }
}

fn parse_alignment(value: &str) -> Option<Align> {
    match value.trim().to_ascii_lowercase().as_str() {
        "left" => Some(Align::Left),
        "right" => Some(Align::Right),
        "start" | "initial" => Some(Align::Start),
        "end" => Some(Align::End),
        "center" => Some(Align::Center),
        "justify" => Some(Align::Justify),
        _ => None,
    }
}

#[derive(Clone, Copy)]
struct CssLength {
    em: Option<f32>,
    fraction: Option<f32>,
}

struct CssMargins {
    before: Option<CssLength>,
    after: Option<CssLength>,
    left: Option<CssLength>,
    right: Option<CssLength>,
}

fn parse_margin_values(values: &HashMap<&str, &str>) -> CssMargins {
    let top = values.get("margin-top").and_then(|v| parse_length(v));
    let right = values.get("margin-right").and_then(|v| parse_length(v));
    let bottom = values.get("margin-bottom").and_then(|v| parse_length(v));
    let left = values.get("margin-left").and_then(|v| parse_length(v));
    CssMargins {
        before: top.filter(|l| l.em.is_some()),
        after: bottom.filter(|l| l.em.is_some()),
        left,
        right,
    }
}

fn parse_length(value: &str) -> Option<CssLength> {
    let raw = value.trim().to_ascii_lowercase();
    if matches!(raw.as_str(), "0" | "+0" | "-0") {
        return Some(CssLength {
            em: Some(0.0),
            fraction: None,
        });
    }
    let parsed = if let Some(num) = raw.strip_suffix('%') {
        parse_f32_finite(num).map(|n| CssLength {
            em: None,
            fraction: Some((n / 100.0).clamp(0.0, 0.45)),
        })
    } else if let Some(num) = raw.strip_suffix("rem") {
        parse_f32_finite(num).map(|n| CssLength {
            em: Some(n),
            fraction: None,
        })
    } else if let Some(num) = raw.strip_suffix("em") {
        parse_f32_finite(num).map(|n| CssLength {
            em: Some(n),
            fraction: None,
        })
    } else if let Some(num) = raw.strip_suffix("px") {
        parse_f32_finite(num).map(|n| CssLength {
            em: Some(n / 16.0),
            fraction: None,
        })
    } else if let Some(num) = raw.strip_suffix("pt") {
        parse_f32_finite(num).map(|n| CssLength {
            em: Some(n / 12.0),
            fraction: None,
        })
    } else {
        None
    };
    parsed.map(|l| CssLength {
        em: l.em.filter(|v| v.is_finite()).map(|v| v.clamp(0.0, 6.0)),
        fraction: l.fraction,
    })
}

fn resolve_line_height(
    value: Option<&str>,
    font_scale: f32,
    inherited: Option<&CssComputed>,
) -> (Option<f32>, Option<f32>, Option<f32>) {
    let inherit_fn = || -> (Option<f32>, Option<f32>, Option<f32>) {
        if let Some(u) = inherited.and_then(|i| i.unitless_line_height) {
            return (Some(u), None, Some(u));
        }
        if let Some(abs) = inherited.and_then(|i| i.absolute_line_height_em) {
            return (
                Some((abs / font_scale.max(0.1)).clamp(1.0, 2.4)),
                Some(abs),
                None,
            );
        }
        (inherited.and_then(|i| i.line_height_mult), None, None)
    };

    let Some(raw_str) = value else {
        return inherit_fn();
    };
    let raw = raw_str.trim().to_ascii_lowercase();
    if matches!(raw.as_str(), "inherit" | "unset") {
        return inherit_fn();
    }
    if matches!(raw.as_str(), "normal" | "initial") {
        return (None, None, None);
    }
    if let Some(num) = parse_f32_finite(&raw).filter(|&n| n > 0.0) {
        let safe = num.clamp(1.0, 2.4);
        return (Some(safe), None, Some(safe));
    }
    let abs = if let Some(num) = raw.strip_suffix('%') {
        parse_f32_finite(num).map(|n| (n / 100.0) * font_scale)
    } else if let Some(num) = raw.strip_suffix("rem") {
        parse_f32_finite(num)
    } else if let Some(num) = raw.strip_suffix("em") {
        parse_f32_finite(num).map(|n| n * font_scale)
    } else if let Some(num) = raw.strip_suffix("px") {
        parse_f32_finite(num).map(|n| n / 16.0)
    } else if let Some(num) = raw.strip_suffix("pt") {
        parse_f32_finite(num).map(|n| n / 12.0)
    } else {
        None
    }
    .filter(|&v| v.is_finite() && v > 0.0);

    if let Some(absolute) = abs {
        return (
            Some((absolute / font_scale.max(0.1)).clamp(1.0, 2.4)),
            Some(absolute),
            None,
        );
    }
    inherit_fn()
}

fn parse_decoration(value: &str) -> Option<i32> {
    let mut has_none = false;
    let mut mask = 0_i32;
    for token in value.split_whitespace() {
        let lower = token.to_ascii_lowercase();
        match lower.as_str() {
            "none" => has_none = true,
            "underline" => mask |= 1,
            "line-through" => mask |= 2,
            _ => {}
        }
    }
    if has_none {
        return Some(0);
    }
    (mask != 0).then_some(mask)
}

fn parse_page_break(value: &str) -> Option<bool> {
    match value.trim().to_ascii_lowercase().as_str() {
        "always" | "left" | "right" | "page" => Some(true),
        "auto" | "avoid" => Some(false),
        _ => None,
    }
}

#[inline]
fn parse_f32_finite(raw: &str) -> Option<f32> {
    raw.trim().parse::<f32>().ok().filter(|v| v.is_finite())
}

// -----------------------------------------------------------------------------
// XML Attribute Helpers
// -----------------------------------------------------------------------------

fn attr_unprefixed(tag: &BytesStart<'_>, name: &str) -> Option<String> {
    for attr in tag.html_attributes().with_checks(false).flatten() {
        if attr.key.into_inner() == name {
            return Some(super::decode_attr_value(&attr.value));
        }
    }
    None
}

fn href_of(tag: &BytesStart<'_>) -> Option<String> {
    super::find_attr_local(tag, "href")
}

fn language_of(tag: &BytesStart<'_>) -> Option<String> {
    let raw = super::find_attr_local(tag, "lang")?;
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(normalize_language_tag(Some(trimmed)).unwrap_or_else(|| trimmed.to_lowercase()))
}

fn safe_external_href(href: &str) -> bool {
    let Some((scheme, _)) = href.split_once(':') else {
        return false;
    };
    matches!(
        scheme.to_ascii_lowercase().as_str(),
        "http" | "https" | "mailto" | "tel"
    )
}

fn block_align_of(value: Option<&str>) -> Option<Align> {
    match value?.trim().to_ascii_lowercase().as_str() {
        "left" => Some(Align::Left),
        "right" => Some(Align::Right),
        "center" => Some(Align::Center),
        _ => None,
    }
}

fn with_language(block: Option<BlockStyle>, language: Option<&str>) -> Option<BlockStyle> {
    let Some(lang) = language else {
        return block;
    };
    let mut b = block.unwrap_or_default();
    b.language = Some(lang.to_owned());
    if b.direction.is_none() {
        b.direction = Some(if is_rtl_language(lang) {
            TextDir::Rtl
        } else {
            TextDir::Ltr
        });
    }
    Some(b)
}

fn heading_block(computed: &CssComputed, language: Option<&str>) -> Option<BlockStyle> {
    let centered = BlockStyle::centered();
    let applied = computed.apply_to(Some(&centered)).map(|mut b| {
        b.space_before_specified = computed.space_before_em.is_some();
        b.space_after_specified = computed.space_after_em.is_some();
        b
    });
    with_language(applied, language)
}

fn register_anchor(
    tag: &BytesStart<'_>,
    anchors: &mut HashMap<String, usize>,
    element_index: usize,
) {
    if let Some(raw_id) = attr_unprefixed(tag, "id") {
        let id = raw_id.trim();
        if !id.is_empty() {
            anchors.entry(format!("#{id}")).or_insert(element_index);
        }
    }
}

fn inline_style_for(tag: &str) -> Option<SpanStyle> {
    match tag {
        "emphasis" | "i" => Some(SpanStyle {
            italic: Some(true),
            ..SpanStyle::default()
        }),
        "strong" | "b" => Some(SpanStyle {
            weight: Some(700),
            ..SpanStyle::default()
        }),
        "strikethrough" => Some(SpanStyle {
            decoration: Some(2),
            ..SpanStyle::default()
        }),
        "code" => Some(SpanStyle {
            family: Some("mono"),
            ..SpanStyle::default()
        }),
        "sup" => Some(SpanStyle {
            baseline_shift: Some(0.5),
            font_size_em: Some(0.75),
            ..SpanStyle::default()
        }),
        "sub" => Some(SpanStyle {
            baseline_shift: Some(-0.5),
            font_size_em: Some(0.75),
            ..SpanStyle::default()
        }),
        _ => None,
    }
}

// -----------------------------------------------------------------------------
// FB2 Sanitization from Bytes or File
// -----------------------------------------------------------------------------

pub(crate) fn sanitize_bytes_bounded(
    bytes: &[u8],
    limits: &Fb2Limits,
) -> Result<String, Fb2ParseError> {
    if (bytes.len() as u64) > limits.max_fb2_bytes {
        return Err(Fb2ParseError::Limit {
            kind: LIMIT_KIND_ENTRY_SIZE,
            message: format!("FB2 document exceeds {} bytes", limits.max_fb2_bytes),
        });
    }
    let max_usize = usize::try_from(limits.max_fb2_bytes).unwrap_or(usize::MAX);
    let mut sanitizer = super::Fb2Sanitizer::with_max_input_bytes(false, max_usize);
    let mut out = Vec::with_capacity(bytes.len());
    sanitizer
        .feed(bytes, true, &mut out)
        .map_err(Fb2ParseError::Io)?;
    String::from_utf8(out).map_err(|_| Fb2ParseError::Io("Sanitized FB2 is not valid UTF-8".into()))
}

pub(crate) fn sanitize_file_bounded(
    source_path: &Path,
    limits: &Fb2Limits,
) -> Result<String, Fb2ParseError> {
    let mut file = File::open(source_path)
        .map_err(|e| Fb2ParseError::Io(format!("Failed to open FB2 file: {e}")))?;
    let initial_cap = file
        .metadata()
        .ok()
        .map(|m| m.len().min(limits.max_fb2_bytes).min(8 * 1024 * 1024) as usize)
        .unwrap_or(FILE_READ_CHUNK_BYTES);
    let max_usize = usize::try_from(limits.max_fb2_bytes).unwrap_or(usize::MAX);
    let mut sanitizer = super::Fb2Sanitizer::with_max_input_bytes(false, max_usize);
    let mut out = Vec::with_capacity(initial_cap);
    let mut buf = vec![0_u8; FILE_READ_CHUNK_BYTES];
    let mut total_read = 0_u64;

    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| Fb2ParseError::Io(format!("Failed to read FB2 file: {e}")))?;
        if n == 0 {
            sanitizer
                .feed(&[], true, &mut out)
                .map_err(Fb2ParseError::Io)?;
            break;
        }
        total_read = total_read.saturating_add(n as u64);
        if total_read > limits.max_fb2_bytes {
            return Err(Fb2ParseError::Limit {
                kind: LIMIT_KIND_ENTRY_SIZE,
                message: format!("FB2 document exceeds {} bytes", limits.max_fb2_bytes),
            });
        }
        sanitizer
            .feed(&buf[..n], false, &mut out)
            .map_err(Fb2ParseError::Io)?;
    }

    String::from_utf8(out).map_err(|_| Fb2ParseError::Io("Sanitized FB2 is not valid UTF-8".into()))
}

// -----------------------------------------------------------------------------
// Recursive-Descent / Streaming Event Body Parser
// -----------------------------------------------------------------------------

struct RawChapter {
    depth: i32,
    title: Option<String>,
    elements: Vec<Element>,
    anchors: HashMap<String, usize>,
}

struct ParserState {
    limits: Fb2Limits,
    chapters: Vec<ParsedChapter>,
    referenced_images: HashSet<String>,
    binaries: Vec<(String, BinaryAsset)>,
    binary_index: HashMap<String, usize>,
    notes: Vec<(String, ParsedNote)>,
    note_index: HashMap<String, usize>,
    anchor_locations: HashMap<String, (i32, i32)>,
    anchor_order: Vec<String>,
    body_index: usize,
    css_sheets: Vec<String>,
    stylesheet: Fb2Stylesheet,
    stylesheet_count: usize,
    stylesheet_bytes: u64,
    in_title_info: bool,
    language: Option<String>,
    binary_count: usize,
    binary_bytes: u64,
    has_main_sections: bool,
    xml_error: Option<String>,
}

impl ParserState {
    fn new(limits: Fb2Limits) -> Self {
        Self {
            limits,
            chapters: Vec::new(),
            referenced_images: HashSet::new(),
            binaries: Vec::new(),
            binary_index: HashMap::new(),
            notes: Vec::new(),
            note_index: HashMap::new(),
            anchor_locations: HashMap::new(),
            anchor_order: Vec::new(),
            body_index: 0,
            css_sheets: Vec::new(),
            stylesheet: Fb2Stylesheet::empty(),
            stylesheet_count: 0,
            stylesheet_bytes: 0,
            in_title_info: false,
            language: None,
            binary_count: 0,
            binary_bytes: 0,
            has_main_sections: false,
            xml_error: None,
        }
    }

    fn record_anchor(&mut self, key: String, target: (i32, i32)) {
        if !self.anchor_locations.contains_key(&key) {
            self.anchor_order.push(key.clone());
            self.anchor_locations.insert(key, target);
        }
    }

    fn record_note(&mut self, key: String, note: ParsedNote) {
        if let Some(&idx) = self.note_index.get(&key) {
            self.notes[idx].1 = note;
        } else {
            let idx = self.notes.len();
            self.note_index.insert(key.clone(), idx);
            self.notes.push((key, note));
        }
    }

    fn record_binary(&mut self, id: String, asset: BinaryAsset) {
        if let Some(&idx) = self.binary_index.get(&id) {
            self.binaries[idx].1 = asset;
        } else {
            let idx = self.binaries.len();
            self.binary_index.insert(id.clone(), idx);
            self.binaries.push((id, asset));
        }
    }

    fn finish_unresolved(self) -> Result<UnresolvedFb2Book, Fb2ParseError> {
        if let Some(err) = self.xml_error {
            if self.chapters.is_empty() && self.notes.is_empty() {
                return Err(Fb2ParseError::Io(err));
            }
        }
        let link_targets = self
            .anchor_order
            .into_iter()
            .filter_map(|k| self.anchor_locations.get(&k).copied().map(|v| (k, v)))
            .collect();
        Ok(UnresolvedFb2Book {
            chapters: self.chapters,
            notes: self.notes,
            link_targets,
            declared_language: self.language,
            binaries: self.binaries,
            has_main_sections: self.has_main_sections,
        })
    }
}

fn new_xml_reader(xml: &str) -> Reader<&[u8]> {
    let mut reader = Reader::from_str(xml);
    let cfg = reader.config_mut();
    cfg.allow_dangling_amp = true;
    cfg.allow_unmatched_ends = true;
    cfg.check_end_names = false;
    reader
}

pub(crate) fn parse_fb2_unresolved_str(
    sanitized_xml: &str,
    limits: Fb2Limits,
) -> Result<UnresolvedFb2Book, Fb2ParseError> {
    let mut reader = new_xml_reader(sanitized_xml);
    let mut state = ParserState::new(limits);
    parse_document(&mut reader, &mut state)?;
    state.finish_unresolved()
}

pub(crate) fn parse_fb2_unresolved_bytes(
    raw_bytes: &[u8],
    limits: Fb2Limits,
) -> Result<UnresolvedFb2Book, Fb2ParseError> {
    let sanitized = sanitize_bytes_bounded(raw_bytes, &limits)?;
    parse_fb2_unresolved_str(&sanitized, limits)
}

pub(crate) fn parse_fb2_unresolved_file(
    source_path: &Path,
    limits: Fb2Limits,
) -> Result<UnresolvedFb2Book, Fb2ParseError> {
    let sanitized = sanitize_file_bounded(source_path, &limits)?;
    parse_fb2_unresolved_str(&sanitized, limits)
}

/// Parses only the requested `section_indices` using `Fb2StructureScanner` offsets,
/// skipping all other sections and skipping unreferenced/corrupted `<binary>` payloads.
pub(crate) fn parse_fb2_targeted_sections(
    raw_bytes: &[u8],
    section_indices: &[i32],
    images_dir: &Path,
    limits: Fb2Limits,
) -> Result<ParsedBook, Fb2ParseError> {
    let sanitized = sanitize_bytes_bounded(raw_bytes, &limits)?;
    let structure = super::scan_fb2_structure(raw_bytes).map_err(Fb2ParseError::Io)?;
    if (structure.max_section_depth_seen as usize) > limits.max_fb2_structural_depth {
        return Err(Fb2ParseError::Limit {
            kind: LIMIT_KIND_STRUCTURAL_DEPTH,
            message: format!(
                "FB2 section nesting exceeds {} levels",
                limits.max_fb2_structural_depth
            ),
        });
    }

    let mut seen_indices = HashSet::new();
    let mut valid_targets = Vec::new();
    for &idx in section_indices {
        if idx >= 0 && seen_indices.insert(idx) {
            if let Some(sec) = structure.sections.get(idx as usize) {
                valid_targets.push(sec);
            }
        }
    }

    if valid_targets.is_empty() {
        return Ok(ParsedBook {
            chapters: Vec::new(),
            notes: Vec::new(),
            link_targets: Vec::new(),
            language: normalize_language_tag(
                (!structure.book_lang.is_empty()).then_some(structure.book_lang.as_str()),
            ),
        });
    }

    // Parse stylesheets up to limits
    let mut state = ParserState::new(limits);
    state.language = (!structure.book_lang.is_empty()).then(|| structure.book_lang.clone());
    for range in structure
        .stylesheets
        .iter()
        .take(limits.max_fb2_stylesheet_count)
    {
        let start = range.start as usize;
        let end = (range.end as usize).min(sanitized.len());
        if start >= end || !sanitized.is_char_boundary(start) || !sanitized.is_char_boundary(end) {
            continue;
        }
        let slice = &sanitized[start..end];
        let mut r = new_xml_reader(slice);
        loop {
            match r.read_event() {
                Ok(Event::Start(tag)) if tag.local_name().into_inner() == "stylesheet" => {
                    if let Some(sheet) =
                        read_element_text_limited(&mut r, limits.max_stylesheet_bytes, true)
                    {
                        let bytes = utf16_length(&sheet) as u64 * 2;
                        if bytes
                            <= limits
                                .max_fb2_stylesheet_aggregate_bytes
                                .saturating_sub(state.stylesheet_bytes)
                        {
                            state.css_sheets.push(sheet);
                            state.stylesheet_bytes += bytes;
                        }
                    }
                    break;
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }
    }
    state.stylesheet = Fb2Stylesheet::parse(&state.css_sheets);

    // Parse each target section
    for target in valid_targets {
        let start = target.start_offset as usize;
        let end = (target.end_offset as usize).min(sanitized.len());
        if start >= end || !sanitized.is_char_boundary(start) || !sanitized.is_char_boundary(end) {
            continue;
        }
        let section_slice = &sanitized[start..end];
        let inherited_lang = inherited_language_for_section(&structure, target);
        let mut r = new_xml_reader(section_slice);
        loop {
            match r.read_event() {
                Ok(Event::Start(tag)) if tag.local_name().into_inner() == "section" => {
                    let sub = parse_section_tree(
                        &mut r,
                        &tag,
                        &mut state,
                        target.depth as usize,
                        inherited_lang.as_deref(),
                    )?;
                    for raw in sub {
                        let ch_idx = state.chapters.len() as i32;
                        state.chapters.push(ParsedChapter {
                            title: raw.title,
                            elements: raw.elements,
                            depth: raw.depth,
                        });
                        for (k, elem_idx) in raw.anchors {
                            state.record_anchor(k, (ch_idx, elem_idx as i32));
                        }
                    }
                    break;
                }
                Ok(Event::Empty(tag)) if tag.local_name().into_inner() == "section" => break,
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }
    }

    // Extract ONLY binaries referenced by the parsed target sections
    if !state.referenced_images.is_empty() {
        let max_encoded = super::max_encoded_base64_bytes(limits.max_fb2_binary_bytes);
        let wanted_bins: Vec<_> = structure
            .binaries
            .iter()
            .filter(|b| state.referenced_images.contains(&b.id))
            .take(limits.max_fb2_binary_count)
            .collect();
        for bin in wanted_bins {
            let p_start = bin.payload_start_offset as usize;
            let p_end = (bin.payload_end_offset as usize).min(sanitized.len());
            if p_start >= p_end || (p_end - p_start) > max_encoded {
                continue;
            }
            if let Some(decoded) = super::decode_mime_base64(
                &sanitized.as_bytes()[p_start..p_end],
                limits.max_fb2_binary_bytes,
            ) {
                let len = decoded.len() as u64;
                if len
                    <= limits
                        .max_fb2_binary_aggregate_bytes
                        .saturating_sub(state.binary_bytes)
                {
                    state.record_binary(
                        bin.id.clone(),
                        BinaryAsset {
                            bytes: decoded,
                            content_type: (!bin.content_type.is_empty())
                                .then(|| bin.content_type.clone()),
                        },
                    );
                    state.binary_count += 1;
                    state.binary_bytes += len;
                }
            }
        }
    }

    let unresolved = state.finish_unresolved()?;
    let mut written_paths = HashMap::new();
    unresolved.resolve_all(images_dir, &mut written_paths)
}

fn inherited_language_for_section(
    structure: &super::Fb2Structure,
    section: &super::Fb2SectionInfo,
) -> Option<String> {
    let mut parent = section.parent_index;
    while parent >= 0 {
        let Some(anc) = structure.sections.get(parent as usize) else {
            break;
        };
        if !anc.lang.is_empty() {
            let trimmed = anc.lang.trim();
            if !trimmed.is_empty() {
                return Some(
                    normalize_language_tag(Some(trimmed)).unwrap_or_else(|| trimmed.to_lowercase()),
                );
            }
        }
        parent = anc.parent_index;
    }
    let body_lang = structure
        .bodies
        .get(section.body_index as usize)
        .map(|b| b.lang.trim())
        .filter(|s| !s.is_empty())?;
    Some(normalize_language_tag(Some(body_lang)).unwrap_or_else(|| body_lang.to_lowercase()))
}

fn parse_document(
    reader: &mut Reader<&[u8]>,
    state: &mut ParserState,
) -> Result<(), Fb2ParseError> {
    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "stylesheet" => {
                        let mime = attr_unprefixed(&tag, "type");
                        let is_css = mime
                            .as_deref()
                            .and_then(|s| s.split(';').next())
                            .map(str::trim)
                            .is_some_and(|s| s.eq_ignore_ascii_case("text/css"));
                        if state.body_index == 0 && is_css && {
                            let idx = state.stylesheet_count;
                            state.stylesheet_count += 1;
                            idx < state.limits.max_fb2_stylesheet_count
                        } {
                            if let Some(sheet) = read_element_text_limited(
                                reader,
                                state.limits.max_stylesheet_bytes,
                                true,
                            ) {
                                let bytes = utf16_length(&sheet) as u64 * 2;
                                if bytes
                                    <= state
                                        .limits
                                        .max_fb2_stylesheet_aggregate_bytes
                                        .saturating_sub(state.stylesheet_bytes)
                                {
                                    state.css_sheets.push(sheet);
                                    state.stylesheet_bytes += bytes;
                                }
                            }
                        } else {
                            skip_element(reader, state);
                        }
                    }
                    "title-info" => {
                        state.in_title_info = true;
                    }
                    "lang" if state.in_title_info && state.language.is_none() => {
                        if let Some(lang) = read_element_text_limited(reader, 256, true) {
                            state.language = Some(lang.trim().to_owned());
                        }
                    }
                    "body" => {
                        let body_lang = language_of(&tag);
                        if state.body_index == 0 {
                            state.stylesheet = Fb2Stylesheet::parse(&state.css_sheets);
                        }
                        let current_body = state.body_index;
                        state.body_index += 1;
                        if current_body == 0 {
                            parse_body(reader, state, body_lang.as_deref())?;
                        } else {
                            parse_notes_body(reader, state, body_lang.as_deref())?;
                        }
                    }
                    "binary" => {
                        let id = attr_unprefixed(&tag, "id");
                        if let Some(id) = id.filter(|id| state.referenced_images.contains(id)) {
                            let content_type = attr_unprefixed(&tag, "content-type");
                            if state.binary_count >= state.limits.max_fb2_binary_count {
                                skip_element(reader, state);
                            } else {
                                let max_encoded = super::max_encoded_base64_bytes(
                                    state.limits.max_fb2_binary_bytes,
                                );
                                if let Some(encoded) =
                                    read_element_text_limited(reader, max_encoded, false)
                                {
                                    if let Some(decoded) = super::decode_mime_base64(
                                        encoded.as_bytes(),
                                        state.limits.max_fb2_binary_bytes,
                                    ) {
                                        let len = decoded.len() as u64;
                                        if len
                                            <= state
                                                .limits
                                                .max_fb2_binary_aggregate_bytes
                                                .saturating_sub(state.binary_bytes)
                                        {
                                            state.record_binary(
                                                id,
                                                BinaryAsset {
                                                    bytes: decoded,
                                                    content_type,
                                                },
                                            );
                                            state.binary_count += 1;
                                            state.binary_bytes += len;
                                        }
                                    }
                                }
                            }
                        } else {
                            skip_element(reader, state);
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Empty(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "stylesheet" && state.body_index == 0 {
                    let mime = attr_unprefixed(&tag, "type");
                    let is_css = mime
                        .as_deref()
                        .and_then(|s| s.split(';').next())
                        .map(str::trim)
                        .is_some_and(|s| s.eq_ignore_ascii_case("text/css"));
                    if is_css {
                        state.stylesheet_count += 1;
                    }
                } else if local == "body" {
                    if state.body_index == 0 {
                        state.stylesheet = Fb2Stylesheet::parse(&state.css_sheets);
                    }
                    state.body_index += 1;
                }
            }
            Ok(Event::End(tag)) => {
                if tag.local_name().into_inner() == "title-info" {
                    state.in_title_info = false;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML: {e}"));
                break;
            }
            _ => {}
        }
    }
    Ok(())
}

fn parse_body(
    reader: &mut Reader<&[u8]>,
    state: &mut ParserState,
    inherited_lang: Option<&str>,
) -> Result<(), Fb2ParseError> {
    let mut preamble: Vec<Element> = Vec::new();
    let mut preamble_anchors: HashMap<String, usize> = HashMap::new();
    let mut collected: Vec<RawChapter> = Vec::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "title" => {
                        let title_block = title_block_of(&tag, &state.stylesheet, inherited_lang);
                        let title = collect_title(reader, &tag, state, inherited_lang);
                        if !title.text.is_empty() {
                            preamble.push(Element::Heading {
                                styled_text: title,
                                level: 1,
                                block: title_block,
                            });
                        }
                    }
                    "section" => {
                        state.has_main_sections = true;
                        let sub = parse_section_tree(reader, &tag, state, 0, inherited_lang)?;
                        collected.extend(sub);
                    }
                    _ => {
                        register_anchor(&tag, &mut preamble_anchors, preamble.len());
                        handle_block(
                            reader,
                            &tag,
                            false,
                            &mut preamble,
                            state,
                            ParaStyle::Normal,
                            None,
                            inherited_lang,
                            0,
                        )?;
                    }
                }
            }
            Ok(Event::Empty(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "title" | "section" => {
                        if local == "section" {
                            state.has_main_sections = true;
                        }
                    }
                    _ => {
                        register_anchor(&tag, &mut preamble_anchors, preamble.len());
                        handle_block(
                            reader,
                            &tag,
                            true,
                            &mut preamble,
                            state,
                            ParaStyle::Normal,
                            None,
                            inherited_lang,
                            0,
                        )?;
                    }
                }
            }
            Ok(Event::End(tag)) => {
                if tag.local_name().into_inner() == "body" {
                    break;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in body: {e}"));
                break;
            }
            _ => {}
        }
    }

    if collected.is_empty() {
        if !preamble.is_empty() {
            let ch_idx = state.chapters.len() as i32;
            state.chapters.push(ParsedChapter {
                title: None,
                elements: preamble,
                depth: 0,
            });
            for (k, elem_idx) in preamble_anchors {
                state.record_anchor(k, (ch_idx, elem_idx as i32));
            }
        }
        return Ok(());
    }

    if !preamble.is_empty() {
        let preamble_len = preamble.len();
        let first = &mut collected[0];
        for idx in first.anchors.values_mut() {
            *idx += preamble_len;
        }
        for (k, idx) in preamble_anchors {
            first.anchors.entry(k).or_insert(idx);
        }
        let mut combined = preamble;
        combined.append(&mut first.elements);
        first.elements = combined;
    }

    for raw in collected {
        let ch_idx = state.chapters.len() as i32;
        state.chapters.push(ParsedChapter {
            title: raw.title,
            elements: raw.elements,
            depth: raw.depth,
        });
        for (k, elem_idx) in raw.anchors {
            state.record_anchor(k, (ch_idx, elem_idx as i32));
        }
    }
    Ok(())
}

fn parse_section_tree(
    reader: &mut Reader<&[u8]>,
    start_tag: &BytesStart<'_>,
    state: &mut ParserState,
    depth: usize,
    inherited_lang: Option<&str>,
) -> Result<Vec<RawChapter>, Fb2ParseError> {
    if depth >= state.limits.max_fb2_structural_depth {
        return Err(Fb2ParseError::Limit {
            kind: LIMIT_KIND_STRUCTURAL_DEPTH,
            message: format!(
                "FB2 section nesting exceeds {} levels",
                state.limits.max_fb2_structural_depth
            ),
        });
    }
    let own_lang = language_of(start_tag);
    let section_lang = own_lang.as_deref().or(inherited_lang);

    let mut result: Vec<RawChapter> = Vec::new();
    let mut own_title: Option<String> = None;
    let mut own_elements: Vec<Element> = Vec::new();
    let mut own_anchors: HashMap<String, usize> = HashMap::new();
    register_anchor(start_tag, &mut own_anchors, 0);

    let flush_own = |result: &mut Vec<RawChapter>,
                     own_title: &mut Option<String>,
                     own_elements: &mut Vec<Element>,
                     own_anchors: &mut HashMap<String, usize>| {
        if own_title.is_some() || !own_elements.is_empty() {
            result.push(RawChapter {
                depth: depth as i32,
                title: own_title.take(),
                elements: std::mem::take(own_elements),
                anchors: std::mem::take(own_anchors),
            });
        }
    };

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "title" => {
                        let title_block = title_block_of(&tag, &state.stylesheet, section_lang);
                        let title = collect_title(reader, &tag, state, section_lang);
                        if !title.text.is_empty() {
                            let plain_title = title.text.replace(INLINE_IMAGE_CHAR, "");
                            let trimmed = plain_title.trim();
                            if own_title.is_none() && result.is_empty() && !trimmed.is_empty() {
                                own_title = Some(trimmed.to_owned());
                            }
                            let sink = if let Some(last) = result.last_mut() {
                                &mut last.elements
                            } else {
                                &mut own_elements
                            };
                            sink.push(Element::Heading {
                                styled_text: title,
                                level: ((2 + depth).min(6)) as i32,
                                block: title_block,
                            });
                        }
                    }
                    "section" => {
                        let mut sub =
                            parse_section_tree(reader, &tag, state, depth + 1, section_lang)?;
                        if sub.len() == 1 && sub[0].title.is_none() {
                            let child = sub.remove(0);
                            let (sink, anchor_sink) = if let Some(last) = result.last_mut() {
                                (&mut last.elements, &mut last.anchors)
                            } else {
                                (&mut own_elements, &mut own_anchors)
                            };
                            let offset = sink.len();
                            for (k, idx) in child.anchors {
                                anchor_sink.entry(k).or_insert(offset + idx);
                            }
                            sink.extend(child.elements);
                        } else if !sub.is_empty() {
                            flush_own(
                                &mut result,
                                &mut own_title,
                                &mut own_elements,
                                &mut own_anchors,
                            );
                            for (k, _) in own_anchors.drain() {
                                sub[0].anchors.entry(k).or_insert(0);
                            }
                            result.extend(sub);
                        }
                    }
                    _ => {
                        let (sink, anchor_sink) = if let Some(last) = result.last_mut() {
                            (&mut last.elements, &mut last.anchors)
                        } else {
                            (&mut own_elements, &mut own_anchors)
                        };
                        register_anchor(&tag, anchor_sink, sink.len());
                        handle_block(
                            reader,
                            &tag,
                            false,
                            sink,
                            state,
                            ParaStyle::Normal,
                            None,
                            section_lang,
                            0,
                        )?;
                    }
                }
            }
            Ok(Event::Empty(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "title" | "section" => {}
                    _ => {
                        let (sink, anchor_sink) = if let Some(last) = result.last_mut() {
                            (&mut last.elements, &mut last.anchors)
                        } else {
                            (&mut own_elements, &mut own_anchors)
                        };
                        register_anchor(&tag, anchor_sink, sink.len());
                        handle_block(
                            reader,
                            &tag,
                            true,
                            sink,
                            state,
                            ParaStyle::Normal,
                            None,
                            section_lang,
                            0,
                        )?;
                    }
                }
            }
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "section" || local == "body" {
                    break;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in section: {e}"));
                break;
            }
            _ => {}
        }
    }

    flush_own(
        &mut result,
        &mut own_title,
        &mut own_elements,
        &mut own_anchors,
    );
    Ok(result)
}

fn parse_notes_body(
    reader: &mut Reader<&[u8]>,
    state: &mut ParserState,
    inherited_lang: Option<&str>,
) -> Result<(), Fb2ParseError> {
    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => match tag.local_name().into_inner() {
                "section" => {
                    let _ = parse_note_section(reader, &tag, state, inherited_lang, 0)?;
                }
                "title" => skip_element(reader, state),
                _ => {}
            },
            Ok(Event::Empty(_)) => {}
            Ok(Event::End(tag)) => {
                if tag.local_name().into_inner() == "body" {
                    return Ok(());
                }
            }
            Ok(Event::Eof) => return Ok(()),
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in notes body: {e}"));
                return Ok(());
            }
            _ => {}
        }
    }
}

fn parse_note_section(
    reader: &mut Reader<&[u8]>,
    start_tag: &BytesStart<'_>,
    state: &mut ParserState,
    inherited_lang: Option<&str>,
    depth: usize,
) -> Result<Vec<Element>, Fb2ParseError> {
    if depth >= state.limits.max_fb2_structural_depth {
        return Err(Fb2ParseError::Limit {
            kind: LIMIT_KIND_STRUCTURAL_DEPTH,
            message: format!(
                "FB2 note-section nesting exceeds {} levels",
                state.limits.max_fb2_structural_depth
            ),
        });
    }
    let id = attr_unprefixed(start_tag, "id");
    let own_lang = language_of(start_tag);
    let section_lang = own_lang.as_deref().or(inherited_lang);
    let mut elements: Vec<Element> = Vec::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let local = tag.local_name().into_inner();
                match local {
                    "section" => {
                        let nested =
                            parse_note_section(reader, &tag, state, section_lang, depth + 1)?;
                        elements.extend(nested);
                    }
                    "title" => {
                        let title_block = title_block_of(&tag, &state.stylesheet, section_lang);
                        let title = collect_title(reader, &tag, state, section_lang);
                        if !title.text.trim().is_empty() {
                            elements.push(Element::Heading {
                                styled_text: title,
                                level: ((4 + depth).min(6)) as i32,
                                block: title_block,
                            });
                        }
                    }
                    "date" | "v" => {
                        let inline = parse_inline(
                            reader,
                            &tag,
                            false,
                            state,
                            None,
                            section_lang,
                            false,
                            None,
                        )
                        .build();
                        if !inline.text.is_empty() {
                            elements.push(Element::Paragraph {
                                text: inline,
                                style: if local == "v" {
                                    ParaStyle::Poem
                                } else {
                                    ParaStyle::Normal
                                },
                                block: None,
                            });
                        }
                    }
                    _ => {
                        handle_block(
                            reader,
                            &tag,
                            false,
                            &mut elements,
                            state,
                            ParaStyle::Normal,
                            None,
                            section_lang,
                            0,
                        )?;
                    }
                }
            }
            Ok(Event::Empty(tag)) => {
                let local = tag.local_name().into_inner();
                if !matches!(local, "section" | "title" | "date" | "v") {
                    handle_block(
                        reader,
                        &tag,
                        true,
                        &mut elements,
                        state,
                        ParaStyle::Normal,
                        None,
                        section_lang,
                        0,
                    )?;
                }
            }
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "section" || local == "body" {
                    break;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in note section: {e}"));
                break;
            }
            _ => {}
        }
    }

    if let Some(id) = id {
        if !elements.is_empty() {
            state.record_note(format!("#{id}"), ParsedNote { elements });
        }
        return Ok(Vec::new());
    }
    Ok(elements)
}

#[allow(clippy::too_many_arguments)]
fn handle_block(
    reader: &mut Reader<&[u8]>,
    tag: &BytesStart<'_>,
    empty: bool,
    out: &mut Vec<Element>,
    state: &mut ParserState,
    style: ParaStyle,
    block: Option<&BlockStyle>,
    inherited_lang: Option<&str>,
    container_depth: usize,
) -> Result<(), Fb2ParseError> {
    let local = tag.local_name().into_inner();
    match local {
        "p" => {
            let own_lang = language_of(tag);
            let lang = own_lang.as_deref().or(inherited_lang);
            let style_attr = attr_unprefixed(tag, "style");
            let computed = state.stylesheet.computed("p", style_attr.as_deref(), None);
            let inline = parse_inline(reader, tag, empty, state, Some(&computed), lang, true, None);
            if !inline.is_blank() {
                out.push(Element::Paragraph {
                    text: inline.build(),
                    style,
                    block: with_language(computed.apply_to(block), lang),
                });
            } else {
                for img_ref in inline.image_refs {
                    out.push(Element::Image {
                        path: img_ref,
                        alt_text: None,
                    });
                }
            }
        }
        "text-author" => {
            let own_lang = language_of(tag);
            let lang = own_lang.as_deref().or(inherited_lang);
            let style_attr = attr_unprefixed(tag, "style");
            let computed = state
                .stylesheet
                .computed("text-author", style_attr.as_deref(), None);
            let inline = parse_inline(reader, tag, empty, state, Some(&computed), lang, true, None);
            if !inline.is_blank() {
                let ta_block = BlockStyle::text_author();
                out.push(Element::Paragraph {
                    text: inline.build(),
                    style: ParaStyle::Quote,
                    block: with_language(computed.apply_to(Some(&ta_block)), lang),
                });
            } else {
                for img_ref in inline.image_refs {
                    out.push(Element::Image {
                        path: img_ref,
                        alt_text: None,
                    });
                }
            }
        }
        "subtitle" => {
            let own_lang = language_of(tag);
            let lang = own_lang.as_deref().or(inherited_lang);
            let style_attr = attr_unprefixed(tag, "style");
            let computed = state
                .stylesheet
                .computed("subtitle", style_attr.as_deref(), None);
            let text =
                parse_inline(reader, tag, empty, state, Some(&computed), lang, true, None).build();
            if !text.text.is_empty() {
                out.push(Element::Heading {
                    styled_text: text,
                    level: 4,
                    block: heading_block(&computed, lang),
                });
            }
        }
        "empty-line" => {
            out.push(Element::Spacer { height_em: 1.0 });
            if !empty {
                skip_element(reader, state);
            }
        }
        "image" => {
            if let Some(href) = href_of(tag) {
                let id = href.strip_prefix('#').unwrap_or(&href);
                state.referenced_images.insert(id.to_owned());
                let alt = attr_unprefixed(tag, "alt")
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty())
                    .or_else(|| {
                        attr_unprefixed(tag, "title")
                            .map(|s| s.trim().to_owned())
                            .filter(|s| !s.is_empty())
                    });
                out.push(Element::Image {
                    path: format!("#{id}"),
                    alt_text: alt,
                });
            }
            if !empty {
                skip_element(reader, state);
            }
        }
        "epigraph" => {
            if !empty {
                let epi = BlockStyle::epigraph();
                parse_container(
                    reader,
                    tag,
                    "epigraph",
                    out,
                    state,
                    ParaStyle::Quote,
                    Some(&epi),
                    inherited_lang,
                    container_depth,
                )?;
            }
        }
        "cite" => {
            if !empty {
                parse_container(
                    reader,
                    tag,
                    "cite",
                    out,
                    state,
                    ParaStyle::Quote,
                    None,
                    inherited_lang,
                    container_depth,
                )?;
            }
        }
        "annotation" => {
            if !empty {
                parse_container(
                    reader,
                    tag,
                    "annotation",
                    out,
                    state,
                    ParaStyle::Normal,
                    block,
                    inherited_lang,
                    container_depth,
                )?;
            }
        }
        "poem" => {
            if !empty {
                parse_poem(
                    reader,
                    tag,
                    out,
                    state,
                    block,
                    inherited_lang,
                    container_depth,
                )?;
            }
        }
        "table" => {
            if !empty {
                let own_lang = language_of(tag);
                let table_lang = own_lang.as_deref().or(inherited_lang);
                let (rows, table_style) = parse_table(reader, tag, state, table_lang);
                let mut fallback_block =
                    with_language(table_style.apply_to(block), table_lang).unwrap_or_default();
                fallback_block.first_line_indent = Some(false);
                let column_count = rows
                    .iter()
                    .map(|r| r.cells.iter().map(|c| c.col_span).sum::<i32>())
                    .max()
                    .unwrap_or(0);
                if rows.is_empty() || column_count == 0 {
                    // skip empty table
                } else if column_count == 1 {
                    for row in rows {
                        for cell in row.cells {
                            if !cell.text.text.trim().is_empty() {
                                out.push(Element::Paragraph {
                                    text: cell.text,
                                    style,
                                    block: Some(fallback_block.clone()),
                                });
                            }
                        }
                    }
                } else if column_count > 12 || rows.len() > 400 {
                    for row in rows {
                        let joined = row
                            .cells
                            .iter()
                            .map(|c| c.text.text.trim())
                            .collect::<Vec<_>>()
                            .join("    ");
                        let trimmed = joined.trim();
                        if !trimmed.is_empty() {
                            out.push(Element::Paragraph {
                                text: RichText::plain(trimmed.to_owned()),
                                style,
                                block: Some(fallback_block.clone()),
                            });
                        }
                    }
                } else {
                    out.push(Element::Table {
                        rows,
                        block: with_language(table_style.apply_to(block), table_lang),
                        publisher_box: table_style.publisher_box,
                    });
                }
            }
        }
        "style" => {
            let name_attr = attr_unprefixed(tag, "name");
            let computed = state.stylesheet.computed_named(name_attr.as_deref());
            let text = parse_inline(
                reader,
                tag,
                empty,
                state,
                Some(&computed),
                inherited_lang,
                false,
                None,
            )
            .build();
            if !text.text.is_empty() {
                out.push(Element::Paragraph {
                    text,
                    style,
                    block: block.cloned(),
                });
            }
        }
        _ => {
            if !empty {
                skip_element(reader, state);
            }
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn parse_container(
    reader: &mut Reader<&[u8]>,
    start_tag: &BytesStart<'_>,
    container_name: &str,
    out: &mut Vec<Element>,
    state: &mut ParserState,
    style: ParaStyle,
    block: Option<&BlockStyle>,
    inherited_lang: Option<&str>,
    container_depth: usize,
) -> Result<(), Fb2ParseError> {
    if container_depth >= MAX_CONTAINER_DEPTH {
        skip_element(reader, state);
        return Ok(());
    }
    let own_lang = language_of(start_tag);
    let container_lang = own_lang.as_deref().or(inherited_lang);

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                handle_block(
                    reader,
                    &tag,
                    false,
                    out,
                    state,
                    style,
                    block,
                    container_lang,
                    container_depth + 1,
                )?;
            }
            Ok(Event::Empty(tag)) => {
                handle_block(
                    reader,
                    &tag,
                    true,
                    out,
                    state,
                    style,
                    block,
                    container_lang,
                    container_depth + 1,
                )?;
            }
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == container_name || local == "section" || local == "body" {
                    return Ok(());
                }
            }
            Ok(Event::Eof) => return Ok(()),
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in {container_name}: {e}"));
                return Ok(());
            }
            _ => {}
        }
    }
}

fn parse_table(
    reader: &mut Reader<&[u8]>,
    table_tag: &BytesStart<'_>,
    state: &mut ParserState,
    inherited_lang: Option<&str>,
) -> (Vec<TableRow>, CssComputed) {
    let table_style_attr = attr_unprefixed(table_tag, "style");
    let table_style = state
        .stylesheet
        .computed("table", table_style_attr.as_deref(), None);
    let mut rows = Vec::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => match tag.local_name().into_inner() {
                "tr" => {
                    let row_align_attr = attr_unprefixed(&tag, "align");
                    let row_align = block_align_of(row_align_attr.as_deref());
                    let row_style_attr = attr_unprefixed(&tag, "style");
                    let row_style = state.stylesheet.computed(
                        "tr",
                        row_style_attr.as_deref(),
                        Some(&table_style),
                    );
                    let mut cells = Vec::new();
                    loop {
                        match reader.read_event() {
                            Ok(Event::Start(cell_tag)) => {
                                let cell_name = cell_tag.local_name().into_inner();
                                if cell_name == "td" || cell_name == "th" {
                                    cells.push(parse_table_cell(
                                        reader,
                                        &cell_tag,
                                        cell_name,
                                        false,
                                        state,
                                        &table_style,
                                        &row_style,
                                        row_align,
                                        inherited_lang,
                                    ));
                                } else {
                                    skip_element(reader, state);
                                }
                            }
                            Ok(Event::Empty(cell_tag)) => {
                                let cell_name = cell_tag.local_name().into_inner();
                                if cell_name == "td" || cell_name == "th" {
                                    cells.push(parse_table_cell(
                                        reader,
                                        &cell_tag,
                                        cell_name,
                                        true,
                                        state,
                                        &table_style,
                                        &row_style,
                                        row_align,
                                        inherited_lang,
                                    ));
                                }
                            }
                            Ok(Event::End(end_tag)) => {
                                let end_name = end_tag.local_name().into_inner();
                                if end_name == "tr" {
                                    break;
                                }
                                if end_name == "table" {
                                    if !cells.is_empty() {
                                        let is_header = cells.iter().all(|c| c.header);
                                        rows.push(TableRow { cells, is_header });
                                    }
                                    return (rows, table_style);
                                }
                            }
                            Ok(Event::Eof) => break,
                            Err(e) => {
                                state.xml_error = Some(format!("Malformed FB2 XML in tr: {e}"));
                                break;
                            }
                            _ => {}
                        }
                    }
                    if !cells.is_empty() {
                        let is_header = cells.iter().all(|c| c.header);
                        rows.push(TableRow { cells, is_header });
                    }
                }
                _ => skip_element(reader, state),
            },
            Ok(Event::Empty(_)) => {}
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "table" || local == "section" || local == "body" {
                    return (rows, table_style);
                }
            }
            Ok(Event::Eof) => return (rows, table_style),
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in table: {e}"));
                return (rows, table_style);
            }
            _ => {}
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn parse_table_cell(
    reader: &mut Reader<&[u8]>,
    cell_tag: &BytesStart<'_>,
    element: &str,
    empty: bool,
    state: &mut ParserState,
    table_style: &CssComputed,
    row_style: &CssComputed,
    row_align: Option<Align>,
    inherited_lang: Option<&str>,
) -> TableCell {
    let header = element == "th";
    let own_lang = language_of(cell_tag);
    let cell_lang = own_lang.as_deref().or(inherited_lang);
    let col_span = attr_unprefixed(cell_tag, "colspan")
        .and_then(|v| v.trim().parse::<i32>().ok())
        .map_or(1, |v| v.clamp(1, 10));
    let row_span = attr_unprefixed(cell_tag, "rowspan")
        .and_then(|v| v.trim().parse::<i32>().ok())
        .map_or(1, |v| v.clamp(1, 20));
    let cell_align_attr = attr_unprefixed(cell_tag, "align");
    let presentational_align = block_align_of(cell_align_attr.as_deref()).or(row_align);
    let cell_style_attr = attr_unprefixed(cell_tag, "style");
    let computed = state
        .stylesheet
        .computed(element, cell_style_attr.as_deref(), Some(row_style));
    let text = parse_inline(
        reader,
        cell_tag,
        empty,
        state,
        Some(&computed),
        cell_lang,
        false,
        Some(table_style),
    )
    .build();
    let align = computed
        .align
        .or(presentational_align)
        .or_else(|| header.then_some(Align::Center));
    let block = with_language(
        computed.color_block_style(Some(table_style), row_style.background_color_argb),
        cell_lang,
    );
    TableCell {
        text,
        col_span,
        row_span,
        align,
        header,
        block,
        publisher_box: computed.publisher_box,
        publisher_border_specified: computed.border_specified
            || row_style.borderless
            || table_style.border_specified,
    }
}

fn parse_poem(
    reader: &mut Reader<&[u8]>,
    poem_tag: &BytesStart<'_>,
    out: &mut Vec<Element>,
    state: &mut ParserState,
    block: Option<&BlockStyle>,
    inherited_lang: Option<&str>,
    container_depth: usize,
) -> Result<(), Fb2ParseError> {
    if container_depth >= MAX_CONTAINER_DEPTH {
        skip_element(reader, state);
        return Ok(());
    }
    let own_lang = language_of(poem_tag);
    let poem_lang = own_lang.as_deref().or(inherited_lang);

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => match tag.local_name().into_inner() {
                "title" => {
                    let title_block = title_block_of(&tag, &state.stylesheet, poem_lang);
                    let title = collect_title(reader, &tag, state, poem_lang);
                    if !title.text.is_empty() {
                        out.push(Element::Heading {
                            styled_text: title,
                            level: 4,
                            block: title_block,
                        });
                    }
                }
                "stanza" => {
                    parse_stanza(reader, out, state, block, poem_lang, container_depth + 1)?;
                }
                "text-author" | "epigraph" => {
                    handle_block(
                        reader,
                        &tag,
                        false,
                        out,
                        state,
                        ParaStyle::Quote,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                "subtitle" => {
                    handle_block(
                        reader,
                        &tag,
                        false,
                        out,
                        state,
                        ParaStyle::Poem,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                "date" => {
                    let date =
                        parse_inline(reader, &tag, false, state, None, poem_lang, false, None)
                            .build();
                    if !date.text.is_empty() {
                        out.push(Element::Paragraph {
                            text: date,
                            style: ParaStyle::Poem,
                            block: Some(BlockStyle::text_author()),
                        });
                    }
                }
                _ => skip_element(reader, state),
            },
            Ok(Event::Empty(tag)) => match tag.local_name().into_inner() {
                "text-author" | "epigraph" => {
                    handle_block(
                        reader,
                        &tag,
                        true,
                        out,
                        state,
                        ParaStyle::Quote,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                "subtitle" => {
                    handle_block(
                        reader,
                        &tag,
                        true,
                        out,
                        state,
                        ParaStyle::Poem,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                _ => {}
            },
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "poem" || local == "section" || local == "body" {
                    return Ok(());
                }
            }
            Ok(Event::Eof) => return Ok(()),
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in poem: {e}"));
                return Ok(());
            }
            _ => {}
        }
    }
}

fn parse_stanza(
    reader: &mut Reader<&[u8]>,
    out: &mut Vec<Element>,
    state: &mut ParserState,
    block: Option<&BlockStyle>,
    poem_lang: Option<&str>,
    container_depth: usize,
) -> Result<(), Fb2ParseError> {
    let mut stanza = InlineBuilder::new();
    let mut first_line = true;
    let mut verse_count = 0_usize;
    let mut stanza_lang: Option<String> = None;
    let mut one_stanza_lang = true;

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => match tag.local_name().into_inner() {
                "title" => {
                    let title_block = title_block_of(&tag, &state.stylesheet, poem_lang);
                    let title = collect_title(reader, &tag, state, poem_lang);
                    if !title.text.is_empty() {
                        out.push(Element::Heading {
                            styled_text: title,
                            level: 5,
                            block: title_block,
                        });
                    }
                }
                "subtitle" => {
                    handle_block(
                        reader,
                        &tag,
                        false,
                        out,
                        state,
                        ParaStyle::Poem,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                "v" => {
                    if !first_line {
                        stanza.line_break();
                    }
                    first_line = false;
                    let own_lang = language_of(&tag);
                    let verse_lang = own_lang.as_deref().or(poem_lang);
                    if verse_count == 0 {
                        stanza_lang = verse_lang.map(str::to_owned);
                    } else if verse_lang != stanza_lang.as_deref() {
                        one_stanza_lang = false;
                    }
                    verse_count += 1;

                    let v_style_attr = attr_unprefixed(&tag, "style");
                    let computed = state
                        .stylesheet
                        .computed("v", v_style_attr.as_deref(), None);
                    let mut pushes = 0_usize;
                    if let Some(span) = computed.span_style(None) {
                        stanza.push_style(span);
                        pushes += 1;
                    }
                    if let Some(lang) = verse_lang {
                        stanza.push_style(SpanStyle {
                            locales: Some(vec![lang.to_owned()]),
                            ..SpanStyle::default()
                        });
                        pushes += 1;
                    }
                    append_inline(reader, "v", &mut stanza, state);
                    for _ in 0..pushes {
                        stanza.pop();
                    }
                }
                _ => skip_element(reader, state),
            },
            Ok(Event::Empty(tag)) => match tag.local_name().into_inner() {
                "v" => {
                    if !first_line {
                        stanza.line_break();
                    }
                    first_line = false;
                    let own_lang = language_of(&tag);
                    let verse_lang = own_lang.as_deref().or(poem_lang);
                    if verse_count == 0 {
                        stanza_lang = verse_lang.map(str::to_owned);
                    } else if verse_lang != stanza_lang.as_deref() {
                        one_stanza_lang = false;
                    }
                    verse_count += 1;
                }
                "subtitle" => {
                    handle_block(
                        reader,
                        &tag,
                        true,
                        out,
                        state,
                        ParaStyle::Poem,
                        block,
                        poem_lang,
                        container_depth + 1,
                    )?;
                }
                _ => {}
            },
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "stanza" || local == "poem" || local == "section" || local == "body" {
                    break;
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in stanza: {e}"));
                break;
            }
            _ => {}
        }
    }

    let stanza_text = stanza.build();
    if !stanza_text.text.is_empty() {
        let effective_lang = if one_stanza_lang {
            stanza_lang.as_deref()
        } else {
            None
        };
        out.push(Element::Paragraph {
            text: stanza_text,
            style: ParaStyle::Poem,
            block: with_language(block.cloned(), effective_lang),
        });
    }
    Ok(())
}

fn title_block_of(
    title_tag: &BytesStart<'_>,
    stylesheet: &Fb2Stylesheet,
    inherited_lang: Option<&str>,
) -> Option<BlockStyle> {
    let style_attr = attr_unprefixed(title_tag, "style");
    let computed = stylesheet.computed("title", style_attr.as_deref(), None);
    let own_lang = language_of(title_tag);
    heading_block(&computed, own_lang.as_deref().or(inherited_lang))
}

fn collect_title(
    reader: &mut Reader<&[u8]>,
    title_tag: &BytesStart<'_>,
    state: &mut ParserState,
    inherited_lang: Option<&str>,
) -> RichText {
    let mut parts: Vec<(RichText, Option<i32>)> = Vec::new();
    let title_style_attr = attr_unprefixed(title_tag, "style");
    let title_style = state
        .stylesheet
        .computed("title", title_style_attr.as_deref(), None);
    let own_lang = language_of(title_tag);
    let title_lang = own_lang.as_deref().or(inherited_lang);

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => match tag.local_name().into_inner() {
                "p" => {
                    let p_style_attr = attr_unprefixed(&tag, "style");
                    let computed =
                        state
                            .stylesheet
                            .computed("p", p_style_attr.as_deref(), Some(&title_style));
                    let inline = parse_inline(
                        reader,
                        &tag,
                        false,
                        state,
                        Some(&computed),
                        title_lang,
                        false,
                        Some(&title_style),
                    )
                    .build();
                    if !inline.text.is_empty() {
                        let align_code = align_to_compose_text_align(computed.align);
                        parts.push((inline, Some(align_code)));
                    }
                }
                "empty-line" => {
                    parts.push((RichText::default(), None));
                    skip_element(reader, state);
                }
                _ => skip_element(reader, state),
            },
            Ok(Event::Empty(tag)) => {
                if tag.local_name().into_inner() == "empty-line" {
                    parts.push((RichText::default(), None));
                }
            }
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if local == "title" || local == "section" || local == "body" {
                    return join_annotated(parts);
                }
            }
            Ok(Event::Eof) => return join_annotated(parts),
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in title: {e}"));
                return join_annotated(parts);
            }
            _ => {}
        }
    }
}

fn align_to_compose_text_align(align: Option<Align>) -> i32 {
    match align {
        None => 0,
        Some(Align::Left) => 1,
        Some(Align::Right) => 2,
        Some(Align::Center) => 3,
        Some(Align::Justify) => 4,
        Some(Align::Start) => 5,
        Some(Align::End) => 6,
    }
}

fn join_annotated(parts: Vec<(RichText, Option<i32>)>) -> RichText {
    let mut distinct_aligns: Vec<i32> = Vec::new();
    for (_, align) in &parts {
        if let Some(code) = *align {
            if !distinct_aligns.contains(&code) {
                distinct_aligns.push(code);
            }
        }
    }
    let uniform = distinct_aligns.len() <= 1;

    let mut text = String::new();
    let mut utf16_len = 0_i32;
    let mut ranges: Vec<TextRange> = Vec::new();

    for (index, (part, align)) in parts.into_iter().enumerate() {
        if index > 0 {
            text.push('\n');
            utf16_len += 1;
        }
        let start = utf16_len;
        text.push_str(&part.text);
        utf16_len += part.utf16_len;
        for mut r in part.ranges {
            r.start += start;
            r.end += start;
            ranges.push(r);
        }
        if !uniform {
            ranges.push(TextRange {
                start,
                end: utf16_len,
                item: TextAnnotation::ParagraphAlign(align.unwrap_or(0)),
            });
        }
    }

    if uniform && distinct_aligns.len() == 1 && distinct_aligns[0] != 0 {
        ranges.push(TextRange {
            start: 0,
            end: utf16_len,
            item: TextAnnotation::ParagraphAlign(distinct_aligns[0]),
        });
    }

    RichText {
        text,
        utf16_len,
        ranges,
    }
}

#[allow(clippy::too_many_arguments)]
fn parse_inline(
    reader: &mut Reader<&[u8]>,
    start_tag: &BytesStart<'_>,
    empty: bool,
    state: &mut ParserState,
    parent_style: Option<&CssComputed>,
    inherited_lang: Option<&str>,
    block_style_applied: bool,
    relative_to: Option<&CssComputed>,
) -> InlineBuilder {
    let mut builder = InlineBuilder::new();
    let own_lang = language_of(start_tag);
    let lang = own_lang.as_deref().or(inherited_lang);

    let mut pushes = 0_usize;
    if let Some(ps) = parent_style {
        let style = if block_style_applied {
            ps.decoration_span_style()
        } else {
            ps.span_style(relative_to)
        };
        if let Some(span) = style {
            builder.push_style(span);
            pushes += 1;
        }
    }
    if let Some(lang_tag) = lang {
        builder.push_style(SpanStyle {
            locales: Some(vec![lang_tag.to_owned()]),
            ..SpanStyle::default()
        });
        pushes += 1;
    }

    if !empty {
        let enclosing = start_tag.local_name().into_inner().to_owned();
        append_inline(reader, &enclosing, &mut builder, state);
    }

    for _ in 0..pushes {
        builder.pop();
    }
    builder
}

fn append_inline(
    reader: &mut Reader<&[u8]>,
    enclosing_tag: &str,
    out: &mut InlineBuilder,
    state: &mut ParserState,
) {
    let mut pushed: Vec<usize> = Vec::new();
    loop {
        match reader.read_event() {
            Ok(Event::Text(text)) => {
                out.text(&text);
            }
            Ok(Event::CData(cdata)) => {
                out.text(&cdata);
            }
            Ok(Event::GeneralRef(reference)) => {
                let resolved = super::resolve_ref(&reference);
                out.text(&resolved);
            }
            Ok(Event::Start(tag)) => {
                let pushes = push_inline_tag(&tag, out, state);
                pushed.push(pushes);
            }
            Ok(Event::Empty(tag)) => {
                let pushes = push_inline_tag(&tag, out, state);
                for _ in 0..pushes {
                    out.pop();
                }
            }
            Ok(Event::End(tag)) => {
                let local = tag.local_name().into_inner();
                if pushed.is_empty() {
                    let _ = enclosing_tag;
                    return;
                }
                // If closing tag matches our enclosing block element (missing inner end tag),
                // unwind all remaining inline styles and exit cleanly.
                if local == enclosing_tag
                    && !matches!(
                        local,
                        "emphasis"
                            | "i"
                            | "strong"
                            | "b"
                            | "strikethrough"
                            | "code"
                            | "sup"
                            | "sub"
                            | "a"
                            | "style"
                            | "image"
                    )
                {
                    while let Some(count) = pushed.pop() {
                        for _ in 0..count {
                            out.pop();
                        }
                    }
                    return;
                }
                if let Some(count) = pushed.pop() {
                    for _ in 0..count {
                        out.pop();
                    }
                }
            }
            Ok(Event::Eof) => {
                while let Some(count) = pushed.pop() {
                    for _ in 0..count {
                        out.pop();
                    }
                }
                return;
            }
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML in inline: {e}"));
                while let Some(count) = pushed.pop() {
                    for _ in 0..count {
                        out.pop();
                    }
                }
                return;
            }
            _ => {}
        }
    }
}

fn push_inline_tag(
    tag: &BytesStart<'_>,
    out: &mut InlineBuilder,
    state: &mut ParserState,
) -> usize {
    let local = tag.local_name().into_inner();
    let mut pushes = 0_usize;

    if local == "image" {
        if let Some(href) = href_of(tag) {
            let id = href.strip_prefix('#').unwrap_or(&href);
            state.referenced_images.insert(id.to_owned());
            let alt = attr_unprefixed(tag, "alt")
                .map(|s| s.trim().to_owned())
                .filter(|s| !s.is_empty())
                .or_else(|| {
                    attr_unprefixed(tag, "title")
                        .map(|s| s.trim().to_owned())
                        .filter(|s| !s.is_empty())
                });
            out.inline_image(format!("#{id}"), alt.as_deref());
        }
    }

    if local == "a" {
        if let Some(href) = href_of(tag) {
            if href.starts_with('#') {
                let is_note =
                    attr_unprefixed(tag, "type").is_some_and(|t| t.eq_ignore_ascii_case("note"));
                let ann_tag = if is_note { FOOTNOTE_TAG } else { LINK_TAG };
                out.push_annotation(ann_tag, href);
                pushes += 1;
            } else if safe_external_href(&href) {
                out.push_annotation(EXTERNAL_LINK_TAG, href);
                pushes += 1;
            }
        }
    }

    if local == "style" {
        let name = attr_unprefixed(tag, "name");
        if let Some(named_style) = state
            .stylesheet
            .computed_named(name.as_deref())
            .span_style(None)
        {
            out.push_style(named_style);
            pushes += 1;
        }
    }

    if let Some(inline_lang) = language_of(tag) {
        out.push_style(SpanStyle {
            locales: Some(vec![inline_lang.clone()]),
            ..SpanStyle::default()
        });
        pushes += 1;
        out.push_annotation(
            BIDI_TAG,
            if is_rtl_language(&inline_lang) {
                "ISOLATE_RTL".to_owned()
            } else {
                "ISOLATE_LTR".to_owned()
            },
        );
        pushes += 1;
    }

    if let Some(style) = inline_style_for(local) {
        out.push_style(style);
        pushes += 1;
    }

    pushes
}

fn read_element_text_limited(
    reader: &mut Reader<&[u8]>,
    max_units: usize,
    count_utf16: bool,
) -> Option<String> {
    let mut out = String::with_capacity(max_units.min(16 * 1024));
    let mut current_units = 0_usize;
    let mut oversized = false;
    let mut nested_depth = 0_usize;

    loop {
        match reader.read_event() {
            Ok(Event::Start(_)) => {
                nested_depth += 1;
            }
            Ok(Event::End(_)) => {
                if nested_depth == 0 {
                    return (!oversized).then_some(out);
                }
                nested_depth -= 1;
            }
            Ok(Event::Text(text)) if nested_depth == 0 => {
                if !oversized {
                    append_limited(
                        &mut out,
                        &mut current_units,
                        &mut oversized,
                        &text,
                        max_units,
                        count_utf16,
                    );
                }
            }
            Ok(Event::CData(cdata)) if nested_depth == 0 => {
                if !oversized {
                    append_limited(
                        &mut out,
                        &mut current_units,
                        &mut oversized,
                        &cdata,
                        max_units,
                        count_utf16,
                    );
                }
            }
            Ok(Event::GeneralRef(reference)) if nested_depth == 0 => {
                if !oversized {
                    let resolved = super::resolve_ref(&reference);
                    append_limited(
                        &mut out,
                        &mut current_units,
                        &mut oversized,
                        &resolved,
                        max_units,
                        count_utf16,
                    );
                }
            }
            Ok(Event::Eof) | Err(_) => return None,
            _ => {}
        }
    }
}

#[inline]
fn append_limited(
    out: &mut String,
    current_units: &mut usize,
    oversized: &mut bool,
    piece: &str,
    max_units: usize,
    count_utf16: bool,
) {
    let add = if count_utf16 {
        piece.chars().map(char::len_utf16).sum::<usize>()
    } else {
        piece.len()
    };
    if add > max_units.saturating_sub(*current_units) {
        *oversized = true;
        out.clear();
    } else {
        out.push_str(piece);
        *current_units += add;
    }
}

fn skip_element(reader: &mut Reader<&[u8]>, state: &mut ParserState) {
    let mut depth = 1_usize;
    while depth > 0 {
        match reader.read_event() {
            Ok(Event::Start(_)) => depth += 1,
            Ok(Event::End(_)) => depth -= 1,
            Ok(Event::Eof) => return,
            Err(e) => {
                state.xml_error = Some(format!("Malformed FB2 XML while skipping element: {e}"));
                return;
            }
            _ => {}
        }
    }
}

// -----------------------------------------------------------------------------
// Image Extraction & Placeholder Resolution
// -----------------------------------------------------------------------------

fn collect_referenced_images_in_elements(elements: &[Element], out: &mut HashSet<String>) {
    for el in elements {
        match el {
            Element::Image { path, .. } => {
                out.insert(path.strip_prefix('#').unwrap_or(path).to_owned());
            }
            Element::Paragraph { text, .. } => collect_inline_image_ids(text, out),
            Element::Heading { styled_text, .. } => collect_inline_image_ids(styled_text, out),
            Element::Table { rows, .. } => {
                for row in rows {
                    for cell in &row.cells {
                        collect_inline_image_ids(&cell.text, out);
                    }
                }
            }
            Element::Spacer { .. } => {}
        }
    }
}

fn collect_inline_image_ids(text: &RichText, out: &mut HashSet<String>) {
    for r in &text.ranges {
        if let TextAnnotation::String { tag, value } = &r.item {
            if tag == INLINE_IMAGE_TAG {
                out.insert(value.strip_prefix('#').unwrap_or(value).to_owned());
            }
        }
    }
}

fn extract_selected_binaries(
    binaries: &[(String, BinaryAsset)],
    filter: Option<&HashSet<String>>,
    images_dir: &Path,
    written_paths: &mut HashMap<String, String>,
) -> Result<(), Fb2ParseError> {
    let mut dir_created = false;
    for (id, asset) in binaries {
        if let Some(wanted) = filter {
            if !wanted.contains(id) {
                continue;
            }
        }
        if written_paths.contains_key(id) {
            continue;
        }
        if !dir_created {
            fs::create_dir_all(images_dir).map_err(|e| {
                Fb2ParseError::Io(format!("Failed to create FB2 images directory: {e}"))
            })?;
            dir_created = true;
        }
        let file_name = binary_file_name(id, asset.content_type.as_deref());
        let file_path = images_dir.join(&file_name);
        let skip_write = fs::metadata(&file_path)
            .ok()
            .is_some_and(|m| m.is_file() && m.len() == asset.bytes.len() as u64)
            && fs::read(&file_path).ok().as_deref() == Some(asset.bytes.as_slice());
        if !skip_write {
            fs::write(&file_path, &asset.bytes).map_err(|e| {
                Fb2ParseError::Io(format!("Failed to write FB2 image {file_name}: {e}"))
            })?;
        }
        written_paths.insert(id.clone(), file_path.to_string_lossy().into_owned());
    }
    Ok(())
}

fn resolve_elements(elements: &[Element], paths: &HashMap<String, String>) -> Vec<Element> {
    let mut out = Vec::with_capacity(elements.len());
    for element in elements {
        match element {
            Element::Image { path, alt_text } => {
                let key = path.strip_prefix('#').unwrap_or(path);
                if let Some(real_path) = paths.get(key) {
                    out.push(Element::Image {
                        path: real_path.clone(),
                        alt_text: alt_text.clone(),
                    });
                } else if let Some(alt) =
                    alt_text.as_deref().map(str::trim).filter(|s| !s.is_empty())
                {
                    out.push(Element::Paragraph {
                        text: RichText::plain(alt.to_owned()),
                        style: ParaStyle::Normal,
                        block: Some(BlockStyle {
                            align: Some(Align::Center),
                            first_line_indent: Some(false),
                            ..BlockStyle::default()
                        }),
                    });
                }
            }
            Element::Paragraph { text, style, block } => {
                out.push(Element::Paragraph {
                    text: resolve_inline_images(text, paths),
                    style: *style,
                    block: block.clone(),
                });
            }
            Element::Heading {
                styled_text,
                level,
                block,
            } => {
                out.push(Element::Heading {
                    styled_text: resolve_inline_images(styled_text, paths),
                    level: *level,
                    block: block.clone(),
                });
            }
            Element::Table {
                rows,
                block,
                publisher_box,
            } => {
                let resolved_rows = rows
                    .iter()
                    .map(|row| TableRow {
                        cells: row
                            .cells
                            .iter()
                            .map(|cell| TableCell {
                                text: resolve_inline_images(&cell.text, paths),
                                col_span: cell.col_span,
                                row_span: cell.row_span,
                                align: cell.align,
                                header: cell.header,
                                block: cell.block.clone(),
                                publisher_box: cell.publisher_box.clone(),
                                publisher_border_specified: cell.publisher_border_specified,
                            })
                            .collect(),
                        is_header: row.is_header,
                    })
                    .collect();
                out.push(Element::Table {
                    rows: resolved_rows,
                    block: block.clone(),
                    publisher_box: publisher_box.clone(),
                });
            }
            Element::Spacer { height_em } => out.push(Element::Spacer {
                height_em: *height_em,
            }),
        }
    }
    out
}

fn resolve_inline_images(text: &RichText, paths: &HashMap<String, String>) -> RichText {
    let mut marks: Vec<(i32, i32, &str)> = Vec::new();
    for r in &text.ranges {
        if let TextAnnotation::String { tag, value } = &r.item {
            if tag == INLINE_IMAGE_TAG {
                marks.push((r.start, r.end, value.as_str()));
            }
        }
    }
    if marks.is_empty() {
        return text.clone();
    }

    let mut removed: Vec<(i32, i32)> = marks
        .iter()
        .filter(|(_, _, item)| !paths.contains_key(item.strip_prefix('#').unwrap_or(item)))
        .map(|&(start, end, _)| (start, end))
        .collect();
    removed.sort_unstable_by_key(|&(start, _)| start);

    let shift = |offset: i32| -> i32 {
        let delta: i32 = removed
            .iter()
            .filter(|&&(_, end)| end <= offset)
            .map(|&(start, end)| end - start)
            .sum();
        offset - delta
    };
    let survives = |start: i32, end: i32| -> bool {
        !removed.iter().any(|&(rs, re)| rs == start && re == end)
    };

    // Rebuild plain text skipping removed UTF-16 ranges.
    let plain = if removed.is_empty() {
        text.text.clone()
    } else {
        let mut rebuilt = String::with_capacity(text.text.len());
        let mut u16_pos = 0_i32;
        let mut rem_idx = 0_usize;
        for ch in text.text.chars() {
            let ch_len = ch.len_utf16() as i32;
            while rem_idx < removed.len() && removed[rem_idx].1 <= u16_pos {
                rem_idx += 1;
            }
            let in_removed = rem_idx < removed.len()
                && u16_pos >= removed[rem_idx].0
                && u16_pos < removed[rem_idx].1;
            if !in_removed {
                rebuilt.push(ch);
            }
            u16_pos += ch_len;
        }
        rebuilt
    };

    let new_utf16_len = utf16_length(&plain);
    let mut new_ranges = Vec::with_capacity(text.ranges.len());

    // Emit spans, paragraph styles, and string annotations preserving relative behavior.
    for r in &text.ranges {
        match &r.item {
            TextAnnotation::Span(span) => {
                if survives(r.start, r.end) {
                    new_ranges.push(TextRange {
                        start: shift(r.start),
                        end: shift(r.end),
                        item: TextAnnotation::Span(span.clone()),
                    });
                }
            }
            TextAnnotation::ParagraphAlign(align) => {
                new_ranges.push(TextRange {
                    start: shift(r.start),
                    end: shift(r.end),
                    item: TextAnnotation::ParagraphAlign(*align),
                });
            }
            TextAnnotation::String { tag, value } => {
                if !survives(r.start, r.end) {
                    continue;
                }
                let is_image_ref = marks.iter().any(|&(ms, me, m_item)| {
                    ms == r.start && me == r.end && m_item == value.as_str()
                });
                let new_val = if is_image_ref {
                    paths
                        .get(value.strip_prefix('#').unwrap_or(value))
                        .cloned()
                        .unwrap_or_else(|| value.clone())
                } else {
                    value.clone()
                };
                new_ranges.push(TextRange {
                    start: shift(r.start),
                    end: shift(r.end),
                    item: TextAnnotation::String {
                        tag: tag.clone(),
                        value: new_val,
                    },
                });
            }
        }
    }

    RichText {
        text: plain,
        utf16_len: new_utf16_len,
        ranges: new_ranges,
    }
}

// -----------------------------------------------------------------------------
// Wire Envelope Helpers (`FB2C`)
// -----------------------------------------------------------------------------

pub(crate) fn encode_ok_full_wire(payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + payload.len());
    out.extend_from_slice(&FB2_CONTENT_MAGIC.to_le_bytes());
    out.push(FB2_STATUS_OK_FULL);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

pub(crate) fn encode_ok_preview_wire(handle: i64, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(17 + payload.len());
    out.extend_from_slice(&FB2_CONTENT_MAGIC.to_le_bytes());
    out.push(FB2_STATUS_OK_PREVIEW);
    out.extend_from_slice(&handle.to_le_bytes());
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(payload);
    out
}

pub(crate) fn encode_error_wire(err: &Fb2ParseError) -> Vec<u8> {
    match err {
        Fb2ParseError::Limit { kind, message } => {
            let mut out = Vec::with_capacity(10 + message.len());
            out.extend_from_slice(&FB2_CONTENT_MAGIC.to_le_bytes());
            out.push(FB2_STATUS_ERR_LIMIT);
            out.push(*kind);
            out.extend_from_slice(&(message.len() as u32).to_le_bytes());
            out.extend_from_slice(message.as_bytes());
            out
        }
        Fb2ParseError::Io(message) => {
            let mut out = Vec::with_capacity(9 + message.len());
            out.extend_from_slice(&FB2_CONTENT_MAGIC.to_le_bytes());
            out.push(FB2_STATUS_ERR_IO);
            out.extend_from_slice(&(message.len() as u32).to_le_bytes());
            out.extend_from_slice(message.as_bytes());
            out
        }
    }
}

pub(crate) fn preview_threshold_for(preview_element_index: i32) -> u64 {
    (preview_element_index.max(0) as u64).saturating_add(PREVIEW_BUFFER_ELEMENTS)
}

// -----------------------------------------------------------------------------
// Conversion to `crate::frb_model` & High-Level Entry Points
// -----------------------------------------------------------------------------

fn convert_align(align: Align) -> FrbBlockAlign {
    match align {
        Align::Start => FrbBlockAlign::Start,
        Align::Center => FrbBlockAlign::Center,
        Align::End => FrbBlockAlign::End,
        Align::Justify => FrbBlockAlign::Justify,
        Align::Left => FrbBlockAlign::Left,
        Align::Right => FrbBlockAlign::Right,
    }
}

fn convert_dir(dir: TextDir) -> FrbBookTextDirection {
    match dir {
        TextDir::Ltr => FrbBookTextDirection::Ltr,
        TextDir::Rtl => FrbBookTextDirection::Rtl,
    }
}

fn convert_para_style(style: ParaStyle) -> FrbParagraphStyle {
    match style {
        ParaStyle::Normal => FrbParagraphStyle::Normal,
        ParaStyle::Quote => FrbParagraphStyle::Quote,
        ParaStyle::Poem => FrbParagraphStyle::Poem,
    }
}

fn convert_border_side(side: BorderSide) -> FrbPublisherBorderSide {
    FrbPublisherBorderSide {
        width_em: side.width_em,
        color_argb: side.color_argb,
        style: match side.style {
            BorderStyleKind::Solid => FrbPublisherBorderStyle::Solid,
            BorderStyleKind::Dashed => FrbPublisherBorderStyle::Dashed,
            BorderStyleKind::Dotted => FrbPublisherBorderStyle::Dotted,
            BorderStyleKind::Double => FrbPublisherBorderStyle::Double,
        },
        uses_current_color: side.uses_current_color,
    }
}

fn convert_box_borders(borders: BoxBorders) -> FrbPublisherBoxStyle {
    FrbPublisherBoxStyle {
        border_top: borders.border_top.map(convert_border_side),
        border_right: borders.border_right.map(convert_border_side),
        border_bottom: borders.border_bottom.map(convert_border_side),
        border_left: borders.border_left.map(convert_border_side),
        ..FrbPublisherBoxStyle::default()
    }
}

fn convert_block_style(block: BlockStyle) -> FrbBlockStyle {
    FrbBlockStyle {
        align: block.align.map(convert_align),
        italic: block.italic,
        bold: block.bold,
        font_scale: block.font_scale,
        indent_start_frac: block.indent_start_frac,
        indent_start_em: block.indent_start_em,
        indent_end_frac: block.indent_end_frac,
        indent_end_em: block.indent_end_em,
        indent_left_frac: block.indent_left_frac,
        indent_left_em: block.indent_left_em,
        indent_right_frac: block.indent_right_frac,
        indent_right_em: block.indent_right_em,
        first_line_indent: block.first_line_indent,
        first_line_indent_em: block.first_line_indent_em,
        space_before_em: block.space_before_em,
        space_after_em: block.space_after_em,
        space_before_specified: block.space_before_specified,
        space_after_specified: block.space_after_specified,
        font_family: block.font_family,
        line_height_mult: block.line_height_mult,
        page_break_before: block.page_break_before,
        language: block.language,
        direction: block.direction.map(convert_dir),
        foreground_color_argb: block.foreground_color_argb,
        background_color_argb: block.background_color_argb,
        ..FrbBlockStyle::default()
    }
}

fn convert_span_style(span: SpanStyle) -> FrbSpanStyle {
    FrbSpanStyle {
        color: span
            .color_argb
            .map_or(COMPOSE_COLOR_UNSPECIFIED, color_argb_to_compose_ulong),
        size: span
            .font_size_em
            .map_or(FrbTextUnit::Unspecified, FrbTextUnit::Em),
        weight: span.weight,
        italic: span.italic,
        family: span.family.map(str::to_owned),
        baseline: span.baseline_shift,
        locales: span.locales,
        background: span
            .background_argb
            .map_or(COMPOSE_COLOR_UNSPECIFIED, color_argb_to_compose_ulong),
        decoration: span.decoration,
        ..FrbSpanStyle::default()
    }
}

fn convert_rich_text(rich: RichText) -> FrbAnnotatedString {
    let ranges = rich
        .ranges
        .into_iter()
        .map(|r| match r.item {
            TextAnnotation::String { tag, value } => {
                FrbTextRange::string(r.start, r.end, tag, value)
            }
            TextAnnotation::Span(span) => {
                FrbTextRange::span(r.start, r.end, convert_span_style(span))
            }
            TextAnnotation::ParagraphAlign(align) => FrbTextRange::paragraph(
                r.start,
                r.end,
                FrbParagraphAnnotation {
                    align,
                    ..FrbParagraphAnnotation::default()
                },
            ),
        })
        .collect();
    FrbAnnotatedString {
        text: rich.text,
        ranges,
    }
}

fn convert_element(element: Element) -> FrbContentElement {
    match element {
        Element::Paragraph { text, style, block } => FrbContentElement::Paragraph {
            text: convert_rich_text(text),
            style: convert_para_style(style),
            block: block.map(convert_block_style).map(Box::new),
        },
        Element::Heading {
            styled_text,
            level,
            block,
        } => FrbContentElement::Heading {
            styled_text: convert_rich_text(styled_text),
            level,
            block: block.map(convert_block_style).map(Box::new),
        },
        Element::Image { path, alt_text } => {
            let mut img = FrbImageElement::new(path);
            img.alt_text = alt_text;
            FrbContentElement::Image(Box::new(img))
        }
        Element::Spacer { height_em } => FrbContentElement::Spacer { height_em },
        Element::Table {
            rows,
            block,
            publisher_box,
        } => {
            let frb_rows = rows
                .into_iter()
                .map(|row| FrbTableRow {
                    cells: row
                        .cells
                        .into_iter()
                        .map(|cell| {
                            let mut c = FrbTableCell::new(convert_rich_text(cell.text));
                            c.col_span = cell.col_span;
                            c.row_span = cell.row_span;
                            c.align = cell.align.map(convert_align);
                            c.header = cell.header;
                            c.block = cell.block.map(convert_block_style).map(Box::new);
                            c.publisher_box =
                                cell.publisher_box.map(convert_box_borders).map(Box::new);
                            c.publisher_border_specified = cell.publisher_border_specified;
                            c
                        })
                        .collect(),
                    is_header: row.is_header,
                })
                .collect();
            let mut table = FrbTableElement::new(frb_rows);
            table.block = block.map(convert_block_style).map(Box::new);
            table.publisher_box = publisher_box.map(convert_box_borders).map(Box::new);
            FrbContentElement::Table(Box::new(table))
        }
    }
}

pub(crate) fn into_frb_book_content(book: ParsedBook) -> FrbBookContent {
    let chapters = book
        .chapters
        .into_iter()
        .map(|ch| FrbChapter {
            title: ch.title,
            elements: ch.elements.into_iter().map(convert_element).collect(),
            depth: ch.depth,
            publisher_boxes: Vec::new(),
        })
        .collect();
    let notes: BTreeMap<String, FrbNoteDocument> = book
        .notes
        .into_iter()
        .map(|(k, note)| {
            (
                k,
                FrbNoteDocument {
                    elements: note.elements.into_iter().map(convert_element).collect(),
                    publisher_boxes: Vec::new(),
                },
            )
        })
        .collect();
    let link_targets: BTreeMap<String, (i32, i32)> = book.link_targets.into_iter().collect();
    FrbBookContent {
        chapters,
        notes,
        link_targets,
        fonts: Vec::new(),
        language: book.language,
        linked_documents: BTreeMap::new(),
        linked_document_targets: BTreeMap::new(),
        navigation: Vec::new(),
        publisher_publication: None,
        page_progression: FrbPageProgression::Default,
        epub_source_ranges: Vec::new(),
    }
}

pub(crate) fn parse_fb2_content_wire(
    bytes: &[u8],
    images_dir: &Path,
    limits: Fb2Limits,
) -> Vec<u8> {
    let res = (|| -> Result<Vec<u8>, Fb2ParseError> {
        let unresolved = parse_fb2_unresolved_bytes(bytes, limits)?;
        let mut written = HashMap::new();
        let parsed = unresolved.resolve_all(images_dir, &mut written)?;
        let frb = into_frb_book_content(parsed);
        let payload = frb_model::encode_book_content(&frb);
        Ok(encode_ok_full_wire(&payload))
    })();
    match res {
        Ok(wire) => wire,
        Err(err) => encode_error_wire(&err),
    }
}

pub(crate) fn parse_fb2_content_file_wire(
    source_path: &Path,
    images_dir: &Path,
    limits: Fb2Limits,
) -> Vec<u8> {
    let res = (|| -> Result<Vec<u8>, Fb2ParseError> {
        let unresolved = parse_fb2_unresolved_file(source_path, limits)?;
        let mut written = HashMap::new();
        let parsed = unresolved.resolve_all(images_dir, &mut written)?;
        let frb = into_frb_book_content(parsed);
        let payload = frb_model::encode_book_content(&frb);
        Ok(encode_ok_full_wire(&payload))
    })();
    match res {
        Ok(wire) => wire,
        Err(err) => encode_error_wire(&err),
    }
}

pub(crate) fn write_fb2_frb_file_wire(
    source_path: &Path,
    images_dir: &Path,
    dest_frb_path: &Path,
    source_metadata: &[u8],
    limits: Fb2Limits,
) -> Vec<u8> {
    let res = (|| -> Result<Vec<u8>, Fb2ParseError> {
        if dest_frb_path.exists() {
            return Err(Fb2ParseError::Io(
                "Destination FRB file already exists".to_owned(),
            ));
        }
        let unresolved = parse_fb2_unresolved_file(source_path, limits)?;
        let mut written = HashMap::new();
        let parsed = unresolved.resolve_all(images_dir, &mut written)?;
        let frb = into_frb_book_content(parsed);

        if let Some(parent) = dest_frb_path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| {
                    Fb2ParseError::Io(format!("Failed to create FRB destination dir: {e}"))
                })?;
            }
        }

        if let Err(err) = frb_model::write_book_frb(
            &frb,
            dest_frb_path,
            source_metadata,
            crate::frb::MAX_FILE_BYTES,
        ) {
            let _ = fs::remove_file(dest_frb_path);
            return Err(Fb2ParseError::Io(err));
        }
        Ok(encode_ok_full_wire(&[]))
    })();
    match res {
        Ok(wire) => wire,
        Err(err) => encode_error_wire(&err),
    }
}

pub(crate) fn parse_fb2_targeted_sections_wire(
    bytes: &[u8],
    section_indices: &[i32],
    images_dir: &Path,
    limits: Fb2Limits,
) -> Vec<u8> {
    match parse_fb2_targeted_sections(bytes, section_indices, images_dir, limits) {
        Ok(parsed) => {
            let frb = into_frb_book_content(parsed);
            let payload = frb_model::encode_book_content(&frb);
            encode_ok_full_wire(&payload)
        }
        Err(err) => encode_error_wire(&err),
    }
}

pub(crate) struct PendingFb2Parse {
    unresolved: UnresolvedFb2Book,
    images_dir: PathBuf,
    written_paths: HashMap<String, String>,
}

impl PendingFb2Parse {
    pub(crate) fn finish_wire(mut self) -> Vec<u8> {
        match self
            .unresolved
            .resolve_all(&self.images_dir, &mut self.written_paths)
        {
            Ok(parsed) => {
                let frb = into_frb_book_content(parsed);
                let payload = frb_model::encode_book_content(&frb);
                encode_ok_full_wire(&payload)
            }
            Err(err) => encode_error_wire(&err),
        }
    }
}

pub(crate) enum PreviewStartOutcome {
    FinishedWire(Vec<u8>),
    PreviewReady {
        preview_payload: Vec<u8>,
        pending: PendingFb2Parse,
    },
}

pub(crate) fn start_fb2_preview_parse(
    bytes: &[u8],
    images_dir: PathBuf,
    limits: Fb2Limits,
    preview_element_index: i32,
) -> PreviewStartOutcome {
    let unresolved = match parse_fb2_unresolved_bytes(bytes, limits) {
        Ok(book) => book,
        Err(err) => return PreviewStartOutcome::FinishedWire(encode_error_wire(&err)),
    };
    let threshold = preview_threshold_for(preview_element_index);
    let mut written_paths = HashMap::new();
    if let Some(end_ch) = unresolved.find_preview_end_chapter(threshold) {
        match unresolved.resolve_prefix(end_ch, &images_dir, &mut written_paths) {
            Ok(preview_book) => {
                let frb = into_frb_book_content(preview_book);
                let preview_payload = frb_model::encode_book_content(&frb);
                return PreviewStartOutcome::PreviewReady {
                    preview_payload,
                    pending: PendingFb2Parse {
                        unresolved,
                        images_dir,
                        written_paths,
                    },
                };
            }
            Err(err) => return PreviewStartOutcome::FinishedWire(encode_error_wire(&err)),
        }
    }
    match unresolved.resolve_all(&images_dir, &mut written_paths) {
        Ok(parsed) => {
            let frb = into_frb_book_content(parsed);
            let payload = frb_model::encode_book_content(&frb);
            PreviewStartOutcome::FinishedWire(encode_ok_full_wire(&payload))
        }
        Err(err) => PreviewStartOutcome::FinishedWire(encode_error_wire(&err)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_matches_standard_vectors() {
        let digest = sha256(b"abc");
        let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(
            hex,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn binary_file_name_matches_kotlin_resource_cache_naming() {
        let a = binary_file_name("a/b", Some("image/png"));
        let b = binary_file_name("a_b", Some("image/png"));
        assert_ne!(a, b);
        assert!(a.starts_with("fb2_"));
        assert!(a.ends_with("_a_b.png"));
        assert!(binary_file_name("ornament", Some("image/svg+xml")).ends_with("_ornament.svg"));
        assert!(binary_file_name("pic.jpeg", Some("image/jpeg")).ends_with("_pic.jpeg"));
        assert!(!binary_file_name("pic.jpeg", Some("image/jpeg")).ends_with(".jpg"));
    }

    #[test]
    fn stylesheet_cascade_and_caps_match_fb2_stylesheet_kt() {
        let css = r#"
            @charset "UTF-8";
            @namespace fb "urn:fb2;{string-boundary}";
            /* comment with p { broken: yes; } */
            body { font-size: 150%; line-height: 150%; color: #123456; background-color: linen; }
            p { font-size: 120%; background-color: rgb(240 248 255); }
            .p { margin: 3em; }
            p { margin-left: 1em; margin-right: 4em !important; }
            .p { margin-inline-end: 5em; }
            .marked { font-style: italic; text-decoration: underline; }
            style[name="marked"] { font-weight: bold; }
            @media print { p { font-size: 260%; } }
        "#;
        let sheet = Fb2Stylesheet::parse(&[css.to_owned()]);
        let p = sheet.computed("p", None, None);
        assert!((p.font_scale.unwrap() - 1.8).abs() < 0.0001);
        assert!((p.line_height_mult.unwrap() - 1.25).abs() < 0.0001);
        assert_eq!(p.indent_left_em, Some(3.0));
        assert_eq!(p.indent_right_em, Some(4.0));
        assert_eq!(p.foreground_color_argb, Some(0xFF12_3456_u32 as i32));
        assert_eq!(p.visual_background_color_argb, Some(0xFFF0_F8FF_u32 as i32));

        let marked = sheet.computed_named(Some("marked"));
        assert_eq!(marked.italic, Some(true));
        assert_eq!(marked.bold, Some(true));
        assert_eq!(marked.decoration, Some(1));
    }

    #[test]
    fn parses_mismatched_inline_tag_and_nested_sections_and_truncation() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info><book-title>Test</book-title><lang>ua</lang></title-info></description>
          <body>
            <section>
              <title><p>A <strong>rich</strong> <emphasis>chapter</em></p></title>
              <p>Text &amp; bare & and &mdash; entity.</p>
              <section><title><p>Sub 1</p></title><p>Child body.</p></section>
            </section>
          </body>
          <body name="notes">
            <section id="n1"><p>Note text.</p></section>
          </body>
        </FictionBook>"#;
        let unresolved = parse_fb2_unresolved_bytes(xml.as_bytes(), Fb2Limits::default()).unwrap();
        assert_eq!(unresolved.chapters.len(), 2);
        assert_eq!(
            unresolved.chapters[0].title.as_deref(),
            Some("A rich chapter")
        );
        assert_eq!(unresolved.chapters[0].depth, 0);
        assert_eq!(unresolved.chapters[1].title.as_deref(), Some("Sub 1"));
        assert_eq!(unresolved.chapters[1].depth, 1);
        assert_eq!(unresolved.notes.len(), 1);
        assert_eq!(unresolved.notes[0].0, "#n1");
        assert_eq!(
            normalize_language_tag(unresolved.declared_language.as_deref()).as_deref(),
            Some("uk")
        );
    }

    #[test]
    fn enforces_structural_depth_and_doc_size_limits() {
        let xml = "<FictionBook><body>".to_owned()
            + &"<section>".repeat(6)
            + "<p>Deep</p>"
            + &"</section>".repeat(6)
            + "</body></FictionBook>";
        let limits = Fb2Limits {
            max_fb2_structural_depth: 4,
            ..Fb2Limits::default()
        };
        let err = parse_fb2_unresolved_bytes(xml.as_bytes(), limits).unwrap_err();
        assert!(matches!(
            err,
            Fb2ParseError::Limit {
                kind: LIMIT_KIND_STRUCTURAL_DEPTH,
                ..
            }
        ));

        let size_limits = Fb2Limits {
            max_fb2_bytes: 32,
            ..Fb2Limits::default()
        };
        let err = parse_fb2_unresolved_bytes(xml.as_bytes(), size_limits).unwrap_err();
        assert!(matches!(
            err,
            Fb2ParseError::Limit {
                kind: LIMIT_KIND_ENTRY_SIZE,
                ..
            }
        ));
    }

    #[test]
    fn write_fb2_frb_file_writes_searchable_container_and_extracts_images() {
        let dir =
            std::env::temp_dir().join(format!("frogreader-fb2-body-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("sample.fb2");
        let images_dir = dir.join("images");
        let dest = dir.join("content.frb");

        let xml = r##"<?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info><book-title>FRB Test</book-title><lang>ru</lang></title-info></description>
          <body>
            <section id="ch1">
              <title><p>Глава 1</p></title>
              <p>Привет <strong>лягушка</strong><a l:href="#n1" type="note">[1]</a>!</p>
              <image l:href="#pic.png" alt="Logo"/>
            </section>
          </body>
          <body name="notes">
            <section id="n1"><p>Текст сноски.</p></section>
          </body>
          <binary id="pic.png" content-type="image/png">AAECAw==</binary>
        </FictionBook>"##;
        fs::write(&src, xml.as_bytes()).unwrap();

        let metadata = b"test-source-meta";
        let wire =
            write_fb2_frb_file_wire(&src, &images_dir, &dest, metadata, Fb2Limits::default());
        assert_eq!(&wire[..4], &FB2_CONTENT_MAGIC.to_le_bytes());
        assert_eq!(wire[4], FB2_STATUS_OK_FULL);
        assert!(dest.is_file());

        // Refuses existing file without overwriting.
        let before = fs::read(&dest).unwrap();
        let dup_wire =
            write_fb2_frb_file_wire(&src, &images_dir, &dest, metadata, Fb2Limits::default());
        assert_eq!(dup_wire[4], FB2_STATUS_ERR_IO);
        assert_eq!(before, fs::read(&dest).unwrap());

        // Verify frb::Reader and native book_search can read/search the emitted chapter block.
        let mut reader = crate::frb::Reader::open(&dest, metadata).unwrap();
        let ch0 = reader.read_block_bytes(2).unwrap();
        let results = crate::book_search::search_frb_chapter_payload(
            &ch0,
            0,
            0,
            Some(3),
            "лягушка",
            true,
            10,
        )
        .unwrap();
        assert_eq!(results.matches.len(), 1);

        let _ = fs::remove_dir_all(&dir);
    }
}
