use encoding_rs::{Encoding, REPLACEMENT, UTF_16BE, UTF_16LE, UTF_8};
use std::borrow::Cow;

const SNIFF_BYTES: usize = 5 * 1024;
const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;

#[derive(Clone, Copy)]
enum Charset {
    Web(&'static Encoding),
    Ascii,
    Latin1,
    Utf32 { little_endian: bool },
}

/// Decode the source bytes of an EPUB/KF8 chapter. Byte order marks take
/// precedence over declarations. The UTF-8 path can borrow the source.
pub(crate) fn decode(bytes: &[u8]) -> Option<Cow<'_, str>> {
    if bytes.len() > MAX_INPUT_BYTES {
        return None;
    }

    // UTF-32LE and UTF-16LE share their first two BOM bytes.
    let (charset, source) = if let Some(source) = bytes.strip_prefix(b"\x00\x00\xfe\xff") {
        (
            Charset::Utf32 {
                little_endian: false,
            },
            source,
        )
    } else if let Some(source) = bytes.strip_prefix(b"\xff\xfe\x00\x00") {
        (
            Charset::Utf32 {
                little_endian: true,
            },
            source,
        )
    } else if let Some((encoding, size)) = Encoding::for_bom(bytes) {
        (Charset::Web(encoding), &bytes[size..])
    } else {
        let prefix = &bytes[..bytes.len().min(SNIFF_BYTES)];
        (
            declared_charset(prefix).unwrap_or(Charset::Web(UTF_8)),
            bytes,
        )
    };

    Some(match charset {
        Charset::Web(encoding) => encoding.decode_without_bom_handling(source).0,
        Charset::Ascii if source.is_ascii() => String::from_utf8_lossy(source),
        Charset::Ascii => Cow::Owned(
            source
                .iter()
                .map(|&byte| {
                    if byte.is_ascii() {
                        char::from(byte)
                    } else {
                        '\u{fffd}'
                    }
                })
                .collect(),
        ),
        Charset::Latin1 if source.is_ascii() => String::from_utf8_lossy(source),
        Charset::Latin1 => Cow::Owned(source.iter().copied().map(char::from).collect()),
        Charset::Utf32 { little_endian } => Cow::Owned(decode_utf32(source, little_endian)),
    })
}

fn decode_utf32(bytes: &[u8], little_endian: bool) -> String {
    let mut chunks = bytes.chunks_exact(4);
    let mut result: String = chunks
        .by_ref()
        .map(|chunk| {
            let word = [chunk[0], chunk[1], chunk[2], chunk[3]];
            let scalar = if little_endian {
                u32::from_le_bytes(word)
            } else {
                u32::from_be_bytes(word)
            };
            char::from_u32(scalar).unwrap_or('\u{fffd}')
        })
        .collect();
    if !chunks.remainder().is_empty() {
        result.push('\u{fffd}');
    }
    result
}

