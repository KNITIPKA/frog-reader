//! JNI entry points for WOFF 1.0 and WOFF 2.0 font unpacking.

use super::{call_jni, woff};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use std::ptr;

const MAX_WOFF_INPUT_BYTES: usize = 64 * 1024 * 1024;

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
pub extern "system" fn Java_com_example_frogreader_engine_RustWoffNative_decodeWoff(
    mut env: JNIEnv,
    _receiver: JObject,
    woff_bytes: JByteArray,
    max_decoded_bytes: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(max_bytes) = usize::try_from(max_decoded_bytes) else {
            return Ok(ptr::null_mut());
        };
        if max_bytes == 0 {
            return Ok(ptr::null_mut());
        }
        let Some(input) = read_bounded_bytes(env, &woff_bytes, MAX_WOFF_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(decoded) = woff::decode_woff(&input, max_bytes) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&decoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustWoffNative_decodeWoff2(
    mut env: JNIEnv,
    _receiver: JObject,
    woff2_bytes: JByteArray,
    max_decoded_bytes: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(max_bytes) = usize::try_from(max_decoded_bytes) else {
            return Ok(ptr::null_mut());
        };
        if max_bytes == 0 {
            return Ok(ptr::null_mut());
        }
        let Some(input) = read_bounded_bytes(env, &woff2_bytes, MAX_WOFF_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(decoded) = woff::decode_woff2(&input, max_bytes) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&decoded)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}
