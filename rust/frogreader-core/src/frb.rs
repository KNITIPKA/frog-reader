//! Checked, independently compressed chapter container. All on-disk integers are LE.
use crc32fast::hash;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;

pub const MAX_BLOCK_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_INDEX_BYTES: u64 = 8 * 1024 * 1024;
pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_METADATA_BYTES: usize = 1024 * 1024;
pub const MAX_CHAPTERS: usize = 100_000;
const MAX_EXPANDED_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const HEADER_BYTES: usize = 64;
const ENTRY_BYTES: usize = 48;
// The lightweight book index and heavy global model are independently readable.
const PREFIX_BLOCKS: usize = 2;
const MAGIC: &[u8; 8] = b"FRBCACHE";
const VERSION: u32 = 2;

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}
fn add(a: u64, b: u64) -> io::Result<u64> {
    a.checked_add(b).ok_or_else(|| invalid("integer overflow"))
}
fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().expect("fixed field"))
}
fn u64_at(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("fixed field"))
}
fn put32(bytes: &mut [u8], offset: usize, value: u32) {
    bytes[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}
fn put64(bytes: &mut [u8], offset: usize, value: u64) {
    bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
}
fn buffer(size: usize) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(size)
        .map_err(|_| invalid("allocation limit"))?;
    bytes.resize(size, 0);
    Ok(bytes)
}
fn bounded_file(path: &Path, limit: u64) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let length = file.metadata()?.len();
    if length > limit {
        return Err(invalid("decoded block limit"));
    }
    let mut bytes = buffer(length as usize)?;
    file.read_exact(&mut bytes)?;
    if file.read(&mut [0])? != 0 {
        return Err(invalid("input grew"));
    }
    Ok(bytes)
}

#[derive(Clone, Debug)]
struct Entry {
    offset: u64,
    compressed: u64,
    expanded: u64,
    elements: u64,
    start: u64,
    crc: u32,
}
impl Entry {
    fn encode(&self, bytes: &mut [u8]) {
        put64(bytes, 0, self.offset);
        put64(bytes, 8, self.compressed);
        put64(bytes, 16, self.expanded);
        put64(bytes, 24, self.elements);
        put64(bytes, 32, self.start);
        put32(bytes, 40, self.crc);
    }
    fn decode(bytes: &[u8]) -> io::Result<Self> {
        if u32_at(bytes, 44) != 0 {
            return Err(invalid("reserved index field"));
        }
        Ok(Self {
            offset: u64_at(bytes, 0),
            compressed: u64_at(bytes, 8),
            expanded: u64_at(bytes, 16),
            elements: u64_at(bytes, 24),
            start: u64_at(bytes, 32),
            crc: u32_at(bytes, 40),
        })
    }
}

