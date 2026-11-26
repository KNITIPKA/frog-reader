use crate::{
    call_jni,
    css_lengths::{self, LineHeight},
    css_shorthand::{self, RawDeclaration},
    css_variables,
};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong};
use jni::JNIEnv;
use std::{collections::HashMap, ptr};

const MAX_BLOCK_BYTES: usize = 32 * 1024 * 1024;
const MAX_SCOPE_BYTES: usize = 128 * 1024 * 1024;
const MAX_DECLARATIONS: usize = 512;

type WireResult<T> = Result<T, &'static str>;

struct Input<'a>(&'a [u8]);

impl Input<'_> {
    fn int(&mut self) -> WireResult<i32> {
        let (head, rest) = self
            .0
            .split_first_chunk()
            .ok_or("Truncated CSS value data")?;
        self.0 = rest;
        Ok(i32::from_le_bytes(*head))
    }

    fn count(&mut self, limit: usize, min_row_bytes: usize) -> WireResult<usize> {
        let count = usize::try_from(self.int()?).map_err(|_| "Invalid CSS value count")?;
        if count > limit || count > self.0.len() / min_row_bytes {
            return Err("Invalid CSS value count");
        }
        Ok(count)
    }

    fn string(&mut self) -> WireResult<String> {
        let length = usize::try_from(self.int()?).map_err(|_| "Invalid CSS value string length")?;
        let (value, rest) = self
            .0
            .split_at_checked(length)
            .ok_or("Truncated CSS value text")?;
        self.0 = rest;
        std::str::from_utf8(value)
            .map(str::to_owned)
            .map_err(|_| "Invalid UTF-8 in CSS value")
    }

    fn importance(&mut self) -> WireResult<bool> {
        match self.int()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("Invalid CSS importance flag"),
        }
    }

    fn scope(&mut self, limit: usize) -> WireResult<css_variables::Scope> {
        let count = self.count(limit, 8)?;
        (0..count)
            .map(|_| Ok((self.string()?, self.string()?)))
            .collect()
    }

    fn finish(self) -> WireResult<()> {
        if self.0.is_empty() {
            Ok(())
        } else {
            Err("Trailing CSS value data")
        }
    }
}

struct Output {
    bytes: Vec<u8>,
    limit: usize,
}

impl Output {
    fn new(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            limit,
        }
    }

    fn append(&mut self, bytes: &[u8]) -> WireResult<()> {
        if bytes.len() > self.limit - self.bytes.len() {
            return Err("CSS value result exceeds its size limit");
        }
        self.bytes
            .try_reserve(bytes.len())
            .map_err(|_| "Cannot allocate CSS value result")?;
        self.bytes.extend_from_slice(bytes);
        Ok(())
    }

    fn int(&mut self, value: i32) -> WireResult<()> {
        self.append(&value.to_le_bytes())
    }

    fn count(&mut self, value: usize) -> WireResult<()> {
        self.int(i32::try_from(value).map_err(|_| "CSS value count exceeds its limit")?)
    }

    fn string(&mut self, value: Option<&str>) -> WireResult<()> {
        match value {
            Some(value) => {
                self.count(value.len())?;
                self.append(value.as_bytes())
            }
            None => self.int(-1),
        }
    }
}

fn compiled_block(bytes: &[u8]) -> WireResult<Vec<u8>> {
    let mut input = Input(bytes);
    let count = input.count(MAX_DECLARATIONS, 12)?;
    let source: Vec<_> = (0..count)
        .map(|_| {
            Ok(RawDeclaration {
                property: input.string()?,
                value: input.string()?,
                important: input.importance()?,
            })
        })
        .collect::<WireResult<_>>()?;
    input.finish()?;
    let (consumed, declarations) = css_shorthand::compile(&source);
    let mut values = Vec::new();
    let mut value_ids = HashMap::new();
    for declaration in &declarations {
        // Delayed shorthands share the same source string across longhands.
        value_ids
            .entry(declaration.value.as_ref())
            .or_insert_with(|| {
                values.push(declaration.value.as_ref());
                values.len() - 1
            });
    }

    let mut output = Output::new(MAX_SCOPE_BYTES);
    output.count(consumed)?;
    output.count(values.len())?;
    for value in values {
        output.string(Some(value))?;
    }
    output.count(declarations.len())?;
    for declaration in &declarations {
        output.string(Some(&declaration.property))?;
        output.count(value_ids[declaration.value.as_ref()])?;
        output.int(i32::from(declaration.important))?;
        output.int(declaration.expansion)?;
    }
    Ok(output.bytes)
}

