//! Native Rust HTML/XHTML body-to-FRB mapper (Wave 11 M6.2b).
//!
//! Walks `chapter_markup::Document` directly (with optional CSS) and produces
//! `frb_model::FrbContentElement`, `FrbPublisherBoxSpan`, anchors, links, and
//! semantic note documents with 100% parity against Kotlin `HtmlMapper.kt` —
//! without constructing a Jsoup DOM or JVM `AnnotatedString`s.

#![allow(dead_code)]

#[path = "mathml.rs"]
mod mathml;

use crate::chapter_markup::{
    attribute_keys_equal, has_note_hint, is_java_regex_ws, kotlin_is_whitespace, Document, Kind,
    ATTR_ANC_HAS_DIR, ATTR_ANC_HAS_FLOW_SCOPE, ATTR_ANC_HAS_LANG, ATTR_ANC_HAS_SUP,
    ATTR_EPUB_NOTEREF, ATTR_HAS_DIR, ATTR_HAS_EMPTY_LINE_CLASS, ATTR_HAS_HREF, ATTR_HAS_ID,
    ATTR_HAS_LANG, ATTR_HAS_NOTE_HINT, ATTR_HAS_PRESENTATIONAL, ATTR_HAS_SRC, ATTR_HAS_STYLE,
    ATTR_HAS_XLINK_HREF, ATTR_IS_DISPLAY_MATH, ATTR_IS_SEMANTIC_NOTE_CONTAINER,
    ATTR_ROLE_DOC_NOTEREF, ATTR_TYPE_IS_NOTE, DOC_HAS_ANY_PRESENTATIONAL, DOC_HAS_ANY_STYLE,
    SUBTREE_HAS_BLOCK_PROBE, SUBTREE_HAS_CAPTION, SUBTREE_HAS_HORIZONTAL_COMPLEX, SUBTREE_HAS_ID,
    SUBTREE_HAS_IMG, SUBTREE_HAS_INLINE_FLOW_CANDIDATE, SUBTREE_HAS_JAVA_NON_WS_TEXT,
    SUBTREE_HAS_NESTED_LIST, SUBTREE_HAS_STYLE, SUBTREE_HAS_SUP, SUBTREE_HAS_SVG,
    SUBTREE_SIMPLE_LEAF_TEXT, TAG_A, TAG_BR, TAG_CAPTION, TAG_CELL_BLOCK, TAG_CSS_ROW_EXCLUDED,
    TAG_EXPLICIT_INITIAL, TAG_HEADING, TAG_IMAGE, TAG_IMG, TAG_INLINE_TEXT_BLOCK, TAG_MATH,
    TAG_NESTED_LIST, TAG_NON_BLOCK, TAG_NON_CONTENT, TAG_NON_TEXT_CONTENT, TAG_PUBLISHER_BOX,
    TAG_ROOT, TAG_SEMANTIC_MONOSPACE, TAG_SIMPLE_INLINE_TEXT_BOX, TAG_SUP, TAG_SVG, TAG_TABLE,
    TAG_TR, TEXT_ALREADY_COLLAPSED_ASCII, TEXT_ENDS_HTML_WS, TEXT_HAS_CR, TEXT_HAS_KOTLIN_NON_WS,
    TEXT_HTML_BLANK, TEXT_STARTS_HTML_WS, TITLE_CLASS_SHIFT,
};
use crate::css_color;
use crate::css_computed_model::ComputedStyle;
use crate::css_engine::{
    self, computed_bold, format_kotlin_float, ChapterCss, CompiledStylesheets, GeneratedRun,
};
use crate::frb_model::{
    color_argb_to_compose_ulong, FrbAnnotatedString, FrbBlockAlign, FrbBlockStyle,
    FrbBookTextDirection, FrbContentElement, FrbFirstLetterStyle, FrbFloatImage, FrbImageElement,
    FrbNoteDocument, FrbParagraphStyle, FrbPublisherBoxSpan, FrbPublisherBoxStyle,
    FrbPublisherClear, FrbPublisherFloatSide, FrbPublisherRowLayout, FrbSpanStyle, FrbTableCell,
    FrbTableCellStyle, FrbTableElement, FrbTableRow, FrbTextAnnotation, FrbTextRange, FrbTextUnit,
    BIDI_TAG, COMPOSE_COLOR_UNSPECIFIED, EXTERNAL_LINK_TAG, FOOTNOTE_TAG, HIDDEN_TEXT_TAG,
    INLINE_FONT_FAMILY_TAG, INLINE_FONT_TYPOGRAPHY_TAG, INLINE_IMAGE_ALT_TAG, INLINE_IMAGE_CHAR,
    INLINE_IMAGE_TAG, LINK_TAG, TEXT_DECORATION_LINE_THROUGH, TEXT_DECORATION_UNDERLINE,
};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;

// --------------------------------------------------------------------------- Constants

pub(crate) const DEFAULT_HTML_STRUCTURE_DEPTH: usize = 256;
pub(crate) const DEFAULT_MAX_GENERATED_RUN_CHARS: usize = 4 * 1024;
pub(crate) const DEFAULT_MAX_GENERATED_TOTAL_CHARS: usize = 1024 * 1024;

const INLINE_CONTENT_TAG: &str = "androidx.compose.foundation.text.inlineContent";
pub(crate) const INLINE_RUBY_TAG: &str = "frogreader:ruby";
pub(crate) const INLINE_TEXT_BOX_TAG: &str = "frogreader:inline-text-box";
pub(crate) const INLINE_MATH_TAG: &str = mathml::INLINE_MATH_TAG;
const AUDIO_PLACEHOLDER: &str = "♪ Audio is not supported";
const VIDEO_PLACEHOLDER: &str = "▶ Video is not supported";
const MARK_BG_ARGB: i32 = 0x66FF_D54F;
const MAX_CSS_ROW_CELLS: usize = 24;
const MAX_RESPONSIVE_TABLE_CELLS: usize = 4_096;
const MAX_RESPONSIVE_TABLE_DESCENDANTS: usize = 8_192;
const MAX_RUBY_READING_LENGTH: usize = 4_096;
const MAX_RUBY_SPANS: usize = 128;

// --------------------------------------------------------------------------- Public API Types

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum MapError {
    ComplexMarkup(&'static str),
    Internal(String),
}

pub(crate) trait ResourceCallbacks {
    fn resolve_image(&mut self, src: &str) -> Option<String>;
    fn resolve_link(&mut self, href: &str) -> Option<String>;

    /// Optional hook for parser-specific promoted notes (e.g. FB2-to-EPUB conversions).
    fn is_promoted_note(&mut self, _node_id: usize, _key: &str) -> bool {
        false
    }

    /// Writes inline vector `<svg>` markup to a file, returning its path.
    fn resolve_inline_svg(&mut self, _markup: &str) -> Option<String> {
        None
    }
}

#[derive(Clone, Debug)]
pub(crate) struct MapOptions {
    pub publisher_box_id_prefix: String,
    pub max_structure_depth: usize,
    pub max_generated_run_chars: usize,
    pub max_generated_total_chars: usize,
    pub remaining_generated_total_chars: usize,
    pub fb2_epub_conversion: bool,
}

impl Default for MapOptions {
    fn default() -> Self {
        Self {
            publisher_box_id_prefix: "document".to_owned(),
            max_structure_depth: DEFAULT_HTML_STRUCTURE_DEPTH,
            max_generated_run_chars: DEFAULT_MAX_GENERATED_RUN_CHARS,
            max_generated_total_chars: DEFAULT_MAX_GENERATED_TOTAL_CHARS,
            remaining_generated_total_chars: DEFAULT_MAX_GENERATED_TOTAL_CHARS,
            fb2_epub_conversion: false,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub(crate) struct MappedBody {
    pub elements: Vec<FrbContentElement>,
    pub publisher_boxes: Vec<FrbPublisherBoxSpan>,
    pub anchors: BTreeMap<String, i32>,
    pub link_targets: BTreeSet<String>,
    pub note_targets: BTreeSet<String>,
    pub note_documents: BTreeMap<String, FrbNoteDocument>,
    pub generated_chars_used: usize,
    pub was_aggregate_limited: bool,
}

/// Dark-mode image style attributes resolved by CSS.
#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct DarkImageStyle {
    pub image_invert: bool,
    pub background_color_argb: Option<i32>,
    pub background_uses_current_color: bool,
}

/// Pluggable CSS engine interface implemented by `css_engine::ChapterCss`.
pub(crate) trait CssProvider {
    fn has_display_rules(&self) -> bool;
    fn has_page_break_before_rules(&self) -> bool;
    fn computed(&mut self, node_id: usize) -> Arc<ComputedStyle>;
    fn publisher_box_style(&mut self, node_id: usize) -> Option<FrbPublisherBoxStyle>;
    fn first_letter(&mut self, node_id: usize) -> Option<FrbFirstLetterStyle>;
    fn floated_text_initial(
        &mut self,
        node_id: usize,
        paragraph_id: usize,
        source_text_len: i32,
    ) -> Option<FrbFirstLetterStyle>;
    fn generated(&mut self, node_id: usize, after: bool) -> Option<GeneratedRun>;
    fn dark_image_style(&mut self, node_id: usize) -> DarkImageStyle;

    fn visual_background(
        &mut self,
        _node_id: usize,
        _stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        None
    }

    fn max_width_breakpoints_css_px(&self) -> Vec<f32> {
        Vec::new()
    }

    fn has_responsive_at_max_width(&mut self, _width_css_px: f32) -> bool {
        false
    }

    fn responsive_computed(
        &mut self,
        _width_css_px: f32,
        _node_id: usize,
    ) -> Option<Arc<ComputedStyle>> {
        None
    }

    fn responsive_publisher_box_style(
        &mut self,
        _width_css_px: f32,
        _node_id: usize,
    ) -> Option<FrbPublisherBoxStyle> {
        None
    }

    fn responsive_generated(
        &mut self,
        _width_css_px: f32,
        _node_id: usize,
        _after: bool,
    ) -> Option<GeneratedRun> {
        None
    }

    fn responsive_visual_background(
        &mut self,
        _width_css_px: f32,
        _node_id: usize,
        _stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        None
    }
}

impl CssProvider for ChapterCss<'_> {
    #[inline]
    fn has_display_rules(&self) -> bool {
        ChapterCss::has_display_rules(self)
    }

    #[inline]
    fn has_page_break_before_rules(&self) -> bool {
        ChapterCss::has_page_break_before_rules(self)
    }

    #[inline]
    fn computed(&mut self, node_id: usize) -> Arc<ComputedStyle> {
        ChapterCss::computed(self, node_id)
    }

    #[inline]
    fn publisher_box_style(&mut self, node_id: usize) -> Option<FrbPublisherBoxStyle> {
        ChapterCss::publisher_box_style(self, node_id)
    }

    #[inline]
    fn first_letter(&mut self, node_id: usize) -> Option<FrbFirstLetterStyle> {
        ChapterCss::first_letter(self, node_id)
    }

    #[inline]
    fn floated_text_initial(
        &mut self,
        node_id: usize,
        paragraph_id: usize,
        source_text_len: i32,
    ) -> Option<FrbFirstLetterStyle> {
        ChapterCss::floated_text_initial(self, node_id, paragraph_id, source_text_len)
    }

    #[inline]
    fn generated(&mut self, node_id: usize, after: bool) -> Option<GeneratedRun> {
        ChapterCss::generated(self, node_id, after)
    }

    #[inline]
    fn dark_image_style(&mut self, node_id: usize) -> DarkImageStyle {
        let style = ChapterCss::dark_image_style(self, node_id);
        DarkImageStyle {
            image_invert: style.image_invert,
            background_color_argb: style.background_color_argb,
            background_uses_current_color: style.background_uses_current_color,
        }
    }

    #[inline]
    fn visual_background(&mut self, node_id: usize, stop_exclusive: Option<usize>) -> Option<i32> {
        ChapterCss::visual_background(self, node_id, stop_exclusive)
    }

    #[inline]
    fn max_width_breakpoints_css_px(&self) -> Vec<f32> {
        ChapterCss::max_width_breakpoints_css_px(self)
    }

    #[inline]
    fn has_responsive_at_max_width(&mut self, width_css_px: f32) -> bool {
        ChapterCss::has_responsive_at_max_width(self, width_css_px)
    }

    #[inline]
    fn responsive_computed(
        &mut self,
        width_css_px: f32,
        node_id: usize,
    ) -> Option<Arc<ComputedStyle>> {
        ChapterCss::responsive_computed(self, width_css_px, node_id)
    }

    #[inline]
    fn responsive_publisher_box_style(
        &mut self,
        width_css_px: f32,
        node_id: usize,
    ) -> Option<FrbPublisherBoxStyle> {
        ChapterCss::responsive_publisher_box_style(self, width_css_px, node_id)
    }

    #[inline]
    fn responsive_generated(
        &mut self,
        width_css_px: f32,
        node_id: usize,
        after: bool,
    ) -> Option<GeneratedRun> {
        ChapterCss::responsive_generated(self, width_css_px, node_id, after)
    }

    #[inline]
    fn responsive_visual_background(
        &mut self,
        width_css_px: f32,
        node_id: usize,
        stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        ChapterCss::responsive_visual_background(self, width_css_px, node_id, stop_exclusive)
    }
}

// --------------------------------------------------------------------------- Entry Points

/// Maps a parsed `chapter_markup::Document` rooted at `body_node` into FRB elements
/// using an optional native `css_engine::ChapterCss`.
///
/// Mirrors `HtmlMapper.map(body)`: when `css` is `None` but `body_node` or any
/// descendant carries a `style="..."` attribute, lazily constructs a standalone
/// empty-stylesheet `ChapterCss` so inline author declarations still apply.
pub(crate) fn map_document(
    doc: &Document,
    body_node: usize,
    css: Option<&mut ChapterCss<'_>>,
    callbacks: &mut dyn ResourceCallbacks,
    options: &MapOptions,
) -> Result<MappedBody, MapError> {
    if let Some(chapter_css) = css {
        return map_document_with_provider(doc, body_node, Some(chapter_css), callbacks, options);
    }
    if body_node < doc.nodes.len()
        && doc.is_element(body_node)
        && (doc.node_meta(body_node).subtree_flags & SUBTREE_HAS_STYLE) != 0
    {
        let mut inline_only_css = ChapterCss::standalone(CompiledStylesheets::empty(), doc);
        return map_document_with_provider(
            doc,
            body_node,
            Some(&mut inline_only_css),
            callbacks,
            options,
        );
    }
    map_document_with_provider(doc, body_node, None, callbacks, options)
}

/// Maps a parsed `chapter_markup::Document` rooted at `body_node` using any `CssProvider`.
pub(crate) fn map_document_with_provider(
    doc: &Document,
    body_node: usize,
    css: Option<&mut dyn CssProvider>,
    callbacks: &mut dyn ResourceCallbacks,
    options: &MapOptions,
) -> Result<MappedBody, MapError> {
    if options.max_structure_depth == 0 {
        return Err(MapError::Internal("max_structure_depth must be > 0".into()));
    }
    if body_node >= doc.nodes.len() || !doc.is_element(body_node) {
        return Err(MapError::Internal("invalid body_node index".into()));
    }

    if css.is_none() && (doc.node_meta(body_node).subtree_flags & SUBTREE_HAS_STYLE) != 0 {
        let mut inline_only_css = ChapterCss::standalone(CompiledStylesheets::empty(), doc);
        let mut mapper = HtmlMapper::new(doc, Some(&mut inline_only_css), callbacks, options);
        mapper.map(body_node)?;
        return Ok(mapper.into_mapped_body());
    }

    let mut mapper = HtmlMapper::new(doc, css, callbacks, options);
    mapper.map(body_node)?;
    Ok(mapper.into_mapped_body())
}

// --------------------------------------------------------------------------- Publisher Box Slicing

/// Intersects publisher boxes with `[start_element, end_element_exclusive)` and
/// rebases them to `destination_offset`. Mirrors `slicePublisherBoxSpans` in
/// `PublisherLayout.kt`.
pub(crate) fn slice_publisher_box_spans(
    spans: &[FrbPublisherBoxSpan],
    start_element: i32,
    end_element_exclusive: i32,
    destination_offset: i32,
) -> Vec<FrbPublisherBoxSpan> {
    if start_element < 0 || end_element_exclusive < start_element || destination_offset < 0 {
        return Vec::new();
    }
    let mut sliced: Vec<FrbPublisherBoxSpan> = Vec::new();
    for span in spans {
        let intersects = if span.start_element == span.end_element_exclusive {
            span.start_element >= start_element && span.start_element < end_element_exclusive
        } else {
            span.start_element < end_element_exclusive && span.end_element_exclusive > start_element
        };
        if !intersects {
            continue;
        }
        if span.start_element == span.end_element_exclusive {
            let rebased = span.start_element - start_element + destination_offset;
            let mut copy = span.clone();
            copy.start_element = rebased;
            copy.end_element_exclusive = rebased;
            sliced.push(copy);
            continue;
        }
        let includes_start = start_element <= span.start_element;
        let includes_end = end_element_exclusive >= span.end_element_exclusive;
        let mut copy = span.clone();
        copy.start_element =
            span.start_element.max(start_element) - start_element + destination_offset;
        copy.end_element_exclusive = span.end_element_exclusive.min(end_element_exclusive)
            - start_element
            + destination_offset;
        if !includes_start {
            copy.clear = FrbPublisherClear::None;
        }
        copy.draw_start = span.draw_start && includes_start;
        copy.draw_end = span.draw_end && includes_end;
        sliced.push(copy);
    }
    if sliced.is_empty() {
        return sliced;
    }
    let retained_ids: HashSet<String> = sliced.iter().map(|s| s.id.clone()).collect();
    for span in &mut sliced {
        if let Some(pid) = &span.parent_id {
            if !retained_ids.contains(pid) {
                span.parent_id = None;
            }
        }
    }
    sliced
}

// --------------------------------------------------------------------------- Typography Helpers

#[inline]
pub(crate) fn font_typography_from_computed(
    computed: &ComputedStyle,
) -> crate::frb_model::FrbFontTypography {
    css_engine::font_typography_of(computed)
}

#[inline]
pub(crate) fn encode_font_typography(typo: &crate::frb_model::FrbFontTypography) -> String {
    css_engine::encode_font_typography(typo)
}

// --------------------------------------------------------------------------- Mapper Core

struct OpenPublisherBox {
    id: String,
    parent_id: Option<String>,
    start_element: i32,
    style: FrbPublisherBoxStyle,
    float_side: Option<FrbPublisherFloatSide>,
    clear: FrbPublisherClear,
}

struct ListContext {
    ordered: bool,
    next_index: i32,
    style_type: String,
}

struct ExplicitFloatedInitial {
    node_id: usize,
    style: FrbFirstLetterStyle,
}

#[derive(Clone, Copy, Debug)]
enum CssQuery {
    Base,
    Responsive(f32),
}

struct ResponsiveTableStyle {
    width_css_px: f32,
    cells: HashMap<usize, FrbTableCellStyle>,
}

struct HtmlMapper<'doc, 'css, 'cb, 'opt> {
    doc: &'doc Document,
    css: Option<&'css mut (dyn CssProvider + 'css)>,
    callbacks: &'cb mut (dyn ResourceCallbacks + 'cb),
    options: &'opt MapOptions,

    out: Vec<FrbContentElement>,
    pending_inline: Vec<usize>,
    pending_inline_owner: Option<usize>,

    css_block_containers: HashSet<usize>,
    css_line_groups: HashSet<usize>,

    publisher_boxes: Vec<FrbPublisherBoxSpan>,
    publisher_box_stack: Vec<String>,
    publisher_box_sequence: usize,

    heading_level: Option<i32>,
    list_stack: Vec<ListContext>,

    anchors: BTreeMap<String, i32>,
    anchor_ids_by_start: HashMap<i32, Vec<String>>,
    link_targets: BTreeSet<String>,
    note_targets: BTreeSet<String>,
    note_documents: BTreeMap<String, FrbNoteDocument>,

    generated_chars_used: usize,
    was_aggregate_limited: bool,
}