pub struct Writer {
    file: File,
    metadata: Vec<u8>,
    counts: Vec<u64>,
    entries: Vec<Entry>,
    max_file: u64,
    expanded: u64,
    finished: bool,
}
impl Writer {
    pub fn begin(path: &Path, metadata: &[u8], counts: &[i32], max_file: u64) -> io::Result<Self> {
        if metadata.len() > MAX_METADATA_BYTES
            || counts.len() > MAX_CHAPTERS
            || max_file > MAX_FILE_BYTES
        {
            return Err(invalid("container limit"));
        }
        let mut total = 0u64;
        let mut checked_counts = Vec::with_capacity(counts.len());
        for &count in counts {
            let count = u64::try_from(count).map_err(|_| invalid("negative elements"))?;
            total = add(total, count)?;
            if total > i32::MAX as u64 {
                return Err(invalid("element count limit"));
            }
            checked_counts.push(count);
        }
        let prefix = HEADER_BYTES as u64
            + metadata.len() as u64
            + (counts.len() + PREFIX_BLOCKS) as u64 * ENTRY_BYTES as u64;
        if prefix >= max_file {
            return Err(invalid("index exceeds file budget"));
        }
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(path)?;
        file.write_all(&[0; HEADER_BYTES])?;
        file.write_all(metadata)?;
        file.write_all(&buffer((counts.len() + PREFIX_BLOCKS) * ENTRY_BYTES)?)?;
        Ok(Self {
            file,
            metadata: metadata.to_vec(),
            counts: checked_counts,
            entries: Vec::new(),
            max_file,
            expanded: 0,
            finished: false,
        })
    }
    pub fn write_precompressed_block(
        &mut self,
        block: usize,
        compressed: &[u8],
        expanded_len: u64,
        crc: u32,
    ) -> io::Result<()> {
        if self.finished
            || block != self.entries.len()
            || block >= self.counts.len() + PREFIX_BLOCKS
        {
            return Err(invalid("block order"));
        }
        let limit = if block == 0 {
            MAX_INDEX_BYTES
        } else {
            MAX_BLOCK_BYTES
        };
        if expanded_len > limit {
            return Err(invalid("decoded block limit"));
        }
        let max_compressed = MAX_BLOCK_BYTES + MAX_BLOCK_BYTES / 255 + 16;
        if compressed.is_empty() || compressed.len() as u64 > max_compressed {
            return Err(invalid("compressed block limit"));
        }
        let expanded = add(self.expanded, expanded_len)?;
        if expanded > MAX_EXPANDED_BYTES {
            return Err(invalid("total decoded limit"));
        }
        let offset = self.file.stream_position()?;
        if add(offset, compressed.len() as u64)? > self.max_file {
            return Err(invalid("compressed file limit"));
        }
        if let Err(error) = self.file.write_all(compressed) {
            self.finished = true; // A partial payload must never be retried/published.
            return Err(error);
        }
        let (elements, start) = if block < PREFIX_BLOCKS {
            (0, 0)
        } else {
            let previous = &self.entries[block - 1];
            (
                self.counts[block - PREFIX_BLOCKS],
                add(previous.start, previous.elements)?,
            )
        };
        self.entries.push(Entry {
            offset,
            compressed: compressed.len() as u64,
            expanded: expanded_len,
            elements,
            start,
            crc,
        });
        self.expanded = expanded;
        Ok(())
    }
    pub fn write_block_bytes(&mut self, block: usize, raw: &[u8]) -> io::Result<()> {
        if self.finished
            || block != self.entries.len()
            || block >= self.counts.len() + PREFIX_BLOCKS
        {
            return Err(invalid("block order"));
        }
        let limit = if block == 0 {
            MAX_INDEX_BYTES
        } else {
            MAX_BLOCK_BYTES
        };
        if raw.len() as u64 > limit {
            return Err(invalid("decoded block limit"));
        }
        let expanded = add(self.expanded, raw.len() as u64)?;
        if expanded > MAX_EXPANDED_BYTES {
            return Err(invalid("total decoded limit"));
        }
        let compressed = lz4_flex::block::compress(raw);
        self.write_precompressed_block(block, &compressed, raw.len() as u64, hash(raw))
    }
    pub fn write_block(&mut self, block: usize, source: &Path) -> io::Result<()> {
        if self.finished
            || block != self.entries.len()
            || block >= self.counts.len() + PREFIX_BLOCKS
        {
            return Err(invalid("block order"));
        }
        let raw = bounded_file(
            source,
            if block == 0 {
                MAX_INDEX_BYTES
            } else {
                MAX_BLOCK_BYTES
            },
        )?;
        self.write_block_bytes(block, &raw)
    }
    pub fn finish(&mut self) -> io::Result<()> {
        if self.finished || self.entries.len() != self.counts.len() + PREFIX_BLOCKS {
            return Err(invalid("incomplete blocks"));
        }
        // Commit is attempted once. Any partial index/header write remains an
        // unusable temporary file, rather than being retried from a wrong offset.
        self.finished = true;
        let length = self.file.stream_position()?;
        let mut index = buffer(self.entries.len() * ENTRY_BYTES)?;
        for (entry, bytes) in self.entries.iter().zip(index.chunks_exact_mut(ENTRY_BYTES)) {
            entry.encode(bytes);
        }
        self.file
            .seek(SeekFrom::Start((HEADER_BYTES + self.metadata.len()) as u64))?;
        self.file.write_all(&index)?;
        self.file.sync_data()?;
        let mut header = [0; HEADER_BYTES];
        header[..8].copy_from_slice(MAGIC);
        put32(&mut header, 8, VERSION);
        put32(&mut header, 12, HEADER_BYTES as u32);
        put32(&mut header, 16, 1);
        put32(&mut header, 20, self.counts.len() as u32);
        put32(&mut header, 24, self.metadata.len() as u32);
        put32(&mut header, 28, ENTRY_BYTES as u32);
        put64(&mut header, 32, length);
        put64(&mut header, 40, self.expanded);
        put32(&mut header, 48, hash(&self.metadata));
        put32(&mut header, 52, hash(&index));
        let crc = hash(&header);
        put32(&mut header, 56, crc);
        self.file.seek(SeekFrom::Start(0))?;
        self.file.write_all(&header)?;
        self.file.sync_all()?;
        self.finished = true;
        Ok(())
    }
}

