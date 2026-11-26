#[path = "html_mapper.rs"]
pub(crate) mod mapper;

use html5ever::interface::{ElementFlags, NodeOrText, QuirksMode, TreeSink};
use html5ever::tendril::{StrTendril, TendrilSink};
use html5ever::{Attribute, ExpandedName, QualName};
use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;
use std::borrow::Cow;
use std::cell::RefCell;
use std::collections::HashSet;
use std::mem::size_of;

const XML_NAMESPACE: &str = "http://www.w3.org/XML/1998/namespace";
const HTML_NS: &str = "http://www.w3.org/1999/xhtml";
const SVG_NS: &str = "http://www.w3.org/2000/svg";
const MAX_TREE_BYTES: usize = 128 * 1024 * 1024;
const MAX_XML_DEPTH: usize = 512;
const HTML_CHUNK_BYTES: usize = 64 * 1024;
const DOCUMENT: usize = 0;

#[derive(Debug)]
pub(crate) struct Document {
    pub(crate) html: bool,
    pub(crate) nodes: Vec<Node>,
    pub(crate) preindex: Preindex,
}

impl Document {
    #[inline]
    pub(crate) fn node_meta(&self, id: usize) -> NodeMeta {
        self.preindex.nodes.get(id).copied().unwrap_or_default()
    }

    #[inline]
    pub(crate) fn is_element(&self, id: usize) -> bool {
        self.nodes
            .get(id)
            .is_some_and(|n| matches!(n.kind, Kind::Element { .. }))
    }

    #[inline]
    pub(crate) fn element_name(&self, id: usize) -> Option<&str> {
        match &self.nodes.get(id)?.kind {
            Kind::Element { name, .. } => Some(name.as_str()),
            _ => None,
        }
    }

    #[inline]
    pub(crate) fn normal_name(&self, id: usize) -> Cow<'_, str> {
        match self.element_name(id) {
            Some(name) => normalize_tag(name),
            None => Cow::Borrowed(""),
        }
    }

    #[inline]
    pub(crate) fn attr<'a>(&'a self, id: usize, key: &str) -> Option<&'a str> {
        let Kind::Element { attrs, .. } = &self.nodes.get(id)?.kind else {
            return None;
        };
        attrs
            .iter()
            .find(|(k, _)| attribute_keys_equal(k, key))
            .map(|(_, v)| v.as_str())
    }

    #[inline]
    pub(crate) fn attr_or_empty<'a>(&'a self, id: usize, key: &str) -> &'a str {
        self.attr(id, key).unwrap_or("")
    }

    #[inline]
    pub(crate) fn parent_element(&self, id: usize) -> Option<usize> {
        let parent = self.nodes.get(id)?.parent?;
        self.is_element(parent).then_some(parent)
    }

    #[inline]
    pub(crate) fn first_element_child(&self, id: usize) -> Option<usize> {
        let mut child = self.nodes.get(id)?.first_child;
        while let Some(c) = child {
            if self.is_element(c) {
                return Some(c);
            }
            child = self.nodes[c].next_sibling;
        }
        None
    }

    #[inline]
    pub(crate) fn next_element_sibling(&self, id: usize) -> Option<usize> {
        let mut sib = self.nodes.get(id)?.next_sibling;
        while let Some(s) = sib {
            if self.is_element(s) {
                return Some(s);
            }
            sib = self.nodes[s].next_sibling;
        }
        None
    }

    #[inline]
    pub(crate) fn text_content(&self, id: usize) -> Option<&str> {
        match &self.nodes.get(id)?.kind {
            Kind::Text(s) | Kind::Cdata(s) => Some(s.as_str()),
            _ => None,
        }
    }

    #[allow(dead_code)]
    #[inline]
    pub(crate) fn body_or_book(&self, dtbook: bool) -> Option<usize> {
        if dtbook {
            self.preindex.book_node
        } else {
            self.preindex.body_node
        }
    }

    /// Plain text matching Jsoup `Element.text()` whitespace normalization.
    pub(crate) fn jsoup_text(&self, id: usize) -> String {
        let mut out = String::new();
        let mut stack: Vec<(usize, u8)> = vec![(id, 0)];
        while let Some((curr, preserve_depth)) = stack.pop() {
            let Some(node) = self.nodes.get(curr) else {
                continue;
            };
            match &node.kind {
                Kind::Text(value) => {
                    if preserve_depth > 0 {
                        out.push_str(value);
                    } else {
                        append_normalized_jsoup_ws(&mut out, value);
                    }
                }
                Kind::Cdata(value) => {
                    out.push_str(value);
                }
                Kind::Element {
                    name, namespace, ..
                } => {
                    let normal = normalize_tag(name);
                    let is_preserve = namespace == HTML_NS
                        && matches!(
                            normal.as_ref(),
                            "pre" | "plaintext" | "title" | "textarea" | "script"
                        );
                    let next_preserve = if is_preserve {
                        6
                    } else {
                        preserve_depth.saturating_sub(1)
                    };
                    if curr != id
                        && !out.is_empty()
                        && !out.ends_with(' ')
                        && (normal == "br"
                            || (self.node_meta(curr).tag_flags & TAG_PUBLISHER_BOX) != 0)
                    {
                        out.push(' ');
                    }
                    let start = stack.len();
                    let mut child = node.first_child;
                    while let Some(c) = child {
                        stack.push((c, next_preserve));
                        child = self.nodes[c].next_sibling;
                    }
                    stack[start..].reverse();
                }
                _ => {}
            }
        }
        out.trim_matches(|ch: char| ch <= ' ').to_owned()
    }
}

fn append_normalized_jsoup_ws(out: &mut String, text: &str) {
    let mut last_was_white = out.ends_with(' ');
    for ch in text.chars() {
        if matches!(ch, ' ' | '\t' | '\n' | '\r' | '\u{000c}') {
            if !last_was_white && !out.is_empty() {
                out.push(' ');
                last_was_white = true;
            }
        } else if !matches!(ch, '\u{200B}' | '\u{00AD}') {
            out.push(ch);
            last_was_white = false;
        }
    }
}

#[derive(Debug)]
pub(crate) struct Node {
    pub(crate) kind: Kind,
    pub(crate) first_child: Option<usize>,
    pub(crate) next_sibling: Option<usize>,
    pub(crate) parent: Option<usize>,
    previous_sibling: Option<usize>,
    last_child: Option<usize>,
}

// Element tag role flags (exact match for HtmlMapper tag sets).
pub(crate) const TAG_ROOT: u32 = 1 << 0;
pub(crate) const TAG_PUBLISHER_BOX: u32 = 1 << 1;
pub(crate) const TAG_NON_BLOCK: u32 = 1 << 2;
pub(crate) const TAG_NON_CONTENT: u32 = 1 << 3;
pub(crate) const TAG_NON_TEXT_CONTENT: u32 = 1 << 4;
pub(crate) const TAG_NOTE_ITEM: u32 = 1 << 5;
pub(crate) const TAG_IMAGE: u32 = 1 << 6;
pub(crate) const TAG_HEADING: u32 = 1 << 7;
pub(crate) const TAG_BLOCK_PROBE: u32 = 1 << 8;
pub(crate) const TAG_HORIZONTAL_COMPLEX: u32 = 1 << 9;
pub(crate) const TAG_CSS_ROW_EXCLUDED: u32 = 1 << 10;
pub(crate) const TAG_SIMPLE_INLINE_TEXT_BOX: u32 = 1 << 11;
pub(crate) const TAG_INLINE_TEXT_BLOCK: u32 = 1 << 12;
pub(crate) const TAG_EXPLICIT_INITIAL: u32 = 1 << 13;
pub(crate) const TAG_SEMANTIC_MONOSPACE: u32 = 1 << 14;
pub(crate) const TAG_NESTED_LIST: u32 = 1 << 15;
pub(crate) const TAG_CELL_BLOCK: u32 = 1 << 16;
pub(crate) const TAG_MATH: u32 = 1 << 17;
pub(crate) const TAG_MATH_EXACT: u32 = 1 << 18;
pub(crate) const TAG_IMG: u32 = 1 << 19;
pub(crate) const TAG_SVG: u32 = 1 << 20;
pub(crate) const TAG_SUP: u32 = 1 << 21;
pub(crate) const TAG_A: u32 = 1 << 22;
pub(crate) const TAG_BR: u32 = 1 << 23;
pub(crate) const TAG_CAPTION: u32 = 1 << 24;
pub(crate) const TAG_NOTE_PRODNOTE: u32 = 1 << 25;
pub(crate) const TAG_STYLE: u32 = 1 << 26;
pub(crate) const TAG_LINK: u32 = 1 << 27;
pub(crate) const TAG_SCRIPT: u32 = 1 << 28;
pub(crate) const TAG_INLINE_SEMANTIC: u32 = 1 << 29;
pub(crate) const TAG_TABLE: u32 = 1 << 30;
pub(crate) const TAG_TR: u32 = 1 << 31;

// Element attribute & ancestor flags.
pub(crate) const ATTR_HAS_ID: u32 = 1 << 0;
pub(crate) const ATTR_HAS_SRC: u32 = 1 << 1;
pub(crate) const ATTR_HAS_HREF: u32 = 1 << 2;
pub(crate) const ATTR_HAS_XLINK_HREF: u32 = 1 << 3;
pub(crate) const ATTR_HAS_STYLE: u32 = 1 << 4;
pub(crate) const ATTR_HAS_DIR: u32 = 1 << 5;
pub(crate) const ATTR_HAS_LANG: u32 = 1 << 6;
pub(crate) const ATTR_TYPE_IS_NOTE: u32 = 1 << 7;
pub(crate) const ATTR_EPUB_NOTEREF: u32 = 1 << 8;
pub(crate) const ATTR_ROLE_DOC_NOTEREF: u32 = 1 << 9;
pub(crate) const ATTR_NOTE_CONTAINER_TOKEN: u32 = 1 << 10;
pub(crate) const ATTR_NOTE_COLLECTION_TOKEN: u32 = 1 << 11;
pub(crate) const ATTR_IS_SEMANTIC_NOTE_CONTAINER: u32 = 1 << 12;
pub(crate) const ATTR_HAS_NOTE_HINT: u32 = 1 << 13;
pub(crate) const ATTR_HAS_EMPTY_LINE_CLASS: u32 = 1 << 14;
pub(crate) const ATTR_IS_DISPLAY_MATH: u32 = 1 << 15;
pub(crate) const ATTR_IS_BLOCK_PROBE_MATH: u32 = 1 << 16;
pub(crate) const ATTR_ANC_HAS_SUP: u32 = 1 << 17;
pub(crate) const ATTR_ANC_HAS_LANG: u32 = 1 << 18;
pub(crate) const ATTR_ANC_HAS_DIR: u32 = 1 << 19;
pub(crate) const ATTR_ANC_HAS_FLOW_SCOPE: u32 = 1 << 20;
pub(crate) const TITLE_CLASS_SHIFT: u32 = 21;
pub const ATTR_HAS_PRESENTATIONAL: u32 = 1 << 24;

// Subtree summary flags (self or descendant).
pub(crate) const SUBTREE_HAS_BLOCK_PROBE: u32 = 1 << 0;
pub(crate) const SUBTREE_HAS_STYLE: u32 = 1 << 1;
pub(crate) const SUBTREE_HAS_ID: u32 = 1 << 2;
pub(crate) const SUBTREE_HAS_IMG: u32 = 1 << 3;
pub(crate) const SUBTREE_HAS_SVG: u32 = 1 << 4;
pub(crate) const SUBTREE_HAS_HORIZONTAL_COMPLEX: u32 = 1 << 5;
pub(crate) const SUBTREE_HAS_SUP: u32 = 1 << 6;
pub(crate) const SUBTREE_HAS_CAPTION: u32 = 1 << 7;
pub(crate) const SUBTREE_HAS_NESTED_LIST: u32 = 1 << 8;
pub(crate) const SUBTREE_HAS_A_HREF: u32 = 1 << 9;
pub(crate) const SUBTREE_HAS_JAVA_NON_WS_TEXT: u32 = 1 << 10;
pub(crate) const SUBTREE_SIMPLE_LEAF_TEXT: u32 = 1 << 11;
pub const SUBTREE_HAS_INLINE_FLOW_CANDIDATE: u32 = 1 << 12;

// Text node whitespace compaction hints.
pub(crate) const TEXT_HTML_BLANK: u32 = 1 << 0;
pub(crate) const TEXT_COMPACT: u32 = 1 << 1;
pub(crate) const TEXT_ALREADY_COLLAPSED_ASCII: u32 = TEXT_COMPACT;
pub(crate) const TEXT_STARTS_HTML_WS: u32 = 1 << 2;
pub(crate) const TEXT_ENDS_HTML_WS: u32 = 1 << 3;
pub(crate) const TEXT_HAS_JAVA_NON_WS: u32 = 1 << 4;
pub(crate) const TEXT_HAS_CR: u32 = 1 << 5;
const TEXT_HAS_VISIBLE_NON_WS: u32 = 1 << 6;
pub(crate) const TEXT_HAS_KOTLIN_NON_WS: u32 = 1 << 7;

