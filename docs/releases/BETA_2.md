# Frog Reader BETA 2

**2026-10-17 · Android 8.0+ · Version code 6 · GPL-3.0-only**

FrogReader's second beta delivers major reader performance optimizations, a comprehensive typography and custom-font engine, a redesigned floating selection context menu, an overhauled Quotes & Bookmarks screen, image and SVG rendering enhancements, and refined application settings. This release covers all changes since **BETA 1**.

## Install and upgrade

Download **`FrogReader-BETA-2.apk`** from the [Beta 2 release assets](https://github.com/KNITIPKA/frog-reader/releases/tag/beta-2). The accompanying **`SHA256SUMS.txt`** records the APK checksum.

Export a backup in **Settings → Backup** before upgrading. Beta 2 preserves the application ID and signing certificate from Beta 1 and Alpha 3, allowing in-place updates without losing library metadata, reading history, or userdata. Existing per-book settings and appearance choices are retained. Backup format remains **2**.

This APK retains the original Android Debug certificate for update compatibility. The release build requires an explicit legacy-signing opt-in; source builds can instead use an external signing key or produce an unsigned release.

## What's new

- **Redesigned text selection context menu.** A sleek floating bubble with a directional pointer arrow, quick highlight color pickers, and organized action cards for Copy, Quote, Translate, Dictionary, and Web Search.
- **Overhauled Quotes & Bookmarks.** Redesigned screen featuring a collapsible book header with nested scroll handoff, live book search, streamlined tabs, and refined passage cards.
- **Image & SVG performance and raster caching.** Introduced an asynchronous disk cache for rasterized SVGs with coalesced decoding and background prefetching (ahead and behind the viewport in both scroll and paged reading modes), eliminating scrolling stutters on vector illustrations.
- **Fixed dark-mode image Auto Invert.** Fixed image inversion logic to accurately respect publisher dark-mode rules, author-specified dark backgrounds, and reader themes.
- **Dedicated defaults for plain text and Markdown.** Reading `.txt` and `.md` files now opens with optimized defaults: Sans typography, 15 sp text size, 1.5 line height, 14 dp margins, unhyphenated body, and continuous scroll mode.
- **Advanced typography engine and custom fonts.** Support for importing and managing custom OTF, TTF, and WOFF2 fonts, bundled Liberation fonts (Sans, Serif, Mono), font face resolution, numeric font weights, font variation settings, and glyph coverage checks.
- **Custom page counter styles.** Choose between **Page only**, **Page / Total**, and **Chapter** counters with visual preview cards in the reader settings panel.
- **Press-and-hold stepper repetition.** Font size, line spacing, and margin adjustment buttons now feature accelerating continuous repeats on long press.
- **Granular side margin adjustments.** Margins can now be stepped incrementally from 8 to 48 dp in 2 dp increments.
- **Animated segmented controls.** Interactive sliding capsules across reader panels, quotes tabs, and settings with real-time drag previews.
- **Reorganized application settings.** Cleanly categorized into distinct sections: **Panels and menus**, **Controls and gestures**, **Selected text**, and **Screen and startup**.
- **External text actions.** Configure default applications for translation, dictionary lookups, and web search directly in settings with custom app selectors.
- **Library animation optimizations.** Dynamic graphics layer allocations during shelf and tile arrivals, keeping draw and recomposition costs minimal.

The [full changelog](https://github.com/KNITIPKA/frog-reader/blob/beta-2/CHANGELOG.md) includes detailed parser, caching, and technical changes.

## Known limits

Beta 2 remains a prerelease. Dedicated **Statistics**, **Achievements** and profile-wide search remain disabled **Coming soon** placeholders; profile activity, goals, the reading queue, log, and saved passages are fully active.

PDF, KFX, DRM-protected books, full fixed-layout EPUB/KF8 rendering, vertical writing, and embedded audio/video are unsupported. CSS, MathML, and ruby support are bounded native subsets with readable fallbacks. See the [EPUB audit](https://github.com/KNITIPKA/frog-reader/blob/beta-2/docs/epub-standard-ebooks-audit.md) for rendering details.

JVM checks, release assembly, and native desktop previews do not establish physical-device gesture smoothness, full-book visual fidelity, TalkBack behavior, or performance across all Android vendors.

## Verification

- Local JVM suite: **1,927 passed, 8 skipped**, 0 failures, 0 errors.
- Debug and optimized release assembly succeeded with R8 code optimization and resource shrinking enabled.
- Debug and release Android lint completed with **0 errors**.
- APK alignment (`zipalign -c -P 16 -v 4`) and signature verification (`apksigner verify`) passed. Certificate matches the published Alpha 3 / Beta 1 APK.
- The release APK is confirmed non-debuggable with `versionCode = 6` and `versionName = BETA 2`.

## Source and license

The application is licensed under [GPL-3.0-only](https://github.com/KNITIPKA/frog-reader/blob/beta-2/LICENSE). See [third-party notices](https://github.com/KNITIPKA/frog-reader/blob/beta-2/THIRD_PARTY_NOTICES.md) for bundled external material. Source for this binary is available at the exact [`beta-2` tag](https://github.com/KNITIPKA/frog-reader/tree/beta-2), including build instructions and pinned dependencies. GitHub provides the matching [source ZIP](https://github.com/KNITIPKA/frog-reader/archive/refs/tags/beta-2.zip) and [source tarball](https://github.com/KNITIPKA/frog-reader/archive/refs/tags/beta-2.tar.gz).

Please report bugs or feedback via **Telegram** ([@knitipka](https://t.me/knitipka)), **Signal** ([direct message](https://signal.me/#eu/gXfCfTAgdX8qNuAaZ-L3ozxFAJ0qb1ObkD9t2TNTVr6iPYYMhVVOSrXw-5pjdEjb)), **Email** ([knitipka@proton.me](mailto:knitipka@proton.me)) or [GitHub Issues](https://github.com/KNITIPKA/frog-reader/issues).
