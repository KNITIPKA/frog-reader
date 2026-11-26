//! JNI entry points for native FRB book search and text normalization.

use super::{book_search, call_jni, frb, frb_jni};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring, JNI_TRUE};
use jni::JNIEnv;
use std::path::Path;
use std::ptr;

const MAX_SEARCH_PAYLOAD_BYTES: usize = 64 * 1024 * 1024;

fn get_rust_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| e.to_string())
}

fn read_bounded_bytes(
    env: &JNIEnv,
    array: &JByteArray,
    max_len: usize,
) -> Result<Option<Vec<u8>>, String> {
    let len = env.get_array_length(array).map_err(|e| e.to_string())?;
    let Ok(len_usize) = usize::try_from(len) else {
        return Ok(None);
    };
    if len_usize > max_len {
        return Ok(None);
    }
    env.convert_byte_array(array)
        .map(Some)
        .map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_searchFrbFile(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    metadata: JByteArray,
    query: JString,
    cover_count: jint,
    hide_footnotes: jboolean,
    limit: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(limit_usize) = usize::try_from(limit) else {
            return Ok(ptr::null_mut());
        };
        let Some(meta) = read_bounded_bytes(env, &metadata, frb::MAX_METADATA_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let path_str = get_rust_string(env, &path)?;
        let query_str = get_rust_string(env, &query)?;
        let hide = hide_footnotes == JNI_TRUE;

        let encoded = match book_search::search_frb_file(
            Path::new(&path_str),
            &meta,
            &query_str,
            cover_count,
            hide,
            limit_usize,
        ) {
            Ok(results) => match book_search::encode_search_results(&results) {
                Some(bytes) => bytes,
                None => return Ok(ptr::null_mut()),
            },
            Err(book_search::FrbSearchError::ChapterCorrupt {
                chapter_index,
                reason,
            }) => book_search::encode_search_error(chapter_index, &reason),
            Err(book_search::FrbSearchError::Overflow) => book_search::encode_search_error(
                -1,
                "Search occurrence count exceeds reader coordinates",
            ),
            Err(book_search::FrbSearchError::InvalidContainer) => {
                return Ok(ptr::null_mut());
            }
        };

        env.byte_array_from_slice(&encoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_searchFrbHandle(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    start_chapter: jint,
    chapter_count: jint,
    cover_count: jint,
    query: JString,
    hide_footnotes: jboolean,
    limit: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(start_usize) = usize::try_from(start_chapter) else {
            return Ok(ptr::null_mut());
        };
        let Ok(count_usize) = usize::try_from(chapter_count) else {
            return Ok(ptr::null_mut());
        };
        let Ok(limit_usize) = usize::try_from(limit) else {
            return Ok(ptr::null_mut());
        };
        let query_str = get_rust_string(env, &query)?;
        let hide = hide_footnotes == JNI_TRUE;

        let Some(result) = frb_jni::with_reader(handle, |reader| {
            book_search::search_frb_reader_range(
                reader,
                start_usize,
                count_usize,
                cover_count,
                &query_str,
                hide,
                limit_usize,
            )
        }) else {
            return Ok(ptr::null_mut());
        };

        let encoded = match result {
            Ok(results) => match book_search::encode_search_results(&results) {
                Some(bytes) => bytes,
                None => return Ok(ptr::null_mut()),
            },
            Err(book_search::FrbSearchError::ChapterCorrupt {
                chapter_index,
                reason,
            }) => book_search::encode_search_error(chapter_index, &reason),
            Err(book_search::FrbSearchError::Overflow) => book_search::encode_search_error(
                -1,
                "Search occurrence count exceeds reader coordinates",
            ),
            Err(book_search::FrbSearchError::InvalidContainer) => {
                return Ok(ptr::null_mut());
            }
        };

        env.byte_array_from_slice(&encoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_searchFrbChapter(
    mut env: JNIEnv,
    _receiver: JObject,
    payload: JByteArray,
    chapter_index: jint,
    start_item: jint,
    query: JString,
    hide_footnotes: jboolean,
    limit: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(limit_usize) = usize::try_from(limit) else {
            return Ok(ptr::null_mut());
        };
        let Some(bytes) = read_bounded_bytes(env, &payload, MAX_SEARCH_PAYLOAD_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let query_str = get_rust_string(env, &query)?;
        let hide = hide_footnotes == JNI_TRUE;

        let encoded = match book_search::search_frb_chapter_payload(
            &bytes,
            chapter_index,
            start_item,
            None,
            &query_str,
            hide,
            limit_usize,
        ) {
            Ok(results) => match book_search::encode_search_results(&results) {
                Some(b) => b,
                None => return Ok(ptr::null_mut()),
            },
            Err(book_search::FrbSearchError::Overflow) => book_search::encode_search_error(
                chapter_index,
                "Search occurrence count exceeds reader coordinates",
            ),
            Err(_) => return Ok(ptr::null_mut()),
        };

        env.byte_array_from_slice(&encoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_searchChapterTexts(
    mut env: JNIEnv,
    _receiver: JObject,
    packed: JByteArray,
    query: JString,
    chapter_index: jint,
    chapter_start_item: jint,
    limit: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(limit_usize) = usize::try_from(limit) else {
            return Ok(ptr::null_mut());
        };
        let Some(bytes) = read_bounded_bytes(env, &packed, MAX_SEARCH_PAYLOAD_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let query_str = get_rust_string(env, &query)?;

        let encoded = match book_search::search_packed_chapter_texts(
            &bytes,
            &query_str,
            chapter_index,
            chapter_start_item,
            limit_usize,
        ) {
            Ok(results) => match book_search::encode_search_results(&results) {
                Some(b) => b,
                None => return Ok(ptr::null_mut()),
            },
            Err(book_search::FrbSearchError::Overflow) => book_search::encode_search_error(
                chapter_index,
                "Search occurrence count exceeds reader coordinates",
            ),
            Err(_) => return Ok(ptr::null_mut()),
        };

        env.byte_array_from_slice(&encoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_normalizeText(
    mut env: JNIEnv,
    _receiver: JObject,
    text: JString,
) -> jstring {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let input = get_rust_string(env, &text)?;
        let normalized = book_search::normalize_search_text(&input);
        env.new_string(normalized)
            .map(JString::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_tokenizeQuery(
    mut env: JNIEnv,
    _receiver: JObject,
    query: JString,
) -> jobjectArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let input = get_rust_string(env, &query)?;
        let tokens = book_search::tokenize_search_query(&input);
        let arr = env
            .new_object_array(tokens.len() as jint, "java/lang/String", JObject::null())
            .map_err(|e| e.to_string())?;
        for (i, token) in tokens.iter().enumerate() {
            let jstr = env.new_string(token).map_err(|e| e.to_string())?;
            env.set_object_array_element(&arr, i as jint, jstr)
                .map_err(|e| e.to_string())?;
        }
        Ok(arr.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustSearchNative_toFts5PrefixQuery(
    mut env: JNIEnv,
    _receiver: JObject,
    query: JString,
) -> jstring {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let input = get_rust_string(env, &query)?;
        let Some(fts) = book_search::to_fts5_prefix_query(&input) else {
            return Ok(ptr::null_mut());
        };
        env.new_string(fts)
            .map(JString::into_raw)
            .map_err(|e| e.to_string())
    })
}
