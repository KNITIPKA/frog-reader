use crate::{
    call_jni, css_computed,
    css_computed_model::{Border, ComputedStyle, Declaration, Element},
};
use jni::objects::{JByteArray, JObject};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

const MAX_WIRE_BYTES: usize = 128 * 1024 * 1024;
const SIGNATURE: i32 = 0x3154_5343; // CST1

type WireResult<T> = Result<T, &'static str>;

struct Input<'a>(&'a [u8]);

impl Input<'_> {
    fn int(&mut self) -> WireResult<i32> {
        let (head, rest) = self.0.split_first_chunk().ok_or("Truncated computed CSS")?;
        self.0 = rest;
        Ok(i32::from_le_bytes(*head))
    }

    fn flag(&mut self) -> WireResult<bool> {
        match self.int()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err("Invalid computed CSS flag"),
        }
    }

    fn count(&mut self, row_bytes: usize) -> WireResult<usize> {
        let count = usize::try_from(self.int()?).map_err(|_| "Invalid computed CSS count")?;
        if count > self.0.len() / row_bytes {
            return Err("Invalid computed CSS count");
        }
        Ok(count)
    }

    fn string(&mut self) -> WireResult<String> {
        let length =
            usize::try_from(self.int()?).map_err(|_| "Invalid computed CSS text length")?;
        let (value, rest) = self
            .0
            .split_at_checked(length)
            .ok_or("Truncated computed CSS text")?;
        self.0 = rest;
        std::str::from_utf8(value)
            .map(str::to_owned)
            .map_err(|_| "Invalid UTF-8 in computed CSS")
    }
}

fn request(bytes: &[u8]) -> WireResult<(i32, Element, Vec<Declaration>)> {
    let mut input = Input(bytes);
    let parent = input.int()?;
    let element = Element {
        tag: input.string()?,
        align: input.string()?,
        direction: input.string()?,
        foreground: input.string()?,
        body_foreground: input.string()?,
        background: input.string()?,
        first_strong_rtl: input.flag()?,
        pseudo: input.flag()?,
    };
    let values = (0..input.count(4)?)
        .map(|_| input.string())
        .collect::<WireResult<Vec<_>>>()?;
    let declarations = (0..input.count(12)?)
        .map(|_| {
            let property = input.string()?;
            let value =
                usize::try_from(input.int()?).map_err(|_| "Invalid CSS declaration value")?;
            let expansion = input.int()?;
            if !(0..=22).contains(&expansion) {
                return Err("Invalid CSS shorthand expansion");
            }
            Ok(Declaration {
                property,
                value: values
                    .get(value)
                    .ok_or("Invalid CSS declaration value")?
                    .clone(),
                expansion,
            })
        })
        .collect::<WireResult<Vec<_>>>()?;
    if !input.0.is_empty() {
        return Err("Trailing computed CSS data");
    }
    Ok((parent, element, declarations))
}

struct Output(Vec<u8>);

impl Output {
    fn raw(&mut self, bytes: &[u8]) -> WireResult<()> {
        if bytes.len() > MAX_WIRE_BYTES - self.0.len() {
            return Err("Computed CSS output exceeds its size limit");
        }
        self.0
            .try_reserve(bytes.len())
            .map_err(|_| "Cannot allocate computed CSS result")?;
        self.0.extend_from_slice(bytes);
        Ok(())
    }

    fn int(&mut self, value: i32) -> WireResult<()> {
        self.raw(&value.to_le_bytes())
    }

    fn float(&mut self, value: f32) -> WireResult<()> {
        self.raw(&value.to_le_bytes())
    }

    fn count(&mut self, count: usize) -> WireResult<()> {
        self.int(i32::try_from(count).map_err(|_| "Computed CSS count exceeds its limit")?)
    }

    fn string(&mut self, value: Option<&str>) -> WireResult<()> {
        let Some(value) = value else {
            return self.int(-1);
        };
        self.count(value.len())?;
        self.raw(value.as_bytes())
    }

    fn optional_int(&mut self, value: Option<i32>) -> WireResult<()> {
        self.int(i32::from(value.is_some()))?;
        if let Some(value) = value {
            self.int(value)?;
        }
        Ok(())
    }

