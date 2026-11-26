//! Native Rust Presentation MathML parser, formatter, and `InlineMath` JSON codec.
//!
//! Mirrors Kotlin `MathMlFormatter.kt`, `MathMlNativeParser.kt`, and `InlineMath.kt`
//! 1:1 so native layout metadata and linear selectable fallback match bit-for-bit.

use super::{
    kotlin_is_blank, kotlin_trim, merge_span_styles, non_blank_or, push_json_string_escaped,
    safe_external_href, sub_sequence_annotated, utf16_len, HtmlInlineBuilder, RubySpan,
};
use crate::chapter_markup::{
    is_java_regex_ws, kotlin_is_whitespace, Document, Kind, TEXT_HTML_BLANK,
};
use crate::css_engine::format_kotlin_float;
use crate::frb_model::{
    color_argb_to_compose_ulong, FrbSpanStyle, FrbTextAnnotation, FrbTextUnit,
    COMPOSE_COLOR_UNSPECIFIED, EXTERNAL_LINK_TAG, LINK_TAG, TEXT_DECORATION_LINE_THROUGH,
    TEXT_DECORATION_UNDERLINE,
};

pub(super) const INLINE_MATH_TAG: &str = "frogreader:math";
const INLINE_MATH_CONTENT_PREFIX: &str = "frogreader:math:";

const MAX_NODES: usize = 256;
const MAX_DEPTH: usize = 32;
const MAX_TEXT: usize = 4096;
const MAX_PAYLOAD: usize = 32768;

const MISSING_OPERAND: &str = "?";
const FRACTION_SLASH: &str = "⁄";
const SQUARE_ROOT: &str = "√";
const ERROR_COLOR_ARGB: i32 = 0xFFB3_261E_u32 as i32;

// --------------------------------------------------------------------------- Host Context

pub(super) trait MathContext {
    fn doc(&self) -> &Document;
    fn max_depth(&self) -> usize;
    fn resolve_internal_link(&mut self, href: &str) -> Option<String>;
    fn on_internal_link(&mut self, key: String);
    fn resolve_author_style(&mut self, node_id: usize) -> Option<FrbSpanStyle>;
}

// --------------------------------------------------------------------------- InlineMath Model & Codec

#[derive(Clone, Debug, PartialEq)]
struct InlineMath {
    root: InlineMathNode,
    fallback: String,
    description: String,
    whole_styles: Vec<RubySpan>,
}

#[derive(Clone, Debug, PartialEq)]
struct InlineMathNode {
    kind: &'static str,
    text: String,
    children: Vec<InlineMathNode>,
    style: InlineMathStyle,
    left_space_em: f32,
    right_space_em: f32,
    stretchy: bool,
}

impl Default for InlineMathNode {
    fn default() -> Self {
        Self {
            kind: "row",
            text: String::new(),
            children: Vec::new(),
            style: InlineMathStyle::default(),
            left_space_em: 0.0,
            right_space_em: 0.0,
            stretchy: false,
        }
    }
}

impl InlineMathNode {
    fn is_default(&self) -> bool {
        self.kind == "row"
            && self.text.is_empty()
            && self.children.is_empty()
            && self.style.is_default()
            && self.left_space_em.to_bits() == 0.0f32.to_bits()
            && self.right_space_em.to_bits() == 0.0f32.to_bits()
            && !self.stretchy
    }
}

#[derive(Clone, Debug, PartialEq)]
struct InlineMathStyle {
    italic: Option<bool>,
    weight: Option<i32>,
    font_family: Option<&'static str>,
    font_size_em: f32,
    color_argb: Option<i32>,
    underline: bool,
    strike: bool,
    font_features: Option<String>,
    language: Option<String>,
}

impl Default for InlineMathStyle {
    fn default() -> Self {
        Self {
            italic: None,
            weight: None,
            font_family: None,
            font_size_em: 1.0,
            color_argb: None,
            underline: false,
            strike: false,
            font_features: None,
            language: None,
        }
    }
}

impl InlineMathStyle {
    fn is_default(&self) -> bool {
        self.italic.is_none()
            && self.weight.is_none()
            && self.font_family.is_none()
            && self.font_size_em.to_bits() == 1.0f32.to_bits()
            && self.color_argb.is_none()
            && !self.underline
            && !self.strike
            && self.font_features.is_none()
            && self.language.is_none()
    }
}

fn encode_inline_math(value: &InlineMath) -> Option<String> {
    if !is_valid_inline_math(value) {
        return None;
    }
    let mut out = String::new();
    write_inline_math_json(&mut out, value);
    (utf16_len(&out) <= MAX_PAYLOAD).then_some(out)
}

fn is_valid_inline_math(value: &InlineMath) -> bool {
    if value.whole_styles.len() > MAX_NODES
        || kotlin_is_blank(&value.fallback)
        || utf16_len(&value.fallback) > MAX_TEXT
        || utf16_len(&value.description) > MAX_TEXT
        || value.fallback.contains('\n')
    {
        return false;
    }
    let mut nodes = 0usize;
    let mut text = 0usize;
    fn visit(
        node: &InlineMathNode,
        depth: usize,
        parent_scale: f32,
        nodes: &mut usize,
        text: &mut usize,
    ) -> bool {
        *nodes += 1;
        if *nodes > MAX_NODES || depth >= MAX_DEPTH {
            return false;
        }
        *text += utf16_len(&node.text);
        if *text > MAX_TEXT {
            return false;
        }
        let count = node.children.len();
        let bad_arity = match node.kind {
            "token" => count != 0 || node.text.is_empty(),
            "row" | "sqrt" => count == 0,
            "frac" | "root" | "sup" | "sub" => count != 2,
            "subsup" => count != 3,
            _ => true,
        };
        if bad_arity {
            return false;
        }
        let style = &node.style;
        let scale = parent_scale * style.font_size_em;
        if !style.font_size_em.is_finite()
            || !(0.25..=4.0).contains(&style.font_size_em)
            || !scale.is_finite()
            || !(0.01..=8.0).contains(&scale)
            || style.weight.is_some_and(|w| !(1..=1000).contains(&w))
            || style
                .font_family
                .is_some_and(|f| !matches!(f, "serif" | "sans-serif" | "monospace" | "cursive"))
            || style
                .font_features
                .as_deref()
                .is_some_and(|f| utf16_len(f) > 256)
            || style
                .language
                .as_deref()
                .is_some_and(|l| utf16_len(l) > 128)
            || !node.left_space_em.is_finite()
            || !(0.0..=2.0).contains(&node.left_space_em)
            || !node.right_space_em.is_finite()
            || !(0.0..=2.0).contains(&node.right_space_em)
        {
            return false;
        }
        node.children
            .iter()
            .all(|child| visit(child, depth + 1, scale, nodes, text))
    }
    visit(&value.root, 0, 1.0, &mut nodes, &mut text)
}

