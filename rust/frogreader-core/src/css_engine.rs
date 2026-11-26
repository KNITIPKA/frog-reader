//! Complete native Rust CSS stylesheet compiler, selector matcher, cascade
//! cache, and computed-style engine operating directly on
//! [`crate::chapter_markup::Document`].
//!
//! Mirrors `CssResolver.kt` semantics 1:1 while keeping stylesheet compilation,
//! rule-pool bucketing, selector matching, `SharedComputedKey` caching,
//! `FrbPublisherBoxStyle` assembly, `::first-letter`, `floated_text_initial`,
//! `::before`/`::after` `GeneratedRun`, and `dark_image_style` entirely in Rust
//! with zero JNI crossings.

#![allow(dead_code)]

use crate::{
    chapter_markup::{
        attribute_keys_equal, Document, Kind, ATTR_HAS_DIR, ATTR_HAS_PRESENTATIONAL, ATTR_HAS_STYLE,
    },
    css_color, css_computed, css_computed_fonts,
    css_computed_model::{Border, ComputedStyle, Declaration, Element as CssElement},
    css_declarations, css_lengths,
    css_match::{
        self, Attr, AttrOp, Compound, DeclarationRef, Node as MatchNode, Pseudo, Rule as MatchRule,
        Session as MatchSession, Simple,
    },
    css_selectors, css_shorthand, css_syntax, css_variables,
    frb_model::{
        FrbBookTextDirection, FrbFirstLetterStyle, FrbFontTypography, FrbPublisherBorderSide,
        FrbPublisherBorderStyle, FrbPublisherBoxAlign, FrbPublisherBoxStyle,
    },
};
use std::{
    borrow::Cow,
    collections::{BTreeSet, HashMap, HashSet},
    hash::{Hash, Hasher},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
};

// --------------------------------------------------------------------------- Limits (CssResolver.kt parity)

const BLACK_ARGB: i32 = 0xff00_0000_u32 as i32;
const MAX_BOX_INLINE_EM: f32 = 8.0;
const MAX_BOX_INLINE_FRAC: f32 = 0.45;
const MAX_GROUP_RULE_DEPTH: usize = 64;
const MAX_RESPONSIVE_BREAKPOINTS: usize = 8;
const MAX_RULES: usize = 16_384;
const MAX_FONT_FACES: usize = 512;
const MAX_STYLESHEET_DECLARATIONS: usize = 131_072;
const MAX_DECLARATIONS_PER_BLOCK: usize = 512;
const MAX_COMMA_LIST_ITEMS: usize = 256;
const MAX_UNIVERSAL_RULES: usize = 1_024;
const MAX_RULES_PER_INDEX_BUCKET: usize = 4_096;
const MAX_SELECTOR_MATCH_OPERATIONS: i32 = 5_000_000;
const MAX_SHARED_COMPUTED_STYLES: usize = 1_024;
const MAX_INLINE_CACHE_ENTRIES: usize = 256;
const MAX_INLINE_CACHE_BYTES: usize = 128 * 1024;
const MAX_INLINE_CACHE_KEY_BYTES: usize = 2 * 1024;
const MAX_INPUT_BYTES: usize = 32 * 1024 * 1024;
const MAX_CACHED_STYLESHEET_ENTRIES: usize = 16;

const SHEET_MAGIC: u32 = 0x3153_5343; // CSS1
const INLINE_MAGIC: u32 = 0x4453_5343; // CSSD

const POOL_NORMAL: usize = 0;
const POOL_FIRST_LETTER: usize = 1;
const POOL_BEFORE: usize = 2;
const POOL_AFTER: usize = 3;
const POOL_COUNT: usize = 4;

static NEXT_STYLESHEET_ID: AtomicU64 = AtomicU64::new(1);

// --------------------------------------------------------------------------- Public Types

/// Optional known CSS viewport. No parse-time guess is made for device size.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Viewport {
    pub width_px: f32,
    pub height_px: Option<f32>,
    pub dark: Option<bool>,
}

/// One stylesheet text plus the directory its relative URLs resolve from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SheetInput {
    pub text: String,
    pub base_dir: String,
}

impl SheetInput {
    pub(crate) fn new(text: impl Into<String>, base_dir: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            base_dir: base_dir.into(),
        }
    }
}

/// One `@font-face` rule: a face of `family` at `src` (URL as written).
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct FontFaceSpec {
    pub family: String,
    pub src: String,
    pub base_dir: String,
    pub bold: bool,
    pub italic: bool,
    pub weight: i32,
    pub weight_min: i32,
    pub weight_max: i32,
    pub stretch_min: f32,
    pub stretch_max: f32,
    pub unicode_range: Option<String>,
}

/// A `::before`/`::after` text run generated via CSS.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct GeneratedRun {
    pub text: String,
    pub italic: Option<bool>,
    pub bold: Option<bool>,
    pub font_weight: Option<i32>,
    /// Font size relative to the element (1 = same).
    pub scale: f32,
    pub foreground_color_argb: Option<i32>,
    pub background_color_argb: Option<i32>,
    pub font_feature_settings: Option<String>,
    pub text_transform: Option<String>,
    pub visibility_hidden: bool,
    /// A generated block decoration, including empty border separators.
    pub publisher_box: Option<FrbPublisherBoxStyle>,
    pub font_family_name: Option<String>,
    pub font_typography: FrbFontTypography,
}

// --------------------------------------------------------------------------- CompiledStylesheets

/// Immutable compiled CSS rule set, safe to share across chapter worker
/// threads via `Arc<CompiledStylesheets>` with zero reparsing.
pub(crate) struct CompiledStylesheets {
    id: u64,
    viewport: Option<Viewport>,
    pools: [Vec<MatchRule>; POOL_COUNT],
    blocks: Vec<Vec<DeclarationRef>>,
    declarations: Vec<Declaration>,
    property_ids: HashMap<String, i32>,
    absolute_keys: Vec<String>,
    font_faces: Vec<FontFaceSpec>,
    simple_max_width_breakpoints: Vec<f32>,
    has_display_rules: bool,
    has_page_break_before_rules: bool,
    has_flex_centering_rules: bool,
    has_before_rules: bool,
    has_after_rules: bool,
    has_first_letter_rules: bool,
    has_responsive_breakpoints: bool,
    has_prefers_color_scheme_media: bool,
    source_sheets: Vec<SheetInput>,
    dark_variant: OnceLock<Arc<CompiledStylesheets>>,
    responsive_variants: Mutex<HashMap<u32, Arc<CompiledStylesheets>>>,
}

impl CompiledStylesheets {
    /// Compiles a list of stylesheets against an optional viewport.
    pub(crate) fn compile(sheets: &[SheetInput], viewport: Option<Viewport>) -> Arc<Self> {
        let mut compiler = StylesheetCompiler::new(viewport);
        let mut has_prefers_color_scheme = false;
        for sheet in sheets {
            if contains_ascii_ignore_case(&sheet.text, b"prefers-color-scheme") {
                has_prefers_color_scheme = true;
            }
            if !compiler.can_accept_rules() {
                break;
            }
            compiler.parsing_namespaces.clear();
            if sheet.text.len() > MAX_INPUT_BYTES || sheet.text.chars().all(reader_whitespace) {
                continue;
            }
            if let Some(bytes) = css_syntax::sheet_bytes(&sheet.text) {
                if let Some(rules) = decode_sheet_rules(&bytes) {
                    compiler.add_sheet_rules(&rules, &sheet.base_dir, 0);
                }
            }
        }
        compiler.finish(sheets, has_prefers_color_scheme)
    }

    /// Returns a shared empty stylesheet bundle (zero rules, zero font faces).
    pub(crate) fn empty() -> Arc<Self> {
        static EMPTY: OnceLock<Arc<CompiledStylesheets>> = OnceLock::new();
        Arc::clone(EMPTY.get_or_init(|| Self::compile(&[], None)))
    }

    pub(crate) fn font_faces(&self) -> &[FontFaceSpec] {
        &self.font_faces
    }

    pub(crate) fn has_display_rules(&self) -> bool {
        self.has_display_rules
    }

    pub(crate) fn has_page_break_before_rules(&self) -> bool {
        self.has_page_break_before_rules
    }

    pub(crate) fn has_flex_centering_rules(&self) -> bool {
        self.has_flex_centering_rules
    }

    pub(crate) fn has_before_rules(&self) -> bool {
        self.has_before_rules
    }

    pub(crate) fn has_after_rules(&self) -> bool {
        self.has_after_rules
    }

    pub(crate) fn has_generated_rules(&self) -> bool {
        self.has_before_rules || self.has_after_rules
    }

    pub(crate) fn has_first_letter_rules(&self) -> bool {
        self.has_first_letter_rules
    }

    pub(crate) fn has_responsive_breakpoints(&self) -> bool {
        self.has_responsive_breakpoints
    }

    pub(crate) fn max_width_breakpoints_css_px(&self) -> Vec<f32> {
        let mut sorted = self.simple_max_width_breakpoints.clone();
        sorted.sort_by(|a, b| b.total_cmp(a));
        sorted
    }

    pub(crate) fn has_absolute_attr_selectors(&self) -> bool {
        !self.absolute_keys.is_empty()
    }

    /// Lazily compiles and caches a responsive variant for one authored
    /// `max-width` breakpoint.
    pub(crate) fn variant_at_max_width(&self, width_css_px: f32) -> Option<Arc<Self>> {
        let bits = width_css_px.to_bits();
        if !self.simple_max_width_breakpoints.contains(&width_css_px) {
            return None;
        }
        let mut guard = self
            .responsive_variants
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(existing) = guard.get(&bits) {
            return Some(Arc::clone(existing));
        }
        let compiled = Self::compile(
            &self.source_sheets,
            Some(Viewport {
                width_px: width_css_px,
                height_px: None,
                dark: None,
            }),
        );
        guard.insert(bits, Arc::clone(&compiled));
        Some(compiled)
    }

    /// Lazily compiles and caches the dark-appearance variant used by
    /// `dark_image_style`. Returns `self` without recompiling when already
    /// dark or when no `prefers-color-scheme` query exists under a `None`
    /// viewport.
    pub(crate) fn dark_variant(self: &Arc<Self>) -> Arc<Self> {
        if self.viewport.is_some_and(|v| v.dark == Some(true)) {
            return Arc::clone(self);
        }
        if self.viewport.is_none() && !self.has_prefers_color_scheme_media {
            return Arc::clone(self);
        }
        Arc::clone(self.dark_variant.get_or_init(|| {
            Self::compile(
                &self.source_sheets,
                Some(Viewport {
                    width_px: f32::NAN,
                    height_px: None,
                    dark: Some(true),
                }),
            )
        }))
    }
}

// --------------------------------------------------------------------------- SharedStyleCache

struct ArcPtr<T>(Arc<T>);

impl<T> Clone for ArcPtr<T> {
    #[inline]
    fn clone(&self) -> Self {
        Self(Arc::clone(&self.0))
    }
}

impl<T> PartialEq for ArcPtr<T> {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.0, &other.0)
    }
}

impl<T> Eq for ArcPtr<T> {}

impl<T> Hash for ArcPtr<T> {
    #[inline]
    fn hash<H: Hasher>(&self, state: &mut H) {
        (Arc::as_ptr(&self.0) as usize).hash(state);
    }
}

#[derive(Clone)]
struct ParentStyleKey(Option<Arc<ComputedStyle>>);

impl PartialEq for ParentStyleKey {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        match (&self.0, &other.0) {
            (Some(a), Some(b)) => Arc::ptr_eq(a, b),
            (None, None) => true,
            _ => false,
        }
    }
}

impl Eq for ParentStyleKey {}

impl Hash for ParentStyleKey {
    #[inline]
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.0
            .as_ref()
            .map_or(0usize, |p| Arc::as_ptr(p) as usize)
            .hash(state);
    }
}

struct InlineStyle {
    declarations: Vec<Declaration>,
    refs: Vec<DeclarationRef>,
    winners: OnceLock<Arc<CascadedDeclarations>>,
}

impl InlineStyle {
    fn new(
        compiled_decls: Vec<(String, String, bool, i32)>,
        property_ids: &HashMap<String, i32>,
    ) -> Self {
        let mut declarations = Vec::with_capacity(compiled_decls.len());
        let mut refs = Vec::with_capacity(compiled_decls.len());
        for (index, (property, value, important, expansion)) in
            compiled_decls.into_iter().enumerate()
        {
            let prop_id = property_ids
                .get(&property)
                .copied()
                .unwrap_or(-1 - index as i32);
            refs.push(DeclarationRef {
                property: prop_id,
                id: -1 - index as i32,
                important,
            });
            declarations.push(Declaration {
                property,
                value,
                expansion,
            });
        }
        Self {
            declarations,
            refs,
            winners: OnceLock::new(),
        }
    }

    fn winners(&self) -> &Arc<CascadedDeclarations> {
        self.winners.get_or_init(|| {
            if self.declarations.is_empty() {
                return Arc::new(CascadedDeclarations::empty());
            }
            let ids = css_match::cascade_inline(&self.refs);
            let mut decls = Vec::with_capacity(ids.len());
            for id in ids {
                decls.push(clone_declaration(&self.declarations[(-1 - id) as usize]));
            }
            Arc::new(CascadedDeclarations {
                declarations: decls,
            })
        })
    }
}

struct CascadedDeclarations {
    declarations: Vec<Declaration>,
}

impl CascadedDeclarations {
    fn empty() -> Self {
        Self {
            declarations: Vec::new(),
        }
    }

    fn is_empty(&self) -> bool {
        self.declarations.is_empty()
    }

    fn get(&self, property: &str) -> Option<&Declaration> {
        self.declarations
            .iter()
            .find(|decl| decl.property == property)
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
enum DeclCacheKey {
    Empty,
    SheetOnly(ArcPtr<CascadedDeclarations>),
    InlineOnly(ArcPtr<InlineStyle>),
    Mixed {
        inline: ArcPtr<InlineStyle>,
        winner_ids: Vec<i32>,
    },
}

#[derive(Clone, PartialEq, Eq, Hash)]
struct SharedComputedKey {
    parent: ParentStyleKey,
    tag: Cow<'static, str>,
    align: String,
    direction: String,
    foreground: String,
    body_foreground: String,
    background: String,
    declarations: DeclCacheKey,
}

struct PerSheetCache {
    shared_computed: HashMap<SharedComputedKey, Arc<ComputedStyle>>,
    cascade_winner_cache: HashMap<Vec<i32>, Arc<CascadedDeclarations>>,
    inline_declarations: HashMap<String, Arc<InlineStyle>>,
    inline_cache_key_bytes: usize,
    computed_resolutions: usize,
    selector_budget: i32,
}

impl PerSheetCache {
    fn new() -> Self {
        Self {
            shared_computed: HashMap::new(),
            cascade_winner_cache: HashMap::new(),
            inline_declarations: HashMap::new(),
            inline_cache_key_bytes: 0,
            computed_resolutions: 0,
            selector_budget: MAX_SELECTOR_MATCH_OPERATIONS,
        }
    }

    fn sweep_chapter_end(&mut self) {
        if self.shared_computed.len() >= MAX_SHARED_COMPUTED_STYLES
            || self.computed_resolutions >= MAX_SHARED_COMPUTED_STYLES
        {
            self.shared_computed.clear();
            self.cascade_winner_cache.clear();
            self.computed_resolutions = 0;
        }
    }
}

/// Reusable cross-chapter CSS cache owned per worker thread.
///
/// Retains DOM-free `shared_computed`, `cascade_winner_cache`, and parsed
/// inline `style=""` declarations across chapters sharing the same
/// `CompiledStylesheets`, while keeping memory strictly bounded.
#[derive(Default)]
pub(crate) struct SharedStyleCache {
    sheets: HashMap<u64, PerSheetCache>,
}

impl SharedStyleCache {
    pub(crate) fn new() -> Self {
        Self::default()
    }

    pub(crate) fn clear(&mut self) {
        self.sheets.clear();
    }

    pub(crate) fn shared_computed_count(&self, compiled: &CompiledStylesheets) -> usize {
        self.sheets
            .get(&compiled.id)
            .map_or(0, |s| s.shared_computed.len())
    }

    pub(crate) fn cascade_winner_cache_size(&self, compiled: &CompiledStylesheets) -> usize {
        self.sheets
            .get(&compiled.id)
            .map_or(0, |s| s.cascade_winner_cache.len())
    }

    fn sheet_mut(&mut self, sheet_id: u64) -> &mut PerSheetCache {
        if !self.sheets.contains_key(&sheet_id)
            && self.sheets.len() >= MAX_CACHED_STYLESHEET_ENTRIES
        {
            self.sheets.clear();
        }
        self.sheets
            .entry(sheet_id)
            .or_insert_with(PerSheetCache::new)
    }

    fn sheet_ref(&self, sheet_id: u64) -> Option<&PerSheetCache> {
        self.sheets.get(&sheet_id)
    }
}

// --------------------------------------------------------------------------- ChapterCss

enum CacheSlot<'a> {
    Borrowed(&'a mut SharedStyleCache),
    Owned(SharedStyleCache),
}

impl CacheSlot<'_> {
    #[inline]
    fn as_mut(&mut self) -> &mut SharedStyleCache {
        match self {
            Self::Borrowed(cache) => cache,
            Self::Owned(cache) => cache,
        }
    }

    #[inline]
    fn as_ref(&self) -> &SharedStyleCache {
        match self {
            Self::Borrowed(cache) => cache,
            Self::Owned(cache) => cache,
        }
    }
}

struct MatcherState {
    session: MatchSession,
    doc_to_match: Vec<u32>,
}

struct ChapterState<'a> {
    compiled: Arc<CompiledStylesheets>,
    doc: &'a Document,
    computed_by_node: Vec<Option<Arc<ComputedStyle>>>,
    matcher: Option<MatcherState>,
}

