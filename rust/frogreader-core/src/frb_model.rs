//! Universal native Rust FRB v2 binary model encoder.
//!
//! Produces payloads byte-for-byte compatible with Kotlin's `FrbModelCodec` /
//! `FrbModelEncoder` (schema revision 8), including short-string interning,
//! `@InternedFrbStyle` per-descriptor style dictionaries, `shouldEncodeElementDefault = false`
//! default omission, zigzag integer encoding, little-endian IEEE 754 floats,
//! and polymorphic sealed-interface framing.
//!
//! Shared serialization foundation for native Rust parsers (FB2, EPUB, etc.).

#![allow(dead_code)]

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::hash::{Hash, Hasher};
use std::path::Path;

// --------------------------------------------------------------------------- Constants

pub(crate) const FRB_MODEL_MAX_DEPTH: usize = 256;
pub(crate) const FRB_MODEL_DICTIONARY_LIMIT: usize = 4096;
pub(crate) const FRB_MODEL_SHORT_STRING_BYTES: usize = 128;

/// Compose `Color.Unspecified.value` (`Color(0f, 0f, 0f, 0f, ColorSpaces.Unspecified)`).
pub(crate) const COMPOSE_COLOR_UNSPECIFIED: u64 = 16;

/// Packed Compose sRGB `Color(argb: Int).value` representation.
#[inline]
pub(crate) const fn color_argb_to_compose_ulong(argb: i32) -> u64 {
    ((argb as u32) as u64) << 32
}

/// Text decoration bitmask values for `FrbSpanStyle.decoration`.
pub(crate) const TEXT_DECORATION_NONE: i32 = 0;
pub(crate) const TEXT_DECORATION_UNDERLINE: i32 = 1;
pub(crate) const TEXT_DECORATION_LINE_THROUGH: i32 = 2;

/// Standard `AnnotatedString` annotation tags matching `BookContent.kt` and `FontTypography.kt`.
pub(crate) const FOOTNOTE_TAG: &str = "footnote";
pub(crate) const LINK_TAG: &str = "internalLink";
pub(crate) const EXTERNAL_LINK_TAG: &str = "externalLink";
pub(crate) const INLINE_IMAGE_TAG: &str = "inlineImage";
pub(crate) const INLINE_IMAGE_ALT_TAG: &str = "inlineImageAlt";
pub(crate) const INLINE_IMAGE_CHAR: char = '\u{FFFC}';
pub(crate) const BIDI_TAG: &str = "frogreader:bidi";
pub(crate) const HIDDEN_TEXT_TAG: &str = "frogreader:hidden";
pub(crate) const INLINE_FONT_FAMILY_TAG: &str = "frogreader:font-family";
pub(crate) const INLINE_FONT_TYPOGRAPHY_TAG: &str = "frogreader:font-typography";

/// Kotlin `ParsedParagraph.align` integer codes.
pub(crate) const COMPOSE_TEXT_ALIGN_UNSPECIFIED: i32 = 0;
pub(crate) const COMPOSE_TEXT_ALIGN_LEFT: i32 = 1;
pub(crate) const COMPOSE_TEXT_ALIGN_RIGHT: i32 = 2;
pub(crate) const COMPOSE_TEXT_ALIGN_CENTER: i32 = 3;
pub(crate) const COMPOSE_TEXT_ALIGN_JUSTIFY: i32 = 4;
pub(crate) const COMPOSE_TEXT_ALIGN_START: i32 = 5;
pub(crate) const COMPOSE_TEXT_ALIGN_END: i32 = 6;

/// Kotlin `ParsedParagraph.direction` integer codes.
pub(crate) const COMPOSE_TEXT_DIRECTION_UNSPECIFIED: i32 = 0;
pub(crate) const COMPOSE_TEXT_DIRECTION_LTR: i32 = 1;
pub(crate) const COMPOSE_TEXT_DIRECTION_RTL: i32 = 2;
pub(crate) const COMPOSE_TEXT_DIRECTION_CONTENT: i32 = 3;
pub(crate) const COMPOSE_TEXT_DIRECTION_CONTENT_OR_LTR: i32 = 4;
pub(crate) const COMPOSE_TEXT_DIRECTION_CONTENT_OR_RTL: i32 = 5;

// Polymorphic Kotlin class serial names.
const TYPE_CONTENT_PARAGRAPH: &str = "com.example.frogreader.data.model.ContentElement.Paragraph";
const TYPE_CONTENT_HEADING: &str = "com.example.frogreader.data.model.ContentElement.Heading";
const TYPE_CONTENT_IMAGE: &str = "com.example.frogreader.data.model.ContentElement.Image";
const TYPE_CONTENT_DIVIDER: &str = "com.example.frogreader.data.model.ContentElement.Divider";
const TYPE_CONTENT_SPACER: &str = "com.example.frogreader.data.model.ContentElement.Spacer";
const TYPE_CONTENT_TABLE: &str = "com.example.frogreader.data.model.ContentElement.Table";

const TYPE_NAV_READING_ORDER: &str =
    "com.example.frogreader.data.model.BookNavigationTarget.ReadingOrder";
const TYPE_NAV_LINKED: &str = "com.example.frogreader.data.model.BookNavigationTarget.Linked";

const TYPE_SOURCE_EPUB_ARCHIVE: &str =
    "com.example.frogreader.data.model.PublisherSourceDescriptor.EpubArchive";
const TYPE_SOURCE_KF8_BUNDLE: &str =
    "com.example.frogreader.data.model.PublisherSourceDescriptor.Kf8Bundle";

const TYPE_VIEWPORT_CSS_PIXELS: &str =
    "com.example.frogreader.data.model.PublisherViewportDimension.CssPixels";
const TYPE_VIEWPORT_DEVICE: &str =
    "com.example.frogreader.data.model.PublisherViewportDimension.Device";

// --------------------------------------------------------------------------- Float Canonical Helpers

/// Matches Java `Float.floatToIntBits` / `Float.compare` semantics:
/// canonicalizes NaNs to `0x7FC0_0000`, preserves `-0.0` vs `+0.0`.
#[inline]
fn f32_canonical_bits(value: f32) -> u32 {
    if value.is_nan() {
        0x7FC0_0000
    } else {
        value.to_bits()
    }
}

#[inline]
fn f32_eq(a: f32, b: f32) -> bool {
    f32_canonical_bits(a) == f32_canonical_bits(b)
}

#[inline]
fn opt_f32_eq(a: Option<f32>, b: Option<f32>) -> bool {
    match (a, b) {
        (Some(x), Some(y)) => f32_eq(x, y),
        (None, None) => true,
        _ => false,
    }
}

#[inline]
fn hash_f32<H: Hasher>(value: f32, state: &mut H) {
    f32_canonical_bits(value).hash(state);
}

