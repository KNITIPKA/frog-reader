//! Panic-contained JNI boundary for MOBI / AZW3 (KF8) native acceleration.

use crate::{
    call_jni,
    mobi::{
        backward_varint, decode_font_record, forward_varint, is_pdb_book, kf8_assemble,
        kf8_parse_fdst, palmdoc_decompress, parse_exth, parse_mobi_header, parse_palmdoc_header,
        parse_pdb_header, parse_pdb_offsets, trailing_content_length, ExthEntry, FragInput,
        HuffCdic, IndxLimits, IndxParser, Kf8Assembled, Kf8Limits, MobiError, MobiHeaderInfo,
        PalmDocHeaderInfo, ParsedIndex, PdbHeaderInfo, SkelInput,
    },
};
use jni::objects::{JByteArray, JIntArray, JLongArray, JObject};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

const STATUS_OK: u8 = 0;
const STATUS_IO_ERROR: u8 = 1;
const STATUS_RESOURCE_LIMIT: u8 = 2;
const STATUS_DAMAGED_NULL: u8 = 3;

const MAX_INPUT_BYTES: usize = 128 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 256 * 1024 * 1024;
const MAX_HUFF_SESSIONS: usize = 4096;
const MAX_INDX_SESSIONS: usize = 256;

const IDX_MAGIC: i32 = 0x3158_4449; // IDX1
const SKL_MAGIC: i32 = 0x314C_4B53; // SKL1
const FRG_MAGIC: i32 = 0x3147_5246; // FRG1
const KF8_MAGIC: i32 = 0x3138_464B; // KF81
const PDB_MAGIC: i32 = 0x3142_4450; // PDB1
const OFS_MAGIC: i32 = 0x3153_464F; // OFS1
const PLM_MAGIC: i32 = 0x314D_4C50; // PLM1
const MOB_MAGIC: i32 = 0x3142_4F4D; // MOB1
const EXT_MAGIC: i32 = 0x3154_5845; // EXT1

struct Registry<T> {
    next: jlong,
    live: HashMap<jlong, Arc<Mutex<T>>>,
    cap: usize,
}

impl<T> Registry<T> {
    fn new(cap: usize) -> Self {
        Self {
            next: 0,
            live: HashMap::new(),
            cap,
        }
    }

    fn insert(&mut self, value: T) -> Option<jlong> {
        if self.live.len() >= self.cap {
            return None;
        }
        let handle = self.next.checked_add(1)?;
        self.next = handle;
        self.live.insert(handle, Arc::new(Mutex::new(value)));
        Some(handle)
    }

    fn get(&self, handle: jlong) -> Option<Arc<Mutex<T>>> {
        self.live.get(&handle).cloned()
    }

    fn remove(&mut self, handle: jlong) -> Option<Arc<Mutex<T>>> {
        self.live.remove(&handle)
    }
}

fn huff_registry() -> &'static Mutex<Registry<HuffCdic>> {
    static HUFF: OnceLock<Mutex<Registry<HuffCdic>>> = OnceLock::new();
    HUFF.get_or_init(|| Mutex::new(Registry::new(MAX_HUFF_SESSIONS)))
}

fn indx_registry() -> &'static Mutex<Registry<IndxParser>> {
    static INDX: OnceLock<Mutex<Registry<IndxParser>>> = OnceLock::new();
    INDX.get_or_init(|| Mutex::new(Registry::new(MAX_INDX_SESSIONS)))
}

// ---------------------------------------------------------------------------
// JNI conversion & wire helpers
// ---------------------------------------------------------------------------

fn read_full_bytes(
    env: &JNIEnv,
    array: &JByteArray,
    max_len: usize,
) -> Result<Option<Vec<u8>>, String> {
    let length = env.get_array_length(array).map_err(|e| e.to_string())?;
    let Ok(len_u) = usize::try_from(length) else {
        return Ok(None);
    };
    if len_u > max_len {
        return Ok(None);
    }
    if len_u == 0 {
        return Ok(Some(Vec::new()));
    }
    env.convert_byte_array(array)
        .map(Some)
        .map_err(|e| e.to_string())
}

