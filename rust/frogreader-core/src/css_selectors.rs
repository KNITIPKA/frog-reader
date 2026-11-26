//! Lowers Servo's selector syntax to the reader's bounded selector matcher.
use crate::css_match::{Attr, AttrOp, Combinator, Compound, Pseudo, Simple};
use cssparser::{
    serialize_identifier, serialize_string, CowRcStr, Delimiter, ParseError, Parser as CssParser,
    ToCss, Token,
};
use precomputed_hash::PrecomputedHash;
use selectors::attr::{AttrSelectorOperator, NamespaceConstraint, ParsedAttrSelectorOperation};
use selectors::parser::{
    Combinator as ServoCombinator, Component, NonTSPseudoClass, NthSelectorData, NthType,
    ParseRelative, Parser, PseudoElement, Selector, SelectorImpl, SelectorList,
    SelectorParseErrorKind,
};
use std::borrow::{Borrow, Cow};
use std::collections::HashMap;
use std::fmt;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::ops::Range;

const MAX_MEMBERS: usize = 256;
const MAX_MEMBER_UTF16: usize = 16_384;
const MAX_COMPOUNDS: usize = 64;
const MAX_COMPONENTS: usize = 64;
const MAX_COMPILED_BYTES: usize = 128 * 1024 * 1024;

pub(crate) struct CompiledSelector {
    pub(crate) chain: Vec<Compound>,
    pub(crate) specificity: i32,
    pub(crate) pool: i32,
}

/// Recover individual supported members without making the whole rule depend
/// on a sibling the renderer cannot represent.
pub(crate) fn compile(
    text: &str,
    namespaces: &HashMap<String, String>,
    limit: usize,
) -> Vec<CompiledSelector> {
    let mut input = CssParser::new(text);
    let parser = BookParser { namespaces };
    let mut compiled = Vec::new();
    // Reserve at most the existing rule wire ceiling for lowered namespace URIs,
    // including when many tiny selectors repeat a large namespace definition.
    let mut namespace_bytes = MAX_COMPILED_BYTES;
    for _ in 0..MAX_MEMBERS {
        if compiled.len() == limit || input.is_exhausted() {
            break;
        }
        let Ok(member) = input.parse_until_after(Delimiter::Comma, |input| {
            let start = input.position();
            consume_tokens(input);
            Ok::<_, ParseError<()>>(input.slice_from(start))
        }) else {
            continue;
        };
        let member = member.trim_matches(selector_whitespace);
        if member.is_empty() || member.encode_utf16().count() > MAX_MEMBER_UTF16 {
            continue;
        }
        let Some(prepared) = prepare_member(member) else {
            continue;
        };
        let mut source = CssParser::new(&prepared);
        // At most :has(:not([attr])) on the supported path.
        source.set_nested_block_limit(3);
        let Ok(list) = SelectorList::parse(&parser, &mut source, ParseRelative::No) else {
            continue;
        };
        let [selector] = list.slice() else { continue };
        let Some(mut bytes_left) = namespace_bytes.checked_sub(member.len().saturating_mul(8))
        else {
            continue;
        };
        if let Some((chain, specificity, pool)) =
            lower(selector, LoweringScope::Rule, namespaces, &mut bytes_left)
        {
            namespace_bytes = bytes_left;
            compiled.push(CompiledSelector {
                chain,
                specificity: specificity.rank(),
                pool,
            });
        }
    }
    compiled
}

fn selector_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

