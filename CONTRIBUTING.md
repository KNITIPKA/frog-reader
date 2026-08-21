# Contributing to FrogReader 🐸📖

Thank you for your interest in contributing to **FrogReader**! We welcome contributions ranging from format parser improvements and typography fixes to UI enhancements and documentation.

---

## 🛠️ Development Setup & Environment

1. **Android Studio**: Android Studio Ladybug (2024.2.1+) or newer.
2. **JDK**: JDK 11, 17, or 21 (Android Studio bundled JBR recommended).
   - On macOS CLI, set `JAVA_HOME` to the Android Studio JBR:
     ```bash
     export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
     ```
3. **SDK Requirements**:
   - `minSdk` = 26 (Android 8.0 Oreo)
   - `targetSdk` = 36
   - `compileSdk` = 37

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
./gradlew testDebugUnitTest --tests "com.example.frogreader.parser.EpubParserTest"
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

Thank you for helping make FrogReader better! 💚
