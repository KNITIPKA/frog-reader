use crate::chapter_charset;
use crate::chapter_markup::{self, attribute_keys_equal, Document, Kind};
use crate::epub_package::{append_xml_text, xml_attributes, xml_reference};
use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;

const MAGIC: u32 = 0x3156_414e; // NAV1
const FIRST_NAV: u8 = 1;
const FIRST_TOC: u8 = 2;
const MAX_INPUT_BYTES: usize = 16 * 1024 * 1024;
const MAX_RECORDS: usize = 100_000;
const MAX_DEPTH: usize = 512;
const MAX_BYTES: usize = 32 * 1024 * 1024;
const MAX_MISMATCHED_END_STEPS: usize = 10_000;

#[derive(Default)]
struct ListItem {
    parent: Option<usize>,
    direct_link: bool,
    first_span: Option<usize>,
}

struct Link {
    href: String,
    label: usize,
    item: Option<usize>,
    scope: u8,
}

struct Element {
    name: String,
    item: Option<usize>,
    scope: u8,
    captures_text: bool,
}

#[derive(Clone, Copy, Default)]
struct Ancestors {
    linked_depth: u32,
    nearest_unlinked_label: Option<usize>,
}

#[derive(Default)]
struct Navigation {
    elements: Vec<Element>,
    items: Vec<ListItem>,
    links: Vec<Link>,
    labels: Vec<String>,
    active_labels: Vec<usize>,
    found_scopes: u8,
    text_bytes: usize,
    mismatched_end_steps: usize,
}

impl Navigation {
    fn start_xml(&mut self, tag: &BytesStart<'_>) -> Option<()> {
        let name = tag.name().into_inner();
        let parent_scope = self.elements.last().map_or(0, |element| element.scope);
        let epub_type = if self.found_scopes & FIRST_TOC == 0 {
            let [value] = xml_attributes(tag, [b"epub:type"])?;
            (!value.is_empty()).then_some(value)
        } else {
            None
        };
        let scope_for_href = parent_scope != 0 || epub_type.as_deref().is_some_and(has_toc_token);
        let href = if name.eq_ignore_ascii_case("a") && scope_for_href && has_href(tag) {
            let [value] = xml_attributes(tag, [b"href"])?;
            Some(value)
        } else {
            None
        };
        self.start_element(name, epub_type.as_deref(), href)
    }

    fn start_node(&mut self, name: &str, attrs: &[(String, String)]) -> Option<()> {
        let parent_scope = self.elements.last().map_or(0, |element| element.scope);
        let epub_type = (self.found_scopes & FIRST_TOC == 0)
            .then(|| attr_value_ignore_case(attrs, "epub:type"))
            .flatten();
        let scope_for_href = parent_scope != 0 || epub_type.is_some_and(has_toc_token);
        let href = if name.eq_ignore_ascii_case("a") && scope_for_href {
            attr_value_ignore_case(attrs, "href").map(str::to_owned)
        } else {
            None
        };
        self.start_element(name, epub_type, href)
    }

