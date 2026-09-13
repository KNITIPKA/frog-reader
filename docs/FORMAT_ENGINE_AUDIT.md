# FrogReader Format Engine Audit

Snapshot date: 2026-08-31. Scope: FB2 2.x, EPUB 2 / EPUB 3.3,
legacy MOBI6/7, and Kindle Format 8 (KF8/AZW3). Audio and video in EPUB/KF8
are intentionally deferred per product owner decision.

## What Exactly Constitutes the Four Formats

A file extension does not always identify the internal format. A `.mobi` file may contain
only legacy MOBI6/7 or be a combo-container holding both MOBI6 and KF8; `.azw3` usually
contains KF8 only. Therefore, FrogReader must identify the section using PDB/MOBI headers
rather than choosing the engine based solely on the file extension.

This audit covers four independent verification pipelines:

1. FB2 2.x — FictionBook XML semantics.
2. EPUB 2/3 — OPF + XHTML/SVG + CSS.
3. MOBI6/7 — PalmDOC/PDB + legacy HTML/filepos.
4. KF8/AZW3 — PDB/KF8 + HTML5/CSS/FDST/INDX.

KFX is not synonymous with KF8. It is a separate, modern proprietary Amazon format;
FrogReader's current import does not claim support for it, and this audit does not count
it as the "fourth" format.

## Methodology and Parity Criteria

Verification spans five layers: normative format capabilities, container unpacking and
package structure, transformation into the shared `BookContent` model, Jetpack Compose
measurement and rendering, followed by manual verification on a physical device.
A passing parser test is not considered proof if the shared model or renderer subsequently
drops the result; having a decoder dependency is not considered proof of actual animation
on a phone.

Evidence levels in this document:

1. **Spec** — feature is confirmed by the format's normative specification.
2. **Parser/model** — fixture proves that semantics and data reached `BookContent`
   without spurious normalization.
3. **Measure/render** — test proves that pagination and rendering share identical
   metrics and do not discard preserved styling.
4. **Device** — a real book manually verified by the owner on a Pixel 9a.

The matrix below reflects implemented code and automated evidence. Capabilities that
still require a device-gate (primarily GIF, complex SVG, embedded fonts, and very wide
tables) are explicitly not claimed as fully visually verified.

Each capability uses one classification marker:

- ✅ — supported by format, current FrogReader pipeline preserves and renders;
- ◐ — supported by format, support is partial or includes intentional degradation;
- ❌ — supported by format, not yet supported by the reader;
- Ø — unsupported by format, or the element does not exist in its standard;
- ⚠ — depends on undocumented/variable implementations and requires an actual book to verify.

"Parity" does not mean inventing CSS for FB2 or MathML for MOBI6. The goal is that a single
authored book, properly exported to all four formats, must preserve identical semantic blocks,
ordering, navigation, images, and as close typography as possible within the capabilities of
each format.

## Architectural Conclusion

Currently, FrogReader is a normalizing reflow engine. Its semantic paragraph, heading,
image, table, and spacer elements remain flat, providing unified pagination, search,
selection, theming, and bookmarks. In parallel, a hierarchy of `PublisherBoxSpan`
structures is now maintained: nested authored containers can preserve background,
physical margin/padding/border, width/alignment, `break-inside:avoid`, float/clear,
and continuation boundaries when splitting chapters, without breaking stable leaf coordinates.

This is a bounded native CSS box model, not a browser engine. It still cannot express
fixed-layout, full two-dimensional MathML layout, vertical writing, absolute positioning,
or complex SVG/HTML accessibility trees. For MathML, a bounded native fallback keeps
formulas readable and styled without pretending to be a browser math layout engine.

Therefore, the path toward a truly best-in-class engine is dual-track:

1. Maintain Compose-reflow as the primary fast and responsive pipeline for prose.
2. Add a publisher-layout surface for fixed-layout, full MathML fidelity, and documents
   requiring web/SVG layout; selection must be driven by package metadata and actual
   capabilities used, rather than file extension.

Without the second track, promising full EPUB/KF8 parity would be technically disingenuous.
The fixes in this pass maximize the safe shared core and explicitly establish the remaining boundary.

## Fixes Introduced Based on Audit Findings

- Headings now store `AnnotatedString`: strong/emphasis, hyperlinks,
  superscript/subscript, and inline images are no longer flattened to plain strings.
- Pagination and renderer measure and draw rich headings with the same placeholders
  as paragraphs.
- H1–H6 have six distinct sizes in the shared renderer; FB2 structural depth,
  EPUB/KF8 `<h1>`…`<h6>`, and legacy MOBI pass through the exact same metric path.
- Regular fragment links are decoupled from true `noteref`; popup footnotes no
  longer swallow table of contents entries or cross-references.
- Inline `<img>` stays in its original text position; large and floating images
  remain block/floated without duplication. When a large inline image is split into
  its own native leaf, the containing block's `text-align` continues to dictate its
  physical placement; explicit `display:block`, float, and auto margins preserve
  their own CSS semantics.
- Embedded SVG is preserved in full, including concurrent vector shapes, text,
  and raster `<image>`; local raster images inside serialized SVG are embedded as
  data URIs.
