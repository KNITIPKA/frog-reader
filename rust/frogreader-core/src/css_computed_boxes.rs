use crate::css_color;
use crate::css_computed_fonts::parse_float;
use crate::css_computed_model::{Border, ComputedStyle};
use crate::css_lengths::{leading_number, length_em};

const BLACK: i32 = 0xff00_0000_u32 as i32;
const MEDIUM_BORDER_EM: f32 = 2.0 / 16.0;

pub(crate) struct Boxes {
    auto_left: bool,
    auto_right: bool,
    borders: [Border; 4],
}

impl Boxes {
    pub(crate) fn new(style: &ComputedStyle) -> Self {
        Self {
            auto_left: false,
            auto_right: false,
            borders: std::array::from_fn(|_| Border {
                width_em: MEDIUM_BORDER_EM,
                color_argb: style.foreground_color_argb.unwrap_or(BLACK),
                style: 0,
                uses_current_color: style.foreground_color_argb.is_none(),
            }),
        }
    }

    pub(crate) fn apply(
        &mut self,
        style: &mut ComputedStyle,
        parent: Option<&ComputedStyle>,
        property: &str,
        value: &str,
        rtl: bool,
    ) -> bool {
        let em = style.font_size_em;
        match property {
            "width" => {
                if value.ends_with('%') {
                    if let Some(number) = leading_number(value) {
                        style.width_frac = Some((number / 100.0).clamp(0.05, 1.0));
                    }
                } else if let Some(length) = length_em(value, 0.0, em, false) {
                    style.width_em = Some(length);
                }
            }
            "height" => {
                if !value.ends_with('%') && value != "auto" {
                    if let Some(length) =
                        length_em(value, 0.0, em, false).filter(|value| *value > 0.0)
                    {
                        style.height_em = Some(length);
                    }
                }
            }
            "min-height" => match value {
                "inherit" => {
                    style.min_height_em = parent.and_then(|parent| parent.min_height_em);
                    style.min_height_viewport_frac =
                        parent.and_then(|parent| parent.min_height_viewport_frac);
                    style.min_height_offset_em =
                        parent.map_or(0.0, |parent| parent.min_height_offset_em);
                }
                "initial" | "unset" | "revert" | "revert-layer" | "auto" => {}
                _ => {
                    if let Some((fraction, offset)) = viewport_height(value, em) {
                        style.min_height_viewport_frac = Some(fraction);
                        style.min_height_offset_em = offset;
                    } else if !has_viewport_unit(value) {
                        if let Some(length) =
                            length_em(value, 0.0, em, false).filter(|value| *value >= 0.0)
                        {
                            style.min_height_em = Some(length.min(30.0));
                        }
                    }
                }
            },
            "max-width" => {
                if let Some(percent) = value.strip_suffix('%') {
                    if let Some(number) = positive_number(percent) {
                        style.max_width_frac = Some((number / 100.0).clamp(0.01, 1.0));
                    }
                } else if let Some(length) =
                    length_em(value, 0.0, em, false).filter(|value| *value > 0.0)
                {
                    style.max_width_em = Some(length);
                }
            }
            "max-height" => {
                if let Some(viewport) = value.strip_suffix("vh") {
                    if let Some(number) = positive_number(viewport) {
                        style.max_height_viewport_frac = Some((number / 100.0).clamp(0.01, 1.0));
                    }
                } else if !value.ends_with('%') {
                    if let Some(length) =
                        length_em(value, 0.0, em, false).filter(|value| *value > 0.0)
                    {
                        style.max_height_em = Some(length);
                    }
                }
            }
            "box-sizing" => match value {
                "border-box" => style.border_box = true,
                "content-box" | "initial" | "unset" | "revert" | "revert-layer" => {
                    style.border_box = false
                }
                "inherit" => style.border_box = parent.is_some_and(|parent| parent.border_box),
                _ => {}
            },
            "margin-left" => {
                clear_logical_margin(style, !rtl);
                self.auto_left = value == "auto";
                if !self.auto_left {
                    set_box_value(
                        &mut style.margin_start_em,
                        &mut style.margin_start_frac,
                        value,
                        em,
                    );
                }
            }
            "margin-right" => {
                clear_logical_margin(style, rtl);
                self.auto_right = value == "auto";
                if !self.auto_right {
                    set_box_value(
                        &mut style.margin_end_em,
                        &mut style.margin_end_frac,
                        value,
                        em,
                    );
                }
            }
            "margin-inline-start" => {
                clear_physical_margin(style, !rtl);
                if rtl {
                    self.auto_right = value == "auto";
                } else {
                    self.auto_left = value == "auto";
                }
                if value != "auto" {
                    set_box_value(
                        &mut style.margin_inline_start_em,
                        &mut style.margin_inline_start_frac,
                        value,
                        em,
                    );
                }
            }
            "margin-inline-end" => {
                clear_physical_margin(style, rtl);
                if rtl {
                    self.auto_left = value == "auto";
                } else {
                    self.auto_right = value == "auto";
                }
                if value != "auto" {
                    set_box_value(
                        &mut style.margin_inline_end_em,
                        &mut style.margin_inline_end_frac,
                        value,
                        em,
                    );
                }
            }
            "margin-top" => set_length(&mut style.margin_top_em, value, em),
            "margin-bottom" => set_length(&mut style.margin_bottom_em, value, em),
            "padding-top" => set_length(&mut style.padding_top_em, value, em),
            "padding-bottom" => set_length(&mut style.padding_bottom_em, value, em),
            "padding-left" => {
                clear_logical_padding(style, !rtl);
                set_box_value(
                    &mut style.padding_start_em,
                    &mut style.padding_start_frac,
                    value,
                    em,
                );
            }
            "padding-right" => {
                clear_logical_padding(style, rtl);
                set_box_value(
                    &mut style.padding_end_em,
                    &mut style.padding_end_frac,
                    value,
                    em,
                );
            }
            "padding-inline-start" => {
                clear_physical_padding(style, !rtl);
                set_box_value(
                    &mut style.padding_inline_start_em,
                    &mut style.padding_inline_start_frac,
                    value,
                    em,
                );
            }
            "padding-inline-end" => {
                clear_physical_padding(style, rtl);
                set_box_value(
                    &mut style.padding_inline_end_em,
                    &mut style.padding_inline_end_frac,
                    value,
                    em,
                );
            }
            _ => {
                let Some((side, part)) = border_property(property) else {
                    return false;
                };
                let inherited = parent.and_then(|parent| match side {
                    0 => parent.border_top.as_ref(),
                    1 => parent.border_right.as_ref(),
                    2 => parent.border_bottom.as_ref(),
                    _ => parent.border_left.as_ref(),
                });
                apply_border(
                    &mut self.borders[side],
                    inherited,
                    style.foreground_color_argb,
                    part,
                    value,
                    em,
                );
            }
        }
        true
    }

