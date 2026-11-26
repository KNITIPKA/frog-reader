use std::borrow::Cow;
use std::collections::HashMap;

const MAX_REPLACEMENTS: usize = 9;
// A single expansion cannot exceed the declaration wire's size limit.
const MAX_VALUE_BYTES: usize = 16 * 1024 * 1024;
const MAX_REWRITE_BYTES: usize = 32 * 1024 * 1024;

pub(crate) type Scope = Vec<(String, String)>;

/// Resolve own properties against the inherited scope, then use the merged
/// scope to resolve the requested declarations. The inputs are ordered maps.
pub(crate) fn resolve(
    mut parent: Scope,
    own: Scope,
    values: &[String],
) -> (Scope, Vec<Option<String>>) {
    let mut rewrite_bytes = MAX_REWRITE_BYTES;
    if !own.is_empty() {
        let inherited: HashMap<_, _> = parent
            .iter()
            .enumerate()
            .map(|(index, (name, value))| (name.as_str(), (index, value.as_str())))
            .collect();
        let updates: Vec<_> = own
            .into_iter()
            .map(|(name, value)| {
                let position = inherited.get(name.as_str()).map(|&(index, _)| index);
                let resolved = substitute(
                    &value,
                    |name| inherited.get(name).map(|&(_, value)| value),
                    &mut rewrite_bytes,
                );
                let value = match resolved {
                    Some(Cow::Owned(resolved)) => resolved,
                    _ => value,
                };
                (position, name, value)
            })
            .collect();
        for (position, name, value) in updates {
            if let Some(index) = position {
                parent[index].1 = value;
            } else {
                parent.push((name, value));
            }
        }
    }
    let resolved = if values.is_empty() {
        Vec::new()
    } else {
        let props: HashMap<_, _> = parent
            .iter()
            .map(|(name, value)| (name.as_str(), value.as_str()))
            .collect();
        values
            .iter()
            .map(|value| {
                substitute(value, |name| props.get(name).copied(), &mut rewrite_bytes)
                    .map(Cow::into_owned)
            })
            .collect()
    };
    (parent, resolved)
}

fn substitute<'value, 'scope>(
    value: &'value str,
    lookup: impl Fn(&str) -> Option<&'scope str>,
    rewrite_bytes: &mut usize,
) -> Option<Cow<'value, str>> {
    let mut value = Cow::Borrowed(value);
    let mut replacements = 0;
    while let Some(start) = value.find("var(") {
        if replacements == MAX_REPLACEMENTS {
            return None;
        }
        let argument_start = start + 4;
        let (argument_end, name, fallback) = arguments(&value[argument_start..])?;
        let name = name.trim_matches(reader_whitespace).to_lowercase();
        // A defined reference wins over its fallback even when its eventual
        // expansion is invalid or cyclic.
        let replacement = lookup(&name).or(fallback)?;
        let end = argument_start + argument_end + 1;
        let bytes = (value.len() - (end - start)).checked_add(replacement.len())?;
        if bytes > MAX_VALUE_BYTES {
            return None;
        }
        // Charge intermediate rewrites too. An invalid value can still
        // generate a large intermediate expansion before reaching a cycle.
        *rewrite_bytes = rewrite_bytes.checked_sub(bytes)?;
        let mut expanded = String::with_capacity(bytes);
        expanded.push_str(&value[..start]);
        expanded.push_str(replacement);
        expanded.push_str(&value[end..]);
        value = Cow::Owned(expanded);
        replacements += 1;
    }
    Some(value)
}

/// The input starts after the opening parenthesis. Preserve the reader's
/// textual substitution, including counting parentheses inside strings.
fn arguments(input: &str) -> Option<(usize, &str, Option<&str>)> {
    let mut depth = 0;
    let mut comma = None;
    for (index, byte) in input.bytes().enumerate() {
        match byte {
            b'(' => depth += 1,
            b')' if depth == 0 => {
                let name = &input[..comma.unwrap_or(index)];
                let fallback =
                    comma.map(|comma| input[comma + 1..index].trim_matches(reader_whitespace));
                return Some((index, name, fallback));
            }
            b')' => depth -= 1,
            b',' if depth == 0 && comma.is_none() => comma = Some(index),
            _ => {}
        }
    }
    None
}

// Kotlin Char.isWhitespace on the JVM also includes these separators and
// excludes NEL.
fn reader_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scope(entries: &[(&str, &str)]) -> Scope {
        entries
            .iter()
            .map(|&(name, value)| (name.into(), value.into()))
            .collect()
    }

    #[test]
    fn overrides_preserve_inherited_positions_and_pre_resolve_in_the_parent() {
        let parent = scope(&[("--base", "2em"), ("--inherited", "blue")]);
        let own = scope(&[
            ("--base", "3em"),
            ("--alias", "var(--BASE)"),
            ("--pending", "var(--new)"),
            ("--new", "4em"),
        ]);
        let values = ["var(--base)", "var(--alias)", "var(--pending)"].map(String::from);
        let (computed, resolved) = resolve(parent, own, &values);
        assert_eq!(
            computed,
            scope(&[
                ("--base", "3em"),
                ("--inherited", "blue"),
                ("--alias", "2em"),
                ("--pending", "var(--new)"),
                ("--new", "4em"),
            ]),
        );
        assert_eq!(resolved, ["3em", "2em", "4em"].map(|s| Some(s.into())));
    }

    #[test]
    fn fallbacks_keep_nested_commas_whitespace_and_literal_case() {
        let parent = scope(&[("--height", "2em")]);
        let values = [
            "var(--missing, calc(var(\u{a0}--HEIGHT\u{a0}) + min(1em, 2em))) ",
            "VAR(--height)",
            "before var(--missing, ) after",
        ]
        .map(String::from);
        let (_, resolved) = resolve(parent, vec![], &values);
        assert_eq!(
            resolved,
            [
                "calc(2em + min(1em, 2em)) ",
                "VAR(--height)",
                "before  after"
            ]
            .map(|s| Some(s.into())),
        );
    }

    #[test]
    fn defined_cycles_drop_values_without_using_their_fallbacks() {
        let own = scope(&[("--a", "var(--b)"), ("--b", "var(--a)")]);
        let values = ["var(--a, 3em)", "var(--unclosed", "var(--absent, green)"].map(String::from);
        let (computed, resolved) = resolve(vec![], own.clone(), &values);
        assert_eq!(computed, own);
        assert_eq!(resolved, [None, None, Some("green".into())]);
    }

    #[test]
    fn the_ninth_substitution_can_finish_a_value() {
        let values = ["var(--x)".repeat(9), "var(--x)".repeat(10)];
        let (_, resolved) = resolve(scope(&[("--x", "x")]), vec![], &values);
        assert_eq!(resolved, [Some("xxxxxxxxx".into()), None]);
    }

    #[test]
    fn limits_apply_before_allocating_an_intermediate_value() {
        let mut allowance = 3;
        assert!(substitute("var(--large)!", |_| Some("big"), &mut allowance).is_none());
        assert_eq!(allowance, 3);
        assert_eq!(
            substitute("var(--small)", |_| Some("ok"), &mut allowance).as_deref(),
            Some("ok"),
        );
        assert_eq!(allowance, 1);
    }
}