impl<'a> ChapterState<'a> {
    fn new(compiled: Arc<CompiledStylesheets>, doc: &'a Document) -> Self {
        Self {
            compiled,
            doc,
            computed_by_node: vec![None; doc.nodes.len()],
            matcher: None,
        }
    }

    fn ensure_matcher(&mut self) -> Option<&mut MatcherState> {
        if self.matcher.is_none() {
            let (nodes, doc_to_match) = build_match_nodes(self.doc, &self.compiled.absolute_keys)?;
            let pools = clone_rule_pools(&self.compiled.pools);
            let session = MatchSession::new(nodes, pools);
            self.matcher = Some(MatcherState {
                session,
                doc_to_match,
            });
        }
        self.matcher.as_mut()
    }

    fn computed(&mut self, node_id: usize, cache: &mut SharedStyleCache) -> Arc<ComputedStyle> {
        if let Some(Some(cached)) = self.computed_by_node.get(node_id) {
            return Arc::clone(cached);
        }
        if !self.is_element(node_id) {
            return Arc::new(css_computed::resolve(&CssElement::default(), None, &[]));
        }
        let mut unresolved = Vec::new();
        let mut cursor = Some(node_id);
        let mut inherited: Option<Arc<ComputedStyle>> = None;
        while let Some(current) = cursor {
            if !self.is_element(current) {
                break;
            }
            if let Some(Some(cached)) = self.computed_by_node.get(current) {
                inherited = Some(Arc::clone(cached));
                break;
            }
            unresolved.push(current);
            cursor = self.element_parent(current);
        }
        for current in unresolved.into_iter().rev() {
            let resolved = self.resolve_shared(current, inherited.as_ref(), cache);
            self.computed_by_node[current] = Some(Arc::clone(&resolved));
            inherited = Some(resolved);
        }
        inherited
            .unwrap_or_else(|| Arc::new(css_computed::resolve(&CssElement::default(), None, &[])))
    }

    fn resolve_shared(
        &mut self,
        node_id: usize,
        parent: Option<&Arc<ComputedStyle>>,
        cache: &mut SharedStyleCache,
    ) -> Arc<ComputedStyle> {
        let (decl_key, cascaded) = self.cascaded_for(node_id, cache);
        let direction = self.dir_attr(node_id);
        let parent_auto = parent.is_some_and(|p| p.direction.as_deref() == Some("auto"));
        if parent_auto
            || direction
                .trim_matches(reader_whitespace)
                .eq_ignore_ascii_case("auto")
        {
            return Arc::new(self.resolve_node(
                node_id,
                parent.map(Arc::as_ref),
                &cascaded.declarations,
                false,
                cache,
            ));
        }
        let has_presentational = self.has_presentational_attrs(node_id);
        let key = SharedComputedKey {
            parent: ParentStyleKey(parent.cloned()),
            tag: intern_common_tag(&self.normalized_tag(node_id)),
            align: if has_presentational {
                self.attr(node_id, "align").to_owned()
            } else {
                String::new()
            },
            direction: direction.to_owned(),
            foreground: if has_presentational {
                self.attr(node_id, "color").to_owned()
            } else {
                String::new()
            },
            body_foreground: if has_presentational {
                self.attr(node_id, "text").to_owned()
            } else {
                String::new()
            },
            background: if has_presentational {
                self.attr(node_id, "bgcolor").to_owned()
            } else {
                String::new()
            },
            declarations: decl_key,
        };
        let sheet_id = self.compiled.id;
        if let Some(existing) = cache.sheet_mut(sheet_id).shared_computed.get(&key) {
            return Arc::clone(existing);
        }
        let resolved = Arc::new(self.resolve_node(
            node_id,
            parent.map(Arc::as_ref),
            &cascaded.declarations,
            false,
            cache,
        ));
        let sheet_cache = cache.sheet_mut(sheet_id);
        if sheet_cache.shared_computed.len() < MAX_SHARED_COMPUTED_STYLES {
            sheet_cache
                .shared_computed
                .insert(key, Arc::clone(&resolved));
        }
        resolved
    }

    fn resolve_node(
        &self,
        node_id: usize,
        parent: Option<&ComputedStyle>,
        declarations: &[Declaration],
        pseudo_element: bool,
        cache: &mut SharedStyleCache,
    ) -> ComputedStyle {
        let dir_attr = self.dir_attr(node_id);
        let may_use_auto_dir = parent.is_some_and(|p| p.direction.as_deref() == Some("auto"))
            || dir_attr
                .trim_matches(reader_whitespace)
                .eq_ignore_ascii_case("auto");
        let has_logical_insets = may_use_auto_dir
            && declarations.iter().any(|d| {
                matches!(
                    d.property.as_str(),
                    "margin-inline-start"
                        | "margin-inline-end"
                        | "padding-inline-start"
                        | "padding-inline-end"
                )
            });
        let first_strong_rtl =
            has_logical_insets && first_strong_rtl_node(self.doc, node_id) == Some(true);
        let has_presentational = self.has_presentational_attrs(node_id);
        let element = CssElement {
            tag: self.normalized_tag(node_id).into_owned(),
            align: if has_presentational {
                self.attr(node_id, "align").to_owned()
            } else {
                String::new()
            },
            direction: dir_attr.to_owned(),
            foreground: if has_presentational {
                self.attr(node_id, "color").to_owned()
            } else {
                String::new()
            },
            body_foreground: if has_presentational {
                self.attr(node_id, "text").to_owned()
            } else {
                String::new()
            },
            background: if has_presentational {
                self.attr(node_id, "bgcolor").to_owned()
            } else {
                String::new()
            },
            first_strong_rtl,
            pseudo: pseudo_element,
        };
        cache.sheet_mut(self.compiled.id).computed_resolutions += 1;
        css_computed::resolve(&element, parent, declarations)
    }

    fn cascaded_for(
        &mut self,
        node_id: usize,
        cache: &mut SharedStyleCache,
    ) -> (DeclCacheKey, Arc<CascadedDeclarations>) {
        let style_str = self.style_attr(node_id);
        let inline = if style_str.is_empty() {
            None
        } else {
            Some(self.inline_style_for(style_str, cache))
        };
        let sheet_id = self.compiled.id;

        if inline.is_none() {
            if self.compiled.pools[POOL_NORMAL].is_empty()
                || cache.sheet_mut(sheet_id).selector_budget <= 0
            {
                return (DeclCacheKey::Empty, empty_cascaded());
            }
            let winners = self.match_winners(POOL_NORMAL, node_id, &[], cache);
            if winners.is_empty() {
                return (DeclCacheKey::Empty, empty_cascaded());
            }
            if let Some(cached) = cache.sheet_mut(sheet_id).cascade_winner_cache.get(&winners) {
                let arc = Arc::clone(cached);
                return (DeclCacheKey::SheetOnly(ArcPtr(Arc::clone(&arc))), arc);
            }
            let cascaded = Arc::new(self.materialize_winners(&winners, None));
            let sheet_cache = cache.sheet_mut(sheet_id);
            if sheet_cache.cascade_winner_cache.len() < MAX_SHARED_COMPUTED_STYLES {
                sheet_cache
                    .cascade_winner_cache
                    .insert(winners, Arc::clone(&cascaded));
            }
            return (
                DeclCacheKey::SheetOnly(ArcPtr(Arc::clone(&cascaded))),
                cascaded,
            );
        }

        let inline = inline.expect("checked above");
        if self.compiled.pools[POOL_NORMAL].is_empty()
            || cache.sheet_mut(sheet_id).selector_budget <= 0
        {
            let winners = Arc::clone(inline.winners());
            let key = if winners.is_empty() {
                DeclCacheKey::Empty
            } else {
                DeclCacheKey::InlineOnly(ArcPtr(inline))
            };
            return (key, winners);
        }

        let winners = self.match_winners(POOL_NORMAL, node_id, &inline.refs, cache);
        if winners.is_empty() {
            return (DeclCacheKey::Empty, empty_cascaded());
        }
        let all_inline = winners.iter().all(|&id| id < 0);
        if all_inline {
            let cascaded = Arc::clone(inline.winners());
            return (DeclCacheKey::InlineOnly(ArcPtr(inline)), cascaded);
        }
        let all_sheet = winners.iter().all(|&id| id >= 0);
        if all_sheet {
            if let Some(cached) = cache.sheet_mut(sheet_id).cascade_winner_cache.get(&winners) {
                let arc = Arc::clone(cached);
                return (DeclCacheKey::SheetOnly(ArcPtr(Arc::clone(&arc))), arc);
            }
            let cascaded = Arc::new(self.materialize_winners(&winners, None));
            let sheet_cache = cache.sheet_mut(sheet_id);
            if sheet_cache.cascade_winner_cache.len() < MAX_SHARED_COMPUTED_STYLES {
                sheet_cache
                    .cascade_winner_cache
                    .insert(winners, Arc::clone(&cascaded));
            }
            return (
                DeclCacheKey::SheetOnly(ArcPtr(Arc::clone(&cascaded))),
                cascaded,
            );
        }
        let cascaded = Arc::new(self.materialize_winners(&winners, Some(&inline)));
        (
            DeclCacheKey::Mixed {
                inline: ArcPtr(inline),
                winner_ids: winners,
            },
            cascaded,
        )
    }

    fn cascade_pool(
        &mut self,
        pool_id: usize,
        node_id: usize,
        cache: &mut SharedStyleCache,
    ) -> Option<CascadedDeclarations> {
        let sheet_id = self.compiled.id;
        if self.compiled.pools[pool_id].is_empty() || cache.sheet_mut(sheet_id).selector_budget <= 0
        {
            return None;
        }
        let winners = self.match_winners(pool_id, node_id, &[], cache);
        if winners.is_empty() {
            return None;
        }
        Some(self.materialize_winners(&winners, None))
    }

    fn match_winners(
        &mut self,
        pool_id: usize,
        node_id: usize,
        inline_refs: &[DeclarationRef],
        cache: &mut SharedStyleCache,
    ) -> Vec<i32> {
        let sheet_id = self.compiled.id;
        let budget = cache.sheet_mut(sheet_id).selector_budget;
        if budget <= 0 {
            return Vec::new();
        }
        if self.ensure_matcher().is_none() {
            return Vec::new();
        }
        let Some(matcher) = self.matcher.as_mut() else {
            return Vec::new();
        };
        let Some(&match_node_u32) = matcher.doc_to_match.get(node_id) else {
            return Vec::new();
        };
        if match_node_u32 == u32::MAX {
            return Vec::new();
        }
        let match_node = match_node_u32 as usize;
        let (remaining, winners) = matcher.session.cascade(
            pool_id,
            match_node,
            budget,
            &self.compiled.blocks,
            inline_refs,
        );
        cache.sheet_mut(sheet_id).selector_budget = remaining;
        winners
    }

    fn materialize_winners(
        &self,
        winners: &[i32],
        inline: Option<&InlineStyle>,
    ) -> CascadedDeclarations {
        let mut declarations = Vec::with_capacity(winners.len());
        for &id in winners {
            if id < 0 {
                if let Some(inline) = inline {
                    declarations.push(clone_declaration(&inline.declarations[(-1 - id) as usize]));
                }
            } else {
                declarations.push(clone_declaration(&self.compiled.declarations[id as usize]));
            }
        }
        CascadedDeclarations { declarations }
    }

    fn inline_style_for(&self, style: &str, cache: &mut SharedStyleCache) -> Arc<InlineStyle> {
        let sheet_id = self.compiled.id;
        if let Some(existing) = cache.sheet_mut(sheet_id).inline_declarations.get(style) {
            return Arc::clone(existing);
        }
        let compiled_decls = compile_inline_declarations(style);
        let inline = Arc::new(InlineStyle::new(
            compiled_decls,
            &self.compiled.property_ids,
        ));
        let sheet_cache = cache.sheet_mut(sheet_id);
        let utf16_bytes = style.encode_utf16().count().saturating_mul(2);
        let available = MAX_INLINE_CACHE_KEY_BYTES
            .min(MAX_INLINE_CACHE_BYTES.saturating_sub(sheet_cache.inline_cache_key_bytes));
        if sheet_cache.inline_declarations.len() < MAX_INLINE_CACHE_ENTRIES
            && utf16_bytes <= available
        {
            sheet_cache
                .inline_declarations
                .insert(style.to_owned(), Arc::clone(&inline));
            sheet_cache.inline_cache_key_bytes += utf16_bytes;
        }
        inline
    }

    fn publisher_box_style(
        &mut self,
        node_id: usize,
        cache: &mut SharedStyleCache,
    ) -> Option<FrbPublisherBoxStyle> {
        let value = self.computed(node_id, cache);
        self.publisher_box_style_for(node_id, &value, cache)
    }

    fn publisher_box_style_for(
        &mut self,
        node_id: usize,
        value: &ComputedStyle,
        cache: &mut SharedStyleCache,
    ) -> Option<FrbPublisherBoxStyle> {
        let horizontal_align = self.publisher_horizontal_align(node_id, value, cache);
        let rtl = match value.direction.as_deref() {
            Some("rtl") => true,
            Some("auto") => first_strong_rtl_node(self.doc, node_id).unwrap_or(false),
            _ => false,
        };
        build_publisher_box_style(value, rtl, horizontal_align)
    }

    fn publisher_horizontal_align(
        &mut self,
        node_id: usize,
        value: &ComputedStyle,
        cache: &mut SharedStyleCache,
    ) -> FrbPublisherBoxAlign {
        if value.float_side.is_some() {
            return FrbPublisherBoxAlign::Start;
        }
        if value.centered_box {
            return FrbPublisherBoxAlign::Center;
        }
        let Some(parent_id) = self.element_parent(node_id) else {
            return FrbPublisherBoxAlign::Start;
        };
        let tag = self.normalized_tag(node_id);
        let is_inline_replaced = matches!(tag.as_ref(), "img" | "image" | "svg");
        let is_block_display = matches!(
            value.display.as_deref(),
            Some("block" | "flow-root" | "flex" | "grid" | "list-item" | "table")
        );
        let is_block_aligned = (!is_inline_replaced
            && value.display.as_deref() != Some("inline-block"))
            || is_block_display;
        if is_block_aligned
            && !self.compiled.has_flex_centering_rules
            && self.style_attr(parent_id).is_empty()
        {
            return FrbPublisherBoxAlign::Start;
        }
        let container = self.computed(parent_id, cache);
        if container.center_content_vertically && container.align_items.as_deref() == Some("center")
        {
            return FrbPublisherBoxAlign::Center;
        }
        if is_block_aligned {
            return FrbPublisherBoxAlign::Start;
        }
        let container_rtl = match container.direction.as_deref() {
            Some("rtl") => true,
            Some("auto") => first_strong_rtl_node(self.doc, parent_id).unwrap_or(false),
            _ => false,
        };
        match container.text_align.as_deref() {
            Some("center") => FrbPublisherBoxAlign::Center,
            Some("right") => FrbPublisherBoxAlign::End,
            Some("left") => FrbPublisherBoxAlign::Start,
            Some("start") => {
                if container_rtl {
                    FrbPublisherBoxAlign::End
                } else {
                    FrbPublisherBoxAlign::Start
                }
            }
            Some("end") => {
                if container_rtl {
                    FrbPublisherBoxAlign::Start
                } else {
                    FrbPublisherBoxAlign::End
                }
            }
            _ => FrbPublisherBoxAlign::Start,
        }
    }

    fn is_element(&self, node_id: usize) -> bool {
        matches!(
            self.doc.nodes.get(node_id).map(|n| &n.kind),
            Some(Kind::Element { .. })
        )
    }

    fn element_parent(&self, node_id: usize) -> Option<usize> {
        let parent = self.doc.nodes.get(node_id)?.parent?;
        (parent != 0 && self.is_element(parent)).then_some(parent)
    }

    fn normalized_tag(&self, node_id: usize) -> Cow<'_, str> {
        match self.doc.nodes.get(node_id).map(|n| &n.kind) {
            Some(Kind::Element { name, .. }) => {
                let trimmed = name.trim_matches(|ch| ch <= ' ');
                if trimmed.is_ascii() && trimmed.bytes().all(|b| !b.is_ascii_uppercase()) {
                    Cow::Borrowed(trimmed)
                } else {
                    Cow::Owned(trimmed.to_lowercase())
                }
            }
            _ => Cow::Borrowed(""),
        }
    }

    fn attr_flags(&self, node_id: usize) -> u32 {
        self.doc
            .preindex
            .nodes
            .get(node_id)
            .map_or(0, |meta| meta.attr_flags)
    }

    fn style_attr(&self, node_id: usize) -> &str {
        if (self.attr_flags(node_id) & ATTR_HAS_STYLE) == 0 {
            return "";
        }
        self.attr(node_id, "style")
    }

    fn dir_attr(&self, node_id: usize) -> &str {
        if (self.attr_flags(node_id) & ATTR_HAS_DIR) == 0 {
            return "";
        }
        self.attr(node_id, "dir")
    }

    fn has_presentational_attrs(&self, node_id: usize) -> bool {
        (self.attr_flags(node_id) & ATTR_HAS_PRESENTATIONAL) != 0
    }

    fn attr(&self, node_id: usize, name: &str) -> &str {
        let Some(Kind::Element { attrs, .. }) = self.doc.nodes.get(node_id).map(|n| &n.kind) else {
            return "";
        };
        attrs
            .iter()
            .find(|(key, _)| attribute_keys_equal(key, name))
            .map_or("", |(_, value)| value.as_str())
    }
}

