//! Checked page anchors for a single publication and layout specification.
use crc32fast::{hash, Hasher};
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;

pub const KEY_BYTES: usize = 32;
pub const MAX_ANCHOR_INTS: usize = 2_000_000;
const MAGIC: &[u8; 8] = b"FROGPAGE";
const VERSION: u32 = 1;
const HEADER_BYTES: usize = 64;
const PAGE_BYTES: usize = 8;
const BUFFER_BYTES: usize = 8192;
const MAX_FILE_BYTES: usize = HEADER_BYTES + MAX_ANCHOR_INTS / 2 * PAGE_BYTES;

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn put_u32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn validate_anchor(
    anchor: [i32; 2],
    previous: Option<[i32; 2]>,
    item_count: i32,
) -> io::Result<()> {
    if anchor[0] < 0 || anchor[0] >= item_count || anchor[1] < 0 {
        return Err(invalid("page anchor outside source coordinates"));
    }
    if previous.is_some_and(|previous| anchor < previous) {
        return Err(invalid("page anchors out of order"));
    }
    Ok(())
}

pub fn valid_int_count(count: usize) -> bool {
    (2..=MAX_ANCHOR_INTS).contains(&count) && count.is_multiple_of(2)
}

pub fn valid_file_size(bytes: usize) -> bool {
    (HEADER_BYTES + PAGE_BYTES..=MAX_FILE_BYTES).contains(&bytes)
}

pub fn read(path: &Path, key: &[u8; KEY_BYTES], item_count: i32) -> io::Result<Vec<i32>> {
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() {
        return Err(invalid("page index file type"));
    }
    let file_bytes =
        usize::try_from(metadata.len()).map_err(|_| invalid("page index file length"))?;
    read_from(file, file_bytes, key, item_count)
}

pub fn read_bytes(bytes: &[u8], key: &[u8; KEY_BYTES], item_count: i32) -> io::Result<Vec<i32>> {
    read_from(bytes, bytes.len(), key, item_count)
}

fn read_from(
    mut input: impl Read,
    file_bytes: usize,
    key: &[u8; KEY_BYTES],
    item_count: i32,
) -> io::Result<Vec<i32>> {
    if item_count <= 0 {
        return Err(invalid("empty page index source"));
    }
    if !valid_file_size(file_bytes) {
        return Err(invalid("page index file length"));
    }
    let mut header = [0; HEADER_BYTES];
    input.read_exact(&mut header)?;
    if &header[..8] != MAGIC
        || u32_at(&header, 8) != VERSION
        || u32_at(&header, 12) as usize != HEADER_BYTES
        || hash(&header[..60]) != u32_at(&header, 60)
    {
        return Err(invalid("page index header"));
    }
    if u32_at(&header, 16) != item_count as u32 || &header[24..56] != key {
        return Err(invalid("page index source or layout key changed"));
    }
    let page_count = u32_at(&header, 20) as usize;
    if !(1..=MAX_ANCHOR_INTS / 2).contains(&page_count)
        || file_bytes != HEADER_BYTES + page_count * PAGE_BYTES
    {
        return Err(invalid("page index anchor count"));
    }

    let mut anchors = Vec::new();
    anchors
        .try_reserve_exact(page_count * 2)
        .map_err(|_| io::Error::other("cannot allocate page anchors"))?;
    let mut hasher = Hasher::new();
    let mut buffer = [0; BUFFER_BYTES];
    let mut remaining_bytes = page_count * PAGE_BYTES;
    let mut previous = None;
    while remaining_bytes > 0 {
        let take = remaining_bytes.min(buffer.len());
        let bytes = &mut buffer[..take];
        input.read_exact(bytes)?;
        hasher.update(bytes);
        for pair in bytes.chunks_exact(PAGE_BYTES) {
            let anchor = [u32_at(pair, 0) as i32, u32_at(pair, 4) as i32];
            validate_anchor(anchor, previous, item_count)?;
            anchors.extend_from_slice(&anchor);
            previous = Some(anchor);
        }
        remaining_bytes -= take;
    }
    if hasher.finalize() != u32_at(&header, 56) || input.read(&mut [0])? != 0 {
        return Err(invalid("page index payload checksum or length"));
    }
    Ok(anchors)
}

