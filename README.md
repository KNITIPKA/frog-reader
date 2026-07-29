# FrogReader 🐸📖

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-SDK%2026+-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Download APK](https://img.shields.io/github/v/release/KNITIPKA/frog-reader?include_prereleases&color=orange&label=Download%20Alpha%20APK&style=for-the-badge&logo=android)](https://github.com/KNITIPKA/frog-reader/releases/latest)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-orange.svg?style=for-the-badge)](https://github.com/KNITIPKA/frog-reader/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)

> ### ⚠️ This project is in **alpha**
>
> It is under active development and not finished. Expect bugs, unpolished screens and
> changes that break between versions. Builds are debug-signed and not on Google Play.
> Try it if that sounds fine to you — and please report what breaks.

**FrogReader** is an open-source e-book reader for Android, built with 100% Kotlin and Jetpack
Compose. It has a fully custom format parsing engine (EPUB, FB2, MOBI/KF8/PalmDoc), a CSS
resolver, embedded font decompression, paged and scrolling reading modes, and extensive reader
customization. Runs on **Android 8.0+ (minSdk 26)**.

> 📥 **Download**: grab the latest alpha APK from
> **[GitHub Releases](https://github.com/KNITIPKA/frog-reader/releases/latest)**
> — the file is named `frog-reader-<version>-alpha.apk`.
>
> The current build is ~71 MB because it is an unminified debug APK; that is not the size of
> the finished app.

---

## ✨ Features & Capabilities

### 📚 Multi-Format Book Engine
- **EPUB Parser**: OPF manifest parsing, NCX & HTML5 NAV table of contents, spine ordering, and container resource extraction.
- **FB2 Parser**: Deep XML structure mapping (epigraphs, poem stanzas, sidebars, inline images, sub-titles, sections).
- **MOBI / KF8 / PDB Engine**: Low-level PalmDoc LZ77 decompression, PDB record parsing, MOBI header decoding, and KF8 section mapping.

### 🎨 Advanced Typography & Layout Engine
- **WOFF & WOFF2 Decompressor**: Embedded WOFF and WOFF2 web fonts are decompressed natively into TTF using Brotli for high-fidelity rendering.
- **Font De-obfuscation**: Supports Adobe and IDPF font de-obfuscation algorithms found in commercial EPUBs.
- **Custom CSS Engine**: Full CSS resolver with relative units (`em`, `rem`, `%`), inheritance, cascading rules, list markers, and `calc()` expression parsing.
- **Publisher Mode**: Toggle between original publisher formatting (embedded fonts, publisher margins/indents/line spacing) or user-customized styling.
- **Drop Caps**: Renders decorated initial capitals defined by publisher CSS.
- **Rich Elements**: Jetpack Compose layouts for inline images, sideboxes, table grids (`TableGrid`), quotes, and Ruby annotations.

### 📖 Reading Experience & Customization
- **Dual Reading Modes**: Choose between **Paged Mode** (page-by-page turning) or **Continuous Scroll**.
- **Hardware Controls**: Turn pages using device volume keys (`VolumeKeyPaging`).
- **Custom Fonts**: Choose Literata, Serif, Sans-Serif, or import your own custom `.ttf` / `.otf` font files.
- **Night / OLED Image Inversion**: Invert diagram & scan colors automatically in dark/OLED themes so bright white scan images don't glare at night.
- **Footnote Handling**: Inline footnote popups or clean footnote stripping (`hideFootnotes`).
- **Themes & Margins**: Light, Beige and Midnight themes, shared by the library and the reading surface; customizable page margins, font sizes, and line heights.
- **Page-Turn Animations**: Configurable transitions (Slide, Cascade, Page Curl).

### ⚡ Metrics, Search & Navigation
- **Reading Speed & Metrics**: Live words-per-minute (WPM) calculation, estimated time remaining per chapter, and precise progress metrics.
- **Interactive Tools**: Full-text book search with live match previews, chapter TOC sheet, text selection, and quote saving toolbar.
- **Dynamic Pagination**: Custom page-breaking engine with `PaginationCache` for instantly smooth page turns.

### 📚 Library
- **Shelves**: drag one book onto another to group them; open a shelf to rename it or pull a book back out.
- **Grid & List Views**: three-column cover grid or a compact list with inline reading progress.
- **Live Search**: filters by title and author as you type, and looks inside shelves.

### 🔐 App Security & Widgets
- **App Lock**: Optional biometric / device-credential lock on the library (Android 10+).
- **Reading Analytics Dashboard**: Track daily reading time, set daily reading goals (in minutes), and view reading history.
- **Home Screen Widget**: Jetpack Glance AppWidget to jump directly back into "Continue Reading".

---

## 🛠️ Architecture & Tech Stack

```text
FrogReader/
├── app/src/main/java/com/example/frogreader/
│   ├── data/
│   │   ├── model/          # Core book models, bookmarks & library schemas
│   │   ├── parser/         # EPUB, FB2, MOBI parsers, CSS resolver, WOFF/2 decoders
│   │   └── repository/     # DataStore & book storage repositories
│   ├── ui/
│   │   ├── library/        # Main bookshelf & book details UI
│   │   ├── reader/         # Paginated reader, typography, gesture handlers, popups
│   │   ├── stats/          # Reading stats & analytics UI
│   │   ├── settings/       # Customization & app settings
│   │   ├── lock/           # Lock screen & PIN protection UI
│   │   └── theme/          # Typography, color schemes, Material 3 theme
│   └── widget/             # Glance AppWidget ("Continue Reading")
```

- **Language**: 100% Kotlin 2.x
- **UI Framework**: Jetpack Compose (Material 3)
- **Asynchrony**: Kotlin Coroutines & Flow
- **Data Persistence**: Jetpack DataStore Preferences & JSON Serialization
- **Parsing**: JSoup, XML Pull Parser, Brotli (WOFF2)
- **Image Processing**: Coil Compose & Coil SVG
- **Widgets**: Jetpack Glance AppWidget

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio**: Ladybug (2024.2.1+) or newer
- **Android SDK**: `minSdk 26` (Android 8.0 Oreo+), `targetSdk 36`, `compileSdk 37`
- **JDK**: Java 11 / 17

### Building from Source

1. **Clone the repository:**
   ```bash
   git clone https://github.com/KNITIPKA/frog-reader.git
   cd frog-reader
   ```

2. **Open in Android Studio:**
   Open the `frog-reader` folder in Android Studio and let Gradle sync.

3. **Build & Run:**
   Run `./gradlew assembleDebug` or click **Run** in Android Studio to deploy to an emulator or physical device.

---

## 🧪 Testing

The codebase includes comprehensive JVM unit tests covering parsers, CSS calculation rules, font decoders, and reader metrics.

To run tests:
```bash
./gradlew test
```

---

## 🤝 Contributing

Contributions are welcome! Check out [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on bug reporting and pull requests.

Because the project is in alpha, **bug reports are the most useful contribution right now** —
especially with the book that triggered the problem, if you can share it.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

<p align="center">Crafted with 💚 for book lovers and Android developers.</p>
