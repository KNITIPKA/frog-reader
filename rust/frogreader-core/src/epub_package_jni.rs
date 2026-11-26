use crate::{call_jni, epub_nav, epub_ncx, epub_package, frb};
use jni::objects::{JByteArray, JLongArray, JObject, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use std::path::Path;
use std::ptr;

const MAX_XML_BYTES: i32 = 16 * 1024 * 1024;

fn read_xml(env: &JNIEnv, input: &JByteArray) -> Result<Option<Vec<u8>>, String> {
    let size = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if size > MAX_XML_BYTES {
        return Ok(None);
    }
    env.convert_byte_array(input)
        .map(Some)
        .map_err(|error| error.to_string())
}

fn parse_bytes(
    env: &mut JNIEnv,
    input: &JByteArray,
    parse: fn(&[u8]) -> Option<Vec<u8>>,
) -> jbyteArray {
    call_jni(env, ptr::null_mut(), |env| {
        let Some(bytes) = read_xml(env, input)?.as_deref().and_then(parse) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubPackageNative_rootfile(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jstring {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(path) = read_xml(env, &input)?
            .as_deref()
            .and_then(epub_package::container_rootfile)
        else {
            return Ok(ptr::null_mut());
        };
        env.new_string(path)
            .map(JString::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubPackageNative_packageData(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse_bytes(&mut env, &input, epub_package::package_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubPackageNative_ncxData(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse_bytes(&mut env, &input, epub_ncx::ncx_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubPackageNative_navData(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse_bytes(&mut env, &input, epub_nav::nav_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubPackageNative_parseEncryption(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    parse_bytes(&mut env, &input, epub_package::encryption_bytes)
}

fn read_epub_limits(
    env: &JNIEnv,
    limits_arr: &JLongArray,
) -> Result<Option<epub_package::epub_frb::EpubLimits>, String> {
    if limits_arr.is_null() {
        return Ok(None);
    }
    let len = env
        .get_array_length(limits_arr)
        .map_err(|error| error.to_string())?;
    if len as usize != epub_package::epub_frb::LIMITS_LEN {
        return Ok(None);
    }
    let mut raw = [0_i64; epub_package::epub_frb::LIMITS_LEN];
    env.get_long_array_region(limits_arr, 0, &mut raw)
        .map_err(|error| error.to_string())?;
    Ok(epub_package::epub_frb::EpubLimits::from_slice(&raw))
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustEpubPackage_parseEpubContentNative(
    mut env: JNIEnv,
    _receiver: JObject,
    epub_path: JString,
    images_dir: JString,
    limits: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if epub_path.is_null() || images_dir.is_null() {
            return Ok(ptr::null_mut());
        }
        let Some(parsed_limits) = read_epub_limits(env, &limits)? else {
            return Ok(ptr::null_mut());
        };
        let epub_str: String = env
            .get_string(&epub_path)
            .map_err(|error| error.to_string())?
            .into();
        let images_str: String = env
            .get_string(&images_dir)
            .map_err(|error| error.to_string())?
            .into();
        let wire = epub_package::epub_frb::parse_epub_content_wire(
            Path::new(&epub_str),
            Path::new(&images_str),
            &parsed_limits,
        );
        env.byte_array_from_slice(&wire)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustEpubPackage_writeEpubFrbNative(
    mut env: JNIEnv,
    _receiver: JObject,
    epub_path: JString,
    images_dir: JString,
    dest_frb_path: JString,
    source_metadata: JByteArray,
    limits: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if epub_path.is_null()
            || images_dir.is_null()
            || dest_frb_path.is_null()
            || source_metadata.is_null()
        {
            return Ok(ptr::null_mut());
        }
        let Some(parsed_limits) = read_epub_limits(env, &limits)? else {
            return Ok(ptr::null_mut());
        };
        let meta_len = env
            .get_array_length(&source_metadata)
            .map_err(|error| error.to_string())?;
        let Ok(meta_len) = usize::try_from(meta_len) else {
            return Ok(ptr::null_mut());
        };
        if meta_len > frb::MAX_METADATA_BYTES {
            return Ok(ptr::null_mut());
        }
        let metadata = env
            .convert_byte_array(&source_metadata)
            .map_err(|error| error.to_string())?;
        let epub_str: String = env
            .get_string(&epub_path)
            .map_err(|error| error.to_string())?
            .into();
        let images_str: String = env
            .get_string(&images_dir)
            .map_err(|error| error.to_string())?
            .into();
        let dest_str: String = env
            .get_string(&dest_frb_path)
            .map_err(|error| error.to_string())?
            .into();
        let wire = epub_package::epub_frb::write_epub_frb_wire(
            Path::new(&epub_str),
            Path::new(&images_str),
            Path::new(&dest_str),
            &metadata,
            &parsed_limits,
        );
        env.byte_array_from_slice(&wire)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}