// Document summary flags.
pub(crate) const DOC_HAS_MATH: u32 = 1 << 0;
pub(crate) const DOC_HAS_SVG: u32 = 1 << 1;
pub(crate) const DOC_HAS_SCRIPT: u32 = 1 << 2;
pub(crate) const DOC_MAYBE_VERTICAL_WRITING: u32 = 1 << 3;
pub(crate) const DOC_HAS_ANY_STYLE: u32 = 1 << 4;
pub(crate) const DOC_HAS_ANY_LANG: u32 = 1 << 5;
pub(crate) const DOC_HAS_ANY_DIR: u32 = 1 << 6;
pub(crate) const DOC_HAS_ANY_ID: u32 = 1 << 7;
pub(crate) const DOC_HAS_ANY_A_HREF: u32 = 1 << 8;
pub const DOC_HAS_ANY_PRESENTATIONAL: u32 = 1 << 9;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct NodeMeta {
    pub(crate) tag_flags: u32,
    pub(crate) attr_flags: u32,
    pub(crate) subtree_flags: u32,
    pub(crate) text_flags: u32,
    pub(crate) is_stylesheet: bool,
}

#[derive(Debug, Default)]
pub(crate) struct Preindex {
    pub(crate) nodes: Vec<NodeMeta>,
    pub(crate) doc_flags: u32,
    pub(crate) body_node: Option<usize>,
    pub(crate) book_node: Option<usize>,
    pub(crate) head_title_node: Option<usize>,
    pub(crate) viewport_meta_node: Option<usize>,
}

#[derive(Debug)]
pub(crate) enum Kind {
    Document,
    Element {
        name: String,
        namespace: String,
        attrs: Vec<(String, String)>,
        self_closing: bool,
    },
    Text(String),
    Data(String),
    Cdata(String),
    Comment(String),
    Doctype {
        name: String,
        public_id: String,
        system_id: String,
    },
    Pi {
        target: String,
        data: String,
    },
}

impl Kind {
    fn payload_bytes(&self) -> usize {
        match self {
            Self::Document => 0,
            Self::Element {
                name,
                namespace,
                attrs,
                ..
            } => {
                name.len()
                    + namespace.len()
                    + attrs
                        .iter()
                        .map(|(key, value)| size_of::<(String, String)>() + key.len() + value.len())
                        .sum::<usize>()
            }
            Self::Text(text) | Self::Data(text) | Self::Cdata(text) | Self::Comment(text) => {
                text.len()
            }
            Self::Doctype {
                name,
                public_id,
                system_id,
            } => name.len() + public_id.len() + system_id.len(),
            Self::Pi { target, data } => target.len() + data.len(),
        }
    }
}

struct Arena {
    nodes: Vec<Node>,
    bytes: usize,
    exhausted: bool,
}

impl Arena {
    fn new() -> Self {
        let mut arena = Self {
            nodes: Vec::new(),
            bytes: 0,
            exhausted: false,
        };
        arena.insert(Kind::Document);
        arena
    }

    fn charge(&mut self, bytes: usize) -> bool {
        if self.exhausted || bytes > MAX_TREE_BYTES.saturating_sub(self.bytes) {
            self.exhausted = true;
            return false;
        }
        self.bytes += bytes;
        true
    }

    fn insert(&mut self, kind: Kind) -> Option<usize> {
        if !self.charge(size_of::<Node>() + kind.payload_bytes()) {
            return None;
        }
        let index = self.nodes.len();
        self.nodes.push(Node {
            kind,
            first_child: None,
            next_sibling: None,
            parent: None,
            previous_sibling: None,
            last_child: None,
        });
        Some(index)
    }

    fn detach(&mut self, index: usize) {
        let Some(parent) = self.nodes[index].parent.take() else {
            return;
        };
        let previous = self.nodes[index].previous_sibling.take();
        let next = self.nodes[index].next_sibling.take();
        if let Some(previous) = previous {
            self.nodes[previous].next_sibling = next;
        } else {
            self.nodes[parent].first_child = next;
        }
        if let Some(next) = next {
            self.nodes[next].previous_sibling = previous;
        } else {
            self.nodes[parent].last_child = previous;
        }
    }

    fn attach(&mut self, index: usize, parent: usize, next: Option<usize>) {
        self.detach(index);
        let previous = match next {
            Some(next) => self.nodes[next].previous_sibling,
            None => self.nodes[parent].last_child,
        };
        self.nodes[index].parent = Some(parent);
        self.nodes[index].previous_sibling = previous;
        self.nodes[index].next_sibling = next;
        if let Some(previous) = previous {
            self.nodes[previous].next_sibling = Some(index);
        } else {
            self.nodes[parent].first_child = Some(index);
        }
        if let Some(next) = next {
            self.nodes[next].previous_sibling = Some(index);
        } else {
            self.nodes[parent].last_child = Some(index);
        }
    }

    fn push(&mut self, parent: usize, kind: Kind) -> Option<usize> {
        let index = self.insert(kind)?;
        self.attach(index, parent, None);
        Some(index)
    }

    fn append_text(&mut self, parent: usize, next: Option<usize>, text: &str, data: bool) {
        if text.is_empty() || self.exhausted {
            return;
        }
        let previous = match next {
            Some(next) => self.nodes[next].previous_sibling,
            None => self.nodes[parent].last_child,
        };
        if let Some(previous) = previous.filter(|&previous| {
            matches!(
                (&self.nodes[previous].kind, data),
                (Kind::Text(_), false) | (Kind::Data(_), true)
            )
        }) {
            if self.charge(text.len()) {
                match &mut self.nodes[previous].kind {
                    Kind::Text(previous) | Kind::Data(previous) => previous.push_str(text),
                    _ => unreachable!(),
                }
            }
            return;
        }
        let kind = if data {
            Kind::Data(text.to_owned())
        } else {
            Kind::Text(text.to_owned())
        };
        if let Some(index) = self.insert(kind) {
            self.attach(index, parent, next);
        }
    }

    fn reparent(&mut self, from: usize, to: usize) {
        if from != to {
            while let Some(child) = self.nodes[from].first_child {
                self.attach(child, to, None);
            }
        }
    }

    fn finish(self, html: bool) -> Option<Document> {
        if self.exhausted {
            return None;
        }
        let preindex = Preindex::build(&self.nodes)?;
        Some(Document {
            html,
            nodes: self.nodes,
            preindex,
        })
    }
}

pub(crate) fn attribute_keys_equal(left: &str, right: &str) -> bool {
    if left.eq_ignore_ascii_case(right) {
        return true;
    }
    if left.is_ascii() && right.is_ascii()
        || left.encode_utf16().count() != right.encode_utf16().count()
    {
        return false;
    }
    // Jsoup uses String.equalsIgnoreCase for attribute lookups. Compare by
    // character without expanding uppercase mappings such as sharp s to SS.
    let fold = |ch: char| {
        let mut uppercase = ch.to_uppercase();
        let upper = uppercase.next().expect("at least one uppercase character");
        let simple_upper = if uppercase.next().is_none() {
            upper
        } else {
            ch
        };
        simple_upper
            .to_lowercase()
            .next()
            .expect("at least one lowercase character")
    };
    let mut right = right.chars();
    left.chars()
        .all(|a| right.next().is_some_and(|b| a == b || fold(a) == fold(b)))
        && right.next().is_none()
}

pub(crate) fn normalize_tag(name: &str) -> Cow<'_, str> {
    if name.bytes().all(|byte| !byte.is_ascii_uppercase()) && name.is_ascii() {
        Cow::Borrowed(name)
    } else if name.is_ascii() {
        Cow::Owned(name.to_ascii_lowercase())
    } else {
        Cow::Owned(name.to_lowercase())
    }
}

fn classify_tag(normal: &str, local: &str) -> u32 {
    let mut flags = 0u32;
    match normal {
        "body" | "html" | "#root" => flags |= TAG_ROOT,
        _ => {}
    }
    match normal {
        "p" => {
            flags |= TAG_PUBLISHER_BOX
                | TAG_NOTE_ITEM
                | TAG_BLOCK_PROBE
                | TAG_INLINE_TEXT_BLOCK
                | TAG_CELL_BLOCK
        }
        "li" => flags |= TAG_PUBLISHER_BOX | TAG_NOTE_ITEM | TAG_INLINE_TEXT_BLOCK,
        "dt" | "dd" | "pre" => flags |= TAG_PUBLISHER_BOX | TAG_INLINE_TEXT_BLOCK,
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            flags |= TAG_PUBLISHER_BOX | TAG_HEADING | TAG_BLOCK_PROBE | TAG_INLINE_TEXT_BLOCK;
        }
        "levelhd" | "hd" | "bridgehead" | "doctitle" | "covertitle" | "cite" | "poem"
        | "epigraph" | "figcaption" | "summary" | "dl" | "figure" | "center" | "nav"
        | "details" | "dtbook" | "book" | "frontmatter" | "bodymatter" | "rearmatter" | "level"
        | "level1" | "level2" | "level3" | "level4" | "level5" | "level6" | "sidebar"
        | "annotation" | "linegroup" | "imggroup" | "hr" | "byline" | "dateline" | "docauthor"
        | "address" | "article" | "header" | "footer" | "main" => {
            flags |= TAG_PUBLISHER_BOX;
        }
        "caption" => flags |= TAG_PUBLISHER_BOX | TAG_CAPTION,
        "blockquote" => flags |= TAG_PUBLISHER_BOX | TAG_BLOCK_PROBE,
        "ul" | "ol" => {
            flags |= TAG_PUBLISHER_BOX | TAG_BLOCK_PROBE | TAG_HORIZONTAL_COMPLEX | TAG_NESTED_LIST
        }
        "list" => flags |= TAG_PUBLISHER_BOX | TAG_NESTED_LIST,
        "audio" | "video" => flags |= TAG_PUBLISHER_BOX | TAG_HORIZONTAL_COMPLEX,
        "div" => flags |= TAG_PUBLISHER_BOX | TAG_NOTE_ITEM | TAG_BLOCK_PROBE | TAG_CELL_BLOCK,
        "section" | "aside" => flags |= TAG_PUBLISHER_BOX | TAG_NOTE_ITEM,
        "note" | "prodnote" => flags |= TAG_PUBLISHER_BOX | TAG_NOTE_ITEM | TAG_NOTE_PRODNOTE,
        "svg" => flags |= TAG_PUBLISHER_BOX | TAG_HORIZONTAL_COMPLEX | TAG_SVG,
        "table" => {
            flags |= TAG_PUBLISHER_BOX
                | TAG_BLOCK_PROBE
                | TAG_HORIZONTAL_COMPLEX
                | TAG_CSS_ROW_EXCLUDED
                | TAG_TABLE
        }
        "line" => flags |= TAG_PUBLISHER_BOX | TAG_INLINE_TEXT_BLOCK,
        "img" => {
            flags |= TAG_PUBLISHER_BOX
                | TAG_NON_BLOCK
                | TAG_IMAGE
                | TAG_BLOCK_PROBE
                | TAG_HORIZONTAL_COMPLEX
                | TAG_IMG
        }
        "image" => flags |= TAG_PUBLISHER_BOX | TAG_IMAGE,
        "span" => {
            flags |= TAG_NON_BLOCK
                | TAG_SIMPLE_INLINE_TEXT_BOX
                | TAG_EXPLICIT_INITIAL
                | TAG_INLINE_SEMANTIC
        }
        "a" => flags |= TAG_NON_BLOCK | TAG_A | TAG_INLINE_SEMANTIC,
        "em" | "i" | "b" | "strong" => {
            flags |= TAG_NON_BLOCK
                | TAG_SIMPLE_INLINE_TEXT_BOX
                | TAG_EXPLICIT_INITIAL
                | TAG_INLINE_SEMANTIC;
        }
        "u" | "s" | "small" => {
            flags |= TAG_NON_BLOCK | TAG_SIMPLE_INLINE_TEXT_BOX | TAG_INLINE_SEMANTIC
        }
        "abbr" => flags |= TAG_SIMPLE_INLINE_TEXT_BOX | TAG_INLINE_SEMANTIC,
        "sup" => flags |= TAG_NON_BLOCK | TAG_SUP | TAG_INLINE_SEMANTIC,
        "sub" | "big" | "ruby" | "rt" | "rp" | "rb" => flags |= TAG_NON_BLOCK | TAG_INLINE_SEMANTIC,
        "code" => flags |= TAG_NON_BLOCK | TAG_SEMANTIC_MONOSPACE | TAG_INLINE_SEMANTIC,
        "kbd" | "samp" | "tt" => flags |= TAG_SEMANTIC_MONOSPACE | TAG_INLINE_SEMANTIC,
        "font" => flags |= TAG_EXPLICIT_INITIAL | TAG_INLINE_SEMANTIC,
        "strike" | "del" | "ins" | "var" | "dfn" | "q" | "mark" | "bdi" | "bdo" => {
            flags |= TAG_INLINE_SEMANTIC;
        }
        "br" => flags |= TAG_NON_BLOCK | TAG_NON_CONTENT | TAG_BR | TAG_INLINE_SEMANTIC,
        "wbr" => flags |= TAG_NON_CONTENT | TAG_INLINE_SEMANTIC,
        "style" => flags |= TAG_NON_CONTENT | TAG_NON_TEXT_CONTENT | TAG_STYLE,
        "link" => flags |= TAG_NON_CONTENT | TAG_NON_TEXT_CONTENT | TAG_LINK,
        "script" => flags |= TAG_NON_CONTENT | TAG_NON_TEXT_CONTENT | TAG_SCRIPT,
        "head" | "title" | "meta" => flags |= TAG_NON_CONTENT | TAG_NON_TEXT_CONTENT,
        "source" | "track" => flags |= TAG_NON_TEXT_CONTENT,
        "tr" => flags |= TAG_CSS_ROW_EXCLUDED | TAG_TR,
        "td" | "th" => flags |= TAG_CSS_ROW_EXCLUDED | TAG_INLINE_TEXT_BLOCK,
        _ => {}
    }
    if normal == "math" {
        flags |= TAG_MATH_EXACT;
    }
    if local == "math" {
        flags |= TAG_MATH;
    }
    flags
}

