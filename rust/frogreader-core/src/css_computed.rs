//! Complete reader computed-style assembly from the winning author declarations.
use crate::{
    css_color,
    css_computed_boxes::Boxes,
    css_computed_fonts,
    css_computed_model::{ComputedStyle, Declaration, Element},
    css_lengths, css_shorthand, css_variables,
};
use std::collections::HashMap;
use std::sync::Arc;

const BLACK_ARGB: i32 = 0xff00_0000_u32 as i32;

/// Resolve inherited values, HTML presentation hints, and author declarations
/// in the same order used by the reader's block and inline style consumers.
pub(crate) fn resolve(
    element: &Element,
    parent: Option<&ComputedStyle>,
    declarations: &[Declaration],
) -> ComputedStyle {
    let (custom_props, substituted) = variable_values(parent, declarations);
    let raw_values: Vec<_> = declarations
        .iter()
        .map(|declaration| {
            let raw = if declaration.value.contains("var(") {
                substituted
                    .get(declaration.value.as_str())
                    .and_then(Option::as_deref)?
            } else {
                &declaration.value
            };
            Some(raw.trim_matches(reader_whitespace).to_lowercase())
        })
        .collect();
    let values: Vec<_> = declarations
        .iter()
        .zip(&raw_values)
        .map(|(declaration, raw)| {
            let normalized = if matches!(
                declaration.property.as_str(),
                "font-variation-settings" | "font-feature-settings"
            ) {
                let raw = if declaration.value.contains("var(") {
                    substituted
                        .get(declaration.value.as_str())
                        .and_then(Option::as_deref)?
                } else {
                    &declaration.value
                };
                raw.trim_matches(reader_whitespace).to_owned()
            } else {
                raw.as_ref()?.clone()
            };
            if declaration.expansion == 0 {
                Some(normalized)
            } else {
                css_shorthand::expand(declaration.expansion, &normalized)
            }
        })
        .collect();
    let raw_value = |property| lookup(declarations, &raw_values, property);
    let value = |property| lookup(declarations, &values, property);
    let tag = if element.pseudo { "" } else { &element.tag };

    let inherited_size = parent.map_or(1.0, |parent| parent.font_size_em);
    let declared_size = value("font-size").and_then(|value| match value {
        "inherit" | "unset" => Some(inherited_size),
        "initial" => Some(1.0),
        _ => css_lengths::font_size(value, inherited_size),
    });
    let font_size_em = declared_size
        .unwrap_or_else(|| inherited_size * tag_font_scale(tag))
        .clamp(0.5, 3.0);
    let mut style = ComputedStyle {
        custom_props,
        font_size_em,
        text_indent_em: parent.and_then(|parent| parent.text_indent_em),
        hidden: parent.is_some_and(|parent| parent.hidden),
        underline: parent.is_some_and(|parent| parent.underline),
        strike: parent.is_some_and(|parent| parent.strike),
        hyphens_auto: parent.and_then(|parent| parent.hyphens_auto),
        direction: parent.and_then(|parent| parent.direction.clone()),
        list_style_type: parent.and_then(|parent| parent.list_style_type.clone()),
        flex_direction: "row".into(),
        flex_wrap: "nowrap".into(),
        padding_specified: declarations
            .iter()
            .any(|declaration| declaration.property.starts_with("padding-")),
        border_specified: declarations.iter().any(|declaration| {
            [
                "border-top-",
                "border-right-",
                "border-bottom-",
                "border-left-",
            ]
            .iter()
            .any(|prefix| declaration.property.starts_with(prefix))
        }),
        margin_top_specified: declarations
            .iter()
            .any(|declaration| declaration.property == "margin-top"),
        margin_bottom_specified: declarations
            .iter()
            .any(|declaration| declaration.property == "margin-bottom"),
        ..ComputedStyle::default()
    };
    css_computed_fonts::inherit(&mut style, parent);
    style.text_align = alignment_hint(tag, &element.align)
        .map(str::to_owned)
        .or_else(|| {
            if tag == "center" {
                Some("center".into())
            } else {
                parent.and_then(|parent| parent.text_align.clone())
            }
        });
    let direction = element
        .direction
        .trim_matches(reader_whitespace)
        .to_lowercase();
    if matches!(direction.as_str(), "ltr" | "rtl" | "auto") {
        style.direction = Some(direction);
    }
    match raw_value("direction") {
        Some(value @ ("ltr" | "rtl")) => style.direction = Some(value.into()),
        Some("initial" | "revert" | "revert-layer") => style.direction = Some("ltr".into()),
        Some("inherit" | "unset") => {
            style.direction = parent.and_then(|parent| parent.direction.clone())
        }
        _ => {}
    }
    let has_logical_box = declarations.iter().any(|declaration| {
        matches!(
            declaration.property.as_str(),
            "margin-inline-start"
                | "margin-inline-end"
                | "padding-inline-start"
                | "padding-inline-end"
        )
    });
    let rtl = match style.direction.as_deref() {
        Some("rtl") => true,
        Some("auto") => has_logical_box && element.first_strong_rtl,
        _ => false,
    };
    let declared_foreground = raw_value("color").or_else(|| {
        if element.pseudo {
            None
        } else if !element.foreground.chars().all(reader_whitespace) {
            Some(element.foreground.as_str())
        } else if element.tag == "body" && !element.body_foreground.chars().all(reader_whitespace) {
            Some(element.body_foreground.as_str())
        } else {
            None
        }
    });
    let inherited_foreground = parent.and_then(|parent| parent.foreground_color_argb);
    style.foreground_color_argb = declared_foreground
        .and_then(|value| foreground(value, inherited_foreground))
        .or(inherited_foreground);
    let declared_background = value("background-color").or_else(|| {
        (!element.pseudo && !element.background.chars().all(reader_whitespace))
            .then_some(element.background.as_str())
    });
    style.background_color_argb = declared_background.and_then(|value| {
        background(
            value,
            parent.and_then(|parent| parent.background_color_argb),
            style.foreground_color_argb,
        )
    });
    style.background_uses_current_color = match declared_background {
        Some("currentcolor") => style.foreground_color_argb.is_none(),
        Some("inherit") => parent.is_some_and(|parent| parent.background_uses_current_color),
        _ => false,
    };

    // Semantic defaults apply before authored declarations. Reverting a font
    // property therefore restores the semantic value of this particular tag.
    match tag {
        "i" | "em" | "dfn" | "var" | "cite" => style.italic = Some(true),
        "b" | "strong" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => style.font_weight = Some(700),
        "u" | "ins" => style.underline = true,
        "s" | "strike" | "del" => style.strike = true,
        "sup" => style.super_script = true,
        "sub" => style.sub_script = true,
        "code" | "kbd" | "samp" | "tt" | "pre" => style.monospace = true,
        _ => {}
    }
    let semantic_weight = style.font_weight.unwrap_or(400);
    let semantic_italic = style.italic.unwrap_or(false);
    let mut boxes = Boxes::new(&style);
    for (declaration, value) in declarations.iter().zip(&values) {
        if declaration.property.starts_with("--") {
            continue;
        }
        let Some(value) = value.as_deref() else {
            continue;
        };
        let property = declaration.property.as_str();
        if css_computed_fonts::apply(
            &mut style,
            parent,
            property,
            value,
            semantic_weight,
            semantic_italic,
        ) || boxes.apply(&mut style, parent, property, value, rtl)
        {
            continue;
        }
        match property {
            // These were resolved before iteration so source order cannot
            // change currentColor, logical axes, or relative size bases.
            "color" | "background-color" => {}
            "hyphens" | "-webkit-hyphens" | "-epub-hyphens" | "-moz-hyphens" => match value {
                "auto" => style.hyphens_auto = Some(true),
                "none" | "manual" => style.hyphens_auto = Some(false),
                _ => {}
            },
            "direction" if matches!(value, "ltr" | "rtl") => style.direction = Some(value.into()),
            "unicode-bidi" => match value {
                "normal" | "embed" | "isolate" | "bidi-override" | "isolate-override"
                | "plaintext" => style.unicode_bidi = Some(value.into()),
                "inherit" => {
                    style.unicode_bidi = parent.and_then(|parent| parent.unicode_bidi.clone())
                }
                "initial" | "unset" | "revert" | "revert-layer" => style.unicode_bidi = None,
                _ => {}
            },
            "adobe-hyphenate" => match value {
                "explicit" | "none" => style.hyphens_auto = Some(false),
                "auto" => style.hyphens_auto = Some(true),
                _ => {}
            },
            "text-align" => apply_alignment(&mut style, parent, value),
            "text-indent" => {
                if matches!(value, "initial" | "revert" | "revert-layer") {
                    style.text_indent_em = Some(0.0);
                } else if let Some(indent) =
                    css_lengths::length_em(value, 0.30, style.font_size_em, true)
                {
                    style.text_indent_em = Some(indent.clamp(-8.0, 8.0));
                }
            }
            "display" => match value {
                "inherit" => style.display = parent.and_then(|parent| parent.display.clone()),
                "initial" | "unset" | "revert" | "revert-layer" => style.display = None,
                _ => {
                    style.display = Some(value.into());
                    style.hidden |= value == "none";
                }
            },
            "flex-direction" => match value {
                "row" | "row-reverse" | "column" | "column-reverse" => {
                    style.flex_direction = value.into()
                }
                "inherit" => {
                    style.flex_direction = parent
                        .map(|parent| parent.flex_direction.as_str())
                        .unwrap_or("row")
                        .into()
                }
                "initial" | "unset" | "revert" | "revert-layer" => {
                    style.flex_direction = "row".into()
                }
                _ => {}
            },
            "flex-wrap" => match value {
                "nowrap" | "wrap" | "wrap-reverse" => style.flex_wrap = value.into(),
                "inherit" => {
                    style.flex_wrap = parent
                        .map(|parent| parent.flex_wrap.as_str())
                        .unwrap_or("nowrap")
                        .into()
                }
                "initial" | "unset" | "revert" | "revert-layer" => {
                    style.flex_wrap = "nowrap".into()
                }
                _ => {}
            },
            "justify-content" => match value {
                "normal" | "start" | "end" | "center" | "flex-start" | "flex-end" | "left"
                | "right" | "space-between" | "space-around" | "space-evenly" | "stretch" => {
                    style.justify_content = Some(value.into())
                }
                "inherit" => {
                    style.justify_content = parent.and_then(|parent| parent.justify_content.clone())
                }
                "initial" | "unset" | "revert" | "revert-layer" => style.justify_content = None,
                _ => {}
            },
            "align-items" => match value {
                "normal" | "start" | "end" | "center" | "flex-start" | "flex-end" | "baseline"
                | "stretch" => style.align_items = Some(value.into()),
                "inherit" => {
                    style.align_items = parent.and_then(|parent| parent.align_items.clone())
                }
                "initial" | "unset" | "revert" | "revert-layer" => style.align_items = None,
                _ => {}
            },
            "filter" => {
                let compact: String = value.chars().filter(|ch| !ascii_whitespace(*ch)).collect();
                match compact.as_str() {
                    "invert(100%)" | "invert(1)" => style.image_invert = true,
                    "none" | "initial" | "unset" | "revert" | "revert-layer" => {
                        style.image_invert = false
                    }
                    _ => {}
                }
            }
            "text-decoration" | "text-decoration-line" => {
                style.underline |= value.contains("underline");
                style.strike |= value.contains("line-through");
                if value == "none" {
                    style.underline = false;
                    style.strike = false;
                }
            }
            "vertical-align" => {
                if value.contains("super") {
                    style.super_script = true;
                } else if value.contains("sub") {
                    style.sub_script = true;
                }
            }
            "list-style-type" => {
                if let Some(value) = list_type(value) {
                    style.list_style_type = Some(value.into());
                }
            }
            "list-style" => {
                if let Some(value) = value.split(ascii_whitespace).find_map(list_type) {
                    style.list_style_type = Some(value.into());
                }
            }
            "page-break-before" | "break-before" => match value {
                "always" | "left" | "right" | "page" => style.page_break_before = true,
                "auto" | "avoid" => style.page_break_before = false,
                _ => {}
            },
            "page-break-after" | "break-after" => match value {
                "always" | "left" | "right" | "page" => style.page_break_after = true,
                "auto" | "avoid" | "initial" | "unset" | "revert" | "revert-layer" => {
                    style.page_break_after = false
                }
                _ => {}
            },
            "page-break-inside" | "break-inside" => match value {
                "avoid" | "avoid-page" | "avoid-column" | "avoid-region" => {
                    style.break_inside_avoid = true
                }
                "auto" | "initial" | "unset" | "revert" | "revert-layer" => {
                    style.break_inside_avoid = false
                }
                "inherit" => {
                    style.break_inside_avoid =
                        parent.is_some_and(|parent| parent.break_inside_avoid)
                }
                _ => {}
            },
            "float" => match value {
                "left" | "right" => style.float_side = Some(value.into()),
                "none" => style.float_side = None,
                _ => {}
            },
            "clear" => {
                style.clear = match value {
                    "left" | "right" | "both" => Some(value.into()),
                    "inline-start" => Some(if rtl { "right" } else { "left" }.into()),
                    "inline-end" => Some(if rtl { "left" } else { "right" }.into()),
                    "inherit" => parent.and_then(|parent| parent.clear.clone()),
                    _ => None,
                }
            }
            _ => {}
        }
    }

    if matches!(raw_value("position"), Some("absolute" | "fixed"))
        && ["left", "top"].iter().any(|property| {
            raw_value(property)
                .and_then(|value| css_lengths::length_em(value, 0.0, style.font_size_em, true))
                .is_some_and(|offset| offset < -100.0)
        })
    {
        style.hidden = true;
    }
    boxes.finish(&mut style);
    css_computed_fonts::finish(&mut style);
    style.center_content_vertically = style.display.as_deref() == Some("flex")
        && style.flex_direction == "column"
        && style.justify_content.as_deref() == Some("center")
        && style.min_height_viewport_frac.is_some();
    style
}