- Standalone SVG items in the EPUB spine open as full image content pages.
- `<pre>` preserves whitespace and line breaks, using monospace typography.
- GIF decoder is integrated for API 26+, and the parser retains GIF assets as GIF; actual
  animation playback remains a mandatory manual device-gate check.
- `alt`, `aria-label`, and `title` reach block-image accessibility; for missing
  resources, text fallbacks remain visible.
- CSS `@import` in EPUB is resolved recursively relative to the importing file,
  with cycle protection.
- KF8 `kindle:flow` `@import` is processed separately from MOBI6, supporting Kindle media
  queries, source order, repeated imports, cycle protection, and safeguards against
  adversarial resource graphs.
- Inline `style=` attributes work even in XHTML/KF8 documents without a separate
  `<link rel="stylesheet">` or `<style>` block.
- An explicit short leading text span with `float:left/right` converts to the same
  SideBox structure as `::first-letter`: EPUB/MOBI6/KF8 preserve exact glyph,
  side, scale, font, color, language/direction without duplicating letters. This
  provides KF8 with a portable drop cap without requiring unsupported `::first-letter`.
- CSS media types now distinguish screen/print/speech, `not screen`, `not print`,
  and comma alternatives; print rules do not leak into the reader.
- EPUB resolves manifest fallback chains, honors declared `spine@toc` NCX,
  tokenized `properties`, literal `+` signs in ZIP paths, and standalone SVG spine items.
- Multiple EPUB nav/NCX targets inside a single XHTML become distinct chapters;
  percent-encoded Unicode fragments resolve to actual XML IDs.
- EPUB `spine itemref linear="no"` is stored outside the main reading order and opens
  only via typed hyperlink/TOC targets in a separate transient surface; normal progress,
  search, pagination, and book completion are unaffected.
- Legacy FB2EPUB notes are recognized solely through combined converter metadata,
  bracketed markers, `ch2-N.xhtml`, and fragments — avoiding brittle global heuristics.
- FB2 fixes first-body semantics, full poem/stanza/subtitle/date, rich titles,
  inline images in poems/tables, SVG/GIF MIME extraction, image alt fallbacks,
  regular anchors, and link/noteref separation.
- FB2 root `text/css` stylesheets, literal `style=`, and named `<style name>`
  undergo a bounded XML-aware cascade; `xml:lang` inherits from body and sections
  down to blocks and individual inline runs.
- Reflow bidi now follows a unified pipeline for FB2/EPUB/MOBI6/KF8: inherited
  HTML `dir=ltr/rtl/auto`, CSS `direction`/`unicode-bidi`, `bdi`/`bdo`, headings,
  lists, and table cells reach Compose layout. Internal UBA controls are created only
  in a temporary layout string with an explicit offset map; source text for search,
  copying, links, and footnotes remains unmodified. Logical `start/end` and physical
  `left/right` are not conflated; their cascade does not double identical margins.
  Horizontal book geometry is independent of Android UI locale, and the first
  authored column of an RTL table renders on the right.
- MOBI6 and KF8 independently filter `amzn-mobi`/`amzn-kf8`, retain legacy CSS,
  NCX/INDX navigation, and decouple filepos navigation from true note markers.
- Legacy HTML `align=` and `<center>` inherit across blocks; CSS retains precedence.
- Legacy `<font size/face>` no longer loses size or generic font family mappings.
- Table typography (scale/family/line-height/bold/italic/lang/direction) now participates
  in both measurement and rendering, rather than outer margins alone.
- Inline images inside table cells participate in min/max intrinsic measurement and
  render through the shared rich-text pipeline; wide images are no longer collapsed to
  the width of U+FFFC nor clipped by column allocators.
- Semantic footnotes now store a complete `NoteDocument` rather than only the first
  three paragraphs or 700 characters: popups use the shared renderer for headings, lists,
  blockquotes, poems, tables, block/inline images, CSS, and note-to-note hyperlinks.
- EPUB 2 DTBook (`application/x-dtbook+xml`) passes through a true manifest/spine/NCX
  pipeline: levels, lists, poems, tables, SVG, anchors, and CSS do not require a
  spurious XHTML media type.
- Presentation MathML provides a bounded native formatter for scripts, fractions,
  roots, fences, limits, matrices, semantics/accessibility fallbacks, anchors, and links;
  inline/display placement is preserved.
- CSS cascade now correctly resolves margin/font shorthands, relative line-height,
  nested imports/media, selector work limits, and repeated aliases; the FB2 compatibility
  profile follows the same core cascade rules.
- On API 26–32, book sniffing no longer invokes API 33-only `InputStream.readNBytes`;
  a compatible bounded prefix reader has been added.
- Publication-wide resource budgets have been introduced for ZIP/FB2/PDB records,
  decompression, CSS/import expansion, DOM/generated content, fonts/WOFF, HUFF dictionaries,
  KF8 assembly/index/markers, and SVG resources. Corrupted decorative assets degrade locally,
  while corrupted mandatory content yields a controlled error.
- CSS `color`/`background-color`, legacy `color`/`text`/`bgcolor`, and FB2 styles
  are preserved across blocks, inline runs, headings, first-letter drop caps, tables/cells,
  and rich notes. The publisher toggle strips them completely; single authored colors
  receive contrast-safe counterparts, while complete foreground/background pairs are
  preserved without unprompted recoloring.
