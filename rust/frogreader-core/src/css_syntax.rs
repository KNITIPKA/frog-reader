use crate::css_declarations::{declaration_block, strip_comments};
use cssparser::{
    AtRuleParser, BasicParseErrorKind, CowRcStr, ParseError, ParseErrorKind, Parser, ParserState,
    QualifiedRuleParser, StyleSheetParser,
};
use std::borrow::Cow;

const MAGIC: u32 = 0x3153_5343; // CSS1
const MAX_INPUT_BYTES: usize = 32 * 1024 * 1024;
const MAX_WIRE_BYTES: usize = 32 * 1024 * 1024;
const MAX_RAW_RULES: usize = 262_144;
const MAX_GROUP_DEPTH: u8 = 64;

pub(crate) fn sheet_bytes(text: &str) -> Option<Vec<u8>> {
    if text.len() > MAX_INPUT_BYTES {
        return None;
    }
    let mut input = Parser::new(text);
    input.set_nested_block_limit(2 * MAX_GROUP_DEPTH);
    let mut writer = SheetWriter {
        wire: MAGIC.to_le_bytes().to_vec(),
        raw_rules: 0,
        depth: 0,
    };
    writer.write_rule_list(&mut input).ok()?;
    Some(writer.wire)
}

#[derive(Clone, Copy)]
#[repr(u8)]
enum RuleTag {
    Qualified = 1,
    FontFace = 2,
    Media = 3,
    Supports = 4,
    Namespace = 5,
}

