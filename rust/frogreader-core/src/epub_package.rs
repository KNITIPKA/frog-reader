use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesRef, BytesStart, Event};
use quick_xml::Reader;
use std::borrow::Cow;
use std::mem::size_of;

#[path = "epub_frb.rs"]
pub(crate) mod epub_frb;

const MAX_XML_BYTES: usize = 16 * 1024 * 1024;
const MAX_WIRE_BYTES: usize = 32 * 1024 * 1024;
const MAX_RECORDS: u32 = 100_000;
const MAX_DEPTH: usize = 512;
const MAX_MISMATCHED_END_STEPS: usize = 10_000;
const OPF_MAGIC: u32 = 0x3146_504F;
const ENC_MAGIC: u32 = 0x3143_4E45;

/// EPUB package XML defaults to UTF-8. A Unicode BOM takes precedence.
pub(crate) fn xml_input(bytes: &[u8]) -> Option<Cow<'_, str>> {
    if bytes.len() > MAX_XML_BYTES {
        return None;
    }
    let decoded = match bytes {
        [0, 0, 0xfe, 0xff, rest @ ..] => Cow::Owned(decode_utf32(rest, u32::from_be_bytes)),
        [0xff, 0xfe, 0, 0, rest @ ..] => Cow::Owned(decode_utf32(rest, u32::from_le_bytes)),
        [0xfe, 0xff, rest @ ..] => Cow::Owned(decode_utf16(rest, u16::from_be_bytes)),
        [0xff, 0xfe, rest @ ..] => Cow::Owned(decode_utf16(rest, u16::from_le_bytes)),
        [0xef, 0xbb, 0xbf, rest @ ..] => String::from_utf8_lossy(rest),
        _ => String::from_utf8_lossy(bytes),
    };
    (decoded.len() <= MAX_WIRE_BYTES).then_some(decoded)
}

fn decode_utf16(bytes: &[u8], decode: fn([u8; 2]) -> u16) -> String {
    let (chunks, remaining) = bytes.as_chunks::<2>();
    let values = chunks.iter().copied().map(decode);
    let mut decoded: String = char::decode_utf16(values)
        .map(|value| value.unwrap_or(char::REPLACEMENT_CHARACTER))
        .collect();
    if !remaining.is_empty() {
        decoded.push(char::REPLACEMENT_CHARACTER);
    }
    decoded
}

fn decode_utf32(bytes: &[u8], decode: fn([u8; 4]) -> u32) -> String {
    let (chunks, remaining) = bytes.as_chunks::<4>();
    let mut decoded: String = chunks
        .iter()
        .copied()
        .map(|value| char::from_u32(decode(value)).unwrap_or(char::REPLACEMENT_CHARACTER))
        .collect();
    if !remaining.is_empty() {
        decoded.push(char::REPLACEMENT_CHARACTER);
    }
    decoded
}

fn reader(xml: &str) -> Reader<&[u8]> {
    let mut reader = Reader::from_str(xml);
    let config = reader.config_mut();
    config.allow_dangling_amp = true;
    config.allow_unmatched_ends = true;
    config.check_end_names = false;
    reader
}

/// Reads the first occurrence of each requested attribute. Preserve literal
/// spacing and unknown entity names from older package files.
pub(crate) fn xml_attributes<const N: usize>(
    start: &BytesStart<'_>,
    keys: [&[u8]; N],
) -> Option<[String; N]> {
    let mut values: [Option<String>; N] = std::array::from_fn(|_| None);
    for attribute in start.html_attributes().with_checks(false).flatten() {
        let name = attribute.key.into_inner().as_bytes();
        if let Some(index) = keys.iter().position(|key| key.eq_ignore_ascii_case(name)) {
            if values[index].is_none() {
                values[index] = Some(decode_attribute(&attribute.value)?);
            }
        }
    }
    Some(values.map(Option::unwrap_or_default))
}

fn decode_attribute(value: &str) -> Option<String> {
    let mut parts = value.split('&');
    let mut output = parts.next()?.to_owned();
    for part in parts {
        if let Some((name, rest)) = part.split_once(';') {
            output.push_str(&xml_reference(&BytesRef::new(name)));
            output.push_str(rest);
        } else {
            output.push('&');
            output.push_str(part);
        }
        if output.len() > MAX_WIRE_BYTES {
            return None;
        }
    }
    Some(output)
}

