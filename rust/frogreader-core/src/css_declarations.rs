use cssparser::{
    AtRuleParser, BasicParseErrorKind, CowRcStr, DeclarationParser, ParseError, Parser,
    ParserState, QualifiedRuleParser, RuleBodyItemParser, RuleBodyParser, SourcePosition, Token,
};
use std::borrow::Cow;

const INLINE_MAGIC: u32 = 0x4453_5343; // CSSD
const MAX_DECLARATIONS: usize = 512;
const MAX_WIRE_BYTES: usize = 16 * 1024 * 1024;

/// Declaration attempts in source order. An empty name marks a discarded item.
pub(crate) fn declaration_block(input: &mut Parser<'_>) -> Option<Vec<u8>> {
    encode_block(input, Vec::new())
}

pub(crate) fn inline_bytes(text: &str) -> Option<Vec<u8>> {
    if text.len() > MAX_WIRE_BYTES {
        return None;
    }
    encode_block(&mut Parser::new(text), INLINE_MAGIC.to_le_bytes().to_vec())
}

fn encode_block(input: &mut Parser<'_>, mut bytes: Vec<u8>) -> Option<Vec<u8>> {
    let count_position = bytes.len();
    bytes.extend_from_slice(&0u32.to_le_bytes());
    let mut count = 0u32;
    for declaration in RuleBodyParser::new(input, &mut Declarations).take(MAX_DECLARATIONS) {
        let (name, value, important) = match &declaration {
            Ok(entry) if !entry.value.trim().is_empty() => {
                (&*entry.name, entry.value.trim(), entry.important)
            }
            // Invalid entries still count against the Kotlin stylesheet budget.
            _ => ("", "", false),
        };
        if bytes.len() + 9 + name.len() + value.len() > MAX_WIRE_BYTES {
            return None;
        }
        for field in [name, value] {
            bytes.extend_from_slice(&(field.len() as u32).to_le_bytes());
            bytes.extend_from_slice(field.as_bytes());
        }
        bytes.push(u8::from(important));
        count += 1;
    }
    if count as usize == MAX_DECLARATIONS {
        // A delimited parser must reach the end before its enclosing rule can
        // publish. Skip the remaining tokens without constructing declarations.
        while input.next_including_whitespace_and_comments().is_ok() {}
    }
    bytes[count_position..count_position + 4].copy_from_slice(&count.to_le_bytes());
    Some(bytes)
}

struct Declaration<'i> {
    name: CowRcStr<'i>,
    value: Cow<'i, str>,
    important: bool,
}

struct Declarations;

impl<'i> DeclarationParser<'i> for Declarations {
    type Declaration = Declaration<'i>;
    type Error = ();

    fn parse_value(
        &mut self,
        name: CowRcStr<'i>,
        input: &mut Parser<'i>,
        _start: &ParserState,
    ) -> Result<Self::Declaration, ParseError<()>> {
        let mut value = SourceText::new(input.position(), " ");
        let important = consume_components(input, &mut value, true)?;
        let end = important.unwrap_or_else(|| input.position());
        Ok(Declaration {
            name,
            value: value.finish(input, end),
            important: important.is_some(),
        })
    }
}

impl<'i> AtRuleParser<'i> for Declarations {
    type Prelude = ();
    type AtRule = Declaration<'i>;
    type Error = ();
}

impl<'i> QualifiedRuleParser<'i> for Declarations {
    type Prelude = ();
    type QualifiedRule = Declaration<'i>;
    type Error = ();
}

impl<'i> RuleBodyItemParser<'i, Declaration<'i>, ()> for Declarations {
    fn parse_declarations(&self) -> bool {
        true
    }

    fn parse_qualified(&self) -> bool {
        false
    }
}

/// Erases comments from a rule's prelude without injecting descendant combinators.
pub(crate) fn strip_comments(text: &str) -> Option<Cow<'_, str>> {
    if !text.contains("/*") {
        return Some(Cow::Borrowed(text));
    }
    let mut input = Parser::new(text);
    let mut source = SourceText::new(input.position(), "");
    consume_components(&mut input, &mut source, false).ok()?;
    Some(source.finish(&input, input.position()))
}

struct SourceText {
    copied_through: SourcePosition,
    buffer: Option<String>,
    comment_separator: &'static str,
}

impl SourceText {
    fn new(start: SourcePosition, comment_separator: &'static str) -> Self {
        Self {
            copied_through: start,
            buffer: None,
            comment_separator,
        }
    }