fn write_inline_math_json(out: &mut String, value: &InlineMath) {
    out.push('{');
    let mut comma = false;
    if !value.root.is_default() {
        out.push_str("\"root\":");
        write_math_node_json(out, &value.root);
        comma = true;
    }
    if !value.fallback.is_empty() {
        if comma {
            out.push(',');
        }
        out.push_str("\"fallback\":");
        push_json_string_escaped(out, &value.fallback);
        comma = true;
    }
    if !value.description.is_empty() {
        if comma {
            out.push(',');
        }
        out.push_str("\"description\":");
        push_json_string_escaped(out, &value.description);
        comma = true;
    }
    if !value.whole_styles.is_empty() {
        if comma {
            out.push(',');
        }
        out.push_str("\"wholeStyles\":[");
        for (i, span) in value.whole_styles.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            span.write_json(out);
        }
        out.push(']');
    }
    out.push('}');
}

fn write_math_node_json(out: &mut String, node: &InlineMathNode) {
    out.push('{');
    let mut comma = false;
    if node.kind != "row" {
        out.push_str("\"kind\":");
        push_json_string_escaped(out, node.kind);
        comma = true;
    }
    if !node.text.is_empty() {
        if comma {
            out.push(',');
        }
        out.push_str("\"text\":");
        push_json_string_escaped(out, &node.text);
        comma = true;
    }
    if !node.children.is_empty() {
        if comma {
            out.push(',');
        }
        out.push_str("\"children\":[");
        for (i, child) in node.children.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            write_math_node_json(out, child);
        }
        out.push(']');
        comma = true;
    }
    if !node.style.is_default() {
        if comma {
            out.push(',');
        }
        out.push_str("\"style\":");
        write_math_style_json(out, &node.style);
        comma = true;
    }
    if node.left_space_em != 0.0 {
        if comma {
            out.push(',');
        }
        out.push_str("\"leftSpaceEm\":");
        out.push_str(&format_kotlin_float(node.left_space_em));
        comma = true;
    }
    if node.right_space_em != 0.0 {
        if comma {
            out.push(',');
        }
        out.push_str("\"rightSpaceEm\":");
        out.push_str(&format_kotlin_float(node.right_space_em));
        comma = true;
    }
    if node.stretchy {
        if comma {
            out.push(',');
        }
        out.push_str("\"stretchy\":true");
    }
    out.push('}');
}

fn write_math_style_json(out: &mut String, style: &InlineMathStyle) {
    out.push('{');
    let mut comma = false;
    if let Some(italic) = style.italic {
        out.push_str("\"italic\":");
        out.push_str(if italic { "true" } else { "false" });
        comma = true;
    }
    if let Some(weight) = style.weight {
        if comma {
            out.push(',');
        }
        out.push_str("\"weight\":");
        out.push_str(&weight.to_string());
        comma = true;
    }
    if let Some(fam) = style.font_family {
        if comma {
            out.push(',');
        }
        out.push_str("\"fontFamily\":");
        push_json_string_escaped(out, fam);
        comma = true;
    }
    if style.font_size_em != 1.0 {
        if comma {
            out.push(',');
        }
        out.push_str("\"fontSizeEm\":");
        out.push_str(&format_kotlin_float(style.font_size_em));
        comma = true;
    }
    if let Some(color) = style.color_argb {
        if comma {
            out.push(',');
        }
        out.push_str("\"colorArgb\":");
        out.push_str(&color.to_string());
        comma = true;
    }
    if style.underline {
        if comma {
            out.push(',');
        }
        out.push_str("\"underline\":true");
        comma = true;
    }
    if style.strike {
        if comma {
            out.push(',');
        }
        out.push_str("\"strike\":true");
        comma = true;
    }
    if let Some(features) = &style.font_features {
        if comma {
            out.push(',');
        }
        out.push_str("\"fontFeatures\":");
        push_json_string_escaped(out, features);
        comma = true;
    }
    if let Some(lang) = &style.language {
        if comma {
            out.push(',');
        }
        out.push_str("\"language\":");
        push_json_string_escaped(out, lang);
    }
    out.push('}');
}

// --------------------------------------------------------------------------- Public Entry Point

/// Formats a `<math>` element into `out`, attaching native `InlineMath` presentation
/// metadata when admitted by `MathMlNativeParser`, or emitting the linear styled
/// fallback directly.
pub(super) fn append_math<C: MathContext>(
    ctx: &mut C,
    math_node: usize,
    out: &mut HtmlInlineBuilder,
    apply_root_author_style: bool,
) {
    let native = parse_native_math(ctx, math_node, apply_root_author_style);
    let Some(native) = native else {
        append_element(ctx, math_node, out, 0, apply_root_author_style);
        return;
    };

    let preserve_leading_space = out.last_character.is_some_and(|ch| ch != ' ' && ch != '\n');
    let mut fb_builder = HtmlInlineBuilder::new();
    if preserve_leading_space {
        fb_builder.text("\u{FFFF}");
    }
    append_element(ctx, math_node, &mut fb_builder, 0, apply_root_author_style);
    let raw_fallback = fb_builder.build();
    let raw_len = utf16_len(&raw_fallback.text) as i32;
    let fallback = if preserve_leading_space {
        sub_sequence_annotated(&raw_fallback, 1, raw_len)
    } else {
        raw_fallback
    };
    let fb_len = utf16_len(&fallback.text) as i32;
    let whole_styles: Vec<RubySpan> = fallback
        .ranges
        .iter()
        .filter_map(|r| {
            if r.start == 0 && r.end == fb_len {
                if let FrbTextAnnotation::Span(ref style) = r.annotation {
                    return Some(RubySpan::from_frb_span(style, 0, 0));
                }
            }
            None
        })
        .collect();
    let description =
        accessibility_label(ctx.doc(), math_node).unwrap_or_else(|| fallback.text.clone());
    let inline_math = InlineMath {
        root: native,
        fallback: fallback.text.clone(),
        description,
        whole_styles,
    };
    let Some(payload) = encode_inline_math(&inline_math) else {
        out.append_annotated(&fallback, false);
        return;
    };
    out.push_annotation(INLINE_MATH_TAG, payload.clone());
    let inline_id = format!("{INLINE_MATH_CONTENT_PREFIX}{payload}");
    out.inline_annotated(&inline_id, fallback);
    out.pop();
}

// --------------------------------------------------------------------------- MathMlNativeParser

fn parse_native_math<C: MathContext>(
    ctx: &mut C,
    math_node: usize,
    apply_root_author_style: bool,
) -> Option<InlineMathNode> {
    let mut count = 0usize;
    let mut chars = 0usize;
    visit_native(
        ctx,
        math_node,
        0,
        apply_root_author_style,
        &mut count,
        &mut chars,
    )
}

