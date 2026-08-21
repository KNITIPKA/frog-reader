# Project: FrogReader

## Overview
FrogReader is a modern, high-performance native Android e-book reader built with Kotlin 2.x and Jetpack Compose (Material 3 Expressive). It features custom pagination, WOFF2/Brotli typography decoding, rich multi-format parsing (EPUB, FB2, MOBI6, KF8/AZW3), split atomic JSON persistence, automated backups, and cross-page text selection.

## Architecture
- **Language & Runtime**: Kotlin 2.3.x, Target SDK 36, Min SDK 26 (Android 8.0 Oreo+).
- **UI Framework**: Jetpack Compose with Material 3 Expressive design tokens.
- **Typography & Font Engine**: WOFF2 fonts decoded via Brotli into TTF; `Typeface.Builder` runtime guarded (`runCatching`) for Android 8.0+ ROM stability.
- **Persistence Model**: Three distinct data domains managed by `AtomicJsonFile` (`.tmp` -> fsync -> `.bak` -> atomic rename):
  1. `library.json` (book metadata, file paths, formats, cover cache pointers)
  2. `userdata.json` (bookmarks, quotes, highlights, reviews, ratings)
  3. `progress.json` (reading positions, scroll/page offsets, timestamps)
- **Repository Architecture**: `BookRepository` exposes unified `Book` entities and diffs before/after states to automatically route writes to the appropriate JSON store.
- **Backup Architecture**: Decoupled JVM ZIP backup format (`BackupArchive.kt`) independent of on-disk store representation with backward compatibility manifest.
- **Reader Engine**: Custom off-thread pagination engine, side box layout, custom font rendering, and cross-page text selection gestures with floating action pill.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | R1: Safety Backup | Create `backup-before-cleanup` branch/tag preserving all 27 modified + 2 untracked files | M1 | ORIGINAL_REQUEST §R1 |
| 2 | R5: AI Mentions Scrubbing | Remove `.claude/`, `AGENTS.md`; create clean `ARCHITECTURE.md` & `CONTRIBUTING.md`; sanitize `PROJECT.md`, `.gitignore`, and `UserBookDiagnosticTest.kt` | M1 | ORIGINAL_REQUEST §R5 |
| 3 | R2: Commit Restructuring | Reorganize `main` into 12 Conventional Commits with author/committer `Frog <knitipka@proton.me>` and zero AI metadata | M2 | ORIGINAL_REQUEST §R2 |
| 4 | R6: Test & Release Build Verification | Verify `./gradlew test` passes 100% (544+ passing tests) and `./gradlew assembleRelease` outputs signed release APK | M3 | ORIGINAL_REQUEST §R6 |
| 5 | R3: Branch Sanitation | Delete obsolete local and remote branches (`codex/*`, `feature/*`, `selection-across-pages`); retain only clean `main` | M4 | ORIGINAL_REQUEST §R3 |
| 6 | R4: GitHub Releases & Tag Cleanup | Delete old releases (`v1.47`..`v1.101`) & tags; create single clean "Alpha 1" release with release APK | M4 | ORIGINAL_REQUEST §R4 |
| 7 | Acceptance & Final Audit Gate | Reviewers, Challengers, and Forensic Auditor verify 100% integrity, clean git graph, zero AI traces, and passing tests | M5 | ORIGINAL_REQUEST §Acceptance Criteria |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Safety Backup & AI Scrubbing | Create backup branch/tag `backup-before-cleanup`, clean AI files/paths, draft human docs | none | DONE |
| 2 | M2: Commit History Consolidation | Rebuild git history into 12 Conventional Commits with clean authorship | M1 | DONE |
| 3 | M3: Verification & Release Build | Verify 100% unit tests pass (`./gradlew test`) & assemble release APK | M2 | IN_PROGRESS |
| 4 | M4: Repository & Release Sanitation | Clean remote branches/tags, delete legacy GitHub releases, publish "Alpha 1" release | M3 | PLANNED |
| 5 | M5: Multi-Agent Review & Forensic Audit Gate | Independent Reviewers, Challenger verification, and Forensic Integrity Audit | M4 | PLANNED |

## Code Layout
- `app/src/main/java/com/example/frogreader/`:
  - `data/model/`: Stored entities (`Book`, `BookContent`, `ReadingProgress`, `UserBookData`, etc.)
  - `data/parser/`: Format parsers (`EpubParser`, `Fb2Parser`, `mobi/*`, `HtmlMapper`, `CssResolver`, `Woff2Decoder`)
  - `data/repository/`: `BookRepository`, `SettingsRepository`, `AtomicJsonFile`
  - `data/backup/`: `BackupArchive`, `BackupManifest`, `BackupWorker`
  - `ui/library/`: Shelf UI, Cover Flow, Folder navigation, File scanner dialogs
  - `ui/reader/`: Reader screen, Pagination engine, Selection gestures, Floating action pill, Typography settings
  - `ui/settings/`: Preferences, Themes, Backup UI
- `app/src/test/java/com/example/frogreader/`: Unit test suite (73 test suites, 548 unit tests)
- Root documentation: `ARCHITECTURE.md`, `CONTRIBUTING.md`, `README.md`, `PROJECT.md`