    fn start_element(
        &mut self,
        name: &str,
        epub_type: Option<&str>,
        href: Option<String>,
    ) -> Option<()> {
        if self.elements.len() == MAX_DEPTH {
            return None;
        }
        let mut item = self.elements.last().and_then(|element| element.item);
        let mut scope = self.elements.last().map_or(0, |element| element.scope);
        let direct_item = self
            .elements
            .last()
            .filter(|parent| parent.name == "li")
            .and_then(|parent| parent.item);

        if self.found_scopes & FIRST_NAV == 0 && name.eq_ignore_ascii_case("nav") {
            scope |= FIRST_NAV;
            self.found_scopes |= FIRST_NAV;
        }
        if self.found_scopes & FIRST_TOC == 0 && epub_type.is_some_and(has_toc_token) {
            scope |= FIRST_TOC;
            self.found_scopes |= FIRST_TOC;
        }

        let mut captures_text = false;
        if name == "li" {
            if self.items.len() == MAX_RECORDS {
                return None;
            }
            self.items.push(ListItem {
                parent: item,
                ..ListItem::default()
            });
            item = Some(self.items.len() - 1);
        } else if name == "span" {
            if let Some(direct) = direct_item {
                if self.items[direct].first_span.is_none() {
                    self.items[direct].first_span = Some(self.start_label()?);
                    captures_text = true;
                }
            }
        } else if name.eq_ignore_ascii_case("a") {
            if let Some(direct) = direct_item {
                self.items[direct].direct_link |= name == "a";
            }
            if scope != 0 {
                if let Some(href) = href {
                    if self.links.len() == MAX_RECORDS {
                        return None;
                    }
                    self.charge_text(href.len())?;
                    let label = self.start_label()?;
                    self.links.push(Link {
                        href,
                        label,
                        item,
                        scope,
                    });
                    captures_text = true;
                }
            }
        } else if name.eq_ignore_ascii_case("br") {
            self.append_text(" ", false)?;
        }

        self.elements.push(Element {
            name: name.to_owned(),
            item,
            scope,
            captures_text,
        });
        Some(())
    }

    fn start_label(&mut self) -> Option<usize> {
        if self.labels.len() == 2 * MAX_RECORDS {
            return None;
        }
        let label = self.labels.len();
        self.labels.push(String::new());
        self.active_labels.push(label);
        Some(label)
    }

    fn close_last(&mut self) -> Option<()> {
        if self.elements.pop()?.captures_text {
            self.active_labels.pop();
        }
        Some(())
    }

    fn end(&mut self, name: &str) -> Option<bool> {
        let distance = self
            .elements
            .iter()
            .rev()
            .position(|element| element.name == name);
        self.mismatched_end_steps += distance.unwrap_or(self.elements.len());
        if self.mismatched_end_steps > MAX_MISMATCHED_END_STEPS {
            return None;
        }
        if let Some(distance) = distance {
            for _ in 0..=distance {
                self.close_last()?;
            }
        }
        Some(distance == Some(0))
    }

    fn has_links_in_scope(&self) -> bool {
        let scope = if self.found_scopes & FIRST_TOC != 0 {
            FIRST_TOC
        } else {
            FIRST_NAV
        };
        self.links
            .iter()
            .any(|link| link.scope & scope != 0 && !self.labels[link.label].trim().is_empty())
    }

    fn charge_text(&mut self, bytes: usize) -> Option<()> {
        self.text_bytes = self.text_bytes.checked_add(bytes)?;
        (self.text_bytes <= MAX_BYTES).then_some(())
    }

    fn append_text(&mut self, text: &str, cdata: bool) -> Option<()> {
        // Malformed nested links can capture the same text. Charge every copy.
        self.charge_text(text.len().checked_mul(self.active_labels.len())?)?;
        for &index in &self.active_labels {
            if cdata {
                self.labels[index].push_str(text);
            } else {
                append_xml_text(&mut self.labels[index], text);
            }
        }
        Some(())
    }

