//! Typography inheritance and authored computed values for the reader style model.
use crate::css_computed_model::ComputedStyle;
use crate::css_lengths::{self, LineHeight};

pub(crate) fn inherit(style: &mut ComputedStyle, parent: Option<&ComputedStyle>) {
    let Some(parent) = parent else { return };
    style.italic = parent.italic;
    style.font_weight = parent.font_weight;
    style.font_family_name.clone_from(&parent.font_family_name);
    style.monospace = parent.monospace;
    style
        .font_variant_caps
        .clone_from(&parent.font_variant_caps);
    style
        .font_variant_numeric
        .clone_from(&parent.font_variant_numeric);
    style
        .explicit_font_features
        .clone_from(&parent.explicit_font_features);
    style.font_kerning.clone_from(&parent.font_kerning);
    style
        .font_variant_ligatures
        .clone_from(&parent.font_variant_ligatures);
    style.variation_axes.clone_from(&parent.variation_axes);
    style.stretch_percent = parent.stretch_percent;
    style.optical_sizing = parent.optical_sizing;
    style.text_transform.clone_from(&parent.text_transform);
    style.visibility_hidden = parent.visibility_hidden;
    style.line_height_number = parent.line_height_number;
    style.line_height_absolute_em = parent.line_height_absolute_em;
    style.line_height_mult = line_height_multiplier(style);
}

/// Returns whether this declaration belongs to the typography portion of the model.
pub(crate) fn apply(
    style: &mut ComputedStyle,
    parent: Option<&ComputedStyle>,
    property: &str,
    value: &str,
    semantic_weight: i32,
    semantic_italic: bool,
) -> bool {
    match property {
        "font-style" => {
            if value.starts_with("italic") || value.starts_with("oblique") {
                style.italic = Some(true);
            } else {
                style.italic = match value {
                    "normal" | "initial" => Some(false),
                    "inherit" | "unset" => Some(parent.and_then(|p| p.italic).unwrap_or(false)),
                    "revert" | "revert-layer" => Some(semantic_italic),
                    _ => style.italic,
                };
            }
        }
        "font-weight" => {
            if let Some(weight) =
                font_weight(value, parent.and_then(|p| p.font_weight), semantic_weight)
            {
                style.font_weight = Some(weight);
            }
        }
        // The final size is resolved before any size-dependent declaration.
        "font-size" => {}
        "font-variant" | "font-variant-caps" => apply_variant(style, parent, property, value),
        "font-variant-numeric" => match value {
            "inherit" | "unset" => {
                style.font_variant_numeric = parent.and_then(|p| p.font_variant_numeric.clone());
            }
            "normal" | "initial" | "revert" | "revert-layer" => style.font_variant_numeric = None,
            _ if !value.is_empty() && words(value).all(|word| numeric_features(word).is_some()) => {
                style.font_variant_numeric = Some(value.into());
            }
            _ => {}
        },
        "font-variation-settings" => match value.to_lowercase().as_str() {
            "inherit" | "unset" | "revert" | "revert-layer" => {
                style.variation_axes = parent.and_then(|p| p.variation_axes.clone());
            }
            "normal" | "initial" => style.variation_axes = Some(Vec::new()),
            _ => {
                if let Some(axes) = parse_font_variations(value) {
                    style.variation_axes = Some(axes);
                }
            }
        },
        "font-stretch" | "font-width" => match value {
            "inherit" | "unset" | "revert" | "revert-layer" => {
                style.stretch_percent = parent.and_then(|p| p.stretch_percent);
            }
            "initial" => style.stretch_percent = Some(100.0),
            _ => {
                if let Some(stretch) = parse_font_stretch(value) {
                    style.stretch_percent = Some(stretch);
                }
            }
        },
        "font-optical-sizing" => match value {
            "inherit" | "unset" | "revert" | "revert-layer" => {
                style.optical_sizing = parent.and_then(|p| p.optical_sizing);
            }
            "auto" | "initial" => style.optical_sizing = Some(true),
            "none" => style.optical_sizing = Some(false),
            _ => {}
        },
        "font-kerning" => match value {
            "inherit" | "unset" | "revert" | "revert-layer" => {
                style.font_kerning = parent.and_then(|p| p.font_kerning.clone());
            }
            "auto" | "initial" => style.font_kerning = None,
            "normal" | "none" => style.font_kerning = Some(value.into()),
            _ => {}
        },
        "font-variant-ligatures" => match value {
            "inherit" | "unset" | "revert" | "revert-layer" => {
                style.font_variant_ligatures =
                    parent.and_then(|p| p.font_variant_ligatures.clone());
            }
            "normal" | "initial" => style.font_variant_ligatures = None,
            _ if valid_ligatures(value) => style.font_variant_ligatures = Some(value.into()),
            _ => {}
        },
        "font-feature-settings" => match value.to_lowercase().as_str() {
            "inherit" | "unset" => {}
            "normal" | "initial" | "revert" | "revert-layer" => style.explicit_font_features = None,
            _ => {
                if let Some(features) = parse_font_features(value) {
                    style.explicit_font_features = Some(features);
                }
            }
        },
        "font-family" => match value {
            "inherit" | "unset" => {}
            "initial" | "revert" | "revert-layer" => {
                style.monospace = false;
                style.font_family_name = None;
            }
            _ => {
                style.monospace = value.contains("monospace");
                if let Some(family) = first_family(value) {
                    style.font_family_name = Some(family);
                }
            }
        },
        "line-height" => match value {
            "inherit" | "unset" => {}
            "normal" | "initial" => {
                style.line_height_number = None;
                style.line_height_absolute_em = None;
                style.line_height_mult = None;
            }
            _ => {
                if let Some(height) = css_lengths::line_height(value, style.font_size_em) {
                    match height {
                        LineHeight::Number(number) => {
                            style.line_height_number = Some(number);
                            style.line_height_absolute_em = None;
                        }
                        LineHeight::AbsoluteEm(height) => {
                            style.line_height_number = None;
                            style.line_height_absolute_em = Some(height);
                        }
                    }
                    style.line_height_mult = line_height_multiplier(style);
                }
            }
        },
        "text-transform" => match value {
            "inherit" | "unset" => {}
            "none" | "initial" | "revert" | "revert-layer" => style.text_transform = None,
            "uppercase" | "lowercase" | "capitalize" => style.text_transform = Some(value.into()),
            _ => {}
        },
        "visibility" => match value {
            "inherit" | "unset" => {}
            "visible" | "initial" | "revert" | "revert-layer" => style.visibility_hidden = false,
            "hidden" | "collapse" => style.visibility_hidden = true,
            _ => {}
        },
        _ => return false,
    }
    true
}

