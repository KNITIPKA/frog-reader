use crate::call_jni;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{BufReader, Read, Write};
use std::path::Path;
use std::ptr;
use std::sync::{Arc, Mutex, OnceLock};
use zip::ZipArchive;

#[cfg(test)]
mod tests;

const MAX_ENTRY_BYTES: u64 = 64 * 1024 * 1024;
const MAX_DIRECTORY_BYTES: usize = 16 * 1024 * 1024;
const MAX_SESSIONS: usize = 32;

struct EpubZip {
    archive: ZipArchive<BufReader<File>>,
    directory: Vec<u8>,
}

#[derive(Debug)]
enum OpenError {
    Archive,
    EntryCount,
    DeclaredBytes,
}

enum EntryError {
    Input,
    Output,
}

impl EpubZip {
    fn open(path: &Path, max_entries: usize, max_declared: u64) -> Result<Self, OpenError> {
        let file = File::open(path).map_err(|_| OpenError::Archive)?;
        let archive = ZipArchive::new(BufReader::new(file)).map_err(|_| OpenError::Archive)?;
        if archive.len() > max_entries {
            return Err(OpenError::EntryCount);
        }
        // Ambiguous names would hide entries from the decoded directory and its budgets.
        if archive.has_duplicate_names() {
            return Err(OpenError::Archive);
        }
        let mut directory = Vec::new();
        directory.extend_from_slice(&(archive.len() as u32).to_le_bytes());
        let mut names = std::collections::HashSet::with_capacity(archive.len());
        let mut declared = 0u64;
        for index in 0..archive.len() {
            let entry = archive
                .by_index_data(index)
                .map_err(|_| OpenError::Archive)?;
            let name = entry.name().map_err(|_| OpenError::Archive)?;
            let size = entry.size();
            let compressed = entry.compressed_size();
            declared = declared.checked_add(size).ok_or(OpenError::DeclaredBytes)?;
            if declared > max_declared {
                return Err(OpenError::DeclaredBytes);
            }
            if name.is_empty()
                || compressed > i64::MAX as u64
                || name.len() + 21 > MAX_DIRECTORY_BYTES - directory.len()
                || !names.insert(name.to_string())
            {
                return Err(OpenError::Archive);
            }
            directory.extend_from_slice(&(name.len() as u32).to_le_bytes());
            directory.extend_from_slice(name.as_bytes());
            directory.extend_from_slice(&size.to_le_bytes());
            directory.extend_from_slice(&compressed.to_le_bytes());
            directory.push(u8::from(name.ends_with('/')));
        }
        Ok(Self { archive, directory })
    }

    fn read(&mut self, index: usize) -> Result<Vec<u8>, EntryError> {
        let size = self.entry_size(index)?;
        let mut output = Vec::new();
        output
            .try_reserve_exact(size as usize)
            .map_err(|_| EntryError::Input)?;
        self.extract(index, size, &mut output)?;
        Ok(output)
    }

    fn copy(&mut self, index: usize, target: &Path) -> Result<(), EntryError> {
        let size = self.entry_size(index)?;
        let mut file = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(target)
            .map_err(|_| EntryError::Output)?;
        self.extract(index, size, &mut file)
    }

    fn entry_size(&self, index: usize) -> Result<u64, EntryError> {
        let entry = self
            .archive
            .by_index_data(index)
            .map_err(|_| EntryError::Input)?;
        let size = entry.size();
        if size > MAX_ENTRY_BYTES || entry.is_dir() {
            return Err(EntryError::Input);
        }
        Ok(size)
    }

