# Changelog

## BETA 1 — 2026-10-11

First public beta, covering the changes since the `alpha-3` tag. The Android
release label is **BETA 1**, version code **4**, with Android 8.0+ support retained.

### Profile and personal reading records

- Added a profile dashboard with editable name, avatar selection/cropping,
  last-read time, weekly reading activity, streaks and an adjustable daily goal.
  Refined its landscape, narrow-screen and large-text layouts and goal controls.
- Added **Reading Queue**, an independently ordered list with titles, authors,
  notes, optional private book attachments, drag priority, swipe/menu actions
  and deletion with Undo. Starting an attached book imports or reuses its
  library record, transfers its note and opens the reader.
- Added **Reading Log**, with one record per reading and support for FrogReader,
  paper and other-app sources. Entries include In progress, Finished or
  Abandoned status, exact/partial dates, retained covers, half-star ratings,
  reviews and recorded reading time.
- Added log filters, start/finish sort choices, compact cards, full-screen
  add/edit/details views, swipe deletion and Undo. Same-date manual ordering
  preserves independent start and finish chronology, including filtered lists.
- Added calendar, month/year and year-only date choices with preserved precision,
  direct month/year navigation and swipe-month changes. Dates are capped at
  today; year choices run from the current year to 1900. Choosing a finish date
  completes an active reading.
- Added camera and gallery cover selection for manual entries. FrogReader
  entries retain their linked title, author, cover and actual file format.
- Added live progress percentages for active FrogReader readings and a frozen
  stopping percentage for abandoned readings, including after library deletion.
  Delayed position writes cannot replace abandoned snapshots.
- Added **Continue reading / Start a new reading** for abandoned books and
  **Just browse / Start a new reading** for finished books. Rereads preserve
  previous dates, time, review and rating; browsing does not alter history.
- Made completion persist on Back, Back to library and Save and exit, even
  without a rating or review. Time on completion/review screens is excluded.
- Synchronized active log/library status, dates and reviews without overwriting
  older readings. Deleting the latest reading resets its associated progress;
  Undo restores history without replacing a newer reading or annotation edits.
  Guarded against stale reader writes reviving deleted reading state.
- Added a centralized **Quotes & Bookmarks** manager with search, quote sorting,
  note editing, copying/sharing, delete/Undo and navigation to saved passages.
- Marked profile-wide search and the separate **Statistics** and **Achievements**
  destinations as disabled **Coming soon** placeholders. The profile's reading
  activity and daily goal remain available.

### Reader controls, page turns and progress

- Added **Floating** and **Classic** control styles, with responsive settings
  previews and shared application-level selection.
- Added distinct **Stack**, **Page curl** and **None** renderers alongside Slide.
  Stack moves a rounded sheet over adjacent content; None switches pages directly.
  Updated the animation picker and its narrow/large-text layout.
- Added a finger-controlled paper curl with two-dimensional grip geometry,
  perspective, reverse-face shading and shadows, mirrored for RTL books.
  Cancellation, edge taps, volume keys, accessibility actions and selection
  auto-advance share page ownership and navigation.
- Stabilized curl settling, contact-limit geometry, text-height grabs and page
  footers. New gestures can interrupt settling so rapid consecutive turns do
  not discard input or let stale animations turn another page.
- Added segmented whole-book progress with an expanded active chapter, live
  scrubbing, animated chapter captions and haptic feedback. Dense contents use
  a readable linear track with sparse chapter markers; seeking geometry and
  animation work are cached/shared.
- Improved active chapter labels for multiline/nested titles and index-only
  subchapter headings. Chapter/book page captions follow the track edges and
  stay current during seeking.
- Added **Show page counter** and **Pages left in chapter** controls. Each page's
  footer moves with that page during animated turns.
- Fixed typography and layout settings leaking between books. First opening
  pins reader defaults; saved per-book choices survive delayed bookkeeping,
  reopening, metadata edits and backups.
- Bundled IBM Plex Sans regular/italic for the **Sans** choice alongside Literata,
  keeping existing font identifiers and custom-font support.

### Selection and saved highlights

- Added independently stored quote colors and nested/overlapping highlights.
  Inner passages retain their color; quote actions use anchored ranges instead
  of matching identical text elsewhere. Colors survive persistence and backups.
- Refined highlight paths, the magnifier, hit testing and page-edge auto-advance.
  Selection geometry is limited to the visible page/owned page pair during
  turns so adjacent-page text cannot capture the current gesture.