impl<'doc, 'css, 'cb, 'opt> HtmlMapper<'doc, 'css, 'cb, 'opt> {
    fn new(
        doc: &'doc Document,
        css: Option<&'css mut (dyn CssProvider + 'css)>,
        callbacks: &'cb mut (dyn ResourceCallbacks + 'cb),
        options: &'opt MapOptions,
    ) -> Self {
        Self {
            doc,
            css,
            callbacks,
            options,
            out: Vec::new(),
            pending_inline: Vec::new(),
            pending_inline_owner: None,
            css_block_containers: HashSet::new(),
            css_line_groups: HashSet::new(),
            publisher_boxes: Vec::new(),
            publisher_box_stack: Vec::new(),
            publisher_box_sequence: 0,
            heading_level: None,
            list_stack: Vec::new(),
            anchors: BTreeMap::new(),
            anchor_ids_by_start: HashMap::new(),
            link_targets: BTreeSet::new(),
            note_targets: BTreeSet::new(),
            note_documents: BTreeMap::new(),
            generated_chars_used: 0,
            was_aggregate_limited: false,
        }
    }

    fn into_mapped_body(self) -> MappedBody {
        MappedBody {
            elements: self.out,
            publisher_boxes: self.publisher_boxes,
            anchors: self.anchors,
            link_targets: self.link_targets,
            note_targets: self.note_targets,
            note_documents: self.note_documents,
            generated_chars_used: self.generated_chars_used,
            was_aggregate_limited: self.was_aggregate_limited,
        }
    }

    #[inline]
    fn has_tag(&self, node_id: usize, flag: u32) -> bool {
        (self.doc.node_meta(node_id).tag_flags & flag) != 0
    }

    #[inline]
    fn has_attr(&self, node_id: usize, flag: u32) -> bool {
        (self.doc.node_meta(node_id).attr_flags & flag) != 0
    }

    #[inline]
    fn has_subtree(&self, node_id: usize, flag: u32) -> bool {
        (self.doc.node_meta(node_id).subtree_flags & flag) != 0
    }

    #[inline]
    fn is_text_blank(&self, node_id: usize) -> bool {
        (self.doc.node_meta(node_id).text_flags & TEXT_HTML_BLANK) != 0
    }

    #[inline]
    fn anchor_id_of(&self, node_id: usize) -> &'doc str {
        if !self.has_attr(node_id, ATTR_HAS_ID) {
            return "";
        }
        self.doc.attr_or_empty(node_id, "id")
    }

    #[inline]
    fn src_of(&self, node_id: usize) -> &'doc str {
        if !self.has_attr(node_id, ATTR_HAS_SRC) {
            return "";
        }
        self.doc.attr_or_empty(node_id, "src")
    }

    #[inline]
    fn href_of(&self, node_id: usize) -> &'doc str {
        if !self.has_attr(node_id, ATTR_HAS_HREF) {
            return "";
        }
        self.doc.attr_or_empty(node_id, "href")
    }

    #[inline]
    fn xlink_href_of(&self, node_id: usize) -> &'doc str {
        if !self.has_attr(node_id, ATTR_HAS_XLINK_HREF) {
            return "";
        }
        self.doc.attr_or_empty(node_id, "xlink:href")
    }

    #[inline]
    fn css_computed(&mut self, node_id: usize) -> Option<Arc<ComputedStyle>> {
        self.css.as_mut().map(|c| c.computed(node_id))
    }

    #[inline]
    fn is_css_flow_block(&mut self, node_id: usize) -> bool {
        if self.has_tag(node_id, TAG_PUBLISHER_BOX | TAG_NON_CONTENT) {
            return false;
        }
        let Some(computed) = self.css_computed(node_id) else {
            return false;
        };
        matches!(
            computed.display.as_deref(),
            Some("block" | "flow-root" | "list-item" | "flex" | "grid" | "table")
        )
    }

    fn map(&mut self, body_node: usize) -> Result<(), MapError> {
        self.index_css_block_containers(body_node);
        let body_box = self.begin_publisher_box(body_node, true, false);
        let result = (|| -> Result<(), MapError> {
            self.walk(body_node, false, 0)?;
            self.flush_pending(false)?;
            Ok(())
        })();
        self.end_publisher_box(body_box);
        result
    }

    fn index_css_block_containers(&mut self, body_node: usize) {
        let Some(has_display_rules) = self.css.as_ref().map(|c| c.has_display_rules()) else {
            return;
        };
        let require_style_attr = !has_display_rules;
        if !self.has_subtree(body_node, SUBTREE_HAS_INLINE_FLOW_CANDIDATE) {
            return;
        }
        if require_style_attr && !self.has_subtree(body_node, SUBTREE_HAS_STYLE) {
            return;
        }

        let mut visited: Vec<usize> = Vec::new();
        let mut stack: Vec<(usize, usize)> = vec![(body_node, 0)];
        while let Some((element, depth)) = stack.pop() {
            visited.push(element);
            if depth >= self.options.max_structure_depth
                || self.css_computed(element).is_some_and(|c| c.hidden)
            {
                continue;
            }
            let mut child = self.doc.first_element_child(element);
            while let Some(c) = child {
                if self.has_subtree(c, SUBTREE_HAS_INLINE_FLOW_CANDIDATE)
                    && (!require_style_attr || self.has_subtree(c, SUBTREE_HAS_STYLE))
                {
                    stack.push((c, depth + 1));
                }
                child = self.doc.next_element_sibling(c);
            }
        }

        for &element in visited.iter().rev() {
            if self.is_css_flow_block(element) || self.css_block_containers.contains(&element) {
                if let Some(parent) = self.doc.parent_element(element) {
                    self.css_block_containers.insert(parent);
                }
            }
            if self.css_block_containers.contains(&element) {
                let mut all_line_compatible = true;
                let mut child = self.doc.nodes[element].first_child;
                while let Some(c) = child {
                    let ok = match &self.doc.nodes[c].kind {
                        Kind::Text(_) | Kind::Cdata(_) => self.is_text_blank(c),
                        Kind::Element { .. } => {
                            self.css_computed(c).is_some_and(|comp| comp.hidden)
                                || self.is_css_flow_block(c)
                                || self.css_line_groups.contains(&c)
                                || self.has_tag(c, TAG_BR)
                        }
                        _ => true,
                    };
                    if !ok {
                        all_line_compatible = false;
                        break;
                    }
                    child = self.doc.nodes[c].next_sibling;
                }
                if all_line_compatible {
                    self.css_line_groups.insert(element);
                }
            }
        }
    }

    fn register_anchor(&mut self, id: &str) {
        if !id.is_empty() && !self.anchors.contains_key(id) {
            let pos = self.out.len() as i32;
            self.anchors.insert(id.to_owned(), pos);
            self.anchor_ids_by_start
                .entry(pos)
                .or_default()
                .push(id.to_owned());
        }
    }

    fn for_each_matching_subtree(&self, root: usize, flag: u32, mut f: impl FnMut(usize)) {
        if !self.has_subtree(root, flag) {
            return;
        }
        let mut stack = vec![root];
        while let Some(current) = stack.pop() {
            f(current);
            let start = stack.len();
            let mut child = self.doc.first_element_child(current);
            while let Some(c) = child {
                if self.has_subtree(c, flag) {
                    stack.push(c);
                }
                child = self.doc.next_element_sibling(c);
            }
            stack[start..].reverse();
        }
    }

    fn register_all_ids(&mut self, root: usize) {
        if !self.has_subtree(root, SUBTREE_HAS_ID) {
            return;
        }
        let mut ids: Vec<&'doc str> = Vec::new();
        self.for_each_matching_subtree(root, SUBTREE_HAS_ID, |node_id| {
            let id = self.anchor_id_of(node_id);
            if !id.is_empty() {
                ids.push(id);
            }
        });
        for id in ids {
            self.register_anchor(id);
        }
    }

    fn collect_images(&self, root: usize) -> Vec<usize> {
        let mut images = Vec::new();
        self.for_each_matching_subtree(root, SUBTREE_HAS_IMG, |node_id| {
            if self.has_tag(node_id, TAG_IMG) {
                images.push(node_id);
            }
        });
        images
    }

    fn collect_svgs(&self, root: usize) -> Vec<usize> {
        let mut svgs = Vec::new();
        self.for_each_matching_subtree(root, SUBTREE_HAS_SVG, |node_id| {
            if self.has_tag(node_id, TAG_SVG) {
                svgs.push(node_id);
            }
        });
        svgs
    }

    fn query_computed(&mut self, query: CssQuery, node_id: usize) -> Option<Arc<ComputedStyle>> {
        match query {
            CssQuery::Base => self.css_computed(node_id),
            CssQuery::Responsive(width) => self
                .css
                .as_mut()
                .and_then(|c| c.responsive_computed(width, node_id)),
        }
    }

    fn query_publisher_box_style(
        &mut self,
        query: CssQuery,
        node_id: usize,
    ) -> Option<FrbPublisherBoxStyle> {
        match query {
            CssQuery::Base => self
                .css
                .as_mut()
                .and_then(|c| c.publisher_box_style(node_id)),
            CssQuery::Responsive(width) => self
                .css
                .as_mut()
                .and_then(|c| c.responsive_publisher_box_style(width, node_id)),
        }
    }

    fn query_visual_background(
        &mut self,
        query: CssQuery,
        node_id: usize,
        stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        match query {
            CssQuery::Base => self
                .css
                .as_mut()
                .and_then(|c| c.visual_background(node_id, stop_exclusive)),
            CssQuery::Responsive(width) => self
                .css
                .as_mut()
                .and_then(|c| c.responsive_visual_background(width, node_id, stop_exclusive)),
        }
    }

    // -------------------------------------------------------------- Walk

    fn walk(&mut self, container: usize, quote: bool, depth: usize) -> Result<(), MapError> {
        if depth >= self.options.max_structure_depth {
            let mut child = self.doc.nodes[container].first_child;
            while let Some(c) = child {
                if matches!(self.doc.nodes[c].kind, Kind::Text(_) | Kind::Cdata(_))
                    && !self.is_text_blank(c)
                {
                    self.queue_pending(c, Some(container));
                }
                child = self.doc.nodes[c].next_sibling;
            }
            return Ok(());
        }

        let mut child = self.doc.nodes[container].first_child;
        while let Some(node_id) = child {
            let next = self.doc.nodes[node_id].next_sibling;
            match &self.doc.nodes[node_id].kind {
                Kind::Text(_) | Kind::Cdata(_) => {
                    if !self.is_text_blank(node_id) || !self.pending_inline.is_empty() {
                        self.queue_pending(node_id, Some(container));
                    }
                }
                Kind::Element { .. } => {
                    let rich_note_container =
                        self.has_attr(node_id, ATTR_IS_SEMANTIC_NOTE_CONTAINER);
                    if rich_note_container
                        || self.is_css_flow_block(node_id)
                        || self.css_block_containers.contains(&node_id)
                    {
                        self.flush_pending(quote)?;
                    }
                    let node_anchor_id = self.anchor_id_of(node_id);
                    self.register_anchor(node_anchor_id);
                    let note_start = self.out.len();
                    self.walk_element(node_id, quote, depth + 1)?;
                    if rich_note_container {
                        self.flush_pending(quote)?;
                        if self.out.len() > note_start {
                            let document = FrbNoteDocument {
                                elements: self.out[note_start..].to_vec(),
                                publisher_boxes: slice_publisher_box_spans(
                                    &self.publisher_boxes,
                                    note_start as i32,
                                    self.out.len() as i32,
                                    0,
                                ),
                            };
                            if !node_anchor_id.is_empty() {
                                self.note_documents
                                    .entry(node_anchor_id.to_owned())
                                    .or_insert_with(|| document.clone());
                            }
                            let mut desc_ids: Vec<String> = Vec::new();
                            self.for_each_matching_subtree(node_id, SUBTREE_HAS_ID, |desc| {
                                let id = self.anchor_id_of(desc);
                                if !id.is_empty() {
                                    desc_ids.push(id.to_owned());
                                }
                            });
                            for id in desc_ids {
                                self.note_documents
                                    .entry(id)
                                    .or_insert_with(|| document.clone());
                            }
                            if let Some(aliases) =
                                self.anchor_ids_by_start.get(&(note_start as i32)).cloned()
                            {
                                for id in aliases {
                                    self.note_documents
                                        .entry(id)
                                        .or_insert_with(|| document.clone());
                                }
                            }
                        }
                    }
                }
                _ => {}
            }
            child = next;
        }
        Ok(())
    }

    fn walk_element(&mut self, node_id: usize, quote: bool, depth: usize) -> Result<(), MapError> {
        let opens_flow_box =
            !self.has_tag(node_id, TAG_IMAGE) && self.is_publisher_box_element(node_id, false);
        if opens_flow_box {
            self.flush_pending(quote)?;
        }
        let open_box = if opens_flow_box {
            self.begin_publisher_box(node_id, false, true)
        } else {
            None
        };
        let res = self.walk_element_content(node_id, quote, depth);
        self.end_publisher_box(open_box);
        res
    }

    fn walk_element_content(
        &mut self,
        node_id: usize,
        quote: bool,
        depth: usize,
    ) -> Result<(), MapError> {
        if self.css_computed(node_id).is_some_and(|c| c.hidden) {
            return Ok(());
        }
        if self.has_tag(node_id, TAG_MATH) {
            if self.has_attr(node_id, ATTR_IS_DISPLAY_MATH) {
                self.flush_pending(quote)?;
                self.emit_math_block(node_id, quote);
            } else {
                self.queue_pending(node_id, self.doc.parent_element(node_id));
            }
            return Ok(());
        }

        if self.css.is_some()
            && !self.has_tag(node_id, TAG_CSS_ROW_EXCLUDED)
            && self.emit_css_horizontal_row(node_id, quote)?
        {
            return Ok(());
        }

        if self.is_css_flow_block(node_id) {
            self.flush_pending(quote)?;
            if self.css_block_containers.contains(&node_id) {
                self.walk(node_id, false, depth)?;
                self.flush_pending(false)?;
            } else {
                self.emit_paragraph(node_id, false, depth, true)?;
            }
            return Ok(());
        }

        let tag_cow = self.doc.normal_name(node_id);
        match tag_cow.as_ref() {
            "p" | "li" | "dt" | "dd" | "pre" => {
                self.flush_pending(quote)?;
                self.emit_paragraph(node_id, quote, depth, false)?;
            }
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                let level = (tag_cow.as_bytes()[1] - b'0') as i32;
                self.flush_pending(quote)?;
                self.emit_heading(node_id, level)?;
            }
            "levelhd" | "hd" | "bridgehead" => {
                self.flush_pending(quote)?;
                let level = self.dtbook_heading_level(node_id);
                self.emit_heading(node_id, level)?;
            }
            "doctitle" | "covertitle" => {
                self.flush_pending(quote)?;
                self.emit_heading(node_id, 1)?;
            }
            "blockquote" | "cite" | "poem" | "epigraph" => {
                self.flush_pending(quote)?;
                self.emit_generated_paragraph(node_id, false, true);
                self.walk(node_id, true, depth)?;
                self.flush_pending(true)?;
                self.emit_generated_paragraph(node_id, true, true);
            }
            "ul" | "ol" => {
                self.flush_pending(quote)?;
                let ordered = tag_cow.as_ref() == "ol";
                let type_attr = self.doc.attr_or_empty(node_id, "type");
                let css_list_type = self
                    .css_computed(node_id)
                    .and_then(|c| c.list_style_type.clone());
                let style_type = list_css_type_for(if type_attr.is_empty() {
                    None
                } else {
                    Some(type_attr)
                })
                .map(str::to_owned)
                .or_else(|| {
                    list_applicable_css_type(css_list_type.as_deref(), ordered).map(str::to_owned)
                })
                .unwrap_or_else(|| {
                    list_default_type(ordered, self.list_stack.len() + 1).to_owned()
                });
                let start = if ordered {
                    self.doc
                        .attr_or_empty(node_id, "start")
                        .parse::<i32>()
                        .unwrap_or(1)
                } else {
                    1
                };
                self.list_stack.push(ListContext {
                    ordered,
                    next_index: start,
                    style_type,
                });
                let res = (|| -> Result<(), MapError> {
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                    Ok(())
                })();
                self.list_stack.pop();
                res?;
            }
            "list" => {
                self.flush_pending(quote)?;
                let kind = self.doc.attr_or_empty(node_id, "type").to_lowercase();
                let enum_attr = self.doc.attr_or_empty(node_id, "enum");
                let ordered = kind == "ol" || !enum_attr.is_empty();
                let css_list_type = self
                    .css_computed(node_id)
                    .and_then(|c| c.list_style_type.clone());
                let style_type = if kind == "pl" {
                    "none".to_owned()
                } else {
                    list_css_type_for(if enum_attr.is_empty() {
                        None
                    } else {
                        Some(enum_attr)
                    })
                    .map(str::to_owned)
                    .or_else(|| {
                        list_applicable_css_type(css_list_type.as_deref(), ordered)
                            .map(str::to_owned)
                    })
                    .unwrap_or_else(|| {
                        list_default_type(ordered, self.list_stack.len() + 1).to_owned()
                    })
                };
                let start = if ordered {
                    self.doc
                        .attr_or_empty(node_id, "start")
                        .parse::<i32>()
                        .unwrap_or(1)
                } else {
                    1
                };
                self.list_stack.push(ListContext {
                    ordered,
                    next_index: start,
                    style_type,
                });
                let res = (|| -> Result<(), MapError> {
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                    Ok(())
                })();
                self.list_stack.pop();
                res?;
            }
            "figcaption" | "caption" => {
                self.flush_pending(quote)?;
                if self.css_block_containers.contains(&node_id)
                    || self.has_subtree(node_id, SUBTREE_HAS_BLOCK_PROBE)
                {
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                } else {
                    self.emit_caption_like(node_id, quote)?;
                }
            }
            "summary" => {
                self.flush_pending(quote)?;
                if self.has_subtree(node_id, SUBTREE_HAS_BLOCK_PROBE) {
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                } else {
                    self.emit_summary(node_id, quote)?;
                }
            }
            "audio" | "video" => {
                self.flush_pending(quote)?;
                self.emit_media_fallback(node_id, quote, depth)?;
            }
            "div" | "section" | "article" | "aside" | "main" | "header" | "footer" | "dl"
            | "figure" | "center" | "nav" | "details" | "dtbook" | "book" | "frontmatter"
            | "bodymatter" | "rearmatter" | "level" | "level1" | "level2" | "level3" | "level4"
            | "level5" | "level6" | "sidebar" | "note" | "prodnote" | "annotation"
            | "linegroup" | "imggroup" => {
                self.flush_pending(quote)?;
                if self.is_empty_line_block(node_id) {
                    self.out.push(FrbContentElement::Spacer { height_em: 1.0 });
                    return Ok(());
                }
                let empty_box_start = self.out.len();
                self.emit_generated_paragraph(node_id, false, quote);
                let title_level = self.title_class_level(node_id);
                if title_level.is_some() && self.heading_level.is_none() {
                    self.heading_level = title_level;
                    let res = (|| -> Result<(), MapError> {
                        self.walk(node_id, quote, depth)?;
                        self.flush_pending(quote)?;
                        Ok(())
                    })();
                    self.heading_level = None;
                    res?;
                } else {
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                }
                self.emit_generated_paragraph(node_id, true, quote);
                if self.out.len() == empty_box_start {
                    let min_height = self
                        .css
                        .as_mut()
                        .and_then(|c| c.publisher_box_style(node_id))
                        .and_then(|b| b.min_height_em)
                        .unwrap_or(0.0);
                    if min_height > 0.0 {
                        self.out.push(FrbContentElement::Spacer {
                            height_em: min_height,
                        });
                    }
                }
            }
            "img" => {
                self.flush_pending(quote)?;
                let src = self.src_of(node_id);
                self.emit_image(src, Some(node_id));
            }
            "image" => {
                self.flush_pending(quote)?;
                let xlink = self.xlink_href_of(node_id);
                let src = if xlink.is_empty() {
                    self.href_of(node_id)
                } else {
                    xlink
                };
                self.emit_image(src, Some(node_id));
            }
            "svg" => {
                self.flush_pending(quote)?;
                self.emit_svg(node_id);
            }
            "hr" => {
                self.flush_pending(quote)?;
                let computed = self.css_computed(node_id);
                if computed.as_ref().is_some_and(|c| c.border_specified) {
                    let height_em = computed.and_then(|c| c.height_em).unwrap_or(0.0);
                    self.out.push(FrbContentElement::Spacer { height_em });
                } else {
                    self.out.push(FrbContentElement::Divider);
                }
            }
            "table" => {
                self.flush_pending(quote)?;
                self.emit_table(node_id)?;
            }
            "line" | "byline" | "dateline" | "docauthor" | "address" => {
                self.flush_pending(quote)?;
                self.emit_paragraph(node_id, quote, depth, false)?;
            }
            "br" => {
                self.queue_pending(node_id, self.doc.parent_element(node_id));
            }
            "style" | "script" | "head" | "title" | "link" | "meta" | "source" | "track" => {}
            _ => {
                if self.css_block_containers.contains(&node_id)
                    || self.has_subtree(node_id, SUBTREE_HAS_BLOCK_PROBE)
                {
                    self.flush_pending(quote)?;
                    self.walk(node_id, quote, depth)?;
                    self.flush_pending(quote)?;
                } else {
                    self.queue_pending(node_id, self.doc.parent_element(node_id));
                }
            }
        }
        Ok(())
    }

    // -------------------------------------------------------------- Publisher Boxes

    fn queue_pending(&mut self, node_id: usize, owner: Option<usize>) {
        if self.pending_inline.is_empty() {
            self.pending_inline_owner = owner;
        }
        self.pending_inline.push(node_id);
    }

    fn is_publisher_box_element(&mut self, node_id: usize, allow_root: bool) -> bool {
        if self.is_css_flow_block(node_id) {
            return true;
        }
        let is_root = self.has_tag(node_id, TAG_ROOT);
        if (!allow_root && is_root)
            || (self.has_tag(node_id, TAG_NON_BLOCK) && !self.has_tag(node_id, TAG_IMAGE))
        {
            return false;
        }
        (allow_root && is_root)
            || self.has_tag(node_id, TAG_PUBLISHER_BOX)
            || (self.has_tag(node_id, TAG_MATH) && self.has_attr(node_id, ATTR_IS_DISPLAY_MATH))
            || self.has_subtree(node_id, SUBTREE_HAS_BLOCK_PROBE)
    }

    fn begin_publisher_box(
        &mut self,
        node_id: usize,
        allow_root: bool,
        known_box_element: bool,
    ) -> Option<OpenPublisherBox> {
        if !known_box_element && !self.is_publisher_box_element(node_id, allow_root) {
            return None;
        }
        let computed = self.css_computed(node_id)?;
        if computed.hidden {
            return None;
        }
        let mut style = self
            .css
            .as_mut()?
            .publisher_box_style(node_id)
            .unwrap_or_default();
        if self.css_line_groups.contains(&node_id) && !self.has_tag(node_id, TAG_ROOT) {
            style.fit_text_flow = true;
        }
        let float_side = match computed.float_side.as_deref() {
            Some("left") => Some(FrbPublisherFloatSide::Left),
            Some("right") => Some(FrbPublisherFloatSide::Right),
            _ => None,
        };
        let clear = match computed.clear.as_deref() {
            Some("left") => FrbPublisherClear::Left,
            Some("right") => FrbPublisherClear::Right,
            Some("both") => FrbPublisherClear::Both,
            _ => FrbPublisherClear::None,
        };
        if style.is_default() && float_side.is_none() && clear == FrbPublisherClear::None {
            return None;
        }

        let prefix = if kotlin_is_blank(&self.options.publisher_box_id_prefix) {
            "document"
        } else {
            self.options.publisher_box_id_prefix.as_str()
        };
        let seq = self.publisher_box_sequence;
        self.publisher_box_sequence += 1;
        let id = format!("{prefix}:publisher-box-{seq}");
        let parent_id = self.publisher_box_stack.last().cloned();
        self.publisher_box_stack.push(id.clone());
        Some(OpenPublisherBox {
            id,
            parent_id,
            start_element: self.out.len() as i32,
            style,
            float_side,
            clear,
        })
    }

    fn end_publisher_box(&mut self, open: Option<OpenPublisherBox>) {
        let Some(open) = open else {
            return;
        };
        let popped = self.publisher_box_stack.pop();
        debug_assert_eq!(popped.as_deref(), Some(open.id.as_str()));
        let end = self.out.len() as i32;
        if end == open.start_element {
            if open.clear != FrbPublisherClear::None {
                let mut span = FrbPublisherBoxSpan::new(open.id, end, end);
                span.parent_id = open.parent_id;
                span.clear = open.clear;
                self.publisher_boxes.push(span);
            } else {
                for span in &mut self.publisher_boxes {
                    if span.parent_id.as_deref() == Some(open.id.as_str()) {
                        span.parent_id = open.parent_id.clone();
                    }
                }
            }
            return;
        }
        self.publisher_boxes.push(FrbPublisherBoxSpan {
            id: open.id,
            parent_id: open.parent_id,
            start_element: open.start_element,
            end_element_exclusive: end,
            style: open.style,
            float_side: open.float_side,
            clear: open.clear,
            draw_start: true,
            draw_end: true,
        });
    }

    // -------------------------------------------------------------- Block Emitters

    fn flush_pending(&mut self, quote: bool) -> Result<(), MapError> {
        if self.pending_inline.is_empty() {
            return Ok(());
        }
        let nodes = std::mem::take(&mut self.pending_inline);
        let owner = self.pending_inline_owner.take();
        let mut builder = HtmlInlineBuilder::new();
        for node_id in nodes {
            self.append_inline(node_id, &mut builder, false, 0, None, None)?;
        }
        if !builder.is_blank() {
            let level = self
                .heading_level
                .or_else(|| owner.and_then(|o| self.title_class_level(o)));
            if let Some(lvl) = level {
                let text = trimmed_annotated(builder.build());
                let styled_text = self.flow_annotations(text, owner);
                let block = Some(Box::new(self.loose_block_text_style(owner, false)));
                self.out.push(FrbContentElement::Heading {
                    styled_text,
                    level: lvl.clamp(1, 6),
                    block,
                });
            } else {
                let text = self.flow_annotations(builder.build(), owner);
                let block = Some(Box::new(self.loose_block_text_style(owner, true)));
                self.out.push(FrbContentElement::Paragraph {
                    text,
                    style: if quote {
                        FrbParagraphStyle::Quote
                    } else {
                        FrbParagraphStyle::Normal
                    },
                    block,
                });
            }
        }
        Ok(())
    }

    fn loose_block_text_style(
        &mut self,
        owner: Option<usize>,
        with_first_letter: bool,
    ) -> FrbBlockStyle {
        let mut base = owner
            .and_then(|o| self.block_style_for(o, with_first_letter))
            .unwrap_or_default();
        if base.first_line_indent.is_none() {
            base.first_line_indent = Some(false);
        }
        base.space_before_specified = true;
        base.space_after_specified = true;
        base
    }

    fn flow_annotations(
        &mut self,
        mut text: FrbAnnotatedString,
        owner: Option<usize>,
    ) -> FrbAnnotatedString {
        if text.text.is_empty() {
            return text;
        }
        let Some(owner_id) = owner else {
            return text;
        };
        if !self.has_attr(owner_id, ATTR_ANC_HAS_FLOW_SCOPE) {
            return text;
        }
        let text_utf16_len = utf16_len(&text.text) as i32;
        let mut scope = Some(owner_id);
        let mut depth = 0_usize;
        while let Some(s) = scope {
            if self.has_tag(s, TAG_ROOT) || depth >= self.options.max_structure_depth {
                break;
            }
            depth += 1;
            if !self.has_tag(s, TAG_PUBLISHER_BOX) {
                let computed = self.css_computed(s);
                if let Some(mode) = self.inline_bidi_mode(s, computed.as_deref()) {
                    text.ranges
                        .push(FrbTextRange::string(0, text_utf16_len, BIDI_TAG, mode));
                }
            }
            if self.has_tag(s, TAG_A) {
                let href = kotlin_trim(self.href_of(s));
                let key = if href.is_empty() {
                    None
                } else {
                    self.callbacks.resolve_link(href)
                };
                if let Some(key) = key {
                    self.link_targets.insert(key.clone());
                    let note = self.is_note_reference(s, &key);
                    if note {
                        self.note_targets.insert(key.clone());
                    }
                    text.ranges.push(FrbTextRange::string(
                        0,
                        text_utf16_len,
                        if note { FOOTNOTE_TAG } else { LINK_TAG },
                        key,
                    ));
                } else if safe_external_href(href) {
                    text.ranges.push(FrbTextRange::string(
                        0,
                        text_utf16_len,
                        EXTERNAL_LINK_TAG,
                        href,
                    ));
                }
            }
            scope = self.doc.parent_element(s);
        }
        text
    }

    fn build_simple_leaf_annotated_string(
        &mut self,
        element: usize,
        text_node: usize,
        trim_trailing: bool,
    ) -> FrbAnnotatedString {
        let computed = self.css_computed(element);
        let raw = self.doc.text_content(text_node).unwrap_or("");
        let text_flags = self.doc.node_meta(text_node).text_flags;
        let transform = computed
            .as_deref()
            .and_then(|c| c.text_transform.as_deref());
        let collapsed = match transform {
            None | Some("none") => collapse_preindexed_leaf_text(raw, text_flags, trim_trailing),
            Some(tr) => {
                if let Some(transformed) = self.transform_text_cow(raw, tr, Some(element), None) {
                    collapse_leaf_text(&transformed, trim_trailing)
                } else {
                    collapse_preindexed_leaf_text(raw, text_flags, trim_trailing)
                }
            }
        };
        let Some(computed) = computed else {
            return FrbAnnotatedString::plain(collapsed);
        };
        if collapsed.is_empty() {
            return FrbAnnotatedString::plain(collapsed);
        }
        let end = utf16_len(&collapsed) as i32;
        let family = if computed.monospace {
            Some("monospace")
        } else {
            computed.font_family_name.as_deref()
        };
        let hidden = computed.visibility_hidden;
        let typo = font_typography_from_computed(&computed);
        let encoded_typo = encode_font_typography(&typo);
        let mut ranges = Vec::with_capacity(3);
        if let Some(fam) = family {
            ranges.push(FrbTextRange::string(0, end, INLINE_FONT_FAMILY_TAG, fam));
        }
        ranges.push(FrbTextRange::string(
            0,
            end,
            INLINE_FONT_TYPOGRAPHY_TAG,
            encoded_typo,
        ));
        if hidden {
            ranges.push(FrbTextRange::string(0, end, HIDDEN_TEXT_TAG, "true"));
        }
        FrbAnnotatedString {
            text: collapsed,
            ranges,
        }
    }

    fn emit_paragraph(
        &mut self,
        element: usize,
        quote: bool,
        depth: usize,
        anonymous: bool,
    ) -> Result<(), MapError> {
        let tag = self.doc.normal_name(element).into_owned();
        if self.css_block_containers.contains(&element) && tag != "li" && tag != "pre" {
            self.emit_generated_paragraph(element, false, quote);
            self.walk(element, false, depth)?;
            self.flush_pending(false)?;
            self.emit_generated_paragraph(element, true, quote);
            return Ok(());
        }

        let is_simple_leaf = self.has_subtree(element, SUBTREE_SIMPLE_LEAF_TEXT);
        if is_simple_leaf {
            let id = self.anchor_id_of(element);
            self.register_anchor(id);
        } else {
            self.register_all_ids(element);
        }

        let own_title_level = self.title_class_level(element);
        let level = self.heading_level.or(own_title_level);
        if let Some(lvl) = level {
            return self.emit_heading(element, lvl);
        }
        if self.is_empty_line_block(element) {
            self.out.push(FrbContentElement::Spacer { height_em: 1.0 });
            return Ok(());
        }

        if is_simple_leaf
            && ((!anonymous && tag == "p") || (anonymous && tag != "li" && tag != "pre"))
            && self
                .css
                .as_mut()
                .is_none_or(|c| c.generated(element, false).is_none())
            && self
                .css
                .as_mut()
                .is_none_or(|c| c.generated(element, true).is_none())
            && self
                .css
                .as_mut()
                .is_none_or(|c| c.first_letter(element).is_none())
        {
            if let Some(text_node) = self.doc.nodes[element].first_child {
                let styled_text =
                    self.build_simple_leaf_annotated_string(element, text_node, false);
                if !styled_text.text.is_empty() {
                    let block = if anonymous {
                        let mut b = self.loose_block_text_style(Some(element), false);
                        b.is_css_text_line = true;
                        Some(Box::new(b))
                    } else {
                        self.block_style_for(element, false).map(Box::new)
                    };
                    let text = self.flow_annotations(styled_text, Some(element));
                    self.out.push(FrbContentElement::Paragraph {
                        text,
                        style: if quote {
                            FrbParagraphStyle::Quote
                        } else {
                            FrbParagraphStyle::Normal
                        },
                        block,
                    });
                }
                return Ok(());
            }
        }

        let mut builder = HtmlInlineBuilder::new();
        let preformatted = tag == "pre";
        if preformatted {
            builder.push_style(FrbSpanStyle {
                family: Some("mono".to_owned()),
                ..FrbSpanStyle::default()
            });
        }
        let mut is_list_item = false;
        if tag == "li" {
            if let Some(ctx) = self.list_stack.last_mut() {
                is_list_item = true;
                if ctx.ordered {
                    if let Ok(val) = self.doc.attr_or_empty(element, "value").parse::<i32>() {
                        ctx.next_index = val;
                    }
                }
                let marker = list_marker(&ctx.style_type, ctx.next_index);
                builder.text(&marker);
                if ctx.ordered {
                    ctx.next_index = ctx.next_index.wrapping_add(1);
                }
            } else {
                builder.text("• ");
            }
        }

        let explicit_initial = if !preformatted && !is_list_item {
            self.explicit_floated_initial(element)
        } else {
            None
        };
        let suppressed_node = explicit_initial.as_ref().map(|e| e.node_id);

        let gen_before = self.generated(element, false);
        self.append_generated_run(&mut builder, gen_before.as_ref());

        let mut child = self.doc.nodes[element].first_child;
        while let Some(c) = child {
            let next = self.doc.nodes[c].next_sibling;
            if !(self.doc.is_element(c) && self.has_tag(c, TAG_NESTED_LIST)) {
                self.append_inline(c, &mut builder, preformatted, 0, suppressed_node, None)?;
            }
            child = next;
        }

        let gen_after = self.generated(element, true);
        self.append_generated_run(&mut builder, gen_after.as_ref());
        if preformatted {
            builder.pop();
        }

        let images = self.collect_images(element);
        let mut float_image: Option<FrbFloatImage> = None;
        let mut float_element: Option<usize> = None;
        if self.css.is_some() && !images.is_empty() {
            for &img in &images {
                let Some(computed) = self.css_computed(img) else {
                    continue;
                };
                if computed.hidden {
                    continue;
                }
                let Some(side) = computed.float_side.as_deref() else {
                    continue;
                };
                let frac = computed
                    .width_frac
                    .or_else(|| computed.width_em.map(|w| w / 30.0))
                    .unwrap_or(0.33);
                if frac > 0.45 {
                    continue;
                }
                let src = self.src_of(img);
                let Some(path) = self.callbacks.resolve_image(src) else {
                    continue;
                };
                float_image = Some(FrbFloatImage {
                    path,
                    width_frac: frac.clamp(0.1, 0.45),
                    left: side == "left",
                    alt_text: self.image_alt(img),
                });
                float_element = Some(img);
                break;
            }
        }

        let has_text = !builder.is_blank();
        let has_svg = self.has_subtree(element, SUBTREE_HAS_SVG);
        let inline_refs: HashSet<String> = if !images.is_empty() || has_svg {
            builder.image_refs.iter().cloned().collect()
        } else {
            HashSet::new()
        };

        if has_text {
            let mut block = if anonymous {
                let mut b = self.loose_block_text_style(Some(element), true);
                b.is_css_text_line = true;
                Some(b)
            } else {
                self.block_style_for(element, true)
            };
            if is_list_item {
                let mut base = block.unwrap_or_default();
                base.indent_start_em += 1.2 * ((self.list_stack.len() - 1) as f32);
                base.first_line_indent = Some(false);
                block = Some(base);
            }
            if let Some(fi) = float_image {
                let mut base = block.unwrap_or_default();
                base.float_image = Some(Box::new(fi));
                block = Some(base);
            }
            if let Some(init) = explicit_initial {
                let mut base = block.unwrap_or_default();
                base.first_letter = Some(Box::new(init.style));
                block = Some(base);
            }
            if preformatted {
                let mut base = block.unwrap_or_default();
                base.font_family = Some("monospace".to_owned());
                base.first_line_indent = Some(false);
                block = Some(base);
            }
            let text = self.flow_annotations(builder.build(), Some(element));
            self.out.push(FrbContentElement::Paragraph {
                text,
                style: if quote {
                    FrbParagraphStyle::Quote
                } else {
                    FrbParagraphStyle::Normal
                },
                block: block.map(Box::new),
            });
        } else {
            float_element = None;
        }

        for img in images {
            let src = self.src_of(img);
            let path = self.callbacks.resolve_image(src);
            if Some(img) != float_element
                && (!has_text
                    || self.is_block_like_media(img)
                    || path.as_ref().is_some_and(|p| !inline_refs.contains(p)))
            {
                self.emit_image(src, Some(img));
            }
        }
        for svg in self.collect_svgs(element) {
            if let Some(path) = self.resolve_svg(svg) {
                if !has_text || !inline_refs.contains(&path) {
                    self.out
                        .push(FrbContentElement::Image(Box::new(FrbImageElement::new(
                            path,
                        ))));
                }
            }
        }

        if self.has_subtree(element, SUBTREE_HAS_NESTED_LIST) {
            let mut nested = self.doc.first_element_child(element);
            while let Some(n) = nested {
                let next = self.doc.next_element_sibling(n);
                if self.has_tag(n, TAG_NESTED_LIST) && depth < self.options.max_structure_depth {
                    self.walk_element(n, quote, depth + 1)?;
                }
                nested = next;
            }
        }
        Ok(())
    }

    fn emit_heading(&mut self, element: usize, level: i32) -> Result<(), MapError> {
        let is_simple_leaf = self.has_subtree(element, SUBTREE_SIMPLE_LEAF_TEXT);
        if is_simple_leaf {
            let id = self.anchor_id_of(element);
            self.register_anchor(id);
            if self
                .css
                .as_mut()
                .is_none_or(|c| c.generated(element, false).is_none())
                && self
                    .css
                    .as_mut()
                    .is_none_or(|c| c.generated(element, true).is_none())
                && self
                    .css
                    .as_mut()
                    .is_none_or(|c| c.first_letter(element).is_none())
            {
                if let Some(text_node) = self.doc.nodes[element].first_child {
                    let styled_text =
                        self.build_simple_leaf_annotated_string(element, text_node, true);
                    if !styled_text.text.is_empty() {
                        let block = self.block_style_for(element, false).map(Box::new);
                        self.out.push(FrbContentElement::Heading {
                            styled_text,
                            level: level.clamp(1, 6),
                            block,
                        });
                    }
                    return Ok(());
                }
            }
        } else {
            self.register_all_ids(element);
        }

        let mut builder = HtmlInlineBuilder::new();
        let gen_before = self.generated(element, false);
        self.append_generated_run(&mut builder, gen_before.as_ref());
        self.append_children_inline(element, &mut builder, false, 0, None, None)?;
        let gen_after = self.generated(element, true);
        self.append_generated_run(&mut builder, gen_after.as_ref());

        let has_text = !builder.is_blank();
        let images = self.collect_images(element);
        let has_svg = self.has_subtree(element, SUBTREE_HAS_SVG);
        let inline_refs: HashSet<String> = if !images.is_empty() || has_svg {
            builder.image_refs.iter().cloned().collect()
        } else {
            HashSet::new()
        };

        if has_text {
            let styled_text = trimmed_annotated(builder.build());
            let block = self.block_style_for(element, false).map(Box::new);
            self.out.push(FrbContentElement::Heading {
                styled_text,
                level: level.clamp(1, 6),
                block,
            });
        }

        for img in images {
            let src = self.src_of(img);
            let path = self.callbacks.resolve_image(src);
            if !has_text
                || self.is_block_like_media(img)
                || path.as_ref().is_some_and(|p| !inline_refs.contains(p))
            {
                self.emit_image(src, Some(img));
            }
        }
        for svg in self.collect_svgs(element) {
            if let Some(path) = self.resolve_svg(svg) {
                if !has_text || !inline_refs.contains(&path) {
                    self.out
                        .push(FrbContentElement::Image(Box::new(FrbImageElement::new(
                            path,
                        ))));
                }
            }
        }
        Ok(())
    }

    fn emit_math_block(&mut self, element: usize, quote: bool) {
        self.register_all_ids(element);
        let mut builder = HtmlInlineBuilder::new();
        mathml::append_math(self, element, &mut builder, false);
        if builder.is_blank() {
            return;
        }
        let mut base = self.block_style_for(element, false).unwrap_or_default();
        if base.align.is_none() {
            base.align = Some(FrbBlockAlign::Center);
        }
        base.first_line_indent = Some(false);
        self.out.push(FrbContentElement::Paragraph {
            text: builder.build(),
            style: if quote {
                FrbParagraphStyle::Quote
            } else {
                FrbParagraphStyle::Normal
            },
            block: Some(Box::new(base)),
        });
    }

    fn emit_caption_like(&mut self, node_id: usize, quote: bool) -> Result<(), MapError> {
        self.register_all_ids(node_id);
        let mut builder = HtmlInlineBuilder::new();
        self.append_children_inline(node_id, &mut builder, false, 0, None, None)?;
        let has_text = !builder.is_blank();
        let images = self.collect_images(node_id);
        let has_svg = self.has_subtree(node_id, SUBTREE_HAS_SVG);
        let inline_refs: HashSet<String> = if !images.is_empty() || has_svg {
            builder.image_refs.iter().cloned().collect()
        } else {
            HashSet::new()
        };
        if has_text {
            let mut base = self.block_style_for(node_id, false).unwrap_or_default();
            if base.align.is_none() {
                base.align = Some(FrbBlockAlign::Center);
            }
            if base.italic.is_none() {
                base.italic = Some(true);
            }
            let scale = base.font_scale.unwrap_or(1.0);
            base.font_scale = Some(if (0.99..=1.01).contains(&scale) {
                0.9
            } else {
                scale
            });
            base.first_line_indent = Some(false);
            self.out.push(FrbContentElement::Paragraph {
                text: builder.build(),
                style: if quote {
                    FrbParagraphStyle::Quote
                } else {
                    FrbParagraphStyle::Normal
                },
                block: Some(Box::new(base)),
            });
        }
        for img in images {
            let src = self.src_of(img);
            let path = self.callbacks.resolve_image(src);
            if !has_text
                || self.is_block_like_media(img)
                || path.as_ref().is_some_and(|p| !inline_refs.contains(p))
            {
                self.emit_image(src, Some(img));
            }
        }
        for svg in self.collect_svgs(node_id) {
            if let Some(path) = self.resolve_svg(svg) {
                if !has_text || !inline_refs.contains(&path) {
                    self.out
                        .push(FrbContentElement::Image(Box::new(FrbImageElement::new(
                            path,
                        ))));
                }
            }
        }
        Ok(())
    }

    fn emit_summary(&mut self, node_id: usize, quote: bool) -> Result<(), MapError> {
        self.register_all_ids(node_id);
        let mut builder = HtmlInlineBuilder::new();
        self.append_children_inline(node_id, &mut builder, false, 0, None, None)?;
        if builder.is_blank() {
            return Ok(());
        }
        let mut base = self.block_style_for(node_id, false).unwrap_or_default();
        if base.bold.is_none() {
            base.bold = Some(true);
        }
        base.first_line_indent = Some(false);
        self.out.push(FrbContentElement::Paragraph {
            text: builder.build(),
            style: if quote {
                FrbParagraphStyle::Quote
            } else {
                FrbParagraphStyle::Normal
            },
            block: Some(Box::new(base)),
        });
        Ok(())
    }

    fn emit_media_fallback(
        &mut self,
        node_id: usize,
        quote: bool,
        depth: usize,
    ) -> Result<(), MapError> {
        let poster = self.doc.attr_or_empty(node_id, "poster");
        if !poster.is_empty() && self.callbacks.resolve_image(poster).is_some() {
            self.emit_image(poster, None);
            return Ok(());
        }
        let has_fallback_text = self.has_subtree(node_id, SUBTREE_HAS_JAVA_NON_WS_TEXT);
        if self.has_subtree(node_id, SUBTREE_HAS_BLOCK_PROBE) || has_fallback_text {
            self.walk(node_id, quote, depth)?;
            self.flush_pending(quote)?;
            return Ok(());
        }
        let label = if self.doc.normal_name(node_id) == "audio" {
            AUDIO_PLACEHOLDER
        } else {
            VIDEO_PLACEHOLDER
        };
        self.out.push(FrbContentElement::Paragraph {
            text: FrbAnnotatedString::plain(label),
            style: FrbParagraphStyle::Normal,
            block: Some(Box::new(FrbBlockStyle {
                align: Some(FrbBlockAlign::Center),
                italic: Some(true),
                font_scale: Some(0.85),
                first_line_indent: Some(false),
                ..FrbBlockStyle::default()
            })),
        });
        Ok(())
    }

    // -------------------------------------------------------------- CSS Horizontal Rows

    fn emit_css_horizontal_row(&mut self, element: usize, quote: bool) -> Result<bool, MapError> {
        if self.has_tag(element, TAG_CSS_ROW_EXCLUDED) {
            return Ok(false);
        }
        let Some(has_display_rules) = self.css.as_ref().map(|c| c.has_display_rules()) else {
            return Ok(false);
        };
        if !has_display_rules && !self.has_attr(element, ATTR_HAS_STYLE) {
            return Ok(false);
        }
        let Some(computed) = self.css_computed(element) else {
            return Ok(false);
        };
        let Some(display) = computed.display.as_deref() else {
            return Ok(false);
        };
        if !matches!(display, "flex" | "inline-flex" | "table" | "inline-table") {
            return Ok(false);
        }
        let flex = matches!(display, "flex" | "inline-flex");
        if flex && (computed.flex_direction != "row" || computed.flex_wrap != "nowrap") {
            return Ok(false);
        }

        let mut source_cells: Vec<Vec<usize>> = Vec::new();
        let mut anonymous: Vec<usize> = Vec::new();
        let finish_anonymous =
            |anon: &mut Vec<usize>, out_cells: &mut Vec<Vec<usize>>, doc: &Document| {
                if anon.iter().any(|&n| {
                    matches!(doc.nodes[n].kind, Kind::Text(_) | Kind::Cdata(_))
                        && (doc.node_meta(n).text_flags & TEXT_HTML_BLANK) == 0
                }) {
                    out_cells.push(std::mem::take(anon));
                } else {
                    anon.clear();
                }
            };

        let mut child = self.doc.nodes[element].first_child;
        while let Some(c) = child {
            let next = self.doc.nodes[c].next_sibling;
            match &self.doc.nodes[c].kind {
                Kind::Text(_) | Kind::Cdata(_) => anonymous.push(c),
                Kind::Element { .. } => {
                    if self.css_computed(c).is_some_and(|comp| comp.hidden)
                        || self.has_tag(c, TAG_NON_TEXT_CONTENT)
                    {
                        child = next;
                        continue;
                    }
                    finish_anonymous(&mut anonymous, &mut source_cells, self.doc);
                    if self.has_subtree(c, SUBTREE_HAS_HORIZONTAL_COMPLEX) {
                        return Ok(false);
                    }
                    if !flex
                        && self
                            .css_computed(c)
                            .as_deref()
                            .and_then(|comp| comp.display.as_deref())
                            != Some("table-cell")
                    {
                        return Ok(false);
                    }
                    source_cells.push(vec![c]);
                }
                _ => {}
            }
            child = next;
        }
        finish_anonymous(&mut anonymous, &mut source_cells, self.doc);

        let raw_before = self.css.as_mut().and_then(|c| c.generated(element, false));
        let raw_after = self.css.as_mut().and_then(|c| c.generated(element, true));
        let has_cell = |run: &Option<GeneratedRun>| {
            run.as_ref()
                .is_some_and(|r| !kotlin_is_blank(&r.text) || r.publisher_box.is_some())
        };
        let count = source_cells.len()
            + usize::from(has_cell(&raw_before))
            + usize::from(has_cell(&raw_after));
        if !(2..=MAX_CSS_ROW_CELLS).contains(&count) {
            return Ok(false);
        }

        self.flush_pending(quote)?;
        self.register_all_ids(element);

        let mut cells: Vec<FrbTableCell> = Vec::with_capacity(count);
        let add_generated =
            |this: &mut Self, cells: &mut Vec<FrbTableCell>, raw: Option<GeneratedRun>| {
                let Some(run) = raw else {
                    return;
                };
                if kotlin_is_blank(&run.text) && run.publisher_box.is_none() {
                    return;
                }
                if !run.text.is_empty() && !this.accept_generated(utf16_len(&run.text)) {
                    return;
                }
                let mut builder = HtmlInlineBuilder::new();
                this.append_generated_run(&mut builder, Some(&run));
                let mut cell = FrbTableCell::new(builder.build());
                cell.publisher_box = run.publisher_box.map(Box::new);
                cell.publisher_padding_specified = true;
                cell.publisher_border_specified = true;
                cells.push(cell);
            };

        add_generated(self, &mut cells, raw_before);
        for nodes in source_cells {
            let cell_element =
                (nodes.len() == 1 && self.doc.is_element(nodes[0])).then_some(nodes[0]);
            let mut builder = HtmlInlineBuilder::new();
            if let Some(el) = cell_element {
                self.append_cell_content(el, &mut builder)?;
            } else {
                for node_id in nodes {
                    self.append_inline(node_id, &mut builder, false, 0, None, None)?;
                }
            }
            let text = self.flow_annotations(
                trimmed_annotated(builder.build()),
                Some(cell_element.unwrap_or(element)),
            );
            let align = cell_element.and_then(|el| self.cell_align(el, false, CssQuery::Base));
            let block = cell_element
                .and_then(|el| {
                    self.table_cell_block_style(el, element, CssQuery::Base, CssQuery::Base)
                })
                .map(Box::new);
            let publisher_box = cell_element
                .and_then(|el| self.css.as_mut().and_then(|c| c.publisher_box_style(el)))
                .map(Box::new);
            cells.push(FrbTableCell {
                text,
                col_span: 1,
                row_span: 1,
                align,
                header: false,
                block,
                publisher_box,
                publisher_padding_specified: true,
                publisher_border_specified: true,
                narrow_style: None,
            });
        }
        add_generated(self, &mut cells, raw_after);

        if cells.len() < 2 {
            return Ok(false);
        }
        let block = self
            .block_style_for(element, false)
            .map(|mut b| {
                b.first_line_indent = Some(false);
                b
            })
            .map(Box::new);
        let publisher_row_layout = Some(match computed.justify_content.as_deref() {
            Some("space-between") => FrbPublisherRowLayout::SpaceBetween,
            Some("space-around" | "space-evenly") => FrbPublisherRowLayout::SpaceAround,
            _ => FrbPublisherRowLayout::Natural,
        });
        self.out
            .push(FrbContentElement::Table(Box::new(FrbTableElement {
                rows: vec![FrbTableRow {
                    cells,
                    is_header: false,
                }],
                block,
                publisher_row_layout,
                center_cells_vertically: computed.align_items.as_deref() == Some("center"),
                stack_cells_below_css_px: None,
                publisher_box: None,
            })));
        Ok(true)
    }

    // -------------------------------------------------------------- Tables

    fn find_first_caption(&self, table: usize) -> Option<usize> {
        if !self.has_subtree(table, SUBTREE_HAS_CAPTION) {
            return None;
        }
        let mut stack = vec![table];
        while let Some(current) = stack.pop() {
            if self.has_tag(current, TAG_CAPTION) {
                return Some(current);
            }
            let start = stack.len();
            let mut child = self.doc.first_element_child(current);
            while let Some(c) = child {
                if self.has_subtree(c, SUBTREE_HAS_CAPTION) {
                    stack.push(c);
                }
                child = self.doc.next_element_sibling(c);
            }
            stack[start..].reverse();
        }
        None
    }

    fn collect_own_table_rows(&mut self, table: usize) -> Vec<usize> {
        let mut rows = Vec::new();
        let mut stack = Vec::new();
        let mut child = self.doc.first_element_child(table);
        while let Some(c) = child {
            stack.push(c);
            child = self.doc.next_element_sibling(c);
        }
        stack.reverse();
        while let Some(current) = stack.pop() {
            if self.has_tag(current, TAG_TABLE) {
                continue;
            }
            if self.has_tag(current, TAG_TR)
                && !self.css_computed(current).is_some_and(|c| c.hidden)
            {
                rows.push(current);
            }
            let start = stack.len();
            let mut grand = self.doc.first_element_child(current);
            while let Some(g) = grand {
                stack.push(g);
                grand = self.doc.next_element_sibling(g);
            }
            stack[start..].reverse();
        }
        rows
    }

    fn emit_table(&mut self, table: usize) -> Result<(), MapError> {
        let table_block = self.block_style_for(table, false);
        if let Some(caption) = self.find_first_caption(table) {
            self.emit_table_caption(caption)?;
        }

        let own_rows = self.collect_own_table_rows(table);
        if own_rows.is_empty() {
            let raw_text = self.doc.jsoup_text(table);
            let trimmed = kotlin_trim(&raw_text);
            if !trimmed.is_empty() {
                self.out.push(FrbContentElement::Paragraph {
                    text: FrbAnnotatedString::plain(trimmed),
                    style: FrbParagraphStyle::Normal,
                    block: None,
                });
            }
            return Ok(());
        }

        let mut source_rows: Vec<(usize, Vec<usize>)> = Vec::with_capacity(own_rows.len());
        for row_element in own_rows {
            let mut cell_elements = Vec::new();
            let mut cell = self.doc.first_element_child(row_element);
            while let Some(c) = cell {
                let name = self.doc.normal_name(c);
                if (name == "td" || name == "th")
                    && !self.css_computed(c).is_some_and(|comp| comp.hidden)
                {
                    cell_elements.push(c);
                }
                cell = self.doc.next_element_sibling(c);
            }
            if !cell_elements.is_empty() {
                source_rows.push((row_element, cell_elements));
            }
        }

        let responsive = self.responsive_table_style(table, &source_rows);
        let mut rows: Vec<FrbTableRow> = Vec::with_capacity(source_rows.len());
        for (row_element, cell_elements) in source_rows {
            let mut in_head = false;
            let mut parent = self.doc.parent_element(row_element);
            while let Some(p) = parent {
                if self.doc.normal_name(p) == "thead" {
                    in_head = true;
                    break;
                }
                parent = self.doc.parent_element(p);
            }
            let mut cells: Vec<FrbTableCell> = Vec::with_capacity(cell_elements.len());
            for cell_element in cell_elements {
                let mut builder = HtmlInlineBuilder::new();
                self.append_cell_content(cell_element, &mut builder)?;
                let header = self.doc.normal_name(cell_element) == "th";
                let cell_computed = self.css_computed(cell_element);
                let cell_publisher_box = self
                    .css
                    .as_mut()
                    .and_then(|c| c.publisher_box_style(cell_element))
                    .or_else(|| {
                        cell_computed
                            .as_deref()
                            .is_some_and(|c| c.padding_specified || c.border_specified)
                            .then(FrbPublisherBoxStyle::default)
                    });
                let col_span = self
                    .doc
                    .attr_or_empty(cell_element, "colspan")
                    .parse::<i32>()
                    .ok()
                    .map_or(1, |v| v.clamp(1, 10));
                let row_span = self
                    .doc
                    .attr_or_empty(cell_element, "rowspan")
                    .parse::<i32>()
                    .ok()
                    .map_or(1, |v| v.clamp(1, 20));
                let align = self.cell_align(cell_element, header, CssQuery::Base);
                let block = self
                    .table_cell_block_style(cell_element, table, CssQuery::Base, CssQuery::Base)
                    .map(Box::new);
                let publisher_padding_specified = cell_computed
                    .as_deref()
                    .is_some_and(|c| c.padding_specified);
                let publisher_border_specified =
                    self.table_border_specified(cell_element, table, CssQuery::Base);
                let narrow_style = responsive
                    .as_ref()
                    .and_then(|r| r.cells.get(&cell_element).cloned())
                    .map(Box::new);
                cells.push(FrbTableCell {
                    text: builder.build(),
                    col_span,
                    row_span,
                    align,
                    header,
                    block,
                    publisher_box: cell_publisher_box.map(Box::new),
                    publisher_padding_specified,
                    publisher_border_specified,
                    narrow_style,
                });
            }
            let is_header = in_head || cells.iter().all(|c| c.header);
            rows.push(FrbTableRow { cells, is_header });
        }

        let column_count = rows
            .iter()
            .map(|r| r.cells.iter().map(|c| c.col_span).sum::<i32>())
            .max()
            .unwrap_or(0);
        let authored_geometry = self
            .css
            .as_mut()
            .and_then(|c| c.publisher_box_style(table))
            .is_some()
            || rows.iter().any(|r| {
                r.cells.iter().any(|c| {
                    c.publisher_border_specified
                        || c.publisher_padding_specified
                        || c.publisher_box.as_ref().is_some_and(|b| !b.is_default())
                })
            });

        if rows.is_empty() || column_count == 0 {
            return Ok(());
        }
        if (column_count == 1 && !authored_geometry) || column_count > 12 {
            self.register_all_ids(table);
            for row in rows {
                for cell in row.cells {
                    if kotlin_is_blank(&cell.text.text) {
                        continue;
                    }
                    let merged = merged_table_cell_block(table_block.as_ref(), &cell);
                    self.out.push(FrbContentElement::Paragraph {
                        text: cell.text,
                        style: FrbParagraphStyle::Normal,
                        block: Some(Box::new(merged)),
                    });
                }
            }
        } else {
            self.register_all_ids(table);
            self.out
                .push(FrbContentElement::Table(Box::new(FrbTableElement {
                    rows,
                    block: table_block.map(Box::new),
                    publisher_row_layout: None,
                    center_cells_vertically: false,
                    stack_cells_below_css_px: responsive.map(|r| r.width_css_px),
                    publisher_box: None,
                })));
        }
        Ok(())
    }

    fn responsive_table_style(
        &mut self,
        table: usize,
        source_rows: &[(usize, Vec<usize>)],
    ) -> Option<ResponsiveTableStyle> {
        let thresholds = self.css.as_ref()?.max_width_breakpoints_css_px();
        if thresholds.is_empty() {
            return None;
        }
        let total_cells: usize = source_rows.iter().map(|(_, cells)| cells.len()).sum();
        if source_rows.is_empty()
            || source_rows
                .iter()
                .any(|(_, cells)| !(2..=4).contains(&cells.len()))
            || total_cells > MAX_RESPONSIVE_TABLE_CELLS
        {
            return None;
        }
        let mut cells = Vec::with_capacity(total_cells);
        for (_, row_cells) in source_rows {
            cells.extend_from_slice(row_cells);
        }
        for &cell in &cells {
            let colspan = self
                .doc
                .attr_or_empty(cell, "colspan")
                .parse::<i32>()
                .ok()
                .map_or(1, |v| v.clamp(1, 10));
            let rowspan = self
                .doc
                .attr_or_empty(cell, "rowspan")
                .parse::<i32>()
                .ok()
                .map_or(1, |v| v.clamp(1, 20));
            if colspan != 1 || rowspan != 1 {
                return None;
            }
            let base_display = self.css_computed(cell)?;
            if !matches!(base_display.display.as_deref(), None | Some("table-cell")) {
                return None;
            }
        }

        let mut descendants: Vec<usize> = Vec::new();
        let collect_children = |element: usize, desc: &mut Vec<usize>, doc: &Document| -> bool {
            let mut child = doc.nodes[element].first_child;
            while let Some(c) = child {
                if doc.is_element(c) {
                    if desc.len() >= MAX_RESPONSIVE_TABLE_DESCENDANTS {
                        return false;
                    }
                    desc.push(c);
                }
                child = doc.nodes[c].next_sibling;
            }
            true
        };
        for &cell in &cells {
            if !collect_children(cell, &mut descendants, self.doc) {
                return None;
            }
        }
        let mut desc_idx = 0usize;
        while desc_idx < descendants.len() {
            let curr = descendants[desc_idx];
            desc_idx += 1;
            if !collect_children(curr, &mut descendants, self.doc) {
                return None;
            }
        }

        let base_table_box = self.query_publisher_box_style(CssQuery::Base, table);
        let mut admitted: Option<ResponsiveTableStyle> = None;
        let mut admitted_cells: Option<Vec<FrbTableCellStyle>> = None;

        for threshold in thresholds {
            if !self.css.as_mut()?.has_responsive_at_max_width(threshold) {
                continue;
            }
            let resp_query = CssQuery::Responsive(threshold);
            if self.query_computed(resp_query, table)?.hidden
                || self.query_publisher_box_style(resp_query, table) != base_table_box
            {
                return None;
            }
            let mut matches = true;
            for &cell in &cells {
                let own = self.query_computed(resp_query, cell)?;
                let original = self.query_computed(CssQuery::Base, cell)?;
                let cell_ok = own.display.as_deref() == Some("block")
                    && !own.hidden
                    && own.visibility_hidden == original.visibility_hidden
                    && own.text_transform == original.text_transform
                    && own.font_family_name == original.font_family_name
                    && font_typography_from_computed(&own)
                        == font_typography_from_computed(&original)
                    && own.underline == original.underline
                    && own.strike == original.strike
                    && own.super_script == original.super_script
                    && own.sub_script == original.sub_script
                    && own.unicode_bidi == original.unicode_bidi
                    && self.same_responsive_generated_content(cell, threshold);
                if !cell_ok {
                    matches = false;
                    break;
                }
            }
            if !matches {
                if admitted.is_some() {
                    return None;
                }
                continue;
            }
            for &el in &descendants {
                let base_comp = self.query_computed(CssQuery::Base, el)?;
                let resp_comp = self.query_computed(resp_query, el)?;
                if !same_responsive_inline_presentation(&base_comp, &resp_comp)
                    || self.query_publisher_box_style(CssQuery::Base, el)
                        != self.query_publisher_box_style(resp_query, el)
                    || !self.same_responsive_generated_content(el, threshold)
                {
                    return None;
                }
            }
            let mut variants = Vec::with_capacity(cells.len());
            for &cell in &cells {
                let header = self.doc.normal_name(cell) == "th";
                variants.push(self.responsive_cell_style(cell, table, header, threshold));
            }
            if admitted.is_none() {
                let mut styles = HashMap::with_capacity(cells.len());
                for (index, &cell) in cells.iter().enumerate() {
                    styles.insert(cell, variants[index].clone());
                }
                admitted = Some(ResponsiveTableStyle {
                    width_css_px: threshold,
                    cells: styles,
                });
                admitted_cells = Some(variants);
            } else if admitted_cells.as_ref() != Some(&variants) {
                return None;
            }
        }
        admitted
    }

    fn same_responsive_generated_content(&mut self, element: usize, width_css_px: f32) -> bool {
        let same_side = |this: &mut Self, after: bool| -> bool {
            let base = this.css.as_mut().and_then(|c| c.generated(element, after));
            let narrow = this
                .css
                .as_mut()
                .and_then(|c| c.responsive_generated(width_css_px, element, after));
            match (base, narrow) {
                (None, None) => true,
                (Some(a), Some(b)) => {
                    a.text == b.text
                        && a.italic == b.italic
                        && a.font_weight == b.font_weight
                        && a.scale == b.scale
                        && a.foreground_color_argb == b.foreground_color_argb
                        && a.background_color_argb == b.background_color_argb
                        && a.font_feature_settings == b.font_feature_settings
                        && a.font_family_name == b.font_family_name
                        && a.font_typography == b.font_typography
                        && a.text_transform == b.text_transform
                        && a.visibility_hidden == b.visibility_hidden
                        && a.publisher_box == b.publisher_box
                }
                _ => false,
            }
        };
        same_side(self, false) && same_side(self, true)
    }

    fn responsive_cell_style(
        &mut self,
        cell: usize,
        table: usize,
        header: bool,
        width_css_px: f32,
    ) -> FrbTableCellStyle {
        let resp_query = CssQuery::Responsive(width_css_px);
        let computed = self.query_computed(resp_query, cell);
        let padding_specified = computed.as_deref().is_some_and(|c| c.padding_specified);
        let border_specified_own = computed.as_deref().is_some_and(|c| c.border_specified);
        let publisher_box = self
            .query_publisher_box_style(resp_query, cell)
            .or_else(|| {
                (padding_specified || border_specified_own).then(FrbPublisherBoxStyle::default)
            });
        FrbTableCellStyle {
            block: self
                .table_cell_block_style(cell, table, resp_query, CssQuery::Base)
                .map(Box::new),
            publisher_box: publisher_box.map(Box::new),
            align: self.cell_align(cell, header, resp_query),
            publisher_padding_specified: padding_specified,
            publisher_border_specified: self.table_border_specified(cell, table, resp_query),
        }
    }

    fn table_border_specified(&mut self, cell: usize, table: usize, query: CssQuery) -> bool {
        let mut current = Some(cell);
        while let Some(el) = current {
            if self
                .query_computed(query, el)
                .is_some_and(|c| c.border_specified)
            {
                if el == cell || el == table {
                    return true;
                }
                let row_box = self.query_publisher_box_style(query, el);
                let has_painted_row_border = row_box.as_ref().is_some_and(|b| {
                    [b.border_top, b.border_right, b.border_bottom, b.border_left]
                        .into_iter()
                        .flatten()
                        .any(|side| {
                            side.uses_current_color || ((side.color_argb as u32) >> 24) != 0
                        })
                });
                if !has_painted_row_border {
                    return true;
                }
            }
            if el == table {
                return self
                    .doc
                    .attr(table, "border")
                    .is_some_and(|v| kotlin_trim(v).parse::<i32>().ok() == Some(0));
            }
            current = self.doc.parent_element(el);
        }
        false
    }

    fn emit_table_caption(&mut self, caption: usize) -> Result<(), MapError> {
        let open_box = self.begin_publisher_box(caption, false, false);
        let res = (|| -> Result<(), MapError> {
            let mut builder = HtmlInlineBuilder::new();
            self.append_children_inline(caption, &mut builder, false, 0, None, None)?;
            if !builder.is_blank() {
                let mut block = self.block_style_for(caption, false).unwrap_or_default();
                block.align = Some(FrbBlockAlign::Center);
                block.italic = Some(true);
                block.first_line_indent = Some(false);
                self.out.push(FrbContentElement::Paragraph {
                    text: builder.build(),
                    style: FrbParagraphStyle::Normal,
                    block: Some(Box::new(block)),
                });
            }
            Ok(())
        })();
        self.end_publisher_box(open_box);
        res
    }

    fn append_cell_content(
        &mut self,
        cell: usize,
        builder: &mut HtmlInlineBuilder,
    ) -> Result<(), MapError> {
        let whole_cell_style = self.table_cell_inline_style(cell);
        if let Some(style) = whole_cell_style.clone() {
            builder.push_style(style);
        }
        let gen_before = self.generated(cell, false);
        self.append_generated_run(builder, gen_before.as_ref());

        let mut child = self.doc.nodes[cell].first_child;
        while let Some(c) = child {
            let next = self.doc.nodes[c].next_sibling;
            let is_cell_block = self.doc.is_element(c)
                && (self.has_tag(c, TAG_CELL_BLOCK) || self.is_css_flow_block(c));
            if is_cell_block {
                if !builder.is_blank() && builder.last_character != Some('\n') {
                    builder.line_break();
                }
                self.append_inline(c, builder, false, 0, None, None)?;
                builder.line_break();
            } else {
                self.append_inline(c, builder, false, 0, None, None)?;
            }
            child = next;
        }

        let gen_after = self.generated(cell, true);
        self.append_generated_run(builder, gen_after.as_ref());
        if whole_cell_style.is_some() {
            builder.pop();
        }
        Ok(())
    }

    fn table_cell_block_style(
        &mut self,
        cell: usize,
        table: usize,
        own_query: CssQuery,
        base_query: CssQuery,
    ) -> Option<FrbBlockStyle> {
        let language = self.language_for(cell);
        let table_language = self.language_for(table);
        if self.css.is_none() {
            let direction = self.attribute_direction(cell);
            let table_dir = self.attribute_direction(table);
            let fg = self.legacy_foreground(cell);
            let table_fg = self.legacy_foreground(table);
            let style = FrbBlockStyle {
                language: language.filter(|l| Some(l) != table_language.as_ref()),
                direction: direction.filter(|d| Some(*d) != table_dir),
                foreground_color_argb: fg.filter(|c| Some(*c) != table_fg),
                background_color_argb: self.legacy_visual_background(cell, Some(table)),
                ..FrbBlockStyle::default()
            };
            return (!style.is_default()).then_some(style);
        }

        let own = self.query_computed(own_query, cell)?;
        let base = self.query_computed(base_query, table)?;
        let own_direction = match own.direction.as_deref() {
            Some("ltr") => Some(FrbBookTextDirection::Ltr),
            Some("rtl") => Some(FrbBookTextDirection::Rtl),
            Some("auto") => Some(FrbBookTextDirection::Auto),
            _ => self.attribute_direction(cell),
        };
        let base_direction = match base.direction.as_deref() {
            Some("ltr") => Some(FrbBookTextDirection::Ltr),
            Some("rtl") => Some(FrbBookTextDirection::Rtl),
            Some("auto") => Some(FrbBookTextDirection::Auto),
            _ => self.attribute_direction(table),
        };
        let own_bold = computed_bold(&own);
        let base_bold = computed_bold(&base);
        let own_typo = font_typography_from_computed(&own);
        let base_typo = font_typography_from_computed(&base);
        let line_height_mult = match (own.line_height_mult, base.line_height_mult) {
            (None, Some(_)) => Some(1.3),
            (Some(olh), None) => Some(olh),
            (Some(olh), Some(blh)) if (olh - blh).abs() > 0.001 => Some(olh),
            _ => None,
        };
        let style = FrbBlockStyle {
            italic: own.italic.filter(|_| own.italic != base.italic),
            bold: own_bold.filter(|_| own_bold != base_bold),
            font_weight: own
                .font_weight
                .filter(|_| own.font_weight != base.font_weight),
            font_scale: ((own.font_size_em - base.font_size_em).abs() > 0.001)
                .then(|| own.font_size_em.clamp(0.5, 3.0)),
            font_family: own
                .font_family_name
                .as_ref()
                .filter(|_| own.font_family_name != base.font_family_name)
                .cloned(),
            font_feature_settings: (own.font_feature_settings != base.font_feature_settings)
                .then(|| own.font_feature_settings.clone().unwrap_or_default()),
            font_typography: (own_typo != base_typo).then(|| Box::new(own_typo)),
            line_height_mult,
            language: language.filter(|l| Some(l) != table_language.as_ref()),
            direction: own_direction.filter(|d| Some(*d) != base_direction),
            foreground_color_argb: own
                .foreground_color_argb
                .filter(|_| own.foreground_color_argb != base.foreground_color_argb),
            background_color_argb: self.query_visual_background(own_query, cell, Some(table)),
            ..FrbBlockStyle::default()
        };
        (!style.is_default()).then_some(style)
    }

    fn table_cell_inline_style(&mut self, cell: usize) -> Option<FrbSpanStyle> {
        let computed = self.css_computed(cell)?;
        let mut style = FrbSpanStyle::default();
        let mut any = false;
        let dec = (if computed.underline {
            TEXT_DECORATION_UNDERLINE
        } else {
            0
        }) | (if computed.strike {
            TEXT_DECORATION_LINE_THROUGH
        } else {
            0
        });
        if dec != 0 {
            style.decoration = Some(dec);
            any = true;
        }
        if computed.super_script || computed.sub_script {
            style.baseline = Some(if computed.super_script { 0.5 } else { -0.5 });
            style.size = FrbTextUnit::Em(0.75);
            any = true;
        }
        any.then_some(style)
    }

    fn cell_align(&mut self, cell: usize, header: bool, query: CssQuery) -> Option<FrbBlockAlign> {
        if let Some(comp) = self.query_computed(query, cell) {
            let css_align = match comp.text_align.as_deref() {
                Some("center") => Some(FrbBlockAlign::Center),
                Some("end") => Some(FrbBlockAlign::End),
                Some("start") => Some(FrbBlockAlign::Start),
                Some("right") => Some(FrbBlockAlign::Right),
                Some("left") => Some(FrbBlockAlign::Left),
                _ => None,
            };
            if css_align.is_some() {
                return css_align;
            }
        }
        let attr = self.doc.attr_or_empty(cell, "align");
        if attr.eq_ignore_ascii_case("center") {
            Some(FrbBlockAlign::Center)
        } else if attr.eq_ignore_ascii_case("right") {
            Some(FrbBlockAlign::Right)
        } else if attr.eq_ignore_ascii_case("left") {
            Some(FrbBlockAlign::Left)
        } else if header {
            Some(FrbBlockAlign::Center)
        } else {
            None
        }
    }

    // -------------------------------------------------------------- Inline & Block SVG

    fn emit_svg(&mut self, source: usize) {
        if self.css_computed(source).is_some_and(|c| c.hidden) {
            return;
        }
        let Some(path) = self.resolve_svg(source) else {
            return;
        };
        let computed = self.css_computed(source);
        let dark_style = self.css.as_mut().map(|c| c.dark_image_style(source));
        let alt_text = self.svg_alt_text(source);
        self.out
            .push(FrbContentElement::Image(Box::new(FrbImageElement {
                path,
                width_frac: computed.as_ref().and_then(|c| c.width_frac),
                width_em: computed.as_ref().and_then(|c| c.width_em),
                height_em: computed.as_ref().and_then(|c| c.height_em),
                max_width_frac: computed.as_ref().and_then(|c| c.max_width_frac),
                max_width_em: computed.as_ref().and_then(|c| c.max_width_em),
                max_height_em: computed.as_ref().and_then(|c| c.max_height_em),
                max_height_viewport_frac: computed
                    .as_ref()
                    .and_then(|c| c.max_height_viewport_frac),
                dark_mode_invert: dark_style.is_some_and(|d| d.image_invert),
                dark_mode_background_argb: dark_style.and_then(|d| d.background_color_argb),
                dark_mode_background_current_color: dark_style
                    .is_some_and(|d| d.background_uses_current_color),
                alt_text,
                space_before_specified: computed.as_ref().is_some_and(|c| c.margin_top_specified),
                space_after_specified: computed.as_ref().is_some_and(|c| c.margin_bottom_specified),
            })));
    }

    fn resolve_svg(&mut self, source: usize) -> Option<String> {
        if self.css_computed(source).is_some_and(|c| c.hidden) {
            return None;
        }
        let mut markup = String::new();
        self.serialize_svg_node(source, true, &mut markup);
        self.callbacks.resolve_inline_svg(&markup)
    }

    fn svg_alt_text(&self, source: usize) -> Option<String> {
        let aria = self.doc.attr_or_empty(source, "aria-label");
        if !kotlin_is_blank(aria) {
            let trimmed = kotlin_trim(aria);
            if !trimmed.is_empty() {
                return Some(trimmed.to_owned());
            }
        }
        // Mirrors `source.selectFirst("title")?.text().orEmpty().trim()`.
        let mut stack = vec![source];
        while let Some(curr) = stack.pop() {
            if self.doc.normal_name(curr) == "title" {
                let text = self.doc.jsoup_text(curr);
                let trimmed = kotlin_trim(&text);
                return (!trimmed.is_empty()).then(|| trimmed.to_owned());
            }
            let start = stack.len();
            let mut child = self.doc.first_element_child(curr);
            while let Some(c) = child {
                stack.push(c);
                child = self.doc.next_element_sibling(c);
            }
            stack[start..].reverse();
        }
        None
    }

    fn serialize_svg_node(&mut self, node_id: usize, is_svg_root: bool, out: &mut String) {
        match &self.doc.nodes[node_id].kind {
            Kind::Element {
                name,
                attrs,
                self_closing,
                ..
            } => {
                let normal = self.doc.normal_name(node_id);
                let image_override: Option<(&'static str, String)> = if normal == "image" {
                    let attr_name = if self.doc.attr(node_id, "xlink:href").is_some() {
                        "xlink:href"
                    } else {
                        "href"
                    };
                    let raw_ref = self.doc.attr_or_empty(node_id, attr_name);
                    if !raw_ref.is_empty()
                        && !raw_ref
                            .get(..5)
                            .is_some_and(|p| p.eq_ignore_ascii_case("data:"))
                    {
                        self.callbacks.resolve_image(raw_ref).and_then(|path| {
                            let bytes = std::fs::read(&path).ok()?;
                            if bytes.is_empty() {
                                return None;
                            }
                            let fname = Path::new(&path)
                                .file_name()
                                .and_then(|s| s.to_str())
                                .unwrap_or("");
                            let mime = image_mime(fname, &bytes);
                            let encoded = base64_encode(&bytes);
                            Some((attr_name, format!("data:{mime};base64,{encoded}")))
                        })
                    } else {
                        None
                    }
                } else {
                    None
                };

                let has_non_empty_xmlns = is_svg_root
                    && attrs
                        .iter()
                        .any(|(k, v)| attribute_keys_equal(k, "xmlns") && !v.is_empty());
                let mut wrote_xmlns = has_non_empty_xmlns;
                let mut replaced_image_attr = false;

                out.push('<');
                out.push_str(name);
                for (key, value) in attrs {
                    out.push(' ');
                    out.push_str(key);
                    out.push_str("=\"");
                    if is_svg_root && !wrote_xmlns && attribute_keys_equal(key, "xmlns") {
                        out.push_str("http://www.w3.org/2000/svg");
                        wrote_xmlns = true;
                    } else if let Some((target_attr, ref data_uri)) = image_override {
                        if !replaced_image_attr && attribute_keys_equal(key, target_attr) {
                            push_xml_escaped_attr(out, data_uri);
                            replaced_image_attr = true;
                        } else {
                            push_xml_escaped_attr(out, value);
                        }
                    } else {
                        push_xml_escaped_attr(out, value);
                    }
                    out.push('"');
                }
                if is_svg_root && !wrote_xmlns {
                    out.push_str(" xmlns=\"http://www.w3.org/2000/svg\"");
                }
                if let Some((target_attr, ref data_uri)) = image_override {
                    if !replaced_image_attr {
                        out.push(' ');
                        out.push_str(target_attr);
                        out.push_str("=\"");
                        push_xml_escaped_attr(out, data_uri);
                        out.push('"');
                    }
                }

                let first_child = self.doc.nodes[node_id].first_child;
                if first_child.is_none() && *self_closing {
                    out.push_str(" />");
                    return;
                }
                out.push('>');
                let mut child = first_child;
                while let Some(c) = child {
                    let next = self.doc.nodes[c].next_sibling;
                    self.serialize_svg_node(c, false, out);
                    child = next;
                }
                out.push_str("</");
                out.push_str(name);
                out.push('>');
            }
            Kind::Text(text) => push_xml_escaped_text(out, text),
            Kind::Data(data) => out.push_str(data),
            Kind::Cdata(cdata) => {
                out.push_str("<![CDATA[");
                out.push_str(cdata);
                out.push_str("]]>");
            }
            Kind::Comment(comment) => {
                out.push_str("<!--");
                out.push_str(comment);
                out.push_str("-->");
            }
            Kind::Pi { target, data } => {
                out.push_str("<?");
                out.push_str(target);
                if !data.is_empty() {
                    out.push(' ');
                    out.push_str(data);
                }
                out.push_str("?>");
            }
            Kind::Document | Kind::Doctype { .. } => {}
        }
    }

    // -------------------------------------------------------------- Images

    fn emit_image(&mut self, src: &str, node_id: Option<usize>) {
        let open_box = node_id.and_then(|id| self.begin_publisher_box(id, false, false));
        self.emit_image_content(src, node_id);
        self.end_publisher_box(open_box);
    }

    fn emit_image_content(&mut self, src: &str, node_id: Option<usize>) {
        if let Some(id) = node_id {
            if self.css_computed(id).is_some_and(|c| c.hidden) {
                return;
            }
        }
        let alt = node_id.and_then(|id| self.image_alt(id));
        if src.is_empty() {
            self.emit_missing_image_alt(alt);
            return;
        }
        let Some(path) = self.callbacks.resolve_image(src) else {
            self.emit_missing_image_alt(alt);
            return;
        };
        let computed = node_id.and_then(|id| self.css_computed(id));
        let dark_style = node_id.and_then(|id| self.css.as_mut().map(|c| c.dark_image_style(id)));
        self.out
            .push(FrbContentElement::Image(Box::new(FrbImageElement {
                path,
                width_frac: computed.as_ref().and_then(|c| c.width_frac),
                width_em: computed.as_ref().and_then(|c| c.width_em),
                height_em: computed.as_ref().and_then(|c| c.height_em),
                max_height_em: computed.as_ref().and_then(|c| c.max_height_em),
                max_height_viewport_frac: computed
                    .as_ref()
                    .and_then(|c| c.max_height_viewport_frac),
                max_width_em: computed.as_ref().and_then(|c| c.max_width_em),
                max_width_frac: computed.as_ref().and_then(|c| c.max_width_frac),
                dark_mode_invert: dark_style.is_some_and(|d| d.image_invert),
                dark_mode_background_argb: dark_style.and_then(|d| d.background_color_argb),
                dark_mode_background_current_color: dark_style
                    .is_some_and(|d| d.background_uses_current_color),
                alt_text: alt,
                space_before_specified: computed.as_ref().is_some_and(|c| c.margin_top_specified),
                space_after_specified: computed.as_ref().is_some_and(|c| c.margin_bottom_specified),
            })));
    }

    fn emit_missing_image_alt(&mut self, alt: Option<String>) {
        let Some(alt) = alt else {
            return;
        };
        self.out.push(FrbContentElement::Paragraph {
            text: FrbAnnotatedString::plain(alt),
            style: FrbParagraphStyle::Normal,
            block: Some(Box::new(FrbBlockStyle {
                italic: Some(true),
                first_line_indent: Some(false),
                ..FrbBlockStyle::default()
            })),
        });
    }

    fn image_alt(&self, node_id: usize) -> Option<String> {
        let raw = non_blank_or(self.doc.attr_or_empty(node_id, "alt"), || {
            non_blank_or(self.doc.attr_or_empty(node_id, "aria-label"), || {
                self.doc.attr_or_empty(node_id, "title")
            })
        });
        let trimmed = kotlin_trim(raw);
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    }

    fn is_block_like_media(&mut self, node_id: usize) -> bool {
        let Some(computed) = self.css_computed(node_id) else {
            return false;
        };
        computed.float_side.is_some()
            || computed.width_frac.unwrap_or(0.0) > 0.45
            || computed.width_em.unwrap_or(0.0) > 12.0
            || computed.height_em.unwrap_or(0.0) > 3.0
    }

    // -------------------------------------------------------------- Drop Caps

    fn explicit_floated_initial(&mut self, paragraph: usize) -> Option<ExplicitFloatedInitial> {
        self.css.as_ref()?;
        if !self.has_subtree(paragraph, SUBTREE_HAS_INLINE_FLOW_CANDIDATE) {
            return None;
        }
        if self.css.as_mut()?.generated(paragraph, false).is_some() {
            return None;
        }

        let mut candidate: Option<usize> = None;
        let mut child = self.doc.nodes[paragraph].first_child;
        while let Some(c) = child {
            match &self.doc.nodes[c].kind {
                Kind::Text(_) | Kind::Cdata(_) => {
                    if !self.is_text_blank(c) {
                        return None;
                    }
                }
                Kind::Element { .. } => {
                    if !self.css_computed(c).is_some_and(|comp| comp.hidden) {
                        candidate = Some(c);
                        break;
                    }
                }
                _ => {}
            }
            child = self.doc.nodes[c].next_sibling;
        }
        let candidate = candidate?;
        if !self.has_tag(candidate, TAG_EXPLICIT_INITIAL) {
            return None;
        }
        let mut raw = String::new();
        let mut c = self.doc.nodes[candidate].first_child;
        while let Some(cid) = c {
            match &self.doc.nodes[cid].kind {
                Kind::Text(s) | Kind::Cdata(s) => raw.push_str(s),
                _ => return None,
            }
            c = self.doc.nodes[cid].next_sibling;
        }
        if self.css.as_mut()?.generated(candidate, false).is_some()
            || self.css.as_mut()?.generated(candidate, true).is_some()
        {
            return None;
        }

        // Collapse [\s\u00A0]+ to single ASCII space, then Kotlin trim.
        let collapsed = collapse_inline_whitespace(&raw);
        let visible = kotlin_trim(&collapsed);
        if visible.is_empty() || visible.contains(' ') {
            return None;
        }
        let code_points = visible.chars().count();
        if !(1..=3).contains(&code_points) || !visible.chars().any(char::is_alphabetic) {
            return None;
        }

        let source_text_len = utf16_len(visible) as i32;
        let mut style =
            self.css
                .as_mut()?
                .floated_text_initial(candidate, paragraph, source_text_len)?;
        style.language = self.language_for(candidate);
        if style.direction.is_none() {
            style.direction = self.attribute_direction(candidate);
        }
        Some(ExplicitFloatedInitial {
            node_id: candidate,
            style,
        })
    }

    // -------------------------------------------------------------- Block Styles

    fn block_style_for(
        &mut self,
        element: usize,
        with_first_letter: bool,
    ) -> Option<FrbBlockStyle> {
        if self.css.is_none() {
            let style = FrbBlockStyle {
                align: self.attribute_align(element),
                language: self.language_for(element),
                direction: self.attribute_direction(element),
                foreground_color_argb: self.legacy_foreground(element),
                background_color_argb: self.legacy_visual_background(element, None),
                ..FrbBlockStyle::default()
            };
            return (!style.is_default()).then_some(style);
        }

        let computed = self.css_computed(element)?;
        let mut page_break = computed.page_break_before;
        if !page_break {
            let has_pb_rules = self
                .css
                .as_ref()
                .is_some_and(|c| c.has_page_break_before_rules());
            let skip_walk = !has_pb_rules && (self.doc.preindex.doc_flags & DOC_HAS_ANY_STYLE) == 0;
            if !skip_walk {
                let mut child = element;
                let mut ancestor = self.doc.parent_element(element);
                while let Some(anc) = ancestor {
                    if self.has_tag(anc, TAG_ROOT) {
                        break;
                    }
                    if self.css_computed(anc).is_some_and(|b| b.page_break_before)
                        && self.first_block_child(anc) == Some(child)
                    {
                        page_break = true;
                        break;
                    }
                    child = anc;
                    ancestor = self.doc.parent_element(anc);
                }
            }
        }

        let align = match computed.text_align.as_deref() {
            Some("center") => Some(FrbBlockAlign::Center),
            Some("end") => Some(FrbBlockAlign::End),
            Some("start") => Some(FrbBlockAlign::Start),
            Some("right") => Some(FrbBlockAlign::Right),
            Some("left") => Some(FrbBlockAlign::Left),
            Some("justify") => Some(FrbBlockAlign::Justify),
            _ => self.attribute_align(element),
        };

        let computed_font_scale = computed.font_size_em.clamp(0.6, 2.6);
        let font_scale = if !(0.999..=1.001).contains(&computed_font_scale)
            || self.has_tag(element, TAG_HEADING)
        {
            Some(computed_font_scale)
        } else {
            None
        };
        let typo = font_typography_from_computed(&computed);
        let first_letter = if with_first_letter {
            self.css.as_mut().and_then(|c| c.first_letter(element))
        } else {
            None
        };
        let direction = match computed.direction.as_deref() {
            Some("ltr") => Some(FrbBookTextDirection::Ltr),
            Some("rtl") => Some(FrbBookTextDirection::Rtl),
            Some("auto") => Some(FrbBookTextDirection::Auto),
            _ => self.attribute_direction(element),
        };

        let style = FrbBlockStyle {
            align,
            italic: computed.italic,
            bold: computed.font_weight.map(|w| w >= 600),
            font_weight: computed.font_weight,
            font_scale,
            first_line_indent: computed.text_indent_em.map(|v| v > 0.05),
            first_line_indent_em: computed.text_indent_em,
            font_family: computed.font_family_name.clone(),
            font_feature_settings: computed.font_feature_settings.clone(),
            font_typography: (!typo.is_empty()).then(|| Box::new(typo)),
            line_height_mult: computed.line_height_mult,
            hyphens: computed.hyphens_auto,
            space_before_specified: computed.margin_top_specified,
            space_after_specified: computed.margin_bottom_specified,
            page_break_before: page_break,
            first_letter: first_letter.map(Box::new),
            language: self.language_for(element),
            direction,
            foreground_color_argb: computed.foreground_color_argb,
            background_color_argb: computed.background_color_argb,
            ..FrbBlockStyle::default()
        };
        (!style.is_default()).then_some(style)
    }

    fn first_block_child(&self, container: usize) -> Option<usize> {
        let mut child = self.doc.first_element_child(container);
        while let Some(c) = child {
            if !self.has_tag(c, TAG_NON_BLOCK) {
                return Some(c);
            }
            child = self.doc.next_element_sibling(c);
        }
        None
    }

    // -------------------------------------------------------------- Generated Runs

    fn generated(&mut self, element: usize, after: bool) -> Option<GeneratedRun> {
        let run = self.css.as_mut()?.generated(element, after)?;
        if run.text.is_empty() {
            return Some(run);
        }
        let char_count = utf16_len(&run.text);
        if self.accept_generated(char_count) {
            Some(run)
        } else {
            None
        }
    }

    fn accept_generated(&mut self, char_count: usize) -> bool {
        if char_count == 0 || char_count > self.options.max_generated_run_chars {
            return false;
        }
        let cap = self
            .options
            .remaining_generated_total_chars
            .min(self.options.max_generated_total_chars);
        if self.generated_chars_used > cap.saturating_sub(char_count) {
            self.was_aggregate_limited = true;
            return false;
        }
        self.generated_chars_used += char_count;
        true
    }

    fn append_generated_run(&self, builder: &mut HtmlInlineBuilder, run: Option<&GeneratedRun>) {
        let Some(run) = run else {
            return;
        };
        if let Some(fam) = &run.font_family_name {
            builder.push_annotation(INLINE_FONT_FAMILY_TAG, fam.clone());
        }
        builder.push_annotation(
            INLINE_FONT_TYPOGRAPHY_TAG,
            encode_font_typography(&run.font_typography),
        );
        if run.visibility_hidden {
            builder.push_annotation(HIDDEN_TEXT_TAG, "true".to_owned());
        }
        let style = generated_span_style(run);
        if let Some(s) = style.clone() {
            builder.push_style(s);
        }
        let transformed = match run.text_transform.as_deref() {
            None | Some("none") => None,
            Some(tr) => self.transform_text_cow(&run.text, tr, None, None),
        };
        builder.text(transformed.as_deref().unwrap_or(&run.text));
        if style.is_some() {
            builder.pop();
        }
        if run.visibility_hidden {
            builder.pop();
        }
        builder.pop();
        if run.font_family_name.is_some() {
            builder.pop();
        }
    }

    fn emit_generated_paragraph(&mut self, element: usize, after: bool, quote: bool) {
        let Some(run) = self.generated(element, after) else {
            return;
        };
        let mut builder = HtmlInlineBuilder::new();
        self.append_generated_run(&mut builder, Some(&run));
        let generated_box = run.publisher_box;
        if builder.is_blank() && generated_box.is_none() {
            return;
        }
        let start = self.out.len() as i32;
        if builder.is_blank() {
            let height_em = generated_box
                .as_ref()
                .and_then(|b| b.min_height_em)
                .unwrap_or(0.0);
            self.out.push(FrbContentElement::Spacer { height_em });
        } else {
            let mut block = self.loose_block_text_style(Some(element), false);
            block.first_line_indent = Some(false);
            self.out.push(FrbContentElement::Paragraph {
                text: builder.build(),
                style: if quote {
                    FrbParagraphStyle::Quote
                } else {
                    FrbParagraphStyle::Normal
                },
                block: Some(Box::new(block)),
            });
        }
        if let Some(style) = generated_box {
            let seq = self.publisher_box_sequence;
            self.publisher_box_sequence += 1;
            let id = format!(
                "{}:generated-box-{seq}",
                self.options.publisher_box_id_prefix
            );
            self.publisher_boxes.push(FrbPublisherBoxSpan {
                id,
                parent_id: self.publisher_box_stack.last().cloned(),
                start_element: start,
                end_element_exclusive: self.out.len() as i32,
                style,
                float_side: None,
                clear: FrbPublisherClear::None,
                draw_start: true,
                draw_end: true,
            });
        }
    }

    // -------------------------------------------------------------- Headings & Empty-Line

    fn title_class_level(&self, element: usize) -> Option<i32> {
        let level = (self.doc.node_meta(element).attr_flags >> TITLE_CLASS_SHIFT) & 0x7;
        if level == 0 {
            return None;
        }
        if (1..=6).contains(&level) {
            return Some(level as i32);
        }
        // Fallback for non-ASCII digits / overflow marker (7)
        let class_attr = self.doc.attr_or_empty(element, "class");
        for token in class_attr
            .split([' ', '\t', '\r', '\n', '\u{000c}'])
            .filter(|t| !t.is_empty())
        {
            if let Some(prefix) = token.get(..5) {
                if prefix.eq_ignore_ascii_case("title") {
                    let suffix = &token[5..];
                    if suffix.is_empty() {
                        return Some(1);
                    }
                    if suffix.chars().all(|c| c.is_ascii_digit()) {
                        let parsed = suffix.parse::<i32>().unwrap_or(6);
                        return Some(parsed.saturating_add(1).min(6));
                    }
                }
            }
        }
        None
    }

    fn dtbook_heading_level(&self, element: usize) -> i32 {
        let mut nested_generic_levels = 0_i32;
        let mut parent = self.doc.parent_element(element);
        while let Some(p) = parent {
            let name = self.doc.normal_name(p);
            if name == "level" {
                nested_generic_levels += 1;
            } else if name.len() == 6 && name.starts_with("level") {
                if let Some(digit) = name.as_bytes()[5].checked_sub(b'0').filter(|&d| d <= 9) {
                    return (i32::from(digit) + nested_generic_levels).clamp(1, 6);
                }
            }
            parent = self.doc.parent_element(p);
        }
        nested_generic_levels.clamp(1, 6)
    }

    fn is_empty_line_block(&self, element: usize) -> bool {
        if !self.has_attr(element, ATTR_HAS_EMPTY_LINE_CLASS) {
            return false;
        }
        !self.has_subtree(element, SUBTREE_HAS_JAVA_NON_WS_TEXT)
    }

    // -------------------------------------------------------------- Inline

    fn append_children_inline(
        &mut self,
        element: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        suppressed_style_element: Option<usize>,
        parent_computed: Option<Arc<ComputedStyle>>,
    ) -> Result<(), MapError> {
        let mut child = self.doc.nodes[element].first_child;
        if child.is_none() {
            return Ok(());
        }
        let computed = parent_computed.or_else(|| self.css_computed(element));
        while let Some(c) = child {
            let next = self.doc.nodes[c].next_sibling;
            self.append_inline(
                c,
                out,
                preserve_whitespace,
                depth + 1,
                suppressed_style_element,
                computed.clone(),
            )?;
            child = next;
        }
        Ok(())
    }

    fn append_text_content(
        &self,
        node_id: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        text_transform: Option<&str>,
        parent: Option<usize>,
    ) {
        let raw = self.doc.text_content(node_id).unwrap_or("");
        let text_flags = self.doc.node_meta(node_id).text_flags;
        let no_transform = matches!(text_transform, None | Some("none"));
        if preserve_whitespace {
            if no_transform || (text_flags & TEXT_HTML_BLANK) != 0 {
                out.preindexed_preformatted_text(raw, text_flags);
            } else if let Some(transformed) = self.transform_text_cow(
                raw,
                text_transform.unwrap_or("none"),
                parent,
                out.last_character,
            ) {
                out.preformatted_text(&transformed);
            } else {
                out.preindexed_preformatted_text(raw, text_flags);
            }
        } else if no_transform || (text_flags & TEXT_HTML_BLANK) != 0 {
            out.preindexed_text(raw, text_flags);
        } else if let Some(transformed) = self.transform_text_cow(
            raw,
            text_transform.unwrap_or("none"),
            parent,
            out.last_character,
        ) {
            out.text(&transformed);
        } else {
            out.preindexed_text(raw, text_flags);
        }
    }

    fn append_inline(
        &mut self,
        node_id: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        suppressed_style_element: Option<usize>,
        parent_computed: Option<Arc<ComputedStyle>>,
    ) -> Result<(), MapError> {
        match &self.doc.nodes[node_id].kind {
            Kind::Text(_) | Kind::Cdata(_) => {
                let parent = self.doc.parent_element(node_id);
                let computed =
                    parent_computed.or_else(|| parent.and_then(|p| self.css_computed(p)));
                let hidden = computed.as_deref().is_some_and(|c| c.visibility_hidden);
                let family = computed.as_deref().and_then(|c| {
                    if c.monospace {
                        Some("monospace")
                    } else {
                        c.font_family_name.as_deref()
                    }
                });
                if let Some(fam) = family {
                    out.push_annotation(INLINE_FONT_FAMILY_TAG, fam.to_owned());
                }
                if let Some(comp) = computed.as_deref() {
                    let typo = font_typography_from_computed(comp);
                    out.push_annotation(INLINE_FONT_TYPOGRAPHY_TAG, encode_font_typography(&typo));
                }
                if hidden {
                    out.push_annotation(HIDDEN_TEXT_TAG, "true".to_owned());
                }
                self.append_text_content(
                    node_id,
                    out,
                    preserve_whitespace,
                    computed
                        .as_deref()
                        .and_then(|c| c.text_transform.as_deref()),
                    parent,
                );
                if hidden {
                    out.pop();
                }
                if computed.is_some() {
                    out.pop();
                }
                if family.is_some() {
                    out.pop();
                }
                Ok(())
            }
            Kind::Element { .. } => {
                let computed = self.css_computed(node_id);
                let bidi_mode = self.inline_bidi_mode(node_id, computed.as_deref());
                if let Some(mode) = bidi_mode {
                    out.push_annotation(BIDI_TAG, mode.to_owned());
                }
                let res = self.append_inline_element_inner(
                    node_id,
                    out,
                    preserve_whitespace,
                    depth,
                    suppressed_style_element,
                    computed,
                    parent_computed,
                );
                if bidi_mode.is_some() {
                    out.pop();
                }
                res
            }
            _ => Ok(()),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn append_inline_element_inner(
        &mut self,
        node_id: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        suppressed_style_element: Option<usize>,
        computed: Option<Arc<ComputedStyle>>,
        parent_computed: Option<Arc<ComputedStyle>>,
    ) -> Result<(), MapError> {
        if self.has_tag(node_id, TAG_NON_TEXT_CONTENT) {
            return Ok(());
        }
        if depth >= self.options.max_structure_depth {
            let mut child = self.doc.nodes[node_id].first_child;
            while let Some(c) = child {
                if matches!(self.doc.nodes[c].kind, Kind::Text(_) | Kind::Cdata(_)) {
                    self.append_text_content(c, out, preserve_whitespace, None, None);
                }
                child = self.doc.nodes[c].next_sibling;
            }
            return Ok(());
        }
        if computed.as_deref().is_some_and(|c| c.hidden) {
            return Ok(());
        }
        if let Some(own) = computed.as_ref() {
            if own.width_em.is_some()
                && self.append_inline_text_box(
                    node_id,
                    own,
                    out,
                    preserve_whitespace,
                    depth,
                    parent_computed.as_ref(),
                )?
            {
                return Ok(());
            }
        }
        if self.has_tag(node_id, TAG_MATH) {
            self.register_all_ids(node_id);
            self.append_inline_math(node_id, out);
            return Ok(());
        }

        let scoped_style = if Some(node_id) == suppressed_style_element {
            None
        } else {
            self.inline_style_for(node_id, computed.as_ref(), parent_computed.as_ref())
        };
        if let Some(style) = scoped_style.clone() {
            out.push_style(style);
        }

        let tag_name = self.doc.normal_name(node_id).into_owned();
        let body_res = match tag_name.as_str() {
            "br" => {
                out.line_break();
                Ok(())
            }
            "wbr" => {
                out.word_break_opportunity();
                Ok(())
            }
            "img" | "image" => {
                if !self.is_block_like_media(node_id) {
                    let src = if tag_name == "img" {
                        self.src_of(node_id)
                    } else {
                        let xlink = self.xlink_href_of(node_id);
                        if xlink.is_empty() {
                            self.href_of(node_id)
                        } else {
                            xlink
                        }
                    };
                    let alt = self.image_alt(node_id);
                    if let Some(path) = self.callbacks.resolve_image(src) {
                        out.inline_image(path, alt.as_deref());
                    } else if let Some(alt_text) = alt {
                        out.text(&alt_text);
                    }
                }
                Ok(())
            }
            "svg" => {
                let block_like = computed.as_deref().is_some_and(|c| {
                    c.width_frac.unwrap_or(0.0) > 0.45
                        || c.width_em.unwrap_or(0.0) > 12.0
                        || c.height_em.unwrap_or(0.0) > 3.0
                });
                if !block_like {
                    if let Some(path) = self.resolve_svg(node_id) {
                        let alt = self.svg_alt_text(node_id);
                        out.inline_image(path, alt.as_deref());
                    }
                }
                Ok(())
            }
            "ruby" => self.append_ruby(
                node_id,
                out,
                preserve_whitespace,
                depth,
                suppressed_style_element,
                computed,
            ),
            "rp" => Ok(()),
            "rt" => {
                out.push_style(FrbSpanStyle {
                    size: FrbTextUnit::Em(0.6),
                    baseline: Some(0.35),
                    ..FrbSpanStyle::default()
                });
                let r = self.append_children_inline(
                    node_id,
                    out,
                    preserve_whitespace,
                    depth,
                    suppressed_style_element,
                    computed,
                );
                out.pop();
                r
            }
            "q" => {
                let gen_before = self.generated(node_id, false);
                let gen_after = self.generated(node_id, true);
                let (open_q, close_q) = self.quote_marks_for(node_id);
                if let Some(run) = gen_before.as_ref() {
                    self.append_generated_run(out, Some(run));
                } else {
                    out.text(open_q);
                }
                self.append_children_inline(
                    node_id,
                    out,
                    preserve_whitespace,
                    depth,
                    suppressed_style_element,
                    computed,
                )?;
                if let Some(run) = gen_after.as_ref() {
                    self.append_generated_run(out, Some(run));
                } else {
                    out.text(close_q);
                }
                Ok(())
            }
            "mark" => {
                out.push_style(FrbSpanStyle {
                    background: color_argb_to_compose_ulong(MARK_BG_ARGB),
                    ..FrbSpanStyle::default()
                });
                let gen_before = self.generated(node_id, false);
                self.append_generated_run(out, gen_before.as_ref());
                let r = self.append_children_inline(
                    node_id,
                    out,
                    preserve_whitespace,
                    depth,
                    suppressed_style_element,
                    computed,
                );
                if r.is_ok() {
                    let gen_after = self.generated(node_id, true);
                    self.append_generated_run(out, gen_after.as_ref());
                }
                out.pop();
                r
            }
            "a" => {
                let href = kotlin_trim(self.href_of(node_id));
                let key = if href.is_empty() {
                    None
                } else {
                    self.callbacks.resolve_link(href)
                };
                if let Some(key) = key {
                    self.link_targets.insert(key.clone());
                    let note = self.is_note_reference(node_id, &key);
                    if note {
                        self.note_targets.insert(key.clone());
                    }
                    out.push_annotation(if note { FOOTNOTE_TAG } else { LINK_TAG }, key);
                    let r = self.append_link_content(
                        node_id,
                        out,
                        preserve_whitespace,
                        depth,
                        suppressed_style_element,
                        computed,
                    );
                    out.pop();
                    r
                } else if safe_external_href(href) {
                    out.push_annotation(EXTERNAL_LINK_TAG, href.to_owned());
                    let r = self.append_link_content(
                        node_id,
                        out,
                        preserve_whitespace,
                        depth,
                        suppressed_style_element,
                        computed,
                    );
                    out.pop();
                    r
                } else {
                    self.append_link_content(
                        node_id,
                        out,
                        preserve_whitespace,
                        depth,
                        suppressed_style_element,
                        computed,
                    )
                }
            }
            _ => {
                let gen_before = self.generated(node_id, false);
                self.append_generated_run(out, gen_before.as_ref());
                self.append_children_inline(
                    node_id,
                    out,
                    preserve_whitespace,
                    depth,
                    suppressed_style_element,
                    computed,
                )?;
                let gen_after = self.generated(node_id, true);
                self.append_generated_run(out, gen_after.as_ref());
                Ok(())
            }
        };

        if scoped_style.is_some() {
            out.pop();
        }
        body_res
    }

    fn append_link_content(
        &mut self,
        node_id: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        suppressed_style_element: Option<usize>,
        computed: Option<Arc<ComputedStyle>>,
    ) -> Result<(), MapError> {
        let gen_before = self.generated(node_id, false);
        self.append_generated_run(out, gen_before.as_ref());
        self.append_children_inline(
            node_id,
            out,
            preserve_whitespace,
            depth,
            suppressed_style_element,
            computed,
        )?;
        let gen_after = self.generated(node_id, true);
        self.append_generated_run(out, gen_after.as_ref());
        Ok(())
    }

    fn append_inline_text_box(
        &mut self,
        element: usize,
        own: &Arc<ComputedStyle>,
        builder: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        parent_computed: Option<&Arc<ComputedStyle>>,
    ) -> Result<bool, MapError> {
        if self.css.is_none() {
            return Ok(false);
        }
        let Some(width) = own.width_em else {
            return Ok(false);
        };
        if own.display.as_deref() != Some("inline-block")
            || !width.is_finite()
            || width <= 0.0
            || own.super_script
            || own.sub_script
            || own.float_side.is_some()
            || own.padding_specified
            || own.border_specified
            || own.height_em.is_some()
            || own.max_width_em.is_some()
            || own.max_width_frac.is_some()
            || own
                .background_color_argb
                .is_some_and(|c| ((c as u32) >> 24) != 0)
            || own.margin_top_em != 0.0
            || own.margin_bottom_em != 0.0
            || own.margin_start_em != 0.0
            || own.margin_end_em != 0.0
            || own.margin_start_frac != 0.0
            || own.margin_end_frac != 0.0
            || own.margin_inline_start_em != 0.0
            || own.margin_inline_end_em != 0.0
            || own.margin_inline_start_frac != 0.0
            || own.margin_inline_end_frac != 0.0
        {
            return Ok(false);
        }
        if !self.has_tag(element, TAG_SIMPLE_INLINE_TEXT_BOX) {
            return Ok(false);
        }
        let mut raw = String::new();
        let mut child = self.doc.nodes[element].first_child;
        while let Some(c) = child {
            match &self.doc.nodes[c].kind {
                Kind::Text(s) | Kind::Cdata(s) => raw.push_str(s),
                _ => return Ok(false),
            }
            child = self.doc.nodes[c].next_sibling;
        }
        if self.css.as_mut().is_some_and(|c| {
            c.generated(element, false).is_some() || c.generated(element, true).is_some()
        }) {
            return Ok(false);
        }
        if raw.contains('\n') || !(1..=2).contains(&raw.chars().count()) {
            return Ok(false);
        }

        let mut container: Option<usize> = None;
        let mut ancestor = self.doc.parent_element(element);
        while let Some(anc) = ancestor {
            if self.has_tag(anc, TAG_INLINE_TEXT_BLOCK) || self.is_css_flow_block(anc) {
                container = Some(anc);
                break;
            }
            ancestor = self.doc.parent_element(anc);
        }
        let Some(container) = container.or_else(|| self.doc.parent_element(element)) else {
            return Ok(false);
        };
        let Some(container_computed) = self.css_computed(container) else {
            return Ok(false);
        };
        let width_em = width / container_computed.font_size_em;
        if !width_em.is_finite() || !(0.05..=16.0).contains(&width_em) {
            return Ok(false);
        }

        let mut content = HtmlInlineBuilder::new();
        let style = self.inline_style_for(element, Some(own), parent_computed);
        if let Some(s) = style.clone() {
            content.push_style(s);
        }
        self.append_children_inline(
            element,
            &mut content,
            preserve_whitespace,
            depth,
            None,
            Some(Arc::clone(own)),
        )?;
        if style.is_some() {
            content.pop();
        }
        let text = content.build();
        if text.text.is_empty() || text.text.chars().count() > 2 {
            return Ok(false);
        }
        let align = own
            .text_align
            .as_deref()
            .filter(|a| matches!(*a, "start" | "end" | "left" | "right" | "center"))
            .unwrap_or("start");
        let id = format!(
            "inline-box:{}:{}",
            self.options.publisher_box_id_prefix, builder.utf16_len
        );
        let spec = InlineTextBoxSpec {
            id: id.clone(),
            width_em,
            align,
            source_length: utf16_len(&text.text) as i32,
        };
        builder.push_annotation(INLINE_TEXT_BOX_TAG, spec.encode());
        builder.inline_annotated(&id, text);
        builder.pop();
        Ok(true)
    }

    fn append_ruby(
        &mut self,
        ruby: usize,
        out: &mut HtmlInlineBuilder,
        preserve_whitespace: bool,
        depth: usize,
        suppressed_style_element: Option<usize>,
        ruby_computed: Option<Arc<ComputedStyle>>,
    ) -> Result<(), MapError> {
        let parent_style = ruby_computed.or_else(|| self.css_computed(ruby));
        let mut base = HtmlInlineBuilder::new();
        let mut child = self.doc.nodes[ruby].first_child;
        while let Some(c) = child {
            let next = self.doc.nodes[c].next_sibling;
            if self.doc.is_element(c) && self.doc.normal_name(c) == "rp" {
                child = next;
                continue;
            }
            if !self.doc.is_element(c) || self.doc.normal_name(c) != "rt" {
                self.append_inline(
                    c,
                    &mut base,
                    preserve_whitespace,
                    depth + 1,
                    suppressed_style_element,
                    parent_style.clone(),
                )?;
                child = next;
                continue;
            }

            let child_computed = self.css_computed(c);
            if child_computed.as_deref().is_some_and(|comp| comp.hidden) {
                let built_base = base.build();
                out.append_annotated(&built_base, false);
                base = HtmlInlineBuilder::new();
                child = next;
                continue;
            }

            let base_text = trimmed_annotated(base.build());
            let mut reading = HtmlInlineBuilder::new();
            let style = self
                .inline_style_for(c, child_computed.as_ref(), parent_style.as_ref())
                .or_else(|| {
                    self.css.is_none().then(|| FrbSpanStyle {
                        size: FrbTextUnit::Em(0.5),
                        ..FrbSpanStyle::default()
                    })
                });
            if let Some(s) = style.clone() {
                reading.push_style(s);
            }
            self.append_children_inline(
                c,
                &mut reading,
                preserve_whitespace,
                depth + 1,
                suppressed_style_element,
                child_computed,
            )?;
            if style.is_some() {
                reading.pop();
            }
            let reading_text = trimmed_annotated(reading.build());
            let base_len = utf16_len(&base_text.text) as i32;
            let mark = if !base_text.text.contains('\n')
                && !has_string_annotation_in(&base_text, INLINE_IMAGE_TAG, 0, base_len)
            {
                InlineRuby::from(format!("ruby:{}", out.utf16_len), base_len, &reading_text)
            } else {
                None
            };
            match mark {
                None => {
                    out.append_annotated(&base_text, false);
                    if !reading_text.text.is_empty() {
                        out.push_style(rt_span_style());
                        out.append_annotated(&reading_text, false);
                        out.pop();
                    }
                }
                Some(mark) => {
                    let fallback = concat_ruby_fallback(&base_text, &reading_text);
                    out.push_annotation(INLINE_RUBY_TAG, mark.encode());
                    out.inline_annotated(&mark.id, fallback);
                    out.pop();
                }
            }
            base = HtmlInlineBuilder::new();
            child = next;
        }
        let trailing = base.build();
        out.append_annotated(&trailing, false);
        Ok(())
    }

    fn append_inline_math(&mut self, node_id: usize, out: &mut HtmlInlineBuilder) {
        let mut math_builder = HtmlInlineBuilder::new();
        let seeded = match out.last_character {
            None => false,
            Some(' ' | '\n') if out.last_was_space => false,
            Some(ch) if out.last_was_space => {
                let mut buf = [0u8; 4];
                math_builder.preformatted_text(ch.encode_utf8(&mut buf));
                true
            }
            Some(_) => {
                math_builder.text("\u{FFFF}");
                true
            }
        };
        mathml::append_math(self, node_id, &mut math_builder, true);
        let built = math_builder.build();
        let built_len = utf16_len(&built.text) as i32;
        if seeded && built_len == 1 {
            for range in &built.ranges {
                if compose_intersect(1, 1, range.start, range.end) {
                    if let FrbTextAnnotation::String(ref item) = range.annotation {
                        out.push_annotation(&range.tag, item.clone());
                        out.pop();
                    }
                }
            }
            return;
        }
        let result = if seeded {
            sub_sequence_annotated(&built, 1, built_len)
        } else {
            built
        };
        out.append_annotated(&result, true);
    }

    // -------------------------------------------------------------- Inline Styles

    fn inline_style_for(
        &mut self,
        element: usize,
        own_computed: Option<&Arc<ComputedStyle>>,
        parent_computed: Option<&Arc<ComputedStyle>>,
    ) -> Option<FrbSpanStyle> {
        if self.css.is_none() {
            let tag = self.doc.normal_name(element);
            let mut merged = tag_style_for(&tag);
            merged = merge_span_styles(merged, self.legacy_inline_style(element));
            merged = merge_span_styles(merged, self.inline_language_style(element));
            return merged;
        }
        let Some(parent) = self.doc.parent_element(element) else {
            let tag = self.doc.normal_name(element);
            return tag_style_for(&tag);
        };
        let own = own_computed
            .cloned()
            .or_else(|| self.css_computed(element))?;
        let base = parent_computed
            .cloned()
            .or_else(|| self.css_computed(parent))?;

        let is_monospace = self.has_tag(element, TAG_SEMANTIC_MONOSPACE);
        let bg_argb = own
            .background_color_argb
            .filter(|&c| ((c as u32) >> 24) != 0);

        if Arc::ptr_eq(&own, &base) {
            if !own.super_script && !own.sub_script && !is_monospace && bg_argb.is_none() {
                let locales = self.inline_language_locale_list(element)?;
                return Some(FrbSpanStyle {
                    locales: Some(locales),
                    ..FrbSpanStyle::default()
                });
            }
            let baseline = if own.super_script {
                Some(0.5)
            } else if own.sub_script {
                Some(-0.5)
            } else {
                None
            };
            let size = if baseline.is_some() {
                FrbTextUnit::Em(0.75)
            } else {
                FrbTextUnit::Unspecified
            };
            return Some(FrbSpanStyle {
                size,
                family: is_monospace.then(|| "mono".to_owned()),
                baseline,
                locales: self.inline_language_locale_list(element),
                background: bg_argb.map_or(COMPOSE_COLOR_UNSPECIFIED, color_argb_to_compose_ulong),
                ..FrbSpanStyle::default()
            });
        }

        let mut features: Option<String> = None;
        let mut italic: Option<bool> = None;
        let mut weight: Option<i32> = None;
        let mut size = FrbTextUnit::Unspecified;
        let mut baseline: Option<f32> = None;
        let mut need_underline = false;
        let mut need_strike = false;
        let mut family: Option<String> = None;
        let mut color = COMPOSE_COLOR_UNSPECIFIED;
        let mut background = COMPOSE_COLOR_UNSPECIFIED;
        let mut any = false;

        if own.font_feature_settings != base.font_feature_settings {
            features = Some(own.font_feature_settings.clone().unwrap_or_default());
            any = true;
        }
        if own.italic.is_some() && own.italic != base.italic {
            italic = own.italic;
            any = true;
        }
        if own.font_weight.is_some() && own.font_weight != base.font_weight {
            weight = own.font_weight;
            any = true;
        }
        let size_ratio = own.font_size_em / base.font_size_em;
        if !(0.99..=1.01).contains(&size_ratio) {
            size = FrbTextUnit::Em(size_ratio.clamp(0.5, 2.0));
            any = true;
        }
        if own.super_script {
            baseline = Some(0.5);
            if size.is_unspecified() {
                size = FrbTextUnit::Em(0.75);
            }
            any = true;
        } else if own.sub_script {
            baseline = Some(-0.5);
            if size.is_unspecified() {
                size = FrbTextUnit::Em(0.75);
            }
            any = true;
        }
        if own.underline && !base.underline {
            need_underline = true;
            any = true;
        }
        if own.strike && !base.strike {
            need_strike = true;
            any = true;
        }
        if is_monospace {
            family = Some("mono".to_owned());
            any = true;
        }
        if own.foreground_color_argb != base.foreground_color_argb {
            if let Some(fg) = own.foreground_color_argb {
                color = color_argb_to_compose_ulong(fg);
                any = true;
            }
        }
        if let Some(bg) = bg_argb {
            background = color_argb_to_compose_ulong(bg);
            any = true;
        }
        let locales = self.inline_language_locale_list(element);
        if !any && locales.is_none() {
            return None;
        }
        let decoration = match (need_underline, need_strike) {
            (true, true) => Some(TEXT_DECORATION_UNDERLINE | TEXT_DECORATION_LINE_THROUGH),
            (true, false) => Some(TEXT_DECORATION_UNDERLINE),
            (false, true) => Some(TEXT_DECORATION_LINE_THROUGH),
            (false, false) => None,
        };
        Some(FrbSpanStyle {
            color,
            size,
            weight,
            italic,
            family,
            features,
            baseline,
            locales,
            background,
            decoration,
            ..FrbSpanStyle::default()
        })
    }

    // -------------------------------------------------------------- Legacy Attributes

    fn attribute_align(&self, element: usize) -> Option<FrbBlockAlign> {
        if (self.doc.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL) == 0 {
            return None;
        }
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if self.has_tag(node, TAG_ROOT) {
                break;
            }
            if self.doc.normal_name(node) == "center" {
                return Some(FrbBlockAlign::Center);
            }
            if self.has_attr(node, ATTR_HAS_PRESENTATIONAL) {
                let align = self.doc.attr_or_empty(node, "align");
                if align.eq_ignore_ascii_case("center") {
                    return Some(FrbBlockAlign::Center);
                } else if align.eq_ignore_ascii_case("right") {
                    return Some(FrbBlockAlign::Right);
                } else if align.eq_ignore_ascii_case("left") {
                    return Some(FrbBlockAlign::Left);
                } else if align.eq_ignore_ascii_case("justify") {
                    return Some(FrbBlockAlign::Justify);
                }
            }
            cursor = self.doc.parent_element(node);
        }
        None
    }

    fn legacy_inline_style(&self, element: usize) -> Option<FrbSpanStyle> {
        if (self.doc.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL) == 0
            || !self.has_attr(element, ATTR_HAS_PRESENTATIONAL)
        {
            return None;
        }
        let color_argb = {
            let raw = self.doc.attr_or_empty(element, "color");
            if kotlin_is_blank(raw) {
                None
            } else {
                css_color::parse_color(raw).map(|c| c as i32)
            }
        };
        let bg_argb = {
            let raw = self.doc.attr_or_empty(element, "bgcolor");
            if kotlin_is_blank(raw) {
                None
            } else {
                css_color::parse_color(raw)
                    .filter(|&c| (c >> 24) != 0)
                    .map(|c| c as i32)
            }
        };
        if color_argb.is_none() && bg_argb.is_none() {
            return None;
        }
        Some(FrbSpanStyle {
            color: color_argb.map_or(COMPOSE_COLOR_UNSPECIFIED, color_argb_to_compose_ulong),
            background: bg_argb.map_or(COMPOSE_COLOR_UNSPECIFIED, color_argb_to_compose_ulong),
            ..FrbSpanStyle::default()
        })
    }

    fn legacy_foreground(&self, element: usize) -> Option<i32> {
        if (self.doc.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL) == 0 {
            return None;
        }
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if self.has_attr(node, ATTR_HAS_PRESENTATIONAL) {
                let raw = non_blank_or(self.doc.attr_or_empty(node, "color"), || {
                    if self.doc.normal_name(node) == "body" {
                        self.doc.attr_or_empty(node, "text")
                    } else {
                        ""
                    }
                });
                if !kotlin_is_blank(raw) {
                    if let Some(argb) = css_color::parse_color(raw) {
                        return Some(argb as i32);
                    }
                }
            }
            cursor = self.doc.parent_element(node);
        }
        None
    }

    fn legacy_visual_background(
        &self,
        element: usize,
        stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        if (self.doc.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL) == 0 {
            return None;
        }
        let mut layers: Vec<i32> = Vec::with_capacity(3);
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if Some(node) == stop_exclusive {
                break;
            }
            if self.has_attr(node, ATTR_HAS_PRESENTATIONAL) {
                let raw = self.doc.attr_or_empty(node, "bgcolor");
                if !kotlin_is_blank(raw) {
                    if let Some(argb) = css_color::parse_color(raw) {
                        layers.push(argb as i32);
                    }
                }
            }
            cursor = self.doc.parent_element(node);
        }
        let mut result = 0_i32;
        for &layer in layers.iter().rev() {
            result = composite_argb(layer, result);
        }
        (((result as u32) >> 24) != 0).then_some(result)
    }

    // -------------------------------------------------------------- Language & Bidi

    fn inline_language_locale_list(&self, element: usize) -> Option<Vec<String>> {
        if !self.has_attr(element, ATTR_HAS_LANG) {
            return None;
        }
        let own = self.language_for(element)?;
        let inherited = self
            .doc
            .parent_element(element)
            .and_then(|p| self.language_for(p));
        if Some(own.as_str()) == inherited.as_deref() {
            return None;
        }
        let locales: Vec<String> = own
            .split(',')
            .map(kotlin_trim)
            .filter(|s| !s.is_empty())
            .map(canonical_bcp47_for_compose)
            .collect();
        (!locales.is_empty()).then_some(locales)
    }

    fn inline_language_style(&self, element: usize) -> Option<FrbSpanStyle> {
        let locales = self.inline_language_locale_list(element)?;
        Some(FrbSpanStyle {
            locales: Some(locales),
            ..FrbSpanStyle::default()
        })
    }

    fn language_for(&self, element: usize) -> Option<String> {
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if !self.has_attr(node, ATTR_ANC_HAS_LANG) {
                return None;
            }
            if self.has_attr(node, ATTR_HAS_LANG) {
                let raw = kotlin_trim(non_blank_or(self.doc.attr_or_empty(node, "lang"), || {
                    self.doc.attr_or_empty(node, "xml:lang")
                }));
                if !raw.is_empty() {
                    return Some(normalize_language_tag(raw).unwrap_or_else(|| raw.to_lowercase()));
                }
            }
            cursor = self.doc.parent_element(node);
        }
        None
    }

    fn attribute_direction(&self, element: usize) -> Option<FrbBookTextDirection> {
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if !self.has_attr(node, ATTR_ANC_HAS_DIR) {
                return None;
            }
            if self.has_attr(node, ATTR_HAS_DIR) {
                let dir = self.doc.attr_or_empty(node, "dir");
                if dir.eq_ignore_ascii_case("ltr") {
                    return Some(FrbBookTextDirection::Ltr);
                } else if dir.eq_ignore_ascii_case("rtl") {
                    return Some(FrbBookTextDirection::Rtl);
                } else if dir.eq_ignore_ascii_case("auto") {
                    return Some(FrbBookTextDirection::Auto);
                }
            }
            cursor = self.doc.parent_element(node);
        }
        None
    }

    fn inline_bidi_mode(
        &mut self,
        element: usize,
        computed: Option<&ComputedStyle>,
    ) -> Option<&'static str> {
        let computed_bidi = computed.and_then(|c| c.unicode_bidi.as_deref());
        if computed_bidi == Some("normal") {
            return None;
        }
        let can_skip_dir = !self.has_attr(element, ATTR_HAS_DIR);
        let tag_name = self.doc.normal_name(element);
        if computed_bidi.is_none() && can_skip_dir && tag_name != "bdi" && tag_name != "bdo" {
            return None;
        }
        let raw_dir_lower = if can_skip_dir {
            String::new()
        } else {
            let attr = self.doc.attr_or_empty(element, "dir");
            if attr.is_empty() {
                String::new()
            } else {
                kotlin_trim(attr).to_lowercase()
            }
        };
        let unicode_bidi = match tag_name.as_ref() {
            "bdi" => computed_bidi.unwrap_or("isolate"),
            "bdo" => computed_bidi.unwrap_or("bidi-override"),
            _ => computed_bidi.unwrap_or(if !raw_dir_lower.is_empty() {
                "isolate"
            } else {
                "normal"
            }),
        };
        if unicode_bidi == "normal" {
            return None;
        }
        if unicode_bidi == "plaintext" {
            return Some("PLAINTEXT");
        }
        if !matches!(
            unicode_bidi,
            "isolate" | "embed" | "bidi-override" | "isolate-override"
        ) {
            return None;
        }
        let computed_dir = computed.and_then(|c| c.direction.as_deref());
        let parent_dir: Option<String> =
            if tag_name == "bdi" && !matches!(raw_dir_lower.as_str(), "ltr" | "rtl" | "auto") {
                self.doc
                    .parent_element(element)
                    .and_then(|p| self.css_computed(p))
                    .and_then(|c| c.direction.clone())
            } else {
                None
            };
        let direction: &str = if matches!(raw_dir_lower.as_str(), "ltr" | "rtl" | "auto") {
            computed_dir.unwrap_or(raw_dir_lower.as_str())
        } else if tag_name == "bdi" {
            computed_dir
                .filter(|&d| Some(d) != parent_dir.as_deref())
                .unwrap_or("auto")
        } else {
            computed_dir.unwrap_or(match self.attribute_direction(element) {
                Some(FrbBookTextDirection::Ltr) => "ltr",
                Some(FrbBookTextDirection::Rtl) => "rtl",
                Some(FrbBookTextDirection::Auto) | None => "auto",
            })
        };

        match unicode_bidi {
            "isolate" => Some(match direction {
                "ltr" => "ISOLATE_LTR",
                "rtl" => "ISOLATE_RTL",
                _ => "ISOLATE_AUTO",
            }),
            "embed" => Some(match direction {
                "rtl" => "EMBED_RTL",
                "ltr" => "EMBED_LTR",
                _ => "ISOLATE_AUTO",
            }),
            "bidi-override" => Some(match direction {
                "rtl" => "OVERRIDE_RTL",
                _ => "OVERRIDE_LTR",
            }),
            "isolate-override" => Some(match direction {
                "rtl" => "ISOLATE_OVERRIDE_RTL",
                _ => "ISOLATE_OVERRIDE_LTR",
            }),
            _ => None,
        }
    }

    // -------------------------------------------------------------- Notes & Quotes

    fn is_note_reference(&mut self, link: usize, key: &str) -> bool {
        if !key.contains('#') {
            return false;
        }
        if self.has_attr(link, ATTR_EPUB_NOTEREF) || self.has_attr(link, ATTR_ROLE_DOC_NOTEREF) {
            return true;
        }
        if self.has_attr(link, ATTR_TYPE_IS_NOTE) {
            return true;
        }
        if self.options.fb2_epub_conversion && self.is_fb2_epub_promoted_note(link, key) {
            return true;
        }
        if self.callbacks.is_promoted_note(link, key) {
            return true;
        }
        if self.has_attr(link, ATTR_HAS_NOTE_HINT) {
            return true;
        }
        let file_part = key.split('#').next().unwrap_or("");
        let file_hint = file_part.rsplit('/').next().unwrap_or(file_part);
        if has_note_hint(&[Some(file_hint)]) {
            return true;
        }
        let has_sup = self.has_tag(link, TAG_SUP)
            || self.has_attr(link, ATTR_ANC_HAS_SUP)
            || self.has_subtree(link, SUBTREE_HAS_SUP);
        if !has_sup {
            return false;
        }
        let text = self.doc.jsoup_text(link);
        let marker = kotlin_trim(&text).trim_matches(|c| matches!(c, '[' | ']' | '(' | ')'));
        !marker.is_empty()
            && marker
                .chars()
                .all(|c| is_java_digit(c) || matches!(c, '*' | '†' | '‡'))
    }

    fn is_fb2_epub_promoted_note(&self, link: usize, key: &str) -> bool {
        let Some((path_part, frag)) = key.split_once('#') else {
            return false;
        };
        if frag.is_empty() {
            return false;
        }
        let basename = path_part.rsplit('/').next().unwrap_or(path_part);
        if !matches_fb2_epub_note_file(basename) {
            return false;
        }
        let text = self.doc.jsoup_text(link);
        matches_fb2_epub_note_marker(kotlin_trim(&text))
    }

    fn quote_marks_for(&self, element: usize) -> (&'static str, &'static str) {
        let mut q_ancestors = 0_usize;
        let mut anc = self.doc.parent_element(element);
        while let Some(a) = anc {
            if self.doc.normal_name(a) == "q" {
                q_ancestors += 1;
            }
            anc = self.doc.parent_element(a);
        }
        let nested = q_ancestors % 2 == 1;
        let mut language: Option<String> = None;
        let mut cursor = Some(element);
        while let Some(node) = cursor {
            if !self.has_attr(node, ATTR_ANC_HAS_LANG) {
                break;
            }
            if self.has_attr(node, ATTR_HAS_LANG) {
                let raw = non_blank_or(self.doc.attr_or_empty(node, "lang"), || {
                    self.doc.attr_or_empty(node, "xml:lang")
                });
                let primary = raw.split('-').next().unwrap_or("").to_lowercase();
                if !primary.is_empty() {
                    language = Some(primary);
                    break;
                }
            }
            cursor = self.doc.parent_element(node);
        }
        match language.as_deref() {
            Some("ru" | "uk" | "be" | "fr") => {
                if nested {
                    ("„", "“")
                } else {
                    ("«", "»")
                }
            }
            Some("de") => {
                if nested {
                    ("‚", "‘")
                } else {
                    ("„", "“")
                }
            }
            _ => {
                if nested {
                    ("‘", "’")
                } else {
                    ("“", "”")
                }
            }
        }
    }

    // -------------------------------------------------------------- Text Transform

    /// Returns `Some(transformed)` if `text` changed, or `None` if unchanged.
    fn transform_text_cow(
        &self,
        text: &str,
        transform: &str,
        element: Option<usize>,
        previous_character: Option<char>,
    ) -> Option<String> {
        if transform == "none" || text.is_empty() {
            return None;
        }
        let lang = element.and_then(|el| self.language_for(el));
        let primary = lang
            .as_deref()
            .and_then(|l| l.split('-').next())
            .unwrap_or("");
        let turkic = matches!(primary, "tr" | "az");
        let transformed = match transform {
            "uppercase" => locale_uppercase(text, turkic),
            "lowercase" => locale_lowercase(text, turkic),
            "capitalize" => locale_capitalize(text, previous_character, turkic),
            _ => return None,
        };
        if transformed == text {
            None
        } else {
            Some(transformed)
        }
    }
}

