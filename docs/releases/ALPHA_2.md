# FrogReader Alpha 2

Alpha 2 is a major upgrade to FrogReader's reading engine, navigation, and
format fidelity.

## Highlights

- **Smart “Back to page” navigation** restores the exact previous reading
  position after internal links, search, contents, bookmarks, quotes, progress
  scrubbing, and long continuous scrolling. It works in both paged and scroll
  modes, stays clear of the bottom controls, and is placed on the right for
  comfortable one-handed use.
- **Right-to-left reading preview** adds RTL-aware Arabic and Hebrew text,
  mixed-direction content, page progression, tap zones, selection mapping,
  tables, links, and logical alignment.
- **Major format-engine upgrade** substantially improves fidelity across FB2,
  EPUB, MOBI6, and KF8/AZW3, which are now tested as four distinct engines.
- **Correct H1–H6 headings** give all six levels distinct sizes while retaining
  rich inline formatting.
- **Rich footnotes** can contain headings, lists, poems, tables, images,
  publisher styles, and nested links without the previous length limit.
- **Improved publisher styling** covers more CSS, embedded fonts, colors,
  backgrounds, spacing, drop caps, tables, inline images, SVG, and legacy HTML.
- **MathML and EPUB 2 DTBook** add readable native mathematical notation and
  better compatibility with older accessible EPUB publications.
- **Stronger resilience** adds extensive limits and validation for archives,
  fonts, CSS, SVG, images, MOBI/KF8 structures, and damaged book resources.

## Additional improvements

- EPUB `linear="no"` documents open as linked surfaces without corrupting book
  progress, pagination, search, or completion.
- EPUB NAV/NCX, Unicode anchors, MOBI `filepos`, and KF8 INDX navigation are
  more accurate.
- Complex tables preserve cell typography, alignment, spans, inline images,
  page splitting, and repeated first header rows.
- An API-33-only format-sniff read was replaced with an API-26-compatible path
  for Android 8–12.
- FrogCompare now provides 132 aligned cases across separate FB2, EPUB, MOBI6,
  and KF8/AZW3 fixtures, plus an EPUB 2 DTBook fixture.

## Known limitations

- Fixed-layout EPUB/KF8, KFX, vertical writing, audio, video, and KF8 panel
  magnification are not enabled yet.
- MathML uses a bounded native reading representation rather than a complete
  browser-compatible two-dimensional layout engine.
- RTL shaping, animated GIFs, and embedded fonts on older vendor ROMs still
  benefit from physical-device verification.

## Verification

- 761 JVM tests discovered: 757 executed successfully and 4 optional
  real-book diagnostics skipped because their private fixtures were absent.
- Debug and release lint: 0 errors.
- Optimized R8 release APK verified for Android 8.0+ (minSdk 26), signature
  continuity with Alpha 1, and ZIP alignment.

Download and install `FrogReader-alpha-2.apk` on Android 8.0 or newer.