fn read_slice(
    env: &JNIEnv,
    array: &JByteArray,
    off: jint,
    len: jint,
    max_len: usize,
) -> Result<Option<Vec<u8>>, String> {
    let total = env.get_array_length(array).map_err(|e| e.to_string())?;
    let Ok(total_u) = usize::try_from(total) else {
        return Ok(None);
    };
    let Ok(off_u) = usize::try_from(off) else {
        return Ok(None);
    };
    let Ok(len_u) = usize::try_from(len) else {
        return Ok(None);
    };
    let Some(end) = off_u.checked_add(len_u) else {
        return Ok(None);
    };
    if end > total_u || len_u > max_len {
        return Ok(None);
    }
    if len_u == 0 {
        return Ok(Some(Vec::new()));
    }
    if off_u == 0 && len_u == total_u {
        return env
            .convert_byte_array(array)
            .map(Some)
            .map_err(|e| e.to_string());
    }
    let mut out = Vec::new();
    if out.try_reserve_exact(len_u).is_err() {
        return Ok(None);
    }
    let mut chunk = vec![0_i8; len_u.min(64 * 1024)];
    let mut pos = 0;
    while pos < len_u {
        let take = (len_u - pos).min(chunk.len());
        env.get_byte_array_region(array, (off_u + pos) as jint, &mut chunk[..take])
            .map_err(|e| e.to_string())?;
        out.extend(chunk[..take].iter().map(|&b| b as u8));
        pos += take;
    }
    Ok(Some(out))
}

fn read_longs(
    env: &JNIEnv,
    array: &JLongArray,
    expected: usize,
) -> Result<Option<Vec<i64>>, String> {
    let length = env.get_array_length(array).map_err(|e| e.to_string())?;
    if usize::try_from(length).ok() != Some(expected) {
        return Ok(None);
    }
    let mut out = vec![0_i64; expected];
    env.get_long_array_region(array, 0, &mut out)
        .map_err(|e| e.to_string())?;
    Ok(Some(out))
}

fn read_ints(env: &JNIEnv, array: &JIntArray, max_len: usize) -> Result<Option<Vec<i32>>, String> {
    let length = env.get_array_length(array).map_err(|e| e.to_string())?;
    let Ok(len_u) = usize::try_from(length) else {
        return Ok(None);
    };
    if len_u > max_len {
        return Ok(None);
    }
    let mut out = vec![0_i32; len_u];
    if len_u > 0 {
        env.get_int_array_region(array, 0, &mut out)
            .map_err(|e| e.to_string())?;
    }
    Ok(Some(out))
}

fn to_jbytes(env: &JNIEnv, bytes: &[u8]) -> Result<jbyteArray, String> {
    env.byte_array_from_slice(bytes)
        .map(JByteArray::into_raw)
        .map_err(|e| e.to_string())
}

fn error_envelope(error: MobiError) -> Vec<u8> {
    match error {
        MobiError::Io(msg) => {
            let mut out = Vec::with_capacity(1 + msg.len());
            out.push(STATUS_IO_ERROR);
            out.extend_from_slice(msg.as_bytes());
            out
        }
        MobiError::Limit(kind, msg) => {
            let mut out = Vec::with_capacity(2 + msg.len());
            out.push(STATUS_RESOURCE_LIMIT);
            out.push(kind as u8);
            out.extend_from_slice(msg.as_bytes());
            out
        }
    }
}

fn ok_payload_envelope(payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() >= MAX_OUTPUT_BYTES {
        return None;
    }
    let mut out = Vec::new();
    out.try_reserve_exact(1 + payload.len()).ok()?;
    out.push(STATUS_OK);
    out.extend_from_slice(payload);
    Some(out)
}

struct WireWriter {
    buf: Vec<u8>,
}

impl WireWriter {
    fn new_status_ok() -> Self {
        Self {
            buf: vec![STATUS_OK],
        }
    }

    fn append(&mut self, bytes: &[u8]) -> Option<()> {
        if bytes.len() > MAX_OUTPUT_BYTES - self.buf.len() {
            return None;
        }
        self.buf.try_reserve(bytes.len()).ok()?;
        self.buf.extend_from_slice(bytes);
        Some(())
    }

    fn u8(&mut self, value: u8) -> Option<()> {
        self.append(&[value])
    }

    fn i32(&mut self, value: i32) -> Option<()> {
        self.append(&value.to_le_bytes())
    }

    fn i64(&mut self, value: i64) -> Option<()> {
        self.append(&value.to_le_bytes())
    }

    fn count(&mut self, value: usize) -> Option<()> {
        self.i32(i32::try_from(value).ok()?)
    }

    fn bytes(&mut self, data: &[u8]) -> Option<()> {
        self.count(data.len())?;
        self.append(data)
    }

    fn string(&mut self, text: &str) -> Option<()> {
        self.count(text.len())?;
        self.append(text.as_bytes())
    }

    fn optional_string(&mut self, text: Option<&str>) -> Option<()> {
        match text {
            Some(s) => {
                self.u8(1)?;
                self.string(s)
            }
            None => self.u8(0),
        }
    }
}

