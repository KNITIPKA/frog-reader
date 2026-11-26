use std::collections::{HashMap, HashSet};

pub(crate) struct Node {
    pub(crate) tag: String,
    pub(crate) id: String,
    pub(crate) classes: Vec<String>,
    pub(crate) attrs: Vec<(String, String)>,
    /// Jsoup resolves these lookups against the source document's base URI.
    pub(crate) absolute_attrs: Vec<(String, String)>,
    pub(crate) parent: Option<usize>,
    pub(crate) previous: Option<usize>,
    pub(crate) next: Option<usize>,
    pub(crate) first_child: Option<usize>,
}

#[derive(Clone, Copy)]
pub(crate) enum Combinator {
    Descendant,
    Child,
    NextSibling,
    SubsequentSibling,
}

pub(crate) enum AttrOp {
    Exists,
    Equals,
    Includes,
    Dash,
    Prefix,
    Suffix,
    Substring,
}

pub(crate) struct Attr {
    pub(crate) name: String,
    pub(crate) op: AttrOp,
    pub(crate) value: Option<String>,
    pub(crate) namespace: Option<String>,
}

pub(crate) struct Simple {
    pub(crate) tag: Option<String>,
    pub(crate) id: Option<String>,
    pub(crate) classes: Vec<String>,
    pub(crate) attrs: Vec<Attr>,
    pub(crate) pseudos: Vec<Pseudo>,
}

pub(crate) struct Compound {
    pub(crate) combinator: Combinator,
    pub(crate) simple: Simple,
}

pub(crate) enum Pseudo {
    FirstChild,
    LastChild,
    OnlyChild,
    FirstOfType,
    Nth {
        a: i32,
        b: i32,
        of_type: bool,
        from_end: bool,
    },
    Has {
        id: usize,
        relatives: Vec<Vec<Compound>>,
    },
    Not(Box<Simple>),
}

pub(crate) struct Rule {
    /// Index of the compiled declaration block. A selector list can share one block.
    pub(crate) id: i32,
    pub(crate) specificity: i32,
    pub(crate) order: i32,
    pub(crate) chain: Vec<Compound>,
}

pub(crate) struct DeclarationRef {
    pub(crate) property: i32,
    pub(crate) id: i32,
    pub(crate) important: bool,
}

struct RulePool {
    rules: Vec<Rule>,
    universal: Vec<usize>,
    by_tag: HashMap<String, Vec<usize>>,
    by_class: HashMap<String, Vec<usize>>,
    by_id: HashMap<String, Vec<usize>>,
}

impl RulePool {
    fn new(rules: Vec<Rule>) -> Self {
        let mut pool = Self {
            rules,
            universal: Vec::new(),
            by_tag: HashMap::new(),
            by_class: HashMap::new(),
            by_id: HashMap::new(),
        };
        for (index, rule) in pool.rules.iter().enumerate() {
            let subject = &rule.chain.last().expect("validated selector chain").simple;
            let bucket = if let Some(id) = &subject.id {
                pool.by_id.entry(id.clone()).or_default()
            } else if let Some(class) = subject.classes.first() {
                pool.by_class.entry(class.clone()).or_default()
            } else if let Some(tag) = subject.tag.as_ref().filter(|tag| tag.as_str() != "*") {
                pool.by_tag.entry(tag.clone()).or_default()
            } else {
                &mut pool.universal
            };
            bucket.push(index);
        }
        pool
    }

    fn candidates<'a>(&'a self, node: &'a Node) -> impl Iterator<Item = &'a Rule> {
        // Bucket visitation order matters when the operation budget runs out.
        self.universal
            .iter()
            .chain(self.by_tag.get(&node.tag).into_iter().flatten())
            .chain(
                node.classes
                    .iter()
                    .filter_map(|class| self.by_class.get(class))
                    .flatten(),
            )
            .chain(
                (!node.id.is_empty())
                    .then(|| self.by_id.get(&node.id))
                    .flatten()
                    .into_iter()
                    .flatten(),
            )
            .map(|&index| &self.rules[index])
    }
}

pub(crate) struct Session {
    nodes: Vec<Node>,
    pools: [RulePool; 4],
    context: MatchContext,
}