impl mathml::MathContext for HtmlMapper<'_, '_, '_, '_> {
    #[inline]
    fn doc(&self) -> &Document {
        self.doc
    }

    #[inline]
    fn max_depth(&self) -> usize {
        self.options.max_structure_depth
    }

    #[inline]
    fn resolve_internal_link(&mut self, href: &str) -> Option<String> {
        self.callbacks.resolve_link(href)
    }

    #[inline]
    fn on_internal_link(&mut self, key: String) {
        self.link_targets.insert(key);
    }

    #[inline]
    fn resolve_author_style(&mut self, node_id: usize) -> Option<FrbSpanStyle> {
        self.inline_style_for(node_id, None, None)
    }
}

// --------------------------------------------------------------------------- HtmlInlineBuilder

struct HtmlInlineBuilder {
    text: String,
    utf16_len: i32,
    ranges: Vec<FrbTextRange>,
    open_stack: Vec<usize>,
    last_was_space: bool,
    has_visible_content: bool,
    last_character: Option<char>,
    image_refs: Vec<String>,
}

impl HtmlInlineBuilder {
    fn new() -> Self {
        Self {
            text: String::new(),
            utf16_len: 0,
            ranges: Vec::new(),
            open_stack: Vec::new(),
            last_was_space: true,
            has_visible_content: false,
            last_character: None,
            image_refs: Vec::new(),
        }
    }