    pub(crate) fn finish(self, style: &mut ComputedStyle) {
        for value in [
            &mut style.margin_start_em,
            &mut style.margin_end_em,
            &mut style.margin_inline_start_em,
            &mut style.margin_inline_end_em,
            &mut style.padding_start_em,
            &mut style.padding_end_em,
            &mut style.padding_inline_start_em,
            &mut style.padding_inline_end_em,
        ] {
            *value = value.clamp(0.0, 8.0);
        }
        for value in [
            &mut style.margin_start_frac,
            &mut style.margin_end_frac,
            &mut style.margin_inline_start_frac,
            &mut style.margin_inline_end_frac,
            &mut style.padding_start_frac,
            &mut style.padding_end_frac,
            &mut style.padding_inline_start_frac,
            &mut style.padding_inline_end_frac,
        ] {
            *value = value.clamp(0.0, 0.45);
        }
        for value in [
            &mut style.margin_top_em,
            &mut style.margin_bottom_em,
            &mut style.padding_top_em,
            &mut style.padding_bottom_em,
        ] {
            *value = value.clamp(0.0, 12.0);
        }
        style.centered_box = self.auto_left && self.auto_right;
        let [top, right, bottom, left] = self
            .borders
            .map(|border| (border.width_em > 0.0 && border.style != 0).then_some(border));
        style.border_top = top;
        style.border_right = right;
        style.border_bottom = bottom;
        style.border_left = left;
    }
}

fn set_length(target: &mut f32, value: &str, em: f32) {
    if let Some(length) = length_em(value, 0.0, em, false) {
        *target = length;
    }
}

fn set_box_value(length: &mut f32, fraction: &mut f32, value: &str, em: f32) {
    set_length(length, value, em);
    if value.ends_with('%') {
        if let Some(number) = leading_number(value) {
            *fraction = (number / 100.0).clamp(0.0, 0.6);
        }
    }
}