impl Session {
    pub(crate) fn new(nodes: Vec<Node>, pools: [Vec<Rule>; 4]) -> Self {
        Self {
            nodes,
            pools: pools.map(RulePool::new),
            context: MatchContext::default(),
        }
    }

    pub(crate) fn node_count(&self) -> usize {
        self.nodes.len()
    }

    pub(crate) fn cascade(
        &mut self,
        pool: usize,
        node: usize,
        budget: i32,
        blocks: &[Vec<DeclarationRef>],
        inline: &[DeclarationRef],
    ) -> (i32, Vec<i32>) {
        let (remaining, matched) = self.matching_rules(pool, node, budget);
        let blocks = matched
            .iter()
            .map(|rule| blocks[rule.id as usize].as_slice());
        (remaining, cascade_declarations(blocks, inline))
    }

    #[cfg(test)]
    fn matches(&mut self, pool: usize, node: usize, budget: i32) -> (i32, Vec<i32>) {
        let (remaining, matched) = self.matching_rules(pool, node, budget);
        (remaining, matched.into_iter().map(|rule| rule.id).collect())
    }

    fn matching_rules(&mut self, pool: usize, node: usize, budget: i32) -> (i32, Vec<&Rule>) {
        let Some(pool) = self.pools.get(pool) else {
            return (budget, Vec::new());
        };
        let Some(subject) = self.nodes.get(node) else {
            return (budget, Vec::new());
        };
        let context = &mut self.context;
        context.remaining = budget.max(0) as usize;
        let mut matched = Vec::new();
        for rule in pool.candidates(subject) {
            if context.exhausted() {
                break;
            }
            let chain = &rule.chain;
            let matches = if chain.len() == 1 {
                context.consume(1) && context.simple_matches(&self.nodes, &chain[0].simple, node)
            } else {
                context.chain_matches(&self.nodes, chain, chain.len() - 1, node, &mut Memo::new())
            };
            if matches {
                matched.push(rule);
            }
        }
        matched.sort_by_key(|rule| (rule.specificity, rule.order));
        (context.remaining as i32, matched)
    }
}

pub(crate) fn cascade_inline(inline: &[DeclarationRef]) -> Vec<i32> {
    cascade_declarations(std::iter::empty(), inline)
}

fn cascade_declarations<'a>(
    blocks: impl DoubleEndedIterator<Item = &'a [DeclarationRef]> + Clone,
    inline: &[DeclarationRef],
) -> Vec<i32> {
    let mut seen = HashSet::new();
    let mut winners = Vec::new();
    // Visit stronger declarations first so the first occurrence of each
    // property is its winner. Reversing the result restores the position of
    // the winning declaration in the cascade, including for logical sides.
    for important in [true, false] {
        let sheets = blocks.clone().flat_map(|block| block.iter());
        for declaration in inline.iter().rev().chain(sheets.rev()) {
            if declaration.important == important && seen.insert(declaration.property) {
                winners.push(declaration.id);
            }
        }
    }
    winners.reverse();
    winners
}

#[derive(Default)]
struct MatchContext {
    remaining: usize,
    sibling_positions: HashMap<usize, SiblingPosition>,
    has_results: HashMap<(usize, usize), bool>,
}

#[derive(Clone, Copy)]
struct SiblingPosition {
    child: usize,
    of_type: usize,
    total: usize,
    total_of_type: usize,
}

/// Selector chains have at most 64 compounds. Each bit tracks one `(node, compound)` state.
#[derive(Default)]
struct MatchStates {
    evaluated: u64,
    matched: u64,
}

impl MatchStates {
    fn get(&self, index: usize) -> Option<bool> {
        let bit = 1 << index;
        (self.evaluated & bit != 0).then_some(self.matched & bit != 0)
    }

    fn record(&mut self, index: usize, matched: bool) {
        let bit = 1 << index;
        self.evaluated |= bit;
        if matched {
            self.matched |= bit;
        }
    }
}

type Memo = HashMap<usize, MatchStates>;

impl MatchContext {
    fn exhausted(&self) -> bool {
        self.remaining == 0
    }

    fn consume(&mut self, cost: usize) -> bool {
        if self.remaining < cost {
            self.remaining = 0;
            return false;
        }
        self.remaining -= cost;
        true
    }

