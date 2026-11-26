use crate::css_calc;

const ROOT_EM_PER_PX: f32 = 1.0 / 16.0;

/// An author's font size in reader-root em, using the inherited size for
/// percentages and font-relative units.
pub(crate) fn font_size(value: &str, inherited: f32) -> Option<f32> {
    Some(match value {
        "medium" => 1.0,
        "small" => 0.9,
        "x-small" => 0.75,
        "xx-small" => 0.6,
        "large" => 1.2,
        "x-large" => 1.5,
        "xx-large" => 2.0,
        "smaller" => inherited * 0.85,
        "larger" => inherited * 1.2,
        _ if is_math(value) => {
            return css_calc::evaluate(
                value,
                inherited,
                1.0,
                ROOT_EM_PER_PX,
                Some(inherited / 100.0),
                false,
            )
            .filter(|size| *size > 0.0);
        }
        _ => {
            let number = leading_number(value)?;
            match unit_of(value) {
                "em" => inherited * number,
                "%" => inherited * number / 100.0,
                "ch" | "ex" => inherited * number * 0.5,
                unit => root_relative(number, unit, false)?,
            }
        }
    })
}

/// Box lengths resolve their font-relative units against the computed font
/// size at the element. Viewport units follow the reader's 30-em convention.
pub(crate) fn length_em(
    value: &str,
    percent_base: f32,
    em_scale: f32,
    allow_negative: bool,
) -> Option<f32> {
    if value == "0" {
        return Some(0.0);
    }
    if is_math(value) {
        let percent = (percent_base > 0.0).then(|| percent_base * 30.0 / 100.0);
        let length = css_calc::evaluate(value, em_scale, 1.0, ROOT_EM_PER_PX, percent, false)?;
        return length
            .is_finite()
            .then_some(nonnegative_if_required(length, allow_negative));
    }
    let number = leading_number(value)?;
    if number < 0.0 && !allow_negative {
        return Some(0.0);
    }
    match unit_of(value) {
        "em" => Some(number * em_scale),
        "ch" | "ex" => Some(number * 0.5 * em_scale),
        "%" => (percent_base > 0.0).then(|| number / 100.0 * percent_base * 30.0),
        unit => root_relative(number, unit, true),
    }
}

#[derive(Debug, PartialEq)]
pub(crate) enum LineHeight {
    Number(f32),
    AbsoluteEm(f32),
}

/// Retain whether descendants inherit a unitless multiplier or a height
/// computed against the declaring element's font size.
pub(crate) fn line_height(value: &str, font_size_em: f32) -> Option<LineHeight> {
    if is_math(value) {
        let length = has_line_height_unit(value);
        let (em, percent) = if length {
            (font_size_em, Some(font_size_em / 100.0))
        } else {
            (1.0, None)
        };
        let result = css_calc::evaluate(value, em, 1.0, ROOT_EM_PER_PX, percent, !length)?;
        return (result.is_finite() && result > 0.0).then_some(if length {
            LineHeight::AbsoluteEm(result)
        } else {
            LineHeight::Number(result)
        });
    }

    let number = leading_number(value)?;
    if number <= 0.0 {
        return None;
    }
    let height = match unit_of(value) {
        "" => return Some(LineHeight::Number(number)),
        "%" => font_size_em * number / 100.0,
        "em" => font_size_em * number,
        "ch" | "ex" => font_size_em * number * 0.5,
        unit => root_relative(number, unit, true)?,
    };
    height.is_finite().then_some(LineHeight::AbsoluteEm(height))
}

fn root_relative(number: f32, unit: &str, allow_quarter_millimeter: bool) -> Option<f32> {
    Some(match unit {
        "rem" | "pc" => number,
        "px" => number / 16.0,
        "pt" => number / 12.0,
        "in" => number * 6.0,
        "cm" => number * 2.3622,
        "mm" => number * 0.23622,
        "q" if allow_quarter_millimeter => number * 0.059055,
        "vw" | "vmin" => number * 0.30,
        "vh" | "vmax" => number * 0.50,
        _ => return None,
    })
}

fn nonnegative_if_required(value: f32, allow_negative: bool) -> f32 {
    if value < 0.0 && !allow_negative {
        0.0
    } else {
        value
    }
}

fn is_math(value: &str) -> bool {
    ["calc(", "min(", "max(", "clamp("]
        .iter()
        .any(|prefix| value.starts_with(prefix))
}

fn unit_of(value: &str) -> &str {
    value
        .char_indices()
        .rev()
        .find(|&(_, ch)| !is_unit_character(ch))
        .map_or(value, |(index, ch)| &value[index + ch.len_utf8()..])
}

fn is_unit_character(ch: char) -> bool {
    // The original scanner works on UTF-16 code units. Recognized units
    // are ASCII, but any trailing letter can obstruct a supported suffix.
    ch == '%' || (ch.len_utf16() == 1 && ch.is_alphabetic())
}