fn resolved_variables(bytes: &[u8]) -> WireResult<Vec<u8>> {
    let mut input = Input(bytes);
    let parent = input.scope(MAX_SCOPE_BYTES / 8)?;
    let own = input.scope(MAX_SCOPE_BYTES / 8)?;
    let return_scope = !own.is_empty();
    let count = input.count(MAX_SCOPE_BYTES / 4, 4)?;
    let values = (0..count)
        .map(|_| input.string())
        .collect::<WireResult<Vec<_>>>()?;
    input.finish()?;
    let (scope, resolved) = css_variables::resolve(parent, own, &values);

    let mut output = Output::new(MAX_SCOPE_BYTES + MAX_BLOCK_BYTES);
    if return_scope {
        output.count(scope.len())?;
        for (name, value) in scope {
            output.string(Some(&name))?;
            output.string(Some(&value))?;
        }
    } else {
        output.int(-1)?;
    }
    output.count(resolved.len())?;
    for value in &resolved {
        output.string(value.as_deref())?;
    }
    Ok(output.bytes)
}

fn input_bytes(env: &JNIEnv<'_>, input: &JByteArray<'_>, limit: usize) -> Result<Vec<u8>, String> {
    let length = env
        .get_array_length(input)
        .map_err(|error| error.to_string())?;
    if length as usize > limit {
        return Err("CSS value input exceeds its size limit".into());
    }
    env.convert_byte_array(input)
        .map_err(|error| error.to_string())
}

fn value_operation<T>(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
    fallback: T,
    apply: impl FnOnce(&str, &mut JNIEnv<'_>) -> Result<T, String>,
) -> T {
    call_jni(env, fallback, |env| {
        let bytes = input_bytes(env, input, MAX_BLOCK_BYTES)?;
        let value = std::str::from_utf8(&bytes).map_err(|error| error.to_string())?;
        apply(value, env)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_compile(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let bytes = input_bytes(env, &input, MAX_BLOCK_BYTES)?;
        let result = compiled_block(&bytes)?;
        env.byte_array_from_slice(&result)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_variables(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let bytes = input_bytes(env, &input, MAX_SCOPE_BYTES)?;
        let result = resolved_variables(&bytes)?;
        env.byte_array_from_slice(&result)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_expand(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    expansion: jint,
) -> jbyteArray {
    value_operation(&mut env, &input, ptr::null_mut(), |value, env| {
        let Some(expanded) = css_shorthand::expand(expansion, value) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(expanded.as_bytes())
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_fontSize(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    inherited: jfloat,
) -> jfloat {
    value_operation(&mut env, &input, f32::NAN, |value, _| {
        Ok(css_lengths::font_size(value, inherited).unwrap_or(f32::NAN))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_lengthEm(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    percent_base: jfloat,
    em_scale: jfloat,
    allow_negative: jboolean,
) -> jfloat {
    value_operation(&mut env, &input, f32::NAN, |value, _| {
        Ok(
            css_lengths::length_em(value, percent_base, em_scale, allow_negative != 0)
                .unwrap_or(f32::NAN),
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssValuesNative_lineHeight(
    mut env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
    font_size_em: jfloat,
) -> jlong {
    value_operation(&mut env, &input, 0, |value, _| {
        Ok(match css_lengths::line_height(value, font_size_em) {
            None => 0,
            Some(LineHeight::Number(number)) => (1_i64 << 32) | i64::from(number.to_bits()),
            Some(LineHeight::AbsoluteEm(length)) => (2_i64 << 32) | i64::from(length.to_bits()),
        })
    })
}