pub(crate) fn java_is_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && !matches!(ch, '\u{0085}' | '\u{00a0}' | '\u{2007}' | '\u{202f}'))
        || ('\u{001c}'..='\u{001f}').contains(&ch)
}

pub(crate) fn kotlin_is_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{0085}') || ('\u{001c}'..='\u{001f}').contains(&ch)
}

pub(crate) fn is_java_regex_ws(ch: char) -> bool {
    matches!(ch, ' ' | '\t' | '\n' | '\x0b' | '\u{000c}' | '\r')
}

fn classify_text(value: &str) -> u32 {
    let mut html_blank = true;
    let mut compact = true;
    let mut prev_space = false;
    let mut has_java_non_ws = false;
    let mut has_kotlin_non_ws = false;
    let mut has_visible_non_ws = false;
    let mut has_cr = false;
    let bytes = value.as_bytes();
    let starts_html_ws = bytes
        .first()
        .is_some_and(|&byte| matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 0x0c));
    let ends_html_ws = bytes
        .last()
        .is_some_and(|&byte| matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 0x0c));

    if value.is_ascii() {
        for &byte in bytes {
            match byte {
                b' ' => {
                    if prev_space {
                        compact = false;
                    }
                    prev_space = true;
                }
                b'\t' | b'\n' | 0x0c => {
                    compact = false;
                    prev_space = false;
                }
                b'\r' => {
                    compact = false;
                    has_cr = true;
                    prev_space = false;
                }
                0x0b | 0x1c..=0x1f => {
                    html_blank = false;
                    prev_space = false;
                }
                _ => {
                    html_blank = false;
                    has_java_non_ws = true;
                    has_kotlin_non_ws = true;
                    has_visible_non_ws = true;
                    prev_space = false;
                }
            }
        }
    } else {
        for ch in value.chars() {
            match ch {
                ' ' => {
                    if prev_space {
                        compact = false;
                    }
                    prev_space = true;
                }
                '\t' | '\n' | '\u{000c}' => {
                    compact = false;
                    prev_space = false;
                }
                '\r' => {
                    compact = false;
                    has_cr = true;
                    prev_space = false;
                }
                _ => {
                    html_blank = false;
                    if !java_is_whitespace(ch) {
                        has_java_non_ws = true;
                        if !matches!(ch, '\u{00A0}' | '\u{200B}' | '\u{00AD}') {
                            has_visible_non_ws = true;
                        }
                    }
                    if !kotlin_is_whitespace(ch) {
                        has_kotlin_non_ws = true;
                    }
                    prev_space = false;
                }
            }
        }
    }

    let mut flags = 0;
    if html_blank {
        flags |= TEXT_HTML_BLANK;
    }
    if compact {
        flags |= TEXT_COMPACT | TEXT_ALREADY_COLLAPSED_ASCII;
    }
    if starts_html_ws {
        flags |= TEXT_STARTS_HTML_WS;
    }
    if ends_html_ws {
        flags |= TEXT_ENDS_HTML_WS;
    }
    if has_java_non_ws {
        flags |= TEXT_HAS_JAVA_NON_WS;
    }
    if has_cr {
        flags |= TEXT_HAS_CR;
    }
    if has_visible_non_ws {
        flags |= TEXT_HAS_VISIBLE_NON_WS;
    }
    if has_kotlin_non_ws {
        flags |= TEXT_HAS_KOTLIN_NON_WS;
    }
    flags
}

fn contains_ascii_ignore_case(haystack: &str, needle: &[u8]) -> bool {
    if needle.is_empty() || haystack.len() < needle.len() {
        return false;
    }
    haystack
        .as_bytes()
        .windows(needle.len())
        .any(|window| window.eq_ignore_ascii_case(needle))
}

fn style_has_display_block(style: &str) -> bool {
    style.split(';').any(|declaration| {
        let Some((property, value)) = declaration.split_once(':') else {
            return false;
        };
        let property = property.trim_matches(|ch: char| ch <= ' ');
        let value = value
            .split_once('!')
            .map_or(value, |(prefix, _)| prefix)
            .trim_matches(|ch: char| ch <= ' ');
        property.eq_ignore_ascii_case("display") && value.eq_ignore_ascii_case("block")
    })
}

fn title_class_level(class_attr: &str) -> u32 {
    for token in class_attr
        .split([' ', '\t', '\r', '\n', '\u{000c}'])
        .filter(|token| !token.is_empty())
    {
        let Some(prefix) = token.get(..5) else {
            continue;
        };
        if prefix.eq_ignore_ascii_case("title") {
            let suffix = &token[5..];
            if suffix.is_empty() {
                return 1;
            }
            if !suffix.is_ascii() {
                return 7;
            }
            if suffix.bytes().all(|byte| byte.is_ascii_digit()) {
                return match suffix.parse::<i32>() {
                    Ok(digit) => digit.saturating_add(1).clamp(1, 6) as u32,
                    Err(_) => 7,
                };
            }
        }
    }
    0
}

fn has_empty_line_class(class_attr: &str) -> bool {
    class_attr
        .split([' ', '\t', '\r', '\n', '\u{000c}'])
        .any(|token| token.eq_ignore_ascii_case("empty-line"))
}

fn classify_note_tokens(values: &[Option<&str>]) -> (bool, bool) {
    let mut is_container = false;
    let mut is_collection = false;
    for value in values.iter().flatten() {
        let lowered: Cow<'_, str> = if value.is_ascii() {
            Cow::Borrowed(value)
        } else {
            Cow::Owned(value.to_lowercase())
        };
        let split_token = |ch: char| !matches!(ch, 'a'..='z' | 'A'..='Z' | '0'..='9' | '_' | '-');
        for token in lowered.split(split_token) {
            if token.is_empty() {
                continue;
            }
            if token.eq_ignore_ascii_case("footnote")
                || token.eq_ignore_ascii_case("endnote")
                || token.eq_ignore_ascii_case("rearnote")
                || token.eq_ignore_ascii_case("doc-footnote")
                || token.eq_ignore_ascii_case("doc-endnote")
            {
                is_container = true;
                is_collection = true;
            } else if token.eq_ignore_ascii_case("footnotes")
                || token.eq_ignore_ascii_case("endnotes")
                || token.eq_ignore_ascii_case("rearnotes")
                || token.eq_ignore_ascii_case("notes")
                || token.eq_ignore_ascii_case("doc-endnotes")
            {
                is_collection = true;
            }
            if is_container {
                return (true, true);
            }
        }
    }
    (is_container, is_collection)
}

pub(crate) fn has_note_hint(values: &[Option<&str>]) -> bool {
    for value in values.iter().flatten() {
        let lowered: Cow<'_, str> = if value.is_ascii() {
            Cow::Borrowed(value)
        } else {
            Cow::Owned(value.to_lowercase())
        };
        for token in lowered.split(|ch: char| matches!(ch, '-' | '_') || is_java_regex_ws(ch)) {
            if token.eq_ignore_ascii_case("footnote")
                || token.eq_ignore_ascii_case("endnote")
                || token.eq_ignore_ascii_case("noteref")
                || token.eq_ignore_ascii_case("note")
            {
                return true;
            }
        }
    }
    false
}