    fn sibling_index(
        &mut self,
        nodes: &[Node],
        node: usize,
        of_type: bool,
        from_end: bool,
    ) -> Option<usize> {
        let Some(parent) = nodes[node].parent else {
            return Some(1);
        };
        if !self.sibling_positions.contains_key(&node) {
            let children =
                || std::iter::successors(nodes[parent].first_child, |&child| nodes[child].next);
            let count = children().count();
            if !self.consume(count) {
                return None;
            }
            let mut type_counts = HashMap::new();
            for child in children() {
                *type_counts.entry(nodes[child].tag.as_str()).or_insert(0) += 1;
            }
            let mut type_indices = HashMap::new();
            for (index, child) in children().enumerate() {
                let tag = nodes[child].tag.as_str();
                let type_index = type_indices.entry(tag).or_insert(0);
                *type_index += 1;
                self.sibling_positions.insert(
                    child,
                    SiblingPosition {
                        child: index + 1,
                        of_type: *type_index,
                        total: count,
                        total_of_type: type_counts[tag],
                    },
                );
            }
        }
        let position = self.sibling_positions.get(&node)?;
        let (index, total) = if of_type {
            (position.of_type, position.total_of_type)
        } else {
            (position.child, position.total)
        };
        Some(if from_end { total - index + 1 } else { index })
    }

    fn simple_matches(&mut self, nodes: &[Node], simple: &Simple, node: usize) -> bool {
        if !self.consume(1 + simple.classes.len() + simple.attrs.len() + simple.pseudos.len()) {
            return false;
        }
        let subject = &nodes[node];
        simple
            .tag
            .as_deref()
            .is_none_or(|tag| tag == "*" || subject.tag == tag)
            && simple.id.as_ref().is_none_or(|id| subject.id == *id)
            && simple
                .classes
                .iter()
                .all(|class| subject.classes.contains(class))
            && simple.attrs.iter().all(|attr| attr.matches(nodes, node))
            && simple
                .pseudos
                .iter()
                .all(|pseudo| self.pseudo_matches(nodes, pseudo, node))
    }

    fn pseudo_matches(&mut self, nodes: &[Node], pseudo: &Pseudo, node: usize) -> bool {
        let subject = &nodes[node];
        match pseudo {
            Pseudo::FirstChild => subject.previous.is_none(),
            Pseudo::LastChild => subject.next.is_none(),
            Pseudo::OnlyChild => subject.previous.is_none() && subject.next.is_none(),
            Pseudo::FirstOfType => self.sibling_index(nodes, node, true, false) == Some(1),
            Pseudo::Nth {
                a,
                b,
                of_type,
                from_end,
            } => {
                let Some(index) = self.sibling_index(nodes, node, *of_type, *from_end) else {
                    return false;
                };
                let diff = index as i64 - i64::from(*b);
                let a = i64::from(*a);
                if a == 0 {
                    diff == 0
                } else {
                    diff % a == 0 && diff / a >= 0
                }
            }
            Pseudo::Has { id, relatives } => {
                let key = (*id, node);
                if let Some(&cached) = self.has_results.get(&key) {
                    return cached;
                }
                let matched = relatives
                    .iter()
                    .any(|chain| self.relative_matches(nodes, chain, 0, node, &mut Memo::new()));
                self.has_results.insert(key, matched);
                matched
            }
            Pseudo::Not(inner) => !self.simple_matches(nodes, inner, node),
        }
    }

    fn chain_matches(
        &mut self,
        nodes: &[Node],
        chain: &[Compound],
        index: usize,
        node: usize,
        memo: &mut Memo,
    ) -> bool {
        if !self.consume(1) {
            return false;
        }
        if let Some(value) = memo.get(&node).and_then(|states| states.get(index)) {
            return value;
        }
        let compound = &chain[index];
        let matched = self.simple_matches(nodes, &compound.simple, node)
            && (index == 0 || {
                let parent = |element: usize| {
                    nodes[element]
                        .parent
                        .filter(|&parent| nodes[parent].tag != "#root")
                };
                let previous = |element: usize| nodes[element].previous;
                let mut candidate = match compound.combinator {
                    Combinator::Descendant | Combinator::Child => parent(node),
                    Combinator::NextSibling | Combinator::SubsequentSibling => previous(node),
                };
                let mut found = false;
                while let Some(element) = candidate {
                    if self.chain_matches(nodes, chain, index - 1, element, memo) {
                        found = true;
                        break;
                    }
                    candidate = match compound.combinator {
                        Combinator::Descendant => parent(element),
                        Combinator::SubsequentSibling => previous(element),
                        Combinator::Child | Combinator::NextSibling => None,
                    };
                }
                found
            });
        memo.entry(node).or_default().record(index, matched);
        matched
    }

