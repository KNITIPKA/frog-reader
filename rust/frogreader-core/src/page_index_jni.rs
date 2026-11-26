use super::{call_jni, page_index};
use jni::objects::{JByteArray, JIntArray, JObject, JString};
use jni::sys::{jboolean, jint, jintArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::path::Path;
use std::ptr;

fn key_bytes(
    env: &JNIEnv,
    key: &JByteArray,
) -> Result<Option<[u8; page_index::KEY_BYTES]>, String> {
    let length = env
        .get_array_length(key)
        .map_err(|error| error.to_string())?;
    if length as usize != page_index::KEY_BYTES {
        return Ok(None);
    }
    env.convert_byte_array(key)
        .map(|key| key.try_into().ok())
        .map_err(|error| error.to_string())
}

fn java_anchors(env: &JNIEnv, anchors: &[i32]) -> Result<jintArray, String> {
    let array = env
        .new_int_array(anchors.len() as jint)
        .map_err(|error| error.to_string())?;
    env.set_int_array_region(&array, 0, anchors)
        .map_err(|error| error.to_string())?;
    Ok(array.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustPageIndexNative_read(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    key: JByteArray,
    item_count: jint,
) -> jintArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(key) = key_bytes(env, &key)? else {
            return Ok(ptr::null_mut());
        };
        let path: String = env
            .get_string(&path)
            .map_err(|error| error.to_string())?
            .into();
        let Ok(anchors) = page_index::read(Path::new(&path), &key, item_count) else {
            return Ok(ptr::null_mut());
        };
        java_anchors(env, &anchors)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustPageIndexNative_readBytes(
    mut env: JNIEnv,
    _receiver: JObject,
    bytes: JByteArray,
    key: JByteArray,
    item_count: jint,
) -> jintArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let length = env
            .get_array_length(&bytes)
            .map_err(|error| error.to_string())? as usize;
        if item_count <= 0 || !page_index::valid_file_size(length) {
            return Ok(ptr::null_mut());
        }
        let Some(key) = key_bytes(env, &key)? else {
            return Ok(ptr::null_mut());
        };
        let bytes = env
            .convert_byte_array(&bytes)
            .map_err(|error| error.to_string())?;
        let Ok(anchors) = page_index::read_bytes(&bytes, &key, item_count) else {
            return Ok(ptr::null_mut());
        };
        java_anchors(env, &anchors)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustPageIndexNative_write(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    key: JByteArray,
    item_count: jint,
    anchors: JIntArray,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let length = env
            .get_array_length(&anchors)
            .map_err(|error| error.to_string())? as usize;
        if item_count <= 0 || !page_index::valid_int_count(length) {
            return Ok(JNI_FALSE);
        }
        let Some(key) = key_bytes(env, &key)? else {
            return Ok(JNI_FALSE);
        };
        let path: String = env
            .get_string(&path)
            .map_err(|error| error.to_string())?
            .into();
        let mut values = Vec::new();
        if values.try_reserve_exact(length).is_err() {
            return Ok(JNI_FALSE);
        }
        values.resize(length, 0);
        env.get_int_array_region(&anchors, 0, &mut values)
            .map_err(|error| error.to_string())?;
        Ok(
            if page_index::write(Path::new(&path), &key, item_count, &values).is_ok() {
                JNI_TRUE
            } else {
                JNI_FALSE
            },
        )
    })
}