/// Per-chapter CSS resolver bound to one shared [`CompiledStylesheets`] and one
/// [`Document`], reusing a per-worker-thread [`SharedStyleCache`] across
/// chapters.
pub(crate) struct ChapterCss<'a> {
    primary: ChapterState<'a>,
    dark: Option<ChapterState<'a>>,
    responsive: HashMap<u32, ChapterState<'a>>,
    cache: CacheSlot<'a>,
}

impl<'a> ChapterCss<'a> {
    /// Creates a chapter resolver reusing a cross-chapter worker cache.
    pub(crate) fn new(
        compiled: Arc<CompiledStylesheets>,
        doc: &'a Document,
        shared_cache: &'a mut SharedStyleCache,
    ) -> Self {
        Self {
            primary: ChapterState::new(compiled, doc),
            dark: None,
            responsive: HashMap::new(),
            cache: CacheSlot::Borrowed(shared_cache),
        }
    }

    /// Creates a standalone chapter resolver with its own local cache.
    pub(crate) fn standalone(compiled: Arc<CompiledStylesheets>, doc: &'a Document) -> Self {
        Self {
            primary: ChapterState::new(compiled, doc),
            dark: None,
            responsive: HashMap::new(),
            cache: CacheSlot::Owned(SharedStyleCache::new()),
        }
    }

    pub(crate) fn compiled(&self) -> &Arc<CompiledStylesheets> {
        &self.primary.compiled
    }

    pub(crate) fn has_display_rules(&self) -> bool {
        self.primary.compiled.has_display_rules
    }

    pub(crate) fn has_page_break_before_rules(&self) -> bool {
        self.primary.compiled.has_page_break_before_rules
    }

    pub(crate) fn has_before_rules(&self) -> bool {
        self.primary.compiled.has_before_rules
    }

    pub(crate) fn has_after_rules(&self) -> bool {
        self.primary.compiled.has_after_rules
    }

    pub(crate) fn has_generated_rules(&self) -> bool {
        self.primary.compiled.has_generated_rules()
    }

    pub(crate) fn has_first_letter_rules(&self) -> bool {
        self.primary.compiled.has_first_letter_rules
    }

    pub(crate) fn has_responsive_breakpoints(&self) -> bool {
        self.primary.compiled.has_responsive_breakpoints
    }

    pub(crate) fn remaining_selector_budget(&self) -> i32 {
        self.cache
            .as_ref()
            .sheet_ref(self.primary.compiled.id)
            .map_or(MAX_SELECTOR_MATCH_OPERATIONS, |s| s.selector_budget)
    }

    pub(crate) fn selector_budget_exhausted(&self) -> bool {
        self.remaining_selector_budget() <= 0
    }

    /// Computed style of `node_id`; walks and caches the ancestor chain.
    pub(crate) fn computed(&mut self, node_id: usize) -> Arc<ComputedStyle> {
        self.primary.computed(node_id, self.cache.as_mut())
    }

    /// Own CSS box decoration of `node_id` in renderer-ready physical axes, or
    /// `None` when the element contributes no retained native box styling.
    pub(crate) fn publisher_box_style(&mut self, node_id: usize) -> Option<FrbPublisherBoxStyle> {
        self.primary
            .publisher_box_style(node_id, self.cache.as_mut())
    }

    /// The `::first-letter` style targeting `node_id`, or `None`.
    pub(crate) fn first_letter(&mut self, node_id: usize) -> Option<FrbFirstLetterStyle> {
        if self.primary.compiled.pools[POOL_FIRST_LETTER].is_empty() {
            return None;
        }
        let cache = self.cache.as_mut();
        let merged = self
            .primary
            .cascade_pool(POOL_FIRST_LETTER, node_id, cache)?;

        let mut scale = 1.0_f32;
        let mut font_weight: Option<i32> = None;
        let mut italic: Option<bool> = None;
        let mut float_side: Option<String> = None;
        let mut family: Option<String> = None;

        let inherited = self.primary.computed(node_id, cache);
        let pseudo =
            self.primary
                .resolve_node(node_id, Some(&inherited), &merged.declarations, true, cache);
        let typography = font_typography_of(&pseudo);
        let inherited_typography = font_typography_of(&inherited);
        let mut direction = inherited.direction.clone();
        let foreground = merged
            .get("color")
            .and_then(|d| resolve_foreground(&d.value, inherited.foreground_color_argb))
            .or(inherited.foreground_color_argb);
        let background = merged.get("background-color").and_then(|d| {
            resolve_background(&d.value, inherited.background_color_argb, foreground)
        });

        for decl in &merged.declarations {
            let value = decl.value.trim_matches(reader_whitespace).to_lowercase();
            match decl.property.as_str() {
                "font-size" => {
                    if let Some(parsed) = css_lengths::font_size(&value, 1.0) {
                        scale = parsed;
                    }
                }
                "font-weight" => {
                    if let Some(parsed) = css_computed_fonts::font_weight(
                        &value,
                        inherited.font_weight,
                        inherited.font_weight.unwrap_or(400),
                    ) {
                        font_weight = Some(parsed);
                    }
                }
                "font-style" => {
                    if value.starts_with("italic") || value.starts_with("oblique") {
                        italic = Some(true);
                    } else if matches!(value.as_str(), "normal" | "initial") {
                        italic = Some(false);
                    } else if matches!(
                        value.as_str(),
                        "inherit" | "unset" | "revert" | "revert-layer"
                    ) {
                        italic = Some(inherited.italic.unwrap_or(false));
                    }
                }
                "float" => {
                    if matches!(value.as_str(), "left" | "right") {
                        float_side = Some(value);
                    }
                }
                "font-family" => {
                    if let Some(parsed) = first_family_name(&value) {
                        family = Some(parsed);
                    }
                }
                "direction" => {
                    if matches!(value.as_str(), "ltr" | "rtl") {
                        direction = Some(value);
                    }
                }
                _ => {}
            }
        }

        let is_drop_cap = float_side.is_some() || scale >= 1.5;
        let plain = !is_drop_cap
            && (0.99..=1.01).contains(&scale)
            && font_weight.is_none()
            && italic.is_none()
            && family.is_none()
            && foreground == inherited.foreground_color_argb
            && background.is_none()
            && typography == inherited_typography
            && pseudo.font_feature_settings == inherited.font_feature_settings;
        if plain {
            return None;
        }

        let resolved_weight = font_weight.or(inherited.font_weight);
        Some(FrbFirstLetterStyle {
            scale: scale.clamp(1.0, 4.0),
            bold: resolved_weight.map(|w| w >= 600),
            italic: italic.or(inherited.italic),
            is_drop_cap,
            font_family: family,
            foreground_color_argb: foreground
                .filter(|&c| Some(c) != inherited.foreground_color_argb),
            background_color_argb: background,
            left_side: float_side.as_deref() != Some("right"),
            direction: parse_book_direction(direction.as_deref()),
            language: None,
            source_text_length: None,
            font_weight: resolved_weight,
            font_typography: Some(typography),
            font_feature_settings: pseudo.font_feature_settings.or_else(|| {
                inherited
                    .font_feature_settings
                    .as_ref()
                    .map(|_| String::new())
            }),
        })
    }

    /// Converts a real leading floated text element into `FrbFirstLetterStyle`
    /// (KF8/EPUB `<span style="float:left">K</span>` pattern).
    pub(crate) fn floated_text_initial(
        &mut self,
        elem_id: usize,
        paragraph_id: usize,
        source_text_len: i32,
    ) -> Option<FrbFirstLetterStyle> {
        let cache = self.cache.as_mut();
        let own = self.primary.computed(elem_id, cache);
        let side = own.float_side.as_deref()?;
        let base = self.primary.computed(paragraph_id, cache);
        let base_size = base.font_size_em.max(0.1);

        let (_, cascaded) = self.primary.cascaded_for(elem_id, cache);
        let declared_absolute = cascaded.get("font-size").and_then(|decl| {
            let value = resolved_declaration_value(decl, &own.custom_props)?;
            match value.as_str() {
                "inherit" | "unset" => Some(base_size),
                "initial" => Some(1.0),
                other => css_lengths::font_size(other, base_size),
            }
        });
        let scale = (declared_absolute.unwrap_or(own.font_size_em) / base_size).clamp(1.0, 4.0);
        Some(FrbFirstLetterStyle {
            scale,
            bold: computed_bold(&own),
            italic: own.italic,
            is_drop_cap: true,
            font_family: own.font_family_name.clone(),
            foreground_color_argb: own
                .foreground_color_argb
                .filter(|&c| Some(c) != base.foreground_color_argb),
            background_color_argb: own.background_color_argb,
            left_side: side != "right",
            direction: parse_book_direction(own.direction.as_deref()),
            language: None,
            source_text_length: Some(source_text_len),
            font_weight: own.font_weight,
            font_typography: Some(font_typography_of(&own)),
            font_feature_settings: own
                .font_feature_settings
                .clone()
                .or_else(|| base.font_feature_settings.as_ref().map(|_| String::new())),
        })
    }

    /// Static generated content for `node_id` (`::after` when `after` is true,
    /// `::before` otherwise).
    pub(crate) fn generated(&mut self, node_id: usize, after: bool) -> Option<GeneratedRun> {
        generated_in_state(&mut self.primary, node_id, after, self.cache.as_mut())
    }

    /// Dark-appearance computed style for images (`@media (prefers-color-scheme: dark)`).
    pub(crate) fn dark_image_style(&mut self, node_id: usize) -> Arc<ComputedStyle> {
        if self
            .primary
            .compiled
            .viewport
            .is_some_and(|v| v.dark == Some(true))
            || (self.primary.compiled.viewport.is_none()
                && !self.primary.compiled.has_prefers_color_scheme_media)
        {
            return self.primary.computed(node_id, self.cache.as_mut());
        }
        let doc = self.primary.doc;
        let dark_state = self.dark.get_or_insert_with(|| {
            let dark_compiled = self.primary.compiled.dark_variant();
            ChapterState::new(dark_compiled, doc)
        });
        dark_state.computed(node_id, self.cache.as_mut())
    }

    /// Visible background contributed by `node_id` and its wrapper ancestors up
    /// to (excluding) `stop_exclusive`.
    pub(crate) fn visual_background(
        &mut self,
        node_id: usize,
        stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        visual_background_in_state(
            &mut self.primary,
            node_id,
            stop_exclusive,
            self.cache.as_mut(),
        )
    }

    /// Authored simple `max-width` breakpoints in descending CSS pixel order.
    pub(crate) fn max_width_breakpoints_css_px(&self) -> Vec<f32> {
        self.primary.compiled.max_width_breakpoints_css_px()
    }

    /// Ensures the lazily compiled responsive state for `width_css_px` exists
    /// and reports whether `width_css_px` is a known breakpoint.
    pub(crate) fn has_responsive_at_max_width(&mut self, width_css_px: f32) -> bool {
        let bits = width_css_px.to_bits();
        if self.responsive.contains_key(&bits) {
            return true;
        }
        let Some(compiled) = self.primary.compiled.variant_at_max_width(width_css_px) else {
            return false;
        };
        let doc = self.primary.doc;
        self.responsive
            .insert(bits, ChapterState::new(compiled, doc));
        true
    }

    pub(crate) fn responsive_computed(
        &mut self,
        width_css_px: f32,
        node_id: usize,
    ) -> Option<Arc<ComputedStyle>> {
        if !self.has_responsive_at_max_width(width_css_px) {
            return None;
        }
        let state = self.responsive.get_mut(&width_css_px.to_bits())?;
        Some(state.computed(node_id, self.cache.as_mut()))
    }

    pub(crate) fn responsive_publisher_box_style(
        &mut self,
        width_css_px: f32,
        node_id: usize,
    ) -> Option<FrbPublisherBoxStyle> {
        if !self.has_responsive_at_max_width(width_css_px) {
            return None;
        }
        let state = self.responsive.get_mut(&width_css_px.to_bits())?;
        state.publisher_box_style(node_id, self.cache.as_mut())
    }

    pub(crate) fn responsive_generated(
        &mut self,
        width_css_px: f32,
        node_id: usize,
        after: bool,
    ) -> Option<GeneratedRun> {
        if !self.has_responsive_at_max_width(width_css_px) {
            return None;
        }
        let state = self.responsive.get_mut(&width_css_px.to_bits())?;
        generated_in_state(state, node_id, after, self.cache.as_mut())
    }

    pub(crate) fn responsive_visual_background(
        &mut self,
        width_css_px: f32,
        node_id: usize,
        stop_exclusive: Option<usize>,
    ) -> Option<i32> {
        if !self.has_responsive_at_max_width(width_css_px) {
            return None;
        }
        let state = self.responsive.get_mut(&width_css_px.to_bits())?;
        visual_background_in_state(state, node_id, stop_exclusive, self.cache.as_mut())
    }
}

fn generated_in_state(
    state: &mut ChapterState<'_>,
    node_id: usize,
    after: bool,
    cache: &mut SharedStyleCache,
) -> Option<GeneratedRun> {
    let pool_id = if after { POOL_AFTER } else { POOL_BEFORE };
    if state.compiled.pools[pool_id].is_empty() {
        return None;
    }
    let merged = state.cascade_pool(pool_id, node_id, cache)?;
    let text = merged
        .get("content")
        .and_then(|d| parse_content_value(&d.value))?;
    let inherited = state.computed(node_id, cache);
    let value = state.resolve_node(node_id, Some(&inherited), &merged.declarations, true, cache);
    let publisher_box = state.publisher_box_style_for(node_id, &value, cache);
    let value_bold = computed_bold(&value);
    let inherited_bold = computed_bold(&inherited);
    Some(GeneratedRun {
        text,
        italic: value.italic.filter(|&v| Some(v) != inherited.italic),
        bold: value_bold.filter(|&v| Some(v) != inherited_bold),
        font_weight: value
            .font_weight
            .filter(|&v| Some(v) != inherited.font_weight),
        scale: (value.font_size_em / inherited.font_size_em).clamp(0.5, 2.5),
        foreground_color_argb: value
            .foreground_color_argb
            .filter(|&c| Some(c) != inherited.foreground_color_argb),
        background_color_argb: value.background_color_argb,
        font_feature_settings: value.font_feature_settings.clone().or_else(|| {
            inherited
                .font_feature_settings
                .as_ref()
                .map(|_| String::new())
        }),
        text_transform: value.text_transform.clone(),
        visibility_hidden: value.visibility_hidden,
        publisher_box,
        font_family_name: if value.monospace {
            Some("monospace".into())
        } else {
            value.font_family_name.clone()
        },
        font_typography: font_typography_of(&value),
    })
}

fn visual_background_in_state(
    state: &mut ChapterState<'_>,
    node_id: usize,
    stop_exclusive: Option<usize>,
    cache: &mut SharedStyleCache,
) -> Option<i32> {
    let mut layers = Vec::with_capacity(4);
    let mut cursor = Some(node_id);
    while let Some(current) = cursor {
        if Some(current) == stop_exclusive || current == 0 || !state.is_element(current) {
            break;
        }
        if let Some(bg) = state.computed(current, cache).background_color_argb {
            layers.push(bg);
        }
        cursor = state.doc.nodes[current].parent;
    }
    let mut result = 0_i32;
    for &layer in layers.iter().rev() {
        result = composite_argb(layer, result);
    }
    (((result as u32) >> 24) != 0).then_some(result)
}

impl Drop for ChapterCss<'_> {
    fn drop(&mut self) {
        let primary_id = self.primary.compiled.id;
        let dark_id = self.dark.as_ref().map(|d| d.compiled.id);
        let responsive_ids: Vec<u64> = self
            .responsive
            .values()
            .map(|state| state.compiled.id)
            .collect();
        let cache = self.cache.as_mut();
        if let Some(sheet) = cache.sheets.get_mut(&primary_id) {
            sheet.sweep_chapter_end();
        }
        if let Some(dark_id) = dark_id {
            if let Some(sheet) = cache.sheets.get_mut(&dark_id) {
                sheet.sweep_chapter_end();
            }
        }
        for resp_id in responsive_ids {
            if let Some(sheet) = cache.sheets.get_mut(&resp_id) {
                sheet.sweep_chapter_end();
            }
        }
    }
}

// --------------------------------------------------------------------------- Public Helpers for HtmlMapper

#[inline]
pub(crate) fn computed_bold(style: &ComputedStyle) -> Option<bool> {
    style.font_weight.map(|w| w >= 600)
}

pub(crate) fn font_typography_of(style: &ComputedStyle) -> FrbFontTypography {
    FrbFontTypography {
        variation_axes: style
            .variation_axes
            .as_ref()
            .map(|axes| axes.iter().map(|(k, v)| (k.clone(), *v)).collect()),
        stretch_percent: style.stretch_percent,
        optical_sizing: style.optical_sizing,
    }
}

/// Encodes `FrbFontTypography` into the exact JSON format produced by
/// Kotlin's `FontTypography.encode()` (stored in `INLINE_FONT_TYPOGRAPHY_TAG`).
pub(crate) fn encode_font_typography(typo: &FrbFontTypography) -> String {
    let mut out = String::from("{");
    let mut need_comma = false;
    if let Some(axes) = &typo.variation_axes {
        out.push_str("\"axes\":{");
        let mut axis_comma = false;
        for (tag, &value) in axes {
            if valid_font_axis(tag, value) {
                if axis_comma {
                    out.push(',');
                }
                axis_comma = true;
                push_json_string(&mut out, tag);
                out.push(':');
                out.push_str(&format_kotlin_float(value));
            }
        }
        out.push('}');
        need_comma = true;
    }
    if let Some(stretch) = typo.stretch_percent.filter(|&v| v.is_finite() && v > 0.0) {
        if need_comma {
            out.push(',');
        }
        out.push_str("\"stretch\":");
        out.push_str(&format_kotlin_float(stretch));
        need_comma = true;
    }
    if let Some(optical) = typo.optical_sizing {
        if need_comma {
            out.push(',');
        }
        out.push_str("\"optical\":");
        out.push_str(if optical { "true" } else { "false" });
    }
    out.push('}');
    out
}

