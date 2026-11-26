use crate::{
    call_jni,
    css_match::{AttrOp, Combinator, Compound, Pseudo, Simple},
    css_selectors::{compile, CompiledSelector},
};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use std::collections::{BTreeSet, HashMap};
use std::ptr;

const MAX_INPUT_BYTES: i32 = 32 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 128 * 1024 * 1024;
const MAX_NAMESPACES: usize = 262_144;
const MAX_SELECTOR_COUNT: usize = 256;

type WireResult<T> = Result<T, &'static str>;

fn take_int(bytes: &mut &[u8]) -> WireResult<i32> {
    let (head, tail) = bytes
        .split_first_chunk()
        .ok_or("Truncated CSS namespace length")?;
    *bytes = tail;
    Ok(i32::from_le_bytes(*head))
}

fn take_text<'a>(bytes: &mut &'a [u8]) -> WireResult<&'a str> {
    let length = usize::try_from(take_int(bytes)?).map_err(|_| "Invalid CSS namespace length")?;
    let (head, tail) = bytes
        .split_at_checked(length)
        .ok_or("Truncated CSS namespace text")?;
    *bytes = tail;
    std::str::from_utf8(head).map_err(|_| "Invalid UTF-8 in CSS namespace")
}

fn decode_namespaces(mut bytes: &[u8]) -> WireResult<HashMap<String, String>> {
    let count =
        usize::try_from(take_int(&mut bytes)?).map_err(|_| "Invalid CSS namespace count")?;
    if count > MAX_NAMESPACES || count > bytes.len() / (2 * size_of::<i32>()) {
        return Err("Invalid CSS namespace count");
    }
    let mut namespaces = HashMap::new();
    namespaces
        .try_reserve(count)
        .map_err(|_| "Could not allocate CSS namespaces")?;
    for _ in 0..count {
        let prefix = take_text(&mut bytes)?;
        let uri = take_text(&mut bytes)?;
        namespaces.insert(prefix.to_owned(), uri.to_owned());
    }
    if !bytes.is_empty() {
        return Err("Trailing CSS namespace data");
    }
    Ok(namespaces)
}

fn collect_absolute_keys<'a>(simple: &'a Simple, keys: &mut BTreeSet<&'a str>) {
    for attr in &simple.attrs {
        if attr.namespace.is_none() && attr.name.starts_with("abs:") {
            keys.insert(&attr.name);
        }
    }
    for pseudo in &simple.pseudos {
        match pseudo {
            Pseudo::Not(inner) => collect_absolute_keys(inner, keys),
            Pseudo::Has { relatives, .. } => {
                for compound in relatives.iter().flatten() {
                    collect_absolute_keys(&compound.simple, keys);
                }
            }
            _ => {}
        }
    }
}

#[derive(Default)]
struct Output(Vec<u8>);

impl Output {
    fn write(&mut self, bytes: &[u8]) -> WireResult<()> {
        if bytes.len() > MAX_OUTPUT_BYTES - self.0.len() {
            return Err("Compiled CSS selector data exceeds its size limit");
        }
        self.0
            .try_reserve(bytes.len())
            .map_err(|_| "Could not allocate compiled CSS selector data")?;
        self.0.extend_from_slice(bytes);
        Ok(())
    }

    fn int(&mut self, number: i32) -> WireResult<()> {
        self.write(&number.to_le_bytes())
    }

    fn length(&mut self, length: usize) -> WireResult<()> {
        self.int(i32::try_from(length).map_err(|_| "Invalid CSS selector length")?)
    }

    fn text(&mut self, value: Option<&str>) -> WireResult<()> {
        let Some(value) = value else {
            return self.int(-1);
        };
        self.length(value.len())?;
        self.write(value.as_bytes())
    }

    fn bucket(&mut self, subject: &Simple) -> WireResult<()> {
        let prefixed = subject
            .id
            .as_deref()
            .map(|id| (b'#', id))
            .or_else(|| subject.classes.first().map(|class| (b'.', class.as_str())));
        if let Some((prefix, name)) = prefixed {
            self.length(1 + name.len())?;
            self.write(&[prefix])?;
            self.write(name.as_bytes())
        } else {
            self.text(subject.tag.as_deref().filter(|tag| *tag != "*"))
        }
    }

    fn selector(&mut self, selector: &CompiledSelector) -> WireResult<()> {
        let subject = &selector
            .chain
            .last()
            .ok_or("Compiled CSS selector is empty")?
            .simple;
        self.int(selector.pool)?;
        self.int(selector.specificity)?;
        self.bucket(subject)?;
        let mut absolute_keys = BTreeSet::new();
        for compound in &selector.chain {
            collect_absolute_keys(&compound.simple, &mut absolute_keys);
        }
        self.length(absolute_keys.len())?;
        for key in absolute_keys {
            self.text(Some(key))?;
        }
        let length_position = self.0.len();
        self.int(0)?;
        let chain_start = self.0.len();
        self.chain(&selector.chain)?;
        let chain_bytes = (self.0.len() - chain_start) as i32;
        self.0[length_position..chain_start].copy_from_slice(&chain_bytes.to_le_bytes());
        Ok(())
    }

    // These fields are read directly by the matcher program decoder.
    fn chain(&mut self, chain: &[Compound]) -> WireResult<()> {
        self.length(chain.len())?;
        for compound in chain {
            self.int(match compound.combinator {
                Combinator::Descendant => 0,
                Combinator::Child => 1,
                Combinator::NextSibling => 2,
                Combinator::SubsequentSibling => 3,
            })?;
            self.simple(&compound.simple)?;
        }
        Ok(())
    }

