use crate::css_color::{hex_digit, parse_color};
use crate::css_lengths::{font_size, leading_number, length_em};
use crate::css_match::{cascade_inline, DeclarationRef};
use std::borrow::Cow;
use std::collections::HashMap;

const MAX_DECLARATIONS_PER_BLOCK: usize = 512;

// The expansion codes are carried in the chapter CSS bridge.
const IDENTITY: i32 = 0;
const TOP: i32 = 1;
const RIGHT: i32 = 2;
const BOTTOM: i32 = 3;
const LEFT: i32 = 4;
const INLINE_START: i32 = 5;
const INLINE_END: i32 = 6;
const FONT_FIRST: i32 = 7;
const FONT_LAST: i32 = 18;
const BORDER_WIDTH: i32 = 19;
const BORDER_STYLE: i32 = 20;
const BORDER_COLOR: i32 = 21;
const BACKGROUND: i32 = 22;

const PHYSICAL_SIDES: [(&str, i32); 4] = [
    ("top", TOP),
    ("right", RIGHT),
    ("bottom", BOTTOM),
    ("left", LEFT),
];
const INLINE_SIDES: [(&str, i32); 2] = [("inline-start", INLINE_START), ("inline-end", INLINE_END)];
const BORDER_PARTS: [(&str, i32); 3] = [
    ("width", BORDER_WIDTH),
    ("style", BORDER_STYLE),
    ("color", BORDER_COLOR),
];
const FONT_PROPERTIES: [&str; 12] = [
    "font-style",
    "font-weight",
    "font-size",
    "line-height",
    "font-family",
    "font-variant",
    "font-stretch",
    "font-feature-settings",
    "font-kerning",
    "font-optical-sizing",
    "font-variation-settings",
    "font-variant-ligatures",
];

pub(crate) struct RawDeclaration {
    pub(crate) property: String,
    pub(crate) value: String,
    pub(crate) important: bool,
}

pub(crate) struct Declaration<'a> {
    pub(crate) property: String,
    pub(crate) value: Cow<'a, str>,
    pub(crate) important: bool,
    pub(crate) expansion: i32,
}

/// Prepare declarations for the cascade, admitting each shorthand as a unit.
/// Even malformed or rejected rows count against the caller's sheet budget.
pub(crate) fn compile(source: &[RawDeclaration]) -> (usize, Vec<Declaration<'_>>) {
    let mut declarations = Vec::new();
    let mut properties = HashMap::new();
    let mut consumed = 0;
    for raw in source.iter().take(MAX_DECLARATIONS_PER_BLOCK) {
        consumed += 1;
        let candidates = prepare(raw);
        // Each shorthand lists every longhand at most once.
        let new_properties = candidates
            .iter()
            .filter(|candidate| !properties.contains_key(&candidate.property))
            .count();
        if properties.len() + new_properties > MAX_DECLARATIONS_PER_BLOCK {
            break;
        }
        for candidate in candidates {
            let id = properties.len() as i32;
            properties.entry(candidate.property.clone()).or_insert(id);
            declarations.push(candidate);
        }
    }

    if declarations.len() == properties.len() {
        return (consumed, declarations);
    }
    let references: Vec<_> = declarations
        .iter()
        .enumerate()
        .map(|(index, declaration)| DeclarationRef {
            property: properties[&declaration.property],
            id: -1 - index as i32,
            important: declaration.important,
        })
        .collect();
    let mut declarations: Vec<_> = declarations.into_iter().map(Some).collect();
    let winners = cascade_inline(&references)
        .into_iter()
        .filter_map(|id| declarations[(-1 - id) as usize].take())
        .collect();
    (consumed, winners)
}