#[inline]
fn hash_opt_f32<H: Hasher>(value: Option<f32>, state: &mut H) {
    match value {
        Some(v) => {
            1u8.hash(state);
            hash_f32(v, state);
        }
        None => 0u8.hash(state),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
struct F32Key(u32);

impl F32Key {
    #[inline]
    fn from_f32(value: f32) -> Self {
        Self(f32_canonical_bits(value))
    }

    #[inline]
    fn to_f32(self) -> f32 {
        f32::from_bits(self.0)
    }
}

// --------------------------------------------------------------------------- AnnotatedString Model

#[derive(Clone, Copy, Debug, Default)]
pub(crate) enum FrbTextUnit {
    #[default]
    Unspecified,
    Sp(f32),
    Em(f32),
}

impl FrbTextUnit {
    #[inline]
    pub(crate) fn is_unspecified(self) -> bool {
        matches!(self, Self::Unspecified)
    }
}

impl PartialEq for FrbTextUnit {
    fn eq(&self, other: &Self) -> bool {
        match (*self, *other) {
            (Self::Unspecified, Self::Unspecified) => true,
            (Self::Sp(a), Self::Sp(b)) | (Self::Em(a), Self::Em(b)) => f32_eq(a, b),
            _ => false,
        }
    }
}

impl Eq for FrbTextUnit {}

impl Hash for FrbTextUnit {
    fn hash<H: Hasher>(&self, state: &mut H) {
        match *self {
            Self::Unspecified => 0u8.hash(state),
            Self::Sp(v) => {
                1u8.hash(state);
                hash_f32(v, state);
            }
            Self::Em(v) => {
                2u8.hash(state);
                hash_f32(v, state);
            }
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct FrbShadow {
    pub color: u64,
    pub x: f32,
    pub y: f32,
    pub blur: f32,
}

impl PartialEq for FrbShadow {
    fn eq(&self, other: &Self) -> bool {
        self.color == other.color
            && f32_eq(self.x, other.x)
            && f32_eq(self.y, other.y)
            && f32_eq(self.blur, other.blur)
    }
}

impl Eq for FrbShadow {}

impl Hash for FrbShadow {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.color.hash(state);
        hash_f32(self.x, state);
        hash_f32(self.y, state);
        hash_f32(self.blur, state);
    }
}

/// Wire equivalent of `ParsedSpan` (`@InternedFrbStyle`).
#[derive(Clone, Debug)]
pub(crate) struct FrbSpanStyle {
    /// Packed Compose `Color.value` (default: `COMPOSE_COLOR_UNSPECIFIED`).
    pub color: u64,
    pub size: FrbTextUnit,
    pub weight: Option<i32>,
    pub italic: Option<bool>,
    /// `"all"`, `"none"`, `"weight"`, or `"style"`.
    pub synthesis: Option<String>,
    /// `"default"`, `"sans"`, `"serif"`, `"mono"`, or `"cursive"`.
    pub family: Option<String>,
    pub features: Option<String>,
    pub spacing: FrbTextUnit,
    pub baseline: Option<f32>,
    pub scale_x: Option<f32>,
    pub skew_x: Option<f32>,
    pub locales: Option<Vec<String>>,
    /// Packed Compose `Color.value` (default: `COMPOSE_COLOR_UNSPECIFIED`).
    pub background: u64,
    /// Bitmask: 1 = Underline, 2 = LineThrough.
    pub decoration: Option<i32>,
    pub shadow: Option<FrbShadow>,
}

impl Default for FrbSpanStyle {
    fn default() -> Self {
        Self {
            color: COMPOSE_COLOR_UNSPECIFIED,
            size: FrbTextUnit::Unspecified,
            weight: None,
            italic: None,
            synthesis: None,
            family: None,
            features: None,
            spacing: FrbTextUnit::Unspecified,
            baseline: None,
            scale_x: None,
            skew_x: None,
            locales: None,
            background: COMPOSE_COLOR_UNSPECIFIED,
            decoration: None,
            shadow: None,
        }
    }
}

impl PartialEq for FrbSpanStyle {
    fn eq(&self, other: &Self) -> bool {
        self.color == other.color
            && self.size == other.size
            && self.weight == other.weight
            && self.italic == other.italic
            && self.synthesis == other.synthesis
            && self.family == other.family
            && self.features == other.features
            && self.spacing == other.spacing
            && opt_f32_eq(self.baseline, other.baseline)
            && opt_f32_eq(self.scale_x, other.scale_x)
            && opt_f32_eq(self.skew_x, other.skew_x)
            && self.locales == other.locales
            && self.background == other.background
            && self.decoration == other.decoration
            && self.shadow == other.shadow
    }
}

impl Eq for FrbSpanStyle {}

impl Hash for FrbSpanStyle {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.color.hash(state);
        self.size.hash(state);
        self.weight.hash(state);
        self.italic.hash(state);
        self.synthesis.hash(state);
        self.family.hash(state);
        self.features.hash(state);
        self.spacing.hash(state);
        hash_opt_f32(self.baseline, state);
        hash_opt_f32(self.scale_x, state);
        hash_opt_f32(self.skew_x, state);
        self.locales.hash(state);
        self.background.hash(state);
        self.decoration.hash(state);
        self.shadow.hash(state);
    }
}

/// Wire equivalent of `ParsedParagraph` (`@InternedFrbStyle`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct FrbParagraphAnnotation {
    pub align: i32,
    pub direction: i32,
    pub height: FrbTextUnit,
    pub first_indent: Option<FrbTextUnit>,
    pub rest_indent: Option<FrbTextUnit>,
}

/// Wire equivalent of `ParsedLinkStyles` (`@InternedFrbStyle`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub(crate) struct FrbLinkStyles {
    pub style: Option<FrbSpanStyle>,
    pub focused: Option<FrbSpanStyle>,
    pub hovered: Option<FrbSpanStyle>,
    pub pressed: Option<FrbSpanStyle>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FrbTextAnnotation {
    String(String),
    Span(Box<FrbSpanStyle>),
    Paragraph(Box<FrbParagraphAnnotation>),
    Tts(String),
    Url(String),
    Link {
        url: String,
        styles: Option<Box<FrbLinkStyles>>,
    },
    Click {
        tag: String,
        styles: Option<Box<FrbLinkStyles>>,
    },
}

/// One annotation range over UTF-16 code-unit offsets `[start, end)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbTextRange {
    pub start: i32,
    pub end: i32,
    pub tag: String,
    pub annotation: FrbTextAnnotation,
}

impl FrbTextRange {
    pub(crate) fn string(
        start: i32,
        end: i32,
        tag: impl Into<String>,
        value: impl Into<String>,
    ) -> Self {
        Self {
            start,
            end,
            tag: tag.into(),
            annotation: FrbTextAnnotation::String(value.into()),
        }
    }

    pub(crate) fn span(start: i32, end: i32, style: FrbSpanStyle) -> Self {
        Self {
            start,
            end,
            tag: String::new(),
            annotation: FrbTextAnnotation::Span(Box::new(style)),
        }
    }

    pub(crate) fn paragraph(start: i32, end: i32, style: FrbParagraphAnnotation) -> Self {
        Self {
            start,
            end,
            tag: String::new(),
            annotation: FrbTextAnnotation::Paragraph(Box::new(style)),
        }
    }

    pub(crate) fn link(
        start: i32,
        end: i32,
        url: impl Into<String>,
        styles: Option<FrbLinkStyles>,
    ) -> Self {
        Self {
            start,
            end,
            tag: String::new(),
            annotation: FrbTextAnnotation::Link {
                url: url.into(),
                styles: styles.map(Box::new),
            },
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbAnnotatedString {
    pub text: String,
    pub ranges: Vec<FrbTextRange>,
}

impl FrbAnnotatedString {
    pub(crate) fn plain(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            ranges: Vec::new(),
        }
    }
}

// --------------------------------------------------------------------------- Enums

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbBlockAlign {
    Start = 0,
    Center = 1,
    End = 2,
    Justify = 3,
    Left = 4,
    Right = 5,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbBookTextDirection {
    Ltr = 0,
    Rtl = 1,
    Auto = 2,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbParagraphStyle {
    #[default]
    Normal = 0,
    Quote = 1,
    Poem = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPublisherRowLayout {
    Natural = 0,
    SpaceBetween = 1,
    SpaceAround = 2,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPageProgression {
    #[default]
    Default = 0,
    Ltr = 1,
    Rtl = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPublisherBorderStyle {
    None = 0,
    Solid = 1,
    Dashed = 2,
    Dotted = 3,
    Double = 4,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPublisherBoxAlign {
    #[default]
    Start = 0,
    Center = 1,
    End = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPublisherFloatSide {
    Left = 0,
    Right = 1,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
#[repr(u64)]
pub(crate) enum FrbPublisherClear {
    #[default]
    None = 0,
    Left = 1,
    Right = 2,
    Both = 3,
}

// --------------------------------------------------------------------------- Block & Typography Styles

#[derive(Clone, Debug, Default)]
pub(crate) struct FrbFontTypography {
    pub variation_axes: Option<BTreeMap<String, f32>>,
    pub stretch_percent: Option<f32>,
    pub optical_sizing: Option<bool>,
}

impl FrbFontTypography {
    pub(crate) fn is_empty(&self) -> bool {
        self.variation_axes.is_none()
            && self.stretch_percent.is_none()
            && self.optical_sizing.is_none()
    }
}

impl PartialEq for FrbFontTypography {
    fn eq(&self, other: &Self) -> bool {
        let axes_eq = match (&self.variation_axes, &other.variation_axes) {
            (Some(a), Some(b)) => {
                a.len() == b.len()
                    && a.iter()
                        .zip(b.iter())
                        .all(|((ka, va), (kb, vb))| ka == kb && f32_eq(*va, *vb))
            }
            (None, None) => true,
            _ => false,
        };
        axes_eq
            && opt_f32_eq(self.stretch_percent, other.stretch_percent)
            && self.optical_sizing == other.optical_sizing
    }
}

impl Eq for FrbFontTypography {}

impl Hash for FrbFontTypography {
    fn hash<H: Hasher>(&self, state: &mut H) {
        match &self.variation_axes {
            Some(axes) => {
                1u8.hash(state);
                axes.len().hash(state);
                for (k, v) in axes {
                    k.hash(state);
                    hash_f32(*v, state);
                }
            }
            None => 0u8.hash(state),
        }
        hash_opt_f32(self.stretch_percent, state);
        self.optical_sizing.hash(state);
    }
}

#[derive(Clone, Debug)]
pub(crate) struct FrbFloatImage {
    pub path: String,
    pub width_frac: f32,
    pub left: bool,
    pub alt_text: Option<String>,
}

impl PartialEq for FrbFloatImage {
    fn eq(&self, other: &Self) -> bool {
        self.path == other.path
            && f32_eq(self.width_frac, other.width_frac)
            && self.left == other.left
            && self.alt_text == other.alt_text
    }
}

impl Eq for FrbFloatImage {}

impl Hash for FrbFloatImage {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.path.hash(state);
        hash_f32(self.width_frac, state);
        self.left.hash(state);
        self.alt_text.hash(state);
    }
}

#[derive(Clone, Debug)]
pub(crate) struct FrbFirstLetterStyle {
    pub scale: f32,
    pub bold: Option<bool>,
    pub italic: Option<bool>,
    pub is_drop_cap: bool,
    pub font_family: Option<String>,
    pub foreground_color_argb: Option<i32>,
    pub background_color_argb: Option<i32>,
    pub left_side: bool,
    pub direction: Option<FrbBookTextDirection>,
    pub language: Option<String>,
    pub source_text_length: Option<i32>,
    pub font_weight: Option<i32>,
    pub font_typography: Option<FrbFontTypography>,
    pub font_feature_settings: Option<String>,
}

impl FrbFirstLetterStyle {
    pub(crate) fn new(scale: f32, is_drop_cap: bool) -> Self {
        Self {
            scale,
            bold: None,
            italic: None,
            is_drop_cap,
            font_family: None,
            foreground_color_argb: None,
            background_color_argb: None,
            left_side: true,
            direction: None,
            language: None,
            source_text_length: None,
            font_weight: None,
            font_typography: None,
            font_feature_settings: None,
        }
    }
}

impl PartialEq for FrbFirstLetterStyle {
    fn eq(&self, other: &Self) -> bool {
        f32_eq(self.scale, other.scale)
            && self.bold == other.bold
            && self.italic == other.italic
            && self.is_drop_cap == other.is_drop_cap
            && self.font_family == other.font_family
            && self.foreground_color_argb == other.foreground_color_argb
            && self.background_color_argb == other.background_color_argb
            && self.left_side == other.left_side
            && self.direction == other.direction
            && self.language == other.language
            && self.source_text_length == other.source_text_length
            && self.font_weight == other.font_weight
            && self.font_typography == other.font_typography
            && self.font_feature_settings == other.font_feature_settings
    }
}

impl Eq for FrbFirstLetterStyle {}

impl Hash for FrbFirstLetterStyle {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_f32(self.scale, state);
        self.bold.hash(state);
        self.italic.hash(state);
        self.is_drop_cap.hash(state);
        self.font_family.hash(state);
        self.foreground_color_argb.hash(state);
        self.background_color_argb.hash(state);
        self.left_side.hash(state);
        self.direction.hash(state);
        self.language.hash(state);
        self.source_text_length.hash(state);
        self.font_weight.hash(state);
        self.font_typography.hash(state);
        self.font_feature_settings.hash(state);
    }
}

/// Wire equivalent of `BlockStyle` (`@InternedFrbStyle`).
#[derive(Clone, Debug, Default)]
pub(crate) struct FrbBlockStyle {
    pub align: Option<FrbBlockAlign>,
    pub italic: Option<bool>,
    pub bold: Option<bool>,
    pub font_scale: Option<f32>,
    pub indent_start_frac: f32,
    pub indent_start_em: f32,
    pub indent_end_frac: f32,
    pub indent_end_em: f32,
    pub indent_left_frac: f32,
    pub indent_left_em: f32,
    pub indent_right_frac: f32,
    pub indent_right_em: f32,
    pub first_line_indent: Option<bool>,
    pub first_line_indent_em: Option<f32>,
    pub is_css_text_line: bool,
    pub space_before_em: f32,
    pub space_after_em: f32,
    pub space_before_specified: bool,
    pub space_after_specified: bool,
    pub font_family: Option<String>,
    pub line_height_mult: Option<f32>,
    pub hyphens: Option<bool>,
    pub page_break_before: bool,
    pub first_letter: Option<Box<FrbFirstLetterStyle>>,
    pub float_image: Option<Box<FrbFloatImage>>,
    pub language: Option<String>,
    pub direction: Option<FrbBookTextDirection>,
    pub foreground_color_argb: Option<i32>,
    pub background_color_argb: Option<i32>,
    pub font_feature_settings: Option<String>,
    pub font_weight: Option<i32>,
    pub font_typography: Option<Box<FrbFontTypography>>,
}

impl FrbBlockStyle {
    pub(crate) fn is_default(&self) -> bool {
        *self == Self::default()
    }
}

impl PartialEq for FrbBlockStyle {
    fn eq(&self, other: &Self) -> bool {
        self.align == other.align
            && self.italic == other.italic
            && self.bold == other.bold
            && opt_f32_eq(self.font_scale, other.font_scale)
            && f32_eq(self.indent_start_frac, other.indent_start_frac)
            && f32_eq(self.indent_start_em, other.indent_start_em)
            && f32_eq(self.indent_end_frac, other.indent_end_frac)
            && f32_eq(self.indent_end_em, other.indent_end_em)
            && f32_eq(self.indent_left_frac, other.indent_left_frac)
            && f32_eq(self.indent_left_em, other.indent_left_em)
            && f32_eq(self.indent_right_frac, other.indent_right_frac)
            && f32_eq(self.indent_right_em, other.indent_right_em)
            && self.first_line_indent == other.first_line_indent
            && opt_f32_eq(self.first_line_indent_em, other.first_line_indent_em)
            && self.is_css_text_line == other.is_css_text_line
            && f32_eq(self.space_before_em, other.space_before_em)
            && f32_eq(self.space_after_em, other.space_after_em)
            && self.space_before_specified == other.space_before_specified
            && self.space_after_specified == other.space_after_specified
            && self.font_family == other.font_family
            && opt_f32_eq(self.line_height_mult, other.line_height_mult)
            && self.hyphens == other.hyphens
            && self.page_break_before == other.page_break_before
            && self.first_letter == other.first_letter
            && self.float_image == other.float_image
            && self.language == other.language
            && self.direction == other.direction
            && self.foreground_color_argb == other.foreground_color_argb
            && self.background_color_argb == other.background_color_argb
            && self.font_feature_settings == other.font_feature_settings
            && self.font_weight == other.font_weight
            && self.font_typography == other.font_typography
    }
}

impl Eq for FrbBlockStyle {}

impl Hash for FrbBlockStyle {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.align.hash(state);
        self.italic.hash(state);
        self.bold.hash(state);
        hash_opt_f32(self.font_scale, state);
        hash_f32(self.indent_start_frac, state);
        hash_f32(self.indent_start_em, state);
        hash_f32(self.indent_end_frac, state);
        hash_f32(self.indent_end_em, state);
        hash_f32(self.indent_left_frac, state);
        hash_f32(self.indent_left_em, state);
        hash_f32(self.indent_right_frac, state);
        hash_f32(self.indent_right_em, state);
        self.first_line_indent.hash(state);
        hash_opt_f32(self.first_line_indent_em, state);
        self.is_css_text_line.hash(state);
        hash_f32(self.space_before_em, state);
        hash_f32(self.space_after_em, state);
        self.space_before_specified.hash(state);
        self.space_after_specified.hash(state);
        self.font_family.hash(state);
        hash_opt_f32(self.line_height_mult, state);
        self.hyphens.hash(state);
        self.page_break_before.hash(state);
        self.first_letter.hash(state);
        self.float_image.hash(state);
        self.language.hash(state);
        self.direction.hash(state);
        self.foreground_color_argb.hash(state);
        self.background_color_argb.hash(state);
        self.font_feature_settings.hash(state);
        self.font_weight.hash(state);
        self.font_typography.hash(state);
    }
}

// --------------------------------------------------------------------------- Publisher Box Layout

#[derive(Clone, Copy, Debug)]
pub(crate) struct FrbPublisherBorderSide {
    pub width_em: f32,
    pub color_argb: i32,
    pub style: FrbPublisherBorderStyle,
    pub uses_current_color: bool,
}

impl PartialEq for FrbPublisherBorderSide {
    fn eq(&self, other: &Self) -> bool {
        f32_eq(self.width_em, other.width_em)
            && self.color_argb == other.color_argb
            && self.style == other.style
            && self.uses_current_color == other.uses_current_color
    }
}

impl Eq for FrbPublisherBorderSide {}

impl Hash for FrbPublisherBorderSide {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_f32(self.width_em, state);
        self.color_argb.hash(state);
        self.style.hash(state);
        self.uses_current_color.hash(state);
    }
}

/// Wire equivalent of `PublisherBoxStyle` (`@InternedFrbStyle`).
#[derive(Clone, Debug, Default)]
pub(crate) struct FrbPublisherBoxStyle {
    pub margin_top_em: f32,
    pub margin_right_em: f32,
    pub margin_right_frac: f32,
    pub margin_bottom_em: f32,
    pub margin_left_em: f32,
    pub margin_left_frac: f32,
    pub padding_top_em: f32,
    pub padding_right_em: f32,
    pub padding_right_frac: f32,
    pub padding_bottom_em: f32,
    pub padding_left_em: f32,
    pub padding_left_frac: f32,
    pub background_color_argb: Option<i32>,
    pub border_top: Option<FrbPublisherBorderSide>,
    pub border_right: Option<FrbPublisherBorderSide>,
    pub border_bottom: Option<FrbPublisherBorderSide>,
    pub border_left: Option<FrbPublisherBorderSide>,
    pub width_frac: Option<f32>,
    pub width_em: Option<f32>,
    pub shrink_to_fit: bool,
    pub hanging_indent_em: f32,
    pub fit_text_flow: bool,
    pub horizontal_align: FrbPublisherBoxAlign,
    pub break_inside_avoid: bool,
    pub page_break_before: bool,
    pub page_break_after: bool,
    pub min_height_em: Option<f32>,
    pub min_height_viewport_frac: Option<f32>,
    pub min_height_offset_em: f32,
    pub center_content_vertically: bool,
    pub border_box: bool,
    pub max_width_em: Option<f32>,
    pub max_width_frac: Option<f32>,
}

impl FrbPublisherBoxStyle {
    pub(crate) fn is_default(&self) -> bool {
        *self == Self::default()
    }
}

impl PartialEq for FrbPublisherBoxStyle {
    fn eq(&self, other: &Self) -> bool {
        f32_eq(self.margin_top_em, other.margin_top_em)
            && f32_eq(self.margin_right_em, other.margin_right_em)
            && f32_eq(self.margin_right_frac, other.margin_right_frac)
            && f32_eq(self.margin_bottom_em, other.margin_bottom_em)
            && f32_eq(self.margin_left_em, other.margin_left_em)
            && f32_eq(self.margin_left_frac, other.margin_left_frac)
            && f32_eq(self.padding_top_em, other.padding_top_em)
            && f32_eq(self.padding_right_em, other.padding_right_em)
            && f32_eq(self.padding_right_frac, other.padding_right_frac)
            && f32_eq(self.padding_bottom_em, other.padding_bottom_em)
            && f32_eq(self.padding_left_em, other.padding_left_em)
            && f32_eq(self.padding_left_frac, other.padding_left_frac)
            && self.background_color_argb == other.background_color_argb
            && self.border_top == other.border_top
            && self.border_right == other.border_right
            && self.border_bottom == other.border_bottom
            && self.border_left == other.border_left
            && opt_f32_eq(self.width_frac, other.width_frac)
            && opt_f32_eq(self.width_em, other.width_em)
            && self.shrink_to_fit == other.shrink_to_fit
            && f32_eq(self.hanging_indent_em, other.hanging_indent_em)
            && self.fit_text_flow == other.fit_text_flow
            && self.horizontal_align == other.horizontal_align
            && self.break_inside_avoid == other.break_inside_avoid
            && self.page_break_before == other.page_break_before
            && self.page_break_after == other.page_break_after
            && opt_f32_eq(self.min_height_em, other.min_height_em)
            && opt_f32_eq(
                self.min_height_viewport_frac,
                other.min_height_viewport_frac,
            )
            && f32_eq(self.min_height_offset_em, other.min_height_offset_em)
            && self.center_content_vertically == other.center_content_vertically
            && self.border_box == other.border_box
            && opt_f32_eq(self.max_width_em, other.max_width_em)
            && opt_f32_eq(self.max_width_frac, other.max_width_frac)
    }
}

impl Eq for FrbPublisherBoxStyle {}

impl Hash for FrbPublisherBoxStyle {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_f32(self.margin_top_em, state);
        hash_f32(self.margin_right_em, state);
        hash_f32(self.margin_right_frac, state);
        hash_f32(self.margin_bottom_em, state);
        hash_f32(self.margin_left_em, state);
        hash_f32(self.margin_left_frac, state);
        hash_f32(self.padding_top_em, state);
        hash_f32(self.padding_right_em, state);
        hash_f32(self.padding_right_frac, state);
        hash_f32(self.padding_bottom_em, state);
        hash_f32(self.padding_left_em, state);
        hash_f32(self.padding_left_frac, state);
        self.background_color_argb.hash(state);
        self.border_top.hash(state);
        self.border_right.hash(state);
        self.border_bottom.hash(state);
        self.border_left.hash(state);
        hash_opt_f32(self.width_frac, state);
        hash_opt_f32(self.width_em, state);
        self.shrink_to_fit.hash(state);
        hash_f32(self.hanging_indent_em, state);
        self.fit_text_flow.hash(state);
        self.horizontal_align.hash(state);
        self.break_inside_avoid.hash(state);
        self.page_break_before.hash(state);
        self.page_break_after.hash(state);
        hash_opt_f32(self.min_height_em, state);
        hash_opt_f32(self.min_height_viewport_frac, state);
        hash_f32(self.min_height_offset_em, state);
        self.center_content_vertically.hash(state);
        self.border_box.hash(state);
        hash_opt_f32(self.max_width_em, state);
        hash_opt_f32(self.max_width_frac, state);
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherBoxSpan {
    pub id: String,
    pub parent_id: Option<String>,
    pub start_element: i32,
    pub end_element_exclusive: i32,
    pub style: FrbPublisherBoxStyle,
    pub float_side: Option<FrbPublisherFloatSide>,
    pub clear: FrbPublisherClear,
    pub draw_start: bool,
    pub draw_end: bool,
}

impl FrbPublisherBoxSpan {
    pub(crate) fn new(
        id: impl Into<String>,
        start_element: i32,
        end_element_exclusive: i32,
    ) -> Self {
        Self {
            id: id.into(),
            parent_id: None,
            start_element,
            end_element_exclusive,
            style: FrbPublisherBoxStyle::default(),
            float_side: None,
            clear: FrbPublisherClear::None,
            draw_start: true,
            draw_end: true,
        }
    }
}

// --------------------------------------------------------------------------- Table & ContentElement

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbTableCellStyle {
    pub block: Option<Box<FrbBlockStyle>>,
    pub publisher_box: Option<Box<FrbPublisherBoxStyle>>,
    pub align: Option<FrbBlockAlign>,
    pub publisher_padding_specified: bool,
    pub publisher_border_specified: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbTableCell {
    pub text: FrbAnnotatedString,
    pub col_span: i32,
    pub row_span: i32,
    pub align: Option<FrbBlockAlign>,
    pub header: bool,
    pub block: Option<Box<FrbBlockStyle>>,
    pub publisher_box: Option<Box<FrbPublisherBoxStyle>>,
    pub publisher_padding_specified: bool,
    pub publisher_border_specified: bool,
    pub narrow_style: Option<Box<FrbTableCellStyle>>,
}

impl FrbTableCell {
    pub(crate) fn new(text: FrbAnnotatedString) -> Self {
        Self {
            text,
            col_span: 1,
            row_span: 1,
            align: None,
            header: false,
            block: None,
            publisher_box: None,
            publisher_padding_specified: false,
            publisher_border_specified: false,
            narrow_style: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbTableRow {
    pub cells: Vec<FrbTableCell>,
    pub is_header: bool,
}

#[derive(Clone, Debug)]
pub(crate) struct FrbImageElement {
    pub path: String,
    pub width_frac: Option<f32>,
    pub width_em: Option<f32>,
    pub height_em: Option<f32>,
    pub alt_text: Option<String>,
    pub space_before_specified: bool,
    pub space_after_specified: bool,
    pub max_height_em: Option<f32>,
    pub max_height_viewport_frac: Option<f32>,
    pub max_width_em: Option<f32>,
    pub max_width_frac: Option<f32>,
    pub dark_mode_invert: bool,
    pub dark_mode_background_argb: Option<i32>,
    pub dark_mode_background_current_color: bool,
}

impl FrbImageElement {
    pub(crate) fn new(path: impl Into<String>) -> Self {
        Self {
            path: path.into(),
            width_frac: None,
            width_em: None,
            height_em: None,
            alt_text: None,
            space_before_specified: false,
            space_after_specified: false,
            max_height_em: None,
            max_height_viewport_frac: None,
            max_width_em: None,
            max_width_frac: None,
            dark_mode_invert: false,
            dark_mode_background_argb: None,
            dark_mode_background_current_color: false,
        }
    }
}

impl PartialEq for FrbImageElement {
    fn eq(&self, other: &Self) -> bool {
        self.path == other.path
            && opt_f32_eq(self.width_frac, other.width_frac)
            && opt_f32_eq(self.width_em, other.width_em)
            && opt_f32_eq(self.height_em, other.height_em)
            && self.alt_text == other.alt_text
            && self.space_before_specified == other.space_before_specified
            && self.space_after_specified == other.space_after_specified
            && opt_f32_eq(self.max_height_em, other.max_height_em)
            && opt_f32_eq(
                self.max_height_viewport_frac,
                other.max_height_viewport_frac,
            )
            && opt_f32_eq(self.max_width_em, other.max_width_em)
            && opt_f32_eq(self.max_width_frac, other.max_width_frac)
            && self.dark_mode_invert == other.dark_mode_invert
            && self.dark_mode_background_argb == other.dark_mode_background_argb
            && self.dark_mode_background_current_color == other.dark_mode_background_current_color
    }
}

impl Eq for FrbImageElement {}

#[derive(Clone, Debug)]
pub(crate) struct FrbTableElement {
    pub rows: Vec<FrbTableRow>,
    pub block: Option<Box<FrbBlockStyle>>,
    pub publisher_row_layout: Option<FrbPublisherRowLayout>,
    pub center_cells_vertically: bool,
    pub stack_cells_below_css_px: Option<f32>,
    pub publisher_box: Option<Box<FrbPublisherBoxStyle>>,
}

impl FrbTableElement {
    pub(crate) fn new(rows: Vec<FrbTableRow>) -> Self {
        Self {
            rows,
            block: None,
            publisher_row_layout: None,
            center_cells_vertically: false,
            stack_cells_below_css_px: None,
            publisher_box: None,
        }
    }
}

impl PartialEq for FrbTableElement {
    fn eq(&self, other: &Self) -> bool {
        self.rows == other.rows
            && self.block == other.block
            && self.publisher_row_layout == other.publisher_row_layout
            && self.center_cells_vertically == other.center_cells_vertically
            && opt_f32_eq(
                self.stack_cells_below_css_px,
                other.stack_cells_below_css_px,
            )
            && self.publisher_box == other.publisher_box
    }
}

impl Eq for FrbTableElement {}

#[derive(Clone, Debug)]
pub(crate) enum FrbContentElement {
    Paragraph {
        text: FrbAnnotatedString,
        style: FrbParagraphStyle,
        block: Option<Box<FrbBlockStyle>>,
    },
    Heading {
        styled_text: FrbAnnotatedString,
        level: i32,
        block: Option<Box<FrbBlockStyle>>,
    },
    Image(Box<FrbImageElement>),
    Divider,
    Spacer {
        height_em: f32,
    },
    Table(Box<FrbTableElement>),
}

impl PartialEq for FrbContentElement {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (
                Self::Paragraph {
                    text: t1,
                    style: s1,
                    block: b1,
                },
                Self::Paragraph {
                    text: t2,
                    style: s2,
                    block: b2,
                },
            ) => t1 == t2 && s1 == s2 && b1 == b2,
            (
                Self::Heading {
                    styled_text: t1,
                    level: l1,
                    block: b1,
                },
                Self::Heading {
                    styled_text: t2,
                    level: l2,
                    block: b2,
                },
            ) => t1 == t2 && l1 == l2 && b1 == b2,
            (Self::Image(a), Self::Image(b)) => a == b,
            (Self::Divider, Self::Divider) => true,
            (Self::Spacer { height_em: a }, Self::Spacer { height_em: b }) => f32_eq(*a, *b),
            (Self::Table(a), Self::Table(b)) => a == b,
            _ => false,
        }
    }
}

impl Eq for FrbContentElement {}

// --------------------------------------------------------------------------- Chapter, Notes, Nav, Fonts

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbChapter {
    pub title: Option<String>,
    pub elements: Vec<FrbContentElement>,
    pub depth: i32,
    pub publisher_boxes: Vec<FrbPublisherBoxSpan>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbNoteDocument {
    pub elements: Vec<FrbContentElement>,
    pub publisher_boxes: Vec<FrbPublisherBoxSpan>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbLinkedDocument {
    pub id: String,
    pub title: Option<String>,
    pub elements: Vec<FrbContentElement>,
    pub publisher_boxes: Vec<FrbPublisherBoxSpan>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbLinkedDocumentTarget {
    pub document_id: String,
    pub element_index: i32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FrbNavigationTarget {
    ReadingOrder {
        chapter_index: i32,
        element_index: i32,
    },
    Linked {
        document_id: String,
        element_index: i32,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbNavigationEntry {
    pub title: String,
    pub depth: i32,
    pub target: FrbNavigationTarget,
}

#[derive(Clone, Debug)]
pub(crate) struct FrbBookFont {
    pub family: String,
    pub path: String,
    pub bold: bool,
    pub italic: bool,
    pub weight: i32,
    pub weight_min: i32,
    pub weight_max: i32,
    pub stretch_min: f32,
    pub stretch_max: f32,
    pub unicode_range: Option<String>,
}

impl FrbBookFont {
    pub(crate) fn new(
        family: impl Into<String>,
        path: impl Into<String>,
        bold: bool,
        italic: bool,
    ) -> Self {
        let weight = if bold { 700 } else { 400 };
        Self {
            family: family.into(),
            path: path.into(),
            bold,
            italic,
            weight,
            weight_min: weight,
            weight_max: weight,
            stretch_min: 100.0,
            stretch_max: 100.0,
            unicode_range: None,
        }
    }
}

impl PartialEq for FrbBookFont {
    fn eq(&self, other: &Self) -> bool {
        self.family == other.family
            && self.path == other.path
            && self.bold == other.bold
            && self.italic == other.italic
            && self.weight == other.weight
            && self.weight_min == other.weight_min
            && self.weight_max == other.weight_max
            && f32_eq(self.stretch_min, other.stretch_min)
            && f32_eq(self.stretch_max, other.stretch_max)
            && self.unicode_range == other.unicode_range
    }
}

impl Eq for FrbBookFont {}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbEmbeddedFont {
    pub name: String,
    pub path: String,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct FrbPublisherStyle {
    pub justify: Option<bool>,
    pub line_height: Option<f32>,
    pub hyphenation: Option<bool>,
    pub drop_caps: bool,
    pub font_name: Option<String>,
    pub font_path: Option<String>,
    pub font_css: Option<String>,
    pub embedded_fonts: Vec<FrbEmbeddedFont>,
    pub font_faces: Vec<FrbBookFont>,
    pub font_typography: Option<FrbFontTypography>,
}

impl FrbPublisherStyle {
    pub(crate) fn is_empty(&self) -> bool {
        self.justify.is_none()
            && self.line_height.is_none()
            && self.hyphenation.is_none()
            && !self.drop_caps
            && self.font_name.is_none()
            && self.embedded_fonts.is_empty()
            && self.font_typography.is_none()
    }
}

impl PartialEq for FrbPublisherStyle {
    fn eq(&self, other: &Self) -> bool {
        self.justify == other.justify
            && opt_f32_eq(self.line_height, other.line_height)
            && self.hyphenation == other.hyphenation
            && self.drop_caps == other.drop_caps
            && self.font_name == other.font_name
            && self.font_path == other.font_path
            && self.font_css == other.font_css
            && self.embedded_fonts == other.embedded_fonts
            && self.font_faces == other.font_faces
            && self.font_typography == other.font_typography
    }
}

impl Eq for FrbPublisherStyle {}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbEpubSourceRange {
    pub spine_ordinal: i32,
    pub path: String,
    pub document_start: i32,
    pub chapter_index: i32,
    pub chapter_start: i32,
    pub element_count: i32,
}

// --------------------------------------------------------------------------- PublisherPublication (Fixed-Layout)

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbPublisherFormat {
    Epub2 = 0,
    Epub3 = 1,
    Kf8 = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbPublisherProfile {
    EpubStandard = 0,
    AmazonEpub2Fixed = 1,
    Kf8Detected = 2,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FrbPublisherSourceDescriptor {
    EpubArchive {
        package_path: String,
    },
    Kf8Bundle {
        generation_key: String,
        relative_directory: String,
        format_version: i32,
    },
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbRenditionLayout {
    #[default]
    Reflowable = 0,
    PrePaginated = 1,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbRenditionOrientation {
    #[default]
    Auto = 0,
    Portrait = 1,
    Landscape = 2,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbRenditionSpread {
    #[default]
    Auto = 0,
    None = 1,
    Landscape = 2,
    Portrait = 3,
    Both = 4,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbPagePlacement {
    #[default]
    Auto = 0,
    Left = 1,
    Right = 2,
    Center = 3,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbPrimaryWritingMode {
    HorizontalLr = 0,
    HorizontalRl = 1,
    VerticalLr = 2,
    VerticalRl = 3,
}

#[derive(Clone, Copy, Debug)]
pub(crate) enum FrbPublisherViewportDimension {
    CssPixels(f32),
    Device,
}

impl PartialEq for FrbPublisherViewportDimension {
    fn eq(&self, other: &Self) -> bool {
        match (*self, *other) {
            (Self::CssPixels(a), Self::CssPixels(b)) => f32_eq(a, b),
            (Self::Device, Self::Device) => true,
            _ => false,
        }
    }
}

impl Eq for FrbPublisherViewportDimension {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbViewportSource {
    XhtmlMeta = 0,
    SvgViewbox = 1,
    AmazonOriginalResolution = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherViewport {
    pub width: FrbPublisherViewportDimension,
    pub height: FrbPublisherViewportDimension,
    pub source: FrbViewportSource,
    pub inferred: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbPublisherRendition {
    pub layout: FrbRenditionLayout,
    pub orientation: FrbRenditionOrientation,
    pub spread: FrbRenditionSpread,
    pub placement: FrbPagePlacement,
    pub viewport: Option<FrbPublisherViewport>,
    pub primary_writing_mode: Option<FrbPrimaryWritingMode>,
    pub layout_blank: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
#[repr(u64)]
pub(crate) enum FrbPublisherCapability {
    MathMl = 0,
    EmbeddedSvg = 1,
    StandaloneSvg = 2,
    VerticalWriting = 3,
    Scripted = 4,
    KindleRegionMagnification = 5,
    KindleVirtualPanels = 6,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbKindleRegionKind {
    Text = 0,
    Image = 1,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbKindleRegion {
    pub source_element_id: Option<String>,
    pub target_element_id: String,
    pub ordinal: i32,
    pub kind: FrbKindleRegionKind,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherSpineItem {
    pub id: String,
    pub item_ref_id: Option<String>,
    pub manifest_id: String,
    pub resource_path: String,
    pub linear: bool,
    pub title: Option<String>,
    pub rendition: FrbPublisherRendition,
    pub capabilities: BTreeSet<FrbPublisherCapability>,
    pub kindle_regions: Vec<FrbKindleRegion>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(u64)]
pub(crate) enum FrbPublisherResourceTransform {
    #[default]
    None = 0,
    IdpfFontObfuscation = 1,
    AdobeFontObfuscation = 2,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherResource {
    pub path: String,
    pub media_type: String,
    pub properties: BTreeSet<String>,
    pub transform: FrbPublisherResourceTransform,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherTextAnchor {
    pub fragment: String,
    pub text_offset: i32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherTextDocument {
    pub resource_path: String,
    pub text: String,
    pub anchors: Vec<FrbPublisherTextAnchor>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbPublisherTextIndex {
    pub documents: BTreeMap<String, FrbPublisherTextDocument>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbPublisherPublication {
    pub format: FrbPublisherFormat,
    pub profile: FrbPublisherProfile,
    pub source: FrbPublisherSourceDescriptor,
    pub defaults: FrbPublisherRendition,
    pub page_progression: FrbPageProgression,
    pub spine: Vec<FrbPublisherSpineItem>,
    pub resources: BTreeMap<String, FrbPublisherResource>,
    pub text_index: FrbPublisherTextIndex,
}

// --------------------------------------------------------------------------- BookIndex & BookContent

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct FrbChapterIndex {
    pub title: Option<String>,
    pub depth: i32,
    pub element_count: i32,
    pub start_element: i32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbBookIndex {
    pub chapters: Vec<FrbChapterIndex>,
    pub navigation: Vec<FrbNavigationEntry>,
    pub link_targets: BTreeMap<String, (i32, i32)>,
    pub linked_document_targets: BTreeMap<String, FrbLinkedDocumentTarget>,
    pub fonts: Vec<FrbBookFont>,
    pub language: Option<String>,
    pub page_progression: FrbPageProgression,
    pub total_elements: i32,
    pub resource_paths: Vec<String>,
    pub publisher_style: Option<FrbPublisherStyle>,
    pub epub_source_ranges: Vec<FrbEpubSourceRange>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct FrbBookContent {
    pub chapters: Vec<FrbChapter>,
    pub notes: BTreeMap<String, FrbNoteDocument>,
    pub link_targets: BTreeMap<String, (i32, i32)>,
    pub fonts: Vec<FrbBookFont>,
    pub language: Option<String>,
    pub linked_documents: BTreeMap<String, FrbLinkedDocument>,
    pub linked_document_targets: BTreeMap<String, FrbLinkedDocumentTarget>,
    pub navigation: Vec<FrbNavigationEntry>,
    pub publisher_publication: Option<FrbPublisherPublication>,
    pub page_progression: FrbPageProgression,
    pub epub_source_ranges: Vec<FrbEpubSourceRange>,
}

// --------------------------------------------------------------------------- Binary Wire Encoder

struct StyleDict<'a, T: Eq + Hash> {
    indices: HashMap<&'a T, usize>,
    size: usize,
}

impl<'a, T: Eq + Hash> StyleDict<'a, T> {
    fn new() -> Self {
        Self {
            indices: HashMap::new(),
            size: 0,
        }
    }
}

struct FrbEncoder<'a> {
    out: Vec<u8>,
    strings: HashMap<&'a str, usize>,
    block_styles: StyleDict<'a, FrbBlockStyle>,
    box_styles: StyleDict<'a, FrbPublisherBoxStyle>,
    span_styles: StyleDict<'a, FrbSpanStyle>,
    paragraph_styles: StyleDict<'a, FrbParagraphAnnotation>,
    link_styles: StyleDict<'a, FrbLinkStyles>,
}

impl<'a> FrbEncoder<'a> {
    fn with_capacity(capacity: usize) -> Self {
        Self {
            out: Vec::with_capacity(capacity),
            strings: HashMap::new(),
            block_styles: StyleDict::new(),
            box_styles: StyleDict::new(),
            span_styles: StyleDict::new(),
            paragraph_styles: StyleDict::new(),
            link_styles: StyleDict::new(),
        }
    }

    #[inline]
    fn write_u8(&mut self, v: u8) {
        self.out.push(v);
    }

    #[inline]
    fn write_bool(&mut self, v: bool) {
        self.out.push(u8::from(v));
    }

    #[inline]
    fn write_unsigned(&mut self, mut bits: u64) {
        while (bits >> 7) != 0 {
            self.out.push(((bits as u8) & 0x7F) | 0x80);
            bits >>= 7;
        }
        self.out.push(bits as u8);
    }

    #[inline]
    fn write_tag(&mut self, tag: u64) {
        self.write_unsigned(tag);
    }

    #[inline]
    fn write_end(&mut self) {
        self.out.push(0);
    }

    #[inline]
    fn write_i64(&mut self, value: i64) {
        let zigzag = ((value << 1) ^ (value >> 63)) as u64;
        self.write_unsigned(zigzag);
    }

    #[inline]
    fn write_i32(&mut self, value: i32) {
        self.write_i64(i64::from(value));
    }

    #[inline]
    fn write_ulong(&mut self, value: u64) {
        // Kotlin `ULong.serializer()` delegates to `encodeInline(...).encodeLong(value.toLong())`.
        self.write_i64(value as i64);
    }

    #[inline]
    fn write_f32(&mut self, value: f32) {
        self.out.extend_from_slice(&value.to_bits().to_le_bytes());
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

    #[inline]
    fn write_required_nullable_string(&mut self, tag: u64, value: Option<&'a str>) {
        self.write_tag(tag);
        match value {
            Some(s) => {
                self.write_u8(1);
                self.write_string(s);
            }
            None => self.write_u8(0),
        }
    }

    #[inline]
    fn write_opt_string(&mut self, tag: u64, value: Option<&'a str>) {
        if let Some(s) = value {
            self.write_tag(tag);
            self.write_u8(1);
            self.write_string(s);
        }
    }

    #[inline]
    fn write_opt_bool(&mut self, tag: u64, value: Option<bool>) {
        if let Some(v) = value {
            self.write_tag(tag);
            self.write_u8(1);
            self.write_bool(v);
        }
    }

    #[inline]
    fn write_opt_i32(&mut self, tag: u64, value: Option<i32>) {
        if let Some(v) = value {
            self.write_tag(tag);
            self.write_u8(1);
            self.write_i32(v);
        }
    }

    #[inline]
    fn write_opt_f32(&mut self, tag: u64, value: Option<f32>) {
        if let Some(v) = value {
            self.write_tag(tag);
            self.write_u8(1);
            self.write_f32(v);
        }
    }

    #[inline]
    fn write_default_f32(&mut self, tag: u64, value: f32, default: f32) {
        if !f32_eq(value, default) {
            self.write_tag(tag);
            self.write_f32(value);
        }
    }

    #[inline]
    fn write_default_bool(&mut self, tag: u64, value: bool, default: bool) {
        if value != default {
            self.write_tag(tag);
            self.write_bool(value);
        }
    }

    #[inline]
    fn write_pair_i32(&mut self, pair: (i32, i32)) {
        self.write_tag(1);
        self.write_i32(pair.0);
        self.write_tag(2);
        self.write_i32(pair.1);
        self.write_end();
    }

    // ---- ParsedAnnotatedStringSerializer types

    fn write_parsed_unit(&mut self, unit: FrbTextUnit) {
        let (kind, bits) = match unit {
            FrbTextUnit::Unspecified => (0i32, 0i32),
            FrbTextUnit::Sp(v) => (1i32, v.to_bits() as i32),
            FrbTextUnit::Em(v) => (2i32, v.to_bits() as i32),
        };
        self.write_tag(1);
        self.write_i32(kind);
        if bits != 0 {
            self.write_tag(2);
            self.write_i32(bits);
        }
        self.write_end();
    }

    fn write_parsed_shadow(&mut self, shadow: &FrbShadow) {
        self.write_tag(1);
        self.write_ulong(shadow.color);
        self.write_tag(2);
        self.write_f32(shadow.x);
        self.write_tag(3);
        self.write_f32(shadow.y);
        self.write_tag(4);
        self.write_f32(shadow.blur);
        self.write_end();
    }

    fn write_interned_span(&mut self, span: &'a FrbSpanStyle) {
        if let Some(&existing) = self.span_styles.indices.get(span) {
            self.write_unsigned((existing as u64) + 1);
            return;
        }
        self.write_unsigned(0);

        if span.color != COMPOSE_COLOR_UNSPECIFIED {
            self.write_tag(1);
            self.write_ulong(span.color);
        }
        if !span.size.is_unspecified() {
            self.write_tag(2);
            self.write_parsed_unit(span.size);
        }
        self.write_opt_i32(3, span.weight);
        self.write_opt_bool(4, span.italic);
        self.write_opt_string(5, span.synthesis.as_deref());
        self.write_opt_string(6, span.family.as_deref());
        self.write_opt_string(7, span.features.as_deref());
        if !span.spacing.is_unspecified() {
            self.write_tag(8);
            self.write_parsed_unit(span.spacing);
        }
        self.write_opt_f32(9, span.baseline);
        self.write_opt_f32(10, span.scale_x);
        self.write_opt_f32(11, span.skew_x);
        if let Some(locales) = &span.locales {
            self.write_tag(12);
            self.write_u8(1);
            self.write_unsigned(locales.len() as u64);
            for locale in locales {
                self.write_string(locale);
            }
        }
        if span.background != COMPOSE_COLOR_UNSPECIFIED {
            self.write_tag(13);
            self.write_ulong(span.background);
        }
        self.write_opt_i32(14, span.decoration);
        if let Some(shadow) = &span.shadow {
            self.write_tag(15);
            self.write_u8(1);
            self.write_parsed_shadow(shadow);
        }
        self.write_end();

        if self.span_styles.size < FRB_MODEL_DICTIONARY_LIMIT {
            self.span_styles
                .indices
                .entry(span)
                .or_insert(self.span_styles.size);
            self.span_styles.size += 1;
        }
    }

    fn write_interned_paragraph_annotation(&mut self, para: &'a FrbParagraphAnnotation) {
        if let Some(&existing) = self.paragraph_styles.indices.get(para) {
            self.write_unsigned((existing as u64) + 1);
            return;
        }
        self.write_unsigned(0);

        if para.align != 0 {
            self.write_tag(1);
            self.write_i32(para.align);
        }
        if para.direction != 0 {
            self.write_tag(2);
            self.write_i32(para.direction);
        }
        if !para.height.is_unspecified() {
            self.write_tag(3);
            self.write_parsed_unit(para.height);
        }
        if let Some(first_indent) = para.first_indent {
            self.write_tag(4);
            self.write_u8(1);
            self.write_parsed_unit(first_indent);
        }
        if let Some(rest_indent) = para.rest_indent {
            self.write_tag(5);
            self.write_u8(1);
            self.write_parsed_unit(rest_indent);
        }
        self.write_end();

        if self.paragraph_styles.size < FRB_MODEL_DICTIONARY_LIMIT {
            self.paragraph_styles
                .indices
                .entry(para)
                .or_insert(self.paragraph_styles.size);
            self.paragraph_styles.size += 1;
        }
    }

    fn write_interned_link_styles(&mut self, styles: &'a FrbLinkStyles) {
        if let Some(&existing) = self.link_styles.indices.get(styles) {
            self.write_unsigned((existing as u64) + 1);
            return;
        }
        self.write_unsigned(0);

        if let Some(s) = &styles.style {
            self.write_tag(1);
            self.write_u8(1);
            self.write_interned_span(s);
        }
        if let Some(s) = &styles.focused {
            self.write_tag(2);
            self.write_u8(1);
            self.write_interned_span(s);
        }
        if let Some(s) = &styles.hovered {
            self.write_tag(3);
            self.write_u8(1);
            self.write_interned_span(s);
        }
        if let Some(s) = &styles.pressed {
            self.write_tag(4);
            self.write_u8(1);
            self.write_interned_span(s);
        }
        self.write_end();

        if self.link_styles.size < FRB_MODEL_DICTIONARY_LIMIT {
            self.link_styles
                .indices
                .entry(styles)
                .or_insert(self.link_styles.size);
            self.link_styles.size += 1;
        }
    }

    fn write_annotated_string(&mut self, value: &'a FrbAnnotatedString) {
        self.write_tag(1);
        self.write_string(&value.text);
        if !value.ranges.is_empty() {
            self.write_tag(2);
            self.write_unsigned(value.ranges.len() as u64);
            for range in &value.ranges {
                let type_name = match &range.annotation {
                    FrbTextAnnotation::String(_) => "string",
                    FrbTextAnnotation::Span(_) => "span",
                    FrbTextAnnotation::Paragraph(_) => "paragraph",
                    FrbTextAnnotation::Tts(_) => "tts",
                    FrbTextAnnotation::Url(_) => "url",
                    FrbTextAnnotation::Link { .. } => "link",
                    FrbTextAnnotation::Click { .. } => "click",
                };
                self.write_tag(1);
                self.write_string(type_name);
                self.write_tag(2);
                self.write_i32(range.start);
                self.write_tag(3);
                self.write_i32(range.end);
                if !range.tag.is_empty() {
                    self.write_tag(4);
                    self.write_string(&range.tag);
                }
                match &range.annotation {
                    FrbTextAnnotation::String(v)
                    | FrbTextAnnotation::Tts(v)
                    | FrbTextAnnotation::Url(v) => {
                        self.write_tag(5);
                        self.write_u8(1);
                        self.write_string(v);
                    }
                    FrbTextAnnotation::Span(span) => {
                        self.write_tag(6);
                        self.write_u8(1);
                        self.write_interned_span(span);
                    }
                    FrbTextAnnotation::Paragraph(para) => {
                        self.write_tag(7);
                        self.write_u8(1);
                        self.write_interned_paragraph_annotation(para);
                    }
                    FrbTextAnnotation::Link { url, styles } => {
                        self.write_tag(5);
                        self.write_u8(1);
                        self.write_string(url);
                        if let Some(s) = styles {
                            self.write_tag(8);
                            self.write_u8(1);
                            self.write_interned_link_styles(s);
                        }
                    }
                    FrbTextAnnotation::Click { tag, styles } => {
                        self.write_tag(5);
                        self.write_u8(1);
                        self.write_string(tag);
                        if let Some(s) = styles {
                            self.write_tag(8);
                            self.write_u8(1);
                            self.write_interned_link_styles(s);
                        }
                    }
                }
                self.write_end();
            }
        }
        self.write_end();
    }

    // ---- Typography & BlockStyle

    fn write_font_typography(&mut self, typo: &'a FrbFontTypography) {
        if let Some(axes) = &typo.variation_axes {
            self.write_tag(1);
            self.write_u8(1);
            self.write_unsigned(axes.len() as u64);
            for (tag, &val) in axes {
                self.write_string(tag);
                self.write_f32(val);
            }
        }
        self.write_opt_f32(2, typo.stretch_percent);
        self.write_opt_bool(3, typo.optical_sizing);
        self.write_end();
    }

    fn write_float_image(&mut self, img: &'a FrbFloatImage) {
        self.write_tag(1);
        self.write_string(&img.path);
        self.write_tag(2);
        self.write_f32(img.width_frac);
        self.write_tag(3);
        self.write_bool(img.left);
        self.write_opt_string(4, img.alt_text.as_deref());
        self.write_end();
    }

    fn write_first_letter_style(&mut self, fl: &'a FrbFirstLetterStyle) {
        self.write_tag(1);
        self.write_f32(fl.scale);
        self.write_opt_bool(2, fl.bold);
        self.write_opt_bool(3, fl.italic);
        self.write_tag(4);
        self.write_bool(fl.is_drop_cap);
        self.write_opt_string(5, fl.font_family.as_deref());
        self.write_opt_i32(6, fl.foreground_color_argb);
        self.write_opt_i32(7, fl.background_color_argb);
        self.write_default_bool(8, fl.left_side, true);
        if let Some(dir) = fl.direction {
            self.write_tag(9);
            self.write_u8(1);
            self.write_unsigned(dir as u64);
        }
        self.write_opt_string(10, fl.language.as_deref());
        self.write_opt_i32(11, fl.source_text_length);
        self.write_opt_i32(12, fl.font_weight);
        if let Some(typo) = &fl.font_typography {
            self.write_tag(13);
            self.write_u8(1);
            self.write_font_typography(typo);
        }
        self.write_opt_string(14, fl.font_feature_settings.as_deref());
        self.write_end();
    }

    fn write_interned_block_style(&mut self, style: &'a FrbBlockStyle) {
        if let Some(&existing) = self.block_styles.indices.get(style) {
            self.write_unsigned((existing as u64) + 1);
            return;
        }
        self.write_unsigned(0);

        if let Some(align) = style.align {
            self.write_tag(1);
            self.write_u8(1);
            self.write_unsigned(align as u64);
        }
        self.write_opt_bool(2, style.italic);
        self.write_opt_bool(3, style.bold);
        self.write_opt_f32(4, style.font_scale);
        self.write_default_f32(5, style.indent_start_frac, 0.0);
        self.write_default_f32(6, style.indent_start_em, 0.0);
        self.write_default_f32(7, style.indent_end_frac, 0.0);
        self.write_default_f32(8, style.indent_end_em, 0.0);
        self.write_default_f32(9, style.indent_left_frac, 0.0);
        self.write_default_f32(10, style.indent_left_em, 0.0);
        self.write_default_f32(11, style.indent_right_frac, 0.0);
        self.write_default_f32(12, style.indent_right_em, 0.0);
        self.write_opt_bool(13, style.first_line_indent);
        self.write_opt_f32(14, style.first_line_indent_em);
        self.write_default_bool(15, style.is_css_text_line, false);
        self.write_default_f32(16, style.space_before_em, 0.0);
        self.write_default_f32(17, style.space_after_em, 0.0);
        self.write_default_bool(18, style.space_before_specified, false);
        self.write_default_bool(19, style.space_after_specified, false);
        self.write_opt_string(20, style.font_family.as_deref());
        self.write_opt_f32(21, style.line_height_mult);
        self.write_opt_bool(22, style.hyphens);
        self.write_default_bool(23, style.page_break_before, false);
        if let Some(fl) = &style.first_letter {
            self.write_tag(24);
            self.write_u8(1);
            self.write_first_letter_style(fl);
        }
        if let Some(fi) = &style.float_image {
            self.write_tag(25);
            self.write_u8(1);
            self.write_float_image(fi);
        }
        self.write_opt_string(26, style.language.as_deref());
        if let Some(dir) = style.direction {
            self.write_tag(27);
            self.write_u8(1);
            self.write_unsigned(dir as u64);
        }
        self.write_opt_i32(28, style.foreground_color_argb);
        self.write_opt_i32(29, style.background_color_argb);
        self.write_opt_string(30, style.font_feature_settings.as_deref());
        self.write_opt_i32(31, style.font_weight);
        if let Some(typo) = &style.font_typography {
            self.write_tag(32);
            self.write_u8(1);
            self.write_font_typography(typo);
        }
        self.write_end();

        if self.block_styles.size < FRB_MODEL_DICTIONARY_LIMIT {
            self.block_styles
                .indices
                .entry(style)
                .or_insert(self.block_styles.size);
            self.block_styles.size += 1;
        }
    }

    // ---- Publisher Box Layout

    fn write_border_side(&mut self, side: &FrbPublisherBorderSide) {
        self.write_tag(1);
        self.write_f32(side.width_em);
        self.write_tag(2);
        self.write_i32(side.color_argb);
        self.write_tag(3);
        self.write_unsigned(side.style as u64);
        self.write_default_bool(4, side.uses_current_color, false);
        self.write_end();
    }

    fn write_interned_publisher_box_style(&mut self, style: &'a FrbPublisherBoxStyle) {
        if let Some(&existing) = self.box_styles.indices.get(style) {
            self.write_unsigned((existing as u64) + 1);
            return;
        }
        self.write_unsigned(0);

        self.write_default_f32(1, style.margin_top_em, 0.0);
        self.write_default_f32(2, style.margin_right_em, 0.0);
        self.write_default_f32(3, style.margin_right_frac, 0.0);
        self.write_default_f32(4, style.margin_bottom_em, 0.0);
        self.write_default_f32(5, style.margin_left_em, 0.0);
        self.write_default_f32(6, style.margin_left_frac, 0.0);
        self.write_default_f32(7, style.padding_top_em, 0.0);
        self.write_default_f32(8, style.padding_right_em, 0.0);
        self.write_default_f32(9, style.padding_right_frac, 0.0);
        self.write_default_f32(10, style.padding_bottom_em, 0.0);
        self.write_default_f32(11, style.padding_left_em, 0.0);
        self.write_default_f32(12, style.padding_left_frac, 0.0);
        self.write_opt_i32(13, style.background_color_argb);
        if let Some(side) = &style.border_top {
            self.write_tag(14);
            self.write_u8(1);
            self.write_border_side(side);
        }
        if let Some(side) = &style.border_right {
            self.write_tag(15);
            self.write_u8(1);
            self.write_border_side(side);
        }
        if let Some(side) = &style.border_bottom {
            self.write_tag(16);
            self.write_u8(1);
            self.write_border_side(side);
        }
        if let Some(side) = &style.border_left {
            self.write_tag(17);
            self.write_u8(1);
            self.write_border_side(side);
        }
        self.write_opt_f32(18, style.width_frac);
        self.write_opt_f32(19, style.width_em);
        self.write_default_bool(20, style.shrink_to_fit, false);
        self.write_default_f32(21, style.hanging_indent_em, 0.0);
        self.write_default_bool(22, style.fit_text_flow, false);
        if style.horizontal_align != FrbPublisherBoxAlign::Start {
            self.write_tag(23);
            self.write_unsigned(style.horizontal_align as u64);
        }
        self.write_default_bool(24, style.break_inside_avoid, false);
        self.write_default_bool(25, style.page_break_before, false);
        self.write_default_bool(26, style.page_break_after, false);
        self.write_opt_f32(27, style.min_height_em);
        self.write_opt_f32(28, style.min_height_viewport_frac);
        self.write_default_f32(29, style.min_height_offset_em, 0.0);
        self.write_default_bool(30, style.center_content_vertically, false);
        self.write_default_bool(31, style.border_box, false);
        self.write_opt_f32(32, style.max_width_em);
        self.write_opt_f32(33, style.max_width_frac);
        self.write_end();

        if self.box_styles.size < FRB_MODEL_DICTIONARY_LIMIT {
            self.box_styles
                .indices
                .entry(style)
                .or_insert(self.box_styles.size);
            self.box_styles.size += 1;
        }
    }

    fn write_publisher_box_span(&mut self, span: &'a FrbPublisherBoxSpan) {
        self.write_tag(1);
        self.write_string(&span.id);
        self.write_opt_string(2, span.parent_id.as_deref());
        self.write_tag(3);
        self.write_i32(span.start_element);
        self.write_tag(4);
        self.write_i32(span.end_element_exclusive);
        if !span.style.is_default() {
            self.write_tag(5);
            self.write_interned_publisher_box_style(&span.style);
        }
        if let Some(side) = span.float_side {
            self.write_tag(6);
            self.write_u8(1);
            self.write_unsigned(side as u64);
        }
        if span.clear != FrbPublisherClear::None {
            self.write_tag(7);
            self.write_unsigned(span.clear as u64);
        }
        self.write_default_bool(8, span.draw_start, true);
        self.write_default_bool(9, span.draw_end, true);
        self.write_end();
    }

    // ---- Table & ContentElement

    fn write_table_cell_style(&mut self, style: &'a FrbTableCellStyle) {
        if let Some(block) = &style.block {
            self.write_tag(1);
            self.write_u8(1);
            self.write_interned_block_style(block);
        }
        if let Some(pbox) = &style.publisher_box {
            self.write_tag(2);
            self.write_u8(1);
            self.write_interned_publisher_box_style(pbox);
        }
        if let Some(align) = style.align {
            self.write_tag(3);
            self.write_u8(1);
            self.write_unsigned(align as u64);
        }
        self.write_default_bool(4, style.publisher_padding_specified, false);
        self.write_default_bool(5, style.publisher_border_specified, false);
        self.write_end();
    }

    fn write_table_cell(&mut self, cell: &'a FrbTableCell) {
        self.write_tag(1);
        self.write_annotated_string(&cell.text);
        if cell.col_span != 1 {
            self.write_tag(2);
            self.write_i32(cell.col_span);
        }
        if cell.row_span != 1 {
            self.write_tag(3);
            self.write_i32(cell.row_span);
        }
        if let Some(align) = cell.align {
            self.write_tag(4);
            self.write_u8(1);
            self.write_unsigned(align as u64);
        }
        self.write_default_bool(5, cell.header, false);
        if let Some(block) = &cell.block {
            self.write_tag(6);
            self.write_u8(1);
            self.write_interned_block_style(block);
        }
        if let Some(pbox) = &cell.publisher_box {
            self.write_tag(7);
            self.write_u8(1);
            self.write_interned_publisher_box_style(pbox);
        }
        self.write_default_bool(8, cell.publisher_padding_specified, false);
        self.write_default_bool(9, cell.publisher_border_specified, false);
        if let Some(narrow) = &cell.narrow_style {
            self.write_tag(10);
            self.write_u8(1);
            self.write_table_cell_style(narrow);
        }
        self.write_end();
    }

    fn write_table_row(&mut self, row: &'a FrbTableRow) {
        self.write_tag(1);
        self.write_unsigned(row.cells.len() as u64);
        for cell in &row.cells {
            self.write_table_cell(cell);
        }
        self.write_tag(2);
        self.write_bool(row.is_header);
        self.write_end();
    }

    fn write_content_element(&mut self, element: &'a FrbContentElement) {
        match element {
            FrbContentElement::Paragraph { text, style, block } => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_PARAGRAPH);
                self.write_tag(2);
                self.write_tag(1);
                self.write_annotated_string(text);
                if *style != FrbParagraphStyle::Normal {
                    self.write_tag(2);
                    self.write_unsigned(*style as u64);
                }
                if let Some(b) = block {
                    self.write_tag(3);
                    self.write_u8(1);
                    self.write_interned_block_style(b);
                }
                self.write_end(); // end Paragraph
                self.write_end(); // end polymorphic ContentElement
            }
            FrbContentElement::Heading {
                styled_text,
                level,
                block,
            } => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_HEADING);
                self.write_tag(2);
                self.write_tag(1);
                self.write_annotated_string(styled_text);
                self.write_tag(2);
                self.write_i32(*level);
                if let Some(b) = block {
                    self.write_tag(3);
                    self.write_u8(1);
                    self.write_interned_block_style(b);
                }
                self.write_end(); // end Heading
                self.write_end(); // end polymorphic ContentElement
            }
            FrbContentElement::Image(img) => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_IMAGE);
                self.write_tag(2);
                self.write_tag(1);
                self.write_string(&img.path);
                self.write_opt_f32(2, img.width_frac);
                self.write_opt_f32(3, img.width_em);
                self.write_opt_f32(4, img.height_em);
                self.write_opt_string(5, img.alt_text.as_deref());
                self.write_default_bool(6, img.space_before_specified, false);
                self.write_default_bool(7, img.space_after_specified, false);
                self.write_opt_f32(8, img.max_height_em);
                self.write_opt_f32(9, img.max_height_viewport_frac);
                self.write_opt_f32(10, img.max_width_em);
                self.write_opt_f32(11, img.max_width_frac);
                self.write_default_bool(12, img.dark_mode_invert, false);
                self.write_opt_i32(13, img.dark_mode_background_argb);
                self.write_default_bool(14, img.dark_mode_background_current_color, false);
                self.write_end(); // end Image
                self.write_end(); // end polymorphic ContentElement
            }
            FrbContentElement::Divider => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_DIVIDER);
                self.write_tag(2);
                self.write_end(); // empty object record for Divider
                self.write_end(); // end polymorphic ContentElement
            }
            FrbContentElement::Spacer { height_em } => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_SPACER);
                self.write_tag(2);
                self.write_default_f32(1, *height_em, 1.0);
                self.write_end(); // end Spacer
                self.write_end(); // end polymorphic ContentElement
            }
            FrbContentElement::Table(tbl) => {
                self.write_tag(1);
                self.write_string(TYPE_CONTENT_TABLE);
                self.write_tag(2);
                self.write_tag(1);
                self.write_unsigned(tbl.rows.len() as u64);
                for row in &tbl.rows {
                    self.write_table_row(row);
                }
                if let Some(b) = &tbl.block {
                    self.write_tag(2);
                    self.write_u8(1);
                    self.write_interned_block_style(b);
                }
                if let Some(layout) = tbl.publisher_row_layout {
                    self.write_tag(3);
                    self.write_u8(1);
                    self.write_unsigned(layout as u64);
                }
                self.write_default_bool(4, tbl.center_cells_vertically, false);
                self.write_opt_f32(5, tbl.stack_cells_below_css_px);
                if let Some(pbox) = &tbl.publisher_box {
                    self.write_tag(6);
                    self.write_u8(1);
                    self.write_interned_publisher_box_style(pbox);
                }
                self.write_end(); // end Table
                self.write_end(); // end polymorphic ContentElement
            }
        }
    }

    // ---- Chapters, Documents, Nav, Fonts

    fn write_chapter(&mut self, chapter: &'a FrbChapter) {
        self.write_required_nullable_string(1, chapter.title.as_deref());
        self.write_tag(2);
        self.write_unsigned(chapter.elements.len() as u64);
        for elem in &chapter.elements {
            self.write_content_element(elem);
        }
        if chapter.depth != 0 {
            self.write_tag(3);
            self.write_i32(chapter.depth);
        }
        if !chapter.publisher_boxes.is_empty() {
            self.write_tag(4);
            self.write_unsigned(chapter.publisher_boxes.len() as u64);
            for span in &chapter.publisher_boxes {
                self.write_publisher_box_span(span);
            }
        }
        self.write_end();
    }

    fn write_note_document(&mut self, note: &'a FrbNoteDocument) {
        self.write_tag(1);
        self.write_unsigned(note.elements.len() as u64);
        for elem in &note.elements {
            self.write_content_element(elem);
        }
        if !note.publisher_boxes.is_empty() {
            self.write_tag(2);
            self.write_unsigned(note.publisher_boxes.len() as u64);
            for span in &note.publisher_boxes {
                self.write_publisher_box_span(span);
            }
        }
        self.write_end();
    }

    fn write_linked_document(&mut self, doc: &'a FrbLinkedDocument) {
        self.write_tag(1);
        self.write_string(&doc.id);
        self.write_required_nullable_string(2, doc.title.as_deref());
        self.write_tag(3);
        self.write_unsigned(doc.elements.len() as u64);
        for elem in &doc.elements {
            self.write_content_element(elem);
        }
        if !doc.publisher_boxes.is_empty() {
            self.write_tag(4);
            self.write_unsigned(doc.publisher_boxes.len() as u64);
            for span in &doc.publisher_boxes {
                self.write_publisher_box_span(span);
            }
        }
        self.write_end();
    }

    fn write_linked_document_target(&mut self, target: &'a FrbLinkedDocumentTarget) {
        self.write_tag(1);
        self.write_string(&target.document_id);
        self.write_tag(2);
        self.write_i32(target.element_index);
        self.write_end();
    }

    fn write_navigation_target(&mut self, target: &'a FrbNavigationTarget) {
        match target {
            FrbNavigationTarget::ReadingOrder {
                chapter_index,
                element_index,
            } => {
                self.write_tag(1);
                self.write_string(TYPE_NAV_READING_ORDER);
                self.write_tag(2);
                self.write_tag(1);
                self.write_i32(*chapter_index);
                self.write_tag(2);
                self.write_i32(*element_index);
                self.write_end(); // end ReadingOrder
                self.write_end(); // end polymorphic BookNavigationTarget
            }
            FrbNavigationTarget::Linked {
                document_id,
                element_index,
            } => {
                self.write_tag(1);
                self.write_string(TYPE_NAV_LINKED);
                self.write_tag(2);
                self.write_tag(1);
                self.write_string(document_id);
                self.write_tag(2);
                self.write_i32(*element_index);
                self.write_end(); // end Linked
                self.write_end(); // end polymorphic BookNavigationTarget
            }
        }
    }

    fn write_navigation_entry(&mut self, entry: &'a FrbNavigationEntry) {
        self.write_tag(1);
        self.write_string(&entry.title);
        self.write_tag(2);
        self.write_i32(entry.depth);
        self.write_tag(3);
        self.write_navigation_target(&entry.target);
        self.write_end();
    }

    fn write_book_font(&mut self, font: &'a FrbBookFont) {
        self.write_tag(1);
        self.write_string(&font.family);
        self.write_tag(2);
        self.write_string(&font.path);
        self.write_tag(3);
        self.write_bool(font.bold);
        self.write_tag(4);
        self.write_bool(font.italic);
        let default_weight = if font.bold { 700 } else { 400 };
        if font.weight != default_weight {
            self.write_tag(5);
            self.write_i32(font.weight);
        }
        if font.weight_min != font.weight {
            self.write_tag(6);
            self.write_i32(font.weight_min);
        }
        if font.weight_max != font.weight {
            self.write_tag(7);
            self.write_i32(font.weight_max);
        }
        self.write_default_f32(8, font.stretch_min, 100.0);
        self.write_default_f32(9, font.stretch_max, font.stretch_min);
        self.write_opt_string(10, font.unicode_range.as_deref());
        self.write_end();
    }

    fn write_embedded_font(&mut self, font: &'a FrbEmbeddedFont) {
        self.write_tag(1);
        self.write_string(&font.name);
        self.write_tag(2);
        self.write_string(&font.path);
        self.write_end();
    }

    fn write_publisher_style(&mut self, style: &'a FrbPublisherStyle) {
        self.write_opt_bool(1, style.justify);
        self.write_opt_f32(2, style.line_height);
        self.write_opt_bool(3, style.hyphenation);
        self.write_default_bool(4, style.drop_caps, false);
        self.write_opt_string(5, style.font_name.as_deref());
        self.write_opt_string(6, style.font_path.as_deref());
        self.write_opt_string(7, style.font_css.as_deref());
        if !style.embedded_fonts.is_empty() {
            self.write_tag(8);
            self.write_unsigned(style.embedded_fonts.len() as u64);
            for ef in &style.embedded_fonts {
                self.write_embedded_font(ef);
            }
        }
        if !style.font_faces.is_empty() {
            self.write_tag(9);
            self.write_unsigned(style.font_faces.len() as u64);
            for bf in &style.font_faces {
                self.write_book_font(bf);
            }
        }
        if let Some(typo) = &style.font_typography {
            self.write_tag(10);
            self.write_u8(1);
            self.write_font_typography(typo);
        }
        self.write_end();
    }

    fn write_epub_source_range(&mut self, range: &'a FrbEpubSourceRange) {
        self.write_tag(1);
        self.write_i32(range.spine_ordinal);
        self.write_tag(2);
        self.write_string(&range.path);
        self.write_tag(3);
        self.write_i32(range.document_start);
        self.write_tag(4);
        self.write_i32(range.chapter_index);
        self.write_tag(5);
        self.write_i32(range.chapter_start);
        self.write_tag(6);
        self.write_i32(range.element_count);
        self.write_end();
    }

    // ---- PublisherPublication (fixed-layout)

    fn write_publisher_source_descriptor(&mut self, source: &'a FrbPublisherSourceDescriptor) {
        match source {
            FrbPublisherSourceDescriptor::EpubArchive { package_path } => {
                self.write_tag(1);
                self.write_string(TYPE_SOURCE_EPUB_ARCHIVE);
                self.write_tag(2);
                self.write_tag(1);
                self.write_string(package_path);
                self.write_end();
                self.write_end();
            }
            FrbPublisherSourceDescriptor::Kf8Bundle {
                generation_key,
                relative_directory,
                format_version,
            } => {
                self.write_tag(1);
                self.write_string(TYPE_SOURCE_KF8_BUNDLE);
                self.write_tag(2);
                self.write_tag(1);
                self.write_string(generation_key);
                self.write_tag(2);
                self.write_string(relative_directory);
                self.write_tag(3);
                self.write_i32(*format_version);
                self.write_end();
                self.write_end();
            }
        }
    }

    fn write_viewport_dimension(&mut self, dim: FrbPublisherViewportDimension) {
        match dim {
            FrbPublisherViewportDimension::CssPixels(value) => {
                self.write_tag(1);
                self.write_string(TYPE_VIEWPORT_CSS_PIXELS);
                self.write_tag(2);
                self.write_tag(1);
                self.write_f32(value);
                self.write_end();
                self.write_end();
            }
            FrbPublisherViewportDimension::Device => {
                self.write_tag(1);
                self.write_string(TYPE_VIEWPORT_DEVICE);
                self.write_tag(2);
                self.write_end();
                self.write_end();
            }
        }
    }

    fn write_publisher_viewport(&mut self, vp: &FrbPublisherViewport) {
        self.write_tag(1);
        self.write_viewport_dimension(vp.width);
        self.write_tag(2);
        self.write_viewport_dimension(vp.height);
        self.write_tag(3);
        self.write_unsigned(vp.source as u64);
        self.write_default_bool(4, vp.inferred, false);
        self.write_end();
    }

    fn write_publisher_rendition(&mut self, r: &FrbPublisherRendition) {
        if r.layout != FrbRenditionLayout::Reflowable {
            self.write_tag(1);
            self.write_unsigned(r.layout as u64);
        }
        if r.orientation != FrbRenditionOrientation::Auto {
            self.write_tag(2);
            self.write_unsigned(r.orientation as u64);
        }
        if r.spread != FrbRenditionSpread::Auto {
            self.write_tag(3);
            self.write_unsigned(r.spread as u64);
        }
        if r.placement != FrbPagePlacement::Auto {
            self.write_tag(4);
            self.write_unsigned(r.placement as u64);
        }
        if let Some(vp) = &r.viewport {
            self.write_tag(5);
            self.write_u8(1);
            self.write_publisher_viewport(vp);
        }
        if let Some(wm) = r.primary_writing_mode {
            self.write_tag(6);
            self.write_u8(1);
            self.write_unsigned(wm as u64);
        }
        self.write_default_bool(7, r.layout_blank, false);
        self.write_end();
    }

    fn write_kindle_region(&mut self, region: &'a FrbKindleRegion) {
        self.write_required_nullable_string(1, region.source_element_id.as_deref());
        self.write_tag(2);
        self.write_string(&region.target_element_id);
        self.write_tag(3);
        self.write_i32(region.ordinal);
        self.write_tag(4);
        self.write_unsigned(region.kind as u64);
        self.write_end();
    }

    fn write_publisher_spine_item(&mut self, item: &'a FrbPublisherSpineItem) {
        self.write_tag(1);
        self.write_string(&item.id);
        self.write_required_nullable_string(2, item.item_ref_id.as_deref());
        self.write_tag(3);
        self.write_string(&item.manifest_id);
        self.write_tag(4);
        self.write_string(&item.resource_path);
        self.write_tag(5);
        self.write_bool(item.linear);
        self.write_opt_string(6, item.title.as_deref());
        self.write_tag(7);
        self.write_publisher_rendition(&item.rendition);
        if !item.capabilities.is_empty() {
            self.write_tag(8);
            self.write_unsigned(item.capabilities.len() as u64);
            for &cap in &item.capabilities {
                self.write_unsigned(cap as u64);
            }
        }
        if !item.kindle_regions.is_empty() {
            self.write_tag(9);
            self.write_unsigned(item.kindle_regions.len() as u64);
            for region in &item.kindle_regions {
                self.write_kindle_region(region);
            }
        }
        self.write_end();
    }

    fn write_publisher_resource(&mut self, res: &'a FrbPublisherResource) {
        self.write_tag(1);
        self.write_string(&res.path);
        self.write_tag(2);
        self.write_string(&res.media_type);
        if !res.properties.is_empty() {
            self.write_tag(3);
            self.write_unsigned(res.properties.len() as u64);
            for prop in &res.properties {
                self.write_string(prop);
            }
        }
        if res.transform != FrbPublisherResourceTransform::None {
            self.write_tag(4);
            self.write_unsigned(res.transform as u64);
        }
        self.write_end();
    }

    fn write_publisher_text_index(&mut self, index: &'a FrbPublisherTextIndex) {
        if !index.documents.is_empty() {
            self.write_tag(1);
            self.write_unsigned(index.documents.len() as u64);
            for (k, doc) in &index.documents {
                self.write_string(k);
                self.write_tag(1);
                self.write_string(&doc.resource_path);
                self.write_tag(2);
                self.write_string(&doc.text);
                if !doc.anchors.is_empty() {
                    self.write_tag(3);
                    self.write_unsigned(doc.anchors.len() as u64);
                    for anchor in &doc.anchors {
                        self.write_tag(1);
                        self.write_string(&anchor.fragment);
                        self.write_tag(2);
                        self.write_i32(anchor.text_offset);
                        self.write_end();
                    }
                }
                self.write_end();
            }
        }
        self.write_end();
    }

    fn write_publisher_publication(&mut self, pub_data: &'a FrbPublisherPublication) {
        self.write_tag(1);
        self.write_unsigned(pub_data.format as u64);
        self.write_tag(2);
        self.write_unsigned(pub_data.profile as u64);
        self.write_tag(3);
        self.write_publisher_source_descriptor(&pub_data.source);
        self.write_tag(4);
        self.write_publisher_rendition(&pub_data.defaults);
        self.write_tag(5);
        self.write_unsigned(pub_data.page_progression as u64);
        self.write_tag(6);
        self.write_unsigned(pub_data.spine.len() as u64);
        for item in &pub_data.spine {
            self.write_publisher_spine_item(item);
        }
        self.write_tag(7);
        self.write_unsigned(pub_data.resources.len() as u64);
        for (k, res) in &pub_data.resources {
            self.write_string(k);
            self.write_publisher_resource(res);
        }
        if !pub_data.text_index.documents.is_empty() {
            self.write_tag(8);
            self.write_publisher_text_index(&pub_data.text_index);
        }
        self.write_end();
    }

    // ---- Top-level BookIndex & BookContent

    fn write_chapter_index(&mut self, idx: &'a FrbChapterIndex) {
        self.write_required_nullable_string(1, idx.title.as_deref());
        self.write_tag(2);
        self.write_i32(idx.depth);
        self.write_tag(3);
        self.write_i32(idx.element_count);
        self.write_tag(4);
        self.write_i32(idx.start_element);
        self.write_end();
    }

    fn write_book_index(&mut self, index: &'a FrbBookIndex) {
        self.write_tag(1);
        self.write_unsigned(index.chapters.len() as u64);
        for ch in &index.chapters {
            self.write_chapter_index(ch);
        }
        self.write_tag(2);
        self.write_unsigned(index.navigation.len() as u64);
        for nav in &index.navigation {
            self.write_navigation_entry(nav);
        }
        self.write_tag(3);
        self.write_unsigned(index.link_targets.len() as u64);
        for (k, &pair) in &index.link_targets {
            self.write_string(k);
            self.write_pair_i32(pair);
        }
        self.write_tag(4);
        self.write_unsigned(index.linked_document_targets.len() as u64);
        for (k, target) in &index.linked_document_targets {
            self.write_string(k);
            self.write_linked_document_target(target);
        }
        self.write_tag(5);
        self.write_unsigned(index.fonts.len() as u64);
        for font in &index.fonts {
            self.write_book_font(font);
        }
        self.write_required_nullable_string(6, index.language.as_deref());
        self.write_tag(7);
        self.write_unsigned(index.page_progression as u64);
        self.write_tag(8);
        self.write_i32(index.total_elements);
        self.write_tag(9);
        self.write_unsigned(index.resource_paths.len() as u64);
        for path in &index.resource_paths {
            self.write_string(path);
        }
        if let Some(ps) = &index.publisher_style {
            self.write_tag(10);
            self.write_u8(1);
            self.write_publisher_style(ps);
        }
        if !index.epub_source_ranges.is_empty() {
            self.write_tag(11);
            self.write_unsigned(index.epub_source_ranges.len() as u64);
            for range in &index.epub_source_ranges {
                self.write_epub_source_range(range);
            }
        }
        self.write_end();
    }

    fn write_book_content(&mut self, content: &'a FrbBookContent, include_chapters: bool) {
        self.write_tag(1);
        if include_chapters {
            self.write_unsigned(content.chapters.len() as u64);
            for ch in &content.chapters {
                self.write_chapter(ch);
            }
        } else {
            self.write_unsigned(0);
        }
        if !content.notes.is_empty() {
            self.write_tag(2);
            self.write_unsigned(content.notes.len() as u64);
            for (k, note) in &content.notes {
                self.write_string(k);
                self.write_note_document(note);
            }
        }
        if !content.link_targets.is_empty() {
            self.write_tag(3);
            self.write_unsigned(content.link_targets.len() as u64);
            for (k, &pair) in &content.link_targets {
                self.write_string(k);
                self.write_pair_i32(pair);
            }
        }
        if !content.fonts.is_empty() {
            self.write_tag(4);
            self.write_unsigned(content.fonts.len() as u64);
            for font in &content.fonts {
                self.write_book_font(font);
            }
        }
        self.write_opt_string(5, content.language.as_deref());
        if !content.linked_documents.is_empty() {
            self.write_tag(6);
            self.write_unsigned(content.linked_documents.len() as u64);
            for (k, doc) in &content.linked_documents {
                self.write_string(k);
                self.write_linked_document(doc);
            }
        }
        if !content.linked_document_targets.is_empty() {
            self.write_tag(7);
            self.write_unsigned(content.linked_document_targets.len() as u64);
            for (k, target) in &content.linked_document_targets {
                self.write_string(k);
                self.write_linked_document_target(target);
            }
        }
        if !content.navigation.is_empty() {
            self.write_tag(8);
            self.write_unsigned(content.navigation.len() as u64);
            for nav in &content.navigation {
                self.write_navigation_entry(nav);
            }
        }
        if let Some(pub_data) = &content.publisher_publication {
            self.write_tag(9);
            self.write_u8(1);
            self.write_publisher_publication(pub_data);
        }
        if content.page_progression != FrbPageProgression::Default {
            self.write_tag(10);
            self.write_unsigned(content.page_progression as u64);
        }
        if include_chapters && !content.epub_source_ranges.is_empty() {
            self.write_tag(11);
            self.write_unsigned(content.epub_source_ranges.len() as u64);
            for range in &content.epub_source_ranges {
                self.write_epub_source_range(range);
            }
        }
        self.write_end();
    }
}

// --------------------------------------------------------------------------- Public Crate API

/// Encodes a single `Chapter` block payload (`Chapter.serializer()`).
pub(crate) fn encode_chapter(chapter: &FrbChapter) -> Vec<u8> {
    let estimated = (chapter.elements.len() * 64 + 128).min(4 * 1024 * 1024);
    let mut enc = FrbEncoder::with_capacity(estimated);
    enc.write_chapter(chapter);
    enc.out
}

/// Encodes a `BookIndex` block payload (`BookIndex.serializer()`).
pub(crate) fn encode_book_index(index: &FrbBookIndex) -> Vec<u8> {
    let estimated =
        (index.chapters.len() * 32 + index.link_targets.len() * 24 + 256).min(2 * 1024 * 1024);
    let mut enc = FrbEncoder::with_capacity(estimated);
    enc.write_book_index(index);
    enc.out
}

/// Encodes block 1 (globals): `BookContent` with empty `chapters` and empty `epub_source_ranges`,
/// matching `ChunkedContentCache.save`.
pub(crate) fn encode_book_globals(content: &FrbBookContent) -> Vec<u8> {
    let estimated =
        (content.notes.len() * 96 + content.link_targets.len() * 24 + 256).min(4 * 1024 * 1024);
    let mut enc = FrbEncoder::with_capacity(estimated);
    enc.write_book_content(content, false);
    enc.out
}

/// Encodes a complete `BookContent` including `chapters` and `epub_source_ranges`,
/// directly decodable by `FrbModelCodec.decode(bytes, BookContent.serializer())`.
pub(crate) fn encode_book_content(content: &FrbBookContent) -> Vec<u8> {
    let elem_count: usize = content.chapters.iter().map(|c| c.elements.len()).sum();
    let estimated = (elem_count * 64 + content.notes.len() * 96 + 512).min(8 * 1024 * 1024);
    let mut enc = FrbEncoder::with_capacity(estimated);
    enc.write_book_content(content, true);
    enc.out
}

/// Computes `FrbBookIndex` from `FrbBookContent`, matching `ChunkedContentCache.bookIndex`,
/// `contentResourcePaths(content).sorted()`, and `publisherStyleOf(content)`.
pub(crate) fn build_book_index(content: &FrbBookContent) -> Result<FrbBookIndex, String> {
    let mut start: i64 = 0;
    let mut chapters = Vec::with_capacity(content.chapters.len());
    for chapter in &content.chapters {
        let count = i64::try_from(chapter.elements.len())
            .map_err(|_| "Content coordinate overflow".to_string())?;
        if start + count > i64::from(i32::MAX) {
            return Err("Content coordinate overflow".to_string());
        }
        chapters.push(FrbChapterIndex {
            title: chapter.title.clone(),
            depth: chapter.depth,
            element_count: count as i32,
            start_element: start as i32,
        });
        start += count;
    }

    let resource_paths = collect_sorted_resource_paths(content);
    let publisher_style = compute_publisher_style(content);

    Ok(FrbBookIndex {
        chapters,
        navigation: content.navigation.clone(),
        link_targets: content.link_targets.clone(),
        linked_document_targets: content.linked_document_targets.clone(),
        fonts: content.fonts.clone(),
        language: content.language.clone(),
        page_progression: content.page_progression,
        total_elements: start as i32,
        resource_paths,
        publisher_style,
        epub_source_ranges: content.epub_source_ranges.clone(),
    })
}

/// Encodes Block 0 (`BookIndex`), Block 1 (`BookGlobals`), and Blocks `2..N+1` (`Chapter`s),
/// writing a complete CRC-checked `.frb` v2 container to `dest_path`.
pub(crate) fn write_book_frb(
    content: &FrbBookContent,
    dest_path: &Path,
    source_metadata: &[u8],
    max_file_bytes: u64,
) -> Result<(), String> {
    let index = build_book_index(content)?;
    let index_bytes = encode_book_index(&index);
    if index_bytes.len() as u64 > crate::frb::MAX_INDEX_BYTES {
        return Err("FRB index block exceeds limit".to_string());
    }
    let globals_bytes = encode_book_globals(content);
    if globals_bytes.len() as u64 > crate::frb::MAX_BLOCK_BYTES {
        return Err("FRB globals block exceeds limit".to_string());
    }

    let counts: Vec<i32> = index.chapters.iter().map(|c| c.element_count).collect();
    let mut writer = crate::frb::Writer::begin(dest_path, source_metadata, &counts, max_file_bytes)
        .map_err(|e| e.to_string())?;
    writer
        .write_block_bytes(0, &index_bytes)
        .map_err(|e| e.to_string())?;
    writer
        .write_block_bytes(1, &globals_bytes)
        .map_err(|e| e.to_string())?;
    for (idx, chapter) in content.chapters.iter().enumerate() {
        let chapter_bytes = encode_chapter(chapter);
        if chapter_bytes.len() as u64 > crate::frb::MAX_BLOCK_BYTES {
            return Err("FRB chapter block exceeds limit".to_string());
        }
        writer
            .write_block_bytes(idx + 2, &chapter_bytes)
            .map_err(|e| e.to_string())?;
    }
    writer.finish().map_err(|e| e.to_string())?;
    Ok(())
}

// --------------------------------------------------------------------------- Resource Paths & PublisherStyle

fn collect_text_resources(text: &FrbAnnotatedString, paths: &mut HashSet<String>) {
    for range in &text.ranges {
        if range.tag == INLINE_IMAGE_TAG {
            if let FrbTextAnnotation::String(path) = &range.annotation {
                paths.insert(path.clone());
            }
        }
    }
}

fn collect_element_resources(elements: &[FrbContentElement], paths: &mut HashSet<String>) {
    for element in elements {
        match element {
            FrbContentElement::Image(img) => {
                paths.insert(img.path.clone());
            }
            FrbContentElement::Paragraph { text, block, .. } => {
                collect_text_resources(text, paths);
                if let Some(fi) = block.as_ref().and_then(|b| b.float_image.as_ref()) {
                    paths.insert(fi.path.clone());
                }
            }
            FrbContentElement::Heading {
                styled_text, block, ..
            } => {
                collect_text_resources(styled_text, paths);
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
                        collect_text_resources(&cell.text, paths);
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

fn collect_sorted_resource_paths(content: &FrbBookContent) -> Vec<String> {
    let mut paths = HashSet::new();
    for font in &content.fonts {
        paths.insert(font.path.clone());
    }
    for chapter in &content.chapters {
        collect_element_resources(&chapter.elements, &mut paths);
    }
    for note in content.notes.values() {
        collect_element_resources(&note.elements, &mut paths);
    }
    for doc in content.linked_documents.values() {
        collect_element_resources(&doc.elements, &mut paths);
    }
    let mut sorted: Vec<String> = paths.into_iter().collect();
    // Sort by UTF-16 code unit order to match Kotlin `String.compareTo`.
    sorted.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
    sorted
}

/// Insertion-order-preserving vote counter matching Kotlin `LinkedHashMap.maxByOrNull`.
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

fn compute_publisher_style(content: &FrbBookContent) -> Option<FrbPublisherStyle> {
    let mut justify_votes: VoteMap<bool> = VoteMap::new();
    let mut line_height_votes: VoteMap<F32Key> = VoteMap::new();
    let mut hyphen_votes: VoteMap<bool> = VoteMap::new();
    let mut font_votes: VoteMap<String> = VoteMap::new();
    let mut typography_votes: VoteMap<FrbFontTypography> = VoteMap::new();
    let mut drop_caps = false;

    let mut families: BTreeMap<&str, Vec<FrbBookFont>> = BTreeMap::new();
    for font in &content.fonts {
        families
            .entry(font.family.as_str())
            .or_default()
            .push(font.clone());
    }

    for chapter in &content.chapters {
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
                    typography_votes.increment(typo.as_ref().clone());
                }
            }
            match block.align {
                Some(FrbBlockAlign::Justify) => justify_votes.increment(true),
                Some(FrbBlockAlign::Start) => justify_votes.increment(false),
                _ => {}
            }
            if let Some(lh) = block.line_height_mult {
                line_height_votes.increment(F32Key::from_f32(lh));
            }
            if let Some(h) = block.hyphens {
                hyphen_votes.increment(h);
            }
            if let Some(f) = &block.font_family {
                font_votes.increment(f.clone());
            }
            if block.first_letter.as_ref().is_some_and(|fl| fl.is_drop_cap) {
                drop_caps = true;
            }
        }
    }

    let font = font_votes.winner();
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
        justify: justify_votes.winner().copied(),
        line_height: line_height_votes.winner().map(|k| k.to_f32()),
        hyphenation: hyphen_votes.winner().copied(),
        drop_caps,
        font_name,
        font_path,
        font_css,
        embedded_fonts,
        font_faces,
        font_typography: typography_votes.winner().cloned(),
    };
    if style.is_empty() {
        None
    } else {
        Some(style)
    }
}

// --------------------------------------------------------------------------- Unit Tests

#[cfg(test)]
mod tests {
    use super::*;
    use crate::book_search::search_frb_chapter_payload;
    use crate::frb::{Reader, MAX_FILE_BYTES};
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct TempDir(PathBuf);

    impl TempDir {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "frb-model-test-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn boxed_model_sizes_stay_compact() {
        let content_element_size = std::mem::size_of::<FrbContentElement>();
        let text_range_size = std::mem::size_of::<FrbTextRange>();
        let text_annotation_size = std::mem::size_of::<FrbTextAnnotation>();
        println!(
            "AFTER sizes: FrbContentElement={}, FrbTextRange={}, FrbTextAnnotation={}, FrbBlockStyle={}, FrbTableElement={}, FrbTableCell={}, FrbTableCellStyle={}",
            content_element_size,
            text_range_size,
            text_annotation_size,
            std::mem::size_of::<FrbBlockStyle>(),
            std::mem::size_of::<FrbTableElement>(),
            std::mem::size_of::<FrbTableCell>(),
            std::mem::size_of::<FrbTableCellStyle>(),
        );
        assert!(
            content_element_size <= 96,
            "FrbContentElement size {content_element_size} > 96"
        );
        assert!(
            text_range_size <= 80,
            "FrbTextRange size {text_range_size} > 80"
        );
        assert!(
            text_annotation_size <= 40,
            "FrbTextAnnotation size {text_annotation_size} > 40"
        );
    }

    #[test]
    fn compose_color_constants_match_jvm() {
        assert_eq!(COMPOSE_COLOR_UNSPECIFIED, 16);
        assert_eq!(
            color_argb_to_compose_ulong(0xFF11_2233u32 as i32),
            0xFF11_2233_0000_0000
        );
        assert_eq!(
            color_argb_to_compose_ulong(0x80AA_BBCCu32 as i32),
            0x80AA_BBCC_0000_0000
        );
        assert_eq!(color_argb_to_compose_ulong(0), 0);
    }

    #[test]
    fn block_style_default_and_negative_zero_match_kotlin_wire() {
        let mut enc = FrbEncoder::with_capacity(16);
        let default_style = FrbBlockStyle::default();
        enc.write_interned_block_style(&default_style);
        assert_eq!(enc.out, vec![0, 0]);

        let mut enc2 = FrbEncoder::with_capacity(16);
        let neg_zero = FrbBlockStyle {
            indent_start_em: -0.0f32,
            ..Default::default()
        };
        enc2.write_interned_block_style(&neg_zero);
        assert_eq!(enc2.out, vec![0, 6, 0x00, 0x00, 0x00, 0x80, 0]);
    }

    #[test]
    fn book_font_dependent_defaults_match_kotlin_wire() {
        let bold_font = FrbBookFont::new("serif", "/f.ttf", true, false);
        let mut enc = FrbEncoder::with_capacity(32);
        enc.write_book_font(&bold_font);
        assert_eq!(
            enc.out,
            vec![
                1, 10, b's', b'e', b'r', b'i', b'f', // family
                2, 12, b'/', b'f', b'.', b't', b't', b'f', // path
                3, 1, // bold = true
                4, 0, // italic = false
                0, // end (tags 5..10 omitted)
            ]
        );

        let semi_bold = FrbBookFont {
            family: "s".into(),
            path: "p".into(),
            bold: false,
            italic: false,
            weight: 600,
            weight_min: 600,
            weight_max: 600,
            stretch_min: 100.0,
            stretch_max: 100.0,
            unicode_range: None,
        };
        let mut enc2 = FrbEncoder::with_capacity(32);
        enc2.write_book_font(&semi_bold);
        assert_eq!(
            enc2.out,
            vec![
                1, 2, b's', // family
                2, 2, b'p', // path
                3, 0, // bold
                4, 0, // italic
                5, 0xB0, 0x09, // weight = 600 -> zigzag 1200 -> [0xB0, 0x09]
                0,    // end (weight_min/weight_max omitted because == weight)
            ]
        );
    }

    #[test]
    fn short_string_interning_and_empty_string_handling() {
        let mut enc = FrbEncoder::with_capacity(32);
        enc.write_string("");
        enc.write_string("he");
        enc.write_string("he");
        enc.write_string("en");
        enc.write_string("en");
        assert_eq!(
            enc.out,
            vec![
                0, // "" (not interned)
                4, b'h', b'e', // "he" -> slot 0
                1,    // ref slot 0: (0 << 1) | 1
                4, b'e', b'n', // "en" -> slot 1
                3,    // ref slot 1: (1 << 1) | 1
            ]
        );

        // String > 128 bytes is never interned.
        let long = "a".repeat(129);
        let mut enc2 = FrbEncoder::with_capacity(300);
        enc2.write_string(&long);
        enc2.write_string(&long);
        assert_eq!(enc2.strings.len(), 0);
    }

    #[test]
    fn interned_style_tables_deduplicate_per_descriptor() {
        let bold_span = FrbSpanStyle {
            weight: Some(700),
            ..Default::default()
        };
        let center_para = FrbParagraphAnnotation {
            align: COMPOSE_TEXT_ALIGN_CENTER,
            ..Default::default()
        };
        let link_styles = FrbLinkStyles {
            style: Some(bold_span.clone()),
            ..Default::default()
        };
        let block = FrbBlockStyle {
            bold: Some(true),
            ..Default::default()
        };
        let box_style = FrbPublisherBoxStyle {
            padding_left_em: 0.5,
            ..Default::default()
        };

        let make_para = || FrbContentElement::Paragraph {
            text: FrbAnnotatedString {
                text: "text".into(),
                ranges: vec![
                    FrbTextRange::span(0, 4, bold_span.clone()),
                    FrbTextRange::paragraph(0, 4, center_para.clone()),
                    FrbTextRange::link(0, 4, "https://example.invalid", Some(link_styles.clone())),
                ],
            },
            style: FrbParagraphStyle::Normal,
            block: Some(Box::new(block.clone())),
        };

        let chapter = FrbChapter {
            title: None,
            elements: vec![make_para(), make_para()],
            depth: 0,
            publisher_boxes: vec![
                FrbPublisherBoxSpan {
                    style: box_style.clone(),
                    ..FrbPublisherBoxSpan::new("box-0", 0, 1)
                },
                FrbPublisherBoxSpan {
                    style: box_style.clone(),
                    ..FrbPublisherBoxSpan::new("box-1", 1, 2)
                },
            ],
        };

        let bytes = encode_chapter(&chapter);
        // Verify book_search's strict FRB chapter decoder accepts the encoded block cleanly!
        let search = search_frb_chapter_payload(&bytes, 0, 0, Some(2), "text", false, 10)
            .expect("book_search must decode encoded chapter cleanly");
        assert_eq!(search.total_count, 2);
        assert_eq!(search.matches.len(), 2);
    }

    #[test]
    fn full_chapter_with_all_elements_and_styles_decodes_in_book_search() {
        let mut axes = BTreeMap::new();
        axes.insert("wght".to_string(), 550.0);
        let typo = FrbFontTypography {
            variation_axes: Some(axes),
            stretch_percent: Some(95.0),
            optical_sizing: Some(true),
        };
        let full_block = FrbBlockStyle {
            align: Some(FrbBlockAlign::Justify),
            italic: Some(false),
            bold: Some(true),
            font_scale: Some(1.15),
            indent_start_frac: 0.05,
            indent_start_em: 1.0,
            indent_end_frac: 0.02,
            indent_end_em: 0.5,
            indent_left_frac: 0.01,
            indent_left_em: 0.25,
            indent_right_frac: 0.01,
            indent_right_em: 0.25,
            first_line_indent: Some(true),
            first_line_indent_em: Some(1.5),
            is_css_text_line: true,
            space_before_em: 0.5,
            space_after_em: 0.75,
            space_before_specified: true,
            space_after_specified: true,
            font_family: Some("literata".into()),
            line_height_mult: Some(1.4),
            hyphens: Some(true),
            page_break_before: true,
            first_letter: Some(Box::new(FrbFirstLetterStyle {
                scale: 2.5,
                bold: Some(true),
                italic: Some(false),
                is_drop_cap: true,
                font_family: Some("serif".into()),
                foreground_color_argb: Some(0xFF11_2233u32 as i32),
                background_color_argb: Some(0xFFAA_BBCCu32 as i32),
                left_side: false,
                direction: Some(FrbBookTextDirection::Ltr),
                language: Some("en".into()),
                source_text_length: Some(1),
                font_weight: Some(700),
                font_typography: Some(typo.clone()),
                font_feature_settings: Some("\"liga\" 1".into()),
            })),
            float_image: Some(Box::new(FrbFloatImage {
                path: "/tmp/float.png".into(),
                width_frac: 0.3,
                left: true,
                alt_text: Some("Ornament".into()),
            })),
            language: Some("uk".into()),
            direction: Some(FrbBookTextDirection::Auto),
            foreground_color_argb: Some(0xFF22_3344u32 as i32),
            background_color_argb: Some(0xFFEE_FFEEu32 as i32),
            font_feature_settings: Some("\"kern\" 1".into()),
            font_weight: Some(500),
            font_typography: Some(Box::new(typo)),
        };

        let border = FrbPublisherBorderSide {
            width_em: 0.1,
            color_argb: 0xFF33_4455u32 as i32,
            style: FrbPublisherBorderStyle::Solid,
            uses_current_color: true,
        };
        let box_style = FrbPublisherBoxStyle {
            margin_top_em: 0.5,
            padding_left_em: 1.0,
            background_color_argb: Some(0xFFF5_F5F5u32 as i32),
            border_top: Some(border),
            border_right: Some(border),
            border_bottom: Some(border),
            border_left: Some(border),
            width_frac: Some(0.9),
            width_em: Some(20.0),
            shrink_to_fit: true,
            hanging_indent_em: 0.5,
            fit_text_flow: true,
            horizontal_align: FrbPublisherBoxAlign::Center,
            break_inside_avoid: true,
            page_break_before: true,
            page_break_after: true,
            min_height_em: Some(2.0),
            min_height_viewport_frac: Some(0.1),
            min_height_offset_em: 0.25,
            center_content_vertically: true,
            border_box: true,
            max_width_em: Some(30.0),
            max_width_frac: Some(1.0),
            ..Default::default()
        };

        let rich_span = FrbSpanStyle {
            color: color_argb_to_compose_ulong(0xFFAA_0000u32 as i32),
            size: FrbTextUnit::Em(1.2),
            weight: Some(700),
            italic: Some(true),
            synthesis: Some("all".into()),
            family: Some("serif".into()),
            features: Some("\"smcp\" 1".into()),
            spacing: FrbTextUnit::Sp(0.5),
            baseline: Some(0.35),
            scale_x: Some(1.05),
            skew_x: Some(-0.1),
            locales: Some(vec!["uk-UA".into()]),
            background: color_argb_to_compose_ulong(0x20FF_FF00u32 as i32),
            decoration: Some(TEXT_DECORATION_UNDERLINE | TEXT_DECORATION_LINE_THROUGH),
            shadow: Some(FrbShadow {
                color: color_argb_to_compose_ulong(0x8000_0000u32 as i32),
                x: 1.0,
                y: 2.0,
                blur: 3.0,
            }),
        };

        let chapter = FrbChapter {
            title: Some("Розділ 1".into()),
            depth: 1,
            elements: vec![
                FrbContentElement::Heading {
                    styled_text: FrbAnnotatedString::plain("Heading One"),
                    level: 2,
                    block: Some(Box::new(full_block.clone())),
                },
                FrbContentElement::Paragraph {
                    text: FrbAnnotatedString {
                        text: "Привіт світе [1]!".into(),
                        ranges: vec![
                            FrbTextRange::span(0, 6, rich_span),
                            FrbTextRange::string(13, 16, FOOTNOTE_TAG, "#n1"),
                            FrbTextRange {
                                start: 7,
                                end: 12,
                                tag: String::new(),
                                annotation: FrbTextAnnotation::Paragraph(Box::new(
                                    FrbParagraphAnnotation {
                                        align: COMPOSE_TEXT_ALIGN_JUSTIFY,
                                        direction: COMPOSE_TEXT_DIRECTION_CONTENT,
                                        height: FrbTextUnit::Em(1.5),
                                        first_indent: Some(FrbTextUnit::Em(1.0)),
                                        rest_indent: Some(FrbTextUnit::Sp(0.0)),
                                    },
                                )),
                            },
                        ],
                    },
                    style: FrbParagraphStyle::Quote,
                    block: Some(Box::new(full_block.clone())),
                },
                FrbContentElement::Image(Box::new(FrbImageElement {
                    path: "/tmp/pic.jpg".into(),
                    width_frac: Some(0.8),
                    width_em: Some(12.0),
                    height_em: Some(8.0),
                    alt_text: Some("Cover art".into()),
                    space_before_specified: true,
                    space_after_specified: true,
                    max_height_em: Some(15.0),
                    max_height_viewport_frac: Some(0.75),
                    max_width_em: Some(20.0),
                    max_width_frac: Some(1.0),
                    dark_mode_invert: true,
                    dark_mode_background_argb: Some(0xFFFF_FFFFu32 as i32),
                    dark_mode_background_current_color: true,
                })),
                FrbContentElement::Divider,
                FrbContentElement::Spacer { height_em: 1.0 },
                FrbContentElement::Spacer { height_em: 2.5 },
                FrbContentElement::Table(Box::new(FrbTableElement {
                    rows: vec![FrbTableRow {
                        is_header: true,
                        cells: vec![
                            FrbTableCell {
                                text: FrbAnnotatedString::plain("Cell Alpha"),
                                col_span: 2,
                                row_span: 1,
                                align: Some(FrbBlockAlign::Center),
                                header: true,
                                block: Some(Box::new(full_block.clone())),
                                publisher_box: Some(Box::new(box_style.clone())),
                                publisher_padding_specified: true,
                                publisher_border_specified: true,
                                narrow_style: Some(Box::new(FrbTableCellStyle {
                                    block: Some(Box::new(FrbBlockStyle {
                                        bold: Some(false),
                                        ..Default::default()
                                    })),
                                    publisher_box: Some(Box::new(box_style.clone())),
                                    align: Some(FrbBlockAlign::Start),
                                    publisher_padding_specified: true,
                                    publisher_border_specified: false,
                                })),
                            },
                            FrbTableCell::new(FrbAnnotatedString::plain("Cell Beta")),
                        ],
                    }],
                    block: Some(Box::new(full_block)),
                    publisher_row_layout: Some(FrbPublisherRowLayout::SpaceBetween),
                    center_cells_vertically: true,
                    stack_cells_below_css_px: Some(480.0),
                    publisher_box: Some(Box::new(box_style.clone())),
                })),
            ],
            publisher_boxes: vec![FrbPublisherBoxSpan {
                id: "box-root".into(),
                parent_id: None,
                start_element: 0,
                end_element_exclusive: 7,
                style: box_style,
                float_side: Some(FrbPublisherFloatSide::Left),
                clear: FrbPublisherClear::Both,
                draw_start: false,
                draw_end: false,
            }],
        };

        let bytes = encode_chapter(&chapter);
        // Search with footnotes hidden: "[1]" should be stripped!
        let hidden = search_frb_chapter_payload(&bytes, 0, 0, Some(7), "[1]", true, 10)
            .expect("book_search must decode full chapter");
        assert_eq!(hidden.total_count, 0);

        let visible = search_frb_chapter_payload(&bytes, 0, 0, Some(7), "[1]", false, 10)
            .expect("book_search must decode full chapter");
        assert_eq!(visible.total_count, 1);

        let table_hit = search_frb_chapter_payload(&bytes, 0, 0, Some(7), "Cell Beta", false, 10)
            .expect("book_search must decode table cell");
        assert_eq!(table_hit.total_count, 1);
        assert_eq!(table_hit.matches[0].item_index, 6);
    }

    #[test]
    fn build_book_index_collects_resources_and_publisher_style() {
        let mut notes = BTreeMap::new();
        notes.insert(
            "#n1".to_string(),
            FrbNoteDocument {
                elements: vec![FrbContentElement::Image(Box::new(FrbImageElement::new(
                    "/img/note.png",
                )))],
                publisher_boxes: Vec::new(),
            },
        );
        let mut linked_documents = BTreeMap::new();
        linked_documents.insert(
            "doc1".to_string(),
            FrbLinkedDocument {
                id: "doc1".into(),
                title: Some("Appendix".into()),
                elements: vec![FrbContentElement::Paragraph {
                    text: FrbAnnotatedString {
                        text: format!("{INLINE_IMAGE_CHAR} inline"),
                        ranges: vec![FrbTextRange::string(
                            0,
                            1,
                            INLINE_IMAGE_TAG,
                            "/img/inline.png",
                        )],
                    },
                    style: FrbParagraphStyle::Normal,
                    block: None,
                }],
                publisher_boxes: Vec::new(),
            },
        );

        let body_block = FrbBlockStyle {
            align: Some(FrbBlockAlign::Justify),
            line_height_mult: Some(1.45),
            hyphens: Some(true),
            font_family: Some("custom serif".into()),
            first_letter: Some(Box::new(FrbFirstLetterStyle::new(2.0, true))),
            float_image: Some(Box::new(FrbFloatImage {
                path: "/img/float.png".into(),
                width_frac: 0.25,
                left: true,
                alt_text: None,
            })),
            ..Default::default()
        };

        let content = FrbBookContent {
            chapters: vec![
                FrbChapter {
                    title: Some("Ch 1".into()),
                    depth: 0,
                    elements: vec![
                        FrbContentElement::Paragraph {
                            text: FrbAnnotatedString::plain("First"),
                            style: FrbParagraphStyle::Normal,
                            block: Some(Box::new(body_block)),
                        },
                        FrbContentElement::Divider,
                    ],
                    publisher_boxes: Vec::new(),
                },
                FrbChapter {
                    title: None,
                    depth: 1,
                    elements: vec![FrbContentElement::Spacer { height_em: 1.0 }],
                    publisher_boxes: Vec::new(),
                },
            ],
            notes,
            link_targets: BTreeMap::from([("#c1".into(), (0, 0))]),
            fonts: vec![
                FrbBookFont::new("custom serif", "/fonts/bold.ttf", true, false),
                FrbBookFont::new("custom serif", "/fonts/regular.ttf", false, false),
            ],
            language: Some("en".into()),
            linked_documents,
            linked_document_targets: BTreeMap::from([(
                "a1".into(),
                FrbLinkedDocumentTarget {
                    document_id: "doc1".into(),
                    element_index: 0,
                },
            )]),
            navigation: vec![FrbNavigationEntry {
                title: "Ch 1".into(),
                depth: 0,
                target: FrbNavigationTarget::ReadingOrder {
                    chapter_index: 0,
                    element_index: 0,
                },
            }],
            publisher_publication: None,
            page_progression: FrbPageProgression::Ltr,
            epub_source_ranges: Vec::new(),
        };

        let idx = build_book_index(&content).unwrap();
        assert_eq!(idx.total_elements, 3);
        assert_eq!(idx.chapters.len(), 2);
        assert_eq!(idx.chapters[0].start_element, 0);
        assert_eq!(idx.chapters[0].element_count, 2);
        assert_eq!(idx.chapters[1].start_element, 2);
        assert_eq!(idx.chapters[1].element_count, 1);
        assert_eq!(
            idx.resource_paths,
            vec![
                "/fonts/bold.ttf",
                "/fonts/regular.ttf",
                "/img/float.png",
                "/img/inline.png",
                "/img/note.png",
            ]
        );
        let ps = idx
            .publisher_style
            .expect("publisher_style must be present");
        assert_eq!(ps.justify, Some(true));
        assert_eq!(ps.line_height, Some(1.45));
        assert_eq!(ps.hyphenation, Some(true));
        assert!(ps.drop_caps);
        assert_eq!(ps.font_name.as_deref(), Some("Custom serif"));
        // Representative face prefers regular (400) over bold (700).
        assert_eq!(ps.font_path.as_deref(), Some("/fonts/regular.ttf"));
        assert_eq!(ps.font_css.as_deref(), Some("custom serif"));
        assert_eq!(ps.embedded_fonts.len(), 2);
        assert_eq!(ps.font_faces.len(), 2);
    }

    #[test]
    fn write_book_frb_roundtrips_through_frb_reader_and_search() {
        let dir = TempDir::new();
        let dest = dir.0.join("book.frb");
        let meta = b"test-source-meta-v8";

        let content = FrbBookContent {
            chapters: vec![
                FrbChapter {
                    title: Some("Chapter 1".into()),
                    elements: vec![FrbContentElement::Paragraph {
                        text: FrbAnnotatedString::plain("Hello FrogReader native FRB"),
                        style: FrbParagraphStyle::Normal,
                        block: None,
                    }],
                    depth: 0,
                    publisher_boxes: Vec::new(),
                },
                FrbChapter {
                    title: Some("Chapter 2".into()),
                    elements: vec![
                        FrbContentElement::Heading {
                            styled_text: FrbAnnotatedString::plain("Second Part"),
                            level: 1,
                            block: None,
                        },
                        FrbContentElement::Paragraph {
                            text: FrbAnnotatedString::plain("Goodbye FrogReader"),
                            style: FrbParagraphStyle::Poem,
                            block: None,
                        },
                    ],
                    depth: 1,
                    publisher_boxes: Vec::new(),
                },
            ],
            language: Some("en".into()),
            ..Default::default()
        };

        write_book_frb(&content, &dest, meta, MAX_FILE_BYTES).expect("write_book_frb failed");

        let mut reader = Reader::open(&dest, meta).expect("Reader::open failed");
        let native_index = reader.index();
        assert_eq!(native_index.len(), 10); // 2 chapters * 5 fields
        assert_eq!(&native_index[3..5], &[1, 0]);
        assert_eq!(&native_index[8..10], &[2, 1]);

        let ch0 = reader.read_block_bytes(2).unwrap();
        let ch1 = reader.read_block_bytes(3).unwrap();
        let r0 = search_frb_chapter_payload(&ch0, 0, 0, Some(1), "FrogReader", false, 10).unwrap();
        let r1 = search_frb_chapter_payload(&ch1, 1, 1, Some(2), "FrogReader", false, 10).unwrap();
        assert_eq!(r0.total_count, 1);
        assert_eq!(r1.total_count, 1);
        assert_eq!(r1.matches[0].item_index, 2);

        // Also check block 0 and block 1 are non-empty and match direct encoders.
        let expected_index = build_book_index(&content).unwrap();
        assert_eq!(
            reader.read_block_bytes(0).unwrap(),
            encode_book_index(&expected_index)
        );
        assert_eq!(
            reader.read_block_bytes(1).unwrap(),
            encode_book_globals(&content)
        );
    }

    #[test]
    fn golden_cross_language_parity_fixture() {
        // Canonical fixture mirrored verbatim in `FrbModelCodecTest.kt` to verify
        // 100% byte-for-byte parity (exact length + CRC-32) between Rust `frb_model`
        // and Kotlin `FrbModelCodec.encodeToBytes`.
        let mut axes = BTreeMap::new();
        axes.insert("opsz".to_string(), 14.0);
        axes.insert("wght".to_string(), 550.0);
        let typo = FrbFontTypography {
            variation_axes: Some(axes),
            stretch_percent: Some(95.0),
            optical_sizing: Some(true),
        };
        let rich_block = FrbBlockStyle {
            align: Some(FrbBlockAlign::Justify),
            italic: Some(false),
            bold: Some(true),
            font_scale: Some(1.25),
            indent_start_frac: 0.05,
            indent_start_em: 1.0,
            indent_end_frac: 0.02,
            indent_end_em: 0.5,
            indent_left_frac: 0.01,
            indent_left_em: 0.25,
            indent_right_frac: 0.01,
            indent_right_em: 0.25,
            first_line_indent: Some(true),
            first_line_indent_em: Some(1.5),
            is_css_text_line: true,
            space_before_em: 0.5,
            space_after_em: 0.75,
            space_before_specified: true,
            space_after_specified: true,
            font_family: Some("custom serif".into()),
            line_height_mult: Some(1.4),
            hyphens: Some(true),
            page_break_before: true,
            first_letter: Some(Box::new(FrbFirstLetterStyle {
                scale: 2.5,
                bold: Some(true),
                italic: Some(false),
                is_drop_cap: true,
                font_family: Some("serif".into()),
                foreground_color_argb: Some(0xFF11_2233u32 as i32),
                background_color_argb: Some(0xFFAA_BBCCu32 as i32),
                left_side: false,
                direction: Some(FrbBookTextDirection::Ltr),
                language: Some("en".into()),
                source_text_length: Some(1),
                font_weight: Some(700),
                font_typography: Some(typo.clone()),
                font_feature_settings: Some("\"liga\" 1".into()),
            })),
            float_image: Some(Box::new(FrbFloatImage {
                path: "/img/float.png".into(),
                width_frac: 0.25,
                left: true,
                alt_text: Some("Ornament".into()),
            })),
            language: Some("uk".into()),
            direction: Some(FrbBookTextDirection::Auto),
            foreground_color_argb: Some(0xFF22_3344u32 as i32),
            background_color_argb: Some(0xFFEE_FFEEu32 as i32),
            font_feature_settings: Some("\"kern\" 1".into()),
            font_weight: Some(500),
            font_typography: Some(Box::new(typo)),
        };

        let border = FrbPublisherBorderSide {
            width_em: 0.125,
            color_argb: 0xFF33_4455u32 as i32,
            style: FrbPublisherBorderStyle::Solid,
            uses_current_color: true,
        };
        let box_style = FrbPublisherBoxStyle {
            margin_top_em: 0.5,
            padding_left_em: 1.0,
            background_color_argb: Some(0xFFF5_F5F5u32 as i32),
            border_top: Some(border),
            border_right: Some(border),
            border_bottom: Some(border),
            border_left: Some(border),
            width_frac: Some(0.9),
            width_em: Some(20.0),
            shrink_to_fit: true,
            hanging_indent_em: 0.5,
            fit_text_flow: true,
            horizontal_align: FrbPublisherBoxAlign::Center,
            break_inside_avoid: true,
            page_break_before: true,
            page_break_after: true,
            min_height_em: Some(2.0),
            min_height_viewport_frac: Some(0.125),
            min_height_offset_em: 0.25,
            center_content_vertically: true,
            border_box: true,
            max_width_em: Some(30.0),
            max_width_frac: Some(1.0),
            ..Default::default()
        };

        let rich_span = FrbSpanStyle {
            color: color_argb_to_compose_ulong(0xFFAA_0000u32 as i32),
            size: FrbTextUnit::Em(1.25),
            weight: Some(700),
            italic: Some(true),
            synthesis: Some("all".into()),
            family: Some("serif".into()),
            features: Some("\"smcp\" 1".into()),
            spacing: FrbTextUnit::Sp(0.5),
            baseline: Some(0.35),
            scale_x: Some(1.05),
            skew_x: Some(-0.125),
            locales: Some(vec!["uk-UA".into()]),
            background: color_argb_to_compose_ulong(0x20FF_FF00u32 as i32),
            decoration: Some(TEXT_DECORATION_UNDERLINE | TEXT_DECORATION_LINE_THROUGH),
            shadow: Some(FrbShadow {
                color: color_argb_to_compose_ulong(0x8000_0000u32 as i32),
                x: 1.0,
                y: 2.0,
                blur: 3.0,
            }),
        };
        let link_styles = FrbLinkStyles {
            style: Some(rich_span.clone()),
            focused: None,
            hovered: None,
            pressed: Some(FrbSpanStyle {
                weight: Some(700),
                ..Default::default()
            }),
        };
        let para_ann = FrbParagraphAnnotation {
            align: COMPOSE_TEXT_ALIGN_JUSTIFY,
            direction: COMPOSE_TEXT_DIRECTION_CONTENT,
            height: FrbTextUnit::Em(1.5),
            first_indent: Some(FrbTextUnit::Em(1.0)),
            rest_indent: Some(FrbTextUnit::Sp(0.0)),
        };

        let ch0 = FrbChapter {
            title: Some("Розділ 1 📖".into()),
            depth: 1,
            elements: vec![
                FrbContentElement::Heading {
                    styled_text: FrbAnnotatedString::plain("Heading One"),
                    level: 2,
                    block: Some(Box::new(rich_block.clone())),
                },
                FrbContentElement::Paragraph {
                    text: FrbAnnotatedString {
                        text: format!("Привіт світе [1] {INLINE_IMAGE_CHAR}!"),
                        ranges: vec![
                            FrbTextRange::span(0, 6, rich_span.clone()),
                            FrbTextRange::string(13, 16, FOOTNOTE_TAG, "#n1"),
                            FrbTextRange::string(17, 18, INLINE_IMAGE_TAG, "/img/inline.png"),
                            FrbTextRange::paragraph(0, 12, para_ann.clone()),
                            FrbTextRange::link(
                                7,
                                12,
                                "https://example.invalid",
                                Some(link_styles.clone()),
                            ),
                            FrbTextRange {
                                start: 0,
                                end: 6,
                                tag: String::new(),
                                annotation: FrbTextAnnotation::Tts("pryvit".into()),
                            },
                            FrbTextRange {
                                start: 7,
                                end: 12,
                                tag: String::new(),
                                annotation: FrbTextAnnotation::Url("https://url.invalid".into()),
                            },
                            FrbTextRange {
                                start: 0,
                                end: 1,
                                tag: String::new(),
                                annotation: FrbTextAnnotation::Click {
                                    tag: "act".into(),
                                    styles: Some(Box::new(link_styles)),
                                },
                            },
                        ],
                    },
                    style: FrbParagraphStyle::Normal,
                    block: Some(Box::new(rich_block.clone())),
                },
                // Repeat styles to exercise all 5 @InternedFrbStyle hit paths.
                FrbContentElement::Paragraph {
                    text: FrbAnnotatedString {
                        text: "Repeat".into(),
                        ranges: vec![
                            FrbTextRange::span(0, 6, rich_span),
                            FrbTextRange::paragraph(0, 6, para_ann),
                            FrbTextRange::string(0, 6, LINK_TAG, "#c1"),
                        ],
                    },
                    style: FrbParagraphStyle::Quote,
                    block: Some(Box::new(rich_block.clone())),
                },
                FrbContentElement::Image(Box::new(FrbImageElement {
                    path: "/img/pic.jpg".into(),
                    width_frac: Some(0.8),
                    width_em: Some(12.0),
                    height_em: Some(8.0),
                    alt_text: Some("Cover art".into()),
                    space_before_specified: true,
                    space_after_specified: true,
                    max_height_em: Some(15.0),
                    max_height_viewport_frac: Some(0.75),
                    max_width_em: Some(20.0),
                    max_width_frac: Some(1.0),
                    dark_mode_invert: true,
                    dark_mode_background_argb: Some(0xFFFF_FFFFu32 as i32),
                    dark_mode_background_current_color: true,
                })),
                FrbContentElement::Divider,
                FrbContentElement::Spacer { height_em: 1.0 },
                FrbContentElement::Spacer { height_em: 2.5 },
                FrbContentElement::Table(Box::new(FrbTableElement {
                    rows: vec![FrbTableRow {
                        is_header: true,
                        cells: vec![
                            FrbTableCell {
                                text: FrbAnnotatedString::plain("Cell Alpha"),
                                col_span: 2,
                                row_span: 1,
                                align: Some(FrbBlockAlign::Center),
                                header: true,
                                block: Some(Box::new(rich_block.clone())),
                                publisher_box: Some(Box::new(box_style.clone())),
                                publisher_padding_specified: true,
                                publisher_border_specified: true,
                                narrow_style: Some(Box::new(FrbTableCellStyle {
                                    block: Some(Box::new(FrbBlockStyle {
                                        bold: Some(false),
                                        ..Default::default()
                                    })),
                                    publisher_box: Some(Box::new(box_style.clone())),
                                    align: Some(FrbBlockAlign::Start),
                                    publisher_padding_specified: true,
                                    publisher_border_specified: false,
                                })),
                            },
                            FrbTableCell::new(FrbAnnotatedString::plain("Cell Beta")),
                        ],
                    }],
                    block: Some(Box::new(rich_block)),
                    publisher_row_layout: Some(FrbPublisherRowLayout::SpaceBetween),
                    center_cells_vertically: true,
                    stack_cells_below_css_px: Some(480.0),
                    publisher_box: Some(Box::new(box_style.clone())),
                })),
            ],
            publisher_boxes: vec![
                FrbPublisherBoxSpan {
                    id: "box-root".into(),
                    parent_id: None,
                    start_element: 0,
                    end_element_exclusive: 8,
                    style: box_style.clone(),
                    float_side: Some(FrbPublisherFloatSide::Left),
                    clear: FrbPublisherClear::Both,
                    draw_start: false,
                    draw_end: false,
                },
                FrbPublisherBoxSpan {
                    id: "box-child".into(),
                    parent_id: Some("box-root".into()),
                    start_element: 1,
                    end_element_exclusive: 3,
                    style: box_style,
                    float_side: None,
                    clear: FrbPublisherClear::None,
                    draw_start: true,
                    draw_end: true,
                },
            ],
        };

        let content = FrbBookContent {
            chapters: vec![ch0],
            notes: BTreeMap::from([(
                "#n1".into(),
                FrbNoteDocument {
                    elements: vec![FrbContentElement::Paragraph {
                        text: FrbAnnotatedString::plain("Footnote body"),
                        style: FrbParagraphStyle::Normal,
                        block: None,
                    }],
                    publisher_boxes: Vec::new(),
                },
            )]),
            link_targets: BTreeMap::from([("#c1".into(), (0, 0)), ("#p2".into(), (0, 2))]),
            fonts: vec![
                FrbBookFont::new("custom serif", "/fonts/bold.ttf", true, false),
                FrbBookFont {
                    family: "custom serif".into(),
                    path: "/fonts/var.ttf".into(),
                    bold: false,
                    italic: false,
                    weight: 400,
                    weight_min: 300,
                    weight_max: 700,
                    stretch_min: 75.0,
                    stretch_max: 125.0,
                    unicode_range: Some("U+0000-00FF".into()),
                },
            ],
            language: Some("uk".into()),
            linked_documents: BTreeMap::from([(
                "doc1".into(),
                FrbLinkedDocument {
                    id: "doc1".into(),
                    title: None,
                    elements: vec![FrbContentElement::Image(Box::new(FrbImageElement::new(
                        "/img/linked.png",
                    )))],
                    publisher_boxes: Vec::new(),
                },
            )]),
            linked_document_targets: BTreeMap::from([(
                "t1".into(),
                FrbLinkedDocumentTarget {
                    document_id: "doc1".into(),
                    element_index: 0,
                },
            )]),
            navigation: vec![
                FrbNavigationEntry {
                    title: "Ch 1".into(),
                    depth: 0,
                    target: FrbNavigationTarget::ReadingOrder {
                        chapter_index: 0,
                        element_index: 0,
                    },
                },
                FrbNavigationEntry {
                    title: "Doc 1".into(),
                    depth: 1,
                    target: FrbNavigationTarget::Linked {
                        document_id: "doc1".into(),
                        element_index: 0,
                    },
                },
            ],
            publisher_publication: None,
            page_progression: FrbPageProgression::Rtl,
            epub_source_ranges: vec![FrbEpubSourceRange {
                spine_ordinal: 0,
                path: "OPS/ch1.xhtml".into(),
                document_start: 0,
                chapter_index: 0,
                chapter_start: 0,
                element_count: 8,
            }],
        };

        let ch_bytes = encode_chapter(&content.chapters[0]);
        let idx = build_book_index(&content).unwrap();
        let idx_bytes = encode_book_index(&idx);
        let globals_bytes = encode_book_globals(&content);
        let full_bytes = encode_book_content(&content);

        // Exact byte lengths and CRC-32 checksums verified verbatim against Kotlin
        // `FrbModelCodec.encodeToBytes` in `FrbModelCodecTest.kt`.
        assert_eq!(ch_bytes.len(), 1574);
        assert_eq!(crc32fast::hash(&ch_bytes), 0xd8c8_065f);
        assert_eq!(idx_bytes.len(), 550);
        assert_eq!(crc32fast::hash(&idx_bytes), 0x80a0_8724);
        assert_eq!(globals_bytes.len(), 480);
        assert_eq!(crc32fast::hash(&globals_bytes), 0x86ae_2c17);
        assert_eq!(full_bytes.len(), 1950);
        assert_eq!(crc32fast::hash(&full_bytes), 0x8bcb_b83e);
    }
}