pub(crate) fn parse_book_direction(direction: Option<&str>) -> Option<FrbBookTextDirection> {
    match direction? {
        "ltr" => Some(FrbBookTextDirection::Ltr),
        "rtl" => Some(FrbBookTextDirection::Rtl),
        "auto" => Some(FrbBookTextDirection::Auto),
        _ => None,
    }
}

/// First-strong directionality of a string (`Some(true)` = RTL/AL, `Some(false)` = LTR, `None` = neutral).
pub(crate) fn first_strong_rtl_str(text: &str) -> Option<bool> {
    for ch in text.chars() {
        if let Some(rtl) = char_strong_rtl(ch) {
            return Some(rtl);
        }
    }
    None
}

/// First-strong directionality of a `chapter_markup::Document` node's text
/// content in document order, without allocating a concatenated string.
pub(crate) fn first_strong_rtl_node(doc: &Document, node_id: usize) -> Option<bool> {
    let mut stack = vec![node_id];
    let mut visited = 0usize;
    let limit = doc.nodes.len();
    while let Some(current) = stack.pop() {
        visited += 1;
        if visited > limit {
            break;
        }
        let Some(node) = doc.nodes.get(current) else {
            continue;
        };
        match &node.kind {
            Kind::Text(text) | Kind::Cdata(text) => {
                if let Some(rtl) = first_strong_rtl_str(text) {
                    return Some(rtl);
                }
            }
            Kind::Element { .. } | Kind::Document => {
                // Push children in reverse so leftmost child is popped first.
                let start = stack.len();
                let mut child = node.first_child;
                while let Some(c) = child {
                    stack.push(c);
                    child = doc.nodes.get(c).and_then(|n| n.next_sibling);
                }
                stack[start..].reverse();
            }
            _ => {}
        }
    }
    None
}

/// Canonical `unicode-range` descriptor normalization matching `FontUnicodeRange.kt`.
pub(crate) fn normalize_font_unicode_range(value: Option<&str>) -> Option<String> {
    let ranges = parse_font_unicode_ranges(value?)?;
    if ranges.len() == 1 && ranges[0] == (0, 0x10_FFFF) {
        return None;
    }
    let mut out = String::new();
    for (idx, &(first, last)) in ranges.iter().enumerate() {
        if idx > 0 {
            out.push(',');
        }
        if first == last {
            out.push_str(&format!("U+{first:X}"));
        } else {
            out.push_str(&format!("U+{first:X}-{last:X}"));
        }
    }
    Some(out)
}

// --------------------------------------------------------------------------- Stylesheet Compilation

enum SyntaxRule {
    Qualified {
        selector: String,
        declarations: Vec<css_shorthand::RawDeclaration>,
    },
    FontFace {
        declarations: Vec<css_shorthand::RawDeclaration>,
    },
    Media {
        condition: String,
        rules: Vec<SyntaxRule>,
    },
    Supports {
        condition: String,
        rules: Vec<SyntaxRule>,
    },
    Namespace {
        prelude: String,
    },
}

struct WireReader<'a>(&'a [u8]);

impl<'a> WireReader<'a> {
    fn take(&mut self, len: usize) -> Option<&'a [u8]> {
        let (head, tail) = self.0.split_at_checked(len)?;
        self.0 = tail;
        Some(head)
    }

    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }

    fn u32(&mut self) -> Option<u32> {
        let bytes = self.take(4)?;
        Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn string(&mut self) -> Option<String> {
        let len = self.u32()? as usize;
        let bytes = self.take(len)?;
        std::str::from_utf8(bytes).ok().map(str::to_owned)
    }

    fn declarations(&mut self) -> Option<Vec<css_shorthand::RawDeclaration>> {
        let count = self.u32()? as usize;
        if count > MAX_DECLARATIONS_PER_BLOCK {
            return None;
        }
        let mut out = Vec::with_capacity(count);
        for _ in 0..count {
            let property = self.string()?;
            let value = self.string()?;
            let important = match self.u8()? {
                0 => false,
                1 => true,
                _ => return None,
            };
            out.push(css_shorthand::RawDeclaration {
                property,
                value,
                important,
            });
        }
        Some(out)
    }

    fn rules(&mut self, depth: usize) -> Option<Vec<SyntaxRule>> {
        if depth > MAX_GROUP_RULE_DEPTH {
            return None;
        }
        let count = self.u32()? as usize;
        let mut out = Vec::with_capacity(count.min(256));
        for _ in 0..count {
            let rule = match self.u8()? {
                1 => SyntaxRule::Qualified {
                    selector: self.string()?,
                    declarations: self.declarations()?,
                },
                2 => SyntaxRule::FontFace {
                    declarations: self.declarations()?,
                },
                3 => SyntaxRule::Media {
                    condition: self.string()?,
                    rules: self.rules(depth + 1)?,
                },
                4 => SyntaxRule::Supports {
                    condition: self.string()?,
                    rules: self.rules(depth + 1)?,
                },
                5 => SyntaxRule::Namespace {
                    prelude: self.string()?,
                },
                _ => return None,
            };
            out.push(rule);
        }
        Some(out)
    }
}

fn decode_sheet_rules(bytes: &[u8]) -> Option<Vec<SyntaxRule>> {
    let mut reader = WireReader(bytes);
    if reader.u32()? != SHEET_MAGIC {
        return None;
    }
    let rules = reader.rules(0)?;
    reader.0.is_empty().then_some(rules)
}

fn decode_inline_declarations(bytes: &[u8]) -> Option<Vec<css_shorthand::RawDeclaration>> {
    let mut reader = WireReader(bytes);
    if reader.u32()? != INLINE_MAGIC {
        return None;
    }
    let decls = reader.declarations()?;
    reader.0.is_empty().then_some(decls)
}

fn compile_inline_declarations(style: &str) -> Vec<(String, String, bool, i32)> {
    if style.len() > MAX_INPUT_BYTES || style.chars().all(reader_whitespace) {
        return Vec::new();
    }
    let Some(bytes) = css_declarations::inline_bytes(style) else {
        return Vec::new();
    };
    let Some(raw) = decode_inline_declarations(&bytes) else {
        return Vec::new();
    };
    if raw.is_empty() {
        return Vec::new();
    }
    let slice = &raw[..raw.len().min(MAX_DECLARATIONS_PER_BLOCK)];
    let (_, compiled) = css_shorthand::compile(slice);
    compiled
        .into_iter()
        .map(|d| (d.property, d.value.into_owned(), d.important, d.expansion))
        .collect()
}

struct StylesheetCompiler {
    viewport: Option<Viewport>,
    parsing_namespaces: HashMap<String, String>,
    pools: [Vec<MatchRule>; POOL_COUNT],
    bucket_sizes: [HashMap<String, usize>; POOL_COUNT],
    blocks: Vec<Vec<DeclarationRef>>,
    declarations: Vec<Declaration>,
    property_ids: HashMap<String, i32>,
    absolute_keys: BTreeSet<String>,
    font_faces: Vec<FontFaceSpec>,
    font_face_keys: HashSet<String>,
    simple_max_width_breakpoints: Vec<f32>,
    order_counter: i32,
    rule_count: usize,
    stylesheet_declaration_count: usize,
    next_has_id: usize,
}

impl StylesheetCompiler {
    fn new(viewport: Option<Viewport>) -> Self {
        Self {
            viewport,
            parsing_namespaces: HashMap::new(),
            pools: std::array::from_fn(|_| Vec::new()),
            bucket_sizes: std::array::from_fn(|_| HashMap::new()),
            blocks: Vec::new(),
            declarations: Vec::new(),
            property_ids: HashMap::new(),
            absolute_keys: BTreeSet::new(),
            font_faces: Vec::new(),
            font_face_keys: HashSet::new(),
            simple_max_width_breakpoints: Vec::new(),
            order_counter: 0,
            rule_count: 0,
            stylesheet_declaration_count: 0,
            next_has_id: 0,
        }
    }

    fn can_accept_rules(&self) -> bool {
        self.rule_count < MAX_RULES
            && self.stylesheet_declaration_count < MAX_STYLESHEET_DECLARATIONS
    }

    fn add_sheet_rules(&mut self, rules: &[SyntaxRule], base_dir: &str, group_depth: usize) {
        if group_depth > MAX_GROUP_RULE_DEPTH {
            return;
        }
        for rule in rules {
            if !self.can_accept_rules() {
                return;
            }
            match rule {
                SyntaxRule::Namespace { prelude } => {
                    if let Some((prefix, uri)) = parse_namespace_rule(prelude) {
                        self.parsing_namespaces.insert(prefix, uri);
                    }
                }
                SyntaxRule::Media { condition, rules } => {
                    if self.simple_max_width_breakpoints.len() < MAX_RESPONSIVE_BREAKPOINTS {
                        if let Some(bp) = simple_max_width_breakpoint(condition) {
                            if !self.simple_max_width_breakpoints.contains(&bp) {
                                self.simple_max_width_breakpoints.push(bp);
                            }
                        }
                    }
                    if screen_media_applies(condition, self.viewport) {
                        self.add_sheet_rules(rules, base_dir, group_depth + 1);
                    }
                }
                SyntaxRule::Supports { condition, rules } => {
                    let lower = condition.trim_matches(reader_whitespace).to_lowercase();
                    if supports_condition_applies(&lower) {
                        self.add_sheet_rules(rules, base_dir, group_depth + 1);
                    }
                }
                SyntaxRule::FontFace { declarations } => {
                    // Every @font-face counts even if the face cap was reached.
                    self.rule_count += 1;
                    if self.font_faces.len() < MAX_FONT_FACES {
                        let compiled = self.compile_stylesheet_declarations(declarations);
                        self.parse_font_face(&compiled, base_dir);
                    }
                }
                SyntaxRule::Qualified {
                    selector,
                    declarations,
                } => {
                    self.add_qualified_rule(selector, declarations);
                }
            }
        }
    }

    fn compile_stylesheet_declarations(
        &mut self,
        source: &[css_shorthand::RawDeclaration],
    ) -> Vec<(String, String, bool, i32)> {
        let limit = MAX_DECLARATIONS_PER_BLOCK
            .min(MAX_STYLESHEET_DECLARATIONS.saturating_sub(self.stylesheet_declaration_count));
        let count = source.len().min(limit);
        if count == 0 {
            return Vec::new();
        }
        let (consumed, compiled) = css_shorthand::compile(&source[..count]);
        self.stylesheet_declaration_count += consumed;
        compiled
            .into_iter()
            .map(|d| (d.property, d.value.into_owned(), d.important, d.expansion))
            .collect()
    }

    fn add_qualified_rule(
        &mut self,
        selector_text: &str,
        raw_declarations: &[css_shorthand::RawDeclaration],
    ) {
        let compiled_decls = self.compile_stylesheet_declarations(raw_declarations);
        if compiled_decls.is_empty() {
            return;
        }
        let remaining = MAX_RULES.saturating_sub(self.rule_count);
        let selectors = css_selectors::compile(selector_text, &self.parsing_namespaces, remaining);
        let mut block_id: Option<i32> = None;

        for mut selector in selectors {
            let order = self.order_counter;
            self.order_counter += 1;
            self.rule_count += 1;

            let pool_id = selector.pool as usize;
            if pool_id >= POOL_COUNT || selector.chain.is_empty() {
                continue;
            }
            let bucket_key = selector_bucket_key(&selector.chain.last().expect("non-empty").simple);
            let cap = if bucket_key.is_empty() {
                MAX_UNIVERSAL_RULES
            } else {
                MAX_RULES_PER_INDEX_BUCKET
            };
            let bucket_count = self.bucket_sizes[pool_id]
                .get(&bucket_key)
                .copied()
                .unwrap_or(0);
            if bucket_count >= cap {
                continue;
            }
            self.bucket_sizes[pool_id].insert(bucket_key, bucket_count + 1);

            let resolved_block_id = *block_id.get_or_insert_with(|| {
                let id = self.blocks.len() as i32;
                let mut refs = Vec::with_capacity(compiled_decls.len());
                for (property, value, important, expansion) in &compiled_decls {
                    let next_prop = self.property_ids.len() as i32;
                    let prop_id = *self
                        .property_ids
                        .entry(property.clone())
                        .or_insert(next_prop);
                    let decl_id = self.declarations.len() as i32;
                    self.declarations.push(Declaration {
                        property: property.clone(),
                        value: value.clone(),
                        expansion: *expansion,
                    });
                    refs.push(DeclarationRef {
                        property: prop_id,
                        id: decl_id,
                        important: *important,
                    });
                }
                self.blocks.push(refs);
                id
            });

            assign_has_ids(&mut selector.chain, &mut self.next_has_id);
            for compound in &selector.chain {
                collect_absolute_keys(&compound.simple, &mut self.absolute_keys);
            }

            self.pools[pool_id].push(MatchRule {
                id: resolved_block_id,
                specificity: selector.specificity,
                order,
                chain: selector.chain,
            });
        }
    }

    fn parse_font_face(&mut self, declarations: &[(String, String, bool, i32)], base_dir: &str) {
        let lookup = |name: &str| {
            declarations
                .iter()
                .rev()
                .find(|(prop, _, _, _)| prop == name)
                .map(|(_, val, _, _)| val.as_str())
        };
        let Some(family) = lookup("font-family").and_then(first_family_name) else {
            return;
        };
        let Some(src) = lookup("src") else { return };
        let Some(url) = find_font_url(src) else {
            return;
        };
        let weights: Vec<i32> = match lookup("font-weight") {
            Some(raw) => raw
                .trim_matches(reader_whitespace)
                .to_lowercase()
                .split(ascii_regex_whitespace)
                .filter_map(|token| css_computed_fonts::font_weight(token, Some(400), 400))
                .collect(),
            None => vec![400],
        };
        let weight_min = weights.iter().copied().min().unwrap_or(400);
        let weight_max = weights.iter().copied().max().unwrap_or(weight_min);
        let weight = 400.clamp(weight_min, weight_max);
        let style = lookup("font-style").map(|s| s.trim_matches(reader_whitespace).to_lowercase());
        let bold = weight >= 600;
        let italic = style
            .as_deref()
            .is_some_and(|s| s.starts_with("italic") || s.starts_with("oblique"));

        let widths: Vec<f32> = lookup("font-stretch")
            .map(|raw| {
                let lowered = raw.trim_matches(reader_whitespace).to_lowercase();
                let tokens: Vec<&str> = split_regex_whitespace(&lowered);
                if (1..=2).contains(&tokens.len()) {
                    let parsed: Option<Vec<f32>> = tokens
                        .into_iter()
                        .map(css_computed_fonts::parse_font_stretch)
                        .collect();
                    parsed.unwrap_or_default()
                } else {
                    Vec::new()
                }
            })
            .unwrap_or_default();
        let stretch_min = widths.iter().copied().reduce(f32::min).unwrap_or(100.0);
        let stretch_max = widths
            .iter()
            .copied()
            .reduce(f32::max)
            .unwrap_or(stretch_min);
        let unicode_range = normalize_font_unicode_range(lookup("unicode-range"));

        let key = format!(
            "{}\0{}\0{}\0{}\0{}\0{}\0{}\0{}\0{}",
            family.to_lowercase(),
            url,
            base_dir,
            weight_min,
            weight_max,
            italic,
            stretch_min.to_bits(),
            stretch_max.to_bits(),
            unicode_range.as_deref().unwrap_or(""),
        );
        if !self.font_face_keys.insert(key) || self.font_faces.len() >= MAX_FONT_FACES {
            return;
        }
        self.font_faces.push(FontFaceSpec {
            family,
            src: url.to_owned(),
            base_dir: base_dir.to_owned(),
            bold,
            italic,
            weight,
            weight_min,
            weight_max,
            stretch_min,
            stretch_max,
            unicode_range,
        });
    }

    fn finish(
        self,
        source_sheets: &[SheetInput],
        has_prefers_color_scheme_media: bool,
    ) -> Arc<CompiledStylesheets> {
        let mut has_display_rules = false;
        let mut has_page_break_before_rules = false;
        let mut has_flex_centering_rules = false;

        let mut seen_blocks = HashSet::new();
        for rule in &self.pools[POOL_NORMAL] {
            if !seen_blocks.insert(rule.id) {
                continue;
            }
            for decl_ref in &self.blocks[rule.id as usize] {
                let prop = self.declarations[decl_ref.id as usize].property.as_str();
                if !has_display_rules && (prop == "display" || prop.starts_with("--")) {
                    has_display_rules = true;
                }
                if !has_page_break_before_rules
                    && (prop == "page-break-before"
                        || prop == "break-before"
                        || prop.starts_with("--"))
                {
                    has_page_break_before_rules = true;
                }
                if !has_flex_centering_rules && (prop == "align-items" || prop.starts_with("--")) {
                    has_flex_centering_rules = true;
                }
                if has_display_rules && has_page_break_before_rules && has_flex_centering_rules {
                    break;
                }
            }
            if has_display_rules && has_page_break_before_rules && has_flex_centering_rules {
                break;
            }
        }

        let has_first_letter_rules = !self.pools[POOL_FIRST_LETTER].is_empty();
        let has_before_rules = !self.pools[POOL_BEFORE].is_empty();
        let has_after_rules = !self.pools[POOL_AFTER].is_empty();
        let has_responsive_breakpoints = !self.simple_max_width_breakpoints.is_empty();

        // Only retain raw source CSS when a dark or responsive variant may be
        // lazily compiled later; ordinary books avoid duplicating raw CSS text.
        let retained_sheets = if has_prefers_color_scheme_media
            || has_responsive_breakpoints
            || self.viewport.is_some()
        {
            source_sheets.to_vec()
        } else {
            Vec::new()
        };

        Arc::new(CompiledStylesheets {
            id: NEXT_STYLESHEET_ID.fetch_add(1, Ordering::Relaxed),
            viewport: self.viewport,
            pools: self.pools,
            blocks: self.blocks,
            declarations: self.declarations,
            property_ids: self.property_ids,
            absolute_keys: self.absolute_keys.into_iter().collect(),
            font_faces: self.font_faces,
            simple_max_width_breakpoints: self.simple_max_width_breakpoints,
            has_display_rules,
            has_page_break_before_rules,
            has_flex_centering_rules,
            has_before_rules,
            has_after_rules,
            has_first_letter_rules,
            has_responsive_breakpoints,
            has_prefers_color_scheme_media,
            source_sheets: retained_sheets,
            dark_variant: OnceLock::new(),
            responsive_variants: Mutex::new(HashMap::new()),
        })
    }
}

