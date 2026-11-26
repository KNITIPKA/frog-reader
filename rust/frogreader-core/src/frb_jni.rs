//! Safe opaque session IDs. No native pointers cross JNI.
use super::{call_jni, frb};
use jni::objects::{JByteArray, JIntArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::path::Path;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};

const MAX_SESSIONS: usize = 64;
enum Session {
    Writer(frb::Writer),
    Reader(frb::Reader),
}
struct Store {
    next: i64,
    sessions: HashMap<i64, Arc<Mutex<Session>>>,
}
impl Store {
    fn new() -> Self {
        Self {
            next: 1,
            sessions: HashMap::new(),
        }
    }
    fn insert(&mut self, session: Session) -> i64 {
        if self.sessions.len() >= MAX_SESSIONS || self.next == i64::MAX {
            return 0;
        }
        let handle = self.next;
        self.next += 1;
        self.sessions.insert(handle, Arc::new(Mutex::new(session)));
        handle
    }
}
fn store() -> &'static Mutex<Store> {
    static STORE: OnceLock<Mutex<Store>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(Store::new()))
}
fn insert(session: Session) -> i64 {
    store()
        .lock()
        .map(|mut store| store.insert(session))
        .unwrap_or(0)
}
fn get(handle: i64) -> Option<Arc<Mutex<Session>>> {
    store().lock().ok()?.sessions.get(&handle).cloned()
}