fn line_height_multiplier(style: &ComputedStyle) -> Option<f32> {
    style
        .line_height_number
        .or_else(|| {
            style
                .line_height_absolute_em
                .map(|height| height / style.font_size_em.max(0.1))
        })
        .filter(|height| height.is_finite())
        .map(|height| height.clamp(0.5, 4.0))
}

fn apply_variant(
    style: &mut ComputedStyle,
    parent: Option<&ComputedStyle>,
    property: &str,
    value: &str,
) {
    match value {
        "inherit" | "unset" => {
            style.font_variant_caps = parent.and_then(|p| p.font_variant_caps.clone());
            if property == "font-variant" {
                style.font_variant_numeric = parent.and_then(|p| p.font_variant_numeric.clone());
                style.font_variant_ligatures =
                    parent.and_then(|p| p.font_variant_ligatures.clone());
            }
        }
        "normal" | "initial" | "revert" | "revert-layer" => {
            style.font_variant_caps = None;
            if property == "font-variant" {
                style.font_variant_numeric = None;
                style.font_variant_ligatures = None;
            }
        }
        _ if caps_features(value).is_some() => {
            style.font_variant_caps = Some(value.into());
            if property == "font-variant" {
                style.font_variant_numeric = None;
                style.font_variant_ligatures = None;
            }
        }
        _ if property == "font-variant" => {
            style.font_variant_caps = words(value)
                .find(|word| caps_features(word).is_some())
                .map(str::to_owned);
            style.font_variant_numeric =
                join_keywords(value, |word| numeric_features(word).is_some());
            style.font_variant_ligatures = join_keywords(value, |word| {
                ligature_features(word).is_some() || word == "none"
            });
        }
        _ => {}
    }
}

fn join_keywords(value: &str, accepts: impl Fn(&str) -> bool) -> Option<String> {
    let result = words(value)
        .filter(|word| accepts(word))
        .collect::<Vec<_>>()
        .join(" ");
    (!result.is_empty()).then_some(result)
}