    fn extract(
        &mut self,
        index: usize,
        expected: u64,
        output: &mut impl Write,
    ) -> Result<(), EntryError> {
        let mut entry = self
            .archive
            .by_index(index)
            .map_err(|_| EntryError::Input)?;
        let mut buffer = [0; 16 * 1024];
        let mut written = 0u64;
        loop {
            let count = entry.read(&mut buffer).map_err(|_| EntryError::Input)?;
            if count == 0 {
                break;
            }
            if count as u64 > expected - written {
                return Err(EntryError::Input);
            }
            output
                .write_all(&buffer[..count])
                .map_err(|_| EntryError::Output)?;
            written += count as u64;
        }
        // ZipFile validates CRC at EOF; it does not reject a short decoded entry.
        if written != expected {
            return Err(EntryError::Input);
        }
        Ok(())
    }
}

type ArchiveHandle = Arc<Mutex<EpubZip>>;

#[derive(Default)]
struct Sessions {
    last: i64,
    live: HashMap<i64, ArchiveHandle>,
}

fn sessions() -> &'static Mutex<Sessions> {
    static SESSIONS: OnceLock<Mutex<Sessions>> = OnceLock::new();
    SESSIONS.get_or_init(Mutex::default)
}

fn with_archive<T>(handle: jlong, use_archive: impl FnOnce(&mut EpubZip) -> T) -> Option<T> {
    let archive = sessions().lock().ok()?.live.get(&handle)?.clone();
    let mut archive = archive.lock().ok()?;
    Some(use_archive(&mut archive))
}

pub(crate) fn read_entry_bytes(handle: jlong, index: usize) -> Option<Vec<u8>> {
    with_archive(handle, |archive| archive.read(index).ok()).flatten()
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubZipNative_open(
    mut env: JNIEnv,
    _receiver: JObject,
    path: JString,
    max_entries: jint,
    max_declared: jlong,
) -> jlong {
    call_jni(&mut env, 0, |env| {
        if max_entries <= 0 || max_declared < 0 {
            return Ok(0);
        }
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        let archive =
            match EpubZip::open(Path::new(&path), max_entries as usize, max_declared as u64) {
                Ok(archive) => archive,
                Err(OpenError::EntryCount) => return Ok(-1),
                Err(OpenError::DeclaredBytes) => return Ok(-2),
                Err(OpenError::Archive) => return Ok(0),
            };
        let Ok(mut sessions) = sessions().lock() else {
            return Ok(0);
        };
        if sessions.live.len() == MAX_SESSIONS || sessions.last == i64::MAX {
            return Ok(0);
        }
        sessions.last += 1;
        let handle = sessions.last;
        sessions.live.insert(handle, Arc::new(Mutex::new(archive)));
        Ok(handle)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubZipNative_directory(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        with_archive(handle, |archive| {
            env.byte_array_from_slice(&archive.directory)
                .map(JByteArray::into_raw)
                .map_err(|e| e.to_string())
        })
        .unwrap_or(Ok(ptr::null_mut()))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubZipNative_read(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    index: jint,
) -> jbyteArray {
    call_jni(&mut env, ptr::null_mut(), |env| {
        if index < 0 {
            return Ok(ptr::null_mut());
        }
        let Some(Ok(bytes)) = with_archive(handle, |archive| archive.read(index as usize)) else {
            return Ok(ptr::null_mut());
        };
        env.byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubZipNative_copy(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
    index: jint,
    path: JString,
) -> jint {
    call_jni(&mut env, 1, |env| {
        if index < 0 {
            return Ok(1);
        }
        let path: String = env.get_string(&path).map_err(|e| e.to_string())?.into();
        Ok(
            match with_archive(handle, |archive| {
                archive.copy(index as usize, Path::new(&path))
            }) {
                Some(Ok(())) => 0,
                Some(Err(EntryError::Output)) => 2,
                _ => 1,
            },
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_example_frogreader_engine_RustEpubZipNative_close(
    mut env: JNIEnv,
    _receiver: JObject,
    handle: jlong,
) {
    call_jni(&mut env, (), |_| {
        if let Ok(mut sessions) = sessions().lock() {
            sessions.live.remove(&handle);
        }
        Ok(())
    });
}