// --------------------------------------------------------------------------- Document -> css_match::Node

fn build_match_nodes(
    document: &Document,
    absolute_keys: &[String],
) -> Option<(Vec<MatchNode>, Vec<u32>)> {
    let root = document.nodes.first()?;
    let mut doc_to_match = vec![u32::MAX; document.nodes.len()];
    doc_to_match[0] = 0;

    let mut nodes = vec![MatchNode {
        tag: "#root".into(),
        id: String::new(),
        classes: Vec::new(),
        attrs: Vec::new(),
        absolute_attrs: Vec::new(),
        parent: None,
        previous: None,
        next: None,
        first_child: None,
    }];
    let mut pending: Vec<(usize, usize, Option<usize>)> = Vec::new();
    if let Some(child) = root.first_child {
        pending.push((child, 0, None));
    }

    let mut visited = 0usize;
    while let Some((doc_idx, parent, previous)) = pending.pop() {
        let source = document.nodes.get(doc_idx)?;
        visited += 1;
        if visited >= document.nodes.len() {
            return None;
        }
        let Kind::Element { name, attrs, .. } = &source.kind else {
            if let Some(next) = source.next_sibling {
                pending.push((next, parent, previous));
            }
            continue;
        };

        let id = nodes.len();
        doc_to_match[doc_idx] = id as u32;
        if let Some(previous) = previous {
            nodes[previous].next = Some(id);
        } else {
            nodes[parent].first_child = Some(id);
        }

        let mut seen = HashSet::new();
        let classes = chapter_attr(attrs, "class")
            .split([' ', '\t', '\r', '\n', '\u{000c}'])
            .filter(|class| !class.is_empty() && seen.insert(*class))
            .map(str::to_owned)
            .collect();
        let filtered_attrs: Vec<(String, String)> = attrs
            .iter()
            .filter(|(key, _)| !(key.len() > 1 && key.starts_with('/')))
            .cloned()
            .collect();
        let absolute_attrs = if absolute_keys.is_empty() {
            Vec::new()
        } else {
            resolve_absolute_attrs(&filtered_attrs, absolute_keys)
        };

        nodes.push(MatchNode {
            tag: name.trim_matches(|ch| ch <= ' ').to_lowercase(),
            id: chapter_attr(attrs, "id").to_owned(),
            classes,
            attrs: filtered_attrs,
            absolute_attrs,
            parent: Some(parent),
            previous,
            next: None,
            first_child: None,
        });

        if let Some(next) = source.next_sibling {
            pending.push((next, parent, Some(id)));
        }
        if let Some(child) = source.first_child {
            pending.push((child, id, None));
        }
    }
    Some((nodes, doc_to_match))
}

fn chapter_attr<'a>(attrs: &'a [(String, String)], name: &str) -> &'a str {
    attrs
        .iter()
        .find(|(key, _)| {
            key.eq_ignore_ascii_case(name)
                || (!key.is_ascii()
                    && key
                        .chars()
                        .map(|ch| match ch {
                            '\u{0130}' | '\u{0131}' => 'i',
                            '\u{017f}' => 's',
                            _ => ch.to_ascii_lowercase(),
                        })
                        .eq(name.chars()))
        })
        .map_or("", |(_, value)| value.as_str())
}

fn resolve_absolute_attrs(
    attrs: &[(String, String)],
    absolute_keys: &[String],
) -> Vec<(String, String)> {
    let mut result = Vec::new();
    for key in absolute_keys {
        if let Some((_, val)) = attrs.iter().find(|(k, _)| attribute_keys_equal(k, key)) {
            result.push((key.clone(), val.clone()));
            continue;
        }
        if let Some(suffix) = key.strip_prefix("abs:") {
            if let Some((_, val)) = attrs.iter().find(|(k, _)| attribute_keys_equal(k, suffix)) {
                let trimmed = val.trim();
                if has_uri_scheme(trimmed) {
                    result.push((key.clone(), trimmed.to_owned()));
                }
            }
        }
    }
    result
}

fn has_uri_scheme(value: &str) -> bool {
    let Some((scheme, _)) = value.split_once(':') else {
        return false;
    };
    let mut chars = scheme.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    first.is_ascii_alphabetic()
        && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.'))
}

// --------------------------------------------------------------------------- Publisher Box Assembly

fn build_publisher_box_style(
    value: &ComputedStyle,
    rtl: bool,
    horizontal_align: FrbPublisherBoxAlign,
) -> Option<FrbPublisherBoxStyle> {
    let style = FrbPublisherBoxStyle {
        margin_top_em: value.margin_top_em,
        margin_right_em: (value.margin_end_em
            + if rtl {
                value.margin_inline_start_em
            } else {
                value.margin_inline_end_em
            })
        .clamp(0.0, MAX_BOX_INLINE_EM),
        margin_right_frac: (value.margin_end_frac
            + if rtl {
                value.margin_inline_start_frac
            } else {
                value.margin_inline_end_frac
            })
        .clamp(0.0, MAX_BOX_INLINE_FRAC),
        margin_bottom_em: value.margin_bottom_em,
        margin_left_em: (value.margin_start_em
            + if rtl {
                value.margin_inline_end_em
            } else {
                value.margin_inline_start_em
            })
        .clamp(0.0, MAX_BOX_INLINE_EM),
        margin_left_frac: (value.margin_start_frac
            + if rtl {
                value.margin_inline_end_frac
            } else {
                value.margin_inline_start_frac
            })
        .clamp(0.0, MAX_BOX_INLINE_FRAC),
        padding_top_em: value.padding_top_em,
        padding_right_em: (value.padding_end_em
            + if rtl {
                value.padding_inline_start_em
            } else {
                value.padding_inline_end_em
            })
        .clamp(0.0, MAX_BOX_INLINE_EM),
        padding_right_frac: (value.padding_end_frac
            + if rtl {
                value.padding_inline_start_frac
            } else {
                value.padding_inline_end_frac
            })
        .clamp(0.0, MAX_BOX_INLINE_FRAC),
        padding_bottom_em: value.padding_bottom_em,
        padding_left_em: (value.padding_start_em
            + if rtl {
                value.padding_inline_end_em
            } else {
                value.padding_inline_start_em
            })
        .clamp(0.0, MAX_BOX_INLINE_EM),
        padding_left_frac: (value.padding_start_frac
            + if rtl {
                value.padding_inline_end_frac
            } else {
                value.padding_inline_start_frac
            })
        .clamp(0.0, MAX_BOX_INLINE_FRAC),
        background_color_argb: value
            .background_color_argb
            .filter(|&c| ((c as u32) >> 24) != 0),
        border_top: value.border_top.as_ref().map(convert_border),
        border_right: value.border_right.as_ref().map(convert_border),
        border_bottom: value.border_bottom.as_ref().map(convert_border),
        border_left: value.border_left.as_ref().map(convert_border),
        width_frac: value.width_frac,
        width_em: value.width_em,
        shrink_to_fit: value.display.as_deref() == Some("inline-block")
            && value.width_em.is_none()
            && value.width_frac.is_none(),
        hanging_indent_em: value
            .text_indent_em
            .filter(|&v| v < 0.0)
            .map_or(0.0, |v| -v),
        fit_text_flow: false,
        horizontal_align,
        break_inside_avoid: value.break_inside_avoid,
        page_break_before: value.page_break_before,
        page_break_after: value.page_break_after,
        min_height_em: value.min_height_em,
        min_height_viewport_frac: value.min_height_viewport_frac,
        min_height_offset_em: value.min_height_offset_em,
        center_content_vertically: value.center_content_vertically,
        border_box: value.border_box,
        max_width_em: value.max_width_em,
        max_width_frac: value.max_width_frac,
    };
    (!style.is_default()).then_some(style)
}

fn convert_border(border: &Border) -> FrbPublisherBorderSide {
    let style = match border.style {
        1 => FrbPublisherBorderStyle::Solid,
        2 => FrbPublisherBorderStyle::Dashed,
        3 => FrbPublisherBorderStyle::Dotted,
        4 => FrbPublisherBorderStyle::Double,
        _ => FrbPublisherBorderStyle::None,
    };
    FrbPublisherBorderSide {
        width_em: border.width_em,
        color_argb: border.color_argb,
        style,
        uses_current_color: border.uses_current_color,
    }
}

// --------------------------------------------------------------------------- Media / Supports / Namespace / Content Parsers

