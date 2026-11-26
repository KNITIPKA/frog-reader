use super::{EntryError, EpubZip, OpenError, MAX_ENTRY_BYTES};
use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

struct Scratch(PathBuf);

impl Scratch {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "frogreader-epub-zip-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn input(&self, name: &str, bytes: &[u8]) -> PathBuf {
        let path = self.0.join(name);
        fs::write(&path, bytes).unwrap();
        path
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn streamed_epub() -> Vec<u8> {
    let mut zip = ZipWriter::new_stream(Vec::new());
    let stored = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    let deflated = stored.compression_method(CompressionMethod::Deflated);
    zip.start_file("mimetype", stored).unwrap();
    zip.write_all(b"application/epub+zip").unwrap();
    zip.add_directory("OPS/", stored).unwrap();
    zip.start_file("OPS/פרק.xhtml", deflated).unwrap();
    zip.write_all("<p>שלום</p>".as_bytes()).unwrap();
    zip.finish().unwrap().into_inner()
}

#[test]
fn reads_streamed_stored_and_deflated_members_in_central_directory_order() {
    let scratch = Scratch::new();
    let mut zip = EpubZip::open(&scratch.input("streamed.epub", &streamed_epub()), 3, 35).unwrap();
    let mut stored_entry = Vec::from(3u32.to_le_bytes());
    stored_entry.extend_from_slice(&8u32.to_le_bytes());
    stored_entry.extend_from_slice(b"mimetype");
    stored_entry.extend_from_slice(&20u64.to_le_bytes());
    stored_entry.extend_from_slice(&20u64.to_le_bytes());
    stored_entry.push(0);
    assert!(zip.directory.starts_with(&stored_entry));
    assert_eq!(
        zip.read(0).ok().as_deref(),
        Some(&b"application/epub+zip"[..])
    );
    assert!(matches!(zip.read(1), Err(EntryError::Input)));
    assert!(matches!(zip.read(3), Err(EntryError::Input)));

    let output = scratch.input("existing.tmp", b"leftover bytes from an earlier operation");
    assert!(zip.copy(2, &output).is_ok());
    assert_eq!(fs::read(&output).unwrap(), "<p>שלום</p>".as_bytes());
    assert!(matches!(
        zip.copy(2, &scratch.0.join("missing.tmp")),
        Err(EntryError::Output)
    ));
}

#[test]
fn enforces_archive_count_declared_total_and_native_entry_ceiling() {
    let scratch = Scratch::new();
    let mut data = streamed_epub();
    let path = scratch.input("streamed.epub", &data);
    assert!(matches!(
        EpubZip::open(&path, 2, 35),
        Err(OpenError::EntryCount)
    ));
    assert!(matches!(
        EpubZip::open(&path, 3, 34),
        Err(OpenError::DeclaredBytes)
    ));

    // Central directory size at offset 24 in the first member's header.
    let central = data
        .windows(4)
        .position(|bytes| bytes == b"PK\x01\x02")
        .unwrap();
    data[central + 24..central + 28].copy_from_slice(&(MAX_ENTRY_BYTES as u32 + 1).to_le_bytes());
    let path = scratch.input("oversize.epub", &data);
    let mut zip = EpubZip::open(&path, 3, u64::MAX).unwrap();
    assert!(matches!(zip.read(0), Err(EntryError::Input)));
}

#[test]
fn rejects_damaged_members_and_keeps_the_rest_of_the_archive_readable() {
    let scratch = Scratch::new();
    let mut data = streamed_epub();
    let mime = b"application/epub+zip";
    let payload = data
        .windows(mime.len())
        .position(|bytes| bytes == mime)
        .unwrap();
    data[payload] = b'X';
    let mut zip = EpubZip::open(&scratch.input("bad-crc.epub", &data), 3, 35).unwrap();
    assert!(matches!(zip.read(0), Err(EntryError::Input)));
    let output = scratch.input("partial.tmp", b"");
    assert!(matches!(zip.copy(0, &output), Err(EntryError::Input)));
    assert_eq!(zip.read(2).ok().as_deref(), Some("<p>שלום</p>".as_bytes()));
}

#[test]
fn rejects_duplicate_member_names_before_indexing_entries() {
    let scratch = Scratch::new();
    let mut zip = ZipWriter::new_stream(Vec::new());
    zip.start_file("a.xhtml", SimpleFileOptions::default())
        .unwrap();
    zip.start_file("b.xhtml", SimpleFileOptions::default())
        .unwrap();
    let mut data = zip.finish().unwrap().into_inner();
    // Change both local and central names without invalidating header offsets.
    let offsets: Vec<_> = data
        .windows(7)
        .enumerate()
        .filter_map(|(index, bytes)| (bytes == b"b.xhtml").then_some(index))
        .collect();
    assert_eq!(offsets.len(), 2);
    for index in offsets {
        data[index] = b'a';
    }
    let path = scratch.input("duplicate.epub", &data);
    assert!(matches!(
        EpubZip::open(&path, 2, 100),
        Err(OpenError::Archive)
    ));
}