struct WireReader<'a>(&'a [u8]);

impl<'a> WireReader<'a> {
    fn i32(&mut self) -> Option<i32> {
        let (head, rest) = self.0.split_first_chunk()?;
        self.0 = rest;
        Some(i32::from_le_bytes(*head))
    }

    fn i64(&mut self) -> Option<i64> {
        let (head, rest) = self.0.split_first_chunk()?;
        self.0 = rest;
        Some(i64::from_le_bytes(*head))
    }

    fn u8(&mut self) -> Option<u8> {
        let (&head, rest) = self.0.split_first()?;
        self.0 = rest;
        Some(head)
    }

    fn count(&mut self, min_row_bytes: usize) -> Option<usize> {
        let count = usize::try_from(self.i32()?).ok()?;
        if min_row_bytes > 0 && count > self.0.len() / min_row_bytes {
            return None;
        }
        Some(count)
    }

    fn string(&mut self) -> Option<String> {
        let len = usize::try_from(self.i32()?).ok()?;
        let (slice, rest) = self.0.split_at_checked(len)?;
        self.0 = rest;
        std::str::from_utf8(slice).ok().map(str::to_owned)
    }

    fn finish(self) -> bool {
        self.0.is_empty()
    }
}

fn encode_parsed_index(parsed: &ParsedIndex) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(IDX_MAGIC)?;
    w.count(parsed.entries.len())?;
    for entry in &parsed.entries {
        w.string(&entry.label)?;
        w.count(entry.tags.len())?;
        for (tag_id, values) in &entry.tags {
            w.i32(i32::from(*tag_id))?;
            w.count(values.len())?;
            for &val in values {
                w.i64(val)?;
            }
        }
    }
    w.count(parsed.cncx.len())?;
    for (key, text) in &parsed.cncx {
        w.i32(*key)?;
        w.string(text)?;
    }
    Some(w.buf)
}

fn decode_skel_wire(bytes: &[u8]) -> Option<Vec<SkelInput>> {
    let mut r = WireReader(bytes);
    if r.i32()? != SKL_MAGIC {
        return None;
    }
    let count = r.count(2)?;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let has_tag1 = r.u8()?;
        let num_frags = match has_tag1 {
            0 => None,
            1 => Some(r.i64()?),
            _ => return None,
        };
        let has_tag6 = r.u8()?;
        let geometry = match has_tag6 {
            0 => None,
            1 => {
                let len = r.count(8)?;
                let mut vals = Vec::with_capacity(len);
                for _ in 0..len {
                    vals.push(r.i64()?);
                }
                Some(vals)
            }
            _ => return None,
        };
        out.push(SkelInput {
            num_frags,
            geometry,
        });
    }
    if !r.finish() {
        return None;
    }
    Some(out)
}

fn decode_frag_wire(bytes: &[u8]) -> Option<Vec<FragInput>> {
    let mut r = WireReader(bytes);
    if r.i32()? != FRG_MAGIC {
        return None;
    }
    let count = r.count(5)?;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let label = r.string()?;
        let has_len = r.u8()?;
        let length = match has_len {
            0 => None,
            1 => Some(r.i64()?),
            _ => return None,
        };
        out.push(FragInput { label, length });
    }
    if !r.finish() {
        return None;
    }
    Some(out)
}

fn encode_kf8_assembled(assembled: &Kf8Assembled) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(KF8_MAGIC)?;
    w.count(assembled.parts.len())?;
    for part in &assembled.parts {
        w.count(part.len())?;
        w.append(part)?;
    }
    w.count(assembled.frag_locations.len())?;
    for &(part_idx, offset) in &assembled.frag_locations {
        w.i32(part_idx)?;
        w.i32(offset)?;
    }
    Some(w.buf)
}

fn encode_pdb_header(info: &PdbHeaderInfo) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(PDB_MAGIC)?;
    w.string(&info.name)?;
    w.string(&info.type_creator)?;
    w.count(info.record_count)?;
    Some(w.buf)
}

fn encode_pdb_offsets(offsets: &[i32]) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(OFS_MAGIC)?;
    w.count(offsets.len())?;
    for &offset in offsets {
        w.i32(offset)?;
    }
    Some(w.buf)
}

fn encode_palmdoc_header(info: &PalmDocHeaderInfo) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(PLM_MAGIC)?;
    w.i32(info.compression)?;
    w.i32(info.text_length)?;
    w.i32(info.text_record_count)?;
    w.i32(info.record_size)?;
    w.i32(info.encryption_type)?;
    Some(w.buf)
}