enum AtPrelude<'i> {
    FontFace,
    Group(RuleTag, Cow<'i, str>),
    Namespace(Cow<'i, str>),
}

enum Rule<'i> {
    Block,
    Namespace(Cow<'i, str>),
}

#[derive(Debug)]
struct CapacityExceeded;

type SyntaxResult<T> = Result<T, ParseError<CapacityExceeded>>;

struct SheetWriter {
    wire: Vec<u8>,
    raw_rules: usize,
    depth: u8,
}

impl SheetWriter {
    fn write_rule_list(&mut self, input: &mut Parser<'_>) -> SyntaxResult<()> {
        let counter = self.wire.len();
        self.write(&0_u32.to_le_bytes())?;
        let mut count = 0_u32;
        let mut rules = StyleSheetParser::new(input, self);
        loop {
            let start = rules.parser.wire.len();
            let rules_before = rules.parser.raw_rules;
            match rules.next() {
                Some(Ok(Rule::Block)) => count += 1,
                Some(Ok(Rule::Namespace(prelude))) => {
                    // Namespace parsing is still performed in the resolver.
                    rules.parser.start_rule(RuleTag::Namespace)?;
                    let prefix = "@namespace ";
                    let size = prefix.len() + prelude.len();
                    rules.parser.write(&(size as u32).to_le_bytes())?;
                    rules.parser.write(prefix.as_bytes())?;
                    rules.parser.write(prelude.as_bytes())?;
                    count += 1;
                }
                Some(Err((error, ..))) => {
                    if matches!(error.kind, ParseErrorKind::Custom(_)) {
                        return Err(error);
                    }
                    // cssparser can reject a block after visiting it. Leave no
                    // partial row in the list and continue with the next rule.
                    rules.parser.wire.truncate(start);
                    rules.parser.raw_rules = rules_before;
                }
                None => break,
            }
        }
        self.wire[counter..counter + 4].copy_from_slice(&count.to_le_bytes());
        Ok(())
    }

    fn start_rule(&mut self, tag: RuleTag) -> SyntaxResult<()> {
        if self.raw_rules >= MAX_RAW_RULES {
            return Err(capacity_error());
        }
        self.raw_rules += 1;
        self.write(&[tag as u8])
    }

    fn write_string(&mut self, text: &str) -> SyntaxResult<()> {
        self.write(&(text.len() as u32).to_le_bytes())?;
        self.write(text.as_bytes())
    }

    fn write(&mut self, bytes: &[u8]) -> SyntaxResult<()> {
        if bytes.len() > MAX_WIRE_BYTES - self.wire.len() {
            return Err(capacity_error());
        }
        self.wire
            .try_reserve(bytes.len())
            .map_err(|_| capacity_error())?;
        self.wire.extend_from_slice(bytes);
        Ok(())
    }

    fn write_declarations(&mut self, input: &mut Parser<'_>) -> SyntaxResult<()> {
        let block = declaration_block(input).ok_or_else(capacity_error)?;
        self.write(&block)
    }
}

impl<'i> QualifiedRuleParser<'i> for SheetWriter {
    type Prelude = Cow<'i, str>;
    type QualifiedRule = Rule<'i>;
    type Error = CapacityExceeded;

    fn parse_prelude(&mut self, input: &mut Parser<'i>) -> SyntaxResult<Self::Prelude> {
        prelude_text(input)
    }

    fn parse_block(
        &mut self,
        prelude: Self::Prelude,
        _start: &ParserState,
        input: &mut Parser<'i>,
    ) -> SyntaxResult<Self::QualifiedRule> {
        self.start_rule(RuleTag::Qualified)?;
        self.write_string(&prelude)?;
        self.write_declarations(input)?;
        Ok(Rule::Block)
    }
}

impl<'i> AtRuleParser<'i> for SheetWriter {
    type Prelude = AtPrelude<'i>;
    type AtRule = Rule<'i>;
    type Error = CapacityExceeded;

    fn parse_prelude(
        &mut self,
        name: CowRcStr<'i>,
        input: &mut Parser<'i>,
    ) -> SyntaxResult<Self::Prelude> {
        if name.eq_ignore_ascii_case("media") {
            Ok(AtPrelude::Group(RuleTag::Media, prelude_text(input)?))
        } else if name.eq_ignore_ascii_case("supports") {
            Ok(AtPrelude::Group(RuleTag::Supports, prelude_text(input)?))
        } else if name.eq_ignore_ascii_case("font-face") {
            consume(input);
            Ok(AtPrelude::FontFace)
        } else if name.eq_ignore_ascii_case("namespace") {
            Ok(AtPrelude::Namespace(prelude_text(input)?))
        } else {
            // Imports are expanded by the EPUB/MOBI resource loaders. Reject
            // unsupported at-rules so cssparser consumes their entire block.
            Err(ParseError::from_basic_kind(
                BasicParseErrorKind::AtRuleInvalid,
            ))
        }
    }

    fn rule_without_block(
        &mut self,
        prelude: Self::Prelude,
        _start: &ParserState,
    ) -> Result<Self::AtRule, ()> {
        match prelude {
            AtPrelude::Namespace(text) => Ok(Rule::Namespace(text)),
            _ => Err(()),
        }
    }

    fn parse_block(
        &mut self,
        prelude: Self::Prelude,
        _start: &ParserState,
        input: &mut Parser<'i>,
    ) -> SyntaxResult<Self::AtRule> {
        match prelude {
            AtPrelude::FontFace => {
                self.start_rule(RuleTag::FontFace)?;
                self.write_declarations(input)?;
            }
            AtPrelude::Group(tag, text) if self.depth < MAX_GROUP_DEPTH => {
                self.start_rule(tag)?;
                self.write_string(&text)?;
                self.depth += 1;
                let result = self.write_rule_list(input);
                self.depth -= 1;
                result?;
            }
            _ => {
                return Err(ParseError::from_basic_kind(
                    BasicParseErrorKind::AtRuleBodyInvalid,
                ));
            }
        }
        Ok(Rule::Block)
    }
}

fn consume(input: &mut Parser<'_>) {
    while input.next_including_whitespace_and_comments().is_ok() {}
}

fn prelude_text<'i>(input: &mut Parser<'i>) -> SyntaxResult<Cow<'i, str>> {
    let start = input.position();
    consume(input);
    let text = input.slice_from(start).trim();
    let cleaned = strip_comments(text).ok_or_else(ParseError::unexpected_token)?;
    Ok(match cleaned {
        Cow::Borrowed(text) => Cow::Borrowed(text.trim()),
        Cow::Owned(text) => Cow::Owned(text.trim().to_owned()),
    })
}