fn consume_tokens(input: &mut CssParser<'_>) {
    while input.next_including_whitespace_and_comments().is_ok() {}
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct Atom(String);

impl From<&str> for Atom {
    fn from(text: &str) -> Self {
        Atom(text.to_owned())
    }
}

impl Borrow<str> for Atom {
    fn borrow(&self) -> &str {
        &self.0
    }
}

impl ToCss for Atom {
    fn to_css<W: fmt::Write>(&self, dest: &mut W) -> fmt::Result {
        serialize_identifier(&self.0, dest)
    }
}

impl PrecomputedHash for Atom {
    fn precomputed_hash(&self) -> u32 {
        let mut hasher = DefaultHasher::new();
        self.0.hash(&mut hasher);
        hasher.finish() as u32
    }
}

#[derive(Clone, Debug)]
struct BookSelector;

impl SelectorImpl for BookSelector {
    type ExtraMatchingData<'a> = ();
    type AttrValue = Atom;
    type Identifier = Atom;
    type LocalName = Atom;
    type NamespaceUrl = Atom;
    type NamespacePrefix = Atom;
    type BorrowedNamespaceUrl = str;
    type BorrowedLocalName = str;
    type NonTSPseudoClass = UnsupportedPseudo;
    type PseudoElement = BookPseudoElement;
}

#[derive(Clone, Eq, PartialEq)]
enum UnsupportedPseudo {}

impl NonTSPseudoClass for UnsupportedPseudo {
    fn is_active_or_hover(&self) -> bool {
        match *self {}
    }

    fn is_user_action_state(&self) -> bool {
        match *self {}
    }
}

impl ToCss for UnsupportedPseudo {
    fn to_css<W: fmt::Write>(&self, _dest: &mut W) -> fmt::Result {
        match *self {}
    }
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum BookPseudoElement {
    FirstLetter,
    Before,
    After,
}

impl PseudoElement for BookPseudoElement {
    fn is_before_or_after(&self) -> bool {
        matches!(self, Self::Before | Self::After)
    }
}

impl ToCss for BookPseudoElement {
    fn to_css<W: fmt::Write>(&self, dest: &mut W) -> fmt::Result {
        dest.write_str(match self {
            Self::FirstLetter => "::first-letter",
            Self::Before => "::before",
            Self::After => "::after",
        })
    }
}

struct BookParser<'a> {
    namespaces: &'a HashMap<String, String>,
}

impl<'i> Parser<'i> for BookParser<'_> {
    type Impl = BookSelector;
    type Error = SelectorParseErrorKind;

    fn parse_has(&self) -> bool {
        true
    }

    fn allow_forgiving_selectors(&self) -> bool {
        false
    }

    fn parse_pseudo_element(
        &self,
        name: CowRcStr<'i>,
    ) -> Result<BookPseudoElement, ParseError<Self::Error>> {
        if name.eq_ignore_ascii_case("first-letter") {
            Ok(BookPseudoElement::FirstLetter)
        } else if name.eq_ignore_ascii_case("before") {
            Ok(BookPseudoElement::Before)
        } else if name.eq_ignore_ascii_case("after") {
            Ok(BookPseudoElement::After)
        } else {
            Err(ParseError::custom(
                SelectorParseErrorKind::UnsupportedPseudoClassOrElement,
            ))
        }
    }

    fn namespace_for_prefix(&self, prefix: &Atom) -> Option<Atom> {
        // This AST is only lowered; use the small prefix as a key and resolve
        // the potentially large URI once per retained namespace test.
        self.namespaces
            .contains_key(&prefix.0)
            .then(|| prefix.clone())
    }
}

struct Edit {
    range: Range<usize>,
    replacement: Cow<'static, str>,
}

fn prepare_member(text: &str) -> Option<Cow<'_, str>> {
    let mut source = CssParser::new(text);
    source.set_nested_block_limit(3);
    let mut edits = Vec::new();
    scan_member(&mut source, &mut edits, false).ok()?;
    if edits.is_empty() {
        return Some(Cow::Borrowed(text));
    }
    let mut result = String::with_capacity(text.len());
    let mut cursor = 0;
    for edit in edits {
        result.push_str(&text[cursor..edit.range.start]);
        result.push_str(&edit.replacement);
        cursor = edit.range.end;
    }
    result.push_str(&text[cursor..]);
    Some(Cow::Owned(result))
}