    fn simple(&mut self, simple: &Simple) -> WireResult<()> {
        self.text(simple.tag.as_deref())?;
        self.text(simple.id.as_deref())?;
        self.length(simple.classes.len())?;
        for class in &simple.classes {
            self.text(Some(class))?;
        }
        self.length(simple.attrs.len())?;
        for attr in &simple.attrs {
            self.text(Some(&attr.name))?;
            self.int(match attr.op {
                AttrOp::Exists => 0,
                AttrOp::Equals => 1,
                AttrOp::Includes => 2,
                AttrOp::Dash => 3,
                AttrOp::Prefix => 4,
                AttrOp::Suffix => 5,
                AttrOp::Substring => 6,
            })?;
            self.text(attr.value.as_deref())?;
            self.text(attr.namespace.as_deref())?;
        }
        self.length(simple.pseudos.len())?;
        for pseudo in &simple.pseudos {
            self.pseudo(pseudo)?;
        }
        Ok(())
    }

    fn pseudo(&mut self, pseudo: &Pseudo) -> WireResult<()> {
        match pseudo {
            Pseudo::FirstChild => self.int(0),
            Pseudo::LastChild => self.int(1),
            Pseudo::OnlyChild => self.int(2),
            Pseudo::FirstOfType => self.int(3),
            Pseudo::Nth {
                a,
                b,
                of_type,
                from_end,
            } => {
                self.int(4)?;
                self.int(*a)?;
                self.int(*b)?;
                self.int(i32::from(*of_type))?;
                self.int(i32::from(*from_end))
            }
            Pseudo::Has { relatives, .. } => {
                self.int(5)?;
                self.length(relatives.len())?;
                for relative in relatives {
                    self.chain(relative)?;
                }
                Ok(())
            }
            Pseudo::Not(inner) => {
                self.int(6)?;
                self.simple(inner)
            }
        }
    }
}

fn compiled_bytes(selectors: &[CompiledSelector]) -> WireResult<Vec<u8>> {
    let mut output = Output::default();
    output.length(selectors.len())?;
    for selector in selectors {
        output.selector(selector)?;
    }
    Ok(output.0)
}

fn read_input(env: &JNIEnv, input: &JByteArray) -> Result<Vec<u8>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if length > MAX_INPUT_BYTES {
        return Err("CSS selector input exceeds its size limit".into());
    }
    env.convert_byte_array(input)
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssSelectorsNative_compile(
    mut env: JNIEnv,
    _receiver: JObject,
    selector: JByteArray,
    namespaces: JByteArray,
    limit: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let limit = usize::try_from(limit)
            .map_err(|_| "Invalid CSS selector limit")?
            .min(MAX_SELECTOR_COUNT);
        let selector = read_input(env, &selector)?;
        let selector =
            std::str::from_utf8(&selector).map_err(|_| "Invalid UTF-8 in CSS selector")?;
        let namespaces = decode_namespaces(&read_input(env, &namespaces)?)?;
        let compiled = compile(selector, &namespaces, limit);
        env.byte_array_from_slice(&compiled_bytes(&compiled)?)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compiled_row_precedes_the_matcher_chain_with_bucket_metadata() {
        let selectors = compile("p:first-child::before", &HashMap::new(), 1);
        let wire = compiled_bytes(&selectors).unwrap();
        let mut bytes = wire.as_slice();
        assert_eq!(take_int(&mut bytes), Ok(1));
        assert_eq!(take_int(&mut bytes), Ok(2));
        assert_eq!(take_int(&mut bytes), Ok(1002));
        assert_eq!(take_text(&mut bytes), Ok("p"));
        assert_eq!(take_int(&mut bytes), Ok(0));
        assert_eq!(take_int(&mut bytes), Ok(33));
        assert_eq!(take_int(&mut bytes), Ok(1)); // Compounds
        assert_eq!(take_int(&mut bytes), Ok(0)); // Descendant
        assert_eq!(take_text(&mut bytes), Ok("p"));
        assert_eq!(take_int(&mut bytes), Ok(-1)); // No ID
        assert_eq!(take_int(&mut bytes), Ok(0)); // Classes
        assert_eq!(take_int(&mut bytes), Ok(0)); // Attributes
        assert_eq!(take_int(&mut bytes), Ok(1)); // Pseudos
        assert_eq!(take_int(&mut bytes), Ok(0)); // First child
        assert!(bytes.is_empty());
    }

    #[test]
    fn snapshot_keys_cover_nested_selectors_without_duplicates() {
        let namespaces = HashMap::from([("epub".into(), "urn:epub".into())]);
        let selectors = compile(
            "div:not([abs:href]):has(> a[abs:href], span[abs:data-link][epub|type])",
            &namespaces,
            1,
        );
        let wire = compiled_bytes(&selectors).unwrap();
        let mut bytes = wire.as_slice();
        assert_eq!(take_int(&mut bytes), Ok(1));
        assert_eq!(take_int(&mut bytes), Ok(0));
        take_int(&mut bytes).unwrap(); // Specificity
        assert_eq!(take_text(&mut bytes), Ok("div"));
        assert_eq!(take_int(&mut bytes), Ok(2));
        assert_eq!(take_text(&mut bytes), Ok("abs:data-link"));
        assert_eq!(take_text(&mut bytes), Ok("abs:href"));
    }

    #[test]
    fn truncated_and_invalid_utf8_namespaces_are_rejected() {
        let mut wire = Output::default();
        wire.int(1).unwrap();
        wire.text(Some("epub")).unwrap();
        wire.int(1).unwrap();
        assert!(decode_namespaces(&wire.0).is_err());
        wire.0.push(0xff);
        assert!(decode_namespaces(&wire.0).is_err());
        *wire.0.last_mut().unwrap() = b'x';
        assert_eq!(decode_namespaces(&wire.0).unwrap()["epub"], "x");
    }
}