fn declared_charset(prefix: &[u8]) -> Option<Charset> {
    // Match the existing chapter loader's priority between HTML metadata and
    // the XML declaration. A direct charset attribute ends the meta search
    // even when the label is unknown.
    let mut scan = prefix;
    while let Some(open) = scan.iter().position(|byte| *byte == b'<') {
        scan = &scan[open + 1..];
        if let Some(comment) = scan.strip_prefix(b"!--") {
            let Some(remaining) = after(comment, b"-->") else {
                break;
            };
            scan = remaining;
            continue;
        }
        if let Some(cdata) = scan.strip_prefix(b"![CDATA[") {
            let Some(remaining) = after(cdata, b"]]>") else {
                break;
            };
            scan = remaining;
            continue;
        }
        if let Some(instruction) = scan.strip_prefix(b"?") {
            let Some(remaining) = after(instruction, b"?>") else {
                break;
            };
            scan = remaining;
            continue;
        }
        if scan.first() == Some(&b'!') {
            let Some(end) = tag_end(scan, true) else {
                break;
            };
            scan = &scan[end + 1..];
            continue;
        }
        if !scan.first().is_some_and(u8::is_ascii_alphabetic) {
            continue;
        }

        let name_end = scan
            .iter()
            .position(|byte| !tag_name_char(*byte))
            .unwrap_or(scan.len());
        let name = &scan[..name_end];
        let Some(end) = tag_end(scan, false) else {
            break;
        };
        let parameters = &scan[name_end..end];
        scan = &scan[end + 1..];
        if name.eq_ignore_ascii_case(b"meta") {
            let mut meta = Metadata::default();
            for (key, value) in Attributes(parameters) {
                if key.eq_ignore_ascii_case(b"charset") {
                    meta.charset.get_or_insert(value);
                } else if key.eq_ignore_ascii_case(b"content") {
                    meta.content.get_or_insert(value);
                } else if key.eq_ignore_ascii_case(b"http-equiv") {
                    meta.http_equiv.get_or_insert(value);
                }
            }
            if let Some(found) = meta.content_type_charset() {
                return Some(found);
            }
            if let Some(label) = meta.charset {
                return resolve_label(label);
            }
        } else if !parameters.trim_ascii_end().ends_with(b"/") && is_raw_text(name) {
            let Some(remaining) = skip_raw_text(scan, name) else {
                break;
            };
            scan = remaining;
        }
    }
    xml_declaration_charset(prefix)
}

#[derive(Default)]
struct Metadata<'a> {
    charset: Option<&'a [u8]>,
    content: Option<&'a [u8]>,
    http_equiv: Option<&'a [u8]>,
}

impl Metadata<'_> {
    fn content_type_charset(&self) -> Option<Charset> {
        let equiv = self.http_equiv?;
        if self.charset.is_none() && !equiv.eq_ignore_ascii_case(b"content-type") {
            return None;
        }
        let content = attribute_text(self.content?);
        let bytes = content.as_bytes();
        let prefix = b"charset=";
        let start = bytes
            .windows(prefix.len())
            .enumerate()
            .find_map(|(index, item)| {
                let word_before = index != 0
                    && (bytes[index - 1].is_ascii_alphanumeric() || bytes[index - 1] == b'_');
                (!word_before && item.eq_ignore_ascii_case(prefix)).then_some(index + prefix.len())
            })?;
        let mut label = bytes[start..].trim_ascii_start();
        if matches!(label.first(), Some(b'"' | b'\'')) {
            label = &label[1..];
        }
        let length = label
            .iter()
            .position(|byte| byte.is_ascii_whitespace() || b",;\"'".contains(byte))
            .unwrap_or(label.len());
        resolve_label(&label[..length])
    }
}

fn xml_declaration_charset(prefix: &[u8]) -> Option<Charset> {
    let data = prefix.trim_ascii_start().strip_prefix(b"<?")?;
    let xml = data.get(..3)?.eq_ignore_ascii_case(b"xml");
    if !xml || !data.get(3)?.is_ascii_whitespace() {
        return None;
    }
    let end = data.windows(2).position(|pair| pair == b"?>")?;
    Attributes(&data[3..end])
        .find(|(key, _)| key.eq_ignore_ascii_case(b"encoding"))
        .and_then(|(_, value)| resolve_label(value))
}

fn attribute_text(raw: &[u8]) -> Cow<'_, str> {
    let Ok(text) = std::str::from_utf8(raw) else {
        return String::from_utf8_lossy(raw);
    };
    quick_xml::escape::unescape(text).unwrap_or(Cow::Borrowed(text))
}

