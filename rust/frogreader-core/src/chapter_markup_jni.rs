use crate::{
    call_jni, chapter_charset,
    chapter_markup::{self, Document, Kind},
};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::{
    borrow::Cow,
    collections::HashMap,
    ptr,
    sync::{Arc, Mutex, OnceLock},
};

const MAGIC: i32 = 0x324b_524d; // MRK2
const MAX_SOURCE_BYTES: usize = 64 * 1024 * 1024;
// MOBI has already been decoded by its container. Its UTF-8 can take three
// bytes per character even when the original chapter was single-byte text.
const MAX_UTF8_BYTES: usize = 96 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 256 * 1024 * 1024;
const MAX_RETAINED_CHAPTERS: usize = 8;

#[derive(Default)]
struct Chapters {
    last: jlong,
    live: HashMap<jlong, Arc<Document>>,
}

impl Chapters {
    fn insert(&mut self, document: Document) -> Result<jlong, &'static str> {
        if self.live.len() >= MAX_RETAINED_CHAPTERS {
            return Err("Too many retained chapter documents");
        }
        let handle = self
            .last
            .checked_add(1)
            .ok_or("Chapter handles exhausted")?;
        self.live.insert(handle, Arc::new(document));
        self.last = handle;
        Ok(handle)
    }
}

fn chapters() -> &'static Mutex<Chapters> {
    static CHAPTERS: OnceLock<Mutex<Chapters>> = OnceLock::new();
    CHAPTERS.get_or_init(Mutex::default)
}

/// Capture an immutable chapter without holding the registry lock while it is used.
pub(crate) fn retained_document(handle: jlong) -> Result<Arc<Document>, &'static str> {
    chapters()
        .lock()
        .map_err(|_| "Chapter document registry unavailable")?
        .live
        .get(&handle)
        .cloned()
        .ok_or("Invalid retained chapter handle")
}

struct Output(Vec<u8>);

impl Output {
    fn append(&mut self, bytes: &[u8]) -> Option<()> {
        if bytes.len() > MAX_OUTPUT_BYTES - self.0.len() {
            return None;
        }
        self.0.try_reserve(bytes.len()).ok()?;
        self.0.extend_from_slice(bytes);
        Some(())
    }

    fn int(&mut self, value: i32) -> Option<()> {
        self.append(&value.to_le_bytes())
    }

    fn count(&mut self, value: usize) -> Option<()> {
        self.int(i32::try_from(value).ok()?)
    }

    fn string(&mut self, value: &str) -> Option<()> {
        self.count(value.len())?;
        self.append(value.as_bytes())
    }
}

pub(crate) fn chapter_bytes(document: &Document) -> Option<Vec<u8>> {
    let mut output = Output(Vec::new());
    output.int(MAGIC)?;
    output.int(i32::from(document.html))?;
    output.int(document.preindex.doc_flags as i32)?;
    let summary_offset = output.0.len();
    output.int(-1)?; // body_node
    output.int(-1)?; // book_node
    output.int(-1)?; // head_title_node
    output.int(-1)?; // viewport_meta_node
    let count_offset = output.0.len();
    output.int(0)?;

    let root = document.nodes.first()?;
    let mut pending = Vec::new();
    if let Some(child) = root.first_child {
        pending.push((child, -1));
    }

    let mut body_wire = -1_i32;
    let mut book_wire = -1_i32;
    let mut head_title_wire = -1_i32;
    let mut viewport_meta_wire = -1_i32;
    let mut stylesheet_wires = Vec::new();

    let mut count = 0_i32;
    // Preorder gives the receiver each parent before its children. Sibling
    // links let us emit the tree without a recursive walk over publication input.
    while let Some((node_index, parent)) = pending.pop() {
        let node = document.nodes.get(node_index)?;
        let node_meta = document.preindex.nodes.get(node_index)?;
        let index = count;
        count = count.checked_add(1)?;
        if usize::try_from(count).ok()? >= document.nodes.len() {
            return None;
        }
        if document.preindex.body_node == Some(node_index) {
            body_wire = index;
        }
        if document.preindex.book_node == Some(node_index) {
            book_wire = index;
        }
        if document.preindex.head_title_node == Some(node_index) {
            head_title_wire = index;
        }
        if document.preindex.viewport_meta_node == Some(node_index) {
            viewport_meta_wire = index;
        }
        if node_meta.is_stylesheet {
            stylesheet_wires.push(index);
        }

        output.int(parent)?;
        match &node.kind {
            Kind::Document => return None,
            Kind::Element {
                name,
                namespace,
                self_closing,
                attrs,
            } => {
                output.int(1)?;
                output.string(name)?;
                output.string(namespace)?;
                output.int(i32::from(*self_closing))?;
                output.int(node_meta.tag_flags as i32)?;
                output.int(node_meta.attr_flags as i32)?;
                output.int(node_meta.subtree_flags as i32)?;
                output.count(attrs.len())?;
                for (key, value) in attrs {
                    output.string(key)?;
                    output.string(value)?;
                }
            }
            Kind::Text(value) => {
                output.int(2)?;
                output.int(node_meta.text_flags as i32)?;
                output.string(value)?;
            }
            Kind::Data(value) => write_text(&mut output, 3, value)?,
            Kind::Cdata(value) => write_text(&mut output, 4, value)?,
            Kind::Comment(value) => write_text(&mut output, 5, value)?,
            Kind::Doctype {
                name,
                public_id,
                system_id,
            } => {
                output.int(6)?;
                output.string(name)?;
                output.string(public_id)?;
                output.string(system_id)?;
            }
            Kind::Pi { target, data } => {
                output.int(7)?;
                output.string(target)?;
                output.string(data)?;
            }
        }
        if let Some(sibling) = node.next_sibling {
            pending.push((sibling, parent));
        }
        if let Some(child) = node.first_child {
            pending.push((child, index));
        }
    }
    output.count(stylesheet_wires.len())?;
    for index in stylesheet_wires {
        output.int(index)?;
    }

    output.0[summary_offset..summary_offset + 4].copy_from_slice(&body_wire.to_le_bytes());
    output.0[summary_offset + 4..summary_offset + 8].copy_from_slice(&book_wire.to_le_bytes());
    output.0[summary_offset + 8..summary_offset + 12]
        .copy_from_slice(&head_title_wire.to_le_bytes());
    output.0[summary_offset + 12..summary_offset + 16]
        .copy_from_slice(&viewport_meta_wire.to_le_bytes());
    output.0[count_offset..count_offset + 4].copy_from_slice(&count.to_le_bytes());
    Some(output.0)
}

