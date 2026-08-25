# Changelog

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
