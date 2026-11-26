//! A checked transfer of the chapter's selector and cascade inputs.
use crate::{
    call_jni,
    chapter_markup::{Document, Kind},
    chapter_markup_jni::retained_document,
    css_match::{
        cascade_inline, Attr, AttrOp, Combinator, Compound, DeclarationRef, Node, Pseudo, Rule,
        Session, Simple,
    },
};
use jni::objects::{JByteArray, JIntArray, JObject};
use jni::sys::{jint, jintArray, jlong};
use jni::JNIEnv;
use std::collections::{HashMap, HashSet};
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

const MAX_DOM_BYTES: i32 = 256 * 1024 * 1024;
const MAX_RULE_BYTES: i32 = 128 * 1024 * 1024;
const MAX_RULES: usize = 16_384;
const MAX_DECLARATIONS_PER_BLOCK: usize = 512;
// The standalone cascade can also receive the candidates of one expanded block.
const MAX_INLINE_CANDIDATES: usize = MAX_DECLARATIONS_PER_BLOCK * 12;
const MAX_SELECTOR_COMPOUNDS: usize = 64;
const MAX_SIMPLE_COMPONENTS: usize = 64;
const MAX_RELATIVES: usize = 256;
const MAX_BUDGET: i32 = 5_000_000;
const POOL_COUNT: usize = 4;

type DecodeResult<T> = Result<T, &'static str>;

struct Input<'a> {
    bytes: &'a [u8],
    next_has_id: usize,
}

#[derive(Clone, Copy, PartialEq)]
enum SimpleContext {
    Rule,
    Relative,
    Negated,
}

