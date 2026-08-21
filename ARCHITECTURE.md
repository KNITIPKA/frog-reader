# FrogReader Architecture Guide 🐸📖

## 1. System Overview

FrogReader is an offline-first, native Android e-book reader engineered for high-fidelity typography, split atomic persistence, and robust format parsing. It is written in **Kotlin 2.x** and **Jetpack Compose (Material 3 Expressive)**.

```
┌─────────────────────────────────────────────────────────────┐
│                       Compose UI Layer                      │
│   LibraryScreen   │   ReaderScreen   │   SettingsScreen     │
└──────────────┬──────────────────┬─────────────────┬─────────┘
               │                  │                 │
┌──────────────▼──────────────────▼─────────────────▼─────────┐
│                       ViewModel Layer                       │
│    LibraryViewModel    │    ReaderViewModel   │   Settings  │
└──────────────┬──────────────────┬───────────────────────────┘
               │                  │
┌──────────────▼──────────────────▼───────────────────────────┐
│                      Repository Layer                       │
│      BookRepository     │  SettingsRepo  │   StatsRepo      │
│   (Diffing & Routing)   │   (DataStore)  │   (Reading)      │
└──────────────┬──────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────────┐
│                    Persistence & Engine                     │
│  AtomicJsonFile (library.json, userdata.json, progress.json)│
│  Format Parsers (EPUB, FB2, MOBI/KF8) & Typography (Brotli) │
│  Backup Engine (BackupArchive, SafFolderTarget, WorkManager)│
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Platform & SDK Compatibility

- **Minimum SDK (`minSdk`)**: 26 (Android 8.0 Oreo). Ensures compatibility with >98% of active Android devices worldwide.
- **Target SDK (`targetSdk`)**: 36.
- **Compile SDK (`compileSdk`)**: 37 (release version).
- **Compatibility Invariant**: API 28+ and API 29+ platform features (e.g. `BiometricPrompt`, `BiometricManager`) must always be guarded with runtime checks (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` or `VERSION_CODES.P`). Never raise `minSdk` without explicit justification.

---

## 3. Data Persistence & Storage Architecture

Data is cleanly separated into three JSON documents in internal storage (`context.filesDir`), partitioned by owner and mutation frequency:

| Document | Purpose | Mutated When | Safety Invariant |
|---|---|---|---|
| `library.json` | Book metadata derived by parsing (titles, authors, formats, covers, word counts, shelves). | On import, edit, or shelving. | Can be reconstructed by re-parsing book files if lost. |
| `userdata.json` | Irreplaceable user creations: quotes, bookmarks, notes, star ratings, reviews, and per-book reader settings. | When the user creates notes, bookmarks, or changes ratings. | **Irreplaceable**. Must never be overwritten by metadata refreshes or high-frequency updates. |
| `progress.json` | Current reading position (page index, char offset, percentage). | On every settled page flip. | Separated from `userdata.json` so high-frequency writes never risk user notes or bookmarks. |

### 3.1 Atomic File Storage (`AtomicJsonFile`)
All file-based persistence routes through `AtomicJsonFile.kt`:
1. Write to `.tmp` file.
2. Flush to physical storage via `FileOutputStream.fd.sync()`.
3. Backup existing target to `.bak`.
4. Atomically rename `.tmp` to target.
5. Automatic fallback to `.bak` if primary file decode fails. Never write a store with a bare `writeText`.

### 3.2 Unified `BookRepository` Diffing
- `BookRepository` presents cohesive `Book` domain models to the UI.
- On save/update, `BookRepository` computes a diff between the old and new `Book` objects and automatically routes changes to `library.json`, `userdata.json`, and/or `progress.json`. Callers must not have to declare what they changed.
- Stamps `updatedAtMillis` and maintains deletion tombstones for future device synchronization.

### 3.3 Model Serialization & R8
- Serialization uses `kotlinx.serialization`.
- **Backward Compatibility Rule**: Any newly added field on `@Serializable` data classes must define a default value to prevent deserialization crashes with existing stored data.
- Domain models in `com.example.frogreader.data.model` are preserved in release builds by `app/src/main/keepRules/rules.keep`.

---

## 4. Reading, Parsing & Typography Engine

### 4.1 Normalized Document Model (`BookContent`)
All supported book formats parse into a shared, reflowable document tree (`BookContent`):
- `Chapter`: Depth hierarchy, title, elements.
- `ContentElement`: `Paragraph` (rich `AnnotatedString`), `Heading`, `Image` (block & inline), `Table`, `Spacer`.
- `Note`: Semantic footnotes and popups.

### 4.2 Supported Formats & Parsers
- **EPUB 2 / EPUB 3 (`EpubParser.kt`)**: OPF manifest, spine navigation, NCX / NAV table of contents, CSS stylesheets with recursive `@import`, and standalone SVG spine pages.
- **FB2 2.x (`Fb2Parser.kt`)**: XML pull parsing with an automated repair pass for unescaped entities (`&nbsp;`, `&mdash;`, bare `&`). Supports poems, stanzas, epigraphs, text-authors, and inline base64 images.
- **MOBI / KF8 / PalmDoc (`MobiParser.kt`, `Kf8Assembler.kt`)**: PalmDoc LZ77 decompression, PDB record extraction, MOBI header decoding, Huffman/CDIC decompression, and KF8 skeleton/fragment assembly.

### 4.3 Typography, Fonts & Brotli WOFF2
- **WOFF2 Font Decompression (`Woff2Decoder.kt`)**: Decompresses WOFF2 font tables into standard OpenType/TrueType bytes using Google's pure-Java Brotli decoder (`org.brotli:dec:0.1.2`).
- **WOFF Font Decompression (`WoffDecoder.kt`)**: Decompresses WOFF tables via zlib.
- **Font Obfuscation (`FontObfuscation.kt`)**: De-obfuscates Adobe and IDPF XOR mask byte transformations.
- **Font Loading Resilience**: Native font instantiation uses `Typeface.Builder` wrapped in `runCatching` with fallback to `FontFamily.Serif` or bundled `LiterataFamily` to prevent system crashes on customized Android 8.0 ROMs.
- **CSS Engine (`CssResolver.kt`, `CssCalc.kt`)**: Full support for CSS inheritance, specificity, relative units (`em`, `rem`, `%`), list markers, `calc()`, drop caps (`::first-letter`), and media query filtering (`screen` vs `print`).

### 4.4 Pagination Engine (`Pagination.kt`)
- Background page calculation using Compose `TextMeasurer` on `Dispatchers.Default`.
- `PaginationCache.kt` caches calculated page slices per book and layout dimension for instant page turns and responsive orientation changes.
- Cross-page text selection, gestures, handles, and floating action pill.

---

## 5. Backup & Disaster Recovery (`data/backup/`)

- **Independent Archive Format (`BackupArchive.kt`)**:
  - Encapsulated `.zip` archive containing `BackupManifest` and serialized `Book` models.
  - No Android framework types, enabling 100% JVM unit testability.
  - Decoupled from on-disk JSON file layouts: future storage refactorings do not break existing backup archives. Bump `BackupManifest.FORMAT_VERSION` only for breaking changes older readers could not survive.
- **Storage Targets (`BackupTarget.kt`)**:
  - `SafDocumentTarget`: Single picked file.
  - `SafFolderTarget`: Folder target supporting local storage or cloud storage providers (Google Drive, Dropbox, Nextcloud).
- **Automated Scheduled Backups (`ScheduledBackup.kt`)**:
  - Background WorkManager periodic worker with automated 5-snapshot rotation (`BackupRotationTest`).
