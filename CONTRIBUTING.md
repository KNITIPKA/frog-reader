# Contributing to FrogReader 🐸📖

Thank you for your interest in contributing to **FrogReader**! We welcome contributions ranging from format parser improvements and typography fixes to UI enhancements and documentation.

---

## 🛠️ Development Setup & Environment

1. **Android Studio**: use a version compatible with Android Gradle Plugin 9.4.1. The checked-in Gradle wrapper and version catalog define the build toolchain.
2. **JDK**: Gradle uses the checked-in JDK 21 daemon criteria. Android Studio's bundled JBR is recommended for CLI commands; Gradle resolves the requested daemon toolchain.
   - On macOS CLI, set `JAVA_HOME` to the Android Studio JBR:
     ```bash
     export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
     ```
3. **SDK Requirements**:
   - `minSdk` = 26 (Android 8.0 Oreo)
   - `targetSdk` = 36
   - `compileSdk` = 37
   - Install Android SDK Platform 37 and Build Tools 37.0.0.

---

## 📐 Core Architectural Guidelines

When contributing code, please adhere to these core invariants:

1. **SDK Compatibility**:
   - Do **NOT** raise `minSdk` from 26.
   - Always guard API 28/29+ platform features (e.g. `BiometricPrompt`) with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` or `VERSION_CODES.P`.
2. **Data Persistence**:
   - All store writes must use `AtomicJsonFile` (`.tmp` -> `fsync` -> `.bak` -> atomic rename). Never use bare `writeText`.
   - Any new property added to a `@Serializable` model in `com.example.frogreader.data.model` **must define a default value**.
   - Keep `BookRepository` diff-based: callers work with whole `Book` objects, and the repository handles persistence routing.
3. **Typography & Font Safety**:
   - Always wrap custom font loading (`Typeface.Builder`) in `runCatching` with fallback to `FontFamily.Serif` or `LiterataFamily`.
4. **Backup Format Invariance**:
   - The backup zip format (`data/backup/BackupArchive.kt`) must remain independent of on-disk storage layout. Bump `BackupManifest.FORMAT_VERSION` only for breaking format changes.
5. **Storage & SAF**:
   - Use an 8KB buffer when streaming content resolver streams to temporary cache files, and always close streams via `.use { }`.

---

## 🧪 Testing & Verification

Before submitting a pull request, verify that all unit tests pass:

```bash
# Run all unit tests
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test

# Run a specific unit test class
./gradlew :app:testDebugUnitTest --tests "com.example.frogreader.parser.EpubParserTest"
```

---

## 🚀 Pull Request Workflow

1. **Fork the repository** on GitHub.
2. **Create a topic branch**:
   ```bash
   git checkout -b feat/my-new-feature
   ```
3. **Write clean, idiomatic Kotlin code** following Jetpack Compose and Material 3 Expressive guidelines.
4. **Follow Conventional Commits**:
   - `feat(parser): add support for XYZ tag`
   - `fix(reader): resolve text clipping on landscape orientation`
   - `test(mobi): add test case for corrupted PDB header`
   - `docs(arch): update persistence documentation`
5. **Push and open a Pull Request** against the `main` branch.

## Release checks and licensing

For BETA 1, also verify `./gradlew :app:assembleRelease :app:lintDebug :app:lintRelease`.
Release builds run R8 optimization and are unsigned unless signing is explicitly
configured. See [the release guide](docs/releasing.md).

The project uses [GPL-3.0-only](LICENSE). Contributions must be compatible with
that license. Preserve the licenses and attribution of third-party assets; see
[Third-party notices](THIRD_PARTY_NOTICES.md). Do not add private books, keys,
local settings, generated APKs, build reports, or screenshots of personal data.
Optional corpus diagnostics must skip gracefully when their local input books
are absent. Public regression tests should use small, redistributable fixtures.
To run local corpus checks, set `FROGREADER_TEST_BOOK_DIR` or pass
`-PtestBookDir=/path/to/books`; these files remain outside Git.