- HTML containers now remain flat for the semantic reader API, but their nested geometry
  is stored via separate half-open `PublisherBoxSpan` intervals. A parent background is no
  longer duplicated onto each child paragraph, rendering once around the range.
- Direct text in structural `div`/`section` elements creates an anonymous CSS block rather
  than a default reader paragraph: spurious first-line indents and paragraph gaps are omitted,
  while explicit or inherited `text-indent` is preserved.
- The native publisher-box planner retains nested backgrounds, margin/padding, basic
  solid/dashed/dotted/double borders, percentage/font-relative widths, centering, and
  `break-inside:avoid`; measurement, pagination cache, and painting utilize an identical model.
- Basic `float:left/right` now applies beyond single drop caps or standalone images: safe
  short container/figure blocks can be wrapped by adjacent text. `clear:left/right/both`
  terminates wrapping, and unsafe combinations fall back safely to normal flow instead
  of losing content.
- Authored table cell geometry is stored alongside its text styling: padding and border
  are separated, including explicit `padding:0` and `border:none`; intrinsic measurement,
  cross-page splitting, and rendering account for this geometry. An unbreakable
  row/rowspan group taller than the available page height is not clipped: that specific
  page receives a local vertical scroll container as an emergency fallback, keeping all
  content intact and other pages normal.

## Real-World Regression: *The Math Book*

The file that exhibited discrepancies against third-party readers was inspected as a full
package rather than through isolated screenshots. It is a reflowable EPUB, not
pre-paginated or fixed-layout:

- 122 XHTML content documents;
- 450 raster assets and 477 image references;
- Two actual HTML tables; several visual tables and diagrams on pages are actually JPEGs;
- No embedded fonts, SVG, MathML, audio, or video.

Therefore, font discrepancies cannot be resolved by "extracting the book font": none is present
in the package. FrogReader must preserve authored size/weight/style/family hints without
fabricating a nonexistent font family.

The observed failure was in the native reflow pipeline: styled container backgrounds were duplicated
onto leaf paragraphs, floating groups lost coordination between images and captions/text, and
table cell padding/borders failed to reach the shared measure/render pipeline. The fix does not
check the title, ISBN, or class names of this specific book: all production logic relies on
standard DOM/CSS semantics applicable to future textbooks.

`MathBookPublisherRegressionTest` conditionally opens the local original file and asserts that gray,
green, and pink panels, white rules, unindented rule labels, left floats, centered 80% images,
90% images, and actual cell geometry reach the shared model. The test is skipped if the personal
EPUB is not present on the machine, complementing rather than replacing synthetic deterministic
fixtures. Until the device-gate below passes, this serves as evidence of parser/model/measure
policy compliance rather than pixel-identical visual parity.

## Unified Numbered Capability Checklist

Identical numbers are deliberately used across all four columns. In this way, any
regression corpus and manual book can refer to the same case ID regardless of the container.

### A. Container, Metadata, and Resources

| № | Capability | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 1 | Signature / container detected by content | ✅ | ✅ | ✅ | ✅ |
| 2 | Correct text character encoding | ✅ | ✅ | ✅ | ✅ |
| 3 | Compression and safe resource bounds | ✅ | ✅ | ✅ | ✅ |
| 4 | Encryption / DRM | Ø | ◐ | 🔒 | 🔒 |
| 5 | Title and primary author | ✅ | ✅ | ✅ | ✅ |
| 6 | Multiple authors / contributors / translators | ✅ | ◐ | ◐ | ◐ |
| 7 | Publisher, date/year, ISBN, subjects/genres | ✅ | ✅ | ✅ | ✅ |
| 8 | Series / collection | ✅ | ✅ | Ø | Ø |
| 9 | Annotation / description | ◐ | ◐ | ◐ | ◐ |
| 10 | Cover and thumbnail | ◐ | ✅ | ◐ | ◐ |
| 11 | Keywords, provenance, rights, roles, refinements | ◐ | ◐ | ◐ | ◐ |
| 12 | Global publication language | ✅ | ✅ | ✅ | ✅ |
| 13 | Multiple renditions of a single publication | Ø | ❌ | Ø | Ø |
| 14 | Manifest / resource fallback chain | Ø | ✅ | Ø | ⚠ |
| 15 | Embedded / obfuscated fonts | Ø | ✅ | Ø | ✅ |

`🔒` denotes an intentional refusal to implement DRM, rather than an attempt to circumvent protection. EPUB
supports standard IDPF/Adobe font obfuscation, but not commercial DRM.

### B. Order, Chapters, Navigation, and Links

