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
   - `BookRepository` writes `library.json` using atomic file replacement (`.tmp` -> `.json` -> `.bak`).
   - `SettingsRepository` uses DataStore Preferences.