fn prepare(raw: &RawDeclaration) -> Vec<Declaration<'_>> {
    let mut property = trim(&raw.property).to_lowercase();
    let value = trim(&raw.value);
    if property.is_empty() || value.is_empty() {
        return Vec::new();
    }
    if property == "font-width" {
        property = "font-stretch".into();
    }

    let deferred = |property, expansion| Declaration {
        property,
        value: Cow::Borrowed(value),
        important: raw.important,
        expansion,
    };
    let sides = |names: &[(&str, i32)], prefix: &str, suffix: &str| {
        if !has_component_count(value, names.len()) {
            return Vec::new();
        }
        names
            .iter()
            .map(|&(name, expansion)| deferred(format!("{prefix}-{name}{suffix}"), expansion))
            .collect()
    };

    match property.as_str() {
        "margin" | "padding" => sides(&PHYSICAL_SIDES, &property, ""),
        "margin-inline" | "padding-inline" => {
            let prefix = property.strip_suffix("-inline").expect("inline shorthand");
            sides(&INLINE_SIDES, prefix, "")
        }
        "border-width" => sides(&PHYSICAL_SIDES, "border", "-width"),
        "border-style" => sides(&PHYSICAL_SIDES, "border", "-style"),
        "border-color" => sides(&PHYSICAL_SIDES, "border", "-color"),
        "border" | "border-top" | "border-right" | "border-bottom" | "border-left" => {
            if !contains_var(value) && border_values(&value.to_lowercase()).is_none() {
                return Vec::new();
            }
            PHYSICAL_SIDES
                .iter()
                .filter(|&&(side, _)| {
                    property == "border" || property.strip_prefix("border-") == Some(side)
                })
                .flat_map(|&(side, _)| {
                    BORDER_PARTS.iter().map(move |&(part, expansion)| {
                        deferred(format!("border-{side}-{part}"), expansion)
                    })
                })
                .collect()
        }
        "background" => {
            if !contains_var(value) && background_color(&value.to_lowercase()).is_none() {
                return Vec::new();
            }
            vec![deferred("background-color".into(), BACKGROUND)]
        }
        "font" => {
            if let Some(values) = font_values(value) {
                FONT_PROPERTIES
                    .into_iter()
                    .zip(values.into_parts())
                    .map(|(property, value)| Declaration {
                        property: property.into(),
                        value,
                        important: raw.important,
                        expansion: IDENTITY,
                    })
                    .collect()
            } else if contains_var(value) {
                FONT_PROPERTIES
                    .into_iter()
                    .enumerate()
                    .map(|(index, property)| deferred(property.into(), FONT_FIRST + index as i32))
                    .collect()
            } else {
                Vec::new()
            }
        }
        _ => vec![deferred(property, IDENTITY)],
    }
}

/// Extract one longhand from a shorthand after variable substitution and
/// normalization. Retain the source value in the cascade until this point.
pub(crate) fn expand(expansion: i32, value: &str) -> Option<String> {
    let part = match expansion {
        IDENTITY => value,
        TOP..=INLINE_END => {
            let mut values = tokens(value, false);
            let top = values.next()?;
            let right = values.next().unwrap_or(top);
            let bottom = values.next().unwrap_or(top);
            let left = values.next().unwrap_or(right);
            if values.next().is_some() {
                return None;
            }
            match expansion {
                TOP | INLINE_START => top,
                RIGHT | INLINE_END => right,
                BOTTOM => bottom,
                LEFT => left,
                _ => unreachable!(),
            }
        }
        FONT_FIRST..=FONT_LAST => {
            return font_values(value)?
                .into_parts()
                .into_iter()
                .nth((expansion - FONT_FIRST) as usize)
                .map(Cow::into_owned);
        }
        BORDER_WIDTH..=BORDER_COLOR => border_values(value)?[(expansion - BORDER_WIDTH) as usize],
        BACKGROUND => background_color(value)?,
        _ => return None,
    };
    Some(part.into())
}

fn has_component_count(value: &str, max: usize) -> bool {
    let count = tokens(value, false).take(max + 1).count();
    (1..=max).contains(&count)
}

fn css_wide_keyword(value: &str) -> bool {
    matches!(
        value,
        "inherit" | "initial" | "unset" | "revert" | "revert-layer"
    )
}

fn border_values(value: &str) -> Option<[&str; 3]> {
    let value = trim(value);
    if css_wide_keyword(value) {
        return Some([value; 3]);
    }
    let mut width = None;
    let mut style = None;
    let mut color = None;
    for (index, token) in tokens(value, false).enumerate() {
        if index == 3 {
            return None;
        }
        if style.is_none()
            && matches!(
                token,
                "none" | "hidden" | "solid" | "dashed" | "dotted" | "double"
            )
        {
            style = Some(token);
        } else if width.is_none()
            && (matches!(token, "thin" | "medium" | "thick")
                || length_em(token, 0.0, 1.0, false).is_some())
        {
            width = Some(token);
        } else if color.is_none() && is_color(token) {
            color = Some(token);
        } else {
            return None;
        }
    }
    if width.is_none() && style.is_none() && color.is_none() {
        return None;
    }
    Some([
        width.unwrap_or("medium"),
        style.unwrap_or("none"),
        color.unwrap_or("currentcolor"),
    ])
}