fn visit_native<C: MathContext>(
    ctx: &mut C,
    element: usize,
    depth: usize,
    apply_root_author_style: bool,
    count: &mut usize,
    chars: &mut usize,
) -> Option<InlineMathNode> {
    *count += 1;
    if *count > MAX_NODES || depth >= MAX_DEPTH {
        return None;
    }
    if ctx.doc().attr(element, "href").is_some() || ctx.doc().attr(element, "xlink:href").is_some()
    {
        return None;
    }
    let name = math_name(ctx.doc(), element);
    let children: Vec<usize> = element_children(ctx.doc(), element)
        .into_iter()
        .filter(|&c| {
            let cname = math_name(ctx.doc(), c);
            !matches!(cname.as_str(), "annotation" | "annotation-xml")
        })
        .collect();
    let direct_raw = direct_text(ctx.doc(), element);
    let direct = kotlin_trim(&direct_raw);

    let author_css = if depth > 0 || apply_root_author_style {
        ctx.resolve_author_style(element)
    } else {
        None
    };
    let mut style = native_style(ctx.doc(), element, author_css.as_ref())?;
    if style.color_argb.is_some() {
        return None;
    }

    if matches!(name.as_str(), "mi" | "mn" | "mo" | "mtext") {
        if !children.is_empty() || direct.is_empty() || direct.contains('\n') {
            return None;
        }
        *chars += utf16_len(direct);
        if *chars > MAX_TEXT {
            return None;
        }
        if name == "mi" && direct.chars().count() == 1 && style.italic.is_none() {
            style.italic = Some(true);
        }
        let fence = ctx.doc().attr_or_empty(element, "fence") == "true" || is_native_fence(direct);
        let padded = name == "mo" && !fence && is_native_operator(direct);
        let left_space_em = if padded { 0.18 } else { 0.0 };
        let right_space_em = if padded {
            0.18
        } else if name == "mo" && matches!(direct, "," | ";") {
            0.15
        } else {
            0.0
        };
        let stretchy = name == "mo"
            && fence
            && !ctx
                .doc()
                .attr_or_empty(element, "stretchy")
                .eq_ignore_ascii_case("false");
        return Some(InlineMathNode {
            kind: "token",
            text: direct.to_owned(),
            children: Vec::new(),
            style,
            left_space_em,
            right_space_em,
            stretchy,
        });
    }

    if !direct.is_empty() {
        return None;
    }

    let kind = match name.as_str() {
        "math" | "mrow" | "mstyle" => "row",
        "semantics" => {
            let first = *children.first()?;
            return visit_native(ctx, first, depth + 1, apply_root_author_style, count, chars);
        }
        "mfrac" => "frac",
        "msqrt" => "sqrt",
        "mroot" => "root",
        "msup" => "sup",
        "msub" => "sub",
        "msubsup" => "subsup",
        _ => return None,
    };

    if children.is_empty()
        || (matches!(kind, "frac" | "root" | "sup" | "sub") && children.len() != 2)
        || (kind == "subsup" && children.len() != 3)
    {
        return None;
    }
    if name == "mfrac"
        && ctx
            .doc()
            .attr_or_empty(element, "bevelled")
            .eq_ignore_ascii_case("true")
    {
        return None;
    }
    if name == "mfrac"
        && ctx.doc().attr(element, "linethickness").is_some()
        && !matches!(
            ctx.doc().attr_or_empty(element, "linethickness"),
            "medium" | "1" | "1px"
        )
    {
        return None;
    }

    let mut parsed = Vec::with_capacity(children.len());
    for child in children {
        parsed.push(visit_native(
            ctx,
            child,
            depth + 1,
            apply_root_author_style,
            count,
            chars,
        )?);
    }
    Some(InlineMathNode {
        kind,
        text: String::new(),
        children: parsed,
        style,
        left_space_em: 0.0,
        right_space_em: 0.0,
        stretchy: false,
    })
}

fn native_style(
    doc: &Document,
    element: usize,
    css: Option<&FrbSpanStyle>,
) -> Option<InlineMathStyle> {
    let mut italic: Option<bool> = None;
    let mut weight: Option<i32> = None;
    let mut family: Option<&'static str> = None;
    let raw_variant = doc.attr_or_empty(element, "mathvariant");
    match raw_variant.to_lowercase().as_str() {
        "" | "normal" => {
            if raw_variant == "normal" {
                italic = Some(false);
                weight = Some(400);
            }
        }
        "italic" => italic = Some(true),
        "bold" => weight = Some(700),
        "bold-italic" => {
            weight = Some(700);
            italic = Some(true);
        }
        "monospace" => family = Some("monospace"),
        "sans-serif" => family = Some("sans-serif"),
        _ => return None,
    }

    let mut size = match parse_native_relative_size(doc.attr_or_empty(element, "mathsize")) {
        Some(v) => v,
        None if doc.attr(element, "mathsize").is_some() => return None,
        None => 1.0,
    };

    let color_raw = non_blank_or(doc.attr_or_empty(element, "mathcolor"), || {
        doc.attr_or_empty(element, "color")
    });
    let mut color = parse_native_color(color_raw);
    if (doc.attr(element, "mathcolor").is_some() || doc.attr(element, "color").is_some())
        && color.is_none()
    {
        return None;
    }

    if let Some(css) = css {
        if let Some(it) = css.italic {
            italic = Some(it);
        }
        if let Some(w) = css.weight {
            weight = Some(w);
        }
        if let Some(fam) = css.family.as_deref() {
            family = Some(match fam {
                "serif" => "serif",
                "sans" => "sans-serif",
                "mono" => "monospace",
                "cursive" => "cursive",
                _ => return None,
            });
        }
        match css.size {
            FrbTextUnit::Em(v) => size = v,
            FrbTextUnit::Unspecified => {}
            FrbTextUnit::Sp(_) => return None,
        }
        if css.color != COMPOSE_COLOR_UNSPECIFIED {
            color = Some(((css.color >> 32) as u32) as i32);
        }
        if css.baseline.is_some()
            || !css.spacing.is_unspecified()
            || css.background != COMPOSE_COLOR_UNSPECIFIED
        {
            return None;
        }
    }

    let decoration = css.and_then(|c| c.decoration).unwrap_or(0);
    Some(InlineMathStyle {
        italic,
        weight,
        font_family: family,
        font_size_em: size.clamp(0.25, 4.0),
        color_argb: color,
        underline: (decoration & TEXT_DECORATION_UNDERLINE) != 0,
        strike: (decoration & TEXT_DECORATION_LINE_THROUGH) != 0,
        font_features: css.and_then(|c| c.features.clone()),
        language: css.and_then(|c| c.locales.as_ref().and_then(|v| v.first()).cloned()),
    })
}

fn parse_native_relative_size(raw: &str) -> Option<f32> {
    let value = kotlin_trim(raw).to_lowercase();
    let parsed = match value.as_str() {
        "normal" => Some(1.0),
        "small" => Some(0.8),
        "big" => Some(1.25),
        _ if value.ends_with("em") => value[..value.len() - 2]
            .trim_matches(|c: char| c <= ' ')
            .parse::<f32>()
            .ok(),
        _ if value.ends_with('%') => value[..value.len() - 1]
            .trim_matches(|c: char| c <= ' ')
            .parse::<f32>()
            .ok()
            .map(|v| v / 100.0),
        _ => None,
    };
    parsed.filter(|&v| v.is_finite() && (0.25..=4.0).contains(&v))
}