impl<'a> Input<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self {
            bytes,
            next_has_id: 0,
        }
    }

    fn take(&mut self, length: usize) -> DecodeResult<&'a [u8]> {
        let (head, tail) = self
            .bytes
            .split_at_checked(length)
            .ok_or("Truncated CSS selector payload")?;
        self.bytes = tail;
        Ok(head)
    }

    fn int(&mut self) -> DecodeResult<i32> {
        let bytes = self.take(4)?;
        Ok(i32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn flag(&mut self) -> DecodeResult<bool> {
        match self.int()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("Invalid CSS wire flag"),
        }
    }

    fn optional_index(&mut self) -> DecodeResult<Option<usize>> {
        match self.int()? {
            -1 => Ok(None),
            index => usize::try_from(index)
                .map(Some)
                .map_err(|_| "Invalid CSS selector node index"),
        }
    }

    fn read_text(&mut self, length: i32) -> DecodeResult<String> {
        let length = usize::try_from(length).map_err(|_| "Invalid CSS selector string length")?;
        std::str::from_utf8(self.take(length)?)
            .map(str::to_owned)
            .map_err(|_| "Invalid UTF-8 in CSS selector payload")
    }

    fn text(&mut self) -> DecodeResult<String> {
        let length = self.int()?;
        self.read_text(length)
    }

    fn optional_text(&mut self) -> DecodeResult<Option<String>> {
        let length = self.int()?;
        if length == -1 {
            Ok(None)
        } else {
            self.read_text(length).map(Some)
        }
    }

    fn list<T>(
        &mut self,
        limit: usize,
        minimum_bytes: usize,
        mut read_item: impl FnMut(&mut Self) -> DecodeResult<T>,
    ) -> DecodeResult<Vec<T>> {
        let count = usize::try_from(self.int()?).map_err(|_| "Invalid CSS selector list count")?;
        // The size of a list is checked before reserving Rust-side storage.
        if count > limit || count > self.bytes.len() / minimum_bytes {
            return Err("Invalid CSS selector list count");
        }
        let mut items = Vec::new();
        items
            .try_reserve_exact(count)
            .map_err(|_| "Cannot allocate CSS selector list")?;
        for _ in 0..count {
            items.push(read_item(self)?);
        }
        Ok(items)
    }

    fn attribute_values(&mut self) -> DecodeResult<Vec<(String, String)>> {
        self.list(usize::MAX, 8, |input| Ok((input.text()?, input.text()?)))
    }

    fn node(&mut self) -> DecodeResult<Node> {
        Ok(Node {
            tag: self.text()?,
            id: self.text()?,
            classes: self.list(usize::MAX, 4, Self::text)?,
            attrs: self.attribute_values()?,
            absolute_attrs: self.attribute_values()?,
            parent: self.optional_index()?,
            previous: self.optional_index()?,
            next: self.optional_index()?,
            first_child: self.optional_index()?,
        })
    }

    fn rule(&mut self, block_count: usize) -> DecodeResult<Rule> {
        let id = self.int()?;
        let specificity = self.int()?;
        let order = self.int()?;
        if id < 0 || id as usize >= block_count || order < 0 {
            return Err("Invalid CSS declaration block reference or source order");
        }
        Ok(Rule {
            id,
            specificity,
            order,
            chain: self.chain(SimpleContext::Rule)?,
        })
    }

    fn chain(&mut self, context: SimpleContext) -> DecodeResult<Vec<Compound>> {
        let chain = self.list(MAX_SELECTOR_COMPOUNDS, 24, |input| {
            let combinator = match input.int()? {
                0 => Combinator::Descendant,
                1 => Combinator::Child,
                2 => Combinator::NextSibling,
                3 => Combinator::SubsequentSibling,
                _ => return Err("Invalid CSS selector combinator"),
            };
            let simple = input.simple(context)?;
            Ok(Compound { combinator, simple })
        })?;
        if chain.is_empty() {
            return Err("Empty CSS selector chain");
        }
        Ok(chain)
    }

    fn simple(&mut self, context: SimpleContext) -> DecodeResult<Simple> {
        let tag = self.optional_text()?;
        let id = self.optional_text()?;
        let classes = self.list(MAX_SIMPLE_COMPONENTS, 4, Self::text)?;
        let attrs = self.list(MAX_SIMPLE_COMPONENTS - classes.len(), 16, Self::attribute)?;
        let pseudo_limit = if context == SimpleContext::Negated {
            0
        } else {
            MAX_SIMPLE_COMPONENTS - classes.len() - attrs.len()
        };
        let pseudos = self.list(pseudo_limit, 4, |input| input.pseudo(context))?;
        Ok(Simple {
            tag,
            id,
            classes,
            attrs,
            pseudos,
        })
    }

    fn attribute(&mut self) -> DecodeResult<Attr> {
        let name = self.text()?;
        let op = match self.int()? {
            0 => AttrOp::Exists,
            1 => AttrOp::Equals,
            2 => AttrOp::Includes,
            3 => AttrOp::Dash,
            4 => AttrOp::Prefix,
            5 => AttrOp::Suffix,
            6 => AttrOp::Substring,
            _ => return Err("Invalid CSS attribute operator"),
        };
        let value = self.optional_text()?;
        let namespace = self.optional_text()?;
        Ok(Attr {
            name,
            op,
            value,
            namespace,
        })
    }

    fn pseudo(&mut self, context: SimpleContext) -> DecodeResult<Pseudo> {
        match self.int()? {
            0 => Ok(Pseudo::FirstChild),
            1 => Ok(Pseudo::LastChild),
            2 => Ok(Pseudo::OnlyChild),
            3 => Ok(Pseudo::FirstOfType),
            4 => Ok(Pseudo::Nth {
                a: self.int()?,
                b: self.int()?,
                of_type: self.flag()?,
                from_end: self.flag()?,
            }),
            5 if context == SimpleContext::Rule => {
                let id = self.next_has_id;
                self.next_has_id += 1;
                let relatives = self.list(MAX_RELATIVES, 28, |input| {
                    input.chain(SimpleContext::Relative)
                })?;
                if relatives.is_empty() {
                    return Err("Empty CSS relative selector list");
                }
                Ok(Pseudo::Has { id, relatives })
            }
            6 => Ok(Pseudo::Not(Box::new(self.simple(SimpleContext::Negated)?))),
            _ => Err("Invalid CSS selector pseudo-class"),
        }
    }

    fn end(&self) -> DecodeResult<()> {
        if self.bytes.is_empty() {
            Ok(())
        } else {
            Err("Trailing bytes in CSS selector payload")
        }
    }
}

fn decode_nodes(bytes: &[u8]) -> DecodeResult<Vec<Node>> {
    let mut input = Input::new(bytes);
    let nodes = input.list(usize::MAX, 36, Input::node)?;
    input.end()?;
    validate_topology(&nodes)?;
    Ok(nodes)
}