fn lookup<'a>(
    declarations: &[Declaration],
    values: &'a [Option<String>],
    property: &str,
) -> Option<&'a str> {
    declarations
        .iter()
        .position(|declaration| declaration.property == property)
        .and_then(|index| values[index].as_deref())
}

fn variable_values<'a>(
    parent: Option<&ComputedStyle>,
    declarations: &'a [Declaration],
) -> (Arc<css_variables::Scope>, HashMap<&'a str, Option<String>>) {
    let inherited = parent
        .map(|parent| Arc::clone(&parent.custom_props))
        .unwrap_or_default();
    let own: css_variables::Scope = declarations
        .iter()
        .filter(|declaration| declaration.property.starts_with("--"))
        .map(|declaration| (declaration.property.clone(), declaration.value.clone()))
        .collect();
    let mut indices = HashMap::new();
    let mut references = Vec::new();
    for declaration in declarations {
        if !declaration.property.starts_with("--") && declaration.value.contains("var(") {
            indices
                .entry(declaration.value.as_str())
                .or_insert_with(|| {
                    let index = references.len();
                    references.push(declaration.value.clone());
                    index
                });
        }
    }
    if own.is_empty() && references.is_empty() {
        return (inherited, HashMap::new());
    }
    let changes_scope = !own.is_empty();
    let (scope, values) = css_variables::resolve(inherited.as_ref().clone(), own, &references);
    let scope = if changes_scope {
        Arc::new(scope)
    } else {
        inherited
    };
    let substitutions = indices
        .into_iter()
        .map(|(source, index)| (source, values[index].clone()))
        .collect();
    (scope, substitutions)
}