fn parse_native_color(raw: &str) -> Option<i32> {
    let value = kotlin_trim(raw).to_lowercase();
    if !value.starts_with('#') {
        return match value.as_str() {
            "black" => Some(0xFF00_0000_u32 as i32),
            "white" => Some(0xFFFF_FFFF_u32 as i32),
            "red" => Some(0xFFFF_0000_u32 as i32),
            "blue" => Some(0xFF00_00FF_u32 as i32),
            _ => None,
        };
    }
    parse_hex_rgb_color(&value)
}

fn parse_hex_rgb_color(value: &str) -> Option<i32> {
    let hex = value.strip_prefix('#')?;
    if !hex.is_ascii() {
        return None;
    }
    match hex.len() {
        3 => {
            let mut expanded = [0u8; 6];
            let b = hex.as_bytes();
            expanded[0] = b[0];
            expanded[1] = b[0];
            expanded[2] = b[1];
            expanded[3] = b[1];
            expanded[4] = b[2];
            expanded[5] = b[2];
            let s = std::str::from_utf8(&expanded).ok()?;
            let rgb = u32::from_str_radix(s, 16).ok()?;
            Some((rgb | 0xFF00_0000) as i32)
        }
        6 => {
            let rgb = u32::from_str_radix(hex, 16).ok()?;
            Some((rgb | 0xFF00_0000) as i32)
        }
        _ => None,
    }
}

fn is_native_fence(s: &str) -> bool {
    matches!(s, "(" | ")" | "[" | "]" | "{" | "}" | "|" | "‖")
}

fn is_native_operator(s: &str) -> bool {
    matches!(
        s,
        "=" | "+" | "−" | "-" | "×" | "÷" | "<" | ">" | "≤" | "≥" | "≠" | "≈" | "∝" | "±"
    )
}

// --------------------------------------------------------------------------- MathMlFormatter

fn append_element<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
    apply_author_style: bool,
) {
    if depth >= ctx.max_depth() {
        append_direct_text(ctx.doc(), element, out);
        return;
    }

    let href = kotlin_trim(non_blank_or(
        ctx.doc().attr_or_empty(element, "href"),
        || ctx.doc().attr_or_empty(element, "xlink:href"),
    ))
    .to_owned();
    let internal = (!href.is_empty())
        .then(|| ctx.resolve_internal_link(&href))
        .flatten();
    let external =
        (!href.is_empty() && internal.is_none() && safe_external_href(&href)).then_some(href);

    if let Some(ref key) = internal {
        ctx.on_internal_link(key.clone());
        out.push_annotation(LINK_TAG, key.clone());
    } else if let Some(ref url) = external {
        out.push_annotation(EXTERNAL_LINK_TAG, url.clone());
    }

    let name = math_name(ctx.doc(), element);
    let default_style = if name == "mi" {
        default_mi_style(ctx.doc(), element)
    } else {
        None
    };
    let math_style = element_math_style(ctx.doc(), element);
    let author_style = if apply_author_style {
        ctx.resolve_author_style(element)
    } else {
        None
    };
    let style = merge_span_styles(merge_span_styles(default_style, math_style), author_style);
    let pushed_style = style.is_some();
    if let Some(s) = style {
        out.push_style(s);
    }

    append_element_content(ctx, element, &name, out, depth);

    if pushed_style {
        out.pop();
    }
    if internal.is_some() || external.is_some() {
        out.pop();
    }
}

fn append_element_content<C: MathContext>(
    ctx: &mut C,
    element: usize,
    name: &str,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    match name {
        "math" => append_math_root(ctx, element, out, depth),
        "semantics" => append_semantics(ctx, element, out, depth),
        "annotation" | "annotation-xml" => {}

        "mi" | "mn" => append_token(ctx.doc(), element, out),
        "mo" => append_operator(ctx.doc(), element, out),
        "mtext" => append_text_content(ctx, element, out, depth),
        "ms" => append_string_literal(ctx.doc(), element, out),
        "mglyph" => {
            let alt = non_blank_or(ctx.doc().attr_or_empty(element, "alt"), || {
                ctx.doc().attr_or_empty(element, "title")
            });
            if !kotlin_is_blank(alt) {
                out.text(alt);
            }
        }

        "mrow" | "mstyle" | "mpadded" | "mtd" | "mtr" | "mlabeledtr" => {
            append_children(ctx, element, out, depth);
        }

        "msup" => append_scripts(ctx, element, out, depth, false, true),
        "msub" => append_scripts(ctx, element, out, depth, true, false),
        "msubsup" => append_scripts(ctx, element, out, depth, true, true),
        "mmultiscripts" => append_multi_scripts(ctx, element, out, depth),
        "mprescripts" | "none" => {}

        "mfrac" => append_fraction(ctx, element, out, depth),
        "msqrt" => append_square_root(ctx, element, out, depth),
        "mroot" => append_indexed_root(ctx, element, out, depth),
        "mfenced" => append_fenced(ctx, element, out, depth),
        "menclose" => append_enclosure(ctx, element, out, depth),

        "mover" => append_limits(ctx, element, out, depth, false, true),
        "munder" => append_limits(ctx, element, out, depth, true, false),
        "munderover" => append_limits(ctx, element, out, depth, true, true),

        "mtable" => append_table(ctx, element, out, depth),
        "mspace" => append_space(ctx.doc(), element, out),
        "merror" => append_error(ctx, element, out, depth),
        "mphantom" | "maligngroup" | "malignmark" => {}
        "maction" => append_action(ctx, element, out, depth),

        _ => append_children(ctx, element, out, depth),
    }
}

fn append_math_root<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children: Vec<usize> = element_children(ctx.doc(), element)
        .into_iter()
        .filter(|&c| !is_annotation_tag(&math_name(ctx.doc(), c)))
        .collect();
    if children
        .iter()
        .any(|&c| has_renderable_presentation(ctx.doc(), c, depth + 1, ctx.max_depth()))
    {
        for c in children {
            append_element(ctx, c, out, depth + 1, true);
        }
        return;
    }
    if let Some(fb) = accessibility_fallback(ctx.doc(), element) {
        out.text(&fb);
    }
}

fn append_semantics<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children = element_children(ctx.doc(), element);
    if let Some(&direct_pres) = children.iter().find(|&&c| {
        !is_annotation_tag(&math_name(ctx.doc(), c))
            && has_renderable_presentation(ctx.doc(), c, depth + 1, ctx.max_depth())
    }) {
        append_element(ctx, direct_pres, out, depth + 1, true);
        return;
    }

    let embedded_pres = children
        .iter()
        .find(|&&c| {
            math_name(ctx.doc(), c) == "annotation-xml"
                && is_presentation_encoding(&ctx.doc().attr_or_empty(c, "encoding").to_lowercase())
        })
        .and_then(|&ann| {
            element_children(ctx.doc(), ann)
                .into_iter()
                .find(|&c| has_renderable_presentation(ctx.doc(), c, depth + 1, ctx.max_depth()))
        });
    if let Some(pres) = embedded_pres {
        append_element(ctx, pres, out, depth + 1, true);
        return;
    }

    if let Some(fb) = accessibility_fallback(ctx.doc(), element) {
        out.text(&fb);
    }
}