    #[inline]
    fn is_blank(&self) -> bool {
        !self.has_visible_content
    }

    #[inline]
    fn push_str_tracked(&mut self, s: &str) {
        self.text.push_str(s);
        self.utf16_len += utf16_len(s) as i32;
    }

    #[inline]
    fn push_char_tracked(&mut self, ch: char) {
        self.text.push(ch);
        self.utf16_len += ch.len_utf16() as i32;
    }

    fn text(&mut self, raw: &str) {
        if raw.is_empty() {
            return;
        }
        self.collapsed_append(raw, None);
    }

    fn preindexed_text(&mut self, raw: &str, text_flags: u32) {
        if raw.is_empty() {
            return;
        }
        if (text_flags & TEXT_HTML_BLANK) != 0 {
            if !self.last_was_space {
                self.push_char_tracked(' ');
                self.last_character = Some(' ');
                self.last_was_space = true;
            }
            return;
        }
        if (text_flags & TEXT_ALREADY_COLLAPSED_ASCII) != 0 {
            let start = if self.last_was_space && (text_flags & TEXT_STARTS_HTML_WS) != 0 {
                1
            } else {
                0
            };
            if start >= raw.len() {
                return;
            }
            let slice = &raw[start..];
            self.push_str_tracked(slice);
            self.last_character = slice.chars().next_back();
            self.last_was_space = (text_flags & TEXT_ENDS_HTML_WS) != 0;
            if !self.has_visible_content && (text_flags & TEXT_HAS_KOTLIN_NON_WS) != 0 {
                self.has_visible_content = true;
            }
            return;
        }
        self.collapsed_append(raw, Some(text_flags));
    }

