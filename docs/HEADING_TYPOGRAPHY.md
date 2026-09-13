# Native heading typography

The shared heading defaults apply to EPUB, FB2, MOBI6, KF8/AZW3 and Markdown. TXT remains literal text and does not synthesize headings.

## Behavior

- Textual headings with no author alignment use logical start: left for left-to-right text and right for right-to-left text. Level alone does not imply centering.
- Author alignment, including inherited alignment, wins by default. Explicit center, left, right, start, end and justify remain distinct.
- App Settings → Reading → Center headings is an explicit user override for all six levels. It defaults off, applies across books even when they have individual reader settings, and overrides authored alignment when enabled. This preference belongs to `AppSettings`; the effective `ReaderSettings` only carries a transient projection for shared rendering and pagination. The pagination key includes its value.
- Short ornament-only headings such as `* * *`, `⁂` and `❦` default to center. They remain overridable by author alignment.
- H1–H6 use 1.50, 1.32, 1.18, 1.10, 1.04 and 1.00 times the chosen body size. These are phone-oriented defaults, not a specification requirement. Explicit author sizes can be smaller or larger.
- Default top/bottom gaps in body-font units are H1 1.40/0.55, H2 1.20/0.50, H3 1.00/0.45 and H4–H6 0.85/0.40. The smaller lower gap associates the heading with the following content. Gaps follow the selected font size instead of remaining fixed at 28 dp.
- Author margins replace the corresponding default edge in publisher mode. HTML publisher boxes own their numerical margins; FB2 heading leaves own theirs, including explicit zero margins.
- Headings retain the existing keep-with-next pagination behavior and disabled automatic hyphenation. Both reading modes and pagination use `ReaderMetrics`; layout engine version 13 invalidates old page maps. Publisher text blocks retain the full line-height leading at their boundaries (`Trim.None`), so zero-margin paragraphs keep the same line rhythm as their interior lines.
- FB2 titles now carry stylesheet/inline title block properties. Child title paragraphs inherit their title style and apply relative inline sizes once. Native FB2 title/subtitle elements no longer inject centered alignment into the model.

## Comparison used for this decision

Sources inspected on 2026-09-13. This is source-level comparison, not a claim of screenshot parity with installed competing applications.

- [Readium CSS defaults](https://readium.org/css/docs/CSS08-defaults.html) separate default styles for unstyled EPUBs from publication styling. Its [quickstart](https://readium.org/css/docs/CSS02-quickstart.html) describes the user/publisher/reading-system precedence. Readium CSS is an EPUB reference, not an FB2 or MOBI renderer.
- [KOReader EPUB stylesheet](https://github.com/koreader/crengine/blob/master/cr3gui/data/epub.css) uses font-relative asymmetric heading margins, disables heading hyphenation, and reduces its hierarchy from 150% to 100%. Its [FB2 stylesheet](https://github.com/koreader/crengine/blob/master/cr3gui/data/fb2.css) centers title/subtitle elements. There is no single alignment shared by all its format styles.
- Librera uses MuPDF, with generated styles passed through [MuPdfDocument](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/java/org/ebookdroid/droids/mupdf/codec/MuPdfDocument.java). Its [default stylesheet](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/assets/app-Librera.css) centers all H1–H6 and marks headings to avoid a break after them. Its [BookCSS](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/java/com/foobnix/pdf/info/model/BookCSS.java) distinguishes document+user, document-only and user-only modes. Thus centered headings are a legitimate house style, not a universal correctness rule.

FrogReader chooses a neutral shared default for both prose and technical documents, while keeping explicit book design authoritative. The exact size/spacing scale is our design choice; it is not copied from another reader.

## Verification

`HeadingFormatTest` runs identical numbered cases through EPUB, FB2, MOBI6 and KF8 files, checking all six default levels and authored centering, size, weight, line height and zero margins; Markdown tests the same default hierarchy. `ReaderMetricsTest` covers RTL-aware alignment, font-relative spacing, ornament handling, author alignment and consistent pagination/rendering metrics. Existing parser, publisher-box and pagination tests guard the surrounding behavior.

Device review: reopen the already imported Markdown test, inspect MD-01/02 and MD-38/39, then compare a book with explicitly centered chapter titles. Check paged and scrolling modes, publisher formatting and enlarged text. Automated tests do not establish visual parity on the phone.