/// The first finite decimal match of the reader's numeric scanner. It
/// accepts leading-dot values and does not consume exponents.
pub(crate) fn leading_number(value: &str) -> Option<f32> {
    let bytes = value.as_bytes();
    for (start, byte) in bytes.iter().copied().enumerate() {
        let body = start + usize::from(byte == b'-');
        let first = bytes.get(body).copied()?;
        let starts_decimal = first == b'.' && bytes.get(body + 1).is_some_and(u8::is_ascii_digit);
        if !(first.is_ascii_digit() || starts_decimal) {
            continue;
        }
        let mut end = body + ascii_digits(&bytes[body..]);
        if bytes.get(end) == Some(&b'.') && bytes.get(end + 1).is_some_and(u8::is_ascii_digit) {
            end += 1 + ascii_digits(&bytes[end + 1..]);
        }
        return value[start..end]
            .parse::<f32>()
            .ok()
            .filter(|number| number.is_finite());
    }
    None
}

fn ascii_digits(bytes: &[u8]) -> usize {
    bytes
        .iter()
        .take_while(|byte| byte.is_ascii_digit())
        .count()
}

fn has_line_height_unit(value: &str) -> bool {
    const UNITS: [&[u8]; 15] = [
        b"rem", b"em", b"px", b"pt", b"pc", b"in", b"cm", b"mm", b"q", b"ch", b"ex", b"vw", b"vh",
        b"vmin", b"vmax",
    ];
    let bytes = value.as_bytes();
    if bytes.contains(&b'%') {
        return true;
    }
    bytes.iter().copied().enumerate().any(|(index, ch)| {
        if !ch.is_ascii_digit() && ch != b'.' {
            return false;
        }
        let tail = &bytes[index + 1..];
        let end = tail
            .iter()
            .take_while(|byte| byte.is_ascii_alphabetic())
            .take(5)
            .count();
        let unit = &tail[..end];
        tail.get(end).is_none_or(|byte| {
            matches!(
                byte,
                b'\t'..=b'\r' | b' ' | b')' | b',' | b'*' | b'/' | b'+' | b'-'
            )
        }) && UNITS
            .iter()
            .any(|expected| unit.eq_ignore_ascii_case(expected))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn leading_decimal_uses_the_first_match() {
        assert_eq!(leading_number("‰-.25em"), Some(-0.25));
        assert_eq!(leading_number("prefix +12.5e-3"), Some(12.5));
        assert_eq!(leading_number(". then .5"), Some(0.5));
        assert_eq!(leading_number("4."), Some(4.0));
        assert_eq!(leading_number("无１２"), None);
        assert_eq!(leading_number(&format!("{} 2", "9".repeat(80))), None);
    }

    #[test]
    fn font_size_resolves_lengths_against_the_inherited_font() {
        assert_eq!(font_size("larger", 2.0), Some(2.4));
        assert_eq!(font_size(".5em", 2.0), Some(1.0));
        assert_eq!(font_size("150%", 2.0), Some(3.0));
        assert_eq!(font_size("18pt", 2.0), Some(1.5));
        assert_eq!(font_size("calc(75% + 8px)", 2.0), Some(2.0));
        assert_eq!(font_size("min(1em, 120%)", 2.0), Some(2.0));
        assert_eq!(font_size("calc(0em)", 2.0), None);
        assert_eq!(font_size("0", 2.0), None);
        assert_eq!(font_size("1q", 2.0), None);
    }

    #[test]
    fn box_lengths_keep_distinct_relative_bases() {
        assert_eq!(length_em(".25ch", 0.0, 2.0, false), Some(0.25));
        assert_eq!(length_em("1q", 0.0, 2.0, false), Some(0.059055));
        assert_eq!(length_em("50%", 2.0, 10.0, false), Some(30.0));
        assert_eq!(length_em("calc(50% + 1em)", 2.0, 2.0, false), Some(32.0));
        assert_eq!(length_em("calc(50% + 1em)", 0.0, 2.0, false), None);
        assert_eq!(length_em("-2em", 0.0, 2.0, false), Some(0.0));
        assert_eq!(length_em("calc(-2em)", 0.0, 2.0, false), Some(0.0));
        assert_eq!(length_em("-2em", 0.0, 2.0, true), Some(-4.0));
        assert_eq!(length_em("0", 0.0, 2.0, false), Some(0.0));
    }

    #[test]
    fn line_height_preserves_the_kind_needed_for_inheritance() {
        assert_eq!(line_height("1.5", 2.0), Some(LineHeight::Number(1.5)));
        assert_eq!(
            line_height("min(1.2, 1.5)", 2.0),
            Some(LineHeight::Number(1.2))
        );
        assert_eq!(line_height("150%", 2.0), Some(LineHeight::AbsoluteEm(3.0)));
        assert_eq!(
            line_height("calc(100% + 8px)", 2.0),
            Some(LineHeight::AbsoluteEm(2.5)),
        );
        assert_eq!(line_height("24px", 2.0), Some(LineHeight::AbsoluteEm(1.5)));
        assert_eq!(line_height("calc(0em)", 2.0), None);
        assert_eq!(line_height("calc(0.5 + 1em)", 2.0), None);
        assert_eq!(line_height("2em", f32::MAX), None);
    }

    #[test]
    fn line_height_math_detection_keeps_unit_boundaries() {
        assert!(has_line_height_unit("calc(.5em + 1q)"));
        assert!(has_line_height_unit("calc(2VW\u{b}*3)"));
        assert!(has_line_height_unit("calc(1vmin+2)"));
        assert!(has_line_height_unit("calc(3%)"));
        assert!(!has_line_height_unit("calc(3emphasis)"));
        assert!(!has_line_height_unit("calc(2em(3))"));
        assert!(!has_line_height_unit("calc(2mm\u{a0}*3)"));
    }
}