impl Preindex {
    fn build(nodes: &[Node]) -> Option<Self> {
        let mut meta = Vec::new();
        meta.try_reserve_exact(nodes.len()).ok()?;
        meta.resize(nodes.len(), NodeMeta::default());

        let mut preserve_ws_depth = Vec::new();
        preserve_ws_depth.try_reserve_exact(nodes.len()).ok()?;
        preserve_ws_depth.resize(nodes.len(), 0u8);

        let mut order = Vec::new();
        order.try_reserve_exact(nodes.len()).ok()?;
        let mut stack = Vec::new();
        if let Some(first) = nodes.get(DOCUMENT).and_then(|root| root.first_child) {
            stack.push(first);
        }
        while let Some(index) = stack.pop() {
            order.push(index);
            let node = &nodes[index];
            if let Some(sibling) = node.next_sibling {
                stack.push(sibling);
            }
            if let Some(child) = node.first_child {
                stack.push(child);
            }
        }

        let mut doc_flags = 0u32;
        let mut body_node = None;
        let mut book_node = None;
        let mut first_local_html: Option<usize> = None;
        let mut first_exact_html: Option<usize> = None;

        for &index in &order {
            let node = &nodes[index];
            match &node.kind {
                Kind::Element {
                    name,
                    namespace,
                    attrs,
                    ..
                } => {
                    let normal = normalize_tag(name);
                    let local = normal.rsplit(':').next().unwrap_or(normal.as_ref());
                    let is_preserve_ws = namespace == HTML_NS
                        && matches!(
                            normal.as_ref(),
                            "pre" | "plaintext" | "title" | "textarea" | "script"
                        );
                    preserve_ws_depth[index] = if is_preserve_ws {
                        6
                    } else if let Some(parent_idx) = node.parent {
                        preserve_ws_depth[parent_idx].saturating_sub(1)
                    } else {
                        0
                    };
                    let tag_flags = classify_tag(normal.as_ref(), local);
                    meta[index].tag_flags = tag_flags;

                    if (tag_flags & TAG_MATH) != 0 {
                        doc_flags |= DOC_HAS_MATH;
                    }
                    if (tag_flags & TAG_SVG) != 0 || local == "svg" {
                        doc_flags |= DOC_HAS_SVG;
                    }
                    if (tag_flags & TAG_SCRIPT) != 0 || local == "script" {
                        doc_flags |= DOC_HAS_SCRIPT;
                    }
                    if normal == "center" {
                        doc_flags |= DOC_HAS_ANY_PRESENTATIONAL;
                    }
                    if body_node.is_none() && normal == "body" {
                        body_node = Some(index);
                    }
                    if book_node.is_none() && normal == "book" {
                        book_node = Some(index);
                    }
                    if first_local_html.is_none() && local == "html" {
                        first_local_html = Some(index);
                    }
                    if first_exact_html.is_none() && normal == "html" {
                        first_exact_html = Some(index);
                    }

                    let mut id_val: Option<&str> = None;
                    let mut src_seen = false;
                    let mut href_seen = false;
                    let mut xlink_href_seen = false;
                    let mut style_val: Option<&str> = None;
                    let mut dir_val: Option<&str> = None;
                    let mut lang_val: Option<&str> = None;
                    let mut xml_lang_val: Option<&str> = None;
                    let mut type_val: Option<&str> = None;
                    let mut epub_type_val: Option<&str> = None;
                    let mut role_val: Option<&str> = None;
                    let mut class_val: Option<&str> = None;
                    let mut rel_val: Option<&str> = None;
                    let mut display_val: Option<&str> = None;
                    let mut has_presentational = false;

                    for (key, value) in attrs {
                        if id_val.is_none() && attribute_keys_equal(key, "id") {
                            id_val = Some(value.as_str());
                        } else if !src_seen && attribute_keys_equal(key, "src") {
                            src_seen = true;
                        } else if !href_seen && attribute_keys_equal(key, "href") {
                            href_seen = true;
                        } else if !xlink_href_seen && attribute_keys_equal(key, "xlink:href") {
                            xlink_href_seen = true;
                        } else if style_val.is_none() && attribute_keys_equal(key, "style") {
                            style_val = Some(value.as_str());
                        } else if dir_val.is_none() && attribute_keys_equal(key, "dir") {
                            dir_val = Some(value.as_str());
                        } else if lang_val.is_none() && attribute_keys_equal(key, "lang") {
                            lang_val = Some(value.as_str());
                        } else if xml_lang_val.is_none() && attribute_keys_equal(key, "xml:lang") {
                            xml_lang_val = Some(value.as_str());
                        } else if type_val.is_none() && attribute_keys_equal(key, "type") {
                            type_val = Some(value.as_str());
                        } else if epub_type_val.is_none() && attribute_keys_equal(key, "epub:type")
                        {
                            epub_type_val = Some(value.as_str());
                        } else if role_val.is_none() && attribute_keys_equal(key, "role") {
                            role_val = Some(value.as_str());
                        } else if class_val.is_none() && attribute_keys_equal(key, "class") {
                            class_val = Some(value.as_str());
                        } else if rel_val.is_none() && attribute_keys_equal(key, "rel") {
                            rel_val = Some(value.as_str());
                        } else if display_val.is_none() && attribute_keys_equal(key, "display") {
                            display_val = Some(value.as_str());
                        } else if !has_presentational
                            && !value.is_empty()
                            && (key.eq_ignore_ascii_case("align")
                                || key.eq_ignore_ascii_case("color")
                                || key.eq_ignore_ascii_case("text")
                                || key.eq_ignore_ascii_case("bgcolor"))
                        {
                            has_presentational = true;
                        }
                    }

                    let mut attr_flags = 0u32;
                    if id_val.is_some_and(|value| !value.is_empty()) {
                        attr_flags |= ATTR_HAS_ID;
                        doc_flags |= DOC_HAS_ANY_ID;
                    }
                    if src_seen {
                        attr_flags |= ATTR_HAS_SRC;
                    }
                    if href_seen {
                        attr_flags |= ATTR_HAS_HREF;
                        if (tag_flags & TAG_A) != 0 {
                            doc_flags |= DOC_HAS_ANY_A_HREF;
                        }
                    }
                    if xlink_href_seen {
                        attr_flags |= ATTR_HAS_XLINK_HREF;
                    }
                    if let Some(style) = style_val {
                        attr_flags |= ATTR_HAS_STYLE;
                        doc_flags |= DOC_HAS_ANY_STYLE;
                        if contains_ascii_ignore_case(style, b"writing-mode") {
                            doc_flags |= DOC_MAYBE_VERTICAL_WRITING;
                        }
                    }
                    if dir_val
                        .is_some_and(|value| !value.trim_matches(|c: char| c <= ' ').is_empty())
                    {
                        attr_flags |= ATTR_HAS_DIR;
                        doc_flags |= DOC_HAS_ANY_DIR;
                    }
                    if lang_val
                        .is_some_and(|value| !value.trim_matches(|c: char| c <= ' ').is_empty())
                        || xml_lang_val
                            .is_some_and(|value| !value.trim_matches(|c: char| c <= ' ').is_empty())
                    {
                        attr_flags |= ATTR_HAS_LANG;
                        doc_flags |= DOC_HAS_ANY_LANG;
                    }
                    if has_presentational {
                        attr_flags |= ATTR_HAS_PRESENTATIONAL;
                        doc_flags |= DOC_HAS_ANY_PRESENTATIONAL;
                    }
                    if type_val.is_some_and(|value| value.eq_ignore_ascii_case("note")) {
                        attr_flags |= ATTR_TYPE_IS_NOTE;
                    }
                    if epub_type_val.is_some_and(|value| {
                        value
                            .split(is_java_regex_ws)
                            .any(|token| token.eq_ignore_ascii_case("noteref"))
                    }) {
                        attr_flags |= ATTR_EPUB_NOTEREF;
                    }
                    if role_val.is_some_and(|value| {
                        value
                            .split(is_java_regex_ws)
                            .any(|token| token.eq_ignore_ascii_case("doc-noteref"))
                    }) {
                        attr_flags |= ATTR_ROLE_DOC_NOTEREF;
                    }

                    let class_trimmed =
                        class_val.map(|value| value.trim_matches(|c: char| c <= ' '));
                    let (note_container_tok, note_collection_tok) =
                        classify_note_tokens(&[epub_type_val, role_val, type_val, class_trimmed]);
                    if note_container_tok {
                        attr_flags |= ATTR_NOTE_CONTAINER_TOKEN;
                    }
                    if note_collection_tok {
                        attr_flags |= ATTR_NOTE_COLLECTION_TOKEN;
                    }
                    if has_note_hint(&[class_trimmed, id_val, rel_val]) {
                        attr_flags |= ATTR_HAS_NOTE_HINT;
                    }
                    if let Some(class_attr) = class_val {
                        if has_empty_line_class(class_attr) {
                            attr_flags |= ATTR_HAS_EMPTY_LINE_CLASS;
                        }
                        let title_level = title_class_level(class_attr);
                        attr_flags |= (title_level & 0x7) << TITLE_CLASS_SHIFT;
                    }
                    if (tag_flags & TAG_MATH_EXACT) != 0
                        && display_val.is_some_and(|value| value.eq_ignore_ascii_case("block"))
                    {
                        attr_flags |= ATTR_IS_BLOCK_PROBE_MATH;
                    }
                    if (tag_flags & TAG_MATH) != 0
                        && (display_val.is_some_and(|value| value.eq_ignore_ascii_case("block"))
                            || style_val.is_some_and(style_has_display_block))
                    {
                        attr_flags |= ATTR_IS_DISPLAY_MATH;
                    }

                    if let Some(parent_idx) = node.parent {
                        let parent_meta = meta[parent_idx];
                        if (parent_meta.tag_flags & TAG_SUP) != 0
                            || (parent_meta.attr_flags & ATTR_ANC_HAS_SUP) != 0
                        {
                            attr_flags |= ATTR_ANC_HAS_SUP;
                        }
                        if (parent_meta.attr_flags & ATTR_ANC_HAS_LANG) != 0 {
                            attr_flags |= ATTR_ANC_HAS_LANG;
                        }
                        if (parent_meta.attr_flags & ATTR_ANC_HAS_DIR) != 0 {
                            attr_flags |= ATTR_ANC_HAS_DIR;
                        }
                        if (tag_flags & TAG_ROOT) == 0
                            && (parent_meta.tag_flags & TAG_ROOT) == 0
                            && (parent_meta.attr_flags & ATTR_ANC_HAS_FLOW_SCOPE) != 0
                        {
                            attr_flags |= ATTR_ANC_HAS_FLOW_SCOPE;
                        }
                    }
                    if (attr_flags & ATTR_HAS_LANG) != 0 {
                        attr_flags |= ATTR_ANC_HAS_LANG;
                    }
                    if (attr_flags & ATTR_HAS_DIR) != 0 {
                        attr_flags |= ATTR_ANC_HAS_DIR;
                    }
                    if (tag_flags & TAG_ROOT) == 0
                        && ((tag_flags & TAG_A) != 0
                            || (tag_flags & (TAG_ROOT | TAG_PUBLISHER_BOX)) == 0)
                    {
                        attr_flags |= ATTR_ANC_HAS_FLOW_SCOPE;
                    }

                    let is_note_container = ((tag_flags & TAG_NOTE_PRODNOTE) != 0)
                        || ((tag_flags & TAG_NOTE_ITEM) != 0
                            && (attr_flags & ATTR_NOTE_CONTAINER_TOKEN) != 0)
                        || ((tag_flags & TAG_NOTE_ITEM) != 0
                            && (attr_flags & ATTR_HAS_ID) != 0
                            && {
                                let mut anc = node.parent;
                                let mut found = false;
                                for _ in 0..3 {
                                    let Some(anc_idx) = anc else { break };
                                    if (meta[anc_idx].attr_flags & ATTR_NOTE_COLLECTION_TOKEN) != 0
                                    {
                                        found = true;
                                        break;
                                    }
                                    anc = nodes[anc_idx].parent;
                                }
                                found
                            });
                    if is_note_container {
                        attr_flags |= ATTR_IS_SEMANTIC_NOTE_CONTAINER;
                    }

                    if (tag_flags & TAG_STYLE) != 0 || ((tag_flags & TAG_LINK) != 0 && href_seen) {
                        meta[index].is_stylesheet = true;
                    }
                    if (tag_flags & TAG_STYLE) != 0 || local == "style" {
                        let mut child = node.first_child;
                        while let Some(child_idx) = child {
                            match &nodes[child_idx].kind {
                                Kind::Text(text) | Kind::Cdata(text) | Kind::Data(text) => {
                                    if contains_ascii_ignore_case(text, b"writing-mode") {
                                        doc_flags |= DOC_MAYBE_VERTICAL_WRITING;
                                    }
                                }
                                _ => {}
                            }
                            child = nodes[child_idx].next_sibling;
                        }
                    }

                    meta[index].attr_flags = attr_flags;
                }
                Kind::Text(value) | Kind::Cdata(value) => {
                    meta[index].text_flags = classify_text(value);
                }
                _ => {}
            }
        }

        let find_head = |html_idx: usize| -> Option<usize> {
            let mut child = nodes[html_idx].first_child;
            while let Some(c_idx) = child {
                if let Kind::Element { name, .. } = &nodes[c_idx].kind {
                    let normal = normalize_tag(name);
                    let local = normal.rsplit(':').next().unwrap_or(normal.as_ref());
                    if local == "head" {
                        return Some(c_idx);
                    }
                }
                child = nodes[c_idx].next_sibling;
            }
            None
        };

        let mut head_title_node = None;
        if let Some(html_idx) = first_local_html {
            if let Some(head) = find_head(html_idx) {
                let mut h_child = nodes[head].first_child;
                while let Some(c_idx) = h_child {
                    if let Kind::Element { name, .. } = &nodes[c_idx].kind {
                        let normal = normalize_tag(name);
                        let local = normal.rsplit(':').next().unwrap_or(normal.as_ref());
                        if local == "title" {
                            head_title_node = Some(c_idx);
                            break;
                        }
                    }
                    h_child = nodes[c_idx].next_sibling;
                }
            }
        }

        let mut viewport_meta_node = None;
        if let Some(html_idx) = first_exact_html {
            if let Some(head) = find_head(html_idx) {
                stack.clear();
                if let Some(first) = nodes[head].first_child {
                    stack.push(first);
                }
                while let Some(c_idx) = stack.pop() {
                    let c_node = &nodes[c_idx];
                    if let Kind::Element { name, attrs, .. } = &c_node.kind {
                        let normal = normalize_tag(name);
                        let local = normal.rsplit(':').next().unwrap_or(normal.as_ref());
                        if local == "meta"
                            && attrs.iter().any(|(key, value)| {
                                attribute_keys_equal(key, "name")
                                    && value.eq_ignore_ascii_case("viewport")
                            })
                        {
                            viewport_meta_node = Some(c_idx);
                            break;
                        }
                    }
                    if let Some(sibling) = c_node.next_sibling {
                        stack.push(sibling);
                    }
                    if let Some(first_c) = c_node.first_child {
                        stack.push(first_c);
                    }
                }
            }
        }

        for &index in order.iter().rev() {
            let mut sub = meta[index].subtree_flags;
            match &nodes[index].kind {
                Kind::Element { .. } => {
                    let tag_flags = meta[index].tag_flags;
                    let attr_flags = meta[index].attr_flags;
                    if (tag_flags & TAG_BLOCK_PROBE) != 0
                        || (attr_flags & ATTR_IS_BLOCK_PROBE_MATH) != 0
                    {
                        sub |= SUBTREE_HAS_BLOCK_PROBE;
                    }
                    if (attr_flags & ATTR_HAS_STYLE) != 0 {
                        sub |= SUBTREE_HAS_STYLE;
                    }
                    if (attr_flags & ATTR_HAS_ID) != 0 {
                        sub |= SUBTREE_HAS_ID;
                    }
                    if (tag_flags & TAG_IMG) != 0 {
                        sub |= SUBTREE_HAS_IMG;
                    }
                    if (tag_flags & TAG_SVG) != 0 {
                        sub |= SUBTREE_HAS_SVG;
                    }
                    if (tag_flags & TAG_HORIZONTAL_COMPLEX) != 0 {
                        sub |= SUBTREE_HAS_HORIZONTAL_COMPLEX;
                    }
                    if (tag_flags & TAG_SUP) != 0 {
                        sub |= SUBTREE_HAS_SUP;
                    }
                    if (tag_flags & TAG_CAPTION) != 0 {
                        sub |= SUBTREE_HAS_CAPTION;
                    }
                    if (tag_flags & TAG_NESTED_LIST) != 0 {
                        sub |= SUBTREE_HAS_NESTED_LIST;
                    }
                    if (tag_flags & TAG_A) != 0 && (attr_flags & ATTR_HAS_HREF) != 0 {
                        sub |= SUBTREE_HAS_A_HREF;
                    }
                    if (tag_flags & (TAG_PUBLISHER_BOX | TAG_NON_CONTENT | TAG_ROOT)) == 0 {
                        sub |= SUBTREE_HAS_INLINE_FLOW_CANDIDATE;
                    }
                    if let Some(child_idx) = nodes[index].first_child {
                        if nodes[child_idx].next_sibling.is_none()
                            && matches!(nodes[child_idx].kind, Kind::Text(_))
                            && (meta[child_idx].text_flags & TEXT_HAS_KOTLIN_NON_WS) != 0
                        {
                            sub |= SUBTREE_SIMPLE_LEAF_TEXT;
                        }
                    }
                }
                Kind::Text(_) => {
                    let preserve = nodes[index]
                        .parent
                        .is_some_and(|parent| preserve_ws_depth[parent] > 0);
                    let mask = if preserve {
                        TEXT_HAS_JAVA_NON_WS
                    } else {
                        TEXT_HAS_VISIBLE_NON_WS
                    };
                    if (meta[index].text_flags & mask) != 0 {
                        sub |= SUBTREE_HAS_JAVA_NON_WS_TEXT;
                    }
                }
                Kind::Cdata(_) => {
                    if (meta[index].text_flags & TEXT_HAS_JAVA_NON_WS) != 0 {
                        sub |= SUBTREE_HAS_JAVA_NON_WS_TEXT;
                    }
                }
                _ => {}
            }
            meta[index].subtree_flags = sub;
            if let Some(parent) = nodes[index].parent {
                meta[parent].subtree_flags |= sub & !SUBTREE_SIMPLE_LEAF_TEXT;
            }
        }

        Some(Self {
            nodes: meta,
            doc_flags,
            body_node,
            book_node,
            head_title_node,
            viewport_meta_node,
        })
    }
}