fn parse_namespace_rule(prelude: &str) -> Option<(String, String)> {
    let trimmed = prelude.trim_matches(reader_whitespace);
    let rest = strip_ascii_prefix_ignore_case(trimmed, "@namespace")?;
    let (prefix_rest, had_ws) = skip_ascii_regex_ws(rest);
    if !had_ws {
        return None;
    }
    let prefix_len = prefix_rest
        .bytes()
        .take_while(|&b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        .count();
    if prefix_len == 0 {
        return None;
    }
    let prefix = &prefix_rest[..prefix_len];
    let (after_prefix, had_ws) = skip_ascii_regex_ws(&prefix_rest[prefix_len..]);
    if !had_ws {
        return None;
    }
    if let Some(after_url) = strip_ascii_prefix_ignore_case(after_prefix, "url(") {
        let (inner, _) = skip_ascii_regex_ws(after_url);
        let inner = inner.strip_prefix(['"', '\'']).unwrap_or(inner);
        let uri_len = inner
            .bytes()
            .take_while(|&b| !is_ascii_regex_ws_byte(b) && !matches!(b, b'"' | b'\'' | b')'))
            .count();
        if uri_len == 0 {
            return None;
        }
        let uri = &inner[..uri_len];
        let tail = &inner[uri_len..];
        let tail = tail.strip_prefix(['"', '\'']).unwrap_or(tail);
        let (tail, _) = skip_ascii_regex_ws(tail);
        if tail == ")" {
            return Some((prefix.to_owned(), uri.to_owned()));
        }
        return None;
    }
    if let Some(inner) = after_prefix.strip_prefix(['"', '\'']) {
        let uri_len = inner
            .bytes()
            .take_while(|&b| b != b'"' && b != b'\'')
            .count();
        if uri_len == 0 {
            return None;
        }
        let uri = &inner[..uri_len];
        let tail = &inner[uri_len..];
        if tail == "\"" || tail == "'" {
            return Some((prefix.to_owned(), uri.to_owned()));
        }
    }
    None
}

fn simple_max_width_breakpoint(raw: &str) -> Option<f32> {
    let mut rest = raw.trim_matches(reader_whitespace);
    // Optional: (?:(?:only\s+)?(?:screen|all)\s+and\s+)?
    if !rest.starts_with('(') {
        if let Some(after_only) = strip_ascii_prefix_ignore_case(rest, "only") {
            let (after_ws, had_ws) = skip_ascii_regex_ws(after_only);
            if had_ws {
                rest = after_ws;
            }
        }
        let after_type = strip_ascii_prefix_ignore_case(rest, "screen")
            .or_else(|| strip_ascii_prefix_ignore_case(rest, "all"))?;
        let (after_ws, had_ws) = skip_ascii_regex_ws(after_type);
        if !had_ws {
            return None;
        }
        let after_and = strip_ascii_prefix_ignore_case(after_ws, "and")?;
        let (after_ws, had_ws) = skip_ascii_regex_ws(after_and);
        if !had_ws {
            return None;
        }
        rest = after_ws;
    }
    let inner = rest.strip_prefix('(')?.strip_suffix(')')?;
    if inner.contains('(') || inner.contains(')') {
        return None;
    }
    let (inner, _) = skip_ascii_regex_ws(inner);
    let inner = strip_ascii_prefix_ignore_case(inner, "max-width")?;
    let (inner, _) = skip_ascii_regex_ws(inner);
    let inner = inner.strip_prefix(':')?;
    let (inner, _) = skip_ascii_regex_ws(inner);
    if inner.is_empty() {
        return None;
    }
    let value = inner.trim_matches(reader_whitespace).to_lowercase();
    if !matches!(unit_of(&value), "px" | "em" | "rem") {
        return None;
    }
    let px = css_lengths::length_em(&value, 0.0, 1.0, false)? * 16.0;
    (px.is_finite() && (1.0..=10_000.0).contains(&px)).then_some(px)
}

fn screen_media_applies(raw: &str, viewport: Option<Viewport>) -> bool {
    comma_slices(raw).into_iter().any(|branch| {
        let mut query = branch.trim_matches(reader_whitespace).to_lowercase();
        if query.is_empty() {
            return true;
        }
        let mut negated = false;
        if let Some(rest) = query.strip_prefix("only ") {
            query = rest.trim_start_matches(reader_whitespace).to_owned();
        }
        if let Some(rest) = query.strip_prefix("not ") {
            negated = true;
            query = rest.trim_start_matches(reader_whitespace).to_owned();
        }
        let media_type = extract_media_type(&query);
        let mut base_applies = matches!(media_type, "" | "all" | "screen");

        let (features, stripped) = extract_media_features(&query);
        let without_and = remove_word_and(&stripped);
        let remainder = without_and.trim_matches(reader_whitespace);
        let remainder = remainder
            .strip_prefix(media_type)
            .unwrap_or(remainder)
            .trim_matches(reader_whitespace);
        if !remainder.is_empty() {
            return false;
        }
        for feature in features {
            let Some(applies) = media_feature_applies(feature, viewport) else {
                return false;
            };
            base_applies = base_applies && applies;
        }
        if negated {
            !base_applies
        } else {
            base_applies
        }
    })
}

fn extract_media_type(query: &str) -> &str {
    // Matches Kotlin: query.split(Regex("""\s+and\b"""), limit = 2).first().trim()
    //   .takeUnless { it.startsWith("(") }?.substringBefore(' ').orEmpty()
    let bytes = query.as_bytes();
    let mut split_at = query.len();
    let mut i = 0;
    while i < bytes.len() {
        if is_ascii_regex_ws_byte(bytes[i]) {
            let ws_start = i;
            while i < bytes.len() && is_ascii_regex_ws_byte(bytes[i]) {
                i += 1;
            }
            if bytes.get(i..i + 3) == Some(b"and")
                && bytes.get(i + 3).is_none_or(|&b| !is_ascii_word_byte(b))
            {
                split_at = ws_start;
                break;
            }
        } else {
            i += 1;
        }
    }
    let head = query[..split_at].trim_matches(reader_whitespace);
    if head.starts_with('(') {
        return "";
    }
    head.split(' ').next().unwrap_or("")
}

fn extract_media_features(query: &str) -> (Vec<&str>, String) {
    // Equivalent to Kotlin regex `\(([^()]*)\)`: finds all non-nested `(...)`.
    let mut features = Vec::new();
    let mut stripped = String::with_capacity(query.len());
    let bytes = query.as_bytes();
    let mut cursor = 0;
    while cursor < bytes.len() {
        if bytes[cursor] == b'(' {
            let mut end = cursor + 1;
            while end < bytes.len() && bytes[end] != b'(' && bytes[end] != b')' {
                end += 1;
            }
            if end < bytes.len() && bytes[end] == b')' {
                features.push(&query[cursor + 1..end]);
                cursor = end + 1;
                continue;
            }
        }
        let ch = query[cursor..].chars().next().expect("valid utf-8");
        stripped.push(ch);
        cursor += ch.len_utf8();
    }
    (features, stripped)
}

fn remove_word_and(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes.get(i..i + 3) == Some(b"and")
            && (i == 0 || !is_ascii_word_byte(bytes[i - 1]))
            && bytes.get(i + 3).is_none_or(|&b| !is_ascii_word_byte(b))
        {
            i += 3;
            continue;
        }
        let ch = text[i..].chars().next().expect("valid utf-8");
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

fn media_feature_applies(raw: &str, viewport: Option<Viewport>) -> Option<bool> {
    let (property, value) = match raw.split_once(':') {
        Some((p, v)) => (
            p.trim_matches(reader_whitespace),
            v.trim_matches(reader_whitespace),
        ),
        None => (raw.trim_matches(reader_whitespace), ""),
    };
    if property == "prefers-color-scheme" {
        let dark = viewport?.dark?;
        return match value {
            "dark" => Some(dark),
            "light" => Some(!dark),
            _ => None,
        };
    }
    if property == "orientation" {
        let vp = viewport?;
        let width = Some(vp.width_px).filter(|w| w.is_finite() && *w > 0.0)?;
        let height = vp.height_px.filter(|h| h.is_finite() && *h > 0.0)?;
        return match value {
            "portrait" => Some(height >= width),
            "landscape" => Some(width > height),
            _ => None,
        };
    }
    let stripped = property.strip_prefix("min-").unwrap_or(property);
    let stripped = stripped.strip_prefix("max-").unwrap_or(stripped);
    let dimension = match stripped {
        "width" => viewport.map(|v| v.width_px),
        "height" => viewport.and_then(|v| v.height_px),
        _ => return None,
    }
    .filter(|d| d.is_finite() && *d > 0.0)?;
    if value.ends_with('%') || value.ends_with("vh") || value.ends_with("vw") {
        return None;
    }
    let threshold = css_lengths::length_em(value, 0.0, 1.0, false)? * 16.0;
    Some(if property.starts_with("min-") {
        dimension >= threshold
    } else if property.starts_with("max-") {
        dimension <= threshold
    } else {
        dimension == threshold
    })
}

fn supports_condition_applies(raw: &str) -> bool {
    let (negated, query) = if let Some(rest) = raw.strip_prefix("not ") {
        (true, rest.trim_start_matches(reader_whitespace))
    } else {
        (false, raw)
    };
    let Some(inner) = query.strip_prefix('(').and_then(|s| s.strip_suffix(')')) else {
        return false;
    };
    if inner.contains('(') || inner.contains(')') {
        return false;
    }
    let (inner, _) = skip_ascii_regex_ws(inner);
    let prop_len = inner
        .bytes()
        .take_while(|&b| is_ascii_word_byte(b) || b == b'-')
        .count();
    if prop_len == 0 {
        return false;
    }
    let property = &inner[..prop_len];
    let (rest, _) = skip_ascii_regex_ws(&inner[prop_len..]);
    let Some(rest) = rest.strip_prefix(':') else {
        return false;
    };
    let (rest, _) = skip_ascii_regex_ws(rest);
    if rest.is_empty() {
        return false;
    }
    let value = rest.trim_matches(reader_whitespace);
    let supported = match property {
        "display" => matches!(
            value,
            "none"
                | "block"
                | "inline"
                | "inline-block"
                | "table"
                | "table-row"
                | "table-cell"
                | "flex"
        ),
        "color" | "background-color" => {
            value == "currentcolor" || css_color::parse_color(value).is_some()
        }
        "font-variation-settings" => {
            value == "normal" || css_computed_fonts::parse_font_variations(value).is_some()
        }
        "font-width" | "font-stretch" => css_computed_fonts::parse_font_stretch(value).is_some(),
        "font-optical-sizing" => matches!(value, "auto" | "none"),
        "font-kerning" => matches!(value, "auto" | "normal" | "none"),
        "font-variant-ligatures" => value == "normal" || css_computed_fonts::valid_ligatures(value),
        "font-feature-settings" => {
            value == "normal" || css_computed_fonts::parse_font_features(value).is_some()
        }
        _ => false,
    };
    if negated {
        !supported
    } else {
        supported
    }
}

fn parse_content_value(raw: &str) -> Option<String> {
    let value = raw.trim_matches(reader_whitespace);
    if value.is_empty() {
        return None;
    }
    let lower = value.to_lowercase();
    if lower == "none" || lower == "normal" {
        return None;
    }
    let chars: Vec<char> = value.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    let mut saw_string = false;
    while i < chars.len() {
        let c = chars[i];
        if c == '"' || c == '\'' {
            saw_string = true;
            i += 1;
            while i < chars.len() && chars[i] != c {
                if chars[i] == '\\' && i + 1 < chars.len() {
                    i += 1;
                    let hex_start = i;
                    let mut hex_end = i;
                    while hex_end < chars.len()
                        && hex_end - hex_start < 6
                        && chars[hex_end].is_ascii_hexdigit()
                    {
                        hex_end += 1;
                    }
                    if hex_end > hex_start {
                        let mut cp = 0u32;
                        for &hd in &chars[hex_start..hex_end] {
                            cp = (cp << 4) | hd.to_digit(16).expect("ascii hex digit");
                        }
                        if (1..=0x10_FFFF).contains(&cp) {
                            if let Some(ch) = char::from_u32(cp) {
                                out.push(ch);
                            }
                        }
                        i = hex_end;
                        // A single ASCII space terminates the escape.
                        if i < chars.len() && chars[i] == ' ' {
                            i += 1;
                        }
                    } else {
                        if chars[i] == '\r' && chars.get(i + 1) == Some(&'\n') {
                            i += 1;
                        }
                        if !matches!(chars[i], '\n' | '\r' | '\u{000c}') {
                            out.push(chars[i]);
                        }
                        i += 1;
                    }
                } else {
                    if matches!(chars[i], '\n' | '\r' | '\u{000c}') {
                        return None;
                    }
                    out.push(chars[i]);
                    i += 1;
                }
            }
            if i >= chars.len() {
                return None;
            }
            i += 1; // closing quote
        } else if reader_whitespace(c) {
            i += 1;
        } else {
            return None; // bare identifiers/functions: unsupported
        }
    }
    saw_string.then_some(out)
}

fn find_font_url(src: &str) -> Option<&str> {
    let mut search = src;
    while let Some(pos) = search.find("url(") {
        let after_open = &search[pos + 4..];
        let (rest, _) = skip_ascii_regex_ws(after_open);
        let rest = rest.strip_prefix(['\'', '"']).unwrap_or(rest);
        let url_len = rest
            .bytes()
            .take_while(|&b| !matches!(b, b'\'' | b'"' | b')'))
            .count();
        if url_len > 0 {
            let candidate = &rest[..url_len];
            let tail = &rest[url_len..];
            let tail = tail.strip_prefix(['\'', '"']).unwrap_or(tail);
            let (tail, _) = skip_ascii_regex_ws(tail);
            if tail.starts_with(')') {
                return Some(candidate);
            }
        }
        search = after_open;
    }
    None
}

fn parse_font_unicode_ranges(value: &str) -> Option<Vec<(u32, u32)>> {
    if value.encode_utf16().count() > 16_384 {
        return None;
    }
    let mut ranges = Vec::new();
    let mut token_count = 0usize;
    for token in value.split(',') {
        token_count += 1;
        if token_count > 1024 {
            return None;
        }
        let text = token.trim_matches(reader_whitespace).to_ascii_uppercase();
        let rest = text.strip_prefix("U+")?;
        let range = if let Some((a, b)) = rest.split_once('-') {
            if !(1..=6).contains(&a.len())
                || !(1..=6).contains(&b.len())
                || !a.bytes().all(|c| c.is_ascii_hexdigit())
                || !b.bytes().all(|c| c.is_ascii_hexdigit())
            {
                return None;
            }
            let first = u32::from_str_radix(a, 16).ok()?;
            let last = u32::from_str_radix(b, 16).ok()?;
            if first > last || last > 0x10_FFFF {
                return None;
            }
            (first, last)
        } else if rest.contains('?') {
            let hex_len = rest.bytes().take_while(|c| c.is_ascii_hexdigit()).count();
            let q_part = &rest[hex_len..];
            if hex_len > 5
                || !(1..=6).contains(&q_part.len())
                || hex_len + q_part.len() > 6
                || !q_part.bytes().all(|c| c == b'?')
            {
                return None;
            }
            let prefix = if hex_len == 0 {
                0u32
            } else {
                u32::from_str_radix(&rest[..hex_len], 16).ok()?
            };
            let shift = (q_part.len() as u32) * 4;
            let first = prefix << shift;
            let last = first | ((1u32 << shift) - 1);
            if last > 0x10_FFFF {
                return None;
            }
            (first, last)
        } else {
            if !(1..=6).contains(&rest.len()) || !rest.bytes().all(|c| c.is_ascii_hexdigit()) {
                return None;
            }
            let cp = u32::from_str_radix(rest, 16).ok()?;
            if cp > 0x10_FFFF {
                return None;
            }
            (cp, cp)
        };
        ranges.push(range);
    }
    if ranges.is_empty() {
        return None;
    }
    ranges.sort_by_key(|&(first, _)| first);
    let mut merged: Vec<(u32, u32)> = Vec::with_capacity(ranges.len());
    for (first, last) in ranges {
        if let Some(prev) = merged.last_mut() {
            if first <= prev.1 + 1 {
                prev.1 = prev.1.max(last);
                continue;
            }
        }
        merged.push((first, last));
    }
    Some(merged)
}

// --------------------------------------------------------------------------- Value / Color / Cloning Helpers

fn resolved_declaration_value(
    decl: &Declaration,
    custom_props: &css_variables::Scope,
) -> Option<String> {
    let raw = if decl.value.contains("var(") {
        let (_, mut resolved) = css_variables::resolve(
            custom_props.clone(),
            Vec::new(),
            std::slice::from_ref(&decl.value),
        );
        resolved.pop().flatten()?
    } else {
        decl.value.clone()
    };
    let normalized = raw.trim_matches(reader_whitespace).to_lowercase();
    if decl.expansion == 0 {
        Some(normalized)
    } else {
        css_shorthand::expand(decl.expansion, &normalized)
    }
}

fn resolve_foreground(value: &str, inherited: Option<i32>) -> Option<i32> {
    match value
        .trim_matches(reader_whitespace)
        .to_lowercase()
        .as_str()
    {
        "inherit" | "unset" | "currentcolor" => inherited,
        "initial" | "revert" | "revert-layer" => Some(BLACK_ARGB),
        _ => css_color::parse_color(value).map(|c| c as i32),
    }
}

fn resolve_background(value: &str, inherited: Option<i32>, current: Option<i32>) -> Option<i32> {
    match value
        .trim_matches(reader_whitespace)
        .to_lowercase()
        .as_str()
    {
        "inherit" => inherited,
        "currentcolor" => current,
        "initial" | "unset" | "revert" | "revert-layer" => None,
        _ => css_color::parse_color(value).map(|c| c as i32),
    }
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

fn first_family_name(value: &str) -> Option<String> {
    let first = value.split(',').next()?;
    let trimmed = first
        .trim_matches(reader_whitespace)
        .trim_matches(['"', '\''])
        .trim_matches(reader_whitespace)
        .to_lowercase();
    (!trimmed.is_empty() && trimmed != "inherit").then_some(trimmed)
}

fn unit_of(value: &str) -> &str {
    value
        .char_indices()
        .rev()
        .find(|&(_, ch)| !(ch == '%' || (ch.len_utf16() == 1 && ch.is_alphabetic())))
        .map_or(value, |(idx, ch)| &value[idx + ch.len_utf8()..])
}

fn comma_slices(text: &str) -> Vec<&str> {
    let mut result = Vec::with_capacity(MAX_COMMA_LIST_ITEMS.min(8));
    let mut start = 0usize;
    let mut quote: Option<char> = None;
    let mut parens = 0usize;
    let mut brackets = 0usize;
    let mut backslashes = 0usize;
    for (index, ch) in text.char_indices() {
        if let Some(open) = quote {
            if ch == open && backslashes.is_multiple_of(2) {
                quote = None;
            }
        } else {
            match ch {
                '\'' | '"' => quote = Some(ch),
                '(' => parens += 1,
                ')' => parens = parens.saturating_sub(1),
                '[' => brackets += 1,
                ']' => brackets = brackets.saturating_sub(1),
                ',' if parens == 0 && brackets == 0 => {
                    result.push(&text[start..index]);
                    if result.len() >= MAX_COMMA_LIST_ITEMS {
                        return result;
                    }
                    start = index + 1;
                }
                _ => {}
            }
        }
        backslashes = if ch == '\\' { backslashes + 1 } else { 0 };
    }
    if result.len() < MAX_COMMA_LIST_ITEMS {
        result.push(&text[start..]);
    }
    result
}

fn empty_cascaded() -> Arc<CascadedDeclarations> {
    static EMPTY: OnceLock<Arc<CascadedDeclarations>> = OnceLock::new();
    Arc::clone(EMPTY.get_or_init(|| Arc::new(CascadedDeclarations::empty())))
}

fn clone_declaration(decl: &Declaration) -> Declaration {
    Declaration {
        property: decl.property.clone(),
        value: decl.value.clone(),
        expansion: decl.expansion,
    }
}

fn selector_bucket_key(subject: &Simple) -> String {
    if let Some(id) = &subject.id {
        let mut key = String::with_capacity(1 + id.len());
        key.push('#');
        key.push_str(id);
        key
    } else if let Some(class) = subject.classes.first() {
        let mut key = String::with_capacity(1 + class.len());
        key.push('.');
        key.push_str(class);
        key
    } else if let Some(tag) = subject.tag.as_deref().filter(|&t| t != "*") {
        tag.to_owned()
    } else {
        String::new()
    }
}

fn assign_has_ids(chain: &mut [Compound], next_has_id: &mut usize) {
    for compound in chain {
        for pseudo in &mut compound.simple.pseudos {
            if let Pseudo::Has { id, .. } = pseudo {
                *id = *next_has_id;
                *next_has_id += 1;
            }
        }
    }
}

fn collect_absolute_keys(simple: &Simple, keys: &mut BTreeSet<String>) {
    for attr in &simple.attrs {
        if attr.namespace.is_none() && attr.name.starts_with("abs:") {
            keys.insert(attr.name.clone());
        }
    }
    for pseudo in &simple.pseudos {
        match pseudo {
            Pseudo::Not(inner) => collect_absolute_keys(inner, keys),
            Pseudo::Has { relatives, .. } => {
                for compound in relatives.iter().flatten() {
                    collect_absolute_keys(&compound.simple, keys);
                }
            }
            _ => {}
        }
    }
}

fn clone_rule_pools(pools: &[Vec<MatchRule>; POOL_COUNT]) -> [Vec<MatchRule>; POOL_COUNT] {
    std::array::from_fn(|idx| pools[idx].iter().map(clone_rule).collect())
}

fn clone_rule(rule: &MatchRule) -> MatchRule {
    MatchRule {
        id: rule.id,
        specificity: rule.specificity,
        order: rule.order,
        chain: rule.chain.iter().map(clone_compound).collect(),
    }
}

fn clone_compound(compound: &Compound) -> Compound {
    Compound {
        combinator: compound.combinator,
        simple: clone_simple(&compound.simple),
    }
}

fn clone_simple(simple: &Simple) -> Simple {
    Simple {
        tag: simple.tag.clone(),
        id: simple.id.clone(),
        classes: simple.classes.clone(),
        attrs: simple.attrs.iter().map(clone_attr).collect(),
        pseudos: simple.pseudos.iter().map(clone_pseudo).collect(),
    }
}

fn clone_attr(attr: &Attr) -> Attr {
    Attr {
        name: attr.name.clone(),
        op: match attr.op {
            AttrOp::Exists => AttrOp::Exists,
            AttrOp::Equals => AttrOp::Equals,
            AttrOp::Includes => AttrOp::Includes,
            AttrOp::Dash => AttrOp::Dash,
            AttrOp::Prefix => AttrOp::Prefix,
            AttrOp::Suffix => AttrOp::Suffix,
            AttrOp::Substring => AttrOp::Substring,
        },
        value: attr.value.clone(),
        namespace: attr.namespace.clone(),
    }
}

fn clone_pseudo(pseudo: &Pseudo) -> Pseudo {
    match pseudo {
        Pseudo::FirstChild => Pseudo::FirstChild,
        Pseudo::LastChild => Pseudo::LastChild,
        Pseudo::OnlyChild => Pseudo::OnlyChild,
        Pseudo::FirstOfType => Pseudo::FirstOfType,
        Pseudo::Nth {
            a,
            b,
            of_type,
            from_end,
        } => Pseudo::Nth {
            a: *a,
            b: *b,
            of_type: *of_type,
            from_end: *from_end,
        },
        Pseudo::Has { id, relatives } => Pseudo::Has {
            id: *id,
            relatives: relatives
                .iter()
                .map(|chain| chain.iter().map(clone_compound).collect())
                .collect(),
        },
        Pseudo::Not(inner) => Pseudo::Not(Box::new(clone_simple(inner))),
    }
}

fn intern_common_tag(tag: &str) -> Cow<'static, str> {
    let static_tag = match tag {
        "p" => "p",
        "div" => "div",
        "span" => "span",
        "a" => "a",
        "em" => "em",
        "i" => "i",
        "b" => "b",
        "strong" => "strong",
        "br" => "br",
        "img" => "img",
        "h1" => "h1",
        "h2" => "h2",
        "h3" => "h3",
        "h4" => "h4",
        "h5" => "h5",
        "h6" => "h6",
        "li" => "li",
        "ul" => "ul",
        "ol" => "ol",
        "blockquote" => "blockquote",
        "section" => "section",
        "body" => "body",
        "html" => "html",
        "sup" => "sup",
        "sub" => "sub",
        "small" => "small",
        "code" => "code",
        "pre" => "pre",
        "figure" => "figure",
        "figcaption" => "figcaption",
        "hr" => "hr",
        "table" => "table",
        "tr" => "tr",
        "td" => "td",
        "th" => "th",
        "tbody" => "tbody",
        "thead" => "thead",
        "article" => "article",
        "aside" => "aside",
        "header" => "header",
        "footer" => "footer",
        "nav" => "nav",
        "cite" => "cite",
        "u" => "u",
        "s" => "s",
        "del" => "del",
        "ins" => "ins",
        "dl" => "dl",
        "dt" => "dt",
        "dd" => "dd",
        _ => return Cow::Owned(tag.to_owned()),
    };
    Cow::Borrowed(static_tag)
}

// --------------------------------------------------------------------------- Character / Formatting Helpers

#[inline]
fn reader_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

#[inline]
fn is_ascii_regex_ws_byte(b: u8) -> bool {
    matches!(b, b' ' | b'\t' | b'\n' | b'\x0b' | b'\x0c' | b'\r')
}

#[inline]
fn ascii_regex_whitespace(ch: char) -> bool {
    ch.is_ascii() && is_ascii_regex_ws_byte(ch as u8)
}

#[inline]
fn is_ascii_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn skip_ascii_regex_ws(s: &str) -> (&str, bool) {
    let trimmed = s.trim_start_matches(ascii_regex_whitespace);
    (trimmed, trimmed.len() != s.len())
}

fn split_regex_whitespace(s: &str) -> Vec<&str> {
    // Matches Kotlin `s.split(Regex("\\s+"))` on an already-trimmed string.
    let mut result = Vec::new();
    let bytes = s.as_bytes();
    let mut start = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if is_ascii_regex_ws_byte(bytes[i]) {
            result.push(&s[start..i]);
            while i < bytes.len() && is_ascii_regex_ws_byte(bytes[i]) {
                i += 1;
            }
            start = i;
        } else {
            i += 1;
        }
    }
    result.push(&s[start..]);
    result
}

fn strip_ascii_prefix_ignore_case<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    let head = s.get(..prefix.len())?;
    head.eq_ignore_ascii_case(prefix)
        .then(|| &s[prefix.len()..])
}