    fn collapsed_append(&mut self, raw: &str, text_flags: Option<u32>) {
        let mut in_space = self.last_was_space;
        let mut last_char: Option<char> = None;
        let mut appended_any = false;
        let bytes = raw.as_bytes();
        let len = bytes.len();
        let mut i = 0;

        while i < len {
            if is_html_ws_byte(bytes[i]) {
                while i < len && is_html_ws_byte(bytes[i]) {
                    i += 1;
                }
                if !in_space {
                    self.push_char_tracked(' ');
                    last_char = Some(' ');
                    in_space = true;
                    appended_any = true;
                }
            } else {
                let run_start = i;
                i += 1;
                while i < len && !is_html_ws_byte(bytes[i]) {
                    i += 1;
                }
                let run = &raw[run_start..i];
                self.push_str_tracked(run);
                last_char = run.chars().next_back();
                in_space = false;
                appended_any = true;
            }
        }
        if !appended_any {
            return;
        }
        self.last_character = last_char;
        self.last_was_space = in_space;
        if !self.has_visible_content {
            let visible = match text_flags {
                Some(flags) => (flags & TEXT_HAS_KOTLIN_NON_WS) != 0,
                None => !kotlin_is_blank(raw),
            };
            if visible {
                self.has_visible_content = true;
            }
        }
    }