fn scan_member<'i>(
    input: &mut CssParser<'i>,
    edits: &mut Vec<Edit>,
    relatives: bool,
) -> Result<(), ParseError<()>> {
    let mut commas = 0;
    while !input.is_exhausted() {
        let start = input.position();
        match input.next_including_whitespace_and_comments()? {
            Token::Function(name) => {
                let name = name.to_ascii_lowercase();
                input.parse_nested_block(|args| {
                    let start = args.position();
                    if name == "not" {
                        scan_member(args, edits, false)?;
                        if args
                            .slice_from(start)
                            .trim_matches(selector_whitespace)
                            .chars()
                            .any(selector_whitespace)
                        {
                            return Err(ParseError::custom(()));
                        }
                    } else if name == "has" && !relatives {
                        scan_member(args, edits, true)?;
                    } else if matches!(
                        name.as_str(),
                        "nth-child" | "nth-of-type" | "nth-last-child" | "nth-last-of-type"
                    ) {
                        consume_tokens(args);
                        let raw = args.slice_from(start);
                        let formula = raw.trim_matches(selector_whitespace).replace(' ', "");
                        if !valid_nth_formula(&formula) {
                            return Err(ParseError::custom(()));
                        }
                        if formula != raw {
                            edits.push(Edit {
                                range: start.byte_index()..args.position().byte_index(),
                                replacement: Cow::Owned(formula),
                            });
                        }
                    } else {
                        return Err(ParseError::custom(()));
                    }
                    Ok(())
                })?;
                if !input.slice_from(start).ends_with(')') {
                    return Err(ParseError::custom(()));
                }
            }
            Token::SquareBracketBlock => {
                input.parse_nested_block(|attr| scan_attribute(attr, edits))?;
                if !input.slice_from(start).ends_with(']') {
                    return Err(ParseError::custom(()));
                }
            }
            Token::Hash(name) => {
                // The reader already accepts IDs such as #123. Escape them as
                // identifiers for Servo without changing their DOM value.
                let mut escaped = "#".to_owned();
                serialize_identifier(name, &mut escaped).expect("write to String");
                edits.push(Edit {
                    range: start.byte_index()..input.position().byte_index(),
                    replacement: Cow::Owned(escaped),
                });
            }
            Token::Comma if relatives => {
                commas += 1;
                if commas == MAX_MEMBERS {
                    let comma = input.position().byte_index() - 1;
                    consume_tokens(input);
                    edits.push(Edit {
                        range: comma..input.position().byte_index(),
                        replacement: Cow::Borrowed(""),
                    });
                    return Ok(());
                }
            }
            token if token.is_parse_error() => return Err(ParseError::custom(())),
            _ => {}
        }
    }
    Ok(())
}

fn scan_attribute<'i>(
    input: &mut CssParser<'i>,
    edits: &mut Vec<Edit>,
) -> Result<(), ParseError<()>> {
    input.skip_whitespace();
    let mut in_name = true;
    while !input.is_exhausted() {
        let start = input.position();
        match input.next_including_whitespace_and_comments()? {
            // EPUB styles frequently spell a literal colon in names such as
            // epub:type and abs:href. Keep a colon in the local name without
            // interpreting it as a CSS namespace separator.
            Token::Colon if in_name => edits.push(Edit {
                range: start.byte_index()..start.byte_index(),
                replacement: Cow::Borrowed("\\"),
            }),
            Token::Ident(_) | Token::Delim('|') => {}
            Token::WhiteSpace(_) | Token::Comment(_) => in_name = false,
            Token::Delim('=')
            | Token::IncludeMatch
            | Token::DashMatch
            | Token::PrefixMatch
            | Token::SuffixMatch
            | Token::SubstringMatch => {
                let start = input.position();
                consume_tokens(input);
                let raw = input.slice_from(start).trim_matches(selector_whitespace);
                // Preserve the reader's unquoted numeric and namespaced
                // values, e.g. data-rank=2 and epub:type=z3998:note.
                if !raw.starts_with(['\'', '"'])
                    && CssParser::new(raw)
                        .parse_entirely(|value| {
                            value
                                .expect_ident_cloned()
                                .map_err(Into::<ParseError<()>>::into)
                        })
                        .is_err()
                {
                    let mut escaped = String::new();
                    serialize_string(raw, &mut escaped).expect("write to String");
                    edits.push(Edit {
                        range: start.byte_index()..input.position().byte_index(),
                        replacement: Cow::Owned(escaped),
                    });
                }
                return Ok(());
            }
            _ => {
                consume_tokens(input);
                return Ok(());
            }
        }
    }
    Ok(())
}