    fn border(&mut self, value: Option<&Border>) -> WireResult<()> {
        self.int(i32::from(value.is_some()))?;
        if let Some(border) = value {
            self.float(border.width_em)?;
            self.int(border.color_argb)?;
            self.int(border.style)?;
            self.int(i32::from(border.uses_current_color))?;
        }
        Ok(())
    }

    fn axes(&mut self, axes: Option<&[(String, f32)]>) -> WireResult<()> {
        let Some(axes) = axes else {
            return self.int(-1);
        };
        self.count(axes.len())?;
        for (key, value) in axes {
            self.string(Some(key))?;
            self.float(*value)?;
        }
        Ok(())
    }

    fn scope(&mut self, scope: &[(String, String)]) -> WireResult<()> {
        self.count(scope.len())?;
        for (key, value) in scope {
            self.string(Some(key))?;
            self.string(Some(value))?;
        }
        Ok(())
    }
}

fn response(id: i32, style: &ComputedStyle, inherited_scope: bool) -> WireResult<Vec<u8>> {
    let mut output = Output(Vec::new());
    output.int(SIGNATURE)?;
    output.int(id)?;
    output.int(style.italic.map_or(-1, i32::from))?;
    output.optional_int(style.font_weight)?;
    output.float(style.font_size_em)?;
    output.string(style.text_align.as_deref())?;
    output.float(style.text_indent_em.unwrap_or(f32::NAN))?;
    output.int(i32::from(style.hidden))?;
    output.string(style.display.as_deref())?;
    output.float(style.margin_start_em)?;
    output.float(style.margin_start_frac)?;
    output.float(style.margin_end_em)?;
    output.float(style.margin_end_frac)?;
    output.float(style.margin_inline_start_em)?;
    output.float(style.margin_inline_start_frac)?;
    output.float(style.margin_inline_end_em)?;
    output.float(style.margin_inline_end_frac)?;
    output.float(style.margin_top_em)?;
    output.float(style.margin_bottom_em)?;
    output.int(i32::from(style.margin_top_specified))?;
    output.int(i32::from(style.margin_bottom_specified))?;
    output.float(style.padding_start_em)?;
    output.float(style.padding_start_frac)?;
    output.float(style.padding_end_em)?;
    output.float(style.padding_end_frac)?;
    output.float(style.padding_inline_start_em)?;
    output.float(style.padding_inline_start_frac)?;
    output.float(style.padding_inline_end_em)?;
    output.float(style.padding_inline_end_frac)?;
    output.float(style.padding_top_em)?;
    output.float(style.padding_bottom_em)?;
    output.int(i32::from(style.centered_box))?;
    output.int(i32::from(style.underline))?;
    output.int(i32::from(style.strike))?;
    output.int(i32::from(style.super_script))?;
    output.int(i32::from(style.sub_script))?;
    output.int(i32::from(style.monospace))?;
    output.string(style.font_family_name.as_deref())?;
    output.float(style.line_height_mult.unwrap_or(f32::NAN))?;
    output.float(style.line_height_number.unwrap_or(f32::NAN))?;
    output.float(style.line_height_absolute_em.unwrap_or(f32::NAN))?;
    output.int(style.hyphens_auto.map_or(-1, i32::from))?;
    output.string(style.direction.as_deref())?;
    output.string(style.unicode_bidi.as_deref())?;
    output.string(style.list_style_type.as_deref())?;
    output.optional_int(style.foreground_color_argb)?;
    output.optional_int(style.background_color_argb)?;
    output.int(i32::from(style.page_break_before))?;
    output.int(i32::from(style.break_inside_avoid))?;
    output.string(style.float_side.as_deref())?;
    output.string(style.clear.as_deref())?;
    output.float(style.width_frac.unwrap_or(f32::NAN))?;
    output.float(style.width_em.unwrap_or(f32::NAN))?;
    output.float(style.height_em.unwrap_or(f32::NAN))?;
    output.border(style.border_top.as_ref())?;
    output.border(style.border_right.as_ref())?;
    output.border(style.border_bottom.as_ref())?;
    output.border(style.border_left.as_ref())?;
    output.int(i32::from(style.padding_specified))?;
    output.int(i32::from(style.border_specified))?;
    if inherited_scope {
        output.int(-1)?;
    } else {
        output.scope(&style.custom_props)?;
    }
    output.string(style.font_feature_settings.as_deref())?;
    output.string(style.font_variant_caps.as_deref())?;
    output.string(style.font_variant_numeric.as_deref())?;
    output.string(style.explicit_font_features.as_deref())?;
    output.string(style.font_kerning.as_deref())?;
    output.string(style.font_variant_ligatures.as_deref())?;
    output.axes(style.variation_axes.as_deref())?;
    output.float(style.stretch_percent.unwrap_or(f32::NAN))?;
    output.int(style.optical_sizing.map_or(-1, i32::from))?;
    output.string(style.text_transform.as_deref())?;
    output.int(i32::from(style.visibility_hidden))?;
    output.float(style.max_width_frac.unwrap_or(f32::NAN))?;
    output.float(style.max_width_em.unwrap_or(f32::NAN))?;
    output.float(style.max_height_em.unwrap_or(f32::NAN))?;
    output.float(style.max_height_viewport_frac.unwrap_or(f32::NAN))?;
    output.float(style.min_height_em.unwrap_or(f32::NAN))?;
    output.int(i32::from(style.page_break_after))?;
    output.string(Some(&style.flex_direction))?;
    output.string(Some(&style.flex_wrap))?;
    output.string(style.justify_content.as_deref())?;
    output.string(style.align_items.as_deref())?;
    output.int(i32::from(style.image_invert))?;
    output.int(i32::from(style.background_uses_current_color))?;
    output.float(style.min_height_viewport_frac.unwrap_or(f32::NAN))?;
    output.float(style.min_height_offset_em)?;
    output.int(i32::from(style.center_content_vertically))?;
    output.int(i32::from(style.border_box))?;
    Ok(output.0)
}

