//! Panic-contained JNI bindings for M4.1 (TXT/MD) and M4.2 (FB2 streaming).

use crate::{call_jni, fb2_stream, text_book};
use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

const MAX_SESSIONS: usize = 64;
const MAX_CHUNK_BYTES: usize = 16 * 1024 * 1024;
const MAX_PREFIX_BYTES: usize = 16 * 1024;
const MAX_COVER_LIMIT_BYTES: usize = 64 * 1024 * 1024;

struct SessionStore<T> {
    next: i64,
    sessions: HashMap<i64, Arc<Mutex<T>>>,
}

impl<T> SessionStore<T> {
    fn new() -> Self {
        Self {
            next: 1,
            sessions: HashMap::new(),
        }
    }

    fn insert(&mut self, value: T) -> i64 {
        if self.sessions.len() >= MAX_SESSIONS || self.next == i64::MAX {
            return 0;
        }
        let handle = self.next;
        self.next += 1;
        self.sessions.insert(handle, Arc::new(Mutex::new(value)));
        handle
    }
}

fn sanitizers() -> &'static Mutex<SessionStore<fb2_stream::Fb2Sanitizer>> {
    static SANITIZERS: OnceLock<Mutex<SessionStore<fb2_stream::Fb2Sanitizer>>> = OnceLock::new();
    SANITIZERS.get_or_init(|| Mutex::new(SessionStore::new()))
}

fn scanners() -> &'static Mutex<SessionStore<fb2_stream::Fb2StructureScanner>> {
    static SCANNERS: OnceLock<Mutex<SessionStore<fb2_stream::Fb2StructureScanner>>> =
        OnceLock::new();
    SCANNERS.get_or_init(|| Mutex::new(SessionStore::new()))
}

fn metadata_parsers() -> &'static Mutex<SessionStore<fb2_stream::Fb2MetadataParser>> {
    static PARSERS: OnceLock<Mutex<SessionStore<fb2_stream::Fb2MetadataParser>>> = OnceLock::new();
    PARSERS.get_or_init(|| Mutex::new(SessionStore::new()))
}

fn pending_body_parses() -> &'static Mutex<SessionStore<fb2_stream::body::PendingFb2Parse>> {
    static PARSES: OnceLock<Mutex<SessionStore<fb2_stream::body::PendingFb2Parse>>> =
        OnceLock::new();
    PARSES.get_or_init(|| Mutex::new(SessionStore::new()))
}

fn read_fb2_limits(
    env: &JNIEnv,
    limits_arr: &JLongArray,
) -> Result<Option<fb2_stream::body::Fb2Limits>, String> {
    if limits_arr.is_null() {
        return Ok(None);
    }
    let len = env
        .get_array_length(limits_arr)
        .map_err(|error| error.to_string())?;
    if len != 8 {
        return Ok(None);
    }
    let mut raw = [0_i64; 8];
    env.get_long_array_region(limits_arr, 0, &mut raw)
        .map_err(|error| error.to_string())?;
    Ok(fb2_stream::body::Fb2Limits::from_slice(&raw))
}

fn read_jstring(env: &mut JNIEnv, input: &JString) -> Result<Option<String>, String> {
    if input.is_null() {
        return Ok(None);
    }
    env.get_string(input)
        .map(|s| Some(String::from(s)))
        .map_err(|error| error.to_string())
}

fn read_bounded_bytes(
    env: &JNIEnv,
    input: &JByteArray,
    max_len: usize,
) -> Result<Option<Vec<u8>>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    let Ok(length) = usize::try_from(length) else {
        return Ok(None);
    };
    if length > max_len {
        return Ok(None);
    }
    env.convert_byte_array(input)
        .map(Some)
        .map_err(|error| error.to_string())
}