/// Explicit settings overwrite the same variant feature while keeping its position.
pub(crate) fn finish(style: &mut ComputedStyle) {
    let mut features = Vec::new();
    if let Some(tags) = style.font_variant_caps.as_deref().and_then(caps_features) {
        for tag in tags {
            put(&mut features, (*tag).into(), 1);
        }
    }
    if let Some(numeric) = &style.font_variant_numeric {
        for tags in words(numeric).filter_map(numeric_features) {
            for tag in tags {
                put(&mut features, (*tag).into(), 1);
            }
        }
    }
    match style.font_kerning.as_deref() {
        Some("none") => put(&mut features, "kern".into(), 0),
        Some("normal") => put(&mut features, "kern".into(), 1),
        _ => {}
    }
    if style.font_variant_ligatures.as_deref() == Some("none") {
        for tag in ["liga", "clig", "dlig", "hlig", "calt"] {
            put(&mut features, tag.into(), 0);
        }
    } else if let Some(ligatures) = &style.font_variant_ligatures {
        for (tags, enabled) in words(ligatures).filter_map(ligature_features) {
            for tag in tags {
                put(&mut features, (*tag).into(), enabled);
            }
        }
    }
    if let Some(explicit) = &style.explicit_font_features {
        // This field has already been validated and normalized at application.
        for (tag, amount) in comma_members(explicit).filter_map(feature) {
            put(&mut features, tag.into(), amount);
        }
    }
    style.font_feature_settings = format_features(&features);
}

pub(crate) fn font_weight(value: &str, inherited: Option<i32>, reverted: i32) -> Option<i32> {
    let base = inherited.unwrap_or(400);
    Some(match value {
        "normal" | "initial" => 400,
        "revert" | "revert-layer" => reverted,
        "bold" => 700,
        "inherit" | "unset" => base,
        "bolder" => match base {
            ..350 => 400,
            350..550 => 700,
            550..900 => 900,
            _ => base,
        },
        "lighter" => match base {
            ..100 => base,
            100..550 => 100,
            550..750 => 400,
            _ => 700,
        },
        _ => {
            let weight = parse_float(value).filter(|weight| (1.0..=1000.0).contains(weight))?;
            weight.round_ties_even() as i32
        }
    })
}

pub(crate) fn parse_font_stretch(value: &str) -> Option<f32> {
    Some(match value {
        "ultra-condensed" => 50.0,
        "extra-condensed" => 62.5,
        "condensed" => 75.0,
        "semi-condensed" => 87.5,
        "normal" => 100.0,
        "semi-expanded" => 112.5,
        "expanded" => 125.0,
        "extra-expanded" => 150.0,
        "ultra-expanded" => 200.0,
        _ => {
            return value
                .strip_suffix('%')
                .and_then(parse_float)
                .filter(|width| *width > 0.0)
        }
    })
}

pub(crate) fn parse_font_variations(value: &str) -> Option<Vec<(String, f32)>> {
    let mut axes = Vec::new();
    for (index, item) in comma_members(value).enumerate() {
        if index == 64 {
            return None;
        }
        let item = trim(item);
        let bytes = item.as_bytes();
        let quote = *bytes
            .first()
            .filter(|quote| matches!(quote, b'\'' | b'"'))?;
        let tag = item
            .get(1..5)
            .filter(|tag| tag.bytes().all(|byte| (b' '..=b'~').contains(&byte)))?;
        if bytes.get(5) != Some(&quote) {
            return None;
        }
        let remaining = item.get(6..)?;
        if !remaining.chars().next().is_some_and(ascii_whitespace) {
            return None;
        }
        let amount = remaining.trim_start_matches(ascii_whitespace);
        if !decimal_literal(amount) {
            return None;
        }
        put(
            &mut axes,
            tag.into(),
            amount
                .parse::<f32>()
                .ok()
                .filter(|amount| amount.is_finite())?,
        );
    }
    (!axes.is_empty()).then_some(axes)
}

pub(crate) fn valid_ligatures(value: &str) -> bool {
    if value.is_empty() {
        return false;
    }
    if value == "none" {
        return true;
    }
    let mut groups = Vec::new();
    for token in words(value) {
        if ligature_features(token).is_none() {
            return false;
        }
        let group = token.strip_prefix("no-").unwrap_or(token);
        if groups.contains(&group) {
            return false;
        }
        groups.push(group);
    }
    true
}