    fn preformatted_text(&mut self, raw: &str) {
        if raw.is_empty() {
            return;
        }
        let normalized;
        let chunk = if raw.contains('\r') {
            normalized = normalize_cr(raw);
            normalized.as_str()
        } else {
            raw
        };
        self.push_str_tracked(chunk);
        if let Some(tail) = chunk.chars().next_back() {
            self.last_character = Some(tail);
            self.last_was_space = kotlin_is_whitespace(tail);
        }
        if !self.has_visible_content && chunk.chars().any(|c| !kotlin_is_whitespace(c)) {
            self.has_visible_content = true;
        }
    }

    fn preindexed_preformatted_text(&mut self, raw: &str, text_flags: u32) {
        if raw.is_empty() {
            return;
        }
        let normalized;
        let chunk = if (text_flags & TEXT_HAS_CR) == 0 {
            raw
        } else {
            normalized = normalize_cr(raw);
            normalized.as_str()
        };
        self.push_str_tracked(chunk);
        if let Some(tail) = chunk.chars().next_back() {
            self.last_character = Some(tail);
            self.last_was_space =
                (text_flags & TEXT_ENDS_HTML_WS) != 0 || kotlin_is_whitespace(tail);
        }
        if !self.has_visible_content && (text_flags & TEXT_HAS_KOTLIN_NON_WS) != 0 {
            self.has_visible_content = true;
        }
    }

    fn line_break(&mut self) {
        self.push_char_tracked('\n');
        self.last_character = Some('\n');
        self.last_was_space = true;
    }

    fn word_break_opportunity(&mut self) {
        self.push_char_tracked('\u{200B}');
        self.last_character = Some('\u{200B}');
        self.last_was_space = false;
    }

    fn inline_image(&mut self, ref_str: String, alt_text: Option<&str>) {
        let alt = alt_text.map(kotlin_trim).filter(|s| !s.is_empty());
        let start = self.utf16_len;
        let end = start + 1;
        if let Some(alt_val) = alt {
            self.ranges.push(FrbTextRange::string(
                start,
                end,
                INLINE_IMAGE_ALT_TAG,
                alt_val,
            ));
        }
        self.ranges.push(FrbTextRange::string(
            start,
            end,
            INLINE_IMAGE_TAG,
            ref_str.clone(),
        ));
        self.ranges.push(FrbTextRange::string(
            start,
            end,
            INLINE_CONTENT_TAG,
            ref_str.clone(),
        ));
        self.push_char_tracked(INLINE_IMAGE_CHAR);
        self.last_character = Some(INLINE_IMAGE_CHAR);
        self.image_refs.push(ref_str);
        self.last_was_space = false;
    }

    fn push_style(&mut self, style: FrbSpanStyle) {
        let idx = self.ranges.len();
        self.ranges
            .push(FrbTextRange::span(self.utf16_len, self.utf16_len, style));
        self.open_stack.push(idx);
    }

    fn push_annotation(&mut self, tag: &str, value: String) {
        let idx = self.ranges.len();
        self.ranges.push(FrbTextRange::string(
            self.utf16_len,
            self.utf16_len,
            tag,
            value,
        ));
        self.open_stack.push(idx);
    }

    fn pop(&mut self) {
        if let Some(idx) = self.open_stack.pop() {
            self.ranges[idx].end = self.utf16_len;
        }
    }

    fn append_annotated(&mut self, text: &FrbAnnotatedString, keep_empty_annotations: bool) {
        if text.text.is_empty() {
            if keep_empty_annotations && !text.ranges.is_empty() {
                let base = self.utf16_len;
                for r in &text.ranges {
                    let mut rebased = r.clone();
                    rebased.start += base;
                    rebased.end += base;
                    self.ranges.push(rebased);
                }
            }
            return;
        }
        let base = self.utf16_len;
        let mut images: Vec<(i32, i32, &str)> = Vec::new();
        for r in &text.ranges {
            if r.tag == INLINE_IMAGE_TAG {
                if let FrbTextAnnotation::String(ref item) = r.annotation {
                    images.push((r.start, r.end, item.as_str()));
                }
            }
        }
        self.push_str_tracked(&text.text);
        for r in &text.ranges {
            let mut rebased = r.clone();
            rebased.start += base;
            rebased.end += base;
            self.ranges.push(rebased);
        }
        let tail = text.text.chars().next_back();
        self.last_character = tail;
        self.last_was_space = matches!(tail, Some(' ' | '\n'));
        for &(_, _, item) in &images {
            self.image_refs.push(item.to_owned());
        }
        if !self.has_visible_content {
            let mut offset = 0_i32;
            for ch in text.text.chars() {
                let is_img_placeholder = ch == INLINE_IMAGE_CHAR
                    && images
                        .iter()
                        .any(|&(start, end, _)| offset >= start && offset < end);
                if !kotlin_is_whitespace(ch) && !is_img_placeholder {
                    self.has_visible_content = true;
                    break;
                }
                offset += ch.len_utf16() as i32;
            }
        }
    }

    fn inline_annotated(&mut self, id: &str, mut text: FrbAnnotatedString) {
        if text.text.is_empty() {
            return;
        }
        let len = utf16_len(&text.text) as i32;
        text.ranges
            .push(FrbTextRange::string(0, len, INLINE_CONTENT_TAG, id));
        self.append_annotated(&text, false);
    }

    fn build(mut self) -> FrbAnnotatedString {
        while let Some(idx) = self.open_stack.pop() {
            self.ranges[idx].end = self.utf16_len;
        }
        FrbAnnotatedString {
            text: self.text,
            ranges: self.ranges,
        }
    }
}

// --------------------------------------------------------------------------- Pure Helpers

#[inline]
fn is_html_ws_byte(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | b'\r' | 0x0c)
}

#[inline]
fn is_html_ws_char(ch: char) -> bool {
    matches!(ch, ' ' | '\t' | '\n' | '\r' | '\u{000c}')
}

#[inline]
fn kotlin_trim(s: &str) -> &str {
    s.trim_matches(kotlin_is_whitespace)
}

#[inline]
fn kotlin_is_blank(s: &str) -> bool {
    s.chars().all(kotlin_is_whitespace)
}

#[inline]
fn non_blank_or<'a>(primary: &'a str, fallback: impl FnOnce() -> &'a str) -> &'a str {
    if kotlin_is_blank(primary) {
        fallback()
    } else {
        primary
    }
}

#[inline]
fn utf16_len(s: &str) -> usize {
    if s.is_ascii() {
        s.len()
    } else {
        s.chars().map(char::len_utf16).sum()
    }
}

fn normalize_cr(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '\r' {
            if chars.peek() == Some(&'\n') {
                chars.next();
            }
            out.push('\n');
        } else {
            out.push(ch);
        }
    }
    out
}

fn collapse_preindexed_leaf_text(raw: &str, text_flags: u32, trim_trailing: bool) -> String {
    if (text_flags & TEXT_ALREADY_COLLAPSED_ASCII) != 0 {
        let start = if (text_flags & TEXT_STARTS_HTML_WS) != 0 {
            1
        } else {
            0
        };
        let end = if trim_trailing && (text_flags & TEXT_ENDS_HTML_WS) != 0 {
            raw.len().saturating_sub(1)
        } else {
            raw.len()
        };
        if start >= end {
            return String::new();
        }
        return raw[start..end].to_owned();
    }
    collapse_leaf_text(raw, trim_trailing)
}

fn collapse_leaf_text(raw: &str, trim_trailing: bool) -> String {
    let bytes = raw.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    while i < len && is_html_ws_byte(bytes[i]) {
        i += 1;
    }
    if i >= len {
        return String::new();
    }
    let mut end = len;
    if trim_trailing {
        while end > i && is_html_ws_byte(bytes[end - 1]) {
            end -= 1;
        }
    }
    let first_run_start = i;
    while i < end && !is_html_ws_byte(bytes[i]) {
        i += 1;
    }
    if i >= end {
        return raw[first_run_start..end].to_owned();
    }
    let mut out = String::with_capacity(end - first_run_start);
    out.push_str(&raw[first_run_start..i]);
    let mut in_space = false;
    while i < end {
        if is_html_ws_byte(bytes[i]) {
            while i < end && is_html_ws_byte(bytes[i]) {
                i += 1;
            }
            if !in_space {
                out.push(' ');
                in_space = true;
            }
        } else {
            let run_start = i;
            i += 1;
            while i < end && !is_html_ws_byte(bytes[i]) {
                i += 1;
            }
            out.push_str(&raw[run_start..i]);
            in_space = false;
        }
    }
    out
}

/// Trims leading and trailing HTML whitespace from an `FrbAnnotatedString`,
/// matching Compose `AnnotatedString.subSequence(start, end)` range clamping
/// and intersection semantics exactly.
fn trimmed_annotated(input: FrbAnnotatedString) -> FrbAnnotatedString {
    let bytes = input.text.as_bytes();
    let mut byte_start = 0;
    while byte_start < bytes.len() && is_html_ws_byte(bytes[byte_start]) {
        byte_start += 1;
    }
    let mut byte_end = bytes.len();
    while byte_end > byte_start && is_html_ws_byte(bytes[byte_end - 1]) {
        byte_end -= 1;
    }
    if byte_start == 0 && byte_end == bytes.len() {
        return input;
    }
    let u16_start = byte_start as i32; // leading HTML ws is strictly 1-byte ASCII
    let trimmed_text = input.text[byte_start..byte_end].to_owned();
    let u16_end = u16_start + utf16_len(&trimmed_text) as i32;

    let mut ranges = Vec::with_capacity(input.ranges.len());
    for mut range in input.ranges {
        if compose_intersect(u16_start, u16_end, range.start, range.end) {
            range.start = range.start.clamp(u16_start, u16_end) - u16_start;
            range.end = range.end.clamp(u16_start, u16_end) - u16_start;
            ranges.push(range);
        }
    }
    FrbAnnotatedString {
        text: trimmed_text,
        ranges,
    }
}

#[inline]
fn compose_contains(l_start: i32, l_end: i32, r_start: i32, r_end: i32) -> bool {
    (l_start <= r_start && r_end <= l_end)
        && (l_end != r_end || (r_start == r_end) == (l_start == l_end))
}

#[inline]
fn compose_intersect(l_start: i32, l_end: i32, r_start: i32, r_end: i32) -> bool {
    l_start.max(r_start) < l_end.min(r_end)
        || compose_contains(l_start, l_end, r_start, r_end)
        || compose_contains(r_start, r_end, l_start, l_end)
}

fn utf16_offset_to_byte(s: &str, target_u16: usize) -> usize {
    if target_u16 == 0 {
        return 0;
    }
    let mut acc = 0usize;
    for (byte_idx, ch) in s.char_indices() {
        if acc >= target_u16 {
            return byte_idx;
        }
        acc += ch.len_utf16();
    }
    s.len()
}

fn sub_sequence_annotated(
    input: &FrbAnnotatedString,
    u16_start: i32,
    u16_end: i32,
) -> FrbAnnotatedString {
    let total_u16 = utf16_len(&input.text) as i32;
    let start = u16_start.clamp(0, total_u16);
    let end = u16_end.clamp(start, total_u16);
    if start == 0 && end == total_u16 {
        return input.clone();
    }
    let byte_start = utf16_offset_to_byte(&input.text, start as usize);
    let byte_end = utf16_offset_to_byte(&input.text, end as usize);
    let sliced_text = input.text[byte_start..byte_end].to_owned();
    let mut ranges = Vec::with_capacity(input.ranges.len());
    for mut range in input.ranges.iter().cloned() {
        if compose_intersect(start, end, range.start, range.end) {
            range.start = range.start.clamp(start, end) - start;
            range.end = range.end.clamp(start, end) - start;
            ranges.push(range);
        }
    }
    FrbAnnotatedString {
        text: sliced_text,
        ranges,
    }
}

fn has_string_annotation_in(text: &FrbAnnotatedString, tag: &str, start: i32, end: i32) -> bool {
    text.ranges.iter().any(|r| {
        r.tag == tag
            && matches!(r.annotation, FrbTextAnnotation::String(_))
            && compose_intersect(start, end, r.start, r.end)
    })
}

fn rt_span_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.6),
        baseline: Some(0.35),
        ..FrbSpanStyle::default()
    }
}

fn concat_ruby_fallback(
    base: &FrbAnnotatedString,
    reading: &FrbAnnotatedString,
) -> FrbAnnotatedString {
    let base_u16 = utf16_len(&base.text) as i32;
    let reading_u16 = utf16_len(&reading.text) as i32;
    let mut text = String::with_capacity(base.text.len() + reading.text.len());
    text.push_str(&base.text);
    text.push_str(&reading.text);
    let mut ranges = Vec::with_capacity(base.ranges.len() + reading.ranges.len() + 1);
    ranges.extend(base.ranges.iter().cloned());
    ranges.push(FrbTextRange::span(
        base_u16,
        base_u16 + reading_u16,
        rt_span_style(),
    ));
    for r in &reading.ranges {
        let mut rebased = r.clone();
        rebased.start += base_u16;
        rebased.end += base_u16;
        ranges.push(rebased);
    }
    FrbAnnotatedString { text, ranges }
}

fn merged_table_cell_block(table: Option<&FrbBlockStyle>, cell: &FrbTableCell) -> FrbBlockStyle {
    let mut base = table.cloned().unwrap_or_default();
    let own = cell.block.as_deref();
    if let Some(a) = cell.align {
        base.align = Some(a);
    }
    if let Some(o) = own {
        if o.italic.is_some() {
            base.italic = o.italic;
        }
        if o.bold.is_some() {
            base.bold = o.bold;
        }
        if o.font_weight.is_some() {
            base.font_weight = o.font_weight;
        }
        if o.font_scale.is_some() {
            base.font_scale = o.font_scale;
        }
        if o.font_family.is_some() {
            base.font_family = o.font_family.clone();
        }
        if o.font_feature_settings.is_some() {
            base.font_feature_settings = o.font_feature_settings.clone();
        }
        if o.font_typography.is_some() {
            base.font_typography = o.font_typography.clone();
        }
        if o.line_height_mult.is_some() {
            base.line_height_mult = o.line_height_mult;
        }
        if o.language.is_some() {
            base.language = o.language.clone();
        }
        if o.direction.is_some() {
            base.direction = o.direction;
        }
        if o.foreground_color_argb.is_some() {
            base.foreground_color_argb = o.foreground_color_argb;
        }
        if o.background_color_argb.is_some() {
            base.background_color_argb = o.background_color_argb;
        }
    }
    base.first_line_indent = Some(false);
    base
}

fn same_responsive_inline_presentation(a: &ComputedStyle, b: &ComputedStyle) -> bool {
    a.hidden == b.hidden
        && a.display == b.display
        && a.italic == b.italic
        && a.font_weight == b.font_weight
        && a.font_size_em == b.font_size_em
        && a.font_family_name == b.font_family_name
        && a.font_feature_settings == b.font_feature_settings
        && font_typography_from_computed(a) == font_typography_from_computed(b)
        && a.monospace == b.monospace
        && a.underline == b.underline
        && a.strike == b.strike
        && a.super_script == b.super_script
        && a.sub_script == b.sub_script
        && a.foreground_color_argb == b.foreground_color_argb
        && a.background_color_argb == b.background_color_argb
        && a.direction == b.direction
        && a.unicode_bidi == b.unicode_bidi
        && a.visibility_hidden == b.visibility_hidden
        && a.text_transform == b.text_transform
        && a.width_frac == b.width_frac
        && a.width_em == b.width_em
        && a.height_em == b.height_em
        && a.max_width_frac == b.max_width_frac
        && a.max_width_em == b.max_width_em
        && a.max_height_em == b.max_height_em
        && a.max_height_viewport_frac == b.max_height_viewport_frac
        && a.image_invert == b.image_invert
}

// --------------------------------------------------------------------------- Inline JSON Codecs

/// Wire-compatible equivalent of Kotlin `InlineTextBoxSpec` (`encodeDefaults = false`).
struct InlineTextBoxSpec<'a> {
    id: String,
    width_em: f32,
    align: &'a str,
    source_length: i32,
}

impl InlineTextBoxSpec<'_> {
    fn encode(&self) -> String {
        let mut out = String::from("{");
        let mut comma = false;
        if !self.id.is_empty() {
            out.push_str("\"id\":");
            push_json_string_escaped(&mut out, &self.id);
            comma = true;
        }
        if self.width_em != 1.0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"widthEm\":");
            out.push_str(&format_kotlin_float(self.width_em));
            comma = true;
        }
        if self.align != "start" {
            if comma {
                out.push(',');
            }
            out.push_str("\"align\":");
            push_json_string_escaped(&mut out, self.align);
            comma = true;
        }
        if self.source_length != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"sourceLength\":");
            out.push_str(&self.source_length.to_string());
        }
        out.push('}');
        out
    }
}

/// Wire-compatible equivalent of Kotlin `RubySpan` (`encodeDefaults = false`).
#[derive(Clone, Debug, PartialEq)]
struct RubySpan {
    start: i32,
    end: i32,
    font_size: Option<f32>,
    font_size_is_em: bool,
    italic: Option<bool>,
    weight: Option<i32>,
    family: Option<&'static str>,
    font_features: Option<String>,
    foreground_argb: Option<i32>,
    background_argb: Option<i32>,
    baseline_shift: Option<f32>,
    underline: bool,
    strike: bool,
    language: Option<String>,
}

impl RubySpan {
    fn from_frb_span(style: &FrbSpanStyle, start: i32, end: i32) -> Self {
        let (font_size, font_size_is_em) = match style.size {
            FrbTextUnit::Unspecified => (None, false),
            FrbTextUnit::Em(v) => (Some(v), true),
            FrbTextUnit::Sp(v) => (Some(v), false),
        };
        let family = match style.family.as_deref() {
            Some("serif") => Some("serif"),
            Some("sans") => Some("sans-serif"),
            Some("mono") => Some("monospace"),
            Some("cursive") => Some("cursive"),
            _ => None,
        };
        let foreground_argb = (style.color != COMPOSE_COLOR_UNSPECIFIED)
            .then_some(((style.color >> 32) as u32) as i32);
        let background_argb = (style.background != COMPOSE_COLOR_UNSPECIFIED)
            .then_some(((style.background >> 32) as u32) as i32);
        let dec = style.decoration.unwrap_or(0);
        Self {
            start,
            end,
            font_size,
            font_size_is_em,
            italic: style.italic,
            weight: style.weight,
            family,
            font_features: style.features.clone(),
            foreground_argb,
            background_argb,
            baseline_shift: style.baseline,
            underline: (dec & TEXT_DECORATION_UNDERLINE) != 0,
            strike: (dec & TEXT_DECORATION_LINE_THROUGH) != 0,
            language: style.locales.as_ref().and_then(|v| v.first()).cloned(),
        }
    }

    fn write_json(&self, out: &mut String) {
        out.push('{');
        let mut comma = false;
        if self.start != 0 {
            out.push_str("\"start\":");
            out.push_str(&self.start.to_string());
            comma = true;
        }
        if self.end != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"end\":");
            out.push_str(&self.end.to_string());
            comma = true;
        }
        if let Some(size) = self.font_size {
            if comma {
                out.push(',');
            }
            out.push_str("\"fontSize\":");
            out.push_str(&format_kotlin_float(size));
            comma = true;
        }
        if !self.font_size_is_em {
            if comma {
                out.push(',');
            }
            out.push_str("\"fontSizeIsEm\":false");
            comma = true;
        }
        if let Some(italic) = self.italic {
            if comma {
                out.push(',');
            }
            out.push_str("\"italic\":");
            out.push_str(if italic { "true" } else { "false" });
            comma = true;
        }
        if let Some(weight) = self.weight {
            if comma {
                out.push(',');
            }
            out.push_str("\"weight\":");
            out.push_str(&weight.to_string());
            comma = true;
        }
        if let Some(fam) = self.family {
            if comma {
                out.push(',');
            }
            out.push_str("\"family\":");
            push_json_string_escaped(out, fam);
            comma = true;
        }
        if let Some(features) = &self.font_features {
            if comma {
                out.push(',');
            }
            out.push_str("\"fontFeatures\":");
            push_json_string_escaped(out, features);
            comma = true;
        }
        if let Some(fg) = self.foreground_argb {
            if comma {
                out.push(',');
            }
            out.push_str("\"foregroundArgb\":");
            out.push_str(&fg.to_string());
            comma = true;
        }
        if let Some(bg) = self.background_argb {
            if comma {
                out.push(',');
            }
            out.push_str("\"backgroundArgb\":");
            out.push_str(&bg.to_string());
            comma = true;
        }
        if let Some(bs) = self.baseline_shift {
            if comma {
                out.push(',');
            }
            out.push_str("\"baselineShift\":");
            out.push_str(&format_kotlin_float(bs));
            comma = true;
        }
        if self.underline {
            if comma {
                out.push(',');
            }
            out.push_str("\"underline\":true");
            comma = true;
        }
        if self.strike {
            if comma {
                out.push(',');
            }
            out.push_str("\"strike\":true");
            comma = true;
        }
        if let Some(lang) = &self.language {
            if comma {
                out.push(',');
            }
            out.push_str("\"language\":");
            push_json_string_escaped(out, lang);
        }
        out.push('}');
    }
}

struct RubyAnnotation {
    tag: String,
    value: String,
    start: i32,
    end: i32,
}

impl RubyAnnotation {
    fn write_json(&self, out: &mut String) {
        out.push('{');
        let mut comma = false;
        if !self.tag.is_empty() {
            out.push_str("\"tag\":");
            push_json_string_escaped(out, &self.tag);
            comma = true;
        }
        if !self.value.is_empty() {
            if comma {
                out.push(',');
            }
            out.push_str("\"value\":");
            push_json_string_escaped(out, &self.value);
            comma = true;
        }
        if self.start != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"start\":");
            out.push_str(&self.start.to_string());
            comma = true;
        }
        if self.end != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"end\":");
            out.push_str(&self.end.to_string());
        }
        out.push('}');
    }
}

struct InlineRuby {
    id: String,
    base_length: i32,
    source_length: i32,
    reading: String,
    spans: Vec<RubySpan>,
    annotations: Vec<RubyAnnotation>,
}