fn clear_logical_margin(style: &mut ComputedStyle, start: bool) {
    if start {
        style.margin_inline_start_em = 0.0;
        style.margin_inline_start_frac = 0.0;
    } else {
        style.margin_inline_end_em = 0.0;
        style.margin_inline_end_frac = 0.0;
    }
}

fn clear_physical_margin(style: &mut ComputedStyle, left: bool) {
    if left {
        style.margin_start_em = 0.0;
        style.margin_start_frac = 0.0;
    } else {
        style.margin_end_em = 0.0;
        style.margin_end_frac = 0.0;
    }
}

fn clear_logical_padding(style: &mut ComputedStyle, start: bool) {
    if start {
        style.padding_inline_start_em = 0.0;
        style.padding_inline_start_frac = 0.0;
    } else {
        style.padding_inline_end_em = 0.0;
        style.padding_inline_end_frac = 0.0;
    }
}

fn clear_physical_padding(style: &mut ComputedStyle, left: bool) {
    if left {
        style.padding_start_em = 0.0;
        style.padding_start_frac = 0.0;
    } else {
        style.padding_end_em = 0.0;
        style.padding_end_frac = 0.0;
    }
}

fn border_property(property: &str) -> Option<(usize, &str)> {
    let suffix = property.strip_prefix("border-")?;
    let (side, part) = suffix.split_once('-')?;
    if !matches!(part, "width" | "style" | "color") {
        return None;
    }
    let side = match side {
        "top" => 0,
        "right" => 1,
        "bottom" => 2,
        "left" => 3,
        _ => return None,
    };
    Some((side, part))
}

fn apply_border(
    border: &mut Border,
    inherited: Option<&Border>,
    foreground: Option<i32>,
    part: &str,
    value: &str,
    em: f32,
) {
    let current_color = foreground.unwrap_or(BLACK);
    match part {
        "width" => {
            let width = match value {
                "inherit" => Some(inherited.map_or(MEDIUM_BORDER_EM, |border| border.width_em)),
                "initial" | "unset" | "revert" | "revert-layer" | "medium" => {
                    Some(MEDIUM_BORDER_EM)
                }
                "thin" => Some(1.0 / 16.0),
                "thick" => Some(3.0 / 16.0),
                _ => length_em(value, 0.0, em, false).map(|value| value.clamp(0.0, 2.0)),
            };
            if let Some(width) = width {
                border.width_em = width;
            }
        }
        "style" => {
            let style = match value {
                "inherit" => Some(inherited.map_or(0, |border| border.style)),
                "none" | "hidden" | "initial" | "unset" | "revert" | "revert-layer" => Some(0),
                "solid" => Some(1),
                "dashed" => Some(2),
                "dotted" => Some(3),
                "double" => Some(4),
                _ => None,
            };
            if let Some(style) = style {
                border.style = style;
            }
        }
        "color" => {
            let color = match value {
                "inherit" => Some(inherited.map_or(current_color, |border| border.color_argb)),
                "currentcolor" | "initial" | "unset" | "revert" | "revert-layer" => {
                    Some(current_color)
                }
                _ => css_color::parse_color(value).map(|color| color as i32),
            };
            if let Some(color) = color {
                border.color_argb = color;
                border.uses_current_color = match value {
                    "inherit" => {
                        inherited.map_or(foreground.is_none(), |border| border.uses_current_color)
                    }
                    "currentcolor" | "initial" | "unset" | "revert" | "revert-layer" => {
                        foreground.is_none()
                    }
                    _ => false,
                };
            }
        }
        _ => unreachable!(),
    }
}

fn positive_number(value: &str) -> Option<f32> {
    parse_float(value.trim()).filter(|value| value.is_finite() && *value > 0.0)
}

fn unit_of(value: &str) -> &str {
    value
        .char_indices()
        .rev()
        .find(|&(_, ch)| !(ch == '%' || ch.len_utf16() == 1 && ch.is_alphabetic()))
        .map_or(value, |(index, ch)| &value[index + ch.len_utf8()..])
}

fn viewport_term(value: &str, em: f32) -> Option<(f32, f32)> {
    let unit = unit_of(value);
    if unit == "vh" {
        return leading_number(value).map(|value| (value / 100.0, 0.0));
    }
    if value != "0"
        && !matches!(
            unit,
            "em" | "rem" | "px" | "pt" | "pc" | "in" | "cm" | "mm" | "q" | "ch" | "ex"
        )
    {
        return None;
    }
    length_em(value, 0.0, em, true).map(|value| (0.0, value))
}

