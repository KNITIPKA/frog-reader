use crate::epub_package::{append_xml_text, xml_attributes, xml_input, xml_reference};
use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;

const MAGIC: u32 = 0x3158_434e; // NCX1
const MAX_INPUT_BYTES: usize = 16 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 32 * 1024 * 1024;
const MAX_POINTS: usize = 100_000;
const MAX_NESTING: usize = 512;
const MAX_MISMATCHED_END_STEPS: usize = 10_000;

#[derive(Default)]
struct Point {
    depth: u32,
    source: Option<usize>,
    title: Option<usize>,
}

struct Element {
    name: String,
    starts_title: bool,
}

#[derive(Default)]
struct Navigation {
    elements: Vec<Element>,
    active_points: Vec<usize>,
    active_titles: Vec<usize>,
    points: Vec<Point>,
    titles: Vec<String>,
    sources: Vec<String>,
    depth: u32,
    text_bytes: usize,
    mismatched_end_steps: usize,
}

impl Navigation {
    fn start(&mut self, tag: &BytesStart<'_>) -> Option<()> {
        if self.elements.len() == MAX_NESTING {
            return None;
        }
        let name = tag.name().into_inner();
        let mut starts_title = false;
        if name.eq_ignore_ascii_case("navPoint") {
            if self.points.len() == MAX_POINTS {
                return None;
            }
            self.active_points.push(self.points.len());
            self.points.push(Point {
                depth: self.depth,
                ..Point::default()
            });
            if name == "navPoint" {
                self.depth += 1;
            }
        } else if name.eq_ignore_ascii_case("content") {
            if self.claim_first(|point| &mut point.source, self.sources.len()) {
                let [src] = xml_attributes(tag, [b"src"])?;
                self.sources.push(src);
            }
        } else if name.eq_ignore_ascii_case("text")
            && self
                .elements
                .last()
                .is_some_and(|parent| parent.name.eq_ignore_ascii_case("navLabel"))
            && self.claim_first(|point| &mut point.title, self.titles.len())
        {
            self.active_titles.push(self.titles.len());
            self.titles.push(String::new());
            starts_title = true;
        } else if name.eq_ignore_ascii_case("br") {
            for &index in &self.active_titles {
                let title = &mut self.titles[index];
                if !title.is_empty() && !title.ends_with(' ') {
                    self.text_bytes += 1;
                    if self.text_bytes > MAX_OUTPUT_BYTES {
                        return None;
                    }
                    title.push(' ');
                }
            }
        }
        self.elements.push(Element {
            name: name.to_owned(),
            starts_title,
        });
        Some(())
    }

    // Each point uses the first matching descendant. Once an active point has
    // a value, its ancestors have each seen a matching descendant as well.
    fn claim_first(
        &mut self,
        slot: fn(&mut Point) -> &mut Option<usize>,
        value_index: usize,
    ) -> bool {
        let mut used = false;
        for &point in self.active_points.iter().rev() {
            let candidate = slot(&mut self.points[point]);
            if candidate.is_some() {
                break;
            }
            *candidate = Some(value_index);
            used = true;
        }
        used
    }

    fn end(&mut self, name: &str) -> Option<()> {
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
                let element = self.elements.pop()?;
                if element.starts_title {
                    self.active_titles.pop();
                }
                if element.name.eq_ignore_ascii_case("navPoint") {
                    self.active_points.pop();
                    if element.name == "navPoint" {
                        self.depth -= 1;
                    }
                }
            }
        }
        Some(())
    }

    fn append_text(&mut self, text: &str, cdata: bool) -> Option<()> {
        // Bound normalization work and storage when malformed nesting causes
        // several navigation titles to capture the same text.
        self.text_bytes += text.len().checked_mul(self.active_titles.len())?;
        if self.text_bytes > MAX_OUTPUT_BYTES {
            return None;
        }
        for &index in &self.active_titles {
            let title = &mut self.titles[index];
            if cdata {
                title.push_str(text);
            } else {
                append_xml_text(title, text);
            }
        }
        Some(())
    }

    fn encode(self) -> Option<Vec<u8>> {
        let mut bytes = Vec::from(MAGIC.to_le_bytes());
        bytes.extend_from_slice(&0u32.to_le_bytes());
        let mut count = 0u32;
        for point in self.points {
            let (Some(src), Some(title)) = (point.source, point.title) else {
                continue;
            };
            let src = &self.sources[src];
            let title = self.titles[title].trim();
            if src.is_empty() || title.is_empty() {
                continue;
            }
            if bytes.len() + 12 + src.len() + title.len() > MAX_OUTPUT_BYTES {
                return None;
            }
            bytes.extend_from_slice(&point.depth.to_le_bytes());
            bytes.extend_from_slice(&(src.len() as u32).to_le_bytes());
            bytes.extend_from_slice(src.as_bytes());
            bytes.extend_from_slice(&(title.len() as u32).to_le_bytes());
            bytes.extend_from_slice(title.as_bytes());
            count += 1;
        }
        bytes[4..8].copy_from_slice(&count.to_le_bytes());
        Some(bytes)
    }
}