| № | Capability | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 16 | Authored primary reading order | ✅ | ✅ | ✅ | ✅ |
| 17 | Supplementary / non-linear bodies and resources | ◐ | ✅ | Ø | ◐ |
| 18 | Nested section/chapter hierarchy | ✅ | ✅ | ✅ | ✅ |
| 19 | TOC/NCX/INDX labels and depth | ✅ | ✅ | ✅ | ✅ |
| 20 | Multiple TOC targets in a single content file | ✅ | ✅ | ✅ | ◐ |
| 21 | Page-list, landmarks, guide/start-reading | Ø | ❌ | ❌ | ❌ |
| 22 | IDs/anchors on arbitrary blocks | ◐ | ✅ | ✅ | ✅ |
| 23 | Standard internal cross-references | ✅ | ✅ | ✅ | ✅ |
| 24 | Semantic footnotes / endnotes | ✅ | ✅ | ✅ | ✅ |
| 25 | Backlink remains navigation, not a popup note | ✅ | ✅ | ✅ | ✅ |
| 26 | Secure HTTP(S)/mailto/tel links | ✅ | ✅ | ✅ | ✅ |
| 27 | EPUB CFI / Kindle locations / portable ranges | Ø | ❌ | ❌ | ❌ |
| 28 | Author page breaks before block | ◐ | ✅ | ✅ | ✅ |
| 29 | Break after/inside, widows/orphans | Ø/◐ | ◐ | ◐ | ◐ |
| 30 | RTL page progression / spreads | ◐ | ◐ | ◐ | ◐ |

EPUB `linear="no"` is not intermixed with regular chapters or reading progress: a standard XHTML hyperlink
or nav/NCX row opens the document separately, and Back returns to the prior location.
Popup notes store a `NoteDocument` and use the shared `RenderPart`, ensuring tables, images, lists,
poems, headings, and links are not flattened.

The reflow reader mirrors physical page progression, tap zones, and selection auto-turn for RTL.
EPUB `page-progression-direction` takes precedence; FB2, MOBI6, and KF8 without an explicit preserved
directive safely infer progression direction from the book's language. Authored spreads currently
belong to the deferred fixed-layout layer, which is why row 30 remains partial rather than full support.

All formats share a unified, browser-like internal navigation history: links, TOC, search, bookmarks,
quotes, progress scrubbing, and accidental scroll jumps exceeding two screenfuls preserve the return position.
The contextual back button is physically located on the right, accounts for the actual height and animation
of the bottom navigation bar, disappears after a short interval, while system Back remains available for a
bounded period. Positions for main reading, EPUB `linear="no"`, and rich notes are tracked separately; in
scroll mode, text anchors restore after width or font adjustments, while non-text blocks use pixel fallbacks.

### C. Text Structure and Inline Formatting

| № | Capability | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 31 | Paragraphs and mixed inline content | ✅ | ✅ | ✅ | ✅ |
| 32 | Rich multi-line headings/titles | ✅ | ✅ | ✅ | ✅ |
| 33 | Subtitle | ✅ | ◐ | ◐ | ◐ |
| 34 | Bold/strong and italic/emphasis | ✅ | ✅ | ✅ | ✅ |
| 35 | Underline, strike/del/ins | ◐ | ✅ | ✅ | ✅ |
| 36 | Superscript/subscript | ✅ | ✅ | ✅ | ✅ |
| 37 | Code/monospace and preformatted whitespace | ✅/Ø | ✅ | ✅ | ✅ |
| 38 | Quote/blockquote/cite/epigraph | ✅ | ✅ | ✅ | ✅ |
| 39 | Poem, stanza, verse, text-author, date | ✅ | ◐ | Ø/◐ | ◐ |
| 40 | Ordered/unordered/nested lists | Ø | ✅ | ✅ | ✅ |
| 41 | Definition lists | Ø | ◐ | ◐ | ◐ |
| 42 | Ruby | Ø | ◐ | Ø | ◐ |
| 43 | `<q>` with language-aware quotation marks | Ø | ✅ | Ø/⚠ | ✅ |
| 44 | `<mark>` visible highlighting | Ø | ◐ | Ø/⚠ | ◐ |
| 45 | `<wbr>` break opportunity | Ø | ✅ | Ø | ✅ |
| 46 | `<nobr>` / CSS white-space modes | Ø | ❌ | ◐ | ❌ |
| 47 | Generated `::before`/`::after` strings | Ø | ◐ | ⚠ | Ø/⚠ |
| 48 | Drop caps: `::first-letter` / explicit floated span | Ø | ◐ | ◐ | ◐ |
| 49 | Block `lang`/`xml:lang` | ✅ | ✅ | ◐ | ✅ |
| 50 | Inline span language | ✅ | ✅ | ◐ | ✅ |
| 51 | `dir`/CSS `direction` on block | Ø/◐ | ✅ | ◐ | ✅ |
| 52 | `bdi`/`bdo`/`unicode-bidi` | Ø/◐ | ✅ | ◐ | ✅ |
| 53 | Vertical writing / text orientation / combine | Ø | ❌ | Ø | ⚠ |

Ruby currently renders via a sensible degradation of "base text + small superscript rt",
rather than full interlinear ruby typography. Bidi isolation/override operates in native
reflow, whereas vertical CJK writing continues to require a high-fidelity renderer.

FB2 2.x lacks standardized `dir`, `bdi`, or `unicode-bidi` elements: FrogReader can infer
block direction from `xml:lang` and isolate an inline span explicitly tagged with another
language, but cannot reconstruct unexpressed author overrides. Legacy MOBI6 likewise provides
no reliable contract for modern HTML5 isolates: basic `dir`/CSS and preserved markup are
handled if they actually reached the content. EPUB and KF8 express these semantics more fully.
Within a single flattened native paragraph, CSS `unicode-bidi: plaintext` uses a safe
first-strong isolate; a separate reset at each internal CSS paragraph boundary is not possible
once the source box has been flattened by the model. Automated tests demonstrate
parser/model/offset/measure behavior; shaping complex Arabic ligatures and mixed selection
still requires a manual device-gate on a Pixel 9a.