fn numeric_term_length(value: &str) -> Option<usize> {
    let bytes = value.as_bytes();
    let mut cursor = usize::from(
        bytes
            .first()
            .is_some_and(|byte| matches!(byte, b'+' | b'-')),
    );
    let before = cursor;
    while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
        cursor += 1;
    }
    let digits = cursor - before;
    if bytes.get(cursor) == Some(&b'.') {
        cursor += 1;
        let fraction = cursor;
        while bytes.get(cursor).is_some_and(u8::is_ascii_digit) {
            cursor += 1;
        }
        if cursor == fraction {
            return None;
        }
    } else if digits == 0 {
        return None;
    }
    Some(cursor)
}

fn valid_viewport_term(value: &str) -> bool {
    numeric_term_length(value).is_some_and(|number| {
        matches!(
            &value[number..],
            "" | "vh" | "rem" | "em" | "px" | "pt" | "pc" | "in" | "cm" | "mm" | "q" | "ch" | "ex"
        )
    })
}

fn css_whitespace(ch: char) -> bool {
    matches!(ch, '\t'..='\r' | ' ')
}

fn viewport_height(value: &str, em: f32) -> Option<(f32, f32)> {
    let result = if value
        .strip_suffix("vh")
        .is_some_and(|number| numeric_term_length(number) == Some(number.len()))
    {
        viewport_term(value, em)
    } else {
        let contents = value
            .strip_prefix("calc(")?
            .strip_suffix(')')?
            .trim_matches(css_whitespace);
        let left_end = contents.find(css_whitespace)?;
        let (left, tail) = contents.split_at(left_end);
        let tail = tail.trim_start_matches(css_whitespace);
        let operator = tail.as_bytes().first().copied()?;
        if !matches!(operator, b'+' | b'-') || !tail[1..].starts_with(css_whitespace) {
            return None;
        }
        let right = tail[1..].trim_start_matches(css_whitespace);
        if !valid_viewport_term(left) || !valid_viewport_term(right) {
            return None;
        }
        let left = viewport_term(left, em)?;
        let right = viewport_term(right, em)?;
        let sign = if operator == b'-' { -1.0 } else { 1.0 };
        Some((left.0 + sign * right.0, left.1 + sign * right.1))
    }?;
    (result.0.is_finite()
        && (0.01..=4.0).contains(&result.0)
        && result.1.is_finite()
        && (-100.0..=100.0).contains(&result.1))
    .then_some(result)
}