fn return_optional_bytes(env: &JNIEnv, payload: Option<Vec<u8>>) -> Result<jbyteArray, String> {
    let Some(bytes) = payload else {
        return Ok(ptr::null_mut());
    };
    env.byte_array_from_slice(&bytes)
        .map(JByteArray::into_raw)
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_decodeText(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::decode_text_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseTxtParagraphs(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::parse_txt_paragraphs_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_headingSlugs(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::heading_slugs_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_renderMarkdownHtml(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::render_markdown_html_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseMarkdownTitle(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::parse_markdown_title_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseMarkdownMarkup(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::parse_markdown_markup_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseMarkdownContent(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, text_book::MAX_TEXT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, text_book::parse_markdown_content_wire(&bytes))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_writeMdFrb(
    mut env: JNIEnv,
    _receiver: JObject,
    source_bytes: JByteArray,
    dest_path: JString,
    source_metadata: JByteArray,
) -> jint {
    call_jni(&mut env, text_book::MD_FRB_STATUS_ERR, |env| {
        let Some(bytes) = read_bounded_bytes(env, &source_bytes, text_book::MAX_TEXT_BYTES)? else {
            return Ok(text_book::MD_FRB_STATUS_ERR);
        };
        let Some(metadata) =
            read_bounded_bytes(env, &source_metadata, crate::frb::MAX_METADATA_BYTES)?
        else {
            return Ok(text_book::MD_FRB_STATUS_ERR);
        };
        let Some(path_str) = read_jstring(env, &dest_path)? else {
            return Ok(text_book::MD_FRB_STATUS_ERR);
        };
        Ok(text_book::write_md_frb(
            &bytes,
            Path::new(&path_str),
            &metadata,
        ))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_writeTxtFrb(
    mut env: JNIEnv,
    _receiver: JObject,
    source_bytes: JByteArray,
    dest_path: JString,
    source_metadata: JByteArray,
    language: JString,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let Some(bytes) = read_bounded_bytes(env, &source_bytes, text_book::MAX_TEXT_BYTES)? else {
            return Ok(JNI_FALSE);
        };
        let Some(metadata) =
            read_bounded_bytes(env, &source_metadata, crate::frb::MAX_METADATA_BYTES)?
        else {
            return Ok(JNI_FALSE);
        };
        if dest_path.is_null() {
            return Ok(JNI_FALSE);
        }
        let path_str: String = env
            .get_string(&dest_path)
            .map_err(|error| error.to_string())?
            .into();
        let lang_opt: Option<String> = if language.is_null() {
            None
        } else {
            Some(
                env.get_string(&language)
                    .map_err(|error| error.to_string())?
                    .into(),
            )
        };
        Ok(
            if text_book::write_txt_frb(
                &bytes,
                Path::new(&path_str),
                &metadata,
                lang_opt.as_deref(),
            )
            .is_ok()
            {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_detectXmlEncoding(
    mut env: JNIEnv,
    _receiver: JObject,
    prefix: JByteArray,
) -> jstring {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &prefix, MAX_PREFIX_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let detected = fb2_stream::detect_xml_encoding(&bytes);
        env.new_string(detected.label)
            .map(jni::objects::JString::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_openSanitizer(
    mut env: JNIEnv,
    _receiver: JObject,
    rewrite_prolog_encoding: jboolean,
) -> jlong {
    call_jni(&mut env, 0, |_| {
        let sanitizer = fb2_stream::Fb2Sanitizer::new(rewrite_prolog_encoding != 0);
        let Ok(mut store) = sanitizers().lock() else {
            return Ok(0);
        };
        Ok(store.insert(sanitizer))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_feedSanitizer(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    chunk: JByteArray,
    length: jint,
    eof: jboolean,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(len) = usize::try_from(length) else {
            return Ok(ptr::null_mut());
        };
        if len > MAX_CHUNK_BYTES {
            return Ok(ptr::null_mut());
        }
        let bytes = if len == 0 {
            Vec::new()
        } else {
            let Some(arr) = read_bounded_bytes(env, &chunk, MAX_CHUNK_BYTES)? else {
                return Ok(ptr::null_mut());
            };
            if len > arr.len() {
                return Ok(ptr::null_mut());
            }
            arr
        };
        let session = {
            let Ok(store) = sanitizers().lock() else {
                return Ok(ptr::null_mut());
            };
            store.sessions.get(&handle).cloned()
        };
        let Some(session) = session else {
            return Ok(ptr::null_mut());
        };
        let mut out = Vec::new();
        {
            let Ok(mut guard) = session.lock() else {
                return Ok(ptr::null_mut());
            };
            if guard.feed(&bytes[..len], eof != 0, &mut out).is_err() {
                return Ok(ptr::null_mut());
            }
        }
        env.byte_array_from_slice(&out)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_closeSanitizer(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut store) = sanitizers().lock() {
            store.sessions.remove(&handle);
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_openStructureScan(
    mut env: JNIEnv,
    _receiver: JObject,
) -> jlong {
    call_jni(&mut env, 0, |_| {
        let scanner = fb2_stream::Fb2StructureScanner::new();
        let Ok(mut store) = scanners().lock() else {
            return Ok(0);
        };
        Ok(store.insert(scanner))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_feedStructureScan(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    chunk: JByteArray,
    length: jint,
    eof: jboolean,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let Ok(len) = usize::try_from(length) else {
            return Ok(JNI_FALSE);
        };
        if len > MAX_CHUNK_BYTES {
            return Ok(JNI_FALSE);
        }
        let bytes = if len == 0 {
            Vec::new()
        } else {
            let Some(arr) = read_bounded_bytes(env, &chunk, MAX_CHUNK_BYTES)? else {
                return Ok(JNI_FALSE);
            };
            if len > arr.len() {
                return Ok(JNI_FALSE);
            }
            arr
        };
        let session = {
            let Ok(store) = scanners().lock() else {
                return Ok(JNI_FALSE);
            };
            store.sessions.get(&handle).cloned()
        };
        let Some(session) = session else {
            return Ok(JNI_FALSE);
        };
        let Ok(mut guard) = session.lock() else {
            return Ok(JNI_FALSE);
        };
        Ok(if guard.feed(&bytes[..len], eof != 0).is_ok() {
            JNI_TRUE
        } else {
            JNI_FALSE
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_finishStructureScan(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let session = {
            let Ok(mut store) = scanners().lock() else {
                return Ok(ptr::null_mut());
            };
            store.sessions.remove(&handle)
        };
        let Some(session) = session else {
            return Ok(ptr::null_mut());
        };
        let scanner = match Arc::try_unwrap(session) {
            Ok(mutex) => mutex.into_inner().ok(),
            Err(_) => None,
        };
        let Some(scanner) = scanner else {
            return Ok(ptr::null_mut());
        };
        let Ok(structure) = scanner.finish() else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, fb2_stream::encode_structure_wire(&structure))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_closeStructureScan(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut store) = scanners().lock() {
            store.sessions.remove(&handle);
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_scanStructure(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_bounded_bytes(env, &input, fb2_stream::MAX_FB2_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Ok(structure) = fb2_stream::scan_fb2_structure(&bytes) else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, fb2_stream::encode_structure_wire(&structure))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_openMetadataParse(
    mut env: JNIEnv,
    _receiver: JObject,
    max_cover_bytes: jlong,
) -> jlong {
    call_jni(&mut env, 0, |_| {
        let Ok(limit) = usize::try_from(max_cover_bytes) else {
            return Ok(0);
        };
        if limit == 0 || limit > MAX_COVER_LIMIT_BYTES {
            return Ok(0);
        }
        let parser = fb2_stream::Fb2MetadataParser::new(limit);
        let Ok(mut store) = metadata_parsers().lock() else {
            return Ok(0);
        };
        Ok(store.insert(parser))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_feedMetadataParse(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    chunk: JByteArray,
    length: jint,
    eof: jboolean,
) -> jint {
    call_jni(&mut env, 0, |env| {
        let Ok(len) = usize::try_from(length) else {
            return Ok(0);
        };
        if len > MAX_CHUNK_BYTES {
            return Ok(0);
        }
        let bytes = if len == 0 {
            Vec::new()
        } else {
            let Some(arr) = read_bounded_bytes(env, &chunk, MAX_CHUNK_BYTES)? else {
                return Ok(0);
            };
            if len > arr.len() {
                return Ok(0);
            }
            arr
        };
        let session = {
            let Ok(store) = metadata_parsers().lock() else {
                return Ok(0);
            };
            store.sessions.get(&handle).cloned()
        };
        let Some(session) = session else {
            return Ok(0);
        };
        let Ok(mut guard) = session.lock() else {
            return Ok(0);
        };
        match guard.feed(&bytes[..len], eof != 0) {
            Ok(true) => Ok(2),
            Ok(false) => Ok(1),
            Err(_) => Ok(0),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_finishMetadataParse(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let session = {
            let Ok(mut store) = metadata_parsers().lock() else {
                return Ok(ptr::null_mut());
            };
            store.sessions.remove(&handle)
        };
        let Some(session) = session else {
            return Ok(ptr::null_mut());
        };
        let parser = match Arc::try_unwrap(session) {
            Ok(mutex) => mutex.into_inner().ok(),
            Err(_) => None,
        };
        let Some(parser) = parser else {
            return Ok(ptr::null_mut());
        };
        let Ok(metadata) = parser.finish() else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, fb2_stream::encode_metadata_wire(&metadata))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_closeMetadataParse(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut store) = metadata_parsers().lock() {
            store.sessions.remove(&handle);
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseFb2Metadata(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    max_cover_bytes: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(limit) = usize::try_from(max_cover_bytes) else {
            return Ok(ptr::null_mut());
        };
        if limit == 0 || limit > MAX_COVER_LIMIT_BYTES {
            return Ok(ptr::null_mut());
        }
        let Some(bytes) = read_bounded_bytes(env, &input, fb2_stream::MAX_FB2_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Ok(metadata) = fb2_stream::parse_fb2_metadata(&bytes, limit) else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, fb2_stream::encode_metadata_wire(&metadata))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_decodeMimeBase64(
    mut env: JNIEnv,
    _class: JClass,
    raw: JByteArray,
    max_decoded_bytes: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if raw.is_null() {
            return Ok(ptr::null_mut());
        }
        let Ok(max_usize) = usize::try_from(max_decoded_bytes) else {
            return Ok(ptr::null_mut());
        };
        let max_encoded = fb2_stream::max_encoded_base64_bytes(max_usize);
        let Some(bytes) = read_bounded_bytes(env, &raw, max_encoded)? else {
            return Ok(ptr::null_mut());
        };
        return_optional_bytes(env, fb2_stream::decode_mime_base64(&bytes, max_usize))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseFb2Content(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    images_dir: JString,
    limits_arr: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(limits) = read_fb2_limits(env, &limits_arr)? else {
            return Ok(ptr::null_mut());
        };
        let Some(images_dir_str) = read_jstring(env, &images_dir)? else {
            return Ok(ptr::null_mut());
        };
        if input.is_null() {
            return Ok(ptr::null_mut());
        }
        let max_bytes = usize::try_from(limits.max_fb2_bytes)
            .unwrap_or(fb2_stream::MAX_FB2_BYTES)
            .min(fb2_stream::MAX_FB2_BYTES);
        let length = env
            .get_array_length(&input)
            .map_err(|error| error.to_string())?;
        let Ok(length_usize) = usize::try_from(length) else {
            return Ok(ptr::null_mut());
        };
        if (length_usize as u64) > limits.max_fb2_bytes {
            let err = fb2_stream::body::Fb2ParseError::Limit {
                kind: fb2_stream::body::LIMIT_KIND_ENTRY_SIZE,
                message: format!("FB2 document exceeds {} bytes", limits.max_fb2_bytes),
            };
            return return_optional_bytes(env, Some(fb2_stream::body::encode_error_wire(&err)));
        }
        if length_usize > max_bytes {
            return Ok(ptr::null_mut());
        }
        let bytes = env
            .convert_byte_array(&input)
            .map_err(|error| error.to_string())?;
        let wire =
            fb2_stream::body::parse_fb2_content_wire(&bytes, Path::new(&images_dir_str), limits);
        return_optional_bytes(env, Some(wire))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseFb2ContentFile(
    mut env: JNIEnv,
    _receiver: JObject,
    source_path: JString,
    images_dir: JString,
    limits_arr: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(limits) = read_fb2_limits(env, &limits_arr)? else {
            return Ok(ptr::null_mut());
        };
        let Some(source_str) = read_jstring(env, &source_path)? else {
            return Ok(ptr::null_mut());
        };
        let Some(images_dir_str) = read_jstring(env, &images_dir)? else {
            return Ok(ptr::null_mut());
        };
        let wire = fb2_stream::body::parse_fb2_content_file_wire(
            Path::new(&source_str),
            Path::new(&images_dir_str),
            limits,
        );
        return_optional_bytes(env, Some(wire))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_writeFb2FrbFile(
    mut env: JNIEnv,
    _receiver: JObject,
    source_path: JString,
    images_dir: JString,
    dest_frb_path: JString,
    source_metadata: JByteArray,
    limits_arr: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(limits) = read_fb2_limits(env, &limits_arr)? else {
            return Ok(ptr::null_mut());
        };
        let Some(source_str) = read_jstring(env, &source_path)? else {
            return Ok(ptr::null_mut());
        };
        let Some(images_dir_str) = read_jstring(env, &images_dir)? else {
            return Ok(ptr::null_mut());
        };
        let Some(dest_str) = read_jstring(env, &dest_frb_path)? else {
            return Ok(ptr::null_mut());
        };
        let Some(metadata) =
            read_bounded_bytes(env, &source_metadata, crate::frb::MAX_METADATA_BYTES)?
        else {
            return Ok(ptr::null_mut());
        };
        let wire = fb2_stream::body::write_fb2_frb_file_wire(
            Path::new(&source_str),
            Path::new(&images_dir_str),
            Path::new(&dest_str),
            &metadata,
            limits,
        );
        return_optional_bytes(env, Some(wire))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_parseFb2TargetedSections(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    section_indices: JIntArray,
    images_dir: JString,
    limits_arr: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(limits) = read_fb2_limits(env, &limits_arr)? else {
            return Ok(ptr::null_mut());
        };
        let Some(images_dir_str) = read_jstring(env, &images_dir)? else {
            return Ok(ptr::null_mut());
        };
        if input.is_null() || section_indices.is_null() {
            return Ok(ptr::null_mut());
        }
        let max_bytes = usize::try_from(limits.max_fb2_bytes)
            .unwrap_or(fb2_stream::MAX_FB2_BYTES)
            .min(fb2_stream::MAX_FB2_BYTES);
        let Some(bytes) = read_bounded_bytes(env, &input, max_bytes)? else {
            let err = fb2_stream::body::Fb2ParseError::Limit {
                kind: fb2_stream::body::LIMIT_KIND_ENTRY_SIZE,
                message: format!("FB2 document exceeds {} bytes", limits.max_fb2_bytes),
            };
            return return_optional_bytes(env, Some(fb2_stream::body::encode_error_wire(&err)));
        };
        let idx_len = env
            .get_array_length(&section_indices)
            .map_err(|error| error.to_string())?;
        let Ok(idx_usize) = usize::try_from(idx_len) else {
            return Ok(ptr::null_mut());
        };
        if idx_usize > 100_000 {
            return Ok(ptr::null_mut());
        }
        let mut indices = vec![0_i32; idx_usize];
        if idx_usize > 0 {
            env.get_int_array_region(&section_indices, 0, &mut indices)
                .map_err(|error| error.to_string())?;
        }
        let wire = fb2_stream::body::parse_fb2_targeted_sections_wire(
            &bytes,
            &indices,
            Path::new(&images_dir_str),
            limits,
        );
        return_optional_bytes(env, Some(wire))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_startFb2ContentWithPreview(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    images_dir: JString,
    limits_arr: JLongArray,
    preview_element_index: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(limits) = read_fb2_limits(env, &limits_arr)? else {
            return Ok(ptr::null_mut());
        };
        let Some(images_dir_str) = read_jstring(env, &images_dir)? else {
            return Ok(ptr::null_mut());
        };
        if input.is_null() {
            return Ok(ptr::null_mut());
        }
        let max_bytes = usize::try_from(limits.max_fb2_bytes)
            .unwrap_or(fb2_stream::MAX_FB2_BYTES)
            .min(fb2_stream::MAX_FB2_BYTES);
        let length = env
            .get_array_length(&input)
            .map_err(|error| error.to_string())?;
        let Ok(length_usize) = usize::try_from(length) else {
            return Ok(ptr::null_mut());
        };
        if (length_usize as u64) > limits.max_fb2_bytes {
            let err = fb2_stream::body::Fb2ParseError::Limit {
                kind: fb2_stream::body::LIMIT_KIND_ENTRY_SIZE,
                message: format!("FB2 document exceeds {} bytes", limits.max_fb2_bytes),
            };
            return return_optional_bytes(env, Some(fb2_stream::body::encode_error_wire(&err)));
        }
        if length_usize > max_bytes {
            return Ok(ptr::null_mut());
        }
        let bytes = env
            .convert_byte_array(&input)
            .map_err(|error| error.to_string())?;
        match fb2_stream::body::start_fb2_preview_parse(
            &bytes,
            PathBuf::from(images_dir_str),
            limits,
            preview_element_index,
        ) {
            fb2_stream::body::PreviewStartOutcome::FinishedWire(wire) => {
                return_optional_bytes(env, Some(wire))
            }
            fb2_stream::body::PreviewStartOutcome::PreviewReady {
                preview_payload,
                pending,
            } => {
                let handle = {
                    let Ok(mut store) = pending_body_parses().lock() else {
                        return Ok(ptr::null_mut());
                    };
                    store.insert(pending)
                };
                if handle == 0 {
                    return Ok(ptr::null_mut());
                }
                let wire = fb2_stream::body::encode_ok_preview_wire(handle, &preview_payload);
                return_optional_bytes(env, Some(wire))
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_finishFb2ContentParse(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let session = {
            let Ok(mut store) = pending_body_parses().lock() else {
                return Ok(ptr::null_mut());
            };
            store.sessions.remove(&handle)
        };
        let Some(session) = session else {
            return Ok(ptr::null_mut());
        };
        let pending = match Arc::try_unwrap(session) {
            Ok(mutex) => mutex.into_inner().ok(),
            Err(_) => None,
        };
        let Some(pending) = pending else {
            return Ok(ptr::null_mut());
        };
        let wire = pending.finish_wire();
        return_optional_bytes(env, Some(wire))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustFb2TxtNative_closeFb2ContentParse(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut store) = pending_body_parses().lock() {
            store.sessions.remove(&handle);
        }
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_store_bounds_capacity_and_never_reuses_handles() {
        let mut store = SessionStore::new();
        for i in 1..=MAX_SESSIONS {
            assert_eq!(store.insert(i), i as i64);
        }
        assert_eq!(store.insert(999), 0);
        store.sessions.remove(&1);
        let next = store.insert(1000);
        assert_eq!(next, MAX_SESSIONS as i64 + 1);
    }
}