fn write_text(output: &mut Output, kind: i32, value: &str) -> Option<()> {
    output.int(kind)?;
    output.string(value)
}

fn parse_bytes(bytes: &[u8], html_only: bool) -> Option<Document> {
    let limit = if html_only {
        MAX_UTF8_BYTES
    } else {
        MAX_SOURCE_BYTES
    };
    if bytes.len() > limit {
        return None;
    }
    let text = if html_only {
        std::str::from_utf8(bytes).ok().map(Cow::Borrowed)
    } else {
        chapter_charset::decode(bytes)
    };
    text.and_then(|text| chapter_markup::parse(&text, html_only))
}

fn parse_document(
    env: &JNIEnv,
    input: &JByteArray,
    html_only: bool,
) -> Result<Option<Document>, String> {
    let limit = if html_only {
        MAX_UTF8_BYTES
    } else {
        MAX_SOURCE_BYTES
    };
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if length < 0 || length as usize > limit {
        return Ok(None);
    }
    let bytes = env
        .convert_byte_array(input)
        .map_err(|error| error.to_string())?;
    Ok(parse_bytes(&bytes, html_only))
}

fn parse_zip_document(
    zip_handle: jlong,
    entry_index: jint,
    html_only: bool,
) -> Result<Option<Document>, String> {
    let Ok(index) = usize::try_from(entry_index) else {
        return Ok(None);
    };
    let Some(bytes) = crate::epub_zip::read_entry_bytes(zip_handle, index) else {
        return Ok(None);
    };
    Ok(parse_bytes(&bytes, html_only))
}

fn snapshot(env: &JNIEnv, document: &Document) -> Result<jbyteArray, String> {
    let Some(bytes) = chapter_bytes(document) else {
        return Ok(ptr::null_mut());
    };
    env.byte_array_from_slice(&bytes)
        .map(JByteArray::into_raw)
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_parse(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    html_only: jboolean,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(document) = parse_document(env, &input, html_only != 0)? else {
            return Ok(ptr::null_mut());
        };
        snapshot(env, &document)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_open(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    html_only: jboolean,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        let Some(document) = parse_document(env, &input, html_only != 0)? else {
            return Ok(0);
        };
        Ok(chapters()
            .lock()
            .map_err(|_| "Chapter document registry unavailable")?
            .insert(document)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_parseFromZip(
    mut env: JNIEnv,
    _receiver: JObject,
    zip_handle: jlong,
    entry_index: jint,
    html_only: jboolean,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(document) = parse_zip_document(zip_handle, entry_index, html_only != 0)? else {
            return Ok(ptr::null_mut());
        };
        snapshot(env, &document)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_openFromZip(
    mut env: JNIEnv,
    _receiver: JObject,
    zip_handle: jlong,
    entry_index: jint,
    html_only: jboolean,
) -> jlong {
    call_jni(&mut env, 0, |_| {
        let Some(document) = parse_zip_document(zip_handle, entry_index, html_only != 0)? else {
            return Ok(0);
        };
        Ok(chapters()
            .lock()
            .map_err(|_| "Chapter document registry unavailable")?
            .insert(document)?)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_snapshot(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let document = retained_document(handle)?;
        snapshot(env, &document)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustChapterMarkupNative_close(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        let document = chapters()
            .lock()
            .map_err(|_| "Chapter document registry unavailable")?
            .live
            .remove(&handle);
        drop(document);
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retained_handles_are_bounded_and_not_reused() {
        fn document() -> Document {
            chapter_markup::parse("<html><body><p>chapter</p></body></html>", false).unwrap()
        }
        let mut chapters = Chapters::default();
        for ordinal in 1..=MAX_RETAINED_CHAPTERS {
            assert_eq!(chapters.insert(document()), Ok(ordinal as jlong));
        }
        assert!(chapters.insert(document()).is_err());
        let in_flight = chapters.live.get(&1).unwrap().clone();
        assert!(chapters.live.remove(&1).is_some());
        assert!(chapters.live.remove(&1).is_none());
        assert_eq!(chapter_bytes(&in_flight), chapter_bytes(&document()));
        assert_eq!(
            chapters.insert(document()),
            Ok(MAX_RETAINED_CHAPTERS as jlong + 1)
        );
        assert!(!chapters.live.contains_key(&1));
        chapters.live.remove(&2);
        chapters.last = jlong::MAX;
        assert!(chapters.insert(document()).is_err());
    }

    #[test]
    fn parse_zip_document_rejects_negative_index_and_unknown_handle() {
        assert!(parse_zip_document(0, 0, false).unwrap().is_none());
        assert!(parse_zip_document(1, -1, false).unwrap().is_none());
        assert!(parse_zip_document(i64::MAX, 0, true).unwrap().is_none());
    }
}
