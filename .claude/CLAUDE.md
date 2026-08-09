# FrogReader — Claude / AI Agent Guide

## Overview
FrogReader is a native Android e-book reader built with **Kotlin 2.x** and **Jetpack Compose (Material 3 Expressive)**.

## Core Rules for AI Assistants
1. **Target SDK & Minimum SDK**:
   - `minSdk` = 26 (Android 8.0 Oreo+). Do NOT raise `minSdk` or use unguarded API 28+ native calls without `Build.VERSION.SDK_INT >= Q` checks.
   - `compileSdk` = 37, `targetSdk` = 36.
2. **Build Environment**:
   - Use `./gradlew test` to verify logic.
   - Specify `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` when executing CLI commands on macOS.
3. **Typography & Font Engine**:
   - WOFF2 fonts are decoded via Brotli into TTF.
   - Font loading uses `Typeface.Builder` wrapped in `runCatching` to prevent crashes on vendor-modified Android 8.0 ROMs.
4. **Data Persistence**:
   - Three documents, split by data owner: `library.json` (metadata), `userdata.json` (quotes, bookmarks, ratings, reviews — irreplaceable), `progress.json` (reading positions, written constantly).
   - All of them go through `AtomicJsonFile` (`.tmp` -> fsync -> `.bak` -> atomic rename). Never write a store with a bare `writeText`.
   - `BookRepository` exposes whole `Book` objects and works out which document a write touches by diffing before/after. Keep it that way — callers must not have to declare what they changed.
   - Adding a field to a stored model needs a default value, or every existing file fails to decode and reads as corrupt.
   - New `@Serializable` models belong in `data.model` — the R8 keep rule in `app/src/main/keepRules/rules.keep` covers that package only.
   - `SettingsRepository` uses DataStore Preferences.
5. **Backup**: the backup zip format (`data/backup/BackupArchive.kt`) is deliberately independent of the on-disk layout and of the destination. Changing how data is stored must not change the backup format; bump `BackupManifest.FORMAT_VERSION` only for a change older readers could not survive.