fn valid_nth_formula(formula: &str) -> bool {
    if formula.eq_ignore_ascii_case("odd") || formula.eq_ignore_ascii_case("even") {
        return true;
    }
    let Some(n) = formula.find(['n', 'N']) else {
        return formula.parse::<i32>().is_ok();
    };
    let (factor, offset) = (&formula[..n], &formula[n + 1..]);
    (matches!(factor, "" | "+" | "-") || factor.parse::<i32>().is_ok())
        && (offset.is_empty() || (offset.starts_with(['+', '-']) && offset.parse::<i32>().is_ok()))
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum LoweringScope {
    Rule,
    Relative,
    Negated,
}

#[derive(Default)]
struct Specificity {
    ids: i32,
    classes: i32,
    types: i32,
}

impl Specificity {
    fn rank(&self) -> i32 {
        // Preserve the reader's established decimal packing, including the
        // existing signed behavior for unusually large selectors.
        self.ids
            .wrapping_mul(1_000_000)
            .wrapping_add(self.classes.wrapping_mul(1_000))
            .wrapping_add(self.types)
    }

    fn extend(&mut self, other: Self) {
        self.ids += other.ids;
        self.classes += other.classes;
        self.types += other.types;
    }

    fn extend_rank(&mut self, rank: i32) {
        self.ids += rank / 1_000_000;
        self.classes += rank / 1_000 % 1_000;
        self.types += rank % 1_000;
    }
}

fn simple() -> Simple {
    Simple {
        tag: None,
        id: None,
        classes: Vec::new(),
        attrs: Vec::new(),
        pseudos: Vec::new(),
    }
}

fn lower(
    selector: &Selector<BookSelector>,
    scope: LoweringScope,
    namespaces: &HashMap<String, String>,
    namespace_bytes: &mut usize,
) -> Option<(Vec<Compound>, Specificity, i32)> {
    let mut chain = Vec::new();
    let mut compound = simple();
    let mut specificity = Specificity::default();
    let mut pool = 0;
    let mut relative = false;
    // Servo stores compounds right-to-left but stores each compound's
    // components in authored order. The first class determines our bucket.
    for component in selector.iter_raw_match_order() {
        use Component::*;
        match component {
            RelativeSelectorAnchor if scope == LoweringScope::Relative => relative = true,
            Combinator(ServoCombinator::PseudoElement) if pool != 0 => {}
            Combinator(comb) if scope != LoweringScope::Negated => {
                chain.push(Compound {
                    combinator: lower_combinator(*comb)?,
                    simple: std::mem::replace(&mut compound, simple()),
                });
                if chain.len() > MAX_COMPOUNDS {
                    return None;
                }
            }
            LocalName(local) => {
                compound.tag = Some(local.name.0.to_lowercase());
                specificity.types += 1;
            }
            ExplicitUniversalType => compound.tag = Some("*".into()),
            ID(id) => {
                // Older reader rules retain the last ID but specificity
                // counts every ID in the compound.
                compound.id = Some(id.0.clone());
                specificity.ids += 1;
            }
            Class(class) => {
                compound.classes.push(class.0.clone());
                specificity.classes += 1;
            }
            AttributeInNoNamespaceExists { local_name, .. } => {
                compound.attrs.push(attribute(local_name, None, None)?);
                specificity.classes += 1;
            }
            AttributeInNoNamespace {
                local_name,
                operator,
                value,
                ..
            } => {
                compound
                    .attrs
                    .push(attribute(local_name, None, Some((*operator, value)))?);
                specificity.classes += 1;
            }
            AttributeOther(attr) => {
                let namespace = match &attr.namespace {
                    None => None,
                    Some(NamespaceConstraint::Specific((prefix, _))) if !prefix.0.is_empty() => {
                        let uri = namespaces.get(&prefix.0)?;
                        *namespace_bytes = namespace_bytes.checked_sub(uri.len())?;
                        Some((prefix.0.as_str(), uri.as_str()))
                    }
                    _ => return None,
                };
                let value = match &attr.operation {
                    ParsedAttrSelectorOperation::Exists => None,
                    ParsedAttrSelectorOperation::WithValue {
                        operator, value, ..
                    } => Some((*operator, value)),
                };
                compound
                    .attrs
                    .push(attribute(&attr.local_name, namespace, value)?);
                specificity.classes += 1;
            }
            PseudoElement(element) if scope == LoweringScope::Rule && pool == 0 => {
                pool = match element {
                    BookPseudoElement::FirstLetter => 1,
                    BookPseudoElement::Before => 2,
                    BookPseudoElement::After => 3,
                };
                specificity.types += 1;
            }
            Nth(nth) if scope != LoweringScope::Negated => {
                lower_nth(nth, &mut compound.pseudos);
                specificity.classes += 1;
            }
            Negation(inner) if scope != LoweringScope::Negated => {
                let [selector] = inner.slice() else {
                    return None;
                };
                let (mut negated, rank, _) = lower(
                    selector,
                    LoweringScope::Negated,
                    namespaces,
                    namespace_bytes,
                )?;
                compound
                    .pseudos
                    .push(Pseudo::Not(Box::new(negated.pop()?.simple)));
                specificity.extend(rank);
            }
            Has(relatives) if scope == LoweringScope::Rule => {
                let mut relative_chains = Vec::with_capacity(relatives.len());
                let mut max_rank = i32::MIN;
                for relative in relatives.iter().take(MAX_MEMBERS) {
                    let (relative, rank, _) = lower(
                        &relative.selector,
                        LoweringScope::Relative,
                        namespaces,
                        namespace_bytes,
                    )?;
                    max_rank = max_rank.max(rank.rank());
                    relative_chains.push(relative);
                }
                if relative_chains.is_empty() {
                    return None;
                }
                // The rule decoder assigns unique matcher IDs to :has.
                compound.pseudos.push(Pseudo::Has {
                    id: 0,
                    relatives: relative_chains,
                });
                specificity.extend_rank(max_rank);
            }
            _ => return None,
        }
        if compound.classes.len() + compound.attrs.len() + compound.pseudos.len() > MAX_COMPONENTS {
            return None;
        }
    }
    if scope == LoweringScope::Relative {
        if !relative {
            return None;
        }
    } else {
        chain.push(Compound {
            combinator: Combinator::Descendant,
            simple: compound,
        });
    }
    if chain.is_empty() || chain.len() > MAX_COMPOUNDS {
        return None;
    }
    chain.reverse();
    Some((chain, specificity, pool))
}

fn lower_combinator(combinator: ServoCombinator) -> Option<Combinator> {
    match combinator {
        ServoCombinator::Descendant => Some(Combinator::Descendant),
        ServoCombinator::Child => Some(Combinator::Child),
        ServoCombinator::NextSibling => Some(Combinator::NextSibling),
        ServoCombinator::LaterSibling => Some(Combinator::SubsequentSibling),
        _ => None,
    }
}

fn lower_nth(nth: &NthSelectorData, output: &mut Vec<Pseudo>) {
    let nth_match = || Pseudo::Nth {
        a: nth.an_plus_b.0,
        b: nth.an_plus_b.1,
        of_type: nth.ty.is_of_type(),
        from_end: nth.ty.is_from_end(),
    };
    let pseudo = match (nth.ty, nth.is_function) {
        (NthType::Child, false) => Pseudo::FirstChild,
        (NthType::LastChild, false) => Pseudo::LastChild,
        (NthType::OnlyChild, false) => Pseudo::OnlyChild,
        (NthType::OfType, false) => Pseudo::FirstOfType,
        (NthType::OnlyOfType, false) => {
            output.push(Pseudo::Nth {
                a: 0,
                b: 1,
                of_type: true,
                from_end: true,
            });
            Pseudo::FirstOfType
        }
        _ => nth_match(),
    };
    output.push(pseudo);
}

fn attribute(
    local: &Atom,
    namespace: Option<(&str, &str)>,
    operation: Option<(AttrSelectorOperator, &Atom)>,
) -> Option<Attr> {
    let local = local.0.to_lowercase();
    if local.is_empty()
        || !local
            .chars()
            .all(|c| c.is_alphanumeric() || matches!(c, '-' | '_' | ':'))
    {
        return None;
    }
    let (name, namespace) = match namespace {
        Some((prefix, uri)) => (
            format!("{}:{local}", prefix.to_lowercase()),
            Some(uri.into()),
        ),
        None => (local, None),
    };
    let (op, value) = match operation {
        None => (AttrOp::Exists, None),
        Some((operator, value)) => {
            use AttrSelectorOperator::*;
            let op = match operator {
                Equal => AttrOp::Equals,
                Includes => AttrOp::Includes,
                DashMatch => AttrOp::Dash,
                Prefix => AttrOp::Prefix,
                Suffix => AttrOp::Suffix,
                Substring => AttrOp::Substring,
            };
            (op, Some(value.0.clone()))
        }
    };
    // Matching retains the established case-sensitive attribute value policy.
    Some(Attr {
        name,
        op,
        value,
        namespace,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_compound_order_and_supported_pseudo_element_pools() {
        let compiled = compile(
            ".parent > .first.second#one#two::before, p:first-letter, ::after",
            &HashMap::new(),
            3,
        );
        assert_eq!(
            compiled.iter().map(|rule| rule.pool).collect::<Vec<_>>(),
            [2, 1, 3]
        );
        let [parent, subject] = compiled[0].chain.as_slice() else {
            panic!("expected parent and subject");
        };
        assert_eq!(parent.simple.classes, ["parent"]);
        assert!(matches!(subject.combinator, Combinator::Child));
        assert_eq!(subject.simple.classes, ["first", "second"]);
        assert_eq!(subject.simple.id.as_deref(), Some("two"));
        assert_eq!(compiled[0].specificity, 2_003_001);
    }

    #[test]
    fn preserves_literal_and_qualified_attributes_and_quoted_commas() {
        let namespaces = HashMap::from([("epub".to_owned(), "urn:example:epub".to_owned())]);
        let compiled = compile(
            r#"p:has(> em, :hover), p:not(.a, .b), p[data-pick="a,b"][epub|type~="footnote"][ABS:HREF]"#,
            &namespaces,
            5,
        );
        assert_eq!(compiled.len(), 1);
        let [quoted, namespaced, absolute] = compiled[0].chain[0].simple.attrs.as_slice() else {
            panic!("expected all three attributes");
        };
        assert_eq!(quoted.value.as_deref(), Some("a,b"));
        assert_eq!(namespaced.namespace.as_deref(), Some("urn:example:epub"));
        assert_eq!(namespaced.name, "epub:type");
        assert!(matches!(namespaced.op, AttrOp::Includes));
        assert_eq!(absolute.name, "abs:href");
        assert_eq!(absolute.namespace, None);
    }

    #[test]
    fn preserves_unquoted_epub_values_and_numeric_ids() {
        let compiled = compile(
            "#123[data-rank=2][epub:type=z3998:note]",
            &HashMap::new(),
            1,
        );
        assert_eq!(compiled.len(), 1);
        let element = &compiled[0].chain[0].simple;
        assert_eq!(element.id.as_deref(), Some("123"));
        assert_eq!(element.attrs[0].value.as_deref(), Some("2"));
        assert_eq!(element.attrs[1].name, "epub:type");
        assert_eq!(element.attrs[1].value.as_deref(), Some("z3998:note"));
    }

    #[test]
    fn relative_specificity_and_structural_evaluation_order_are_stable() {
        let compiled = compile(
            "section:has(> h1 + .last:not(#drop), #extra em:nth-child(2 n + 1)):only-of-type::before",
            &HashMap::new(),
            1,
        );
        assert_eq!(compiled.len(), 1);
        assert_eq!(compiled[0].specificity, 1_002_003);
        let [Pseudo::Has { relatives, .. }, Pseudo::Nth {
            of_type: true,
            from_end: true,
            ..
        }, Pseudo::FirstOfType] = compiled[0].chain[0].simple.pseudos.as_slice()
        else {
            panic!("unexpected structural pseudo evaluation order");
        };
        assert_eq!(relatives.len(), 2);
        assert!(matches!(relatives[0][0].combinator, Combinator::Child));
        assert!(matches!(
            relatives[0][1].combinator,
            Combinator::NextSibling
        ));
        assert!(matches!(
            relatives[1][1].simple.pseudos[0],
            Pseudo::Nth {
                a: 2,
                b: 1,
                of_type: false,
                from_end: false
            }
        ));
    }

    #[test]
    fn bounds_selector_members_and_nested_operand_work() {
        let namespaces = HashMap::new();
        let wide = format!("{} .dropped, .kept", ".group ".repeat(MAX_COMPOUNDS));
        assert_eq!(
            compile(&wide, &namespaces, 2)[0].chain[0].simple.classes,
            ["kept"]
        );
        assert!(compile(
            &format!("{}.lost", ":hover,".repeat(MAX_MEMBERS)),
            &namespaces,
            2
        )
        .is_empty());
        let capped_relatives = format!("p:has({}.last, :hover)", "em,".repeat(MAX_MEMBERS - 1));
        let compiled = compile(&capped_relatives, &namespaces, 1);
        let [Pseudo::Has { relatives, .. }] = compiled[0].chain[0].simple.pseudos.as_slice() else {
            panic!("expected bounded relative list");
        };
        assert_eq!(relatives.len(), MAX_MEMBERS);
        let deep = format!("p{}x{}, .kept", ":not(".repeat(1000), ")".repeat(1000));
        let compiled = compile(&deep, &namespaces, 2);
        assert_eq!(compiled.len(), 1);
        assert_eq!(compiled[0].chain[0].simple.classes, ["kept"]);
    }
}