    /// Walk each relative chain forward from its owning element.
    fn relative_matches(
        &mut self,
        nodes: &[Node],
        chain: &[Compound],
        index: usize,
        anchor: usize,
        memo: &mut Memo,
    ) -> bool {
        if !self.consume(1) {
            return false;
        }
        if let Some(value) = memo.get(&anchor).and_then(|states| states.get(index)) {
            return value;
        }
        let compound = &chain[index];
        let mut candidate = match compound.combinator {
            Combinator::Child | Combinator::Descendant => nodes[anchor].first_child,
            Combinator::NextSibling | Combinator::SubsequentSibling => nodes[anchor].next,
        };
        let mut matched = false;
        while let Some(node) = candidate {
            if !self.consume(1) {
                break;
            }
            if self.simple_matches(nodes, &compound.simple, node)
                && (index + 1 == chain.len()
                    || self.relative_matches(nodes, chain, index + 1, node, memo))
            {
                matched = true;
                break;
            }
            candidate = match compound.combinator {
                Combinator::NextSibling => None,
                Combinator::Child | Combinator::SubsequentSibling => nodes[node].next,
                Combinator::Descendant => self.next_descendant(nodes, node, anchor),
            };
        }
        memo.entry(anchor).or_default().record(index, matched);
        matched
    }

    fn next_descendant(&mut self, nodes: &[Node], node: usize, anchor: usize) -> Option<usize> {
        if let Some(child) = nodes[node].first_child {
            return Some(child);
        }
        let mut cursor = node;
        while cursor != anchor && nodes[cursor].next.is_none() {
            if !self.consume(1) {
                return None;
            }
            cursor = nodes[cursor].parent?;
        }
        if cursor == anchor || self.exhausted() {
            return None;
        }
        nodes[cursor].next
    }
}

impl Attr {
    fn matches(&self, nodes: &[Node], node: usize) -> bool {
        let subject = &nodes[node];
        let actual = if let Some(namespace) = &self.namespace {
            let local_name = self
                .name
                .split_once(':')
                .map_or(self.name.as_str(), |(_, local)| local);
            subject.attrs.iter().find_map(|(key, value)| {
                let (prefix, local) = key.split_once(':')?;
                (local == local_name && attribute_namespace(nodes, node, prefix) == Some(namespace))
                    .then_some(value.as_str())
            })
        } else if self.name.starts_with("abs:") {
            attribute_value(&subject.absolute_attrs, &self.name)
        } else {
            attribute_value(&subject.attrs, &self.name)
        };
        let Some(actual) = actual else {
            return false;
        };
        let Some(expected) = self.value.as_deref() else {
            return true;
        };
        match self.op {
            AttrOp::Exists => true,
            AttrOp::Equals => actual == expected,
            AttrOp::Includes => {
                !expected.is_empty()
                    && actual
                        .split(|ch| matches!(ch, ' ' | '\t'..='\r'))
                        .any(|word| word == expected)
            }
            AttrOp::Dash => {
                actual == expected
                    || actual
                        .strip_prefix(expected)
                        .is_some_and(|rest| rest.starts_with('-'))
            }
            AttrOp::Prefix => !expected.is_empty() && actual.starts_with(expected),
            AttrOp::Suffix => !expected.is_empty() && actual.ends_with(expected),
            AttrOp::Substring => !expected.is_empty() && actual.contains(expected),
        }
    }
}