### D. CSS and Reflowable Layout Geometry

| № | Capability | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 54 | Stylesheet and inline `style` | ◐ | ✅ | ◐ | ✅ |
| 55 | Cascade/specificity/inheritance/`!important` | ◐ | ◐ | ◐ | ◐ |
| 56 | Class/id/tag/attribute/combinator selectors | ◐ | ◐ | ◐ | ◐ |
| 57 | Structural pseudo-classes | Ø | ◐ | ⚠ | Ø/⚠ |
| 58 | CSS custom properties and `calc()` subset | Ø/⚠ | ◐ | ⚠ | ⚠ |
| 59 | Local recursive `@import` | Ø/⚠ | ✅ | Ø/⚠ | ✅ |
| 60 | Screen/print and Kindle media types | Ø/◐ | ◐ | ✅ | ✅ |
| 61 | Device width/aspect/orientation queries | Ø | ❌ | Ø/⚠ | ❌ |
| 62 | Font family/style/weight/size | ◐ | ◐ | ◐ | ◐ |
| 63 | Inline named embedded font family | Ø | ◐ | Ø | ◐ |
| 64 | Line-height and hyphenation hints | ◐ | ◐ | ◐ | ◐ |
| 65 | Text align/justify/indent | ✅ | ✅ | ✅ | ✅ |
| 66 | Margins/padding/centered boxes | ◐ | ◐ | ◐ | ◐ |
| 67 | Foreground color and background | ✅ | ✅ | ✅ | ✅ |
| 68 | Basic borders / radius, outline, shadow | ◐ | ◐ | ◐ | ◐ |
| 69 | Letter/word spacing, transform, text-shadow | ❌ | ❌ | ❌ | ❌ |
| 70 | Image/container float and basic text wrapping | Ø/◐ | ◐ | ◐ | ◐ |
| 71 | Clear/overflow/object-fit/min-max/aspect-ratio | Ø | ◐ | ◐ | ◐ |
| 72 | Absolute/fixed positioning, z-index, transform | Ø | ❌ | Ø | ❌ |
| 73 | Flex/grid/columns | Ø | ❌ | Ø | Ø/⚠ |
| 74 | Full table CSS / border-collapse / layout | ◐ | ◐ | ◐ | ◐ |
| 75 | `@page`, named pages, page floats | Ø | ❌ | Ø | Ø/⚠ |

FB2 stylesheet support is a compatibility profile on top of semantic XML, not a browser mandate.
EPUB/KF8, in contrast, genuinely support vastly more CSS, so incomplete rows 68–75 represent
reader gaps rather than format limitations. In row 68, physical basic borders are implemented,
but not radius/outline/shadow; in row 71, only basic `clear` is supported; in row 74, cell
padding/background/borders are supported without the full browser `border-collapse` model,
colgroup, or nested layout.

### E. Images, SVG, Tables, and Special Modes

| № | Capability | FB2 | EPUB | MOBI6 | KF8/AZW3 |
|---:|---|:---:|:---:|:---:|:---:|
| 76 | JPEG/PNG and block images | ✅ | ✅ | ✅ | ✅ |
| 77 | GIF animation | ◐ | ◐ | ⚠ | ⚠ |
| 78 | WebP/BMP/AVIF tolerant static decode | ◐ | ◐ | ⚠ | ⚠ |
| 79 | Inline images in original text position | ✅ | ✅ | ✅ | ✅ |
| 80 | Float images without duplication | Ø/◐ | ✅ | ✅ | ✅ |
| 81 | Width/height/aspect preservation | ◐ | ◐ | ✅ | ✅ |
| 82 | `alt`/`aria-label`/`title`, missing fallback | ◐ | ◐ | ◐ | ◐ |
| 83 | SVG binary / by-reference | ✅ | ◐ | Ø/⚠ | ◐ |
| 84 | Mixed inline SVG shapes + text + raster image | Ø | ◐ | Ø | ◐ |
| 85 | Standalone SVG content page | Ø | ◐ | Ø | ❌ |
| 86 | SVG links/search/CSS/fonts/resource origin | Ø | ❌ | Ø | ❌ |
| 87 | Table grid/header/caption | ◐ | ✅ | ✅ | ✅ |
| 88 | Colspan/rowspan | ◐ | ◐ | ◐ | ◐ |
| 89 | Cell align | ✅ | ✅ | ✅ | ✅ |
| 90 | Cell vertical align / colgroup / complex nested cells | ❌ | ❌ | ❌ | ❌ |
| 91 | Table splitting and repeated header | ✅ | ✅ | ✅ | ✅ |
| 92 | Presentation MathML | Ø | ◐ | Ø | Ø |
| 93 | Fixed-layout pages/spreads/orientation | Ø | ❌ | Ø | ❌ |
| 94 | KF8 panels / region magnification / text popups | Ø | Ø | Ø | ❌ |
| 95 | Script/forms/canvas/iframe | Ø | ❌/optional | Ø | Ø |
| 96 | Audio/video/media overlays | Ø | ⏸ | Ø | ⏸/Ø |
| 97 | Semantic accessibility tree / ARIA / DPUB-ARIA | ◐ | ◐ | Ø/◐ | ◐ |
| 98 | Search/selection across normal text | ✅ | ✅ | ✅ | ✅ |
| 99 | Search/selection across SVG/MathML/nonlinear content | Ø | ◐ | Ø | ❌ |
| 100 | Large-file bounds, damaged input degradation | ◐ | ◐ | ◐ | ◐ |