fn contains_ascii_ignore_case(haystack: &str, needle_lower: &[u8]) -> bool {
    if needle_lower.is_empty() {
        return true;
    }
    haystack
        .as_bytes()
        .windows(needle_lower.len())
        .any(|w| w.eq_ignore_ascii_case(needle_lower))
}

fn valid_font_axis(tag: &str, value: f32) -> bool {
    tag.len() == 4
        && tag
            .bytes()
            .all(|b| (b' '..=b'~').contains(&b) && b != b'\'')
        && value.is_finite()
}

fn push_json_string(out: &mut String, value: &str) {
    out.push('"');
    for ch in value.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Formats a finite `f32` to match Kotlin/Java `Float.toString` output used by
/// `JsonPrimitive(float).toString()`.
pub(crate) fn format_kotlin_float(v: f32) -> String {
    if v == 0.0 {
        return if v.is_sign_negative() {
            "-0.0".into()
        } else {
            "0.0".into()
        };
    }
    let abs = v.abs();
    if (1e-3..1e7).contains(&abs) {
        let mut s = format!("{v}");
        if !s.contains('.') {
            s.push_str(".0");
        }
        s
    } else {
        let raw = format!("{v:E}");
        if let Some((mantissa, exp)) = raw.split_once('E') {
            if mantissa.contains('.') {
                format!("{mantissa}E{exp}")
            } else {
                format!("{mantissa}.0E{exp}")
            }
        } else {
            raw
        }
    }
}

/// Classifies strong Unicode bidirectional directionality matching Java's
/// `Character.getDirectionality`: `Some(true)` for `R`/`AL`, `Some(false)` for
/// `L`, `None` for digits, punctuation, marks, whitespace, and bidi controls.
fn char_strong_rtl(ch: char) -> Option<bool> {
    let cp = ch as u32;
    if cp < 0x80 {
        return ch.is_ascii_alphabetic().then_some(false);
    }
    if cp == 0x200E {
        return Some(false); // LRM
    }
    if cp == 0x200F || cp == 0x061C {
        return Some(true); // RLM / ALM
    }
    // Strong RTL (R + AL) script ranges, excluding combining marks (NSM) & digits.
    let is_rtl = matches!(
        cp,
        // Hebrew R letters & punctuation
        0x05BE | 0x05C0 | 0x05C3 | 0x05C6 | 0x05D0..=0x05EA | 0x05EF..=0x05F4
        // Arabic AL letters & punctuation
        | 0x0608 | 0x060B | 0x060D | 0x061B | 0x061D..=0x064A | 0x066D..=0x066F
        | 0x0671..=0x06D5 | 0x06E5..=0x06E6 | 0x06EE..=0x06EF | 0x06FA..=0x06FF
        // Syriac & Thaana AL
        | 0x0700..=0x070D | 0x0710 | 0x0712..=0x072F | 0x074D..=0x07A5 | 0x07B1
        // NKo R
        | 0x07C0..=0x07EA | 0x07F4..=0x07F5 | 0x07FA | 0x07FE..=0x07FF
        // Samaritan R
        | 0x0800..=0x0815 | 0x081A | 0x0824 | 0x0828 | 0x0830..=0x083E
        // Mandaic R
        | 0x0840..=0x0858 | 0x085E
        // Syriac Supplement, Arabic Extended-B & Extended-A AL
        | 0x0860..=0x086A | 0x0870..=0x088E | 0x08A0..=0x08C9
        // Hebrew presentation forms R
        | 0xFB1D | 0xFB1F..=0xFB28 | 0xFB2A..=0xFB4F
        // Arabic presentation forms A & B AL
        | 0xFB50..=0xFBC2 | 0xFBD3..=0xFD3D | 0xFD50..=0xFDC7 | 0xFDF0..=0xFDFC
        | 0xFE70..=0xFEFC
        // SMP RTL scripts (Cypriot, Aramaic, Phoenician, Lydian, Kharoshthi,
        // Old South/North Arabian, Manichaean, Avestan, Pahlavi, Old Turkic,
        // Old Hungarian, Hanifi Rohingya, Yezidi, Sogdian, Chorasmian, Elymaic,
        // Mende Kikakui, Adlam, Siyaq, Arabic Math)
        | 0x10800..=0x109FF | 0x10A00..=0x10A00 | 0x10A10..=0x10A37 | 0x10A40..=0x10CFF
        | 0x10D00..=0x10D23 | 0x10E80..=0x10FFF
        | 0x1E800..=0x1E8CF | 0x1E900..=0x1E943 | 0x1E94B..=0x1E95F
        | 0x1EC70..=0x1ECBF | 0x1ED00..=0x1ED4F | 0x1EE00..=0x1EEFF
    );
    if is_rtl {
        return Some(true);
    }
    if ch.is_alphabetic() {
        return Some(false);
    }
    None
}

// --------------------------------------------------------------------------- Unit Tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chapter_markup;
    use std::collections::BTreeMap;

    fn parse_html(html: &str) -> Document {
        chapter_markup::parse(html, true).expect("parse html")
    }

    fn parse_xml(xml: &str) -> Document {
        chapter_markup::parse(xml, false).expect("parse xml")
    }

    fn find_by_id(doc: &Document, target_id: &str) -> usize {
        doc.nodes
            .iter()
            .enumerate()
            .find_map(|(idx, node)| match &node.kind {
                Kind::Element { attrs, .. }
                    if attrs
                        .iter()
                        .any(|(k, v)| k.eq_ignore_ascii_case("id") && v == target_id) =>
                {
                    Some(idx)
                }
                _ => None,
            })
            .unwrap_or_else(|| panic!("missing element #{target_id}"))
    }

    fn find_all_by_tag(doc: &Document, tag: &str) -> Vec<usize> {
        doc.nodes
            .iter()
            .enumerate()
            .filter_map(|(idx, node)| match &node.kind {
                Kind::Element { name, .. } if name.eq_ignore_ascii_case(tag) => Some(idx),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn compiled_stylesheets_is_send_and_sync() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<CompiledStylesheets>();
        assert_send_sync::<FontFaceSpec>();
        assert_send_sync::<GeneratedRun>();
        assert_send_sync::<Viewport>();
        assert_send_sync::<SheetInput>();
    }

    #[test]
    fn selectors_specificity_important_inline_and_inheritance() {
        let sheets = [SheetInput::new(
            r#"
            body { color: #112233; font-size: 1.25em; }
            p { color: #ff0000; font-weight: 400; text-indent: 1em; }
            .lead { color: #00ff00; font-style: italic; }
            #hero { color: #0000ff; }
            p.forced { font-weight: 700 !important; }
            section > p + p { text-align: center; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<html><body><section>
                <p id="hero" class="lead forced" style="font-weight: 300; text-decoration: underline">First</p>
                <p id="second">Second</p>
            </section></body></html>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let hero = css.computed(find_by_id(&doc, "hero"));
        assert_eq!(hero.foreground_color_argb, Some(0xff00_00ff_u32 as i32));
        assert_eq!(hero.font_weight, Some(700)); // !important sheet beats non-important inline
        assert_eq!(computed_bold(&hero), Some(true));
        assert_eq!(hero.italic, Some(true));
        assert!(hero.underline);
        assert!((hero.font_size_em - 1.25).abs() < 0.001);
        assert!((hero.text_indent_em.unwrap() - 1.25).abs() < 0.001);

        let second = css.computed(find_by_id(&doc, "second"));
        assert_eq!(second.text_align.as_deref(), Some("center"));
        assert_eq!(second.foreground_color_argb, Some(0xffff_0000_u32 as i32));
    }

    #[test]
    fn multiple_has_selectors_do_not_collide_in_matcher_memo() {
        let sheets = [SheetInput::new(
            r#"
            div:has(> em) { font-style: italic; }
            div:has(> strong) { font-weight: bold; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<div><div id="a"><em>e</em></div><div id="b"><strong>s</strong></div></div>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let a = css.computed(find_by_id(&doc, "a"));
        assert_eq!(a.italic, Some(true));
        assert_eq!(a.font_weight, None);

        let b = css.computed(find_by_id(&doc, "b"));
        assert_eq!(b.italic, None);
        assert_eq!(b.font_weight, Some(700));
    }

    #[test]
    fn custom_properties_and_calc_resolve_across_ancestors() {
        let sheets = [SheetInput::new(
            r#"
            body { --tone: red; --gap: 1.5em; }
            div { --tone: blue; }
            p {
                --stroke: var(--tone);
                color: var(--tone);
                border: 2px dashed var(--stroke);
                text-indent: calc(var(--gap) * 2);
            }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html("<body><div><p id='x'>Hi</p></div></body>");
        let mut css = ChapterCss::standalone(compiled, &doc);
        let p = css.computed(find_by_id(&doc, "x"));

        assert_eq!(p.foreground_color_argb, Some(0xff00_00ff_u32 as i32));
        assert!((p.text_indent_em.unwrap() - 3.0).abs() < 0.001);
        let border = p.border_top.as_ref().expect("border_top");
        assert_eq!(border.color_argb, 0xff00_00ff_u32 as i32);
        assert_eq!(border.style, 2); // dashed
    }

    #[test]
    fn media_supports_namespace_and_font_face_compilation() {
        let sheets = [SheetInput::new(
            r#"
            @charset "utf-8";
            @namespace epub "http://www.idpf.org/2007/ops";
            @font-face {
                font-family: "Book Serif", serif;
                src: local("Book"), url("fonts/book-bold.woff2") format("woff2");
                font-weight: 600 800;
                font-style: italic;
                font-stretch: semi-condensed expanded;
                unicode-range: U+0041-005A, U+006?;
            }
            @media print { p { color: red; } }
            @media not print { p[epub|type="poem"] { font-style: italic; } }
            @media screen and (max-width: 40em) { p { text-align: left; } }
            @supports (display: flex) { p { margin-top: 2em; } }
            @supports not (display: grid) { p { margin-bottom: 3em; } }
            "#,
            "OEBPS/styles",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        assert!(compiled.has_responsive_breakpoints());
        assert_eq!(compiled.max_width_breakpoints_css_px(), vec![640.0]);
        assert_eq!(compiled.font_faces().len(), 1);
        let face = &compiled.font_faces()[0];
        assert_eq!(face.family, "book serif");
        assert_eq!(face.src, "fonts/book-bold.woff2");
        assert_eq!(face.base_dir, "OEBPS/styles");
        assert!(face.bold);
        assert!(face.italic);
        assert_eq!(face.weight, 600);
        assert_eq!(face.weight_min, 600);
        assert_eq!(face.weight_max, 800);
        assert_eq!(face.stretch_min, 87.5);
        assert_eq!(face.stretch_max, 125.0);
        assert_eq!(face.unicode_range.as_deref(), Some("U+41-5A,U+60-6F"));

        let doc = parse_xml(
            r#"<html xmlns:ops="http://www.idpf.org/2007/ops"><body><p id="p" ops:type="poem">Verse</p></body></html>"#,
        );
        let mut css = ChapterCss::standalone(Arc::clone(&compiled), &doc);
        let p = css.computed(find_by_id(&doc, "p"));
        assert_eq!(p.foreground_color_argb, None); // print skipped
        assert_eq!(p.italic, Some(true)); // @namespace + not print applied
        assert_eq!(p.text_align, None); // max-width: 40em skipped without viewport
        assert!((p.margin_top_em - 2.0).abs() < 0.001); // @supports (display: flex)
        assert!((p.margin_bottom_em - 3.0).abs() < 0.001); // @supports not (display: grid)

        // Responsive breakpoint variant activates max-width: 40em
        let narrow = compiled
            .variant_at_max_width(640.0)
            .expect("narrow variant");
        let mut narrow_css = ChapterCss::standalone(narrow, &doc);
        assert_eq!(
            narrow_css
                .computed(find_by_id(&doc, "p"))
                .text_align
                .as_deref(),
            Some("left")
        );
    }

    #[test]
    fn publisher_box_style_handles_rtl_logical_margins_and_inline_image_align() {
        let sheets = [SheetInput::new(
            r#"
            #rtl {
                direction: rtl;
                padding-left: 1em;
                padding-inline-start: 2em;
                padding-inline-end: 3em;
                border-top: 2px solid #123456;
                background: #abcdef;
            }
            #auto-rtl {
                margin-inline-start: 2em;
                margin-inline-end: 1em;
            }
            .center { text-align: center; }
            .rtl-start { direction: rtl; text-align: start; }
            img { width: 80%; }
            img.block { display: block; }
            #plain { color: red; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<html><body>
                <div id="rtl">نص</div>
                <p id="auto-rtl" dir="auto">مرحبا بالعالم</p>
                <p class="center"><img id="inline" src="a.png"></p>
                <p class="center"><img id="block" class="block" src="b.png"></p>
                <p class="rtl-start"><img id="rtl-start" src="c.png"></p>
                <p id="plain">Plain</p>
            </body></html>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let rtl = css
            .publisher_box_style(find_by_id(&doc, "rtl"))
            .expect("rtl box");
        assert!((rtl.padding_left_em - 3.0).abs() < 0.001);
        assert!((rtl.padding_right_em - 2.0).abs() < 0.001);
        assert_eq!(rtl.background_color_argb, Some(0xffab_cdef_u32 as i32));
        let top = rtl.border_top.expect("border_top");
        assert_eq!(top.style, FrbPublisherBorderStyle::Solid);
        assert!((top.width_em - 0.125).abs() < 0.001);
        assert_eq!(top.color_argb, 0xff12_3456_u32 as i32);

        let auto_rtl = css
            .publisher_box_style(find_by_id(&doc, "auto-rtl"))
            .expect("auto rtl box");
        assert!((auto_rtl.margin_right_em - 2.0).abs() < 0.001);
        assert!((auto_rtl.margin_left_em - 1.0).abs() < 0.001);

        assert_eq!(
            css.publisher_box_style(find_by_id(&doc, "inline"))
                .unwrap()
                .horizontal_align,
            FrbPublisherBoxAlign::Center
        );
        assert_eq!(
            css.publisher_box_style(find_by_id(&doc, "block"))
                .unwrap()
                .horizontal_align,
            FrbPublisherBoxAlign::Start
        );
        assert_eq!(
            css.publisher_box_style(find_by_id(&doc, "rtl-start"))
                .unwrap()
                .horizontal_align,
            FrbPublisherBoxAlign::End
        );
        // Plain element with only text color has no publisher box style.
        assert!(css.publisher_box_style(find_by_id(&doc, "plain")).is_none());
    }

    #[test]
    fn first_letter_and_floated_text_initial_match_kotlin_rules() {
        let sheets = [SheetInput::new(
            r#"
            p.drop::first-letter {
                float: left;
                font-size: 3em;
                font-weight: bold;
                font-family: "Initials", serif;
                color: #880000;
            }
            p.plain::first-letter { font-size: 1em; }
            .cap { float: left; font-size: 3.4em; font-style: italic; color: #004488; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        assert!(compiled.has_first_letter_rules());

        let doc = parse_html(
            r#"<html><body>
                <p id="drop" class="drop">Once upon a time.</p>
                <p id="plain" class="plain">Plain paragraph.</p>
                <p id="fp"><span id="cap" class="cap">K</span>ingdom.</p>
            </body></html>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let fl = css
            .first_letter(find_by_id(&doc, "drop"))
            .expect("drop cap");
        assert!((fl.scale - 3.0).abs() < 0.001);
        assert!(fl.is_drop_cap);
        assert_eq!(fl.bold, Some(true));
        assert_eq!(fl.font_weight, Some(700));
        assert_eq!(fl.font_family.as_deref(), Some("initials"));
        assert_eq!(fl.foreground_color_argb, Some(0xff88_0000_u32 as i32));
        assert!(fl.left_side);
        assert_eq!(fl.font_typography, Some(FrbFontTypography::default()));

        assert!(css.first_letter(find_by_id(&doc, "plain")).is_none());

        // Floated initial recovers 3.4em (> normal 3.0em cap, bounded to 4.0em)
        let floated = css
            .floated_text_initial(find_by_id(&doc, "cap"), find_by_id(&doc, "fp"), 1)
            .expect("floated initial");
        assert!((floated.scale - 3.4).abs() < 0.001);
        assert!(floated.is_drop_cap);
        assert_eq!(floated.italic, Some(true));
        assert_eq!(floated.source_text_length, Some(1));
        assert_eq!(floated.foreground_color_argb, Some(0xff00_4488_u32 as i32));
    }

    #[test]
    fn generated_before_and_after_content_with_escapes_and_boxes() {
        let sheets = [SheetInput::new(
            r#"
            .note::before { content: "\201C" "Note: "; font-weight: bold; color: #336699; }
            .note::after { content: "\201D"; border-bottom: 1px solid #000; }
            .dynamic::before { content: counter(section); }
            .none::before { content: none; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        assert!(compiled.has_before_rules());
        assert!(compiled.has_after_rules());
        assert!(compiled.has_generated_rules());

        let doc = parse_html(
            r#"<html><body>
                <p id="n" class="note">Body</p>
                <p id="d" class="dynamic">Body</p>
                <p id="z" class="none">Body</p>
            </body></html>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let before = css
            .generated(find_by_id(&doc, "n"), false)
            .expect("before run");
        assert_eq!(before.text, "\u{201C}Note: ");
        assert_eq!(before.bold, Some(true));
        assert_eq!(before.font_weight, Some(700));
        assert_eq!(before.foreground_color_argb, Some(0xff33_6699_u32 as i32));
        assert!(before.publisher_box.is_none());

        let after = css
            .generated(find_by_id(&doc, "n"), true)
            .expect("after run");
        assert_eq!(after.text, "\u{201D}");
        assert!(after.publisher_box.is_some());

        assert!(css.generated(find_by_id(&doc, "d"), false).is_none());
        assert!(css.generated(find_by_id(&doc, "z"), false).is_none());
    }

    #[test]
    fn dark_image_style_and_visual_background_compositing() {
        let sheets = [SheetInput::new(
            r#"
            img.diagram { background-color: transparent; }
            @media (prefers-color-scheme: dark) {
                img.diagram { filter: invert(100%); background-color: #ffffff; }
            }
            .outer { background-color: #0000ff; }
            .inner { background-color: rgba(255, 0, 0, 0.5); }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<html><body>
                <img id="img" class="diagram" src="fig.png">
                <div id="outer" class="outer"><p id="inner" class="inner">Hi</p></div>
            </body></html>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let light = css.computed(find_by_id(&doc, "img"));
        assert!(!light.image_invert);
        let dark = css.dark_image_style(find_by_id(&doc, "img"));
        assert!(dark.image_invert);
        assert_eq!(dark.background_color_argb, Some(0xffff_ffff_u32 as i32));

        let outer_id = find_by_id(&doc, "outer");
        let inner_id = find_by_id(&doc, "inner");
        let composited = css
            .visual_background(inner_id, None)
            .expect("composited bg");
        // 50% red (alpha 128) over opaque blue -> opaque purple (R~128, G=0, B~127)
        assert_eq!((composited as u32) >> 24, 0xFF);
        let r = ((composited as u32) >> 16) & 0xFF;
        let b = (composited as u32) & 0xFF;
        assert!((126..=129).contains(&r));
        assert!((126..=129).contains(&b));

        // Stopping before outer leaves only inner's translucent red
        let stopped = css
            .visual_background(inner_id, Some(outer_id))
            .expect("stopped bg");
        assert_eq!((stopped as u32) >> 24, 128);
    }

    #[test]
    fn shared_style_cache_reuses_arcs_across_chapters_and_respects_order() {
        let sheets = [SheetInput::new(
            r#"
            p { margin: 1em 0; font-size: 1.1em; }
            p, .physical { margin-left: 3em; }
            p { margin-inline-start: 2em; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let mut shared_cache = SharedStyleCache::new();

        let doc1 = parse_html("<body><p>A</p><p>B</p><p class='physical'>C</p></body>");
        let doc2 = parse_html("<body><p>D</p><p>E</p></body>");

        let (p1_style, p2_style, phys_style) = {
            let mut css1 = ChapterCss::new(Arc::clone(&compiled), &doc1, &mut shared_cache);
            let ps = find_all_by_tag(&doc1, "p");
            let s0 = css1.computed(ps[0]);
            let s1 = css1.computed(ps[1]);
            let s2 = css1.computed(ps[2]);
            assert!(Arc::ptr_eq(&s0, &s1));
            assert!(!Arc::ptr_eq(&s0, &s2));
            assert_eq!(s0.margin_start_em, 0.0);
            assert!((s0.margin_inline_start_em - 2.2).abs() < 0.001);
            assert!((s2.margin_start_em - 3.3).abs() < 0.001);
            assert_eq!(s2.margin_inline_start_em, 0.0);
            (s0, s1, s2)
        };
        let _ = (p2_style, phys_style);
        assert!(shared_cache.shared_computed_count(&compiled) > 0);
        assert!(shared_cache.cascade_winner_cache_size(&compiled) > 0);

        // Chapter 2 reuses the exact same Arc<ComputedStyle> from Chapter 1!
        let mut css2 = ChapterCss::new(Arc::clone(&compiled), &doc2, &mut shared_cache);
        let ps2 = find_all_by_tag(&doc2, "p");
        let ch2_p0 = css2.computed(ps2[0]);
        let ch2_p1 = css2.computed(ps2[1]);
        assert!(Arc::ptr_eq(&p1_style, &ch2_p0));
        assert!(Arc::ptr_eq(&ch2_p0, &ch2_p1));
    }

    #[test]
    fn encode_font_typography_matches_kotlin_float_json_format() {
        let mut axes = BTreeMap::new();
        axes.insert("wght".to_owned(), 450.0);
        axes.insert("GRAD".to_owned(), -50.5);
        let typo = FrbFontTypography {
            variation_axes: Some(axes),
            stretch_percent: Some(100.0),
            optical_sizing: Some(true),
        };
        assert_eq!(
            encode_font_typography(&typo),
            r#"{"axes":{"GRAD":-50.5,"wght":450.0},"stretch":100.0,"optical":true}"#
        );
        assert_eq!(encode_font_typography(&FrbFontTypography::default()), "{}");
    }

    #[test]
    fn normalize_unicode_range_merges_overlaps_and_drops_full_range() {
        assert_eq!(normalize_font_unicode_range(Some("U+0-10FFFF")), None);
        assert_eq!(
            normalize_font_unicode_range(Some("u+004?, U+0050-005A, U+0040")),
            Some("U+40-5A".into())
        );
        assert_eq!(normalize_font_unicode_range(Some("U+110000")), None);
        assert_eq!(normalize_font_unicode_range(Some("U+50-40")), None);
    }

    #[test]
    fn inline_style_caching_and_mixed_sheet_inline_important() {
        let sheets = [SheetInput::new(
            r#"
            p { color: red; font-weight: 400 !important; text-align: left; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<body>
                <p id="a" style="color: blue; font-weight: 700; text-align: center !important">A</p>
                <p id="b" style="color: blue; font-weight: 700; text-align: center !important">B</p>
                <p id="c" style="margin-left: 3em !important; margin-inline-start: 2em !important; margin-left: 7em; text-align: center; text-align: right">C</p>
                <p id="d" style="margin-left: 3em !important; margin-inline-start: 2em !important; margin-left: 7em; text-align: center; text-align: right">D</p>
            </body>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let a = css.computed(find_by_id(&doc, "a"));
        let b = css.computed(find_by_id(&doc, "b"));
        assert!(Arc::ptr_eq(&a, &b));
        assert_eq!(a.foreground_color_argb, Some(0xff00_00ff_u32 as i32)); // inline beats normal sheet
        assert_eq!(a.font_weight, Some(400)); // !important sheet beats normal inline
        assert_eq!(a.text_align.as_deref(), Some("center")); // !important inline wins

        // Inline-only sheet parity
        let empty = CompiledStylesheets::empty();
        let mut empty_css = ChapterCss::standalone(empty, &doc);
        let c = empty_css.computed(find_by_id(&doc, "c"));
        let d = empty_css.computed(find_by_id(&doc, "d"));
        assert!(Arc::ptr_eq(&c, &d));
        assert_eq!(c.text_align.as_deref(), Some("right"));
        assert_eq!(c.margin_start_em, 0.0);
        assert!((c.margin_inline_start_em - 2.0).abs() < 0.001);
    }

    #[test]
    fn presentational_attributes_and_auto_direction_stay_accurate() {
        let sheets = [SheetInput::new(
            "p { margin-inline-start: 2em; margin-left: 3em; }",
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<body text="green">
                <p id="en" dir="auto">123 Hello</p>
                <p id="ar" dir="AUTO">123 مرحبا</p>
                <p id="he" dir="auto">«שלום»</p>
                <div dir="auto">
                    <p id="inh-en">World</p>
                    <p id="inh-ar">سلام</p>
                </div>
                <p id="center" align="CENTER" color="red" bgcolor="blue">Legacy</p>
            </body>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let en = css.computed(find_by_id(&doc, "en"));
        let ar = css.computed(find_by_id(&doc, "ar"));
        let he = css.computed(find_by_id(&doc, "he"));
        let inh_en = css.computed(find_by_id(&doc, "inh-en"));
        let inh_ar = css.computed(find_by_id(&doc, "inh-ar"));

        // LTR: later margin-left clears margin-inline-start
        assert_eq!(en.margin_inline_start_em, 0.0);
        assert_eq!(en.margin_start_em, 3.0);
        assert_eq!(inh_en.margin_inline_start_em, 0.0);
        assert_eq!(inh_en.margin_start_em, 3.0);

        // RTL: margin-inline-start maps to right, so margin-left does NOT clear it
        assert_eq!(ar.margin_inline_start_em, 2.0);
        assert_eq!(ar.margin_start_em, 3.0);
        assert_eq!(he.margin_inline_start_em, 2.0);
        assert_eq!(he.margin_start_em, 3.0);
        assert_eq!(inh_ar.margin_inline_start_em, 2.0);
        assert_eq!(inh_ar.margin_start_em, 3.0);

        // Body text="green" inherited on en
        assert_eq!(en.foreground_color_argb, Some(0xff00_8000_u32 as i32));
        // Legacy presentational attributes on #center
        let center = css.computed(find_by_id(&doc, "center"));
        assert_eq!(center.text_align.as_deref(), Some("center"));
        assert_eq!(center.foreground_color_argb, Some(0xffff_0000_u32 as i32));
        assert_eq!(center.background_color_argb, Some(0xff00_00ff_u32 as i32));
    }

    #[test]
    fn publisher_box_flex_centering_shrink_to_fit_and_hanging_indent() {
        let sheets = [SheetInput::new(
            r#"
            .hero {
                display: flex;
                flex-direction: column;
                justify-content: center;
                align-items: center;
                min-height: calc(100vh - 2em);
                box-sizing: border-box;
                page-break-before: always;
                page-break-after: always;
                break-inside: avoid;
            }
            .badge { display: inline-block; padding: 0.5em; }
            .verse { text-indent: -1.5em; margin-left: 2em; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        assert!(compiled.has_display_rules());
        assert!(compiled.has_page_break_before_rules());
        assert!(compiled.has_flex_centering_rules());

        let doc = parse_html(
            r#"<body>
                <div id="hero" class="hero">
                    <div id="child"><span id="badge" class="badge">Tag</span></div>
                </div>
                <p id="verse" class="verse">Line</p>
            </body>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let hero = css
            .publisher_box_style(find_by_id(&doc, "hero"))
            .expect("hero box");
        assert!(hero.center_content_vertically);
        assert!(hero.border_box);
        assert!(hero.page_break_before);
        assert!(hero.page_break_after);
        assert!(hero.break_inside_avoid);
        assert_eq!(hero.min_height_viewport_frac, Some(1.0));
        assert!((hero.min_height_offset_em - (-2.0)).abs() < 0.001);

        // Child block inside vertically-centered flex container with align-items:center
        let child = css
            .publisher_box_style(find_by_id(&doc, "child"))
            .expect("centered child");
        assert_eq!(child.horizontal_align, FrbPublisherBoxAlign::Center);

        let badge = css
            .publisher_box_style(find_by_id(&doc, "badge"))
            .expect("badge box");
        assert!(badge.shrink_to_fit);

        let verse = css
            .publisher_box_style(find_by_id(&doc, "verse"))
            .expect("verse box");
        assert!((verse.hanging_indent_em - 1.5).abs() < 0.001);
        assert!((verse.margin_left_em - 2.0).abs() < 0.001);
    }

    #[test]
    fn media_and_supports_edge_cases_and_dark_fast_path() {
        let sheets = [SheetInput::new(
            r#"
            @media (orientation: landscape) and (min-width: 500px) { p { font-weight: bold; } }
            @media not all and (hover: hover) { p { font-style: italic; } }
            @supports (font-variation-settings: "wght" 500) { p { text-align: justify; } }
            @supports (font-variant-ligatures: common-ligatures contextual) { p { hyphens: auto; } }
            @supports (font-feature-settings: "smcp" on) { p { text-transform: uppercase; } }
            @supports (unknown-prop: 1) { p { display: none; } }
            "#,
            "",
        )];
        let compiled_none = CompiledStylesheets::compile(&sheets, None);
        assert!(!compiled_none.has_display_rules()); // unknown-prop skipped
        let doc = parse_html("<body><p id='p'>Text</p></body>");
        let mut css_none = ChapterCss::standalone(Arc::clone(&compiled_none), &doc);
        let p_none = css_none.computed(find_by_id(&doc, "p"));
        assert_eq!(p_none.font_weight, None);
        assert_eq!(p_none.italic, None); // unknown feature in negated query does NOT apply
        assert_eq!(p_none.text_align.as_deref(), Some("justify"));
        assert_eq!(p_none.hyphens_auto, Some(true));
        assert_eq!(p_none.text_transform.as_deref(), Some("uppercase"));

        // No prefers-color-scheme in sheets -> dark_image_style fast path returns same Arc!
        let dark_same = css_none.dark_image_style(find_by_id(&doc, "p"));
        assert!(Arc::ptr_eq(&p_none, &dark_same));

        // Landscape viewport activates orientation + min-width
        let compiled_land = CompiledStylesheets::compile(
            &sheets,
            Some(Viewport {
                width_px: 800.0,
                height_px: Some(600.0),
                dark: None,
            }),
        );
        let mut css_land = ChapterCss::standalone(compiled_land, &doc);
        assert_eq!(
            css_land.computed(find_by_id(&doc, "p")).font_weight,
            Some(700)
        );
    }

    #[test]
    fn first_letter_feature_reset_and_generated_content_escapes_and_monospace() {
        let sheets = [SheetInput::new(
            r#"
            p.caps { font-variant-caps: small-caps; }
            p.caps::first-letter { font-variant-caps: normal; float: right; font-size: 2em; }
            code::before { content: "\2014 "; font-size: 5em; }
            p.cont::before { content: "line1\
line2"; }
            p.bad::before { content: "unclosed; }
            "#,
            "",
        )];
        let compiled = CompiledStylesheets::compile(&sheets, None);
        let doc = parse_html(
            r#"<body>
                <p id="caps" class="caps">Hello</p>
                <code id="code">x</code>
                <p id="bad" class="bad">b</p>
                <p id="cont" class="cont">c</p>
            </body>"#,
        );
        let mut css = ChapterCss::standalone(compiled, &doc);

        let fl = css
            .first_letter(find_by_id(&doc, "caps"))
            .expect("first-letter");
        assert!(!fl.left_side);
        assert_eq!(fl.font_feature_settings.as_deref(), Some("")); // explicitly clears parent 'smcp' 1

        let code_before = css
            .generated(find_by_id(&doc, "code"), false)
            .expect("code before");
        assert_eq!(code_before.text, "\u{2014}"); // trailing space after hex escape consumed
        assert_eq!(code_before.font_family_name.as_deref(), Some("monospace"));
        assert!((code_before.scale - 2.5).abs() < 0.001); // clamped to 2.5

        assert!(css.generated(find_by_id(&doc, "bad"), false).is_none());
        assert_eq!(parse_content_value("\"unclosed"), None);
        assert_eq!(parse_content_value("\"raw\nnewline\""), None);
        let cont = css
            .generated(find_by_id(&doc, "cont"), false)
            .expect("escaped newline continuation");
        assert_eq!(cont.text, "line1line2");
    }

    #[test]
    fn shared_style_cache_isolates_distinct_stylesheets_and_respects_rule_caps() {
        let sheet_a =
            CompiledStylesheets::compile(&[SheetInput::new("p { color: red; }", "")], None);
        let sheet_b =
            CompiledStylesheets::compile(&[SheetInput::new("p { color: blue; }", "")], None);
        let doc = parse_html("<body><p id='p'>Hello</p></body>");
        let p_id = find_by_id(&doc, "p");

        let mut shared_cache = SharedStyleCache::new();
        let a1 = {
            let mut css = ChapterCss::new(Arc::clone(&sheet_a), &doc, &mut shared_cache);
            css.computed(p_id)
        };
        let b1 = {
            let mut css = ChapterCss::new(Arc::clone(&sheet_b), &doc, &mut shared_cache);
            css.computed(p_id)
        };
        let a2 = {
            let mut css = ChapterCss::new(Arc::clone(&sheet_a), &doc, &mut shared_cache);
            css.computed(p_id)
        };

        assert_eq!(a1.foreground_color_argb, Some(0xffff_0000_u32 as i32));
        assert_eq!(b1.foreground_color_argb, Some(0xff00_00ff_u32 as i32));
        assert!(Arc::ptr_eq(&a1, &a2));

        // Universal rule bucket cap = MAX_UNIVERSAL_RULES (1024)
        let mut many_universal = String::new();
        for i in 0..(MAX_UNIVERSAL_RULES + 50) {
            many_universal.push_str(&format!("* {{ --v{i}: 1; }}\n"));
        }
        let capped = CompiledStylesheets::compile(&[SheetInput::new(many_universal, "")], None);
        assert_eq!(capped.pools[POOL_NORMAL].len(), MAX_UNIVERSAL_RULES);
        // Custom property in normal rules conservatively sets display/page-break/flex flags.
        assert!(capped.has_display_rules());
        assert!(capped.has_page_break_before_rules());
        assert!(capped.has_flex_centering_rules());
    }
}