- Improved contrast of saved highlights and quote controls in Midnight.

### Library, appearance and first launch

- Unified Book Info and Edit into one stable screen with inline metadata,
  cover actions, half-star rating/review cards and expandable edition details.
  Preserved drafts, save retries, discard behavior and the distinction between
  embedded book metadata and personal reviews.
- Added the continue-reading library hero, reader opening/closing motion and
  route-aware Back transitions, including predictive Back.
- Opening a shelf member now promotes both that book and its shelf. Ordinary
  page turns write progress without repeatedly rewriting library metadata.
- Unified bottom docks and Add buttons, including reversible dock/FAB motion,
  immediate reveal during editor dismissal and synchronized shadow/content.
- Refined Light and Beige palettes across library, menus, profile, reader
  settings and saved passages, preserving optional Material You colors.
- Reworked theme settings with native previews and automatic appearance options:
  choose Light or Beige while following system light mode, plus Midnight in dark mode.
- Added a first-launch **Welcome** screen for control style, startup destination
  and appearance. Release builds show it for new installations; existing users
  retain their choices and do not repeat onboarding on an update.
- Updated fresh-install defaults to Literata 18 sp, 1.5 line spacing, wide
  margins, Pages/Stack, publisher formatting and Floating controls. Existing
  installs receive a migration preserving their previous defaults/settings.
- Added bounded cover-cache warming and a prepared library handoff after Welcome.
- Added shared themed in-app notifications with Undo, wrapping text, dismissal,
  accessible timeouts and narrow/large-text layouts.
- Fixed profile-name editing completion on keyboard dismissal and added Change
  name to the profile menu. Refined avatar controls and optional camera hardware.
- Removed unused launcher artwork and updated adaptive-icon resources.

### Publisher rendering and performance

- Removed the global **Center headings** override. EPUB/MOBI/KF8 keep authored
  alignment; FB2 titles have semantic centered defaults; TXT remains literal
  and Markdown uses logical-start defaults. Old settings/backups remain readable.
- Preserved structural CSS selectors, bounded relative `:has`, reverse selectors,
  inherited OpenType features, language-aware case transforms and typographic
  whitespace. Hidden ranges retain their advance without visible paint.
- Preserved empty authored boxes, border-only generated content, forced breaks,
  poem indentation, supported horizontal rows and short inline text boxes.
- Added bounded native two-dimensional MathML fractions, roots and scripts and
  above-base ruby readings, including Latin word joins around inflection ruby.
  Unsupported structures retain readable text; selection/search offsets remain
  source-consistent.
- Shared native inline geometry across paragraphs, headings, tables, side boxes,
  pagination and scrolling. Indivisible objects wrap together; oversized formulas
  scale to the text column with adjacent punctuation preserved.
- Removed the destructive long-table row fallback, retaining all audited dialogue
  rows. Added supported narrow responsive table stacking with source coordinates
  and authored borders/widths intact. Tables with unspecified borders retain the
  reader's default grid; explicit borderless tables remain borderless.
- Preserved authored image width/height maxima, viewport-relative bounds and
  supported dark-mode treatment for diagrams versus realistic illustrations.
- Resolved embedded inline font ranges before both measurement and drawing.
  Added supported viewport-relative centering for short text groups.
- Bounded SVG geometry probing to 16 KiB with a file-aware cache, replaced repeated
  publisher-box scans with an interval sweep, and shared measured table grids
  across cached page fragments. Updated disposable layout/cache identities;
  persistent book data and backup formats are unchanged.
- Expanded regression coverage with synthetic fixtures and optional original
  Standard Ebooks corpus tests. Native MathML/ruby, responsive CSS and other
  publisher features remain bounded subsets, not full browser conformance.

### Data compatibility, release and community

- Extended atomic user-data persistence and backups for the profile, queue,
  attachment snapshots, separate readings, half-stars and quote colors.
  Kept defaulted stored fields, legacy serialized identifiers and backup format 2.
- Updated Kotlin, Compose/Material 3, AndroidX and parser dependencies, Mockito
  test-agent setup and native-library packaging; retained SDK levels 26/36/37.
- Added GitHub Actions build/test verification and documented contributor,
  rendering and theme contracts.
- Changed FrogReader application code from MIT to **GPL-3.0-only** and added
  in-app license access and third-party notices. Bundled external material keeps
  its original licenses; earlier releases retain their historical licensing.
- Refreshed the README for implemented beta features and removed old repository
  screenshots pending replacement.