pub struct Reader {
    file: File,
    entries: Vec<Entry>,
    generation: [u8; 8],
}
impl Reader {
    pub fn open(path: &Path, metadata: &[u8]) -> io::Result<Self> {
        if metadata.len() > MAX_METADATA_BYTES {
            return Err(invalid("metadata limit"));
        }
        let mut file = File::open(path)?;
        let length = file.metadata()?.len();
        if !(HEADER_BYTES as u64..=MAX_FILE_BYTES).contains(&length) {
            return Err(invalid("file length"));
        }
        let mut header = [0; HEADER_BYTES];
        file.read_exact(&mut header)?;
        let crc = u32_at(&header, 56);
        put32(&mut header, 56, 0);
        if hash(&header) != crc
            || &header[..8] != MAGIC
            || u32_at(&header, 8) != VERSION
            || u32_at(&header, 12) != HEADER_BYTES as u32
            || u32_at(&header, 16) != 1
            || u32_at(&header, 28) != ENTRY_BYTES as u32
            || u64_at(&header, 32) != length
            || u32_at(&header, 60) != 0
        {
            return Err(invalid("header"));
        }
        let chapters = u32_at(&header, 20) as usize;
        let meta_length = u32_at(&header, 24) as usize;
        if chapters > MAX_CHAPTERS
            || meta_length != metadata.len()
            || u64_at(&header, 40) > MAX_EXPANDED_BYTES
        {
            return Err(invalid("header limits"));
        }
        let index_length = (chapters + PREFIX_BLOCKS) * ENTRY_BYTES;
        let mut offset = HEADER_BYTES as u64 + meta_length as u64 + index_length as u64;
        if offset > length {
            return Err(invalid("index bounds"));
        }
        let mut actual_metadata = buffer(meta_length)?;
        file.read_exact(&mut actual_metadata)?;
        if actual_metadata != metadata || hash(&actual_metadata) != u32_at(&header, 48) {
            return Err(invalid("source identity"));
        }
        let mut index = buffer(index_length)?;
        file.read_exact(&mut index)?;
        let index_crc = u32_at(&header, 52);
        if hash(&index) != index_crc {
            return Err(invalid("index CRC"));
        }
        let mut entries = Vec::with_capacity(chapters + PREFIX_BLOCKS);
        let mut flat = 0u64;
        let mut expanded = 0u64;
        for (i, bytes) in index.chunks_exact(ENTRY_BYTES).enumerate() {
            let entry = Entry::decode(bytes)?;
            let end = add(entry.offset, entry.compressed)?;
            if entry.offset != offset
                || entry.compressed == 0
                || end > length
                || entry.expanded > MAX_BLOCK_BYTES
                || (i == 0 && entry.expanded > MAX_INDEX_BYTES)
                || entry.compressed > MAX_BLOCK_BYTES + MAX_BLOCK_BYTES / 255 + 16
                || entry.start != flat
                || entry.elements > i32::MAX as u64
                || (i < PREFIX_BLOCKS && entry.elements != 0)
            {
                return Err(invalid("block bounds/coordinates"));
            }
            flat = add(flat, entry.elements)?;
            if flat > i32::MAX as u64 {
                return Err(invalid("element total"));
            }
            expanded = add(expanded, entry.expanded)?;
            if expanded > MAX_EXPANDED_BYTES {
                return Err(invalid("expanded total"));
            }
            offset = end;
            entries.push(entry);
        }
        if offset != length || expanded != u64_at(&header, 40) {
            return Err(invalid("trailing data/expanded total"));
        }
        let mut generation = [0; 8];
        put32(&mut generation, 0, crc);
        put32(&mut generation, 4, index_crc);
        Ok(Self {
            file,
            entries,
            generation,
        })
    }
    pub fn generation(&self) -> [u8; 8] {
        self.generation
    }
    pub fn index(&self) -> Vec<i64> {
        self.entries
            .iter()
            .skip(PREFIX_BLOCKS)
            .flat_map(|entry| {
                [
                    entry.offset as i64,
                    entry.compressed as i64,
                    entry.expanded as i64,
                    entry.elements as i64,
                    entry.start as i64,
                ]
            })
            .collect()
    }
    pub fn read_block_bytes(&mut self, block: usize) -> io::Result<Vec<u8>> {
        let entry = self
            .entries
            .get(block)
            .ok_or_else(|| invalid("block index"))?;
        self.file.seek(SeekFrom::Start(entry.offset))?;
        let mut compressed = buffer(entry.compressed as usize)?;
        self.file.read_exact(&mut compressed)?;
        let mut raw = buffer(entry.expanded as usize)?;
        let decoded = lz4_flex::block::decompress_into(&compressed, &mut raw)
            .map_err(|_| invalid("invalid LZ4"))?;
        if decoded != raw.len() || hash(&raw) != entry.crc {
            return Err(invalid("block length/CRC"));
        }
        Ok(raw)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct Directory(PathBuf);
    impl Directory {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "frb-format-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
        fn file(&self, name: &str) -> PathBuf {
            self.0.join(name)
        }
        fn input(&self, name: &str, bytes: &[u8]) -> PathBuf {
            let path = self.file(name);
            fs::write(&path, bytes).unwrap();
            path
        }
        fn complete(
            &self,
            name: &str,
            metadata: &[u8],
            values: &[&[u8]],
            counts: &[i32],
        ) -> PathBuf {
            let path = self.file(name);
            let mut writer = Writer::begin(&path, metadata, counts, MAX_FILE_BYTES).unwrap();
            for (i, bytes) in values.iter().enumerate() {
                writer
                    .write_block(i, &self.input(&format!("input-{name}-{i}"), bytes))
                    .unwrap();
            }
            writer.finish().unwrap();
            path
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn fix_crcs(bytes: &mut [u8]) {
        let offset = HEADER_BYTES + u32_at(bytes, 24) as usize;
        let len = (u32_at(bytes, 20) as usize + PREFIX_BLOCKS) * ENTRY_BYTES;
        put32(bytes, 52, hash(&bytes[offset..offset + len]));
        put32(bytes, 56, 0);
        let crc = hash(&bytes[..HEADER_BYTES]);
        put32(bytes, 56, crc);
    }
    fn fixture(dir: &Directory) -> PathBuf {
        dir.complete(
            "book.frb",
            b"source-hash",
            &[
                b"{book index}",
                b"{globals}",
                b"chapter1\0\xff",
                b"chapter2",
            ],
            &[3, 5],
        )
    }

    #[test]
    fn chapters_are_independent_and_coordinates_exact() {
        let dir = Directory::new();
        let file = fixture(&dir);
        let mut reader = Reader::open(&file, b"source-hash").unwrap();
        let index = reader.index();
        assert_eq!(index.len(), 10);
        assert_eq!(&index[3..5], &[3, 0]);
        assert_eq!(&index[8..10], &[5, 3]);
        assert!(index[5] >= index[0] + index[1]);
        assert_eq!(reader.read_block_bytes(3).unwrap(), b"chapter2");
        assert_eq!(reader.read_block_bytes(2).unwrap(), b"chapter1\0\xff");
        assert_eq!(reader.read_block_bytes(1).unwrap(), b"{globals}");
        assert_eq!(reader.read_block_bytes(0).unwrap(), b"{book index}");
        assert!(reader.read_block_bytes(4).is_err());
    }

    #[test]
    fn empty_publication_and_zero_element_chapters_remain_distinct() {
        let dir = Directory::new();
        let file = dir.complete("empty.frb", b"", &[b"", b""], &[]);
        let mut reader = Reader::open(&file, b"").unwrap();
        assert!(reader.index().is_empty());
        assert!(reader.read_block_bytes(0).unwrap().is_empty());
        let file = dir.complete("zeros.frb", b"id", &[b"{}", b"{}", b"[]", b"[]"], &[0, 0]);
        let index = Reader::open(&file, b"id").unwrap().index();
        assert_eq!([index[3], index[4], index[8], index[9]], [0, 0, 0, 0]);
    }

    #[test]
    fn all_truncation_boundaries_are_rejected() {
        let dir = Directory::new();
        let file = fixture(&dir);
        let bytes = fs::read(file).unwrap();
        let corrupt = dir.file("truncated.frb");
        for length in 0..bytes.len() {
            fs::write(&corrupt, &bytes[..length]).unwrap();
            assert!(
                Reader::open(&corrupt, b"source-hash").is_err(),
                "accepted truncation at {length}"
            );
        }
        let mut trailing = bytes;
        trailing.push(0);
        fs::write(&corrupt, trailing).unwrap();
        assert!(Reader::open(&corrupt, b"source-hash").is_err());
    }

    #[test]
    fn header_metadata_and_index_corruption_are_rejected() {
        let dir = Directory::new();
        let file = fixture(&dir);
        let bytes = fs::read(file).unwrap();
        let end = HEADER_BYTES + b"source-hash".len() + 4 * ENTRY_BYTES;
        for i in 0..end {
            let mut corrupt = bytes.clone();
            corrupt[i] ^= 1;
            let file = dir.input("corrupt.frb", &corrupt);
            assert!(
                Reader::open(&file, b"source-hash").is_err(),
                "accepted metadata corruption at {i}"
            );
        }
    }

    #[test]
    fn source_identity_is_compared_exactly() {
        let dir = Directory::new();
        let file = fixture(&dir);
        assert!(Reader::open(&file, b"source-HASH").is_err());
        assert!(Reader::open(&file, b"source-hash-extra").is_err());
    }

    #[test]
    fn incomplete_and_out_of_order_writes_never_open_as_complete() {
        let dir = Directory::new();
        let path = dir.file("incomplete.frb");
        let input = dir.input("chapter.json", b"{}");
        let mut writer = Writer::begin(&path, b"id", &[1], MAX_FILE_BYTES).unwrap();
        assert!(Reader::open(&path, b"id").is_err());
        assert!(writer.finish().is_err());
        assert!(writer.write_block(1, &input).is_err());
        writer.write_block(0, &input).unwrap();
        assert!(writer.write_block(0, &input).is_err());
        assert!(writer.finish().is_err());
        writer.write_block(1, &input).unwrap();
        assert!(writer.finish().is_err());
        writer.write_block(2, &input).unwrap();
        writer.finish().unwrap();
        assert!(writer.finish().is_err());
        assert!(writer.write_block(1, &input).is_err());
        assert!(Reader::open(&path, b"id").is_ok());
    }

    #[test]
    fn existing_destination_is_never_truncated() {
        let dir = Directory::new();
        let existing = dir.input("existing", b"retain me");
        assert!(Writer::begin(&existing, b"id", &[], MAX_FILE_BYTES).is_err());
        assert_eq!(fs::read(existing).unwrap(), b"retain me");
    }

    #[test]
    fn limits_reject_before_large_allocation_or_publishing() {
        let dir = Directory::new();
        let target = dir.file("limit.frb");
        assert!(Writer::begin(&target, b"", &[-1], MAX_FILE_BYTES).is_err());
        assert!(Writer::begin(&target, b"", &[i32::MAX, 1], MAX_FILE_BYTES).is_err());
        assert!(Writer::begin(&target, b"", &vec![0; MAX_CHAPTERS + 1], MAX_FILE_BYTES).is_err());
        assert!(Writer::begin(
            &target,
            &vec![0; MAX_METADATA_BYTES + 1],
            &[],
            MAX_FILE_BYTES
        )
        .is_err());
        assert!(Writer::begin(&target, b"", &[], MAX_FILE_BYTES + 1).is_err());
        assert!(Writer::begin(&target, b"", &[], HEADER_BYTES as u64).is_err());
        assert!(!target.exists());
        let sparse = File::create(dir.file("large.json")).unwrap();
        sparse.set_len(MAX_BLOCK_BYTES + 1).unwrap();
        let mut writer = Writer::begin(&target, b"", &[], MAX_FILE_BYTES).unwrap();
        assert!(writer.write_block(0, &dir.file("large.json")).is_err());
        assert!(writer.finish().is_err());
        assert!(Reader::open(&target, b"").is_err());
        let manifest = dir.file("oversized-index.json");
        File::create(&manifest)
            .unwrap()
            .set_len(MAX_INDEX_BYTES + 1)
            .unwrap();
        let mut writer =
            Writer::begin(&dir.file("manifest.frb"), b"", &[], MAX_FILE_BYTES).unwrap();
        assert!(writer.write_block(0, &manifest).is_err());
        assert!(writer.finish().is_err());
        let path = dir.file("budget.frb");
        let mut writer = Writer::begin(&path, b"", &[], 1024).unwrap();
        let incompressible: Vec<u8> = (0u32..4096)
            .flat_map(|n| n.wrapping_mul(2654435761).to_le_bytes())
            .collect();
        assert!(writer
            .write_block(0, &dir.input("random.json", &incompressible))
            .is_err());
        assert!(writer.finish().is_err());
        assert!(fs::metadata(path).unwrap().len() <= 1024);
    }

    #[test]
    fn hostile_checked_index_fields_fail_even_with_valid_crcs() {
        let dir = Directory::new();
        let bytes = fs::read(fixture(&dir)).unwrap();
        let index = HEADER_BYTES + b"source-hash".len();
        // Bad position, length, decoded bound, count, cumulative offset, reserved bits.
        for (position, value) in [
            (index, 0),
            (index, u64::MAX),
            (index + 8, u64::MAX),
            (index + 16, MAX_BLOCK_BYTES + 1),
            (index + 16, MAX_INDEX_BYTES + 1),
            (index + 24, 1),
            (index + ENTRY_BYTES + 24, 1),
            (index + ENTRY_BYTES + 24, i32::MAX as u64),
            (index + ENTRY_BYTES + 32, 1),
            (index + PREFIX_BLOCKS * ENTRY_BYTES + 32, 1),
            (
                index + (PREFIX_BLOCKS + 1) * ENTRY_BYTES + 24,
                i32::MAX as u64,
            ),
        ] {
            let mut corrupt = bytes.clone();
            put64(&mut corrupt, position, value);
            fix_crcs(&mut corrupt);
            assert!(
                Reader::open(&dir.input("index.frb", &corrupt), b"source-hash").is_err(),
                "accepted field {position}"
            );
        }
        let mut overlap = bytes.clone();
        let first_offset = u64_at(&overlap, index);
        put64(&mut overlap, index + ENTRY_BYTES, first_offset);
        fix_crcs(&mut overlap);
        assert!(Reader::open(&dir.input("overlap.frb", &overlap), b"source-hash").is_err());
        let mut reserved = bytes.clone();
        put32(&mut reserved, index + 44, 1);
        fix_crcs(&mut reserved);
        assert!(Reader::open(&dir.input("reserved.frb", &reserved), b"source-hash").is_err());
        for (position, value) in [
            (8, 1),
            (16, 0),
            (20, (MAX_CHAPTERS + 1) as u32),
            (28, 0),
            (60, 1),
        ] {
            let mut corrupt = bytes.clone();
            put32(&mut corrupt, position, value);
            // Header count controls index size: only fix header after modifying it.
            put32(&mut corrupt, 56, 0);
            let crc = hash(&corrupt[..HEADER_BYTES]);
            put32(&mut corrupt, 56, crc);
            assert!(Reader::open(&dir.input("header.frb", &corrupt), b"source-hash").is_err());
        }
        let mut decoded = bytes;
        put64(&mut decoded, 40, MAX_EXPANDED_BYTES + 1);
        fix_crcs(&mut decoded);
        assert!(Reader::open(&dir.input("expanded.frb", &decoded), b"source-hash").is_err());
    }

    #[test]
    fn corrupt_payload_or_crc_never_returns_decoded_bytes() {
        let dir = Directory::new();
        let bytes = fs::read(fixture(&dir)).unwrap();
        let index = HEADER_BYTES + b"source-hash".len();
        let offset = u64_at(&bytes, index) as usize;
        let mut bad_lz4 = bytes.clone();
        bad_lz4[offset..offset + 3].fill(0);
        let mut reader = Reader::open(&dir.input("bad-lz4.frb", &bad_lz4), b"source-hash").unwrap();
        assert!(reader.read_block_bytes(0).is_err());
        let mut bad_crc = bytes;
        let crc = u32_at(&bad_crc, index + 40);
        put32(&mut bad_crc, index + 40, crc ^ 1);
        fix_crcs(&mut bad_crc);
        let mut reader = Reader::open(&dir.input("bad-crc.frb", &bad_crc), b"source-hash").unwrap();
        assert!(reader.read_block_bytes(0).is_err());
    }

    #[test]
    fn corrupt_unrequested_chapter_does_not_block_index_or_distant_chapter() {
        let dir = Directory::new();
        let bytes = fs::read(fixture(&dir)).unwrap();
        let entries = HEADER_BYTES + b"source-hash".len();
        let first = entries + PREFIX_BLOCKS * ENTRY_BYTES;
        let offset = u64_at(&bytes, first) as usize;
        let mut corrupted = bytes;
        // Change only the first chapter's payload, preserving the checked
        // container framing. Opening must not inflate it as a side effect.
        corrupted[offset] ^= 0xff;
        let path = dir.input("one-corrupt-chapter.frb", &corrupted);
        let mut reader = Reader::open(&path, b"source-hash").unwrap();
        assert_eq!(reader.index()[3..5], [3, 0]);
        assert_eq!(reader.index()[8..10], [5, 3]);
        assert_eq!(reader.read_block_bytes(0).unwrap(), b"{book index}");
        assert_eq!(reader.read_block_bytes(1).unwrap(), b"{globals}");
        assert_eq!(reader.read_block_bytes(3).unwrap(), b"chapter2");
        assert!(reader.read_block_bytes(2).is_err());
    }

    #[test]
    fn light_index_does_not_require_decoding_heavy_global_tables() {
        let dir = Directory::new();
        let mut bytes = fs::read(fixture(&dir)).unwrap();
        let globals = HEADER_BYTES + b"source-hash".len() + ENTRY_BYTES;
        let offset = u64_at(&bytes, globals) as usize;
        bytes[offset] ^= 0xff;
        let mut reader =
            Reader::open(&dir.input("bad-heavy-globals.frb", &bytes), b"source-hash").unwrap();
        assert_eq!(reader.index().len(), 10);
        assert_eq!(reader.read_block_bytes(0).unwrap(), b"{book index}");
        assert_eq!(reader.read_block_bytes(3).unwrap(), b"chapter2");
        assert!(reader.read_block_bytes(1).is_err());
    }

    #[test]
    fn active_reader_retains_its_inode_after_atomic_replacement() {
        let dir = Directory::new();
        let path = dir.complete(
            "current.frb",
            b"id",
            &[b"old index", b"old globals", b"old chapter"],
            &[3],
        );
        let mut old = Reader::open(&path, b"id").unwrap();
        let old_generation = old.generation();
        let replacement = dir.complete(
            "replacement.frb",
            b"id",
            &[b"old index", b"old globals", b"new chapter"],
            &[3],
        );
        fs::rename(replacement, &path).unwrap();
        assert_eq!(old.read_block_bytes(2).unwrap(), b"old chapter");
        assert_eq!(old.index()[3], 3);
        let mut new = Reader::open(&path, b"id").unwrap();
        assert_eq!(new.read_block_bytes(2).unwrap(), b"new chapter");
        assert_eq!(new.index(), old.index());
        assert_eq!(old.generation(), old_generation);
        assert_ne!(new.generation(), old_generation);
    }

    #[test]
    fn write_block_bytes_matches_file_blocks_and_enforces_limits() {
        let dir = Directory::new();
        let file_based = fixture(&dir);
        let in_memory = dir.file("in-memory.frb");
        let payloads: [&[u8]; 4] = [
            b"{book index}",
            b"{globals}",
            b"chapter1\0\xff",
            b"chapter2",
        ];
        let mut writer =
            Writer::begin(&in_memory, b"source-hash", &[3, 5], MAX_FILE_BYTES).unwrap();
        // Out-of-order block must fail without changing writer state.
        assert!(writer.write_block_bytes(1, b"{globals}").is_err());
        for (i, payload) in payloads.iter().enumerate() {
            writer.write_block_bytes(i, payload).unwrap();
        }
        // Extra block past counts.len() + PREFIX_BLOCKS must fail.
        assert!(writer.write_block_bytes(4, b"extra").is_err());
        writer.finish().unwrap();
        assert!(writer.write_block_bytes(0, b"after").is_err());

        assert_eq!(
            fs::read(&file_based).unwrap(),
            fs::read(&in_memory).unwrap()
        );

        let mut reader = Reader::open(&in_memory, b"source-hash").unwrap();
        assert_eq!(
            reader.index(),
            Reader::open(&file_based, b"source-hash").unwrap().index()
        );
        for (i, payload) in payloads.iter().enumerate() {
            assert_eq!(reader.read_block_bytes(i).unwrap(), *payload);
        }

        // Block 0 larger than MAX_INDEX_BYTES is rejected.
        let mut idx_limit_writer =
            Writer::begin(&dir.file("idx-limit.frb"), b"", &[], MAX_FILE_BYTES).unwrap();
        let oversized_idx = vec![b'a'; (MAX_INDEX_BYTES as usize) + 1];
        assert!(idx_limit_writer
            .write_block_bytes(0, &oversized_idx)
            .is_err());
        assert!(idx_limit_writer.finish().is_err());

        // Compressed file budget enforcement matches write_block.
        let mut budget_writer = Writer::begin(&dir.file("budget-mem.frb"), b"", &[], 1024).unwrap();
        let incompressible: Vec<u8> = (0u32..4096)
            .flat_map(|n| n.wrapping_mul(2654435761).to_le_bytes())
            .collect();
        assert!(budget_writer.write_block_bytes(0, &incompressible).is_err());
        assert!(budget_writer.finish().is_err());
    }

    #[test]
    fn write_precompressed_block_matches_write_block_bytes_and_enforces_limits() {
        let dir = Directory::new();
        let reference = fixture(&dir);
        let precompressed_path = dir.file("precompressed.frb");
        let payloads: [&[u8]; 4] = [
            b"{book index}",
            b"{globals}",
            b"chapter1\0\xff",
            b"chapter2",
        ];

        let mut writer =
            Writer::begin(&precompressed_path, b"source-hash", &[3, 5], MAX_FILE_BYTES).unwrap();
        // Out-of-order and empty compressed payload must be rejected cleanly.
        assert!(writer
            .write_precompressed_block(
                1,
                &lz4_flex::block::compress(payloads[1]),
                9,
                hash(payloads[1])
            )
            .is_err());
        assert!(writer.write_precompressed_block(0, &[], 0, 0).is_err());
        assert!(writer
            .write_precompressed_block(0, &[0], MAX_INDEX_BYTES + 1, 0)
            .is_err());

        for (i, payload) in payloads.iter().enumerate() {
            let compressed = lz4_flex::block::compress(payload);
            writer
                .write_precompressed_block(i, &compressed, payload.len() as u64, hash(payload))
                .unwrap();
        }
        assert!(writer
            .write_precompressed_block(4, &lz4_flex::block::compress(b"x"), 1, hash(b"x"))
            .is_err());
        writer.finish().unwrap();
        assert!(writer
            .write_precompressed_block(0, &lz4_flex::block::compress(b"x"), 1, hash(b"x"))
            .is_err());

        // Container bytes must be 100% bit-for-bit identical to file/in-memory writers.
        assert_eq!(
            fs::read(&reference).unwrap(),
            fs::read(&precompressed_path).unwrap()
        );

        let mut reader = Reader::open(&precompressed_path, b"source-hash").unwrap();
        for (i, payload) in payloads.iter().enumerate() {
            assert_eq!(reader.read_block_bytes(i).unwrap(), *payload);
        }

        // Enforce MAX_BLOCK_BYTES, MAX_EXPANDED_BYTES, and max_file budget.
        let mut block_limit =
            Writer::begin(&dir.file("pre-block-limit.frb"), b"", &[1], MAX_FILE_BYTES).unwrap();
        let tiny = lz4_flex::block::compress(b"{}");
        block_limit
            .write_precompressed_block(0, &tiny, 2, hash(b"{}"))
            .unwrap();
        block_limit
            .write_precompressed_block(1, &tiny, 2, hash(b"{}"))
            .unwrap();
        assert!(block_limit
            .write_precompressed_block(2, &tiny, MAX_BLOCK_BYTES + 1, 0)
            .is_err());

        let mut budget = Writer::begin(&dir.file("pre-budget.frb"), b"", &[], 512).unwrap();
        let big = vec![0xAA; 600];
        assert!(budget.write_precompressed_block(0, &big, 600, 0).is_err());
        assert!(budget.finish().is_err());
    }
}
