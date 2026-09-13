# FrogReader Alpha 3

Alpha 3 is a major update expanding FrogReader with native Plain Text and Markdown reading, in-file metadata editing for EPUB, FB2, and MOBI/KF8, publisher layout and heading typography refinements, hierarchical table of contents, and remembered translation flows.

## Highlights

- **Native TXT and Markdown book support** — import `.txt`, `.md`, and `.markdown` via file picker, folder scans, or file intents. Includes robust encoding detection (UTF-8, UTF-16 with BOM, and Windows-1251 fallback for legacy Cyrillic files), CommonMark/GFM formatting (headings, lists, quotes, tables, code, strikethrough), and auto-generated Contents from Markdown headings.
- **In-file metadata editing & cover management** — edit title, authors, description, genres, series, volume index, publisher, year, ISBN, translators, language, and cover image directly inside EPUB, FB2, and MOBI/KF8 files. Uses safe copy-on-write rewriting with generation checks, preserving your bookmarks, quotes, reading progress, reviews, and embedded fonts.
- **Dedicated Book Info & file sharing** — comprehensive book information screen with detailed metadata and direct export/sharing via Android sharesheet.
- **Publisher layout geometry & typography polish** — reworked publisher margins, padding, alignment, line spacing, inline images, tables, SVG, and drop caps. Preserves publisher line-box leading at paragraph boundaries to maintain proper vertical rhythm with zero-margin paragraphs.
- **Heading alignment & "Center headings" setting** — shared H1–H6 font-relative hierarchy. Headings default to logical start alignment while respecting explicit publisher styles; ornament-only headings receive a centered default. A new global setting allows overriding heading alignment across all books.
- **Hierarchical Contents & navigation polish** — structured table of contents tree with expand/collapse navigation. Smarter "Back to page" return history with a 15-second accessible auto-hide timeout (configurable in settings) and decoupled reader chrome controls.
- **Remembered text translator** — quick-translate remembers your preferred translator app instead of showing the system chooser every time, with a settings toggle under **Application settings → Reading → Default translator** to switch or reset handlers.
- **Library progress & backup hardening** — fixed list-mode reading progress bars (respects RTL and active theme palette). Backup archives now detect physical ZIP truncation during import.

## Additional improvements

- Original source bytes of TXT and Markdown files are preserved without modification through import, reload, sharing, export, and backup.
- Safe Android `FileProvider` sharing and export of independent book copies.
- Upgraded Android Gradle Plugin to 9.4 and Gradle to 9.6.
- Architecture audit guidelines and format-engine documentation recorded.

## Known limitations

- Fixed-layout EPUB/KF8, KFX, vertical writing, audio, video, and KF8 panel magnification are not enabled yet.
- MathML uses a bounded native reading representation rather than a complete browser-compatible two-dimensional layout engine.
- RTL shaping, animated GIFs, and embedded fonts on older vendor ROMs still benefit from physical-device verification.

## Verification

- 920 JVM unit tests executed successfully (0 failures, 5 skipped diagnostics).
- Debug and release lint: 0 errors.
- Optimized R8 release APK verified for Android 8.0+ (minSdk 26) with APK Signature Scheme v2.

Download and install `FrogReader-alpha-3.apk` on Android 8.0 or newer.