impl InlineRuby {
    fn from(id: String, base_length: i32, reading: &FrbAnnotatedString) -> Option<Self> {
        let reading_u16 = utf16_len(&reading.text);
        let span_count = reading
            .ranges
            .iter()
            .filter(|r| matches!(r.annotation, FrbTextAnnotation::Span(_)))
            .count();
        if reading_u16 == 0
            || reading_u16 > MAX_RUBY_READING_LENGTH
            || span_count > MAX_RUBY_SPANS
            || reading.text.contains('\n')
            || base_length <= 0
            || has_string_annotation_in(reading, INLINE_IMAGE_TAG, 0, reading_u16 as i32)
        {
            return None;
        }
        let spans: Vec<RubySpan> = reading
            .ranges
            .iter()
            .filter_map(|r| match &r.annotation {
                FrbTextAnnotation::Span(s) => Some(RubySpan::from_frb_span(s, r.start, r.end)),
                _ => None,
            })
            .collect();
        let reading_len_i32 = reading_u16 as i32;
        let annotations: Vec<RubyAnnotation> = reading
            .ranges
            .iter()
            .filter_map(|r| {
                if !compose_intersect(0, reading_len_i32, r.start, r.end) {
                    return None;
                }
                if !matches!(
                    r.tag.as_str(),
                    INLINE_FONT_FAMILY_TAG
                        | INLINE_FONT_TYPOGRAPHY_TAG
                        | HIDDEN_TEXT_TAG
                        | BIDI_TAG
                ) {
                    return None;
                }
                if let FrbTextAnnotation::String(ref val) = r.annotation {
                    Some(RubyAnnotation {
                        tag: r.tag.clone(),
                        value: val.clone(),
                        start: r.start,
                        end: r.end,
                    })
                } else {
                    None
                }
            })
            .take(MAX_RUBY_SPANS)
            .collect();
        Some(Self {
            id,
            base_length,
            source_length: base_length + reading_len_i32,
            reading: reading.text.clone(),
            spans,
            annotations,
        })
    }

    fn encode(&self) -> String {
        let mut out = String::from("{");
        let mut comma = false;
        if !self.id.is_empty() {
            out.push_str("\"id\":");
            push_json_string_escaped(&mut out, &self.id);
            comma = true;
        }
        if self.base_length != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"baseLength\":");
            out.push_str(&self.base_length.to_string());
            comma = true;
        }
        if self.source_length != 0 {
            if comma {
                out.push(',');
            }
            out.push_str("\"sourceLength\":");
            out.push_str(&self.source_length.to_string());
            comma = true;
        }
        if !self.reading.is_empty() {
            if comma {
                out.push(',');
            }
            out.push_str("\"reading\":");
            push_json_string_escaped(&mut out, &self.reading);
            comma = true;
        }
        if !self.spans.is_empty() {
            if comma {
                out.push(',');
            }
            out.push_str("\"spans\":[");
            for (i, span) in self.spans.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                span.write_json(&mut out);
            }
            out.push(']');
            comma = true;
        }
        if !self.annotations.is_empty() {
            if comma {
                out.push(',');
            }
            out.push_str("\"annotations\":[");
            for (i, ann) in self.annotations.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                ann.write_json(&mut out);
            }
            out.push(']');
        }
        out.push('}');
        out
    }
}

/// Escapes a JSON string matching `kotlinx.serialization.json.internal.StringOps.kt`.
fn push_json_string_escaped(out: &mut String, value: &str) {
    out.push('"');
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{0008}' => out.push_str("\\b"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\u{000C}' => out.push_str("\\f"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => {
                let code = c as u32;
                let hi = (code >> 4) as u8;
                let lo = (code & 0xF) as u8;
                let hex = |n: u8| {
                    if n < 10 {
                        (b'0' + n) as char
                    } else {
                        (b'a' + (n - 10)) as char
                    }
                };
                out.push_str("\\u00");
                out.push(hex(hi));
                out.push(hex(lo));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

// --------------------------------------------------------------------------- SVG & Base64 Helpers

fn image_mime(file_name: &str, bytes: &[u8]) -> &'static str {
    let lower = file_name.to_lowercase();
    let prefix = String::from_utf8_lossy(&bytes[..bytes.len().min(256)]);
    if lower.ends_with(".svg") || contains_ascii_ci(&prefix, b"<svg") {
        "image/svg+xml"
    } else if lower.ends_with(".gif") || bytes.starts_with(b"GIF") {
        "image/gif"
    } else if lower.ends_with(".webp") {
        "image/webp"
    } else if lower.ends_with(".jpg") || lower.ends_with(".jpeg") {
        "image/jpeg"
    } else {
        "image/png"
    }
}

fn contains_ascii_ci(haystack: &str, needle: &[u8]) -> bool {
    if needle.is_empty() || haystack.len() < needle.len() {
        return false;
    }
    haystack
        .as_bytes()
        .windows(needle.len())
        .any(|w| w.eq_ignore_ascii_case(needle))
}

fn base64_encode(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    let mut chunks = bytes.chunks_exact(3);
    for chunk in &mut chunks {
        let n = ((chunk[0] as u32) << 16) | ((chunk[1] as u32) << 8) | (chunk[2] as u32);
        out.push(TABLE[((n >> 18) & 0x3F) as usize] as char);
        out.push(TABLE[((n >> 12) & 0x3F) as usize] as char);
        out.push(TABLE[((n >> 6) & 0x3F) as usize] as char);
        out.push(TABLE[(n & 0x3F) as usize] as char);
    }
    let rem = chunks.remainder();
    if rem.len() == 1 {
        let n = (rem[0] as u32) << 16;
        out.push(TABLE[((n >> 18) & 0x3F) as usize] as char);
        out.push(TABLE[((n >> 12) & 0x3F) as usize] as char);
        out.push('=');
        out.push('=');
    } else if rem.len() == 2 {
        let n = ((rem[0] as u32) << 16) | ((rem[1] as u32) << 8);
        out.push(TABLE[((n >> 18) & 0x3F) as usize] as char);
        out.push(TABLE[((n >> 12) & 0x3F) as usize] as char);
        out.push(TABLE[((n >> 6) & 0x3F) as usize] as char);
        out.push('=');
    }
    out
}

fn push_xml_escaped_attr(out: &mut String, value: &str) {
    for ch in value.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '"' => out.push_str("&quot;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '\u{00A0}' => out.push_str("&#xa0;"),
            c => out.push(c),
        }
    }
}

fn push_xml_escaped_text(out: &mut String, value: &str) {
    for ch in value.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '\u{00A0}' => out.push_str("&#xa0;"),
            c => out.push(c),
        }
    }
}

fn collapse_inline_whitespace(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_ws = false;
    for ch in raw.chars() {
        if is_java_regex_ws(ch) || ch == '\u{00A0}' {
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

fn tag_style_for(tag: &str) -> Option<FrbSpanStyle> {
    Some(match tag {
        "i" | "em" | "dfn" | "var" => FrbSpanStyle {
            italic: Some(true),
            ..FrbSpanStyle::default()
        },
        "b" | "strong" => FrbSpanStyle {
            weight: Some(700),
            ..FrbSpanStyle::default()
        },
        "u" | "ins" => FrbSpanStyle {
            decoration: Some(TEXT_DECORATION_UNDERLINE),
            ..FrbSpanStyle::default()
        },
        "s" | "strike" | "del" => FrbSpanStyle {
            decoration: Some(TEXT_DECORATION_LINE_THROUGH),
            ..FrbSpanStyle::default()
        },
        "code" | "kbd" | "samp" | "tt" => FrbSpanStyle {
            family: Some("mono".to_owned()),
            ..FrbSpanStyle::default()
        },
        "sup" => FrbSpanStyle {
            baseline: Some(0.5),
            size: FrbTextUnit::Em(0.75),
            ..FrbSpanStyle::default()
        },
        "sub" => FrbSpanStyle {
            baseline: Some(-0.5),
            size: FrbTextUnit::Em(0.75),
            ..FrbSpanStyle::default()
        },
        "small" => FrbSpanStyle {
            size: FrbTextUnit::Em(0.85),
            ..FrbSpanStyle::default()
        },
        _ => return None,
    })
}

fn merge_span_styles(
    first: Option<FrbSpanStyle>,
    second: Option<FrbSpanStyle>,
) -> Option<FrbSpanStyle> {
    match (first, second) {
        (None, s) => s,
        (f, None) => f,
        (Some(f), Some(s)) => Some(FrbSpanStyle {
            color: if s.color != COMPOSE_COLOR_UNSPECIFIED {
                s.color
            } else {
                f.color
            },
            size: if !s.size.is_unspecified() {
                s.size
            } else {
                f.size
            },
            weight: s.weight.or(f.weight),
            italic: s.italic.or(f.italic),
            synthesis: s.synthesis.or(f.synthesis),
            family: s.family.or(f.family),
            features: s.features.or(f.features),
            spacing: if !s.spacing.is_unspecified() {
                s.spacing
            } else {
                f.spacing
            },
            baseline: s.baseline.or(f.baseline),
            scale_x: s.scale_x.or(f.scale_x),
            skew_x: s.skew_x.or(f.skew_x),
            locales: s.locales.or(f.locales),
            background: if s.background != COMPOSE_COLOR_UNSPECIFIED {
                s.background
            } else {
                f.background
            },
            decoration: s.decoration.or(f.decoration),
            shadow: s.shadow.or(f.shadow),
        }),
    }
}

fn generated_span_style(run: &GeneratedRun) -> Option<FrbSpanStyle> {
    let mut style = FrbSpanStyle::default();
    let mut any = false;
    if let Some(features) = &run.font_feature_settings {
        style.features = Some(features.clone());
        any = true;
    }
    if let Some(italic) = run.italic {
        style.italic = Some(italic);
        any = true;
    }
    if run.font_weight.is_some() || run.bold.is_some() {
        style.weight =
            Some(
                run.font_weight
                    .unwrap_or(if run.bold == Some(true) { 700 } else { 400 }),
            );
        any = true;
    }
    if !(0.99..=1.01).contains(&run.scale) {
        style.size = FrbTextUnit::Em(run.scale);
        any = true;
    }
    if let Some(fg) = run.foreground_color_argb {
        style.color = color_argb_to_compose_ulong(fg);
        any = true;
    }
    if let Some(bg) = run
        .background_color_argb
        .filter(|&c| ((c as u32) >> 24) != 0)
    {
        style.background = color_argb_to_compose_ulong(bg);
        any = true;
    }
    any.then_some(style)
}

fn composite_argb(foreground: i32, background: i32) -> i32 {
    let fg = foreground as u32;
    let bg = background as u32;
    let fa = (fg >> 24) & 0xFF;
    if fa == 0 {
        return background;
    }
    if fa == 0xFF {
        return foreground;
    }
    let ba = (bg >> 24) & 0xFF;
    let out_a = fa + (ba * (255 - fa) + 127) / 255;
    if out_a == 0 {
        return 0;
    }
    let channel = |shift: u32| -> u32 {
        let fc = (fg >> shift) & 0xFF;
        let bc = (bg >> shift) & 0xFF;
        let premul = fc * fa + (bc * ba * (255 - fa) + 127) / 255;
        (premul + out_a / 2) / out_a
    };
    ((out_a << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0)) as i32
}

fn safe_external_href(href: &str) -> bool {
    let Some((scheme, _)) = href.split_once(':') else {
        return false;
    };
    scheme.eq_ignore_ascii_case("http")
        || scheme.eq_ignore_ascii_case("https")
        || scheme.eq_ignore_ascii_case("mailto")
        || scheme.eq_ignore_ascii_case("tel")
}

/// Matches `LanguageTag.normalize`: `"ru-RU"` -> `"ru-ru"`, `"ua"` -> `"uk"`.
pub(crate) fn normalize_language_tag(raw: &str) -> Option<String> {
    let tag = kotlin_trim(raw).to_lowercase().replace('_', "-");
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
    for sub in parts {
        if !(2..=8).contains(&sub.len())
            || !sub
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        {
            return false;
        }
    }
    true
}

/// Canonicalizes a BCP-47 tag to match Compose `Locale(tag).toLanguageTag()`.
fn canonical_bcp47_for_compose(tag: &str) -> String {
    let parts: Vec<&str> = tag.split('-').collect();
    if parts.is_empty() {
        return String::new();
    }
    let mut out = String::with_capacity(tag.len());
    let primary = parts[0].to_ascii_lowercase();
    let mapped_primary = match primary.as_str() {
        "iw" => "he",
        "ji" => "yi",
        "in" => "id",
        other => other,
    };
    out.push_str(mapped_primary);
    let mut seen_script = false;
    let mut seen_region = false;
    let mut in_extension = false;
    for &part in &parts[1..] {
        out.push('-');
        if part.len() == 1 {
            in_extension = true;
            out.push_str(&part.to_ascii_lowercase());
            continue;
        }
        if !in_extension
            && !seen_script
            && !seen_region
            && part.len() == 4
            && part.bytes().all(|b| b.is_ascii_alphabetic())
        {
            seen_script = true;
            let mut bytes = part.bytes();
            if let Some(first) = bytes.next() {
                out.push(first.to_ascii_uppercase() as char);
            }
            for b in bytes {
                out.push(b.to_ascii_lowercase() as char);
            }
        } else if !in_extension
            && !seen_region
            && part.len() == 2
            && part.bytes().all(|b| b.is_ascii_alphabetic())
        {
            seen_region = true;
            out.push_str(&part.to_ascii_uppercase());
        } else {
            out.push_str(&part.to_ascii_lowercase());
        }
    }
    out
}

fn is_java_digit(ch: char) -> bool {
    if ch.is_ascii_digit() {
        return true;
    }
    if ch.is_ascii() {
        return false;
    }
    // Unicode Nd decimal digit blocks matching Java Character.isDigit.
    const DECIMAL_ZEROS: &[u32] = &[
        0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6,
        0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0,
        0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0,
        0xAA50, 0xABF0, 0xFF10,
    ];
    let cp = ch as u32;
    DECIMAL_ZEROS
        .iter()
        .any(|&zero| cp >= zero && cp <= zero + 9)
}

fn matches_fb2_epub_note_marker(s: &str) -> bool {
    let Some(inner) = s.strip_prefix('[').and_then(|rest| rest.strip_suffix(']')) else {
        return false;
    };
    if inner.is_empty() {
        return false;
    }
    inner.bytes().all(|b| b.is_ascii_digit()) || inner.chars().all(|c| matches!(c, '*' | '†' | '‡'))
}

fn matches_fb2_epub_note_file(s: &str) -> bool {
    if s.len() < 11 {
        return false;
    }
    let Some(prefix) = s.get(..4) else {
        return false;
    };
    if !prefix.eq_ignore_ascii_case("ch2-") {
        return false;
    }
    let rest = &s[4..];
    let Some(dot) = rest.rfind('.') else {
        return false;
    };
    let digits = &rest[..dot];
    let ext = &rest[dot..];
    !digits.is_empty()
        && digits.bytes().all(|b| b.is_ascii_digit())
        && ext.eq_ignore_ascii_case(".xhtml")
}

// --------------------------------------------------------------------------- ListMarkers

fn list_marker(style_type: &str, index: i32) -> String {
    match style_type {
        "none" => String::new(),
        "disc" => "• ".to_owned(),
        "circle" => "◦ ".to_owned(),
        "square" => "▪ ".to_owned(),
        "decimal" => format!("{index}. "),
        "lower-alpha" | "lower-latin" => format!("{}. ", to_alpha(index)),
        "upper-alpha" | "upper-latin" => format!("{}. ", to_alpha(index).to_ascii_uppercase()),
        "lower-roman" => format!("{}. ", to_roman(index)),
        "upper-roman" => format!("{}. ", to_roman(index).to_ascii_uppercase()),
        _ => format!("{index}. "),
    }
}

fn to_alpha(n: i32) -> String {
    if n < 1 {
        return n.to_string();
    }
    let mut value = n;
    let mut buf = Vec::new();
    while value > 0 {
        value -= 1;
        buf.push(b'a' + (value % 26) as u8);
        value /= 26;
    }
    buf.reverse();
    String::from_utf8(buf).unwrap_or_default()
}

fn to_roman(n: i32) -> String {
    if !(1..=3999).contains(&n) {
        return n.to_string();
    }
    const VALUES: &[i32] = &[1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1];
    const SYMBOLS: &[&str] = &[
        "m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i",
    ];
    let mut value = n;
    let mut out = String::new();
    for (&v, &sym) in VALUES.iter().zip(SYMBOLS.iter()) {
        while value >= v {
            out.push_str(sym);
            value -= v;
        }
    }
    out
}

fn list_css_type_for(type_attr: Option<&str>) -> Option<&'static str> {
    match type_attr? {
        "1" => Some("decimal"),
        "a" => Some("lower-alpha"),
        "A" => Some("upper-alpha"),
        "i" => Some("lower-roman"),
        "I" => Some("upper-roman"),
        _ => None,
    }
}

fn list_applicable_css_type(css_type: Option<&str>, ordered: bool) -> Option<&str> {
    let t = css_type?;
    let is_bullet = matches!(t, "disc" | "circle" | "square");
    if t == "none" || is_bullet != ordered {
        Some(t)
    } else {
        None
    }
}

fn list_default_type(ordered: bool, depth: usize) -> &'static str {
    if ordered {
        "decimal"
    } else {
        match (depth.saturating_sub(1)) % 3 {
            0 => "disc",
            1 => "circle",
            _ => "square",
        }
    }
}

// --------------------------------------------------------------------------- Locale Case Transform

fn locale_uppercase(text: &str, turkic: bool) -> String {
    if !turkic {
        return text.to_uppercase();
    }
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        if ch == 'i' {
            out.push('İ');
        } else {
            for u in ch.to_uppercase() {
                out.push(u);
            }
        }
    }
    out
}

fn locale_lowercase(text: &str, turkic: bool) -> String {
    if !turkic {
        return text.to_lowercase();
    }
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            'I' => out.push('ı'),
            'İ' => out.push('i'),
            _ => {
                for l in ch.to_lowercase() {
                    out.push(l);
                }
            }
        }
    }
    out
}

fn locale_capitalize(text: &str, previous_character: Option<char>, turkic: bool) -> String {
    let mut out = String::with_capacity(text.len());
    let mut prev = previous_character;
    for ch in text.chars() {
        let prev_is_word = prev.is_some_and(|p| p.is_alphanumeric() || matches!(p, '’' | '\''));
        if ch.is_alphabetic() && !prev_is_word {
            if turkic && ch == 'i' {
                out.push('İ');
            } else {
                for u in ch.to_uppercase() {
                    out.push(u);
                }
            }
        } else {
            out.push(ch);
        }
        prev = Some(ch);
    }
    out
}