pub(crate) fn with_reader<R>(handle: i64, f: impl FnOnce(&mut frb::Reader) -> R) -> Option<R> {
    let session = get(handle)?;
    let mut guard = session.lock().ok()?;
    match &mut *guard {
        Session::Reader(reader) => Some(f(reader)),
        Session::Writer(_) => None,
    }
}
fn flag(success: bool) -> jboolean {
    if success {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|value| value.into())
        .map_err(|error| error.to_string())
}
fn metadata(env: &JNIEnv, value: &JByteArray) -> Result<Option<Vec<u8>>, String> {
    let length = env
        .get_array_length(value)
        .map_err(|error| error.to_string())?;
    if length as usize > frb::MAX_METADATA_BYTES {
        return Ok(None);
    }
    env.convert_byte_array(value)
        .map(Some)
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_beginWrite(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    source: JByteArray,
    counts: JIntArray,
    max_file: jlong,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        let length = env
            .get_array_length(&counts)
            .map_err(|error| error.to_string())?;
        if length as usize > frb::MAX_CHAPTERS {
            return Ok(0);
        }
        let Some(metadata) = metadata(env, &source)? else {
            return Ok(0);
        };
        let mut elements = vec![0; length as usize];
        env.get_int_array_region(&counts, 0, &mut elements)
            .map_err(|error| error.to_string())?;
        let path = string(env, &path)?;
        let Ok(limit) = u64::try_from(max_file) else {
            return Ok(0);
        };
        Ok(
            frb::Writer::begin(Path::new(&path), &metadata, &elements, limit)
                .map(|writer| insert(Session::Writer(writer)))
                .unwrap_or(0),
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_writeBlock(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    block: jint,
    path: JString,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let path = string(env, &path)?;
        let Some(session) = get(handle) else {
            return Ok(JNI_FALSE);
        };
        let Ok(mut session) = session.lock() else {
            return Ok(JNI_FALSE);
        };
        Ok(flag(match &mut *session {
            Session::Writer(writer) if block >= 0 => {
                writer.write_block(block as usize, Path::new(&path)).is_ok()
            }
            _ => false,
        }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_writeBlockBytes(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    block: jint,
    payload: JByteArray,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |env| {
        let Ok(block_idx) = usize::try_from(block) else {
            return Ok(JNI_FALSE);
        };
        let limit = if block_idx == 0 {
            frb::MAX_INDEX_BYTES
        } else {
            frb::MAX_BLOCK_BYTES
        };
        let length = env
            .get_array_length(&payload)
            .map_err(|error| error.to_string())?;
        let Ok(length) = u64::try_from(length) else {
            return Ok(JNI_FALSE);
        };
        if length > limit {
            return Ok(JNI_FALSE);
        }
        let bytes = env
            .convert_byte_array(&payload)
            .map_err(|error| error.to_string())?;
        let Some(session) = get(handle) else {
            return Ok(JNI_FALSE);
        };
        let Ok(mut session) = session.lock() else {
            return Ok(JNI_FALSE);
        };
        Ok(flag(match &mut *session {
            Session::Writer(writer) => writer.write_block_bytes(block_idx, &bytes).is_ok(),
            _ => false,
        }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_finishWrite(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jboolean {
    call_jni(&mut env, JNI_FALSE, |_| {
        let Some(session) = get(handle) else {
            return Ok(JNI_FALSE);
        };
        let Ok(mut session) = session.lock() else {
            return Ok(JNI_FALSE);
        };
        Ok(flag(match &mut *session {
            Session::Writer(writer) => writer.finish().is_ok(),
            _ => false,
        }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_openRead(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    source: JByteArray,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        let Some(metadata) = metadata(env, &source)? else {
            return Ok(0);
        };
        let path = string(env, &path)?;
        Ok(frb::Reader::open(Path::new(&path), &metadata)
            .map(|reader| insert(Session::Reader(reader)))
            .unwrap_or(0))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_readIndex(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jlongArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(session) = get(handle) else {
            return Ok(ptr::null_mut());
        };
        let Ok(session) = session.lock() else {
            return Ok(ptr::null_mut());
        };
        let Session::Reader(reader) = &*session else {
            return Ok(ptr::null_mut());
        };
        let values = reader.index();
        let output = env
            .new_long_array(values.len() as jint)
            .map_err(|error| error.to_string())?;
        env.set_long_array_region(&output, 0, &values)
            .map_err(|error| error.to_string())?;
        Ok(output.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_readGeneration(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Some(session) = get(handle) else {
            return Ok(ptr::null_mut());
        };
        let Ok(session) = session.lock() else {
            return Ok(ptr::null_mut());
        };
        let Session::Reader(reader) = &*session else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&reader.generation())
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_readBlockBytes(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    block: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        let Ok(block) = usize::try_from(block) else {
            return Ok(ptr::null_mut());
        };
        let Some(session) = get(handle) else {
            return Ok(ptr::null_mut());
        };
        let bytes = {
            let Ok(mut session) = session.lock() else {
                return Ok(ptr::null_mut());
            };
            let Session::Reader(reader) = &mut *session else {
                return Ok(ptr::null_mut());
            };
            let Ok(bytes) = reader.read_block_bytes(block) else {
                return Ok(ptr::null_mut());
            };
            bytes
        };
        env.byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustChunkedCacheNative_close(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut store) = store().lock() {
            store.sessions.remove(&handle);
        }
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::sync::atomic::{AtomicU64, Ordering};
    #[test]
    fn closed_handles_never_resolve_or_get_reused() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "frb-store-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let writer = frb::Writer::begin(&path, b"identity", &[], 1024).unwrap();
        let mut store = Store::new();
        let first = store.insert(Session::Writer(writer));
        assert!(first > 0);
        store.sessions.remove(&first);
        assert!(!store.sessions.contains_key(&first));
        assert!(get(-1).is_none());
        let writer2_path = path.with_extension("next");
        let second = store.insert(Session::Writer(
            frb::Writer::begin(&writer2_path, b"identity", &[], 1024).unwrap(),
        ));
        assert!(second > first);
        drop(store);
        fs::remove_file(path).unwrap();
        fs::remove_file(writer2_path).unwrap();
    }
    #[test]
    fn session_table_has_finite_capacity_and_no_id_overflow() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let root = std::env::temp_dir().join(format!(
            "frb-capacity-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        let mut store = Store::new();
        for i in 0..MAX_SESSIONS {
            let writer = frb::Writer::begin(&root.join(i.to_string()), b"", &[], 1024).unwrap();
            assert!(store.insert(Session::Writer(writer)) > 0);
        }
        let writer = frb::Writer::begin(&root.join("overflow"), b"", &[], 1024).unwrap();
        assert_eq!(store.insert(Session::Writer(writer)), 0);
        store.sessions.clear();
        store.next = i64::MAX;
        let writer = frb::Writer::begin(&root.join("counter"), b"", &[], 1024).unwrap();
        assert_eq!(store.insert(Session::Writer(writer)), 0);
        assert!(store.sessions.is_empty());
        drop(store);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn with_reader_exposes_reader_only_and_rejects_writer_or_closed() {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "frb-with-reader-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let meta = b"meta";
        let writer = frb::Writer::begin(&path, meta, &[1], frb::MAX_FILE_BYTES).unwrap();
        let writer_handle = insert(Session::Writer(writer));
        assert!(writer_handle > 0);
        // Writer handles must never be exposed through with_reader.
        assert!(with_reader(writer_handle, |_| ()).is_none());
        // Finish the file via the stored writer and close its handle.
        {
            let session = get(writer_handle).unwrap();
            let mut guard = session.lock().unwrap();
            let Session::Writer(w) = &mut *guard else {
                panic!("expected writer");
            };
            w.write_block_bytes(0, b"idx").unwrap();
            w.write_block_bytes(1, b"glb").unwrap();
            w.write_block_bytes(2, b"ch0").unwrap();
            w.finish().unwrap();
        }
        store().lock().unwrap().sessions.remove(&writer_handle);

        let reader = frb::Reader::open(&path, meta).unwrap();
        let reader_handle = insert(Session::Reader(reader));
        assert!(reader_handle > 0);
        assert_eq!(with_reader(reader_handle, |r| r.index().len()), Some(5));
        assert_eq!(
            with_reader(reader_handle, |r| r.read_block_bytes(2).unwrap()),
            Some(b"ch0".to_vec())
        );

        store().lock().unwrap().sessions.remove(&reader_handle);
        assert!(with_reader(reader_handle, |_| ()).is_none());
        assert!(with_reader(0, |_| ()).is_none());
        assert!(with_reader(-1, |_| ()).is_none());

        fs::remove_file(path).unwrap();
    }
}
