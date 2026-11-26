use crate::{call_jni, css_color, css_declarations, css_syntax};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use std::ptr;

const MAX_INPUT_BYTES: i32 = 32 * 1024 * 1024;
const MAX_COLOR_BYTES: i32 = 4096;

fn read_input(env: &JNIEnv, input: &JByteArray, limit: i32) -> Result<Option<Vec<u8>>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if length > limit {
        return Ok(None);
    }
    env.convert_byte_array(input)
        .map(Some)
        .map_err(|error| error.to_string())
}

fn parse(
    env: &mut JNIEnv,
    input: &JByteArray,
    syntax: impl FnOnce(&str) -> Option<Vec<u8>>,
) -> jbyteArray {
    call_jni(env, ptr::null_mut(), |env| {
        let Some(bytes) = read_input(env, input, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(result) = std::str::from_utf8(&bytes).ok().and_then(syntax) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&result)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustCssSyntaxNative_sheet(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse(&mut env, &input, css_syntax::sheet_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustCssSyntaxNative_inline(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse(&mut env, &input, css_declarations::inline_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustCssSyntaxNative_color(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jlong {
    call_jni(&mut env, -1, |env| {
        let Some(bytes) = read_input(env, &input, MAX_COLOR_BYTES)? else {
            return Ok(-1);
        };
        let color = std::str::from_utf8(&bytes)
            .ok()
            .and_then(css_color::parse_color);
        Ok(color.map_or(-1, jlong::from))
    })
}