fn encode_mobi_header(info: &MobiHeaderInfo) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(MOB_MAGIC)?;
    w.i32(info.header_length)?;
    w.i32(info.mobi_type)?;
    w.i32(info.text_encoding)?;
    w.i32(info.file_version)?;
    w.optional_string(info.full_name.as_deref())?;
    w.string(&info.charset_name)?;
    w.i32(info.locale)?;
    w.i32(info.first_non_book_index)?;
    w.i32(info.first_image_index)?;
    w.i32(info.huffman_record_offset)?;
    w.i32(info.huffman_record_count)?;
    w.i32(info.exth_flags)?;
    w.i32(info.extra_record_data_flags)?;
    w.i32(info.indx_record_offset)?;
    w.i32(info.fdst_record)?;
    w.i32(info.fdst_count)?;
    w.i32(info.skel_index)?;
    w.i32(info.frag_index)?;
    Some(w.buf)
}

fn encode_exth(entries: &[ExthEntry]) -> Option<Vec<u8>> {
    let mut w = WireWriter::new_status_ok();
    w.i32(EXT_MAGIC)?;
    w.count(entries.len())?;
    for entry in entries {
        w.i32(entry.entry_type)?;
        w.bytes(&entry.data)?;
    }
    Some(w.buf)
}

// ---------------------------------------------------------------------------
// PDB container & MOBI / PalmDOC / EXTH headers JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_isPdbBook(
    mut env: JNIEnv,
    _receiver: JObject,
    header: JByteArray,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let total = env.get_array_length(&header).map_err(|e| e.to_string())?;
        if total < 68 {
            return Ok(JNI_FALSE);
        }
        let mut head = [0_i8; 68];
        env.get_byte_array_region(&header, 0, &mut head)
            .map_err(|e| e.to_string())?;
        let head_u8 = head.map(|b| b as u8);
        Ok(if is_pdb_book(&head_u8) {
            JNI_TRUE
        } else {
            JNI_FALSE
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_parsePdbHeader(
    mut env: JNIEnv,
    _receiver: JObject,
    head: JByteArray,
    file_size: jlong,
    max_mobi_records: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(head_bytes) = read_full_bytes(env, &head, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Ok(max_records) = usize::try_from(max_mobi_records) else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io("Invalid MOBI record limit".into())),
            );
        };
        match parse_pdb_header(&head_bytes, file_size, max_records) {
            Ok(info) => {
                let Some(wire) = encode_pdb_header(&info) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_parsePdbOffsets(
    mut env: JNIEnv,
    _receiver: JObject,
    table: JByteArray,
    record_count: jint,
    file_size: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(table_bytes) = read_full_bytes(env, &table, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Ok(count) = usize::try_from(record_count) else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io(format!(
                    "Damaged MOBI: bad record count {record_count}"
                ))),
            );
        };
        match parse_pdb_offsets(&table_bytes, count, file_size) {
            Ok(offsets) => {
                let Some(wire) = encode_pdb_offsets(&offsets) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_parsePalmDocHeader(
    mut env: JNIEnv,
    _receiver: JObject,
    record0: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let total = env.get_array_length(&record0).map_err(|e| e.to_string())?;
        let take = usize::try_from(total).unwrap_or(0).min(16);
        let mut buf = [0_i8; 16];
        if take > 0 {
            env.get_byte_array_region(&record0, 0, &mut buf[..take])
                .map_err(|e| e.to_string())?;
        }
        let buf_u8 = buf.map(|b| b as u8);
        match parse_palmdoc_header(&buf_u8[..take]) {
            Ok(info) => {
                let Some(wire) = encode_palmdoc_header(&info) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_parseMobiHeader(
    mut env: JNIEnv,
    _receiver: JObject,
    record0: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &record0, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        match parse_mobi_header(&bytes) {
            Ok(Some(info)) => {
                let Some(wire) = encode_mobi_header(&info) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Ok(None) => to_jbytes(env, &[STATUS_DAMAGED_NULL]),
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_parseExth(
    mut env: JNIEnv,
    _receiver: JObject,
    record0: JByteArray,
    mobi_header_length: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &record0, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let entries = parse_exth(&bytes, mobi_header_length);
        let Some(wire) = encode_exth(&entries) else {
            return Ok(ptr::null_mut());
        };
        to_jbytes(env, &wire)
    })
}

// ---------------------------------------------------------------------------
// PalmDOC & TrailingEntries JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_palmdocDecompress(
    mut env: JNIEnv,
    _receiver: JObject,
    src: JByteArray,
    off: jint,
    len: jint,
    max_out: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(slice) = read_slice(env, &src, off, len, MAX_INPUT_BYTES)? else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io("PalmDoc: output overflow".into())),
            );
        };
        let Ok(max_out_u) = usize::try_from(max_out) else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io("PalmDoc: output overflow".into())),
            );
        };
        let bounded = max_out_u.min(MAX_OUTPUT_BYTES - 1);
        match palmdoc_decompress(&slice, bounded) {
            Ok(decoded) => {
                let Some(wire) = ok_payload_envelope(&decoded) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_trailingContentLength(
    mut env: JNIEnv,
    _receiver: JObject,
    data: JByteArray,
    off: jint,
    len: jint,
    flags: jint,
) -> jint {
    call_jni(&mut env, -1, |env| {
        let Some(slice) = read_slice(env, &data, off, len, MAX_INPUT_BYTES)? else {
            return Ok(-1);
        };
        let content_len = trailing_content_length(&slice, flags as u32);
        Ok(jint::try_from(content_len).unwrap_or(-1))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_backwardVarint(
    mut env: JNIEnv,
    _receiver: JObject,
    data: JByteArray,
    off: jint,
    end_len: jint,
) -> jint {
    call_jni(&mut env, -1, |env| {
        let Some(slice) = read_slice(env, &data, off, end_len, MAX_INPUT_BYTES)? else {
            return Ok(-1);
        };
        Ok(backward_varint(&slice))
    })
}

// ---------------------------------------------------------------------------
// Huff/CDIC JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_huffOpen(
    mut env: JNIEnv,
    _receiver: JObject,
    huff_record: JByteArray,
    max_decoded_bytes: jint,
    max_dictionary_bytes: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &huff_record, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        match HuffCdic::new(&bytes, max_decoded_bytes, max_dictionary_bytes) {
            Ok(decoder) => {
                let handle = huff_registry()
                    .lock()
                    .map_err(|_| "HUFF registry unavailable".to_string())?
                    .insert(decoder);
                let Some(handle) = handle else {
                    return Ok(ptr::null_mut());
                };
                let mut out = [0u8; 9];
                out[0] = STATUS_OK;
                out[1..9].copy_from_slice(&handle.to_le_bytes());
                to_jbytes(env, &out)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

fn with_session<S, T>(
    registry: &Mutex<Registry<S>>,
    label: &str,
    handle: jlong,
    op: impl FnOnce(&mut S) -> T,
) -> Result<Option<T>, String> {
    let session = registry
        .lock()
        .map_err(|_| format!("{label} registry unavailable"))?
        .get(handle);
    let Some(session) = session else {
        return Ok(None);
    };
    let mut guard = session
        .lock()
        .map_err(|_| format!("{label} session unavailable"))?;
    Ok(Some(op(&mut guard)))
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_huffAddCdic(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    cdic_record: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &cdic_record, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(result) = with_session(huff_registry(), "HUFF", handle, |d| d.add_cdic(&bytes))?
        else {
            return Ok(ptr::null_mut());
        };
        match result {
            Ok(()) => to_jbytes(env, &[STATUS_OK]),
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_huffDecompress(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    src: JByteArray,
    off: jint,
    len: jint,
    max_out: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(slice) = read_slice(env, &src, off, len, MAX_INPUT_BYTES)? else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io("Damaged MOBI: HUFF output overflow".into())),
            );
        };
        let Ok(max_out_u) = usize::try_from(max_out) else {
            return to_jbytes(
                env,
                &error_envelope(MobiError::Io("Damaged MOBI: HUFF output overflow".into())),
            );
        };
        let Some(result) = with_session(huff_registry(), "HUFF", handle, |decoder| {
            decoder.decompress(&slice, max_out_u.min(MAX_OUTPUT_BYTES - 1))
        })?
        else {
            return Ok(ptr::null_mut());
        };
        match result {
            Ok(decoded) => {
                let Some(wire) = ok_payload_envelope(&decoded) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_huffDecompressBounded(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    src: JByteArray,
    off: jint,
    len: jint,
    max_output_bytes: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(slice) = read_slice(env, &src, off, len, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(result) = with_session(huff_registry(), "HUFF", handle, |decoder| {
            decoder.decompress_bounded(&slice, max_output_bytes)
        })?
        else {
            return Ok(ptr::null_mut());
        };
        match result {
            Ok(decoded) => {
                let Some(wire) = ok_payload_envelope(&decoded) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_huffClose(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        let removed = huff_registry()
            .lock()
            .map_err(|_| "HUFF registry unavailable".to_string())?
            .remove(handle);
        drop(removed);
        Ok(())
    });
}

// ---------------------------------------------------------------------------
// MobiFontRecord JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_decodeFontRecord(
    mut env: JNIEnv,
    _receiver: JObject,
    data: JByteArray,
    off: jint,
    len: jint,
    max_decoded_bytes: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if max_decoded_bytes <= 0 {
            return Ok(ptr::null_mut());
        }
        let Some(slice) = read_slice(env, &data, off, len, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(decoded) = decode_font_record(&slice, max_decoded_bytes) else {
            return Ok(ptr::null_mut());
        };
        to_jbytes(env, &decoded)
    })
}

// ---------------------------------------------------------------------------
// MobiIndex JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_indexBegin(
    mut env: JNIEnv,
    _receiver: JObject,
    header: JByteArray,
    limits: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(header_bytes) = read_full_bytes(env, &header, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(limit_vals) = read_longs(env, &limits, 5)? else {
            return Ok(ptr::null_mut());
        };
        let Ok(max_tagx_entries) = usize::try_from(limit_vals[0]) else {
            return Ok(ptr::null_mut());
        };
        let Ok(max_entries) = usize::try_from(limit_vals[1]) else {
            return Ok(ptr::null_mut());
        };
        let Ok(max_values) = usize::try_from(limit_vals[2]) else {
            return Ok(ptr::null_mut());
        };
        let Ok(max_cncx_entries) = usize::try_from(limit_vals[3]) else {
            return Ok(ptr::null_mut());
        };
        let indx_limits = IndxLimits {
            max_tagx_entries,
            max_entries,
            max_values,
            max_cncx_entries,
            max_cncx_bytes: limit_vals[4],
        };
        match IndxParser::begin(&header_bytes, indx_limits) {
            Ok(Some(parser)) => {
                let entry_records = parser.entry_record_count as i32;
                let cncx_count = parser.cncx_count as i32;
                let handle = indx_registry()
                    .lock()
                    .map_err(|_| "INDX registry unavailable".to_string())?
                    .insert(parser);
                let Some(handle) = handle else {
                    return Ok(ptr::null_mut());
                };
                let mut out = [0u8; 17];
                out[0] = STATUS_OK;
                out[1..9].copy_from_slice(&handle.to_le_bytes());
                out[9..13].copy_from_slice(&entry_records.to_le_bytes());
                out[13..17].copy_from_slice(&cncx_count.to_le_bytes());
                to_jbytes(env, &out)
            }
            Ok(None) => to_jbytes(env, &[STATUS_DAMAGED_NULL]),
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_indexAddEntryRecord(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    record: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &record, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(result) = with_session(indx_registry(), "INDX", handle, |p| {
            p.add_entry_record(&bytes)
        })?
        else {
            return Ok(ptr::null_mut());
        };
        match result {
            Ok(true) => to_jbytes(env, &[STATUS_OK]),
            Ok(false) => to_jbytes(env, &[STATUS_DAMAGED_NULL]),
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_indexAddCncxRecord(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    record: JByteArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(bytes) = read_full_bytes(env, &record, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(result) = with_session(indx_registry(), "INDX", handle, |p| {
            p.add_cncx_record(&bytes)
        })?
        else {
            return Ok(ptr::null_mut());
        };
        match result {
            Ok(()) => to_jbytes(env, &[STATUS_OK]),
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_indexFinish(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let session = indx_registry()
            .lock()
            .map_err(|_| "INDX registry unavailable".to_string())?
            .remove(handle);
        let Some(session) = session else {
            return Ok(ptr::null_mut());
        };
        let Ok(parser) = Arc::try_unwrap(session) else {
            return Ok(ptr::null_mut());
        };
        let parser = parser
            .into_inner()
            .map_err(|_| "INDX session unavailable".to_string())?;
        let parsed = parser.finish();
        let Some(wire) = encode_parsed_index(&parsed) else {
            return Ok(ptr::null_mut());
        };
        to_jbytes(env, &wire)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_indexClose(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        let removed = indx_registry()
            .lock()
            .map_err(|_| "INDX registry unavailable".to_string())?
            .remove(handle);
        drop(removed);
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_forwardVarint(
    mut env: JNIEnv,
    _receiver: JObject,
    data: JByteArray,
    offset: jint,
) -> jlongArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(off_u) = usize::try_from(offset) else {
            return Ok(ptr::null_mut());
        };
        let Some(bytes) = read_full_bytes(env, &data, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some((value, consumed)) = forward_varint(&bytes, off_u) else {
            return Ok(ptr::null_mut());
        };
        let output = env.new_long_array(2).map_err(|e| e.to_string())?;
        env.set_long_array_region(&output, 0, &[value, consumed as i64])
            .map_err(|e| e.to_string())?;
        Ok(output.into_raw())
    })
}

// ---------------------------------------------------------------------------
// Kf8Assembler JNI
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_kf8ParseFdst(
    mut env: JNIEnv,
    _receiver: JObject,
    fdst: JByteArray,
    raw_text_len: jint,
    max_flow_aggregate_bytes: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(raw_len_u) = usize::try_from(raw_text_len) else {
            return Ok(ptr::null_mut());
        };
        let Some(fdst_bytes) = read_full_bytes(env, &fdst, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let fdst_opt = if fdst_bytes.is_empty() {
            None
        } else {
            Some(fdst_bytes.as_slice())
        };
        match kf8_parse_fdst(fdst_opt, raw_len_u, max_flow_aggregate_bytes) {
            Ok(ranges) => {
                let mut w = WireWriter::new_status_ok();
                let Some(()) = w.count(ranges.len()) else {
                    return Ok(ptr::null_mut());
                };
                for (start, end) in ranges {
                    let (Ok(s), Ok(e)) = (i32::try_from(start), i32::try_from(end)) else {
                        return Ok(ptr::null_mut());
                    };
                    if w.i32(s).is_none() || w.i32(e).is_none() {
                        return Ok(ptr::null_mut());
                    }
                }
                to_jbytes(env, &w.buf)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustMobiNative_kf8Assemble(
    mut env: JNIEnv,
    _receiver: JObject,
    raw_text: JByteArray,
    flow_ranges: JIntArray,
    skel_wire: JByteArray,
    frag_wire: JByteArray,
    limits: JLongArray,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(ranges) = read_ints(env, &flow_ranges, 8192)? else {
            return Ok(ptr::null_mut());
        };
        if ranges.len() < 2 {
            return Ok(ptr::null_mut());
        }
        let flow0_start = ranges[0];
        let Some(flow0_len) = ranges[1].checked_sub(flow0_start) else {
            return Ok(ptr::null_mut());
        };
        let Some(flow0) = read_slice(env, &raw_text, flow0_start, flow0_len, MAX_INPUT_BYTES)?
        else {
            return Ok(ptr::null_mut());
        };
        let Some(skel_bytes) = read_full_bytes(env, &skel_wire, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(frag_bytes) = read_full_bytes(env, &frag_wire, MAX_INPUT_BYTES)? else {
            return Ok(ptr::null_mut());
        };
        let Some(limit_vals) = read_longs(env, &limits, 4)? else {
            return Ok(ptr::null_mut());
        };
        let Some(skel) = decode_skel_wire(&skel_bytes) else {
            return Ok(ptr::null_mut());
        };
        let Some(frag) = decode_frag_wire(&frag_bytes) else {
            return Ok(ptr::null_mut());
        };
        let (Ok(max_parts), Ok(max_fragments)) = (
            usize::try_from(limit_vals[0]),
            usize::try_from(limit_vals[1]),
        ) else {
            return Ok(ptr::null_mut());
        };
        let kf8_limits = Kf8Limits {
            max_parts,
            max_fragments,
            max_part_bytes: limit_vals[2],
            max_assembled_bytes: limit_vals[3],
        };
        match kf8_assemble(&flow0, &skel, &frag, &kf8_limits) {
            Ok(assembled) => {
                let Some(wire) = encode_kf8_assembled(&assembled) else {
                    return Ok(ptr::null_mut());
                };
                to_jbytes(env, &wire)
            }
            Err(error) => to_jbytes(env, &error_envelope(error)),
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_handles_are_bounded_and_monotonic() {
        let mut reg = Registry::new(2);
        let h1 = reg.insert(10i32).unwrap();
        let h2 = reg.insert(20i32).unwrap();
        assert_eq!(h1, 1);
        assert_eq!(h2, 2);
        assert!(reg.insert(30i32).is_none());
        assert!(reg.remove(h1).is_some());
        assert!(reg.remove(h1).is_none());
        let h3 = reg.insert(40i32).unwrap();
        assert_eq!(h3, 3);

        reg.live.clear();
        reg.next = jlong::MAX;
        assert!(reg.insert(50i32).is_none());
    }

    #[test]
    fn skel_and_frag_wire_round_trip() {
        let mut skel_buf = Vec::new();
        skel_buf.extend_from_slice(&SKL_MAGIC.to_le_bytes());
        skel_buf.extend_from_slice(&1i32.to_le_bytes());
        skel_buf.push(1);
        skel_buf.extend_from_slice(&2i64.to_le_bytes());
        skel_buf.push(1);
        skel_buf.extend_from_slice(&2i32.to_le_bytes());
        skel_buf.extend_from_slice(&0i64.to_le_bytes());
        skel_buf.extend_from_slice(&42i64.to_le_bytes());
        let skel = decode_skel_wire(&skel_buf).unwrap();
        assert_eq!(skel.len(), 1);
        assert_eq!(skel[0].num_frags, Some(2));
        assert_eq!(skel[0].geometry.as_deref(), Some(&[0, 42][..]));

        let mut frag_buf = Vec::new();
        frag_buf.extend_from_slice(&FRG_MAGIC.to_le_bytes());
        frag_buf.extend_from_slice(&1i32.to_le_bytes());
        frag_buf.extend_from_slice(&2i32.to_le_bytes());
        frag_buf.extend_from_slice(b"12");
        frag_buf.push(1);
        frag_buf.extend_from_slice(&15i64.to_le_bytes());
        let frag = decode_frag_wire(&frag_buf).unwrap();
        assert_eq!(frag.len(), 1);
        assert_eq!(frag[0].label, "12");
        assert_eq!(frag[0].length, Some(15));
    }

    #[test]
    fn parsed_index_and_kf8_wires_encode_cleanly() {
        let parsed = ParsedIndex {
            entries: vec![crate::mobi::IndexEntry {
                label: "e0".into(),
                tags: vec![(1, vec![10, 20])],
            }],
            cncx: vec![(0, "Chapter".into())],
        };
        let wire = encode_parsed_index(&parsed).unwrap();
        assert_eq!(wire[0], STATUS_OK);
        assert_eq!(&wire[1..5], &IDX_MAGIC.to_le_bytes());

        let assembled = Kf8Assembled {
            parts: vec![b"<html/>".to_vec()],
            frag_locations: vec![(0, 6)],
        };
        let kf8_wire = encode_kf8_assembled(&assembled).unwrap();
        assert_eq!(kf8_wire[0], STATUS_OK);
        assert_eq!(&kf8_wire[1..5], &KF8_MAGIC.to_le_bytes());
    }

    #[test]
    fn pdb_and_header_wires_encode_cleanly() {
        let pdb_wire = encode_pdb_header(&PdbHeaderInfo {
            name: "Book".into(),
            type_creator: "BOOKMOBI".into(),
            record_count: 3,
        })
        .unwrap();
        assert_eq!(pdb_wire[0], STATUS_OK);
        assert_eq!(&pdb_wire[1..5], &PDB_MAGIC.to_le_bytes());

        let ofs_wire = encode_pdb_offsets(&[94, 100, 110, 120]).unwrap();
        assert_eq!(ofs_wire[0], STATUS_OK);
        assert_eq!(&ofs_wire[1..5], &OFS_MAGIC.to_le_bytes());

        let palm_wire = encode_palmdoc_header(&PalmDocHeaderInfo {
            compression: 2,
            text_length: 123,
            text_record_count: 1,
            record_size: 4096,
            encryption_type: 0,
        })
        .unwrap();
        assert_eq!(palm_wire[0], STATUS_OK);
        assert_eq!(&palm_wire[1..5], &PLM_MAGIC.to_le_bytes());

        let mobi_wire = encode_mobi_header(&MobiHeaderInfo {
            header_length: 232,
            mobi_type: 2,
            text_encoding: 65001,
            file_version: 6,
            full_name: Some("Title".into()),
            charset_name: "UTF-8".into(),
            locale: 0,
            first_non_book_index: -1,
            first_image_index: 5,
            huffman_record_offset: -1,
            huffman_record_count: 0,
            exth_flags: 0x40,
            extra_record_data_flags: 1,
            indx_record_offset: -1,
            fdst_record: -1,
            fdst_count: -1,
            skel_index: -1,
            frag_index: -1,
        })
        .unwrap();
        assert_eq!(mobi_wire[0], STATUS_OK);
        assert_eq!(&mobi_wire[1..5], &MOB_MAGIC.to_le_bytes());

        let exth_wire = encode_exth(&[ExthEntry {
            entry_type: 100,
            data: b"Author".to_vec(),
        }])
        .unwrap();
        assert_eq!(exth_wire[0], STATUS_OK);
        assert_eq!(&exth_wire[1..5], &EXT_MAGIC.to_le_bytes());
    }
}