/// Match the Jsoup snapshot's element-only preorder, including its document root.
fn chapter_nodes(document: &Document) -> DecodeResult<Vec<Node>> {
    let invalid = "Invalid retained chapter topology";
    let root = document.nodes.first().ok_or(invalid)?;
    let mut nodes = vec![Node {
        tag: "#root".into(),
        id: String::new(),
        classes: Vec::new(),
        attrs: Vec::new(),
        absolute_attrs: Vec::new(),
        parent: None,
        previous: None,
        next: None,
        first_child: None,
    }];
    let mut pending: Vec<(usize, usize, Option<usize>)> = Vec::new();
    if let Some(child) = root.first_child {
        pending.push((child, 0, None));
    }

    let mut visited = 0;
    while let Some((index, parent, previous)) = pending.pop() {
        let source = document.nodes.get(index).ok_or(invalid)?;
        visited += 1;
        if visited >= document.nodes.len() {
            return Err(invalid);
        }
        let Kind::Element { name, attrs, .. } = &source.kind else {
            if let Some(next) = source.next_sibling {
                pending.push((next, parent, previous));
            }
            continue;
        };

        let id = nodes.len();
        if let Some(previous) = previous {
            nodes[previous].next = Some(id);
        } else {
            nodes[parent].first_child = Some(id);
        }
        let mut seen = HashSet::new();
        // Jsoup splits class tokens on exactly these five HTML space characters.
        let classes = chapter_attr(attrs, "class")
            .split([' ', '\t', '\r', '\n', '\u{000c}'])
            .filter(|class| !class.is_empty() && seen.insert(*class))
            .map(str::to_owned)
            .collect();
        nodes
            .try_reserve(1)
            .map_err(|_| "Cannot allocate retained chapter selector nodes")?;
        nodes.push(Node {
            tag: name.trim_matches(|ch| ch <= ' ').to_lowercase(),
            id: chapter_attr(attrs, "id").to_owned(),
            classes,
            attrs: attrs
                .iter()
                .filter(|(key, _)| !(key.len() > 1 && key.starts_with('/')))
                .cloned()
                .collect(),
            absolute_attrs: Vec::new(),
            parent: Some(parent),
            previous,
            next: None,
            first_child: None,
        });

        if let Some(next) = source.next_sibling {
            pending.push((next, parent, Some(id)));
        }
        if let Some(child) = source.first_child {
            pending.push((child, id, None));
        }
    }
    Ok(nodes)
}

/// First case-insensitive match, as in Jsoup's id() and classNames().
fn chapter_attr<'a>(attrs: &'a [(String, String)], name: &str) -> &'a str {
    attrs
        .iter()
        .find(|(key, _)| {
            key.eq_ignore_ascii_case(name)
                || (!key.is_ascii()
                    && key
                        .chars()
                        .map(|ch| match ch {
                            '\u{0130}' | '\u{0131}' => 'i',
                            '\u{017f}' => 's',
                            _ => ch.to_ascii_lowercase(),
                        })
                        .eq(name.chars()))
        })
        .map_or("", |(_, value)| value)
}

fn validate_topology(nodes: &[Node]) -> DecodeResult<()> {
    let invalid = "Invalid CSS selector DOM topology";
    let root = nodes.first().ok_or(invalid)?;
    if root.parent.is_some() || root.previous.is_some() || root.next.is_some() {
        return Err(invalid);
    }
    for (index, node) in nodes.iter().enumerate() {
        if index > 0 {
            let parent = node
                .parent
                .filter(|parent| *parent < index)
                .ok_or(invalid)?;
            if let Some(previous) = node.previous {
                if previous >= index
                    || nodes[previous].parent != Some(parent)
                    || nodes[previous].next != Some(index)
                {
                    return Err(invalid);
                }
            } else if nodes[parent].first_child != Some(index) {
                return Err(invalid);
            }
        }
        if let Some(next) = node.next {
            let sibling = nodes.get(next).ok_or(invalid)?;
            if next <= index || sibling.previous != Some(index) || sibling.parent != node.parent {
                return Err(invalid);
            }
        }
        if let Some(first_child) = node.first_child {
            let child = nodes.get(first_child).ok_or(invalid)?;
            if first_child <= index || child.parent != Some(index) || child.previous.is_some() {
                return Err(invalid);
            }
        }
    }
    // Check that traversing these links gives the same order as the transfer.
    // Forward sibling links and backward parent links were validated above.
    let mut cursor = Some(0);
    for index in 0..nodes.len() {
        if cursor != Some(index) {
            return Err(invalid);
        }
        cursor = nodes[index].first_child;
        if cursor.is_none() {
            let mut node = Some(index);
            while let Some(current) = node {
                if nodes[current].next.is_some() {
                    cursor = nodes[current].next;
                    break;
                }
                node = nodes[current].parent;
            }
        }
    }
    if cursor.is_some() {
        return Err(invalid);
    }
    Ok(())
}

