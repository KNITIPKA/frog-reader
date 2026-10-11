# Native heading typography

EPUB, FB2, MOBI6, KF8/AZW3 and Markdown share a font-relative heading hierarchy, while alignment follows the publication and its format. TXT remains literal text and does not synthesize headings.

## Behavior

- Unstyled HTML headings in EPUB/MOBI/KF8 and native Markdown headings use logical start: left for left-to-right text and right for right-to-left text. Level alone does not imply centering.
- FB2 titles and subtitles use a centered native default, including book, section, poem and stanza titles. This is a reader convention for FB2's semantic markup, not a requirement of the FB2 schema.
- Author alignment, including inherited alignment, wins in paged and scrolling modes, independently of the publisher typography toggle. Explicit center, left, right, start, end and justify remain distinct.
- Legacy HTML `align` attributes and `<center>` participate in alignment resolution at their owning element. An element's own CSS overrides its hint; a hint overrides inherited alignment. Explicit `inherit`, `unset`, `initial` and `match-parent` are resolved before mapping to native headings.
- Native Markdown syntax does not encode heading alignment. Its existing literal-HTML policy is unchanged.
- Short ornament-only headings such as `* * *`, `⁂` and `❦` default to center. They remain overridable by author alignment.
- H1–H6 use 1.50, 1.32, 1.18, 1.10, 1.04 and 1.00 times the chosen body size. These are phone-oriented defaults, not a specification requirement. Explicit author sizes can be smaller or larger.
- Default top/bottom gaps in body-font units are H1 1.40/0.55, H2 1.20/0.50, H3 1.00/0.45 and H4–H6 0.85/0.40. The smaller lower gap associates the heading with the following content. Gaps follow the selected font size instead of remaining fixed at 28 dp.
- Author margins replace the corresponding default edge in publisher mode. HTML publisher boxes own their numerical margins; FB2 heading leaves own theirs, including explicit zero margins.
- Headings retain the existing keep-with-next pagination behavior and disabled automatic hyphenation. Both reading modes and pagination use `ReaderMetrics`; layout engine version 15 invalidates old page maps. Publisher text blocks retain the full line-height leading at their boundaries (`Trim.None`), so zero-margin paragraphs keep the same line rhythm as their interior lines.
- FB2 titles carry stylesheet/inline title block properties. Child title paragraphs inherit their title style, preserve their own alignment as paragraph ranges and apply relative inline sizes once.
- Layout maps the explicit title paragraphs to Compose's paragraph boundaries without adding the source newlines a second time. Source text, authored empty lines and selection offsets remain intact; inline bidi controls stay inside their paragraph ranges.
- Uniformly aligned title paragraphs share one native paragraph, preserving the original line-height rhythm. Separate native paragraphs are used only when their alignments differ.
- The retired global heading override is absent from the UI, settings models, rendering, diagnostics and pagination keys. A one-time DataStore migration removes its old preference; backup import ignores the obsolete JSON field while retaining the remaining settings and annotations. The backup format version is unchanged.

## Comparison used for this decision

Sources inspected on 2026-09-13. This is source-level comparison, not a claim of screenshot parity with installed competing applications.

- [Readium CSS defaults](https://readium.org/css/docs/CSS08-defaults.html) separate default styles for unstyled EPUBs from publication styling. Its [quickstart](https://readium.org/css/docs/CSS02-quickstart.html) describes the user/publisher/reading-system precedence. Readium CSS is an EPUB reference, not an FB2 or MOBI renderer.
- [KOReader EPUB stylesheet](https://github.com/koreader/crengine/blob/master/cr3gui/data/epub.css) uses font-relative asymmetric heading margins, disables heading hyphenation, and reduces its hierarchy from 150% to 100%. Its [FB2 stylesheet](https://github.com/koreader/crengine/blob/master/cr3gui/data/fb2.css) centers title/subtitle elements. There is no single alignment shared by all its format styles.
- Librera uses MuPDF, with generated styles passed through [MuPdfDocument](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/java/org/ebookdroid/droids/mupdf/codec/MuPdfDocument.java). Its [default stylesheet](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/assets/app-Librera.css) centers all H1–H6 and marks headings to avoid a break after them. Its [BookCSS](https://github.com/foobnix/LibreraReader/blob/master/app/src/main/java/com/foobnix/pdf/info/model/BookCSS.java) distinguishes document+user, document-only and user-only modes. Thus centered headings are a legitimate house style, not a universal correctness rule.

FrogReader keeps explicit book alignment authoritative and uses defaults appropriate to the source format when alignment is absent. The exact size/spacing scale is our design choice; it is not copied from another reader. [CSS Text](https://www.w3.org/TR/css-text-3/#text-align-property) defines inherited `text-align` with an initial value of `start`; the [FB2 schema](https://github.com/gribuser/fb2/blob/master/FictionBook.xsd) describes semantic titles and optional stylesheets without prescribing universal centered rendering.

## Verification

`HeadingFormatTest` runs actual EPUB, FB2, MOBI6 and KF8 files through their parsers, checking all six default levels, inherited author alignment, legacy HTML hints, authored size, weight, line height and zero margins. It also checks FB2 subtitles/poem/stanza titles, independently aligned title paragraphs and Markdown's default hierarchy. `ReaderMetricsTest` covers RTL-aware alignment, font-relative spacing, ornament handling and author alignment with publisher typography on and off. Settings and backup tests cover removal of the obsolete preference and restoration of old backups. Existing parser, publisher-box and pagination tests guard the surrounding behavior.

On 2026-10-08, all four `HeadingLayoutTest` instrumentation cases passed on Pixel 9a using the real Compose text measurer. They verify left/right/center alignment without extra lines, authored empty lines at different font sizes with publisher typography on/off, preservation of line metrics in uniformly aligned titles, and paragraph boundaries containing inline bidi controls.

Device review: reopen the already imported Markdown test, inspect MD-01/02 and MD-38/39, then compare a book with explicitly centered chapter titles. Check paged and scrolling modes, publisher formatting and enlarged text. Automated tests do not establish visual parity on the phone.