`⏸` marks explicitly deferred audio/video features. For KF8, the official support table also
marks standard HTML audio/video as unsupported, despite historical Kindle publishing workflows.

## Profile of Each Engine

### FB2 2.x

FB2 primarily describes a book's semantics through XML elements: body/section/title,
epigraph/cite, poem/stanza/v, annotation, auxiliary bodies for notes, basic tables,
and binary resources. The XSD permits arbitrary root `stylesheet` declarations, literal `style`
attributes on text/table elements, named inline `<style name>`, and `xml:lang`, but does not
specify a mandatory browser layout engine. Thus, the absence of flexbox/grid is not an FB2 reader
defect; losing valid `style` declarations, tables, language tags, or rich titles is.

The current native pipeline preserves all core structure, deep section levels down to H6,
rich titles/subtitles, poems, tables, links/footnotes, inline/block images, SVG/GIF binaries,
complete rich note bodies, and a bounded CSS compatibility profile. The remaining major boundary
consists of elements absent from the FB2 model itself (true HTML lists, MathML, fixed layout,
complex SVG DOM).

### EPUB 2 / EPUB 3.3

EPUB is the broadest of the four formats: a package may contain XHTML, CSS, SVG,
Presentation MathML, embedded fonts, reflow/fixed-layout metadata, nav/NCX, page-list/landmarks,
non-linear spine resources, fallbacks, media overlays, and scripts. Consequently, "HTML text renders"
represents merely a baseline tier of EPUB support.

The reflow pipeline currently preserves package/spine/fallbacks, nav and NCX with multiple
fragment targets within a single XHTML document, Unicode IRI fragments, embedded/obfuscated
fonts, recursive imports, substantial CSS cascade, tables, lists, ruby, preformatted text,
SVG/images, semantic links, and independently opened `linear="no"` documents. EPUB 2 DTBook
traverses the same package pipeline, and Presentation MathML features a readable native fallback.
Nested reflowable HTML containers now preserve bounded box geometry, basic float/clear, and
cell geometry without shifting to fixed layout. The primary true reader gaps remain: fixed layout
and browser-level MathML fidelity, vertical writing, remaining browser-level CSS painting/box model,
SVG as a searchable/accessibility tree, scripts/media overlays, and DPUB-ARIA semantics.

### MOBI6/7

Legacy MOBI is a PDB/PalmDOC container containing OEB-like HTML, record/filepos links,
EXTH metadata, older presentational markup, and a significantly smaller CSS/HTML footprint.
It cannot be expected to support EPUB 3 MathML, modern SVG DOM, grid, or fixed-layout semantics
that the format does not reliably express.

The current pipeline independently decodes PalmDOC/HUFF-CDIC text, charset/EXTH,
filepos/guide/NCX-like navigation, legacy `<font>`, `align`, tables, images, page breaks, links,
and narrowly recognized note markers. Its priority is not to emulate a browser, but to avoid losing
legacy authored markup and to safely degrade damaged records. Files with `.mobi`, `.prc`, and older
`.azw` extensions are handled by a single legacy engine regardless of extension.

### KF8 / MOBI8 / AZW3

KF8 carries HTML5/CSS and resources inside PDB/KF8 structures (FDST flows, skeleton/fragment
reconstruction, INDX/NCX). This represents an independent capability column, even when KF8
is packaged as a secondary section within a combo `.mobi` file. Pure `.azw3` and combo KF8
must yield identical reflow results.

The current pipeline reconstructs KF8 markup, resources/fonts/navigation, distinguishes
`amzn-kf8` from `amzn-mobi`, recursively and iteratively expands `kindle:flow` `@import`,
preserves repeated source ordering, and caps adversarial import graphs. Amazon's official
documentation explicitly designates `::before`, `::after`, `::first-letter`, and structural
pseudo-classes as unsupported by KF8; their absence cannot be classified as a normative reader gap.
The real major gaps are KF8 fixed-layout/panels/region magnification/text popups and full supported
Amazon CSS painting. KFX remains a separate, proprietary fifth container format and is outside
the scope of this implementation.

## Remaining Work by Priority

### P0 — Dedicated Architecture Rather Than Another Parser `when`

1. **Publisher-layout surface.** Introduce a typed surface selector: `NativeReflow` for prose
   and an isolated package-aware layout for EPUB fixed-layout, full Presentation MathML,
   vertical writing, full SVG/HTML, and KF8 fixed panels. It must access local resources via
   a restricted origin, forbid arbitrary network/file access, retain typed internal navigation,
   and avoid conflating coordinates with reflow progress.
2. **Dual progress spaces.** The primary reading order is already decoupled from EPUB linked
   documents. Publisher-layout must uphold this invariant and provide stable anchors without
   fictitious chapter indices.

Rich notes and publication-wide resource budgets from previous P0 items have been implemented
and verified with automated regressions. They are no longer listed as future architecture.