fn append_token(doc: &Document, element: usize, out: &mut HtmlInlineBuilder) {
    let direct = direct_text(doc, element);
    let text = if !kotlin_is_blank(&direct) {
        direct.as_str()
    } else {
        non_blank_or(doc.attr_or_empty(element, "alttext"), || {
            doc.attr_or_empty(element, "aria-label")
        })
    };
    if kotlin_is_blank(text) {
        return;
    }
    out.text(text);
}

fn default_mi_style(doc: &Document, element: usize) -> Option<FrbSpanStyle> {
    let direct = direct_text(doc, element);
    let text = kotlin_trim(&direct);
    if text.chars().count() != 1 {
        return None;
    }
    Some(FrbSpanStyle {
        italic: Some(true),
        ..FrbSpanStyle::default()
    })
}

fn append_operator(doc: &Document, element: usize, out: &mut HtmlInlineBuilder) {
    let direct = direct_text(doc, element);
    let token = kotlin_trim(&direct);
    if token.is_empty() {
        return;
    }
    let is_separator = doc
        .attr_or_empty(element, "separator")
        .eq_ignore_ascii_case("true")
        || matches!(token, "," | ";");
    let is_fence = doc
        .attr_or_empty(element, "fence")
        .eq_ignore_ascii_case("true")
        || is_fence_operator(token);
    if is_separator {
        out.text(&format!("{token} "));
    } else if is_fence {
        out.text(token);
    } else if is_padded_operator(token) {
        out.text(&format!(" {token} "));
    } else {
        out.text(token);
    }
}

fn append_text_content<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let mut child = ctx.doc().nodes[element].first_child;
    while let Some(c) = child {
        let next = ctx.doc().nodes[c].next_sibling;
        match &ctx.doc().nodes[c].kind {
            Kind::Text(s) | Kind::Cdata(s) => out.text(s),
            Kind::Element { .. } => append_element(ctx, c, out, depth + 1, true),
            _ => {}
        }
        child = next;
    }
}

fn append_string_literal(doc: &Document, element: usize, out: &mut HtmlInlineBuilder) {
    let open = non_blank_or(doc.attr_or_empty(element, "lquote"), || "\"");
    let close = non_blank_or(doc.attr_or_empty(element, "rquote"), || "\"");
    out.text(open);
    out.text(&direct_text(doc, element));
    out.text(close);
}

fn append_scripts<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
    has_sub: bool,
    has_sup: bool,
) {
    let children = element_children(ctx.doc(), element);
    if let Some(&base) = children.first() {
        append_element(ctx, base, out, depth + 1, true);
    } else {
        out.text(MISSING_OPERAND);
    }
    let mut index = 1usize;
    if has_sub {
        if let Some(&sub) = children.get(index) {
            append_script(ctx, sub, out, depth, false);
        }
        index += 1;
    }
    if has_sup {
        if let Some(&sup) = children.get(index) {
            append_script(ctx, sup, out, depth, true);
        }
    }
}

fn append_multi_scripts<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children = element_children(ctx.doc(), element);
    let base = children.first().copied();
    let marker = children
        .iter()
        .position(|&c| math_name(ctx.doc(), c) == "mprescripts");
    let (post, pre): (&[usize], &[usize]) = match marker {
        None => {
            if children.len() > 1 {
                (&children[1..], &[])
            } else {
                (&[], &[])
            }
        }
        Some(m) => {
            let post_slice = if m > 1 { &children[1..m] } else { &[] };
            let pre_slice = if m + 1 < children.len() {
                &children[m + 1..]
            } else {
                &[]
            };
            (post_slice, pre_slice)
        }
    };
    append_script_pairs(ctx, pre, out, depth);
    if let Some(b) = base {
        append_element(ctx, b, out, depth + 1, true);
    } else {
        out.text(MISSING_OPERAND);
    }
    append_script_pairs(ctx, post, out, depth);
}

fn append_script_pairs<C: MathContext>(
    ctx: &mut C,
    children: &[usize],
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let mut i = 0usize;
    while i < children.len() {
        if let Some(&sub) = children.get(i) {
            if math_name(ctx.doc(), sub) != "none" {
                append_script(ctx, sub, out, depth, false);
            }
        }
        if let Some(&sup) = children.get(i + 1) {
            if math_name(ctx.doc(), sup) != "none" {
                append_script(ctx, sup, out, depth, true);
            }
        }
        i += 2;
    }
}

fn append_script<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
    superscript: bool,
) {
    out.push_style(if superscript {
        superscript_style()
    } else {
        subscript_style()
    });
    append_grouped(ctx, element, out, depth + 1);
    out.pop();
}

fn append_fraction<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children = element_children(ctx.doc(), element);
    let numerator = children.first().copied();
    let denominator = children.get(1).copied();

    out.push_style(numerator_style());
    if let Some(num) = numerator {
        append_grouped(ctx, num, out, depth + 1);
    } else {
        out.text(MISSING_OPERAND);
    }
    out.pop();

    out.text(FRACTION_SLASH);

    out.push_style(denominator_style());
    if let Some(den) = denominator {
        append_grouped(ctx, den, out, depth + 1);
    } else {
        out.text(MISSING_OPERAND);
    }
    out.pop();
}

fn append_square_root<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    out.text(SQUARE_ROOT);
    append_grouped_children(ctx, element, out, depth, true);
}

fn append_indexed_root<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children = element_children(ctx.doc(), element);
    if let Some(&index) = children.get(1) {
        out.push_style(root_index_style());
        append_grouped(ctx, index, out, depth + 1);
        out.pop();
    }
    out.text(SQUARE_ROOT);
    if let Some(&radicand) = children.first() {
        append_grouped(ctx, radicand, out, depth + 1);
    } else {
        out.text(MISSING_OPERAND);
    }
}

fn append_fenced<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let open_attr = ctx.doc().attr_or_empty(element, "open");
    let open = if open_attr.is_empty() { "(" } else { open_attr }.to_owned();
    let close_attr = ctx.doc().attr_or_empty(element, "close");
    let close = if close_attr.is_empty() {
        ")"
    } else {
        close_attr
    }
    .to_owned();
    let sep_raw = non_blank_or(ctx.doc().attr_or_empty(element, "separators"), || ",");
    let separators: Vec<char> = sep_raw
        .chars()
        .filter(|&c| !kotlin_is_whitespace(c))
        .collect();
    let separators = if separators.is_empty() {
        vec![',']
    } else {
        separators
    };

    out.text(&open);
    let children = element_children(ctx.doc(), element);
    for (index, child) in children.into_iter().enumerate() {
        if index > 0 {
            let sep = separators[(index - 1).min(separators.len() - 1)];
            out.text(&format!("{sep} "));
        }
        append_element(ctx, child, out, depth + 1, true);
    }
    out.text(&close);
}