fn attribute_namespace<'a>(nodes: &'a [Node], node: usize, prefix: &str) -> Option<&'a str> {
    if prefix == "xml" {
        return Some("http://www.w3.org/XML/1998/namespace");
    }
    let name = format!("xmlns:{prefix}");
    let mut owner = Some(node);
    while let Some(index) = owner {
        if let Some(value) =
            attribute_value(&nodes[index].attrs, &name).filter(|value| !value.is_empty())
        {
            return Some(value);
        }
        owner = nodes[index].parent;
    }
    None
}

fn attribute_value<'a>(attrs: &'a [(String, String)], name: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find_map(|(key, value)| attribute_keys_equal(key, name).then_some(value.as_str()))
}

fn attribute_keys_equal(left: &str, right: &str) -> bool {
    if left.eq_ignore_ascii_case(right) {
        return true;
    }
    if left.is_ascii() && right.is_ascii()
        || left.encode_utf16().count() != right.encode_utf16().count()
    {
        return false;
    }
    // Jsoup uses String.equalsIgnoreCase for unqualified names. Compare by
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

#[cfg(test)]
mod tests {
    use super::*;

    fn dom() -> Vec<Node> {
        [
            ("#root", None, None, None, Some(1)),
            ("section", Some(0), None, None, Some(2)),
            ("p", Some(1), None, Some(3), None),
            ("div", Some(1), Some(2), Some(4), Some(5)),
            ("p", Some(1), Some(3), None, None),
            ("span", Some(3), None, None, None),
        ]
        .into_iter()
        .map(|(tag, parent, previous, next, first_child)| Node {
            tag: tag.into(),
            id: String::new(),
            classes: Vec::new(),
            attrs: Vec::new(),
            absolute_attrs: Vec::new(),
            parent,
            previous,
            next,
            first_child,
        })
        .collect()
    }

    fn simple(tag: Option<&str>) -> Simple {
        Simple {
            tag: tag.map(str::to_owned),
            id: None,
            classes: Vec::new(),
            attrs: Vec::new(),
            pseudos: Vec::new(),
        }
    }

    fn compound(combinator: Combinator, tag: &str) -> Compound {
        Compound {
            combinator,
            simple: simple(Some(tag)),
        }
    }

    fn rule(id: i32, specificity: i32, order: i32, simple: Simple) -> Rule {
        Rule {
            id,
            specificity,
            order,
            chain: vec![Compound {
                combinator: Combinator::Descendant,
                simple,
            }],
        }
    }

    fn declaration(property: i32, id: i32, important: bool) -> DeclarationRef {
        DeclarationRef {
            property,
            id,
            important,
        }
    }

    #[test]
    fn cascade_returns_winners_in_the_order_of_their_last_occurrence() {
        let rules = vec![
            rule(2, 2, 4, simple(Some("p"))),
            rule(0, 1, 5, simple(Some("p"))),
            rule(1, 2, 0, simple(Some("p"))),
        ];
        let blocks = vec![
            vec![
                declaration(1, 0, false),
                declaration(0, 1, true),
                declaration(2, 2, false),
                declaration(5, 3, false),
            ],
            vec![
                declaration(5, 4, false),
                declaration(0, 5, true),
                declaration(3, 6, false),
                declaration(6, 7, true),
            ],
            vec![declaration(5, 8, false), declaration(6, 9, true)],
        ];
        let inline = [
            declaration(0, -1, true),
            declaration(1, -2, false),
            declaration(-1, -3, false),
            declaration(6, -4, false),
        ];
        let mut session = Session::new(dom(), [rules, vec![], vec![], vec![]]);
        assert_eq!(
            session.cascade(0, 2, 100, &blocks, &inline),
            (94, vec![2, 6, 8, -2, -3, 9, -1])
        );
        assert_eq!(
            session.cascade(0, 3, 100, &blocks, &inline),
            (100, vec![-2, -3, -4, -1])
        );
        assert_eq!(
            session.cascade(0, 2, 1, &blocks, &inline),
            (0, vec![-2, -3, -4, -1])
        );
    }

    #[test]
    fn inline_cascade_preserves_importance_and_the_last_winning_occurrence() {
        let inline = [
            declaration(1, -1, true),
            declaration(2, -2, false),
            declaration(3, -3, true),
            declaration(1, -4, false),
            declaration(3, -5, true),
        ];
        assert_eq!(cascade_inline(&inline), vec![-2, -1, -5]);
        assert!(cascade_inline(&[]).is_empty());
    }

    #[test]
    fn bucket_visitation_spends_budget_before_specificity_and_source_sort() {
        let mut nodes = dom();
        nodes[2].classes = vec!["z".into(), "a".into()];
        nodes[2].id = "selected".into();
        let rules = vec![
            rule(1, 10, 5, simple(Some("*"))),
            rule(2, 1, 2, simple(Some("p"))),
            rule(
                3,
                4,
                1,
                Simple {
                    classes: vec!["a".into()],
                    ..simple(None)
                },
            ),
            rule(
                4,
                4,
                0,
                Simple {
                    classes: vec!["z".into()],
                    ..simple(None)
                },
            ),
            rule(
                5,
                20,
                4,
                Simple {
                    id: Some("selected".into()),
                    ..simple(None)
                },
            ),
        ];
        let mut session = Session::new(
            nodes,
            [
                rules,
                vec![rule(6, 1, 1, simple(Some("p")))],
                vec![],
                vec![],
            ],
        );
        assert_eq!(session.matches(0, 2, 7), (0, vec![2, 4, 1]));
        assert_eq!(session.matches(0, 2, 12), (0, vec![2, 4, 3, 1, 5]));
        assert_eq!(session.matches(1, 2, 3), (1, vec![6]));
    }

    #[test]
    fn structural_positions_are_reused_within_a_document_across_pools() {
        let nth = |a, b, of_type, from_end| {
            rule(
                b,
                1,
                1,
                Simple {
                    pseudos: vec![Pseudo::Nth {
                        a,
                        b,
                        of_type,
                        from_end,
                    }],
                    ..simple(Some("p"))
                },
            )
        };
        let mut session = Session::new(
            dom(),
            [
                vec![nth(0, 2, true, false)],
                vec![nth(1, i32::MIN, true, true)],
                vec![nth(i32::MIN, i32::MAX, false, false)],
                vec![],
            ],
        );
        assert_eq!(session.matches(0, 4, 20), (14, vec![2]));
        assert_eq!(session.matches(1, 2, 14), (11, vec![i32::MIN]));
        assert_eq!(session.matches(2, 2, 11), (8, vec![]));
    }

    #[test]
    fn relative_chains_stay_anchored_and_cache_each_has_result() {
        use Combinator::{Child, Descendant, NextSibling, SubsequentSibling};

        let has = |id, relatives| Simple {
            pseudos: vec![Pseudo::Has { id, relatives }],
            ..simple(None)
        };
        let rules = vec![
            rule(
                1,
                1,
                1,
                has(
                    0,
                    vec![vec![compound(Child, "div"), compound(NextSibling, "p")]],
                ),
            ),
            rule(
                2,
                2,
                2,
                has(
                    1,
                    vec![vec![
                        compound(NextSibling, "div"),
                        compound(Descendant, "span"),
                    ]],
                ),
            ),
            rule(
                3,
                3,
                3,
                has(
                    2,
                    vec![vec![
                        compound(Descendant, "div"),
                        compound(SubsequentSibling, "span"),
                    ]],
                ),
            ),
            rule(4, 4, 4, has(3, vec![vec![compound(Descendant, "p")]])),
        ];
        let mut session = Session::new(dom(), [rules, vec![], vec![], vec![]]);
        let (remaining, first) = session.matches(0, 1, 100);
        assert_eq!(first, vec![1, 4]);
        assert!(remaining < 88);
        assert_eq!(session.matches(0, 1, 100), (88, first));
        assert_eq!(session.matches(0, 2, 100).1, vec![2]);
        assert!(session.matches(0, 3, 100).1.is_empty());
    }

    #[test]
    fn chain_combinators_do_not_promote_document_into_a_matching_ancestor() {
        use Combinator::{Child, Descendant, NextSibling, SubsequentSibling};

        let rules = [
            vec![
                compound(Descendant, "#root"),
                compound(Child, "section"),
                compound(Descendant, "p"),
            ],
            vec![
                compound(Descendant, "section"),
                compound(Child, "div"),
                compound(NextSibling, "p"),
            ],
            vec![compound(Descendant, "p"), compound(SubsequentSibling, "p")],
            vec![compound(Descendant, "p"), compound(NextSibling, "p")],
            vec![
                compound(Descendant, "section"),
                compound(Descendant, "span"),
            ],
        ]
        .into_iter()
        .enumerate()
        .map(|(index, chain)| Rule {
            id: index as i32,
            specificity: 1,
            order: index as i32,
            chain,
        })
        .collect();
        let mut session = Session::new(dom(), [rules, vec![], vec![], vec![]]);
        assert_eq!(session.matches(0, 4, 100).1, vec![1, 2]);
        assert_eq!(session.matches(0, 5, 100).1, vec![4]);
    }

    #[test]
    fn attribute_resolution_preserves_namespace_and_ordered_lookup() {
        let mut nodes = dom();
        nodes[0].attrs = vec![("xmlns:b".into(), "urn:b".into())];
        nodes[1].attrs = vec![("XMLNS:A".into(), "urn:a".into())];
        nodes[2].attrs = vec![
            ("a:type".into(), "heading\u{000b}large".into()),
            ("b:type".into(), "other".into()),
            ("xml:lang".into(), "en-US".into()),
            ("DİM".into(), "10px".into()),
            ("dim".into(), "20px".into()),
            ("href".into(), "entry.xhtml".into()),
        ];
        nodes[2].absolute_attrs =
            vec![("abs:href".into(), "https://example.org/entry.xhtml".into())];
        let attr = |name: &str, op, value: Option<&str>, namespace: Option<&str>| Attr {
            name: name.into(),
            op,
            value: value.map(str::to_owned),
            namespace: namespace.map(str::to_owned),
        };
        let attrs = [
            attr("epub:type", AttrOp::Includes, Some("large"), Some("urn:a")),
            attr("prefix:type", AttrOp::Equals, Some("other"), Some("urn:b")),
            attr(
                "prefix:lang",
                AttrOp::Dash,
                Some("en"),
                Some("http://www.w3.org/XML/1998/namespace"),
            ),
            attr("dim", AttrOp::Prefix, Some("10"), None),
            attr("abs:href", AttrOp::Suffix, Some("/entry.xhtml"), None),
            attr("href", AttrOp::Exists, None, None),
            attr("href", AttrOp::Substring, Some("try.x"), None),
        ];
        assert!(attrs.iter().all(|attr| attr.matches(&nodes, 2)));
        nodes[1].attrs[0].1 = "urn:shadow".into();
        assert!(!attrs[0].matches(&nodes, 2));
        assert!(!attr("href", AttrOp::Suffix, Some(""), None).matches(&nodes, 2));
        assert!(!attr("prefix:href", AttrOp::Exists, None, Some("urn:a")).matches(&nodes, 2));
    }

    #[test]
    fn tree_pseudos_preserve_detached_and_negation_behavior() {
        let mut nodes = dom();
        let mut detached = dom().pop().unwrap();
        detached.parent = None;
        nodes.push(detached);
        let rules = vec![
            rule(
                1,
                1,
                1,
                Simple {
                    pseudos: vec![Pseudo::OnlyChild],
                    ..simple(None)
                },
            ),
            rule(
                2,
                1,
                2,
                Simple {
                    pseudos: vec![Pseudo::FirstChild],
                    ..simple(None)
                },
            ),
            rule(
                3,
                1,
                3,
                Simple {
                    pseudos: vec![Pseudo::LastChild],
                    ..simple(None)
                },
            ),
            rule(
                4,
                1,
                4,
                Simple {
                    pseudos: vec![Pseudo::FirstOfType],
                    ..simple(None)
                },
            ),
            rule(
                5,
                1,
                5,
                Simple {
                    pseudos: vec![Pseudo::Not(Box::new(simple(Some("p"))))],
                    ..simple(None)
                },
            ),
        ];
        let mut session = Session::new(nodes, [rules, vec![], vec![], vec![]]);
        assert_eq!(session.matches(0, 2, 100).1, vec![2, 4]);
        assert_eq!(session.matches(0, 4, 100).1, vec![3]);
        assert_eq!(session.matches(0, 6, 100).1, vec![1, 2, 3, 4, 5]);
    }
}