pub(crate) fn parse_font_features(value: &str) -> Option<String> {
    let mut features = Vec::new();
    for item in comma_members(value) {
        let (tag, amount) = feature(item)?;
        put(&mut features, tag.into(), amount);
    }
    format_features(&features)
}

fn feature(item: &str) -> Option<(&str, i32)> {
    let item = trim(item);
    let bytes = item.as_bytes();
    if !matches!(bytes.first(), Some(b'\'' | b'"')) || !matches!(bytes.get(5), Some(b'\'' | b'"')) {
        return None;
    }
    let tag = item
        .get(1..5)
        .filter(|tag| tag.bytes().all(|byte| (b' '..=b'~').contains(&byte)))?;
    let remainder = item.get(6..)?;
    if remainder.is_empty() {
        return Some((tag, 1));
    }
    if !remainder.chars().next().is_some_and(ascii_whitespace) {
        return None;
    }
    let argument = remainder.trim_start_matches(ascii_whitespace);
    let amount = if argument.eq_ignore_ascii_case("on") {
        1
    } else if argument.eq_ignore_ascii_case("off") {
        0
    } else if !argument.is_empty() && argument.bytes().all(|byte| byte.is_ascii_digit()) {
        argument
            .parse::<i32>()
            .ok()
            .filter(|amount| (0..=65_535).contains(amount))?
    } else {
        return None;
    };
    Some((tag, amount))
}

fn first_family(value: &str) -> Option<String> {
    let first = value.split(',').next()?;
    let family = trim(trim(first).trim_matches(['\'', '"'])).to_lowercase();
    (!family.is_empty() && family != "inherit").then_some(family)
}

fn format_features(features: &[(String, i32)]) -> Option<String> {
    (!features.is_empty()).then(|| {
        features
            .iter()
            .map(|(tag, amount)| format!("'{tag}' {amount}"))
            .collect::<Vec<_>>()
            .join(", ")
    })
}

fn put<T>(values: &mut Vec<(String, T)>, tag: String, amount: T) {
    if let Some((_, previous)) = values.iter_mut().find(|(name, _)| *name == tag) {
        *previous = amount;
    } else {
        values.push((tag, amount));
    }
}

fn caps_features(keyword: &str) -> Option<&'static [&'static str]> {
    Some(match keyword {
        "small-caps" => &["smcp"],
        "all-small-caps" => &["smcp", "c2sc"],
        "petite-caps" => &["pcap"],
        "all-petite-caps" => &["pcap", "c2pc"],
        "unicase" => &["unic"],
        "titling-caps" => &["titl"],
        _ => return None,
    })
}

fn numeric_features(keyword: &str) -> Option<&'static [&'static str]> {
    Some(match keyword {
        "lining-nums" => &["lnum"],
        "oldstyle-nums" => &["onum"],
        "proportional-nums" => &["pnum"],
        "tabular-nums" => &["tnum"],
        "diagonal-fractions" => &["frac"],
        "stacked-fractions" => &["afrc"],
        "ordinal" => &["ordn"],
        "slashed-zero" => &["zero"],
        _ => return None,
    })
}

fn ligature_features(keyword: &str) -> Option<(&'static [&'static str], i32)> {
    Some(match keyword {
        "common-ligatures" => (&["liga", "clig"], 1),
        "no-common-ligatures" => (&["liga", "clig"], 0),
        "discretionary-ligatures" => (&["dlig"], 1),
        "no-discretionary-ligatures" => (&["dlig"], 0),
        "historical-ligatures" => (&["hlig"], 1),
        "no-historical-ligatures" => (&["hlig"], 0),
        "contextual" => (&["calt"], 1),
        "no-contextual" => (&["calt"], 0),
        _ => return None,
    })
}

fn reader_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

fn trim(value: &str) -> &str {
    value.trim_matches(reader_whitespace)
}

fn ascii_whitespace(ch: char) -> bool {
    matches!(ch, ' ' | '\t'..='\r')
}

fn words(value: &str) -> impl Iterator<Item = &str> {
    value
        .split(ascii_whitespace)
        .filter(|word| !word.is_empty())
}