    fn remove_comment(&mut self, input: &Parser<'_>, start: SourcePosition) {
        let buffer = self.buffer.get_or_insert_with(String::new);
        buffer.push_str(input.slice(self.copied_through..start));
        buffer.push_str(self.comment_separator);
        self.copied_through = input.position();
    }

    fn finish<'i>(self, input: &Parser<'i>, end: SourcePosition) -> Cow<'i, str> {
        let remaining = input.slice(self.copied_through..end);
        match self.buffer {
            Some(mut owned) => {
                owned.push_str(remaining);
                Cow::Owned(owned)
            }
            None => Cow::Borrowed(remaining),
        }
    }
}

/// Walk component values with cssparser's nesting limit instead of rewriting
/// token spellings. Only the outer declaration may end in `!important`.
fn consume_components(
    input: &mut Parser<'_>,
    source: &mut SourceText,
    allow_important: bool,
) -> Result<Option<SourcePosition>, ParseError<()>> {
    loop {
        let start = input.position();
        let token = match input.next_including_whitespace_and_comments() {
            Ok(token) => token,
            Err(error) if error.kind == BasicParseErrorKind::EndOfInput => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        match token {
            Token::Comment(_) => source.remove_comment(input, start),
            Token::Delim('!') if allow_important => {
                let terminal_marker = input.try_parse(|tail| {
                    tail.expect_ident_matching("important")?;
                    tail.expect_exhausted()
                });
                if terminal_marker.is_ok() {
                    return Ok(Some(start));
                }
            }
            Token::Function(_)
            | Token::ParenthesisBlock
            | Token::SquareBracketBlock
            | Token::CurlyBracketBlock => {
                input.parse_nested_block(|nested| consume_components(nested, source, false))?;
            }
            token if token.is_parse_error() => return Err(ParseError::unexpected_token()),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(css: &str) -> Vec<(String, String, u8)> {
        let wire = inline_bytes(css).unwrap();
        fn field(input: &mut &[u8]) -> String {
            let (length, trailing) = input.split_at(4);
            let length = u32::from_le_bytes(length.try_into().unwrap()) as usize;
            let (value, rest) = trailing.split_at(length);
            *input = rest;
            std::str::from_utf8(value).unwrap().to_owned()
        }
        assert_eq!(&wire[..4], b"CSSD");
        let count = u32::from_le_bytes(wire[4..8].try_into().unwrap());
        let mut input = &wire[8..];
        let result = (0..count)
            .map(|_| {
                let name = field(&mut input);
                let value = field(&mut input);
                let (important, rest) = input.split_first().unwrap();
                input = rest;
                (name, value, *important)
            })
            .collect();
        assert!(input.is_empty());
        result
    }

    #[test]
    fn preserves_raw_values_and_only_strips_terminal_important() {
        let css = r#"src: url("fonts/a;b/*keep*/.woff2") format('woff2');
            --calculated: var(--space, calc(+01.50em /*skip*/ + 2e-1px)) !/**/IMPORTANT;
            content: 'literal !important; /*keep*/'; other: custom(a !important)"#;
        let actual = rows(css);
        let expected = [
            (
                "src",
                r#"url("fonts/a;b/*keep*/.woff2") format('woff2')"#,
                0,
            ),
            ("--calculated", "var(--space, calc(+01.50em   + 2e-1px))", 1),
            ("content", "'literal !important; /*keep*/'", 0),
            ("other", "custom(a !important)", 0),
        ];
        assert_eq!(
            actual,
            expected.map(|(n, v, flag)| (n.to_owned(), v.to_owned(), flag))
        );
        assert_eq!(
            strip_comments(r#"a/**/.b[href="/*keep*/"]"#).as_deref(),
            Some(r#"a.b[href="/*keep*/"]"#)
        );
    }

    #[test]
    fn counts_malformed_attempts_and_exhausts_the_bounded_rule_body() {
        let css = "missing colon; color: ; ;; width: 1px; ".to_owned() + &"width: 3px;".repeat(512);
        let mut input = Parser::new(&css);
        let block = declaration_block(&mut input).unwrap();
        assert!(input.is_exhausted());
        assert_eq!(&block[..4], &512u32.to_le_bytes());
        assert_eq!(&block[4..22], &[0u8; 18]);
        assert_eq!(rows(&css)[2], ("width".to_owned(), "1px".to_owned(), 0));
    }
}