    fn encode(self) -> Option<Vec<u8>> {
        let mut bytes = Vec::from(MAGIC.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        let mut count = 0u32;
        let scope = if self.found_scopes & FIRST_TOC != 0 {
            FIRST_TOC
        } else {
            FIRST_NAV
        };
        // A direct <a> may follow its nested list. Resolve ancestry after the
        // complete document, with each item visited once in source order.
        let ancestry = self.ancestry();
        for link in self
            .links
            .into_iter()
            .filter(|link| link.scope & scope != 0)
        {
            let label = self.labels[link.label].trim();
            if label.is_empty() {
                continue;
            }
            let ancestors = link
                .item
                .and_then(|own_item| self.items[own_item].parent)
                .map_or_else(Ancestors::default, |parent| ancestry[parent]);
            let prefix = ancestors
                .nearest_unlinked_label
                .map_or("", |value| self.labels[value].trim());
            if bytes.len() + 16 + link.href.len() + label.len() + prefix.len() > MAX_BYTES {
                return None;
            }
            bytes.extend_from_slice(&ancestors.linked_depth.to_le_bytes());
            for field in [link.href.as_str(), label, prefix] {
                bytes.extend_from_slice(&(field.len() as u32).to_le_bytes());
                bytes.extend_from_slice(field.as_bytes());
            }
            count += 1;
        }
        bytes[4..8].copy_from_slice(&count.to_le_bytes());
        Some(bytes)
    }

    fn ancestry(&self) -> Vec<Ancestors> {
        let mut ancestry = Vec::with_capacity(self.items.len());
        for item in &self.items {
            let mut inherited = item
                .parent
                .map_or_else(Ancestors::default, |parent| ancestry[parent]);
            if item.direct_link {
                inherited.linked_depth += 1;
            } else if let Some(index) = item
                .first_span
                .filter(|&index| !self.labels[index].trim().is_empty())
            {
                inherited.nearest_unlinked_label = Some(index);
            }
            ancestry.push(inherited);
        }
        ancestry
    }
}

fn has_toc_token(epub_type: &str) -> bool {
    epub_type
        .split_ascii_whitespace()
        .any(|token| token == "toc")
}

fn attr_value_ignore_case<'a>(attrs: &'a [(String, String)], name: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find(|(key, _)| attribute_keys_equal(key, name))
        .map(|(_, value)| value.as_str())
}

fn is_html_void_tag(name: &str) -> bool {
    [
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
        "source", "track", "wbr",
    ]
    .iter()
    .any(|void_tag| name.eq_ignore_ascii_case(void_tag))
}

fn has_href(tag: &BytesStart<'_>) -> bool {
    tag.html_attributes()
        .with_checks(false)
        .flatten()
        .any(|attribute| attribute.key.into_inner().eq_ignore_ascii_case("href"))
}

enum XmlNavResult {
    Clean(Option<Vec<u8>>),
    Fallback,
}

fn parse_nav_xml(input: &str) -> XmlNavResult {
    let mut reader = Reader::from_str(input);
    reader.config_mut().allow_dangling_amp = true;
    reader.config_mut().allow_unmatched_ends = true;
    reader.config_mut().check_end_names = false;
    let mut nav = Navigation::default();
    let mut had_html_quirk = false;
    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                if is_html_void_tag(tag.name().into_inner()) {
                    had_html_quirk = true;
                }
                if nav.start_xml(&tag).is_none() {
                    return XmlNavResult::Fallback;
                }
            }
            Ok(Event::Empty(tag)) => {
                if nav.start_xml(&tag).is_none() || nav.close_last().is_none() {
                    return XmlNavResult::Fallback;
                }
            }
            Ok(Event::End(tag)) => match nav.end(tag.name().into_inner()) {
                Some(true) => {}
                Some(false) => had_html_quirk = true,
                None => return XmlNavResult::Fallback,
            },
            Ok(Event::Text(text)) => {
                if nav.append_text(&text, false).is_none() {
                    return XmlNavResult::Fallback;
                }
            }
            Ok(Event::GeneralRef(reference)) if !nav.active_labels.is_empty() => {
                if nav.append_text(&xml_reference(&reference), false).is_none() {
                    return XmlNavResult::Fallback;
                }
            }
            Ok(Event::CData(text)) => {
                if nav.append_text(&text, true).is_none() {
                    return XmlNavResult::Fallback;
                }
            }
            Ok(Event::Eof) => {
                if !nav.elements.is_empty() {
                    had_html_quirk = true;
                }
                break;
            }
            // Some EPUB navigation documents end with damaged or incomplete
            // XHTML. Keep already-parsed rows when only the tail is truncated
            // and no HTML recovery was needed; otherwise fall back to html5ever.
            Err(_) => {
                let at_eof = reader.buffer_position() as usize == input.len();
                if !at_eof || had_html_quirk || !nav.has_links_in_scope() {
                    had_html_quirk = true;
                }
                break;
            }
            _ => {}
        }
    }
    if had_html_quirk {
        XmlNavResult::Fallback
    } else {
        XmlNavResult::Clean(nav.encode())
    }
}