/// Split the same bounded comma lists used by declaration capability parsing.
fn comma_members(value: &str) -> impl Iterator<Item = &str> {
    let mut start = 0;
    let mut quote = None;
    let mut parens = 0usize;
    let mut brackets = 0usize;
    let mut backslashes = 0;
    let mut result = Vec::new();
    for (index, ch) in value.char_indices() {
        if let Some(quoted) = quote {
            if ch == quoted && backslashes % 2 == 0 {
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
                    result.push(&value[start..index]);
                    if result.len() == 256 {
                        return result.into_iter();
                    }
                    start = index + 1;
                }
                _ => {}
            }
        }
        backslashes = if ch == '\\' { backslashes + 1 } else { 0 };
    }
    result.push(&value[start..]);
    result.into_iter()
}

/// Java's finite Float parsing accepts scalar suffixes and hexadecimal floats.
pub(crate) fn parse_float(value: &str) -> Option<f32> {
    let value = value.trim_matches(|ch| ch <= '\u{20}');
    let value = value.strip_suffix(['f', 'F', 'd', 'D']).unwrap_or(value);
    let unsigned = value.strip_prefix(['+', '-']).unwrap_or(value);
    if unsigned.starts_with("0x") || unsigned.starts_with("0X") {
        return hex_float(value).filter(|value| value.is_finite());
    }
    value.parse::<f32>().ok().filter(|value| value.is_finite())
}

fn hex_float(value: &str) -> Option<f32> {
    let negative = value.starts_with('-');
    let value = value.strip_prefix(['+', '-']).unwrap_or(value);
    let value = value.get(2..)?;
    let exponent = value.find(['p', 'P'])?;
    let (digits, exponent) = (&value[..exponent], &value[exponent + 1..]);
    let exponent = exponent.parse::<i32>().ok()?;
    let mut number = 0.0_f64;
    let mut fractional = false;
    let mut fractional_digits = 0_i64;
    let mut digit_count = 0;
    for ch in digits.chars() {
        if ch == '.' && !fractional {
            fractional = true;
            continue;
        }
        let digit = ch.to_digit(16)?;
        number = number * 16.0 + f64::from(digit);
        digit_count += 1;
        fractional_digits += i64::from(fractional);
    }
    if digit_count == 0 {
        return None;
    }
    let shift = i64::from(exponent) - 4 * fractional_digits;
    if number == 0.0 {
        return Some(if negative { -0.0 } else { 0.0 });
    }
    let shift = i32::try_from(shift).ok()?;
    let number = number * 2.0_f64.powi(shift);
    Some((if negative { -number } else { number }) as f32)
}