struct RuleProgram {
    blocks: Vec<Vec<DeclarationRef>>,
    pools: [Vec<Rule>; POOL_COUNT],
}

fn decode_rules(bytes: &[u8]) -> DecodeResult<RuleProgram> {
    let mut input = Input::new(bytes);
    // IDs are positions in the flattened table. Kotlin uses the same order
    // to recover the original declarations without returning their values.
    let mut next_id = 0;
    let blocks = input.list(MAX_RULES, 4, |input| {
        input.list(MAX_DECLARATIONS_PER_BLOCK, 8, |input| {
            let property = input.int()?;
            if property < 0 {
                return Err("Invalid CSS stylesheet property ID");
            }
            let important = input.flag()?;
            let id = next_id;
            next_id += 1; // At most MAX_RULES * MAX_DECLARATIONS_PER_BLOCK IDs.
            Ok(DeclarationRef {
                property,
                id,
                important,
            })
        })
    })?;
    let mut remaining = MAX_RULES;
    let mut pools = std::array::from_fn(|_| Vec::new());
    for pool in &mut pools {
        *pool = input.list(remaining, 40, |input| input.rule(blocks.len()))?;
        remaining -= pool.len();
    }
    input.end()?;
    Ok(RuleProgram { blocks, pools })
}

struct CssSession {
    matcher: Session,
    blocks: Vec<Vec<DeclarationRef>>,
}

#[derive(Default)]
struct Sessions {
    last: jlong,
    live: HashMap<jlong, Arc<Mutex<CssSession>>>,
}

impl Sessions {
    fn insert(&mut self, session: CssSession) -> Result<jlong, String> {
        // Match sessions live as long as their Kotlin owner. Handle IDs are
        // never reused even if a session closes during an in-flight match.
        self.last = self
            .last
            .checked_add(1)
            .ok_or("CSS selector session IDs exhausted")?;
        self.live.insert(self.last, Arc::new(Mutex::new(session)));
        Ok(self.last)
    }
}

fn sessions() -> &'static Mutex<Sessions> {
    static SESSIONS: OnceLock<Mutex<Sessions>> = OnceLock::new();
    SESSIONS.get_or_init(Mutex::default)
}

fn read_input(env: &JNIEnv, input: &JByteArray, limit: i32) -> Result<Vec<u8>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if length > limit {
        return Err("CSS selector payload is too large".into());
    }
    env.convert_byte_array(input)
        .map_err(|error| error.to_string())
}

fn valid_inline_length(length: usize) -> bool {
    length <= MAX_INLINE_CANDIDATES * 2 && length.is_multiple_of(2)
}

fn decode_inline(values: &[i32]) -> DecodeResult<Vec<DeclarationRef>> {
    debug_assert!(valid_inline_length(values.len()));
    values
        .chunks_exact(2)
        .enumerate()
        .map(|(index, pair)| {
            let important = match pair[1] {
                0 => false,
                1 => true,
                _ => return Err("Invalid CSS inline importance flag"),
            };
            Ok(DeclarationRef {
                property: pair[0],
                id: -1 - index as i32,
                important,
            })
        })
        .collect()
}