fn next_or_ascend(
    document: &Document,
    nav: &mut Navigation,
    mut current: usize,
) -> Option<Option<usize>> {
    loop {
        if let Some(sibling) = document.nodes[current].next_sibling {
            return Some(Some(sibling));
        }
        let Some(parent) = document.nodes[current].parent else {
            return Some(None);
        };
        if parent == 0 {
            return Some(None);
        }
        if matches!(document.nodes[parent].kind, Kind::Element { .. }) {
            nav.close_last()?;
        }
        current = parent;
    }
}

fn walk_nav_document(document: &Document) -> Option<Vec<u8>> {
    let mut nav = Navigation::default();
    let mut cursor = document.nodes.first().and_then(|root| root.first_child);
    while let Some(index) = cursor {
        let node = document.nodes.get(index)?;
        match &node.kind {
            Kind::Element { name, attrs, .. } => {
                nav.start_node(name, attrs)?;
                if let Some(child) = node.first_child {
                    cursor = Some(child);
                    continue;
                }
                nav.close_last()?;
            }
            Kind::Text(text) => nav.append_text(text, false)?,
            Kind::Cdata(text) => nav.append_text(text, true)?,
            _ => {}
        }
        cursor = next_or_ascend(document, &mut nav, index)?;
    }
    nav.encode()
}