pub(crate) fn parse(text: &str, html_only: bool) -> Option<Document> {
    if html_only {
        return parse_html(text);
    }
    let xml = parse_xml(text);
    if xml.as_ref().is_some_and(has_readable_xml_root) {
        xml
    } else {
        parse_html(text).or(xml)
    }
}

fn has_readable_xml_root(doc: &Document) -> bool {
    let mut next = doc.nodes[DOCUMENT].first_child;
    while let Some(index) = next {
        let node = &doc.nodes[index];
        if matches!(&node.kind, Kind::Element { name, .. } if name.eq_ignore_ascii_case("body") || name.eq_ignore_ascii_case("book"))
        {
            let mut child = node.first_child;
            while let Some(index) = child {
                let node = &doc.nodes[index];
                match &node.kind {
                    Kind::Element { .. } => return true,
                    Kind::Text(value) | Kind::Cdata(value)
                        if value.chars().any(|ch| !reader_whitespace(ch)) =>
                    {
                        return true
                    }
                    _ => {}
                }
                child = node.next_sibling;
            }
            return false;
        }
        next = node.first_child.or_else(|| {
            let mut current = index;
            while doc.nodes[current].next_sibling.is_none() {
                current = doc.nodes[current].parent?;
            }
            doc.nodes[current].next_sibling
        });
    }
    false
}

fn reader_whitespace(ch: char) -> bool {
    (ch.is_whitespace() && ch != '\u{85}') || ('\u{1c}'..='\u{1f}').contains(&ch)
}

fn parse_xml(text: &str) -> Option<Document> {
    // quick-xml omits the BOM from buffer positions. Keep source slices in
    // the same coordinate system when combining text and entity events.
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let mut reader = Reader::from_str(text);
    reader.config_mut().allow_dangling_amp = true;
    reader.config_mut().allow_unmatched_ends = true;
    reader.config_mut().check_end_names = false;
    let mut arena = Arena::new();
    let mut stack = vec![DOCUMENT];
    let mut pending_text = None;
    loop {
        let position = reader.buffer_position() as usize;
        let event = reader.read_event();
        if matches!(event, Ok(Event::Text(_) | Event::GeneralRef(_))) {
            pending_text.get_or_insert(position);
            continue;
        }
        if let Some(start) = pending_text.take() {
            let value = decode_entities(text.get(start..position)?, false);
            arena.append_text(*stack.last()?, None, &value, false);
        }
        let empty = matches!(event, Ok(Event::Empty(_)));
        match event {
            Ok(Event::Start(tag)) | Ok(Event::Empty(tag)) => {
                if stack.len() == MAX_XML_DEPTH {
                    stack.pop();
                }
                let node = start_xml_element(&mut arena, &stack, &tag, empty)?;
                if !empty {
                    stack.push(node);
                }
            }
            Ok(Event::End(end)) => {
                let name = end.name();
                if let Some(pos) = stack.iter().rposition(|&index| matches!(&arena.nodes[index].kind, Kind::Element { name: open, .. } if open == name.into_inner())) {
                    stack.truncate(pos);
                }
            }
            Ok(Event::CData(text)) => {
                arena.push(*stack.last()?, Kind::Cdata(text.into_inner().into_owned()))?;
            }
            Ok(Event::Comment(text)) => {
                arena.push(
                    *stack.last()?,
                    Kind::Comment(text.into_inner().into_owned()),
                )?;
            }
            Ok(Event::DocType(text)) => {
                arena.push(*stack.last()?, xml_doctype(&text))?;
            }
            Ok(Event::Decl(declaration)) => {
                let data = declaration.as_ref().strip_prefix("xml").unwrap_or_default();
                arena.push(
                    *stack.last()?,
                    Kind::Pi {
                        target: "xml".into(),
                        data: data.trim_start().into(),
                    },
                )?;
            }
            Ok(Event::PI(pi)) => {
                arena.push(
                    *stack.last()?,
                    Kind::Pi {
                        target: pi.target().to_owned(),
                        data: pi.content().trim_start().to_owned(),
                    },
                )?;
            }
            Ok(Event::Eof) => break,
            Ok(Event::Text(_) | Event::GeneralRef(_)) => unreachable!(),
            // A truncated closing token does not invalidate the XML already read.
            Err(_) if reader.buffer_position() as usize == text.len() => break,
            Err(_) => return None,
        }
        if arena.exhausted {
            return None;
        }
    }
    arena.finish(false)
}

fn start_xml_element(
    arena: &mut Arena,
    stack: &[usize],
    tag: &BytesStart<'_>,
    self_closing: bool,
) -> Option<usize> {
    let mut seen = HashSet::new();
    let attrs: Vec<_> = tag
        .html_attributes()
        .with_checks(false)
        .flatten()
        .filter_map(|attr| {
            let key = attr.key.into_inner();
            seen.insert(key).then(|| {
                (
                    key.to_owned(),
                    decode_entities(&attr.value, true).into_owned(),
                )
            })
        })
        .collect();
    let name = tag.name().into_inner();
    let declaration = name
        .split_once(':')
        .map(|(prefix, _)| format!("xmlns:{prefix}"));
    let find = |key| {
        xml_namespace_in(&attrs, key).or_else(|| {
            stack
                .iter()
                .rev()
                .find_map(|&index| match &arena.nodes[index].kind {
                    Kind::Element { attrs, .. } => xml_namespace_in(attrs, key),
                    _ => None,
                })
        })
    };
    let namespace = declaration
        .as_deref()
        .and_then(|key| find(key).or_else(|| (key == "xmlns:xml").then_some(XML_NAMESPACE)))
        .or_else(|| find("xmlns"))
        .unwrap_or(XML_NAMESPACE);
    let element = Kind::Element {
        name: name.into(),
        namespace: namespace.into(),
        attrs,
        self_closing,
    };
    arena.push(*stack.last()?, element)
}

fn xml_namespace_in<'a>(attrs: &'a [(String, String)], key: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find_map(|(name, value)| (name == key).then_some(value.as_str()))
}

fn xml_doctype(text: &str) -> Kind {
    fn quoted(text: &str) -> Option<(&str, &str)> {
        let text = text.trim_start();
        let quote = text.chars().next().filter(|ch| matches!(ch, '\'' | '"'))?;
        text[1..].split_once(quote)
    }
    let text = text.trim_start();
    let name_end = text
        .find(|ch: char| ch.is_ascii_whitespace() || ch == '[')
        .unwrap_or(text.len());
    let (name, data) = text.split_at(name_end);
    let data = data.trim_start();
    let mut public_id = "";
    let mut system_id = "";
    if let Some(data) = data.strip_prefix("PUBLIC") {
        if let Some((value, remaining)) = quoted(data) {
            public_id = value;
            if let Some((value, _)) = quoted(remaining) {
                system_id = value;
            }
        }
    } else if let Some(data) = data.strip_prefix("SYSTEM") {
        if let Some((value, _)) = quoted(data) {
            system_id = value;
        }
    }
    Kind::Doctype {
        name: name.into(),
        public_id: public_id.into(),
        system_id: system_id.into(),
    }
}

fn decode_entities(value: &str, in_attribute: bool) -> Cow<'_, str> {
    let Some(mut marker) = value.find('&') else {
        return Cow::Borrowed(value);
    };
    let mut output = String::with_capacity(value.len());
    let mut start = 0;
    loop {
        output.push_str(&value[start..marker]);
        let suffix = &value[marker + 1..];
        start = marker + 1;
        if let Some((first, second, len)) = character_reference(suffix, in_attribute) {
            output.push(first);
            if let Some(second) = second {
                output.push(second);
            }
            start += len;
        } else {
            output.push('&');
        }
        let Some(offset) = value[start..].find('&') else {
            break;
        };
        marker = start + offset;
    }
    output.push_str(&value[start..]);
    Cow::Owned(output)
}

fn character_reference(value: &str, in_attribute: bool) -> Option<(char, Option<char>, usize)> {
    if let Some(number) = value.strip_prefix('#') {
        let (digits, base, prefix_len) = match number.strip_prefix(['x', 'X']) {
            Some(digits) => (digits, 16, 2),
            None => (number, 10, 1),
        };
        let len = digits
            .bytes()
            .take_while(|byte| byte.is_ascii_digit() || (base == 16 && byte.is_ascii_hexdigit()))
            .count();
        if len == 0 {
            return None;
        }
        let code = u32::from_str_radix(&digits[..len], base).ok();
        let scalar = match code {
            Some(code @ 0x80..=0x9f) => html5ever::data::C1_REPLACEMENTS[(code - 0x80) as usize]
                .or_else(|| char::from_u32(code)),
            _ => code.and_then(char::from_u32),
        }
        .unwrap_or(char::REPLACEMENT_CHARACTER);
        let len = prefix_len + len;
        return Some((
            scalar,
            None,
            len + usize::from(value.as_bytes().get(len) == Some(&b';')),
        ));
    }
    if !value
        .as_bytes()
        .first()
        .is_some_and(u8::is_ascii_alphabetic)
    {
        return None;
    }
    let letters = value.bytes().take_while(u8::is_ascii_alphabetic).count();
    let digits = value[letters..]
        .bytes()
        .take_while(u8::is_ascii_digit)
        .count();
    let len = letters + digits;
    let semicolon = value.as_bytes().get(len) == Some(&b';');
    let consumed = len + usize::from(semicolon);
    let mut found = entity_value(&value[..consumed]).map(|value| (value, consumed));
    if found.is_none() && !in_attribute {
        // The longest legacy entity prefix, excluding the semicolon, is
        // allowed in text even when additional name characters follow.
        found = (1..=len.min(32))
            .rev()
            .find_map(|end| entity_value(&value[..end]).map(|entity| (entity, end)));
    }
    let ((first, second), len) = found?;
    if in_attribute
        && value
            .as_bytes()
            .get(len)
            .is_some_and(|next| next.is_ascii_alphanumeric() || matches!(next, b'=' | b'-' | b'_'))
        && !semicolon
    {
        return None;
    }
    Some((
        char::from_u32(first)?,
        char::from_u32(second).filter(|_char| second != 0),
        len,
    ))
}

fn entity_value(name: &str) -> Option<(u32, u32)> {
    html5ever::data::NAMED_ENTITIES
        .get(name)
        .copied()
        .filter(|&(first, _)| first != 0)
}

#[derive(Clone, Debug)]
struct HtmlHandle {
    index: usize,
    name: Option<QualName>,
    template_contents: Option<usize>,
    integration_point: bool,
}

impl HtmlHandle {
    fn leaf(index: usize) -> Self {
        Self {
            index,
            name: None,
            template_contents: None,
            integration_point: false,
        }
    }
}

struct HtmlSink {
    arena: RefCell<Arena>,
    templates: RefCell<Vec<(usize, usize)>>,
}

fn parse_html(text: &str) -> Option<Document> {
    let sink = HtmlSink {
        arena: RefCell::new(Arena::new()),
        templates: RefCell::new(Vec::new()),
    };
    let mut options = html5ever::ParseOpts::default();
    options.tree_builder.scripting_enabled = false;
    let mut parser = html5ever::parse_document(sink, options);
    let mut offset = 0;
    while offset < text.len() {
        let mut end = (offset + HTML_CHUNK_BYTES).min(text.len());
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        parser.process(StrTendril::from(&text[offset..end]));
        if parser.tokenizer.sink.sink.arena.borrow().exhausted {
            return None;
        }
        offset = end;
    }
    parser.finish()
}