fn read_inline(env: &JNIEnv, input: &JIntArray) -> Result<Vec<DeclarationRef>, String> {
    if input.is_null() {
        return Ok(Vec::new());
    }
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())? as usize;
    if !valid_inline_length(length) {
        return Err("Invalid CSS inline declaration count".into());
    }
    let mut values = vec![0; length];
    env.get_int_array_region(input, 0, &mut values)
        .map_err(|error| error.to_string())?;
    Ok(decode_inline(&values)?)
}

fn open_session(env: &JNIEnv, nodes: Vec<Node>, rules: &JByteArray) -> Result<jlong, String> {
    let RuleProgram { blocks, pools } = decode_rules(&read_input(env, rules, MAX_RULE_BYTES)?)?;
    let session = CssSession {
        matcher: Session::new(nodes, pools),
        blocks,
    };
    sessions()
        .lock()
        .map_err(|_| "CSS selector session registry unavailable")?
        .insert(session)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssMatchNative_open(
    mut env: JNIEnv,
    _receiver: JObject,
    dom: JByteArray,
    rules: JByteArray,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        let nodes = decode_nodes(&read_input(env, &dom, MAX_DOM_BYTES)?)?;
        open_session(env, nodes, &rules)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssMatchNative_openChapter(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    rules: JByteArray,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        let chapter = retained_document(handle)?;
        open_session(env, chapter_nodes(&chapter)?, &rules)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssMatchNative_cascade(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    pool: jint,
    node: jint,
    budget: jint,
    inline: JIntArray,
) -> jintArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if !(0..POOL_COUNT as jint).contains(&pool) || !(0..=MAX_BUDGET).contains(&budget) {
            return Err("Invalid CSS selector pool or work budget".into());
        }
        let inline = read_inline(env, &inline)?;
        let (remaining, mut ids) = if handle == 0 {
            if node != -1 {
                return Err("Invalid inline-only CSS subject".into());
            }
            (budget, cascade_inline(&inline))
        } else {
            let node = usize::try_from(node).map_err(|_| "Invalid CSS selector subject")?;
            let session = sessions()
                .lock()
                .map_err(|_| "CSS selector session registry unavailable")?
                .live
                .get(&handle)
                .cloned()
                .ok_or("Invalid CSS selector session")?;
            let mut session = session
                .lock()
                .map_err(|_| "CSS selector session unavailable")?;
            let CssSession { matcher, blocks } = &mut *session;
            if node >= matcher.node_count() {
                return Err("Invalid CSS selector subject".into());
            }
            matcher.cascade(pool as usize, node, budget, blocks, &inline)
        };
        ids.insert(0, remaining);
        let result = env
            .new_int_array(ids.len() as jint)
            .map_err(|error| error.to_string())?;
        env.set_int_array_region(&result, 0, &ids)
            .map_err(|error| error.to_string())?;
        Ok(JIntArray::into_raw(result))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssMatchNative_close(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        let session = sessions()
            .lock()
            .map_err(|_| "CSS selector session registry unavailable")?
            .live
            .remove(&handle);
        drop(session);
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_ints(output: &mut Vec<u8>, values: &[i32]) {
        output.extend(values.iter().flat_map(|value| value.to_le_bytes()));
    }

    fn write_text(output: &mut Vec<u8>, value: &str) {
        write_ints(output, &[value.len() as i32]);
        output.extend_from_slice(value.as_bytes());
    }

    fn write_node(output: &mut Vec<u8>, tag: &str, links: [i32; 4]) {
        write_text(output, tag);
        write_text(output, "");
        write_ints(output, &[0, 0, 0]); // No classes, attributes or absolute attributes.
        write_ints(output, &links);
    }

    fn write_rule(output: &mut Vec<u8>, block: i32, order: i32) {
        write_ints(output, &[block, 1, order, 1, 0]); // One type selector.
        write_text(output, "p");
        write_ints(output, &[-1, 0, 0, 0]); // No ID, classes, attributes or pseudo-classes.
    }

    #[test]
    fn retained_chapter_uses_element_preorder_and_ordered_jsoup_attributes() {
        let document = crate::chapter_markup::parse(
            "<html><body><P ıd='first' id='later' cLaſs='x y&#160;z x X'><em/>text</P><!--gap--><p/></body></html>",
            false,
        )
        .unwrap();
        let nodes = chapter_nodes(&document).unwrap();
        validate_topology(&nodes).unwrap();
        assert_eq!(
            nodes
                .iter()
                .map(|node| node.tag.as_str())
                .collect::<Vec<_>>(),
            ["#root", "html", "body", "p", "em", "p"]
        );
        assert_eq!(nodes[3].id, "first");
        assert_eq!(nodes[3].classes, ["x", "y\u{00a0}z", "X"]);
        assert_eq!(
            nodes[3].attrs,
            [
                ("ıd".into(), "first".into()),
                ("id".into(), "later".into()),
                ("cLaſs".into(), "x y\u{00a0}z x X".into()),
            ]
        );
        assert_eq!(nodes[3].next, Some(5));
        assert_eq!(nodes[5].previous, Some(3));
        assert_eq!(nodes[3].first_child, Some(4));
        assert_eq!(nodes[4].parent, Some(3));
    }

    #[test]
    fn retained_html_uses_final_tree_order_after_foster_parenting() {
        let document = crate::chapter_markup::parse(
            "<table><b id='moved'>text</b><tr><td>cell</td></tr></table><p>after",
            true,
        )
        .unwrap();
        let nodes = chapter_nodes(&document).unwrap();
        validate_topology(&nodes).unwrap();
        assert_eq!(
            nodes
                .iter()
                .map(|node| node.tag.as_str())
                .collect::<Vec<_>>(),
            ["#root", "html", "head", "body", "b", "table", "tbody", "tr", "td", "p"]
        );
        assert_eq!(nodes[4].id, "moved");
        assert_eq!(nodes[4].next, Some(5));
        assert_eq!(nodes[5].next, Some(9));
    }

    #[test]
    fn decodes_shared_declaration_blocks_and_ordered_winner_ids() {
        let mut dom = Vec::new();
        write_ints(&mut dom, &[2]);
        write_node(&mut dom, "#root", [-1, -1, -1, 1]);
        write_node(&mut dom, "p", [0, -1, -1, -1]);
        let nodes = decode_nodes(&dom).unwrap();

        let mut rules = Vec::new();
        write_ints(&mut rules, &[2, 1, 2, 0, 2, 12, 0, 18, 1]); // Two blocks, three declarations.
        write_ints(&mut rules, &[0, 2]);
        write_rule(&mut rules, 1, 3);
        write_rule(&mut rules, 1, 4);
        write_ints(&mut rules, &[0, 0]);
        let RuleProgram { blocks, pools } = decode_rules(&rules).unwrap();
        let inline = decode_inline(&[12, 0, -2, 0, 18, 0]).unwrap();
        let mut session = Session::new(nodes, pools);
        assert_eq!(
            session.cascade(0, 1, 100, &blocks, &inline),
            (100, vec![-1, -2, -3])
        );
        assert_eq!(
            session.cascade(1, 1, 100, &blocks, &inline),
            (96, vec![-1, -2, 2])
        );
    }

    #[test]
    fn rejects_malformed_lists_block_references_and_dom_links() {
        assert!(decode_rules(&i32::MAX.to_le_bytes()).is_err());
        assert!(decode_rules(&[0; 21]).is_err());
        let mut rules = Vec::new();
        write_ints(&mut rules, &[0, 1]); // One rule, no blocks.
        write_rule(&mut rules, 0, 1);
        write_ints(&mut rules, &[0, 0, 0]);
        assert!(decode_rules(&rules).is_err());

        let mut dom = Vec::new();
        write_ints(&mut dom, &[2]);
        write_node(&mut dom, "#root", [-1, -1, -1, 1]);
        write_node(&mut dom, "p", [0, -1, 2, -1]);
        assert!(decode_nodes(&dom).is_err());
    }

    #[test]
    fn accepts_expanded_inline_block_and_checks_candidate_flags() {
        let raw = [7, 0].repeat(MAX_INLINE_CANDIDATES);
        assert!(valid_inline_length(raw.len()));
        assert!(!valid_inline_length(raw.len() + 2));
        assert!(!valid_inline_length(3));
        assert!(decode_inline(&[7, 2]).is_err());
        assert_eq!(
            cascade_inline(&decode_inline(&raw).unwrap()),
            vec![-(MAX_INLINE_CANDIDATES as i32)]
        );
    }
}