- Published an optimized release APK with a readable Beta 1 name and SHA-256
  checksum. Physical-device gesture, TalkBack and rendering checks remain
  separate from JVM tests, builds and desktop Compose previews.

## ALPHA 3 — 2026-09-13

ALPHA 3 records the changes delivered after the Alpha 2 baseline.

### Book files and library data

- Added native `.txt`, `.md` and `.markdown` import through the file picker,
  folder scan and Android file intents.
- Added UTF-8 and BOM-marked UTF-16 decoding, with a Windows-1251 fallback for
  legacy Cyrillic files. Text files are bounded to 16 MiB and binary, empty or
  oversized inputs are rejected.
- TXT keeps source text literal, line-oriented and lossless. Markdown renders
  headings, emphasis, links, lists, quotes, code, GFM tables and strikethrough.
  Headings populate Contents and fragment navigation; the first heading becomes
  the title when one is available.
- Preserved the original bytes of TXT and Markdown books through import,
  reload, sharing, export and backup. Added format-aware MIME labels and
  disabled embedded metadata editing where the format has no metadata
  container.
- Increased the backup format version for the new book formats and made ZIP
  import detect physical truncation instead of silently accepting a partial
  archive. Optional settings/statistics JSON remains tolerant of malformed
  content when the archive itself is complete.
- Fixed list-mode library progress so the row background fills to the actual
  reading percentage, respects RTL layout, and uses the active theme palette.

### Metadata editing and sharing

- Added a complete in-file metadata editor for EPUB, FB2 and MOBI/KF8,
  including title, authors, description, genres, series, publisher, year,
  ISBN, translators, language and cover actions.
- Added book information pages, safe copy-on-write rewrites, generation
  checks, reread verification, cover replacement/removal, search refresh and
  Android sharing/export of an independent copy.
- Preserved unrelated book records, reading progress, quotes, bookmarks,
  ratings, reviews and embedded font resources during metadata edits.

### Reader layout and typography

- Reworked publisher geometry and native layout handling for margins, colors,
  backgrounds, alignment, line spacing, inline/replaced images, SVG, tables,
  drop caps, embedded fonts and legacy presentation attributes.
- Added shared H1–H6 defaults with font-relative sizes and asymmetric spacing.
  Headings use logical start alignment by default; explicit book alignment is
  respected, while ornament-only headings receive a centered default.
- Added the application setting **Center headings**, which overrides heading
  alignment across books without writing an app preference into individual book
  settings. Pagination keys include this preference, and old page maps are
  invalidated after layout changes.
- Fixed FB2 title/subtitle style inheritance and stopped the parser from
  forcing every title into the same centered style.
- Preserved publisher line-box leading at paragraph boundaries so zero-margin
  paragraphs keep their expected vertical rhythm.
- Added regression coverage for the supplied *Irresistible* EPUB and the
  heading cases across EPUB, FB2, MOBI6, KF8 and Markdown.

### Navigation and reading controls

- Added structured Contents hierarchy with collapse/expand behavior and stable
  navigation for nested entries.
- Refined the return-to-origin history for links, search, Contents, bookmarks,
  quotes, progress jumps and long continuous-mode scrolls.
- Page-origin entries from large jumps now expire after 15 seconds by default;
  the timeout honors accessibility recommendations and can be disabled in
  **Application settings → Reading → Auto-hide the return button**. Document
  and note navigation remains available as session history.
- Improved pagination metrics, exact position restoration, large-scroll
  detection and progress seeking. The bottom navigation bar can be controlled
  centrally while reader chrome remains independent.

### Translation selection

- Replaced the repeated generic text-processing chooser with a remembered
  selected-text handler. The first Translate action lists compatible installed
  apps; later actions launch the saved activity directly.
- Added **Application settings → Reading → Default translator** to change or
  reset the saved handler. Removed or unavailable handlers trigger selection
  again instead of sending text to an arbitrary application.
- Added tests for first-use selection, exact component validation, persistence,
  reset, removed handlers, save failures and Unicode selected text.

### Build, compatibility and verification

- Updated Android Gradle Plugin and Gradle versions used by the project.
- Kept Android 8.0/API 26 compatibility in the import and text-processing
  paths, and documented the narrow serialization keep-rule rationale.
- Added focused parser, backup, metadata, navigation, typography, settings,
  sharing and translation tests. The final local run completed successfully:
  920 unit tests, 0 failures, 5 skipped, and no Android Lint errors.

## ALPHA 2

ALPHA 2 is a major update to FrogReader's reading engine, navigation, and
format fidelity.