#[derive(Default)]
struct Context {
    styles: Vec<ComputedStyle>,
}

impl Context {
    fn resolve(&mut self, bytes: &[u8]) -> WireResult<Vec<u8>> {
        let (parent, element, declarations) = request(bytes)?;
        let parent = match parent {
            -1 => None,
            parent => Some(
                self.styles
                    .get(usize::try_from(parent).map_err(|_| "Invalid parent CSS style")?)
                    .ok_or("Invalid parent CSS style")?,
            ),
        };
        let style = css_computed::resolve(&element, parent, &declarations);
        let inherited_scope =
            parent.is_some_and(|parent| Arc::ptr_eq(&parent.custom_props, &style.custom_props));
        let id = i32::try_from(self.styles.len()).map_err(|_| "CSS style identifiers exhausted")?;
        let result = response(id, &style, inherited_scope)?;
        self.styles.push(style);
        Ok(result)
    }
}

#[derive(Default)]
struct Contexts {
    next: jlong,
    contexts: HashMap<jlong, Arc<Mutex<Context>>>,
}

fn contexts() -> &'static Mutex<Contexts> {
    static CONTEXTS: OnceLock<Mutex<Contexts>> = OnceLock::new();
    CONTEXTS.get_or_init(Mutex::default)
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssComputedNative_open(
    mut env: JNIEnv,
    _receiver: JObject,
) -> jlong {
    call_jni(&mut env, 0, |_| {
        let mut contexts = contexts()
            .lock()
            .map_err(|_| "CSS style contexts unavailable")?;
        let id = contexts
            .next
            .checked_add(1)
            .ok_or("CSS style context identifiers exhausted")?;
        contexts.next = id;
        contexts
            .contexts
            .insert(id, Arc::new(Mutex::new(Context::default())));
        Ok(id)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssComputedNative_resolve(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    input: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let length = env
            .get_array_length(&input)
            .map_err(|error| error.to_string())?;
        if length as usize > MAX_WIRE_BYTES {
            return Err("Computed CSS input exceeds its size limit".into());
        }
        let bytes = env
            .convert_byte_array(&input)
            .map_err(|error| error.to_string())?;
        let context = contexts()
            .lock()
            .map_err(|_| "CSS style contexts unavailable")?
            .contexts
            .get(&handle)
            .cloned()
            .ok_or("Invalid CSS style context")?;
        let result = context
            .lock()
            .map_err(|_| "CSS style context unavailable")?
            .resolve(&bytes)?;
        env.byte_array_from_slice(&result)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_data_parser_RustCssComputedNative_close(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        let context = contexts()
            .lock()
            .map_err(|_| "CSS style contexts unavailable")?
            .contexts
            .remove(&handle);
        drop(context);
        Ok(())
    });
}