fn background_color(value: &str) -> Option<&str> {
    let value = trim(value);
    if value == "none" {
        return Some("transparent");
    }
    if css_wide_keyword(value) || is_color(value) {
        return Some(value);
    }
    let mut colors = tokens(value, false).filter(|token| is_color(token));
    let color = colors.next()?;
    colors.next().is_none().then_some(color)
}

fn is_color(value: &str) -> bool {
    value == "currentcolor" || parse_color(value).is_some()
}

struct FontValues<'a> {
    style: &'a str,
    weight: &'a str,
    size: &'a str,
    line_height: &'a str,
    family: Cow<'a, str>,
    variant: &'a str,
    stretch: &'a str,
    reset: &'a str,
}

impl<'a> FontValues<'a> {
    fn defaults(family: &'a str) -> Self {
        Self {
            style: "normal",
            weight: "normal",
            size: "medium",
            line_height: "normal",
            family: Cow::Borrowed(family),
            variant: "normal",
            stretch: "normal",
            reset: "initial",
        }
    }

    fn into_parts(self) -> [Cow<'a, str>; 12] {
        [
            self.style.into(),
            self.weight.into(),
            self.size.into(),
            self.line_height.into(),
            self.family,
            self.variant.into(),
            self.stretch.into(),
            self.reset.into(),
            self.reset.into(),
            self.reset.into(),
            self.reset.into(),
            self.reset.into(),
        ]
    }
}

fn font_values(value: &str) -> Option<FontValues<'_>> {
    let value = trim(value);
    match value.to_lowercase().as_str() {
        "inherit" | "unset" => {
            return Some(FontValues {
                style: "inherit",
                weight: "inherit",
                size: "inherit",
                line_height: "inherit",
                family: Cow::Borrowed("inherit"),
                variant: "inherit",
                stretch: "inherit",
                reset: "inherit",
            });
        }
        "initial" | "revert" | "revert-layer" => return Some(FontValues::defaults("initial")),
        "caption" | "icon" | "menu" | "message-box" | "small-caption" | "status-bar" => {
            return Some(FontValues::defaults("sans-serif"));
        }
        _ => {}
    }

    let mut parts = tokens(value, true).peekable();
    let mut font = FontValues::defaults("");
    loop {
        let token = parts.next()?;
        let lower = token.to_lowercase();
        if font_size(&lower, 1.0).is_some() {
            font.size = token;
            break;
        }
        match lower.as_str() {
            "italic" => font.style = "italic",
            "oblique" => {
                font.style = "oblique";
                if parts.peek().is_some_and(|angle| is_font_angle(angle)) {
                    parts.next();
                }
            }
            "normal" => {}
            "small-caps" => font.variant = "small-caps",
            "bold" => font.weight = "bold",
            "bolder" => font.weight = "bolder",
            "lighter" => font.weight = "lighter",
            "ultra-condensed" => font.stretch = "ultra-condensed",
            "extra-condensed" => font.stretch = "extra-condensed",
            "condensed" => font.stretch = "condensed",
            "semi-condensed" => font.stretch = "semi-condensed",
            "semi-expanded" => font.stretch = "semi-expanded",
            "expanded" => font.stretch = "expanded",
            "extra-expanded" => font.stretch = "extra-expanded",
            "ultra-expanded" => font.stretch = "ultra-expanded",
            _ if is_font_weight(&lower) => font.weight = token,
            _ => return None,
        }
    }

    if parts.peek() == Some(&"/") {
        parts.next();
        font.line_height = parts.next().filter(|part| *part != "/")?;
    }
    let family = parts.next()?;
    font.family = if let Some(second) = parts.next() {
        let mut family = family.to_owned();
        for part in std::iter::once(second).chain(parts) {
            family.push(' ');
            family.push_str(part);
        }
        Cow::Owned(family)
    } else {
        if family == "/" {
            return None;
        }
        Cow::Borrowed(family)
    };
    Some(font)
}