fn append_enclosure<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let raw = ctx.doc().attr_or_empty(element, "notation").to_lowercase();
    let mut has_radical = false;
    let mut has_box = false;
    for token in raw.split(is_java_regex_ws) {
        if token == "radical" {
            has_radical = true;
        } else if matches!(
            token,
            "box" | "roundedbox" | "circle" | "longdiv" | "actuarial"
        ) {
            has_box = true;
        }
    }
    if has_radical {
        out.text(SQUARE_ROOT);
        append_grouped_children(ctx, element, out, depth, true);
    } else if has_box {
        out.text("[");
        append_children(ctx, element, out, depth);
        out.text("]");
    } else {
        append_children(ctx, element, out, depth);
    }
}

fn append_limits<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
    has_under: bool,
    has_over: bool,
) {
    let children = element_children(ctx.doc(), element);
    if let Some(&base) = children.first() {
        append_element(ctx, base, out, depth + 1, true);
    } else {
        out.text(MISSING_OPERAND);
    }
    let mut index = 1usize;
    if has_under {
        if let Some(&under) = children.get(index) {
            append_script(ctx, under, out, depth, false);
        }
        index += 1;
    }
    if has_over {
        if let Some(&over) = children.get(index) {
            append_script(ctx, over, out, depth, true);
        }
    }
}

fn append_table<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let rows: Vec<usize> = element_children(ctx.doc(), element)
        .into_iter()
        .filter(|&c| matches!(math_name(ctx.doc(), c).as_str(), "mtr" | "mlabeledtr"))
        .collect();
    out.text("[");
    for (row_index, row) in rows.into_iter().enumerate() {
        if row_index > 0 {
            out.text("; ");
        }
        let cells: Vec<usize> = element_children(ctx.doc(), row)
            .into_iter()
            .filter(|&c| math_name(ctx.doc(), c) == "mtd")
            .collect();
        for (cell_index, cell) in cells.into_iter().enumerate() {
            if cell_index > 0 {
                out.text(", ");
            }
            append_children(ctx, cell, out, depth + 2);
        }
    }
    out.text("]");
}

fn append_space(doc: &Document, element: usize, out: &mut HtmlInlineBuilder) {
    if doc
        .attr_or_empty(element, "linebreak")
        .eq_ignore_ascii_case("newline")
    {
        out.line_break();
        return;
    }
    let width = kotlin_trim(doc.attr_or_empty(element, "width")).to_lowercase();
    if width.starts_with('0') || width.starts_with('-') {
        return;
    }
    out.text(" ");
}

fn append_error<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    out.push_style(FrbSpanStyle {
        color: color_argb_to_compose_ulong(ERROR_COLOR_ARGB),
        ..FrbSpanStyle::default()
    });
    out.text("⟦");
    if has_renderable_presentation(ctx.doc(), element, depth + 1, ctx.max_depth()) {
        append_children(ctx, element, out, depth);
    } else {
        let fb = accessibility_fallback(ctx.doc(), element);
        out.text(fb.as_deref().unwrap_or("Math error"));
    }
    out.text("⟧");
    out.pop();
}

fn append_action<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let children: Vec<usize> = element_children(ctx.doc(), element)
        .into_iter()
        .filter(|&c| !is_annotation_tag(&math_name(ctx.doc(), c)))
        .collect();
    if children.is_empty() {
        return;
    }
    let selected = ctx
        .doc()
        .attr_or_empty(element, "selection")
        .parse::<i32>()
        .ok()
        .map_or(0, |v| v - 1);
    let idx = selected.clamp(0, (children.len() - 1) as i32) as usize;
    append_element(ctx, children[idx], out, depth + 1, true);
}

fn append_grouped_children<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
    force_for_many: bool,
) {
    let children: Vec<usize> = element_children(ctx.doc(), element)
        .into_iter()
        .filter(|&c| !is_annotation_tag(&math_name(ctx.doc(), c)))
        .collect();
    let grouped = force_for_many
        && (children.len() > 1 || children.iter().any(|&c| needs_grouping(ctx.doc(), c)));
    if grouped {
        out.text("(");
    }
    for c in children {
        append_element(ctx, c, out, depth + 1, true);
    }
    if grouped {
        out.text(")");
    }
}

fn append_grouped<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let grouped = needs_grouping(ctx.doc(), element);
    if grouped {
        out.text("(");
    }
    append_element(ctx, element, out, depth, true);
    if grouped {
        out.text(")");
    }
}

fn needs_grouping(doc: &Document, element: usize) -> bool {
    let name = math_name(doc, element);
    if matches!(name.as_str(), "mfrac" | "mtable") {
        return true;
    }
    let meaningful: Vec<usize> = element_children(doc, element)
        .into_iter()
        .filter(|&c| !is_annotation_tag(&math_name(doc, c)))
        .collect();
    if meaningful.len() <= 1 {
        return false;
    }
    meaningful.len() > 2
        || meaningful.iter().any(|&c| {
            math_name(doc, c) == "mo" && {
                let raw = direct_text(doc, c);
                is_padded_operator(kotlin_trim(&raw))
            }
        })
}

fn append_children<C: MathContext>(
    ctx: &mut C,
    element: usize,
    out: &mut HtmlInlineBuilder,
    depth: usize,
) {
    let mut child = ctx.doc().nodes[element].first_child;
    while let Some(c) = child {
        let next = ctx.doc().nodes[c].next_sibling;
        match &ctx.doc().nodes[c].kind {
            Kind::Text(s) | Kind::Cdata(s) => {
                if (ctx.doc().node_meta(c).text_flags & TEXT_HTML_BLANK) == 0 {
                    out.text(s);
                }
            }
            Kind::Element { .. } => append_element(ctx, c, out, depth + 1, true),
            _ => {}
        }
        child = next;
    }
}

fn append_direct_text(doc: &Document, element: usize, out: &mut HtmlInlineBuilder) {
    let mut child = doc.nodes[element].first_child;
    while let Some(c) = child {
        if let Kind::Text(s) | Kind::Cdata(s) = &doc.nodes[c].kind {
            if (doc.node_meta(c).text_flags & TEXT_HTML_BLANK) == 0 {
                out.text(s);
            }
        }
        child = doc.nodes[c].next_sibling;
    }
}

fn direct_text(doc: &Document, element: usize) -> String {
    let mut out = String::new();
    let mut child = doc.nodes[element].first_child;
    while let Some(c) = child {
        if let Kind::Text(s) | Kind::Cdata(s) = &doc.nodes[c].kind {
            out.push_str(s);
        }
        child = doc.nodes[c].next_sibling;
    }
    out
}

