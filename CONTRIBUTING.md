# Contributing to FrogReader 🐸📖

Thank you for your interest in contributing to **FrogReader**! We welcome all contributions from bug reports and documentation fixes to major feature additions and new format parsers.

---

## 🚀 How to Contribute

### 1. Reporting Bugs & Suggesting Features
- Search existing [Issues](https://github.com/KNITIPKA/frog-reader/issues) to ensure your report or request hasn't already been submitted.
- When creating a bug report, please include:
  - Android OS version and device model.
  - Book format (EPUB, FB2, MOBI) causing issues.
  - Steps to reproduce the bug.
  - Expected vs. actual behavior.

### 2. Submitting Pull Requests (PRs)
1. **Fork the Repository** on GitHub.
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/frog-reader.git
   ```
3. **Create a Topic Branch**:
   ```bash
   git checkout -b feature/my-new-feature
   ```
4. **Make Your Changes**:
   - Write clean, readable Kotlin code.
   - Follow standard Android & Jetpack Compose best practices.
   - Maintain docstrings and comments.
5. **Run Tests**:
   Ensure all unit tests pass before submitting:
   ```bash
   ./gradlew test
   ```
6. **Commit & Push**:
   ```bash
   git commit -m "feat: add support for XYZ feature"
   git push origin feature/my-new-feature
   ```
7. **Open a Pull Request** against the `main` branch of the original repository.

---

## 🎨 Code Style Guidelines

- **Kotlin Standard Style**: Follow official Kotlin coding conventions.
- **Jetpack Compose**: Use `@Composable` functions efficiently, avoiding unnecessary recompositions and side-effects.
- **Parsers & Engines**: Ensure all format parsers handle malformed XML/HTML gracefully without crashing.

Thank you for helping make FrogReader better! 💚