### Highlights

#### Smart “Back to page” navigation

- A contextual return button now appears after following an internal link,
  opening a result from search or the table of contents, jumping to a bookmark
  or quote, scrubbing progress, or making a long fling in continuous mode.
- Return restores the exact reading position in both paged and continuous
  modes instead of merely reopening the previous chapter.
- Navigation history also covers linked EPUB documents and nested footnotes.
- The button is placed on the right for comfortable one-handed use, stays clear
  of the reader controls, hides after a short prompt window, and can reappear
  with the reader controls while return history remains available.

#### Right-to-left reading

- Added right-to-left reading support for Arabic and Hebrew books.
- Mixed RTL/LTR structure is preserved for text, numbers, punctuation,
  headings, lists, links, footnotes, and table cells.
- EPUB and KF8 support includes `dir=auto`, `bdi`, `bdo`, CSS `direction`,
  `unicode-bidi`, and logical start/end alignment and margins.
- RTL-aware page progression, tap zones, selection-coordinate mapping, and
  navigation are implemented while search and copied text remain source-clean.
  Final glyph shaping and visual order remain part of the physical-device
  release check.

#### Major format-engine upgrade

- FB2, EPUB, MOBI6, and KF8/AZW3 are now exercised as four distinct engines
  against a shared 132-case FrogCompare corpus.
- Fixed all six heading levels: H1 through H6 now have distinct sizes, and rich
  headings preserve inline emphasis, links, scripts, and images.
- Improved publisher typography, font loading, colors, backgrounds, margins,
  alignment, line spacing, drop caps, inline images, SVG, and legacy HTML
  presentation attributes.
- MOBI6 and KF8 no longer share assumptions that are valid for only one Kindle
  generation.

### Notes, links, and navigation

- Footnotes now render as untruncated rich structured content rather than a
  short plain-text preview. They can contain headings, quotes, poems, lists,
  tables, images, publisher styles, and other links.
- Removed the previous three-paragraph and 700-character note limits.
- Ordinary internal links are no longer misclassified as footnotes.
- EPUB `linear="no"` documents remain outside book progress and search but can
  be opened through their links or table-of-contents entries.
- Improved EPUB NAV/NCX destinations, Unicode anchors, MOBI `filepos`, and KF8
  INDX navigation.

### Typography and rich content

- Expanded CSS cascade and inheritance, shorthand handling, variables,
  relative units, calculated sizes, imports, media rules, and embedded fonts.
- Added broad publisher-color support including modern and legacy CSS syntax,
  `currentColor`, transparency, HTML color attributes, and FB2 styles.
- Improved complex tables: cell typography, alignment, headers, borders,
  `rowspan`, `colspan`, inline images, page splitting, and a repeated first
  header row on continuations.
- Added readable native MathML for common fractions, roots, scripts, limits,
  fences, and matrices.
- Added EPUB 2 DTBook compatibility for headings, lists, poems, tables, SVG,
  CSS, anchors, and NCX navigation.
- Improved standalone and embedded SVG handling and added GIF decoding support.

### Reliability and security

- Added explicit size, count, depth, decompression, and expansion limits across
  ZIP/EPUB, FB2, PDB/MOBI, HUFF/CDIC, KF8, CSS, SVG/image resources, and
  embedded fonts, reducing the risk of excessive memory use or parser stalls
  from damaged or hostile books.
- Decorative resource failures now degrade locally where possible, while
  damage to required book content produces a controlled error.
- Hardened archive paths, resource naming, decompression, CSS expansion, DOM
  depth, and pagination-cache identity.
- Replaced an API-33-only format-sniff read with an API-26-compatible
  implementation, restoring this import path on Android 8–12.

### Known limitations

- Fixed-layout EPUB/KF8 rendering is not enabled yet. FrogReader recognizes the
  relevant EPUB metadata but continues to use its readable native fallback.
- KFX, KF8 panel magnification, vertical writing, audio, and video are not
  supported yet.
- MathML is rendered as a bounded native reading representation, not as a full
  browser-compatible two-dimensional layout engine.
- GIF animation, embedded-font behavior on older vendor ROMs, and final RTL
  shaping should receive a visual check on a physical device before release.

## ALPHA 1

- Initial public alpha release of FrogReader.
- Native EPUB, FB2, MOBI6, and KF8/AZW3 reading with paged and continuous modes.
- Library shelves, search, book details, text selection, quotes, bookmarks,
  backups, themes, widgets, and publisher typography.