fn has_renderable_presentation(
    doc: &Document,
    element: usize,
    depth: usize,
    max_depth: usize,
) -> bool {
    if depth >= max_depth {
        return !kotlin_is_blank(&direct_text(doc, element));
    }
    match math_name(doc, element).as_str() {
        "annotation" | "annotation-xml" | "mphantom" | "maligngroup" | "malignmark"
        | "mprescripts" | "none" => false,
        "mi" | "mn" | "mo" | "mtext" | "ms" => !kotlin_is_blank(&direct_text(doc, element)),
        "mglyph" => {
            !kotlin_is_blank(doc.attr_or_empty(element, "alt"))
                || !kotlin_is_blank(doc.attr_or_empty(element, "title"))
        }
        "mspace" => false,
        "semantics" => {
            element_children(doc, element).into_iter().any(|c| {
                !is_annotation_tag(&math_name(doc, c))
                    && has_renderable_presentation(doc, c, depth + 1, max_depth)
            }) || accessibility_fallback(doc, element).is_some()
        }
        _ => {
            !kotlin_is_blank(&direct_text(doc, element))
                || element_children(doc, element)
                    .into_iter()
                    .any(|c| has_renderable_presentation(doc, c, depth + 1, max_depth))
                || accessibility_label(doc, element).is_some()
        }
    }
}

fn accessibility_fallback(doc: &Document, element: usize) -> Option<String> {
    if let Some(label) = accessibility_label(doc, element) {
        return Some(label);
    }
    // Mirrors Jsoup `element.select("annotation, annotation-xml")` in pre-order.
    let mut annotations = Vec::new();
    let mut stack = vec![element];
    while let Some(curr) = stack.pop() {
        let name = doc.normal_name(curr);
        if name == "annotation" || name == "annotation-xml" {
            annotations.push(curr);
        }
        let start = stack.len();
        let mut child = doc.first_element_child(curr);
        while let Some(c) = child {
            stack.push(c);
            child = doc.next_element_sibling(c);
        }
        stack[start..].reverse();
    }
    let preferred = annotations
        .iter()
        .copied()
        .find(|&ann| {
            let enc = kotlin_trim(doc.attr_or_empty(ann, "encoding")).to_lowercase();
            is_accessible_encoding(&enc) && !kotlin_is_blank(&doc.jsoup_text(ann))
        })
        .or_else(|| {
            annotations
                .iter()
                .copied()
                .find(|&ann| !kotlin_is_blank(&doc.jsoup_text(ann)))
        })?;
    let text = doc.jsoup_text(preferred);
    let trimmed = kotlin_trim(&text);
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

fn accessibility_label(doc: &Document, element: usize) -> Option<String> {
    let raw = non_blank_or(doc.attr_or_empty(element, "aria-label"), || {
        doc.attr_or_empty(element, "alttext")
    });
    let trimmed = kotlin_trim(raw);
    (!trimmed.is_empty()).then(|| trimmed.to_owned())
}

fn element_math_style(doc: &Document, element: usize) -> Option<FrbSpanStyle> {
    let mut style = FrbSpanStyle::default();
    let mut any = false;
    let variant = kotlin_trim(doc.attr_or_empty(element, "mathvariant"))
        .to_lowercase()
        .replace('_', "-");
    match variant.as_str() {
        "normal" => {
            style.italic = Some(false);
            style.weight = Some(400);
            any = true;
        }
        "bold" => {
            style.weight = Some(700);
            any = true;
        }
        "italic" => {
            style.italic = Some(true);
            any = true;
        }
        "bold-italic" => {
            style.weight = Some(700);
            style.italic = Some(true);
            any = true;
        }
        "monospace" => {
            style.family = Some("mono".to_owned());
            any = true;
        }
        "sans-serif" => {
            style.family = Some("sans".to_owned());
            any = true;
        }
        "bold-sans-serif" | "sans-serif-bold" => {
            style.family = Some("sans".to_owned());
            style.weight = Some(700);
            any = true;
        }
        "sans-serif-italic" => {
            style.family = Some("sans".to_owned());
            style.italic = Some(true);
            any = true;
        }
        "sans-serif-bold-italic" => {
            style.family = Some("sans".to_owned());
            style.weight = Some(700);
            style.italic = Some(true);
            any = true;
        }
        _ => {}
    }

    if let Some(scale) = parse_formatter_relative_size(doc.attr_or_empty(element, "mathsize")) {
        style.size = FrbTextUnit::Em(scale);
        any = true;
    }
    let color_raw = non_blank_or(doc.attr_or_empty(element, "mathcolor"), || {
        doc.attr_or_empty(element, "color")
    });
    if let Some(argb) = parse_formatter_color(color_raw) {
        style.color = color_argb_to_compose_ulong(argb);
        any = true;
    }
    any.then_some(style)
}

fn parse_formatter_relative_size(raw: &str) -> Option<f32> {
    let value = kotlin_trim(raw).to_lowercase();
    if value.is_empty() || value == "normal" {
        return None;
    }
    let parse_trimmed = |s: &str| s.trim_matches(|c: char| c <= ' ').parse::<f32>().ok();
    let scale = match value.as_str() {
        "small" => Some(0.8),
        "big" => Some(1.25),
        _ if value.ends_with('%') => parse_trimmed(&value[..value.len() - 1]).map(|v| v / 100.0),
        _ if value.ends_with("em") => parse_trimmed(&value[..value.len() - 2]),
        _ if value.ends_with("px") => parse_trimmed(&value[..value.len() - 2]).map(|v| v / 16.0),
        _ if value.ends_with("pt") => parse_trimmed(&value[..value.len() - 2]).map(|v| v / 12.0),
        _ => None,
    };
    scale.filter(|v| v.is_finite()).map(|v| v.clamp(0.5, 2.5))
}

/// Named colors match Jetpack Compose `Color.*` constants used in `MathMlFormatter.kt`.
fn parse_formatter_color(raw: &str) -> Option<i32> {
    let value = kotlin_trim(raw).to_lowercase();
    if value.is_empty() {
        return None;
    }
    match value.as_str() {
        "black" => return Some(0xFF00_0000_u32 as i32),
        "white" => return Some(0xFFFF_FFFF_u32 as i32),
        "red" => return Some(0xFFFF_0000_u32 as i32),
        "blue" => return Some(0xFF00_00FF_u32 as i32),
        "green" => return Some(0xFF00_FF00_u32 as i32),
        "gray" | "grey" => return Some(0xFF88_8888_u32 as i32),
        "yellow" => return Some(0xFFFF_FF00_u32 as i32),
        "cyan" => return Some(0xFF00_FFFF_u32 as i32),
        "magenta" => return Some(0xFFFF_00FF_u32 as i32),
        _ => {}
    }
    parse_hex_rgb_color(&value)
}

// --------------------------------------------------------------------------- Helpers & Tables

fn math_name(doc: &Document, node_id: usize) -> String {
    let normal = doc.normal_name(node_id);
    normal
        .rsplit(':')
        .next()
        .unwrap_or(normal.as_ref())
        .to_lowercase()
}

fn element_children(doc: &Document, node_id: usize) -> Vec<usize> {
    let mut out = Vec::new();
    let mut child = doc.first_element_child(node_id);
    while let Some(c) = child {
        out.push(c);
        child = doc.next_element_sibling(c);
    }
    out
}

fn is_annotation_tag(name: &str) -> bool {
    matches!(name, "annotation" | "annotation-xml")
}

fn is_presentation_encoding(enc: &str) -> bool {
    matches!(
        enc,
        "application/mathml-presentation+xml" | "mathml-presentation"
    )
}

fn is_accessible_encoding(enc: &str) -> bool {
    matches!(
        enc,
        "text/plain" | "application/x-tex" | "application/x-latex" | "application/tex"
    )
}

fn is_fence_operator(token: &str) -> bool {
    matches!(
        token,
        "(" | ")" | "[" | "]" | "{" | "}" | "⟨" | "⟩" | "⌈" | "⌉" | "⌊" | "⌋" | "|" | "‖"
    )
}

fn is_padded_operator(token: &str) -> bool {
    matches!(
        token,
        "=" | "≠"
            | "<"
            | ">"
            | "≤"
            | "≥"
            | "≈"
            | "≃"
            | "≅"
            | "≡"
            | "∼"
            | "∝"
            | "+"
            | "-"
            | "−"
            | "±"
            | "∓"
            | "×"
            | "÷"
            | "·"
            | "∗"
            | "/"
            | "∈"
            | "∉"
            | "∋"
            | "⊂"
            | "⊃"
            | "⊆"
            | "⊇"
            | "∩"
            | "∪"
            | "∧"
            | "∨"
            | "⊕"
            | "⊗"
            | "→"
            | "←"
            | "↔"
            | "⇒"
            | "⇐"
            | "⇔"
            | ":="
    ) || token
        .chars()
        .any(|c| matches!(c, '=' | '<' | '>' | '+' | '−' | '±' | '×' | '÷'))
}

fn superscript_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.72),
        baseline: Some(0.5),
        ..FrbSpanStyle::default()
    }
}