pub(crate) fn ncx_bytes(xml: &[u8]) -> Option<Vec<u8>> {
    if xml.len() > MAX_INPUT_BYTES {
        return None;
    }
    let input = xml_input(xml)?;
    let mut reader = Reader::from_str(&input);
    // Old NCX files may omit closing tags. Match each explicit end to its open
    // tag and keep the rows already read if a truncated tail cannot be parsed.
    reader.config_mut().check_end_names = false;
    reader.config_mut().allow_unmatched_ends = true;
    let mut navigation = Navigation::default();
    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => navigation.start(&tag)?,
            Ok(Event::Empty(tag)) => {
                navigation.start(&tag)?;
                navigation.end(tag.name().into_inner())?;
            }
            Ok(Event::End(tag)) => navigation.end(tag.name().into_inner())?,
            Ok(Event::Text(text)) if !navigation.active_titles.is_empty() => {
                navigation.append_text(&text, false)?;
            }
            Ok(Event::CData(text)) if !navigation.active_titles.is_empty() => {
                navigation.append_text(&text, true)?;
            }
            Ok(Event::GeneralRef(reference)) if !navigation.active_titles.is_empty() => {
                navigation.append_text(&xml_reference(&reference), false)?;
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    navigation.encode()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rows(xml: &[u8]) -> Vec<(u32, String, String)> {
        fn word(bytes: &mut &[u8]) -> u32 {
            let (value, rest) = bytes.split_at(4);
            *bytes = rest;
            u32::from_le_bytes(value.try_into().unwrap())
        }
        fn string(bytes: &mut &[u8]) -> String {
            let size = word(bytes) as usize;
            let (value, rest) = bytes.split_at(size);
            *bytes = rest;
            std::str::from_utf8(value).unwrap().into()
        }
        let encoded = ncx_bytes(xml).unwrap();
        let mut bytes = encoded.as_slice();
        assert_eq!(word(&mut bytes), MAGIC);
        let rows = (0..word(&mut bytes))
            .map(|_| (word(&mut bytes), string(&mut bytes), string(&mut bytes)))
            .collect();
        assert!(bytes.is_empty());
        rows
    }

    #[test]
    fn keeps_publisher_order_raw_targets_repeated_destinations_and_depth() {
        let utf16: Vec<u8> = r#"<?xml version="1.0" encoding="UTF-16"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
            <navPoint playOrder="9"><navLabel><text> חלק&nbsp; <i>א</i><br/> A &amp; B </text></navLabel>
                <content src="book+1.xhtml#%D7%90&amp;b"/>
                <navPoint playOrder="1"><navLabel><text><![CDATA[Part  two]]></text></navLabel>
                    <content src="book+1.xhtml#%D7%90&amp;b"/></navPoint>
            </navPoint>
        </navMap></ncx>"#
            .encode_utf16()
            .flat_map(u16::to_le_bytes)
            .collect();
        assert_eq!(
            rows(&[&[0xff, 0xfe], utf16.as_slice()].concat()),
            vec![
                (0, "book+1.xhtml#%D7%90&b".into(), "חלק א A & B".into()),
                (1, "book+1.xhtml#%D7%90&b".into(), "Part  two".into()),
            ],
        );
    }

    #[test]
    fn missing_parent_fields_take_first_descendant_even_with_truncated_tail() {
        let xml = br#"<ncx><navMap>
            <navPoint><navPoint><navLabel><text>One</text></navLabel><content src="a.xhtml"/></navPoint>
                <navLabel><text>Ignored</text></navLabel><content src="b.xhtml"/>
            </navPoint>
            <navPoint><navLabel><text>Two</text></navLabel><content src="c.xhtml"/><unclosed"#;
        assert_eq!(
            rows(xml),
            vec![
                (0, "a.xhtml".into(), "One".into()),
                (1, "a.xhtml".into(), "One".into()),
                (0, "c.xhtml".into(), "Two".into()),
            ],
        );
    }

    #[test]
    fn bounds_nesting() {
        let xml = "<x>".repeat(MAX_NESTING + 1);
        assert!(ncx_bytes(xml.as_bytes()).is_none());
    }
}