pub fn write(
    path: &Path,
    key: &[u8; KEY_BYTES],
    item_count: i32,
    anchors: &[i32],
) -> io::Result<()> {
    if item_count <= 0 || !valid_int_count(anchors.len()) {
        return Err(invalid("page index source or anchor count"));
    }
    let mut previous = None;
    for pair in anchors.chunks_exact(2) {
        let anchor = [pair[0], pair[1]];
        validate_anchor(anchor, previous, item_count)?;
        previous = Some(anchor);
    }

    let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
    let mut header = [0; HEADER_BYTES];
    file.write_all(&header)?;
    let mut hasher = Hasher::new();
    let mut buffer = [0; BUFFER_BYTES];
    for chunk in anchors.chunks(BUFFER_BYTES / 4) {
        let bytes = &mut buffer[..chunk.len() * 4];
        for (value, dest) in chunk.iter().zip(bytes.chunks_exact_mut(4)) {
            dest.copy_from_slice(&value.to_le_bytes());
        }
        hasher.update(bytes);
        file.write_all(bytes)?;
    }
    header[..8].copy_from_slice(MAGIC);
    put_u32(&mut header, 8, VERSION);
    put_u32(&mut header, 12, HEADER_BYTES as u32);
    put_u32(&mut header, 16, item_count as u32);
    put_u32(&mut header, 20, (anchors.len() / 2) as u32);
    header[24..56].copy_from_slice(key);
    put_u32(&mut header, 56, hasher.finalize());
    let header_crc = hash(&header[..60]);
    put_u32(&mut header, 60, header_crc);
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header)?;
    file.sync_all()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    const KEY: [u8; KEY_BYTES] = [0xA5; KEY_BYTES];
    const ANCHORS: [i32; 8] = [0, 0, 1, 4, 1, 4, 5, 0];

    struct Scratch(PathBuf);

    impl Scratch {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "frog-page-index-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn fixture(&self) -> PathBuf {
            let path = self.0.join("pages.tmp");
            write(&path, &KEY, 6, &ANCHORS).unwrap();
            path
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn round_trip_preserves_equal_anchors_and_checks_layout_identity() {
        let scratch = Scratch::new();
        let path = scratch.fixture();
        assert_eq!(read(&path, &KEY, 6).unwrap(), ANCHORS);
        assert!(read(&path, &[0; KEY_BYTES], 6).is_err());
        assert!(read(&path, &KEY, 7).is_err());
        let bytes = fs::read(path).unwrap();
        assert_eq!(read_bytes(&bytes, &KEY, 6).unwrap(), ANCHORS);
        assert!(read_bytes(&bytes, &[0; KEY_BYTES], 6).is_err());
        assert!(read_bytes(&bytes, &KEY, 7).is_err());
    }

    #[test]
    fn malformed_or_incomplete_files_return_no_anchors() {
        let scratch = Scratch::new();
        let path = scratch.fixture();
        let original = fs::read(&path).unwrap();
        for length in [
            0,
            HEADER_BYTES - 1,
            HEADER_BYTES + PAGE_BYTES,
            original.len() - 1,
        ] {
            fs::write(&path, &original[..length]).unwrap();
            assert!(read(&path, &KEY, 6).is_err());
            assert!(read_bytes(&original[..length], &KEY, 6).is_err());
        }
        for offset in [0, 8, 16, 24, 56, 60, HEADER_BYTES + 4] {
            let mut corrupt = original.clone();
            corrupt[offset] ^= 1;
            assert!(read_bytes(&corrupt, &KEY, 6).is_err());
            fs::write(&path, corrupt).unwrap();
            assert!(read(&path, &KEY, 6).is_err());
        }
        let mut trailing = original;
        trailing.push(0);
        assert!(read_bytes(&trailing, &KEY, 6).is_err());
        fs::write(&path, trailing).unwrap();
        assert!(read(&path, &KEY, 6).is_err());
    }

    #[test]
    fn valid_checksums_cannot_bypass_order_coordinates_or_count_limits() {
        let scratch = Scratch::new();
        let path = scratch.fixture();
        let original = fs::read(&path).unwrap();
        for (offset, value) in [
            (8, VERSION + 1),
            (20, 0),
            (20, MAX_ANCHOR_INTS as u32),
            (HEADER_BYTES, 6),
            (HEADER_BYTES + 4, u32::MAX),
            (HEADER_BYTES + PAGE_BYTES * 2 + 4, 3),
        ] {
            let mut invalid = original.clone();
            put_u32(&mut invalid, offset, value);
            let payload_crc = hash(&invalid[HEADER_BYTES..]);
            put_u32(&mut invalid, 56, payload_crc);
            let header_crc = hash(&invalid[..60]);
            put_u32(&mut invalid, 60, header_crc);
            assert!(read_bytes(&invalid, &KEY, 6).is_err(), "offset {offset}");
            fs::write(&path, invalid).unwrap();
            assert!(read(&path, &KEY, 6).is_err(), "offset {offset}");
        }
    }

    #[test]
    fn writer_rejects_invalid_anchors_before_creating_a_file() {
        let scratch = Scratch::new();
        let path = scratch.0.join("invalid.tmp");
        for anchors in [
            &[][..],
            &[0][..],
            &[-1, 0][..],
            &[0, -1][..],
            &[6, 0][..],
            &[1, 4, 1, 3][..],
        ] {
            assert!(write(&path, &KEY, 6, anchors).is_err());
            assert!(!path.exists());
        }
        assert!(write(&path, &KEY, 0, &[0, 0]).is_err());
        assert!(!path.exists());
    }

    #[test]
    fn writer_never_replaces_an_existing_generation() {
        let scratch = Scratch::new();
        let path = scratch.fixture();
        assert!(write(&path, &KEY, 6, &[5, 8]).is_err());
        assert_eq!(read(&path, &KEY, 6).unwrap(), ANCHORS);
    }

    #[test]
    fn maximum_page_count_round_trips_and_extra_page_is_rejected() {
        let scratch = Scratch::new();
        let path = scratch.0.join("largest.tmp");
        let anchors = vec![0; MAX_ANCHOR_INTS];
        write(&path, &KEY, 1, &anchors).unwrap();
        assert_eq!(read(&path, &KEY, 1).unwrap(), anchors);
        let path = scratch.0.join("oversized.tmp");
        assert!(write(&path, &KEY, 1, &vec![0; MAX_ANCHOR_INTS + 2]).is_err());
        assert!(!path.exists());
    }
}