pub(crate) fn nav_bytes(xml: &[u8]) -> Option<Vec<u8>> {
    if xml.len() > MAX_INPUT_BYTES {
        return None;
    }
    let decoded = chapter_charset::decode(xml)?;
    if decoded.len() > MAX_BYTES {
        return None;
    }
    let input = decoded.strip_prefix('\u{feff}').unwrap_or(&decoded);
    match parse_nav_xml(input) {
        XmlNavResult::Clean(bytes) => bytes,
        XmlNavResult::Fallback => {
            let document = chapter_markup::parse(input, true)?;
            walk_nav_document(&document)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(xml: &[u8]) -> Vec<(u32, String, String, String)> {
        let bytes = nav_bytes(xml).unwrap();
        let mut bytes = bytes.as_slice();
        fn number(bytes: &mut &[u8]) -> u32 {
            let (head, tail) = bytes.split_at(size_of::<u32>());
            *bytes = tail;
            u32::from_le_bytes(head.try_into().unwrap())
        }
        fn text(bytes: &mut &[u8]) -> String {
            let length = number(bytes) as usize;
            let (head, tail) = bytes.split_at(length);
            *bytes = tail;
            String::from_utf8(head.to_vec()).unwrap()
        }
        assert_eq!(number(&mut bytes), MAGIC);
        let rows = (0..number(&mut bytes))
            .map(|_| {
                (
                    number(&mut bytes),
                    text(&mut bytes),
                    text(&mut bytes),
                    text(&mut bytes),
                )
            })
            .collect();
        assert!(bytes.is_empty());
        rows
    }

    #[test]
    fn chooses_typed_scope_and_preserves_duplicate_destinations_and_ancestor_labels() {
        let xml = br#"<html><body>
            <nav epub:type="landmarks"><a href="ignored.xhtml">Ignored</a></nav>
            <nav epub:type="feature toc"><ol><li><span>Volume <em>I</em></span>
                <ol><li><ol><li><a href="part+one.xhtml#same">Nested<br/>One</a></li></ol>
                <a href="part+one.xhtml#same">Primary &amp; Alternate</a></li></ol>
            </li></ol></nav>
            <nav epub:type="toc"><a href="ignored.xhtml">Second TOC</a></nav>
            </body></html>"#;
        let rows = rows(xml);
        assert_eq!(rows.len(), 2);
        assert_eq!(
            rows[0],
            (
                1,
                "part+one.xhtml#same".into(),
                "Nested One".into(),
                "Volume I".into()
            )
        );
        assert_eq!(
            rows[1],
            (
                0,
                "part+one.xhtml#same".into(),
                "Primary & Alternate".into(),
                "Volume I".into()
            )
        );
    }

    #[test]
    fn first_nav_recovers_links_with_unquoted_attributes_and_missing_closures() {
        let xml = br#"<html><nav><ol><li><a HREF=one.xhtml?q=1&amp;follow=2>One & Two</a>
            <ol><li><a href=extra.xhtml>Extra</a></ol></nav><nav><a href=ignored.xhtml>Ignored</a>"#;
        let rows = rows(xml);
        assert_eq!(rows.len(), 2);
        assert_eq!(
            rows[0],
            (
                0,
                "one.xhtml?q=1&follow=2".into(),
                "One & Two".into(),
                "".into()
            )
        );
        assert_eq!(
            rows[1],
            (1, "extra.xhtml".into(), "Extra".into(), "".into())
        );
    }

    #[test]
    fn decodes_windows_1251_and_utf16_nav_documents() {
        let win1251_head = b"<!DOCTYPE html><html><head><meta charset=\"windows-1251\"></head>\
            <body><nav epub:type=\"toc\"><ol><li><span>\xd7\xe0\xf1\xf2\xfc I</span>\
            <ol><li><a href=\"ch1.xhtml\">\xc3\xeb\xe0\xe2\xe0 1</a></li></ol></li></ol></nav></body></html>";
        let win1251_rows = rows(win1251_head);
        assert_eq!(
            win1251_rows,
            vec![(0, "ch1.xhtml".into(), "Глава 1".into(), "Часть I".into())]
        );

        let win1251_xml = b"<?xml version=\"1.0\" encoding=\"windows-1251\"?>\
            <html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\
            <body><nav epub:type=\"toc\"><ol><li><a href=\"intro.xhtml\">\xcf\xf0\xe5\xe4\xe8\xf1\xeb\xee\xe2\xe8\xe5</a></li></ol></nav></body></html>";
        assert_eq!(
            rows(win1251_xml),
            vec![(0, "intro.xhtml".into(), "Предисловие".into(), "".into())]
        );

        let utf16_source = r#"<?xml version="1.0" encoding="utf-16"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="u16.xhtml#top">Первая глава</a></li>
                  </ol>
                </nav>
              </body>
            </html>"#;
        let mut utf16le = vec![0xff, 0xfe];
        utf16le.extend(utf16_source.encode_utf16().flat_map(u16::to_le_bytes));
        assert_eq!(
            rows(&utf16le),
            vec![(0, "u16.xhtml#top".into(), "Первая глава".into(), "".into())]
        );

        let mut utf16be = vec![0xfe, 0xff];
        utf16be.extend(utf16_source.encode_utf16().flat_map(u16::to_be_bytes));
        assert_eq!(
            rows(&utf16be),
            vec![(0, "u16.xhtml#top".into(), "Первая глава".into(), "".into())]
        );
    }

    #[test]
    fn recovers_malformed_html5_nav_with_script_void_tags_and_unclosed_list_items() {
        let html5 = br#"<!DOCTYPE html>
            <html>
              <head>
                <meta charset="utf-8">
                <link rel="stylesheet" href="nav.css">
                <script>if (1 < 2) { var fake = "<nav epub:type='toc'><a href='bad.xhtml'>Bad</a></nav>"; }</script>
              </head>
              <body>
                <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">Cover</a></ol></nav>
                <nav epub:type="toc">
                  <ol>
                    <li><span>Section<br>Alpha</span>
                      <ol>
                        <li><a href="a.xhtml"><b>One &amp; Done</a></b>
                        <li><a href="b.xhtml"><img src="dot.png">Two</a>
                      </ol>
                  </ol>
                </nav>
              </body>
            </html>"#;
        assert_eq!(
            rows(html5),
            vec![
                (
                    0,
                    "a.xhtml".into(),
                    "One & Done".into(),
                    "Section Alpha".into()
                ),
                (0, "b.xhtml".into(), "Two".into(), "Section Alpha".into()),
            ]
        );
    }
}
