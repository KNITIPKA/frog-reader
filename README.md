# FrogReader

[![Release](https://img.shields.io/badge/Release-BETA%201-2f7356)](https://github.com/KNITIPKA/frog-reader/releases/tag/beta-1)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![CI](https://img.shields.io/github/actions/workflow/status/KNITIPKA/frog-reader/ci.yml?branch=main&label=CI&logo=github)](https://github.com/KNITIPKA/frog-reader/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-GPL%203.0-blue)](LICENSE)

**FrogReader is a free, open-source Android e-book reader for reading, organizing books and keeping a personal reading history.** It supports EPUB, FB2, MOBI/KF8, TXT and Markdown, with native paged and continuous reading, publisher formatting, a reading queue, saved passages and local backups.

**[Download Frog Reader BETA 1](https://github.com/KNITIPKA/frog-reader/releases/tag/beta-1)** · [Release notes](docs/releases/BETA_1.md) · [Changelog](CHANGELOG.md) · [Report a bug](https://github.com/KNITIPKA/frog-reader/issues)

Runs on **Android 8.0 or later**. Download the APK from the release assets and open it on your Android device. Android may ask you to allow installation from the app used to open the file.

> FrogReader is in beta. Bugs and unfinished features remain, and it is not available on Google Play yet. Export a backup before upgrading or trying a development build.

## Reading

- **Pages or continuous scrolling**, with Slide, Stack, Page curl and None page-turn choices. Page curl follows a two-dimensional finger drag, supports cancellation and mirrors for right-to-left books.
- **Floating or Classic controls**, selectable in the Welcome screen and settings.
- **Per-book typography**: font, size, line spacing, margins, justification, hyphenation and layout choices. New books start with the reader defaults; changing one book does not change another.
- **Bundled Literata and IBM Plex Sans**, with regular and italic faces, plus custom TTF/OTF import.
- **Publisher formatting** for supported embedded fonts, alignment, colors, spacing, headings, drop caps, poetry, images and tables.
- **Chapter-aware progress**, with an expanded active chapter, live chapter/page captions, haptic feedback and a compact linear fallback for dense tables of contents.
- **Optional page counter**, showing the page number or pages remaining in the chapter.
- **Volume-key page turning**, brightness controls and a keep-screen-on option.
- **Rich footnotes** with nested links, headings, lists, poetry, tables and images.
- **Right-to-left books** with RTL page progression, logical alignment and mixed-direction text handling.
- **Image inversion controls** and supported publisher dark-mode artwork treatment.

## Library and book details

- Import individual files, scan a chosen folder or open supported files from Android file managers.
- Switch between cover grid and list views; organize books into shelves and use multi-select actions.
- Search titles, authors, series and descriptions, including books inside shelves.
- See library progress and a continue-reading card. Opening a shelf book brings the book and its shelf forward in recent ordering.
- View and edit book information in one screen, with half-star ratings, reviews, cover actions and expandable edition/technical details.
- Rewrite embedded metadata in imported EPUB, FB2 and MOBI/KF8 copies, with verification and recovery safeguards. The external file originally imported is preserved. [Metadata editing details](docs/BOOK_METADATA_EDITING.md).
- Share or export an independent copy of a book through Android's file picker and share sheet.

## Profile, Reading Queue and Reading Log

- **Personal profile** with editable name, cropped avatar, last-read time, weekly activity, reading streak and an adjustable daily goal.
- **Reading Queue** for books you plan to read: add a title, author and note; drag to set priority; attach a file; swipe or use a menu to start reading or remove an entry. Undo restores removed entries. Attached files stay outside the library until you start reading. [Queue details](docs/reading-queue.md).
- **Reading Log** with separate records for each reading, including paper books and books read in another app. Track In progress, Finished or Abandoned status, exact or partial dates, half-star ratings, reviews, covers and recorded reading time.
- Filter and sort the log, reorder eligible entries with matching remembered dates, and add or edit entries in full-screen forms. Capture manual-book covers with the camera or choose them from the gallery.
- Resume an abandoned reading or start a separate reread while preserving the previous record. Finished books can be browsed without starting a new reading.
- View current reading progress and the stopping percentage for abandoned FrogReader entries. History and retained covers survive removal of the library book. Deletion supports Undo. [Log details](docs/reading-log.md).

The separate **Statistics**, **Achievements** and profile-wide search controls are marked **Coming soon** and disabled. The profile's activity, daily goal, queue, log, quotes and bookmarks are available now.

## Search, navigation and saved passages

- Full-text search with match previews and an expandable table of contents.
- Return to the previous reading position after following links, opening search results, jumping to saved passages, seeking progress or making a long continuous scroll.
- Select text to copy, translate or save a quote. Choose a preferred installed translation app and change it later in settings.
- Save quotes in multiple colors, including overlapping and nested passages with independent anchors and notes.
- Manage **Quotes & Bookmarks** from the profile: search saved content, sort quotes, edit notes, copy/share passages, delete with Undo and return to their reading positions.
- Cached pagination and shared layout geometry keep navigation and position restoration consistent.

## Appearance and Android integration

- **Light, Beige and Midnight** palettes, including refined neutral Light controls and linen/ivory Beige surfaces.
- Follow the system appearance; choose Light or Beige for its light mode. Optional **Material You** colors are available on Android 12+.
- A first-launch Welcome screen selects the control style, startup screen and appearance. Existing installations retain their saved choices.
- Animated library/reader transitions, shared dock/Add actions and themed transient notifications with accessible timeouts.
- Optional app lock using biometrics or device credentials on Android 10+.
- A **Continue Reading** home-screen widget.

## Local data and backups

FrogReader works offline and has no account requirement or built-in cloud sync. It uses Android's file picker for files and folders; camera access is optional and requested for cover capture.

- Export and restore a ZIP archive, with **data-only** or **full** backups.
- Data-only archives retain metadata, shelves, reading progress, quotes, bookmarks, reviews, profile/settings, queue and reading history without copying book files.
- Full archives also include stored book files, queue attachments and covers.
- Schedule daily or weekly backups and retain the five most recent automatic snapshots.
- Choose a backup location offered by Android's document picker, including a cloud-provider folder when the provider exposes one.
- Metadata, user-created records and frequently written progress use separate stores with atomic writes and recovery copies.

Beta 1 retains the Alpha 3 backup format (version 2). Data-only restoration on another device may require reattaching book files. Keep your own exported backup before uninstalling the app.

## Formats and rendering

| Format | Supported content |
| --- | --- |
| EPUB 2/3 | Reflowable publications, NAV/NCX contents, linked resources, publisher CSS, images and embedded fonts, including Adobe/IDPF font de-obfuscation and WOFF/WOFF2 decoding. |
| FB2 / FB2 ZIP | Sections, notes, inline styles, poetry, epigraphs, embedded artwork and covers. |
| MOBI / KF8 / AZW3 / PalmDoc | Legacy and newer Kindle text/resource structures, including supported combined MOBI/KF8 files. |
| TXT | Literal text, preserved original bytes, UTF-8/BOM-marked UTF-16 decoding and Windows-1251 fallback. |
| Markdown (`.md`, `.markdown`) | CommonMark content, GFM tables and strikethrough, heading-based contents and fragment navigation. |

Beta 1 expands native publisher rendering for structural CSS selectors, whitespace, embedded font ranges, authored image bounds, responsive dialogue tables and very long tables. Supported MathML fractions, roots and scripts have native two-dimensional layout; simple ruby readings appear above their bases. Unsupported structures keep a readable fallback. [Advanced EPUB audit and limits](docs/epub-standard-ebooks-audit.md).

This is a native reading engine rather than a browser CSS implementation. PDF, KFX, DRM-protected books, full fixed-layout EPUB/KF8 rendering, vertical writing and embedded audio/video are not supported. Complex CSS, MathML and ruby may differ from publisher layout. Device rendering, accessibility and performance still need testing across Android versions and vendors.

## Build and test

Use **JDK 21**, an Android Studio version compatible with the project's Android Gradle Plugin, and Android SDK platform **37**. The app targets SDK **36** and retains minimum SDK **26**. Gradle and dependency versions are pinned in the repository.

```sh
git clone https://github.com/KNITIPKA/frog-reader.git
cd frog-reader
./gradlew test :app:assembleDebug
```

On macOS, use Android Studio's bundled JBR for CLI commands:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew test :app:assembleDebug
```

Build an optimized release with `./gradlew :app:assembleRelease`. Release signing and version configuration are described in [Contributing](CONTRIBUTING.md); the release label and monotonically increasing Android version code live in `version.properties`. APKs are generated under `app/build/outputs/apk/`.

The JVM suite covers parsers, publisher layout, pagination, selection, data persistence, backups, navigation and UI state. Local-book corpus tests are optional and skip when their external files are absent; synthetic regression fixtures run in CI. Builds and unit tests do not replace physical-device reading or gesture checks.

## Contributing and planned work

Bug reports, parser fixes, accessibility testing, documentation and translations are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow. Include the app version, Android/device details and steps to reproduce; share a sample book only when you have permission to redistribute it.

Current priorities include broader rendering fidelity and performance, physical-device accessibility/gesture testing, dedicated statistics and achievements, additional widgets, localization and future format support. Planned features are not included in Beta 1 unless listed above.

## License

FrogReader's application code is licensed under the **GNU General Public License, version 3 only** (**GPL-3.0-only**). See [LICENSE](LICENSE). Modified distributions must comply with that license, including its source-code requirements.

Bundled fonts, libraries and other third-party material retain their respective licenses and attribution notices; the GPL change does not relicense them. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