fn subscript_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.72),
        baseline: Some(-0.5),
        ..FrbSpanStyle::default()
    }
}

fn root_index_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.62),
        baseline: Some(0.55),
        ..FrbSpanStyle::default()
    }
}

fn numerator_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.88),
        baseline: Some(0.16),
        ..FrbSpanStyle::default()
    }
}

fn denominator_style() -> FrbSpanStyle {
    FrbSpanStyle {
        size: FrbTextUnit::Em(0.88),
        baseline: Some(-0.12),
        ..FrbSpanStyle::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chapter_markup;

    struct DummyMathCtx<'a> {
        doc: &'a Document,
        max_depth: usize,
        links: Vec<String>,
    }

    impl MathContext for DummyMathCtx<'_> {
        fn doc(&self) -> &Document {
            self.doc
        }
        fn max_depth(&self) -> usize {
            self.max_depth
        }
        fn resolve_internal_link(&mut self, href: &str) -> Option<String> {
            href.starts_with('#').then(|| format!("ch.xhtml{href}"))
        }
        fn on_internal_link(&mut self, key: String) {
            self.links.push(key);
        }
        fn resolve_author_style(&mut self, _node_id: usize) -> Option<FrbSpanStyle> {
            None
        }
    }

    fn format_math(xml: &str) -> (crate::frb_model::FrbAnnotatedString, Vec<String>) {
        let wrapped = format!("<body xmlns:m=\"http://www.w3.org/1998/Math/MathML\">{xml}</body>");
        let doc = chapter_markup::parse(&wrapped, false).unwrap();
        let math_node = doc
            .nodes
            .iter()
            .position(|n| matches!(&n.kind, Kind::Element { name, .. } if name.ends_with("math")))
            .expect("math node");
        let mut ctx = DummyMathCtx {
            doc: &doc,
            max_depth: 256,
            links: Vec::new(),
        };
        let mut out = HtmlInlineBuilder::new();
        append_math(&mut ctx, math_node, &mut out, true);
        (out.build(), ctx.links)
    }

    #[test]
    fn fallback_handles_fences_roots_limits_tables_strings_actions_errors_and_phantoms() {
        let (text, _) = format_math(
            r#"<math>
                <msubsup><mo>∑</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></msubsup>
                <mo>+</mo><mroot><mi>x</mi><mn>3</mn></mroot>
                <mo>+</mo><mfenced open="{" close="}" separators=";"><mi>a</mi><mi>b</mi></mfenced>
                <mo>=</mo>
                <mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr><mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable>
            </math>"#,
        );
        assert_eq!(text.text, "∑(i = 1)n + 3√x + {a; b} = [1, 2; 3, 4]");

        let (rare, _) = format_math(
            r#"<math>
                <ms lquote="«" rquote="»">текст</ms><mo>;</mo>
                <maction selection="2"><mi>wrong</mi><mi>chosen</mi></maction><mo>;</mo>
                <mover><mi>x</mi><mo>^</mo></mover><mo>;</mo>
                <munder><mo>lim</mo><mrow><mi>x</mi><mo>→</mo><mn>0</mn></mrow></munder><mo>;</mo>
                <merror><mtext>bad input</mtext></merror><mo>;</mo>
                <mphantom><mtext>secret</mtext></mphantom>
                <mi href="https://example.com/math">link</mi>
            </math>"#,
        );
        assert_eq!(
            rare.text,
            "«текст»; chosen; x^; lim(x → 0); ⟦bad input⟧; link"
        );
        assert!(rare.ranges.iter().any(|r| {
            r.tag == EXTERNAL_LINK_TAG
                && r.annotation == FrbTextAnnotation::String("https://example.com/math".to_owned())
        }));
    }

    #[test]
    fn semantics_prefers_presentation_and_falls_back_to_accessible_annotation() {
        let (pres, _) = format_math(
            r#"<math><semantics><msup><mi>x</mi><mn>2</mn></msup><annotation encoding="application/x-tex">x^2</annotation></semantics></math>"#,
        );
        assert_eq!(pres.text, "x2");
        assert!(pres.ranges.iter().any(|r| r.tag == INLINE_MATH_TAG));

        let (ann, _) = format_math(
            r#"<math><semantics><mrow/><annotation encoding="text/plain">empty set</annotation></semantics></math>"#,
        );
        assert_eq!(ann.text, "empty set");
        assert!(!ann.ranges.iter().any(|r| r.tag == INLINE_MATH_TAG));
    }

    #[test]
    fn deep_native_tree_rejected_to_fallback_and_compose_named_colors_match() {
        let mut deep = String::from("<math>");
        for _ in 0..33 {
            deep.push_str("<mrow>");
        }
        deep.push_str("<mn>1</mn>");
        for _ in 0..33 {
            deep.push_str("</mrow>");
        }
        deep.push_str("</math>");
        let (text, _) = format_math(&deep);
        assert_eq!(text.text, "1");
        assert!(!text.ranges.iter().any(|r| r.tag == INLINE_MATH_TAG));

        // Compose Color.Green is 0xFF00FF00 and Color.Gray is 0xFF888888
        assert_eq!(parse_formatter_color("green"), Some(0xFF00_FF00_u32 as i32));
        assert_eq!(parse_formatter_color("gray"), Some(0xFF88_8888_u32 as i32));
        assert_eq!(parse_formatter_color("#f0a"), Some(0xFFFF_00AA_u32 as i32));
    }
}
