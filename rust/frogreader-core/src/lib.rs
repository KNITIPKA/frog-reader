//! Versioned JNI boundary for the optional FrogReader engine.
//!
//! M0 establishes transport and failure containment. M1 adds the checked FRB
//! chapter container; Kotlin owns semantic model serialization and validation.
#![deny(unsafe_op_in_unsafe_fn)]

mod book_search;
mod book_search_jni;
mod chapter_charset;
mod chapter_markup;
mod chapter_markup_jni;
mod css_calc;
mod css_color;
mod css_computed;
mod css_computed_boxes;
mod css_computed_fonts;
mod css_computed_jni;
mod css_computed_model;
mod css_declarations;
pub(crate) mod css_engine;
mod css_lengths;
mod css_match;
mod css_match_jni;
mod css_selectors;
mod css_selectors_jni;
mod css_shorthand;
mod css_syntax;
mod css_syntax_jni;
mod css_values_jni;
mod css_variables;
mod epub_nav;
mod epub_ncx;
mod epub_package;
mod epub_package_jni;
mod epub_zip;
mod fb2_stream;
mod fb2_txt_jni;
mod frb;
mod frb_jni;
pub(crate) mod frb_model;
mod mobi;
mod mobi_jni;
mod page_index;
mod page_index_jni;
mod text_book;
mod woff;
mod woff_jni;

use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

const PROTOCOL_VERSION: jint = 1;

#[derive(Debug, PartialEq)]
enum BridgeFailure {
    Operation(String),
    Panic,
}

/// All fallible Rust work, including JNI conversions, belongs inside this guard.
fn contain_failure<T>(operation: impl FnOnce() -> Result<T, String>) -> Result<T, BridgeFailure> {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(result) => result.map_err(BridgeFailure::Operation),
        Err(_) => Err(BridgeFailure::Panic),
    }
}

fn call_jni<T>(
    env: &mut JNIEnv,
    fallback: T,
    operation: impl FnOnce(&mut JNIEnv) -> Result<T, String>,
) -> T {
    match contain_failure(|| operation(env)) {
        Ok(value) => value,
        Err(failure) => {
            // Preserve an existing Java exception (including OutOfMemoryError).
            // The JVM observes it on return; Kotlin decides whether fallback is safe.
            if matches!(env.exception_check(), Ok(false)) {
                let message = match failure {
                    BridgeFailure::Operation(message) => message,
                    BridgeFailure::Panic => "FrogReader Rust engine panicked".into(),
                };
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
            fallback
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustNative_protocolVersion(
    mut env: JNIEnv,
    _receiver: JObject,
) -> jint {
    call_jni(&mut env, 0, |_| Ok(PROTOCOL_VERSION))
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustNative_echo(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let bytes = env.convert_byte_array(&input).map_err(|e| e.to_string())?;
        env.byte_array_from_slice(&bytes)
            .map(|array| array.into_raw())
            .map_err(|e| e.to_string())
    })
}

// A deliberate panic exists only in the host test build. It is absent from APKs.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustNative_panicProbe(
    mut env: JNIEnv,
    _receiver: JObject,
) -> jint {
    call_jni(&mut env, 0, |_| panic!("intentional JNI containment probe"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn successful_operation_survives_boundary_unchanged() {
        assert_eq!(
            contain_failure(|| Ok(vec![0, 128, 255])),
            Ok(vec![0, 128, 255])
        );
    }

    #[test]
    fn operation_error_is_not_reported_as_success() {
        assert_eq!(
            contain_failure::<()>(|| Err("bad input".into())),
            Err(BridgeFailure::Operation("bad input".into()))
        );
    }

    #[test]
    fn panic_is_contained_before_returning_to_jvm() {
        assert_eq!(
            contain_failure::<()>(|| panic!("intentional containment test")),
            Err(BridgeFailure::Panic)
        );
        assert_eq!(contain_failure(|| Ok(PROTOCOL_VERSION)), Ok(1));
    }
}