fn decimal_literal(value: &str) -> bool {
    let value = value.strip_prefix(['+', '-']).unwrap_or(value);
    let mut pieces = value.split(['e', 'E']);
    let mantissa = pieces.next().unwrap_or_default();
    if let Some(exponent) = pieces.next() {
        let exponent = exponent.strip_prefix(['+', '-']).unwrap_or(exponent);
        if exponent.is_empty()
            || !exponent.bytes().all(|byte| byte.is_ascii_digit())
            || pieces.next().is_some()
        {
            return false;
        }
    }
    let mut pieces = mantissa.split('.');
    let first = pieces.next().unwrap_or_default();
    let second = pieces.next();
    if pieces.next().is_some() {
        return false;
    }
    let digits = |value: &str| value.bytes().all(|byte| byte.is_ascii_digit());
    digits(first)
        && match second {
            Some(second) => digits(second) && (!first.is_empty() || !second.is_empty()),
            None => !first.is_empty(),
        }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inheritance_recomputes_absolute_line_height_and_keeps_nullable_typography() {
        let parent = ComputedStyle {
            font_size_em: 2.0,
            font_weight: Some(500),
            italic: Some(true),
            line_height_absolute_em: Some(3.0),
            variation_axes: Some(Vec::new()),
            text_transform: Some("uppercase".into()),
            ..ComputedStyle::default()
        };
        let mut child = ComputedStyle {
            font_size_em: 1.0,
            ..ComputedStyle::default()
        };
        inherit(&mut child, Some(&parent));
        assert_eq!(child.line_height_mult, Some(3.0));
        assert_eq!(child.line_height_number, None);
        assert_eq!(child.variation_axes, Some(Vec::new()));
        assert_eq!(child.text_transform.as_deref(), Some("uppercase"));
        apply(
            &mut child,
            Some(&parent),
            "line-height",
            "calc(2 + .5)",
            500,
            true,
        );
        assert_eq!(child.line_height_number, Some(2.5));
        assert_eq!(child.line_height_absolute_em, None);
        child.font_size_em = 0.5;
        inherit(&mut child, Some(&parent));
        assert_eq!(child.line_height_mult, Some(4.0));
    }

    #[test]
    fn features_preserve_source_case_and_variant_override_positions() {
        let mut style = ComputedStyle::default();
        for (property, value) in [
            (
                "font-variant",
                "all-small-caps oldstyle-nums no-common-ligatures",
            ),
            ("font-kerning", "normal"),
            ("font-feature-settings", "'smcp' off, 'TEST' 2, 'TEST' 3"),
        ] {
            assert!(apply(&mut style, None, property, value, 400, false));
        }
        finish(&mut style);
        assert_eq!(
            style.font_feature_settings.as_deref(),
            Some("'smcp' 0, 'c2sc' 1, 'onum' 1, 'kern' 1, 'liga' 0, 'clig' 0, 'TEST' 3")
        );
        assert_eq!(
            parse_font_variations("'wght' 450, \"GRAD\" -.5e+2, 'wght' 500"),
            Some(vec![("wght".into(), 500.0), ("GRAD".into(), -50.0)])
        );
        assert!(parse_font_variations("'wght\" 450").is_none());
        assert_eq!(
            parse_font_variations("'wght'\u{b}450"),
            Some(vec![("wght".into(), 450.0)])
        );
        assert_eq!(
            parse_font_features("'kern'\u{b}on").as_deref(),
            Some("'kern' 1")
        );
        assert!(parse_font_features("'wght' -1").is_none());
        assert!(parse_font_features("'wght' 65536").is_none());
    }

    #[test]
    fn computed_font_declarations_retain_inheritance_resets_and_invalid_values() {
        let parent = ComputedStyle {
            font_weight: Some(300),
            italic: None,
            font_variant_caps: Some("small-caps".into()),
            stretch_percent: Some(120.0),
            optical_sizing: Some(false),
            font_family_name: Some("source serif".into()),
            ..ComputedStyle::default()
        };
        let mut style = ComputedStyle {
            font_size_em: 1.0,
            monospace: true,
            ..ComputedStyle::default()
        };
        inherit(&mut style, Some(&parent));
        assert!(apply(
            &mut style,
            Some(&parent),
            "font-style",
            "inherit",
            700,
            true
        ));
        assert_eq!(style.italic, Some(false));
        apply(
            &mut style,
            Some(&parent),
            "font-weight",
            "revert",
            700,
            true,
        );
        assert_eq!(style.font_weight, Some(700));
        apply(
            &mut style,
            Some(&parent),
            "font-variant-ligatures",
            "common-ligatures no-common-ligatures",
            700,
            true,
        );
        assert_eq!(style.font_variant_ligatures, None);
        apply(
            &mut style,
            Some(&parent),
            "font-variation-settings",
            "normal",
            700,
            true,
        );
        assert_eq!(style.variation_axes, Some(Vec::new()));
        apply(
            &mut style,
            Some(&parent),
            "font-optical-sizing",
            "initial",
            700,
            true,
        );
        assert_eq!(style.optical_sizing, Some(true));
        apply(
            &mut style,
            Some(&parent),
            "font-family",
            "monospace",
            700,
            true,
        );
        assert!(style.monospace);
        assert_eq!(style.font_family_name.as_deref(), Some("monospace"));
        assert!(!apply(
            &mut style,
            Some(&parent),
            "margin-left",
            "1em",
            700,
            true
        ));
    }

    #[test]
    fn weights_and_widths_use_finite_jvm_float_forms_and_even_rounding() {
        assert_eq!(font_weight("400.5", None, 400), Some(400));
        assert_eq!(font_weight("401.5", None, 400), Some(402));
        assert_eq!(font_weight("0x1.9p8", None, 400), Some(400));
        assert_eq!(font_weight("900f", None, 400), Some(900));
        assert_eq!(font_weight("NaN", None, 400), None);
        assert_eq!(font_weight("bolder", Some(550), 400), Some(900));
        assert_eq!(font_weight("lighter", Some(550), 400), Some(400));
        assert_eq!(parse_font_stretch("62.5%"), Some(62.5));
        assert_eq!(parse_font_stretch("0%"), None);
    }
}