impl HtmlSink {
    fn insert(&self, kind: Kind) -> HtmlHandle {
        HtmlHandle::leaf(self.arena.borrow_mut().insert(kind).unwrap_or(usize::MAX))
    }
}

impl TreeSink for HtmlSink {
    type Handle = HtmlHandle;
    type Output = Option<Document>;
    type ElemName<'a> = ExpandedName<'a>;

    fn finish(self) -> Self::Output {
        let mut arena = self.arena.into_inner();
        for (element, contents) in self.templates.into_inner() {
            arena.reparent(contents, element);
        }
        arena.finish(true)
    }

    fn parse_error(&self, _message: Cow<'static, str>) {}

    fn get_document(&self) -> Self::Handle {
        HtmlHandle::leaf(DOCUMENT)
    }

    fn elem_name<'a>(&'a self, target: &'a Self::Handle) -> Self::ElemName<'a> {
        target
            .name
            .as_ref()
            .expect("HTML parser requests only element names")
            .expanded()
    }

    fn create_element(
        &self,
        name: QualName,
        attrs: Vec<Attribute>,
        flags: ElementFlags,
    ) -> Self::Handle {
        let mut arena = self.arena.borrow_mut();
        let index = arena
            .insert(Kind::Element {
                self_closing: false,
                name: qualified_name(&name),
                namespace: name.ns.to_string(),
                attrs: attrs
                    .iter()
                    .map(|attr| (qualified_name(&attr.name), attr.value.to_string()))
                    .collect(),
            })
            .unwrap_or(usize::MAX);
        let template_contents = if flags.template {
            let fragment = arena.insert(Kind::Document);
            if let Some(fragment) = fragment {
                self.templates.borrow_mut().push((index, fragment));
            }
            Some(fragment.unwrap_or(usize::MAX))
        } else {
            None
        };
        HtmlHandle {
            index,
            name: Some(name),
            template_contents,
            integration_point: flags.mathml_annotation_xml_integration_point,
        }
    }

    fn create_comment(&self, text: StrTendril) -> Self::Handle {
        self.insert(Kind::Comment(text.to_string()))
    }

    fn create_pi(&self, target: StrTendril, data: StrTendril) -> Self::Handle {
        self.insert(Kind::Pi {
            target: target.to_string(),
            data: data.to_string(),
        })
    }

    fn append(&self, parent: &Self::Handle, child: NodeOrText<Self::Handle>) {
        let mut arena = self.arena.borrow_mut();
        if !arena.exhausted {
            append_html(&mut arena, parent.index, None, child);
        }
    }

    fn append_based_on_parent_node(
        &self,
        element: &Self::Handle,
        previous_element: &Self::Handle,
        child: NodeOrText<Self::Handle>,
    ) {
        let mut arena = self.arena.borrow_mut();
        if !arena.exhausted {
            if let Some(parent) = arena.nodes[element.index].parent {
                append_html(&mut arena, parent, Some(element.index), child);
            } else {
                append_html(&mut arena, previous_element.index, None, child);
            }
        }
    }

    fn append_doctype_to_document(
        &self,
        name: StrTendril,
        public_id: StrTendril,
        system_id: StrTendril,
    ) {
        self.arena.borrow_mut().push(
            DOCUMENT,
            Kind::Doctype {
                name: name.to_string(),
                public_id: public_id.to_string(),
                system_id: system_id.to_string(),
            },
        );
    }

    fn get_template_contents(&self, target: &Self::Handle) -> Self::Handle {
        HtmlHandle::leaf(
            target
                .template_contents
                .expect("HTML parser asks only template contents"),
        )
    }

    fn same_node(&self, left: &Self::Handle, right: &Self::Handle) -> bool {
        left.index == right.index
    }

    fn set_quirks_mode(&self, _mode: QuirksMode) {}

    fn append_before_sibling(&self, sibling: &Self::Handle, child: NodeOrText<Self::Handle>) {
        let mut arena = self.arena.borrow_mut();
        if !arena.exhausted {
            if let Some(parent) = arena.nodes[sibling.index].parent {
                append_html(&mut arena, parent, Some(sibling.index), child);
            }
        }
    }

    fn add_attrs_if_missing(&self, target: &Self::Handle, new_attributes: Vec<Attribute>) {
        let mut arena = self.arena.borrow_mut();
        if arena.exhausted {
            return;
        }
        for attribute in new_attributes {
            let key = qualified_name(&attribute.name);
            let Kind::Element { attrs, .. } = &arena.nodes[target.index].kind else {
                return;
            };
            if attrs.iter().any(|(name, _)| *name == key) {
                continue;
            }
            if !arena.charge(size_of::<(String, String)>() + key.len() + attribute.value.len()) {
                return;
            }
            let Kind::Element { attrs, .. } = &mut arena.nodes[target.index].kind else {
                unreachable!()
            };
            attrs.push((key, attribute.value.to_string()));
        }
    }

    fn remove_from_parent(&self, target: &Self::Handle) {
        let mut arena = self.arena.borrow_mut();
        if !arena.exhausted {
            arena.detach(target.index);
        }
    }

    fn reparent_children(&self, node: &Self::Handle, new_parent: &Self::Handle) {
        let mut arena = self.arena.borrow_mut();
        if !arena.exhausted {
            arena.reparent(node.index, new_parent.index);
        }
    }

    fn is_mathml_annotation_xml_integration_point(&self, handle: &Self::Handle) -> bool {
        handle.integration_point
    }
}

fn qualified_name(name: &QualName) -> String {
    if let Some(prefix) = &name.prefix {
        format!("{prefix}:{}", name.local)
    } else {
        name.local.to_string()
    }
}

fn append_html(
    arena: &mut Arena,
    parent: usize,
    next: Option<usize>,
    child: NodeOrText<HtmlHandle>,
) {
    match child {
        NodeOrText::AppendNode(child) => arena.attach(child.index, parent, next),
        NodeOrText::AppendText(text) => {
            let data = match &arena.nodes[parent].kind {
                Kind::Element {
                    name, namespace, ..
                } if namespace == HTML_NS => matches!(
                    name.as_str(),
                    "iframe" | "noembed" | "noframes" | "script" | "style" | "xmp"
                ),
                Kind::Element {
                    name, namespace, ..
                } if namespace == SVG_NS => name == "script",
                _ => false,
            };
            arena.append_text(parent, next, &text, data);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn element<'a>(document: &'a Document, wanted: &str) -> &'a Node {
        document
            .nodes
            .iter()
            .find(|node| matches!(&node.kind, Kind::Element { name, .. } if name == wanted))
            .expect("expected element")
    }

    fn descendant_text(document: &Document, parent: usize) -> String {
        let mut output = String::new();
        let mut next = document.nodes[parent].first_child;
        while let Some(index) = next {
            let node = &document.nodes[index];
            match &node.kind {
                Kind::Text(value) | Kind::Cdata(value) => output.push_str(value),
                _ => {}
            }
            next = node.first_child.or_else(|| {
                let mut current = index;
                while document.nodes[current].next_sibling.is_none() {
                    current = document.nodes[current].parent?;
                    if current == parent {
                        return None;
                    }
                }
                document.nodes[current].next_sibling
            });
        }
        output
    }

    fn validate_reachable_links(document: &Document) -> Vec<usize> {
        let mut seen = HashSet::new();
        let mut pending = vec![DOCUMENT];
        let mut preorder = Vec::new();
        while let Some(index) = pending.pop() {
            assert!(seen.insert(index), "node appears twice in the tree");
            preorder.push(index);
            let mut child = document.nodes[index].first_child;
            let mut previous = None;
            let mut children = Vec::new();
            while let Some(child_index) = child {
                let node = &document.nodes[child_index];
                assert_eq!(node.parent, Some(index));
                assert_eq!(node.previous_sibling, previous);
                children.push(child_index);
                previous = Some(child_index);
                child = node.next_sibling;
            }
            assert_eq!(document.nodes[index].last_child, previous);
            pending.extend(children.into_iter().rev());
        }
        preorder
    }

    #[test]
    fn xml_retains_namespace_declarations_case_self_closes_and_cdata() {
        let source = "\u{feff}<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml' \
            xmlns:ops='http://www.idpf.org/2007/ops'><head><title/><style><![CDATA[\n            p::before { content: '<&>'; }\n]]></style></head><body>            <P ops:type='note' DATA-RANK='1' data-rank='2'>A&nbsp;&#x1f600;</P>            <svg xmlns='http://www.w3.org/2000/svg'><path d='M0 0'/></svg>            <section xmlns=''><custom/></section></body></html>";
        let document = parse(source, false).unwrap();
        assert!(!document.html);
        validate_reachable_links(&document);
        let Kind::Element {
            namespace, attrs, ..
        } = &element(&document, "html").kind
        else {
            unreachable!()
        };
        assert_eq!(namespace, HTML_NS);
        assert!(attrs.contains(&("xmlns:ops".into(), "http://www.idpf.org/2007/ops".into())));
        let Kind::Element {
            attrs,
            namespace,
            self_closing,
            ..
        } = &element(&document, "P").kind
        else {
            unreachable!()
        };
        assert_eq!(namespace, HTML_NS);
        assert!(!self_closing);
        assert!(attrs.contains(&("DATA-RANK".into(), "1".into())));
        assert!(attrs.contains(&("data-rank".into(), "2".into())));
        assert!(matches!(
            &element(&document, "title").kind,
            Kind::Element {
                self_closing: true,
                ..
            }
        ));
        assert!(
            matches!(&element(&document, "path").kind, Kind::Element { namespace, self_closing: true, .. } if namespace == SVG_NS)
        );
        assert!(
            matches!(&element(&document, "custom").kind, Kind::Element { namespace, .. } if namespace.is_empty())
        );
        let cdata = document
            .nodes
            .iter()
            .find_map(|node| match &node.kind {
                Kind::Cdata(text) => Some(text.as_str()),
                _ => None,
            })
            .unwrap();
        assert!(cdata.starts_with('\n'));
        assert!(cdata.contains("content: '<&>'"));
        assert!(
            descendant_text(&document, DOCUMENT).contains("A\u{a0}😀"),
            "{:?}",
            descendant_text(&document, DOCUMENT)
        );
    }

    #[test]
    fn xml_recovery_keeps_completed_nodes_and_unmatched_close_scope() {
        let document =
            parse_xml("<body><p data-a=1>first</missing><span>second</body><p>tail</p>").unwrap();
        validate_reachable_links(&document);
        assert_eq!(descendant_text(&document, DOCUMENT), "firstsecondtail");
        let Kind::Element {
            attrs, namespace, ..
        } = &element(&document, "p").kind
        else {
            unreachable!()
        };
        assert_eq!(namespace, XML_NAMESPACE);
        assert_eq!(attrs, &[("data-a".into(), "1".into())]);
        let truncated = parse_xml("<body><p>kept</p><span title='unfinished").unwrap();
        assert_eq!(descendant_text(&truncated, DOCUMENT), "kept");
        validate_reachable_links(&truncated);
    }

    #[test]
    fn fragments_use_html_body_and_html_rawtext_is_preserved() {
        let document = parse(
            "<style>p:before { content: '<&amp;>'; }</style><p>A&nbsp;B",
            false,
        )
        .unwrap();
        assert!(document.html);
        validate_reachable_links(&document);
        element(&document, "body");
        assert_eq!(descendant_text(&document, DOCUMENT), "A\u{a0}B");
        assert!(document.nodes.iter().any(|node| matches!(
            &node.kind,
            Kind::Data(text) if text == "p:before { content: '<&amp;>'; }"
        )));
        let empty_body = parse("<html><body> \n </body></html>", false).unwrap();
        assert!(empty_body.html);
    }

    #[test]
    fn malformed_html_fosters_table_text_and_flattens_template_contents() {
        let document =
            parse_html("<table>before<tr><td>A<td>B</table>after<template><p>template</template>")
                .unwrap();
        validate_reachable_links(&document);
        assert_eq!(
            descendant_text(&document, DOCUMENT),
            "beforeABaftertemplate"
        );
        let template = element(&document, "template");
        assert!(template.first_child.is_some());
        assert!(document
            .nodes
            .iter()
            .any(|node| matches!(&node.kind, Kind::Element { name, .. } if name == "tbody")));
    }

    #[test]
    fn xml_depth_pruning_retains_the_full_stream_and_flat_drop_is_bounded() {
        let source = format!(
            "<body>{}tail{}</body>",
            "<section>".repeat(4096),
            "</section>".repeat(4096)
        );
        let document = parse(&source, false).unwrap();
        assert!(!document.html);
        let preorder = validate_reachable_links(&document);
        let mut depths = vec![0; document.nodes.len()];
        for index in preorder {
            if let Some(parent) = document.nodes[index].parent {
                depths[index] = depths[parent] + 1;
            }
        }
        assert!(depths.into_iter().max().unwrap() <= MAX_XML_DEPTH);
        assert_eq!(
            document
                .nodes
                .iter()
                .filter(
                    |node| matches!(&node.kind, Kind::Element { name, .. } if name == "section")
                )
                .count(),
            4096
        );
        assert_eq!(descendant_text(&document, DOCUMENT), "tail");
        let mut arena = Arena::new();
        assert!(arena.charge(MAX_TREE_BYTES - arena.bytes));
        assert!(arena.insert(Kind::Text("overflow".into())).is_none());
        assert!(arena.finish(false).is_none());
    }

    #[test]
    fn preindex_computes_semantic_tags_notes_resources_and_whitespace_hints() {
        let source = r##"
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" dir="ltr">
              <head>
                <title>Chapter Title</title>
                <meta name="viewport" content="width=600, height=800"/>
                <link rel="stylesheet" href="book.css"/>
                <style>body { writing-mode: vertical-rl; }</style>
              </head>
              <body>
                <div class="title2"><p>Heading</p></div>
                <div class="empty-line">   </div>
                <p id="p1" style="color:#112233">Lead <a epub:type="noteref" href="notes.xhtml#n1"><sup>1</sup></a> <a role="doc-noteref" class="my-footnote" href="#n2">note</a> <img src="fig.png"/></p>
                <aside epub:type="footnote"><p id="n1">Footnote</p></aside>
                <ol class="notes"><li id="n2">Endnote</li></ol>
                <math display="block"><mi>x</mi></math>
                <svg><image xlink:href="cover.jpg"/></svg>
                <div id="empty-nbsp" class="empty-line">&nbsp;&#x200B;</div>
                <div id="pre-nbsp" class="empty-line"><pre>&nbsp;</pre></div>
              </body>
            </html>
        "##;
        let doc = parse(source, false).unwrap();
        let pre = &doc.preindex;
        assert_ne!(pre.doc_flags & DOC_HAS_MATH, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_SVG, 0);
        assert_ne!(pre.doc_flags & DOC_MAYBE_VERTICAL_WRITING, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_ANY_STYLE, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_ANY_LANG, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_ANY_DIR, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_ANY_ID, 0);
        assert_ne!(pre.doc_flags & DOC_HAS_ANY_A_HREF, 0);
        assert_eq!(pre.doc_flags & DOC_HAS_SCRIPT, 0);
        assert!(pre.body_node.is_some());
        assert!(pre.head_title_node.is_some());
        assert!(pre.viewport_meta_node.is_some());
        assert_eq!(
            pre.nodes.iter().filter(|meta| meta.is_stylesheet).count(),
            2
        );

        let idx_of = |predicate: &dyn Fn(&Node) -> bool| -> usize {
            doc.nodes.iter().position(predicate).unwrap()
        };
        let p1 = idx_of(
            &|n| matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == "p1")),
        );
        assert_ne!(pre.nodes[p1].tag_flags & TAG_PUBLISHER_BOX, 0);
        assert_ne!(pre.nodes[p1].attr_flags & ATTR_HAS_ID, 0);
        assert_ne!(pre.nodes[p1].attr_flags & ATTR_HAS_STYLE, 0);
        assert_ne!(pre.nodes[p1].attr_flags & ATTR_ANC_HAS_LANG, 0);
        assert_ne!(pre.nodes[p1].attr_flags & ATTR_ANC_HAS_DIR, 0);
        assert_ne!(pre.nodes[p1].subtree_flags & SUBTREE_HAS_IMG, 0);
        assert_ne!(pre.nodes[p1].subtree_flags & SUBTREE_HAS_SUP, 0);
        assert_ne!(pre.nodes[p1].subtree_flags & SUBTREE_HAS_A_HREF, 0);