pub(crate) fn xml_reference(reference: &BytesRef<'_>) -> Cow<'static, str> {
    match reference.resolve_char_ref() {
        Ok(Some(value)) => Cow::Owned(value.to_string()),
        Err(_) => Cow::Borrowed("\u{fffd}"),
        Ok(None) => match resolve_predefined_entity(reference) {
            Some(value) => Cow::Borrowed(value),
            None => Cow::Owned(format!("&{reference};", reference = &**reference)),
        },
    }
}

/// EPUB metadata uses the reader's existing visible text normalization.
pub(crate) fn append_xml_text(output: &mut String, input: &str) {
    let mut space = output.ends_with(' ');
    for value in input.chars() {
        match value {
            ' ' | '\t' | '\n' | '\r' | '\x0c' | '\u{00a0}' => {
                if !space {
                    output.push(' ');
                    space = true;
                }
            }
            '\u{200b}' | '\u{00ad}' => {}
            _ => {
                output.push(value);
                space = false;
            }
        }
    }
}

pub(crate) fn container_rootfile(xml: &[u8]) -> Option<String> {
    let input = xml_input(xml)?;
    let mut reader = reader(&input);
    loop {
        match reader.read_event().ok()? {
            Event::Start(start) | Event::Empty(start)
                if start
                    .local_name()
                    .into_inner()
                    .eq_ignore_ascii_case("rootfile") =>
            {
                let [path] = xml_attributes(&start, [b"full-path"])?;
                return (!path.is_empty()).then_some(path);
            }
            Event::Eof => return None,
            _ => {}
        }
    }
}

#[derive(Default)]
struct EncryptedDataBlock {
    uri: Option<String>,
    algorithm: Option<String>,
}

/// Parses `META-INF/encryption.xml` into `(uri, algorithm)` pairs in document order.
pub fn parse_encryption(bytes: &[u8]) -> Option<Vec<(String, String)>> {
    let input = xml_input(bytes)?;
    let mut reader = reader(&input);
    let mut tags = Vec::<String>::new();
    let mut active_blocks = Vec::<usize>::new();
    let mut blocks = Vec::<EncryptedDataBlock>::new();
    let mut total_bytes = size_of::<[u32; 2]>();
    let mut mismatched_end_steps = 0usize;
    while let Ok(event) = reader.read_event() {
        let empty = matches!(event, Event::Empty(_));
        match event {
            Event::Start(start) | Event::Empty(start) => {
                if tags.len() == MAX_DEPTH {
                    return None;
                }
                let local = start.local_name().into_inner();
                if local.eq_ignore_ascii_case("EncryptedData") {
                    if !empty {
                        if blocks.len() == MAX_RECORDS as usize {
                            return None;
                        }
                        active_blocks.push(blocks.len());
                        blocks.push(EncryptedDataBlock::default());
                    }
                } else if local.eq_ignore_ascii_case("EncryptionMethod") {
                    if let Some(&index) = active_blocks.last() {
                        if blocks[index].algorithm.is_none() {
                            let [algorithm] = xml_attributes(&start, [b"Algorithm"])?;
                            total_bytes =
                                total_bytes.checked_add(size_of::<u32>() + algorithm.len())?;
                            if total_bytes > MAX_WIRE_BYTES {
                                return None;
                            }
                            blocks[index].algorithm = Some(algorithm);
                        }
                    }
                } else if local.eq_ignore_ascii_case("CipherReference") {
                    if let Some(&index) = active_blocks.last() {
                        if blocks[index].uri.is_none() {
                            let [uri] = xml_attributes(&start, [b"URI"])?;
                            total_bytes = total_bytes.checked_add(size_of::<u32>() + uri.len())?;
                            if total_bytes > MAX_WIRE_BYTES {
                                return None;
                            }
                            blocks[index].uri = Some(uri);
                        }
                    }
                }
                if !empty {
                    tags.push(local.to_owned());
                }
            }
            Event::End(end) => {
                let end_local = end.local_name().into_inner();
                let distance = tags
                    .iter()
                    .rev()
                    .position(|tag| tag.eq_ignore_ascii_case(end_local));
                mismatched_end_steps += distance.unwrap_or(tags.len());
                if mismatched_end_steps > MAX_MISMATCHED_END_STEPS {
                    return None;
                }
                if let Some(distance) = distance {
                    for _ in 0..=distance {
                        let tag = tags.pop()?;
                        if tag.eq_ignore_ascii_case("EncryptedData") {
                            active_blocks.pop();
                        }
                    }
                }
            }
            Event::Eof => break,
            _ => {}
        }
    }
    let mut entries = Vec::new();
    for block in blocks {
        let (Some(uri), Some(algorithm)) = (block.uri, block.algorithm) else {
            continue;
        };
        if uri.is_empty() || algorithm.is_empty() {
            continue;
        }
        entries.push((uri, algorithm));
    }
    Some(entries)
}

pub(crate) fn encryption_bytes(xml: &[u8]) -> Option<Vec<u8>> {
    let entries = parse_encryption(xml)?;
    let mut records = Records::default();
    let mut size = size_of::<[u32; 2]>();
    for (uri, algorithm) in &entries {
        records.append([uri.as_str(), algorithm.as_str()], &mut size)?;
    }
    let mut out = Vec::with_capacity(size);
    out.extend_from_slice(&ENC_MAGIC.to_le_bytes());
    out.extend_from_slice(&records.count.to_le_bytes());
    out.extend(records.bytes);
    Some(out)
}

pub(crate) fn package_bytes(xml: &[u8]) -> Option<Vec<u8>> {
    let input = xml_input(xml)?;
    let mut reader = reader(&input);
    let mut tags = Vec::<String>::new();
    let mut metadata = None::<MetadataRow>;
    let mut package = Package::default();
    let mut mismatched_end_steps = 0;
    loop {
        let event = reader.read_event().ok()?;
        let empty = matches!(event, Event::Empty(_));
        match event {
            Event::Start(start) | Event::Empty(start) => {
                if tags.len() == MAX_DEPTH {
                    return None;
                }
                if let Some(row) = &mut metadata {
                    if start.name().into_inner() == "br" && !row.text.is_empty() {
                        append_xml_text(&mut row.text, " ");
                    }
                } else {
                    let depth = tags.len();
                    metadata =
                        package.read_element(tags.last().map(String::as_str), &start, depth)?;
                }
                if empty {
                    package.finish_metadata(&mut metadata, tags.len())?;
                } else {
                    tags.push(start.name().into_inner().to_owned());
                }
            }
            Event::End(end) => {
                // A broken closing tag used to pop through the closest matching
                // ancestor, leaving unknown closing tags alone.
                let distance = tags
                    .iter()
                    .rev()
                    .position(|tag| tag == end.name().into_inner());
                mismatched_end_steps += distance.unwrap_or(tags.len());
                if mismatched_end_steps > MAX_MISMATCHED_END_STEPS {
                    return None;
                }
                if let Some(distance) = distance {
                    let next_depth = tags.len() - distance - 1;
                    package.finish_metadata(&mut metadata, next_depth)?;
                    tags.truncate(next_depth);
                }
            }
            Event::Text(text) => {
                if let Some(row) = &mut metadata {
                    append_xml_text(&mut row.text, &text);
                }
            }
            Event::GeneralRef(reference) => {
                if let Some(row) = &mut metadata {
                    append_xml_text(&mut row.text, &xml_reference(&reference));
                }
            }
            Event::CData(text) => {
                if let Some(row) = &mut metadata {
                    row.text.push_str(&text);
                }
            }
            Event::Eof => {
                package.finish_metadata(&mut metadata, 0)?;
                return package.finish();
            }
            _ => {}
        }
        let text_len = metadata.as_ref().map_or(0, |row| row.text.len());
        if package.size + text_len > MAX_WIRE_BYTES {
            return None;
        }
    }
}

struct MetadataRow {
    depth: usize,
    tag: String,
    attrs: [String; 7],
    text: String,
}

#[derive(Default)]
struct Records {
    count: u32,
    bytes: Vec<u8>,
}

impl Records {
    fn append<const N: usize>(&mut self, fields: [&str; N], size: &mut usize) -> Option<()> {
        if self.count == MAX_RECORDS {
            return None;
        }
        let bytes = wire_size(fields);
        if bytes > MAX_WIRE_BYTES - *size {
            return None;
        }
        *size += bytes;
        self.count += 1;
        write_strings(&mut self.bytes, fields);
        Some(())
    }
}

struct Package {
    headers: Option<[String; 2]>,
    spine_attrs: Option<[String; 2]>,
    size: usize,
    metadata: Records,
    manifest: Records,
    itemrefs: Records,
}

impl Default for Package {
    fn default() -> Self {
        Self {
            headers: None,
            spine_attrs: None,
            // Magic, four string lengths and three record counts.
            size: size_of::<[u32; 8]>(),
            metadata: Records::default(),
            manifest: Records::default(),
            itemrefs: Records::default(),
        }
    }
}

impl Package {
    fn read_element(
        &mut self,
        parent: Option<&str>,
        start: &BytesStart<'_>,
        depth: usize,
    ) -> Option<Option<MetadataRow>> {
        let name = start.local_name().into_inner();
        if name.eq_ignore_ascii_case("package") && self.headers.is_none() {
            let attrs = xml_attributes(start, [b"version", b"unique-identifier"])?;
            self.size += attrs.iter().map(String::len).sum::<usize>();
            self.headers = Some(attrs);
        }
        if name.eq_ignore_ascii_case("spine") && self.spine_attrs.is_none() {
            let attrs = xml_attributes(start, [b"toc", b"page-progression-direction"])?;
            self.size += attrs.iter().map(String::len).sum::<usize>();
            self.spine_attrs = Some(attrs);
        }
        if let Some(parent) = parent {
            let parent = parent.split_once(':').map_or(parent, |(_, local)| local);
            if parent.eq_ignore_ascii_case("metadata") {
                return Some(Some(MetadataRow {
                    depth,
                    tag: start.name().into_inner().to_owned(),
                    attrs: xml_attributes(
                        start,
                        [
                            b"id",
                            b"opf:role",
                            b"opf:scheme",
                            b"name",
                            b"property",
                            b"refines",
                            b"content",
                        ],
                    )?,
                    text: String::new(),
                }));
            }
            if parent.eq_ignore_ascii_case("manifest") && name.eq_ignore_ascii_case("item") {
                let attrs = xml_attributes(
                    start,
                    [b"id", b"href", b"media-type", b"properties", b"fallback"],
                )?;
                self.manifest
                    .append(attrs.each_ref().map(String::as_str), &mut self.size)?;
            }
            if parent.eq_ignore_ascii_case("spine") && name.eq_ignore_ascii_case("itemref") {
                let attrs = xml_attributes(start, [b"idref", b"linear", b"id", b"properties"])?;
                self.itemrefs
                    .append(attrs.each_ref().map(String::as_str), &mut self.size)?;
            }
        }
        Some(None)
    }

    fn finish_metadata(
        &mut self,
        active: &mut Option<MetadataRow>,
        next_depth: usize,
    ) -> Option<()> {
        if active.as_ref().is_some_and(|row| row.depth >= next_depth) {
            let row = active.take()?;
            let [id, role, scheme, name, property, refines, content] =
                row.attrs.each_ref().map(String::as_str);
            self.metadata.append(
                [
                    &row.tag,
                    row.text.trim_matches(|c| c <= ' '),
                    id,
                    role,
                    scheme,
                    name,
                    property,
                    refines,
                    content,
                ],
                &mut self.size,
            )?;
        }
        Some(())
    }

    fn finish(self) -> Option<Vec<u8>> {
        let headers = self.headers?;
        let spine = self.spine_attrs.unwrap_or_default();
        let mut out = Vec::with_capacity(self.size);
        out.extend_from_slice(&OPF_MAGIC.to_le_bytes());
        write_strings(&mut out, headers.each_ref().map(String::as_str));
        write_strings(&mut out, spine.each_ref().map(String::as_str));
        for records in [self.metadata, self.manifest, self.itemrefs] {
            out.extend_from_slice(&records.count.to_le_bytes());
            out.extend(records.bytes);
        }
        Some(out)
    }
}

fn wire_size<const N: usize>(fields: [&str; N]) -> usize {
    fields
        .iter()
        .map(|value| size_of::<u32>() + value.len())
        .sum()
}

fn write_strings<const N: usize>(out: &mut Vec<u8>, fields: [&str; N]) {
    for value in fields {
        out.extend_from_slice(&(value.len() as u32).to_le_bytes());
        out.extend_from_slice(value.as_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn container_rootfile_decodes_bom_namespaces_and_attribute_entities() {
        let source = "<ocf:container><ocf:rootfile full-path='OPS/&eacute; &amp; +.opf'/>\
            <ocf:rootfile full-path='other.opf'/></ocf:container>";
        let mut xml = vec![0xff, 0xfe];
        xml.extend(source.encode_utf16().flat_map(u16::to_le_bytes));
        assert_eq!(container_rootfile(&xml).as_deref(), Some("OPS/é & +.opf"));
    }

    #[test]
    fn package_wire_preserves_refs_and_bounds_malformed_closing_tags() {
        let xml = br#"<opf:package version="3.2" unique-identifier="book">
            <opf:metadata><dc:title> A <em>title</em>&#160;&amp; name </dc:title></opf:metadata>
            <opf:manifest><opf:item id="a" href="part.xhtml" media-type="application/xhtml+xml"/></opf:manifest>
            <opf:spine toc="toc" page-progression-direction="rtl">
              <opf:itemref idref="a" linear="no"/><opf:itemref idref="a" id="repeat"/>
            </opf:spine></opf:package>"#;
        let mut expected = Vec::from(OPF_MAGIC.to_le_bytes());
        write_strings(&mut expected, ["3.2", "book", "toc", "rtl"]);
        expected.extend_from_slice(&1u32.to_le_bytes());
        write_strings(
            &mut expected,
            ["dc:title", "A title & name", "", "", "", "", "", "", ""],
        );
        expected.extend_from_slice(&1u32.to_le_bytes());
        write_strings(
            &mut expected,
            ["a", "part.xhtml", "application/xhtml+xml", "", ""],
        );
        expected.extend_from_slice(&2u32.to_le_bytes());
        write_strings(&mut expected, ["a", "no", "", ""]);
        write_strings(&mut expected, ["a", "", "repeat", ""]);
        assert_eq!(package_bytes(xml), Some(expected));

        let malformed = format!(
            "<package>{}{}</package>",
            "<div>".repeat(500),
            "</missing>".repeat(100)
        );
        assert!(package_bytes(malformed.as_bytes()).is_none());
    }

    #[test]
    fn parse_encryption_handles_namespaces_multiple_entries_and_missing_attributes() {
        let xml = br#"<?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
              <enc:EncryptedData>
                <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                <enc:KeyInfo><enc:KeyName>book-id</enc:KeyName></enc:KeyInfo>
                <enc:CipherData>
                  <enc:CipherReference URI="OEBPS/Fonts/Body&amp;1.otf"/>
                  <enc:CipherReference URI="OEBPS/Fonts/IgnoredSecond.otf"/>
                </enc:CipherData>
                <enc:EncryptionMethod Algorithm="http://ignored.example/second"/>
              </enc:EncryptedData>
              <EncryptedData>
                <EncryptionMethod/>
                <CipherData><CipherReference URI="OEBPS/Fonts/NoAlg.otf"/></CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm=""/>
                <CipherData><CipherReference URI="OEBPS/Fonts/EmptyAlg.otf"/></CipherData>
              </EncryptedData>
              <EncryptedData>
                <EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
                <CipherData><CipherReference/></CipherData>
              </EncryptedData>
              <xenc:encrypteddata xmlns:xenc="http://www.w3.org/2001/04/xmlenc#">
                <xenc:cipherdata>
                  <xenc:cipherreference uri="Fonts/Title%20Bold.woff"/>
                </xenc:cipherdata>
                <xenc:encryptionmethod algorithm="http://ns.adobe.com/pdf/enc#RC"/>
              </xenc:encrypteddata>
            </encryption>"#;

        let parsed = parse_encryption(xml).expect("valid encryption.xml");
        assert_eq!(
            parsed,
            vec![
                (
                    "OEBPS/Fonts/Body&1.otf".to_string(),
                    "http://www.idpf.org/2008/embedding".to_string(),
                ),
                (
                    "Fonts/Title%20Bold.woff".to_string(),
                    "http://ns.adobe.com/pdf/enc#RC".to_string(),
                ),
            ]
        );

        let mut expected_wire = Vec::from(ENC_MAGIC.to_le_bytes());
        expected_wire.extend_from_slice(&2u32.to_le_bytes());
        write_strings(
            &mut expected_wire,
            [
                "OEBPS/Fonts/Body&1.otf",
                "http://www.idpf.org/2008/embedding",
            ],
        );
        write_strings(
            &mut expected_wire,
            ["Fonts/Title%20Bold.woff", "http://ns.adobe.com/pdf/enc#RC"],
        );
        assert_eq!(encryption_bytes(xml), Some(expected_wire));
    }

    #[test]
    fn parse_encryption_recovers_malformed_xml_and_enforces_bounds() {
        let malformed = br#"<encryption>
            <enc:EncryptedData>
              <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding">
              <enc:CipherData>
                <enc:CipherReference URI="Fonts/Unclosed.ttf">
              </enc:CipherData>
            </EncryptedData>
            <EncryptedData>
              <EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC"/>
              <CipherData><CipherReference URI="Fonts/TruncatedTail.otf"/><unclosed"#;

        assert_eq!(
            parse_encryption(malformed),
            Some(vec![
                (
                    "Fonts/Unclosed.ttf".to_string(),
                    "http://www.idpf.org/2008/embedding".to_string(),
                ),
                (
                    "Fonts/TruncatedTail.otf".to_string(),
                    "http://ns.adobe.com/pdf/enc#RC".to_string(),
                ),
            ])
        );

        let excessive_mismatch = format!(
            "<encryption>{}{}</encryption>",
            "<div>".repeat(500),
            "</missing>".repeat(100)
        );
        assert!(parse_encryption(excessive_mismatch.as_bytes()).is_none());
    }
}