fn tag_font_scale(tag: &str) -> f32 {
    match tag {
        "h1" => 1.50,
        "h2" => 1.32,
        "h3" => 1.18,
        "h4" => 1.10,
        "h5" => 1.04,
        "small" => 0.85,
        "big" => 1.2,
        "rt" => 0.5,
        _ => 1.0,
    }
}

fn alignment_hint<'a>(tag: &str, align: &'a str) -> Option<&'a str> {
    if !matches!(
        tag,
        "div" | "p" | "h1" | "h2" | "h3" | "h4" | "h5" | "h6" | "center"
    ) {
        return None;
    }
    let align = align.trim_matches(reader_whitespace);
    // The owning HTML attribute is matched case-insensitively, but the
    // returned CSS keyword always has its canonical lowercase spelling.
    ["left", "right", "center", "justify"]
        .into_iter()
        .find(|keyword| align.eq_ignore_ascii_case(keyword))
}

fn apply_alignment(style: &mut ComputedStyle, parent: Option<&ComputedStyle>, value: &str) {
    match value {
        "center" | "right" | "left" | "end" | "start" | "justify" => {
            style.text_align = Some(value.into())
        }
        "initial" | "revert" | "revert-layer" => style.text_align = Some("start".into()),
        "inherit" | "unset" => {
            style.text_align = Some(
                parent
                    .and_then(|parent| parent.text_align.as_deref())
                    .unwrap_or("start")
                    .into(),
            )
        }
        "match-parent" => {
            style.text_align = match parent {
                None => Some("start".into()),
                Some(parent) => match parent.text_align.as_deref().unwrap_or("start") {
                    "start" => Some(
                        if parent.direction.as_deref() == Some("rtl") {
                            "right"
                        } else {
                            "left"
                        }
                        .into(),
                    ),
                    "end" => Some(
                        if parent.direction.as_deref() == Some("rtl") {
                            "left"
                        } else {
                            "right"
                        }
                        .into(),
                    ),
                    _ => parent.text_align.clone(),
                },
            }
        }
        _ => {}
    }
}