        let noteref = idx_of(
            &|n| matches!(&n.kind, Kind::Element { name, attrs, .. } if name == "a" && attrs.iter().any(|(k, _)| k == "epub:type")),
        );
        assert_ne!(pre.nodes[noteref].attr_flags & ATTR_EPUB_NOTEREF, 0);
        assert_ne!(pre.nodes[noteref].attr_flags & ATTR_HAS_HREF, 0);
        assert_ne!(pre.nodes[noteref].subtree_flags & SUBTREE_HAS_SUP, 0);

        let role_noteref = idx_of(
            &|n| matches!(&n.kind, Kind::Element { name, attrs, .. } if name == "a" && attrs.iter().any(|(k, _)| k == "role")),
        );
        assert_ne!(
            pre.nodes[role_noteref].attr_flags & ATTR_ROLE_DOC_NOTEREF,
            0
        );
        assert_ne!(pre.nodes[role_noteref].attr_flags & ATTR_HAS_NOTE_HINT, 0);

        let footnote_aside =
            idx_of(&|n| matches!(&n.kind, Kind::Element { name, .. } if name == "aside"));
        assert_ne!(
            pre.nodes[footnote_aside].attr_flags & ATTR_IS_SEMANTIC_NOTE_CONTAINER,
            0
        );

        let collection_li = idx_of(
            &|n| matches!(&n.kind, Kind::Element { name, attrs, .. } if name == "li" && attrs.iter().any(|(k, v)| k == "id" && v == "n2")),
        );
        assert_ne!(
            pre.nodes[collection_li].attr_flags & ATTR_IS_SEMANTIC_NOTE_CONTAINER,
            0
        );

