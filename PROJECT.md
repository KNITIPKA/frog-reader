# Project: FrogReader E-Book Reader Engine & Application

## Architecture & Tech Stack
Android native e-book reader built with **Kotlin 2.x** and **Jetpack Compose (Material 3 Expressive)**.

- **SDK Configuration**:
  - `minSdk`: 26 (Android 8.0 Oreo, covers >98% of active devices worldwide)
  - `targetSdk`: 36
  - `compileSdk`: 37 (release version)
- **Data Persistence**: three JSON documents, split by who owns the data.
  - `library.json` — book metadata the app derived by parsing the file. Written on import, edit or shelving.
  - `userdata.json` — what the user made: quotes, bookmarks, notes, ratings, reviews, statuses, per-book reader settings. The only one that cannot be reconstructed.
  - `progress.json` — reading positions, written on every settled page turn. Kept apart so constant writing never touches the irreplaceable document.
  - `AtomicJsonFile.kt`: the shared write path — `.tmp` -> fsync -> `.bak` -> atomic rename, with `.bak` recovery on read. Used by every store.
  - `BookRepository.kt`: assembles whole `Book` objects from the three documents and routes writes back by diffing before/after, so callers keep working in whole books. Also stamps `updatedAtMillis` and records deletion tombstones — unused today, groundwork for syncing two devices.
  - `SettingsRepository.kt`: DataStore Preferences for user settings.
  - `StatsRepository.kt`: Reading time tracking & 100-day streaks using `java.time.LocalDate`.
- **Backup** (`data/backup/`):
  - `BackupArchive.kt`: the zip format. No Android types, so the round trip is JVM-testable.
  - `BackupTarget.kt`: where the bytes go — `SafDocumentTarget` (a picked file), `SafFolderTarget` (a picked folder, which may live in Google Drive/Dropbox). A Drive API target could be added without touching the format.
  - `ScheduledBackup.kt`: WorkManager periodic job, data-only, with snapshot rotation.
  - A backup holds whole `Book` objects, deliberately unlike the on-disk layout, so storage changes never invalidate backups already written.
- **Custom Reading & Typography Engine**:
  - **EPUB Parser** (`EpubParser.kt`): OPF manifest, NCX, nav TOC, XHTML mapping.
  - **FB2 Parser** (`Fb2Parser.kt`): XML stanzas, epigraphs, sub-titles, inline images.
  - **MOBI / KF8 / PDB Engine** (`MobiParser.kt`): PalmDoc LZ77, PDB record parsing, MOBI headers, KF8 sections.
  - **WOFF/WOFF2 Decoder** (`Woff2Decoder.kt`, `WoffDecoder.kt`): Brotli decompression to TTF/OTF. `Typeface.Builder` for native font instantiation with `runCatching` fallback to `Literata` / `Serif`.
  - **Font De-obfuscation** (`FontObfuscation.kt`): IDPF & Adobe XOR mask byte transformations.
  - **CSS Engine** (`CssResolver.kt`, `CssCalc.kt`): Cascade rules, relative units (`em`, `rem`, `%`), list markers, `calc()`.
  - **Pagination** (`Pagination.kt`): `TextMeasurer` background pagination on `Dispatchers.Default` with `PaginationCache`.
- **UI Layer**:
  - **Material 3 Expressive** (`1.5.0-alpha22`): `MaterialExpressiveTheme`, `MaterialShapes`, `MotionScheme.expressive()`.
  - **Coil 3.2.0**: `AsyncImage` and SVG decoding (`SvgDecoder`).
  - **Glance AppWidget**: `ContinueReadingWidget.kt` home screen widget.
  - **App Lock / Biometrics**: `LockScreen.kt` & `SettingsScreen.kt` with `Build.VERSION.SDK_INT >= Q` safety guards for API 26/27 compatibility.

## Build & Test Commands
```bash
# Set JAVA_HOME to Android Studio JBR if running from CLI
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Run unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug
```

## Key Guidelines for AI Agents (Claude / Antigravity)
1. **SDK Compatibility**: Maintain `minSdk = 26`. Always guard API 28/29+ specific platform calls (e.g. `BiometricPrompt`, `BiometricManager`) with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`.
2. **Font Safety**: Custom fonts must always be wrapped in `runCatching` with fallback to `FontFamily.Serif` / `LiterataFamily` to prevent UI crashes.
3. **SAF & Storage**: Keep buffer size 8KB when streaming `openInputStream` to temporary `cacheDir` files.
4. **No Code Reductions**: Preserve existing comments, docstrings, and error fallback handlers.