fn foreground(value: &str, inherited: Option<i32>) -> Option<i32> {
    match value
        .trim_matches(reader_whitespace)
        .to_lowercase()
        .as_str()
    {
        "inherit" | "unset" | "currentcolor" => inherited,
        "initial" | "revert" | "revert-layer" => Some(BLACK_ARGB),
        _ => css_color::parse_color(value).map(|color| color as i32),
    }
}

fn background(value: &str, inherited: Option<i32>, current: Option<i32>) -> Option<i32> {
    match value
        .trim_matches(reader_whitespace)
        .to_lowercase()
        .as_str()
    {
        "inherit" => inherited,
        "currentcolor" => current,
        "initial" | "unset" | "revert" | "revert-layer" => None,
        _ => css_color::parse_color(value).map(|color| color as i32),
    }
}

fn list_type(value: &str) -> Option<&str> {
    matches!(
        value,
        "disc"
            | "circle"
            | "square"
            | "none"
            | "decimal"
            | "lower-alpha"
            | "lower-latin"
            | "upper-alpha"
            | "upper-latin"
            | "lower-roman"
            | "upper-roman"
    )
    .then_some(value)
}

fn reader_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

fn ascii_whitespace(ch: char) -> bool {
    matches!(ch, ' ' | '\t'..='\r')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn element(tag: &str) -> Element {
        Element {
            tag: tag.into(),
            ..Element::default()
        }
    }

    fn declarations(values: &[(&str, &str)]) -> Vec<Declaration> {
        values
            .iter()
            .map(|&(property, value)| Declaration {
                property: property.into(),
                value: value.into(),
                expansion: 0,
            })
            .collect()
    }

    #[test]
    fn semantic_defaults_and_inherited_alignment_obey_author_resets() {
        let mut body = resolve(
            &element("body"),
            None,
            &declarations(&[
                ("direction", "rtl"),
                ("text-align", "start"),
                ("font-size", "2em"),
            ]),
        );
        body.italic = Some(true);
        let mut heading = element("h1");
        heading.align = "CENTER".into();
        let style = resolve(
            &heading,
            Some(&body),
            &declarations(&[
                ("font-size", "inherit"),
                ("font-weight", "revert"),
                ("font-style", "normal"),
                ("text-align", "match-parent"),
            ]),
        );
        assert_eq!(style.font_size_em, 2.0);
        assert_eq!(style.font_weight, Some(700));
        assert_eq!(style.italic, Some(false));
        assert_eq!(style.text_align.as_deref(), Some("right"));
        let pseudo = resolve(
            &Element {
                pseudo: true,
                ..element("h1")
            },
            Some(&body),
            &[],
        );
        assert_eq!(pseudo.font_size_em, 2.0);
        assert_eq!(pseudo.font_weight, body.font_weight);
    }

    #[test]
    fn foreground_is_final_before_current_color_and_parent_only_custom_values() {
        let body = resolve(&element("body"), None, &declarations(&[("--tone", "red")]));
        let style = resolve(
            &element("p"),
            Some(&body),
            &declarations(&[
                ("--stroke", "var(--tone)"),
                ("--tone", "blue"),
                ("background-color", "currentcolor"),
                ("color", "var(--tone)"),
                ("border-top-color", "var(--stroke)"),
                ("border-top-style", "solid"),
            ]),
        );
        assert_eq!(style.foreground_color_argb, Some(0xff00_00ff_u32 as i32));
        assert_eq!(style.background_color_argb, style.foreground_color_argb);
        assert_eq!(style.border_top.unwrap().color_argb, 0xffff_0000_u32 as i32);
        assert_eq!(style.custom_props[0], ("--tone".into(), "blue".into()));
        assert_eq!(style.custom_props[1], ("--stroke".into(), "red".into()));
    }

    #[test]
    fn unchanged_variable_scopes_are_shared_and_references_are_resolved_once() {
        let parent = resolve(
            &element("body"),
            None,
            &declarations(&[("--size", "2em"), ("--cycle", "var(--cycle)")]),
        );
        let unchanged = resolve(&element("span"), Some(&parent), &[]);
        assert!(Arc::ptr_eq(&parent.custom_props, &unchanged.custom_props));

        let declared = declarations(&[
            ("font-size", "var(--size)"),
            ("padding-top", "var(--size)"),
            ("color", "var(--cycle, red)"),
        ]);
        let (scope, substitutions) = variable_values(Some(&parent), &declared);
        assert!(Arc::ptr_eq(&parent.custom_props, &scope));
        assert_eq!(substitutions.len(), 2);
        assert_eq!(
            substitutions.get("var(--size)").and_then(Option::as_deref),
            Some("2em"),
        );
        assert_eq!(substitutions.get("var(--cycle, red)"), Some(&None));

        let child = resolve(&element("p"), Some(&parent), &declared);
        assert!(Arc::ptr_eq(&parent.custom_props, &child.custom_props));
        assert_eq!(child.font_size_em, 2.0);
        assert_eq!(child.padding_top_em, 4.0);
        assert_eq!(child.foreground_color_argb, None);

        let overridden = resolve(
            &element("p"),
            Some(&parent),
            &declarations(&[("--size", "3em")]),
        );
        assert!(!Arc::ptr_eq(&parent.custom_props, &overridden.custom_props));
        assert_eq!(parent.custom_props[0].1, "2em");
        assert_eq!(overridden.custom_props[0].1, "3em");
    }

    #[test]
    fn visibility_flow_breaks_and_positioning_keep_their_inheritance_rules() {
        let parent = resolve(
            &element("section"),
            None,
            &declarations(&[
                ("display", "none"),
                ("visibility", "hidden"),
                ("break-inside", "avoid"),
                ("text-decoration", "underline"),
                ("page-break-after", "always"),
            ]),
        );
        let child = resolve(
            &element("p"),
            Some(&parent),
            &declarations(&[
                ("display", "block"),
                ("visibility", "visible"),
                ("break-inside", "inherit"),
                ("text-decoration", "none"),
                ("page-break-after", "inherit"),
            ]),
        );
        assert!(child.hidden);
        assert!(!child.visibility_hidden);
        assert!(child.break_inside_avoid);
        assert!(!child.underline);
        assert!(!child.page_break_after);
        let offscreen = resolve(
            &element("p"),
            None,
            &declarations(&[("position", "absolute"), ("left", "-9999px")]),
        );
        assert!(offscreen.hidden);
    }

    #[test]
    fn logical_sides_use_the_final_direction_and_clear_competing_physical_values() {
        let mut paragraph = element("p");
        paragraph.direction = "auto".into();
        paragraph.first_strong_rtl = true;
        let style = resolve(
            &paragraph,
            None,
            &declarations(&[
                ("margin-right", "4em"),
                ("margin-inline-start", "2em"),
                ("clear", "inline-start"),
            ]),
        );
        assert_eq!(style.margin_end_em, 0.0);
        assert_eq!(style.margin_inline_start_em, 2.0);
        assert_eq!(style.clear.as_deref(), Some("right"));
    }
}