### Publisher-Layout: Adopted Architectural Plan

Fixed-layout cannot simply be added as another `ContentElement`: a single EPUB spine may mix
reflowable and pre-paginated items, repeat a single manifest resource across multiple `itemref`
entries, specify overrides on each occurrence, and include `linear="no"` targets. The plan therefore
introduces dedicated `PublisherPublication`/`PublisherSpineItem` models and typed
`ReaderLocation.Reflow`/`ReaderLocation.Publisher` locations, maintaining a single shared logical
reading order. Progress is calculated by linear occurrence, while synthetic blanks, spread slots,
panels, and linked documents do not distort it.

Implementation is divided into three verifiable slices:

1. EPUB fixed-layout foundation: OPF `rendition:*`, viewport/SVG `viewBox`, stable occurrence IDs,
   local resource session, single fixed page rendering, and typed navigation/progress/back.
2. Spreads and mixed spine: LTR/RTL planner, placement/blank/true-spread handling, rotation restoration,
   reflow↔fixed transitions, publisher search/bookmarks, and vertical writing on the browser surface.
3. KF8 fixed-layout: verified retained metadata first, followed by typed Kindle magnification/text
   popup/panel regions and virtual-panel fallbacks.

The local publisher surface must use a dedicated HTTPS app-assets origin per publication; JavaScript,
network access, file/content schemes, forms, frames, and media are disabled. WebView state does not
leak into `BookContent`/disk cache, and runtime sessions are closed by the ViewModel. These invariants
require instrumentation testing on API 26 and modern Android before enabling the surface in production.

### P1 — Fidelity of Standard Reflowable Books

- Border radius/outline/shadow, border-collapse, and more comprehensive safe box painting on top
  of already implemented basic borders/backgrounds in Compose;
- White-space/word-break/overflow-wrap, letter/word spacing, text-transform, visibility, and supported
  font-feature/variant properties;
- More comprehensive float formatting context: multiple concurrent floats, multi-paragraph text wrapping,
  min/max-width, and overflow/object-fit;
- Richer object/picture/srcset/resource fallbacks, URL query/base semantics, and external references
  inside SVG;
- EPUB page-list, landmarks/guide/start-reading, multiple rootfiles/renditions;
- Table vertical-align/colgroup/nested blocks and a more complete caption model;
- Accessibility roles/labels/DPUB-ARIA, SVG/MathML text inclusion in search and selection;
- Corpus of physical books: distinct MOBI6 `.mobi` and KF8 `.azw3` files, avoiding conclusions drawn
  from a single combo-file.

### P2 — Tolerant / Rare Extensions

- Corrupted CSS selectors/declarations, duplicate IDs, and unusual character encodings;
- Vendor prefixes, converter-specific note conventions, and obsolete HTML elements;
- WebP/BMP/AVIF and animated GIF behavior across diverse Android image decoders;
- Extreme nested lists/sections/tables/import graphs with graceful truncation and diagnostics
  in place of crashes or ANRs.

## Executable Evidence and Test Boundaries

`FormatParityTest` synthesizes four temporary equivalent books — FB2, EPUB, MOBI6, and KF8 — and
compares normalized model snapshots across five identical cases:

1. Rich heading + bold/italic runs;
2. Regular paragraph with END alignment;
3. Regular cross-reference versus genuine footnote;
4. Inline and block PNG with identical decoded bytes;
5. Table header/grid/rowspan/colspan/cell alignment.

This gate deliberately does not pretend to be a pixel test or claim false parity for features
absent from FB2. Dedicated suites verify CSS cascade, HTML5 inline semantics, FB2 poems/styles/languages,
EPUB package/navigation, MOBI6 and KF8 independently, pagination metrics, table grids, and link routing.
Separate tests verify EPUB 2 DTBook package/NCX, MathML linear fallback, rich notes with tables and
images, API 26 import pipelines, and adversarial resource budgets.

The persistent FrogCompare corpus now materializes 132 numbered test cases into distinct `.fb2`,
`.epub`, legacy `.mobi`, and pure `.azw3` books, alongside a separate EPUB 2 DTBook fixture.
Source definitions and deterministic SHA-256 validation live in
`app/src/test/java/com/example/frogreader/testbooks/`; regeneration is triggered explicitly via
`-PgenerateTestBooks=true`. For EPUB, relevant reflow test cases from the official W3C EPUB test
suite should continue to be incorporated incrementally. Synthetic fixtures verify exact invariants;
real books verify our understanding of the ecosystem.

The publisher-layout gate is split across architectural layers: `PublisherLayoutCssTest` and
`PublisherBoxStyleResolverTest` verify cascade and box values; `PublisherLayoutMapperTest` and
`PublisherLayoutSpanTest` verify nested half-open ranges, slicing/rebasing, and clear markers;
`ReaderPublisherLayoutTest` verifies safe float planning; `PublisherBoxLayoutGeometryTest` and
`PaginationPublisherPolicyTest` verify shared measure/render geometry and pagination policy;
`PaginationCacheTest` verifies full serialization of the new model. `MathBookPublisherRegressionTest`
adds conditional verification of the original EPUB when available locally.

## Mandatory Manual Device-Gate (Pixel 9a)

Phone-level automated UI tests are not executed in CI. The owner manually verifies:

1. H1–H6: six visually distinct sizes at small, medium, and maximum base font settings.
2. Publisher `font-size:1em` genuinely overrides default semantic heading sizes.
3. Tables: inherited size/family/italic/bold/line-height, wide columns, rowspan, and repeated headers
   following a page break.
4. EPUB `linear="no"`: opening from text and Contents, linked→main, linked→linked, Back navigation
   without progress jumps or false completion triggers.
5. Animated GIF in FB2 and EPUB; static fallback when decoder fails.
6. Mixed SVG shapes + text + raster images, standalone SVG pages, and missing-resource alt fallbacks.
7. Embedded regular/bold/italic fonts on the API 26 compatibility pipeline.
8. Cross-references do not open as notes; true notes do not navigate as TOC jumps.
9. Identical complex tables, headings, and images displayed side-by-side across all four formats.
10. Rich notes longer than 700 characters: heading, list/poem, table, block and inline image,
    note→note navigation, and return to main text.
11. EPUB 2 DTBook: NCX jumping to fragment, level1–level6, nested lists, poems, SVG, and tables;
    content does not drop out due to media type.
12. MathML: inline scripts/fractions/roots and display matrices/limits remain readable, selectable,
    and do not disrupt pagination; comparison does not claim pixel-identical browser MathML.
13. Author colors in body text, inline spans, headings/drop caps, quotes, tables/cells, and rich notes:
    exact colors appear when Publisher formatting is on, disappear completely when turned off,
    and isolated colors do not become illegible on OLED or sepia themes.
14. Smart return: internal link/Contents/search/bookmark/quote/progress scrubbing and distant
    page/scroll jumps; precise return in both modes, nested notes, and linked documents, no phantom
    history following cancel/no-op, right-hand button does not obscure the bottom bar and dismisses
    automatically.
15. Arabic/Hebrew: mixed RTL/LTR with numbers and parentheses, `dir=auto`, `bdi`/`bdo`, headings/lists/table,
    logical margins, page order/tap zones, selection, search/copy in both paged and scroll modes.
16. *The Math Book*, "Numerals take their places": green illustration and heading preserve authored
    width; `IN CONTEXT` is a single gray bordered panel with white separators rather than separate
    gray bands behind each paragraph; `KEY CIVILIZATION`, `FIELD`, `BEFORE`, `AFTER` align to the shared
    left margin.
17. *The Math Book*, `Cuneiform`: diagram with caption on the left and text on the right remain a
    single light-green bordered container; in narrow viewports or large font sizes, stacked fallback
    retains image, caption, and text.
18. *The Math Book*, `Parabolas`: mirror diagram occupies approximately 90% width, while pink
    `Practical applications` retains heading rule, background, frame, and safe image/text wrapping.
19. Both actual HTML tables in the book: cell padding/background/borders are visible, columns do not
    overflow the viewport, and page breaks do not clip bottom insets/borders. Raster 80% base-60 diagram
    is centered in its column rather than pinned to the left edge.
20. Items 16–19 repeat in paged and scroll modes, under small and large font settings. Publisher
    formatting enabled preserves hierarchy; disabled removes author boxes/colors without losing
    text, images, links, or progress anchors.

Because this book contains no embedded font, the device-gate must not demand pixel-identical typefaces
matching reference reading applications. Authored font size/weight/style and layout hierarchy are verified,
while selection of available system fonts remains the policy of reader typography.

Until this verification passes, status remains "code and automated gates passing", not "visually superior
engine on the market proven".

## Normative and Reference Sources

- [EPUB 3.3 — W3C Recommendation](https://www.w3.org/TR/epub-33/)
- [EPUB Reading Systems 3.3](https://www.w3.org/TR/epub-rs-33/)
- [W3C EPUB tests](https://w3c.github.io/epub-tests/index.html)
- [EPUBCheck test suite, including EPUB 2 DTBook](https://www.w3.org/publishing/epubcheck/docs/test-suite/)
- [CSS Color Module Level 4](https://www.w3.org/TR/css-color-4/)
- [Android Developers: local content in WebView](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)
- [AndroidX WebKit releases](https://developer.android.com/jetpack/androidx/releases/webkit)
- [FictionBook 2.x XSD](https://github.com/gribuser/fb2/blob/master/FictionBook.xsd)
- [Amazon Kindle Publishing Guidelines](https://kdp.amazon.com/en_US/help/topic/GU72M65VRFPH43L6)
- [HTML/CSS supported in Kindle Format 8](https://kdp.amazon.com/en_US/help/topic/GG5R7N649LECKP7U)
- [Amazon Kindle media queries (`amzn-mobi` / `amzn-kf8`)](https://kdp.amazon.com/en_US/help/topic/GR4KL488MXKPZ5BK)
- [Library of Congress: Mobipocket format description](https://www.loc.gov/preservation/digital/formats/fdd/fdd000472.shtml)
- [MobileRead MOBI container reference](https://wiki.mobileread.com/wiki/MOBI)

EPUB and FB2 have open normative schemas and specifications. Binary MOBI and KF8 container formats
are not fully or publicly standardized by Amazon in modern form, so low-level record parsing is
verified against independent implementations and real files; publisher-facing HTML/CSS claims are
derived directly from Amazon documentation.