fn resolve_label(raw: &[u8]) -> Option<Charset> {
    let raw = attribute_text(raw);
    let label = raw.trim_matches(|ch| ch <= ' ');
    let label = if label.contains(['"', '\'']) {
        Cow::Owned(label.replace(['"', '\''], ""))
    } else {
        Cow::Borrowed(label)
    };
    let label = label.to_ascii_lowercase();
    // encoding_rs follows browser aliases. Preserve Java's distinct ASCII,
    // Latin-1 and big-endian default for an explicit UTF-16 declaration.
    match label.as_str() {
        "ascii" | "us-ascii" | "ansi_x3.4-1968" | "ansi_x3.4-1986" | "iso-ir-6"
        | "iso_646.irv:1991" | "iso646-us" | "us" | "ibm367" | "cp367" | "csascii" | "646" => {
            Some(Charset::Ascii)
        }
        "iso-8859-1" | "iso8859-1" | "iso8859_1" | "iso88591" | "iso_8859-1"
        | "iso_8859-1:1987" | "iso-ir-100" | "latin1" | "latin-1" | "l1" | "ibm819" | "cp819"
        | "csisolatin1" | "819" => Some(Charset::Latin1),
        "utf-16" | "utf16" | "unicode" | "unicodebig" => Some(Charset::Web(UTF_16BE)),
        "unicodebigunmarked" => Some(Charset::Web(UTF_16BE)),
        "unicodelittle" | "unicodelittleunmarked" => Some(Charset::Web(UTF_16LE)),
        "utf-32" | "utf32" | "utf-32be" | "utf32be" => Some(Charset::Utf32 {
            little_endian: false,
        }),
        "utf-32le" | "utf32le" => Some(Charset::Utf32 {
            little_endian: true,
        }),
        _ => Encoding::for_label(label.as_bytes())
            .filter(|encoding| *encoding != REPLACEMENT)
            .map(Charset::Web),
    }
}

fn tag_name_char(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || b"-_:.".contains(&byte) || !byte.is_ascii()
}

fn tag_end(bytes: &[u8], declaration: bool) -> Option<usize> {
    let mut quote = None;
    let mut subset = 0_usize;
    for (index, &byte) in bytes.iter().enumerate() {
        match (quote, byte) {
            (Some(previous), _) if previous == byte => quote = None,
            (Some(_), _) => {}
            (None, b'\'' | b'"') => quote = Some(byte),
            (None, b'[') if declaration => subset += 1,
            (None, b']') if declaration => subset = subset.saturating_sub(1),
            (None, b'>') if subset == 0 => return Some(index),
            _ => {}
        }
    }
    None
}

fn after<'a>(source: &'a [u8], delimiter: &[u8]) -> Option<&'a [u8]> {
    let end = source
        .windows(delimiter.len())
        .position(|item| item == delimiter)?;
    Some(&source[end + delimiter.len()..])
}

fn is_raw_text(name: &[u8]) -> bool {
    [
        b"script".as_slice(),
        b"style",
        b"title",
        b"textarea",
        b"xmp",
        b"iframe",
        b"noembed",
        b"noframes",
    ]
    .iter()
    .any(|tag| name.eq_ignore_ascii_case(tag))
}

fn skip_raw_text<'a>(source: &'a [u8], name: &[u8]) -> Option<&'a [u8]> {
    for (index, bytes) in source.windows(2).enumerate() {
        if bytes != b"</" {
            continue;
        }
        let closing = &source[index + 2..];
        if closing
            .get(..name.len())
            .is_some_and(|name_in_source| name_in_source.eq_ignore_ascii_case(name))
            && closing
                .get(name.len())
                .is_some_and(|byte| matches!(byte, b'>' | b'/') || byte.is_ascii_whitespace())
        {
            return Some(&closing[tag_end(closing, false)? + 1..]);
        }
    }
    None
}

struct Attributes<'a>(&'a [u8]);

impl<'a> Iterator for Attributes<'a> {
    type Item = (&'a [u8], &'a [u8]);