        let title_div = idx_of(
            &|n| matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "class" && v == "title2")),
        );
        assert_eq!(
            (pre.nodes[title_div].attr_flags >> TITLE_CLASS_SHIFT) & 0x7,
            3
        );

        let empty_line = idx_of(
            &|n| matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "class" && v == "empty-line")),
        );
        assert_ne!(
            pre.nodes[empty_line].attr_flags & ATTR_HAS_EMPTY_LINE_CLASS,
            0
        );
        assert_eq!(
            pre.nodes[empty_line].subtree_flags & SUBTREE_HAS_JAVA_NON_WS_TEXT,
            0
        );

        let empty_nbsp = idx_of(
            &|n| matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == "empty-nbsp")),
        );
        assert_eq!(
            pre.nodes[empty_nbsp].subtree_flags & SUBTREE_HAS_JAVA_NON_WS_TEXT,
            0
        );

        let pre_nbsp = idx_of(
            &|n| matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == "pre-nbsp")),
        );
        assert_ne!(
            pre.nodes[pre_nbsp].subtree_flags & SUBTREE_HAS_JAVA_NON_WS_TEXT,
            0
        );

        let math = idx_of(&|n| matches!(&n.kind, Kind::Element { name, .. } if name == "math"));
        assert_ne!(pre.nodes[math].attr_flags & ATTR_IS_DISPLAY_MATH, 0);
        assert_ne!(pre.nodes[math].attr_flags & ATTR_IS_BLOCK_PROBE_MATH, 0);

        let blank_text = doc.nodes[empty_line].first_child.unwrap();
        assert_ne!(pre.nodes[blank_text].text_flags & TEXT_HTML_BLANK, 0);
        assert_eq!(pre.nodes[blank_text].text_flags & TEXT_COMPACT, 0);
        let compact_text = doc.nodes[collection_li].first_child.unwrap();
        assert_eq!(pre.nodes[compact_text].text_flags & TEXT_HTML_BLANK, 0);
        assert_ne!(pre.nodes[compact_text].text_flags & TEXT_COMPACT, 0);
        assert_ne!(pre.nodes[compact_text].text_flags & TEXT_HAS_JAVA_NON_WS, 0);
    }

    #[test]
    fn preindex_classifies_text_already_collapsed_ascii_and_boundaries() {
        let xml = r#"
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <p id="plain">Hello world</p>
                <p id="edges"> Hello </p>
                <p id="single-space"> </p>
                <p id="nbsp">Привет&nbsp;мир &#x2009;!</p>
                <p id="nbsp-only">&#160;&#8239;</p>
                <p id="vtab"> &#x000B;</p>
                <p id="double">a  b</p>
                <p id="newline">a
b</p>
                <p id="tab">a&#9;b</p>
                <p id="cr">a&#13;b</p>
                <p id="ff">a&#12;b</p>
                <p id="entities">Hello &amp; world</p>
                <p id="entities-double">Hello  &amp; world</p>
                <pre id="pre"> single leading </pre>
              </body>
            </html>
        "#;
        let doc = parse(xml, false).unwrap();
        assert!(!doc.html);
        let pre = &doc.preindex;
        let text_flags_of = |id: &str| -> u32 {
            let elem_idx = doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            let text_idx = doc.nodes[elem_idx].first_child.unwrap();
            assert!(matches!(doc.nodes[text_idx].kind, Kind::Text(_)));
            pre.nodes[text_idx].text_flags
        };

        let plain = text_flags_of("plain");
        assert_ne!(plain & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(plain & TEXT_HTML_BLANK, 0);
        assert_eq!(plain & TEXT_STARTS_HTML_WS, 0);
        assert_eq!(plain & TEXT_ENDS_HTML_WS, 0);
        assert_ne!(plain & TEXT_HAS_JAVA_NON_WS, 0);
        assert_eq!(plain & TEXT_HAS_CR, 0);

        let edges = text_flags_of("edges");
        assert_ne!(edges & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(edges & TEXT_HTML_BLANK, 0);
        assert_ne!(edges & TEXT_STARTS_HTML_WS, 0);
        assert_ne!(edges & TEXT_ENDS_HTML_WS, 0);
        assert_ne!(edges & TEXT_HAS_JAVA_NON_WS, 0);

        let single_space = text_flags_of("single-space");
        assert_ne!(single_space & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_ne!(single_space & TEXT_HTML_BLANK, 0);
        assert_ne!(single_space & TEXT_STARTS_HTML_WS, 0);
        assert_ne!(single_space & TEXT_ENDS_HTML_WS, 0);
        assert_eq!(single_space & TEXT_HAS_JAVA_NON_WS, 0);

        let nbsp = text_flags_of("nbsp");
        assert_ne!(nbsp & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(nbsp & TEXT_HTML_BLANK, 0);
        assert_ne!(nbsp & TEXT_HAS_JAVA_NON_WS, 0);
        assert_ne!(nbsp & TEXT_HAS_KOTLIN_NON_WS, 0);

        let nbsp_only = text_flags_of("nbsp-only");
        assert_ne!(nbsp_only & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(nbsp_only & TEXT_HTML_BLANK, 0);
        assert_ne!(nbsp_only & TEXT_HAS_JAVA_NON_WS, 0);
        assert_eq!(nbsp_only & TEXT_HAS_KOTLIN_NON_WS, 0);

        let nel = classify_text("\u{0085}");
        assert_ne!(nel & TEXT_HAS_JAVA_NON_WS, 0);
        assert_ne!(nel & TEXT_HAS_KOTLIN_NON_WS, 0);

        let vtab = text_flags_of("vtab");
        assert_ne!(vtab & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(vtab & TEXT_HTML_BLANK, 0);
        assert_ne!(vtab & TEXT_STARTS_HTML_WS, 0);
        assert_eq!(vtab & TEXT_ENDS_HTML_WS, 0);
        assert_eq!(vtab & TEXT_HAS_JAVA_NON_WS, 0);
        assert_eq!(vtab & TEXT_HAS_KOTLIN_NON_WS, 0);

        assert_eq!(text_flags_of("double") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(text_flags_of("newline") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(text_flags_of("tab") & TEXT_ALREADY_COLLAPSED_ASCII, 0);

        let cr = text_flags_of("cr");
        assert_eq!(cr & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_ne!(cr & TEXT_HAS_CR, 0);

        assert_eq!(text_flags_of("ff") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_ne!(text_flags_of("entities") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(
            text_flags_of("entities-double") & TEXT_ALREADY_COLLAPSED_ASCII,
            0
        );

        let pre_flags = text_flags_of("pre");
        assert_ne!(pre_flags & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_ne!(pre_flags & TEXT_STARTS_HTML_WS, 0);
        assert_ne!(pre_flags & TEXT_ENDS_HTML_WS, 0);

        let html_doc = parse(
            "<p id='h1'>collapsed text</p><p id='h2'>two  spaces</p><p id='h3'>line1\nline2</p>",
            true,
        )
        .unwrap();
        assert!(html_doc.html);
        let html_flags = |id: &str| -> u32 {
            let elem_idx = html_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            let text_idx = html_doc.nodes[elem_idx].first_child.unwrap();
            html_doc.preindex.nodes[text_idx].text_flags
        };
        assert_ne!(html_flags("h1") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(html_flags("h2") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
        assert_eq!(html_flags("h3") & TEXT_ALREADY_COLLAPSED_ASCII, 0);
    }

    #[test]
    fn preindex_classifies_subtree_simple_leaf_text_without_ancestor_leakage() {
        let xml = r#"
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div id="wrap"><p id="simple">Plain text</p></div>
                <h1 id="h1">  Heading
                  multiline  </h1>
                <p id="with-span"><span id="inner-span">Span text</span></p>
                <p id="mixed">Lead <em>italic</em></p>
                <p id="comment">Text<!-- comment --></p>
                <p id="cdata"><![CDATA[cdata text]]></p>
                <p id="blank">   &#10;&#9; </p>
                <p id="nbsp">&#160;&#8239;</p>
                <p id="empty"></p>
              </body>
            </html>
        "#;
        let doc = parse(xml, false).unwrap();
        let pre = &doc.preindex;
        let subtree_flags_of = |id: &str| -> u32 {
            let elem_idx = doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            pre.nodes[elem_idx].subtree_flags
        };

        assert_ne!(subtree_flags_of("simple") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_ne!(subtree_flags_of("h1") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_ne!(subtree_flags_of("inner-span") & SUBTREE_SIMPLE_LEAF_TEXT, 0);

        assert_eq!(subtree_flags_of("wrap") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("with-span") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("mixed") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("comment") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("cdata") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("blank") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("nbsp") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(subtree_flags_of("empty") & SUBTREE_SIMPLE_LEAF_TEXT, 0);

        let html_doc = parse(
            "<div id='hw'><p id='hs'>HTML leaf</p><p id='hb'>   </p></div>",
            true,
        )
        .unwrap();
        let html_subtree = |id: &str| -> u32 {
            let idx = html_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            html_doc.preindex.nodes[idx].subtree_flags
        };
        assert_ne!(html_subtree("hs") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(html_subtree("hw") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
        assert_eq!(html_subtree("hb") & SUBTREE_SIMPLE_LEAF_TEXT, 0);
    }

    #[test]
    fn preindex_classifies_ancestor_flow_scope_across_blocks_and_inline_wrappers() {
        let xml = r##"
            <html id="html-root" xmlns="http://www.w3.org/1999/xhtml">
              <body id="body-root">
                <p id="p-body">Standard paragraph</p>
                <h1 id="h1-body">H1</h1>
                <h2 id="h2-body">H2</h2>
                <h3 id="h3-body">H3</h3>
                <h4 id="h4-body">H4</h4>
                <h5 id="h5-body">H5</h5>
                <h6 id="h6-body">H6</h6>
                <div id="div-body">
                  <section id="sec">
                    <blockquote id="bq">
                      <p id="p-nested">Nested block paragraph</p>
                      <h2 id="h2-nested">Nested heading</h2>
                      <div id="div-nested">Nested div</div>
                    </blockquote>
                  </section>
                </div>
                <a id="a-wrap" href="#fn1">
                  <p id="p-in-a">Block inside link</p>
                  <div id="div-in-a"><h3 id="h3-in-a">Heading inside link</h3></div>
                </a>
                <span id="span-wrap" dir="rtl">
                  <p id="p-in-span">Paragraph inside span</p>
                  <div id="div-in-span">Div inside span</div>
                </span>
                <bdi id="bdi-wrap"><p id="p-in-bdi">Paragraph inside bdi</p></bdi>
                <bdo id="bdo-wrap" dir="rtl"><div id="div-in-bdo"><p id="p-in-bdo">Paragraph inside bdo</p></div></bdo>
                <em id="em-wrap"><span id="span-in-em">Verse line</span></em>
              </body>
            </html>
        "##;
        let doc = parse(xml, false).unwrap();
        let attr_flags_of = |id: &str| -> u32 {
            let idx = doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap_or_else(|| panic!("missing element #{id}"));
            doc.preindex.nodes[idx].attr_flags
        };

        for id in [
            "html-root",
            "body-root",
            "p-body",
            "h1-body",
            "h2-body",
            "h3-body",
            "h4-body",
            "h5-body",
            "h6-body",
            "div-body",
            "sec",
            "bq",
            "p-nested",
            "h2-nested",
            "div-nested",
        ] {
            assert_eq!(
                attr_flags_of(id) & ATTR_ANC_HAS_FLOW_SCOPE,
                0,
                "expected ATTR_ANC_HAS_FLOW_SCOPE == 0 on #{id}"
            );
        }

        for id in [
            "a-wrap",
            "p-in-a",
            "div-in-a",
            "h3-in-a",
            "span-wrap",
            "p-in-span",
            "div-in-span",
            "bdi-wrap",
            "p-in-bdi",
            "bdo-wrap",
            "div-in-bdo",
            "p-in-bdo",
            "em-wrap",
            "span-in-em",
        ] {
            assert_ne!(
                attr_flags_of(id) & ATTR_ANC_HAS_FLOW_SCOPE,
                0,
                "expected ATTR_ANC_HAS_FLOW_SCOPE != 0 on #{id}"
            );
        }
    }

    #[test]
    fn preindex_classifies_subtree_inline_flow_candidate() {
        let plain_xml = r#"
            <html id="html-root" xmlns="http://www.w3.org/1999/xhtml">
              <body id="body-root">
                <div id="wrap">
                  <h1 id="h1">Heading</h1>
                  <p id="p1">Plain text</p>
                  <p id="p-br">Line one<br id="br1"/>Line two</p>
                  <p id="p-img"><img id="img1" src="a.png"/></p>
                </div>
              </body>
            </html>
        "#;
        let plain_doc = parse(plain_xml, false).unwrap();
        let plain_sub = |id: &str| -> u32 {
            let idx = plain_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap_or_else(|| panic!("missing #{id}"));
            plain_doc.preindex.nodes[idx].subtree_flags
        };
        for id in [
            "html-root",
            "body-root",
            "wrap",
            "h1",
            "p1",
            "p-br",
            "br1",
            "p-img",
            "img1",
        ] {
            assert_eq!(
                plain_sub(id) & SUBTREE_HAS_INLINE_FLOW_CANDIDATE,
                0,
                "expected SUBTREE_HAS_INLINE_FLOW_CANDIDATE == 0 on #{id}"
            );
        }

        let mixed_xml = r#"
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body id="body-mixed">
                <div id="plain-div"><p id="plain-p">Plain text</p></div>
                <div id="span-div"><p id="span-p"><span id="inner-span">text</span></p></div>
                <p id="font-p"><font id="inner-font">A</font>fter</p>
                <p id="em-p"><em id="inner-em">Em</em></p>
              </body>
            </html>
        "#;
        let mixed_doc = parse(mixed_xml, false).unwrap();
        let mixed_sub = |id: &str| -> u32 {
            let idx = mixed_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap_or_else(|| panic!("missing #{id}"));
            mixed_doc.preindex.nodes[idx].subtree_flags
        };
        for id in ["plain-div", "plain-p"] {
            assert_eq!(
                mixed_sub(id) & SUBTREE_HAS_INLINE_FLOW_CANDIDATE,
                0,
                "expected SUBTREE_HAS_INLINE_FLOW_CANDIDATE == 0 on #{id}"
            );
        }
        for id in [
            "body-mixed",
            "span-div",
            "span-p",
            "inner-span",
            "font-p",
            "inner-font",
            "em-p",
            "inner-em",
        ] {
            assert_ne!(
                mixed_sub(id) & SUBTREE_HAS_INLINE_FLOW_CANDIDATE,
                0,
                "expected SUBTREE_HAS_INLINE_FLOW_CANDIDATE != 0 on #{id}"
            );
        }

        let html_doc = parse(
            "<p id='hp'>plain text</p><p id='hs'><span id='s'>inline</span></p>",
            true,
        )
        .unwrap();
        let html_sub = |id: &str| -> u32 {
            let idx = html_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            html_doc.preindex.nodes[idx].subtree_flags
        };
        assert_eq!(html_sub("hp") & SUBTREE_HAS_INLINE_FLOW_CANDIDATE, 0);
        assert_ne!(html_sub("hs") & SUBTREE_HAS_INLINE_FLOW_CANDIDATE, 0);
        assert_ne!(html_sub("s") & SUBTREE_HAS_INLINE_FLOW_CANDIDATE, 0);
    }

    #[test]
    fn preindex_classifies_presentational_attributes() {
        let xml = r##"
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body id="body-text" text="#112233">
                <p id="plain" class="lead" style="font-style: italic">Plain</p>
                <p id="empty-attrs" align="" color="" text="" bgcolor="">Empty</p>
                <p id="has-align" align="center">Centered</p>
                <font id="has-color" COLOR="#336699">Colored</font>
                <div id="has-bgcolor" BgColor="#eeeeee">Shaded</div>
                <div id="title-no-pres" class="title6">Title</div>
                <div id="title-with-pres" class="title6" ALIGN="right">Title aligned</div>
              </body>
            </html>
        "##;
        let doc = parse(xml, false).unwrap();
        let attr_flags_of = |id: &str| -> u32 {
            let idx = doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap_or_else(|| panic!("missing #{id}"));
            doc.preindex.nodes[idx].attr_flags
        };

        for id in ["plain", "empty-attrs", "title-no-pres"] {
            assert_eq!(
                attr_flags_of(id) & ATTR_HAS_PRESENTATIONAL,
                0,
                "expected ATTR_HAS_PRESENTATIONAL == 0 on #{id}"
            );
        }
        for id in [
            "body-text",
            "has-align",
            "has-color",
            "has-bgcolor",
            "title-with-pres",
        ] {
            assert_ne!(
                attr_flags_of(id) & ATTR_HAS_PRESENTATIONAL,
                0,
                "expected ATTR_HAS_PRESENTATIONAL != 0 on #{id}"
            );
        }
        assert_eq!(
            (attr_flags_of("title-no-pres") >> TITLE_CLASS_SHIFT) & 0x7,
            6
        );
        assert_eq!(
            (attr_flags_of("title-with-pres") >> TITLE_CLASS_SHIFT) & 0x7,
            6
        );

        let html_doc = parse(
            "<p id='hp'>Plain</p><p id='ha' ALIGN='right'>Right</p><p id='he' align=''>Empty</p>",
            true,
        )
        .unwrap();
        let html_flags = |id: &str| -> u32 {
            let idx = html_doc
                .nodes
                .iter()
                .position(|n| {
                    matches!(&n.kind, Kind::Element { attrs, .. } if attrs.iter().any(|(k, v)| k == "id" && v == id))
                })
                .unwrap();
            html_doc.preindex.nodes[idx].attr_flags
        };
        assert_eq!(html_flags("hp") & ATTR_HAS_PRESENTATIONAL, 0);
        assert_ne!(html_flags("ha") & ATTR_HAS_PRESENTATIONAL, 0);
        assert_eq!(html_flags("he") & ATTR_HAS_PRESENTATIONAL, 0);
    }

    #[test]
    fn preindex_sets_doc_has_any_presentational() {
        let plain_xml = r#"
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <p class="lead" style="text-align: center">Plain</p>
                <p align="" color="" text="" bgcolor="">Empty attrs</p>
              </body>
            </html>
        "#;
        let plain = parse(plain_xml, false).unwrap();
        assert_eq!(plain.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL, 0);

        for snippet in [
            r##"<html xmlns="http://www.w3.org/1999/xhtml"><body><p align="center">A</p></body></html>"##,
            r##"<html xmlns="http://www.w3.org/1999/xhtml"><body><font color="#123456">B</font></body></html>"##,
            r##"<html xmlns="http://www.w3.org/1999/xhtml"><body text="#222222"><p>C</p></body></html>"##,
            r##"<html xmlns="http://www.w3.org/1999/xhtml"><body><div bgcolor="#eeeeee"><p>D</p></div></body></html>"##,
            r##"<html xmlns="http://www.w3.org/1999/xhtml"><body><center><p>E</p></center></body></html>"##,
        ] {
            let doc = parse(snippet, false).unwrap();
            assert_ne!(
                doc.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL,
                0,
                "expected DOC_HAS_ANY_PRESENTATIONAL != 0 for {snippet}"
            );
        }

        let plain_html = parse("<p>Hello</p><p align=''>World</p>", true).unwrap();
        assert_eq!(
            plain_html.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL,
            0
        );
        let center_html = parse("<center><p>Centered</p></center>", true).unwrap();
        assert_ne!(
            center_html.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL,
            0
        );
        let align_html = parse("<p ALIGN='right'>Right</p>", true).unwrap();
        assert_ne!(
            align_html.preindex.doc_flags & DOC_HAS_ANY_PRESENTATIONAL,
            0
        );
    }

    #[test]
    fn entity_decoding_keeps_unknown_names_and_attribute_ambiguity() {
        assert_eq!(
            decode_entities("&nbsp;&amp; &#x1f600; &unknown;", false),
            "\u{a0}& 😀 &unknown;"
        );
        assert_eq!(
            decode_entities("&notebook &not; &copy=", false),
            "¬ebook ¬ ©="
        );
        assert_eq!(
            decode_entities("&notebook &copy= &copy;= &#128;", true),
            "&notebook &copy= ©= €"
        );
        assert_eq!(decode_entities("&NotEqualTilde;", false), "≂\u{338}");
        assert_eq!(
            decode_entities("&#x110000; &#999999999999999999999;", false),
            "� �"
        );
    }
}