// --------------------------------------------------------------------------- Unit Tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chapter_markup;
    use crate::css_engine::{SharedStyleCache, SheetInput};
    use crate::frb_model::{FrbFontTypography, FrbTextAnnotation};

    struct TestCallbacks {
        allow_images: bool,
    }

    impl ResourceCallbacks for TestCallbacks {
        fn resolve_image(&mut self, src: &str) -> Option<String> {
            if self.allow_images && !src.is_empty() {
                Some(format!("/img/{src}"))
            } else {
                None
            }
        }

        fn resolve_link(&mut self, href: &str) -> Option<String> {
            if href.starts_with('#') || href.ends_with(".xhtml") || href.contains(".xhtml#") {
                Some(href.to_owned())
            } else {
                None
            }
        }
    }

    struct MockCss {
        styles: HashMap<usize, Arc<ComputedStyle>>,
        boxes: HashMap<usize, FrbPublisherBoxStyle>,
        before: HashMap<usize, GeneratedRun>,
        after: HashMap<usize, GeneratedRun>,
        has_display: bool,
    }

    impl MockCss {
        fn new() -> Self {
            Self {
                styles: HashMap::new(),
                boxes: HashMap::new(),
                before: HashMap::new(),
                after: HashMap::new(),
                has_display: false,
            }
        }
    }

    impl CssProvider for MockCss {
        fn has_display_rules(&self) -> bool {
            self.has_display
        }

        fn has_page_break_before_rules(&self) -> bool {
            false
        }

        fn computed(&mut self, node_id: usize) -> Arc<ComputedStyle> {
            self.styles.get(&node_id).cloned().unwrap_or_else(|| {
                Arc::new(ComputedStyle {
                    font_size_em: 1.0,
                    flex_direction: "row".to_owned(),
                    flex_wrap: "nowrap".to_owned(),
                    ..ComputedStyle::default()
                })
            })
        }

        fn publisher_box_style(&mut self, node_id: usize) -> Option<FrbPublisherBoxStyle> {
            self.boxes.get(&node_id).cloned()
        }

        fn first_letter(&mut self, _node_id: usize) -> Option<FrbFirstLetterStyle> {
            None
        }

        fn floated_text_initial(
            &mut self,
            _node_id: usize,
            _paragraph_id: usize,
            _source_text_len: i32,
        ) -> Option<FrbFirstLetterStyle> {
            None
        }

        fn generated(&mut self, node_id: usize, after: bool) -> Option<GeneratedRun> {
            if after {
                self.after.get(&node_id).cloned()
            } else {
                self.before.get(&node_id).cloned()
            }
        }

        fn dark_image_style(&mut self, _node_id: usize) -> DarkImageStyle {
            DarkImageStyle::default()
        }
    }

    fn map_html(html: &str, allow_images: bool) -> Result<MappedBody, MapError> {
        let doc = chapter_markup::parse(html, true).expect("parse html");
        let body = doc.preindex.body_node.expect("body");
        let mut cb = TestCallbacks { allow_images };
        let opts = MapOptions::default();
        map_document_with_provider(&doc, body, None, &mut cb, &opts)
    }

    #[test]
    fn maps_utf16_surrogate_offsets_and_inline_styles() {
        let mapped = map_html(
            "<p>🐸 <b>bold</b> and <i>שלום</i> <a href=\"#target\">link</a> <a href=\"https://example.com\">web</a></p>",
            false,
        )
        .unwrap();
        assert_eq!(mapped.elements.len(), 1);
        let FrbContentElement::Paragraph { text, style, block } = &mapped.elements[0] else {
            panic!("expected paragraph");
        };
        assert_eq!(*style, FrbParagraphStyle::Normal);
        assert!(block.is_none());
        assert_eq!(text.text, "🐸 bold and שלום link web");
        // 🐸 is 2 UTF-16 code units, space is 1 -> "bold" is [3, 7)
        assert_eq!(text.ranges[0].start, 3);
        assert_eq!(text.ranges[0].end, 7);
        assert!(matches!(
            &text.ranges[0].annotation,
            FrbTextAnnotation::Span(s) if s.weight == Some(700)
        ));
        // " and " is 5 code units -> "שלום" is [12, 16)
        assert_eq!(text.ranges[1].start, 12);
        assert_eq!(text.ranges[1].end, 16);
        assert!(matches!(
            &text.ranges[1].annotation,
            FrbTextAnnotation::Span(s) if s.italic == Some(true)
        ));
        // "link" is [17, 21)
        assert_eq!(text.ranges[2].start, 17);
        assert_eq!(text.ranges[2].end, 21);
        assert_eq!(text.ranges[2].tag, LINK_TAG);
        assert!(mapped.link_targets.contains("#target"));
        // "web" is [22, 25)
        assert_eq!(text.ranges[3].start, 22);
        assert_eq!(text.ranges[3].end, 25);
        assert_eq!(text.ranges[3].tag, EXTERNAL_LINK_TAG);
    }

    #[test]
    fn maps_headings_lists_quotes_code_and_hr() {
        let html = r#"
            <h1 id="top">  Title  </h1>
            <blockquote><p>Quoted</p></blockquote>
            <ul><li>First<ul><li>Nested</li></ul></li></ul>
            <ol start="3"><li>Third</li><li value="10">Tenth</li></ol>
            <pre><code>  line 1
    line 2
</code></pre>
            <hr/>
        "#;
        let mapped = map_html(html, false).unwrap();
        assert_eq!(mapped.anchors.get("top"), Some(&0));
        assert!(matches!(
            &mapped.elements[0],
            FrbContentElement::Heading { styled_text, level: 1, .. } if styled_text.text == "Title"
        ));
        assert!(matches!(
            &mapped.elements[1],
            FrbContentElement::Paragraph { text, style: FrbParagraphStyle::Quote, .. } if text.text == "Quoted"
        ));
        assert!(matches!(
            &mapped.elements[2],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "• First" && b.first_line_indent == Some(false) && b.indent_start_em == 0.0
        ));
        assert!(matches!(
            &mapped.elements[3],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "◦ Nested" && (b.indent_start_em - 1.2).abs() < 1e-5
        ));
        assert!(matches!(
            &mapped.elements[4],
            FrbContentElement::Paragraph { text, .. } if text.text == "3. Third"
        ));
        assert!(matches!(
            &mapped.elements[5],
            FrbContentElement::Paragraph { text, .. } if text.text == "10. Tenth"
        ));
        assert!(matches!(
            &mapped.elements[6],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "  line 1\n    line 2\n"
                && b.font_family.as_deref() == Some("monospace")
                && text.ranges.len() == 2
        ));
        assert_eq!(mapped.elements[7], FrbContentElement::Divider);
    }

    #[test]
    fn maps_tables_colspan_rowspan_caption_and_flattening() {
        let html = r#"
            <table id="tbl1" border="0">
              <caption>Sales Summary</caption>
              <thead><tr id="row1"><th>Item</th><th align="right">Qty</th></tr></thead>
              <tbody>
                <tr><td rowspan="2">Apples</td><td align="right">10</td></tr>
                <tr><td colspan="2">Total</td></tr>
              </tbody>
            </table>
            <table><tr><td>Single col A</td></tr><tr><td>Single col B</td></tr></table>
            <table><tr><td>Nested</td><td><table><tr><td>Inner</td></tr></table></td></tr></table>
        "#;
        let mapped = map_html(html, false).unwrap();
        // Table's own id registers in walk() before caption (0); row/cell ids register at the grid (1).
        assert_eq!(mapped.anchors.get("tbl1"), Some(&0));
        assert_eq!(mapped.anchors.get("row1"), Some(&1));
        assert!(matches!(
            &mapped.elements[0],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Sales Summary"
                && b.align == Some(FrbBlockAlign::Center)
                && b.italic == Some(true)
                && b.first_line_indent == Some(false)
        ));
        let FrbContentElement::Table(tbl) = &mapped.elements[1] else {
            panic!("expected table at element 1, got {:?}", mapped.elements[1]);
        };
        assert_eq!(tbl.rows.len(), 3);
        assert!(tbl.rows[0].is_header);
        assert_eq!(tbl.rows[0].cells[0].text.text, "Item");
        assert!(tbl.rows[0].cells[0].header);
        assert_eq!(tbl.rows[0].cells[0].align, Some(FrbBlockAlign::Center));
        assert!(tbl.rows[0].cells[0].publisher_border_specified); // border="0"
        assert_eq!(tbl.rows[0].cells[1].align, Some(FrbBlockAlign::Right));
        assert_eq!(tbl.rows[1].cells[0].row_span, 2);
        assert_eq!(tbl.rows[2].cells[0].col_span, 2);

        // Single-column layout table without authored geometry flattens to paragraphs
        assert!(matches!(
            &mapped.elements[2],
            FrbContentElement::Paragraph { text, .. } if text.text == "Single col A"
        ));
        assert!(matches!(
            &mapped.elements[3],
            FrbContentElement::Paragraph { text, .. } if text.text == "Single col B"
        ));

        // Nested table does not steal rows from outer table
        let FrbContentElement::Table(outer) = &mapped.elements[4] else {
            panic!("expected outer table at element 4");
        };
        assert_eq!(outer.rows.len(), 1);
        assert_eq!(outer.rows[0].cells.len(), 2);
        assert_eq!(outer.rows[0].cells[0].text.text, "Nested");
        assert_eq!(outer.rows[0].cells[1].text.text, "Inner");
    }

    #[test]
    fn maps_responsive_tables_and_rejects_incompatible_breakpoints() {
        let drama_css = r#"
            @namespace epub "http://www.idpf.org/2007/ops";
            table { margin: 1em auto; width: 100%; }
            td { padding: 0.5em; vertical-align: top; }
            td[epub|type~="z3998:persona"] { text-align: right; width: 20%; font-variant: all-small-caps; }
            @media(max-width: 400px) {
                td, td[epub|type~="z3998:persona"] { display: block; padding: 0; text-align: initial; width: 100%; }
                td[epub|type~="z3998:persona"] { margin-top: 1.5em; }
                td:first-child:not([epub|type]) + td { margin-top: 1.5em; }
            }
        "#;
        let html = r#"<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <table>
              <tr><td></td><td><em>Stage direction</em></td></tr>
              <tr><td epub:type="z3998:persona">Richard</td><td>Dialogue.</td></tr>
            </table>
        </body></html>"#;
        let compiled = CompiledStylesheets::compile(&[SheetInput::new(drama_css, "")], None);
        let doc = chapter_markup::parse(html, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut css = ChapterCss::standalone(compiled, &doc);
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let mapped =
            map_document(&doc, body, Some(&mut css), &mut cb, &MapOptions::default()).unwrap();
        let FrbContentElement::Table(tbl) = &mapped.elements[0] else {
            panic!("expected table");
        };
        assert_eq!(tbl.stack_cells_below_css_px, Some(400.0));
        assert_eq!(tbl.rows.len(), 2);
        let persona = &tbl.rows[1].cells[0];
        assert_eq!(persona.align, Some(FrbBlockAlign::Right));
        let narrow = persona.narrow_style.as_ref().expect("narrow style");
        assert_eq!(narrow.align, Some(FrbBlockAlign::Start));
        assert!(narrow.publisher_padding_specified);
        assert_eq!(
            narrow.publisher_box.as_ref().and_then(|b| b.width_frac),
            Some(1.0)
        );
        assert_eq!(
            narrow.publisher_box.as_ref().map(|b| b.margin_top_em),
            Some(1.5)
        );
        assert!(narrow
            .block
            .as_ref()
            .and_then(|b| b.font_feature_settings.as_deref())
            .is_some_and(|f| f.contains("c2sc")));

        // Partial column stacking must NOT set stack_cells_below_css_px
        let partial_css = "@media(max-width:400px) { td:first-child { display: block; } }";
        let compiled_p = CompiledStylesheets::compile(&[SheetInput::new(partial_css, "")], None);
        let doc_p =
            chapter_markup::parse("<table><tr><td>A</td><td>B</td></tr></table>", true).unwrap();
        let body_p = doc_p.preindex.body_node.unwrap();
        let mut css_p = ChapterCss::standalone(compiled_p, &doc_p);
        let mapped_p = map_document(
            &doc_p,
            body_p,
            Some(&mut css_p),
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();
        let FrbContentElement::Table(tbl_p) = &mapped_p.elements[0] else {
            panic!("expected table");
        };
        assert_eq!(tbl_p.stack_cells_below_css_px, None);
        assert!(tbl_p.rows[0].cells.iter().all(|c| c.narrow_style.is_none()));
    }

    #[test]
    fn maps_ruby_inline_annotations_and_fallbacks() {
        // Basic ruby with CSS and nested italic
        let css_text = "rt { font-size: 1em; }";
        let compiled = CompiledStylesheets::compile(&[SheetInput::new(css_text, "")], None);
        let doc = chapter_markup::parse(
            "<p>Excommunicamus <ruby>hunc<rt><i>vel</i> os</rt></ruby> furem.</p>",
            true,
        )
        .unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut css = ChapterCss::standalone(compiled, &doc);
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let mapped =
            map_document(&doc, body, Some(&mut css), &mut cb, &MapOptions::default()).unwrap();
        let FrbContentElement::Paragraph { text, .. } = &mapped.elements[0] else {
            panic!("expected paragraph");
        };
        assert_eq!(text.text, "Excommunicamus huncvel os furem.");
        let ruby_range = text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_RUBY_TAG)
            .expect("ruby annotation");
        assert_eq!(ruby_range.start, 15);
        assert_eq!(ruby_range.end, 25);
        let FrbTextAnnotation::String(ref payload) = ruby_range.annotation else {
            panic!("expected string annotation");
        };
        assert!(payload.contains(r#""id":"ruby:15""#));
        assert!(payload.contains(r#""baseLength":4"#));
        assert!(payload.contains(r#""sourceLength":10"#));
        assert!(payload.contains(r#""reading":"vel os""#));
        assert!(payload.contains(r#""italic":true"#));
        assert!(payload.contains(r#""fontSizeIsEm":false"#));
        let inline_content = text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_CONTENT_TAG)
            .expect("inlineContent annotation");
        assert_eq!(inline_content.start, 15);
        assert_eq!(inline_content.end, 25);
        assert_eq!(
            inline_content.annotation,
            FrbTextAnnotation::String("ruby:15".to_owned())
        );

        // Multiple ruby pairs with <rp> omitted and no CSS (0.5em default)
        let mapped_jp = map_html(
            "<p><ruby><rb>漢</rb><rp>(</rp><rt>かん</rt><rp>)</rp><rb>字</rb><rt>じ</rt></ruby></p>",
            false,
        )
        .unwrap();
        let FrbContentElement::Paragraph { text: jp, .. } = &mapped_jp.elements[0] else {
            panic!("expected jp paragraph");
        };
        assert_eq!(jp.text, "漢かん字じ");
        let ruby_anns: Vec<&str> = jp
            .ranges
            .iter()
            .filter_map(|r| {
                if r.tag == INLINE_RUBY_TAG {
                    if let FrbTextAnnotation::String(ref s) = r.annotation {
                        return Some(s.as_str());
                    }
                }
                None
            })
            .collect();
        assert_eq!(ruby_anns.len(), 2);
        assert!(ruby_anns[0].contains(r#""reading":"かん""#));
        assert!(ruby_anns[0].contains(r#""fontSize":0.5"#));
        assert!(!ruby_anns[0].contains("fontSizeIsEm")); // true is default, omitted!
        assert!(ruby_anns[1].contains(r#""reading":"じ""#));
    }

    #[test]
    fn maps_svg_block_and_inline_with_embedded_raster_image() {
        let tmp_dir =
            std::env::temp_dir().join(format!("frogreader-svg-mapper-test-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&tmp_dir);
        let png_path = tmp_dir.join("cover.png");
        let png_bytes: &[u8] = &[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3];
        std::fs::write(&png_path, png_bytes).unwrap();

        struct SvgTestCallbacks {
            png_path: String,
            captured_svgs: Vec<String>,
            cache: HashMap<String, String>,
        }
        impl ResourceCallbacks for SvgTestCallbacks {
            fn resolve_image(&mut self, src: &str) -> Option<String> {
                (src == "cover.png").then(|| self.png_path.clone())
            }
            fn resolve_link(&mut self, _href: &str) -> Option<String> {
                None
            }
            fn resolve_inline_svg(&mut self, markup: &str) -> Option<String> {
                if let Some(existing) = self.cache.get(markup) {
                    return Some(existing.clone());
                }
                let idx = self.captured_svgs.len();
                self.captured_svgs.push(markup.to_owned());
                let path = format!("/cache/svg_inline_{idx}.svg");
                self.cache.insert(markup.to_owned(), path.clone());
                Some(path)
            }
        }

        let xhtml = r#"<html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink"><body>
            <svg viewBox="0 0 100 100"><title>Cover Art</title><image width="100" height="100" xlink:href="cover.png"/></svg>
            <p>Icon <svg aria-label="Star" width="12" height="12"><circle r="5"/></svg> end.</p>
        </body></html>"#;
        let doc = chapter_markup::parse(xhtml, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut cb = SvgTestCallbacks {
            png_path: png_path.to_string_lossy().into_owned(),
            captured_svgs: Vec::new(),
            cache: HashMap::new(),
        };
        let mapped = map_document(&doc, body, None, &mut cb, &MapOptions::default()).unwrap();
        let _ = std::fs::remove_dir_all(&tmp_dir);

        assert_eq!(mapped.elements.len(), 2);
        let FrbContentElement::Image(cover) = &mapped.elements[0] else {
            panic!("expected block svg image");
        };
        assert_eq!(cover.path, "/cache/svg_inline_0.svg");
        assert_eq!(cover.alt_text.as_deref(), Some("Cover Art"));
        assert!(cb.captured_svgs[0].contains(r#"xmlns="http://www.w3.org/2000/svg""#));
        let expected_b64 = base64_encode(png_bytes);
        assert!(
            cb.captured_svgs[0].contains(&format!(
                "xlink:href=\"data:image/png;base64,{expected_b64}\""
            )),
            "serialized svg was: {}",
            cb.captured_svgs[0]
        );

        let FrbContentElement::Paragraph { text, .. } = &mapped.elements[1] else {
            panic!("expected paragraph with inline svg");
        };
        assert!(text.text.contains(INLINE_IMAGE_CHAR));
        assert!(text.ranges.iter().any(|r| {
            r.tag == INLINE_IMAGE_TAG
                && r.annotation == FrbTextAnnotation::String("/cache/svg_inline_1.svg".to_owned())
        }));
        assert!(text.ranges.iter().any(|r| {
            r.tag == INLINE_IMAGE_ALT_TAG
                && r.annotation == FrbTextAnnotation::String("Star".to_owned())
        }));
    }

    #[test]
    fn maps_native_and_fallback_mathml() {
        // 1. Native admitted math with fraction, sqrt, superscript, and operator spacing
        let xml = r#"<body><p>Formula: <math><mi>y</mi><mo>=</mo><mfrac><mrow><mi>x</mi><mo>−</mo><mn>3</mn><mi>y</mi></mrow><mn>6</mn></mfrac><mo>+</mo><msqrt><msup><mi>x</mi><mn>2</mn></msup></msqrt></math>.</p></body>"#;
        let doc = chapter_markup::parse(xml, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let mapped = map_document(&doc, body, None, &mut cb, &MapOptions::default()).unwrap();
        let FrbContentElement::Paragraph { text, .. } = &mapped.elements[0] else {
            panic!("expected paragraph");
        };
        assert_eq!(text.text, "Formula: y = (x − 3y)⁄6 + √x2.");
        let math_ann = text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_MATH_TAG)
            .expect("native math annotation");
        let FrbTextAnnotation::String(ref payload) = math_ann.annotation else {
            panic!("expected math payload string");
        };
        assert!(payload.contains(r#""fallback":"y = (x − 3y)⁄6 + √x2""#));
        assert!(payload.contains(r#""kind":"frac""#));
        assert!(payload.contains(r#""kind":"sqrt""#));
        assert!(payload.contains(r#""leftSpaceEm":0.18"#));
        assert!(text.ranges.iter().any(|r| {
            r.tag == INLINE_CONTENT_TAG
                && r.annotation == FrbTextAnnotation::String(format!("frogreader:math:{payload}"))
        }));

        // 2. Leading padded operator preserves space next to prose
        let lead_xml = "<body><p>A<math><mo>+</mo><mn>1</mn></math>B</p></body>";
        let lead_doc = chapter_markup::parse(lead_xml, false).unwrap();
        let lead_body = lead_doc.preindex.body_node.unwrap();
        let lead_mapped =
            map_document(&lead_doc, lead_body, None, &mut cb, &MapOptions::default()).unwrap();
        let FrbContentElement::Paragraph {
            text: lead_text, ..
        } = &lead_mapped.elements[0]
        else {
            panic!("expected lead paragraph");
        };
        assert_eq!(lead_text.text, "A + 1B");
        let lead_ann = lead_text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_MATH_TAG)
            .expect("lead math annotation");
        assert!(matches!(
            &lead_ann.annotation,
            FrbTextAnnotation::String(s) if s.contains(r#""fallback":" + 1""#)
        ));

        // 3. Root style="font-size: 150%" captures wholeStyles and fontSizeEm: 1.5
        let styled_xml = r#"<body><p><math style="font-size: 150%"><mfrac><mn>1</mn><mn>2</mn></mfrac></math></p></body>"#;
        let styled_doc = chapter_markup::parse(styled_xml, false).unwrap();
        let styled_body = styled_doc.preindex.body_node.unwrap();
        let styled_mapped = map_document(
            &styled_doc,
            styled_body,
            None,
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();
        let FrbContentElement::Paragraph {
            text: styled_text, ..
        } = &styled_mapped.elements[0]
        else {
            panic!("expected styled paragraph");
        };
        let styled_ann = styled_text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_MATH_TAG)
            .expect("styled math annotation");
        let FrbTextAnnotation::String(ref styled_payload) = styled_ann.annotation else {
            panic!("payload");
        };
        assert!(styled_payload.contains(r#""fontSizeEm":1.5"#));
        assert!(styled_payload.contains(r#""wholeStyles":[{"fontSize":1.5}]"#));

        // 4. Display math centered, no indent, anchors & links preserved, plus mtable/color fallback
        let fallback_xml = r##"<body>
            <math display="block" id="eq1">
              <mrow id="expr1" href="#target"><mi>x</mi><mo>=</mo><mn>1</mn></mrow>
            </math>
            <p><math><mfrac><mi mathcolor="#f00">x</mi><mn>2</mn></mfrac></math> <math><mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr><mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable></math></p>
        </body>"##;
        let fb_doc = chapter_markup::parse(fallback_xml, false).unwrap();
        let fb_body = fb_doc.preindex.body_node.unwrap();
        let fb_mapped =
            map_document(&fb_doc, fb_body, None, &mut cb, &MapOptions::default()).unwrap();
        assert_eq!(fb_mapped.anchors.get("eq1"), Some(&0));
        assert_eq!(fb_mapped.anchors.get("expr1"), Some(&0));
        assert!(fb_mapped.link_targets.contains("#target"));
        assert!(matches!(
            &fb_mapped.elements[0],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "x = 1"
                && b.align == Some(FrbBlockAlign::Center)
                && b.first_line_indent == Some(false)
        ));
        let FrbContentElement::Paragraph { text: fb_text, .. } = &fb_mapped.elements[1] else {
            panic!("expected fallback paragraph");
        };
        assert_eq!(fb_text.text, "x⁄2 [1, 2; 3, 4]");
        assert!(!fb_text.ranges.iter().any(|r| r.tag == INLINE_MATH_TAG));
    }

    #[test]
    fn maps_publisher_boxes_notes_and_generated_typography() {
        let doc = chapter_markup::parse(
            "<div id=\"box1\"><p id=\"p1\">Hello</p></div><aside epub:type=\"footnote\" id=\"fn1\"><p>Note body</p></aside>",
            false,
        )
        .unwrap();
        let body = doc.preindex.body_node.unwrap();
        let div_id = doc
            .nodes
            .iter()
            .position(|n| matches!(&n.kind, Kind::Element { name, .. } if name == "div"))
            .unwrap();
        let mut css = MockCss::new();
        css.boxes.insert(
            div_id,
            FrbPublisherBoxStyle {
                margin_top_em: 1.5,
                ..FrbPublisherBoxStyle::default()
            },
        );
        let mut cb = TestCallbacks { allow_images: true };
        let opts = MapOptions {
            publisher_box_id_prefix: "ch1.xhtml@0".to_owned(),
            ..MapOptions::default()
        };
        let mapped =
            map_document_with_provider(&doc, body, Some(&mut css), &mut cb, &opts).unwrap();
        assert_eq!(mapped.elements.len(), 2);
        assert_eq!(mapped.publisher_boxes.len(), 1);
        assert_eq!(mapped.publisher_boxes[0].id, "ch1.xhtml@0:publisher-box-0");
        assert_eq!(mapped.publisher_boxes[0].start_element, 0);
        assert_eq!(mapped.publisher_boxes[0].end_element_exclusive, 1);
        // With CSS present, simple leaf text carries INLINE_FONT_TYPOGRAPHY_TAG = "{}"
        let FrbContentElement::Paragraph { text, .. } = &mapped.elements[0] else {
            panic!("expected paragraph");
        };
        assert_eq!(text.ranges.len(), 1);
        assert_eq!(text.ranges[0].tag, INLINE_FONT_TYPOGRAPHY_TAG);
        assert_eq!(
            text.ranges[0].annotation,
            FrbTextAnnotation::String("{}".to_owned())
        );
        // Semantic note document captured
        let note = mapped.note_documents.get("fn1").expect("note doc");
        assert_eq!(note.elements.len(), 1);
    }

    #[test]
    fn encode_font_typography_matches_kotlin_json() {
        assert_eq!(encode_font_typography(&FrbFontTypography::default()), "{}");
        let mut axes = BTreeMap::new();
        axes.insert("wght".to_owned(), 600.0);
        axes.insert("opsz".to_owned(), 14.5);
        let typo = FrbFontTypography {
            variation_axes: Some(axes),
            stretch_percent: Some(100.0),
            optical_sizing: Some(true),
        };
        assert_eq!(
            encode_font_typography(&typo),
            r#"{"axes":{"opsz":14.5,"wght":600.0},"stretch":100.0,"optical":true}"#
        );
    }

    #[test]
    fn quote_marks_and_bidi_match_html_mapper() {
        let mapped = map_html(
            "<p lang=\"ru\"><q>Привет <q>мир</q></q> <bdi>שלום</bdi></p>",
            false,
        )
        .unwrap();
        let FrbContentElement::Paragraph { text, block, .. } = &mapped.elements[0] else {
            panic!("expected paragraph");
        };
        assert_eq!(text.text, "«Привет „мир“» שלום");
        assert_eq!(
            block.as_ref().and_then(|b| b.language.as_deref()),
            Some("ru")
        );
        let bidi = text
            .ranges
            .iter()
            .find(|r| r.tag == BIDI_TAG)
            .expect("bidi annotation");
        assert_eq!(
            bidi.annotation,
            FrbTextAnnotation::String("ISOLATE_AUTO".to_owned())
        );
    }

    #[test]
    fn maps_styled_html_with_real_css_engine() {
        let css_text = r#"
            .epigraph { font-style: italic; text-align: right; margin-left: 2em; }
            .lead { text-indent: 1.5em; color: #336699; }
            .lead::first-letter { float: left; font-size: 2.5em; font-weight: bold; }
            .sep::before { content: "* * *"; }
            .hidden { display: none; }
            .box { border-top: 1px solid #112233; padding-top: 0.5em; }
        "#;
        let html = r#"
            <html><body>
              <div class="box">
                <p class="epigraph">An epigraph</p>
                <p class="lead" id="p-lead">First <em>emphasized</em> paragraph.</p>
                <div class="sep"></div>
                <p class="hidden" id="hid">Invisible</p>
              </div>
              <p style="text-align: center">Inline styled</p>
            </body></html>
        "#;
        let compiled = CompiledStylesheets::compile(&[SheetInput::new(css_text, "")], None);
        let doc = chapter_markup::parse(html, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut shared = SharedStyleCache::new();
        let mut chapter_css = ChapterCss::new(compiled, &doc, &mut shared);
        let mut cb = TestCallbacks { allow_images: true };
        let opts = MapOptions {
            publisher_box_id_prefix: "ch1.xhtml@0".to_owned(),
            ..MapOptions::default()
        };
        let mapped = map_document(&doc, body, Some(&mut chapter_css), &mut cb, &opts).unwrap();
        assert_eq!(mapped.elements.len(), 4);
        assert_eq!(mapped.anchors.get("p-lead"), Some(&1));
        assert_eq!(mapped.anchors.get("hid"), Some(&3));
        assert!(mapped
            .publisher_boxes
            .iter()
            .any(|b| b.style.border_top.is_some()
                && b.start_element == 0
                && b.end_element_exclusive == 3));
        let FrbContentElement::Paragraph {
            block: Some(lead_block),
            ..
        } = &mapped.elements[1]
        else {
            panic!("expected lead paragraph with block style");
        };
        assert_eq!(lead_block.first_line_indent, Some(true));
        assert!(lead_block
            .first_letter
            .as_ref()
            .is_some_and(|fl| fl.is_drop_cap));
        let FrbContentElement::Paragraph { text: sep_text, .. } = &mapped.elements[2] else {
            panic!("expected generated separator paragraph");
        };
        assert_eq!(sep_text.text, "* * *");
        let FrbContentElement::Paragraph {
            block: Some(inline_block),
            ..
        } = &mapped.elements[3]
        else {
            panic!("expected inline styled paragraph");
        };
        assert_eq!(inline_block.align, Some(FrbBlockAlign::Center));
    }

    #[test]
    fn maps_inline_style_without_external_css() {
        let html = r#"<html><body><p style="text-align: center; font-weight: bold">Centered</p></body></html>"#;
        let doc = chapter_markup::parse(html, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let mapped = map_document(&doc, body, None, &mut cb, &MapOptions::default()).unwrap();
        assert_eq!(mapped.elements.len(), 1);
        let FrbContentElement::Paragraph { block: Some(b), .. } = &mapped.elements[0] else {
            panic!("expected paragraph with inline style block");
        };
        assert_eq!(b.align, Some(FrbBlockAlign::Center));
        assert_eq!(b.bold, Some(true));
    }

    #[test]
    fn maps_css_flow_blocks_floats_images_and_text_transforms() {
        let css_text = r#"
            .stanza span.line { display: block; text-indent: -1em; padding-left: 1em; }
            .cap { float: left; font-size: 3em; font-weight: bold; }
            .fl-img { float: left; width: 30%; }
            .big-img { width: 80%; }
            .caps { text-transform: uppercase; }
            .capz { text-transform: capitalize; }
            .vis { visibility: hidden; }
            .clr { clear: both; }
            .rule { border-top: 1px solid #222; height: 0.25em; }
        "#;
        let html = r#"
            <html lang="tr"><body>
              <p class="stanza"><span class="line">Line one</span><br/><span class="line">Line two</span></p>
              <p><span class="cap">W</span>elcome to the book.</p>
              <p><img class="fl-img" src="fl.png" alt="Float"/>Wrapped text with <img src="icon.png" alt="Icon"/> inline and <img class="big-img" src="big.png" alt="Big"/> block.</p>
              <p><span class="caps">istanbul</span> <span class="capz">hello world</span> <span class="vis">secret</span></p>
              <div class="clr"></div>
              <hr class="rule"/>
              <figure><img src="missing.png" alt="Fallback alt"/><figcaption>Caption text</figcaption></figure>
              <details><summary>Summary text</summary><p>Detail body</p></details>
              <audio></audio>
              <video></video>
              <div class="empty-line"></div>
              <div class="title1"><p>Sub Title</p></div>
            </body></html>
        "#;
        let compiled = CompiledStylesheets::compile(&[SheetInput::new(css_text, "")], None);
        let doc = chapter_markup::parse(html, false).unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut shared = SharedStyleCache::new();
        let mut chapter_css = ChapterCss::new(compiled, &doc, &mut shared);
        struct SelectiveImages;
        impl ResourceCallbacks for SelectiveImages {
            fn resolve_image(&mut self, src: &str) -> Option<String> {
                if src == "missing.png" {
                    None
                } else {
                    Some(format!("/img/{src}"))
                }
            }
            fn resolve_link(&mut self, _href: &str) -> Option<String> {
                None
            }
        }
        let mut cb = SelectiveImages;
        let mapped = map_document(
            &doc,
            body,
            Some(&mut chapter_css),
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();

        // Stanza lines become separate CSS text lines and the outer <p> box gets fit_text_flow = true
        assert!(matches!(
            &mapped.elements[0],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Line one" && b.is_css_text_line
        ));
        assert!(matches!(
            &mapped.elements[1],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Line two" && b.is_css_text_line
        ));
        assert!(mapped.publisher_boxes.iter().any(|b| b.style.fit_text_flow
            && b.start_element == 0
            && b.end_element_exclusive == 2));

        // Explicit floated initial drop cap
        assert!(matches!(
            &mapped.elements[2],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Welcome to the book."
                && b.first_letter.as_ref().is_some_and(|fl| fl.is_drop_cap && fl.source_text_length == Some(1))
        ));

        // Float image attached to paragraph, inline image in text, big image emitted after
        assert!(matches!(
            &mapped.elements[3],
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if b.float_image.as_ref().is_some_and(|fi| fi.path == "/img/fl.png" && fi.left)
                && text.text.contains(INLINE_IMAGE_CHAR)
                && text.ranges.iter().any(|r| r.tag == INLINE_IMAGE_TAG)
                && text.ranges.iter().any(|r| r.tag == INLINE_CONTENT_TAG)
        ));
        assert!(matches!(
            &mapped.elements[4],
            FrbContentElement::Image(img) if img.path == "/img/big.png"
        ));

        // Turkish uppercase (i -> İ), capitalize, and visibility:hidden annotation
        assert!(matches!(
            &mapped.elements[5],
            FrbContentElement::Paragraph { text, .. }
            if text.text == "İSTANBUL Hello World secret"
                && text.ranges.iter().any(|r| r.tag == HIDDEN_TEXT_TAG)
        ));

        // Zero-length clear box preserved
        assert!(mapped
            .publisher_boxes
            .iter()
            .any(|b| b.start_element == b.end_element_exclusive
                && b.clear == FrbPublisherClear::Both));

        // Authored <hr> becomes Spacer instead of Divider
        assert!(matches!(
            &mapped.elements[6],
            FrbContentElement::Spacer { height_em } if (*height_em - 0.25).abs() < 1e-4
        ));

        // Missing image emits italic alt text paragraph, figcaption centered italic 0.9x,
        // summary bold, audio/video placeholders, empty-line spacer, title1 -> h2
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Fallback alt" && b.italic == Some(true)
        )));
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Caption text" && b.align == Some(FrbBlockAlign::Center) && b.italic == Some(true)
        )));
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Paragraph { text, block: Some(b), .. }
            if text.text == "Summary text" && b.bold == Some(true)
        )));
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Paragraph { text, .. } if text.text == AUDIO_PLACEHOLDER
        )));
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Paragraph { text, .. } if text.text == VIDEO_PLACEHOLDER
        )));
        assert!(mapped.elements.iter().any(|e| matches!(
            e,
            FrbContentElement::Heading { styled_text, level: 2, .. } if styled_text.text == "Sub Title"
        )));
    }

    #[test]
    fn maps_css_horizontal_rows_and_inline_text_boxes() {
        let row_css = ".row { display: flex; justify-content: space-between; align-items: center; } .row::before { content: '«'; }";
        let compiled_row = CompiledStylesheets::compile(&[SheetInput::new(row_css, "")], None);
        let doc_row =
            chapter_markup::parse("<div class=\"row\"><p>A</p><p>B</p></div>", true).unwrap();
        let body_row = doc_row.preindex.body_node.unwrap();
        let mut css_row = ChapterCss::standalone(compiled_row, &doc_row);
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let mapped_row = map_document(
            &doc_row,
            body_row,
            Some(&mut css_row),
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();
        assert_eq!(mapped_row.elements.len(), 1);
        let FrbContentElement::Table(row_tbl) = &mapped_row.elements[0] else {
            panic!("expected flex row table");
        };
        assert_eq!(
            row_tbl.publisher_row_layout,
            Some(FrbPublisherRowLayout::SpaceBetween)
        );
        assert!(row_tbl.center_cells_vertically);
        assert_eq!(row_tbl.rows[0].cells.len(), 3);
        assert_eq!(row_tbl.rows[0].cells[0].text.text, "«");
        assert_eq!(row_tbl.rows[0].cells[1].text.text, "A");
        assert_eq!(row_tbl.rows[0].cells[2].text.text, "B");

        // Vertical flex column is NOT a horizontal row and maps cleanly as a paragraph
        let col_css = ".col { display: flex; flex-direction: column; }";
        let compiled_col = CompiledStylesheets::compile(&[SheetInput::new(col_css, "")], None);
        let doc_col = chapter_markup::parse("<div class=\"col\"><p>Title</p></div>", true).unwrap();
        let body_col = doc_col.preindex.body_node.unwrap();
        let mut css_col = ChapterCss::standalone(compiled_col, &doc_col);
        let mapped_col = map_document(
            &doc_col,
            body_col,
            Some(&mut css_col),
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();
        assert_eq!(mapped_col.elements.len(), 1);
        assert!(matches!(
            &mapped_col.elements[0],
            FrbContentElement::Paragraph { .. }
        ));

        // Inline-block text box with nested inherited font size scaling
        let ib_css = "p { font-size: 150%; } .large { font-size: 200%; } .initial { display: inline-block; width: 1.2em; text-align: center; font-style: italic; }";
        let compiled_ib = CompiledStylesheets::compile(&[SheetInput::new(ib_css, "")], None);
        let doc_ib = chapter_markup::parse(
            "<p><span class=\"large\"><span class=\"initial\">A</span>fter</span></p>",
            true,
        )
        .unwrap();
        let body_ib = doc_ib.preindex.body_node.unwrap();
        let mut css_ib = ChapterCss::standalone(compiled_ib, &doc_ib);
        let mapped_ib = map_document(
            &doc_ib,
            body_ib,
            Some(&mut css_ib),
            &mut cb,
            &MapOptions::default(),
        )
        .unwrap();
        let FrbContentElement::Paragraph {
            text: ib_text,
            block: Some(ib_block),
            ..
        } = &mapped_ib.elements[0]
        else {
            panic!("expected inline text box paragraph");
        };
        assert_eq!(ib_text.text, "After");
        assert!((ib_block.font_scale.unwrap() - 1.5).abs() < 1e-4);
        let box_ann = ib_text
            .ranges
            .iter()
            .find(|r| r.tag == INLINE_TEXT_BOX_TAG)
            .expect("inline text box annotation");
        assert_eq!(box_ann.start, 0);
        assert_eq!(box_ann.end, 1);
        assert_eq!(
            box_ann.annotation,
            FrbTextAnnotation::String(
                r#"{"id":"inline-box:document:0","widthEm":2.4,"align":"center","sourceLength":1}"#
                    .to_owned()
            )
        );
        assert!(ib_text.ranges.iter().any(|r| {
            r.tag == INLINE_CONTENT_TAG
                && r.start == 0
                && r.end == 1
                && r.annotation == FrbTextAnnotation::String("inline-box:document:0".to_owned())
        }));
    }

    #[test]
    fn fb2_epub_promoted_notes_and_expansion_budget_enforced() {
        let doc = chapter_markup::parse(
            "<p>Text <a href=\"ch2-1.xhtml#n1\">[1]</a> and <a href=\"ch1.xhtml#sec2\">[2]</a></p>",
            false,
        )
        .unwrap();
        let body = doc.preindex.body_node.unwrap();
        let mut cb = TestCallbacks {
            allow_images: false,
        };
        let opts = MapOptions {
            fb2_epub_conversion: true,
            ..MapOptions::default()
        };
        let mapped = map_document(&doc, body, None, &mut cb, &opts).unwrap();
        assert!(mapped.note_targets.contains("ch2-1.xhtml#n1"));
        assert!(!mapped.note_targets.contains("ch1.xhtml#sec2"));
        assert!(mapped.link_targets.contains("ch1.xhtml#sec2"));

        // Expansion budget tracks and caps generated characters
        let css = CompiledStylesheets::compile(
            &[SheetInput::new("p::before { content: \"12345\"; }", "")],
            None,
        );
        let bdoc = chapter_markup::parse("<p>A</p><p>B</p>", true).unwrap();
        let bbody = bdoc.preindex.body_node.unwrap();
        let mut ch_css = ChapterCss::standalone(css, &bdoc);
        let bopts = MapOptions {
            max_generated_run_chars: 10,
            max_generated_total_chars: 7,
            remaining_generated_total_chars: 7,
            ..MapOptions::default()
        };
        let bmapped = map_document(&bdoc, bbody, Some(&mut ch_css), &mut cb, &bopts).unwrap();
        assert_eq!(bmapped.generated_chars_used, 5);
        assert!(bmapped.was_aggregate_limited);
        let FrbContentElement::Paragraph { text: p0, .. } = &bmapped.elements[0] else {
            panic!("p0");
        };
        let FrbContentElement::Paragraph { text: p1, .. } = &bmapped.elements[1] else {
            panic!("p1");
        };
        assert_eq!(p0.text, "12345A");
        assert_eq!(p1.text, "B");
    }
}