    fn next(&mut self) -> Option<Self::Item> {
        self.0 = self.0.trim_ascii_start();
        while self.0.first().is_some_and(|byte| b"/?=".contains(byte)) {
            self.0 = self.0[1..].trim_ascii_start();
        }
        if self.0.is_empty() {
            return None;
        }
        let end = self
            .0
            .iter()
            .position(|byte| byte.is_ascii_whitespace() || b"/?=".contains(byte))
            .unwrap_or(self.0.len());
        let key = &self.0[..end];
        self.0 = self.0[end..].trim_ascii_start();
        let Some(with_value) = self.0.strip_prefix(b"=") else {
            return Some((key, b""));
        };
        let value = with_value.trim_ascii_start();
        if let Some(quote @ (b'"' | b'\'')) = value.first() {
            let end = value[1..].iter().position(|byte| byte == quote)? + 1;
            self.0 = &value[end + 1..];
            return Some((key, &value[1..end]));
        }
        let end = value
            .iter()
            .position(u8::is_ascii_whitespace)
            .unwrap_or(value.len());
        self.0 = &value[end..];
        Some((key, &value[..end]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_is_borrowed_and_bom_overrides_declarations() {
        assert!(matches!(
            decode("<p>читай</p>".as_bytes()),
            Some(Cow::Borrowed(_))
        ));
        let text = "<meta charset=windows-1251><p>читай</p>";
        let mut source = b"\xef\xbb\xbf".to_vec();
        source.extend_from_slice(text.as_bytes());
        assert!(matches!(decode(&source), Some(Cow::Borrowed(decoded)) if decoded == text));
    }

    #[test]
    fn utf16_and_utf32_boms_preserve_surrogate_pairs_and_byte_order() {
        let text = "<p>𐍈ב</p>";
        for little_endian in [false, true] {
            let mut utf16 = if little_endian {
                b"\xff\xfe"
            } else {
                b"\xfe\xff"
            }
            .to_vec();
            for word in text.encode_utf16() {
                utf16.extend(if little_endian {
                    word.to_le_bytes()
                } else {
                    word.to_be_bytes()
                });
            }
            assert_eq!(decode(&utf16).as_deref(), Some(text));

            let mut utf32 = if little_endian {
                b"\xff\xfe\x00\x00"
            } else {
                b"\x00\x00\xfe\xff"
            }
            .to_vec();
            for word in text.chars().map(u32::from) {
                utf32.extend(if little_endian {
                    word.to_le_bytes()
                } else {
                    word.to_be_bytes()
                });
            }
            assert_eq!(decode(&utf32).as_deref(), Some(text));
        }
    }

    #[test]
    fn declared_legacy_encodings_and_meta_precedence() {
        let xml = b"<?xml version='1.0' encoding='windows-1251'?><p>\xcf\xf0\xe8\xe2\xe5\xf2</p>";
        assert!(decode(xml).unwrap().ends_with("<p>Привет</p>"));

        let latin1 = b"<?xml encoding='windows-1252'?><meta charset=ISO-8859-1><p>\x80\xe9</p>";
        assert!(decode(latin1).unwrap().ends_with("<p>\u{80}é</p>"));

        let header =
            b"<meta HTTP-EQUIV=Content-Type content='text/html; CHARSET=windows-1252'><p>\x80</p>";
        assert!(decode(header).unwrap().ends_with("<p>€</p>"));

        let ascii = b"<meta charset='us-ascii'><p>\x80</p>";
        assert!(decode(ascii).unwrap().ends_with("<p>\u{fffd}</p>"));
    }

    #[test]
    fn declarations_in_comments_and_raw_text_do_not_win() {
        let source = b"<?xml encoding='windows-1252'?><!-- <meta charset=utf-8> --><script>'<meta charset=utf-8>'</script><p title='<meta charset=utf-8>'>\x80</p>";
        assert!(decode(source).unwrap().ends_with(">€</p>"));
    }

    #[test]
    fn ignores_meta_beyond_the_sniff_window_and_uses_utf8_for_unknown_labels() {
        let mut source = vec![b' '; SNIFF_BYTES];
        source.extend_from_slice(b"<meta charset=windows-1252><p>\x80</p>");
        assert!(decode(&source).unwrap().ends_with("<p>\u{fffd}</p>"));

        let source = b"<?xml encoding='windows-1252'?><meta charset=bogus><p>\x80</p>";
        assert!(decode(source).unwrap().ends_with("<p>\u{fffd}</p>"));
    }

    #[test]
    fn xml_declaration_survives_a_truncated_metadata_probe() {
        let mut source = b"<?xml encoding='windows-1252'?><style>".to_vec();
        source.resize(SNIFF_BYTES + 1, b' ');
        source.extend_from_slice(b"</style><p>\x80</p>");
        assert!(decode(&source).unwrap().ends_with("<p>€</p>"));
    }
}