fn capacity_error() -> ParseError<CapacityExceeded> {
    ParseError {
        kind: ParseErrorKind::Custom(CapacityExceeded),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Wire<'a>(&'a [u8]);

    impl<'a> Wire<'a> {
        fn take(&mut self, len: usize) -> &'a [u8] {
            let (head, tail) = self.0.split_at(len);
            self.0 = tail;
            head
        }

        fn u32(&mut self) -> u32 {
            u32::from_le_bytes(self.take(4).try_into().unwrap())
        }

        fn text(&mut self) -> &'a str {
            let len = self.u32() as usize;
            std::str::from_utf8(self.take(len)).unwrap()
        }

        fn tag(&mut self, expected: RuleTag) {
            assert_eq!(self.take(1), [expected as u8]);
        }

        fn declaration(&mut self, property: &str, value: &str) {
            assert_eq!(self.text(), property);
            assert_eq!(self.text(), value);
            assert_eq!(self.take(1), [0]);
        }
    }

    #[test]
    fn stylesheet_keeps_source_order_and_ignores_unsupported_blocks() {
        let css = r#"
            @charset "UTF-8";
            @import url("vendor;{bundle}.css");
            @namespace svg url("urn:book;namespace");
            [data-title="{same}"]/**/.cover {content:"literal } ; /* slash */"; color:red}
            @unknown (example) {@media screen {bad {color:blue}}}
            @MEDIA (min-width: 0px) {
                @supports (display: grid) {h1 {font-weight:bold}}
                @font-face {font-family:"Publisher"}
            }
            p {text-align:center}
        "#;
        let bytes = sheet_bytes(css).unwrap();
        let mut wire = Wire(&bytes);
        assert_eq!(wire.u32(), MAGIC);
        assert_eq!(wire.u32(), 4);
        wire.tag(RuleTag::Namespace);
        assert_eq!(wire.text(), r#"@namespace svg url("urn:book;namespace")"#);
        wire.tag(RuleTag::Qualified);
        assert_eq!(wire.text(), r#"[data-title="{same}"].cover"#);
        assert_eq!(wire.u32(), 2);
        wire.declaration("content", r#""literal } ; /* slash */""#);
        wire.declaration("color", "red");
        wire.tag(RuleTag::Media);
        assert_eq!(wire.text(), "(min-width: 0px)");
        assert_eq!(wire.u32(), 2);
        wire.tag(RuleTag::Supports);
        assert_eq!(wire.text(), "(display: grid)");
        assert_eq!(wire.u32(), 1);
        wire.tag(RuleTag::Qualified);
        assert_eq!(wire.text(), "h1");
        assert_eq!(wire.u32(), 1);
        wire.declaration("font-weight", "bold");
        wire.tag(RuleTag::FontFace);
        assert_eq!(wire.u32(), 1);
        wire.declaration("font-family", r#""Publisher""#);
        wire.tag(RuleTag::Qualified);
        assert_eq!(wire.text(), "p");
        assert_eq!(wire.u32(), 1);
        wire.declaration("text-align", "center");
        assert!(wire.0.is_empty());
    }

    #[test]
    fn excessive_group_nesting_does_not_hide_following_rules() {
        let depth = usize::from(MAX_GROUP_DEPTH) + 8;
        let css = format!(
            "{}discard {{color: red}}{}keep {{color: green}}",
            "@media screen {".repeat(depth),
            "}".repeat(depth),
        );
        let bytes = sheet_bytes(&css).unwrap();
        let mut wire = Wire(&bytes);
        assert_eq!(wire.u32(), MAGIC);
        assert_eq!(wire.u32(), 2);
        for index in 0..MAX_GROUP_DEPTH {
            wire.tag(RuleTag::Media);
            assert_eq!(wire.text(), "screen");
            assert_eq!(wire.u32(), u32::from(index + 1 < MAX_GROUP_DEPTH));
        }
        wire.tag(RuleTag::Qualified);
        assert_eq!(wire.text(), "keep");
        assert_eq!(wire.u32(), 1);
        wire.declaration("color", "green");
        assert!(wire.0.is_empty());
    }
}