fn has_viewport_unit(value: &str) -> bool {
    value.char_indices().any(|(index, character)| {
        if !character.is_ascii_digit() {
            return false;
        }
        let tail = &value[index + 1..];
        ["vw", "vh", "vmin", "vmax"].iter().any(|unit| {
            tail.get(..unit.len())
                .is_some_and(|prefix| prefix.eq_ignore_ascii_case(unit))
                && tail[unit.len()..]
                    .chars()
                    .next()
                    .is_none_or(|ch| !ch.is_alphanumeric() && ch != '_')
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_style() -> ComputedStyle {
        ComputedStyle {
            font_size_em: 2.0,
            ..Default::default()
        }
    }

    #[test]
    fn later_physical_and_logical_declarations_replace_the_same_axis() {
        for rtl in [false, true] {
            let mut style = base_style();
            let mut boxes = Boxes::new(&style);
            boxes.apply(&mut style, None, "margin-inline-start", "10%", rtl);
            boxes.apply(&mut style, None, "padding-inline-end", "2em", rtl);
            let physical_margin = if rtl { "margin-right" } else { "margin-left" };
            let physical_padding = if rtl { "padding-left" } else { "padding-right" };
            boxes.apply(&mut style, None, physical_margin, "1em", rtl);
            boxes.apply(&mut style, None, physical_padding, "15%", rtl);
            assert_eq!(style.margin_inline_start_frac, 0.0);
            assert_eq!(style.padding_inline_end_em, 0.0);
            boxes.apply(&mut style, None, "margin-inline-start", "3px", rtl);
            boxes.apply(&mut style, None, "padding-inline-end", "3px", rtl);
            boxes.finish(&mut style);
            assert_eq!(style.margin_start_em, 0.0);
            assert_eq!(style.margin_end_em, 0.0);
            assert_eq!(style.padding_start_frac, 0.0);
            assert_eq!(style.padding_end_frac, 0.0);
            assert_eq!(style.margin_inline_start_em, 3.0 / 16.0);
            assert_eq!(style.padding_inline_end_em, 3.0 / 16.0);
        }
    }

    #[test]
    fn box_limits_and_auto_centering_follow_the_last_declaration() {
        let mut style = base_style();
        let mut boxes = Boxes::new(&style);
        boxes.apply(&mut style, None, "margin-left", "auto", false);
        boxes.apply(&mut style, None, "margin-inline-end", "auto", false);
        boxes.apply(&mut style, None, "padding-top", "30em", false);
        boxes.apply(&mut style, None, "padding-left", "70%", false);
        boxes.apply(&mut style, None, "margin-top", "-5em", false);
        boxes.finish(&mut style);
        assert!(style.centered_box);
        assert_eq!(style.padding_top_em, 12.0);
        assert_eq!(style.padding_start_frac, 0.45);
        assert_eq!(style.margin_top_em, 0.0);

        let mut style = base_style();
        let mut boxes = Boxes::new(&style);
        boxes.apply(&mut style, None, "margin-left", "auto", true);
        boxes.apply(&mut style, None, "margin-right", "auto", true);
        boxes.apply(&mut style, None, "margin-inline-end", "0", true);
        boxes.finish(&mut style);
        assert!(!style.centered_box);
    }

    #[test]
    fn borders_keep_unresolved_current_color_and_explicit_inheritance() {
        let mut parent = base_style();
        let mut boxes = Boxes::new(&parent);
        boxes.apply(&mut parent, None, "border-top-style", "solid", false);
        boxes.apply(&mut parent, None, "border-top-width", "thick", false);
        boxes.finish(&mut parent);
        let border = parent.border_top.as_ref().unwrap();
        assert_eq!(border.width_em, 3.0 / 16.0);
        assert!(border.uses_current_color);

        let mut child = ComputedStyle {
            foreground_color_argb: Some(0xffff0000_u32 as i32),
            ..base_style()
        };
        let mut boxes = Boxes::new(&child);
        for part in ["style", "width", "color"] {
            boxes.apply(
                &mut child,
                Some(&parent),
                &format!("border-top-{part}"),
                "inherit",
                false,
            );
        }
        boxes.apply(
            &mut child,
            Some(&parent),
            "border-bottom-style",
            "double",
            false,
        );
        boxes.apply(
            &mut child,
            Some(&parent),
            "border-bottom-width",
            "8em",
            false,
        );
        boxes.apply(
            &mut child,
            Some(&parent),
            "border-bottom-color",
            "blue",
            false,
        );
        boxes.finish(&mut child);
        assert!(child.border_top.as_ref().unwrap().uses_current_color);
        let bottom = child.border_bottom.as_ref().unwrap();
        assert_eq!(bottom.width_em, 2.0);
        assert_eq!(bottom.style, 4);
        assert_eq!(bottom.color_argb, 0xff0000ff_u32 as i32);
        assert!(!bottom.uses_current_color);
        assert!(child.border_left.is_none());
    }

    #[test]
    fn affine_viewport_minimum_and_size_limits_keep_their_native_forms() {
        assert_eq!(viewport_height("50vh", 2.0), Some((0.5, 0.0)));
        assert_eq!(viewport_height("calc(50vh - 2em)", 2.0), Some((0.5, -4.0)));
        assert_eq!(viewport_height("calc(2em + 50vh)", 2.0), Some((0.5, 4.0)));
        assert_eq!(viewport_height("calc(50vh-2em)", 2.0), None);
        assert_eq!(viewport_height("calc(50vh * 2)", 2.0), None);
        assert_eq!(viewport_height("calc(10vw + 2em)", 2.0), None);
        assert_eq!(viewport_height("0vh", 2.0), None);
        assert_eq!(viewport_height("calc(50vh + 101em)", 2.0), None);
        let mut style = base_style();
        let mut boxes = Boxes::new(&style);
        boxes.apply(&mut style, None, "min-height", "calc(50vh - 2em)", false);
        boxes.apply(&mut style, None, "min-height", "initial", false);
        boxes.apply(&mut style, None, "max-height", "200vh", false);
        boxes.apply(&mut style, None, "max-width", "300%", false);
        boxes.apply(&mut style, None, "width", "0%", false);
        boxes.finish(&mut style);
        assert_eq!(style.min_height_viewport_frac, Some(0.5));
        assert_eq!(style.min_height_offset_em, -4.0);
        assert_eq!(style.max_height_viewport_frac, Some(1.0));
        assert_eq!(style.max_width_frac, Some(1.0));
        assert_eq!(style.width_frac, Some(0.05));
    }
}