fn is_font_angle(value: &str) -> bool {
    let value = value.to_lowercase();
    leading_number(&value).is_some()
        && ["deg", "grad", "rad", "turn"]
            .iter()
            .any(|unit| value.ends_with(unit))
}

fn is_font_weight(value: &str) -> bool {
    let mut weight = 0_u16;
    for digit in value.strip_prefix('+').unwrap_or(value).chars() {
        let Some(digit) = hex_digit(digit).filter(|digit| *digit < 10) else {
            return false;
        };
        weight = weight * 10 + u16::from(digit);
        if weight > 1000 {
            return false;
        }
    }
    weight != 0
}

fn contains_var(value: &str) -> bool {
    value
        .as_bytes()
        .windows(4)
        .any(|part| part.eq_ignore_ascii_case(b"var("))
}

fn whitespace(ch: char) -> bool {
    // Kotlin's Char.isWhitespace combines the JVM whitespace and space classes.
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

fn trim(value: &str) -> &str {
    value.trim_matches(whitespace)
}

/// Retains quoted and parenthesized text in one component. The font grammar
/// also treats a top-level slash as a component of its own.
fn tokens(value: &str, split_slash: bool) -> impl Iterator<Item = &str> {
    let mut chars = value.char_indices().peekable();
    std::iter::from_fn(move || {
        let (start, first) = loop {
            let ch = chars.next()?;
            if !whitespace(ch.1) {
                break ch;
            }
        };
        if split_slash && first == '/' {
            return Some(&value[start..start + 1]);
        }
        let mut quote = None;
        let mut parentheses = 0usize;
        let mut escaped = false;
        let mut current = Some((start, first));
        while let Some((_, ch)) = current {
            match quote {
                Some(open) if ch == open && !escaped => quote = None,
                Some(_) => {}
                None => match ch {
                    '\'' | '"' => quote = Some(ch),
                    '(' => parentheses += 1,
                    ')' => parentheses = parentheses.saturating_sub(1),
                    _ => {}
                },
            }
            escaped = ch == '\\' && !escaped;
            if let Some(&(next_index, next)) = chars.peek() {
                if quote.is_none()
                    && parentheses == 0
                    && (whitespace(next) || (split_slash && next == '/'))
                {
                    return Some(&value[start..next_index]);
                }
            }
            current = chars.next();
        }
        Some(&value[start..])
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raw(property: &str, value: &str, important: bool) -> RawDeclaration {
        RawDeclaration {
            property: property.into(),
            value: value.into(),
            important,
        }
    }

    fn row<'a>(declaration: &'a Declaration<'_>) -> (&'a str, &'a str, bool, i32) {
        (
            &declaration.property,
            &declaration.value,
            declaration.important,
            declaration.expansion,
        )
    }

    #[test]
    fn prepares_sides_and_preserves_source_until_expansion() {
        let source = [
            raw(" MARGIN ", "  var(--outer)  ", false),
            raw("padding-inline", "2em 3em", true),
            raw("padding-inline", "1px 2px 3px", false),
        ];
        let (consumed, block) = compile(&source);
        assert_eq!(consumed, 3);
        assert_eq!(
            block.iter().map(row).collect::<Vec<_>>(),
            [
                ("margin-top", "var(--outer)", false, TOP),
                ("margin-right", "var(--outer)", false, RIGHT),
                ("margin-bottom", "var(--outer)", false, BOTTOM),
                ("margin-left", "var(--outer)", false, LEFT),
                ("padding-inline-start", "2em 3em", true, INLINE_START),
                ("padding-inline-end", "2em 3em", true, INLINE_END),
            ]
        );
        assert!(matches!(block[0].value, Cow::Borrowed(_)));
        assert_eq!(
            expand(LEFT, "calc(1em + 2px) 3px 4px").as_deref(),
            Some("3px")
        );
        assert_eq!(expand(RIGHT, "1px 2px 3px 4px 5px"), None);
    }

    #[test]
    fn keeps_font_families_and_resets_and_supports_delayed_font_values() {
        let source = [
            raw(
                "font",
                "OBLIQUE 10deg small-caps 480 Semi-Expanded 16PX/1.2 \"Book Face\", Serif",
                true,
            ),
            raw("font-width", "condensed", false),
        ];
        let (_, block) = compile(&source);
        assert_eq!(block.len(), 12);
        let values = block.iter().map(row).collect::<Vec<_>>();
        assert_eq!(values[0], ("font-style", "oblique", true, IDENTITY));
        assert_eq!(values[1], ("font-weight", "480", true, IDENTITY));
        assert_eq!(values[2], ("font-size", "16PX", true, IDENTITY));
        assert_eq!(values[3], ("line-height", "1.2", true, IDENTITY));
        assert_eq!(
            values[4],
            ("font-family", "\"Book Face\", Serif", true, IDENTITY)
        );
        assert_eq!(values[6], ("font-stretch", "semi-expanded", true, IDENTITY));
        assert_eq!(
            values[11],
            ("font-variant-ligatures", "initial", true, IDENTITY)
        );

        let source = [raw("font", "var(--font)", false)];
        let (_, block) = compile(&source);
        assert_eq!(
            row(&block[4]),
            ("font-family", "var(--font)", false, FONT_FIRST + 4)
        );
        assert_eq!(
            expand(FONT_FIRST + 4, "italic 20px serif").as_deref(),
            Some("serif")
        );
        assert_eq!(expand(FONT_LAST, "unset").as_deref(), Some("inherit"));
        assert_eq!(
            expand(FONT_FIRST + 1, "+٤٨٠ 16px serif").as_deref(),
            Some("+٤٨٠")
        );
        assert_eq!(expand(FONT_FIRST + 1, "١٠٠١ 16px serif"), None);
    }

    #[test]
    fn validates_border_and_background_before_cascade_but_retains_the_raw_value() {
        let source = [
            raw("border-left", "DOTTED 1px rgb(1 2 3)", false),
            raw("border-top", "groove red", false),
            raw("background", "url(cover.png) #abc", false),
            raw("background", "url(cover.png) blue red", true),
        ];
        let (_, block) = compile(&source);
        assert_eq!(block.len(), 4);
        assert_eq!(
            row(&block[0]),
            (
                "border-left-width",
                "DOTTED 1px rgb(1 2 3)",
                false,
                BORDER_WIDTH
            )
        );
        assert_eq!(
            row(&block[1]),
            (
                "border-left-style",
                "DOTTED 1px rgb(1 2 3)",
                false,
                BORDER_STYLE
            )
        );
        assert_eq!(
            row(&block[2]),
            (
                "border-left-color",
                "DOTTED 1px rgb(1 2 3)",
                false,
                BORDER_COLOR
            )
        );
        assert_eq!(
            row(&block[3]),
            ("background-color", "url(cover.png) #abc", false, BACKGROUND)
        );
        assert_eq!(
            expand(BORDER_WIDTH, "dotted rgb(1 2 3)").as_deref(),
            Some("medium")
        );
        assert_eq!(expand(BORDER_COLOR, "inherit").as_deref(), Some("inherit"));
        assert_eq!(
            expand(BACKGROUND, "url(cover.png) #abc").as_deref(),
            Some("#abc")
        );
        assert_eq!(expand(BACKGROUND, "none").as_deref(), Some("transparent"));
    }

    #[test]
    fn duplicate_winners_keep_importance_then_source_order() {
        let source = [
            raw("font-width", "condensed", false),
            raw("font-weight", "500", true),
            raw("font-stretch", "expanded", false),
            raw("font-weight", "1000", false),
            raw("line-height", "normal", false),
        ];
        let (_, block) = compile(&source);
        assert_eq!(
            block.iter().map(row).collect::<Vec<_>>(),
            [
                ("font-stretch", "expanded", false, IDENTITY),
                ("line-height", "normal", false, IDENTITY),
                ("font-weight", "500", true, IDENTITY),
            ]
        );
    }

    #[test]
    fn counts_rejected_rows_and_stops_before_partially_admitting_a_shorthand() {
        let mut source = vec![raw("", "dropped", false)];
        source.extend((0..504).map(|index| raw(&format!("--unique-{index}"), "1", false)));
        source.push(raw("font", "16px serif", false));
        source.push(raw("padding", "1em", false));
        let (consumed, block) = compile(&source);
        assert_eq!(consumed, 506);
        assert_eq!(block.len(), 504);
        assert!(block
            .iter()
            .all(|declaration| declaration.expansion == IDENTITY));
    }
}
